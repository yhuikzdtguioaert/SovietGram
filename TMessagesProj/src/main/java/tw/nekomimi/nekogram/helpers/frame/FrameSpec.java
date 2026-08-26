package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A frame: up to eight layers, each a picture placed around the avatar.
 *
 * <p>The whole thing is authored in a fixed 256-unit space around an 84dp avatar, which is what lets
 * one frame fit every size the profile draws the avatar at — the space is mapped onto whatever box
 * the avatar occupies and every measurement inside it scales with it.
 *
 * <p>Immutable, and built only through {@link Builder}, so every field is already inside the bounds
 * the reference's own editor enforces. That matters because a spec arrives over the network from
 * another user: a layer asking for 4000 copies at 30× scale is clamped on the way in rather than
 * being caught at draw time.
 *
 * <p><b>At most one layer may animate.</b> A second layer asking is kept but drawn still — decoding
 * two videos to paint one avatar is not worth it, and it is the reference's own rule.
 */
public final class FrameSpec {

    public static final int MODE_STRIP = 0;
    public static final int MODE_STAMP = 1;
    public static final int MODE_MARK = 2;
    public static final int MODE_STICKER = 3;
    public static final int MODE_PARTICLES = 4;
    static final int MODE_MAX = 4;

    public static final int FLOW_COURSE = 0;
    public static final int FLOW_OUT = 1;
    public static final int FLOW_IN = 2;
    static final int FLOW_MAX = 2;

    public static final int COLOR_UNSET = 0;
    public static final int MAX_LAYERS = 8;

    public static final int WIDTH_MIN = 1;
    public static final int WIDTH_MAX = 200;
    public static final int OFFSET_LIMIT = 200;
    public static final int SCALE_MIN = 10;
    public static final int SCALE_MAX = 2000;
    public static final int SCALE_KNOB_MAX = 400;
    public static final int REPEAT_MIN = 1;
    public static final int REPEAT_MAX = 64;
    public static final int SPIN_LIMIT = 180;
    public static final int ORBIT_LIMIT = 360;
    public static final int TWIST_LIMIT = 720;
    public static final int SPREAD_MIN = 2;
    public static final int SPREAD_MAX = 400;
    public static final int SPEED_MIN = 10;
    public static final int SPEED_MAX = 400;
    public static final int FIELD_LIMIT = 200;
    public static final int SCATTER_LIMIT = 180;
    public static final int PART_LIMIT = 100;
    public static final int GRAVITY_LIMIT = 100;
    public static final float PLACE_MIN = -0.5f;
    public static final float PLACE_MAX = 1.5f;

    public static final FrameSpec EMPTY = new FrameSpec(Collections.emptyList());

    private final List<Layer> layers;

    private FrameSpec(List<Layer> layers) {
        this.layers = layers;
    }

    public List<Layer> layers() {
        return layers;
    }

    public boolean isEmpty() {
        return layers.isEmpty();
    }

    /** Every picture this frame needs, so a caller can fetch them before drawing. */
    public Set<String> assets() {
        final LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; i < layers.size(); i++) {
            final Layer layer = layers.get(i);
            out.add(layer.src);
            if (layer.corner.length() > 0) {
                out.add(layer.corner);
            }
        }
        return Collections.unmodifiableSet(out);
    }

    /**
     * Reads a spec. Anything unparsable is no frame rather than a broken one — this runs on JSON
     * that arrived from another user.
     */
    public static FrameSpec parse(@Nullable String json) {
        if (json == null || json.trim().length() == 0) {
            return EMPTY;
        }
        final List<Layer> out = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(json);
            boolean animatedTaken = false;
            for (int i = 0; i < array.length() && out.size() < MAX_LAYERS; i++) {
                final JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                Layer layer = read(item);
                if (layer == null) {
                    continue;
                }
                if (layer.animated) {
                    if (animatedTaken) {
                        layer = new Builder(layer).animated(false).build();
                    } else {
                        animatedTaken = true;
                    }
                }
                out.add(layer);
            }
        } catch (Throwable ignore) {
            return EMPTY;
        }
        return out.isEmpty() ? EMPTY : new FrameSpec(Collections.unmodifiableList(out));
    }

    public String encode() {
        final JSONArray array = new JSONArray();
        for (int i = 0; i < layers.size() && array.length() < MAX_LAYERS; i++) {
            try {
                array.put(layers.get(i).write());
            } catch (Throwable ignore) {
            }
        }
        return array.toString();
    }

    /** A spec from layers built by hand, with the same ceiling and the same one-animation rule. */
    public static FrameSpec of(@Nullable List<Layer> list) {
        if (list == null || list.isEmpty()) {
            return EMPTY;
        }
        final List<Layer> out = new ArrayList<>();
        boolean animatedTaken = false;
        for (int i = 0; i < list.size() && out.size() < MAX_LAYERS; i++) {
            Layer layer = list.get(i);
            if (layer == null) {
                continue;
            }
            if (layer.animated) {
                if (animatedTaken) {
                    layer = new Builder(layer).animated(false).build();
                } else {
                    animatedTaken = true;
                }
            }
            out.add(layer);
        }
        return out.isEmpty() ? EMPTY : new FrameSpec(Collections.unmodifiableList(out));
    }

    /** Reads and rewrites a spec, which is what puts a third-party one inside our own bounds. */
    public static String sanitize(@Nullable String json) {
        return parse(json).encode();
    }

    /**
     * A spec with only the layers whose pictures anybody can fetch. Used as the picture picker's own
     * validity check: a path inside this phone is not a picture another user could ever load.
     */
    public static boolean shareable(@Nullable String src) {
        if (src == null) {
            return false;
        }
        if (FrameBlanks.is(src)) {
            return true;
        }
        final String lower = src.trim().toLowerCase(Locale.US);
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /** Replaces picture addresses wholesale, which is how a look's assets are rehosted. */
    public static FrameSpec swap(@Nullable FrameSpec spec, @Nullable Map<String, String> swaps) {
        if (spec == null) {
            return EMPTY;
        }
        if (spec.isEmpty() || swaps == null || swaps.isEmpty()) {
            return spec;
        }
        final List<Layer> out = new ArrayList<>();
        for (int i = 0; i < spec.layers.size(); i++) {
            Layer layer = spec.layers.get(i);
            final String src = pick(swaps, layer.src);
            final String corner = pick(swaps, layer.corner);
            if (!src.equals(layer.src) || !corner.equals(layer.corner)) {
                layer = new Builder(layer).src(src).corner(corner).build();
            }
            out.add(layer);
        }
        return of(out);
    }

    private static String pick(Map<String, String> swaps, String key) {
        final String found = swaps.get(key);
        return (found == null || found.length() == 0) ? key : found;
    }

    /**
     * A number that changes whenever anything drawn changes. Two specs with the same signature draw
     * the same frame, which is what lets the studio tell "the graph still matches the spec" from
     * "somebody installed a different frame underneath me".
     */
    public long signature() {
        long hash = 17;
        for (int i = 0; i < layers.size(); i++) {
            final Layer l = layers.get(i);
            hash = hash * 31 + l.src.hashCode();
            hash = hash * 31 + l.corner.hashCode();
            hash = hash * 31 + l.mode;
            hash = hash * 31 + Math.round(l.at * 10000f);
            hash = hash * 31 + l.offset;
            hash = hash * 31 + l.width;
            hash = hash * 31 + l.scale;
            hash = hash * 31 + l.repeat;
            hash = hash * 31 + l.spin;
            hash = hash * 31 + l.tint;
            hash = hash * 31 + (l.animated ? 1L : 0L);
            hash = hash * 31 + (l.round ? 1L : 0L);
            hash = hash * 31 + (l.seamless ? 1L : 0L);
            hash = hash * 31 + Math.round(l.x * 10000f);
            hash = hash * 31 + Math.round(l.y * 10000f);
            hash = hash * 31 + l.turn;
            hash = hash * 31 + l.spread;
            hash = hash * 31 + l.speed;
            hash = hash * 31 + l.course;
            hash = hash * 31 + l.field;
            hash = hash * 31 + l.flow;
            hash = hash * 31 + l.scatter;
            hash = hash * 31 + l.swirl;
            hash = hash * 31 + l.chaos;
            hash = hash * 31 + l.gravity;
            hash = hash * 31 + l.twist;
            hash = hash * 31 + l.twinkle;
            hash = hash * 31 + l.orbit;
            hash = hash * 31 + (l.off ? 1L : 0L);
        }
        return hash;
    }

    @Nullable
    private static Layer read(JSONObject o) {
        final String src = o.optString("src", "").trim();
        if (src.length() == 0) {
            return null;
        }
        final int mode = o.optInt("mode", 0);
        if (mode < 0 || mode > MODE_MAX) {
            return null;
        }
        return new Builder()
                .src(src)
                .mode(mode)
                .corner(o.optString("corner", ""))
                .at((float) o.optDouble("at", 0))
                .offset(o.optInt("offset", 0))
                .width(o.optInt("width", 24))
                .scale(o.optInt("scale", 100))
                .repeat(o.optInt("repeat", 12))
                .spin(o.optInt("spin", 0))
                .tint(color(o.optString("tint", ""), COLOR_UNSET))
                .animated(o.optBoolean("animated", false))
                .round(o.optBoolean("round", false))
                .seamless(o.optBoolean("seamless", false))
                .x((float) o.optDouble("x", 0.5))
                .y((float) o.optDouble("y", 0.5))
                .turn(o.optInt("turn", 0))
                .spread(o.optInt("spread", 90))
                .speed(o.optInt("speed", 100))
                .course(o.optInt("course", 0))
                .field(o.optInt("field", 40))
                .flow(o.optInt("flow", 0))
                .scatter(o.optInt("scatter", 0))
                .swirl(o.optInt("swirl", 0))
                .chaos(o.optInt("chaos", 0))
                .gravity(o.optInt("gravity", 0))
                .twist(o.optInt("twist", 0))
                .twinkle(o.optInt("twinkle", 0))
                .orbit(o.optInt("orbit", 0))
                .off(o.optBoolean("off", false))
                .build();
    }

    /** {@code #AARRGGBB}, or the same without the hash, which is how a tint is written. */
    public static int color(@Nullable String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        String text = value.trim();
        if (text.length() == 0) {
            return fallback;
        }
        if (text.charAt(0) != '#') {
            text = "#" + text;
        }
        try {
            return android.graphics.Color.parseColor(text);
        } catch (Throwable ignore) {
            return fallback;
        }
    }

    public static String hex(int color) {
        return String.format(Locale.US, "#%08X", color);
    }

    static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    static float clampF(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    /** One layer. Every field is already inside its bounds; see {@link Builder}. */
    public static final class Layer {
        public final String src;
        public final String corner;
        public final int mode;
        public final float at;
        public final int offset;
        public final int width;
        public final int scale;
        public final int repeat;
        public final int spin;
        public final int tint;
        public final boolean animated;
        public final boolean round;
        public final boolean seamless;
        public final float x;
        public final float y;
        public final int turn;
        public final int spread;
        public final int speed;
        public final int course;
        public final int field;
        public final int flow;
        public final int scatter;
        public final int swirl;
        public final int chaos;
        public final int gravity;
        public final int twist;
        public final int twinkle;
        public final int orbit;
        public final boolean off;

        Layer(String src, String corner, int mode, float at, int offset, int width, int scale,
              int repeat, int spin, int tint, boolean animated, boolean round, boolean seamless,
              float x, float y, int turn, int spread, int speed, int course, int field, int flow,
              int scatter, int swirl, int chaos, int gravity, int twist, int twinkle, int orbit,
              boolean off) {
            this.src = src;
            this.corner = corner;
            this.mode = mode;
            this.at = at;
            this.offset = offset;
            this.width = width;
            this.scale = scale;
            this.repeat = repeat;
            this.spin = spin;
            this.tint = tint;
            this.animated = animated;
            this.round = round;
            this.seamless = seamless;
            this.x = x;
            this.y = y;
            this.turn = turn;
            this.spread = spread;
            this.speed = speed;
            this.course = course;
            this.field = field;
            this.flow = flow;
            this.scatter = scatter;
            this.swirl = swirl;
            this.chaos = chaos;
            this.gravity = gravity;
            this.twist = twist;
            this.twinkle = twinkle;
            this.orbit = orbit;
            this.off = off;
        }

        /**
         * Only the keys this mode uses, and only when they differ from the default — which is what
         * keeps a published spec short and, more usefully, keeps two identical frames byte-identical.
         */
        JSONObject write() throws Exception {
            final JSONObject o = new JSONObject();
            o.put("src", src);
            if (mode != 0) {
                o.put("mode", mode);
            }
            if (corner.length() > 0) {
                o.put("corner", corner);
            }
            if (at != 0f) {
                o.put("at", at);
            }
            if (offset != 0) {
                o.put("offset", offset);
            }
            o.put("width", width);
            if (mode != 0) {
                o.put("scale", scale);
            }
            if (mode == MODE_STAMP) {
                o.put("repeat", repeat);
            }
            if (orbit != 0) {
                o.put("orbit", orbit);
            }
            if (spin != 0) {
                o.put("spin", spin);
            }
            if (tint != 0) {
                o.put("tint", hex(tint));
            }
            if (animated) {
                o.put("animated", true);
            }
            if (round) {
                o.put("round", true);
            }
            if (seamless) {
                o.put("seamless", true);
            }
            if (mode == MODE_STICKER) {
                o.put("x", x);
                o.put("y", y);
                if (turn != 0) {
                    o.put("turn", turn);
                }
            }
            if (mode == MODE_PARTICLES) {
                o.put("repeat", repeat);
                o.put("spread", spread);
                o.put("speed", speed);
                o.put("field", field);
                if (course != 0) {
                    o.put("course", course);
                }
                if (turn != 0) {
                    o.put("turn", turn);
                }
                o.put("flow", flow);
                o.put("scatter", scatter);
                o.put("swirl", swirl);
                o.put("chaos", chaos);
                o.put("gravity", gravity);
                o.put("twist", twist);
                o.put("twinkle", twinkle);
            }
            if (off) {
                o.put("off", true);
            }
            return o;
        }
    }

    /** The only way to make a layer, and the only place its bounds live. */
    public static final class Builder {
        private String src = "";
        private String corner = "";
        private int mode = 0;
        private float at;
        private int offset;
        private int width = 24;
        private int scale = 100;
        private int repeat = 12;
        private int spin;
        private int tint = COLOR_UNSET;
        private boolean animated;
        private boolean round;
        private boolean seamless;
        private float x = 0.5f;
        private float y = 0.5f;
        private int turn;
        private int spread = 90;
        private int speed = 100;
        private int course;
        private int field = 40;
        private int flow = 0;
        private int scatter;
        private int swirl;
        private int chaos;
        private int gravity;
        private int twist;
        private int twinkle;
        private int orbit;
        private boolean off;

        public Builder() {
        }

        public Builder(@Nullable Layer layer) {
            if (layer == null) {
                return;
            }
            src = layer.src;
            corner = layer.corner;
            mode = layer.mode;
            at = layer.at;
            offset = layer.offset;
            width = layer.width;
            scale = layer.scale;
            repeat = layer.repeat;
            spin = layer.spin;
            tint = layer.tint;
            animated = layer.animated;
            round = layer.round;
            seamless = layer.seamless;
            x = layer.x;
            y = layer.y;
            turn = layer.turn;
            spread = layer.spread;
            speed = layer.speed;
            course = layer.course;
            field = layer.field;
            flow = layer.flow;
            scatter = layer.scatter;
            swirl = layer.swirl;
            chaos = layer.chaos;
            gravity = layer.gravity;
            twist = layer.twist;
            twinkle = layer.twinkle;
            orbit = layer.orbit;
            off = layer.off;
        }

        public Builder src(@Nullable String value) {
            src = value == null ? "" : value.trim();
            return this;
        }

        public Builder corner(@Nullable String value) {
            corner = value == null ? "" : value.trim();
            return this;
        }

        public Builder mode(int value) {
            mode = (value < 0 || value > MODE_MAX) ? 0 : value;
            return this;
        }

        public Builder at(float value) {
            at = FrameContour.wrap(value);
            return this;
        }

        public Builder offset(int value) {
            offset = clamp(value, -OFFSET_LIMIT, OFFSET_LIMIT);
            return this;
        }

        public Builder width(int value) {
            width = clamp(value, WIDTH_MIN, WIDTH_MAX);
            return this;
        }

        public Builder scale(int value) {
            scale = clamp(value, SCALE_MIN, SCALE_MAX);
            return this;
        }

        public Builder repeat(int value) {
            repeat = clamp(value, REPEAT_MIN, REPEAT_MAX);
            return this;
        }

        public Builder spin(int value) {
            spin = clamp(value, -SPIN_LIMIT, SPIN_LIMIT);
            return this;
        }

        public Builder tint(int value) {
            tint = value;
            return this;
        }

        public Builder animated(boolean value) {
            animated = value;
            return this;
        }

        public Builder round(boolean value) {
            round = value;
            return this;
        }

        public Builder seamless(boolean value) {
            seamless = value;
            return this;
        }

        public Builder x(float value) {
            x = place(value);
            return this;
        }

        public Builder y(float value) {
            y = place(value);
            return this;
        }

        public Builder turn(int value) {
            turn = value % TWIST_LIMIT;
            return this;
        }

        public Builder spread(int value) {
            spread = clamp(value, SPREAD_MIN, SPREAD_MAX);
            return this;
        }

        public Builder speed(int value) {
            speed = clamp(value, SPEED_MIN, SPEED_MAX);
            return this;
        }

        public Builder course(int value) {
            int turned = value % ORBIT_LIMIT;
            if (turned < 0) {
                turned += ORBIT_LIMIT;
            }
            course = turned;
            return this;
        }

        public Builder field(int value) {
            field = clamp(value, 0, FIELD_LIMIT);
            return this;
        }

        public Builder flow(int value) {
            flow = (value < 0 || value > FLOW_MAX) ? 0 : value;
            return this;
        }

        public Builder scatter(int value) {
            scatter = clamp(value, 0, SCATTER_LIMIT);
            return this;
        }

        public Builder swirl(int value) {
            swirl = clamp(value, 0, PART_LIMIT);
            return this;
        }

        public Builder chaos(int value) {
            chaos = clamp(value, 0, PART_LIMIT);
            return this;
        }

        public Builder gravity(int value) {
            gravity = clamp(value, -GRAVITY_LIMIT, GRAVITY_LIMIT);
            return this;
        }

        public Builder twist(int value) {
            twist = clamp(value, -TWIST_LIMIT, TWIST_LIMIT);
            return this;
        }

        public Builder twinkle(int value) {
            twinkle = clamp(value, 0, PART_LIMIT);
            return this;
        }

        public Builder orbit(int value) {
            orbit = value % TWIST_LIMIT;
            return this;
        }

        public Builder off(boolean value) {
            off = value;
            return this;
        }

        private static float place(float value) {
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                return 0.5f;
            }
            return clampF(value, PLACE_MIN, PLACE_MAX);
        }

        public Layer build() {
            return new Layer(src, corner, mode, at, offset, width, scale, repeat, spin, tint,
                    animated, round, seamless, x, y, turn, spread, speed, course, field, flow,
                    scatter, swirl, chaos, gravity, twist, twinkle, orbit, off);
        }
    }
}
