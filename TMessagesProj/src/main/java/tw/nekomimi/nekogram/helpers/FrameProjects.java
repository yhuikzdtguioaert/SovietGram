package tw.nekomimi.nekogram.helpers;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.frame.FrameGraph;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphStore;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphBuild;
import tw.nekomimi.nekogram.helpers.frame.FrameSpec;
import tw.nekomimi.nekogram.helpers.frame.FrameBlanks;
import tw.nekomimi.nekogram.helpers.remote.ApiServersHelper;

/** Local named projects with a separate, account-authenticated server copy. */
public final class FrameProjects {
    public static final int LIMIT = 30;
    private static final Map<String, Runnable> pending = new HashMap<>();
    private static final Map<String, String> uploadedTextures = new HashMap<>();
    private static final Set<String> frameUploads = new HashSet<>();
    private static final Map<Long, Long> lastRefresh = new HashMap<>();
    private static final Set<Long> refreshing = new HashSet<>();
    /** Consecutive failed round trips, UI thread only; the retry chain stops after {@link #MAX_FAILURES}. */
    private static int failures;
    private static final int MAX_FAILURES = 5;

    /**
     * A failed route is retried through a profile push, which comes straight back here. Without a
     * budget a route that stays down (offline, or a server that refuses the body) is hit every 12s forever.
     */
    private static void retryAfterFailure(long owner) {
        lastRefresh.remove(owner);
        if (++failures <= MAX_FAILURES) {
            AndroidUtilities.runOnUIThread(SovietGramSync::scheduleProfilePush, 12000);
        }
    }

    /** Publish textures even when the active frame was not saved as a named project. */
    public static void ensureFramePublished() {
        final int account = UserConfig.selectedAccount;
        if (!SovietGramAccountScope.isLive(account) || !SovietGramApiClient.isReady(account)) return;
        final String graphText = NekoConfig.customProfileFrameGraph.String();
        final String specText = NekoConfig.customProfileFrameSpec.String();
        // All publishable local textures live here; a remote-only frame needs no JSON parsing.
        if (!graphText.contains("frame-assets") && !specText.contains("frame-assets")) return;
        FrameSpec spec = FrameSpec.parse(specText);
        FrameGraph graph = FrameGraph.parse(graphText);
        Set<String> assets = new HashSet<>(spec.assets());
        assets.addAll(graph.sources());
        boolean local = false;
        for (String source : assets) {
            if (source != null && !source.isEmpty() && !FrameBlanks.is(source)
                    && !source.startsWith("https://") && !source.startsWith("http://")) {
                local = true;
                break;
            }
        }
        if (!local) return;
        final long owner = SovietGramTokenStore.ownId(account);
        final String key = owner + ":" + graphText + ":" + specText;
        if (!frameUploads.add(key)) return;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                Map<String, String> urls = textureUrls(account, assets);
                String remoteGraph = graph.swap(urls).encode();
                String remoteSpec = FrameSpec.swap(spec, urls).encode();
                AndroidUtilities.runOnUIThread(() -> {
                    frameUploads.remove(key);
                    if (owner != SovietGramTokenStore.ownId(account)
                            || account != UserConfig.selectedAccount || !SovietGramAccountScope.isLive(account)
                            || !graphText.equals(NekoConfig.customProfileFrameGraph.String())
                            || !specText.equals(NekoConfig.customProfileFrameSpec.String())) return;
                    NekoConfig.customProfileFrameGraph.setConfigString(remoteGraph);
                    NekoConfig.customProfileFrameSpec.setConfigString(remoteSpec);
                    onFrameChanged(remoteGraph, remoteSpec);
                    CustomProfileHelper.onSettingsChanged();
                });
            } catch (Exception e) {
                FileLog.e("Frame texture publishing failed: " + e.getMessage());
                AndroidUtilities.runOnUIThread(() -> frameUploads.remove(key));
            }
        });
    }

    private static Map<String, String> textureUrls(int account, Set<String> assets) throws Exception {
        Map<String, String> remote = new HashMap<>();
        File root = new File(org.telegram.messenger.ApplicationLoader.getFilesDirFixed(), "frame-assets").getCanonicalFile();
        for (String source : assets) {
            if (source == null || source.isEmpty() || FrameBlanks.is(source)
                    || source.startsWith("https://") || source.startsWith("http://")) continue;
            File file = source.startsWith("file:") ? new File(java.net.URI.create(source)) : new File(source);
            file = file.getCanonicalFile();
            if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile()) {
                throw new IllegalArgumentException("Frame texture is not in the app library");
            }
            String key = account + ":" + file.getPath() + ":" + file.lastModified() + ":" + file.length();
            String url = uploadedTextures.get(key);
            if (url == null) {
                JSONObject uploaded = SovietGramApiClient.uploadMediaFile(account, "frame", file);
                String path = uploaded.optString("path", "");
                if (!path.startsWith("/v1/media/")) throw new IllegalStateException("Frame texture upload failed");
                url = ApiServersHelper.baseUrl() + path;
                if (uploadedTextures.size() > 256) uploadedTextures.clear();
                uploadedTextures.put(key, url);
            }
            remote.put(source, url);
        }
        return remote;
    }

    public static final class Project {
        public String id;
        public String name;
        public String graph;
        public String spec;
        public long updated;

        private Project(String id, String name, String graph, String spec, long updated) {
            this.id = id;
            this.name = name;
            this.graph = graph;
            this.spec = spec;
            this.updated = updated;
        }
    }

    private FrameProjects() { }

    public static List<Project> list() {
        return list(UserConfig.selectedAccount);
    }

    private static List<Project> list(int account) {
        final List<Project> result = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(SovietGramAccountScope.str(account, NekoConfig.customProfileFrameProjects));
            for (int i = 0; i < array.length() && i < LIMIT; i++) {
                final JSONObject row = array.optJSONObject(i);
                if (row == null) continue;
                final String id = row.optString("id", "");
                if (id.isEmpty()) continue;
                result.add(new Project(id, row.optString("name", "Frame"),
                        row.optString("graph", ""), row.optString("spec", ""),
                        row.optLong("updated", 0)));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    @Nullable
    public static Project create(String name) {
        final List<Project> projects = list();
        if (projects.size() >= LIMIT) return null;
        final Project project = new Project(UUID.randomUUID().toString(), trimName(name),
                FrameGraphStore.graph().encode(), FrameGraphStore.spec().encode(),
                System.currentTimeMillis());
        projects.add(0, project);
        store(projects);
        push(UserConfig.selectedAccount, project);
        return project;
    }

    @Nullable
    public static Project importDocument(String name, String graph, String spec) {
        final List<Project> projects = list();
        if (projects.size() >= LIMIT) return null;
        final FrameSpec parsed = FrameSpec.parse(spec);
        if (parsed.isEmpty()) return null;
        FrameGraph parsedGraph = FrameGraph.parse(graph);
        if (parsedGraph.count() == 0) parsedGraph = FrameGraphBuild.of(parsed);
        final Project project = new Project(UUID.randomUUID().toString(), trimName(name),
                parsedGraph.encode(), parsed.encode(), System.currentTimeMillis());
        projects.add(0, project);
        store(projects);
        push(UserConfig.selectedAccount, project);
        return project;
    }

    public static boolean open(String id) {
        for (Project project : list()) {
            if (!project.id.equals(id)) continue;
            NekoConfig.customProfileFrameActiveProject.setConfigString(id);
            FrameGraph graph = FrameGraph.parse(project.graph);
            if (graph.count() == 0) graph = FrameGraphBuild.of(FrameSpec.parse(project.spec));
            FrameGraphStore.save(graph);
            return true;
        }
        return false;
    }

    @Nullable
    public static Project duplicate(String id) {
        final List<Project> projects = list();
        if (projects.size() >= LIMIT) return null;
        for (Project original : projects) {
            if (!original.id.equals(id)) continue;
            final Project copy = new Project(UUID.randomUUID().toString(), trimName(original.name + " copy"),
                    original.graph, original.spec, System.currentTimeMillis());
            projects.add(0, copy);
            store(projects);
            push(UserConfig.selectedAccount, copy);
            return copy;
        }
        return null;
    }

    public static void rename(String id, String name) {
        final List<Project> projects = list();
        for (Project project : projects) {
            if (!project.id.equals(id)) continue;
            project.name = trimName(name);
            project.updated = System.currentTimeMillis();
            store(projects);
            push(UserConfig.selectedAccount, project);
            return;
        }
    }

    public static void delete(String id) {
        final List<Project> projects = list();
        projects.removeIf(project -> project.id.equals(id));
        store(projects);
        if (id.equals(NekoConfig.customProfileFrameActiveProject.String())) detach();
        try {
            final JSONArray deletes = new JSONArray(NekoConfig.customProfileFrameProjectDeletes.String());
            deletes.put(id);
            NekoConfig.customProfileFrameProjectDeletes.setConfigString(deletes.toString());
            SovietGramAccountScope.saveLive();
        } catch (Exception e) {
            FileLog.e(e);
        }
        flushDeletes(UserConfig.selectedAccount);
        SovietGramSync.scheduleProfilePush();
    }

    public static void detach() {
        NekoConfig.customProfileFrameActiveProject.setConfigString("");
    }

    /** The Frame Studio calls this after each edit; network writes are coalesced. */
    public static void onFrameChanged(String graph, String spec) {
        final String active = NekoConfig.customProfileFrameActiveProject.String();
        if (active.isEmpty()) return;
        final List<Project> projects = list();
        for (Project project : projects) {
            if (!active.equals(project.id)) continue;
            if (graph.equals(project.graph) && spec.equals(project.spec)) return;
            project.graph = graph;
            project.spec = spec;
            project.updated = System.currentTimeMillis();
            store(projects);
            final int account = UserConfig.selectedAccount;
            final String queueKey = account + ":" + active;
            final Runnable previous = pending.remove(queueKey);
            if (previous != null) AndroidUtilities.cancelRunOnUIThread(previous);
            final Runnable next = () -> {
                pending.remove(queueKey);
                push(account, project);
            };
            pending.put(queueKey, next);
            AndroidUtilities.runOnUIThread(next, 1500);
            return;
        }
    }

    public static void refresh(@Nullable Runnable finished) {
        final int account = UserConfig.selectedAccount;
        refresh(account, finished);
    }

    /** Reconcile the signed-in account's complete project library, also after offline edits. */
    public static void synchronize(int account) {
        long owner = SovietGramTokenStore.ownId(account);
        long now = android.os.SystemClock.elapsedRealtime();
        if (owner <= 0 || now - lastRefresh.getOrDefault(owner, -60000L) < 60000) return;
        refresh(account, null);
    }

    private static void refresh(int account, @Nullable Runnable finished) {
        final long owner = SovietGramTokenStore.ownId(account);
        if (!SovietGramApiClient.isReady(account)) {
            if (finished != null) finished.run();
            return;
        }
        if (!refreshing.add(owner)) { if (finished != null) finished.run(); return; }
        lastRefresh.put(owner, android.os.SystemClock.elapsedRealtime());
        final Set<String> knownDeleted = deletedIds(account);
        flushDeletes(account);
        SovietGramApiClient.get(account, "/v1/frame-projects", (body, error) -> {
            refreshing.remove(owner);
            if (owner != SovietGramTokenStore.ownId(account)) {
                if (finished != null) finished.run();
                return;
            }
            if (body == null) {
                retryAfterFailure(owner);
                if (error != null) FileLog.e("FrameProjects: load failed: " + error);
                if (finished != null) finished.run();
                return;
            }
            failures = 0;
            final List<Project> local = list(account);
            final JSONArray remote = body.optJSONArray("projects");
            final Set<String> deleted = knownDeleted;
            deleted.addAll(deletedIds(account));
            final JSONArray tombstones = body.optJSONArray("deleted");
            for (int i = 0; tombstones != null && i < tombstones.length(); i++) deleted.add(tombstones.optString(i));
            local.removeIf(project -> deleted.contains(project.id));
            String active = SovietGramAccountScope.str(account, NekoConfig.customProfileFrameActiveProject);
            if (deleted.contains(active)) {
                try { SovietGramAccountScope.restoreItems(account,
                        new JSONObject().put(NekoConfig.customProfileFrameActiveProject.getKey(), ""),
                        new tw.nekomimi.nekogram.config.ConfigItem[]{NekoConfig.customProfileFrameActiveProject}); }
                catch (Exception e) { FileLog.e(e); }
            }
            final Set<String> seen = new HashSet<>();
            for (int i = 0; remote != null && i < remote.length(); i++) {
                final JSONObject row = remote.optJSONObject(i);
                if (row == null) continue;
                final String id = row.optString("project_id", "");
                if (id.isEmpty() || deleted.contains(id)) continue;
                seen.add(id);
                final long updated = row.optLong("updated_ms",
                        parseUtc(row.optString("updated_at", "")));
                Project old = null;
                for (Project project : local) {
                    if (project.id.equals(id)) { old = project; break; }
                }
                if (old == null && local.size() < LIMIT) {
                    local.add(new Project(id, row.optString("name", "Frame"),
                            row.optString("graph", ""), row.optString("spec", ""), updated));
                } else if (old != null && (updated > old.updated
                        || (updated == old.updated
                        && (!row.optString("spec", "").equals(old.spec)
                        || !row.optString("graph", "").equals(old.graph)
                        || !row.optString("name", "").equals(old.name))))) {
                    old.name = row.optString("name", old.name);
                    old.graph = row.optString("graph", old.graph);
                    old.spec = row.optString("spec", old.spec);
                    old.updated = updated;
                } else if (old != null && old.updated > updated + 1000) {
                    push(account, old);
                }
            }
            for (Project project : local) {
                if (!seen.contains(project.id)) push(account, project);
            }
            store(account, local);
            if (finished != null) finished.run();
        });
    }

    private static Set<String> deletedIds() {
        return deletedIds(UserConfig.selectedAccount);
    }

    private static Set<String> deletedIds(int account) {
        final Set<String> result = new HashSet<>();
        try {
            final JSONArray array = new JSONArray(SovietGramAccountScope.str(account, NekoConfig.customProfileFrameProjectDeletes));
            for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static void flushDeletes(int account) {
        if (!SovietGramApiClient.isReady(account)) return;
        final long owner = SovietGramTokenStore.ownId(account);
        for (String id : deletedIds(account)) {
            SovietGramApiClient.deleteSigned(account, "/v1/frame-projects/" + id, (body, error) -> {
                if (owner != SovietGramTokenStore.ownId(account)) return;
                if (body == null) {
                    if (error != null) FileLog.e("FrameProjects: delete failed: " + error);
                    retryAfterFailure(owner);
                    return;
                }
                failures = 0;
                final JSONArray remaining = new JSONArray();
                for (String item : deletedIds(account)) if (!id.equals(item)) remaining.put(item);
                try { SovietGramAccountScope.restoreItems(account,
                        new JSONObject().put(NekoConfig.customProfileFrameProjectDeletes.getKey(), remaining.toString()),
                        new tw.nekomimi.nekogram.config.ConfigItem[]{NekoConfig.customProfileFrameProjectDeletes}); }
                catch (Exception e) { FileLog.e(e); }
            });
        }
    }

    private static long parseUtc(String value) {
        try {
            final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            final Date date = format.parse(value);
            return date == null ? 0 : date.getTime();
        } catch (Exception ignore) {
            return 0;
        }
    }

    private static String trimName(String name) {
        final String cleaned = name.trim();
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }

    private static void store(List<Project> projects) {
        store(UserConfig.selectedAccount, projects);
    }

    private static void store(int account, List<Project> projects) {
        final JSONArray array = new JSONArray();
        for (Project project : projects) {
            try {
                array.put(new JSONObject().put("id", project.id).put("name", project.name)
                        .put("graph", project.graph).put("spec", project.spec)
                        .put("updated", project.updated));
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (SovietGramAccountScope.isLive(account)) {
            NekoConfig.customProfileFrameProjects.setConfigString(array.toString());
            SovietGramAccountScope.saveLive();
        } else {
            try { SovietGramAccountScope.restoreItems(account,
                    new JSONObject().put(NekoConfig.customProfileFrameProjects.getKey(), array.toString()),
                    new tw.nekomimi.nekogram.config.ConfigItem[]{NekoConfig.customProfileFrameProjects}); }
            catch (Exception e) { FileLog.e(e); }
        }
    }

    private static void push(int account, Project project) {
        if (!SovietGramApiClient.isReady(account)) return;
        final long owner = SovietGramTokenStore.ownId(account);
        final String id = project.id;
        final String name = project.name;
        final String graphText = project.graph;
        final String specText = project.spec;
        final long updated = project.updated;
        Utilities.globalQueue.postRunnable(() -> {
            if (owner != SovietGramTokenStore.ownId(account)) return;
            try {
                final FrameGraph graph = FrameGraph.parse(graphText);
                final FrameSpec spec = FrameSpec.parse(specText);
                final Set<String> assets = new HashSet<>(spec.assets());
                assets.addAll(graph.sources());
                final Map<String, String> remote = textureUrls(account, assets);
                final String remoteGraph = graph.swap(remote).encode();
                final String remoteSpec = FrameSpec.swap(spec, remote).encode();
                final JSONObject body = new JSONObject().put("name", name)
                        .put("graph", remoteGraph)
                        .put("spec", remoteSpec)
                        .put("updated_ms", updated);
                SovietGramApiClient.putSigned(account, "/v1/frame-projects/" + id,
                        body, (result, error) -> {
                            if (error != null) FileLog.e("FrameProjects: save failed: " + error);
                            if (result == null && owner == SovietGramTokenStore.ownId(account)) {
                                retryAfterFailure(owner);
                            } else if (result != null) {
                                failures = 0;
                            }
                            if (result == null || remote.isEmpty() || account != UserConfig.selectedAccount
                                    || !id.equals(NekoConfig.customProfileFrameActiveProject.String())) return;
                            final List<Project> projects = list();
                            for (Project current : projects) {
                                if (id.equals(current.id) && current.updated == updated) {
                                    current.graph = remoteGraph;
                                    current.spec = remoteSpec;
                                    store(projects);
                                    NekoConfig.customProfileFrameGraph.setConfigString(remoteGraph);
                                    NekoConfig.customProfileFrameSpec.setConfigString(remoteSpec);
                                    CustomProfileHelper.onSettingsChanged();
                                    break;
                                }
                            }
                        });
            } catch (Exception e) {
                FileLog.e("FrameProjects: save failed: " + e.getMessage());
                AndroidUtilities.runOnUIThread(() -> retryAfterFailure(owner));
            }
        });
    }
}
