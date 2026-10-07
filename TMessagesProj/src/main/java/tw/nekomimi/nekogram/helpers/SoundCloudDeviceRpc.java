package tw.nekomimi.nekogram.helpers;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.UserConfig;

import java.util.HashMap;
import java.util.Map;

/** Device-local consent is never imported from profile JSON/cloud/workshop/provider accounts.
 * One explicitly selected Telegram owner may publish. Switching accounts turns consent off. */
public final class SoundCloudDeviceRpc {
    private static final SoundCloudRpcPolicy POLICY = new SoundCloudRpcPolicy();
    private static final Map<Long, SovietGramApiClient.MusicRpcAuth> AUTH = new HashMap<>();
    private static SoundCloudMediaSessionService source;
    private static volatile long allowedGeneration = -1;
    private static final Runnable TICK = SoundCloudDeviceRpc::refresh;

    private SoundCloudDeviceRpc() { }
    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext.getSharedPreferences("soundcloud_device_rpc", Context.MODE_PRIVATE);
    }
    private static long optedOwner() { return preferences().getLong("publishing_owner", 0); }
    public static boolean isEnabled(int account) {
        long owner = UserConfig.getInstance(account).getClientUserId();
        return owner > 0 && optedOwner() == owner;
    }
    public static boolean permissionGranted() {
        try {
            ComponentName component = new ComponentName(ApplicationLoader.applicationContext, SoundCloudMediaSessionService.class);
            String listeners = Settings.Secure.getString(ApplicationLoader.applicationContext.getContentResolver(), "enabled_notification_listeners");
            if (listeners != null) for (String listener : listeners.split(":")) {
                if (listener.equals(component.flattenToString()) || listener.equals(component.flattenToShortString())) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }
    /** Called only after the UI disclosure/confirmation. Android permission is granted by the user. */
    public static boolean setEnabled(int account, boolean enabled) {
        long owner = UserConfig.getInstance(account).getClientUserId();
        if (account != UserConfig.selectedAccount || owner <= 0) return false;
        if (enabled && (!permissionGranted() || !SovietGramApiClient.isReady(account))) return false;
        if (!enabled && optedOwner() != owner) return false;
        if (!enabled) allowedGeneration = -1;
        preferences().edit().putLong("publishing_owner", enabled ? owner : 0).apply();
        if (enabled) {
            SovietGramApiClient.MusicRpcAuth auth = SovietGramApiClient.captureMusicRpcAuth(account, owner);
            if (auth == null) { preferences().edit().putLong("publishing_owner", 0).apply(); return false; }
            AUTH.put(owner, auth);
        }
        CustomProfileIntegrations.clearCache();
        refresh();
        return true;
    }
    public static void onAccountChanging(int incomingAccount) {
        long consent = optedOwner();
        if (consent > 0 && consent != UserConfig.getInstance(incomingAccount).getClientUserId()) disable();
    }
    public static void onLogout(int account) {
        if (isEnabled(account)) disable();
    }
    private static void disable() {
        allowedGeneration = -1; // immediately closes worker authorization, before prefs/native swaps.
        preferences().edit().putLong("publishing_owner", 0).apply();
        CustomProfileIntegrations.clearCache();
        refresh();
    }
    static void attach(SoundCloudMediaSessionService service) {
        if (source != null && source != service) source.stopSource();
        source = service;
        refresh();
    }
    static void detach(SoundCloudMediaSessionService service) {
        service.stopSource();
        if (source != service) return;
        source = null;
        disable(); // listener loss requires explicit consent again, never silently resumes.
    }
    static void permissionLost() { disable(); }
    static boolean isCurrent(long generation) {
        return generation == allowedGeneration && permissionGranted() && isEnabled(UserConfig.selectedAccount);
    }
    static void offer(long generation, SoundCloudRpcPolicy.Snapshot snapshot) {
        if (!isCurrent(generation)) return;
        POLICY.offer(generation, SoundCloudRpcPolicy.PACKAGE, snapshot);
        drain();
    }
    /** One cheap watchdog per second while opted in/clearing; metadata read only on callbacks/heartbeat. */
    public static void refresh() {
        AndroidUtilities.cancelRunOnUIThread(TICK);
        int account = UserConfig.selectedAccount;
        long owner = UserConfig.getInstance(account).getClientUserId();
        long consent = optedOwner();
        boolean permission = permissionGranted();
        if (consent > 0 && (consent != owner || !permission)) {
            preferences().edit().putLong("publishing_owner", 0).apply();
            consent = 0;
            CustomProfileIntegrations.clearCache();
        }
        boolean enabled = owner > 0 && consent == owner && permission && source != null && SovietGramApiClient.isReady(account);
        if (enabled) {
            SovietGramApiClient.MusicRpcAuth auth = AUTH.get(owner);
            if (auth == null) {
                auth = SovietGramApiClient.captureMusicRpcAuth(account, owner);
                if (auth != null) AUTH.put(owner, auth);
            } else if (!auth.isCurrentIdentity()) {
                preferences().edit().putLong("publishing_owner", 0).apply();
                enabled = false; // token/server/slot replacement needs renewed explicit consent.
            }
            enabled &= auth != null;
        }
        POLICY.configure(account, owner, enabled, permission);
        allowedGeneration = enabled ? POLICY.generation() : -1;
        if (source != null) {
            if (enabled) {
                source.startSource(POLICY.generation());
                source.heartbeat(SystemClock.elapsedRealtime());
            } else source.stopSource();
        }
        drain();
        long consentOwner = optedOwner();
        AUTH.entrySet().removeIf(entry -> entry.getKey() != consentOwner && !POLICY.needsOwner(entry.getKey()));
        if (POLICY.hasWork() || optedOwner() > 0) AndroidUtilities.runOnUIThread(TICK, 1000L);
    }
    private static void drain() {
        SoundCloudRpcPolicy.Request request = POLICY.next(SystemClock.elapsedRealtime());
        if (request == null) return;
        SovietGramApiClient.MusicRpcAuth auth = AUTH.get(request.owner);
        if (auth == null) {
            // No POST can be sent without a captured auth. A killed process is covered by server TTL.
            POLICY.complete(request, request.clear, SystemClock.elapsedRealtime());
            return;
        }
        auth.write(request, () -> request.generation == allowedGeneration
                && UserConfig.selectedAccount == request.account
                && UserConfig.getInstance(request.account).getClientUserId() == request.owner
                && permissionGranted() && auth.isCurrentIdentity(), (body, error) -> {
            boolean ok = error == null && body != null && body.optBoolean("ok");
            POLICY.complete(request, ok, SystemClock.elapsedRealtime());
            if (ok && request.clear && optedOwner() != request.owner) AUTH.remove(request.owner);
            if (ok) CustomProfileIntegrations.clearCache();
            drain(); // DELETE follows an already-running POST before any new-owner publication.
        });
    }
}
