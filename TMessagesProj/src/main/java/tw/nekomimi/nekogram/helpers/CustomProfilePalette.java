package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;
import android.util.SparseIntArray;

import androidx.annotation.Nullable;

import org.json.JSONObject;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * A look's palette: theme colour keys it repaints for the profile screen.
 *
 * <p>The reference stores it as {@code {"key_windowBackgroundWhite": "#FF101010", …}} — names of the
 * host app's own theme keys against colours. Anything the profile draws through
 * {@link CustomProfileHelper#themedColor} is therefore the look's to change, which is how a look
 * makes the rows, the dividers and the text agree with its banner instead of with the user's theme.
 *
 * <p>The names are resolved to the {@code Theme.key_*} constants by reflection, once, and cached: they
 * are plain integers in this app and there is no lookup by name. A name that does not resolve is
 * dropped rather than guessed — a palette written against a newer key list must not repaint some
 * unrelated colour that happens to share its number.
 *
 * <p>Bounded at 128 entries, as there, because this arrives over the network from another user.
 */
public final class CustomProfilePalette {

    private static final String PREFIX = "key_";
    private static final int MAX_ENTRIES = 128;

    /** Theme key name to its constant, built once from {@link Theme}'s own fields. */
    @Nullable
    private static Map<String, Integer> keys;

    /** The parsed palette and the string it came from, so the draw path never parses. */
    private static String parsedFrom = "";
    private static SparseIntArray parsed = new SparseIntArray();

    /**
     * The keys the reference's own editor offers, in its own order, each with the group it belongs
     * to. Not every theme key — a look can carry any of them and they are all honoured, but these are
     * the ones worth putting in front of somebody, and they are exactly the reference's list.
     */
    public static final String[][] KNOWN = {
            {"key_windowBackgroundWhite", "Surface"},
            {"key_windowBackgroundGray", "Surface"},
            {"key_divider", "Surface"},
            {"key_listSelector", "Surface"},
            {"key_actionBarDefault", "Surface"},
            {"key_actionBarDefaultIcon", "Surface"},
            {"key_actionBarDefaultTitle", "Surface"},
            {"key_actionBarDefaultSubtitle", "Surface"},
            {"key_actionBarDefaultSelector", "Surface"},
            {"key_windowBackgroundWhiteBlackText", "Text"},
            {"key_windowBackgroundWhiteGrayText", "Text"},
            {"key_windowBackgroundWhiteGrayText2", "Text"},
            {"key_windowBackgroundWhiteGrayText3", "Text"},
            {"key_windowBackgroundWhiteGrayText4", "Text"},
            {"key_windowBackgroundWhiteValueText", "Text"},
            {"key_profile_title", "Text"},
            {"key_profile_status", "Text"},
            {"key_windowBackgroundWhiteBlueText", "Links"},
            {"key_windowBackgroundWhiteBlueText2", "Links"},
            {"key_windowBackgroundWhiteBlueText4", "Links"},
            {"key_windowBackgroundWhiteLinkText", "Links"},
            {"key_chat_messageLinkIn", "Links"},
            {"key_chat_linkSelectBackground", "Links"},
            {"key_avatar_text", "Other"},
    };

    private CustomProfilePalette() {
    }

    /** The palette as it is stored, for the editor. Never null; empty when the look has none. */
    public static JSONObject entries() {
        final String raw = NekoConfig.customProfilePalette.String();
        if (TextUtils.isEmpty(raw)) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    /** The colour this palette gives a key by name, or null when it says nothing about it. */
    @Nullable
    public static Integer colorOf(String name) {
        final String value = entries().optString(name, "");
        return value.isEmpty() ? null : color(value);
    }

    /** Sets one key, or clears it when {@code color} is null. Writes the config and repaints. */
    public static void put(String name, @Nullable Integer color) {
        final JSONObject json = entries();
        try {
            if (color == null) {
                json.remove(name);
            } else {
                json.put(name, String.format(java.util.Locale.US, "#%08X", color));
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return;
        }
        NekoConfig.customProfilePalette.setConfigString(json.length() == 0 ? "" : json.toString());
        CustomProfileHelper.onSettingsChanged();
    }

    /** Takes the whole palette off. */
    public static void clear() {
        NekoConfig.customProfilePalette.setConfigString("");
        CustomProfileHelper.onSettingsChanged();
    }

    /** How many keys the look repaints. */
    public static int size() {
        return entries().length();
    }

    /** Drops the parsed copy; the look under it has changed. */
    public static void invalidate() {
        parsedFrom = "";
        parsed = new SparseIntArray();
    }

    /**
     * The colour this look gives that theme key, or null when it says nothing about it.
     * Called from the draw path for every colour the profile resolves, so it must stay a lookup.
     */
    @Nullable
    public static Integer colorFor(int key) {
        final SparseIntArray palette = palette();
        if (palette.size() == 0) {
            return null;
        }
        final int index = palette.indexOfKey(key);
        return index < 0 ? null : palette.valueAt(index);
    }

    private static SparseIntArray palette() {
        final String raw = CustomProfileHelper.cfgString(NekoConfig.customProfilePalette);
        if (!raw.equals(parsedFrom)) {
            parsedFrom = raw;
            parsed = parse(raw);
        }
        return parsed;
    }

    static SparseIntArray parse(@Nullable String json) {
        final SparseIntArray out = new SparseIntArray();
        if (TextUtils.isEmpty(json)) {
            return out;
        }
        try {
            final JSONObject object = new JSONObject(json);
            final Iterator<String> names = object.keys();
            while (names.hasNext() && out.size() < MAX_ENTRIES) {
                final String name = names.next();
                if (!isKeyName(name)) {
                    continue;
                }
                final Integer key = key(name);
                if (key == null) {
                    continue;
                }
                final Integer color = color(object.optString(name, ""));
                if (color != null) {
                    out.put(key, color);
                }
            }
        } catch (Throwable e) {
            FileLog.e("CustomProfilePalette: unreadable palette: " + e.getMessage());
            return new SparseIntArray();
        }
        return out;
    }

    /** {@code key_} followed by letters, digits or underscores, as the reference requires. */
    private static boolean isKeyName(String name) {
        if (name == null || !name.startsWith(PREFIX) || name.length() <= PREFIX.length() || name.length() > 96) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            final char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }
        return true;
    }

    @Nullable
    private static Integer key(String name) {
        if (keys == null) {
            keys = readKeys();
        }
        return keys.get(name);
    }

    /**
     * Every {@code public static final int key_…} on {@link Theme}. Read once by reflection because
     * the keys are integers with no name lookup of their own, and a look names them.
     */
    private static Map<String, Integer> readKeys() {
        final Map<String, Integer> map = new HashMap<>();
        try {
            for (Field field : Theme.class.getDeclaredFields()) {
                if (!field.getName().startsWith(PREFIX)
                        || field.getType() != int.class
                        || !Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                map.put(field.getName(), field.getInt(null));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
        return map;
    }

    /** {@code #AARRGGBB}, or the same without the hash — both shapes appear in published looks. */
    @Nullable
    private static Integer color(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }
        String text = value.trim();
        if (text.charAt(0) != '#') {
            text = "#" + text;
        }
        try {
            return android.graphics.Color.parseColor(text);
        } catch (Throwable ignore) {
            return null;
        }
    }
}
