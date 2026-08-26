package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

import me.vkryl.android.animator.ListAnimator;

import sovietgram.com.NaConfig;

public class ChatActivityTopPanelLayout extends AnimatedLinearLayout {
    public ChatActivityTopPanelLayout(@NonNull Context context) {
        super(context);

        setOrientation(LinearLayout.VERTICAL);
        flatPanel = NaConfig.isLegacyChatHeader();
        updateColors();
    }

    /**
     * Pre-12.2.0 look: the pinned-message / report-spam stack is a full-width opaque strip
     * glued under the action bar instead of a rounded floating card. Only the corner radius,
     * the side bleed and the background fill change — heights and the animator-driven
     * layout are untouched, so ChatActivity's offsets keep matching.
     */
    private final boolean flatPanel;

    BlurredBackgroundDrawable backgroundDrawable;
    private final android.graphics.Paint flatBackgroundPaint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

    public void setBlurredBackground(BlurredBackgroundDrawable background) {
        backgroundDrawable = background;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkBoundsAndClipping();
    }

    @Override
    protected void onItemsChanged() {
        super.onItemsChanged();
        checkBoundsAndClipping();
        invalidate();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        checkBoundsAndClipping();
        invalidate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        return super.dispatchTouchEvent(ev)
                || ev.getAction() == MotionEvent.ACTION_DOWN && backgroundDrawable != null && backgroundDrawable.getBounds().contains((int) ev.getX(), (int) ev.getY());
    }

    private final Path clipPath = new Path();
    private final RectF clipRectF = new RectF();

    private void checkBoundsAndClipping() {
        final float bgHeight = getMetadata().getTotalHeight();
        final float bgAlpha = getMetadata().getTotalVisibility();

        if (flatPanel) {
            // Full bleed: the panel is drawn edge to edge, ignoring the 7dp gutter that
            // ChatActivity keeps applying as padding for the floating card.
            clipRectF.set(0, 0, getMeasuredWidth(), getPaddingTop() + bgHeight);
        } else {
            clipRectF.set(getPaddingLeft(), getPaddingTop(), getMeasuredWidth() - getPaddingRight(), getPaddingTop() + bgHeight);
        }

        final float r = flatPanel ? 0 : Math.min(dp(18), Math.min(clipRectF.width(), clipRectF.height()) / 2f);
        clipPath.rewind();
        clipPath.addRoundRect(clipRectF, r, r, Path.Direction.CW);

        if (flatPanel) {
            return;
        }

        if (backgroundDrawable != null) {
            backgroundDrawable.setAlpha((int) (bgAlpha * 255));
            backgroundDrawable.setBounds(getPaddingLeft() - dp(7), 0, getMeasuredWidth() - getPaddingRight() + dp(7), getPaddingTop() + getPaddingBottom() + (int) bgHeight);
            backgroundDrawable.setRadius(Math.min(dp(18), bgHeight / 2));
        }
    }

    public void updateColors() {
        if (backgroundDrawable != null) {
            backgroundDrawable.updateColors();
        }

        flatBackgroundPaint.setColor(Theme.getColor(Theme.key_chat_topPanelBackground));

        invalidate();
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        final float totalVisibility = getMetadata().getTotalVisibility();
        if (totalVisibility == 0) return;

        if (flatPanel) {
            final int wasAlpha = flatBackgroundPaint.getAlpha();
            flatBackgroundPaint.setAlpha((int) (wasAlpha * totalVisibility));
            canvas.drawRect(clipRectF, flatBackgroundPaint);
            flatBackgroundPaint.setAlpha(wasAlpha);
        } else if (backgroundDrawable != null) {
            backgroundDrawable.draw(canvas);
        }

        canvas.save();
        canvas.clipPath(clipPath);
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<?> entry = getEntry(a);
            final float top = getPaddingTop() + entry.getRectF().top;

            final float position = entry.getPosition();
            final float alpha = entry.getVisibility() * Math.min(1, position);

            if (alpha <= 0) {
                continue;
            }

            final int wasAlpha = Theme.dividerPaint.getAlpha();
            Theme.dividerPaint.setAlpha((int) (wasAlpha * alpha));
            final float offsetL = getPaddingLeft() + dp(16) * (1f - alpha);
            final float offsetR = getPaddingRight() + dp(16) * (1f - alpha);
            canvas.drawLine(offsetL, top, getWidth() - offsetR, top, Theme.dividerPaint);
            Theme.dividerPaint.setAlpha(wasAlpha);
        }

        super.dispatchDraw(canvas);
        canvas.restore();
    }
}
