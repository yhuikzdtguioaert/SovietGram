package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import sovietgram.com.NaConfig;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

/**
 * Makes the fake-identity settings belong to one account instead of to the install: the fake premium
 * badge, the Fragment number and collectible usernames, the fake Stars and TON balances and the whole
 * Custom Profile look. Enabling any of them while account A is open must show up on account A and
 * nowhere else — before this, every one of them was a global switch, so a number bought on one
 * account appeared on all of them at once.
 *
 * <p>The obvious implementation — make each setting take an account parameter — is not available
 * here. These live in {@link NekoConfig} items that are read from hundreds of places across the app
 * and are bound directly to settings rows, so threading an account through all of that would touch
 * every drawing path the features have.
 *
 * <p>So the values stay global and exactly one account's are live at a time. Switching account saves
 * the outgoing account's live values into {@link NaConfig#getSovietGramAccountScopes()} and loads the
 * incoming account's over the globals ({@link #syncTo(int)}). Every existing read keeps working
 * unchanged and now sees only the current account's identity, and every settings row keeps editing
 * exactly what the user is looking at.
 *
 * <p>Two things need more than the live values, and both are served by the stored snapshots:
 * <ul>
 *   <li>The push to the sync backend runs for every logged-in account at once, and each has to
 *       publish its own identity — {@link #export(int, ConfigItem[])} and the typed readers below
 *       answer for an account whether or not it is the live one.</li>
 *   <li>Telegram's data layer keeps running for accounts that are not on screen. A premium check for
 *       account B must not answer with account A's fake, hence {@link #bool(int, ConfigItem)} at
 *       those call sites rather than a bare {@code Bool()}.</li>
 * </ul>
 *
 * <p>On the first launch after this arrives the store is empty, and whatever the user had configured
 * is adopted by the account they are in — they keep what they set up. A <em>second</em> account then
 * starts from the defaults instead of inheriting it, which is the whole point.
 */
public final class SovietGramAccountScope {

    /**
     * Which account's values are currently live in the global config items, stored inside the same
     * JSON object under a key no telegram id can collide with (every real key is a decimal id).
     *
     * <p>It has to be persisted: the globals survive a restart, so after one the app must know whose
     * values it is holding. Without it the first switch of the next session would save the previous
     * account's identity under whichever account happened to be selected.
     */
    private static final String LOADED_KEY = "@";

    /**
     * The identity settings. Deliberately not everything on the SovietGram exclusive screen: the gift
     * sender, meme frames and the voice changer are client-wide behaviour rather than a per-account
     * identity, and moving them would only surprise the user when they switch account.
     */
    private static final ConfigItem[] IDENTITY = {
            NekoConfig.localPremium,
            NekoConfig.fakeStars,
            NekoConfig.fakeStarsAmount,
            NekoConfig.serverTon,
            NekoConfig.serverTonAmount,
            NekoConfig.serverFragment,
            NekoConfig.serverFragmentPhone,
            NekoConfig.serverFragmentUsernames,
    };

    /** {@link #IDENTITY} plus the Custom Profile look; built once, on first use. */
    @Nullable
    private static ConfigItem[] scoped;

    /**
     * The live account's telegram id, or {@code 0} before it has been read. Cached because the
     * per-account readers are called from data-layer paths that run per update.
     */
    private static long loadedId;
    private static boolean loadedIdRead;

    /** How long {@link #saveLive()} waits before writing, to collapse a burst of edits into one write. */
    private static final long SAVE_DEBOUNCE_MS = 400L;
    private static final Runnable persistRunnable = SovietGramAccountScope::persistLive;

    private SovietGramAccountScope() {
    }

    // ===== switching =====

    /**
     * Makes {@code account}'s identity the live one: saves whatever is live now under its owner and
     * loads {@code account}'s stored values over the global config items. A no-op when the account is
     * already live, when its slot is empty, or when it is not logged in — an account with no telegram
     * id has no identity to hold, and blanking the globals for it would look like the settings had
     * been lost.
     *
     * <p>Call it before the rest of the app reacts to the switch, so the first redraw already has the
     * right values.
     */
    public static synchronized void syncTo(int account) {
        final long incoming = SovietGramTokenStore.ownId(account);
        if (incoming <= 0) {
            return;
        }
        final long outgoing = loadedId();
        if (incoming == outgoing) {
            return;
        }
        // A pending save would write the outgoing account's values — which is exactly what happens
        // below anyway, from a snapshot taken now, so the queued one is redundant. Dropping it also
        // keeps it from landing after the swap and filing them under the wrong account.
        AndroidUtilities.cancelRunOnUIThread(persistRunnable);
        final JSONObject root = root();
        if (outgoing > 0) {
            put(root, outgoing, snapshotLive());
        }
        final JSONObject stored = root.optJSONObject(String.valueOf(incoming));
        if (stored != null) {
            applyToLive(stored);
        } else if (isFirstEverAccount(root)) {
            // Nothing has ever been scoped, so the live values are the ones this user configured back
            // when the settings were global. Adopt them for the account they are in rather than
            // resetting: from their side nothing changed, which is the only acceptable upgrade.
            put(root, incoming, snapshotLive());
        } else {
            // A different account already owns an identity. This one starts clean — inheriting the
            // other account's number, balances and look is exactly the bug being fixed.
            resetLive();
            put(root, incoming, snapshotLive());
        }
        setLoaded(root, incoming);
        write(root);
        loadedId = incoming;
        loadedIdRead = true;
        afterSwap();
    }

    /**
     * Records the live account's current values. Called from the one funnel every scoped edit already
     * passes through ({@link SovietGramSync#scheduleProfilePush()}).
     *
     * <p>Coalesced, because that funnel is also on the path of a colour slider being dragged and the
     * snapshot is some sixty values wide — writing it per frame would be felt. Nothing is at risk in
     * the gap: the live values are already persisted in the config items themselves, and the account
     * they belong to is persisted too, so a process death inside the window loses nothing and the next
     * switch still files them under the right account.
     */
    public static void saveLive() {
        AndroidUtilities.cancelRunOnUIThread(persistRunnable);
        AndroidUtilities.runOnUIThread(persistRunnable, SAVE_DEBOUNCE_MS);
    }

    private static synchronized void persistLive() {
        final long owner = loadedId();
        if (owner <= 0) {
            return;
        }
        final JSONObject root = root();
        put(root, owner, snapshotLive());
        setLoaded(root, owner);
        write(root);
    }

    // ===== ownership =====

    /**
     * The telegram id whose identity the global config items are currently holding, or {@code 0}.
     * This is the id the fake number, usernames and premium flag belong to, and the one thing that
     * decides whether the own-account injection may fabricate anything for a given user object.
     */
    public static synchronized long owner() {
        return loadedId();
    }

    /** Whether {@code account}'s values are the live ones, i.e. plain config reads answer for it. */
    public static synchronized boolean isLive(int account) {
        final long ownId = SovietGramTokenStore.ownId(account);
        return ownId > 0 && ownId == loadedId();
    }

    /** Whether {@code userId} owns the live values. */
    public static synchronized boolean isOwner(long userId) {
        return userId > 0 && userId == loadedId();
    }

    // ===== per-account reads =====

    public static synchronized boolean bool(int account, ConfigItem item) {
        final Object value = valueOf(account, item);
        return value instanceof Boolean ? (Boolean) value : false;
    }

    public static synchronized int integer(int account, ConfigItem item) {
        final Object value = valueOf(account, item);
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    public static synchronized String str(int account, ConfigItem item) {
        final Object value = valueOf(account, item);
        return value == null ? "" : value.toString();
    }

    /**
     * {@code item}'s value for {@code account}: the live one when that account is loaded, its stored
     * snapshot otherwise, and the item's default when the account has never been scoped. Never
     * returns another account's value, which is the entire contract — a missing snapshot means "this
     * account has none of this configured", not "use whatever is loaded".
     */
    @Nullable
    private static Object valueOf(int account, ConfigItem item) {
        final long ownId = SovietGramTokenStore.ownId(account);
        if (ownId <= 0) {
            return item.defaultValue;
        }
        if (ownId == loadedId()) {
            return live(item);
        }
        final JSONObject stored = readRoot().optJSONObject(String.valueOf(ownId));
        return stored == null ? item.defaultValue : stored(stored, item);
    }

    /**
     * {@code items} as a flat JSON object keyed by config key, holding {@code account}'s values. The
     * shape the sync backend takes, and the reason a push can send each logged-in account its own
     * body while only one of them is live.
     */
    public static synchronized JSONObject export(int account, ConfigItem[] items) {
        final long ownId = SovietGramTokenStore.ownId(account);
        final boolean fromLive = ownId > 0 && ownId == loadedId();
        final JSONObject stored = fromLive || ownId <= 0
                ? null
                : readRoot().optJSONObject(String.valueOf(ownId));
        final JSONObject out = new JSONObject();
        for (ConfigItem item : items) {
            try {
                if (fromLive) {
                    out.put(item.getKey(), live(item));
                } else if (stored != null) {
                    out.put(item.getKey(), stored(stored, item));
                } else {
                    out.put(item.getKey(), item.defaultValue);
                }
            } catch (JSONException e) {
                FileLog.e(e);
            }
        }
        return out;
    }

    // ===== live values =====

    /** Every scoped item: the identity settings plus the Custom Profile look. */
    private static ConfigItem[] items() {
        if (scoped == null) {
            final List<ConfigItem> all = new ArrayList<>(IDENTITY.length + 64);
            for (ConfigItem item : IDENTITY) {
                all.add(item);
            }
            for (ConfigItem item : CustomProfileHelper.scopedItems()) {
                all.add(item);
            }
            scoped = all.toArray(new ConfigItem[0]);
        }
        return scoped;
    }

    private static JSONObject snapshotLive() {
        final JSONObject snapshot = new JSONObject();
        for (ConfigItem item : items()) {
            try {
                snapshot.put(item.getKey(), live(item));
            } catch (JSONException e) {
                FileLog.e(e);
            }
        }
        return snapshot;
    }

    private static void applyToLive(JSONObject snapshot) {
        for (ConfigItem item : items()) {
            write(item, snapshot.has(item.getKey()) ? stored(snapshot, item) : item.defaultValue);
        }
    }

    private static void resetLive() {
        for (ConfigItem item : items()) {
            write(item, item.defaultValue);
        }
    }

    /** The item's current value, typed so it round-trips through JSON without losing precision. */
    private static Object live(ConfigItem item) {
        switch (item.type) {
            case ConfigItem.configTypeBool:
            case ConfigItem.configTypeBoolLinkInt:
                return item.Bool();
            case ConfigItem.configTypeInt:
                return item.Int();
            case ConfigItem.configTypeLong:
                return (long) item.Long();
            case ConfigItem.configTypeFloat:
                return (double) item.Float();
            default:
                return item.String();
        }
    }

    /** The stored value for {@code item}, coerced to the item's own type. */
    private static Object stored(JSONObject snapshot, ConfigItem item) {
        final String key = item.getKey();
        switch (item.type) {
            case ConfigItem.configTypeBool:
            case ConfigItem.configTypeBoolLinkInt:
                return snapshot.optBoolean(key, item.defaultValue instanceof Boolean && (Boolean) item.defaultValue);
            case ConfigItem.configTypeInt:
                return snapshot.optInt(key, item.defaultValue instanceof Number ? ((Number) item.defaultValue).intValue() : 0);
            case ConfigItem.configTypeLong:
                return snapshot.optLong(key, item.defaultValue instanceof Number ? ((Number) item.defaultValue).longValue() : 0L);
            case ConfigItem.configTypeFloat:
                return snapshot.optDouble(key, item.defaultValue instanceof Number ? ((Number) item.defaultValue).doubleValue() : 0d);
            default:
                return snapshot.optString(key, item.defaultValue == null ? "" : item.defaultValue.toString());
        }
    }

    private static void write(ConfigItem item, Object value) {
        switch (item.type) {
            case ConfigItem.configTypeBool:
            case ConfigItem.configTypeBoolLinkInt:
                item.setConfigBool(value instanceof Boolean && (Boolean) value);
                break;
            case ConfigItem.configTypeInt:
                item.setConfigInt(value instanceof Number ? ((Number) value).intValue() : 0);
                break;
            case ConfigItem.configTypeLong:
                item.setConfigLong(value instanceof Number ? ((Number) value).longValue() : 0L);
                break;
            case ConfigItem.configTypeFloat:
                item.setConfigFloat(value instanceof Number ? ((Number) value).floatValue() : 0f);
                break;
            default:
                item.setConfigString(value == null ? "" : value.toString());
                break;
        }
    }

    /**
     * Tells everything that reads the scoped values that they have all just changed underneath it:
     * the own-account identity has to be re-injected (and restored on the account that lost it), the
     * decoded banner and background belong to the previous account's files, and any open screen is
     * drawing the wrong look.
     *
     * <p>Posted rather than run inline: this happens in the middle of an account switch, before the
     * app has finished pointing itself at the new account.
     */
    private static void afterSwap() {
        AndroidUtilities.runOnUIThread(() -> {
            ServerFragmentHelper.onSettingsChanged();
            CustomProfileHelper.releaseVideo();
            CustomProfileHelper.onSettingsChanged();
        });
    }

    // ===== store =====

    /** True when no account has ever been scoped, i.e. the live values are pre-scope leftovers. */
    private static boolean isFirstEverAccount(JSONObject root) {
        final Iterator<String> keys = root.keys();
        while (keys.hasNext()) {
            if (!LOADED_KEY.equals(keys.next())) {
                return false;
            }
        }
        return true;
    }

    private static long loadedId() {
        if (!loadedIdRead) {
            loadedId = readRoot().optLong(LOADED_KEY, 0);
            loadedIdRead = true;
        }
        return loadedId;
    }

    private static void setLoaded(JSONObject root, long telegramId) {
        try {
            root.put(LOADED_KEY, telegramId);
        } catch (JSONException e) {
            FileLog.e(e);
        }
    }

    private static void put(JSONObject root, long telegramId, JSONObject snapshot) {
        try {
            root.put(String.valueOf(telegramId), snapshot);
        } catch (JSONException e) {
            FileLog.e(e);
        }
    }

    private static JSONObject root() {
        return parse(NaConfig.INSTANCE.getSovietGramAccountScopes().String());
    }

    /**
     * The parsed store for reading, cached against the raw string it came from. The per-account
     * premium check runs on the data layer's hot paths for accounts that are not the live one, and
     * re-parsing the whole store for each of those reads would be the one expensive thing in here.
     * The returned object must not be modified — every write path takes a fresh copy from
     * {@link #root()} instead, and changes the raw string, which invalidates this by itself.
     */
    private static JSONObject readRoot() {
        final String raw = NaConfig.INSTANCE.getSovietGramAccountScopes().String();
        if (cachedRoot == null || !raw.equals(cachedRaw)) {
            cachedRaw = raw;
            cachedRoot = parse(raw);
        }
        return cachedRoot;
    }

    @Nullable
    private static String cachedRaw;
    @Nullable
    private static JSONObject cachedRoot;

    private static JSONObject parse(@Nullable String raw) {
        if (!TextUtils.isEmpty(raw)) {
            try {
                return new JSONObject(raw);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
        return new JSONObject();
    }

    private static void write(JSONObject root) {
        NaConfig.INSTANCE.getSovietGramAccountScopes()
                .setConfigString(root.length() == 0 ? "" : root.toString());
    }

    /**
     * The file name suffix that keeps one account's picked banner or background from overwriting
     * another's. The paths themselves are scoped, so without this both accounts' paths would point at
     * the same file and the second pick would silently replace the first.
     */
    public static synchronized String fileSuffix() {
        final long owner = loadedId();
        return owner > 0 ? "_" + owner : "";
    }

    /** Convenience for the callers that only ever ask about the account on screen. */
    public static void syncToSelected() {
        syncTo(UserConfig.selectedAccount);
    }
}
