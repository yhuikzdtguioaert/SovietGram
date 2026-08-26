package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * What kinds of node a frame graph can hold, and what each one plugs into.
 *
 * <p>Twenty-four kinds in seven categories. A node <em>gives</em> one thing — a picture, a number, a
 * colour, a layer or a whole frame — and <em>takes</em> a fixed list of those on its pins. Three
 * questions come out of that and the whole editor rests on them: {@link #makesLayer} (this node turns
 * a picture into a layer), {@link #editsLayer} (this node takes a layer and hands back a changed one),
 * and {@link #fits} (this output may be plugged into that pin).
 *
 * <p><b>Knobs double as pins.</b> Any numeric knob can be driven by a wire instead of by its slider:
 * pin number {@code knob + 100} is that knob's socket. That is how one "Значение" node can drive the
 * width of four different layers at once, and how a joystick steers two knobs with one finger.
 *
 * <p>Names are resource ids rather than text, so the same graph reads in whatever language the app is
 * in. Knobs are found by <b>index</b>, never by name — the reference matched on the Russian string,
 * which cannot survive being translated.
 */
public final class FrameGraphType {

    // Categories, in the order the picker shows them.
    public static final int CAT_INPUT = 0;
    public static final int CAT_SHAPE = 1;
    public static final int CAT_PARTICLE = 2;
    public static final int CAT_COLOR = 3;
    public static final int CAT_TRANSFORM = 4;
    public static final int CAT_LAYOUT = 5;
    public static final int CAT_OUTPUT = 6;

    public static final String[] CATEGORY_SLUGS = {
            "Sources", "Decor", "Particles", "Colour", "Placement", "Assembly", "Output",
    };

    // The node kinds. The numbers are the graph's on-disk format and cannot change.
    public static final int IMAGE = 1;
    public static final int COLOR = 3;
    public static final int VALUE = 5;
    public static final int PANEL = 6;
    public static final int RIM = 10;
    public static final int PATTERN = 11;
    public static final int MARK = 12;
    public static final int STICKER = 13;
    public static final int PARTICLES = 14;
    public static final int SCATTER = 20;
    public static final int TURBULENCE = 21;
    public static final int GRAVITY = 22;
    public static final int SPIN = 23;
    public static final int TWINKLE = 24;
    public static final int JITTER = 25;
    public static final int FLOW = 26;
    public static final int TINT = 30;
    public static final int FADE = 31;
    public static final int PLACE = 40;
    public static final int TURN = 41;
    public static final int SIZE = 42;
    public static final int TRANSFORM = 43;
    public static final int STACK = 50;
    public static final int FRAME = 60;
    public static final int VIEW = 61;

    /** What a pin, or a node's output, carries. */
    public static final int PIN_NONE = 0;
    public static final int PIN_IMAGE = 1;
    public static final int PIN_COLOR = 3;
    public static final int PIN_LAYER = 4;
    public static final int PIN_FRAME = 5;
    public static final int PIN_NUMBER = 6;

    /** What a knob is: a slider, a choice, a colour, a picture, or a free number. */
    public static final int KNOB_RANGE = 0;
    public static final int KNOB_CHOICE = 1;
    public static final int KNOB_COLOR = 2;
    public static final int KNOB_IMAGE = 3;
    public static final int KNOB_NUMBER = 4;

    /** Which way a joystick's axis drives a knob. */
    public static final int ROLE_PLAIN = 0;
    public static final int ROLE_ACROSS = 1;
    public static final int ROLE_DOWN = 2;
    public static final int ROLE_TURN = 3;

    /** Pin numbers at or above this are a knob's own socket. */
    public static final int KNOB_PIN_BASE = 100;

    private static final List<Kind> KINDS = new ArrayList<>();

    private FrameGraphType() {
    }

    public static boolean isKnobPin(int pin) {
        return pin >= KNOB_PIN_BASE;
    }

    public static int knobOfPin(int pin) {
        return pin - KNOB_PIN_BASE;
    }

    public static int knobPin(int knob) {
        return knob + KNOB_PIN_BASE;
    }

    /** Whether the node's card is drawn as a panel with something live in it. */
    public static boolean fillsCard(int type) {
        return type == VIEW || type == PANEL;
    }

    /** One knob: a number with bounds, or a colour, a picture or a choice. */
    public static final class Knob {
        /** Stable id for strings and for finding this knob; never its display name. */
        public final String slug;
        public final int kind;
        public final int min;
        public final int max;
        public final int start;
        /** How many options a choice knob has; 0 for everything else. */
        public final int options;
        public final int role;

        Knob(String slug, int kind, int min, int max, int start, int options, int role) {
            this.slug = slug;
            this.kind = kind;
            this.min = min;
            this.max = max;
            this.start = start;
            this.options = options;
            this.role = role;
        }

        /** Colours, pictures and free numbers pass through; everything else is bounded. */
        public int clamp(int value) {
            if (kind == KNOB_COLOR || kind == KNOB_IMAGE || kind == KNOB_NUMBER) {
                return value;
            }
            return Math.max(min, Math.min(max, value));
        }
    }

    public static final class Pin {
        public final String slug;
        public final int kind;
        /** Whether this pin grows: a new empty one appears as soon as the last is filled. */
        public final boolean many;

        Pin(String slug, int kind, boolean many) {
            this.slug = slug;
            this.kind = kind;
            this.many = many;
        }
    }

    public static final class Kind {
        public final int type;
        public final int category;
        /** Stable id used to build every string resource name for this kind. */
        public final String slug;
        public final Pin[] pins;
        /** What this node hands on; {@link #PIN_NONE} for the two output nodes. */
        public final int gives;
        public final Knob[] knobs;

        Kind(int type, int category, String slug, Pin[] pins, int gives, Knob[] knobs) {
            this.type = type;
            this.category = category;
            this.slug = slug;
            this.pins = pins;
            this.gives = gives;
            this.knobs = knobs;
        }

        public int knobs() {
            return knobs.length;
        }

        public int pins() {
            return pins.length;
        }

        public boolean many() {
            return pins.length > 0 && pins[pins.length - 1].many;
        }
    }

    @Nullable
    public static Kind of(int type) {
        for (int i = 0; i < KINDS.size(); i++) {
            if (KINDS.get(i).type == type) {
                return KINDS.get(i);
            }
        }
        return null;
    }

    public static List<Kind> all() {
        return Collections.unmodifiableList(KINDS);
    }

    public static List<Kind> inCategory(int category, List<Kind> out) {
        out.clear();
        for (int i = 0; i < KINDS.size(); i++) {
            if (KINDS.get(i).category == category) {
                out.add(KINDS.get(i));
            }
        }
        return out;
    }

    public static String slug(int type) {
        final Kind kind = of(type);
        return kind == null ? "" : kind.slug;
    }

    public static boolean givesLayer(int type) {
        final Kind kind = of(type);
        return kind != null && kind.gives == PIN_LAYER;
    }

    /** Takes a layer and hands back a changed one. */
    public static boolean editsLayer(int type) {
        final Kind kind = of(type);
        return kind != null && kind.gives == PIN_LAYER
                && kind.pins.length > 0 && kind.pins[0].kind == PIN_LAYER;
    }

    /** Turns a picture into a layer; these are the five that carry a mode. */
    public static boolean makesLayer(int type) {
        final Kind kind = of(type);
        return kind != null && kind.gives == PIN_LAYER
                && kind.pins.length > 0 && kind.pins[0].kind == PIN_IMAGE;
    }

    public static int roleOf(@Nullable Kind kind, int knob) {
        if (kind == null || knob < 0 || knob >= kind.knobs()) {
            return ROLE_PLAIN;
        }
        return kind.knobs[knob].role;
    }

    /** Whether a knob can be driven by a wire. The two number sources cannot drive themselves. */
    public static boolean numericKnob(@Nullable Kind kind, int knob) {
        if (kind == null || knob < 0 || knob >= kind.knobs() || kind.gives == PIN_NUMBER) {
            return false;
        }
        final int at = kind.knobs[knob].kind;
        return at == KNOB_RANGE || at == KNOB_CHOICE || at == KNOB_NUMBER;
    }

    /**
     * Whether a node of {@code from} may be plugged into pin {@code pin} of a node of {@code to}.
     *
     * <p>Layers and frames are interchangeable in both directions, which is what lets a bare layer be
     * plugged straight into the output without a stack in between.
     */
    public static boolean fits(int from, int to, int pin) {
        final Kind source = of(from);
        final Kind target = of(to);
        if (source == null || target == null || source.gives == PIN_NONE) {
            return false;
        }
        final int wanted = pinKind(target, pin);
        if (wanted == PIN_NONE) {
            return false;
        }
        if (wanted == source.gives) {
            return true;
        }
        return (wanted == PIN_FRAME || wanted == PIN_LAYER)
                && (source.gives == PIN_FRAME || source.gives == PIN_LAYER);
    }

    public static int pinKind(@Nullable Kind kind, int pin) {
        if (kind == null || pin < 0) {
            return PIN_NONE;
        }
        if (isKnobPin(pin)) {
            return numericKnob(kind, knobOfPin(pin)) ? PIN_NUMBER : PIN_NONE;
        }
        if (kind.pins.length == 0) {
            return PIN_NONE;
        }
        if (pin < kind.pins.length) {
            return kind.pins[pin].kind;
        }
        final Pin last = kind.pins[kind.pins.length - 1];
        return last.many ? last.kind : PIN_NONE;
    }

    public static String pinSlug(@Nullable Kind kind, int pin) {
        if (kind == null || pin < 0 || kind.pins.length == 0) {
            return "";
        }
        if (pin < kind.pins.length) {
            return kind.pins[pin].slug;
        }
        final Pin last = kind.pins[kind.pins.length - 1];
        return last.many ? last.slug : "";
    }

    // ---------------------------------------------------------------- the catalogue

    static {
        add(IMAGE, CAT_INPUT, "Image", pins(), PIN_IMAGE,
                knobs(knob("File", KNOB_IMAGE, 0, 0, 0)));
        add(VALUE, CAT_INPUT, "Value", pins(), PIN_NUMBER,
                knobs(knob("Number", KNOB_NUMBER, 0, 0, 0)));
        add(PANEL, CAT_INPUT, "Panel", pins(), PIN_NUMBER,
                knobs(knob("Across", KNOB_NUMBER, 0, 0, 0),
                        knob("Down", KNOB_NUMBER, 0, 0, 0)));
        add(COLOR, CAT_INPUT, "Colour", pins(), PIN_COLOR,
                knobs(knob("Colour", KNOB_COLOR, 0, 0, -1)));

        add(RIM, CAT_SHAPE, "Rim", pins(pin("Picture", PIN_IMAGE)), PIN_LAYER,
                knobs(knob("Width", KNOB_RANGE, 1, 80, 10),
                        knob("Offset", KNOB_RANGE, -20, 60, 0),
                        turning("Shift"),
                        turning("Spin"),
                        choice("Seamless", 2, 0)));
        add(PATTERN, CAT_SHAPE, "Pattern", pins(pin("Picture", PIN_IMAGE)), PIN_LAYER,
                knobs(knob("Size", KNOB_RANGE, 10, 400, 90),
                        knob("Count", KNOB_RANGE, 1, 64, 24),
                        knob("Offset", KNOB_RANGE, -20, 60, 3),
                        turning("Shift"),
                        turning("Spin")));
        add(MARK, CAT_SHAPE, "Mark", pins(pin("Picture", PIN_IMAGE)), PIN_LAYER,
                knobs(knob("Size", KNOB_RANGE, 10, 400, 100),
                        knob("Offset", KNOB_RANGE, -20, 60, 0),
                        turning("Place"),
                        turning("Spin")));
        add(STICKER, CAT_SHAPE, "Sticker", pins(pin("Picture", PIN_IMAGE)), PIN_LAYER,
                knobs(knob("Size", KNOB_RANGE, 10, 400, 100),
                        across("Across"),
                        down("Down"),
                        turning("Turn")));
        add(PARTICLES, CAT_SHAPE, "Particles", pins(pin("Picture", PIN_IMAGE)), PIN_LAYER,
                knobs(knob("Count", KNOB_RANGE, 1, 64, 14),
                        knob("Size", KNOB_RANGE, 10, 400, 80),
                        knob("Field", KNOB_RANGE, 0, 200, 40),
                        knob("Spread", KNOB_RANGE, 2, 400, 90),
                        knob("Speed", KNOB_RANGE, 10, 400, 100)));

        add(FLOW, CAT_PARTICLE, "Flow", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(choice("Way", 3, 0), turning("Angle")));
        add(SCATTER, CAT_PARTICLE, "Scatter", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(knob("Scatter", KNOB_RANGE, 0, 180, 30)));
        add(TURBULENCE, CAT_PARTICLE, "Turbulence", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(knob("Force", KNOB_RANGE, 0, 100, 35)));
        add(GRAVITY, CAT_PARTICLE, "Gravity", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(knob("Gravity", KNOB_RANGE, -100, 100, 40)));
        add(SPIN, CAT_PARTICLE, "Spin", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(turning("Turn"), turning("OverLife", 180)));
        add(TWINKLE, CAT_PARTICLE, "Twinkle", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(knob("Depth", KNOB_RANGE, 0, 100, 40)));
        add(JITTER, CAT_PARTICLE, "Jitter", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(knob("Jitter", KNOB_RANGE, 0, 100, 45)));

        add(TINT, CAT_COLOR, "Tint",
                pins(pin("Layer", PIN_LAYER), pin("Colour", PIN_COLOR)), PIN_LAYER,
                knobs(knob("Colour", KNOB_COLOR, 0, 0, -1)));
        add(FADE, CAT_COLOR, "Fade", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(knob("Visible", KNOB_RANGE, 0, 100, 100)));

        add(PLACE, CAT_TRANSFORM, "Place", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(turning("Shift"), knob("Offset", KNOB_RANGE, -20, 60, 0)));
        add(TURN, CAT_TRANSFORM, "Rotate", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(turning("Turn")));
        add(SIZE, CAT_TRANSFORM, "Resize", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(knob("Size", KNOB_RANGE, 10, 400, 100)));
        add(TRANSFORM, CAT_TRANSFORM, "Transform", pins(pin("Layer", PIN_LAYER)), PIN_LAYER,
                knobs(turning("Along"),
                        knob("Offset", KNOB_RANGE, -20, 60, 0),
                        across("Across"),
                        down("Down"),
                        turning("Turn"),
                        knob("Size", KNOB_RANGE, 10, 400, 100)));

        add(STACK, CAT_LAYOUT, "Stack", pins(pin("Layer", PIN_LAYER, true)), PIN_FRAME, knobs());

        add(FRAME, CAT_OUTPUT, "Frame", pins(pin("In", PIN_FRAME)), PIN_NONE, knobs());
        add(VIEW, CAT_OUTPUT, "View", pins(pin("In", PIN_FRAME)), PIN_NONE, knobs());
    }

    private static void add(int type, int category, String slug, Pin[] pins, int gives,
                            Knob[] knobs) {
        KINDS.add(new Kind(type, category, slug, pins, gives, knobs));
    }

    private static Pin[] pins(Pin... items) {
        return items;
    }

    private static Knob[] knobs(Knob... items) {
        return items;
    }

    private static Pin pin(String slug, int kind) {
        return new Pin(slug, kind, false);
    }

    private static Pin pin(String slug, int kind, boolean many) {
        return new Pin(slug, kind, many);
    }

    private static Knob knob(String slug, int kind, int min, int max, int start) {
        return new Knob(slug, kind, min, max, start, 0, ROLE_PLAIN);
    }

    private static Knob choice(String slug, int options, int start) {
        return new Knob(slug, KNOB_CHOICE, 0, options - 1, start, options, ROLE_PLAIN);
    }

    private static Knob steered(String slug, int start, int role) {
        return new Knob(slug, KNOB_NUMBER, 0, 0, start, 0, role);
    }

    private static Knob turning(String slug) {
        return steered(slug, 0, ROLE_TURN);
    }

    private static Knob turning(String slug, int start) {
        return steered(slug, start, ROLE_TURN);
    }

    private static Knob across(String slug) {
        return steered(slug, 0, ROLE_ACROSS);
    }

    private static Knob down(String slug) {
        return steered(slug, 0, ROLE_DOWN);
    }
}
