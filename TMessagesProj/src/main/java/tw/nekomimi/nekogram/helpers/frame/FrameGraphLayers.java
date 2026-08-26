package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The layer list, as a view onto the graph.
 *
 * <p>The studio's first tab shows a frame as what it looks like — a stack of decorations, each with a
 * picture and a handful of sliders. There is no second model behind that: every one of those rows is
 * a shape node hanging off the single stack node, and every slider writes straight into the graph. So
 * this is the translation, and it is the only place that knows the two views are the same thing.
 *
 * <p>Everything here reads the graph as the compiler does — a chain of edit nodes ending at a shape
 * node is one decoration — so a frame drawn on the canvas by hand still shows up in the list, as long
 * as it is shaped like a list.
 */
public final class FrameGraphLayers {

    private static final int MAX_DEPTH = 128;

    private FrameGraphLayers() {
    }

    /** The output node, adding one if the graph somehow has none. */
    public static int frame(@Nullable FrameGraph graph) {
        if (graph == null) {
            return 0;
        }
        final int found = graph.first(FrameGraphType.FRAME);
        return found > 0 ? found : graph.add(FrameGraphType.FRAME, 750, 0);
    }

    /** The stack feeding the output, or 0 when a single decoration is wired straight into it. */
    public static int stack(@Nullable FrameGraph graph) {
        if (graph == null) {
            return 0;
        }
        final int frame = graph.first(FrameGraphType.FRAME);
        if (frame <= 0) {
            return 0;
        }
        int at = graph.input(frame, 0);
        for (int guard = 0; guard <= MAX_DEPTH; guard++) {
            final FrameGraph.Node node = graph.node(at);
            if (node == null) {
                return 0;
            }
            if (node.type == FrameGraphType.STACK) {
                return node.id;
            }
            if (!FrameGraphType.editsLayer(node.type)) {
                return 0;
            }
            at = graph.input(at, 0);
        }
        return 0;
    }

    /** The stack, inserting one above whatever is already there if the graph has none. */
    public static int ensure(@Nullable FrameGraph graph) {
        if (graph == null) {
            return 0;
        }
        final int frame = frame(graph);
        if (frame <= 0) {
            return 0;
        }
        final int existing = stack(graph);
        if (existing > 0) {
            return existing;
        }
        final int wasFeeding = graph.input(frame, 0);
        final int stack = graph.add(FrameGraphType.STACK, 500, 0);
        if (wasFeeding > 0) {
            graph.cut(frame, 0);
            graph.link(wasFeeding, stack, 0);
        }
        graph.link(stack, frame, 0);
        return stack;
    }

    /** One shape node per decoration, in the order the frame draws them. */
    public static List<Integer> shapes(@Nullable FrameGraph graph) {
        final List<Integer> out = new ArrayList<>();
        if (graph == null) {
            return out;
        }
        final int stack = stack(graph);
        if (stack <= 0) {
            final int frame = graph.first(FrameGraphType.FRAME);
            final int only = frame > 0 ? shapeOf(graph, graph.input(frame, 0), 0) : 0;
            if (only > 0) {
                out.add(only);
            }
            return out;
        }
        final int pins = graph.pins(stack);
        for (int pin = 0; pin < pins; pin++) {
            final int shape = shapeOf(graph, graph.input(stack, pin), 0);
            if (shape > 0) {
                out.add(shape);
            }
        }
        return out;
    }

    /** Follows an edit chain down to the shape node it started from. */
    private static int shapeOf(FrameGraph graph, int id, int depth) {
        final FrameGraph.Node node = graph.node(id);
        if (node == null || depth > MAX_DEPTH) {
            return 0;
        }
        if (FrameGraphType.makesLayer(node.type)) {
            return node.id;
        }
        if (FrameGraphType.editsLayer(node.type)) {
            return shapeOf(graph, graph.input(id, 0), depth + 1);
        }
        return 0;
    }

    /** The last node of a decoration's edit chain — what the stack actually holds. */
    public static int top(FrameGraph graph, int shape) {
        int at = shape;
        for (int guard = 0; guard <= MAX_DEPTH; guard++) {
            final int next = graph.output(at);
            final FrameGraph.Node node = graph.node(next);
            if (node == null || !FrameGraphType.editsLayer(node.type)) {
                break;
            }
            at = next;
        }
        return at;
    }

    /** The edit nodes stacked on one decoration, nearest first. */
    public static List<Integer> edits(FrameGraph graph, int shape) {
        final List<Integer> out = new ArrayList<>();
        int at = shape;
        for (int guard = 0; guard <= MAX_DEPTH; guard++) {
            at = graph.output(at);
            final FrameGraph.Node node = graph.node(at);
            if (node == null || !FrameGraphType.editsLayer(node.type)) {
                break;
            }
            out.add(at);
        }
        return out;
    }

    public static int image(FrameGraph graph, int shape) {
        final FrameGraph.Node node = graph.node(graph.input(shape, 0));
        return (node == null || node.type != FrameGraphType.IMAGE) ? 0 : node.id;
    }

    public static String picture(FrameGraph graph, int shape) {
        final FrameGraph.Node node = graph.node(image(graph, shape));
        return node == null ? "" : node.text(0);
    }

    /** Points a decoration at a picture, adding the picture node if it has none. */
    public static void picture(FrameGraph graph, int shape, String src) {
        int image = image(graph, shape);
        if (image <= 0) {
            final FrameGraph.Node node = graph.node(shape);
            image = graph.add(FrameGraphType.IMAGE,
                    node == null ? 0 : node.x - FrameGraph.STEP_X,
                    node == null ? 0 : node.y);
            graph.link(image, shape, 0);
        }
        graph.set(image, 0, src);
    }

    /**
     * Adds a decoration of a kind. The very first one is wired straight into the output — a frame
     * with one decoration needs no stack, and not making one keeps a simple graph simple.
     */
    public static int add(FrameGraph graph, int type, @Nullable String src) {
        if (!FrameGraphType.makesLayer(type) || shapes(graph).size() >= FrameSpec.MAX_LAYERS) {
            return 0;
        }
        final boolean first = stack(graph) <= 0 && shapes(graph).isEmpty();
        final int frame = frame(graph);
        if (frame <= 0) {
            return 0;
        }
        final int stack = first ? 0 : ensure(graph);
        if (!first && stack <= 0) {
            return 0;
        }
        final int pin = first ? 0 : free(graph, stack);
        final int row = (pin - 1) * FrameGraph.STEP_Y;
        final int shape = graph.add(type, 0, row);
        if (shape <= 0) {
            return 0;
        }
        final int image = graph.add(FrameGraphType.IMAGE, -FrameGraph.STEP_X, row);
        graph.set(image, 0, src == null ? "" : src);
        graph.link(image, shape, 0);
        if (first) {
            graph.link(shape, frame, 0);
        } else {
            graph.link(shape, stack, pin);
        }
        return shape;
    }

    private static int free(FrameGraph graph, int stack) {
        final int pins = graph.pins(stack);
        for (int pin = 0; pin < pins; pin++) {
            if (graph.input(stack, pin) == 0) {
                return pin;
            }
        }
        return Math.max(0, pins - 1);
    }

    /** Removes a decoration, its edits, and its picture node if nothing else uses it. */
    public static void remove(FrameGraph graph, int shape) {
        if (graph.node(shape) == null) {
            return;
        }
        final List<Integer> edits = edits(graph, shape);
        final int image = image(graph, shape);
        for (int i = 0; i < edits.size(); i++) {
            graph.drop(edits.get(i));
        }
        if (image > 0 && graph.filled(image) == 0) {
            graph.drop(image);
        }
        graph.drop(shape);
    }

    /** Moves a decoration up or down the stack, which is what changes what covers what. */
    public static void move(FrameGraph graph, int shape, boolean up) {
        final int stack = stack(graph);
        if (stack <= 0) {
            return;
        }
        final int held = top(graph, shape);
        final int pins = graph.pins(stack);
        int at = -1;
        for (int pin = 0; pin < pins; pin++) {
            if (graph.input(stack, pin) == held) {
                at = pin;
            }
        }
        final int to = up ? at - 1 : at + 1;
        if (at < 0 || to < 0 || to >= pins) {
            return;
        }
        graph.swap(stack, at, to);
    }

    /** Adds an effect on top of a decoration, splicing it into whatever the decoration fed. */
    public static int attach(FrameGraph graph, int shape, int type) {
        if (!FrameGraphType.editsLayer(type) || graph.node(shape) == null) {
            return 0;
        }
        final int held = top(graph, shape);
        final FrameGraph.Node node = graph.node(held);
        if (node == null) {
            return 0;
        }
        final int added = graph.add(type, node.x + 125, node.y + 50);
        if (added <= 0) {
            return 0;
        }
        final int fed = graph.output(held);
        final int pin = pinOf(graph, fed, held);
        graph.link(held, added, 0);
        if (fed > 0 && pin >= 0) {
            graph.link(added, fed, pin);
        }
        return added;
    }

    /** Takes an effect out and joins its neighbours back up. */
    public static void detach(FrameGraph graph, int id) {
        final FrameGraph.Node node = graph.node(id);
        if (node == null || !FrameGraphType.editsLayer(node.type)) {
            return;
        }
        final int feeding = graph.input(id, 0);
        final int fed = graph.output(id);
        final int pin = pinOf(graph, fed, id);
        graph.drop(id);
        if (feeding <= 0 || fed <= 0 || pin < 0) {
            return;
        }
        graph.link(feeding, fed, pin);
    }

    private static int pinOf(FrameGraph graph, int to, int from) {
        for (int i = 0; i < graph.wires().size(); i++) {
            final FrameGraph.Wire wire = graph.wires().get(i);
            if (wire.to == to && wire.from == from) {
                return wire.pin;
            }
        }
        return -1;
    }
}
