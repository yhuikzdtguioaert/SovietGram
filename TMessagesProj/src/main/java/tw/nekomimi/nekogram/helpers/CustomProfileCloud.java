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
    private static final ConfigItem[] ACTIVE = {NekoConfig.customProfileFrameActiveProject};
    private CustomProfileCloud() { }

    public static boolean isReady(int account) {
        return ready.contains(SovietGramTokenStore.ownId(account));
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
                AndroidUtilities.runOnUIThread(() -> reconcile(account, after), 12000);
                return;
            }
            ready.add(owner);
            if (!CustomProfileHelper.hasLocalProfileState(account)) apply(account, body);
            if (after != null) after.run();
        });
    }

    public static void restore(int account, SovietGramApiClient.Callback done) {
        long owner = SovietGramTokenStore.ownId(account);
        SovietGramApiClient.get(account, "/v1/profile-settings", (body, error) -> {
            if (owner != SovietGramTokenStore.ownId(account)) return;
            if (body != null && body.optJSONObject("appearance") != null) {
                ready.add(owner);
                apply(account, body);
                saved.remove(owner);
                SovietGramSync.scheduleProfilePush();
            }
            done.onResult(body, error);
        });
    }

    private static void apply(int account, JSONObject body) {
        JSONObject appearance = body.optJSONObject("appearance");
        if (appearance == null) return;
        SovietGramAccountScope.restoreItems(account, appearance, CustomProfileHelper.portableItems());
        try {
            JSONObject project = new JSONObject().put(NekoConfig.customProfileFrameActiveProject.getKey(),
                    body.optString("active_project", ""));
            SovietGramAccountScope.restoreItems(account, project, ACTIVE);
        } catch (Exception e) { FileLog.e(e); }
        if (account == UserConfig.selectedAccount && SovietGramAccountScope.isLive(account)) {
            CustomProfileHelper.onSettingsChanged();
            FrameProjects.refresh(null);
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
