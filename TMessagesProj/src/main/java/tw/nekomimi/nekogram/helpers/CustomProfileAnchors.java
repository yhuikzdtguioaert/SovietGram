package tw.nekomimi.nekogram.helpers;

import android.view.View;
import android.view.ViewParent;

import androidx.annotation.Nullable;

/**
 * Where each part of the header ends up when one part is anchored to another.
 *
 * <p>A part names a target, a point on that target, a point on itself, and a nudge — "the name, its
 * left edge against the avatar's right edge, both centred vertically, 15dp clear". Resolving that is
 * not a single sum, because the target may itself be anchored to something else, so this walks the
 * chain and memoises. A loop — the name anchored to the status and the status to the name — is broken
 * by freezing whichever part is asked for second at the place it is currently drawn, so a mistake
 * looks odd rather than hanging the profile.
 *
 * <p>The measurements are of what is <em>drawn</em>, not of what was laid out: the profile scales and
 * translates its header constantly, so every position is walked up the view chain applying each
 * parent's translation, pivot and scale. Text views report the width of their text rather than of the
 * box holding it, so a name anchored to the right of an avatar sits against the letters instead of
 * against a full-width, mostly-empty view.
 */
public final class CustomProfileAnchors {

    /** The parts, in the order every array here is indexed by. */
    public static final int AVATAR = 0;
    public static final int NAME = 1;
    public static final int STATUS = 2;
    public static final int ACTIONS = 3;
    public static final int TITLE = 4;
    public static final int COUNT = 5;

    /** What a part can be anchored to. */
    public static final int TARGET_NONE = 0;
    public static final int TARGET_SCREEN = 1;
    public static final int TARGET_THOUGHT = 2;
    /** Targets from here on are the parts themselves, in the order above. */
    private static final int TARGET_FIRST_PART = 3;
    public static final int TARGET_LAST = 7;

    public static final int POINT_START = 0;
    public static final int POINT_CENTER = 1;
    public static final int POINT_END = 2;

    public static final int OFFSET_LIMIT = 600;

    /** How long the resolver keeps working before it settles, and what counts as settled. */
    public static final long SETTLE_MS = 700;
    public static final long SETTLE_MAX_MS = 6000;
    public static final int STABLE_RESOLVES = 4;
    public static final float STABLE_EPS = 0.5f;

    private static final int UNVISITED = 0;
    private static final int RESOLVING = 1;
    private static final int RESOLVED = 2;

    /** How far up the view chain a measurement walks before giving up. */
    private static final int MAX_DEPTH = 12;

    private CustomProfileAnchors() {
    }

    public static final String[] NAMES = {"avatar", "name", "status", "actions", "title"};

    public static String name(int part) {
        return part >= 0 && part < NAMES.length ? NAMES[part] : "";
    }

    /** The part a target names, or −1 for the screen, the thought, or nothing. */
    public static int partOf(int target) {
        final int part = target - TARGET_FIRST_PART;
        return (part < 0 || part >= COUNT) ? -1 : part;
    }

    public static int targetOf(int part) {
        return part + TARGET_FIRST_PART;
    }

    public static float fraction(int point) {
        if (point == POINT_START) {
            return 0f;
        }
        return point == POINT_END ? 1f : 0.5f;
    }

    /** Whether the resolver should stop: it has stopped moving, or it has had long enough. */
    public static boolean frozen(int stableResolves, long openedAt, long now) {
        final long age = now - openedAt;
        if (age >= SETTLE_MAX_MS) {
            return true;
        }
        return age >= SETTLE_MS && stableResolves >= STABLE_RESOLVES;
    }

    public static int nextStableCount(int count, float moved) {
        return moved <= STABLE_EPS ? count + 1 : 0;
    }

    /**
     * Drops targets that name a part this profile is not showing, so a look built for a profile with
     * a bio does not leave the status hanging off nothing.
     */
    public static int[] withoutMissing(@Nullable int[] targets, @Nullable boolean[] present) {
        if (targets == null) {
            return new int[0];
        }
        final int[] out = targets.clone();
        if (present == null) {
            return out;
        }
        for (int i = 0; i < out.length; i++) {
            if (i < present.length && !present[i]) {
                out[i] = TARGET_NONE;
            }
        }
        for (int i = 0; i < out.length; i++) {
            final int part = partOf(out[i]);
            if (part >= 0 && (part >= present.length || !present[part])) {
                out[i] = TARGET_NONE;
            }
        }
        return out;
    }

    /**
     * The resolved start of every part along one axis.
     *
     * @param starts      where each part is now, which is also the answer for an unanchored one.
     * @param sizes       how big each part is.
     * @param targets     what each part is anchored to.
     * @param fromPoints  which point of itself each part puts on the anchor.
     * @param toPoints    which point of its target each part anchors to.
     * @param offsets     the nudge, in pixels.
     * @param extraStarts the screen's and the thought's start, indexed by target − 1.
     * @param extraSizes  the same two sizes; a size of 0 means that target is not there at all.
     */
    public static float[] resolve(@Nullable float[] starts, @Nullable float[] sizes,
                                  @Nullable int[] targets, @Nullable float[] fromPoints,
                                  @Nullable float[] toPoints, @Nullable float[] offsets,
                                  @Nullable float[] extraStarts, @Nullable float[] extraSizes) {
        final int count = starts == null ? 0 : starts.length;
        if (count == 0 || sizes == null || targets == null || fromPoints == null
                || toPoints == null || offsets == null
                || sizes.length < count || targets.length < count || fromPoints.length < count
                || toPoints.length < count || offsets.length < count) {
            return starts == null ? new float[0] : starts.clone();
        }
        final float[] out = new float[count];
        final int[] marks = new int[count];
        for (int i = 0; i < count; i++) {
            resolveOne(i, starts, sizes, targets, fromPoints, toPoints, offsets,
                    extraStarts, extraSizes, out, marks);
        }
        return out;
    }

    private static float resolveOne(int part, float[] starts, float[] sizes, int[] targets,
                                    float[] fromPoints, float[] toPoints, float[] offsets,
                                    @Nullable float[] extraStarts, @Nullable float[] extraSizes,
                                    float[] out, int[] marks) {
        if (marks[part] == RESOLVED) {
            return out[part];
        }
        if (marks[part] == RESOLVING) {
            // A loop. This part stays where it is, which breaks the chain without hanging.
            marks[part] = RESOLVED;
            out[part] = starts[part];
            return out[part];
        }
        marks[part] = RESOLVING;
        final int target = targets[part];
        float result = starts[part];
        if (target != TARGET_NONE) {
            float anchor = Float.NaN;
            final int other = partOf(target);
            if (other >= 0 && other != part) {
                final float otherStart = resolveOne(other, starts, sizes, targets, fromPoints,
                        toPoints, offsets, extraStarts, extraSizes, out, marks);
                if (marks[part] == RESOLVED) {
                    // Resolving the target came back round to us and settled us on the way.
                    return out[part];
                }
                anchor = otherStart + toPoints[part] * sizes[other];
            } else if (other < 0) {
                final int extra = target - 1;
                if (extraStarts != null && extraSizes != null && extra >= 0
                        && extra < extraStarts.length && extra < extraSizes.length
                        && extraSizes[extra] > 0f) {
                    anchor = extraStarts[extra] + toPoints[part] * extraSizes[extra];
                }
            }
            if (!Float.isNaN(anchor)) {
                result = anchor + offsets[part] - fromPoints[part] * sizes[part];
            }
        }
        marks[part] = RESOLVED;
        out[part] = result;
        return result;
    }

    // ---------------------------------------------------------------- measuring what is drawn

    /** Where a view is drawn inside {@code root}, along one axis, parents' transforms included. */
    public static float drawnStart(@Nullable View root, @Nullable View view, boolean vertical) {
        float at = 0f;
        View walk = view;
        for (int i = 0; walk != null && walk != root && i < MAX_DEPTH; i++) {
            at = vertical
                    ? toParent(at, walk.getTop(), walk.getTranslationY(), walk.getPivotY(), walk.getScaleY())
                    : toParent(at, walk.getLeft(), walk.getTranslationX(), walk.getPivotX(), walk.getScaleX());
            walk = parentOf(walk);
        }
        return at;
    }

    /** One step of that walk: a child offset turned into the parent's own coordinates. */
    public static float toParent(float inChild, float edge, float translation,
                                 float pivot, float scale) {
        return edge + translation + pivot + (inChild - pivot) * scale;
    }

    public static float chainScale(@Nullable View root, @Nullable View view, boolean vertical) {
        float scale = 1f;
        View walk = view;
        for (int i = 0; walk != null && walk != root && i < MAX_DEPTH; i++) {
            scale *= vertical ? walk.getScaleY() : walk.getScaleX();
            walk = parentOf(walk);
        }
        return scale;
    }

    public static float drawnSize(@Nullable View root, @Nullable View view, boolean vertical) {
        if (view == null) {
            return 0f;
        }
        return (vertical ? view.getHeight() : view.getWidth()) * chainScale(root, view, vertical);
    }

    /** Where the text starts inside a wider box, given the view's gravity. */
    public static float textStart(float boxStart, float boxWidth, float textWidth, float align) {
        if (textWidth <= 0f || textWidth >= boxWidth) {
            return boxStart;
        }
        return boxStart + (boxWidth - textWidth) * Math.max(0f, Math.min(1f, align));
    }

    /** A gravity turned into where in the box its content sits: 0 start, 0.5 middle, 1 end. */
    public static float align(int gravity) {
        final int horizontal = gravity & 7;
        if (horizontal == android.view.Gravity.RIGHT) {
            return 1f;
        }
        return horizontal == android.view.Gravity.CENTER_HORIZONTAL ? 0.5f : 0f;
    }

    @Nullable
    private static View parentOf(View view) {
        final ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }
}
