package tw.nekomimi.nekogram.helpers.frame;

/**
 * Where every particle of a drifting cloud is, right now.
 *
 * <p>Nothing is kept between frames. A particle's whole life — where it started, which way it is
 * going, how old it is, how big, how bright, how far it has spun — is computed from a hash of its
 * index and the clock. That is what makes the same frame look the same on two phones at the same
 * moment, which matters here because a frame travels between users and both of them are looking at
 * the same profile.
 *
 * <p>Placements come back packed five floats at a time: x, y, turn, opacity, size.
 */
public final class FrameParticles {

    public static final int STRIDE = 5;
    /** Seconds a particle lives at speed 100. */
    public static final float LIFE = 2.6f;

    private static final float CHAOS_LIFE = 0.6f;
    private static final float CHAOS_SIZE = 0.5f;
    private static final float CHAOS_WAY = 0.7f;
    private static final float FADE_OUT = 0.34f;
    private static final float GOLDEN_TIME = 0.618034f;
    private static final float GRAVITY_REACH = 1.2f;
    private static final float GROWTH = 0.35f;
    private static final float LIGHT_UP = 0.18f;
    private static final float PLASTIC_X = 0.7548777f;
    private static final float PLASTIC_Y = 0.5698403f;
    private static final float SIZE_MIN = 0.05f;
    private static final float SWIRL_REACH = 0.5f;
    private static final float SWIRL_TURNS = 1.2f;
    private static final float TWINKLE_TIMES = 3f;

    private FrameParticles() {
    }

    /**
     * @param seconds the clock, folded into an hour so it never loses precision.
     * @param field   the box particles are born in, in the frame's own units.
     * @param spread  how far one travels in a life.
     */
    public static int place(FrameSpec.Layer layer, float seconds, float field,
                            float centreX, float centreY, float spread, float[] out) {
        if (layer == null || layer.repeat <= 0 || field <= 0f
                || out == null || out.length < layer.repeat * STRIDE) {
            return 0;
        }
        final int count = layer.repeat;
        final float life = life(layer.speed);
        final float half = field / 2f;
        final float chaos = part(layer.chaos);
        final float swirl = part(layer.swirl) * spread * SWIRL_REACH;
        final float gravity = layer.gravity / 100f * spread * GRAVITY_REACH;
        final float twinkle = part(layer.twinkle);
        for (int i = 0; i < count; i++) {
            final float index = i;
            // Where in its own life this particle is. The offset makes the cloud start staggered
            // rather than every particle being born at once.
            final float age = frac(seconds / Math.max(SIZE_MIN,
                    (CHAOS_LIFE * chaos * spread(i, 1) + 1f) * life)
                    + frac(GOLDEN_TIME * index + 0.5f));
            final float startX = (centreX - half) + frac(PLASTIC_X * index + 0.5f) * field;
            final float startY = (centreY - half) + frac(index * PLASTIC_Y + 0.5f) * field;
            final float fromX = startX - centreX;
            final float fromY = startY - centreY;
            final float distance = (float) Math.sqrt(fromX * fromX + fromY * fromY);
            final float dirX;
            final float dirY;
            if (layer.flow == FrameSpec.FLOW_COURSE || distance <= 1e-4f) {
                final double course = Math.toRadians(layer.course + layer.scatter * spread(i, 2));
                dirX = (float) Math.sin(course);
                dirY = (float) -Math.cos(course);
            } else {
                // Outwards from the avatar, or inwards, then fanned by the scatter around that.
                final float sign = layer.flow == FrameSpec.FLOW_IN ? -1f : 1f;
                final double course = Math.toRadians(layer.course + layer.scatter * spread(i, 2));
                final float cos = (float) Math.cos(course);
                final float sin = (float) Math.sin(course);
                final float ax = fromX / distance * sign;
                final float ay = fromY / distance * sign;
                dirX = ax * cos - ay * sin;
                dirY = ax * sin + ay * cos;
            }
            final float way = (CHAOS_WAY * chaos * spread(i, 3) + 1f) * spread;
            float x = startX + dirX * way * age;
            float y = startY + way * dirY * age;
            if (swirl > 0f) {
                final float wave = swirl * age * (float) Math.sin(
                        ((random(i, 4) * 2f + SWIRL_TURNS) * age + random(i, 5))
                                * 2 * Math.PI);
                x += -dirY * wave;
                y += dirX * wave;
            }
            final int at = i * STRIDE;
            out[at] = x;
            out[at + 1] = y + gravity * age * age;
            out[at + 2] = layer.turn + layer.twist * age * sign(i, 6);
            out[at + 3] = fade(age) * twinkle(twinkle, age, random(i, 7));
            out[at + 4] = Math.max(SIZE_MIN,
                    (age * GROWTH + 0.65f) * (chaos * CHAOS_SIZE * spread(i, 8) + 1f));
        }
        return count;
    }

    public static float life(int speed) {
        return 260f / Math.max(1, speed);
    }

    /** Fades in over the first sixth of a life and out over the last third. */
    public static float fade(float age) {
        final float clamped = FrameSpec.clampF(age, 0f, 1f);
        return FrameSpec.clampF(Math.min(clamped / LIGHT_UP, (1f - clamped) / FADE_OUT), 0f, 1f);
    }

    public static float twinkle(float strength, float age, float phase) {
        if (strength <= 0f) {
            return 1f;
        }
        final float wave = (float) Math.cos((age * TWINKLE_TIMES + phase) * 2 * Math.PI) * 0.5f + 0.5f;
        return 1f - FrameSpec.clampF(strength, 0f, 1f) * 0.75f * (1f - wave);
    }

    /** Their own hash: an index and a channel in, a number between 0 and 1 out. */
    public static float random(int index, int channel) {
        int h = index * 374761393 + channel * 668265263;
        h = (h ^ (h >>> 13)) * 1274126177;
        return ((h ^ (h >>> 16)) >>> 8) / 16777216f;
    }

    public static float spread(int index, int channel) {
        return random(index, channel) * 2f - 1f;
    }

    private static float sign(int index, int channel) {
        return random(index, channel) < 0.5f ? -1f : 1f;
    }

    private static float part(int value) {
        return FrameSpec.clampF(value / 100f, 0f, 1f);
    }

    private static float frac(float value) {
        final float part = value % 1f;
        return part < 0f ? part + 1f : part;
    }
}
