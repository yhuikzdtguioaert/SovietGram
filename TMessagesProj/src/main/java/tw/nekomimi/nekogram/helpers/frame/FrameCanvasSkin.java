package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * How the studio's node canvas is painted: twenty-five colours, named.
 *
 * <p>The canvas is not part of the app's own screens — it is a dark board with cards on it — so it
 * carries its own palette rather than reading the theme. Two are built in, the user can write a third,
 * and the server offers more; and "as in the client" simply picks whichever of the two built-in ones
 * suits the app's current background.
 */
public final class FrameCanvasSkin {

    public static final int ROLES = 25;

    public static final int MODE_CLIENT = 0;
    public static final int MODE_DARK = 1;
    public static final int MODE_LIGHT = 2;
    public static final int MODE_CUSTOM = 3;
    public static final int MODE_SERVER = 4;

    public static final int ACCENT = 0xFF3390EC;

    /** The names a theme is written with; the order is the role order and cannot change. */
    public static final String[] KEYS = {
            "back", "grid", "card", "dead", "shadow", "shadowOff", "title", "titleOff",
            "note", "noteOff", "row", "line", "text", "dim", "faint", "bar", "barFill",
            "press", "pressSoft", "pad", "padOff", "padEdge", "padEdgeOff", "padCross", "skin",
    };

    public static final FrameCanvasSkin DARK = new FrameCanvasSkin(new int[]{
            0xFF15161A, 0x22FFFFFF, 0xFF23252C, 0xFF515151, 0xFF272727, 0xFF212121,
            0xFFFFFFFF, 0xFF787D88, 0xFF939393, 0xFF5F646E, 0xFF191919, 0xFF2C2C2C,
            0xFFD7DBE2, 0xFF787D88, 0xFF5F646E, 0xFF6C6C6C, 0xFFD9D9D9, 0xFF000000,
            0xFF191919, 0xFF2F3138, 0xFF25272C, 0xFF4A4F59, 0xFF34383F, 0xFF3A3E46,
            0xFF5B6473,
    });

    public static final FrameCanvasSkin LIGHT = new FrameCanvasSkin(new int[]{
            0xFFF1F3F6, 0x22000000, 0xFFFFFFFF, 0xFFC8CCD2, 0xFFC2C7CE, 0xFFDADDE2,
            0xFF15171C, 0xFF9AA0A8, 0xFF5C626C, 0xFFA8ADB5, 0xFFEDEFF3, 0xFFDCDFE5,
            0xFF272A31, 0xFF868C95, 0xFFA8ADB5, 0xFFD3D7DD, 0xFF6E747F, 0xFFFFFFFF,
            0xFFF1F3F6, 0xFFE6E9ED, 0xFFEFF1F4, 0xFFB6BCC5, 0xFFD3D7DD, 0xFFC7CCD3,
            0xFFB6BDC7,
    });

    private final int[] roles;

    public final int back;
    public final int grid;
    public final int card;
    public final int dead;
    public final int shadow;
    public final int shadowOff;
    public final int title;
    public final int titleOff;
    public final int note;
    public final int noteOff;
    public final int row;
    public final int line;
    public final int text;
    public final int dim;
    public final int faint;
    public final int bar;
    public final int barFill;
    public final int press;
    public final int pressSoft;
    public final int pad;
    public final int padOff;
    public final int padEdge;
    public final int padEdgeOff;
    public final int padCross;
    public final int skin;
    public final boolean dark;

    private FrameCanvasSkin(int[] roles) {
        this.roles = roles;
        back = roles[0];
        grid = roles[1];
        card = roles[2];
        dead = roles[3];
        shadow = roles[4];
        shadowOff = roles[5];
        title = roles[6];
        titleOff = roles[7];
        note = roles[8];
        noteOff = roles[9];
        row = roles[10];
        line = roles[11];
        text = roles[12];
        dim = roles[13];
        faint = roles[14];
        bar = roles[15];
        barFill = roles[16];
        press = roles[17];
        pressSoft = roles[18];
        pad = roles[19];
        padOff = roles[20];
        padEdge = roles[21];
        padEdgeOff = roles[22];
        padCross = roles[23];
        skin = roles[24];
        dark = isDark(back);
    }

    public int role(int index) {
        return (index < 0 || index >= ROLES) ? 0 : roles[index];
    }

    public FrameCanvasSkin with(int index, int color) {
        if (index < 0 || index >= ROLES) {
            return this;
        }
        final int[] next = new int[ROLES];
        System.arraycopy(roles, 0, next, 0, ROLES);
        next[index] = color;
        return new FrameCanvasSkin(next);
    }

    /**
     * @param custom the user's own theme, when the mode asks for it.
     * @param dark   whether the app itself is dark, for the "as in the client" mode.
     */
    public static FrameCanvasSkin of(int mode, @Nullable FrameCanvasSkin custom, boolean dark) {
        return switch (mode) {
            case MODE_DARK -> DARK;
            case MODE_LIGHT -> LIGHT;
            case MODE_CUSTOM -> custom == null ? DARK : custom;
            default -> dark ? DARK : LIGHT;
        };
    }

    public String encode() {
        final JSONObject json = new JSONObject();
        for (int i = 0; i < ROLES; i++) {
            try {
                json.put(KEYS[i], FrameSpec.hex(roles[i]));
            } catch (Throwable ignore) {
            }
        }
        return json.toString();
    }

    /** Reads a theme. Null when there is nothing to read, so a caller can fall back. */
    @Nullable
    public static FrameCanvasSkin parse(@Nullable String raw) {
        if (raw == null || raw.length() == 0) {
            return null;
        }
        try {
            return parse(new JSONObject(raw));
        } catch (Throwable ignore) {
            return null;
        }
    }

    @Nullable
    public static FrameCanvasSkin parse(@Nullable JSONObject json) {
        if (json == null) {
            return null;
        }
        final int[] roles = new int[ROLES];
        boolean any = false;
        for (int i = 0; i < ROLES; i++) {
            final String value = json.optString(KEYS[i], "");
            roles[i] = FrameSpec.color(value, DARK.roles[i]);
            any |= value.length() > 0;
        }
        return any ? new FrameCanvasSkin(roles) : null;
    }

    /** Perceived brightness, which is what decides whether a background reads as dark. */
    public static boolean isDark(int color) {
        final int r = (color >>> 16) & 0xFF;
        final int g = (color >>> 8) & 0xFF;
        final int b = color & 0xFF;
        return (r * 299 + g * 587 + b * 114) / 1000 < 128;
    }
}
