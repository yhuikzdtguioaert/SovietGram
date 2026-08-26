package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.List;

import sovietgram.com.NaConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

/**
 * Per-account state for the SovietGram sync backend: the API token, the gift-inbox cursor, the
 * time of the last {@code /start} handshake and the APK install the token was last checked against,
 * each keyed by the account's telegram id.
 *
 * <p>Everything here is per-account because the token is the identity. A token is
 * {@code base64url([8-byte BE telegram_id][120 bytes HKDF])}, so it authenticates exactly one
 * logged-in account and nothing else. While the client kept a single global token, a second account
 * added to the same install could never authenticate: the first account's token was already stored,
 * every "do we have a token?" check answered yes, and so the second account never ran a handshake
 * and never synced a thing. The gift cursor has the same problem in reverse — one shared
 * high-water-mark id would let one account's drain skip gifts addressed to another.
 *
 * <p>The maps are stored as small JSON objects in {@link NaConfig} rather than as one config item
 * per account, so adding or removing an account needs no schema change. Reads parse on demand:
 * every caller is a network path or a launch-time check, never a hot loop.
 *
 * <p>An install that predates this class carried its single token in
 * {@link NaConfig#sovietGramApiToken}. {@link #migrateLegacy()} moves that token under the telegram
 * id embedded in it — the id it was actually minted for, whichever account happened to store it —
 * so an existing user keeps working and is never asked to re-run the handshake.
 */
public final class SovietGramTokenStore {

    /** Raw token is exactly 128 bytes: 8 of telegram id, 120 of HKDF output. */
    public static final int TOKEN_RAW_BYTES = 128;
    /** The id occupies the leading 8 bytes, big-endian. */
    private static final int TOKEN_ID_BYTES = 8;

    private static final int BASE64_FLAGS = Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING;

    /**
     * Bumped whenever a client bug made the recorded handshake attempts meaningless, so the accounts
     * they were holding back get one fresh {@code /start} after the upgrade instead of waiting the
     * retry window out. Epoch 2: attempts recorded by a build that sent {@code /start} without first
     * lifting a block on the bot — those handshakes spent a challenge but could never receive a token.
     */
    private static final int ATTEMPTS_EPOCH = 2;
    /**
     * Where the epoch lives inside the attempts map. Every real key is a decimal telegram id, so a
     * non-numeric name cannot collide with one, and {@code optLong} on it is never consulted.
     */
    private static final String ATTEMPTS_EPOCH_KEY = "v";

    /** Legacy migration runs at most once per process, on the first read or write of any map. */
    private static boolean migrated;

    private SovietGramTokenStore() {
    }

    // ===== tokens =====

    /** The token issued to {@code telegramId}, or {@code null} when there is none of a valid shape. */
    @Nullable
    public static synchronized String tokenFor(long telegramId) {
        if (telegramId <= 0) {
            return null;
        }
        migrateLegacy();
        final String token = root(NaConfig.INSTANCE.getSovietGramApiTokens())
                .optString(String.valueOf(telegramId), "");
        return isValidShape(token) ? token : null;
    }

    /** The token belonging to the account in slot {@code account}, or {@code null}. */
    @Nullable
    public static String tokenForAccount(int account) {
        return tokenFor(ownId(account));
    }

    public static boolean hasToken(int account) {
        return tokenForAccount(account) != null;
    }

    /**
     * Stores {@code token} under {@code telegramId}. The caller must have checked that the token was
     * minted for that id ({@link #telegramIdOf}) — storing a token under the wrong account is the
     * one mistake this whole class exists to prevent.
     */
    public static synchronized void putToken(long telegramId, String token) {
        if (telegramId <= 0 || !isValidShape(token)) {
            return;
        }
        migrateLegacy();
        final ConfigItem item = NaConfig.INSTANCE.getSovietGramApiTokens();
        try {
            final JSONObject root = root(item);
            root.put(String.valueOf(telegramId), token);
            write(item, root);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Forgets {@code telegramId}'s token, so the next handshake mints a fresh one. */
    public static synchronized void clearToken(long telegramId) {
        if (telegramId <= 0) {
            return;
        }
        migrateLegacy();
        final ConfigItem item = NaConfig.INSTANCE.getSovietGramApiTokens();
        final JSONObject root = root(item);
        root.remove(String.valueOf(telegramId));
        write(item, root);
    }

    // ===== accounts =====

    /** The logged-in telegram id of slot {@code account}, or {@code 0} if the slot is empty. */
    public static long ownId(int account) {
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return 0;
        }
        final UserConfig userConfig = UserConfig.getInstance(account);
        return userConfig.isClientActivated() ? userConfig.getClientUserId() : 0;
    }

    /** Every activated account that already holds a token, in slot order. */
    public static List<Integer> accountsWithToken() {
        final List<Integer> accounts = new ArrayList<>();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (hasToken(account)) {
                accounts.add(account);
            }
        }
        return accounts;
    }

    /**
     * Any one account holding a token, or {@code -1}. For reads that are not about a particular
     * account — fetching a peer's public profile answers the same regardless of who asks — so the
     * caller does not have to care which of the user's accounts is authenticated.
     */
    public static int anyAccountWithToken() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (hasToken(account)) {
                return account;
            }
        }
        return -1;
    }

    // ===== gift inbox cursor =====

    public static synchronized long giftCursor(int account) {
        final long ownId = ownId(account);
        if (ownId <= 0) {
            return 0;
        }
        migrateLegacy();
        return root(NaConfig.INSTANCE.getSovietGramGiftCursors()).optLong(String.valueOf(ownId), 0L);
    }

    public static synchronized void setGiftCursor(int account, long cursor) {
        final long ownId = ownId(account);
        if (ownId <= 0 || cursor <= 0) {
            return;
        }
        migrateLegacy();
        putLong(NaConfig.INSTANCE.getSovietGramGiftCursors(), ownId, cursor);
    }

    // ===== handshake attempts =====

    /**
     * When we last sent {@code telegramId}'s account a {@code /start}, or {@code 0} if never. The
     * handshake is gated on this: an account whose attempt is still inside the retry window is left
     * alone, which is what stops the client from re-running {@code /start} on every single launch.
     */
    public static synchronized long lastAuthAttemptAt(long telegramId) {
        if (telegramId <= 0) {
            return 0;
        }
        migrateLegacy();
        return root(NaConfig.INSTANCE.getSovietGramAuthAttempts()).optLong(String.valueOf(telegramId), 0L);
    }

    /** Records that a {@code /start} just went out for {@code telegramId}. */
    public static synchronized void markAuthAttempt(long telegramId) {
        if (telegramId <= 0) {
            return;
        }
        migrateLegacy();
        putLong(NaConfig.INSTANCE.getSovietGramAuthAttempts(), telegramId, System.currentTimeMillis());
    }

    /**
     * Forgets the recorded attempt, so the next launch may hand the account another
     * {@code /start} instead of sitting out the retry window.
     *
     * <p>The window exists to stop a repeated {@code /start} from showing up in the user's chat
     * list, so it must only be spent on a {@code /start} that actually reached Telegram. When the
     * request comes back refused nothing was delivered and no challenge was redeemed — keeping the
     * record there would strand the account without a token for hours over a failure that a retry
     * moments later might not even hit.
     */
    public static synchronized void clearAuthAttempt(long telegramId) {
        if (telegramId <= 0) {
            return;
        }
        migrateLegacy();
        final ConfigItem item = NaConfig.INSTANCE.getSovietGramAuthAttempts();
        final JSONObject root = root(item);
        root.remove(String.valueOf(telegramId));
        write(item, root);
    }

    // ===== install verification stamp =====

    /**
     * The APK install this account's token was last checked against — the package's
     * {@code lastUpdateTime} at the moment the check passed — or {@code 0} if it never has been.
     *
     * <p>This is what makes "re-verify on every reinstall and every update" cheap and exactly
     * once-per-event. {@code lastUpdateTime} changes on a fresh install and on every update, whatever
     * the version number says, so comparing it against the stored value answers "has this account
     * been checked since the app last changed underneath it?" without any version bookkeeping. A
     * reinstall additionally wipes this whole config, which reads as {@code 0} — still a mismatch, so
     * the answer stays correct rather than depending on the wipe.
     */
    public static synchronized long verifiedInstall(long telegramId) {
        if (telegramId <= 0) {
            return 0;
        }
        migrateLegacy();
        return root(NaConfig.INSTANCE.getSovietGramAuthInstalls()).optLong(String.valueOf(telegramId), 0L);
    }

    /**
     * Records that {@code telegramId} has had its verification pass for the install identified by
     * {@code stamp}, so the next launch of the same install does not repeat it.
     *
     * <p>Written when the pass is spent, not only when it succeeds. A pass that fails leaves the
     * ordinary retry rules in charge ({@link #lastAuthAttemptAt}), which is the right fallback: if the
     * stamp were only written on success, an account that can never obtain a token would clear its
     * retry window on every single launch and message the bot forever.
     */
    public static synchronized void markVerifiedInstall(long telegramId, long stamp) {
        if (telegramId <= 0 || stamp <= 0) {
            return;
        }
        migrateLegacy();
        putLong(NaConfig.INSTANCE.getSovietGramAuthInstalls(), telegramId, stamp);
    }

    // ===== token decoding =====
    /** The telegram id a token was minted for, read straight out of its first eight bytes; {@code 0} if unreadable. */
    public static long telegramIdOf(@Nullable String token) {
        final byte[] raw = decode(token);
        if (raw == null) {
            return 0;
        }
        long id = 0;
        for (int i = 0; i < TOKEN_ID_BYTES; i++) {
            id = (id << 8) | (raw[i] & 0xFFL);
        }
        return id;
    }

    /** True when {@code token} decodes to exactly the {@value #TOKEN_RAW_BYTES} raw bytes a token is. */
    public static boolean isValidShape(@Nullable String token) {
        return decode(token) != null;
    }

    @Nullable
    private static byte[] decode(@Nullable String token) {
        if (TextUtils.isEmpty(token)) {
            return null;
        }
        try {
            final byte[] raw = Base64.decode(token, BASE64_FLAGS);
            return raw.length == TOKEN_RAW_BYTES ? raw : null;
        } catch (Throwable e) {
            return null;
        }
    }

    // ===== legacy migration =====

    /**
     * Moves a pre-per-account install's single token into the map, keyed by the id embedded in the
     * token rather than by whichever account stored it, and carries that account's gift cursor
     * across with it. The legacy slot is then emptied, which is also what marks the migration done
     * for good — the flag below only avoids repeating the work within one process.
     */
    private static void migrateLegacy() {
        if (migrated) {
            return;
        }
        migrated = true; // set first: a throw must not turn this into a retry on every read
        resetStaleAttempts();
        try {
            final ConfigItem legacyToken = NaConfig.INSTANCE.getSovietGramApiToken();
            final String token = legacyToken.String();
            if (!isValidShape(token)) {
                return;
            }
            final long telegramId = telegramIdOf(token);
            if (telegramId <= 0) {
                return;
            }
            final ConfigItem tokens = NaConfig.INSTANCE.getSovietGramApiTokens();
            final JSONObject root = root(tokens);
            if (!root.has(String.valueOf(telegramId))) {
                root.put(String.valueOf(telegramId), token);
                write(tokens, root);
                final long cursor = NaConfig.INSTANCE.getSovietGramGiftInboxCursor().Long();
                if (cursor > 0) {
                    putLong(NaConfig.INSTANCE.getSovietGramGiftCursors(), telegramId, cursor);
                }
            }
            legacyToken.setConfigString("");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /**
     * Drops every recorded handshake attempt once, when the stored epoch is older than
     * {@link #ATTEMPTS_EPOCH}. Tokens and gift cursors are untouched: an account that already holds a
     * token never looks at its attempt record, so this only affects accounts that are still waiting.
     */
    private static void resetStaleAttempts() {
        try {
            final ConfigItem item = NaConfig.INSTANCE.getSovietGramAuthAttempts();
            final JSONObject root = root(item);
            if (root.optInt(ATTEMPTS_EPOCH_KEY, 0) >= ATTEMPTS_EPOCH) {
                return;
            }
            final JSONObject fresh = new JSONObject();
            fresh.put(ATTEMPTS_EPOCH_KEY, ATTEMPTS_EPOCH);
            // Written directly, not via write(): the epoch alone is not an empty map, and blanking it
            // would make the reset run again on the very next launch.
            item.setConfigString(fresh.toString());
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    // ===== JSON map plumbing =====

    private static JSONObject root(ConfigItem item) {
        final String raw = item.String();
        if (!TextUtils.isEmpty(raw)) {
            try {
                return new JSONObject(raw);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return new JSONObject();
    }

    /** Empty map is stored as "" so a fresh install and a fully cleared one look the same. */
    private static void write(ConfigItem item, JSONObject root) {
        item.setConfigString(root.length() == 0 ? "" : root.toString());
    }

    private static void putLong(ConfigItem item, long telegramId, long value) {
        try {
            final JSONObject root = root(item);
            root.put(String.valueOf(telegramId), value);
            write(item, root);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
