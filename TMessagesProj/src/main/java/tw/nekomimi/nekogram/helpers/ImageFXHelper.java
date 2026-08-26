package tw.nekomimi.nekogram.helpers;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;

import androidx.annotation.Nullable;

import static org.telegram.messenger.LocaleController.getString;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import tw.nekomimi.nekogram.ui.ImageFXSheet;

import java.util.ArrayList;
import java.util.List;

/**
 * The twenty-four looks behind the ImageFX editor.
 * <p>
 * The reference plugin leaned on Pillow, so the maths here follows Pillow's own definitions rather
 * than being re-invented: the convolution kernels, scales and offsets are the ones from
 * {@code ImageFilter}, and {@code ImageEnhance} is reproduced as a blend against a degenerate
 * image, which is exactly what Pillow does. That keeps a filter looking the same as it did before.
 * <p>
 * Every method hands back a fresh bitmap and leaves the source alone; the caller owns both.
 */
public final class ImageFXHelper {

    /** One entry in the carousel. */
    public static final class Filter {
        public final String key;
        public final int nameRes;

        Filter(String key, int nameRes) {
            this.key = key;
            this.nameRes = nameRes;
        }
    }

    public static final String ORIGINAL = "original";

    public static final List<Filter> FILTERS = buildFilters();

    private static List<Filter> buildFilters() {
        final List<Filter> list = new ArrayList<>();
        list.add(new Filter(ORIGINAL, R.string.ImageFXOriginal));
        list.add(new Filter("sepia", R.string.ImageFXSepia));
        list.add(new Filter("blur", R.string.ImageFXBlur));
        list.add(new Filter("grayscale", R.string.ImageFXGrayscale));
        list.add(new Filter("invert", R.string.ImageFXInvert));
        list.add(new Filter("contour", R.string.ImageFXContour));
        list.add(new Filter("emboss", R.string.ImageFXEmboss));
        list.add(new Filter("sharpen", R.string.ImageFXSharpen));
        list.add(new Filter("detail", R.string.ImageFXDetail));
        list.add(new Filter("smooth", R.string.ImageFXSmooth));
        list.add(new Filter("edge_enhance", R.string.ImageFXEdgeEnhance));
        list.add(new Filter("retro", R.string.ImageFXRetro));
        list.add(new Filter("cool", R.string.ImageFXCool));
        list.add(new Filter("neon", R.string.ImageFXNeon));
        list.add(new Filter("hdr", R.string.ImageFXHdr));
        list.add(new Filter("posterize", R.string.ImageFXPosterize));
        list.add(new Filter("solarize", R.string.ImageFXSolarize));
        list.add(new Filter("sunshine", R.string.ImageFXSunshine));
        list.add(new Filter("gold", R.string.ImageFXGold));
        list.add(new Filter("glitch", R.string.ImageFXGlitch));
        list.add(new Filter("deep_fried", R.string.ImageFXDeepFried));
        list.add(new Filter("pixel", R.string.ImageFXPixel));
        list.add(new Filter("thermal", R.string.ImageFXThermal));
        list.add(new Filter("gigachad", R.string.ImageFXGigachad));
        return list;
    }

    private ImageFXHelper() {
    }

    // ----------------------------------------------------------------- kernels

    /** Pillow's {@code ImageFilter} kernels, verbatim: values, then scale, then offset. */
    private static final float[] K_CONTOUR = {-1, -1, -1, -1, 8, -1, -1, -1, -1};
    private static final float[] K_EMBOSS = {-1, 0, 0, 0, 1, 0, 0, 0, 0};
    private static final float[] K_SHARPEN = {-2, -2, -2, -2, 32, -2, -2, -2, -2};
    private static final float[] K_DETAIL = {0, -1, 0, -1, 10, -1, 0, -1, 0};
    private static final float[] K_EDGE_ENHANCE_MORE = {-1, -1, -1, -1, 9, -1, -1, -1, -1};
    private static final float[] K_SMOOTH = {1, 1, 1, 1, 5, 1, 1, 1, 1};
    private static final float[] K_SMOOTH_MORE = {
            1, 1, 1, 1, 1,
            1, 5, 5, 5, 1,
            1, 5, 44, 5, 1,
            1, 5, 5, 5, 1,
            1, 1, 1, 1, 1};

    // ------------------------------------------------------------------ public

    /**
     * Runs one filter over a bitmap.
     *
     * @return the filtered copy, or null when it could not be done — most often because there was
     *         not enough memory for the intermediate buffers.
     */
    @Nullable
    public static Bitmap apply(Bitmap src, String key) {
        if (src == null || src.isRecycled()) {
            return null;
        }
        KEEP.set(src);
        try {
            return applyInternal(src, key);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            KEEP.remove();
        }
    }

    private static Bitmap applyInternal(Bitmap src, String key) {
        switch (key) {
            case "sepia":
                return colorize(src, 0xFF704214, 0xFFC2B280);
            case "blur":
                return blur(src, Math.max(1f, src.getWidth() * 0.008f));
            case "grayscale":
                return grayscale(src);
            case "invert":
                return invert(src);
            case "contour":
                return convolve(src, K_CONTOUR, 3, 1f, 255f);
            case "emboss":
                return convolve(src, K_EMBOSS, 3, 1f, 128f);
            case "sharpen":
                return convolve(src, K_SHARPEN, 3, 16f, 0f);
            case "detail":
                return convolve(src, K_DETAIL, 3, 6f, 0f);
            case "smooth":
                return convolve(src, K_SMOOTH_MORE, 5, 100f, 0f);
            case "edge_enhance":
                return convolve(src, K_EDGE_ENHANCE_MORE, 3, 1f, 0f);
            case "retro":
                return channels(src, 1.15f, 12, 1.05f, 4, 0.85f, 0);
            case "cool":
                return channels(src, 0.85f, 0, 1.05f, 5, 1.2f, 15);
            case "neon":
                return colorize(src, 0xFF00FFFF, 0xFFFF00FF);
            case "hdr":
                return sharpness(contrast(src, 1.4f), 1.6f);
            case "posterize":
                return posterize(src, 3);
            case "solarize":
                return solarize(src, 128);
            case "sunshine":
                return channels(brightness(saturation(src, 1.4f), 1.1f), 1.05f, 5, 1f, 0, 1f, 0);
            case "gold":
                return colorize(src, 0xFF1F1410, 0xFFFFD700);
            case "glitch":
                return glitch(src, Math.max(1f, src.getWidth() * 0.012f));
            case "deep_fried":
                return sharpness(saturation(contrast(
                        pixelate(src, Math.max(2, src.getWidth() / 100)), 2.5f), 3f), 2f);
            case "pixel":
                return pixelate(src, 8);
            case "thermal":
                return thermal(src);
            case "gigachad":
                return sharpness(contrast(grayscale(src), 2.2f), 3f);
            default:
                return copy(src);
        }
    }

    // ----------------------------------------------------------------- helpers

    private static Bitmap copy(Bitmap src) {
        return src.copy(Bitmap.Config.ARGB_8888, true);
    }

    private static int[] pixelsOf(Bitmap src) {
        final int[] pixels = new int[src.getWidth() * src.getHeight()];
        src.getPixels(pixels, 0, src.getWidth(), 0, 0, src.getWidth(), src.getHeight());
        return pixels;
    }

    private static Bitmap fromPixels(int[] pixels, int width, int height) {
        final Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        out.setPixels(pixels, 0, width, 0, 0, width, height);
        return out;
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    /** Pillow's ITU-R 601-2 luma, the one {@code convert("L")} uses. */
    private static int luma(int r, int g, int b) {
        return (r * 299 + g * 587 + b * 114) / 1000;
    }
    // --------------------------------------------------------------- per-pixel

    private static Bitmap grayscale(Bitmap src) {
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            final int l = luma((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
            pixels[a] = (p & 0xFF000000) | (l << 16) | (l << 8) | l;
        }
        return fromPixels(pixels, src.getWidth(), src.getHeight());
    }

    private static Bitmap invert(Bitmap src) {
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            pixels[a] = (p & 0xFF000000) | (~p & 0x00FFFFFF);
        }
        return fromPixels(pixels, src.getWidth(), src.getHeight());
    }

    /** Pillow's {@code colorize}: map the luma ramp onto a two-colour gradient. */
    private static Bitmap colorize(Bitmap src, int black, int white) {
        final int[] ramp = new int[256];
        final int br = Color.red(black), bg = Color.green(black), bb = Color.blue(black);
        final int wr = Color.red(white), wg = Color.green(white), wb = Color.blue(white);
        for (int a = 0; a < 256; a++) {
            final float t = a / 255f;
            ramp[a] = (Math.round(br + (wr - br) * t) << 16)
                    | (Math.round(bg + (wg - bg) * t) << 8)
                    | Math.round(bb + (wb - bb) * t);
        }
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            pixels[a] = (p & 0xFF000000) | ramp[luma((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF)];
        }
        return fromPixels(pixels, src.getWidth(), src.getHeight());
    }

    /** Independent gain and lift per channel, the shape all the "look" filters share. */
    private static Bitmap channels(Bitmap src, float rk, int rb, float gk, int gb, float bk, int bb) {
        final int[] rLut = lut(rk, rb);
        final int[] gLut = lut(gk, gb);
        final int[] bLut = lut(bk, bb);
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            pixels[a] = (p & 0xFF000000)
                    | (rLut[(p >> 16) & 0xFF] << 16)
                    | (gLut[(p >> 8) & 0xFF] << 8)
                    | bLut[p & 0xFF];
        }
        return fromPixels(pixels, src.getWidth(), src.getHeight());
    }

    private static int[] lut(float scale, int bias) {
        final int[] table = new int[256];
        for (int a = 0; a < 256; a++) {
            table[a] = clamp255((int) (a * scale) + bias);
        }
        return table;
    }

    private static Bitmap posterize(Bitmap src, int bits) {
        final int mask = (0xFF << (8 - bits)) & 0xFF;
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            pixels[a] = (p & 0xFF000000)
                    | ((((p >> 16) & 0xFF) & mask) << 16)
                    | ((((p >> 8) & 0xFF) & mask) << 8)
                    | ((p & 0xFF) & mask);
        }
        return fromPixels(pixels, src.getWidth(), src.getHeight());
    }

    private static Bitmap solarize(Bitmap src, int threshold) {
        final int[] table = new int[256];
        for (int a = 0; a < 256; a++) {
            table[a] = a < threshold ? a : 255 - a;
        }
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            pixels[a] = (p & 0xFF000000)
                    | (table[(p >> 16) & 0xFF] << 16)
                    | (table[(p >> 8) & 0xFF] << 8)
                    | table[p & 0xFF];
        }
        return fromPixels(pixels, src.getWidth(), src.getHeight());
    }

    /** The plugin's four-band ramp: blue, cyan, green, then white-hot. */
    private static Bitmap thermal(Bitmap src) {
        final int[] ramp = new int[256];
        for (int a = 0; a < 256; a++) {
            final int r, g, b;
            if (a < 64) {
                r = 0;
                g = 0;
                b = clamp255(a * 4);
            } else if (a < 128) {
                r = 0;
                g = clamp255((a - 64) * 4);
                b = clamp255(255 - (a - 64) * 4);
            } else if (a < 192) {
                r = clamp255((a - 128) * 4);
                g = 255;
                b = 0;
            } else {
                r = 255;
                g = clamp255(255 - (a - 192) * 4);
                b = clamp255((a - 192) * 4);
            }
            ramp[a] = (r << 16) | (g << 8) | b;
        }
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            pixels[a] = (p & 0xFF000000) | ramp[luma((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF)];
        }
        return fromPixels(pixels, src.getWidth(), src.getHeight());
    }
    // ------------------------------------------------------------- ImageEnhance

    /**
     * Pillow's {@code ImageEnhance} is a blend between the image and a degenerate version of it:
     * a flat mid-grey for contrast, black for brightness, grey for colour, a blurred copy for
     * sharpness. A factor above 1 extrapolates past the original rather than interpolating.
     */
    private static Bitmap blend(Bitmap src, Bitmap degenerate, float factor, boolean recycleDegenerate) {
        final int[] a = pixelsOf(src);
        final int[] b = pixelsOf(degenerate);
        for (int i = 0; i < a.length; i++) {
            final int p = a[i], q = b[i];
            final int r = clamp255(Math.round(((q >> 16) & 0xFF) + (((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) * factor));
            final int g = clamp255(Math.round(((q >> 8) & 0xFF) + (((p >> 8) & 0xFF) - ((q >> 8) & 0xFF)) * factor));
            final int bl = clamp255(Math.round((q & 0xFF) + ((p & 0xFF) - (q & 0xFF)) * factor));
            a[i] = (p & 0xFF000000) | (r << 16) | (g << 8) | bl;
        }
        if (recycleDegenerate) {
            degenerate.recycle();
        }
        return fromPixels(a, src.getWidth(), src.getHeight());
    }

    private static Bitmap contrast(Bitmap src, float factor) {
        // Pillow uses the mean of the greyscale histogram as the flat degenerate image.
        final int[] pixels = pixelsOf(src);
        long sum = 0;
        for (int p : pixels) {
            sum += luma((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
        }
        final int mean = pixels.length == 0 ? 128 : (int) (sum / pixels.length);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            final int r = clamp255(Math.round(mean + (((p >> 16) & 0xFF) - mean) * factor));
            final int g = clamp255(Math.round(mean + (((p >> 8) & 0xFF) - mean) * factor));
            final int b = clamp255(Math.round(mean + ((p & 0xFF) - mean) * factor));
            pixels[a] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        final Bitmap out = fromPixels(pixels, src.getWidth(), src.getHeight());
        recycleIfIntermediate(src);
        return out;
    }

    private static Bitmap brightness(Bitmap src, float factor) {
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            pixels[a] = (p & 0xFF000000)
                    | (clamp255(Math.round(((p >> 16) & 0xFF) * factor)) << 16)
                    | (clamp255(Math.round(((p >> 8) & 0xFF) * factor)) << 8)
                    | clamp255(Math.round((p & 0xFF) * factor));
        }
        final Bitmap out = fromPixels(pixels, src.getWidth(), src.getHeight());
        recycleIfIntermediate(src);
        return out;
    }

    private static Bitmap saturation(Bitmap src, float factor) {
        final int[] pixels = pixelsOf(src);
        for (int a = 0; a < pixels.length; a++) {
            final int p = pixels[a];
            final int l = luma((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF);
            final int r = clamp255(Math.round(l + (((p >> 16) & 0xFF) - l) * factor));
            final int g = clamp255(Math.round(l + (((p >> 8) & 0xFF) - l) * factor));
            final int b = clamp255(Math.round(l + ((p & 0xFF) - l) * factor));
            pixels[a] = (p & 0xFF000000) | (r << 16) | (g << 8) | b;
        }
        final Bitmap out = fromPixels(pixels, src.getWidth(), src.getHeight());
        recycleIfIntermediate(src);
        return out;
    }

    private static Bitmap sharpness(Bitmap src, float factor) {
        // Pillow's degenerate image for sharpness is the SMOOTH kernel.
        final Bitmap smoothed = convolve(src, K_SMOOTH, 3, 13f, 0f);
        final Bitmap out = blend(src, smoothed, factor, true);
        recycleIfIntermediate(src);
        return out;
    }

    /**
     * The enhance chain hands its output straight into the next link, so each link frees what it was
     * given. Only the caller's original ever survives, and that one is not ours to free.
     */
    private static final ThreadLocal<Bitmap> KEEP = new ThreadLocal<>();

    private static void recycleIfIntermediate(Bitmap bitmap) {
        if (bitmap != null && bitmap != KEEP.get() && !bitmap.isRecycled()) {
            bitmap.recycle();
        }
    }

    // ------------------------------------------------------------- chat entry

    /** True when the long-press menu on this message should offer the editor. */
    public static boolean canEdit(@Nullable MessageObject messageObject) {
        if (messageObject == null || messageObject.isVideo() || messageObject.isGif()
                || messageObject.isSticker() || messageObject.isAnimatedSticker()
                || messageObject.isRoundVideo() || messageObject.needDrawBluredPreview()) {
            return false;
        }
        return messageObject.isPhoto() && MessageHelper.getPathToMessage(messageObject) != null;
    }

    /** Opens the editor on a photo already in the chat. */
    public static void openEditor(BaseFragment fragment, @Nullable MessageObject messageObject, long dialogId) {
        if (fragment == null || messageObject == null) {
            return;
        }
        final String path = MessageHelper.getPathToMessage(messageObject);
        if (path == null) {
            BulletinFactory.global().createErrorBulletin(getString(R.string.ImageFXFailed)).show();
            return;
        }
        ImageFXSheet.show(fragment, path, dialogId);
    }
    // -------------------------------------------------------------- geometric

    /**
     * A square convolution with the edges held rather than wrapped. Pillow leaves the border
     * untouched; clamping is the closest thing that does not leave a visible frame.
     */
    private static Bitmap convolve(Bitmap src, float[] kernel, int size, float scale, float offset) {
        final int width = src.getWidth();
        final int height = src.getHeight();
        final int[] in = pixelsOf(src);
        final int[] out = new int[in.length];
        final int radius = size / 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float r = 0, g = 0, b = 0;
                int k = 0;
                for (int dy = -radius; dy <= radius; dy++) {
                    final int sy = Math.min(height - 1, Math.max(0, y + dy));
                    for (int dx = -radius; dx <= radius; dx++, k++) {
                        final float weight = kernel[k];
                        if (weight == 0f) {
                            continue;
                        }
                        final int sx = Math.min(width - 1, Math.max(0, x + dx));
                        final int p = in[sy * width + sx];
                        r += ((p >> 16) & 0xFF) * weight;
                        g += ((p >> 8) & 0xFF) * weight;
                        b += (p & 0xFF) * weight;
                    }
                }
                final int index = y * width + x;
                out[index] = (in[index] & 0xFF000000)
                        | (clamp255(Math.round(r / scale + offset)) << 16)
                        | (clamp255(Math.round(g / scale + offset)) << 8)
                        | clamp255(Math.round(b / scale + offset));
            }
        }
        return fromPixels(out, width, height);
    }

    /** Three separable box passes, which converge on a Gaussian of the same radius. */
    private static Bitmap blur(Bitmap src, float radius) {
        final int width = src.getWidth();
        final int height = src.getHeight();
        final int[] pixels = pixelsOf(src);
        final int[] scratch = new int[pixels.length];
        final int r = Math.max(1, Math.round(radius));
        for (int pass = 0; pass < 3; pass++) {
            boxBlur(pixels, scratch, width, height, r);
            boxBlur(scratch, pixels, height, width, r);
        }
        return fromPixels(pixels, width, height);
    }

    /**
     * One horizontal box pass with a running sum, written out transposed. Calling it twice with the
     * dimensions swapped therefore blurs both directions and puts the image back the right way up.
     */
    private static void boxBlur(int[] in, int[] out, int width, int height, int radius) {
        final int span = radius * 2 + 1;
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            int a = 0, r = 0, g = 0, b = 0;
            for (int x = -radius; x <= radius; x++) {
                final int p = in[row + Math.min(width - 1, Math.max(0, x))];
                a += p >>> 24;
                r += (p >> 16) & 0xFF;
                g += (p >> 8) & 0xFF;
                b += p & 0xFF;
            }
            for (int x = 0; x < width; x++) {
                out[x * height + y] = ((a / span) << 24) | ((r / span) << 16) | ((g / span) << 8) | (b / span);
                final int drop = in[row + Math.min(width - 1, Math.max(0, x - radius))];
                final int add = in[row + Math.min(width - 1, Math.max(0, x + radius + 1))];
                a += (add >>> 24) - (drop >>> 24);
                r += ((add >> 16) & 0xFF) - ((drop >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((drop >> 8) & 0xFF);
                b += (add & 0xFF) - (drop & 0xFF);
            }
        }
    }
    /** Nearest-neighbour down and straight back up, so the blocks stay hard-edged. */
    private static Bitmap pixelate(Bitmap src, int block) {
        final int width = src.getWidth();
        final int height = src.getHeight();
        final int smallWidth = Math.max(8, width / Math.max(1, block));
        final int smallHeight = Math.max(8, height / Math.max(1, block));
        final Paint paint = new Paint();
        paint.setFilterBitmap(false);
        final Bitmap small = Bitmap.createBitmap(smallWidth, smallHeight, Bitmap.Config.ARGB_8888);
        new Canvas(small).drawBitmap(src, new Rect(0, 0, width, height),
                new Rect(0, 0, smallWidth, smallHeight), paint);
        final Bitmap out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        new Canvas(out).drawBitmap(small, new Rect(0, 0, smallWidth, smallHeight),
                new Rect(0, 0, width, height), paint);
        small.recycle();
        return out;
    }

    /** Red pulled one way and blue the other: the chromatic split that reads as a broken signal. */
    private static Bitmap glitch(Bitmap src, float shift) {
        final int width = src.getWidth();
        final int height = src.getHeight();
        final int offset = Math.max(1, Math.round(shift));
        final int[] in = pixelsOf(src);
        final int[] out = new int[in.length];
        for (int y = 0; y < height; y++) {
            final int row = y * width;
            for (int x = 0; x < width; x++) {
                final int rx = Math.min(width - 1, Math.max(0, x - offset));
                final int bx = Math.min(width - 1, Math.max(0, x + offset));
                out[row + x] = (in[row + x] & 0xFF000000)
                        | (((in[row + rx] >> 16) & 0xFF) << 16)
                        | (((in[row + x] >> 8) & 0xFF) << 8)
                        | (in[row + bx] & 0xFF);
            }
        }
        return fromPixels(out, width, height);
    }


}
