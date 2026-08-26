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
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.RequestDelegate;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_fragment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * "Server Fragment Username/Number".
 *
 * <p>Nothing here talks to Telegram. The own {@link TLRPC.User} object is rewritten in memory so
 * the client believes the account carries a Fragment phone number and a set of collectible
 * usernames, and the one request the UI would fire to describe such a collectible is answered
 * locally instead of going out. The account itself is never touched, which is the whole point:
 * everything the rest of the app derives from the user object follows along for free.
 *
 * <p>Two client rules drive the whole thing:
 * <ul>
 *   <li>a username is drawn as collectible when {@code TL_username.editable} is false;</li>
 *   <li>a phone number is drawn as collectible when it starts with 888.</li>
 * </ul>
 *
 * <p>The rewritten user object is the same one {@code UserConfig} persists, so the real identity
 * is copied into {@link NekoConfig#serverFragmentBackup} before the first rewrite and read back
 * from there when the feature is switched off. An in-memory snapshot would not survive the app
 * being killed while the toggle was on, and the fabricated identity would then be all that is
 * left on disk.
 */
public final class ServerFragmentHelper {

    /**
     * Fragment sells at auction, so there is no real formula to copy. Short handles going for more
     * is the one rule that always holds, so length sets the base price and the name's hash spreads
     * same-length handles apart instead of pricing them all identically.
     */
    private static final double PRICE_SCALE = 2000.0;
    private static final double PRICE_EXPONENT = 1.6;
    private static final long NANOTONS_IN_TON = 1_000_000_000L;
    /** TL_collectibleInfo.amount is in the currency's minor unit. */
    private static final long CENTS_IN_USD = 100L;
    private static final double TON_USD_RATE = 3.2;
    /** Fragment opened at the end of October 2022, so no purchase can predate this. */
    private static final int FRAGMENT_LAUNCH_DATE = 1666915200;
    private static final long THREE_YEARS_SECONDS = 3L * 365 * 24 * 3600;

    private ServerFragmentHelper() {
    }

    // ===== injection =====

    /**
     * Rewrites {@code user} when it is the own account <em>that owns the setting</em> and the feature
     * is on. Safe to call as often as the user object is read: it costs one boolean read while
     * disabled and is idempotent while enabled.
     *
     * <p>{@code user.self} is true for the own user object of <em>every</em> logged-in account, so it
     * is not enough on its own — the ownership check is what keeps a number configured on one account
     * from appearing on all of them. See {@link SovietGramAccountScope}.
     */
    public static void apply(@Nullable TLRPC.User user) {
        if (user == null || !user.self || !SovietGramAccountScope.isOwner(user.id) || !NekoConfig.serverFragment.Bool()) {
            return;
        }
        Identity backup = readBackup(user.id);
        if (backup == null) {
            if (user.min) {
                // A min user carries no phone and no username list, so capturing it would record an
                // empty identity as the real one. The full object arrives soon enough.
                return;
            }
            backup = writeBackup(user);
        }
        final String phone = phone();
        if (!phone.isEmpty()) {
            user.phone = phone;
            // flags bit 4 is what makes TL_user serialize its phone, and UserConfig round-trips the
            // own user through that serializer. Without it the number is gone after a restart.
            user.flags |= 16;
        }

        // Rebuild from the captured list rather than diffing against what is already there: names
        // dropped from the setting have to disappear too, and rebuilding cannot get that wrong.
        final ArrayList<TLRPC.TL_username> rebuilt = copyOf(backup.usernames);
        for (String name : usernames()) {
            if (!containsName(rebuilt, name)) {
                rebuilt.add(collectible(name));
            }
        }
        setUsernames(user, rebuilt);
    }

    /** Writes the list and keeps flags2 bit 0, which marks the vector as present, in step with it. */
    private static void setUsernames(TLRPC.User user, ArrayList<TLRPC.TL_username> usernames) {
        user.usernames = usernames;
        if (usernames.isEmpty()) {
            user.flags2 &= ~1;
        } else {
            user.flags2 |= 1;
        }
    }

    private static TLRPC.TL_username collectible(String name) {
        final TLRPC.TL_username entry = new TLRPC.TL_username();
        entry.username = name;
        entry.editable = false;
        entry.active = true;
        // active without editable, which is what the server sends for a Fragment username.
        entry.flags = 2;
        return entry;
    }

    /**
     * Re-runs the injection on every logged in account and tells the UI to redraw. Called whenever
     * one of the three settings changes, and whenever the account switch swaps which account the
     * settings belong to: the user object is only rewritten where it is read, so without this an open
     * profile screen keeps showing the previous identity.
     *
     * <p>An account that does not own the setting is actively restored, not merely left alone. That is
     * what hands the fabricated number back after an account switch — the previous owner's real phone
     * and username list return from its backup instead of the fake identity staying on both accounts.
     */
    public static void onSettingsChanged() {
        final boolean enabled = NekoConfig.serverFragment.Bool();
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            final UserConfig userConfig = UserConfig.getInstance(account);
            if (!userConfig.isClientActivated()) {
                continue;
            }
            final TLRPC.User user = userConfig.getCurrentUser();
            if (user == null) {
                continue;
            }
            if (enabled && SovietGramAccountScope.isOwner(user.id)) {
                apply(user);
            } else {
                restore(user);
            }
            // The object we just rewrote is the one UserConfig persists, so write it out now
            // instead of waiting for whatever would have saved it next.
            userConfig.saveConfig(true);
            final int finalAccount = account;
            AndroidUtilities.runOnUIThread(() -> {
                MessagesController.getInstance(finalAccount).putUser(user, false, true);
                NotificationCenter.getInstance(finalAccount).postNotificationName(NotificationCenter.updateInterfaces, MessagesController.UPDATE_MASK_ALL);
                NotificationCenter.getInstance(finalAccount).postNotificationName(NotificationCenter.mainUserInfoChanged);
            });
        }
        // The phone/usernames the UI now shows are what other SovietGram users should see too.
        SovietGramSync.scheduleProfilePush();
    }

    /** Puts the real phone and username list back and drops that account's backup. */
    private static void restore(TLRPC.User user) {
        final Identity backup = readBackup(user.id);
        if (backup == null) {
            return;
        }
        user.phone = backup.phone;
        if (TextUtils.isEmpty(backup.phone)) {
            user.flags &= ~16;
        }
        setUsernames(user, copyOf(backup.usernames));
        dropBackup(user.id);
    }

    // ===== the real identity, kept across restarts =====

    /**
     * The backup is a JSON object keyed by user id, so several logged in accounts can each carry a
     * fabricated identity without overwriting one another's real one.
     */
    private static final class Identity {
        String phone;
        ArrayList<TLRPC.TL_username> usernames = new ArrayList<>();
    }

    private static JSONObject backupRoot() {
        final String raw = NekoConfig.serverFragmentBackup.String();
        if (!TextUtils.isEmpty(raw)) {
            try {
                return new JSONObject(raw);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        return new JSONObject();
    }

    /**
     * putUser runs on every update the app receives, so the own user passes through here often
     * enough that re-parsing the backup each time is worth avoiding. Keyed on the raw string, so
     * any write invalidates it for free.
     */
    private static String cachedRaw;
    private static HashMap<Long, Identity> cachedIdentities;

    @Nullable
    private static synchronized Identity readBackup(long userId) {
        final String raw = NekoConfig.serverFragmentBackup.String();
        if (!raw.equals(cachedRaw) || cachedIdentities == null) {
            cachedRaw = raw;
            cachedIdentities = new HashMap<>();
        } else if (cachedIdentities.containsKey(userId)) {
            return cachedIdentities.get(userId);
        }
        final Identity identity = parseBackup(userId);
        cachedIdentities.put(userId, identity);
        return identity;
    }

    @Nullable
    private static Identity parseBackup(long userId) {
        final JSONObject entry = backupRoot().optJSONObject(String.valueOf(userId));
        if (entry == null) {
            return null;
        }
        final Identity identity = new Identity();
        identity.phone = entry.optString("phone", "");
        final JSONArray names = entry.optJSONArray("usernames");
        if (names != null) {
            for (int i = 0; i < names.length(); i++) {
                final JSONObject name = names.optJSONObject(i);
                if (name == null) {
                    continue;
                }
                final TLRPC.TL_username username = new TLRPC.TL_username();
                username.username = name.optString("username");
                username.editable = name.optBoolean("editable");
                username.active = name.optBoolean("active");
                username.flags = name.optInt("flags");
                identity.usernames.add(username);
            }
        }
        return identity;
    }

    private static Identity writeBackup(TLRPC.User user) {
        final Identity identity = new Identity();
        identity.phone = user.phone == null ? "" : user.phone;
        identity.usernames = copyOf(user.usernames);
        try {
            final JSONObject entry = new JSONObject();
            entry.put("phone", identity.phone);
            final JSONArray names = new JSONArray();
            for (TLRPC.TL_username username : identity.usernames) {
                if (username.username == null) {
                    continue;
                }
                final JSONObject name = new JSONObject();
                name.put("username", username.username);
                name.put("editable", username.editable);
                name.put("active", username.active);
                name.put("flags", username.flags);
                names.put(name);
            }
            entry.put("usernames", names);
            final JSONObject root = backupRoot();
            root.put(String.valueOf(user.id), entry);
            NekoConfig.serverFragmentBackup.setConfigString(root.toString());
        } catch (Exception e) {
            FileLog.e(e);
        }
        return identity;
    }

    private static void dropBackup(long userId) {
        final JSONObject root = backupRoot();
        root.remove(String.valueOf(userId));
        NekoConfig.serverFragmentBackup.setConfigString(root.length() == 0 ? "" : root.toString());
    }

    // ===== local answer for TL_getCollectibleInfo =====

    /**
     * Drop-in for {@code getConnectionsManager().sendRequest(req, onComplete)} at the call sites
     * that describe a collectible. Fabricated names are answered here and the request never leaves
     * the device; anything else goes out as usual.
     *
     * @return the request id to bind to a guid, or 0 when the answer was produced locally.
     */
    public static int sendCollectibleInfo(ConnectionsManager connectionsManager, TL_fragment.TL_getCollectibleInfo req, RequestDelegate onComplete) {
        final TL_fragment.TL_collectibleInfo local = localInfo(req);
        if (local != null) {
            AndroidUtilities.runOnUIThread(() -> onComplete.run(local, null));
            return 0;
        }
        return connectionsManager.sendRequest(req, onComplete);
    }

    @Nullable
    private static TL_fragment.TL_collectibleInfo localInfo(TL_fragment.TL_getCollectibleInfo req) {
        if (req == null) {
            return null;
        }
        if (req.collectible instanceof TL_fragment.TL_inputCollectibleUsername) {
            final String username = clean(((TL_fragment.TL_inputCollectibleUsername) req.collectible).username);
            if (username.isEmpty() || !isFabricatedUsername(username)) {
                return null;
            }
            return buildInfo(username, "https://fragment.com/username/" + username);
        }
        if (req.collectible instanceof TL_fragment.TL_inputCollectiblePhone) {
            final String phone = digitsOf(((TL_fragment.TL_inputCollectiblePhone) req.collectible).phone);
            if (phone.isEmpty() || !isFabricatedPhone(phone)) {
                return null;
            }
            return buildInfo(phone, "https://fragment.com/number/" + phone);
        }
        return null;
    }

    /**
     * Whether this name is fabricated — by the account holding the setting, or by a peer whose fake
     * identity the sync pulled and injected.
     *
     * <p>The peer half is what stops {@code COLLECTIBLE_NOT_FOUND}. A collectible username injected
     * onto a peer by {@link SovietGramProfileSync#applyRemote(TLRPC.User)} looks exactly like a real
     * one, so tapping it asks Fragment to describe a name Fragment has of course never sold, and the
     * sheet dies with that error instead of showing what the peer configured. Only the owner used to
     * be answered here, which is precisely the case that never goes out to the network anyway.
     */
    private static boolean isFabricatedUsername(String username) {
        if (NekoConfig.serverFragment.Bool() && containsIgnoreCase(usernames(), username)) {
            return true;
        }
        return SovietGramProfileSync.hasFakeUsername(username);
    }

    /** The same, for a number; compared as bare digits, the shape both sides store. */
    private static boolean isFabricatedPhone(String phone) {
        if (NekoConfig.serverFragment.Bool() && phone.equals(phone())) {
            return true;
        }
        return SovietGramProfileSync.hasFakePhone(phone);
    }

    private static TL_fragment.TL_collectibleInfo buildInfo(String key, String url) {
        final TL_fragment.TL_collectibleInfo info = new TL_fragment.TL_collectibleInfo();
        final long nanotons = priceNanotons(key);
        info.crypto_currency = "TON";
        info.crypto_amount = nanotons;
        info.currency = "USD";
        info.amount = Math.round(((double) nanotons / NANOTONS_IN_TON) * TON_USD_RATE * CENTS_IN_USD);
        info.purchase_date = purchaseDate(key);
        info.url = url;
        fakeMarkers.put(info, Boolean.TRUE);
        return info;
    }

    /**
     * Identity-keyed rather than a field on {@code TL_collectibleInfo} itself, since that class is
     * generated TL and shared with the real Fragment response. A weak map lets the marker vanish
     * with the object instead of leaking one entry per sheet ever opened.
     */
    private static final java.util.Map<TL_fragment.TL_collectibleInfo, Boolean> fakeMarkers =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /** True when {@code info} was fabricated locally by {@link #buildInfo}, not fetched from Fragment. */
    public static boolean isFake(@Nullable TL_fragment.TL_collectibleInfo info) {
        return info != null && fakeMarkers.containsKey(info);
    }

    private static long priceNanotons(String key) {
        final int length = Math.max(4, Math.min(32, key.length()));
        final double base = PRICE_SCALE / Math.pow(length, PRICE_EXPONENT);
        // 0.75x .. 1.25x, so two handles of the same length do not carry the same price tag.
        final double spread = 0.75 + (Math.abs(key.hashCode() % 1000) / 1000.0) * 0.5;
        final double ton = Math.max(1.0, Math.round(base * spread * 10.0) / 10.0);
        return (long) (ton * NANOTONS_IN_TON);
    }

    private static int purchaseDate(String key) {
        final int now = (int) (System.currentTimeMillis() / 1000L);
        final int date = (int) (now - Math.abs(key.hashCode()) % THREE_YEARS_SECONDS);
        return Math.max(FRAGMENT_LAUNCH_DATE, date);
    }

    // ===== settings =====

    /** Configured phone as bare digits, which is the shape the client expects in TLRPC.User. */
    public static String phone() {
        return digitsOf(NekoConfig.serverFragmentPhone.String());
    }

    /** Configured collectible usernames, without the @ and without duplicates. */
    public static List<String> usernames() {
        return parseUsernames(NekoConfig.serverFragmentUsernames.String());
    }

    /**
     * The same two, for a specific account rather than for whichever one is live. Used by the push:
     * every logged-in account publishes its own Fragment identity, and all but one of them are read
     * out of a stored snapshot. See {@link SovietGramAccountScope}.
     */
    public static String phone(int account) {
        return digitsOf(SovietGramAccountScope.str(account, NekoConfig.serverFragmentPhone));
    }

    public static List<String> usernames(int account) {
        return parseUsernames(SovietGramAccountScope.str(account, NekoConfig.serverFragmentUsernames));
    }

    /**
     * Normaliser for the usernames row: same parsing as {@link #usernames()}, written back as a
     * plain comma separated list so the row shows what was actually understood.
     */
    public static String sanitizeUsernames(String input) {
        return String.join(", ", parseUsernames(input));
    }

    /** Normaliser for the phone row: digits only, at most the 15 an E.164 number can hold. */
    public static String sanitizePhone(String input) {
        final String digits = digitsOf(input);
        return digits.length() > 15 ? digits.substring(0, 15) : digits;
    }

    private static List<String> parseUsernames(String raw) {
        final LinkedHashSet<String> names = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split("[,;\\s]+")) {
                final String name = clean(part);
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return new ArrayList<>(names);
    }

    // ===== small helpers =====

    private static String clean(String name) {
        if (name == null) {
            return "";
        }
        return name.trim().replaceAll("^@+", "").replaceAll("[^A-Za-z0-9_]", "");
    }

    private static String digitsOf(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    private static boolean containsIgnoreCase(List<String> names, String name) {
        for (String candidate : names) {
            if (candidate.equalsIgnoreCase(name)) {
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

    /**
     * The entries themselves are handed to the UI, which never edits them, but the list is rebuilt
     * on every apply, so it must not be shared with the backup.
     */
    private static ArrayList<TLRPC.TL_username> copyOf(@Nullable List<TLRPC.TL_username> source) {
        final ArrayList<TLRPC.TL_username> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (TLRPC.TL_username username : source) {
            if (username == null) {
                continue;
            }
            final TLRPC.TL_username entry = new TLRPC.TL_username();
            entry.username = username.username;
            entry.editable = username.editable;
            entry.active = username.active;
            entry.flags = username.flags;
            copy.add(entry);
        }
        return copy;
    }
}
