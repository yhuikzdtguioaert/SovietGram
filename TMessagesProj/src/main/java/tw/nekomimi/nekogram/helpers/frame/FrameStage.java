package tw.nekomimi.nekogram.helpers.frame;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

/**
 * How big to draw the avatar when a frame is being shown on its own.
 *
 * <p>On a profile the avatar's size is fixed and the frame reaches out from it, off the edge of the
 * header if it has to. In a preview there is nothing to reach into, so the arithmetic runs the other
 * way: work out how far the widest layer sticks out, and shrink the avatar until the whole frame fits
 * the box. That is what stops a wide particle cloud from being clipped into a square.
 */
public final class FrameStage {

    /** How much clear space to leave around the whole thing. */
    private static final float MARGIN = 0.2f;
    /** The avatar never takes more of the box than this, nor less. */
    private static final float PART_MAX = 0.62f;
    private static final float PART_MIN = 0.24f;

    public static final float AVATAR_DP = FramePainter.AVATAR_DP;

    private FrameStage() {
    }

    public static float avatarSide() {
        return AndroidUtilities.dpf2(AVATAR_DP);
    }

    /**
     * The avatar's side inside a square of {@code side}.
     *
     * @param avatarSide what the frame's measurements are written against, from {@link #avatarSide()}.
     */
    public static float box(float side, @Nullable FrameSpec spec, float avatarSide) {
        if (spec == null) {
            return side * PART_MAX;
        }
        float reach = 0f;
        for (int i = 0; i < spec.layers().size(); i++) {
            final FrameSpec.Layer layer = spec.layers().get(i);
            final float size = layer.width * layer.scale / 100f;
            final float out;
            if (layer.mode == FrameSpec.MODE_STRIP) {
                out = layer.offset + layer.width;
            } else if (layer.mode == FrameSpec.MODE_PARTICLES) {
                // A cloud reaches its field plus however far a particle travels, and gravity pulls
                // the far ones further still.
                final float travel = layer.spread * (layer.chaos / 100f + 1f);
                out = layer.field + travel + Math.abs(layer.gravity) / 100f * travel + size / 2f;
            } else if (layer.mode == FrameSpec.MODE_STICKER) {
                // A sticker is placed rather than offset, so how far outside the avatar it hangs is
                // its own doing and is measured in the frame's units rather than in dp.
                reach = Math.max(reach, Math.max(0f, Math.max(Math.abs(layer.x - 0.5f),
                        Math.abs(layer.y - 0.5f)) - 0.5f) * avatarSide);
                out = size / 2f;
            } else {
                out = layer.offset + size;
            }
            reach = Math.max(reach, AndroidUtilities.dpf2(Math.max(0f, out)));
        }
        return Math.max(PART_MIN * side,
                Math.min(side * PART_MAX, side / (reach * 2f / avatarSide + 1f + MARGIN)));
    }
}
