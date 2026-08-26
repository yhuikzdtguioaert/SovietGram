package tw.nekomimi.nekogram.helpers.frame;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

/**
 * Draws a frame around a box, in the frame's own 256-unit space.
 *
 * <p>One painter is one contour: give it the avatar's outline with {@link #contour} and it keeps the
 * ribbon, the corner list and the mesh it needs between frames. That is why this is an object rather
 * than a pile of static methods — the profile page, the studio's preview and every node preview on
 * the studio's canvas are all drawing at once, each against a different shape, and a single shared
 * cache would rebuild on every draw.
 *
 * <p>Pictures come from a {@link Sources}, so the same painter serves the profile (which fetches
 * over the network) and the studio (which also has to play videos).
 */
public final class FramePainter {

    public static final float SPACE = 256f;
    public static final float AVATAR_DP = 84f;

    /** How many corners a frame's corner picture can be swapped in at. */
    private static final int CORNERS_MAX = 24;

    public interface Sources {
        /** @param corner whether the layer's corner picture is wanted rather than its own. */
        @Nullable
        Bitmap bitmap(FrameSpec.Layer layer, boolean corner);
    }

    private final float[] scratch = new float[4];
    private final float[] corners = new float[CORNERS_MAX];
    private final Matrix stamp = new Matrix();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final FrameRibbon ribbon = new FrameRibbon();
    private final float[] hang = new float[2];

    private float[] verts = new float[0];
    private float[] places = new float[FrameStrip.COLUMNS_MAX];
    private FrameContour contour = FrameContour.EMPTY;
    private int columns = FrameStrip.COLUMNS_MIN;
    private int cornerCount;

    private PorterDuffColorFilter tint;
    private int tintColor;

    /** dp to space: a frame's measurements are dp against an 84dp avatar. */
    public static float toSpace() {
        return SPACE / Math.max(1f, AndroidUtilities.dpf2(AVATAR_DP));
    }

    public void contour(@Nullable FrameContour value) {
        contour = value == null ? FrameContour.EMPTY : value;
        ribbon.contour(contour);
        columns = FrameStrip.columns(contour.length());
        cornerCount = contour.corners(FrameStamps.CORNER_DEGREES, corners);
        final int needed = FrameStrip.vertsLength(columns, FrameStrip.ROWS);
        if (verts.length < needed) {
            verts = new float[needed];
        }
    }

    public FrameContour contour() {
        return contour;
    }

    public float length() {
        return contour.length();
    }

    public int cornerCount() {
        return cornerCount;
    }

    /**
     * @param left,top   where the avatar's box starts.
     * @param width,height the box; the 256-unit space is mapped onto it.
     * @param toSpace    dp to space, from {@link #toSpace()}.
     * @param lift       how far off the outline everything sits before its own offset, in space units.
     * @param alpha      0..255, so a frame fades with the avatar it belongs to.
     */
    public boolean draw(@Nullable Canvas canvas, @Nullable FrameSpec spec,
                        float left, float top, float width, float height,
                        float toSpace, float lift, int alpha, @Nullable Sources sources) {
        if (canvas == null || spec == null || spec.isEmpty() || sources == null
                || contour.isEmpty() || alpha <= 0) {
            return false;
        }
        boolean drew = false;
        for (int i = 0; i < spec.layers().size(); i++) {
            final FrameSpec.Layer layer = spec.layers().get(i);
            if (layer.off) {
                continue;
            }
            final Bitmap bitmap = sources.bitmap(layer, false);
            if (bitmap == null || bitmap.isRecycled()) {
                continue;
            }
            paint.setColorFilter(filter(layer.tint));
            paint.setAlpha(alpha);
            drew |= switch (layer.mode) {
                case FrameSpec.MODE_STRIP ->
                        strip(canvas, bitmap, layer, left, top, width, height, toSpace, lift);
                case FrameSpec.MODE_STICKER ->
                        sticker(canvas, bitmap, layer, left, top, width, height, toSpace);
                case FrameSpec.MODE_PARTICLES ->
                        particles(canvas, bitmap, layer, left, top, width, height, toSpace, alpha);
                default ->
                        stamps(canvas, bitmap, layer, left, top, width, height, toSpace, lift, sources);
            };
        }
        paint.setColorFilter(null);
        paint.setAlpha(0xFF);
        return drew;
    }

    private boolean strip(Canvas canvas, Bitmap bitmap, FrameSpec.Layer layer,
                          float left, float top, float width, float height,
                          float toSpace, float lift) {
        final float inner = lift + AndroidUtilities.dpf2(layer.offset) * toSpace;
        final float outer = inner + AndroidUtilities.dpf2(layer.width) * toSpace;
        if (!FrameStrip.verts(ribbon, columns, FrameStrip.ROWS, inner, outer, placeOf(layer), verts)) {
            return false;
        }
        final int save = canvas.save();
        try {
            canvas.translate(left, top);
            canvas.scale(width / SPACE, height / SPACE);
            canvas.drawBitmapMesh(bitmap, columns, FrameStrip.ROWS, verts, 0, null, 0, paint);
            return true;
        } catch (Throwable e) {
            FileLog.e("FramePainter: ribbon failed: " + e.getMessage());
            return false;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private boolean sticker(Canvas canvas, Bitmap bitmap, FrameSpec.Layer layer,
                            float left, float top, float width, float height, float toSpace) {
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return false;
        }
        // A sticker does not follow the outline, so its orbit is a plain turn about the centre.
        final float spin = layer.orbit + layer.at * 360f;
        final float away = AndroidUtilities.dpf2(layer.offset) * toSpace / SPACE;
        if (!FrameStamps.hung(layer.x, layer.y, spin, away, hang)) {
            return false;
        }
        if (!FrameStamps.sticker(hang[0], hang[1], Math.round(layer.turn + spin), SPACE, places)) {
            return false;
        }
        final float target = AndroidUtilities.dpf2(layer.width) * layer.scale / 100f * toSpace;
        final int save = canvas.save();
        try {
            canvas.translate(left, top);
            canvas.scale(width / SPACE, height / SPACE);
            final float factor = target / bitmap.getHeight();
            stamp.reset();
            stamp.postTranslate(-bitmap.getWidth() / 2f, -bitmap.getHeight() / 2f);
            stamp.postScale(factor, factor);
            stamp.postRotate(places[2]);
            stamp.postTranslate(places[0], places[1]);
            canvas.drawBitmap(bitmap, stamp, paint);
            return true;
        } catch (Throwable e) {
            FileLog.e("FramePainter: sticker failed: " + e.getMessage());
            return false;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private boolean particles(Canvas canvas, Bitmap bitmap, FrameSpec.Layer layer,
                              float left, float top, float width, float height,
                              float toSpace, int alpha) {
        if (bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return false;
        }
        final int needed = layer.repeat * FrameParticles.STRIDE;
        if (places.length < needed) {
            places = new float[needed];
        }
        final float seconds = (SystemClock.uptimeMillis() % 3_600_000L) / 1000f;
        final float field = AndroidUtilities.dpf2(layer.field) * 2f * toSpace + SPACE;
        final int placed = FrameParticles.place(layer, seconds, field, SPACE / 2f, SPACE / 2f,
                AndroidUtilities.dpf2(layer.spread) * toSpace, places);
        if (placed <= 0) {
            return false;
        }
        final float target = AndroidUtilities.dpf2(layer.width) * layer.scale / 100f * toSpace;
        boolean drew = false;
        final int save = canvas.save();
        try {
            canvas.translate(left, top);
            canvas.scale(width / SPACE, height / SPACE);
            final float spin = layer.orbit + layer.at * 360f;
            if (spin != 0f) {
                canvas.rotate(spin, SPACE / 2f, SPACE / 2f);
            }
            for (int i = 0; i < placed; i++) {
                final int at = i * FrameParticles.STRIDE;
                final int particleAlpha = Math.round(alpha * places[at + 3]);
                if (particleAlpha <= 0) {
                    continue;
                }
                paint.setAlpha(particleAlpha);
                final float factor = places[at + 4] * target / bitmap.getHeight();
                stamp.reset();
                stamp.postTranslate(-bitmap.getWidth() / 2f, -bitmap.getHeight() / 2f);
                stamp.postScale(factor, factor);
                stamp.postRotate(places[at + 2]);
                stamp.postTranslate(places[at], places[at + 1]);
                canvas.drawBitmap(bitmap, stamp, paint);
                drew = true;
            }
            return drew;
        } catch (Throwable e) {
            FileLog.e("FramePainter: particles failed: " + e.getMessage());
            return drew;
        } finally {
            canvas.restoreToCount(save);
            paint.setAlpha(alpha);
        }
    }

    private boolean stamps(Canvas canvas, Bitmap bitmap, FrameSpec.Layer layer,
                           float left, float top, float width, float height,
                           float toSpace, float lift, Sources sources) {
        final int count = layer.mode == FrameSpec.MODE_STAMP ? layer.repeat : 1;
        final int needed = count * FrameStamps.STRIDE;
        if (places.length < needed) {
            places = new float[needed];
        }
        final float away = lift + AndroidUtilities.dpf2(layer.offset) * toSpace;
        final float start = placeOf(layer);
        final int placed = layer.mode == FrameSpec.MODE_STAMP
                ? FrameStamps.repeated(contour, count, start, away, places, scratch)
                : (FrameStamps.single(contour, start, away, places, scratch) ? 1 : 0);
        if (placed <= 0 || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
            return false;
        }
        final Bitmap cornerBitmap = layer.corner.length() == 0 ? null : sources.bitmap(layer, true);
        final float target = AndroidUtilities.dpf2(layer.width) * layer.scale / 100f * toSpace;
        final int save = canvas.save();
        try {
            canvas.translate(left, top);
            canvas.scale(width / SPACE, height / SPACE);
            for (int i = 0; i < placed; i++) {
                final int at = i * FrameStamps.STRIDE;
                final boolean onCorner = cornerBitmap != null && !cornerBitmap.isRecycled()
                        && FrameStamps.atCorner(corners, cornerCount,
                        FrameStamps.at(i, count, start));
                final Bitmap picture = onCorner ? cornerBitmap : bitmap;
                final float factor = target / picture.getHeight();
                stamp.reset();
                stamp.postTranslate(-picture.getWidth() / 2f, -picture.getHeight() / 2f);
                stamp.postScale(factor, factor);
                stamp.postRotate(places[at + 2]);
                stamp.postTranslate(places[at], places[at + 1]);
                canvas.drawBitmap(picture, stamp, paint);
            }
            return true;
        } catch (Throwable e) {
            FileLog.e("FramePainter: stamps failed: " + e.getMessage());
            return false;
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * Where a layer sits right now, as a fraction of the outline.
     *
     * <p>{@code spin} is degrees per second. The clock is folded into an hour, and an hour divides
     * exactly by any whole number of degrees per second, so the loop joins seamlessly — a spinning
     * layer never jumps when the counter wraps.
     */
    public static float phase(FrameSpec.Layer layer) {
        if (layer.spin == 0) {
            return layer.at;
        }
        return layer.at + (SystemClock.uptimeMillis() % 3_600_000L) * layer.spin / 360_000f;
    }

    /** Whether any layer moves, and so whether the view drawing it must ask for the next frame. */
    public static boolean moving(@Nullable FrameSpec spec) {
        if (spec == null) {
            return false;
        }
        for (int i = 0; i < spec.layers().size(); i++) {
            final FrameSpec.Layer layer = spec.layers().get(i);
            if (layer.spin != 0 || layer.mode == FrameSpec.MODE_PARTICLES) {
                return true;
            }
        }
        return false;
    }

    /**
     * A layer's place, with its orbit applied. {@code orbit} is degrees about the avatar's centre,
     * not arc length: a quarter turn moves a stamp to where the eye expects it on a square too.
     */
    private float placeOf(FrameSpec.Layer layer) {
        final float phase = phase(layer);
        return layer.orbit == 0 ? phase : contour.turned(phase, layer.orbit);
    }

    /**
     * Multiply, not source-in: a tint darkens the picture's own shading rather than flattening it to
     * one colour, which is what keeps a bevelled ribbon looking bevelled after it is tinted gold.
     */
    @Nullable
    private PorterDuffColorFilter filter(int color) {
        if (color == 0) {
            return null;
        }
        if (tint == null || color != tintColor) {
            tintColor = color;
            tint = new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY);
        }
        return tint;
    }
}
