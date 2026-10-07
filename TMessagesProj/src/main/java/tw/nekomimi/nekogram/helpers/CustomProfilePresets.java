package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

/** Three private, per-owner appearance slots, deliberately outside the live/workshop config. */
public final class CustomProfilePresets {
    public static final int SLOT_COUNT = 3;

    private static final java.util.concurrent.ExecutorService IO =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "profile-presets");
                thread.setDaemon(true);
                return thread;
            });

    /** Called on the UI thread: snapshot the initiating look before scheduling disk work. */
    public static void saveAsync(int account, int slot, java.util.function.Consumer<Boolean> completed) {
        final String target;
        final JSONObject values;
        try {
            target = key(account, slot);
            values = capture();
        } catch (Exception e) {
            FileLog.e(e);
            completed.accept(false);
            return;
        }
        IO.execute(() -> {
            boolean success;
            try {
                synchronized (CustomProfilePresets.class) { success = saveSnapshot(target, values); }
            } catch (Exception e) {
                FileLog.e(e);
                success = false;
            }
            final boolean result = success;
            AndroidUtilities.runOnUIThread(() -> completed.accept(result));
        });
    }

    /** Disk copying is off-thread; config and cache updates return to the initiating owner's UI. */
    public static void applyAsync(int account, int slot, java.util.function.Consumer<Boolean> completed) {
        final String target;
        try {
            target = key(account, slot);
        } catch (Exception e) {
            FileLog.e(e);
            completed.accept(false);
            return;
        }
        IO.execute(() -> {
            File directory = null;
            try {
                final JSONObject values;
                synchronized (CustomProfilePresets.class) {
                    values = readValues(target);
                    if (values == null) throw new IOException("Profile preset is empty");
                    validate(values);
                    directory = newActiveDirectory();
                    copyMedia(values, directory);
                }
                final File prepared = directory;
                AndroidUtilities.runOnUIThread(() -> {
                    boolean applied = false;
                    try {
                        // The account number may have been switched or recycled while copying.
                        if (target.equals(key(account, slot))) {
                            applyValues(values);
                            applied = true;
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (!applied) IO.execute(() -> deleteDirectory(prepared));
                    completed.accept(applied);
                });
            } catch (Exception e) {
                if (directory != null) deleteDirectory(directory);
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> completed.accept(false));
            }
        });
    }

    public static void clearAsync(int account, int slot, java.util.function.Consumer<Boolean> completed) {
        final String target;
        try {
            target = key(account, slot);
        } catch (Exception e) {
            FileLog.e(e);
            completed.accept(false);
            return;
        }
        IO.execute(() -> {
            boolean success;
            synchronized (CustomProfilePresets.class) { success = clearKey(target); }
            final boolean result = success;
            AndroidUtilities.runOnUIThread(() -> completed.accept(result));
        });
    }

    private CustomProfilePresets() { }

    private static SharedPreferences preferences() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences("custom_profile_visual_presets", Context.MODE_PRIVATE);
    }

    private static String key(int account, int slot) {
        if (slot < 0 || slot >= SLOT_COUNT) throw new IllegalArgumentException("Invalid profile slot");
        long owner = SovietGramTokenStore.ownId(account);
        if (owner <= 0 || !SovietGramAccountScope.isLive(account)) {
            throw new IllegalStateException("Profile account is not live");
        }
        return "slot_" + owner + "_" + slot;
    }

    public static boolean has(int account, int slot) {
        return preferences().contains(key(account, slot));
    }

    private static List<ConfigItem> visualItems() {
        List<ConfigItem> items = new ArrayList<>(Arrays.asList(CustomProfileHelper.portableItems()));
        items.add(NekoConfig.customProfileBannerPath);
        items.add(NekoConfig.customProfileBackgroundPath);
        items.add(NekoConfig.customProfileNameFontPath);
        items.add(NekoConfig.customProfileThoughtFontPath);
        return items;
    }

    private static JSONObject capture() throws Exception {
        JSONObject values = new JSONObject();
        for (ConfigItem item : visualItems()) values.put(item.getKey(), item.value);
        String raw = values.optString(NekoConfig.customProfileExtraBlocks.getKey(), "");
        if (!raw.isEmpty()) {
            JSONArray blocks = new JSONArray(raw);
            for (int i = 0; i < blocks.length(); i++) {
                JSONObject block = blocks.getJSONObject(i);
                // These are connection identities, not styling. Tokens live in separate helpers
                // and are never included in the visual allowlist in the first place.
                block.remove("accounts");
                if (block.optInt("type") == CustomProfileExtraRows.TYPE_INTEGRATION) block.remove("url");
            }
            values.put(NekoConfig.customProfileExtraBlocks.getKey(), blocks.toString());
        }
        return values;
    }

    public static synchronized boolean save(int account, int slot) {
        try {
            return saveSnapshot(key(account, slot), capture());
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean saveSnapshot(String key, JSONObject values) throws Exception {
        File directory = newDirectory();
        boolean saved = false;
        try {
            copyMedia(values, directory);
            JSONObject snapshot = new JSONObject().put("version", 1)
                    .put("directory", directory.getAbsolutePath()).put("values", values);
            String previous = preferences().getString(key, "");
            saved = preferences().edit().putString(key, snapshot.toString()).commit();
            if (saved) deleteSnapshotDirectory(previous);
            else restorePreference(key, previous);
            return saved;
        } finally {
            if (!saved) deleteDirectory(directory);
        }
    }

    /** Applies a complete local snapshot, never the workshop importer which clears local paths. */
    public static synchronized boolean apply(int account, int slot) {
        File directory = null;
        try {
            JSONObject values = readValues(key(account, slot));
            if (values == null) return false;
            validate(values);
            directory = newActiveDirectory();
            copyMedia(values, directory);
            applyValues(values);
            return true;
        } catch (Exception e) {
            if (directory != null) deleteDirectory(directory);
            FileLog.e(e);
            return false;
        }
    }

    public static synchronized boolean clear(int account, int slot) {
        try {
            return clearKey(key(account, slot));
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static boolean clearKey(String key) {
        try {
            String previous = preferences().getString(key, "");
            if (!preferences().edit().remove(key).commit()) {
                restorePreference(key, previous);
                return false;
            }
            deleteSnapshotDirectory(previous);
            return true;
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private static void restorePreference(String key, String previous) {
        // commit() may return false AFTER changing SharedPreferences' in-memory map. Roll it
        // back as well as keeping the old media, otherwise a failed save loses the live slot.
        SharedPreferences.Editor editor = preferences().edit();
        if (previous.isEmpty()) editor.remove(key);
        else editor.putString(key, previous);
        editor.commit();
    }

    private static JSONObject readValues(String key) throws Exception {
        String raw = preferences().getString(key, "");
        if (raw.isEmpty()) return null;
        JSONObject snapshot = new JSONObject(raw);
        if (snapshot.optInt("version") != 1) throw new IOException("Unsupported profile preset version");
        return snapshot.getJSONObject("values");
    }

    private static void validate(JSONObject values) throws Exception {
        for (ConfigItem item : visualItems()) {
            Object value = values.has(item.getKey()) ? values.get(item.getKey()) : item.defaultValue;
            boolean valid = item.type == ConfigItem.configTypeBool && value instanceof Boolean
                    || item.type == ConfigItem.configTypeInt && value instanceof Integer
                    || item.type == ConfigItem.configTypeString && value instanceof String;
            if (!valid) throw new IOException("Invalid visual preset value: " + item.getKey());
        }
        String blocks = values.optString(NekoConfig.customProfileExtraBlocks.getKey(), "");
        if (!blocks.isEmpty()) new JSONArray(blocks);
    }

    private static File newActiveDirectory() throws IOException {
        File directory = new File(rootDirectory(), "active_" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("Cannot restore profile preset media");
        return directory;
    }

    private static void applyValues(JSONObject values) throws Exception {
        // Resolve identities from the CURRENT look, not from this saved appearance. Provider
        // credentials themselves live elsewhere and are not read, restored or disconnected here.
        mergeLiveConnections(values);
        List<File> previousMedia = activeMediaDirectories();
        CustomProfileHelper.releaseVideo();
        synchronized (NekoConfig.sync) {
            for (ConfigItem item : visualItems()) {
                item.changed(values.has(item.getKey()) ? values.get(item.getKey()) : item.defaultValue);
                item.saveConfig();
            }
        }
        CustomProfileHelper.onSettingsChanged();
        for (File old : previousMedia) deleteDirectory(old);
    }

    private static void mergeLiveConnections(JSONObject values) throws Exception {
        String savedRaw = values.optString(NekoConfig.customProfileExtraBlocks.getKey(), "");
        if (savedRaw.isEmpty()) return;
        JSONArray saved = new JSONArray(savedRaw);
        JSONArray live = liveBlocks();
        for (int i = 0; i < saved.length(); i++) {
            JSONObject block = saved.getJSONObject(i);
            block.remove("accounts");
            if (block.optInt("type") != CustomProfileExtraRows.TYPE_INTEGRATION) continue;
            block.remove("url");
            JSONObject match = null;
            for (int j = 0; j < live.length(); j++) {
                JSONObject candidate = live.optJSONObject(j);
                if (candidate == null || candidate.optInt("type") != CustomProfileExtraRows.TYPE_INTEGRATION
                        || candidate.optInt("service") != block.optInt("service")) continue;
                if (match == null) match = candidate;
                if (candidate.optString("id").equals(block.optString("id"))) {
                    match = candidate;
                    break;
                }
            }
            if (match != null) {
                if (match.has("accounts")) block.put("accounts", match.get("accounts"));
                block.put("url", match.optString("url", ""));
            }
        }
        values.put(NekoConfig.customProfileExtraBlocks.getKey(), saved.toString());
    }

    /** Broken live editing data must not prevent recovery from a valid saved appearance. */
    private static JSONArray liveBlocks() {
        String raw = NekoConfig.customProfileExtraBlocks.String();
        try {
            return raw.isEmpty() ? new JSONArray() : new JSONArray(raw);
        } catch (Exception e) {
            FileLog.e(e);
            return new JSONArray();
        }
    }

    private static List<File> activeMediaDirectories() throws Exception {
        List<File> directories = new ArrayList<>();
        for (ConfigItem item : new ConfigItem[]{NekoConfig.customProfileBannerPath,
                NekoConfig.customProfileBackgroundPath, NekoConfig.customProfileNameFontPath,
                NekoConfig.customProfileThoughtFontPath}) collectActiveDirectory(item.String(), directories);
        JSONArray blocks = liveBlocks();
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.optJSONObject(i);
            if (block != null) collectActiveDirectory(block.optString("media_path", ""), directories);
        }
        return directories;
    }

    private static void collectActiveDirectory(String path, List<File> directories) throws IOException {
        if (path.isEmpty()) return;
        File directory = new File(path).getCanonicalFile().getParentFile();
        if (directory != null && directory.getName().startsWith("active_")
                && rootDirectory().getCanonicalFile().equals(directory.getParentFile())
                && !directories.contains(directory)) directories.add(directory);
    }

    private static File rootDirectory() {
        return new File(ApplicationLoader.getFilesDirFixed(), "custom_profile_visual_presets");
    }

    private static File newDirectory() throws IOException {
        File directory = new File(rootDirectory(), UUID.randomUUID().toString());
        if (!directory.mkdirs()) throw new IOException("Cannot create profile preset directory");
        return directory;
    }

    private static void copyMedia(JSONObject values, File directory) throws Exception {
        ConfigItem[] paths = {NekoConfig.customProfileBannerPath, NekoConfig.customProfileBackgroundPath,
                NekoConfig.customProfileNameFontPath, NekoConfig.customProfileThoughtFontPath};
        for (ConfigItem path : paths) {
            String original = values.optString(path.getKey(), "");
            if (!original.isEmpty()) values.put(path.getKey(), copyFile(original, directory, path.getKey()));
        }
        String raw = values.optString(NekoConfig.customProfileExtraBlocks.getKey(), "");
        if (raw.isEmpty()) return;
        JSONArray blocks = new JSONArray(raw);
        for (int i = 0; i < blocks.length(); i++) {
            JSONObject block = blocks.getJSONObject(i);
            String path = block.optString("media_path", "");
            // URL media is already immutable, fetched by the existing media resolver.
            if (!path.isEmpty() && !path.startsWith("https://") && !path.startsWith("http://")) {
                block.put("media_path", copyFile(path, directory, "block_" + i));
            }
        }
        values.put(NekoConfig.customProfileExtraBlocks.getKey(), blocks.toString());
    }

    private static String copyFile(String path, File directory, String name) throws IOException {
        File source = new File(path);
        if (!source.isFile() || source.length() == 0) throw new IOException("Profile media is missing");
        File target = new File(directory, name);
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            out.getFD().sync();
        }
        return target.getAbsolutePath();
    }

    private static void deleteSnapshotDirectory(String raw) {
        if (raw.isEmpty()) return;
        try {
            File directory = new File(new JSONObject(raw).getString("directory"));
            // Never delete a user-selected file or arbitrary path from corrupt preferences.
            if (directory.getCanonicalFile().getParentFile().equals(rootDirectory().getCanonicalFile())) {
                deleteDirectory(directory);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static void deleteDirectory(File directory) {
        File[] children = directory.listFiles();
        if (children != null) for (File child : children) child.delete();
        directory.delete();
    }
}
