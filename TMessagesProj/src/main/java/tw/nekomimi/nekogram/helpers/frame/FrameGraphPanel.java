package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * The joystick node's geometry: what shape it takes, and what a drag on it means.
 *
 * <p>A joystick is a node whose two knobs are wired into other nodes' knobs. Which knobs it is wired
 * into decides what it looks like — a ring when it only turns things, a horizontal or vertical slider
 * when it only moves them one way, and a two-axis pad when it does both.
 */
public final class FrameGraphPanel {

    public static final int KNOB_ACROSS = 0;
    public static final int KNOB_DOWN = 1;

    public static final int SHAPE_RING = 0;
    public static final int SHAPE_ACROSS = 1;
    public static final int SHAPE_DOWN = 2;
    public static final int SHAPE_PAD = 3;

    /** How far the finger travels for one step of a dragged knob. */
    private static final float PULL = 2.5f;
    /** Nearer to the centre than this and a turn is meaningless, so it is ignored. */
    private static final float DEAD_RADIUS_SQUARED = 400f;

    private FrameGraphPanel() {
    }

    /** What the joystick at {@code id} looks like, from the roles of the knobs it drives. */
    public static int shape(@Nullable FrameGraph graph, int id) {
        if (graph == null || id <= 0) {
            return SHAPE_RING;
        }
        final List<FrameGraph.Wire> wires = graph.wires();
        boolean across = false;
        boolean down = false;
        for (int i = 0; i < wires.size(); i++) {
            final FrameGraph.Wire wire = wires.get(i);
            if (wire.from != id || !FrameGraphType.isKnobPin(wire.pin)) {
                continue;
            }
            final FrameGraph.Node target = graph.node(wire.to);
            if (target == null) {
                continue;
            }
            final int role = FrameGraphType.roleOf(FrameGraphType.of(target.type),
                    FrameGraphType.knobOfPin(wire.pin));
            if (role == FrameGraphType.ROLE_ACROSS) {
                across = true;
            } else if (role == FrameGraphType.ROLE_DOWN) {
                down = true;
            }
        }
        if (across && down) {
            return SHAPE_PAD;
        }
        if (across) {
            return SHAPE_ACROSS;
        }
        if (down) {
            return SHAPE_DOWN;
        }
        return SHAPE_RING;
    }

    /** Which of the joystick's two knobs drives knob {@code knob} of {@code kind}. */
    public static int knobFor(@Nullable FrameGraphType.Kind kind, int knob) {
        return FrameGraphType.roleOf(kind, knob) == FrameGraphType.ROLE_DOWN
                ? KNOB_DOWN : KNOB_ACROSS;
    }

    /** The angle a drag from one point to another sweeps about a centre, in degrees. */
    public static float swept(float fromX, float fromY, float toX, float toY,
                              float centreX, float centreY) {
        final float ax = fromX - centreX;
        final float ay = fromY - centreY;
        final float bx = toX - centreX;
        final float by = toY - centreY;
        if (ax * ax + ay * ay < DEAD_RADIUS_SQUARED
                || bx * bx + by * by < DEAD_RADIUS_SQUARED) {
            return 0f;
        }
        double degrees = Math.toDegrees(Math.atan2(by, bx) - Math.atan2(ay, ax));
        while (degrees > 180.0) {
            degrees -= 360.0;
        }
        while (degrees < -180.0) {
            degrees += 360.0;
        }
        return (float) degrees;
    }

    public static int pulled(int value, float pixels) {
        return value + Math.round(pixels / PULL);
    }

    public static int turned(int value, float degrees) {
        return value + Math.round(degrees);
    }
}
