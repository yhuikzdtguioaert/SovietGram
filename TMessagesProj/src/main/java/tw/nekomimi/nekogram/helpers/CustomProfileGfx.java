package tw.nekomimi.nekogram.helpers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.CornerPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.media.MediaMetadataRetriever;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.File;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Drawing primitives for the Custom Profile feature: avatar outlines, banner and background layers,
 * and the alpha/dim/fade wrapper they are all painted through.
 * <p>
 * Everything here is called from {@code onDraw}, so the shared {@link Paint}s and {@link Matrix} are
 * kept around instead of being allocated per frame. That makes the class UI-thread only, which is
 * where the profile lives anyway.
 */
public final class CustomProfileGfx {

    /** Polygons start here so a vertex points up rather than sideways. */
    private static final float START_ANGLE = -(float) Math.PI / 2;

    private static final float STAR_INNER = 0.42f;

    /** How far the fade smoothing may go at 100%, in dp. */
    private static final float MAX_SMOOTHING = 12f;

    /** Opaque to clear: the fade is applied with DST_IN, so only the alpha of these matters. */
    private static final int[] FADE_COLORS = {0xFFFFFFFF, 0x00FFFFFF};

    /**
     * A heart traced clockwise from the tip at the bottom, in a box running -1..1 on both axes. Four
     * numbers per segment: the anchor to curve to, then the control point that leads to it.
     */
    private static final float[] HEART = {
            -0.62f, 0.45f, -0.22f, 0.94f,
            -1.00f, -0.10f, -1.06f, 0.44f,
            -0.72f, -0.92f, -1.20f, -0.80f,
            0.00f, -0.42f, -0.30f, -1.06f,
            0.72f, -0.92f, 0.30f, -1.06f,
            1.00f, -0.10f, 1.20f, -0.80f,
            0.62f, 0.45f, 1.06f, 0.44f,
            0.00f, 1.00f, 0.22f, 0.94f,
    };

    /** Pulls the heart's control points back towards their chords so the lobes stay inside the box. */
    private static final float HEART_CONTROL = 0.8f;

    /** The flower is laid out on a 24 unit grid, the size the icons it copies were drawn at. */
    private static final float FLOWER_PETAL = 3.1f / 24f;
    private static final float FLOWER_DISTANCE = 3.4f / 24f;
    private static final int FLOWER_PETALS = 6;

    private static final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint dimPaint = new Paint();
    private static final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private static final Matrix matrix = new Matrix();

    static {
        fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    private CustomProfileGfx() {
    }

    public static int clamp(int v, int min, int max) {
        return v < min ? min : Math.min(v, max);
    }

    public static float clampF(float v, float min, float max) {
        return v < min ? min : Math.min(v, max);
    }

    /** Black at the requested strength; the caller draws it SRC_OVER over whatever it wants darkened. */
    public static int dimColor(int dim) {
        return Color.argb((int) (clamp(dim, 0, 100) / 100f * 255), 0, 0, 0);
    }

    /**
     * The avatar outline for one of the eight shapes, in the given box.
     *
     * @param radiusDp         corner radius of shape 1, clamped so it can never exceed half the box.
     * @param smoothingPercent rounds off every corner of the finished path; 0 leaves it sharp.
     */
    public static Path shapePath(int shape, float left, float top, float right, float bottom,
                                 float radiusDp, int smoothingPercent) {
        return shapePath(shape, left, top, right, bottom, radiusDp, smoothingPercent, null);
    }

    /**
     * {@link #shapePath(int, float, float, float, float, float, int)} with the free-form outline a
     * look can carry: shape 8 traces {@code points}, which are {@code x, y} pairs as fractions of the
     * box, in order. Fewer than three points is not an outline, so it falls back to the circle rather
     * than drawing a line or nothing at all.
     */
    public static Path shapePath(int shape, float left, float top, float right, float bottom,
                                 float radiusDp, int smoothingPercent, @Nullable float[] points) {
        final Path path = new Path();
        final float width = right - left;
        final float height = bottom - top;
        if (width <= 0 || height <= 0) {
            return path;
        }
        final float cx = left + width / 2f;
        final float cy = top + height / 2f;
        final float size = Math.min(width, height);
        final float radius = size / 2f;

        switch (shape) {
            case 1: {
                final float corner = clampF(AndroidUtilities.dp(radiusDp), 0, size / 2f);
                path.addRoundRect(new RectF(left, top, right, bottom), corner, corner, Path.Direction.CW);
                break;
            }
            case 2:
                path.addRect(left, top, right, bottom, Path.Direction.CW);
                break;
            case 3:
                poly(path, 6, START_ANGLE, cx, cy, radius);
                break;
            case 4:
                poly(path, 5, START_ANGLE, cx, cy, radius);
                break;
            case 5:
                star(path, cx, cy, radius);
                break;
            case 6:
                heart(path, left, top, right, bottom);
                break;
            case 7:
                flower(path, cx, cy, size);
                break;
            case 8:
                if (points != null && points.length >= 6) {
                    custom(path, points, left, top, width, height);
                    break;
                }
                path.addCircle(cx, cy, radius, Path.Direction.CW);
                break;
            default:
                path.addCircle(cx, cy, radius, Path.Direction.CW);
                break;
        }
        return smoothingPercent > 0 ? smooth(path, smoothingPercent) : path;
    }

    /** The look's own outline, in the order its author drew it. */
    private static void custom(Path path, float[] points, float left, float top, float width, float height) {
        for (int i = 0; i + 1 < points.length; i += 2) {
            final float x = left + clampF(points[i], 0f, 1f) * width;
            final float y = top + clampF(points[i + 1], 0f, 1f) * height;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
    }

    /**
     * The {@code [[x, y], …]} array a look stores its outline as, flattened. Empty for anything
     * unparsable — a broken outline draws as the circle, never as a crash on the draw path.
     */
    public static float[] parsePoints(@Nullable String json) {
        if (TextUtils.isEmpty(json)) {
            return new float[0];
        }
        try {
            final org.json.JSONArray array = new org.json.JSONArray(json);
            final float[] points = new float[array.length() * 2];
            int at = 0;
            for (int i = 0; i < array.length(); i++) {
                final org.json.JSONArray pair = array.optJSONArray(i);
                if (pair == null || pair.length() < 2) {
                    continue;
                }
                points[at++] = clampF((float) pair.optDouble(0, 0), 0f, 1f);
                points[at++] = clampF((float) pair.optDouble(1, 0), 0f, 1f);
            }
            if (at == points.length) {
                return points;
            }
            final float[] trimmed = new float[at];
            System.arraycopy(points, 0, trimmed, 0, at);
            return trimmed;
        } catch (Throwable ignore) {
            return new float[0];
        }
    }

    private static void poly(Path path, int sides, float startAngle, float cx, float cy, float r) {
        for (int i = 0; i < sides; i++) {
            final double angle = startAngle + 2 * Math.PI * i / sides;
            final float x = cx + (float) Math.cos(angle) * r;
            final float y = cy + (float) Math.sin(angle) * r;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
    }

    /** Ten vertices alternating between the outer radius and a much smaller inner one. */
    private static void star(Path path, float cx, float cy, float r) {
        for (int i = 0; i < 10; i++) {
            final double angle = START_ANGLE + Math.PI * i / 5;
            final float radius = i % 2 == 0 ? r : r * STAR_INNER;
            final float x = cx + (float) Math.cos(angle) * radius;
            final float y = cy + (float) Math.sin(angle) * radius;
            if (i == 0) {
                path.moveTo(x, y);
            } else {
                path.lineTo(x, y);
            }
        }
        path.close();
    }

    /**
     * Traces {@link #HEART} in its own -1..1 space and then maps whatever it actually covers onto the
     * box, so the shape fills the avatar however the table is tuned.
     */
    private static void heart(Path path, float left, float top, float right, float bottom) {
        float fromX = 0;
        float fromY = 1;
        path.moveTo(fromX, fromY);
        for (int i = 0; i < HEART.length; i += 4) {
            final float x = HEART[i];
            final float y = HEART[i + 1];
            // Pulled towards the chord so the lobes stay in the box instead of ballooning past it.
            final float midX = (fromX + x) / 2f;
            final float midY = (fromY + y) / 2f;
            path.quadTo(midX + (HEART[i + 2] - midX) * HEART_CONTROL,
                    midY + (HEART[i + 3] - midY) * HEART_CONTROL, x, y);
            fromX = x;
            fromY = y;
        }
        path.close();

        final RectF bounds = new RectF();
        path.computeBounds(bounds, true);
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        matrix.setRectToRect(bounds, new RectF(left, top, right, bottom), Matrix.ScaleToFit.FILL);
        path.transform(matrix);
    }

    private static void flower(Path path, float cx, float cy, float size) {
        final float petal = size * FLOWER_PETAL;
        final float distance = size * FLOWER_DISTANCE;
        for (int i = 0; i < FLOWER_PETALS; i++) {
            final double angle = START_ANGLE + 2 * Math.PI * i / FLOWER_PETALS;
            path.addCircle(cx + (float) Math.cos(angle) * distance,
                    cy + (float) Math.sin(angle) * distance, petal, Path.Direction.CW);
        }
        path.addCircle(cx, cy, distance, Path.Direction.CW);
    }

    /** Corner rounding has to go through a Paint, as CornerPathEffect only exists as a stroke effect. */
    private static Path smooth(Path source, int smoothingPercent) {
        final float radius = AndroidUtilities.dp(clamp(smoothingPercent, 0, 100) / 100f * MAX_SMOOTHING);
        if (radius <= 0) {
            return source;
        }
        final Path smoothed = new Path();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setPathEffect(new CornerPathEffect(radius));
        paint.getFillPath(source, smoothed);
        return smoothed;
    }

    /**
     * The white-to-clear mask a faded layer is multiplied by, or null when the layer is not faded.
     * The caller paints it with DST_IN into an ARGB_8888 layer.
     * <p>
     * Both kinds are laid out in the unit square and scaled onto the box, so the numbers mean the same
     * thing whatever shape the banner happens to be: the centre is a fraction of each side, and the
     * radius is a percent where 100 spans the box and 200 reaches twice as far, so only the middle of
     * the gradient lands inside it. The radial one stays a circle — its radius comes off the longest
     * side rather than from stretching the shader — which is what keeps a radial fade from turning into
     * an oval on a banner three times wider than it is tall.
     *
     * @param mode 0 none, 1 linear along {@code angleDeg}, 2 radial out from the centre.
     */
    @Nullable
    public static Shader fadeShader(int mode, int angleDeg, int radiusPercent,
                                    int centerXPercent, int centerYPercent, float width, float height) {
        if (mode == 0 || width <= 0 || height <= 0) {
            return null;
        }
        final float r = clamp(radiusPercent, 20, 200) / 100f;
        final float cx = clampF(centerXPercent / 100f, 0f, 1f);
        final float cy = clampF(centerYPercent / 100f, 0f, 1f);
        if (mode == 2) {
            final float radius = r * 0.5f * Math.max(width, height);
            if (radius <= 0) {
                return null;
            }
            return new RadialGradient(cx * width, cy * height, radius, FADE_COLORS, null,
                    Shader.TileMode.CLAMP);
        }
        final double theta = Math.toRadians(angleDeg);
        final float half = r * 0.5f;
        final float dx = (float) Math.sin(theta) * half;
        final float dy = -(float) Math.cos(theta) * half;
        return new LinearGradient((cx - dx) * width, (cy - dy) * height,
                (cx + dx) * width, (cy + dy) * height, FADE_COLORS, null, Shader.TileMode.CLAMP);
    }

    /**
     * The mask that softens the avatar's rim, or null when it is not faded.
     * <p>
     * This is a different effect from {@link #fadeShader} and not a variant of it: the picture stays
     * whole and opaque out to {@code radiusPercent}, and only past that does it feather towards the
     * transparency {@code fadePercent} asks for. So 100 fades the outermost pixels away completely and
     * 30 only takes the edge off, and the radius decides how wide the soft band is rather than how
     * much of the avatar survives. Three stops, the first two both opaque — that flat middle is the
     * whole point.
     */
    @Nullable
    public static Shader avatarFadeShader(int fadePercent, int radiusPercent, float width, float height) {
        if (fadePercent <= 0 || width <= 0 || height <= 0) {
            return null;
        }
        final float radius = Math.max(width, height) * 0.5f;
        // Kept off both ends: a feather starting at the exact centre or the exact edge is either a
        // gradient with two identical stops or no gradient at all.
        final float mid = clampF(radiusPercent / 100f, 0.02f, 0.98f);
        final int edge = clamp(Math.round((1f - clamp(fadePercent, 0, 100) / 100f) * 255f), 0, 255);
        return new RadialGradient(width / 2f, height / 2f, radius,
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, (edge << 24) | 0x00FFFFFF},
                new float[]{0f, mid, 1f}, Shader.TileMode.CLAMP);
    }

    /**
     * The banner gradient described by the customProfileGradient* settings of the look being drawn.
     * <p>
     * Built in the unit square and stretched onto the box with a local matrix, so a radial gradient
     * fills the banner as an ellipse rather than as a circle with the corners left over. That is the
     * one place the gradient and the fade differ on purpose — a background wash is expected to cover
     * everything, an alpha mask is expected to keep its shape.
     */
    @Nullable
    public static Shader gradientShader(float width, float height) {
        if (width <= 0 || height <= 0) {
            return null;
        }
        final int count = clamp(CustomProfileHelper.cfgInt(NekoConfig.customProfileGradientCount), 2, 3);
        final int[] colors = new int[count];
        colors[0] = CustomProfileHelper.cfgInt(NekoConfig.customProfileGradientColor1);
        colors[1] = CustomProfileHelper.cfgInt(NekoConfig.customProfileGradientColor2);
        if (count > 2) {
            colors[2] = CustomProfileHelper.cfgInt(NekoConfig.customProfileGradientColor3);
        }
        // Null stops spread the colours evenly, which is 0/1 for two and 0/0.5/1 for three — the same
        // stops the reference writes out by hand.
        final float r = clamp(CustomProfileHelper.cfgInt(NekoConfig.customProfileGradientRadius), 20, 200) / 100f;
        final Shader shader;
        if (CustomProfileHelper.cfgBool(NekoConfig.customProfileGradientRadial)) {
            float radius = r * 0.5f;
            if (radius <= 0) {
                radius = 0.5f;
            }
            shader = new RadialGradient(
                    clampF(CustomProfileHelper.cfgInt(NekoConfig.customProfileGradientCenterX) / 100f, 0f, 1f),
                    clampF(CustomProfileHelper.cfgInt(NekoConfig.customProfileGradientCenterY) / 100f, 0f, 1f),
                    radius, colors, null, Shader.TileMode.CLAMP);
        } else {
            // A linear gradient is always struck through the middle; only its angle and length move.
            final double theta = Math.toRadians(CustomProfileHelper.cfgInt(NekoConfig.customProfileGradientAngle) % 360);
            final float half = r * 0.5f;
            final float dx = (float) Math.sin(theta) * half;
            final float dy = -(float) Math.cos(theta) * half;
            shader = new LinearGradient(0.5f - dx, 0.5f - dy, 0.5f + dx, 0.5f + dy,
                    colors, null, Shader.TileMode.CLAMP);
        }
        matrix.reset();
        matrix.setScale(width, height);
        shader.setLocalMatrix(matrix);
        return shader;
    }

    /**
     * Runs {@code content} through the alpha, dim and fade every banner and background share. Plain
     * fully-opaque content is drawn straight, without a layer, since that is the common case and this
     * is on the draw path.
     */
    public static void drawFaded(Canvas canvas, float width, float height, int fadeMode, int fadeAngle,
                                 int fadeRadius, int fadeCenterX, int fadeCenterY,
                                 int alphaPercent, int dimPercent, Runnable content) {
        if (fadeMode == 0 && alphaPercent >= 100 && dimPercent <= 0) {
            content.run();
            return;
        }
        final int alpha = (int) (clamp(alphaPercent, 0, 100) / 100f * 255);
        final int base = canvas.saveLayerAlpha(0, 0, width, height, alpha);
        content.run();
        if (dimPercent > 0) {
            dimPaint.setColor(dimColor(dimPercent));
            canvas.drawRect(0, 0, width, height, dimPaint);
        }
        final Shader fade = fadeShader(fadeMode, fadeAngle, fadeRadius, fadeCenterX, fadeCenterY, width, height);
        if (fade != null) {
            // Into the layer the content went into, not a fresh one: DST_IN has to have the content as
            // its destination to mask anything, and the layer keeps it off the rest of the canvas.
            fadePaint.setShader(fade);
            canvas.drawRect(0, 0, width, height, fadePaint);
            fadePaint.setShader(null);
        }
        canvas.restoreToCount(base);
    }

    /**
     * Decodes a picture the user picked, downsampled to roughly {@code maxSide}. Null on anything the
     * caller cannot do something about: no path, a file that went away, a decode that failed.
     */
    @Nullable
    public static Bitmap loadScaled(String path, int maxSide) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        try {
            final File file = new File(path);
            if (!file.exists() || file.length() == 0) {
                return null;
            }
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            final int longest = Math.max(bounds.outWidth, bounds.outHeight);
            if (longest <= 0) {
                // Not a picture the bitmap decoder knows, which is what a video looks like from here.
                return videoFrame(path, maxSide);
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            int sample = 1;
            while (longest / (sample * 2) >= Math.max(1, maxSide)) {
                sample *= 2;
            }
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, options);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * The first frame of a video, scaled to roughly {@code maxSide}, or null when the file is not a
     * video or holds no readable frame.
     *
     * <p>This is what a video banner is drawn as when the player cannot open it at all: the bundled
     * ffmpeg carries decoders for h264 and gif and for nothing else, while a fair share of published
     * banners are HEVC, and it also drops any video over 3840px in either direction. Android's own
     * decoders know all of it, so one frame is always available even where playback is not — and a
     * frozen banner is a look that applied, where an empty one is indistinguishable from a look with
     * no banner at all.
     */
    @Nullable
    private static Bitmap videoFrame(String path, int maxSide) {
        final MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(path);
            final Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (frame == null) {
                return null;
            }
            final float scale = Math.min(1f, (float) Math.max(1, maxSide)
                    / Math.max(frame.getWidth(), frame.getHeight()));
            if (scale >= 1f) {
                return frame;
            }
            return Bitmap.createScaledBitmap(frame, Math.max(1, Math.round(frame.getWidth() * scale)),
                    Math.max(1, Math.round(frame.getHeight() * scale)), true);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignore) {
            }
        }
    }

    /**
     * The banner's colour, gradient or picture on its own. Alpha, dim and fade are
     * {@link #drawFaded}'s job, so this is what the caller passes it as the content.
     *
     * @param type the banner type the caller resolved. Passed in rather than read here because a peer's
     *             look has no picture to draw and its type is degraded before it gets this far.
     */
    public static void drawBannerContent(Canvas canvas, float width, float height, int type, @Nullable Bitmap picture) {
        if (type == 1) {
            fillColor(canvas, width, height, CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerColor));
        } else if (type == 2) {
            fillShader(canvas, width, height, gradientShader(width, height));
        } else if (type == 3 || type == 4) {
            centreCrop(canvas, width, height, picture);
        }
    }

    /** The same for the list background, off the customProfileBackground* settings. */
    public static void drawBackgroundContent(Canvas canvas, float width, float height, int type, @Nullable Bitmap picture) {
        if (type == 1) {
            fillColor(canvas, width, height, CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundColor));
        } else if (type == 3 || type == 4) {
            // Type 4 only reaches here when the player could not open the file; the still branch then
            // draws whatever the decoder managed, exactly as the banner's does.
            centreCrop(canvas, width, height, picture);
        }
    }

    private static void fillColor(Canvas canvas, float width, float height, int color) {
        fillPaint.setShader(null);
        fillPaint.setColor(color);
        canvas.drawRect(0, 0, width, height, fillPaint);
    }

    private static void fillShader(Canvas canvas, float width, float height, @Nullable Shader shader) {
        if (shader == null) {
            return;
        }
        fillPaint.setColor(Color.BLACK);
        fillPaint.setShader(shader);
        canvas.drawRect(0, 0, width, height, fillPaint);
        fillPaint.setShader(null);
    }

    /** Telegram's own story colours, so a shaped ring still reads as a story ring. */
    private static final int[] STORY_UNREAD = {0xFF34AADF, 0xFF46CF5A};
    private static final int STORY_READ = 0xFF8E8E93;

    private static final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Strokes {@code path} the way the stock ring strokes its circle. The segmented arcs the real ring
     * draws for several stories are dropped on purpose: they are placed by angle around a circle and
     * have nowhere to sit on a heart or a star.
     */
    public static void drawStoryRing(Canvas canvas, Path path, boolean unread, float width, float height) {
        if (path == null || path.isEmpty()) {
            return;
        }
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setStrokeWidth(AndroidUtilities.dpf2(unread ? 3f : 1.8f));
        if (unread) {
            ringPaint.setColor(Color.BLACK);
            matrix.reset();
            matrix.setScale(width / 256f, height / 256f);
            final LinearGradient gradient = new LinearGradient(0, 0, 256f, 256f, STORY_UNREAD,
                    null, Shader.TileMode.CLAMP);
            gradient.setLocalMatrix(matrix);
            ringPaint.setShader(gradient);
        } else {
            ringPaint.setShader(null);
            ringPaint.setColor(STORY_READ);
        }
        canvas.drawPath(path, ringPaint);
        ringPaint.setShader(null);
    }

    private static void centreCrop(Canvas canvas, float width, float height, @Nullable Bitmap picture) {
        if (picture == null || picture.isRecycled() || width <= 0 || height <= 0) {
            return;
        }
        final float scale = Math.max(width / picture.getWidth(), height / picture.getHeight());
        matrix.reset();
        matrix.setScale(scale, scale);
        matrix.postTranslate((width - picture.getWidth() * scale) / 2f,
                (height - picture.getHeight() * scale) / 2f);
        canvas.drawBitmap(picture, matrix, bitmapPaint);
    }
}
