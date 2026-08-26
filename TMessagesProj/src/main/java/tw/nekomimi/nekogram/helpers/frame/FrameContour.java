package tw.nekomimi.nekogram.helpers.frame;

/**
 * The avatar's outline, as a closed polygon a frame can be laid along.
 *
 * <p>Everything a frame draws except the sticker is placed on this: a fraction between 0 and 1 names
 * a point on it, and the outward normal at that point is which way "away from the avatar" is. A
 * polygon rather than a {@code PathMeasure} because a frame needs three things a path measure cannot
 * give — the vertices themselves (the ribbon bevels its corners), the polar angle of a point about
 * the centre ({@code orbit} turns a layer by degrees, not by arc length), and the corners
 * ({@code corner} swaps in a second picture at each one).
 *
 * <p><b>Where 0 is.</b> The sampled points are normalised by {@link #fromTop}: wound clockwise, and
 * rotated so index 0 is the vertex nearest straight up. Without that a layer's {@code at} would
 * start at a different place on a square than on a circle, and two users with different avatar
 * shapes would see the same frame sitting differently.
 */
public final class FrameContour {

    public static final FrameContour EMPTY =
            new FrameContour(new float[0], new float[0], 0f, 1f, 0f, 0f);

    private final float[] points;
    /** Arc length up to each vertex; one longer than the vertex count, ending at {@link #length}. */
    private final float[] lengths;
    private final float length;
    /** +1 when the polygon winds so that turning the tangent right points outwards, −1 otherwise. */
    private final float outward;
    private final float middleX;
    private final float middleY;

    private FrameContour(float[] points, float[] lengths, float length,
                         float outward, float middleX, float middleY) {
        this.points = points;
        this.lengths = lengths;
        this.length = length;
        this.outward = outward;
        this.middleX = middleX;
        this.middleY = middleY;
    }

    public static FrameContour of(float[] points) {
        return of(points, Float.NaN, Float.NaN);
    }

    /**
     * Builds a contour from {@code x, y} pairs. The centre is given when the caller knows it — the
     * frame space is 256 units wide, so it is always (128, 128) — and computed from the bounding box
     * otherwise.
     */
    public static FrameContour of(float[] points, float centreX, float centreY) {
        if (points == null || points.length < 6) {
            return EMPTY;
        }
        final int count = points.length / 2;
        final int used = count * 2;
        final float[] copy = new float[used];
        System.arraycopy(points, 0, copy, 0, used);
        for (int i = 0; i < used; i++) {
            if (Float.isNaN(copy[i]) || Float.isInfinite(copy[i])) {
                return EMPTY;
            }
        }
        final float[] ordered = fromTop(copy);
        final float[] lengths = new float[count + 1];
        float total = 0f;
        for (int i = 0; i < count; i++) {
            final int next = ((i + 1) % count) * 2;
            final int here = i * 2;
            final float dx = ordered[next] - ordered[here];
            final float dy = ordered[next + 1] - ordered[here + 1];
            total += (float) Math.sqrt(dx * dx + dy * dy);
            lengths[i + 1] = total;
        }
        if (total <= 0f) {
            return EMPTY;
        }
        float cx = centreX;
        float cy = centreY;
        if (Float.isNaN(centreX) || Float.isNaN(centreY)) {
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE;
            float minY = Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (int i = 0; i < count; i++) {
                final int at = i * 2;
                minX = Math.min(minX, ordered[at]);
                maxX = Math.max(maxX, ordered[at]);
                minY = Math.min(minY, ordered[at + 1]);
                maxY = Math.max(maxY, ordered[at + 1]);
            }
            cx = (minX + maxX) / 2f;
            cy = (minY + maxY) / 2f;
        }
        return new FrameContour(ordered, lengths, total,
                orientation(ordered) >= 0f ? 1f : -1f, cx, cy);
    }

    /** Winds the polygon one way and starts it at the vertex nearest the top. */
    private static float[] fromTop(float[] raw) {
        final int count = raw.length / 2;
        float sumX = 0f, sumY = 0f;
        for (int i = 0; i < count; i++) {
            sumX += raw[i * 2];
            sumY += raw[i * 2 + 1];
        }
        final float midX = sumX / count;
        final float midY = sumY / count;
        float[] points = orientation(raw) < 0f ? reversed(raw) : raw;
        double best = Double.MAX_VALUE;
        int start = 0;
        for (int i = 0; i < count; i++) {
            final int at = i * 2;
            final double angle = Math.abs(
                    Math.atan2(points[at + 1] - midY, points[at] - midX) + Math.PI / 2);
            final double distance = Math.min(angle, 2 * Math.PI - angle);
            if (distance < best) {
                start = i;
                best = distance;
            }
        }
        if (start == 0) {
            return points;
        }
        final float[] rotated = new float[points.length];
        for (int i = 0; i < count; i++) {
            final int to = i * 2;
            final int from = ((start + i) % count) * 2;
            rotated[to] = points[from];
            rotated[to + 1] = points[from + 1];
        }
        return rotated;
    }

    private static float[] reversed(float[] raw) {
        final int count = raw.length / 2;
        final float[] out = new float[raw.length];
        for (int i = 0; i < count; i++) {
            final int to = i * 2;
            final int from = (count - 1 - i) * 2;
            out[to] = raw[from];
            out[to + 1] = raw[from + 1];
        }
        return out;
    }

    public float length() {
        return length;
    }

    public boolean isEmpty() {
        return length <= 0f;
    }

    public int nodeCount() {
        return points.length / 2;
    }

    public float nodeX(int index) {
        return points[index * 2];
    }

    public float nodeY(int index) {
        return points[index * 2 + 1];
    }

    /** Where vertex {@code index} sits along the outline, as a fraction. */
    public float nodeAt(int index) {
        if (length > 0f && index >= 0 && index < lengths.length) {
            return lengths[index] / length;
        }
        return 0f;
    }

    public float outward() {
        return outward;
    }

    /**
     * The point at {@code place} and the direction of travel there, as
     * {@code x, y, tangentX, tangentY}.
     */
    public void posTan(float place, float[] out) {
        if (out == null || out.length < 4) {
            return;
        }
        if (isEmpty()) {
            out[0] = 0f;
            out[1] = 0f;
            out[2] = 1f;
            out[3] = 0f;
            return;
        }
        final float along = wrap(place) * length;
        final int index = segment(along);
        final int count = points.length / 2;
        final int next = (index + 1) % count;
        final float x0 = points[index * 2];
        final float y0 = points[index * 2 + 1];
        final float x1 = points[next * 2];
        final float y1 = points[next * 2 + 1];
        final float from = lengths[index];
        final float span = lengths[index + 1] - from;
        final float t = span > 0f ? (along - from) / span : 0f;
        final float dx = x1 - x0;
        final float dy = y1 - y0;
        out[0] = x0 + dx * t;
        out[1] = y0 + dy * t;
        final float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len <= 0f) {
            out[2] = 1f;
            out[3] = 0f;
        } else {
            out[2] = dx / len;
            out[3] = dy / len;
        }
    }

    /** The point at {@code place}, pushed {@code away} out along the outward normal. */
    public void point(float place, float away, float[] out) {
        if (out == null || out.length < 2) {
            return;
        }
        final float[] scratch = new float[4];
        posTan(place, scratch);
        out[0] = scratch[0] + normalX(scratch[3]) * away;
        out[1] = scratch[1] + normalY(scratch[2]) * away;
    }

    public float angle(float place) {
        final float[] scratch = new float[4];
        posTan(place, scratch);
        return (float) Math.toDegrees(Math.atan2(scratch[3], scratch[2]));
    }

    /**
     * The place you reach by walking {@code degrees} around the avatar's centre from {@code place}.
     *
     * <p>This is what {@code orbit} means: a quarter turn moves a stamp a quarter of the way around
     * the shape as the eye sees it, not a quarter of the way along its perimeter — on a square those
     * are different places.
     */
    public float turned(float place, float degrees) {
        return (isEmpty() || degrees == 0f) ? place : atAngle(around(place) + degrees);
    }

    /** The polar angle of the point at {@code place}, about the centre. */
    public float around(float place) {
        if (isEmpty()) {
            return 0f;
        }
        final float[] scratch = new float[4];
        posTan(place, scratch);
        return (float) Math.toDegrees(Math.atan2(scratch[1] - middleY, scratch[0] - middleX));
    }

    /** The place whose polar angle is {@code degrees}; the inverse of {@link #around}. */
    public float atAngle(float degrees) {
        if (isEmpty()) {
            return 0f;
        }
        final float wanted = normalizeDegrees(degrees);
        final int count = points.length / 2;
        for (int i = 0; i < count; i++) {
            final int next = (i + 1) % count;
            final float here = normalizeDegrees(nodeAngle(i) - wanted);
            final float there = normalizeDegrees(nodeAngle(next) - wanted);
            if (here == 0f) {
                return lengths[i] / length;
            }
            // The wanted angle is crossed inside this edge — and the two ends are on opposite sides
            // of it the short way round, which is what rules out the edge on the far side.
            if ((here < 0f) != (there < 0f) && Math.abs(here) + Math.abs(there) < 180f) {
                final float t = Math.abs(here) / (Math.abs(here) + Math.abs(there));
                final float from = lengths[i] / length;
                // Wrapping past the last vertex ends at the whole length, which is 1.
                final int end = next != 0 ? next : count;
                return from + (lengths[end] / length - from) * t;
            }
        }
        return 0f;
    }

    private float nodeAngle(int index) {
        final int at = index * 2;
        return (float) Math.toDegrees(Math.atan2(points[at + 1] - middleY, points[at] - middleX));
    }

    public float normalX(float tangentY) {
        return tangentY * outward;
    }

    public float normalY(float tangentX) {
        return -tangentX * outward;
    }

    /**
     * The places where the outline turns by at least {@code degrees}, written into {@code out}.
     * These are the corners a layer's {@code corner} picture is swapped in at.
     */
    public int corners(float degrees, float[] out) {
        if (out == null || out.length == 0 || isEmpty()) {
            return 0;
        }
        final int count = points.length / 2;
        int found = 0;
        for (int i = 0; i < count && found < out.length; i++) {
            final int before = ((i + count) - 1) % count;
            final int after = (i + 1) % count;
            final float x = points[i * 2];
            final float y = points[i * 2 + 1];
            final float turn = turnDegrees(
                    x - points[before * 2], y - points[before * 2 + 1],
                    points[after * 2] - x, points[after * 2 + 1] - y);
            if (turn >= degrees) {
                out[found] = lengths[i] / length;
                found++;
            }
        }
        return found;
    }

    private static float turnDegrees(float ax, float ay, float bx, float by) {
        final float la = (float) Math.sqrt(ax * ax + ay * ay);
        final float lb = (float) Math.sqrt(bx * bx + by * by);
        if (la <= 0f || lb <= 0f) {
            return 0f;
        }
        final float cos = (ax * bx + ay * by) / (la * lb);
        return (float) Math.toDegrees(Math.acos(Math.max(-1f, Math.min(1f, cos))));
    }

    /** A fraction folded into 0..1; a frame's {@code at} is allowed to be any number. */
    public static float wrap(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            return 0f;
        }
        final float part = value % 1f;
        return part < 0f ? part + 1f : part;
    }

    private static float normalizeDegrees(float value) {
        float degrees = value % 360f;
        if (degrees > 180f) {
            degrees -= 360f;
        }
        return degrees < -180f ? degrees + 360f : degrees;
    }

    /** Binary search for the edge containing {@code along}. */
    private int segment(float along) {
        int low = 0;
        int high = (points.length / 2) - 1;
        while (low < high) {
            final int mid = (low + high + 1) >>> 1;
            if (lengths[mid] <= along) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /** Twice the signed area: positive when the polygon winds one way, negative the other. */
    private static float orientation(float[] raw) {
        final int count = raw.length / 2;
        float sum = 0f;
        for (int i = 0; i < count; i++) {
            final int here = i * 2;
            final int next = ((i + 1) % count) * 2;
            sum += raw[here] * raw[next + 1] - raw[next] * raw[here + 1];
        }
        return sum;
    }
}
