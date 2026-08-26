package tw.nekomimi.nekogram.helpers.frame;

/**
 * The mesh a ribbon is painted on: a grid two rows deep wrapped once round the avatar.
 *
 * <p>Columns are laid out at even distances <em>along the offset line</em> rather than along the
 * avatar's outline, which is what keeps the picture at a constant scale round a corner. Each column
 * holds the outer edge, the middle and the inner edge; three rows rather than two because a fold that
 * pinches the outer edge would otherwise drag the inner one with it in a straight line and the
 * picture would shear.
 *
 * <p>Every row is then run through {@link FrameFold}, and the grid is closed by copying column zero
 * into the last column — the seam has to be exact, not merely close, or a bright ribbon shows a hair
 * line where it meets itself.
 */
public final class FrameStrip {

    public static final int COLUMNS_MIN = 24;
    public static final int COLUMNS_MAX = 192;
    public static final int ROWS = 2;
    private static final float STEP = 4f;

    private static volatile int lastFolded;

    private FrameStrip() {
    }

    /** How many vertices the last call had to move; the studio shows it as a warning. */
    public static int folded() {
        return lastFolded;
    }

    public static int columns(float length) {
        if (Float.isNaN(length) || length <= 0f) {
            return COLUMNS_MIN;
        }
        return Math.max(COLUMNS_MIN, Math.min(COLUMNS_MAX, Math.round(length / STEP)));
    }

    public static int vertsLength(int columns, int rows) {
        return (Math.max(1, columns) + 1) * (Math.max(1, rows) + 1) * 2;
    }

    /**
     * Fills {@code out} with the mesh.
     *
     * @param inner how far out the inner edge sits, in the frame's own units.
     * @param outer how far out the outer edge sits.
     * @param start where on the outline the ribbon begins.
     */
    public static boolean verts(FrameRibbon ribbon, int columns, int rows,
                                float inner, float outer, float start, float[] out) {
        final int cols = Math.max(1, columns);
        final int rowCount = Math.max(1, rows);
        final int needed = vertsLength(cols, rowCount);
        if (out == null || out.length < needed) {
            return false;
        }
        final float near = Math.min(inner, outer);
        final float far = Math.max(inner, outer);
        // The ribbon is built for the middle of the band: that is the radius its corner trims and
        // its fans are correct for, and both edges then ride the same stations.
        if (ribbon == null || !ribbon.prepare((near + far) / 2f)) {
            for (int i = 0; i < needed; i++) {
                out[i] = 0f;
            }
            return false;
        }
        final float from = ribbon.lengthAt(start);
        final float total = ribbon.total();
        int hint = 0;
        for (int column = 0; column <= cols; column++) {
            hint = ribbon.seek(column * total / cols + from, hint);
            final float spotX = ribbon.spotX();
            final float spotY = ribbon.spotY();
            final float normalX = ribbon.spotNX();
            final float normalY = ribbon.spotNY();
            for (int row = 0; row <= rowCount; row++) {
                final float away = far + (near - far) * ((float) row / rowCount);
                final int at = ((cols + 1) * row + column) * 2;
                out[at] = spotX + normalX * away;
                out[at + 1] = spotY + normalY * away;
            }
        }
        int folded = 0;
        for (int row = 0; row <= rowCount; row++) {
            final int offset = (cols + 1) * row * 2;
            folded += FrameFold.unfold(out, offset, cols);
            out[offset + cols * 2] = out[offset];
            out[offset + cols * 2 + 1] = out[offset + 1];
        }
        if (folded > 0) {
            lastFolded = folded;
        }
        return true;
    }
}
