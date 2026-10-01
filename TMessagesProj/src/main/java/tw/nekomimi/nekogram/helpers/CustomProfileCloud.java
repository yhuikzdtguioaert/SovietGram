package tw.nekomimi.nekogram.helpers;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

/** Owner-only styling backup, separate from the appearance sent to other users. */
public final class CustomProfileCloud {
    private static final Set<Long> ready = new HashSet<>();
    private static final Set<Long> loading = new HashSet<>();
    private static final Set<Long> pushing = new HashSet<>();
    private static final Map<Long, String> saved = new HashMap<>();
    /** Consecutive failed first reads of the backup, per account. */
    private static final Map<Long, Integer> failures = new HashMap<>();
    /** After this many failed reads the public look is published anyway; the backup stays untouched. */
    private static final int GIVE_UP_AFTER = 4;
    private static final ConfigItem[] ACTIVE = {NekoConfig.customProfileFrameActiveProject};
    private CustomProfileCloud() { }

    public static boolean isReady(int account) {
        return ready.contains(SovietGramTokenStore.ownId(account));
    }

    /**
     * True once the backup could not be read several times in a row. The public look must not wait
     * on a backup route that is down, so the sync publishes it anyway; only the backup itself keeps
     * waiting, because writing it before it has been read is what could overwrite a real one.
     */
    public static boolean unreachable(int account) {
        return failures.getOrDefault(SovietGramTokenStore.ownId(account), 0) >= GIVE_UP_AFTER;
    }

    /** Read before the first write so a fresh installation cannot overwrite its cloud backup. */
    public static void reconcile(int account, Runnable after) {
        long owner = SovietGramTokenStore.ownId(account);
        if (owner <= 0 || !SovietGramApiClient.isReady(account)) return;
        if (ready.contains(owner)) { if (after != null) after.run(); return; }
        if (!loading.add(owner)) return;
        SovietGramApiClient.get(account, "/v1/profile-settings", (body, error) -> {
            loading.remove(owner);
            if (owner != SovietGramTokenStore.ownId(account)) return;
            if (body == null) {
                FileLog.e("CustomProfileCloud: reconciliation failed: " + error);
                final int count = failures.getOrDefault(owner, 0) + 1;
                failures.put(owner, count);
                // Let the public look go out once the backup has stayed unreachable, then keep trying
                // for the backup itself, gently.
                if (count == GIVE_UP_AFTER) SovietGramSync.scheduleProfilePush();
                AndroidUtilities.runOnUIThread(() -> reconcile(account, after), count < GIVE_UP_AFTER ? 12000 : 60000);
                return;
            }
            failures.remove(owner);
            ready.add(owner);
            if (!CustomProfileHelper.hasLocalProfileState(account)) apply(account, body);
            if (after != null) after.run();
        });
    }

    public static void restore(int account, SovietGramApiClient.Callback done) {
        long owner = SovietGramTokenStore.ownId(account);
        SovietGramApiClient.get(account, "/v1/profile-settings", (body, error) -> {
            if (owner != SovietGramTokenStore.ownId(account)) {
                // The screen is waiting on an answer: tell it, rather than leaving it hanging.
                done.onResult(null, "account_changed");
                return;
            }
            if (body != null && body.optJSONObject("appearance") != null) {
                ready.add(owner);
                saved.remove(owner);
                // Reported once the frame library has been read too, so the screen that asked is
                // redrawn with the frames this account has on the server, not the ones it had before.
                apply(account, body, true, () -> done.onResult(body, error));
                SovietGramSync.scheduleProfilePush();
            } else {
                done.onResult(body, error);
            }
        });
    }

    private static void apply(int account, JSONObject body) {
        apply(account, body, false, null);
    }

    private static void apply(int account, JSONObject body, boolean explicit, Runnable after) {
        JSONObject appearance = body.optJSONObject("appearance");
        if (appearance == null) {
            if (after != null) after.run();
            return;
        }
        SovietGramAccountScope.restoreItems(account, appearance, CustomProfileHelper.portableItems());
        try {
            JSONObject project = new JSONObject().put(NekoConfig.customProfileFrameActiveProject.getKey(),
                    body.optString("active_project", ""));
            SovietGramAccountScope.restoreItems(account, project, ACTIVE);
        } catch (Exception e) { FileLog.e(e); }
        final boolean live = account == UserConfig.selectedAccount && SovietGramAccountScope.isLive(account);
        if (explicit && live) {
            // A file picked on this phone wins over the restored descriptor when it is drawn. After an
            // explicit restore the server's picture is the one that was asked for.
            releaseLocalPath(NekoConfig.customProfileBannerPath, NekoConfig.customProfileBannerMedia);
            releaseLocalPath(NekoConfig.customProfileBackgroundPath, NekoConfig.customProfileBackgroundMedia);
            releaseLocalPath(NekoConfig.customProfileNameFontPath, NekoConfig.customProfileNameFontMedia);
            releaseLocalPath(NekoConfig.customProfileThoughtFontPath, NekoConfig.customProfileThoughtFontMedia);
        }
        if (live) {
            CustomProfileHelper.onSettingsChanged();
            FrameProjects.refresh(after);
        } else if (after != null) {
            after.run();
        }
    }

    private static void releaseLocalPath(ConfigItem path, ConfigItem descriptor) {
        if (!android.text.TextUtils.isEmpty(descriptor.String())) {
            path.setConfigString("");
        }
    }

    public static void backup(int account) {
        long owner = SovietGramTokenStore.ownId(account);
        if (owner <= 0 || !ready.contains(owner)) return;
        try {
            JSONObject body = new JSONObject().put("appearance", CustomProfileHelper.exportProfileJson(account))
                    .put("active_project", SovietGramAccountScope.str(account, NekoConfig.customProfileFrameActiveProject));
            String serialized = body.toString();
            if (serialized.equals(saved.get(owner))) return;
            if (!pushing.add(owner)) return;
            SovietGramApiClient.putSigned(account, "/v1/profile-settings", body, (result, error) -> {
                pushing.remove(owner);
                if (result != null) {
                    saved.put(owner, serialized);
                    // Catch an edit made while this write was in flight without sending overlapping writes.
                    SovietGramSync.scheduleProfilePush();
                }
                else {
                    FileLog.e("CustomProfileCloud: backup failed: " + error);
                    AndroidUtilities.runOnUIThread(SovietGramSync::scheduleProfilePush, 12000);
                }
            });
        } catch (Exception e) { FileLog.e(e); }
    }
}
