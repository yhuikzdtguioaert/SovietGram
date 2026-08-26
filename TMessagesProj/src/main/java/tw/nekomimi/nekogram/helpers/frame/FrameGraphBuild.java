package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a node graph into the frame the profile draws, and builds a graph back from a frame.
 *
 * <p>Compiling walks backwards from the output node. A node that <em>makes</em> a layer turns its
 * picture and its knobs into one; a node that <em>edits</em> one collects whatever its input produced
 * and then changes every layer that came back. That last part is what makes the graph expressive:
 * point a Resize at a single Rim and it resizes that rim, point it at a Stack and it resizes the
 * whole frame at once — and it does so meaningfully, scaling the offsets and the particle field
 * along with the pictures.
 *
 * <p>That "did this come out of a stack" question is {@link #feedsGroup}, and it also decides what
 * a rotation means: turning one decoration spins the picture in place, turning a whole frame walks
 * every decoration around the avatar.
 */
public final class FrameGraphBuild {

    /** Depth guard: a graph cannot contain a cycle, but a hostile file could still be very deep. */
    private static final int MAX_DEPTH = 128;

    /** Whether a picture at this address is a video or an animation. */
    public interface Animated {
        boolean isAnimated(String src);
    }

    private static Animated probe = src -> false;

    /** The app tells the compiler how to recognise a moving picture; the model cannot know. */
    public static void probe(@Nullable Animated value) {
        probe = value == null ? src -> false : value;
    }

    private FrameGraphBuild() {
    }

    /** The frame a graph describes: whatever reaches its output node. */
    public static FrameSpec spec(@Nullable FrameGraph graph) {
        if (graph == null) {
            return FrameSpec.EMPTY;
        }
        final int output = graph.first(FrameGraphType.FRAME);
        return output <= 0 ? FrameSpec.EMPTY : branch(graph, output);
    }

    /** The frame one branch describes, which is what a preview node shows. */
    public static FrameSpec branch(@Nullable FrameGraph graph, int id) {
        if (graph == null || id <= 0) {
            return FrameSpec.EMPTY;
        }
        final List<FrameSpec.Layer> layers = new ArrayList<>();
        collect(graph, graph.input(id, 0), layers, 0);
        return layers.isEmpty() ? FrameSpec.EMPTY : FrameSpec.of(layers);
    }

    private static void collect(FrameGraph graph, int id, List<FrameSpec.Layer> out, int depth) {
        final FrameGraph.Node node = graph.node(id);
        if (node == null || depth > MAX_DEPTH || out.size() >= FrameSpec.MAX_LAYERS) {
            return;
        }
        if (node.type == FrameGraphType.STACK) {
            final int pins = graph.pins(id);
            for (int pin = 0; pin < pins; pin++) {
                collect(graph, graph.input(id, pin), out, depth + 1);
            }
            return;
        }
        if (FrameGraphType.makesLayer(node.type)) {
            final FrameSpec.Layer layer = shape(graph, node);
            if (layer != null) {
                out.add(layer);
            }
            return;
        }
        if (FrameGraphType.editsLayer(node.type)) {
            // Everything this edit's input produced, and only that: an edit deeper in the graph must
            // not reach back over layers a sibling branch already contributed.
            final int before = out.size();
            collect(graph, graph.input(id, 0), out, depth + 1);
            final boolean group = feedsGroup(graph, graph.input(id, 0), 0);
            for (int i = before; i < out.size(); i++) {
                out.set(i, edit(graph, node, out.get(i), group));
            }
        }
    }

    /** Whether what feeds this edit is a whole stack rather than one decoration. */
    private static boolean feedsGroup(FrameGraph graph, int id, int depth) {
        final FrameGraph.Node node = graph.node(id);
        if (node == null || depth > MAX_DEPTH) {
            return false;
        }
        if (node.type == FrameGraphType.STACK) {
            return true;
        }
        if (FrameGraphType.editsLayer(node.type)) {
            return feedsGroup(graph, graph.input(id, 0), depth + 1);
        }
        return false;
    }

    /**
     * A knob's value: the number wired into it if there is one, and the node's own slider otherwise.
     * A joystick hands over whichever of its two axes matches the knob's role.
     */
    private static int knob(FrameGraph graph, FrameGraph.Node node, int index) {
        final FrameGraph.Node driver =
                graph.node(graph.input(node.id, FrameGraphType.knobPin(index)));
        if (driver == null) {
            return node.value(index);
        }
        if (driver.type == FrameGraphType.VALUE) {
            return driver.value(0);
        }
        if (driver.type == FrameGraphType.PANEL) {
            return driver.value(
                    FrameGraphPanel.knobFor(FrameGraphType.of(node.type), index));
        }
        return node.value(index);
    }

    @Nullable
    private static FrameSpec.Layer shape(FrameGraph graph, FrameGraph.Node node) {
        final String picture = picture(graph, node);
        if (picture.length() == 0) {
            return null;
        }
        final FrameSpec.Builder builder = new FrameSpec.Builder()
                .src(picture)
                .animated(probe.isAnimated(picture));
        return switch (node.type) {
            case FrameGraphType.RIM -> builder
                    .mode(FrameSpec.MODE_STRIP)
                    .width(knob(graph, node, 0))
                    .offset(knob(graph, node, 1))
                    .at(knob(graph, node, 2) / 100f)
                    .spin(knob(graph, node, 3))
                    .seamless(knob(graph, node, 4) != 0)
                    .build();
            case FrameGraphType.PATTERN -> builder
                    .mode(FrameSpec.MODE_STAMP)
                    .width(24)
                    .scale(knob(graph, node, 0))
                    .repeat(knob(graph, node, 1))
                    .offset(knob(graph, node, 2))
                    .at(knob(graph, node, 3) / 100f)
                    .spin(knob(graph, node, 4))
                    .build();
            case FrameGraphType.MARK -> builder
                    .mode(FrameSpec.MODE_MARK)
                    .width(24)
                    .scale(knob(graph, node, 0))
                    .offset(knob(graph, node, 1))
                    .at(knob(graph, node, 2) / 100f)
                    .spin(knob(graph, node, 3))
                    .build();
            case FrameGraphType.STICKER -> builder
                    .mode(FrameSpec.MODE_STICKER)
                    .width(18)
                    .scale(knob(graph, node, 0))
                    .x(knob(graph, node, 1) / 100f + 0.5f)
                    .y(knob(graph, node, 2) / 100f + 0.5f)
                    .turn(knob(graph, node, 3))
                    .build();
            case FrameGraphType.PARTICLES -> builder
                    .mode(FrameSpec.MODE_PARTICLES)
                    .width(10)
                    .repeat(knob(graph, node, 0))
                    .scale(knob(graph, node, 1))
                    .field(knob(graph, node, 2))
                    .spread(knob(graph, node, 3))
                    .speed(knob(graph, node, 4))
                    .build();
            default -> null;
        };
    }

    private static FrameSpec.Layer edit(FrameGraph graph, FrameGraph.Node node,
                                        FrameSpec.Layer layer, boolean group) {
        final FrameSpec.Builder builder = new FrameSpec.Builder(layer);
        return switch (node.type) {
            case FrameGraphType.TINT -> builder.tint(colour(graph, node)).build();
            case FrameGraphType.FADE ->
                    builder.tint(shade(layer.tint, knob(graph, node, 0))).build();
            case FrameGraphType.SCATTER ->
                    builder.scatter(layer.scatter + knob(graph, node, 0)).build();
            case FrameGraphType.TURBULENCE ->
                    builder.swirl(layer.swirl + knob(graph, node, 0)).build();
            case FrameGraphType.GRAVITY ->
                    builder.gravity(layer.gravity + knob(graph, node, 0)).build();
            case FrameGraphType.SPIN -> builder
                    .turn(layer.turn + knob(graph, node, 0))
                    .twist(layer.twist + knob(graph, node, 1))
                    .build();
            case FrameGraphType.TWINKLE ->
                    builder.twinkle(layer.twinkle + knob(graph, node, 0)).build();
            case FrameGraphType.JITTER ->
                    builder.chaos(layer.chaos + knob(graph, node, 0)).build();
            case FrameGraphType.FLOW -> builder
                    .flow(knob(graph, node, 0))
                    .course(layer.course + knob(graph, node, 1))
                    .build();
            case FrameGraphType.PLACE -> builder
                    .at(layer.at + knob(graph, node, 0) / 100f)
                    .offset(layer.offset + knob(graph, node, 1))
                    .build();
            case FrameGraphType.TURN -> rotate(layer, knob(graph, node, 0), group);
            case FrameGraphType.SIZE -> resize(layer, knob(graph, node, 0), group);
            case FrameGraphType.TRANSFORM -> transform(graph, node, layer, group);
            default -> layer;
        };
    }

    private static FrameSpec.Layer transform(FrameGraph graph, FrameGraph.Node node,
                                             FrameSpec.Layer layer, boolean group) {
        final FrameSpec.Layer turned =
                resize(rotate(layer, knob(graph, node, 4), group), knob(graph, node, 5), group);
        return new FrameSpec.Builder(turned)
                .at(turned.at + knob(graph, node, 0) / 100f)
                .offset(turned.offset + knob(graph, node, 1))
                .x(turned.x + knob(graph, node, 2) / 100f)
                .y(turned.y + knob(graph, node, 3) / 100f)
                .build();
    }

    /**
     * Resizing one decoration changes its picture. Resizing a whole frame also moves everything
     * further out and widens the particle field, so the frame grows rather than crowding the avatar.
     */
    private static FrameSpec.Layer resize(FrameSpec.Layer layer, int percent, boolean group) {
        final float factor = percent / 100f;
        final FrameSpec.Builder builder = new FrameSpec.Builder(layer);
        if (layer.mode == FrameSpec.MODE_STRIP) {
            builder.width(Math.round(layer.width * factor));
        } else {
            builder.scale(Math.round(layer.scale * factor));
        }
        if (!group) {
            return builder.build();
        }
        builder.offset(Math.round(layer.offset * factor));
        if (layer.mode == FrameSpec.MODE_STICKER) {
            builder.x((layer.x - 0.5f) * factor + 0.5f)
                    .y((layer.y - 0.5f) * factor + 0.5f);
        } else if (layer.mode == FrameSpec.MODE_PARTICLES) {
            builder.field(Math.round(layer.field * factor))
                    .spread(Math.round(layer.spread * factor));
        }
        return builder.build();
    }

    /** Turning one decoration spins it; turning a frame walks it round the avatar. */
    private static FrameSpec.Layer rotate(FrameSpec.Layer layer, int degrees, boolean group) {
        final FrameSpec.Builder builder = new FrameSpec.Builder(layer);
        return group
                ? builder.orbit(layer.orbit + degrees).build()
                : builder.turn(layer.turn + degrees).build();
    }

    private static String picture(FrameGraph graph, FrameGraph.Node node) {
        final FrameGraph.Node source = graph.node(graph.input(node.id, 0));
        return (source != null && source.type == FrameGraphType.IMAGE) ? source.text(0) : "";
    }

    private static int colour(FrameGraph graph, FrameGraph.Node node) {
        final FrameGraph.Node source = graph.node(graph.input(node.id, 1));
        if (source != null && source.type == FrameGraphType.COLOR) {
            return source.value(0);
        }
        return knob(graph, node, 0);
    }

    /** Fading is done by darkening the tint rather than by an alpha, which the spec has no room for. */
    private static int shade(int tint, int percent) {
        final int amount = Math.max(0, Math.min(100, percent));
        final int colour = tint == 0 ? 0xFFFFFFFF : tint;
        final int r = ((colour >> 16) & 0xFF) * amount / 100;
        final int g = ((colour >> 8) & 0xFF) * amount / 100;
        final int b = (colour & 0xFF) * amount / 100;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    // ---------------------------------------------------------------- a graph from a frame

    /**
     * Lays a frame out as a graph, so a frame installed from the workshop can be opened in the studio.
     *
     * <p>Best effort, not a round trip: the five shape nodes carry a fixed picture width, and nothing
     * describes a corner picture or an unrolled ring, so those are lost. That is why the spec stays
     * authoritative — see {@code FrameGraphStore}.
     */
    public static FrameGraph of(@Nullable FrameSpec spec) {
        final FrameGraph graph = FrameGraph.empty();
        final int frame = graph.add(FrameGraphType.FRAME, 750, 0);
        final int view = graph.add(FrameGraphType.VIEW, 1000, 0);
        final int stack = graph.add(FrameGraphType.STACK, 500, 0);
        graph.link(stack, frame, 0);
        graph.link(stack, view, 0);
        if (spec == null || spec.isEmpty()) {
            return graph;
        }
        int placed = 0;
        for (int i = 0; i < spec.layers().size(); i++) {
            final FrameSpec.Layer layer = spec.layers().get(i);
            if (layer.off) {
                continue;
            }
            final int row = (placed - (spec.layers().size() - 1) / 2) * FrameGraph.STEP_Y;
            final int shape = shapeOf(graph, layer, row);
            if (shape <= 0) {
                continue;
            }
            final int picture = graph.add(FrameGraphType.IMAGE, -FrameGraph.STEP_X, row);
            graph.set(picture, 0, layer.src);
            graph.link(picture, shape, 0);
            graph.link(edits(graph, layer, shape, row), stack, placed);
            placed++;
        }
        return graph;
    }

    private static int shapeOf(FrameGraph graph, FrameSpec.Layer layer, int row) {
        switch (layer.mode) {
            case FrameSpec.MODE_STRIP: {
                final int id = graph.add(FrameGraphType.RIM, 0, row);
                graph.set(id, 0, layer.width);
                graph.set(id, 1, layer.offset);
                graph.set(id, 2, Math.round(layer.at * 100f));
                graph.set(id, 3, layer.spin);
                graph.set(id, 4, layer.seamless ? 1 : 0);
                return id;
            }
            case FrameSpec.MODE_STAMP: {
                final int id = graph.add(FrameGraphType.PATTERN, 0, row);
                graph.set(id, 0, layer.scale);
                graph.set(id, 1, layer.repeat);
                graph.set(id, 2, layer.offset);
                graph.set(id, 3, Math.round(layer.at * 100f));
                graph.set(id, 4, layer.spin);
                return id;
            }
            case FrameSpec.MODE_MARK: {
                final int id = graph.add(FrameGraphType.MARK, 0, row);
                graph.set(id, 0, layer.scale);
                graph.set(id, 1, layer.offset);
                graph.set(id, 2, Math.round(layer.at * 100f));
                graph.set(id, 3, layer.spin);
                return id;
            }
            case FrameSpec.MODE_STICKER: {
                final int id = graph.add(FrameGraphType.STICKER, 0, row);
                graph.set(id, 0, layer.scale);
                graph.set(id, 1, Math.round((layer.x - 0.5f) * 100f));
                graph.set(id, 2, Math.round((layer.y - 0.5f) * 100f));
                graph.set(id, 3, layer.turn);
                return id;
            }
            case FrameSpec.MODE_PARTICLES: {
                final int id = graph.add(FrameGraphType.PARTICLES, 0, row);
                graph.set(id, 0, layer.repeat);
                graph.set(id, 1, layer.scale);
                graph.set(id, 2, layer.field);
                graph.set(id, 3, layer.spread);
                graph.set(id, 4, layer.speed);
                return id;
            }
            default:
                return 0;
        }
    }

    /** The chain of edit nodes that reproduces everything the shape node itself cannot carry. */
    private static int edits(FrameGraph graph, FrameSpec.Layer layer, int shape, int row) {
        int tail = shape;
        int step = 0;
        if (layer.tint != 0) {
            step++;
            tail = chain(graph, tail, FrameGraphType.TINT, row, step, layer.tint, 0);
        }
        if (layer.mode != FrameSpec.MODE_PARTICLES) {
            return tail;
        }
        if (layer.flow != 0 || layer.course != 0) {
            step++;
            tail = chain(graph, tail, FrameGraphType.FLOW, row, step, layer.flow, layer.course);
        }
        if (layer.scatter != 0) {
            step++;
            tail = chain(graph, tail, FrameGraphType.SCATTER, row, step, layer.scatter, 0);
        }
        if (layer.swirl != 0) {
            step++;
            tail = chain(graph, tail, FrameGraphType.TURBULENCE, row, step, layer.swirl, 0);
        }
        if (layer.gravity != 0) {
            step++;
            tail = chain(graph, tail, FrameGraphType.GRAVITY, row, step, layer.gravity, 0);
        }
        if (layer.turn != 0 || layer.twist != 0) {
            step++;
            tail = chain(graph, tail, FrameGraphType.SPIN, row, step, layer.turn, layer.twist);
        }
        if (layer.twinkle != 0) {
            step++;
            tail = chain(graph, tail, FrameGraphType.TWINKLE, row, step, layer.twinkle, 0);
        }
        if (layer.chaos != 0) {
            step++;
            tail = chain(graph, tail, FrameGraphType.JITTER, row, step, layer.chaos, 0);
        }
        return tail;
    }

    private static int chain(FrameGraph graph, int from, int type, int row, int step,
                             int first, int second) {
        final int id = graph.add(type, step * 125, row + step * 50);
        graph.set(id, 0, first);
        final FrameGraphType.Kind kind = FrameGraphType.of(type);
        if (kind != null && kind.knobs() > 1) {
            graph.set(id, 1, second);
        }
        graph.link(from, id, 0);
        return id;
    }
}
