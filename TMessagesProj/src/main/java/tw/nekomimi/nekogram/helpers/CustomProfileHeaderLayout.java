package tw.nekomimi.nekogram.helpers;

import android.graphics.RectF;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * The look's header layout: where the avatar, the name, the status and the buttons sit, how far each
 * is turned, and how big each is drawn.
 *
 * <p>Two things are stacked here, and they are described separately because they answer different
 * questions. An <b>element</b> is a plain offset from wherever the profile put a part: shift it right
 * a tenth of the width, turn it five degrees, draw it 120% the size. An <b>anchor</b> is a relation:
 * put this part's left edge against that part's right edge, centred, 15dp clear — which needs the
 * other part measured first and so has to be solved rather than added up. See
 * {@link CustomProfileAnchors} for the solving.
 *
 * <p>Three presets: 0 leaves the profile as Telegram draws it, 1 is the left-hand layout with the
 * name beside the avatar, and 4 is whatever the author set by hand.
 *
 * <p><b>Nothing is ever laid out differently.</b> Every part is moved by translating, rotating and
 * scaling it, and the profile re-applies its own positions on every pass — so what this adds is a
 * delta on top of whatever the header just decided. That is what makes it safe to stop: setting the
 * look aside puts every view back exactly where the profile itself wants it.
 *
 * <p>Which is also the subtle part. The profile animates those same properties, so this cannot simply
 * remember what the view held before: by the next pass the header may have moved it legitimately. So
 * each part remembers what it last <em>wrote</em>, and if the value it finds still matches that, the
 * base is still ours; if it does not, the header changed it and the new value becomes the base. See
 * {@link Transform}.
 *
 * <p>All of it fades out as the avatar is pulled open into the full-screen viewer, over the last 60%
 * of the pull — the layout belongs to the collapsed header and there is nothing left of it to lay out
 * once the picture fills the screen.
 */
public final class CustomProfileHeaderLayout {

    public static final int PRESET_TELEGRAM = 0;
    public static final int PRESET_LEFT = 1;
    public static final int PRESET_CUSTOM = 4;

    /** How far a part may be shifted, as a share of the root's width. */
    static final float SHIFT_LIMIT = 0.45f;
    static final float ROTATE_LIMIT = 180f;
    static final float SCALE_MIN = 0.1f;
    static final float SCALE_MAX = 3f;
    /** The avatar is held tighter than the rest: further and it leaves the header entirely. */
    private static final float AVATAR_BOUND = 0.36f;

    /** Below this much pull the header is still a header; above it the anchors stop resolving. */
    private static final float PULL_FREEZE = 0.02f;
    /** The layout is at full strength up to this much pull, and gone by the end of it. */
    private static final float PULL_HOLD = 0.4f;

    /** Where the name sits relative to its anchor when its view is wider than its text. */
    public static final int NAME_CENTER = 0;
    public static final int NAME_LEFT = 1;
    public static final int NAME_RIGHT = 2;

    public static final int CONTENT_SCALE_MIN = 10;
    public static final int CONTENT_SCALE_MAX = 300;
    public static final int CONTENT_SCALE_DEFAULT = 100;

    private static final String[] PARTS = CustomProfileAnchors.NAMES;
    private static final String[] ANCHOR_SUFFIXES =
            {"_to", "_to_x", "_to_y", "_from_x", "_from_y", "_x", "_y"};
    private static final int ANCHOR_PARTS = 7;
    private static final int TARGET = 0;
    private static final int TO_X = 1;
    private static final int TO_Y = 2;
    private static final int FROM_X = 3;
    private static final int FROM_Y = 4;
    private static final int OFFSET_X = 5;
    private static final int OFFSET_Y = 6;

    // ---------------------------------------------------------------- the parsed look

    /** One part's own offset, turn and size. Always inside the reference's own bounds. */
    public static final class Element {
        public static final Element NONE = new Element(0f, 0f, 0f, 1f, 1f);

        public final float x;
        public final float y;
        public final float rotate;
        public final float scaleX;
        public final float scaleY;

        Element(float x, float y, float rotate, float scaleX, float scaleY) {
            this.x = clampF(x, -SHIFT_LIMIT, SHIFT_LIMIT);
            this.y = clampF(y, -SHIFT_LIMIT, SHIFT_LIMIT);
            this.rotate = clampF(rotate, -ROTATE_LIMIT, ROTATE_LIMIT);
            this.scaleX = clampF(scaleX, SCALE_MIN, SCALE_MAX);
            this.scaleY = clampF(scaleY, SCALE_MIN, SCALE_MAX);
        }

        public boolean isIdle() {
            return x == 0f && y == 0f && rotate == 0f && scaleX == 1f && scaleY == 1f;
        }
    }

    /** The other keys a layout carries beside the five elements and the anchors. */
    public static final class Extras {
        public static final Extras NONE = new Extras(NAME_CENTER, false, false,
                CONTENT_SCALE_DEFAULT, CONTENT_SCALE_DEFAULT);

        public final int nameAnchor;
        /** Keep resolving anchors instead of settling once they stop moving. A debugging aid. */
        public final boolean anchorAlways;
        /** Undo the header's own scaling of the action buttons' contents. */
        public final boolean plainContent;
        public final int contentScaleX;
        public final int contentScaleY;

        Extras(int nameAnchor, boolean anchorAlways, boolean plainContent,
               int contentScaleX, int contentScaleY) {
            this.nameAnchor = nameAnchor;
            this.anchorAlways = anchorAlways;
            this.plainContent = plainContent;
            this.contentScaleX = contentScaleX;
            this.contentScaleY = contentScaleY;
        }
    }

    private static String parsedFrom = "";
    private static int parsedPreset = Integer.MIN_VALUE;
    private static Element[] elements = idleElements();
    private static int[] anchors = new int[CustomProfileAnchors.COUNT * ANCHOR_PARTS];
    private static boolean anchored;
    private static Extras extras = Extras.NONE;

    // ---------------------------------------------------------------- the live state

    private static final Transform[] transforms = {
            new Transform(), new Transform(), new Transform(), new Transform(), new Transform(),
    };
    /** What was last written to each action button's content, and what it was before. */
    private static final java.util.WeakHashMap<View, float[]> contentScales =
            new java.util.WeakHashMap<>();
    private static final float[] shiftX = new float[CustomProfileAnchors.COUNT];
    private static final float[] shiftY = new float[CustomProfileAnchors.COUNT];

    private static boolean anchorsReady;
    private static long anchorSignature = Long.MIN_VALUE;
    private static int stableResolves;
    private static long geometryLast;
    private static long geometrySettled;
    private static int geometryStableFrames;
    private static long openedAt = SystemClock.uptimeMillis();

    private CustomProfileHeaderLayout() {
    }

    /** Drops the parsed layout and starts the anchor solver over; the look under it has changed. */
    public static void invalidate() {
        parsedFrom = "";
        parsedPreset = Integer.MIN_VALUE;
        reopen();
    }

    private static void reopen() {
        anchorsReady = false;
        anchorSignature = Long.MIN_VALUE;
        stableResolves = 0;
        geometryStableFrames = 0;
        openedAt = SystemClock.uptimeMillis();
        java.util.Arrays.fill(shiftX, 0f);
        java.util.Arrays.fill(shiftY, 0f);
    }

    /** Which preset the look on screen asks for. */
    public static int preset() {
        if (!CustomProfileHelper.isEnabled()) {
            return PRESET_TELEGRAM;
        }
        final int preset = CustomProfileHelper.cfgInt(NekoConfig.customProfileHeaderLayout);
        return preset == PRESET_LEFT || preset == PRESET_CUSTOM ? preset : PRESET_TELEGRAM;
    }

    /** Whether anything at all has to be moved. */
    public static boolean has() {
        if (!CustomProfileHelper.isEnabled()) {
            return false;
        }
        parse();
        if (anchored) {
            return true;
        }
        for (Element element : elements) {
            if (!element.isIdle()) {
                return true;
            }
        }
        return false;
    }

    /** The look on screen's extras — the draw path's view, a peer's while their profile is up. */
    public static Extras extras() {
        parse();
        return extras;
    }

    /** The user's own extras, for the editor. */
    public static Extras ownExtras() {
        parseOwn();
        return extras;
    }

    /** The buttons' own content scale, with the header's scaling divided back out when asked. */
    public static float contentFactorX(@Nullable View actions) {
        final Extras e = extras();
        return contentFactor(e.contentScaleX / 100f, e.plainContent ? screenScale(actions, false) : 1f);
    }

    public static float contentFactorY(@Nullable View actions) {
        final Extras e = extras();
        return contentFactor(e.contentScaleY / 100f, e.plainContent ? screenScale(actions, true) : 1f);
    }

    private static float contentFactor(float wanted, float inherited) {
        return Math.abs(inherited) < 0.001f ? wanted : wanted / inherited;
    }

    private static float screenScale(@Nullable View view, boolean vertical) {
        float scale = 1f;
        View walk = view;
        for (int i = 0; walk != null && i < 6; i++) {
            scale *= vertical ? walk.getScaleY() : walk.getScaleX();
            final android.view.ViewParent parent = walk.getParent();
            walk = parent instanceof View ? (View) parent : null;
        }
        return scale;
    }

    /**
     * Moves the header's parts where the look wants them. Called from the profile's layout pass,
     * after it has positioned everything itself.
     *
     * @param root   the view the parts live in and every measurement is taken against.
     * @param expand how far the header is expanded, 0..1.
     * @param pull   how far the avatar has been pulled open into the viewer, 0..1.
     */
    public static void apply(@Nullable View root, @Nullable View avatar, @Nullable View name,
                             @Nullable View status, @Nullable View actions,
                             float expand, float pull) {
        if (!CustomProfileHelper.isEnabled() || !has() || root == null) {
            restoreAll();
            return;
        }
        parse();
        final float width = Math.max(1f, root.getWidth());
        final float amount = headerAmount(expand, pull);
        final View[] views = {avatar, name, status, actions, null};

        // The name's view is as wide as the header, so anchoring it by its edge would anchor empty
        // space. This shifts it by half its text so the letters land where the anchor says.
        final float nameOffset = nameOffset(name);
        final float[] wantedX = new float[CustomProfileAnchors.COUNT];
        final float[] wantedY = new float[CustomProfileAnchors.COUNT];
        for (int i = 0; i < CustomProfileAnchors.COUNT; i++) {
            wantedX[i] = elements[i].x * width;
            wantedY[i] = elements[i].y * width;
        }
        wantedX[CustomProfileAnchors.AVATAR] = clampF(wantedX[CustomProfileAnchors.AVATAR],
                -width * AVATAR_BOUND, width * AVATAR_BOUND);
        wantedX[CustomProfileAnchors.NAME] += nameOffset;

        applyAnchors(root, views, wantedX, wantedY, amount, pull);

        for (int i = 0; i < CustomProfileAnchors.COUNT; i++) {
            transforms[i].apply(views[i], elements[i], wantedX[i], wantedY[i], amount);
        }
        applyActionsContent(actions, amount);
    }

    /**
     * The action buttons' own contents, scaled inside a row whose size the header decides.
     *
     * <p>Two knobs, and they do different things. The scale is plain: draw what is in the buttons
     * bigger or smaller. "Keep size" divides the header's own scaling back out, so the icons and
     * labels stay the size they are on screen while the row around them collapses with the header —
     * which is the point of it, and why it needs the chain of parents rather than one number.
     */
    private static void applyActionsContent(@Nullable View actions, float amount) {
        if (!(actions instanceof android.view.ViewGroup group)) {
            return;
        }
        final Extras e = extras;
        if (e.contentScaleX == CONTENT_SCALE_DEFAULT && e.contentScaleY == CONTENT_SCALE_DEFAULT
                && !e.plainContent) {
            for (int i = 0; i < group.getChildCount(); i++) {
                restoreContent(group.getChildAt(i));
            }
            return;
        }
        final float wantedX = contentFactorX(actions);
        final float wantedY = contentFactorY(actions);
        final float strength = Math.max(0f, Math.min(1f, amount));
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            final float[] written = contentScales.get(child);
            final float baseX = written == null || Math.abs(child.getScaleX() - written[0]) > 0.002f
                    ? child.getScaleX() : (written.length > 2 ? written[2] : 1f);
            final float baseY = written == null || Math.abs(child.getScaleY() - written[1]) > 0.002f
                    ? child.getScaleY() : (written.length > 3 ? written[3] : 1f);
            final float outX = baseX * ((wantedX - 1f) * strength + 1f);
            final float outY = baseY * ((wantedY - 1f) * strength + 1f);
            if (Math.abs(child.getScaleX() - outX) > 0.002f) {
                child.setScaleX(outX);
            }
            if (Math.abs(child.getScaleY() - outY) > 0.002f) {
                child.setScaleY(outY);
            }
            contentScales.put(child, new float[]{outX, outY, baseX, baseY});
        }
    }

    private static void restoreContent(View child) {
        final float[] written = contentScales.remove(child);
        if (written == null || written.length < 4) {
            return;
        }
        if (Math.abs(child.getScaleX() - written[0]) <= 0.002f) {
            child.setScaleX(written[2]);
        }
        if (Math.abs(child.getScaleY() - written[1]) <= 0.002f) {
            child.setScaleY(written[3]);
        }
    }

    /** Puts every part back where the profile itself put it. */
    public static void restoreAll() {
        for (Transform transform : transforms) {
            transform.restore();
        }
        for (View child : new java.util.ArrayList<>(contentScales.keySet())) {
            restoreContent(child);
        }
    }

    /** Puts one view back; kept for callers that only know about one part. */
    public static void restore(@Nullable View view) {
        for (Transform transform : transforms) {
            if (transform.view == view) {
                transform.restore();
            }
        }
    }

    /**
     * How much of the layout applies right now: all of it while the header is open and the avatar has
     * not been pulled, tapering to nothing over the last 60% of the pull.
     */
    static float headerAmount(float expand, float pull) {
        final float pulled = clampF(pull, 0f, 1f);
        final float taper = pulled <= PULL_HOLD ? 1f : 1f - (pulled - PULL_HOLD) / 0.6f;
        return clampF(expand, 0f, 1f) * taper;
    }

    private static float nameOffset(@Nullable View name) {
        final int anchor = extras.nameAnchor;
        if (anchor == NAME_CENTER || name == null) {
            return 0f;
        }
        final float width = textWidth(name);
        if (width <= 0f) {
            return 0f;
        }
        return anchor == NAME_LEFT ? width / 2f : -width / 2f;
    }

    /** A text view's own text width when it reports one, and the view's width otherwise. */
    static float textWidth(@Nullable View view) {
        if (view == null) {
            return 0f;
        }
        if (view instanceof org.telegram.ui.ActionBar.SimpleTextView simple) {
            final float width = simple.getTextWidth();
            if (width > 0f) {
                return width;
            }
        } else if (view instanceof android.widget.TextView text && text.getLayout() != null) {
            float width = 0f;
            for (int i = 0; i < text.getLayout().getLineCount(); i++) {
                width = Math.max(width, text.getLayout().getLineWidth(i));
            }
            if (width > 0f) {
                return width;
            }
        }
        return view.getWidth();
    }

    // ---------------------------------------------------------------- the anchors

    private static void applyAnchors(View root, View[] views, float[] wantedX, float[] wantedY,
                                     float amount, float pull) {
        if (pull > PULL_FREEZE) {
            // The avatar is opening; nothing here can be measured meaningfully any more.
            addShift(wantedX, wantedY);
            return;
        }
        final int[] targets = CustomProfileAnchors.withoutMissing(targets(), present(views));
        boolean any = false;
        long signature = 527L + preset();
        for (int target : targets) {
            signature = signature * 31 + target;
            any |= target != CustomProfileAnchors.TARGET_NONE;
        }
        if (!any) {
            anchorsReady = false;
            return;
        }
        signature = signature * 31 + root.getWidth();
        signature = signature * 31 + root.getHeight();
        signature = signature * 31 + settingsSignature();

        final long geometry = geometrySignature(root, views);
        geometryStableFrames = geometry == geometryLast ? geometryStableFrames + 1 : 0;
        geometryLast = geometry;
        if (geometryStableFrames >= CustomProfileAnchors.STABLE_RESOLVES) {
            geometrySettled = geometry;
        }
        signature = signature * 31 + geometrySettled;

        final boolean working = extras.anchorAlways || !CustomProfileAnchors.frozen(
                stableResolves, openedAt, SystemClock.uptimeMillis());
        // Resolving needs everything measured, and the header fully open — a part measured mid-
        // collapse would anchor its neighbour to a size that is about to change.
        if ((working || !anchorsReady || signature != anchorSignature)
                && amount > 0.99f && measured(views, targets)) {
            resolve(root, views, wantedX, wantedY, targets);
            anchorSignature = signature;
            anchorsReady = true;
        }
        addShift(wantedX, wantedY);
    }

    private static void addShift(float[] wantedX, float[] wantedY) {
        if (!anchorsReady) {
            return;
        }
        for (int i = 0; i < CustomProfileAnchors.COUNT; i++) {
            wantedX[i] += shiftX[i];
            wantedY[i] += shiftY[i];
        }
    }

    private static void resolve(View root, View[] views, float[] wantedX, float[] wantedY,
                                int[] targets) {
        final int count = CustomProfileAnchors.COUNT;
        final float[] startX = new float[count];
        final float[] startY = new float[count];
        final float[] sizeX = new float[count];
        final float[] sizeY = new float[count];
        for (int i = 0; i < count; i++) {
            final View view = views[i];
            if (view == null) {
                continue;
            }
            // Where the part would be without what we already added to it, plus what this pass wants
            // to add — so the answer does not chase its own tail from frame to frame.
            startX[i] = CustomProfileAnchors.drawnStart(root, view, false)
                    - transforms[i].appliedNowX() + wantedX[i];
            startY[i] = CustomProfileAnchors.drawnStart(root, view, true)
                    - transforms[i].appliedNowY() + wantedY[i];
            sizeX[i] = CustomProfileAnchors.drawnSize(root, view, false);
            sizeY[i] = CustomProfileAnchors.drawnSize(root, view, true);
            final float text = textWidth(view) * CustomProfileAnchors.chainScale(root, view, false);
            if (text > 0f && text < sizeX[i]) {
                startX[i] = CustomProfileAnchors.textStart(startX[i], sizeX[i], text,
                        CustomProfileAnchors.align(gravityOf(view)));
                sizeX[i] = text;
            }
        }

        final RectF thought = CustomProfileThought.bounds();
        final float[] extraStartX = {0f, thought == null ? 0f : thought.left};
        final float[] extraSizeX = {root.getWidth(), thought == null ? 0f : thought.width()};
        final float[] extraStartY = {0f, thought == null ? 0f : thought.top};
        final float[] extraSizeY = {root.getHeight(), thought == null ? 0f : thought.height()};

        final float[] outX = CustomProfileAnchors.resolve(startX, sizeX, targets,
                points(false, false), points(true, false), offsets(false), extraStartX, extraSizeX);
        final float[] outY = CustomProfileAnchors.resolve(startY, sizeY, targets,
                points(false, true), points(true, true), offsets(true), extraStartY, extraSizeY);

        float moved = 0f;
        for (int i = 0; i < count; i++) {
            final boolean anchoredHere =
                    views[i] != null && targets[i] != CustomProfileAnchors.TARGET_NONE;
            final float dx = anchoredHere ? outX[i] - startX[i] : 0f;
            final float dy = anchoredHere ? outY[i] - startY[i] : 0f;
            moved = Math.max(moved, Math.max(Math.abs(dx - shiftX[i]), Math.abs(dy - shiftY[i])));
            shiftX[i] = dx;
            shiftY[i] = dy;
        }
        stableResolves = CustomProfileAnchors.nextStableCount(stableResolves, moved);
    }

    private static boolean[] present(View[] views) {
        final boolean[] out = new boolean[views.length];
        for (int i = 0; i < views.length; i++) {
            out[i] = views[i] != null && views[i].getVisibility() != View.GONE;
        }
        return out;
    }

    /** Whether everything an anchor depends on has a size yet. */
    private static boolean measured(View[] views, int[] targets) {
        boolean needsThought = false;
        for (int i = 0; i < views.length; i++) {
            if (targets[i] == CustomProfileAnchors.TARGET_NONE) {
                continue;
            }
            if (views[i] == null || views[i].getWidth() <= 0 || views[i].getHeight() <= 0) {
                return false;
            }
            if (targets[i] == CustomProfileAnchors.TARGET_THOUGHT) {
                needsThought = true;
            }
        }
        for (int target : targets) {
            final int part = CustomProfileAnchors.partOf(target);
            if (part >= 0 && (views[part] == null || views[part].getWidth() <= 0)) {
                return false;
            }
        }
        return !needsThought || CustomProfileThought.bounds() != null;
    }

    /** Coarse, so a pixel of scroll does not count as the header having moved. */
    private static long geometrySignature(View root, View[] views) {
        long hash = 17;
        for (int i = 0; i < views.length; i++) {
            final View view = views[i];
            if (view == null) {
                hash = hash * 31;
                continue;
            }
            hash = hash * 31 + Math.round((CustomProfileAnchors.drawnStart(root, view, false)
                    - transforms[i].appliedNowX()) / 4f);
            hash = hash * 31 + Math.round((CustomProfileAnchors.drawnStart(root, view, true)
                    - transforms[i].appliedNowY()) / 4f);
            hash = hash * 31 + Math.round(CustomProfileAnchors.drawnSize(root, view, false) / 4f);
            hash = hash * 31 + Math.round(CustomProfileAnchors.drawnSize(root, view, true) / 4f);
        }
        return hash;
    }

    private static long settingsSignature() {
        long hash = 17;
        for (int axis = 0; axis < 2; axis++) {
            final boolean vertical = axis == 1;
            final float[] off = offsets(vertical);
            final float[] from = points(false, vertical);
            final float[] to = points(true, vertical);
            for (int i = 0; i < off.length; i++) {
                hash = hash * 31 + Math.round(off[i]);
                hash = hash * 31 + Math.round(from[i] * 100f);
                hash = hash * 31 + Math.round(to[i] * 100f);
            }
        }
        return hash;
    }

    private static int gravityOf(View view) {
        if (view instanceof android.widget.TextView text) {
            return text.getGravity();
        }
        return 0;
    }

    private static int[] targets() {
        final int[] out = new int[CustomProfileAnchors.COUNT];
        for (int i = 0; i < out.length; i++) {
            out[i] = anchorValue(i, TARGET);
        }
        return out;
    }

    private static float[] points(boolean to, boolean vertical) {
        final float[] out = new float[CustomProfileAnchors.COUNT];
        final int key = to ? (vertical ? TO_Y : TO_X) : (vertical ? FROM_Y : FROM_X);
        for (int i = 0; i < out.length; i++) {
            out[i] = CustomProfileAnchors.fraction(anchorValue(i, key));
        }
        return out;
    }

    private static float[] offsets(boolean vertical) {
        final float[] out = new float[CustomProfileAnchors.COUNT];
        final int key = vertical ? OFFSET_Y : OFFSET_X;
        for (int i = 0; i < out.length; i++) {
            out[i] = AndroidUtilities.dpf2(anchorValue(i, key));
        }
        return out;
    }

    private static int anchorValue(int part, int key) {
        if (!anchored || part < 0 || part >= CustomProfileAnchors.COUNT
                || key < 0 || key >= ANCHOR_PARTS) {
            return anchorFallback(key);
        }
        return anchors[part * ANCHOR_PARTS + key];
    }

    /** Nothing is anchored by default, and the points that do exist default to the centre. */
    private static int anchorFallback(int key) {
        return (key >= TO_X && key <= FROM_Y) ? CustomProfileAnchors.POINT_CENTER : 0;
    }

    // ---------------------------------------------------------------- reading the config

    private static void parse() {
        final int preset = preset();
        parse(preset, preset == PRESET_CUSTOM
                ? CustomProfileHelper.cfgString(NekoConfig.customProfileHeaderConfig) : "");
    }

    /**
     * Reads the user's own layout rather than whichever look is on screen.
     *
     * <p>The two differ: {@link CustomProfileHelper#cfgString} hands back the peer's value while a
     * peer's profile is being drawn, which is right for drawing and wrong for editing — an editor
     * that read it would save somebody else's layout into your own settings.
     */
    private static void parseOwn() {
        final int preset = NekoConfig.customProfileHeaderLayout.Int();
        parse(preset == PRESET_LEFT || preset == PRESET_CUSTOM ? preset : PRESET_TELEGRAM,
                NekoConfig.customProfileHeaderConfig.String());
    }

    private static void parse(int preset, String raw) {
        if (preset == parsedPreset && raw.equals(parsedFrom)) {
            return;
        }
        parsedPreset = preset;
        parsedFrom = raw;
        reopen();

        if (preset == PRESET_CUSTOM) {
            elements = new Element[]{
                    element(raw, PARTS[0]), element(raw, PARTS[1]), element(raw, PARTS[2]),
                    element(raw, PARTS[3]), element(raw, PARTS[4]),
            };
            readAnchors(key -> number(raw, key, Float.NaN, 4096f));
            extras = new Extras(
                    clamp((int) whole(raw, "name_anchor", 0), NAME_CENTER, NAME_RIGHT),
                    whole(raw, "anchor_always", 0) != 0,
                    whole(raw, "actions_plain_content", 0) != 0,
                    clamp((int) whole(raw, "actions_content_scale_x", CONTENT_SCALE_DEFAULT),
                            CONTENT_SCALE_MIN, CONTENT_SCALE_MAX),
                    clamp((int) whole(raw, "actions_content_scale_y", CONTENT_SCALE_DEFAULT),
                            CONTENT_SCALE_MIN, CONTENT_SCALE_MAX));
        } else {
            elements = idleElements();
            readAnchors(key -> presetAnchor(preset, key));
            extras = new Extras(preset == PRESET_LEFT ? NAME_LEFT : NAME_CENTER, false, false,
                    CONTENT_SCALE_DEFAULT, CONTENT_SCALE_DEFAULT);
        }
    }

    private interface Lookup {
        /** The value stored under a key, or NaN when there is none. */
        float of(String key);
    }

    private static void readAnchors(Lookup lookup) {
        anchors = new int[CustomProfileAnchors.COUNT * ANCHOR_PARTS];
        anchored = false;
        for (int part = 0; part < CustomProfileAnchors.COUNT; part++) {
            for (int key = 0; key < ANCHOR_PARTS; key++) {
                final float value = lookup.of("anchor_" + PARTS[part] + ANCHOR_SUFFIXES[key]);
                anchors[part * ANCHOR_PARTS + key] = Float.isNaN(value)
                        ? anchorFallback(key) : clampAnchor(key, Math.round(value));
            }
            anchored |= anchors[part * ANCHOR_PARTS + TARGET] != CustomProfileAnchors.TARGET_NONE;
        }
    }

    private static int clampAnchor(int key, int value) {
        if (key == TARGET) {
            return (value < 0 || value > CustomProfileAnchors.TARGET_LAST)
                    ? CustomProfileAnchors.TARGET_NONE : value;
        }
        if (key == OFFSET_X || key == OFFSET_Y) {
            return clamp(value, -CustomProfileAnchors.OFFSET_LIMIT,
                    CustomProfileAnchors.OFFSET_LIMIT);
        }
        return (value < CustomProfileAnchors.POINT_START || value > CustomProfileAnchors.POINT_END)
                ? CustomProfileAnchors.POINT_CENTER : value;
    }

    /**
     * The left-hand preset's own numbers: the avatar tucked into the top-left corner and the name and
     * the status stacked beside it, which is what its editor would have written out.
     */
    private static float presetAnchor(int preset, String key) {
        if (preset == PRESET_LEFT) {
            switch (key) {
                case "anchor_avatar_to": return 1;
                case "anchor_avatar_to_x", "anchor_avatar_to_y",
                     "anchor_avatar_from_x", "anchor_avatar_from_y": return 0;
                case "anchor_avatar_x": return 30;
                case "anchor_avatar_y": return 150;
                case "anchor_name_to", "anchor_status_to": return 3;
                case "anchor_name_to_x", "anchor_status_to_x": return 2;
                case "anchor_name_to_y": return 1;
                case "anchor_name_from_x", "anchor_status_from_x": return 0;
                case "anchor_name_from_y": return 1;
                case "anchor_name_x": return 15;
                case "anchor_name_y": return 0;
                case "anchor_status_to_y", "anchor_status_from_y": return 2;
                case "anchor_status_x": return 30;
                case "anchor_status_y": return -5;
                default: return Float.NaN;
            }
        }
        return Float.NaN;
    }

    private static Element[] idleElements() {
        return new Element[]{
                Element.NONE, Element.NONE, Element.NONE, Element.NONE, Element.NONE,
        };
    }

    private static Element element(String raw, String part) {
        // A single "scale" is what an older editor wrote; it stands in for both axes.
        final float both = number(raw, part + "_scale", 100f, 300f);
        return new Element(
                number(raw, part + "_x", 0f, 45f) / 100f,
                number(raw, part + "_y", 0f, 45f) / 100f,
                number(raw, part + "_rotate", 0f, ROTATE_LIMIT),
                number(raw, part + "_scale_x", both, 300f) / 100f,
                number(raw, part + "_scale_y", both, 300f) / 100f);
    }

    /** One number out of the config, clamped to ±{@code limit}, or {@code fallback} if absent. */
    private static float number(@Nullable String raw, String key, float fallback, float limit) {
        if (TextUtils.isEmpty(raw) || raw.length() > 4096) {
            return fallback;
        }
        try {
            final Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key)
                    + "\\\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))").matcher(raw);
            if (matcher.find()) {
                return clampF(Float.parseFloat(matcher.group(1)), -limit, limit);
            }
        } catch (Throwable ignore) {
        }
        return fallback;
    }

    private static float whole(String raw, String key, float fallback) {
        final float value = number(raw, key, Float.NaN, 100000f);
        return Float.isNaN(value) ? fallback : Math.round(value);
    }

    /** Writes a layout back out, which is what the settings screen edits through. */
    public static String encode(Element[] parts, int[] anchorValues, Extras values) {
        final StringBuilder out = new StringBuilder(1280);
        out.append("{\"version\":3");
        for (int i = 0; i < PARTS.length && i < parts.length; i++) {
            final Element element = parts[i] == null ? Element.NONE : parts[i];
            out.append(String.format(Locale.US,
                    ",\"%1$s_x\":%2$.1f,\"%1$s_y\":%3$.1f,\"%1$s_rotate\":%4$.1f,"
                            + "\"%1$s_scale_x\":%5$.1f,\"%1$s_scale_y\":%6$.1f",
                    PARTS[i], element.x * 100f, element.y * 100f, element.rotate,
                    element.scaleX * 100f, element.scaleY * 100f));
        }
        if (anchorValues != null && anchorValues.length >= CustomProfileAnchors.COUNT * ANCHOR_PARTS) {
            for (int part = 0; part < CustomProfileAnchors.COUNT; part++) {
                for (int key = 0; key < ANCHOR_PARTS; key++) {
                    out.append(",\"anchor_").append(PARTS[part]).append(ANCHOR_SUFFIXES[key])
                            .append("\":").append(anchorValues[part * ANCHOR_PARTS + key]);
                }
            }
        }
        final Extras e = values == null ? Extras.NONE : values;
        out.append(",\"name_anchor\":").append(e.nameAnchor);
        out.append(",\"anchor_always\":").append(e.anchorAlways ? 1 : 0);
        out.append(",\"actions_plain_content\":").append(e.plainContent ? 1 : 0);
        out.append(",\"actions_content_scale_x\":").append(e.contentScaleX);
        out.append(",\"actions_content_scale_y\":").append(e.contentScaleY);
        out.append('}');
        return out.toString();
    }

    /** The layout as it stands, for the settings screen to edit. */
    public static Element[] elements() {
        parseOwn();
        return elements.clone();
    }

    public static int[] anchorValues() {
        parseOwn();
        return anchors.clone();
    }

    public static int anchorPartCount() {
        return ANCHOR_PARTS;
    }

    public static Element makeElement(float x, float y, float rotate, float scaleX, float scaleY) {
        return new Element(x, y, rotate, scaleX, scaleY);
    }

    public static Extras makeExtras(int nameAnchor, boolean anchorAlways, boolean plainContent,
                                    int scaleX, int scaleY) {
        return new Extras(nameAnchor, anchorAlways, plainContent, scaleX, scaleY);
    }

    // ---------------------------------------------------------------- editing

    /**
     * Which preset the look wears. Switching to anything but the custom one leaves the custom
     * configuration in place, so a user can try the built-in layouts and come back to their own.
     */
    public static void setPreset(int preset) {
        NekoConfig.customProfileHeaderLayout.setConfigInt(
                preset == PRESET_LEFT || preset == PRESET_CUSTOM ? preset : PRESET_TELEGRAM);
        CustomProfileHelper.onSettingsChanged();
    }

    /**
     * Rewrites the custom layout with one part's own offsets replaced. Everything is re-encoded
     * together because the layout is one string; that is also what keeps the anchors and the extras
     * from being lost when an offset is nudged.
     */
    public static void setElement(int part, Element element) {
        parseOwn();
        if (part < 0 || part >= CustomProfileAnchors.COUNT || element == null) {
            return;
        }
        final Element[] next = elements.clone();
        next[part] = element;
        write(next, anchors.clone(), extras);
    }

    public static void setAnchor(int part, int key, int value) {
        parseOwn();
        if (part < 0 || part >= CustomProfileAnchors.COUNT || key < 0 || key >= ANCHOR_PARTS) {
            return;
        }
        final int[] next = anchors.clone();
        next[part * ANCHOR_PARTS + key] = clampAnchor(key, value);
        write(elements.clone(), next, extras);
    }

    public static void setExtras(Extras values) {
        parseOwn();
        write(elements.clone(), anchors.clone(), values == null ? Extras.NONE : values);
    }

    /** Puts the custom layout back to "as Telegram draws it" without changing the preset. */
    public static void resetCustom() {
        write(idleElements(), new int[CustomProfileAnchors.COUNT * ANCHOR_PARTS], Extras.NONE);
    }

    private static void write(Element[] parts, int[] anchorValues, Extras values) {
        NekoConfig.customProfileHeaderConfig.setConfigString(encode(parts, anchorValues, values));
        // Editing anything here means the custom preset is the one being edited.
        if (preset() != PRESET_CUSTOM) {
            NekoConfig.customProfileHeaderLayout.setConfigInt(PRESET_CUSTOM);
        }
        invalidate();
        CustomProfileHelper.onSettingsChanged();
    }

    /** Read for the editor: the anchor keys of one part, in the order {@link #encode} writes them. */
    public static int anchorOf(int part, int key) {
        parseOwn();
        return anchorValue(part, key);
    }

    public static final int ANCHOR_TARGET = TARGET;
    public static final int ANCHOR_TO_X = TO_X;
    public static final int ANCHOR_TO_Y = TO_Y;
    public static final int ANCHOR_FROM_X = FROM_X;
    public static final int ANCHOR_FROM_Y = FROM_Y;
    public static final int ANCHOR_OFFSET_X = OFFSET_X;
    public static final int ANCHOR_OFFSET_Y = OFFSET_Y;

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float clampF(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    // ---------------------------------------------------------------- one part's transform

    /**
     * What this class has done to one view, and how to take it back off.
     *
     * <p>The header animates the same five properties, so "what it was before" cannot simply be
     * remembered once. Instead each property remembers the value this class last wrote: finding that
     * value still there means the base is unchanged, and finding anything else means the header moved
     * the view and that new value is the base from now on. The half-pixel and half-degree tolerances
     * are the reference's own, and are what stop float drift from being read as a change.
     */
    private static final class Transform {
        @Nullable
        View view;

        private float baseX;
        private float baseY;
        private float baseRotation;
        private float baseScaleX = 1f;
        private float baseScaleY = 1f;

        private float outputX = Float.NaN;
        private float outputY = Float.NaN;
        private float outputRotation = Float.NaN;
        private float outputScaleX = Float.NaN;
        private float outputScaleY = Float.NaN;

        void apply(@Nullable View target, Element element, float dx, float dy, float amount) {
            if (view != target) {
                restore();
                view = target;
                if (target == null) {
                    return;
                }
                baseX = target.getTranslationX();
                baseY = target.getTranslationY();
                baseRotation = target.getRotation();
                baseScaleX = target.getScaleX();
                baseScaleY = target.getScaleY();
            }
            if (view == null || element == null) {
                return;
            }
            final float strength = Math.max(0f, Math.min(1f, amount));
            applyShift(dx, dy, strength);
            applyRotation(element.rotate, strength);
            applyScale(element.scaleX, element.scaleY, strength);
        }

        private void applyShift(float dx, float dy, float amount) {
            final float nowX = view.getTranslationX();
            final float nowY = view.getTranslationY();
            baseX = base(nowX, outputX, baseX);
            baseY = base(nowY, outputY, baseY);
            outputX = baseX + dx * amount;
            outputY = baseY + dy * amount;
            if (Math.abs(nowX - outputX) > 0.25f) {
                view.setTranslationX(outputX);
            }
            if (Math.abs(nowY - outputY) > 0.25f) {
                view.setTranslationY(outputY);
            }
        }

        private void applyRotation(float degrees, float amount) {
            final float now = view.getRotation();
            baseRotation = base(now, outputRotation, baseRotation);
            outputRotation = baseRotation + degrees * amount;
            if (Math.abs(now - outputRotation) > 0.25f) {
                view.setRotation(outputRotation);
            }
        }

        private void applyScale(float scaleX, float scaleY, float amount) {
            final float nowX = view.getScaleX();
            final float nowY = view.getScaleY();
            baseScaleX = scaleBase(nowX, outputScaleX, baseScaleX);
            baseScaleY = scaleBase(nowY, outputScaleY, baseScaleY);
            outputScaleX = baseScaleX * ((scaleX - 1f) * amount + 1f);
            outputScaleY = baseScaleY * ((scaleY - 1f) * amount + 1f);
            if (Math.abs(nowX - outputScaleX) > 0.002f) {
                view.setScaleX(outputScaleX);
            }
            if (Math.abs(nowY - outputScaleY) > 0.002f) {
                view.setScaleY(outputScaleY);
            }
        }

        float appliedNowX() {
            return view == null ? 0f
                    : view.getTranslationX() - base(view.getTranslationX(), outputX, baseX);
        }

        float appliedNowY() {
            return view == null ? 0f
                    : view.getTranslationY() - base(view.getTranslationY(), outputY, baseY);
        }

        void restore() {
            if (view != null) {
                if (restorable(outputX, view.getTranslationX())) {
                    view.setTranslationX(baseX);
                }
                if (restorable(outputY, view.getTranslationY())) {
                    view.setTranslationY(baseY);
                }
                if (restorable(outputRotation, view.getRotation())) {
                    view.setRotation(baseRotation);
                }
                if (restorableScale(outputScaleX, view.getScaleX())) {
                    view.setScaleX(baseScaleX);
                }
                if (restorableScale(outputScaleY, view.getScaleY())) {
                    view.setScaleY(baseScaleY);
                }
            }
            view = null;
            outputX = Float.NaN;
            outputY = Float.NaN;
            outputRotation = Float.NaN;
            outputScaleX = Float.NaN;
            outputScaleY = Float.NaN;
        }

        private static float base(float now, float lastWritten, float base) {
            return (Float.isNaN(lastWritten) || Math.abs(now - lastWritten) > 0.5f) ? now : base;
        }

        private static float scaleBase(float now, float lastWritten, float base) {
            return (Float.isNaN(lastWritten) || Math.abs(now - lastWritten) > 0.001f) ? now : base;
        }

        private static boolean restorable(float lastWritten, float now) {
            return !Float.isNaN(lastWritten) && Math.abs(now - lastWritten) <= 0.5f;
        }

        private static boolean restorableScale(float lastWritten, float now) {
            return !Float.isNaN(lastWritten) && Math.abs(now - lastWritten) <= 0.001f;
        }
    }
}
