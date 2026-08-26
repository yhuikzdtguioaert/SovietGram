package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * The rows a look invents for itself — a link, a button, a line of text, a picture.
 *
 * <p>These are not the profile's own rows rearranged ({@link CustomProfileRows} does that); they are
 * extra ones the author wrote, and they sit at the end of the profile above the shared media. The
 * reference calls them custom blocks and gives each an id, so an order can name them.
 *
 * <p>Six of its eleven types are drawn here — link, text, header, divider, button and picture — which
 * is every type the published looks use. The other five are a canvas the author draws on, a sticker,
 * a switch, a counter and a note; they are parsed and kept so a look is not silently rewritten, and
 * skipped by the renderer.
 *
 * <p>Everything is bounded on the way in: this arrives over the network from another user, so 64
 * blocks, 40 characters of title, 1024 of text and 512 of URL are the reference's own limits and are
 * applied here too.
 */
public final class CustomProfileExtraRows {

    public static final int TYPE_LINK = 0;
    public static final int TYPE_TEXT = 1;
    public static final int TYPE_HEADER = 2;
    public static final int TYPE_DIVIDER = 3;
    public static final int TYPE_NOTE = 4;
    public static final int TYPE_BUTTON = 5;
    public static final int TYPE_MEDIA = 10;

    /** What tapping a row does. */
    public static final int ACTION_NONE = 0;
    public static final int ACTION_OPEN = 1;
    public static final int ACTION_COPY = 2;
    public static final int ACTION_SHARE = 3;

    /** Their own ceiling: more rows than this in a look are dropped. */
    public static final int MAX_BLOCKS = 64;
    private static final int MAX_TITLE = 40;
    private static final int MAX_TEXT = 1024;
    private static final int MAX_URL = 512;
    private static final int RADIUS_DEFAULT = 16;
    private static final int MEDIA_HEIGHT_DEFAULT = 180;
    private static final int MEDIA_HEIGHT_MIN = 60;
    private static final int MEDIA_HEIGHT_MAX = 400;

    private static String parsedFrom = "";
    private static List<Block> parsed = Collections.emptyList();

    private CustomProfileExtraRows() {
    }

    /** One row the look invents. */
    public static final class Block {
        public String id = "";
        public int type = TYPE_LINK;
        public String title = "";
        public String url = "";
        public String text = "";
        public String icon = "";
        public int iconColor;
        public int iconBackground;
        public int titleColor;
        public int valueColor;
        public int action = ACTION_OPEN;
        public int longAction = ACTION_NONE;
        public int radius = RADIUS_DEFAULT;
        public int mediaHeight = MEDIA_HEIGHT_DEFAULT;
        public String mediaPath = "";
        /**
         * The descriptor other people fetch this row's picture by. A path names a file on one phone
         * and an address is not always available, so a picture picked from the gallery is uploaded
         * and described here — which is what lets a row's picture travel at all. The reference has
         * no such field and its picture rows are the author's alone.
         */
        public String media = "";
        public boolean divider = true;
        /** The author's own note to themselves: shown only on their own profile. */
        public boolean ownOnly;

        /**
         * The picture a row draws.
         *
         * <p>Three places it can come from, tried in the order that works for the most people: an
         * address anybody can fetch, then our own uploaded copy, then a file on this phone. The
         * published looks carry {@code media_path} — a file inside the author's own app storage,
         * which no other phone can read — so for a look installed from the gallery only the address
         * beside it, if there is one, will ever draw. Pictures picked here are uploaded instead.
         */
        public String picture() {
            if (type != TYPE_MEDIA) {
                return "";
            }
            if (fetchable(mediaPath)) {
                return mediaPath;
            }
            if (fetchable(url)) {
                return url;
            }
            // Our own copy of the bytes, once it has been fetched. Null while it is on its way, and
            // the row simply draws nothing until the profile repaints.
            final String fetched = CustomProfileMedia.pathFor(media);
            if (fetched != null) {
                return fetched;
            }
            // Nothing better: this phone's own file, if that is what the path is.
            return mediaPath.isEmpty() ? url : mediaPath;
        }

        /** Whether this row is one of the six with a renderer. */
        public boolean drawable() {
            return type == TYPE_LINK || type == TYPE_TEXT || type == TYPE_HEADER
                    || type == TYPE_DIVIDER || type == TYPE_BUTTON || type == TYPE_MEDIA
                    || type == TYPE_NOTE;
        }
    }

    // ---------------------------------------------------------------- editing

    /** The look's own rows as stored, for the editor. A copy: editing one must not repaint anything. */
    public static List<Block> stored() {
        return new ArrayList<>(parse(NekoConfig.customProfileExtraBlocks.String()));
    }

    /** Writes the whole list back and repaints. */
    public static void store(@Nullable List<Block> blocks) {
        final JSONArray array = new JSONArray();
        if (blocks != null) {
            for (int i = 0; i < blocks.size() && i < MAX_BLOCKS; i++) {
                final JSONObject item = write(blocks.get(i));
                if (item != null) {
                    array.put(item);
                }
            }
        }
        NekoConfig.customProfileExtraBlocks.setConfigString(
                array.length() == 0 ? "" : array.toString());
        CustomProfileHelper.onSettingsChanged();
    }

    /** A fresh row of a type, with an id of its own so an order can name it. */
    public static Block create(int type) {
        final Block block = new Block();
        block.type = type;
        block.id = Long.toHexString(System.currentTimeMillis()) + "_" + type;
        block.action = type == TYPE_LINK ? ACTION_OPEN : ACTION_NONE;
        return block;
    }

    @Nullable
    private static JSONObject write(@Nullable Block block) {
        if (block == null) {
            return null;
        }
        try {
            final JSONObject o = new JSONObject();
            o.put("id", block.id);
            o.put("type", block.type);
            o.put("title", block.title);
            o.put("url", block.url);
            o.put("text", block.text);
            o.put("icon", block.icon);
            putColor(o, "icon_color", block.iconColor);
            putColor(o, "icon_back", block.iconBackground);
            putColor(o, "title_color", block.titleColor);
            putColor(o, "value_color", block.valueColor);
            o.put("action", block.action);
            o.put("long_action", block.longAction);
            o.put("radius", block.radius);
            o.put("media_height", block.mediaHeight);
            o.put("media_path", block.mediaPath);
            o.put("media", block.media);
            o.put("divider", block.divider);
            o.put("own_only", block.ownOnly);
            return o;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static void putColor(JSONObject o, String key, int color) throws org.json.JSONException {
        // Zero means "the theme's own", and an empty string is how that is written.
        o.put(key, color == 0 ? "" : String.format(java.util.Locale.US, "#%08X", color));
    }

    public static void invalidate() {
        parsedFrom = "";
        parsed = Collections.emptyList();
    }

    /**
     * The rows to draw for the look on screen, in order. Empty unless it has any — and a peer's
     * own-only rows are dropped, which is what {@code own_only} is for.
     */
    public static List<Block> blocks() {
        if (!CustomProfileHelper.isEnabled()) {
            return Collections.emptyList();
        }
        final String raw = CustomProfileHelper.cfgString(NekoConfig.customProfileExtraBlocks);
        if (!raw.equals(parsedFrom)) {
            parsedFrom = raw;
            parsed = parse(raw);
        }
        if (parsed.isEmpty() || CustomProfileHelper.drawingOwnLook()) {
            return parsed;
        }
        final List<Block> visible = new ArrayList<>(parsed.size());
        for (Block block : parsed) {
            if (!block.ownOnly) {
                visible.add(block);
            }
        }
        return visible;
    }

    static List<Block> parse(@Nullable String json) {
        if (TextUtils.isEmpty(json)) {
            return Collections.emptyList();
        }
        final List<Block> out = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length() && out.size() < MAX_BLOCKS; i++) {
                final JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final Block block = read(item);
                if (block != null && block.drawable()) {
                    out.add(block);
                }
            }
        } catch (Throwable e) {
            FileLog.e("CustomProfileExtraRows: unreadable blocks: " + e.getMessage());
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(out);
    }

    @Nullable
    private static Block read(JSONObject o) {
        final int type = o.optInt("type", TYPE_LINK);
        if (type < 0 || type > 10) {
            return null;
        }
        final Block b = new Block();
        b.id = trim(o.optString("id", ""), 64);
        b.type = type;
        b.title = trim(o.optString("title", ""), MAX_TITLE);
        b.url = trim(o.optString("url", ""), MAX_URL);
        b.text = trim(o.optString("text", ""), MAX_TEXT);
        b.icon = trim(o.optString("icon", ""), 64);
        b.iconColor = color(o, "icon_color");
        b.iconBackground = color(o, "icon_back");
        b.titleColor = color(o, "title_color");
        b.valueColor = color(o, "value_color");
        b.action = clamp(o.optInt("action", ACTION_OPEN), ACTION_NONE, 4);
        b.longAction = clamp(o.optInt("long_action", ACTION_NONE), ACTION_NONE, 4);
        b.radius = clamp(o.optInt("radius", RADIUS_DEFAULT), 0, 48);
        b.mediaHeight = clamp(o.optInt("media_height", MEDIA_HEIGHT_DEFAULT),
                MEDIA_HEIGHT_MIN, MEDIA_HEIGHT_MAX);
        b.mediaPath = trim(o.optString("media_path", ""), MAX_URL);
        b.media = trim(o.optString("media", ""), 512);
        b.divider = o.optBoolean("divider", true);
        b.ownOnly = o.optBoolean("own_only", false);
        // A row with nothing in it is not a row. A picture row counts as full when it has a picture
        // in either field — dropping the ones that carry only an address threw away every picture
        // row that could actually be drawn by somebody other than its author.
        if (b.type != TYPE_DIVIDER && b.title.isEmpty() && b.text.isEmpty()
                && b.url.isEmpty() && b.mediaPath.isEmpty() && b.media.isEmpty()) {
            return null;
        }
        return b;
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return "";
        }
        final String text = value.trim();
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** Whether an address anybody can fetch, which is what a picture has to be to travel. */
    public static boolean fetchable(@Nullable String value) {
        if (value == null) {
            return false;
        }
        final String lower = value.trim().toLowerCase(java.util.Locale.US);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /** {@code #AARRGGBB} or the same without the hash; 0 means "the theme's own colour". */
    private static int color(JSONObject o, String key) {
        final String value = o.optString(key, "").trim();
        if (value.isEmpty()) {
            return 0;
        }
        try {
            return android.graphics.Color.parseColor(value.charAt(0) == '#' ? value : "#" + value);
        } catch (Throwable ignore) {
            return 0;
        }
    }
}
