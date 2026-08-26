package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import org.telegram.messenger.ImageReceiver;
import org.telegram.ui.ActionBar.Theme;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

/**
 * The light behind avatars and reaction bubbles.
 * <p>
 * The colour is never configured: it is taken from whatever is being drawn. An avatar's glow is the
 * dominant colour of the avatar itself, a reaction's glow is the dominant colour of its emoji, so a
 * red avatar throws red light and a blue one blue. Sampling means reading pixels, which is far too
 * expensive per frame, so a colour is remembered per object and only refreshed once its entry goes
 * stale — briefly for a placeholder that is still waiting for its photo, much less often once the
 * photo has arrived.
 * <p>
 * There is no blur here on purpose. A {@code BlurMaskFilter} wide enough to read as a glow is far too
 * expensive to run per frame in a scrolling list, so the falloff is baked into a six-stop
 * {@link RadialGradient} instead: alpha drops off along {@link #STOP_ALPHA} while the first stops are
 * pulled towards white, which is what gives the centre its hot look. Stamping that gradient a few
 * times ({@code passes}) is what makes the glow denser.
 */
public class GlowSuiteHelper {

    private static final float[] STOPS = {0f, 0.24f, 0.46f, 0.66f, 0.85f, 1f};
    private static final float[] STOP_ALPHA = {1f, 0.8f, 0.5f, 0.26f, 0.09f, 0f};
    /** How far each stop is pulled towards white before its alpha is applied. */
    private static final float[] STOP_WHITE = {0.22f, 0.10f, 0.03f, 0f, 0f, 0f};

    /** Past this the glow stops being a glow and starts being a background. */
    private static final int MAX_RADIUS_DP = 240;
    private static final int MAX_ALPHA = 235;
    /** Fraction of light left after all passes; the per-pass cap is derived from it. */
    private static final double TOTAL_FALLOFF = 0.1;

    /** Used when nothing could be sampled, or when what was sampled is not worth glowing with. */
    private static final int AVATAR_FALLBACK = 0xFF4DA3FF;
    private static final int REACTION_FALLBACK = 0xFFFF5252;

    /** A thumbnail is worth re-checking often; a loaded photo is not going to change under us. */
    private static final long THUMB_TTL = 500;
    private static final long PHOTO_TTL = 4000;
    /** Reactions are keyed by emoji, so a miss must not turn every frame into a pixel read. */
    private static final long REACTION_THROTTLE = 300;

    private static final Glow AVATAR = new Glow(NekoConfig.glowAvatarIntensity, NekoConfig.glowAvatarRadius, NekoConfig.glowAvatarPasses);
    private static final Glow REACTION = new Glow(NekoConfig.glowReactionIntensity, NekoConfig.glowReactionRadius, NekoConfig.glowReactionPasses);

    private GlowSuiteHelper() {
    }

    public static boolean avatarGlowEnabled() {
        return NekoConfig.glowSuiteEnabled.Bool() && NekoConfig.glowAvatarEnabled.Bool();
    }

    public static boolean reactionGlowEnabled() {
        return NekoConfig.glowSuiteEnabled.Bool() && NekoConfig.glowReactionEnabled.Bool();
    }

    /**
     * Draws the avatar glow. {@code radius} is the avatar's own radius; the glow grows out of it, and
     * its colour comes from {@code image}. Small avatars are skipped, see
     * {@link NekoConfig#glowAvatarMinSize}.
     */
    public static void drawAvatarGlow(Canvas canvas, float cx, float cy, float radius, ImageReceiver image) {
        if (!avatarGlowEnabled() || radius * 2f < dp(NekoConfig.glowAvatarMinSize.Int())) {
            return;
        }
        AVATAR.draw(canvas, cx, cy, radius, avatarColor(image));
    }

    /**
     * Draws the reaction glow. {@code radius} is half the bubble's longest side, and the colour is
     * sampled from the reaction's own emoji. {@code key} is what the colour is remembered under —
     * the same emoji in another message should not have to be sampled twice.
     */
    public static void drawReactionGlow(Canvas canvas, float cx, float cy, float radius, Object key, ImageReceiver image, Drawable emoji) {
        if (!reactionGlowEnabled()) {
            return;
        }
        REACTION.draw(canvas, cx, cy, radius, reactionColor(key, image, emoji));
    }

    /** What was last read out of one object, and when. */
    private static final class Sample {

        final int color;
        final long at;
        /** A colour read from a loaded photo, as opposed to from a placeholder or a thumbnail. */
        final boolean fromPhoto;

        Sample(int color, long at, boolean fromPhoto) {
            this.color = color;
            this.at = at;
            this.fromPhoto = fromPhoto;
        }
    }

    /** Weak keys: an entry lives exactly as long as the avatar or the reaction it was read from. */
    private static final Map<ImageReceiver, Sample> avatarSamples = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<Object, Sample> reactionSamples = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Sampling draws a drawable, and drawing one can end up back here, so a thread that is already
     * inside {@link #sampleDrawable} never starts a second read.
     */
    private static final ThreadLocal<Boolean> sampling = new ThreadLocal<>();

    private static int avatarColor(ImageReceiver image) {
        if (image == null) {
            return AVATAR_FALLBACK;
        }
        final long now = SystemClock.elapsedRealtime();
        Sample sample = avatarSamples.get(image);
        if (sample == null || now - sample.at > (sample.fromPhoto ? PHOTO_TTL : THUMB_TTL)) {
            boolean fromPhoto = false;
            Integer color = sampleBitmap(image);
            if (color != null) {
                fromPhoto = true;
            } else {
                // No photo yet: the placeholder is what the user is looking at, so glow with that.
                Drawable drawable = image.getStaticThumb();
                if (drawable == null) {
                    drawable = image.getDrawable();
                }
                color = sampleDrawable(drawable);
            }
            if (color != null) {
                sample = new Sample(color, now, fromPhoto);
                avatarSamples.put(image, sample);
            }
            // A failed read keeps the previous colour rather than flashing the fallback.
        }
        if (sample != null && (sample.color & 0x00FFFFFF) != 0) {
            return sample.color;
        }
        return AVATAR_FALLBACK;
    }

    private static int reactionColor(Object key, ImageReceiver image, Drawable emoji) {
        final long now = SystemClock.elapsedRealtime();
        Sample sample = key == null ? null : reactionSamples.get(key);
        if (sample != null && isVivid(sample.color)) {
            return sample.color;
        }
        if (sample != null && now - sample.at < REACTION_THROTTLE) {
            // The emoji is still loading. Wait it out instead of reading pixels every frame.
            return REACTION_FALLBACK;
        }
        Integer color = sampleBitmap(image);
        if (color == null) {
            color = sampleDrawable(emoji);
        }
        final boolean usable = color != null && isVivid(color);
        if (key != null) {
            reactionSamples.put(key, new Sample(usable ? color : 0, now, usable));
        }
        return usable ? color : REACTION_FALLBACK;
    }

    private static Integer sampleBitmap(ImageReceiver image) {
        if (image == null) {
            return null;
        }
        try {
            final Bitmap bitmap = image.getBitmap();
            if (bitmap != null && !bitmap.isRecycled() && bitmap.getWidth() > 0 && bitmap.getHeight() > 0) {
                return dominantOf(bitmap);
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /**
     * Renders the drawable into a thumbnail of its own and reads that. 18×18 is small enough to be
     * cheap and still large enough to keep a gradient or a letter from being missed entirely.
     */
    private static Integer sampleDrawable(Drawable drawable) {
        if (drawable == null || Boolean.TRUE.equals(sampling.get())) {
            return null;
        }
        sampling.set(Boolean.TRUE);
        Bitmap bitmap = null;
        try {
            bitmap = Bitmap.createBitmap(SAMPLE_SIZE, SAMPLE_SIZE, Bitmap.Config.ARGB_8888);
            final Rect bounds = new Rect(drawable.getBounds());
            try {
                drawable.setBounds(0, 0, SAMPLE_SIZE, SAMPLE_SIZE);
                drawable.draw(new Canvas(bitmap));
            } finally {
                drawable.setBounds(bounds);
            }
            return dominantOf(bitmap);
        } catch (Exception ignore) {
            return null;
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            sampling.set(Boolean.FALSE);
        }
    }

    /** Side of the throwaway bitmap a drawable is rendered into before being read. */
    private static final int SAMPLE_SIZE = 18;
    /** A read walks at most this many pixels per side, whatever the bitmap's real size is. */
    private static final int SAMPLE_STEPS = 20;
    /** Below this share of visible pixels there is not enough picture to call anything dominant. */
    private static final float MIN_OPAQUE_SHARE = 0.05f;
    private static final int MIN_ALPHA = 40;

    /**
     * The average colour of a bitmap, weighted by alpha so a transparent border cannot drag the
     * result towards black. Returns {@code null} when the picture is mostly empty.
     */
    private static Integer dominantOf(Bitmap bitmap) {
        try {
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();
            final int stepX = Math.max(1, width / SAMPLE_STEPS);
            final int stepY = Math.max(1, height / SAMPLE_STEPS);
            double weight = 0, sumR = 0, sumG = 0, sumB = 0;
            int total = 0, opaque = 0;
            for (int y = 0; y < height; y += stepY) {
                for (int x = 0; x < width; x += stepX) {
                    total++;
                    final int pixel = bitmap.getPixel(x, y);
                    final int alpha = (pixel >>> 24) & 0xFF;
                    if (alpha < MIN_ALPHA) {
                        continue;
                    }
                    opaque++;
                    final double w = alpha / 255.0;
                    sumR += ((pixel >> 16) & 0xFF) * w;
                    sumG += ((pixel >> 8) & 0xFF) * w;
                    sumB += (pixel & 0xFF) * w;
                    weight += w;
                }
            }
            if (weight <= 0 || total <= 0 || (float) opaque / total < MIN_OPAQUE_SHARE) {
                return null;
            }
            final int r = clamp((int) Math.round(sumR / weight), 0, 255);
            final int g = clamp((int) Math.round(sumG / weight), 0, 255);
            final int b = clamp((int) Math.round(sumB / weight), 0, 255);
            int rgb = (r << 16) | (g << 8) | b;
            if (rgb == 0) {
                // Pure black would read as "no colour" further down, so nudge it off zero.
                rgb = 0x00010101;
            }
            return rgb | 0xFF000000;
        } catch (Exception ignore) {
            return null;
        }
    }

    /** Whether a sampled colour is saturated enough to be recognisable once it is spread out. */
    private static boolean isVivid(int color) {
        if ((color & 0x00FFFFFF) == 0) {
            return false;
        }
        try {
            final float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            if (hsv[1] < 0.12f) {
                return false;
            }
            return !(hsv[2] > 0.92f && hsv[1] < 0.25f);
        } catch (Exception ignore) {
            return true;
        }
    }

    /**
     * Pushes a sampled colour up to glow strength. Averaging a picture gives a muted colour, and a
     * muted colour spread over a wide gradient reads as grey smudge, so saturation and brightness
     * both get a floor. What was grey to begin with becomes plain white light instead.
     */
    private static int boostColor(int color) {
        try {
            final float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            if (hsv[1] < 0.18f) {
                return Color.WHITE;
            }
            hsv[1] = Math.max(hsv[1], 0.55f);
            hsv[2] = Math.max(hsv[2], 0.95f);
            return Color.HSVToColor(hsv);
        } catch (Exception ignore) {
            return color;
        }
    }

    /**
     * Keeps the glow readable on a light theme. On a dark background a hot colour is exactly right;
     * on a white one the same colour washes out, so it is deepened instead.
     */
    private static int adaptForBg(int color) {
        try {
            final int bg = Theme.getColor(Theme.key_windowBackgroundWhite);
            final double luminance = (((bg >> 16) & 0xFF) * 0.299 + ((bg >> 8) & 0xFF) * 0.587 + (bg & 0xFF) * 0.114) / 255.0;
            if (luminance < 0.55) {
                return color;
            }
            final float[] hsv = new float[3];
            Color.colorToHSV(color, hsv);
            if (hsv[2] > 0.8f && hsv[1] < 0.3f) {
                hsv[1] = Math.max(hsv[1], 0.1f);
                hsv[2] = 0.6f;
            } else {
                hsv[2] = Math.min(hsv[2], 0.82f);
                hsv[1] = Math.max(hsv[1], 0.45f);
            }
            return Color.HSVToColor(255, hsv);
        } catch (Exception ignore) {
            return color;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * One section's cached gradient. Only ever touched from the drawing thread, and a lost race would
     * cost one extra shader, so the cache is deliberately unsynchronised.
     */
    private static class Glow {

        private final ConfigItem intensity;
        private final ConfigItem radiusPercent;
        private final ConfigItem passes;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] colors = new int[STOPS.length];

        private float cachedRadius;
        private int cachedColor;
        private int cachedIntensity;
        private int cachedPasses;

        Glow(ConfigItem intensity, ConfigItem radiusPercent, ConfigItem passes) {
            this.intensity = intensity;
            this.radiusPercent = radiusPercent;
            this.passes = passes;
        }

        void draw(Canvas canvas, float cx, float cy, float radius, int color) {
            if (radius <= 0f || (color & 0x00FFFFFF) == 0) {
                return;
            }
            final int passCount = Math.max(1, Math.min(4, passes.Int()));
            final float outer = Math.min(radius * (radiusPercent.Int() / 100f), dp(MAX_RADIUS_DP));
            if (outer <= 1f) {
                return;
            }
            build(outer, passCount, color);

            canvas.save();
            canvas.translate(cx, cy);
            for (int i = 0; i < passCount; i++) {
                canvas.drawCircle(0, 0, outer, paint);
            }
            canvas.restore();
        }

        private void build(float outer, int passCount, int color) {
            final int strength = intensity.Int();
            if (paint.getShader() != null
                    && cachedRadius == outer
                    && cachedColor == color
                    && cachedIntensity == strength
                    && cachedPasses == passCount) {
                return;
            }
            // The cap is what keeps several passes from stacking into a solid disc: it is the alpha
            // at which the requested share of light is left over once every pass has been stamped.
            final int perPass = clamp((int) ((1 - Math.pow(TOTAL_FALLOFF, 1.0 / passCount)) * 255), 1, MAX_ALPHA);
            // Sampled colours are muted and the theme may be light, so the colour is fixed up once
            // here rather than per stop.
            final int base = adaptForBg(boostColor(color));

            for (int i = 0; i < STOPS.length; i++) {
                final int tinted = blendWhite(base, STOP_WHITE[i]);
                int alpha = clamp((int) (strength * STOP_ALPHA[i]), 0, 255);
                if (alpha > perPass) {
                    alpha = perPass;
                }
                colors[i] = (alpha << 24) | (tinted & 0x00FFFFFF);
            }

            paint.setShader(new RadialGradient(0, 0, outer, colors, STOPS, Shader.TileMode.CLAMP));
            cachedRadius = outer;
            cachedColor = color;
            cachedIntensity = strength;
            cachedPasses = passCount;
        }

        /** Pulls each channel towards white by {@code fraction}. */
        private int blendWhite(int color, float fraction) {
            if (fraction <= 0f) {
                return color;
            }
            final int r = (color >> 16) & 0xFF;
            final int g = (color >> 8) & 0xFF;
            final int b = color & 0xFF;
            return 0xFF000000
                    | (clamp((int) (r + (255 - r) * fraction), 0, 255) << 16)
                    | (clamp((int) (g + (255 - g) * fraction), 0, 255) << 8)
                    | clamp((int) (b + (255 - b) * fraction), 0, 255);
        }
    }
}
