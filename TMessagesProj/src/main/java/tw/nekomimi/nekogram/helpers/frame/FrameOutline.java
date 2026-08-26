package tw.nekomimi.nekogram.helpers.frame;

import android.graphics.Path;
import android.graphics.PathMeasure;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

/**
 * Samples a shape into the polygon a frame is laid along.
 *
 * <p>256 samples, always, whatever the shape: that is enough for a circle to look round at every
 * avatar size the profile draws, and it is what the reference uses, so a frame's {@code at} lands in
 * the same place on both apps.
 */
public final class FrameOutline {

    public static final int SAMPLES = 256;

    private FrameOutline() {
    }

    public static FrameContour of(@Nullable Path path, int samples) {
        if (path == null || samples < 3) {
            return FrameContour.EMPTY;
        }
        try {
            final PathMeasure measure = new PathMeasure(path, false);
            final float length = measure.getLength();
            if (length <= 0f) {
                return FrameContour.EMPTY;
            }
            final float[] points = new float[samples * 2];
            final float[] spot = new float[2];
            for (int i = 0; i < samples; i++) {
                measure.getPosTan(i * length / samples, spot, null);
                points[i * 2] = spot[0];
                points[i * 2 + 1] = spot[1];
            }
            // The centre is known: the space is 256 units wide and the avatar fills it.
            return FrameContour.of(points, FrameShape.SPACE / 2f, FrameShape.SPACE / 2f);
        } catch (Throwable e) {
            FileLog.e("FrameOutline: could not sample the avatar shape: " + e.getMessage());
            return FrameContour.EMPTY;
        }
    }

    public static FrameContour of(int shape, int radiusDp, int smoothingPercent,
                                  @Nullable float[] points, float avatarSide, int samples) {
        try {
            return of(FrameShape.of(shape, radiusDp, smoothingPercent, points, avatarSide), samples);
        } catch (Throwable e) {
            FileLog.e("FrameOutline: could not build the avatar shape: " + e.getMessage());
            return FrameContour.EMPTY;
        }
    }
}
