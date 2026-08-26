package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;
import android.text.style.UpdateAppearance;
import android.view.animation.Interpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextEffects;

import java.util.ArrayList;
import java.util.Random;

import tw.nekomimi.nekogram.NekoConfig;

/**
 * Per character appearance and disappearance effects for message input fields.
 *
 * Characters that are still animating are hidden from the normal text pass by a zero alpha span and
 * painted here instead, so the layout keeps deciding where every glyph belongs and only the paint is
 * ours. Deleted characters have no layout left to ask, so their position is captured before the edit.
 */
public class TextAnimationHelper {

    private static final Interpolator INTERPOLATOR = CubicBezierInterpolator.EASE_OUT_QUINT;
    private static final int MAX_GLYPHS = 64;
    private static final int MAX_PARTICLES = 256;

    public static final int PARTICLE_DUST = 0;
    public static final int PARTICLE_SPARKS = 1;
    public static final int PARTICLE_SNOW = 2;
    public static final int PARTICLE_PETALS = 3;
    public static final int PARTICLE_LETTERS = 4;

    private final EditTextEffects view;
    private final ArrayList<Glyph> glyphs = new ArrayList<>();
    private final ArrayList<Particle> particles = new ArrayList<>();
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final char[] one = new char[1];

    private ArrayList<Removed> pendingRemoval;
    private long lastFrame;

    private float cursorX = -1f;
    private float cursorTargetX = -1f;
    private float cursorStretch;

    public static boolean isEnabled() {
        return NekoConfig.textAnimation.Bool();
    }

    public TextAnimationHelper(EditTextEffects view) {
        this.view = view;
    }

    /** Zero alpha marker: the glyph is on screen, but this class is the one drawing it. */
    private static class HiddenSpan extends CharacterStyle implements UpdateAppearance {
        @Override
        public void updateDrawState(TextPaint tp) {
            tp.setAlpha(0);
        }
    }

    private static class Glyph {
        char c;
        int index;
        long start;
        HiddenSpan span;
    }

    private static class Removed {
        char c;
        float x;
        float y;
    }

    private static class Particle {
        char c;
        float x, y;
        float vx, vy;
        float size;
        float rotation;
        float spin;
        float life;
        float maxLife;
    }

    private int duration() {
        return Math.max(80, NekoConfig.textAnimationDuration.Int());
    }

    public void detach() {
        clearSpans();
        glyphs.clear();
        particles.clear();
    }

    private void clearSpans() {
        if (!(view.getText() instanceof Spannable)) {
            return;
        }
        Spannable spannable = (Spannable) view.getText();
        for (int i = 0; i < glyphs.size(); i++) {
            HiddenSpan span = glyphs.get(i).span;
            if (span != null) {
                spannable.removeSpan(span);
            }
        }
    }

    /**
     * Runs before the edit is applied, while the old layout still knows where the characters are.
     * Their positions are the only thing a deletion effect can be built from afterwards.
     */
    public void beforeTextChanged(CharSequence text, int start, int count) {
        pendingRemoval = null;
        if (count <= 0 || !NekoConfig.textAnimationDelete.Bool() || NekoConfig.textAnimationParticleCount.Int() <= 0) {
            return;
        }
        Layout layout = view.getLayout();
        if (layout == null || text == null || start + count > text.length()) {
            return;
        }
        ArrayList<Removed> removed = new ArrayList<>();
        boolean skipSpaces = NekoConfig.textAnimationIgnoreSpaces.Bool();
        for (int i = start; i < start + count && removed.size() < 24; i++) {
            char c = text.charAt(i);
            if (skipSpaces && Character.isWhitespace(c)) {
                continue;
            }
            try {
                int line = layout.getLineForOffset(i);
                if (!NekoConfig.textAnimationAllLines.Bool() && line != layout.getLineForOffset(start)) {
                    continue;
                }
                Removed r = new Removed();
                r.c = c;
                r.x = layout.getPrimaryHorizontal(i);
                r.y = (layout.getLineTop(line) + layout.getLineBaseline(line)) / 2f;
                removed.add(r);
            } catch (Exception ignore) {
                break;
            }
        }
        if (!removed.isEmpty()) {
            pendingRemoval = removed;
        }
    }

    public void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        if (pendingRemoval != null) {
            for (int i = 0; i < pendingRemoval.size(); i++) {
                spawnParticles(pendingRemoval.get(i));
            }
            pendingRemoval = null;
        }
        // Every surviving glyph sits at a new offset once text is inserted or removed, and the span
        // is what actually keeps its position, so rebuild the index from where the span ended up.
        reindexGlyphs();
        if (lengthAfter > 0) {
            addGlyphs(text, start, lengthAfter);
        }
        view.invalidate();
    }

    private void reindexGlyphs() {
        if (!(view.getText() instanceof Spannable)) {
            glyphs.clear();
            return;
        }
        Spannable spannable = (Spannable) view.getText();
        for (int i = glyphs.size() - 1; i >= 0; i--) {
            Glyph g = glyphs.get(i);
            int at = g.span == null ? -1 : spannable.getSpanStart(g.span);
            if (at < 0 || at >= spannable.length() || spannable.charAt(at) != g.c) {
                if (g.span != null) {
                    spannable.removeSpan(g.span);
                }
                glyphs.remove(i);
            } else {
                g.index = at;
            }
        }
    }

    private void addGlyphs(CharSequence text, int start, int count) {
        if (!(view.getText() instanceof Spannable) || text == null) {
            return;
        }
        Spannable spannable = (Spannable) view.getText();
        int end = Math.min(start + count, spannable.length());
        // Pasting a wall of text should not turn into hundreds of independent animations.
        if (end - start > MAX_GLYPHS) {
            return;
        }
        boolean skipSpaces = NekoConfig.textAnimationIgnoreSpaces.Bool();
        long now = System.currentTimeMillis();
        int stagger = Math.min(24, duration() / Math.max(1, (end - start) * 2));
        int added = 0;
        for (int i = start; i < end; i++) {
            char c = spannable.charAt(i);
            if (skipSpaces && Character.isWhitespace(c)) {
                continue;
            }
            // An emoji or any other replacement draws itself; transforming it here would double it.
            if (spannable.getSpans(i, i + 1, ReplacementSpan.class).length > 0) {
                continue;
            }
            Glyph g = new Glyph();
            g.c = c;
            g.index = i;
            g.start = now + (long) added * stagger;
            g.span = new HiddenSpan();
            try {
                spannable.setSpan(g.span, i, i + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            } catch (Exception ignore) {
                continue;
            }
            glyphs.add(g);
            added++;
        }
        if (glyphs.size() > MAX_GLYPHS) {
            int excess = glyphs.size() - MAX_GLYPHS;
            for (int i = 0; i < excess; i++) {
                release(glyphs.remove(0));
            }
        }
    }

    private void release(Glyph g) {
        if (g.span != null && view.getText() instanceof Spannable) {
            ((Spannable) view.getText()).removeSpan(g.span);
        }
    }

    private void spawnParticles(Removed removed) {
        int count = NekoConfig.textAnimationParticleCount.Int();
        int style = NekoConfig.textAnimationParticleStyle.Int();
        float speed = NekoConfig.textAnimationParticleSpeed.Int() / 50f;
        float spread = NekoConfig.textAnimationParticleSpread.Int() / 50f;
        float size = NekoConfig.textAnimationParticleSize.Int() / 50f;
        for (int i = 0; i < count && particles.size() < MAX_PARTICLES; i++) {
            Particle p = new Particle();
            p.c = removed.c;
            p.x = removed.x;
            p.y = removed.y;
            double angle = random.nextDouble() * Math.PI * 2;
            float velocity = dp(18) * speed * (0.4f + random.nextFloat());
            p.vx = (float) Math.cos(angle) * velocity * spread;
            p.vy = (float) Math.sin(angle) * velocity * spread - dp(10) * speed;
            p.rotation = random.nextFloat() * 360f;
            p.spin = (random.nextFloat() - 0.5f) * 360f;
            p.maxLife = 380f + random.nextFloat() * 260f;
            p.life = p.maxLife;
            switch (style) {
                case PARTICLE_SPARKS -> p.size = dp(1.4f) * size;
                case PARTICLE_SNOW -> p.size = dp(2.6f) * size;
                case PARTICLE_PETALS -> p.size = dp(3.2f) * size;
                case PARTICLE_LETTERS -> p.size = view.getPaint().getTextSize() * 0.7f * size;
                default -> p.size = dp(1.8f) * size;
            }
            particles.add(p);
        }
    }

    /** Called after the normal text pass; the hidden characters are painted here instead. */
    public void draw(Canvas canvas) {
        if (glyphs.isEmpty() && particles.isEmpty()) {
            lastFrame = 0;
            return;
        }
        long now = System.currentTimeMillis();
        long dt = lastFrame == 0 ? 16 : Math.min(48, now - lastFrame);
        lastFrame = now;

        drawGlyphs(canvas, now);
        drawParticles(canvas, dt);

        if (!glyphs.isEmpty() || !particles.isEmpty()) {
            view.invalidate();
        } else {
            lastFrame = 0;
        }
    }

    private void drawGlyphs(Canvas canvas, long now) {
        if (glyphs.isEmpty()) {
            return;
        }
        Layout layout = view.getLayout();
        if (layout == null) {
            return;
        }
        CharSequence text = view.getText();
        int total = duration();
        boolean blur = NekoConfig.textAnimationBlur.Bool();
        int blurDuration = Math.max(80, NekoConfig.textAnimationBlurDuration.Int());
        float blurRadius = NekoConfig.textAnimationBlurRadius.Int();
        float textDelay = NekoConfig.textAnimationBlurTextDelay.Int() / 100f;
        boolean slide = NekoConfig.textAnimationSlide.Bool();
        float slideDist = dp(NekoConfig.textAnimationSlideDistance.Int());
        boolean scale = NekoConfig.textAnimationScale.Bool();
        float scaleStart = NekoConfig.textAnimationScaleStart.Int() / 100f;
        boolean rotate = NekoConfig.textAnimationRotate.Bool();
        float rotateAngle = NekoConfig.textAnimationRotateAngle.Int();

        canvas.save();
        canvas.translate(view.getPaddingLeft(), view.getExtendedPaddingTop());

        for (int i = glyphs.size() - 1; i >= 0; i--) {
            Glyph g = glyphs.get(i);
            long elapsed = now - g.start;
            if (elapsed < 0) {
                // Still staggered out; hold it invisible so it does not pop in early.
                continue;
            }
            long span = blur ? Math.max(total, blurDuration) : total;
            if (elapsed >= span) {
                release(g);
                glyphs.remove(i);
                continue;
            }
            if (g.index < 0 || text == null || g.index >= text.length()) {
                release(g);
                glyphs.remove(i);
                continue;
            }

            float progress = INTERPOLATOR.getInterpolation(Math.min(1f, elapsed / (float) total));
            float x, baseline;
            try {
                int line = layout.getLineForOffset(g.index);
                x = layout.getPrimaryHorizontal(g.index);
                baseline = layout.getLineBaseline(line);
            } catch (Exception ignore) {
                release(g);
                glyphs.remove(i);
                continue;
            }

            paint.set(view.getPaint());
            paint.setColor(view.getCurrentTextColor());
            float alphaProgress = textDelay <= 0f ? progress
                    : INTERPOLATOR.getInterpolation(Math.max(0f, Math.min(1f,
                            (elapsed / (float) total - textDelay) / Math.max(0.05f, 1f - textDelay))));
            paint.setAlpha((int) (alphaProgress * 255));
            if (blur && blurRadius > 0) {
                float blurProgress = Math.min(1f, elapsed / (float) blurDuration);
                float radius = dp(blurRadius) * (1f - blurProgress);
                paint.setMaskFilter(radius > 0.4f ? new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL) : null);
            } else {
                paint.setMaskFilter(null);
            }

            canvas.save();
            if (slide) {
                canvas.translate(0, slideDist * (1f - progress));
            }
            if (rotate || scale) {
                canvas.rotate(rotate ? rotateAngle * (1f - progress) : 0f, x, baseline);
                if (scale) {
                    float s = scaleStart + (1f - scaleStart) * progress;
                    canvas.scale(s, s, x, baseline);
                }
            }
            one[0] = g.c;
            canvas.drawText(one, 0, 1, x, baseline, paint);
            canvas.restore();
        }
        paint.setMaskFilter(null);
        canvas.restore();
    }

    private void drawParticles(Canvas canvas, long dt) {
        if (particles.isEmpty()) {
            return;
        }
        int style = NekoConfig.textAnimationParticleStyle.Int();
        float seconds = dt / 1000f;

        canvas.save();
        canvas.translate(view.getPaddingLeft(), view.getExtendedPaddingTop());
        for (int i = particles.size() - 1; i >= 0; i--) {
            Particle p = particles.get(i);
            p.life -= dt;
            if (p.life <= 0) {
                particles.remove(i);
                continue;
            }
            p.x += p.vx * seconds;
            p.y += p.vy * seconds;
            // Snowflakes and petals drift; dust, sparks and letters simply fall away.
            if (style == PARTICLE_SNOW || style == PARTICLE_PETALS) {
                p.vy += dp(14) * seconds;
                p.vx += (float) Math.sin(p.life / 90f) * dp(6) * seconds;
            } else {
                p.vy += dp(60) * seconds;
            }
            p.rotation += p.spin * seconds;

            paint.set(view.getPaint());
            paint.setMaskFilter(null);
            paint.setColor(view.getCurrentTextColor());
            paint.setAlpha((int) (Math.min(1f, p.life / p.maxLife) * 255));

            canvas.save();
            canvas.rotate(p.rotation, p.x, p.y);
            if (style == PARTICLE_LETTERS) {
                paint.setTextSize(Math.max(1f, p.size));
                one[0] = p.c;
                canvas.drawText(one, 0, 1, p.x, p.y, paint);
            } else if (style == PARTICLE_SPARKS) {
                canvas.drawRect(p.x - p.size, p.y - p.size * 0.35f, p.x + p.size, p.y + p.size * 0.35f, paint);
            } else if (style == PARTICLE_PETALS) {
                canvas.drawOval(p.x - p.size, p.y - p.size * 0.5f, p.x + p.size, p.y + p.size * 0.5f, paint);
            } else {
                canvas.drawCircle(p.x, p.y, p.size, paint);
            }
            canvas.restore();
        }
        canvas.restore();
    }

    /**
     * Eases the caret towards where the selection actually is and reports how far it still has to
     * travel, which is what the liquid variant stretches along.
     *
     * @return the x the caret should be drawn at.
     */
    public float animateCursor(float targetX, long dt) {
        if (!NekoConfig.textAnimationCursor.Bool()) {
            cursorX = targetX;
            cursorStretch = 0f;
            return targetX;
        }
        if (cursorX < 0f || Math.abs(targetX - cursorX) > dp(400)) {
            // First draw, or a jump so large that easing would look like a slide across the field.
            cursorX = targetX;
            cursorStretch = 0f;
            return cursorX;
        }
        cursorTargetX = targetX;
        float speed = Math.max(1, NekoConfig.textAnimationCursorSpeed.Int()) / 25f;
        float step = Math.min(1f, dt / 1000f * 12f * speed);
        float delta = cursorTargetX - cursorX;
        cursorX += delta * step;
        if (Math.abs(cursorTargetX - cursorX) < 0.5f) {
            cursorX = cursorTargetX;
        }
        cursorStretch = delta;
        if (Math.abs(cursorTargetX - cursorX) > 0.5f) {
            view.invalidate();
        }
        return cursorX;
    }

    /**
     * Extra width the caret gains while it is catching up, in pixels. The sign is the direction of
     * travel, so the caller knows which edge to stretch: positive means the caret is heading right
     * and its left edge trails behind.
     */
    public float getCursorStretch(boolean selecting) {
        int strength = selecting
                ? (NekoConfig.textAnimationSelectionEffect.Int() == 0 ? 0 : NekoConfig.textAnimationSelectionStretch.Int())
                : (NekoConfig.textAnimationLiquidCursor.Bool() ? NekoConfig.textAnimationLiquidScale.Int() : 0);
        if (strength <= 0) {
            return 0f;
        }
        float travel = Math.min(dp(60), Math.abs(cursorStretch));
        return Math.signum(cursorStretch) * travel * (strength / 100f);
    }

    public float getSelectionSideStretch() {
        if (NekoConfig.textAnimationSelectionEffect.Int() == 0) {
            return 0f;
        }
        return NekoConfig.textAnimationSelectionSide.Int() / 100f;
    }

    public int getCursorWidth(float fallback) {
        if (!NekoConfig.textAnimationCursor.Bool()) {
            return AndroidUtilities.dp(fallback);
        }
        return AndroidUtilities.dp(Math.max(1, NekoConfig.textAnimationCursorWidth.Int()) / 2f);
    }
}
