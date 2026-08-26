package tw.nekomimi.nekogram.helpers.frame;

/**
 * Turns a picture of a ring into a straight strip, so it can be laid round an avatar of any shape.
 *
 * <p>Frames are often published as a finished round border — a picture with a hole in the middle.
 * Wrapping that round a square avatar looks wrong, and stretching it looks worse. A layer marked
 * {@code round} is instead unrolled: the ring is found by scanning one radius outward from the centre
 * for where the picture starts and stops being opaque, and then resampled into a rectangle 768 columns
 * wide and as many rows as the ring is thick.
 */
public final class FrameUnwrap {

    public static final int ALPHA_THRESHOLD = 24;
    public static final int COLUMNS = 768;
    public static final int MIN_RING = 3;

    private FrameUnwrap() {
    }

    /**
     * Finds the ring in a radial scan of alpha values.
     *
     * @param alphas one scan outward from the centre.
     * @param out    the outer and then the inner radius, as offsets into {@code alphas}.
     */
    public static boolean ring(int[] alphas, int[] out) {
        if (alphas == null || out == null || out.length < 2 || alphas.length < MIN_RING) {
            return false;
        }
        int outer = -1;
        for (int i = alphas.length - 1; i >= 0; i--) {
            if (alphas[i] >= ALPHA_THRESHOLD) {
                outer = i;
                break;
            }
        }
        if (outer < MIN_RING) {
            return false;
        }
        int inner = 0;
        for (int i = outer; i >= 0; i--) {
            if (alphas[i] < ALPHA_THRESHOLD) {
                inner = i + 1;
                break;
            }
        }
        if (outer - inner < MIN_RING) {
            return false;
        }
        out[0] = outer;
        out[1] = inner;
        return true;
    }

    /** Which pixel of the ring a cell of the unrolled strip comes from. */
    public static void source(int column, int row, int columns, int rows,
                              float outer, float inner, float centre, float[] out) {
        if (out == null || out.length < 2 || columns <= 0 || rows <= 0) {
            return;
        }
        final double angle = column * 2 * Math.PI / columns - Math.PI;
        float radius = outer;
        radius += (inner - outer) * ((float) row / rows);
        out[0] = (float) Math.cos(angle) * radius + centre;
        out[1] = centre + radius * (float) Math.sin(angle);
    }

    public static int rows(float outer, float inner) {
        return Math.max(MIN_RING, Math.round(outer - inner));
    }
}
