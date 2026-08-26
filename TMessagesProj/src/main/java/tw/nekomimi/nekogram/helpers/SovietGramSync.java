package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.remote.ApiServersHelper;

/**
 * Pushes the local "fake identity" (premium flag, Fragment phone/usernames, fake Stars/TON balance
 * and the custom-profile look) up to the SovietGram sync backend so it becomes visible to OTHER
 * SovietGram users. Without this the features are purely local: the peer on the other end sees
 * nothing.
 *
 * <p>Every setting that feeds a synced field routes its change here through
 * {@link #scheduleProfilePush()}. The push is debounced, so a burst of edits (dragging a colour
 * slider, toggling several rows in a row) collapses into one {@code PUT /v1/profile}, and it is
 * deduplicated, so an edit that leaves the resulting body unchanged (e.g. picking a new banner
 * picture, whose path is not synced) costs nothing.
 *
 * <p>The body is built to match the server's strict schema exactly (see {@code putProfileSchema} /
 * {@code validation.ts}): unknown top-level keys are rejected, so only the whitelisted fields are
 * ever sent, and each value is pre-validated against the same shape the server enforces. Optional
 * fields are omitted rather than sent empty when their feature is off, which the server reads as
 * {@code null} and clears — so switching a feature off unshares it.
 */
public final class SovietGramSync {

    /** Long enough to swallow a slider drag or a multi-row toggle, short enough to feel immediate. */
    private static final long PROFILE_PUSH_DEBOUNCE_MS = 1500L;

    /**
     * A push that failed is otherwise lost until the user next edits something — the peer keeps seeing
     * the previous state indefinitely, which looks exactly like the sync being broken. So a failure is
     * retried a few times, spaced far enough apart to ride out a lost connection or a server restart.
     */
    private static final long PROFILE_PUSH_RETRY_MS = 12 * 1000L;
    private static final int PROFILE_PUSH_MAX_RETRIES = 3;

    // Mirrors of the server's field validators (validation.ts). Kept in lockstep with the API: a
    // value that would fail server-side is dropped here instead of being sent and 400'd.
    private static final Pattern FRAGMENT_PHONE_RE = Pattern.compile("^\\+?\\d{1,19}$");
    private static final Pattern FRAGMENT_USERNAME_RE = Pattern.compile("^[a-zA-Z0-9_]{4,32}$");
    private static final Pattern NUMERIC_STRING_RE = Pattern.compile("^\\d+(\\.\\d+)?$");
    private static final int MAX_FRAGMENT_USERNAMES = 10;
    private static final int MAX_NUMERIC_LENGTH = 20;

    /**
     * Serialized body of the last push the server accepted, per telegram id. Keyed by id rather than
     * by account slot because slots are reused when an account is removed, and because the dedup is
     * only meaningful against what that specific identity last stored. One shared value would let the
     * first account's successful push suppress every other account's first push.
     */
    private static final Map<Long, String> lastPushedBody = new ConcurrentHashMap<>();

    private static final Runnable profilePushRunnable = SovietGramSync::pushProfileNow;

    /** Retries left for the current round of failures, and whether one is already on the clock. */
    private static final AtomicInteger retriesLeft = new AtomicInteger(PROFILE_PUSH_MAX_RETRIES);
    private static final AtomicBoolean retryPending = new AtomicBoolean();

    private SovietGramSync() {
    }

    /**
     * Coalesced entry point: call it from anywhere a synced setting changes. Safe on any thread and
     * cheap to call repeatedly — only the last call within the debounce window actually fires.
     */
    public static void scheduleProfilePush() {
        // The scoped settings live in global config items, one account's worth at a time, and every
        // edit to any of them lands here. Recording them now is what makes the edit durable against a
        // kill before the next account switch — and, more importantly, what stops the next switch from
        // saving them under whichever account happens to be live by then.
        SovietGramAccountScope.saveLive();
        // A fresh edit is a fresh chance: whatever failed before is superseded by this body anyway.
        retriesLeft.set(PROFILE_PUSH_MAX_RETRIES);
        retryPending.set(false);
        AndroidUtilities.cancelRunOnUIThread(profilePushRunnable);
        AndroidUtilities.runOnUIThread(profilePushRunnable, PROFILE_PUSH_DEBOUNCE_MS);
    }

    /**
     * Builds each account's profile body and PUTs it, signed, for every logged-in account that holds
     * a token. The fake identity belongs to one account, not to the install, so the bodies differ:
     * only the account whose settings are currently live reads them out of the config items, and the
     * rest are read out of their stored snapshots ({@link SovietGramAccountScope}). An account whose
     * body is byte-identical to its last accepted one is skipped. The network calls themselves are
     * dispatched off the UI thread by {@link SovietGramApiClient}.
     */
    public static void pushProfileNow() {
        retryPending.set(false);
        final List<Integer> accounts = SovietGramTokenStore.accountsWithToken();
        if (accounts.isEmpty() || TextUtils.isEmpty(ApiServersHelper.baseUrl())) {
            // No token yet, or no server selected. The next change re-schedules, and the auth handshake
            // fires one push of its own the moment a token lands, so nothing is lost by returning here.
            return;
        }
        // A look whose picture has no fetchable source yet is the one way the blob below can be
        // complete and still show a peer nothing, so this is the moment to fix it: the upload runs in
        // the background and schedules its own push once the descriptor exists.
        CustomProfileMedia.ensurePublished();
        for (int account : accounts) {
            final long ownId = SovietGramTokenStore.ownId(account);
            if (ownId <= 0) {
                continue;
            }            final JSONObject body = buildProfileBody(account);
            if (body == null) {
                continue;
            }
            final String serialized = body.toString();
            if (serialized.equals(lastPushedBody.get(ownId))) {
                continue;
            }
            SovietGramApiClient.putSigned(account, "/v1/profile", body, (response, error) -> {
                if (error == null) {
                    lastPushedBody.put(ownId, serialized);
                } else {
                    FileLog.e("SovietGramSync: profile push failed for " + ownId + ": " + error);
                    scheduleRetry();
                }
            });
        }
    }

    /**
     * Puts one more attempt on the clock after a failure. At most one is ever pending however many
     * accounts failed in the same round, and the whole round costs one of the three retries; a later
     * edit resets the count. Accounts whose push did succeed are skipped by the dedup, so a retry only
     * re-sends what actually failed.
     */
    private static void scheduleRetry() {
        if (retriesLeft.get() <= 0 || !retryPending.compareAndSet(false, true)) {
            return;
        }
        retriesLeft.decrementAndGet();
        AndroidUtilities.cancelRunOnUIThread(profilePushRunnable);
        AndroidUtilities.runOnUIThread(profilePushRunnable, PROFILE_PUSH_RETRY_MS);
    }

    /**
     * Assembles {@code account}'s {@code PUT /v1/profile} body from its own fake-feature settings.
     * Optional fields are left out entirely when their toggle is off (server clears them);
     * {@code fragment_usernames} and {@code custom_profile} are always present so turning a feature
     * off actively unshares it.
     *
     * <p>Every value is read through {@link SovietGramAccountScope}, never straight off the config
     * item: only one account's settings are live at a time, and reading the live ones for all of them
     * is exactly how one account's fake number ended up published under every account's identity.
     */
    @Nullable
    private static JSONObject buildProfileBody(int account) {
        try {
            final JSONObject body = new JSONObject();

            // fake_premium: always sent, so toggling it off immediately drops the badge for others.
            body.put("fake_premium", SovietGramAccountScope.bool(account, NekoConfig.localPremium));

            // fragment_phone / fragment_usernames: only while the Fragment feature is on. Each value is
            // filtered to the exact shape the server accepts, and the username list is capped at 10.
            final JSONArray usernames = new JSONArray();
            if (SovietGramAccountScope.bool(account, NekoConfig.serverFragment)) {
                final String phone = ServerFragmentHelper.phone(account);
                if (!TextUtils.isEmpty(phone) && FRAGMENT_PHONE_RE.matcher(phone).matches()) {
                    body.put("fragment_phone", phone);
                }
                for (String name : ServerFragmentHelper.usernames(account)) {
                    if (usernames.length() >= MAX_FRAGMENT_USERNAMES) {
                        break;
                    }
                    if (name != null && FRAGMENT_USERNAME_RE.matcher(name).matches()) {
                        usernames.put(name);
                    }
                }
            }
            // Always present: an empty array clears the server's list when the feature is off.
            body.put("fragment_usernames", usernames);

            // fake_stars / fake_ton: only while their toggle is on and the amount is a valid number.
            if (SovietGramAccountScope.bool(account, NekoConfig.fakeStars)) {
                final String stars = normalizeNumeric(SovietGramAccountScope.str(account, NekoConfig.fakeStarsAmount));
                if (isNumericString(stars)) {
                    body.put("fake_stars", stars);
                }
            }
            if (SovietGramAccountScope.bool(account, NekoConfig.serverTon)) {
                final String ton = normalizeNumeric(SovietGramAccountScope.str(account, NekoConfig.serverTonAmount));
                if (isNumericString(ton)) {
                    body.put("fake_ton", ton);
                }
            }

            // custom_profile: the styling blob when enabled, or {} to clear it. Always present so
            // disabling the feature removes the look for everyone else.
            body.put("custom_profile", SovietGramAccountScope.bool(account, NekoConfig.customProfileEnabled)
                    ? CustomProfileHelper.exportProfileJson(account)
                    : new JSONObject());

            return body;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /** True when {@code value} matches the server's numericString rule ({@code ^\d+(\.\d+)?$}, ≤20). */
    private static boolean isNumericString(@Nullable String value) {
        return value != null && value.length() <= MAX_NUMERIC_LENGTH && NUMERIC_STRING_RE.matcher(value).matches();
    }

    /**
     * The TON amount field accepts a trailing "." (typing "100." is valid input the sanitizer keeps),
     * but the server's numericString regex requires a digit after the dot. Drop a lone trailing dot so
     * a value like "100." is sent as "100" instead of failing validation and silently clearing the
     * balance. Stars are integer-only and unaffected.
     */
    @Nullable
    private static String normalizeNumeric(@Nullable String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.endsWith(".") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }
}
