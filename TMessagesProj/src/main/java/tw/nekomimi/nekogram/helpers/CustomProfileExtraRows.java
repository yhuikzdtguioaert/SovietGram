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
    public static final int TYPE_INTEGRATION = 12;

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
        public int service;
        public int mode;
        public JSONArray parts = new JSONArray();
        public JSONObject accounts = new JSONObject();
        /** How an integration is shown: 0 as lines of text, 1 as a card with the cover or the calendar. */
        public int intStyle;
        /** Seconds between asking for fresh data; 0 lets the service's own pace decide. */
        public int intRefresh;
        public long emoji;
        public int viewX;
        public int viewY;
        public int viewSpan = 32;

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
            boolean supported = type == TYPE_LINK || type == TYPE_TEXT || type == TYPE_HEADER
                    || type == TYPE_DIVIDER || type == TYPE_BUTTON || type == TYPE_MEDIA
                    || type == TYPE_NOTE || type == TYPE_INTEGRATION;
            return supported && (type == TYPE_DIVIDER || !title.isEmpty() || !text.isEmpty()
                    || !url.isEmpty() || !mediaPath.isEmpty() || !media.isEmpty());
        }
    }

    /** Targeted upgrade: preserve every other block, extension field and ordinary URL. */
    public static String removeRetiredIntegrations(String raw) {
        if (raw == null || raw.isEmpty()) return raw;
        try {
            JSONArray source = new JSONArray(raw), kept = new JSONArray();
            boolean changed = false;
            for (int i = 0; i < source.length(); i++) {
                Object value = source.opt(i);
                JSONObject block = source.optJSONObject(i);
                if (block != null && block.optInt("type") == TYPE_INTEGRATION) {
                    int service = block.optInt("service");
                    if (service >= 5 && service <= 7) { changed = true; continue; }
                    JSONObject accounts = block.optJSONObject("accounts");
                    if (accounts != null) {
                        for (String key : new String[]{"soundcloud", "soundcloud-me", "soundcloud-live"}) {
                            if (accounts.has(key)) { accounts.remove(key); changed = true; }
                        }
                    }
                }
                kept.put(value);
            }
            return changed ? (kept.length() == 0 ? "" : kept.toString()) : raw;
        } catch (Exception ignored) { return raw; }
    }

    /** Clear all old device-consent owners. There is no reader, listener or publisher left. */
    public static void migrateRemovedIntegrations() {
        org.telegram.messenger.ApplicationLoader.applicationContext
                .getSharedPreferences("soundcloud_device_rpc", android.content.Context.MODE_PRIVATE).edit().clear().apply();
        String raw = NekoConfig.customProfileExtraBlocks.String();
        String clean = removeRetiredIntegrations(raw);
        if (!raw.equals(clean)) NekoConfig.customProfileExtraBlocks.setConfigString(clean);
    }

    // ---------------------------------------------------------------- editing

    /** The look's own rows as stored, for the editor. A copy: editing one must not repaint anything. */
    public static List<Block> stored() {
        return new ArrayList<>(parse(NekoConfig.customProfileExtraBlocks.String(), false));
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

    /**
     * Forgets every account an integration row names. A look installed from somebody else carries
     * the author's accounts; showing them as the installer's own would hide that this row still has
     * to be connected, and the connect step would look already done.
     */
    public static void releaseIntegrationAccounts() {
        final List<Block> fresh = stored();
        boolean changed = false;
        for (Block block : fresh) {
            if (block.type != TYPE_INTEGRATION) {
                continue;
            }
            if (!block.url.isEmpty() || block.accounts.length() > 0) {
                block.url = "";
                block.accounts = new JSONObject();
                changed = true;
            }
        }
        if (changed) {
            store(fresh);
        }
    }

    /** A fresh row of a type, with an id of its own so an order can name it. */
    public static Block create(int type) {
        final Block block = new Block();
        block.type = type;
        block.id = java.util.UUID.randomUUID().toString();
        block.action = type == TYPE_LINK ? ACTION_OPEN : ACTION_NONE;
        if (type == TYPE_INTEGRATION) {
            block.action = ACTION_OPEN;
            block.service = 1;
            block.title = CustomProfileIntegrations.serviceName(1);
            block.parts.put(0);
        }
        return block;
    }

    @Nullable
    private static JSONObject write(@Nullable Block block) {
        if (block == null) {
            return null;
        }
        if (block.type == TYPE_INTEGRATION && !CustomProfileIntegrations.isSupported(block.service)) return null;
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
            o.put("emoji", block.emoji);
            o.put("view_x", block.viewX);
            o.put("view_y", block.viewY);
            o.put("view_span", block.viewSpan);
            if (block.type == TYPE_INTEGRATION) {
                o.put("service", block.service);
                o.put("mode", block.mode);
                o.put("parts", block.parts);
                o.put("accounts", block.accounts);
                o.put("int_style", block.intStyle);
                o.put("int_refresh", block.intRefresh);
            }
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
        return parse(json, true);
    }

    private static List<Block> parse(@Nullable String json, boolean onlyDrawable) {
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
                if (block != null && (!onlyDrawable || block.drawable())) {
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
        if (type < 0 || type > TYPE_INTEGRATION) {
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
        b.emoji = Math.max(0, o.optLong("emoji"));
        b.viewX = clamp(o.optInt("view_x"), -4096, 4096);
        b.viewY = clamp(o.optInt("view_y"), -4096, 4096);
        b.viewSpan = clamp(o.optInt("view_span", 32), 8, 256);
        final int storedService = o.optInt("service");
        if (type == TYPE_INTEGRATION && (storedService < 0 || storedService > 4)) {
            // Unknown providers must not be interpreted as a supported account.
            return null;
        }
        b.service = clamp(storedService, 0, 4);
        b.intStyle = clamp(o.optInt("int_style"), 0, 1);
        b.intRefresh = clamp(o.optInt("int_refresh"), 0, 3600);
        final int storedMode = o.optInt("mode");
        b.mode = clamp(storedMode, 0, CustomProfileIntegrations.modeCount(b.service) - 1);
        JSONArray modes = o.optJSONArray("parts");
        if (modes != null) {
            java.util.Set<Integer> seen = new java.util.HashSet<>();
            for (int i = 0; i < modes.length() && b.parts.length() < 8; i++) {
                int mode = modes.optInt(i, -1);
                if (mode >= 0 && mode < CustomProfileIntegrations.modeCount(b.service) && seen.add(mode)) b.parts.put(mode);
            }
        }
        if (b.parts.length() == 0) b.parts.put(b.mode);
        JSONObject accounts = o.optJSONObject("accounts");
        if (accounts != null) {
            for (int i = 0; i < 5; i++) {
                try { b.accounts.put(CustomProfileIntegrations.key(i), trim(accounts.optString(CustomProfileIntegrations.key(i)), 64)); }
                catch (org.json.JSONException ignore) { }
            }
        }
        // Drafts and imported types need to survive editing even before they can be drawn.
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
