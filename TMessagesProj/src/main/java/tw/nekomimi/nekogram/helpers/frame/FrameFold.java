package tw.nekomimi.nekogram.helpers.frame;

/**
 * Takes the folds out of one row of a ribbon's mesh.
 *
 * <p>When a ribbon is pushed out from a shape with a tight concave corner, the outer edge overtakes
 * itself: the vertices there run backwards for a stretch and the mesh paints a little bow-tie. This
 * walks the row and, whenever a vertex further ahead is closer to the current one than its immediate
 * neighbour is, collapses everything between them onto that vertex — the fold becomes a pleat, which
 * a mesh can draw.
 *
 * <p>Ported as it stands, including the part that looks wrong: the baseline distance is measured once
 * per vertex against its neighbour and never refreshed as the search moves ahead. Changing it would
 * change how every published frame looks on a star, so it stays.
 */
public final class FrameFold {

    /** How far ahead to look, as a share of the row. */
    private static final float LOOK_AHEAD = 0.2f;

    private FrameFold() {
    }

    /**
     * @param verts  the mesh, {@code x, y} pairs.
     * @param offset where this row starts in it.
     * @param count  vertices in the row, not counting the repeated closing one.
     * @return how many vertices were moved.
     */
    public static int unfold(float[] verts, int offset, int count) {
        if (verts == null || count < 4 || offset < 0 || count * 2 + offset > verts.length) {
            return 0;
        }
        final int window = Math.max(2, Math.round(count * LOOK_AHEAD));
        int guard = 0;
        int moved = 0;
        for (int i = 0; i < count; ) {
            if (guard++ > count) {
                break;
            }
            int best = i + 1;
            final float baseline = distance(verts, offset, count, i, i + 1);
            for (int ahead = i + 2; ahead <= i + window; ahead++) {
                if (distance(verts, offset, count, i, ahead) < baseline) {
                    best = ahead;
                }
            }
            for (int between = i + 1; between < best; between++) {
                final int onto = best % count;
                set(verts, offset, between % count,
                        get(verts, offset, onto, 0), get(verts, offset, onto, 1));
                moved++;
            }
            i = best;
        }
        return moved;
    }

    private static float distance(float[] verts, int offset, int count, int a, int b) {
        final int from = a % count;
        final int to = b % count;
        final float dx = get(verts, offset, from, 0) - get(verts, offset, to, 0);
        final float dy = get(verts, offset, from, 1) - get(verts, offset, to, 1);
        return dx * dx + dy * dy;
    }

    private static float get(float[] verts, int offset, int index, int axis) {
        return verts[offset + index * 2 + axis];
    }

    private static void set(float[] verts, int offset, int index, float x, float y) {
        final int at = offset + index * 2;
        verts[at] = x;
        verts[at + 1] = y;
    }
}
