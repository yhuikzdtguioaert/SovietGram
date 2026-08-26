package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import tw.nekomimi.nekogram.helpers.remote.ApiServersHelper;

/**
 * Bootstraps the SovietGram sync token, once per logged-in account.
 *
 * <p>An account with no token asks the API for a single-use challenge, then sends
 * {@code /start <challenge>} to one of the two bots — {@code @SovietGrambot} or
 * {@code @SovietGramVerifybot}. The bot redeems the challenge and replies exactly once with the
 * token hidden under a spoiler entity wrapping a code entity (see the server's
 * {@code buildStartMessage}); this class watches that account's incoming messages and stores the
 * token under the telegram id embedded in it.
 *
 * <p>Four things make the handshake stable, and each one fixes a real misbehaviour:
 * <ul>
 *   <li><b>Per-account.</b> Every account runs its own handshake, because a token authenticates the
 *       single telegram id baked into it. With one shared token slot, a second account added to the
 *       install found a token already stored, concluded there was nothing to do, and stayed
 *       unsynced forever.</li>
 *   <li><b>Attempt recorded on disk.</b> A {@code /start} is dispatched at most once per
 *       {@value #AUTH_RETRY_INTERVAL_MS} ms per account. In-memory state alone was not enough: it
 *       resets with the process, so an account that never got a reply re-sent {@code /start} on
 *       every single app launch.</li>
 *   <li><b>{@code messages.startBot}, not a sent message.</b> A normal outgoing message is written
 *       locally before it goes out, so a send the server refuses — which is what happens when the
 *       user has blocked the bot — leaves a permanent message with a red failure mark in the bot
 *       chat. {@code startBot} is a bare request: it either lands or leaves no trace.</li>
 *   <li><b>The block is lifted before the {@code /start}, not after it fails.</b> Telegram accepts
 *       {@code messages.startBot} towards a bot the account has blocked — the request succeeds and
 *       the bot does receive the challenge — but the bot's reply is then refused with
 *       {@code 403 Forbidden: bot was blocked by the user}. Nothing about that failure is visible to
 *       this client: the challenge is spent, no token ever arrives, and the account stays unsynced.
 *       So the block is cleared first, unconditionally, and only then is the {@code /start} sent.</li>
 * </ul>
 *
 * <p>Because the reply arrives a moment after the {@code /start}, an app the user closes in between
 * never sees the update carrying it. So the bot chat's recent history is read before any challenge is
 * spent, and a token found there is filed as if it had just arrived — {@link #ensureToken} recovers
 * on its own from a session that ended at the wrong moment.</p>
 *
 * <p>The token is deterministic server-side (HKDF over the telegram id), so a reinstall that lost
 * local storage recovers the exact same token from a fresh {@code /start} — this class never needs
 * to special-case "already had a token before".
 *
 * <p>A stored token is nevertheless re-checked once per account after every reinstall and every
 * update, which is what {@link #installStamp()} and {@link #verifyStoredToken} are for. The check is
 * a single {@code POST /v1/auth/verify}, so the normal case — the token is still good — costs one
 * small request and no bot message at all; only a token the server actually refuses falls back to the
 * full handshake. Without it an account could sit on a token the server no longer honours (a wiped
 * server database, a rotated secret) and stay silently unsynced, because
 * {@link SovietGramApiClient#isReady} can only judge a token by its shape.
 */
public final class SovietGramAuthHelper implements NotificationCenter.NotificationCenterDelegate {

    private static final String BOT_MAIN = "SovietGrambot";
    private static final String BOT_VERIFY = "SovietGramVerifybot";

    /**
     * Shortest gap between two {@code /start} dispatches for the same account. Long, because the
     * handshake is a once-per-install event: if it did not work, it almost certainly will not work
     * ten minutes later either, and the cost of guessing wrong is an unexplained bot message in the
     * user's chat list.
     */
    private static final long AUTH_RETRY_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static volatile SovietGramAuthHelper instance;

    /** Accounts with a handshake in progress this session, so a second trigger doesn't double-send. */
    private final Set<Integer> inFlight = new HashSet<>();
    /** Accounts whose bot chat has been read for an already-sent token once this session. */
    private final Set<Integer> historyScanned = new HashSet<>();
    /** Accounts whose incoming messages we are already watching for a token reply. */
    private final Set<Integer> observing = new HashSet<>();
    /** Telegram ids whose token the server already rejected once this session — see {@link #onTokenRejected}. */
    private final Set<Long> rejectedOnce = new HashSet<>();
    /** Accounts queued on "a server has been picked", so repeated triggers don't stack listeners. */
    private final Set<Integer> awaitingServer = new HashSet<>();
    /** Telegram ids with a {@code /v1/auth/verify} call outstanding. */
    private final Set<Long> verifying = new HashSet<>();
    private boolean watchingLogins;

    /**
     * The package's {@code lastUpdateTime}, read once per process. Identifies the current install of
     * the APK: it moves on a fresh install and on every update, and stays put across ordinary launches
     * and reboots.
     */
    private volatile long installStamp;
    private SovietGramAuthHelper() {
    }

    public static SovietGramAuthHelper getInstance() {
        SovietGramAuthHelper local = instance;
        if (local == null) {
            synchronized (SovietGramAuthHelper.class) {
                local = instance;
                if (local == null) {
                    instance = local = new SovietGramAuthHelper();
                }
            }
        }
        return local;
    }

    /**
     * Brings every logged-in account up to a token, and re-checks the token of every account that
     * already has one when the app has been reinstalled or updated since it was last checked. Call on
     * launch; cheap and idempotent, so it is also safe to call again whenever the set of accounts
     * might have changed.
     */
    public void ensureTokens() {
        watchLogins();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            ensureToken(account);
        }
    }

    /**
     * Brings one account up to a verified token. No-op when the slot is empty, when a pass is already
     * running for it, or when its token has already been checked against this install of the app.
     *
     * <p>Three outcomes, in order of cost:
     * <ul>
     *   <li><b>Token stored and already checked for this install.</b> Nothing to do.</li>
     *   <li><b>Token stored, install changed.</b> One {@code POST /v1/auth/verify}. This is the
     *       ordinary path after an update: it confirms the token in a single small request and never
     *       touches the bot. Only a token the server refuses falls through to the handshake, via
     *       {@link #onTokenRejected}.</li>
     *   <li><b>No token.</b> The full handshake — read the bot chat, then {@code /start} a challenge.
     *       A changed install additionally releases the retry window first, so an account held back by
     *       an attempt recorded before the update gets a fresh try instead of waiting hours.</li>
     * </ul>
     */
    public void ensureToken(int account) {
        final long ownId = SovietGramTokenStore.ownId(account);
        if (ownId <= 0) {
            return;
        }
        final long stamp = installStamp();
        // A stamp of 0 means the package manager would not answer; treat that as "nothing changed"
        // rather than as a change, so a client that cannot read its own install date behaves exactly
        // as it did before instead of re-verifying on every launch forever.
        final boolean installChanged = stamp > 0 && SovietGramTokenStore.verifiedInstall(ownId) != stamp;

        if (SovietGramTokenStore.hasToken(account)) {
            if (installChanged) {
                verifyStoredToken(account, ownId, stamp);
            }
            return;
        }
        if (installChanged) {
            // Spend the install's one pass now, before anything can fail: what follows is subject to
            // the ordinary retry rules again, which is what keeps an account that can never
            // authenticate from messaging the bot on every single launch.
            SovietGramTokenStore.markVerifiedInstall(ownId, stamp);
            SovietGramTokenStore.clearAuthAttempt(ownId);
        }
        handshake(account, ownId);
    }

    /**
     * Confirms a stored token is still accepted, and records the install it was confirmed for so this
     * costs one request per reinstall or update rather than one per launch.
     *
     * <p>A shape-valid token is not necessarily a working one — the server may have been rebuilt, or
     * the account banned — and {@link SovietGramApiClient#isReady} cannot tell the difference, so
     * without this check such an account would keep every sync feature silently dead while looking
     * perfectly healthy from the inside. On a refusal the client's own 401/403 handling
     * ({@link #onTokenRejected}) drops the token and re-enters {@link #ensureToken} for a full
     * handshake, so there is nothing to do here for that case.
     */
    private void verifyStoredToken(int account, long ownId, long stamp) {
        if (TextUtils.isEmpty(ApiServersHelper.baseUrl())) {
            awaitServer(account);
            return;
        }
        synchronized (verifying) {
            if (!verifying.add(ownId)) {
                return;
            }
        }
        // Empty object rather than no body at all: the route takes no parameters, and a POST that
        // declares JSON with a zero-length body is rejected by the server's parser before the handler.
        SovietGramApiClient.postSigned(account, "/v1/auth/verify", new JSONObject(), (body, error) -> {
            synchronized (verifying) {
                verifying.remove(ownId);
            }
            if (error != null) {
                // Transient (no network, server restarting): leave the stamp unwritten so the next
                // launch checks again. A real refusal has already been handled by onTokenRejected.
                FileLog.d("sovietgram auth: token check failed for account " + account + ": " + error);
                return;
            }
            FileLog.d("sovietgram auth: token confirmed for account " + account);
            SovietGramTokenStore.markVerifiedInstall(ownId, stamp);
            // Authenticated on this install: republish whatever the user configured locally — a fresh
            // install starts with an empty server record — and drain gifts that arrived while away.
            SovietGramSync.scheduleProfilePush();
            SovietGramGiftSync.pollInbox(account);
        });
    }

    /**
     * The full token handshake for an account that has none.
     *
     * <p>The bot chat is read before any challenge is spent. A token the bot already sent is still
     * valid — it is derived from the telegram id, not from the challenge that triggered it — and it
     * may well be sitting there unread: the reply lands a second or two after the {@code /start}, so
     * an app the user closes in the meantime never sees the update that carries it. Recovering it
     * from history costs one request, works inside the retry window, and needs nothing from the user.
     */
    private void handshake(int account, long ownId) {
        // Watch for a token reply as well, so one that arrives while the app is running is picked up
        // the moment it lands rather than on the next launch.
        observe(account);
        final boolean firstPassThisSession;
        synchronized (historyScanned) {
            firstPassThisSession = historyScanned.add(account);
        }
        // mainUserInfoChanged can fire repeatedly through a session. Once the chat has been read and
        // the account is inside its retry window there is nothing left for another pass to do, so stop
        // before spending two requests on re-reading a history that has not changed.
        if (!firstPassThisSession && !mayStartAgain(ownId)) {
            return;
        }
        synchronized (inFlight) {
            if (!inFlight.add(account)) {
                return;
            }
        }
        resolveBot(account, botFor(ownId), bot -> {
            if (bot == null) {
                finish(account);
                return;
            }
            scanBotHistory(account, bot, found -> {
                if (found) {
                    return; // onTokenAcquired already cleaned up
                }
                if (!mayStartAgain(ownId)) {
                    // Asked recently and the bot has not answered in the chat: do not message it
                    // again. finish() so a later trigger in this session can re-read the history.
                    finish(account);
                    return;
                }
                // The bot only answers a /start carrying a live, single-use challenge, and that challenge
                // comes from POST /v1/auth/challenge — which needs a base URL.
                if (TextUtils.isEmpty(ApiServersHelper.baseUrl())) {
                    // Release the pass before waiting and let the queued re-entry run a whole new one.
                    // Waiting here with the account still marked in-flight is what used to strand it for
                    // the rest of the session — see awaitServer.
                    finish(account);
                    awaitServer(account);
                    return;
                }
                requestChallengeAndStart(account, bot);
            });
        });
    }

    /**
     * Re-enters {@link #ensureToken} for {@code account} once a base URL has been picked, and returns
     * immediately.
     *
     * <p>Nothing that talks to the API can happen before {@link ApiServersHelper} has chosen a server,
     * and on a cold install that choice is asynchronous: it needs the remote server list and a health
     * probe. The handshake used to wait for it <i>inside</i> its in-flight window, and
     * {@code onSelected} never fires at all when no candidate answers — so that account stayed marked
     * in-flight for the whole session and every later trigger returned early, while another account,
     * reached after a URL had already been cached, ran straight through. That is precisely the
     * reported "signed into one account and it did not ask, signed into the second and it asked for
     * both" behaviour. Waiting out here holds no per-account state hostage, and the single queued
     * listener per account re-runs the whole pass rather than resuming a fragment of one.
     */
    private void awaitServer(int account) {
        synchronized (awaitingServer) {
            if (!awaitingServer.add(account)) {
                return;
            }
        }
        ApiServersHelper.getInstance().refresh(false);
        ApiServersHelper.getInstance().onSelected(() -> {
            synchronized (awaitingServer) {
                awaitingServer.remove(account);
            }
            ensureToken(account);
        });
    }

    /**
     * The current install's {@code lastUpdateTime}, cached for the process — it cannot change while
     * the app runs — or {@code 0} if the package manager would not answer.
     *
     * <p>{@code lastUpdateTime} rather than {@code firstInstallTime} because an update keeps the
     * first-install time, and an update is one of the two events that must re-trigger verification.
     * It needs no version-number discipline: any new APK moves it, including a rebuild of the same
     * version.
     */
    private long installStamp() {
        if (installStamp != 0) {
            return installStamp;
        }
        try {
            final Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            final PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            installStamp = Math.max(info.lastUpdateTime, 0L);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return installStamp;
    }

    /** True when this account's last {@code /start} is old enough that another one is allowed. */
    private static boolean mayStartAgain(long ownId) {
        final long lastAttempt = SovietGramTokenStore.lastAuthAttemptAt(ownId);
        return lastAttempt <= 0
                || Math.abs(System.currentTimeMillis() - lastAttempt) >= AUTH_RETRY_INTERVAL_MS;
    }

    /**
     * Registers login observers on every account slot — including the empty ones, which is where a
     * newly added account lands. {@code LoginActivity} posts {@code mainUserInfoChanged} on the new
     * account's own notification center once it is authorized, which is how a second account
     * bootstraps mid-session instead of waiting for the next app launch.
     */
    private void watchLogins() {
        if (watchingLogins) {
            return;
        }
        watchingLogins = true;
        AndroidUtilities.runOnUIThread(() -> {
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.mainUserInfoChanged);
            }
        });
    }

    /**
     * Fetches a single-use challenge for {@code account}, then messages {@code bot}. Any failure just
     * clears the in-flight flag; the on-disk attempt record is what decides when we may try again.
     */
    private void requestChallengeAndStart(int account, TLRPC.User bot) {
        final long ownId = SovietGramTokenStore.ownId(account);
        if (ownId <= 0 || SovietGramTokenStore.hasToken(account)) {
            finish(account);
            return;
        }
        SovietGramApiClient.postPublic("/v1/auth/challenge", null, (JSONObject body, String error) -> {
            final String challenge = body == null ? null : body.optString("challenge", null);
            if (TextUtils.isEmpty(challenge)) {
                // Server down or rate limited. No /start was sent, so nothing is recorded and the
                // next launch may try again immediately.
                FileLog.d("sovietgram auth: no challenge for account " + account + ": " + error);
                finish(account);
                return;
            }
            unblockThenStart(account, bot, challenge);
        });
    }

    /**
     * Which bot an account talks to, fixed by the parity of its telegram id. Deterministic on
     * purpose: a retry reaches the same bot as the first attempt, so a user never ends up with two
     * bot conversations, while ids still split evenly across the two bots.
     */
    private static String botFor(long ownId) {
        return (ownId & 1L) == 0L ? BOT_MAIN : BOT_VERIFY;
    }

    /** Receives the resolved bot user, or {@code null} when it could not be resolved. */
    private interface BotCallback {
        void onBot(@Nullable TLRPC.User bot);
    }

    /**
     * Resolves the bot's username and caches the user, then hands it to {@code callback} on the UI
     * thread. Caching is not optional: {@code startBot} needs an InputUser carrying the bot's access
     * hash, and unblocking refuses outright for a peer that is not in the user cache.
     */
    private void resolveBot(int account, String username, BotCallback callback) {
        final TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
        req.username = username;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) {
                FileLog.d("sovietgram auth: cannot resolve @" + username + ": "
                        + (error != null ? error.text : "unexpected response"));
                AndroidUtilities.runOnUIThread(() -> callback.onBot(null));
                return;
            }
            final TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
            if (resolved.users == null || resolved.users.isEmpty()) {
                FileLog.d("sovietgram auth: @" + username + " resolved to no user");
                AndroidUtilities.runOnUIThread(() -> callback.onBot(null));
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(account).putUsers(resolved.users, false);
                callback.onBot(resolved.users.get(0));
            });
        });
    }

    /**
     * Looks for a token the bot has already sent, in the last few messages of the bot chat, and files
     * it if one is there. Reports whether it found one.
     *
     * <p>Read from the server rather than from the local chat database: the whole point is to recover a
     * reply this client was not running to receive, so it is exactly the message that never made it
     * into local storage that we are after.
     */
    private void scanBotHistory(int account, TLRPC.User bot, Utilities.Callback<Boolean> onDone) {
        final long ownId = SovietGramTokenStore.ownId(account);
        final TLRPC.TL_messages_getHistory req = new TLRPC.TL_messages_getHistory();
        req.peer = MessagesController.getInstance(account).getInputPeer(bot.id);
        req.limit = 10;
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            String found = null;
            if (response instanceof TLRPC.messages_Messages) {
                final TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                for (TLRPC.Message message : res.messages) {
                    if (message == null || message.out) continue;
                    final String token = extractToken(message.message, message.entities);
                    // The token names the account it was minted for; one addressed to anybody else is
                    // not ours to file, whatever chat it turned up in.
                    if (token != null && SovietGramTokenStore.telegramIdOf(token) == ownId) {
                        found = token;
                        break;
                    }
                }
            }
            final String token = found;
            AndroidUtilities.runOnUIThread(() -> {
                if (token != null && !SovietGramTokenStore.hasToken(account)) {
                    FileLog.d("sovietgram auth: recovered token from @" + bot.username + " history for account " + account);
                    onTokenAcquired(account, ownId, token);
                    onDone.run(true);
                    return;
                }
                onDone.run(SovietGramTokenStore.hasToken(account));
            });
        });
    }

    /**
     * Clears any block on the bot, then sends the {@code /start}.
     *
     * <p>An account that once blocked the bot — "delete chat and stop bot" is one tap away in the
     * chat list — can still dispatch {@code messages.startBot} successfully, so the client sees a
     * clean handshake while the bot's reply dies with {@code 403 Forbidden: bot was blocked by the
     * user}. The challenge is spent, the token never arrives, and the account is stuck without sync
     * until the retry window lapses. Lifting the block first is what makes the reply deliverable.
     */
    private void unblockThenStart(int account, TLRPC.User bot, String challenge) {
        final MessagesController controller = MessagesController.getInstance(account);
        if (controller.blockePeers.indexOfKey(bot.id) >= 0) {
            // Known blocked: go through MessagesController so its blocked-peer list and counter stay
            // in step with the server, and start only once the unblock has been acknowledged.
            controller.unblockPeer(bot.id, () -> startBot(account, bot, challenge, false));
            return;
        }
        // That list is only populated when the user opens Privacy → Blocked users, so at launch it is
        // empty even for an account that really has the bot blocked. Send the bare request instead:
        // contacts.unblock for a peer that was not blocked simply answers boolFalse, and going around
        // MessagesController keeps it from decrementing a blocked count that never included the bot.
        final TLRPC.TL_contacts_unblock req = new TLRPC.TL_contacts_unblock();
        req.id = controller.getInputPeer(bot.id);
        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) ->
                AndroidUtilities.runOnUIThread(() -> startBot(account, bot, challenge, true)));
    }

    /**
     * Sends {@code /start <challenge>} as {@code messages.startBot}. The bot receives it exactly as
     * it would a {@code t.me/bot?start=} deep link, so the handler redeems the challenge and replies
     * once — and because this is a raw request rather than an outgoing message, a refusal leaves no
     * failed message behind in the chat.
     */
    private void startBot(int account, TLRPC.User bot, String challenge, boolean mayUnblock) {
        final MessagesController controller = MessagesController.getInstance(account);
        final TLRPC.TL_messages_startBot req = new TLRPC.TL_messages_startBot();
        req.bot = controller.getInputUser(bot);
        req.peer = controller.getInputPeer(bot.id);
        req.start_param = challenge;
        req.random_id = Utilities.random.nextLong();

        // Recorded at dispatch, not on success: a challenge has been spent and the bot may already be
        // replying, so this attempt counts either way. Without it a failing account would re-send on
        // every launch.
        final long ownId = SovietGramTokenStore.ownId(account);
        SovietGramTokenStore.markAuthAttempt(ownId);

        ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> {
            if (error != null) {
                if (mayUnblock && isBlockedError(error)) {
                    // Blocked despite the unblock above — the request may have raced it. Lift the
                    // block through MessagesController this time and repeat the same start_param,
                    // which is still unredeemed.
                    AndroidUtilities.runOnUIThread(() ->
                            controller.unblockPeer(bot.id, () -> startBot(account, bot, challenge, false)));
                    return;
                }
                // Nothing was delivered, so give the retry window back rather than making the account
                // wait it out over a request that never reached the bot.
                FileLog.d("sovietgram auth: startBot failed for account " + account + ": " + error.text);
                SovietGramTokenStore.clearAuthAttempt(ownId);
                finish(account);
                return;
            }
            FileLog.d("sovietgram auth: /start sent to @" + bot.username + " for account " + account);
            if (response instanceof TLRPC.Updates) {
                AndroidUtilities.runOnUIThread(() -> controller.processUpdates((TLRPC.Updates) response, false));
            }
        });
    }

    /** True for the errors Telegram returns when the bot sits on the caller's block list. */
    private static boolean isBlockedError(TLRPC.TL_error error) {
        if (error == null || error.text == null) {
            return false;
        }
        final String text = error.text.toUpperCase();
        return text.contains("YOU_BLOCKED_USER") || text.contains("USER_IS_BLOCKED") || text.contains("PEER_BLOCKED");
    }

    /**
     * Drops a token the server refused. {@link SovietGramApiClient} only knows a token by its shape,
     * so a stored-but-rejected token would otherwise keep every sync feature for this account
     * silently dead — {@code isReady(account)} would keep answering yes. Done at most once per
     * account per session, and the re-handshake still honours the on-disk retry window, so a server
     * that rejects everything cannot turn into a {@code /start} loop.
     */
    public void onTokenRejected(int account) {
        final long ownId = SovietGramTokenStore.ownId(account);
        if (ownId <= 0) {
            return;
        }
        synchronized (rejectedOnce) {
            if (!rejectedOnce.add(ownId)) {
                return;
            }
        }
        SovietGramTokenStore.clearToken(ownId);
        AndroidUtilities.runOnUIThread(() -> ensureToken(account));
    }

    private void observe(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (observing.add(account)) {
                NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.didReceiveNewMessages);
            }
        });
    }

    private void stopObserving(int account) {
        AndroidUtilities.runOnUIThread(() -> {
            if (observing.remove(account)) {
                NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.didReceiveNewMessages);
            }
        });
    }

    /**
     * Marks the handshake for {@code account} as no longer running. Observing deliberately continues
     * for the rest of the session: the bot may still answer after we have given up waiting, and
     * catching that reply is free.
     */
    private void finish(int account) {
        synchronized (inFlight) {
            inFlight.remove(account);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.mainUserInfoChanged) {
            // An account just finished logging in (or its user record changed). Sweep every slot
            // rather than just this one: ensureToken is a no-op for accounts already sorted out.
            ensureTokens();
            return;
        }
        if (id != NotificationCenter.didReceiveNewMessages) {
            return;
        }
        final long ownId = SovietGramTokenStore.ownId(account);
        if (ownId <= 0 || SovietGramTokenStore.tokenFor(ownId) != null) {
            return;
        }

        @SuppressWarnings("unchecked")
        final ArrayList<MessageObject> messages = (ArrayList<MessageObject>) args[1];
        if (messages == null) {
            return;
        }

        for (MessageObject messageObject : messages) {
            if (messageObject == null || messageObject.messageOwner == null) continue;
            if (messageObject.isOut()) continue;

            final TLRPC.User sender = MessagesController.getInstance(account)
                    .getUser(messageObject.messageOwner.from_id != null ? messageObject.messageOwner.from_id.user_id : 0);
            if (sender == null || !sender.bot) continue;
            if (!isOurBotUsername(sender)) continue;

            final String token = extractToken(messageObject.messageOwner.message, messageObject.messageOwner.entities);
            if (token == null) continue;
            // The token names the account it was minted for. If that is not the account this message
            // arrived on, something is wrong and filing it here would authenticate the wrong identity.
            if (SovietGramTokenStore.telegramIdOf(token) != ownId) continue;

            FileLog.d("sovietgram auth: token received from @" + sender.username + " for account " + account);
            onTokenAcquired(account, ownId, token);
            return;
        }
    }

    /**
     * Files a freshly obtained token and brings the account's sync to life with it.
     *
     * <p>Shared by both ways a token can arrive — the bot's reply landing while the app runs, and the
     * history scan finding one it sent earlier — because everything that has to happen afterwards is
     * the same either way.
     */
    private void onTokenAcquired(int account, long ownId, String token) {
        SovietGramTokenStore.putToken(ownId, token);
        // This account is now sorted out for the current install, so the next launch of it goes
        // straight past both the verify request and the handshake.
        SovietGramTokenStore.markVerifiedInstall(ownId, installStamp());
        stopObserving(account);
        finish(account);
        ApiServersHelper.getInstance().refresh(true);
        // Reconcile the server with whatever fake-feature state the user already has locally: they may
        // have configured everything before the handshake finished, or be on a fresh install.
        // Debounced, so it is a no-op if nothing is set.
        SovietGramSync.scheduleProfilePush();
        // With a token in hand, drain any gifts sent to this account while it had no way to receive them.
        SovietGramGiftSync.pollInbox(account);
    }

    private static boolean isOurBotUsername(TLRPC.User user) {
        if (user.username != null) {
            if (BOT_MAIN.equalsIgnoreCase(user.username) || BOT_VERIFY.equalsIgnoreCase(user.username)) {
                return true;
            }
        }
        if (user.usernames != null) {
            for (TLRPC.TL_username u : user.usernames) {
                if (u.username != null && (BOT_MAIN.equalsIgnoreCase(u.username) || BOT_VERIFY.equalsIgnoreCase(u.username))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Pulls the token out of a bot message.
     *
     * <p>The bot sends {@code ||`token`||} — a spoiler entity wrapping a code entity over the same
     * range — so the entity pair is tried first. It is only a locator, though: what actually
     * establishes that a string is a token is that it decodes to exactly 128 raw bytes and, at the
     * call site, that those bytes name this account. So if the entities are not the expected pair, the
     * text is swept for a base64url run that validates. Anything the client cannot decode is a token
     * it could not have used anyway, and a locator this narrow is not worth an unusable handshake.
     */
    @Nullable
    private static String extractToken(String text, @Nullable ArrayList<TLRPC.MessageEntity> entities) {
        if (TextUtils.isEmpty(text)) return null;

        TLRPC.MessageEntity spoiler = null;
        TLRPC.MessageEntity code = null;
        if (entities != null) {
            for (TLRPC.MessageEntity entity : entities) {
                if (entity instanceof TLRPC.TL_messageEntitySpoiler) {
                    spoiler = entity;
                } else if (entity instanceof TLRPC.TL_messageEntityCode) {
                    code = entity;
                }
            }
        }
        // The code span must sit inside the spoiler span, and inside the text.
        if (spoiler != null && code != null
                && code.offset >= spoiler.offset
                && (code.offset + code.length) <= (spoiler.offset + spoiler.length)
                && code.offset >= 0
                && code.offset + code.length <= text.length()) {
            final String candidate = text.substring(code.offset, code.offset + code.length).trim();
            if (SovietGramTokenStore.isValidShape(candidate)) {
                return candidate;
            }
        }
        return scanForToken(text);
    }

    /**
     * Longest-first sweep for a base64url substring that decodes to a token. Only maximal runs of the
     * base64url alphabet are considered, so this is a single pass over the text with one decode per
     * run — the messages it looks at are one short line long.
     */
    @Nullable
    private static String scanForToken(String text) {
        final int length = text.length();
        int start = 0;
        while (start < length) {
            if (!isBase64UrlChar(text.charAt(start))) {
                start++;
                continue;
            }
            int end = start;
            while (end < length && isBase64UrlChar(text.charAt(end))) {
                end++;
            }
            final String run = text.substring(start, end);
            if (SovietGramTokenStore.isValidShape(run)) {
                return run;
            }
            start = end;
        }
        return null;
    }

    private static boolean isBase64UrlChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }
}
