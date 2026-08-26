package tw.nekomimi.nekogram.helpers.lyrics;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

import java.util.Random;

/**
 * The soft coloured haze behind the words. A handful of blurred blobs, tinted from the cover art,
 * drift to new spots at a walking pace so the background keeps moving without pulling the eye.
 */
public class AmbientBlobsView extends View {

    private static final int BLOB_COUNT = 12;
    private static final int BLOB_SIZE_DP = 260;
    private static final int BLOB_ALPHA = 120;
    private static final float SATURATION = 2.2f;
    private static final long STEP_DELAY_MS = 667;
    private static final long MOVE_MS = 967;

    private static final int[] FALLBACK = {
            Color.rgb(146, 83, 255),
            Color.rgb(51, 154, 255),
            Color.rgb(255, 84, 162),
            Color.rgb(255, 190, 92),
            Color.rgb(92, 255, 214),
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final Blob[] blobs = new Blob[BLOB_COUNT];
    private int[] palette = FALLBACK;
    private boolean running;
    private int nextBlob;

    private final Runnable stepRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            retarget(blobs[nextBlob]);
            nextBlob = (nextBlob + 1) % BLOB_COUNT;
            invalidate();
            AndroidUtilities.runOnUIThread(this, STEP_DELAY_MS);
        }
    };

    private static class Blob {
        float fromX;
        float fromY;
        float toX;
        float toY;
        long start;
        int color;
    }

    public AmbientBlobsView(Context context) {
        super(context);
        for (int a = 0; a < BLOB_COUNT; a++) {
            blobs[a] = new Blob();
        }
    }

    public void setPalette(@Nullable int[] colors) {
        palette = colors == null || colors.length == 0 ? FALLBACK : colors;
        for (int a = 0; a < BLOB_COUNT; a++) {
            blobs[a].color = saturate(palette[a % palette.length]);
        }
        invalidate();
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        AndroidUtilities.runOnUIThread(stepRunnable, STEP_DELAY_MS);
    }

    public void stop() {
        running = false;
        AndroidUtilities.cancelRunOnUIThread(stepRunnable);
    }

    @Override
    protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (oldWidth == 0 && width > 0) {
            for (int a = 0; a < BLOB_COUNT; a++) {
                Blob blob = blobs[a];
                blob.color = saturate(palette[a % palette.length]);
                blob.fromX = blob.toX = random.nextFloat() * width;
                blob.fromY = blob.toY = random.nextFloat() * height;
                blob.start = 0;
            }
        }
    }

    private void retarget(Blob blob) {
        long now = System.currentTimeMillis();
        float progress = progressOf(blob, now);
        blob.fromX = blob.fromX + (blob.toX - blob.fromX) * progress;
        blob.fromY = blob.fromY + (blob.toY - blob.fromY) * progress;
        blob.toX = random.nextFloat() * getWidth();
        blob.toY = random.nextFloat() * getHeight();
        blob.start = now;
    }

    private float progressOf(Blob blob, long now) {
        if (blob.start == 0) {
            return 1f;
        }
        float value = Math.max(0f, Math.min(1f, (now - blob.start) / (float) MOVE_MS));
        return value * value * (3f - 2f * value);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getWidth() == 0 || getHeight() == 0) {
            return;
        }
        long now = System.currentTimeMillis();
        float radius = dp(BLOB_SIZE_DP) / 2f;
        boolean animating = false;
        for (int a = 0; a < BLOB_COUNT; a++) {
            Blob blob = blobs[a];
            float progress = progressOf(blob, now);
            if (progress < 1f) {
                animating = true;
            }
            float x = blob.fromX + (blob.toX - blob.fromX) * progress;
            float y = blob.fromY + (blob.toY - blob.fromY) * progress;
            int red = Color.red(blob.color);
            int green = Color.green(blob.color);
            int blue = Color.blue(blob.color);
            paint.setShader(new RadialGradient(x, y, radius,
                    new int[]{
                            Color.argb(210, red, green, blue),
                            Color.argb(88, red, green, blue),
                            Color.argb(0, red, green, blue),
                    },
                    new float[]{0f, 0.55f, 1f}, Shader.TileMode.CLAMP));
            paint.setAlpha(BLOB_ALPHA);
            canvas.drawCircle(x, y, radius, paint);
        }
        paint.setShader(null);
        if (animating) {
            invalidate();
        }
    }

    /** Pushes a cover colour further from grey so the haze reads as colour rather than smudge. */
    private static int saturate(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[1] = Math.min(1f, hsv[1] * SATURATION);
        hsv[2] = Math.max(hsv[2], 0.45f);
        return Color.HSVToColor(hsv);
    }
}
