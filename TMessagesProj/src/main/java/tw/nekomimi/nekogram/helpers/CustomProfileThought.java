package tw.nekomimi.nekogram.helpers;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * The thought — a small bubble beside the avatar carrying a line of the user's own text.
 *
 * <p>It is the single most used part of a look: 83% of the published gallery sets one, more than any
 * banner, colour or shape. Which is why it is worth being exact about, and this is a straight port of
 * the reference's own geometry rather than something that merely looks similar:
 * <ul>
 *     <li>the bubble hangs to the right of the avatar, 12dp clear of it and 2dp down;</li>
 *     <li>it may take the space left to the screen edge, but never more than 58% of the width; when
 *         that leaves too little it is instead pinned to the right at 160dp;</li>
 *     <li>the text is laid out at 12.5dp and shrinks, a ninth of the way at a time, until it fits in
 *         four lines or reaches 8dp, after which it wraps as far as it needs to;</li>
 *     <li>the corner radius is half the height for one line and 12dp for more, which is what makes a
 *         short thought a pill and a long one a card;</li>
 *     <li>two small circles trail from the bottom-left corner, at 2.4dp and 1.5dp — the tail that
 *         makes it read as a thought rather than a label.</li>
 * </ul>
 *
 * <p>Hidden while the avatar is smaller than 40dp: the profile collapses the avatar as it scrolls,
 * and a bubble beside a 20dp avatar is a bubble over the name.
 */
public final class CustomProfileThought {

    /** The reference's own ceiling on the text, applied before anything is measured. */
    private static final int MAX_CHARS = 120;
    private static final int MAX_LINES = 4;

    private static final float TEXT_DP = 12.5f;
    private static final float TEXT_MIN_DP = 8f;
    private static final float PADDING_X_DP = 9f;
    private static final float PADDING_Y_DP = 5f;
    private static final float LINE_GAP_DP = 2f;
    private static final float GAP_DP = 12f;
    private static final float TOP_DP = 2f;
    private static final float EDGE_DP = 8f;
    private static final float MIN_AVATAR_DP = 40f;
    private static final float MIN_WIDTH_DP = 70f;
    private static final float PINNED_WIDTH_DP = 160f;
    private static final float MIN_TEXT_WIDTH_DP = 30f;
    private static final float MAX_WIDTH_FRACTION = 0.58f;

    private static final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final RectF rect = new RectF();

    /**
     * Where the bubble was last drawn, in the coordinates of the view that drew it, and whether it
     * was drawn at all this pass. Two things need it: a part of the header can be anchored to the
     * bubble, and the bubble can be tapped to edit its text.
     */
    private static final RectF drawnAt = new RectF();
    private static boolean onScreen;
    /** How far a finger may travel between press and release and still count as a tap. */
    private static final float TAP_SLOP_DP = 10f;
    private static float pressX;
    private static float pressY;
    private static boolean pressed;

    /** What the current layout was built from, so it is rebuilt only when it has to be. */
    private static String laidOutText;
    private static long laidOutSig = Long.MIN_VALUE;
    private static int laidOutWidth = -1;
    private static List<String> lines;
    private static float fontSize;
    private static float lineHeight;
    private static float ascent;
    private static float textWidth;

    private CustomProfileThought() {
    }

    /** The text of the look on screen, trimmed to what will be drawn. Empty when it has none. */
    private static String text() {
        final String text = CustomProfileHelper.cfgString(NekoConfig.customProfileThoughtText);
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        return text.length() > MAX_CHARS ? text.substring(0, MAX_CHARS) : text;
    }

    /** Whether there is a thought to draw at all. */
    public static boolean has() {
        return CustomProfileHelper.isEnabled() && !text().isEmpty();
    }

    /** Drops the cached layout — the text, the colours or the typeface have moved. */
    public static void invalidate() {
        laidOutSig = Long.MIN_VALUE;
        laidOutText = null;
        lines = null;
        onScreen = false;
        pressed = false;
    }

    /**
     * Draws the bubble next to an avatar occupying the given box, in the coordinates of whatever view
     * is being drawn into.
     *
     * @param containerWidth the width available, i.e. the drawing view's own.
     * @param alpha          0..1, so the bubble fades with the header it belongs to.
     */
    public static void draw(Canvas canvas, float avatarLeft, float avatarTop, float avatarSide,
                            float containerWidth, float alpha) {
        final String text = text();
        onScreen = false;
        if (canvas == null || text.isEmpty() || alpha <= 0.02f || containerWidth <= 0) {
            return;
        }
        if (avatarSide < AndroidUtilities.dpf2(MIN_AVATAR_DP)) {
            // The avatar has collapsed into the action bar; the bubble would sit on the name.
            return;
        }
        final int textColor = CustomProfileHelper.cfgInt(NekoConfig.customProfileThoughtTextColor);
        final int backColor = CustomProfileHelper.cfgInt(NekoConfig.customProfileThoughtBackground);
        final int font = fontIndex();
        // The bubble's own file when it has stopped copying the name's, and the name's when it has
        // not — the index says "this look's own file" either way, but they are two different files.
        final String fontPath = font != 7 ? ""
                : (CustomProfileHelper.cfgBool(NekoConfig.customProfileThoughtFontCopy)
                ? CustomProfileHelper.fontPath() : CustomProfileHelper.thoughtFontPath());
        final long sig = signature(textColor, backColor, font, fontPath);
        if (sig != laidOutSig) {
            textPaint.setColor(textColor);
            textPaint.setTextSize(AndroidUtilities.dpf2(TEXT_DP));
            textPaint.setTypeface(typeface(font, fontPath));
            bgPaint.setColor(backColor);
            laidOutSig = sig;
            laidOutText = null;
        }

        final float padX = AndroidUtilities.dpf2(PADDING_X_DP);
        final float padY = AndroidUtilities.dpf2(PADDING_Y_DP);
        float left = avatarLeft + avatarSide + AndroidUtilities.dpf2(GAP_DP);
        final float top = avatarTop + AndroidUtilities.dpf2(TOP_DP);

        float available = containerWidth - left - AndroidUtilities.dpf2(EDGE_DP);
        final float cap = containerWidth * MAX_WIDTH_FRACTION;
        if (available > cap) {
            available = cap;
        }
        if (available < AndroidUtilities.dpf2(MIN_WIDTH_DP)) {
            // Nothing worth reading fits beside the avatar, so the bubble is pinned to the right edge
            // instead, exactly as the reference does rather than letting it shrink away.
            available = Math.min(AndroidUtilities.dpf2(PINNED_WIDTH_DP),
                    containerWidth - AndroidUtilities.dpf2(16));
            left = containerWidth - available - AndroidUtilities.dpf2(EDGE_DP);
        }
        final float inner = available - padX * 2;
        if (inner < AndroidUtilities.dpf2(MIN_TEXT_WIDTH_DP)) {
            return;
        }
        // Quantised to whole 8dp steps so a pixel of scroll does not re-wrap the text.
        final float step = Math.max(AndroidUtilities.dpf2(EDGE_DP), 1f);
        layout(text, sig, (int) (inner / step) * (int) step);
        if (lines == null || lines.isEmpty()) {
            return;
        }

        textPaint.setTextSize(fontSize);
        textPaint.setAlpha((int) (((textColor >>> 24) & 0xFF) * alpha));
        bgPaint.setAlpha((int) (((backColor >>> 24) & 0xFF) * alpha));

        final float gap = AndroidUtilities.dpf2(LINE_GAP_DP);
        final float width = textWidth + padX * 2;
        final float height = lineHeight * lines.size() + (lines.size() - 1) * gap + padY * 2;
        final float radius = lines.size() == 1 ? height / 2f : AndroidUtilities.dpf2(GAP_DP);
        final float bottom = top + height;

        rect.set(left, top, left + width, bottom);
        canvas.drawRoundRect(rect, radius, radius, bgPaint);
        // The tail: two bubbles trailing off the bottom-left corner.
        canvas.drawCircle(left - AndroidUtilities.dpf2(3f), bottom - AndroidUtilities.dpf2(1f),
                AndroidUtilities.dpf2(2.4f), bgPaint);
        canvas.drawCircle(left - AndroidUtilities.dpf2(8.5f), bottom + AndroidUtilities.dpf2(3f),
                AndroidUtilities.dpf2(1.5f), bgPaint);

        float baseline = top + padY - ascent;
        for (int i = 0; i < lines.size(); i++) {
            canvas.drawText(lines.get(i), left + padX, baseline, textPaint);
            baseline += lineHeight + gap;
        }
        drawnAt.set(rect);
        onScreen = true;
    }

    /**
     * Where the bubble is right now, in the drawing view's own coordinates, or null when it is not on
     * screen. Used both to anchor a header part to it and to know what a tap landed on.
     */
    @Nullable
    public static RectF bounds() {
        return onScreen ? drawnAt : null;
    }

    /**
     * Whether a touch belongs to the bubble.
     *
     * <p>Press inside it and release within 10dp on each axis, which is the reference's own slop.
     * Movement in between is ignored rather than cancelling, and the event is never consumed — the
     * list underneath keeps scrolling as it would, and a drag that started on the bubble simply does
     * not end as a tap.
     *
     * @return whether this was a tap on the bubble, which only ever happens on the release.
     */
    /** Whether a point is inside the bubble as it is drawn right now. */
    public static boolean hits(float x, float y) {
        return onScreen && drawnAt.contains(x, y);
    }

    public static boolean onTouch(int action, float x, float y) {
        if (!onScreen) {
            pressed = false;
            return false;
        }
        if (action == android.view.MotionEvent.ACTION_DOWN) {
            pressed = drawnAt.contains(x, y);
            pressX = x;
            pressY = y;
            return false;
        }
        if (action == android.view.MotionEvent.ACTION_UP && pressed) {
            pressed = false;
            final float slop = AndroidUtilities.dpf2(TAP_SLOP_DP);
            return Math.abs(x - pressX) <= slop && Math.abs(y - pressY) <= slop;
        }
        if (action == android.view.MotionEvent.ACTION_CANCEL) {
            pressed = false;
        }
        return false;
    }

    /** Their {@code thought_font_copy}: the bubble follows the name's typeface unless told not to. */
    private static int fontIndex() {
        if (CustomProfileHelper.cfgBool(NekoConfig.customProfileThoughtFontCopy)) {
            return CustomProfileHelper.cfgInt(NekoConfig.customProfileNameFont);
        }
        return CustomProfileHelper.cfgInt(NekoConfig.customProfileThoughtFont);
    }

    @Nullable
    private static Typeface typeface(int font, String path) {
        if (font == 7) {
            return TextUtils.isEmpty(path) ? null : CustomProfileNameFx.typefaceFromFile(path);
        }
        return CustomProfileNameFx.typefaceFor(font);
    }

    private static long signature(int textColor, int backColor, int font, String path) {
        long sig = 527 + textColor;
        sig = sig * 31 + backColor;
        sig = sig * 31 + font;
        sig = sig * 31 + (path == null ? 0 : path.hashCode());
        return sig;
    }

    /**
     * Wraps the text, shrinking the type until it fits.
     *
     * <p>Starts at 12.5dp and steps down by a ninth of the way to 8dp until the text fits in four
     * lines. At the floor it wraps as far as it needs to instead, because a thought that cannot be
     * read small is still better than no thought at all.
     */
    private static void layout(String text, long sig, int maxWidth) {
        if (text.equals(laidOutText) && sig == laidOutSig && maxWidth == laidOutWidth && lines != null) {
            return;
        }
        laidOutText = text;
        laidOutWidth = maxWidth;
        float size = AndroidUtilities.dpf2(TEXT_DP);
        final float floor = AndroidUtilities.dpf2(TEXT_MIN_DP);
        final float step = Math.max((size - floor) / 9f, 1f);
        List<String> wrapped;
        while (true) {
            textPaint.setTextSize(size);
            wrapped = wrap(text, maxWidth, MAX_LINES);
            if (wrapped != null) {
                break;
            }
            if (size <= floor) {
                wrapped = wrap(text, maxWidth, 1000);
                if (wrapped == null) {
                    wrapped = new ArrayList<>();
                    wrapped.add(text);
                }
                break;
            }
            size = Math.max(size - step, floor);
        }
        fontSize = size;
        textPaint.setTextSize(size);
        final Paint.FontMetrics metrics = textPaint.getFontMetrics();
        lineHeight = metrics.descent - metrics.ascent;
        ascent = metrics.ascent;
        float widest = 0;
        for (String line : wrapped) {
            widest = Math.max(widest, textPaint.measureText(line));
        }
        textWidth = widest;
        lines = wrapped;
    }

    /** Greedy word wrap, breaking a word that does not fit on its own. Null when it needs more lines. */
    @Nullable
    private static List<String> wrap(String text, float width, int maxLines) {
        final List<String> out = new ArrayList<>();
        final String[] words = text.split(" ");
        final StringBuilder line = new StringBuilder();
        for (int i = 0; i < words.length; i++) {
            if (out.size() >= maxLines) {
                return null;
            }
            final String word = words[i];
            if (word.isEmpty()) {
                continue;
            }
            final String candidate = line.length() == 0 ? word : line + " " + word;
            if (textPaint.measureText(candidate) <= width) {
                line.setLength(0);
                line.append(candidate);
            } else if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
                i--; // the word has not been placed yet
            } else {
                int fits = word.length();
                while (fits > 1 && textPaint.measureText(word.substring(0, fits)) > width) {
                    fits--;
                }
                out.add(word.substring(0, fits));
                words[i] = word.substring(fits);
                if (!words[i].isEmpty()) {
                    i--; // the rest of the word goes on the next line
                }
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
        return out.size() <= maxLines ? out : null;
    }
}
