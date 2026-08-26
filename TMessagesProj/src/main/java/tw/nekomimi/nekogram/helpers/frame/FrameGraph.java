package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The node graph a frame is authored as: nodes with knob values, and wires between them.
 *
 * <p>This is the authoring state, not the frame. {@link FrameGraphBuild} compiles it into a
 * {@link FrameSpec}, and it is the spec that the profile draws and that travels to other users — a
 * reader has no use for the graph and could not draw from it. Both are saved together; see
 * {@code FrameGraphStore}.
 *
 * <p><b>On-disk format</b>, and it must round-trip with the reference:
 * {@code {"nodes":[{id,t,x,y,o,v[],s[]}], "look":3, "wires":[[from,to,pin]]}}. The {@code look}
 * number is a migration counter: below 2 the coordinates were in a different unit and below 3 the
 * sticker's two placement knobs were stored with an offset of 50.
 *
 * <p>Everything is bounded — 128 nodes, 64KiB of text — and no wire may ever close a loop, which is
 * checked both when one is drawn and again when a graph is read, because a graph can come from a file
 * somebody else wrote.
 */
public final class FrameGraph {

    public static final int MAX_NODES = 128;
    public static final int MAX_TEXT = 65536;

    /** How far apart {@link FrameGraphBuild#of} lays nodes out. */
    public static final int STEP_X = 250;
    public static final int STEP_Y = 150;

    /** The format this app writes. */
    private static final int LOOK = 3;
    /** Below this, coordinates were in a tighter unit. */
    private static final int LOOK_WIDE = 2;
    private static final float LOOK_SPREAD = 1.8857143f;
    /** Below this, a sticker's placement knobs carried an offset. */
    private static final int LOOK_MIDDLE = 3;
    private static final int LOOK_SHIFT = 50;

    private final List<Node> nodes;
    private final List<Wire> wires;

    private FrameGraph(List<Node> nodes, List<Wire> wires) {
        this.nodes = nodes;
        this.wires = wires;
    }

    public static FrameGraph empty() {
        return new FrameGraph(new ArrayList<>(), new ArrayList<>());
    }

    public static final class Node {
        public final int id;
        public final int type;
        public int x;
        public int y;
        /** Whether the card is unfolded on the canvas. */
        public boolean open;
        public final int[] values;
        public final String[] texts;

        Node(int id, int type, int x, int y, boolean open, int[] values, String[] texts) {
            this.id = id;
            this.type = type;
            this.x = x;
            this.y = y;
            this.open = open;
            this.values = values;
            this.texts = texts;
        }

        public int value(int knob) {
            if (knob < 0 || knob >= values.length) {
                return 0;
            }
            return values[knob];
        }

        public String text(int knob) {
            if (knob < 0 || knob >= texts.length) {
                return "";
            }
            return texts[knob] == null ? "" : texts[knob];
        }

        Node copy() {
            return new Node(id, type, x, y, open, values.clone(), texts.clone());
        }
    }

    public static final class Wire {
        public final int from;
        public final int to;
        public final int pin;

        Wire(int from, int to, int pin) {
            this.from = from;
            this.to = to;
            this.pin = pin;
        }
    }

    public List<Node> nodes() {
        return nodes;
    }

    public List<Wire> wires() {
        return wires;
    }

    public int count() {
        return nodes.size();
    }

    @Nullable
    public Node node(int id) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).id == id) {
                return nodes.get(i);
            }
        }
        return null;
    }

    /** The first node of a kind; how the output node is found. */
    public int first(int type) {
        for (int i = 0; i < nodes.size(); i++) {
            if (nodes.get(i).type == type) {
                return nodes.get(i).id;
            }
        }
        return 0;
    }

    /** What is plugged into a node's pin, or 0. */
    public int input(int id, int pin) {
        for (int i = 0; i < wires.size(); i++) {
            final Wire wire = wires.get(i);
            if (wire.to == id && wire.pin == pin) {
                return wire.from;
            }
        }
        return 0;
    }

    /**
     * How many pins a node shows. A growing pin always shows one empty socket past the last filled
     * one, so a stack never runs out of room.
     */
    public int pins(int id) {
        final Node node = node(id);
        final FrameGraphType.Kind kind = node == null ? null : FrameGraphType.of(node.type);
        if (kind == null) {
            return 0;
        }
        if (!kind.many()) {
            return kind.pins();
        }
        int highest = kind.pins() - 2;
        for (int i = 0; i < wires.size(); i++) {
            if (wires.get(i).to == id) {
                highest = Math.max(highest, wires.get(i).pin);
            }
        }
        return highest + 2;
    }

    public int filled(int id) {
        int count = 0;
        for (int i = 0; i < wires.size(); i++) {
            if (wires.get(i).to == id) {
                count++;
            }
        }
        return count;
    }

    public int output(int id) {
        for (int i = 0; i < wires.size(); i++) {
            if (wires.get(i).from == id) {
                return wires.get(i).to;
            }
        }
        return 0;
    }

    /** Whether following wires forward from {@code from} ever arrives at {@code to}. */
    public boolean reaches(int from, int to) {
        if (from == to) {
            return true;
        }
        final List<Integer> seen = new ArrayList<>();
        final List<Integer> queue = new ArrayList<>();
        queue.add(from);
        while (!queue.isEmpty() && seen.size() <= MAX_NODES) {
            final Integer at = queue.remove(0);
            if (seen.contains(at)) {
                continue;
            }
            seen.add(at);
            for (int i = 0; i < wires.size(); i++) {
                final Wire wire = wires.get(i);
                if (wire.from == at) {
                    if (wire.to == to) {
                        return true;
                    }
                    queue.add(wire.to);
                }
            }
        }
        return false;
    }

    public int nextId() {
        int highest = 0;
        for (int i = 0; i < nodes.size(); i++) {
            highest = Math.max(highest, nodes.get(i).id);
        }
        return highest + 1;
    }

    /** Adds a node with every knob at its default. Returns its id, or 0 if the graph is full. */
    public int add(int type, int x, int y) {
        final FrameGraphType.Kind kind = FrameGraphType.of(type);
        if (kind == null || nodes.size() >= MAX_NODES) {
            return 0;
        }
        final int count = kind.knobs();
        final int[] values = new int[count];
        final String[] texts = new String[count];
        for (int i = 0; i < count; i++) {
            values[i] = kind.knobs[i].start;
            texts[i] = "";
        }
        final int id = nextId();
        nodes.add(new Node(id, type, x, y, false, values, texts));
        return id;
    }

    public void drop(int id) {
        for (int i = nodes.size() - 1; i >= 0; i--) {
            if (nodes.get(i).id == id) {
                nodes.remove(i);
            }
        }
        for (int i = wires.size() - 1; i >= 0; i--) {
            final Wire wire = wires.get(i);
            if (wire.from == id || wire.to == id) {
                wires.remove(i);
            }
        }
    }

    public void move(int id, int x, int y) {
        final Node node = node(id);
        if (node != null) {
            node.x = x;
            node.y = y;
        }
    }

    public void toggle(int id) {
        final Node node = node(id);
        if (node != null) {
            node.open = !node.open;
        }
    }

    public void set(int id, int knob, int value) {
        final Node node = node(id);
        final FrameGraphType.Kind kind = node == null ? null : FrameGraphType.of(node.type);
        if (kind == null || knob < 0 || knob >= node.values.length) {
            return;
        }
        node.values[knob] = kind.knobs[knob].clamp(value);
    }

    public void set(int id, int knob, @Nullable String text) {
        final Node node = node(id);
        if (node == null || knob < 0 || knob >= node.texts.length) {
            return;
        }
        node.texts[knob] = text == null ? "" : text;
    }

    /**
     * Draws a wire. Refuses one that would not fit, and one that would close a loop — a graph with a
     * cycle in it would not compile, it would hang.
     */
    public boolean link(int from, int to, int pin) {
        final Node source = node(from);
        final Node target = node(to);
        if (source == null || target == null || from == to || pin < 0
                || !FrameGraphType.fits(source.type, target.type, pin) || reaches(to, from)) {
            return false;
        }
        for (int i = wires.size() - 1; i >= 0; i--) {
            final Wire wire = wires.get(i);
            if (wire.to == to && wire.pin == pin) {
                wires.remove(i);
            }
        }
        wires.add(new Wire(from, to, pin));
        return true;
    }

    public void cut(int to, int pin) {
        for (int i = wires.size() - 1; i >= 0; i--) {
            final Wire wire = wires.get(i);
            if (wire.to == to && wire.pin == pin) {
                wires.remove(i);
            }
        }
    }

    /** Swaps what two of a node's pins hold, which is how a stack is reordered. */
    public void swap(int id, int a, int b) {
        if (a == b) {
            return;
        }
        for (int i = 0; i < wires.size(); i++) {
            final Wire wire = wires.get(i);
            if (wire.to != id) {
                continue;
            }
            if (wire.pin == a) {
                wires.set(i, new Wire(wire.from, id, b));
            } else if (wire.pin == b) {
                wires.set(i, new Wire(wire.from, id, a));
            }
        }
    }

    /** A copy with every picture address replaced, which is how a graph's assets are rehosted. */
    public FrameGraph swap(@Nullable Map<String, String> swaps) {
        if (swaps == null || swaps.isEmpty()) {
            return this;
        }
        final FrameGraph copy = copy();
        for (int i = 0; i < copy.nodes.size(); i++) {
            final String[] texts = copy.nodes.get(i).texts;
            for (int j = 0; j < texts.length; j++) {
                final String replacement = texts[j] == null ? null : swaps.get(texts[j]);
                if (replacement != null) {
                    texts[j] = replacement;
                }
            }
        }
        return copy;
    }

    /** Every picture address the graph names. */
    public List<String> sources() {
        final List<String> out = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            for (String text : nodes.get(i).texts) {
                if (text != null && text.length() > 0 && !FrameBlanks.is(text)
                        && !out.contains(text) && looksLikeSource(text)) {
                    out.add(text);
                }
            }
        }
        return out;
    }

    private static boolean looksLikeSource(String text) {
        return text.startsWith("/") || text.startsWith("http://")
                || text.startsWith("https://") || text.startsWith("content://");
    }

    public FrameGraph copy() {
        final List<Node> copiedNodes = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            copiedNodes.add(nodes.get(i).copy());
        }
        final List<Wire> copiedWires = new ArrayList<>();
        for (int i = 0; i < wires.size(); i++) {
            final Wire wire = wires.get(i);
            copiedWires.add(new Wire(wire.from, wire.to, wire.pin));
        }
        return new FrameGraph(copiedNodes, copiedWires);
    }

    // ---------------------------------------------------------------- on disk

    public String encode() {
        try {
            final JSONArray nodeArray = new JSONArray();
            for (int i = 0; i < nodes.size(); i++) {
                final Node node = nodes.get(i);
                final JSONObject item = new JSONObject();
                item.put("id", node.id);
                item.put("t", node.type);
                item.put("x", node.x);
                item.put("y", node.y);
                if (node.open) {
                    item.put("o", true);
                }
                final JSONArray values = new JSONArray();
                for (int knob = 0; knob < node.values.length; knob++) {
                    values.put(node.values[knob]);
                }
                item.put("v", values);
                final JSONArray texts = new JSONArray();
                boolean any = false;
                for (int knob = 0; knob < node.texts.length; knob++) {
                    final String text = node.texts[knob] == null ? "" : node.texts[knob];
                    texts.put(text);
                    any |= text.length() > 0;
                }
                // Only written when there is something in it: most nodes have no text at all.
                if (any) {
                    item.put("s", texts);
                }
                nodeArray.put(item);
            }
            final JSONArray wireArray = new JSONArray();
            for (int i = 0; i < wires.size(); i++) {
                final Wire wire = wires.get(i);
                final JSONArray triple = new JSONArray();
                triple.put(wire.from);
                triple.put(wire.to);
                triple.put(wire.pin);
                wireArray.put(triple);
            }
            final JSONObject out = new JSONObject();
            out.put("nodes", nodeArray);
            out.put("look", LOOK);
            out.put("wires", wireArray);
            final String encoded = out.toString();
            return encoded.length() > MAX_TEXT ? "" : encoded;
        } catch (Throwable ignore) {
            return "";
        }
    }

    public static FrameGraph parse(@Nullable String json) {
        final FrameGraph graph = empty();
        if (json == null || json.length() == 0 || json.length() > MAX_TEXT) {
            return graph;
        }
        try {
            final JSONObject root = new JSONObject(json);
            final int look = root.optInt("look", 0);
            // Two migrations, both from graphs the reference wrote before we ever saw one.
            final float spread = look >= LOOK_WIDE ? 1f : LOOK_SPREAD;
            final int shift = look >= LOOK_MIDDLE ? 0 : LOOK_SHIFT;

            final JSONArray nodeArray = root.optJSONArray("nodes");
            for (int i = 0; nodeArray != null && i < nodeArray.length()
                    && graph.nodes.size() < MAX_NODES; i++) {
                final JSONObject item = nodeArray.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                final int id = item.optInt("id", 0);
                final int type = item.optInt("t", 0);
                final FrameGraphType.Kind kind = FrameGraphType.of(type);
                if (id <= 0 || kind == null || graph.node(id) != null) {
                    continue;
                }
                final int count = kind.knobs();
                final int[] values = new int[count];
                final String[] texts = new String[count];
                final JSONArray storedValues = item.optJSONArray("v");
                final JSONArray storedTexts = item.optJSONArray("s");
                for (int knob = 0; knob < count; knob++) {
                    int value = (storedValues != null && knob < storedValues.length())
                            ? storedValues.optInt(knob, kind.knobs[knob].start)
                            : kind.knobs[knob].start;
                    if (shift != 0 && type == FrameGraphType.STICKER && knob >= 1 && knob <= 2) {
                        value -= shift;
                    }
                    values[knob] = kind.knobs[knob].clamp(value);
                    texts[knob] = (storedTexts != null && knob < storedTexts.length())
                            ? storedTexts.optString(knob, "") : "";
                }
                graph.nodes.add(new Node(id, type,
                        Math.round(item.optInt("x", 0) * spread),
                        Math.round(item.optInt("y", 0) * spread),
                        item.optBoolean("o", false), values, texts));
            }

            final JSONArray wireArray = root.optJSONArray("wires");
            for (int i = 0; wireArray != null && i < wireArray.length(); i++) {
                final JSONArray triple = wireArray.optJSONArray(i);
                if (triple == null || triple.length() < 3) {
                    continue;
                }
                final int from = triple.optInt(0, 0);
                final int to = triple.optInt(1, 0);
                final int pin = triple.optInt(2, -1);
                final Node source = graph.node(from);
                final Node target = graph.node(to);
                if (source == null || target == null || pin < 0
                        || !FrameGraphType.fits(source.type, target.type, pin)) {
                    continue;
                }
                if (graph.taken(to, pin) || graph.reaches(to, from)) {
                    continue;
                }
                graph.wires.add(new Wire(from, to, pin));
            }
        } catch (Throwable ignore) {
            return empty();
        }
        return graph;
    }

    private boolean taken(int to, int pin) {
        for (int i = 0; i < wires.size(); i++) {
            final Wire wire = wires.get(i);
            if (wire.to == to && wire.pin == pin) {
                return true;
            }
        }
        return false;
    }
}
