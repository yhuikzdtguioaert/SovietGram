package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The pull side of the profile sync: the mirror image of {@link SovietGramSync}, which pushes the
 * own fake identity up. This fetches OTHER SovietGram users' profiles from the backend and injects
 * their fabricated identity — fake premium and Fragment phone/collectible usernames — onto the
 * peer's {@link TLRPC.User} object, so what one SovietGram user configured about themselves is what
 * everyone else sees.
 *
 * <p>A peer's Custom Profile look rides along in the same answer, but it is not part of a
 * {@link TLRPC.User} and so cannot be injected into one. It is kept in the cache and read straight
 * off it by {@link CustomProfileHelper} while their profile screen draws; see
 * {@link #remoteCustomProfile}.
 *
 * <p>Three pieces make that work:
 * <ul>
 *   <li>{@link #requestProfile(int, long)} — a lazy, TTL-cached {@code GET /v1/profile/:id}. It is
 *       triggered from the surfaces that actually render a peer (their profile screen, a chat with
 *       them), never from the hot {@code putUser} path, so it costs one network round-trip per peer
 *       per TTL window and nothing when the answer is already cached.</li>
 *   <li>{@link #sighted(int, long)} — the same, for a peer who is merely on screen: a sender in a
 *       group, a row in a list. Called from binding paths, so it collects ids for a fraction of a
 *       second and reads them all with one {@code GET /v1/profiles}. This is what makes a fake
 *       identity visible in every chat rather than only where a screen asks about one user.</li>
 *   <li>{@link #watch(int, long)} — while a peer's profile is the screen in front of the user, it is
 *       re-read every {@link #WATCH_INTERVAL_MS}. There is no push channel from the backend, so this
 *       is what decides how quickly a change made on the owner's device becomes visible on somebody
 *       else's; the poll runs only while a profile is actually open, and a re-read that comes back
 *       identical costs nothing beyond the request.</li>
 *   <li>{@link #applyRemote(TLRPC.User)} — called from {@code MessagesController.putUser} for every
 *       user object the app ingests. It reads the cache and rewrites the peer object in memory,
 *       exactly the way {@link ServerFragmentHelper} rewrites the own account. Because it runs on
 *       every ingest it is self-healing: Telegram's own updates keep overwriting the object, and we
 *       keep re-applying on top.</li>
 * </ul>
 *
 * <p>The injection is deliberately additive and non-destructive: premium is only ever set, never
 * cleared, so a genuinely-premium peer is never downgraded, and collectible usernames are added
 * alongside whatever the peer really has. Nothing here is persisted — the cache is in-memory and
 * the rewritten user object is re-derived from Telegram's data plus the cache on every ingest.
 */
public final class SovietGramProfileSync {

    /**
     * How long a cached answer is served without asking again, for a passive sighting of the peer —
     * opening a chat with them, for instance. Short, because the whole point of the sync is that a
     * change one user makes shows up for the others: with a long window a peer who had already been
     * looked at once kept their old look for minutes.
     */
    private static final long PROFILE_TTL_MS = 60 * 1000L;

    /**
     * The same window for an answer that carried nothing at all — no fake, no look. Most peers are not
     * SovietGram users and never will be, and a scrolled group chat sights dozens of them; re-asking
     * about each one every minute would spend the read budget on people with nothing to show. They are
     * still re-read, just far less often, so somebody who sets a fake up mid-conversation appears
     * within this window rather than never.
     */
    private static final long EMPTY_TTL_MS = 10 * 60 * 1000L;

    /**
     * The floor under a forced refresh. Opening a profile, and every tick while it stays open, asks
     * again regardless of the TTL — this is what stops that from turning into a request per redraw.
     */
    private static final long MIN_REFETCH_MS = 4 * 1000L;

    /**
     * How often the profile currently on screen is re-read. This is the number that decides how long
     * after a change on one device it appears on another, so it is deliberately short; it costs one
     * small GET per interval and only while somebody is actually looking at that profile.
     */
    private static final long WATCH_INTERVAL_MS = 10 * 1000L;

    /** TL_user premium flag; kept set alongside the boolean so the value survives serialization. */
    private static final int USER_FLAG_PREMIUM = 1 << 28;
    /** TL_user phone-present flag (bit 4), same one {@link ServerFragmentHelper} sets for the own number. */
    private static final int USER_FLAG_PHONE = 1 << 4;
    /** flags2 bit 0 marks the usernames vector as present. */
    private static final int USER_FLAG2_USERNAMES = 1 << 0;

    private static final int MAX_FRAGMENT_USERNAMES = 10;

    private static final Map<Long, RemoteProfile> cache = new ConcurrentHashMap<>();
    private static final Set<Long> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private SovietGramProfileSync() {
    }

    /**
     * Fetches {@code userId}'s profile if the cached answer is older than {@link #PROFILE_TTL_MS} and
     * no fetch is already running for it. Safe and cheap to call whenever a peer comes into view: the
     * own account, an unresolved id, an in-flight id and a fresh cache entry all short-circuit before
     * any network work.
     *
     * <p>{@code account} is the account whose screen is asking. It is only a preference: reading a
     * peer's profile returns the same answer whoever asks, so if that account has no token yet any
     * other account's is used instead — otherwise a freshly added account would show every peer as
     * plain until its own handshake finished.
     */
    public static void requestProfile(int account, long userId) {
        fetch(account, userId, false);
    }

    /**
     * The same, but ignoring the TTL: the caller has a reason to believe the answer may have changed
     * (the user just opened this profile, or it is on screen and being watched). Still bounded by
     * {@link #MIN_REFETCH_MS} and by the in-flight set.
     */
    public static void refreshProfile(int account, long userId) {
        fetch(account, userId, true);
    }

    private static void fetch(int account, long userId, boolean force) {
        if (userId <= 0 || isOwnId(userId)) {
            return;
        }
        final int reader = readerAccount(account);
        if (reader < 0) {
            return;
        }
        if (!stale(cache.get(userId), force)) {
            return;
        }
        if (!inFlight.add(userId)) {
            return; // a fetch for this id is already running
        }
        SovietGramApiClient.get(reader, "/v1/profile/" + userId, (body, error) -> {
            inFlight.remove(userId);
            if (error != null || body == null) {
                // Leave the cache untouched so the next open retries; the trigger is user-driven, so
                // there is no risk of hammering the server on a transient failure.
                if (error != null) {
                    FileLog.e("SovietGramProfileSync: profile pull failed for " + userId + ": " + error);
                }
                return;
            }
            store(userId, body);
        });
    }

    /**
     * Which account's token to read a peer's profile with. Reading returns the same answer whoever
     * asks, so the caller's own account is only a preference: any other account's token is used when it
     * has none yet, otherwise a freshly added account would show every peer as plain until its own
     * handshake finished. {@code -1} when no account can talk to the API at all.
     */
    private static int readerAccount(int account) {
        final int reader = SovietGramTokenStore.hasToken(account) ? account : SovietGramTokenStore.anyAccountWithToken();
        return reader >= 0 && SovietGramApiClient.isReady(reader) ? reader : -1;
    }

    /**
     * Whether a cached entry is old enough to ask again. A forced read is bounded only by
     * {@link #MIN_REFETCH_MS}; an ordinary one by {@link #PROFILE_TTL_MS}, or by the much longer
     * {@link #EMPTY_TTL_MS} when the last answer said the peer has nothing to show.
     */
    private static boolean stale(@Nullable RemoteProfile cached, boolean force) {
        if (cached == null) {
            return true;
        }
        final long window = force
                ? MIN_REFETCH_MS
                : (cached.hasInjectable() || cached.hasLook() ? PROFILE_TTL_MS : EMPTY_TTL_MS);
        return System.currentTimeMillis() - cached.fetchedAt >= window;
    }

    /**
     * Files one answered profile and, when it says something new, pushes it into the UI. Shared by the
     * single read and the batch: the two routes answer the same {@code ProfileDto}.
     */
    private static void store(long userId, JSONObject body) {
        final RemoteProfile profile = parse(userId, body);
        final RemoteProfile previous = cache.get(userId);
        if (previous != null && profile.raw.equals(previous.raw)) {
            // Nothing new. Keep the entry that is already there rather than replacing it with an
            // equal one: the watch re-reads the same profile every few seconds, and
            // {@link CustomProfileHelper#refreshDrawingLook} tells one look from the next by object
            // identity — handed a fresh-but-equal blob it would treat every later user update as a
            // look change and restyle the whole screen for nothing.
            previous.fetchedAt = profile.fetchedAt;
            return;
        }
        cache.put(userId, profile);
        // Only churn the UI when the answer actually says something new — which, past the guard
        // above, it does. The previous state counts too, so a peer who has just switched a fake OFF
        // loses it here rather than at the next unrelated redraw.
        if (profile.hasInjectable() || profile.hasLook()
                || previous != null && (previous.hasInjectable() || previous.hasLook())) {
            reinject(userId);
        }
    }

    // ===== peers coming into view =====

    /**
     * How long sightings are collected before they are read in one request. Long enough that flinging
     * through a group chat produces one batch rather than one per row, short enough that the badge
     * appears while the message is still on screen.
     */
    private static final long SIGHTING_DRAIN_MS = 400L;

    /** The server's own cap on {@code GET /v1/profiles}; a fuller batch is split across drains. */
    private static final int MAX_BATCH_IDS = 100;

    /**
     * Peers sighted on screen and not yet read. A drain empties it, so it never grows past what one
     * screenful plus one drain interval can add.
     */
    private static final Set<Long> sighted = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final AtomicBoolean drainScheduled = new AtomicBoolean();
    /** The account that did the sighting, i.e. whose token to prefer for the batch. */
    private static volatile int sightingAccount = -1;

    private static final Runnable drainRunnable = SovietGramProfileSync::drainSightings;

    /**
     * A peer has just come into view — their message is being bound, their row is being drawn. Cheap
     * enough for exactly those paths: the common case is one map lookup and a return.
     *
     * <p>This is what makes a peer's fake identity visible <em>everywhere</em> rather than only where a
     * screen happens to ask about one specific user. A group chat renders dozens of senders and asked
     * about none of them, so a SovietGram user with fake premium showed the star in a direct chat and
     * on their profile — the two screens that pull explicitly — and nowhere else. Sightings are
     * collected and read in one {@code GET /v1/profiles}, so a screenful of strangers costs a single
     * request instead of one per sender.
     */
    public static void sighted(int account, long userId) {
        if (userId <= 0 || !stale(cache.get(userId), false) || isOwnId(userId)) {
            return;
        }
        sightingAccount = account;
        if (!sighted.add(userId)) {
            return;
        }
        if (drainScheduled.compareAndSet(false, true)) {
            AndroidUtilities.runOnUIThread(drainRunnable, SIGHTING_DRAIN_MS);
        }
    }

    private static void drainSightings() {
        drainScheduled.set(false);
        final int reader = readerAccount(sightingAccount);
        if (reader < 0) {
            // Nothing can be read right now. Drop the sightings rather than keeping them: the surfaces
            // that produced them re-sight on the next bind, and a handshake that finishes later has no
            // use for a list of who was on screen minutes ago.
            sighted.clear();
            return;
        }
        final List<Long> batch = new ArrayList<>();
        final StringBuilder ids = new StringBuilder();
        final Iterator<Long> it = sighted.iterator();
        while (it.hasNext() && batch.size() < MAX_BATCH_IDS) {
            final Long userId = it.next();
            it.remove();
            if (userId == null || !stale(cache.get(userId), false) || !inFlight.add(userId)) {
                continue;
            }
            batch.add(userId);
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(userId.longValue());
        }
        if (!sighted.isEmpty() && drainScheduled.compareAndSet(false, true)) {
            // More than one batch worth was sighted at once; the rest goes out on the next tick.
            AndroidUtilities.runOnUIThread(drainRunnable, SIGHTING_DRAIN_MS);
        }
        if (batch.isEmpty()) {
            return;
        }
        SovietGramApiClient.get(reader, "/v1/profiles?ids=" + ids, (body, error) -> {
            for (Long userId : batch) {
                inFlight.remove(userId);
            }
            if (error != null || body == null) {
                if (error != null) {
                    FileLog.e("SovietGramProfileSync: batch pull of " + batch.size() + " failed: " + error);
                }
                return;
            }
            final JSONArray profiles = body.optJSONArray("profiles");
            if (profiles == null) {
                return;
            }
            for (int i = 0; i < profiles.length(); i++) {
                final JSONObject entry = profiles.optJSONObject(i);
                if (entry == null) {
                    continue;
                }
                // The answer keeps the requested order, but the id is read out of the entry rather than
                // zipped by index: one shifted entry would otherwise file a peer's fakes under somebody
                // else's identity.
                final long userId = parseId(entry.optString("telegram_id", ""));
                if (userId > 0 && !isOwnId(userId)) {
                    store(userId, entry);
                }
            }
        });
    }

    private static long parseId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (Throwable e) {
            return 0;
        }
    }


    // ===== the profile on screen =====

    /**
     * The peer whose profile screen is open, if any, and the account it is open under. A single slot
     * rather than a set: only one profile is on screen at a time, and the screen that opens takes the
     * slot over from whichever one it covered.
     */
    private static long watchedUserId;
    private static int watchedAccount = -1;

    private static final Runnable watchRunnable = SovietGramProfileSync::watchTick;

    /**
     * Starts re-reading {@code userId}'s profile every {@link #WATCH_INTERVAL_MS} and reads it once
     * immediately. Called as a peer's profile screen resumes: while somebody is looking at a profile,
     * a change made on the owner's device should appear within seconds rather than whenever the viewer
     * next reopens the screen. Must be called on the main thread.
     */
    public static void watch(int account, long userId) {
        if (userId <= 0 || isOwnId(userId)) {
            return;
        }
        watchedAccount = account;
        watchedUserId = userId;
        refreshProfile(account, userId);
        AndroidUtilities.cancelRunOnUIThread(watchRunnable);
        AndroidUtilities.runOnUIThread(watchRunnable, WATCH_INTERVAL_MS);
    }

    /**
     * Stops the watch, if {@code userId} is still the watched one — a screen that has already been
     * covered by another profile must not cancel that one's watch as it pauses.
     */
    public static void unwatch(long userId) {
        if (watchedUserId != userId) {
            return;
        }
        watchedUserId = 0;
        watchedAccount = -1;
        AndroidUtilities.cancelRunOnUIThread(watchRunnable);
    }

    private static void watchTick() {
        if (watchedUserId <= 0) {
            return;
        }
        refreshProfile(watchedAccount, watchedUserId);
        AndroidUtilities.runOnUIThread(watchRunnable, WATCH_INTERVAL_MS);
    }


    /**
     * Rewrites {@code user} from its cached remote profile. Called from {@code putUser} for every
     * ingested user; a no-op for the own account (handled by {@link ServerFragmentHelper} and the
     * local premium gate), for users with no cache entry, and for entries carrying no fakes.
     */
    public static void applyRemote(@Nullable TLRPC.User user) {
        if (user == null || user.self) {
            return;
        }
        final RemoteProfile profile = cache.get(user.id);
        if (profile == null || !profile.hasInjectable()) {
            return;
        }
        // Premium: set only, never clear — a real premium peer must not be downgraded by an absent fake.
        if (profile.fakePremium) {
            user.premium = true;
            user.flags |= USER_FLAG_PREMIUM;
        }
        // Fragment phone: a collectible number is drawn when the phone starts with 888, so writing the
        // configured number onto the peer is all it takes. Peers usually expose no phone, so there is
        // nothing real to lose here.
        if (!TextUtils.isEmpty(profile.fragmentPhone)) {
            user.phone = profile.fragmentPhone;
            user.flags |= USER_FLAG_PHONE;
        }
        // Fragment usernames: a username draws as collectible when editable is false. Add the fabricated
        // ones alongside whatever the peer really has, skipping any that are already present.
        if (!profile.fragmentUsernames.isEmpty()) {
            final ArrayList<TLRPC.TL_username> rebuilt =
                    user.usernames != null ? new ArrayList<>(user.usernames) : new ArrayList<>();
            for (String name : profile.fragmentUsernames) {
                if (!containsName(rebuilt, name)) {
                    rebuilt.add(collectible(name));
                }
            }
            user.usernames = rebuilt;
            if (!rebuilt.isEmpty()) {
                user.flags2 |= USER_FLAG2_USERNAMES;
            }
        }
    }

    /**
     * The cached remote Custom Profile blob for {@code userId}, or {@code null} when the peer has no
     * look, has not been pulled yet, or the pull failed. Read by {@link CustomProfileHelper} for the
     * profile screen that is currently drawing.
     */
    @Nullable
    public static JSONObject remoteCustomProfile(long userId) {
        final RemoteProfile profile = cache.get(userId);
        return profile == null ? null : profile.customProfile;
    }

    /**
     * Whether any pulled peer claims {@code username} as a Fragment collectible. Asked by
     * {@link ServerFragmentHelper} when the collectible sheet wants that name described.
     *
     * <p>The whole cache is scanned because the request carries only the name, not the peer it was
     * tapped on. That is cheap: the cache holds the peers seen this session, each with at most ten
     * names, and the question is only ever asked on a tap.
     */
    public static boolean hasFakeUsername(@Nullable String username) {
        if (TextUtils.isEmpty(username)) {
            return false;
        }
        for (RemoteProfile profile : cache.values()) {
            for (String name : profile.fragmentUsernames) {
                if (name.equalsIgnoreCase(username)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The same question for a number, which is compared as bare digits like everywhere else here. */
    public static boolean hasFakePhone(@Nullable String phone) {
        if (TextUtils.isEmpty(phone)) {
            return false;
        }
        for (RemoteProfile profile : cache.values()) {
            if (phone.equals(profile.fragmentPhone)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Re-runs the injection on the cached user object for every activated account and asks the UI to
     * redraw. Mirrors {@link ServerFragmentHelper#onSettingsChanged()}: {@code putUser(user, false,
     * true)} funnels the object back through the hook at the top of {@code putUser}, where
     * {@link #applyRemote} rewrites it, and the two notifications refresh anything showing it. For a
     * peer carrying only a look the putUser round-trip changes nothing and the notifications are the
     * whole point — they are what makes an open profile screen pick the look up.
     */
    private static void reinject(long userId) {
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (!UserConfig.getInstance(account).isClientActivated()) {
                    continue;
                }
                final MessagesController controller = MessagesController.getInstance(account);
                final TLRPC.User user = controller.getUser(userId);
                if (user == null) {
                    continue;
                }
                controller.putUser(user, false, true);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.mainUserInfoChanged);
            }
        });
    }

    private static RemoteProfile parse(long userId, JSONObject body) {
        final RemoteProfile profile = new RemoteProfile();
        profile.fetchedAt = System.currentTimeMillis();
        // The whole answer, verbatim, purely to tell one poll's result from the next one's. Comparing
        // the parsed fields instead would mean keeping an equals() in step with every field added.
        profile.raw = body.toString();
        profile.fakePremium = body.optBoolean("fake_premium", false);
        profile.fragmentPhone = digitsOrNull(optStringOrNull(body, "fragment_phone"));
        final JSONArray names = body.optJSONArray("fragment_usernames");
        if (names != null) {
            for (int i = 0; i < names.length() && profile.fragmentUsernames.size() < MAX_FRAGMENT_USERNAMES; i++) {
                final String name = clean(names.optString(i, null));
                if (!name.isEmpty()) {
                    profile.fragmentUsernames.add(name);
                }
            }
        }
        // Stored for the peer's profile screen to draw from; there is nothing on a TLRPC.User to apply
        // it to, so it is deliberately not touched by applyRemote.
        final JSONObject custom = body.optJSONObject("custom_profile");
        profile.customProfile = custom != null && custom.length() > 0 ? custom : null;
        return profile;
    }

    /** The subset of a peer's ProfileDto that has a visible surface on their profile as others see it. */
    private static final class RemoteProfile {
        /** Written from a network callback and read from any thread, so not a plain long. */
        volatile long fetchedAt;
        String raw = "";
        boolean fakePremium;
        @Nullable String fragmentPhone;
        final List<String> fragmentUsernames = new ArrayList<>();
        @Nullable JSONObject customProfile;

        /** Whether anything here can be written onto the peer's {@link TLRPC.User} object. */
        boolean hasInjectable() {
            return fakePremium || !TextUtils.isEmpty(fragmentPhone) || !fragmentUsernames.isEmpty();
        }

        /** A look is not injectable — it is read off the cache while the peer's profile draws. */
        boolean hasLook() {
            return customProfile != null;
        }
    }

    // ===== small helpers (peer-local mirrors of ServerFragmentHelper's private ones) =====

    /** A collectible username: active, not editable — the client's rule for drawing the Fragment badge. */
    private static TLRPC.TL_username collectible(String name) {
        final TLRPC.TL_username entry = new TLRPC.TL_username();
        entry.username = name;
        entry.editable = false;
        entry.active = true;
        entry.flags = 2;
        return entry;
    }

    private static boolean isOwnId(long userId) {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            final UserConfig userConfig = UserConfig.getInstance(account);
            if (userConfig.isClientActivated() && userConfig.getClientUserId() == userId) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsName(List<TLRPC.TL_username> usernames, String name) {
        for (TLRPC.TL_username username : usernames) {
            if (username != null && name.equalsIgnoreCase(username.username)) {
                return true;
            }
        }
        return false;
    }

    private static String clean(@Nullable String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("^@+", "").replaceAll("[^A-Za-z0-9_]", "");
    }

    @Nullable
    private static String digitsOrNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        final String digits = value.replaceAll("[^0-9]", "");
        return digits.isEmpty() ? null : digits;
    }

    @Nullable
    private static String optStringOrNull(JSONObject body, String key) {
        if (body.isNull(key)) {
            return null;
        }
        final String value = body.optString(key, null);
        return TextUtils.isEmpty(value) ? null : value;
    }
}
