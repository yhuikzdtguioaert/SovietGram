package tw.nekomimi.nekogram.helpers;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.TextureView;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.Utilities;

/**
 * Paints a soft coloured halo behind the media shown in the full-screen viewer: the picture itself,
 * shrunk down, darkened and blurred into a wash of its own colours, drawn across the whole canvas
 * underneath the real photo.
 *
 * <p>One instance per viewer — the app keeps several ({@code Instance}, {@code Instance2},
 * {@code PipInstance}) alive at once, so nothing here is static.
 *
 * <p>The source frame is grabbed several times after the media is opened, because the full-size
 * bitmap arrives asynchronously and the first capture usually only sees the thumbnail. Videos are
 * sampled from their {@link TextureView} when there is one; when the player is running on a
 * {@code SurfaceView} — which this fork prefers from API 30 up — the frame cannot be read back, so
 * the poster image is used instead and the halo simply stays still.
 */
public final class MediaGlowHelper {

    private static final int SOURCE_MAX_SIDE = 420;
    private static final int DARKEN_ALPHA = 72;
    private static final int BLUR_RADIUS = 36;
    private static final float GLOW_ALPHA = 0.62f;
    private static final long PHOTO_FADE_MS = 350L;
    private static final long VIDEO_FADE_MS = 280L;
    private static final float VIDEO_BLEND_ALPHA = 0.34f;
    private static final long VIDEO_REFRESH_MS = 450L;
    private static final long[] CAPTURE_DELAYS = {40L, 260L, 850L, 1500L};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();

    private Bitmap current;
    private Bitmap previous;
    private long fadeStart;
    private long fadeDuration = PHOTO_FADE_MS;
    private boolean video;

    private final Runnable[] scheduled = new Runnable[CAPTURE_DELAYS.length];
    private Runnable videoRefresh;
    private View host;

    /**
     * Drops every frame and cancels the pending captures. Called when the viewer closes so the next
     * open does not flash the previous media's colours.
     */
    public void clear() {
        for (int a = 0; a < scheduled.length; a++) {
            if (scheduled[a] != null) {
                AndroidUtilities.cancelRunOnUIThread(scheduled[a]);
                scheduled[a] = null;
            }
        }
        stopVideoRefresh();
        current = null;
        previous = null;
        host = null;
    }

    private void stopVideoRefresh() {
        if (videoRefresh != null) {
            AndroidUtilities.cancelRunOnUIThread(videoRefresh);
            videoRefresh = null;
        }
    }

    /**
     * Queues the capture passes for the media that has just been shown. {@code source} is asked for
     * a frame each time; returning {@code null} simply skips that pass.
     */
    public void schedule(View host, boolean isVideo, Source source) {
        this.host = host;
        this.video = isVideo;
        for (int a = 0; a < scheduled.length; a++) {
            if (scheduled[a] != null) {
                AndroidUtilities.cancelRunOnUIThread(scheduled[a]);
                scheduled[a] = null;
            }
        }
        stopVideoRefresh();
        for (int a = 0; a < CAPTURE_DELAYS.length; a++) {
            final int index = a;
            Runnable runnable = () -> {
                scheduled[index] = null;
                capture(source);
            };
            scheduled[a] = runnable;
            AndroidUtilities.runOnUIThread(runnable, CAPTURE_DELAYS[a]);
        }
        if (isVideo) {
            startVideoRefresh(source);
        }
    }

    private void startVideoRefresh(Source source) {
        videoRefresh = new Runnable() {
            @Override
            public void run() {
                capture(source);
                AndroidUtilities.runOnUIThread(this, VIDEO_REFRESH_MS);
            }
        };
        AndroidUtilities.runOnUIThread(videoRefresh, VIDEO_REFRESH_MS);
    }

    private void capture(Source source) {
        Bitmap frame;
        try {
            frame = source.get();
        } catch (Exception e) {
            FileLog.e(e);
            return;
        }
        if (frame == null || frame.isRecycled() || frame.getWidth() <= 0 || frame.getHeight() <= 0) {
            return;
        }
        Bitmap prepared = prepare(frame);
        if (prepared == null) {
            return;
        }
        previous = current;
        current = prepared;
        fadeDuration = video ? VIDEO_FADE_MS : PHOTO_FADE_MS;
        fadeStart = System.currentTimeMillis();
        if (host != null) {
            host.invalidate();
        }
    }

    /**
     * Shrinks the frame to at most {@link #SOURCE_MAX_SIDE} on its long side, lays a dark veil over
     * it so the halo never outshines the photo, then blurs it into a smear of colour.
     */
    @Nullable
    private Bitmap prepare(Bitmap frame) {
        try {
            int w = frame.getWidth();
            int h = frame.getHeight();
            float scale = Math.min(1f, SOURCE_MAX_SIDE / (float) Math.max(w, h));
            int dw = Math.max(1, Math.round(w * scale));
            int dh = Math.max(1, Math.round(h * scale));
            Bitmap out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(out);
            Paint copy = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            src.set(0, 0, w, h);
            dst.set(0, 0, dw, dh);
            canvas.drawBitmap(frame, src, dst, copy);
            canvas.drawColor(Color.argb(DARKEN_ALPHA, 0, 0, 0));
            Utilities.stackBlurBitmap(out, BLUR_RADIUS);
            return out;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * Draws the halo across the whole canvas. Must be the first thing painted in the viewer's
     * {@code onDraw} so everything else lands on top of it.
     */
    public void draw(Canvas canvas, int width, int height) {
        if (current == null || width <= 0 || height <= 0) {
            return;
        }
        float progress = 1f;
        if (fadeDuration > 0) {
            long elapsed = System.currentTimeMillis() - fadeStart;
            progress = Math.min(1f, Math.max(0f, elapsed / (float) fadeDuration));
            progress = progress * progress * (3f - 2f * progress);
        }
        // Video frames only ever blend part-way in, so the halo drifts with the picture instead of
        // flickering every time a new frame is sampled.
        float mix = video ? progress * VIDEO_BLEND_ALPHA : progress;
        if (previous != null) {
            drawCover(canvas, previous, width, height, GLOW_ALPHA * (1f - mix));
            drawCover(canvas, current, width, height, GLOW_ALPHA * mix);
            if (!video && progress >= 1f) {
                previous = null;
            }
        } else {
            drawCover(canvas, current, width, height, GLOW_ALPHA * progress);
        }
        if (progress < 1f && host != null) {
            host.invalidate();
        }
    }

    private void drawCover(Canvas canvas, Bitmap bitmap, int width, int height, float alpha) {
        if (bitmap == null || bitmap.isRecycled() || alpha <= 0.001f) {
            return;
        }
        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();
        float scale = Math.max(width / (float) bw, height / (float) bh);
        float dw = bw * scale;
        float dh = bh * scale;
        src.set(0, 0, bw, bh);
        dst.set((width - dw) / 2f, (height - dh) / 2f, (width + dw) / 2f, (height + dh) / 2f);
        paint.setAlpha((int) (Math.min(1f, alpha) * 255));
        canvas.drawBitmap(bitmap, src, dst, paint);
    }

    /**
     * Hands back a frame to build the halo from, or {@code null} when nothing usable is ready yet.
     */
    public interface Source {
        @Nullable
        Bitmap get();
    }

    /**
     * Frame from a video that is being played into a {@link TextureView}. Returns {@code null} when
     * the player is on a {@code SurfaceView} instead, where the pixels cannot be read back.
     */
    @Nullable
    public static Bitmap frameOf(@Nullable TextureView view) {
        if (view == null || !view.isAvailable() || view.getWidth() <= 0 || view.getHeight() <= 0) {
            return null;
        }
        try {
            float scale = Math.min(1f, SOURCE_MAX_SIDE / (float) Math.max(view.getWidth(), view.getHeight()));
            int w = Math.max(1, Math.round(view.getWidth() * scale));
            int h = Math.max(1, Math.round(view.getHeight() * scale));
            return view.getBitmap(w, h);
        } catch (Throwable e) {
            return null;
        }
    }

    /**
     * Frame from a still image. The bitmap belongs to the image receiver's drawable and must never
     * be recycled or drawn into here — {@link #prepare} always copies before touching it.
     */
    @Nullable
    public static Bitmap frameOf(@Nullable ImageReceiver receiver) {
        if (receiver == null) {
            return null;
        }
        try {
            Bitmap bitmap = receiver.getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                return bitmap;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }
}
