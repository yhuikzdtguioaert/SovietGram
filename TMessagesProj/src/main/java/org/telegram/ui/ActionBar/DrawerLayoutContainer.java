/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.os.Build;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.DisplayCutoutCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;

public class DrawerLayoutContainer extends FrameLayout {

    /** Bare minimum of content kept visible to the right of a fully open drawer. */
    private static final int MIN_DRAWER_MARGIN = 64;

    private INavigationLayout parentActionBarLayout;

    private boolean hasCutout;

    private boolean inLayout;

    private boolean firstLayout = true;

    private boolean keyboardVisibility;
    private int imeHeight;

    // --- Side drawer (12.3.1 shell only; null in the tab-bar shell) ---------------

    private View drawerLayout;
    private final int minDrawerMargin;
    private float drawerPosition;
    private boolean drawerOpened;
    private boolean allowOpenDrawer;
    private boolean allowOpenDrawerBySwipe = true;
    private AnimatorSet currentAnimation;

    private boolean maybeStartTracking;
    private boolean startedTracking;
    private boolean beginTrackingSent;
    private int startedTrackingX;
    private int startedTrackingY;
    private int startedTrackingPointerId;
    private VelocityTracker velocityTracker;

    private final Rect rect = new Rect();
    private final Paint scrimPaint = new Paint();
    private float scrimOpacity;
    /**
     * Upstream used R.drawable.menu_shadow for the drawer's trailing edge; that asset
     * went away with the rest of the drawer, so the same falloff is generated here.
     */
    private final Paint edgeShadowPaint = new Paint();
    private int edgeShadowWidth;

    /** @noinspection deprecation*/
    public DrawerLayoutContainer(Context context) {
        super(context);

        minDrawerMargin = (int) (MIN_DRAWER_MARGIN * AndroidUtilities.density + 0.5f);
        edgeShadowWidth = AndroidUtilities.dp(6);
        edgeShadowPaint.setShader(new LinearGradient(0, 0, edgeShadowWidth, 0,
                0x2A000000, 0x00000000, Shader.TileMode.CLAMP));
        setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        setFocusableInTouchMode(true);

        ViewCompat.setOnApplyWindowInsetsListener(this, (v, insets) -> {
            if (Build.VERSION.SDK_INT >= 30) {
                boolean newKeyboardVisibility = insets.isVisible(WindowInsetsCompat.Type.ime());
                int imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
                if (keyboardVisibility != newKeyboardVisibility || this.imeHeight != imeHeight) {
                    keyboardVisibility = newKeyboardVisibility;
                    this.imeHeight = imeHeight;
                    requestLayout();
                }
            }
            final DrawerLayoutContainer drawerLayoutContainer = (DrawerLayoutContainer) v;
            if (AndroidUtilities.statusBarHeight != insets.getSystemWindowInsetTop()) {
                drawerLayoutContainer.requestLayout();
            }
            int newTopInset = insets.getSystemWindowInsetTop();
            if ((newTopInset != 0 || AndroidUtilities.isInMultiwindow || firstLayout) && AndroidUtilities.statusBarHeight != newTopInset) {
                AndroidUtilities.statusBarHeight = newTopInset;
            }
            firstLayout = false;
            drawerLayoutContainer.setWillNotDraw(insets.getSystemWindowInsetTop() <= 0 && getBackground() == null);

            if (Build.VERSION.SDK_INT >= 28) {
                DisplayCutoutCompat cutout = insets.getDisplayCutout();
                hasCutout = cutout != null && !cutout.getBoundingRects().isEmpty();
            }
            invalidate();

            return onApplyWindowInsets(v, insets);
        });
        setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    public void setParentActionBarLayout(INavigationLayout layout) {
        parentActionBarLayout = layout;
    }

    public boolean isDrawCurrentPreviewFragmentAbove() {
        return false;
    }

    /**
     * Installs the side panel. Passing null (the tab-bar shell) leaves every drawer
     * code path inert, so the container behaves exactly like the plain FrameLayout
     * it was before.
     */
    public void setDrawerLayout(View layout) {
        if (drawerLayout != null) {
            removeView(drawerLayout);
        }
        drawerLayout = layout;
        if (layout != null) {
            // Tagged so dispatchApplyWindowInsetsInternal leaves its margins alone — the
            // panel pads itself from AndroidUtilities.statusBarHeight instead, otherwise
            // the cutout inset would slide it out of its off-screen parking position.
            layout.setTag("drawer");
            addView(layout);
            layout.setVisibility(INVISIBLE);
        }
    }

    public boolean hasDrawer() {
        return drawerLayout != null;
    }

    @Keep
    public void setDrawerPosition(float value) {
        if (drawerLayout == null) {
            return;
        }
        drawerPosition = Math.max(0, Math.min(value, drawerLayout.getMeasuredWidth()));
        drawerLayout.setTranslationX(drawerPosition);

        final int newVisibility = drawerPosition > 0 ? VISIBLE : INVISIBLE;
        if (drawerLayout.getVisibility() != newVisibility) {
            drawerLayout.setVisibility(newVisibility);
        }
        scrimOpacity = drawerLayout.getMeasuredWidth() == 0
                ? 0 : drawerPosition / drawerLayout.getMeasuredWidth();
        invalidate();
    }

    @Keep
    public float getDrawerPosition() {
        return drawerPosition;
    }

    public void cancelCurrentAnimation() {
        if (currentAnimation != null) {
            currentAnimation.cancel();
            currentAnimation = null;
        }
    }

    public void openDrawer(boolean fast) {
        if (!allowOpenDrawer || drawerLayout == null) {
            return;
        }
        if (AndroidUtilities.isTablet() && parentActionBarLayout != null && parentActionBarLayout.getParentActivity() != null) {
            AndroidUtilities.hideKeyboard(parentActionBarLayout.getParentActivity().getCurrentFocus());
        }
        cancelCurrentAnimation();
        animateDrawerTo(drawerLayout.getMeasuredWidth(), fast, true);
    }

    public void closeDrawer(boolean fast) {
        if (drawerLayout == null) {
            return;
        }
        cancelCurrentAnimation();
        animateDrawerTo(0, fast, false);
    }

    public void closeDrawer() {
        if (drawerPosition != 0) {
            setDrawerPosition(0);
            onDrawerAnimationEnd(false);
        }
    }

    /**
     * Lets the drawer's own cells push a screen without reaching back into LaunchActivity —
     * DrawerProfileCell's theme toggle relies on this.
     */
    public void presentFragment(BaseFragment fragment) {
        if (parentActionBarLayout != null) {
            parentActionBarLayout.presentFragment(fragment);
        }
    }

    private void animateDrawerTo(float target, boolean fast, boolean opened) {
        final float distance = Math.abs(target - drawerPosition);
        final int width = Math.max(1, drawerLayout.getMeasuredWidth());
        AnimatorSet animatorSet = new AnimatorSet();
        animatorSet.playTogether(ObjectAnimator.ofFloat(this, "drawerPosition", target));
        animatorSet.setInterpolator(new DecelerateInterpolator());
        animatorSet.setDuration(fast ? Math.max((int) (200.0f / width * distance), 50) : 250);
        animatorSet.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animator) {
                onDrawerAnimationEnd(opened);
            }
        });
        animatorSet.start();
        currentAnimation = animatorSet;
    }

    private void onDrawerAnimationEnd(boolean opened) {
        startedTracking = false;
        currentAnimation = null;
        drawerOpened = opened;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != drawerLayout) {
                child.setImportantForAccessibility(opened
                        ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                        : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
    }

    public void setAllowOpenDrawer(boolean value, boolean animated) {
        allowOpenDrawer = value;
        if (!allowOpenDrawer && drawerPosition != 0) {
            if (!animated) {
                setDrawerPosition(0);
                onDrawerAnimationEnd(false);
            } else {
                closeDrawer(true);
            }
        }
    }

    public boolean isAllowOpenDrawer() {
        return allowOpenDrawer;
    }

    public void setAllowOpenDrawerBySwipe(boolean value) {
        allowOpenDrawerBySwipe = value;
    }

    public boolean isDrawerOpened() {
        return drawerOpened;
    }

    private void prepareForDrawerOpen(MotionEvent ev) {
        maybeStartTracking = false;
        startedTracking = true;
        if (ev != null) {
            startedTrackingX = (int) ev.getX();
        }
        beginTrackingSent = false;
    }

    public boolean onTouchEvent(MotionEvent ev) {
        if (drawerLayout == null || parentActionBarLayout == null || parentActionBarLayout.checkTransitionAnimation()) {
            return false;
        }
        if (drawerOpened && ev != null && ev.getX() > drawerPosition && !startedTracking) {
            if (ev.getAction() == MotionEvent.ACTION_UP) {
                closeDrawer(false);
            }
            return true;
        }

        final boolean trackable = (allowOpenDrawerBySwipe || drawerOpened)
                && allowOpenDrawer
                && parentActionBarLayout.getFragmentStack().size() == 1
                && parentActionBarLayout.allowSwipe()
                && (parentActionBarLayout.getLastFragment() == null
                    || parentActionBarLayout.getLastFragment().getLastSheet() == null
                    || !parentActionBarLayout.getLastFragment().getLastSheet().attachedToParent());

        if (trackable) {
            if (ev != null && (ev.getAction() == MotionEvent.ACTION_DOWN || ev.getAction() == MotionEvent.ACTION_MOVE) && !startedTracking && !maybeStartTracking) {
                if (findScrollingChild(this, ev.getX(), ev.getY()) != null) {
                    return false;
                }
                parentActionBarLayout.getView().getHitRect(rect);
                startedTrackingX = (int) ev.getX();
                startedTrackingY = (int) ev.getY();
                if (rect.contains(startedTrackingX, startedTrackingY)) {
                    startedTrackingPointerId = ev.getPointerId(0);
                    maybeStartTracking = true;
                    cancelCurrentAnimation();
                    if (velocityTracker != null) {
                        velocityTracker.clear();
                    }
                }
            } else if (ev != null && ev.getAction() == MotionEvent.ACTION_MOVE && ev.getPointerId(0) == startedTrackingPointerId) {
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain();
                }
                float dx = (int) (ev.getX() - startedTrackingX);
                float dy = Math.abs((int) ev.getY() - startedTrackingY);
                velocityTracker.addMovement(ev);
                if (maybeStartTracking && !startedTracking
                        && (dx > 0 && dx / 3.0f > Math.abs(dy) && Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.2f, true)
                            || drawerOpened && dx < 0 && Math.abs(dx) >= Math.abs(dy) && Math.abs(dx) >= AndroidUtilities.getPixelsInCM(0.4f, true))) {
                    prepareForDrawerOpen(ev);
                    startedTrackingX = (int) ev.getX();
                    requestDisallowInterceptTouchEvent(true);
                } else if (startedTracking) {
                    if (!beginTrackingSent) {
                        if (getContext() instanceof Activity && ((Activity) getContext()).getCurrentFocus() != null) {
                            AndroidUtilities.hideKeyboard(((Activity) getContext()).getCurrentFocus());
                        }
                        beginTrackingSent = true;
                    }
                    setDrawerPosition(drawerPosition + dx);
                    startedTrackingX = (int) ev.getX();
                }
            } else if (ev == null || ev.getPointerId(0) == startedTrackingPointerId
                    && (ev.getAction() == MotionEvent.ACTION_CANCEL || ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_POINTER_UP)) {
                if (velocityTracker == null) {
                    velocityTracker = VelocityTracker.obtain();
                }
                velocityTracker.computeCurrentVelocity(1000);
                if (startedTracking || drawerPosition != 0 && drawerPosition != drawerLayout.getMeasuredWidth()) {
                    float velX = velocityTracker.getXVelocity();
                    float velY = velocityTracker.getYVelocity();
                    boolean backAnimation = drawerPosition < drawerLayout.getMeasuredWidth() / 2.0f && (velX < 3500 || Math.abs(velX) < Math.abs(velY))
                            || velX < 0 && Math.abs(velX) >= 3500;
                    if (!backAnimation) {
                        openDrawer(!drawerOpened && Math.abs(velX) >= 3500);
                    } else {
                        closeDrawer(drawerOpened && Math.abs(velX) >= 3500);
                    }
                }
                startedTracking = false;
                maybeStartTracking = false;
                if (velocityTracker != null) {
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
            }
        } else if (ev == null || ev.getPointerId(0) == startedTrackingPointerId
                && (ev.getAction() == MotionEvent.ACTION_CANCEL || ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_POINTER_UP)) {
            startedTracking = false;
            maybeStartTracking = false;
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
        }
        return startedTracking;
    }

    private View findScrollingChild(ViewGroup parent, float x, float y) {
        int n = parent.getChildCount();
        for (int i = 0; i < n; i++) {
            View child = parent.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            child.getHitRect(rect);
            if (rect.contains((int) x, (int) y)) {
                if (child.canScrollHorizontally(-1)) {
                    return child;
                } else if (child instanceof ViewGroup) {
                    View v = findScrollingChild((ViewGroup) child, x - rect.left, y - rect.top);
                    if (v != null) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return parentActionBarLayout.checkTransitionAnimation() || onTouchEvent(ev);
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        if (maybeStartTracking && !startedTracking) {
            onTouchEvent(null);
        }
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean onRequestSendAccessibilityEvent(View child, AccessibilityEvent event) {
        if (drawerOpened && child != drawerLayout) {
            return false;
        }
        return super.onRequestSendAccessibilityEvent(child, event);
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        if (drawerLayout == null) {
            return super.drawChild(canvas, child, drawingTime);
        }
        final boolean drawingContent = child != drawerLayout;
        int lastVisibleChild = 0;
        final int clipRight = getWidth();
        int clipLeft = 0;

        final int restoreCount = canvas.save();
        if (drawingContent) {
            final int childCount = getChildCount();
            for (int i = 0; i < childCount; i++) {
                final View v = getChildAt(i);
                if (v.getVisibility() == VISIBLE && v != drawerLayout) {
                    lastVisibleChild = i;
                }
                if (v == child || v.getVisibility() != VISIBLE || v != drawerLayout || v.getHeight() < getHeight()) {
                    continue;
                }
                final int vright = (int) Math.ceil(v.getX()) + v.getMeasuredWidth();
                if (vright > clipLeft) {
                    clipLeft = vright;
                }
            }
            if (clipLeft != 0) {
                canvas.clipRect(clipLeft - AndroidUtilities.dp(1), 0, clipRight, getHeight());
            }
        }
        final boolean result = super.drawChild(canvas, child, drawingTime);
        canvas.restoreToCount(restoreCount);

        if (scrimOpacity > 0 && drawingContent) {
            if (indexOfChild(child) == lastVisibleChild) {
                scrimPaint.setColor((int) (0x99 * scrimOpacity) << 24);
                canvas.drawRect(clipLeft, 0, clipRight, getHeight(), scrimPaint);
            }
        } else if (drawerPosition > 0 && drawingContent) {
            final float alpha = Math.min(drawerPosition / AndroidUtilities.dp(20), 1.0f);
            if (alpha > 0) {
                canvas.save();
                canvas.translate(drawerPosition, 0);
                edgeShadowPaint.setAlpha((int) (0xff * alpha));
                canvas.drawRect(0, child.getTop(), edgeShadowWidth, child.getBottom(), edgeShadowPaint);
                canvas.restore();
            }
        }
        return result;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        inLayout = true;
        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            if (child.getVisibility() == GONE) {
                continue;
            }

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();
            try {
                if (child == drawerLayout) {
                    // Parked entirely off-screen to the left; setDrawerPosition slides it in.
                    child.layout(-child.getMeasuredWidth(), lp.topMargin + getPaddingTop(), 0, lp.topMargin + child.getMeasuredHeight() + getPaddingTop());
                } else {
                    child.layout(lp.leftMargin, lp.topMargin + getPaddingTop(), lp.leftMargin + child.getMeasuredWidth(), lp.topMargin + child.getMeasuredHeight() + getPaddingTop());
                }
            } catch (Exception e) {
                FileLog.e(e);
                if (BuildVars.DEBUG_VERSION) {
                    throw e;
                }
            }
        }
        inLayout = false;
    }

    @Override
    public void requestLayout() {
        if (!inLayout) {
            super.requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (!BuildVars.USE_LEGACY_SYSTEM_INSETS) {
            final WindowInsetsCompat insetsCompat = ViewCompat.getRootWindowInsets(this);
            if (insetsCompat != null) {
                final Insets systemInsets = insetsCompat.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.systemBars());

                AndroidUtilities.statusBarHeight = systemInsets.top;
                AndroidUtilities.navigationBarHeight = systemInsets.bottom;
            }
        }

        int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        setMeasuredDimension(widthSize, heightSize);
        final int newSize = heightSize
            - AndroidUtilities.statusBarHeight
            - AndroidUtilities.navigationBarHeight;

        if (newSize > 0 && newSize < 4096) {
            AndroidUtilities.displaySize.y = newSize;
        }

        final int childCount = getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = getChildAt(i);

            if (child.getVisibility() == GONE) {
                continue;
            }

            final LayoutParams lp = (LayoutParams) child.getLayoutParams();

            if (child == drawerLayout) {
                child.setPadding(0, 0, 0, 0);
                child.measure(
                        getChildMeasureSpec(widthMeasureSpec, minDrawerMargin + lp.leftMargin + lp.rightMargin, lp.width),
                        getChildMeasureSpec(heightMeasureSpec, lp.topMargin + lp.bottomMargin, lp.height));
                continue;
            }

            final int contentWidthSpec = MeasureSpec.makeMeasureSpec(widthSize - lp.leftMargin - lp.rightMargin, MeasureSpec.EXACTLY);
            final int contentHeightSpec;
            if (lp.height > 0) {
                contentHeightSpec = MeasureSpec.makeMeasureSpec(lp.height, MeasureSpec.EXACTLY);
            } else {
                contentHeightSpec = MeasureSpec.makeMeasureSpec(heightSize - lp.topMargin - lp.bottomMargin, MeasureSpec.EXACTLY);
            }
            if (child instanceof ActionBarLayout) {
                ActionBarLayout actionBarLayout = (ActionBarLayout) child;
                //fix keyboard measuring
                if (actionBarLayout.storyViewerAttached()) {
                    child.forceLayout();
                }
            }
            child.measure(contentWidthSpec, contentHeightSpec);
        }
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        if (lastWindowInsetsCompat == null) {
            return;
        }

        final Insets insets = lastWindowInsetsCompat.getInsets(WindowInsetsCompat.Type.ime()
            | WindowInsetsCompat.Type.systemBars()
            | WindowInsetsCompat.Type.displayCutout());

        if (insets.bottom > 0) {
            canvas.drawRect(
                0,
                getMeasuredHeight() - insets.bottom,
                getMeasuredWidth(),
                getMeasuredHeight(),
                internalNavbarPaint
            );
        }

        if (hasCutout) {
            final int left = insets.left;
            if (left != 0) {
                canvas.drawRect(0, 0, left, getMeasuredHeight(), Theme.fillingPaint(Color.BLACK));
            }
            final int right = insets.right;
            if (right != 0) {
                canvas.drawRect(right, 0, getMeasuredWidth(), getMeasuredHeight(), Theme.fillingPaint(Color.BLACK));
            }
        }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private final Paint internalNavbarPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Paint getInternalNavbarPaint() {
        return internalNavbarPaint;
    }

    public void setInternalNavigationBarColor(int color) {
        if (internalNavbarPaint.getColor() != color) {
            internalNavbarPaint.setColor(color);
            invalidate();

            for (int a = 0, N = getChildCount(); a < N; a++) {
                getChildAt(a).invalidate();
            }
        }
    }

    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        super.addView(child, index, params);
        if (lastWindowInsetsCompat != null) {
            dispatchApplyWindowInsetsInternal(child, lastWindowInsetsCompat);
        }
    }

    private @Nullable WindowInsetsCompat lastWindowInsetsCompat;

    private void dispatchApplyWindowInsetsInternal(View child, WindowInsetsCompat insets) {
        boolean canApplyInsets = child instanceof ActionBarLayout || child.getTag() == null;
        if (!canApplyInsets) {
            return;
        }

        final MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
        final Insets systemInsetsWithIme = insets.getInsets(WindowInsetsCompat.Type.ime()
                | WindowInsetsCompat.Type.systemBars()
                | WindowInsetsCompat.Type.displayCutout());

        final boolean changed = lp.topMargin != 0 || lp.bottomMargin != 0
                || lp.leftMargin != systemInsetsWithIme.left
                || lp.rightMargin != systemInsetsWithIme.right;

        if (changed) {
            lp.leftMargin = systemInsetsWithIme.left;
            lp.topMargin = 0;
            lp.rightMargin = systemInsetsWithIme.right;
            lp.bottomMargin = 0;

            child.requestLayout();
        }

        final WindowInsetsCompat consumed = insets.inset(
                lp.leftMargin, lp.topMargin,
                lp.rightMargin, lp.bottomMargin);

        ViewCompat.dispatchApplyWindowInsets(child, consumed);
    }

    @NonNull
    private WindowInsetsCompat onApplyWindowInsets(@NonNull View ignoredV, @NonNull WindowInsetsCompat insets) {
        lastWindowInsetsCompat = insets;

        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            dispatchApplyWindowInsetsInternal(child, insets);
        }

        invalidate();
        return WindowInsetsCompat.CONSUMED;
    }
}
