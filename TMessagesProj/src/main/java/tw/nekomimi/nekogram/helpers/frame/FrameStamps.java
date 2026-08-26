package tw.nekomimi.nekogram.helpers.frame;

/**
 * Where a frame's copies go: evenly round the outline, at one place on it, or hung at a point.
 *
 * <p>Each placement is three numbers — x, y and the angle to turn the picture by — so a caller can
 * fill an array once and draw from it without walking the outline again.
 */
public final class FrameStamps {

    /** How sharp a turn counts as a corner, and how near a stamp must be to sit on one. */
    public static final float CORNER_DEGREES = 20f;
    public static final float CORNER_WINDOW = 0.02f;
    public static final int STRIDE = 3;

    private FrameStamps() {
    }

    /** {@code count} copies spread evenly from {@code start}. */
    public static int repeated(FrameContour contour, int count, float start, float away,
                               float[] out, float[] scratch) {
        if (contour == null || contour.isEmpty() || count <= 0 || out == null
                || scratch == null || scratch.length < 4 || out.length < count * STRIDE) {
            return 0;
        }
        for (int i = 0; i < count; i++) {
            place(contour, start + (float) i / count, away, out, i * STRIDE, scratch);
        }
        return count;
    }

    /** One copy, at {@code start}. */
    public static boolean single(FrameContour contour, float start, float away,
                                 float[] out, float[] scratch) {
        if (contour == null || contour.isEmpty() || out == null || out.length < STRIDE
                || scratch == null || scratch.length < 4) {
            return false;
        }
        place(contour, start, away, out, 0, scratch);
        return true;
    }

    /** A sticker's placement: its point in the frame's units, and its turn. */
    public static boolean sticker(float x, float y, int turn, float space, float[] out) {
        if (out == null || out.length < STRIDE || space <= 0f) {
            return false;
        }
        out[0] = x * space;
        out[1] = y * space;
        out[2] = turn;
        return true;
    }

    /**
     * A sticker's point, turned about the centre by {@code spin} and then pushed out from it by
     * {@code away} — which is what makes a sticker orbit rather than slide.
     */
    public static boolean hung(float x, float y, float spin, float away, float[] out) {
        if (out == null || out.length < 2) {
            return false;
        }
        float dx = x - 0.5f;
        float dy = y - 0.5f;
        if (spin != 0f) {
            final double radians = Math.toRadians(spin);
            final float cos = (float) Math.cos(radians);
            final float sin = (float) Math.sin(radians);
            final float turned = dx * cos - dy * sin;
            dy = dy * cos + dx * sin;
            dx = turned;
        }
        if (away != 0f) {
            final float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length > 1e-4f) {
                dx += dx / length * away;
                dy += dy / length * away;
            }
        }
        out[0] = dx + 0.5f;
        out[1] = dy + 0.5f;
        return true;
    }

    /** Whether a place is near enough to one of the outline's corners to use the corner picture. */
    public static boolean atCorner(float[] corners, int count, float place) {
        if (corners == null || count <= 0) {
            return false;
        }
        final float wanted = FrameContour.wrap(place);
        for (int i = 0; i < count && i < corners.length; i++) {
            final float gap = Math.abs(wanted - FrameContour.wrap(corners[i]));
            if (Math.min(gap, 1f - gap) <= CORNER_WINDOW) {
                return true;
            }
        }
        return false;
    }

    /** Where copy {@code index} of {@code count} sits, starting from {@code start}. */
    public static float at(int index, int count, float start) {
        if (count <= 0) {
            return FrameContour.wrap(start);
        }
        return FrameContour.wrap(start + (float) index / count);
    }

    private static void place(FrameContour contour, float place, float away,
                              float[] out, int at, float[] scratch) {
        contour.posTan(place, scratch);
        out[at] = scratch[0] + contour.normalX(scratch[3]) * away;
        out[at + 1] = scratch[1] + contour.normalY(scratch[2]) * away;
        out[at + 2] = (float) Math.toDegrees(Math.atan2(scratch[3], scratch[2]));
    }
}
