package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.helpers.WorkshopHelper;

/**
 * Extra colour schemes for the node canvas, offered by the server.
 *
 * <p>Small, rarely changing and entirely cosmetic, so it is fetched with an ETag and kept in one
 * file: an unchanged list costs a bare 304 and no parsing, and the picker opens from the cache with
 * no network at all. A body that yields nothing usable is not cached, so a bad deploy does not stick.
 */
public final class FrameCanvasThemes {

    private static final String URL = "https://penis.nothalk.fun/cpb/api/frame-themes";
    private static final String CACHE = "sovietgram_frame_themes.json";
    private static final int MAX_BYTES = 256 * 1024;
    /** How long a fetched list is trusted before it is worth asking again. */
    private static final long MIN_GAP = 10 * 60 * 1000L;

    public static final class Theme {
        public final int id;
        public final String name;
        public final FrameCanvasSkin skin;

        Theme(int id, String name, FrameCanvasSkin skin) {
            this.id = id;
            this.name = name;
            this.skin = skin;
        }
    }

    public interface Callback {
        void onThemes(List<Theme> themes);
    }

    private static List<Theme> themes = Collections.emptyList();
    private static String etag = "";
    private static boolean loaded;
    private static boolean checking;
    private static long checkedAt;

    private FrameCanvasThemes() {
    }

    /** What is known right now, from the cache if it has not been read yet. Never blocks on network. */
    public static List<Theme> list() {
        if (!loaded) {
            loaded = true;
            readCache();
        }
        return themes;
    }

    @Nullable
    public static Theme byId(int id) {
        for (Theme theme : list()) {
            if (theme.id == id) {
                return theme;
            }
        }
        return null;
    }

    /** Asks the server, unless it was asked recently. The callback runs on the UI thread. */
    public static void sync(@Nullable Callback callback) {
        final List<Theme> known = list();
        if (checking || System.currentTimeMillis() - checkedAt < MIN_GAP) {
            if (callback != null) {
                callback.onThemes(known);
            }
            return;
        }
        checking = true;
        Utilities.globalQueue.postRunnable(() -> {
            List<Theme> fetched = null;
            try {
                final WorkshopHelper.Json response = WorkshopHelper.getJson(URL, etag);
                if (response.code == 200 && response.body != null) {
                    fetched = parse(response.body);
                    if (!fetched.isEmpty()) {
                        etag = response.etag == null ? "" : response.etag;
                        writeCache(response.body);
                    } else {
                        fetched = null;
                    }
                }
            } catch (Throwable e) {
                FileLog.e("FrameCanvasThemes: could not fetch canvas themes: " + e.getMessage());
            }
            final List<Theme> result = fetched;
            checkedAt = System.currentTimeMillis();
            AndroidUtilities.runOnUIThread(() -> {
                checking = false;
                if (result != null) {
                    themes = result;
                }
                if (callback != null) {
                    callback.onThemes(themes);
                }
            });
        });
    }

    static List<Theme> parse(@Nullable String raw) {
        final List<Theme> out = new ArrayList<>();
        if (raw == null || raw.isEmpty() || raw.length() > MAX_BYTES) {
            return out;
        }
        try {
            final JSONArray array = new JSONObject(raw).optJSONArray("themes");
            for (int i = 0; array != null && i < array.length(); i++) {
                final JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final int id = item.optInt("id", 0);
                final String name = item.optString("name", "").trim();
                final FrameCanvasSkin skin = FrameCanvasSkin.parse(item.optJSONObject("roles"));
                // A theme with no id could not be stored, and one with no colours is not a theme.
                if (id <= 0 || name.isEmpty() || skin == null) {
                    continue;
                }
                out.add(new Theme(id, name, skin));
            }
        } catch (Throwable e) {
            FileLog.e("FrameCanvasThemes: unreadable list: " + e.getMessage());
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(out);
    }

    private static File cacheFile() {
        return new File(ApplicationLoader.getFilesDirFixed(), CACHE);
    }

    private static void readCache() {
        try {
            final File file = cacheFile();
            if (!file.isFile() || file.length() == 0 || file.length() > MAX_BYTES) {
                return;
            }
            themes = parse(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        } catch (Throwable e) {
            FileLog.e("FrameCanvasThemes: cache unreadable: " + e.getMessage());
        }
    }

    private static void writeCache(String body) {
        try {
            if (body.length() > MAX_BYTES) {
                return;
            }
            Files.write(cacheFile().toPath(), body.getBytes(StandardCharsets.UTF_8));
        } catch (Throwable e) {
            FileLog.e("FrameCanvasThemes: cache not written: " + e.getMessage());
        }
    }
}
