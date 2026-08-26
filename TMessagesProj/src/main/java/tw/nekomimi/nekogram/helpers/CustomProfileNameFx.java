package tw.nekomimi.nekogram.helpers;

import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * The look of the profile name: the animated shader, the glow behind the glyphs and the typeface,
 * each read from the look being drawn so a view only has to ask for it while drawing.
 * <p>
 * Modes are 0 none, 1 pulse, 2 gradient, 3 shimmer, 4 rainbow, 5 neon, 6 fire, 7 ice. Pulse is the
 * odd one out: it moves the alpha rather than painting a shader.
 * <p>
 * Values come through {@link CustomProfileHelper#cfgInt} rather than straight off {@link NekoConfig},
 * so the same code paints a peer's synced name effects as paints the local user's own.
 * <p>
 * This runs inside onDraw, so nothing here allocates that does not have to — the one piece of state
 * is a reused {@link Matrix}, reset before every use.
 */
public final class CustomProfileNameFx {

    private static final int[] RAINBOW = {
            0xFFFF0000, 0xFFFF7F00, 0xFFFFFF00, 0xFF00FF00, 0xFF0000FF, 0xFF4B0082, 0xFF8B00FF};
    private static final int[] NEON = {0xFFFFFFFF, 0xFF3390EC, 0xFF7FDBFF};
    private static final int[] FIRE = {0xFFFFE04D, 0xFFFF9500, 0xFFFF3B30};
    private static final int[] ICE = {0xFFFFFFFF, 0xFFB3E5FC, 0xFFFFFFFF};

    /** {@link Shader#setLocalMatrix} copies what it is given, so one instance can serve every call. */
    private static final Matrix MATRIX = new Matrix();

    private CustomProfileNameFx() {
    }

    /** Whether the caller has to invalidate every frame to keep the effect moving. */
    public static boolean isAnimated() {
        return CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFx) != 0;
    }

    /** The animation clock: seconds scaled by the speed setting. Callers wrap it as they need. */
    public static float phase() {
        final float speed = clamp(CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFxSpeed), 10, 300) / 100f;
        return System.nanoTime() * 1e-9f * speed * 0.05f;
    }

    /**
     * The shader for the current effect across a text box of this size, or null when the mode paints
     * none: 0 is off and 1 only touches the alpha.
     */
    @Nullable
    public static Shader shaderFor(int width, int height) {
        final int mode = CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFx);
        if (mode <= 1 || width <= 0 || height <= 0) {
            return null;
        }
        final float angle = CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFxAngle);
        final double theta = Math.toRadians(angle);
        final float dx = (float) Math.sin(theta);
        final float dy = (float) -Math.cos(theta);
        final float cx = width * 0.5f;
        final float cy = height * 0.5f;
        final float x0 = cx - dx * cx;
        final float y0 = cy - dy * cy;
        final float x1 = cx + dx * cx;
        final float y1 = cy + dy * cy;

        final int c1 = CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFxColor1);
        final int c2 = CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFxColor2);
        final Shader shader;
        final boolean moving;
        switch (mode) {
            case 2:
                shader = new LinearGradient(x0, y0, x1, y1, new int[]{c1, c2, c1}, null,
                        Shader.TileMode.MIRROR);
                moving = false;
                break;
            case 3:
                shader = new LinearGradient(x0, y0, x1, y1, new int[]{c1, c2, c1}, null,
                        Shader.TileMode.MIRROR);
                moving = true;
                break;
            case 4:
                shader = new LinearGradient(x0, y0, x1, y1, RAINBOW, null, Shader.TileMode.REPEAT);
                moving = true;
                break;
            case 5:
                shader = new LinearGradient(x0, y0, x1, y1, NEON, null, Shader.TileMode.MIRROR);
                moving = false;
                break;
            case 6:
                shader = new LinearGradient(x0, y0, x1, y1, FIRE, null, Shader.TileMode.MIRROR);
                moving = false;
                break;
            case 7:
                shader = new LinearGradient(x0, y0, x1, y1, ICE, null, Shader.TileMode.CLAMP);
                moving = false;
                break;
            default:
                return null;
        }

        MATRIX.reset();
        if (moving) {
            // Slide along the gradient's own axis, so the sweep keeps following the chosen angle.
            final float offset = ((phase() % 1f) * 2f - 0.5f) * width;
            MATRIX.postTranslate(dx * offset, dy * offset);
        }
        MATRIX.postRotate(angle, cx, cy);
        shader.setLocalMatrix(MATRIX);
        return shader;
    }

    /** The alpha multiplier for the current frame; only pulse moves it, everything else stays opaque. */
    public static float alphaFor() {
        if (CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFx) != 1) {
            return 1f;
        }
        final float pulse = 0.7f + 0.3f * (float) Math.sin(phase() * 2 * Math.PI);
        return clampF(pulse, 0f, 1f);
    }

    /**
     * Puts the configured glow on a paint, or takes it off when the setting is off.
     * <p>
     * A shadow layer is not drawn on a hardware layer, so the view has to be on a software layer
     * ({@code setLayerType(LAYER_TYPE_SOFTWARE, null)}) for this to show up. That is the caller's
     * call to make — it costs memory, and only the caller knows how big the view is.
     */
    public static void applyGlow(Paint paint) {
        if (CustomProfileHelper.cfgBool(NekoConfig.customProfileNameGlow)) {
            // A zero radius is the same as no shadow at all, so keep at least a pixel of blur.
            final float radius = Math.max(
                    AndroidUtilities.dp(CustomProfileHelper.cfgInt(NekoConfig.customProfileNameGlowRadius)) * 0.75f, 1f);
            paint.setShadowLayer(radius, 0, 0, CustomProfileHelper.cfgInt(NekoConfig.customProfileNameGlowColor));
        } else {
            paint.clearShadowLayer();
        }
    }

    /** How many times to redraw the text: each pass stacks another shadow and deepens the glow. */
    public static int glowPasses() {
        return clamp(CustomProfileHelper.cfgInt(NekoConfig.customProfileNameGlowStrength) / 7 + 1, 1, 3);
    }

    /** Stroke width, in pixels, for the outline pass that spreads the glow past the glyphs. */
    public static float glowStrokeWidth() {
        return AndroidUtilities.dp(clampF(CustomProfileHelper.cfgInt(NekoConfig.customProfileNameGlowStrength), 2f, 14f));
    }

    /**
     * The configured typeface, or null for index 0, which means leave the view's own typeface alone
     * rather than replace it with the platform default.
     * <p>
     * Index 7 is a font file the look brought with it — that is how a workshop look ships its own
     * typeface. It falls back to null when there is no readable file, which is also what a peer gets:
     * their blob carries the index but no path, since a path on their phone means nothing here.
     */
    @Nullable
    public static Typeface typefaceFor() {
        return typefaceFor(CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFont));
    }

    /**
     * One entry of the typeface list by index, for anything that carries its own font choice — the
     * thought bubble has one of its own and may or may not follow the name's.
     */
    static Typeface typefaceFor(int font) {
        switch (font) {
            case 1:
                return Typeface.DEFAULT_BOLD;
            case 2:
                return Typeface.SERIF;
            case 3:
                return Typeface.MONOSPACE;
            case 4:
                return Typeface.SANS_SERIF;
            case 5:
                return create("sans-serif-light");
            case 6:
                return create("sans-serif-condensed");
            case 7:
                // Not cfgString: the path is the one setting that means nothing on another phone, so
                // a peer's look resolves it to their fetched copy instead. See fontPath().
                return typefaceFromFile(CustomProfileHelper.fontPath());
            default:
                return null;
        }
    }

    /** The last font file loaded, so onDraw does not go near the filesystem once it is warm. */
    @Nullable
    private static Typeface fileTypeface;
    private static String fileTypefaceFrom = "";

    /**
     * A font file as a typeface, cached by path. A path that cannot be loaded caches null too — this is
     * called from onDraw, and a broken file must cost one failed parse rather than one per frame.
     */
    @Nullable
    static Typeface typefaceFromFile(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        if (!path.equals(fileTypefaceFrom)) {
            fileTypefaceFrom = path;
            fileTypeface = null;
            try {
                final java.io.File file = new java.io.File(path);
                if (file.exists() && file.length() > 0) {
                    fileTypeface = Typeface.createFromFile(file);
                }
            } catch (Throwable ignore) {
                // A font the look shipped that this platform will not parse; the name keeps its own.
            }
        }
        return fileTypeface;
    }

    /** Named families are not guaranteed to exist on every ROM; fall back to the view's own font. */
    @Nullable
    private static Typeface create(String family) {
        try {
            return Typeface.create(family, Typeface.NORMAL);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /** Multiplier for the name's text size. */
    public static float sizeScale() {
        return clamp(CustomProfileHelper.cfgInt(NekoConfig.customProfileNameSize), 50, 200) / 100f;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampF(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}

