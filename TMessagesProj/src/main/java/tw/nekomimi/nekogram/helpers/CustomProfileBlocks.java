package tw.nekomimi.nekogram.helpers;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.messenger.Utilities;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * The frosted glass behind a look's profile rows — the Blocks blur.
 *
 * <p>The Blocks setting recolours every row surface and can make it translucent, which is a colour
 * and goes through {@link CustomProfileHelper#themedColor}. The blur cannot: it is not a property of
 * the surface but of what is behind it, so it has to be painted, into the same rounded rectangle the
 * row card is about to be painted into. That is why the value was imported from workshop looks and
 * kept in the settings for a while without a single line reading it — there was nowhere for a colour
 * to carry it.
 *
 * <p>What gets blurred is the look's own background, drawn again at a fraction of its size. Not a
 * capture of the screen: the rows sit on top of that background and nothing else, so re-drawing it is
 * both exact and far cheaper than reading pixels back from a view. A video background is frosted
 * through its poster frame, which is the one still we already hold for it.
 *
 * <p>The scale and radius come from the reference's own pairing: it blurs a picture shrunk by a
 * factor near twelve, because a stack blur of radius r on a picture shrunk by s looks like a blur of
 * r×s on the original and costs a fraction of it. So the pair is chosen to hit the requested spread
 * with the shrink as close to twelve as the arithmetic allows.
 */
public final class CustomProfileBlocks {

    /** The reference's own bounds on the pair. */
    private static final int MIN_SCALE = 4;
    private static final int MAX_SCALE = 16;
    private static final int TARGET_SCALE = 12;
    private static final int MAX_RADIUS = 30;

    /** The spread a blur of 0 would have; what keeps the lowest settings visibly frosted. */
    private static final int SPREAD_BASE = 3;

    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Matrix matrix = new Matrix();
    private static final Path path = new Path();
    private static final float[] radii = new float[8];
    private static final RectF cardRect = new RectF();
    private static final RectF previousCard = new RectF();
    private static final RectF cornerArc = new RectF();
    private static final RectF patchRect = new RectF();
    private static final Path joinPatch = new Path();
    private static float previousRadius;
    private static float previousAlpha;
    private static boolean hasPrevious;

    @Nullable
    private static Bitmap backdrop;
    @Nullable
    private static BitmapShader backdropShader;
    private static long backdropKey;

    private CustomProfileBlocks() {
    }

    /** A section draw pass must never join against a card from an earlier frame/profile. */
    public static void beginFrame() {
        hasPrevious = false;
        signatureFresh = false;
        backgroundKnown = false;
    }

    /** Drops the frosted copy. Called whenever the look changes under it. */
    public static void invalidate() {
        backdrop = null;
        backdropShader = null;
        backdropKey = 0;
        hasPrevious = false;
        signatureFresh = false;
        backgroundKnown = false;
    }

    /**
     * Paints one row card the way the look asks for: the frosted backdrop, then the Blocks colour at
     * its own opacity. Answers whether it painted, so the caller can fall back to the stock card.
     *
     * <p>The card is painted here rather than recoloured through
     * {@link CustomProfileHelper#themedColor} because the stock painter resolves its colour against
     * the fragment's resources provider, which a profile does not have — so the Blocks colour reached
     * a handful of cell types that set their own background and never the card surface underneath
     * them, and its opacity therefore had nothing to be transparent against. Painting the card is what
     * makes the colour, the opacity and the blur all mean what the look says they mean.
     *
     * @param rect  the card, in {@code list} coordinates, exactly as the card will be drawn.
     * @param alpha the card's own alpha, so a row animating in or out fades with it.
     */
    public static boolean drawCard(Canvas canvas, RectF rect, float topRadius, float bottomRadius,
                                   float alpha, @Nullable View list) {
        if (list == null || alpha <= 0 || !CustomProfileHelper.isEnabled()) {
            return false;
        }
        if (!CustomProfileHelper.cfgBool(NekoConfig.customProfileBlocksEnabled)) {
            return false;
        }
        if (CustomProfileHelper.cfgBool(NekoConfig.customProfileBlocksRadiusEnabled)) {
            final float radius = AndroidUtilities.dpf2(CustomProfileGfx.clamp(
                    CustomProfileHelper.cfgInt(NekoConfig.customProfileBlocksRadius), 0, 30));
            // Zero denotes an interior edge of an existing card, not a default radius.
            if (topRadius > 0) topRadius = radius;
            if (bottomRadius > 0) bottomRadius = radius;
        }
        cardRect.set(rect);
        if (CustomProfileHelper.cfgBool(NekoConfig.customProfileBlocksJoin) && hasPrevious) {
            final float gap = cardRect.top - previousCard.bottom;
            if (gap >= 0 && gap <= AndroidUtilities.dpf2(16)
                    && Math.abs(cardRect.left - previousCard.left) < 1
                    && Math.abs(cardRect.right - previousCard.right) < 1
                    && Math.abs(alpha - previousAlpha) < 0.01f) {
                patchJoin(canvas, Math.min(alpha, previousAlpha), list);
                cardRect.top = previousCard.bottom;
                topRadius = 0;
            }
        }
        drawBlur(canvas, cardRect, topRadius, bottomRadius, alpha, list);
        final int color = CustomProfileHelper.themedColor(Theme.key_windowBackgroundWhite,
                Theme.getColor(Theme.key_windowBackgroundWhite));
        paint.setShader(null);
        paint.setColor(color);
        paint.setAlpha(CustomProfileGfx.clamp(Math.round(Color.alpha(color) * alpha), 0, 255));
        fill(canvas, cardRect, topRadius, bottomRadius);
        previousCard.set(cardRect);
        previousRadius = bottomRadius;
        previousAlpha = alpha;
        hasPrevious = true;
        return true;
    }

    /** Fill only the two missing corner wedges; repainting the whole translucent card doubles tint. */
    private static void patchJoin(Canvas canvas, float alpha, View list) {
        final float radius = Math.min(previousRadius,
                Math.min(previousCard.width() / 2, previousCard.height()));
        if (radius <= 0.5f) return;
        joinPatch.rewind();
        final float bottom = previousCard.bottom;
        final float left = previousCard.left;
        final float right = previousCard.right;
        joinPatch.moveTo(left, bottom - radius);
        joinPatch.lineTo(left, bottom);
        joinPatch.lineTo(left + radius, bottom);
        cornerArc.set(left, bottom - radius * 2, left + radius * 2, bottom);
        joinPatch.arcTo(cornerArc, 90, 90);
        joinPatch.close();
        joinPatch.moveTo(right, bottom - radius);
        joinPatch.lineTo(right, bottom);
        joinPatch.lineTo(right - radius, bottom);
        cornerArc.set(right - radius * 2, bottom - radius * 2, right, bottom);
        joinPatch.arcTo(cornerArc, 90, -90);
        joinPatch.close();
        patchRect.set(left, bottom - radius, right, bottom);
        int save = canvas.save();
        try {
            canvas.clipPath(joinPatch);
            drawBlur(canvas, patchRect, 0, 0, alpha, list);
            int color = CustomProfileHelper.themedColor(Theme.key_windowBackgroundWhite,
                    Theme.getColor(Theme.key_windowBackgroundWhite));
            paint.setShader(null);
            paint.setColor(color);
            paint.setAlpha(CustomProfileGfx.clamp(Math.round(Color.alpha(color) * alpha), 0, 255));
            canvas.drawRect(patchRect, paint);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    /**
     * The frosted copy of the background, inside the card's shape.
     *
     * <p>Does nothing unless the look asks for a blur and has a background to frost — a card over the
     * plain theme colour has nothing behind it that blurring would change.
     */
    private static void drawBlur(Canvas canvas, RectF rect, float topRadius, float bottomRadius,
                                 float alpha, View list) {
        final int blur = CustomProfileGfx.clamp(CustomProfileHelper.cfgInt(NekoConfig.customProfileBlocksBlur), 0, 100);
        if (blur <= 0 || !hasBackground()) {
            return;
        }
        final int width = list.getWidth();
        final int height = list.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        final Bitmap frosted = backdrop(width, height, blur);
        if (frosted == null) {
            return;
        }
        if (backdropShader == null) {
            backdropShader = new BitmapShader(frosted, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        }
        final BitmapShader shader = backdropShader;
        // The backdrop was drawn in the list's own coordinates, only smaller, so scaling it back up is
        // the whole of the mapping — the card's rectangle lands on the same pixels it covers.
        matrix.reset();
        matrix.setScale((float) width / frosted.getWidth(), (float) height / frosted.getHeight());
        shader.setLocalMatrix(matrix);
        paint.setShader(shader);
        paint.setAlpha(CustomProfileGfx.clamp(Math.round(alpha * 255), 0, 255));
        fill(canvas, rect, topRadius, bottomRadius);
        paint.setShader(null);
    }

    private static void fill(Canvas canvas, RectF rect, float topRadius, float bottomRadius) {
        if (topRadius == bottomRadius) {
            canvas.drawRoundRect(rect, topRadius, topRadius, paint);
            return;
        }
        path.rewind();
        radii[0] = radii[1] = radii[2] = radii[3] = topRadius;
        radii[4] = radii[5] = radii[6] = radii[7] = bottomRadius;
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.drawPath(path, paint);
    }

    /**
     * The look's background, shrunk and blurred, rebuilt only when something it depends on has moved.
     * Null when it could not be built, which the caller treats as "no frost this frame" rather than as
     * an error — the card still gets its colour.
     */
    @Nullable
    private static Bitmap backdrop(int width, int height, int blur) {
        final long key = key(width, height, blur);
        final Bitmap cached = backdrop;
        if (cached != null && !cached.isRecycled() && key == backdropKey) {
            return cached;
        }
        // Only on a rebuild: this runs once per card per frame, and the search is thirty iterations.
        final int pair = pair(blur);
        final int scale = pair >> 8;
        final int radius = pair & 0xFF;
        final int smallWidth = Math.max(2, width / scale);
        final int smallHeight = Math.max(2, height / scale);
        try {
            final Bitmap small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(small);
            // Drawn at full size into a shrunken canvas rather than drawn small, so every fade centre,
            // gradient angle and crop lands where it would on screen.
            canvas.scale((float) smallWidth / width, (float) smallHeight / height);
            CustomProfileHelper.drawBackdrop(canvas, width, height);
            Utilities.stackBlurBitmap(small, radius);
            backdrop = small;
            backdropShader = null;
            backdropKey = key;
            return small;
        } catch (Throwable e) {
            FileLog.e(e);
            invalidate();
            return null;
        }
    }

    /**
     * What the frosted copy depends on: the size it is stretched over, the blur itself, and the
     * background that was drawn into it — identity of the source bitmap included, so a look swapped
     * for another one with the same numbers is still noticed.
     */
    private static long key(int width, int height, int blur) {
        long key = 17;
        key = key * 31 + width;
        key = key * 31 + height;
        key = key * 31 + blur;
        if (!signatureFresh) {
            frameSignature = CustomProfileHelper.backgroundSignature();
            signatureFresh = true;
        }
        key = key * 31 + frameSignature;
        return key;
    }

    /**
     * The background's signature, worked out once per section pass instead of once per card. It looks
     * at the picture's file, which is not free, and nothing it reads can change inside one pass of
     * the UI thread. {@link #beginFrame} and {@link #invalidate} start it over.
     */
    private static long frameSignature;
    private static boolean signatureFresh;
    /** Likewise {@link CustomProfileHelper#hasBackground()}, asked once per card otherwise. */
    private static boolean backgroundPresent;
    private static boolean backgroundKnown;

    private static boolean hasBackground() {
        if (!backgroundKnown) {
            backgroundPresent = CustomProfileHelper.hasBackground();
            backgroundKnown = true;
        }
        return backgroundPresent;
    }

    /**
     * The shrink and the blur radius to reach {@code blur}'s spread, packed as {@code scale << 8 |
     * radius}. Ported from the reference, ranges and preference included: the pair minimising the
     * distance from {@code scale × radius} to the spread, and among equals the one whose shrink is
     * nearest twelve.
     */
    private static int pair(int blur) {
        final int spread = Math.max(0, blur) + SPREAD_BASE;
        int bestRadius = 1;
        int bestScale = MIN_SCALE;
        int bestDistance = Integer.MAX_VALUE;
        int bestFromTarget = Integer.MAX_VALUE;
        for (int radius = 1; radius <= MAX_RADIUS; radius++) {
            int scale = Math.round((float) spread / radius);
            if (scale < MIN_SCALE) {
                scale = MIN_SCALE;
            } else if (scale > MAX_SCALE) {
                scale = MAX_SCALE;
            }
            final int distance = Math.abs(scale * radius - spread);
            final int fromTarget = Math.abs(scale - TARGET_SCALE);
            if (distance < bestDistance || (distance == bestDistance && fromTarget < bestFromTarget)) {
                bestRadius = radius;
                bestScale = scale;
                bestDistance = distance;
                bestFromTarget = fromTarget;
            }
        }
        return (bestScale << 8) | bestRadius;
    }
}
