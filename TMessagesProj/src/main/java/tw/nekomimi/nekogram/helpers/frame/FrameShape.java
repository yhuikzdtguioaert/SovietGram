package tw.nekomimi.nekogram.helpers.frame;

import android.graphics.Path;
import android.graphics.RectF;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

import tw.nekomimi.nekogram.helpers.CustomProfileGfx;

/**
 * The avatar's outline drawn in the frame's own 256-unit space.
 *
 * <p>The same eight shapes the profile draws, but built at 256 units square rather than in pixels,
 * because that is the space a frame is authored in. The two rectangular ones are built here rather
 * than by {@link CustomProfileGfx}: their corner radius is given in dp against the avatar's real
 * size, so it has to be converted into space units, and the plain square is given a small radius of
 * its own — a true right angle makes the ribbon's corner fan degenerate.
 */
public final class FrameShape {

    public static final int ROUNDED = 1;
    public static final int SQUARE = 2;

    public static final float SPACE = 256f;
    private static final float SQUARE_CORNER = 8f;

    private FrameShape() {
    }

    public static Path of(int shape, int radiusDp, int smoothingPercent,
                          @Nullable float[] points, float avatarSide) {
        if (shape == ROUNDED || shape == SQUARE) {
            final Path path = new Path();
            final float corner = corner(shape, radiusDp, avatarSide);
            path.addRoundRect(new RectF(0f, 0f, SPACE, SPACE), corner, corner, Path.Direction.CW);
            return path;
        }
        return CustomProfileGfx.shapePath(shape, 0f, 0f, SPACE, SPACE, 0f, smoothingPercent,
                points == null ? new float[0] : points);
    }

    public static float corner(int shape, int radiusDp, float avatarSide) {
        if (shape == SQUARE) {
            return SQUARE_CORNER;
        }
        if (shape != ROUNDED) {
            return 0f;
        }
        return AndroidUtilities.dpf2(radiusDp) * SPACE / Math.max(1f, avatarSide);
    }
}
