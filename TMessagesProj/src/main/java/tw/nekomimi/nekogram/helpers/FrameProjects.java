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
        final List<Project> result = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(NekoConfig.customProfileFrameProjects.String());
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
        if (!SovietGramApiClient.isReady(account)) {
            if (finished != null) finished.run();
            return;
        }
        flushDeletes(account);
        SovietGramApiClient.get(account, "/v1/frame-projects", (body, error) -> {
            if (account != UserConfig.selectedAccount) return;
            if (body == null) {
                if (error != null) FileLog.e("FrameProjects: load failed: " + error);
                if (finished != null) finished.run();
                return;
            }
            final List<Project> local = list();
            final JSONArray remote = body.optJSONArray("projects");
            final Set<String> deleted = deletedIds();
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
                        && !row.optString("spec", "").equals(old.spec)))) {
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
            store(local);
            if (finished != null) finished.run();
        });
    }

    private static Set<String> deletedIds() {
        final Set<String> result = new HashSet<>();
        try {
            final JSONArray array = new JSONArray(NekoConfig.customProfileFrameProjectDeletes.String());
            for (int i = 0; i < array.length(); i++) result.add(array.optString(i));
        } catch (Exception e) {
            FileLog.e(e);
        }
        return result;
    }

    private static void flushDeletes(int account) {
        if (!SovietGramApiClient.isReady(account)) return;
        for (String id : deletedIds()) {
            SovietGramApiClient.deleteSigned(account, "/v1/frame-projects/" + id, (body, error) -> {
                if (account != UserConfig.selectedAccount) return;
                if (body == null) {
                    if (error != null) FileLog.e("FrameProjects: delete failed: " + error);
                    return;
                }
                final JSONArray remaining = new JSONArray();
                for (String item : deletedIds()) if (!id.equals(item)) remaining.put(item);
                NekoConfig.customProfileFrameProjectDeletes.setConfigString(remaining.toString());
                SovietGramAccountScope.saveLive();
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
        NekoConfig.customProfileFrameProjects.setConfigString(array.toString());
        SovietGramAccountScope.saveLive();
    }

    private static void push(int account, Project project) {
        if (!SovietGramApiClient.isReady(account)) return;
        final String id = project.id;
        final String name = project.name;
        final String graphText = project.graph;
        final String specText = project.spec;
        final long updated = project.updated;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final FrameGraph graph = FrameGraph.parse(graphText);
                final FrameSpec spec = FrameSpec.parse(specText);
                final Set<String> assets = new HashSet<>(spec.assets());
                assets.addAll(graph.sources());
                final Map<String, String> remote = new HashMap<>();
                final File root = new File(org.telegram.messenger.ApplicationLoader.getFilesDirFixed(),
                        "frame-assets").getCanonicalFile();
                for (String source : assets) {
                    if (source == null || source.isEmpty() || FrameBlanks.is(source)
                            || source.startsWith("https://") || source.startsWith("http://")) continue;
                    final File file = new File(source).getCanonicalFile();
                    if (!file.getPath().startsWith(root.getPath() + File.separator) || !file.isFile()) {
                        throw new IllegalArgumentException("Frame texture is not in the app library");
                    }
                    final String key = account + ":" + file.getPath() + ":"
                            + file.lastModified() + ":" + file.length();
                    String url = uploadedTextures.get(key);
                    if (url == null) {
                        final JSONObject uploaded = SovietGramApiClient.uploadMediaFile(account, "frame", file);
                        final String path = uploaded.optString("path", "");
                        if (!path.startsWith("/v1/media/")) {
                            throw new IllegalStateException("Frame texture upload failed");
                        }
                        url = ApiServersHelper.baseUrl() + path;
                        if (uploadedTextures.size() > 256) uploadedTextures.clear();
                        uploadedTextures.put(key, url);
                    }
                    remote.put(source, url);
                }
                final String remoteGraph = graph.swap(remote).encode();
                final String remoteSpec = FrameSpec.swap(spec, remote).encode();
                final JSONObject body = new JSONObject().put("name", name)
                        .put("graph", remoteGraph)
                        .put("spec", remoteSpec)
                        .put("updated_ms", updated);
                SovietGramApiClient.putSigned(account, "/v1/frame-projects/" + id,
                        body, (result, error) -> {
                            if (error != null) FileLog.e("FrameProjects: save failed: " + error);
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
            }
        });
    }
}
