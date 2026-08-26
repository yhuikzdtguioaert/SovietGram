package tw.nekomimi.nekogram.helpers.frame;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

import java.util.HashMap;
import java.util.Map;

/**
 * Hides the join where a ribbon meets itself, by mirroring the picture onto twice its width.
 *
 * <p>A ribbon runs the picture once round the avatar, so its left edge lands against its right edge.
 * Unless the picture happens to tile, that shows as a hard line. Mirroring makes the two edges the
 * same column by construction, and the frame comes back round to itself invisibly.
 */
public final class FrameSeam {

    private static final int MAX_WIDTH = 4096;

    private static final Map<String, Bitmap> CACHE = new HashMap<>();
    /**
     * Pictures a mirrored copy could not be made of — too large, or no room for one. Remembered
     * because this is asked on every frame drawn, and retrying an allocation that just failed, sixty
     * times a second, is how a tight memory situation turns into a stopped app.
     */
    private static final java.util.Set<String> REFUSED = new java.util.HashSet<>();

    private FrameSeam() {
    }

    @Nullable
    public static Bitmap seamless(@Nullable String key, @Nullable Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled() || key == null || key.length() == 0) {
            return bitmap;
        }
        final int width = bitmap.getWidth();
        final int height = bitmap.getHeight();
        if (width <= 0 || height <= 0 || width > MAX_WIDTH) {
            return bitmap;
        }
        synchronized (CACHE) {
            final Bitmap cached = CACHE.get(key);
            if (cached != null && !cached.isRecycled()
                    && cached.getWidth() == width * 2 && cached.getHeight() == height) {
                return cached;
            }
            if (REFUSED.contains(key)) {
                return bitmap;
            }
            final Bitmap mirrored = draw(bitmap, width, height);
            if (mirrored == null) {
                REFUSED.add(key);
                return bitmap;
            }
            CACHE.put(key, mirrored);
            return mirrored;
        }
    }

    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            REFUSED.clear();
        }
    }

    @Nullable
    private static Bitmap draw(Bitmap source, int width, int height) {
        try {
            final Bitmap out = Bitmap.createBitmap(width * 2, height, Bitmap.Config.ARGB_8888);
            final Canvas canvas = new Canvas(out);
            final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(source, 0f, 0f, paint);
            final Matrix matrix = new Matrix();
            matrix.setScale(-1f, 1f);
            matrix.postTranslate(width * 2f, 0f);
            canvas.drawBitmap(source, matrix, paint);
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }
}
