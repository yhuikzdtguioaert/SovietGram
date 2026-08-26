package tw.nekomimi.nekogram.helpers.frame;

/**
 * The outline pushed out to a given distance, as a polyline a ribbon can be laid along.
 *
 * <p>The naive way to wrap a picture round a shape is to sample the outline and offset each sample
 * along its normal. That works on a circle and falls apart on anything with corners: on the outside
 * of a corner the offset points fan out and leave a wedge with no picture in it, and on the inside
 * they cross and the picture doubles back on itself.
 *
 * <p>So this builds the offset polyline properly. Each straight side keeps its own normal; at an
 * outward corner a fan of extra stations is inserted, one every six degrees, sweeping the normal
 * round; at an inward corner both sides are trimmed back by {@code tan(θ/2)·r}, which is exactly
 * where their offsets would have met, capped at 45% of the shorter side so a spike cannot eat its
 * neighbours whole.
 *
 * <p>Lengths are then measured <em>along the offset line</em>, not along the original outline, which
 * is what keeps a repeating picture at a constant scale all the way round. {@link #seek} interpolates
 * between stations and renormalises the blended normal — that is what smooths a fan into a curve.
 *
 * <p>One instance is one contour at one radius, kept between frames; {@link #prepare} rebuilds only
 * when the radius moves by more than a quarter of a pixel.
 */
public final class FrameRibbon {

    /** Headroom for the corner fans on top of two stations per contour vertex. */
    private static final int JOIN_ROOM = 512;
    private static final float JOIN_STEP = 6f;
    /** Radii are quantised to this many steps per pixel before deciding to rebuild. */
    private static final float MID_STEP = 4f;
    private static final float TRIM_LIMIT = 0.45f;

    private static final int NO_KEY = Integer.MIN_VALUE;

    private FrameContour contour = FrameContour.EMPTY;

    private float[] x = new float[0];
    private float[] y = new float[0];
    private float[] nx = new float[0];
    private float[] ny = new float[0];
    /** Where each station sits on the original outline, as a fraction. */
    private float[] at = new float[0];
    /** Accumulated length along the offset line, one longer than the station count. */
    private float[] len = new float[0];
    private int count;

    private float[] sideX = new float[0];
    private float[] sideY = new float[0];
    private float[] sideNX = new float[0];
    private float[] sideNY = new float[0];
    private float[] sideLen = new float[0];
    private int[] sideNode = new int[0];
    private float[] cutIn = new float[0];
    private float[] cutOut = new float[0];
    private int sides;

    private float total;
    private int key = NO_KEY;

    private float spotX;
    private float spotY;
    private float spotNX;
    private float spotNY;

    public void contour(FrameContour value) {
        final FrameContour next = value == null ? FrameContour.EMPTY : value;
        if (next == contour) {
            return;
        }
        contour = next;
        count = 0;
        total = 0f;
        key = NO_KEY;
    }

    public FrameContour contour() {
        return contour;
    }

    public boolean isEmpty() {
        return count < 2 || total <= 0f;
    }

    /** Builds the offset line for a radius, or keeps the one already built for it. */
    public boolean prepare(float radius) {
        if (contour.isEmpty()) {
            count = 0;
            total = 0f;
            return false;
        }
        final float clean = (Float.isNaN(radius) || Float.isInfinite(radius))
                ? 0f : Math.max(0f, radius);
        final int rounded = Math.round(clean * MID_STEP);
        if (rounded == key && count >= 2) {
            return total > 0f;
        }
        key = rounded;
        build(rounded / MID_STEP);
        return !isEmpty();
    }

    public float total() {
        return total;
    }

    public int count() {
        return count;
    }

    /** The distance along the offset line of a place on the original outline. */
    public float lengthAt(float place) {
        if (isEmpty()) {
            return 0f;
        }
        final float wanted = FrameContour.wrap(place);
        int low = 0;
        int high = count - 1;
        while (low < high) {
            final int mid = (low + high + 1) >>> 1;
            if (at[mid] <= wanted) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        final float from = at[low];
        final float to = low + 1 < count ? at[low + 1] : 1f;
        final float t = FrameSpec.clampF(to > from ? (wanted - from) / (to - from) : 0f, 0f, 1f);
        return len[low] + (len[low + 1] - len[low]) * t;
    }

    /**
     * Walks to {@code along} on the offset line and leaves the point and its normal in
     * {@link #spotX} and friends.
     *
     * @param hint the station index a previous call returned; walking a whole ribbon is a scan, so
     *             this turns it from N searches into one pass.
     * @return the station index to hand back next time.
     */
    public int seek(float along, int hint) {
        if (isEmpty()) {
            spotX = 0f;
            spotY = 0f;
            spotNX = 0f;
            spotNY = 0f;
            return 0;
        }
        float wanted = along % total;
        if (wanted < 0f) {
            wanted += total;
        }
        int i = hint;
        if (i < 0 || i >= count || len[i] > wanted) {
            i = 0;
        }
        while (i < count - 1 && len[i + 1] < wanted) {
            i++;
        }
        final int next = (i + 1) % count;
        final float from = len[i];
        final float span = len[i + 1] - from;
        final float t = FrameSpec.clampF(span > 0f ? (wanted - from) / span : 0f, 0f, 1f);
        spotX = x[i] + (x[next] - x[i]) * t;
        spotY = y[i] + (y[next] - y[i]) * t;
        final float bx = nx[i] + (nx[next] - nx[i]) * t;
        final float by = ny[i] + (ny[next] - ny[i]) * t;
        final float length = (float) Math.sqrt(bx * bx + by * by);
        if (length <= 0f) {
            spotNX = nx[i];
            spotNY = ny[i];
            return i;
        }
        spotNX = bx / length;
        spotNY = by / length;
        return i;
    }

    public float spotX() {
        return spotX;
    }

    public float spotY() {
        return spotY;
    }

    public float spotNX() {
        return spotNX;
    }

    public float spotNY() {
        return spotNY;
    }

    // ---------------------------------------------------------------- building

    private void build(float radius) {
        count = 0;
        sides = 0;
        total = 0f;
        final int nodes = contour.nodeCount();
        if (nodes < 3) {
            return;
        }
        room(nodes);
        collectSides(nodes);
        if (sides < 3) {
            count = 0;
            return;
        }
        final float outward = contour.outward();
        trimInnerCorners(radius, outward);
        layStations(outward);
        measure(radius);
    }

    /** One side per non-degenerate edge, with its direction, its normal and its length. */
    private void collectSides(int nodes) {
        for (int i = 0; i < nodes; i++) {
            final int next = (i + 1) % nodes;
            final float dx = contour.nodeX(next) - contour.nodeX(i);
            final float dy = contour.nodeY(next) - contour.nodeY(i);
            final float length = (float) Math.sqrt(dx * dx + dy * dy);
            if (length <= 0f) {
                continue;
            }
            sideNode[sides] = i;
            sideLen[sides] = length;
            final float ux = dx / length;
            final float uy = dy / length;
            sideX[sides] = ux;
            sideY[sides] = uy;
            sideNX[sides] = contour.normalX(uy);
            sideNY[sides] = contour.normalY(ux);
            cutIn[sides] = 0f;
            cutOut[sides] = 0f;
            sides++;
        }
    }

    /** At an inward corner, back both sides off to where their offsets would have crossed. */
    private void trimInnerCorners(float radius, float outward) {
        for (int i = 0; i < sides; i++) {
            final int before = ((i + sides) - 1) % sides;
            final float turn = turn(before, i);
            if (turn * outward >= 0f) {
                continue;
            }
            final float wanted = (float) Math.tan(Math.abs(turn) / 2.0) * radius;
            final float cap = Math.min(sideLen[before], sideLen[i]) * TRIM_LIMIT;
            final float safe = (Float.isNaN(wanted) || wanted < 0f) ? 0f : wanted;
            final float cut = Math.min(safe, cap);
            cutOut[before] = cut;
            cutIn[i] = cut;
        }
    }

    /** Two stations per side, plus a fan of them at every outward corner. */
    private void layStations(float outward) {
        final float length = contour.length();
        for (int i = 0; i < sides; i++) {
            final int before = ((i + sides) - 1) % sides;
            final int node = sideNode[i];
            final float nodeX = contour.nodeX(node);
            final float nodeY = contour.nodeY(node);
            final float turn = turn(before, i);
            if (turn * outward > 0f) {
                fan(nodeX, nodeY, before, turn, contour.nodeAt(node));
            }
            add(nodeX + sideX[i] * cutIn[i], nodeY + sideY[i] * cutIn[i],
                    sideNX[i], sideNY[i], contour.nodeAt(node) + cutIn[i] / length);
            final int endNode = sideNode[(i + 1) % sides];
            // The last side ends at the whole outline, not back at zero.
            final float endAt = i == sides - 1 ? 1f : contour.nodeAt(endNode);
            add(contour.nodeX(endNode) - sideX[i] * cutOut[i],
                    contour.nodeY(endNode) - sideY[i] * cutOut[i],
                    sideNX[i], sideNY[i], endAt - cutOut[i] / length);
        }
    }

    /** Stations at one point with the normal swept round, so an outward corner has no wedge in it. */
    private void fan(float atX, float atY, int side, float turn, float place) {
        final int steps = (int) Math.ceil(Math.abs(Math.toDegrees(turn)) / JOIN_STEP);
        for (int i = 1; i < steps; i++) {
            final double angle = i * (double) turn / steps;
            final float cos = (float) Math.cos(angle);
            final float sin = (float) Math.sin(angle);
            final float baseX = sideNX[side];
            final float baseY = sideNY[side];
            add(atX, atY, baseX * cos - baseY * sin, baseX * sin + baseY * cos, place);
        }
    }

    /** The signed angle between two sides' normals; its sign against {@code outward} says which way. */
    private float turn(int a, int b) {
        final float ax = sideNX[a];
        final float ay = sideNY[a];
        final float bx = sideNX[b];
        final float by = sideNY[b];
        return (float) Math.atan2(ax * by - ay * bx, ax * bx + ay * by);
    }

    /** Lengths along the offset line at this radius, which is what a repeating picture is spread by. */
    private void measure(float radius) {
        len[0] = 0f;
        for (int i = 1; i <= count; i++) {
            final int here = i - 1;
            final int next = i % count;
            final float fromX = x[here] + nx[here] * radius;
            final float fromY = y[here] + ny[here] * radius;
            final float dx = (x[next] + nx[next] * radius) - fromX;
            final float dy = (y[next] + ny[next] * radius) - fromY;
            len[i] = len[here] + (float) Math.sqrt(dx * dx + dy * dy);
        }
        total = len[count];
    }

    private void add(float px, float py, float normalX, float normalY, float place) {
        if (count >= x.length) {
            return;
        }
        x[count] = px;
        y[count] = py;
        nx[count] = normalX;
        ny[count] = normalY;
        at[count] = FrameSpec.clampF(place, 0f, 1f);
        count++;
    }

    private void room(int nodes) {
        final int pairs = nodes * 2;
        final int wanted = pairs + JOIN_ROOM;
        if (x.length < wanted) {
            x = new float[wanted];
            y = new float[wanted];
            nx = new float[wanted];
            ny = new float[wanted];
            at = new float[wanted];
            len = new float[pairs + JOIN_ROOM + 1];
        }
        if (sideLen.length < nodes) {
            sideX = new float[nodes];
            sideY = new float[nodes];
            sideNX = new float[nodes];
            sideNY = new float[nodes];
            sideLen = new float[nodes];
            sideNode = new int[nodes];
            cutIn = new float[nodes];
            cutOut = new float[nodes];
        }
    }
}
