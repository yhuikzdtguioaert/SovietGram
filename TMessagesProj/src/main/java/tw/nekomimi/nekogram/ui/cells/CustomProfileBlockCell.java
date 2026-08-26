package tw.nekomimi.nekogram.ui.cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.LayoutHelper;

import tw.nekomimi.nekogram.helpers.CustomProfileExtraRows;

/**
 * One row a look invented for itself: a link, a button, a heading, a line of text, a picture or a
 * divider.
 *
 * <p>One view for all six rather than six view types, because they differ only in which of the same
 * three parts they show — a title, a value and a picture — and how they are painted. That keeps the
 * profile's adapter to a single extra type, which matters in a list that already has thirty.
 *
 * <p>Colours come from the block when it names them and from the theme when it does not, which is
 * how a look can leave its rows agreeing with the user's theme while colouring only the one row it
 * cares about.
 */
public class CustomProfileBlockCell extends FrameLayout {

    private final TextView titleView;
    private final TextView valueView;
    private final BackupImageView imageView;
    private final Paint buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF buttonRect = new RectF();

    @Nullable
    private CustomProfileExtraRows.Block block;
    /** Whether this row's picture is one this phone can actually show. */
    private boolean mediaUsable;
    private final Theme.ResourcesProvider resourcesProvider;

    public CustomProfileBlockCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setWillNotDraw(false);

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 21, 10, 21, 0));

        valueView = new TextView(context);
        valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        valueView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        addView(valueView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                Gravity.TOP | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT), 21, 32, 21, 0));

        imageView = new BackupImageView(context);
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 180,
                Gravity.TOP, 21, 8, 21, 8));
    }

    /** Puts one block on screen. Everything not part of this block's type is simply hidden. */
    public void set(CustomProfileExtraRows.Block block) {
        this.block = block;
        final int type = block.type;

        final boolean showsTitle = !block.title.isEmpty()
                && type != CustomProfileExtraRows.TYPE_DIVIDER
                && type != CustomProfileExtraRows.TYPE_MEDIA;
        final boolean showsValue = !block.text.isEmpty()
                && (type == CustomProfileExtraRows.TYPE_TEXT
                || type == CustomProfileExtraRows.TYPE_NOTE
                || type == CustomProfileExtraRows.TYPE_LINK);
        mediaUsable = usable(block.picture());
        final boolean showsMedia = type == CustomProfileExtraRows.TYPE_MEDIA && mediaUsable;

        titleView.setVisibility(showsTitle ? VISIBLE : GONE);
        valueView.setVisibility(showsValue ? VISIBLE : GONE);
        imageView.setVisibility(showsMedia ? VISIBLE : GONE);

        if (showsTitle) {
            titleView.setText(block.title);
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP,
                    type == CustomProfileExtraRows.TYPE_HEADER ? 15 : 16);
            titleView.setTypeface(type == CustomProfileExtraRows.TYPE_TEXT
                    ? null : AndroidUtilities.bold());
            titleView.setTextColor(block.titleColor != 0 ? block.titleColor : titleColour(type));
            final FrameLayout.LayoutParams params = (LayoutParams) titleView.getLayoutParams();
            params.topMargin = AndroidUtilities.dp(showsValue ? 10 : 14);
            titleView.setLayoutParams(params);
        }
        if (showsValue) {
            valueView.setText(block.text);
            valueView.setTextColor(block.valueColor != 0 ? block.valueColor
                    : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
            final FrameLayout.LayoutParams params = (LayoutParams) valueView.getLayoutParams();
            params.topMargin = AndroidUtilities.dp(showsTitle ? 32 : 12);
            valueView.setLayoutParams(params);
        }
        if (showsMedia) {
            final FrameLayout.LayoutParams params = (LayoutParams) imageView.getLayoutParams();
            params.height = AndroidUtilities.dp(block.mediaHeight);
            imageView.setLayoutParams(params);
            imageView.setRoundRadius(AndroidUtilities.dp(block.radius));
            // Both shapes a block's picture comes in: an address anybody can fetch, or a file this
            // phone already has. A path from somebody else's phone simply loads nothing, which is
            // why an address beside one is preferred over it.
            imageView.setImage(org.telegram.messenger.ImageLocation.getForPath(block.picture()),
                    "400_400", null, null, null, 0);
        }
        requestLayout();
        invalidate();
    }

    /** An address anybody can fetch, or a file this phone can open. Anything else shows nothing. */
    private static boolean usable(String picture) {
        if (picture == null || picture.isEmpty()) {
            return false;
        }
        if (CustomProfileExtraRows.fetchable(picture)) {
            return true;
        }
        try {
            return new java.io.File(picture).canRead();
        } catch (Throwable ignore) {
            return false;
        }
    }

    private int titleColour(int type) {
        if (type == CustomProfileExtraRows.TYPE_HEADER) {
            return Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider);
        }
        if (type == CustomProfileExtraRows.TYPE_LINK || type == CustomProfileExtraRows.TYPE_BUTTON) {
            return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
        }
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = AndroidUtilities.dp(8);
        if (block != null) {
            switch (block.type) {
                case CustomProfileExtraRows.TYPE_DIVIDER -> height = AndroidUtilities.dp(12);
                // A picture nobody here can load takes no room at all: a look built against a file
                // on the author's phone would otherwise leave everyone else a grey gap.
                case CustomProfileExtraRows.TYPE_MEDIA -> height = mediaUsable
                        ? AndroidUtilities.dp(block.mediaHeight + 16) : 0;
                case CustomProfileExtraRows.TYPE_BUTTON -> height = AndroidUtilities.dp(56);
                default -> {
                    height = AndroidUtilities.dp(block.title.isEmpty() ? 4 : 26);
                    if (!block.text.isEmpty()) {
                        valueView.measure(MeasureSpec.makeMeasureSpec(
                                        width - AndroidUtilities.dp(42), MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                        height += valueView.getMeasuredHeight() + AndroidUtilities.dp(
                                block.title.isEmpty() ? 20 : 14);
                    } else {
                        height += AndroidUtilities.dp(14);
                    }
                }
            }
        }
        super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (block == null) {
            return;
        }
        if (block.type == CustomProfileExtraRows.TYPE_BUTTON) {
            // Drawn rather than made of a real button: it is one rounded rectangle and a label, and
            // the label is already the title view above it.
            buttonPaint.setColor(block.iconBackground != 0 ? block.iconBackground
                    : Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider));
            buttonRect.set(AndroidUtilities.dp(21), AndroidUtilities.dp(8),
                    getMeasuredWidth() - AndroidUtilities.dp(21), getMeasuredHeight() - AndroidUtilities.dp(8));
            final float radius = AndroidUtilities.dp(Math.max(4, block.radius));
            canvas.drawRoundRect(buttonRect, radius, radius, buttonPaint);
        }
        if (block.type == CustomProfileExtraRows.TYPE_DIVIDER) {
            canvas.drawLine(AndroidUtilities.dp(21), getMeasuredHeight() / 2f,
                    getMeasuredWidth() - AndroidUtilities.dp(21), getMeasuredHeight() / 2f,
                    Theme.dividerPaint);
        }
    }

    /** The block this row is showing, so the profile can act on a tap. */
    @Nullable
    public CustomProfileExtraRows.Block getBlock() {
        return block;
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (block != null && block.type == CustomProfileExtraRows.TYPE_BUTTON) {
            // The label sits in the middle of the drawn button rather than at the top.
            final View title = titleView;
            final int y = (getMeasuredHeight() - title.getMeasuredHeight()) / 2;
            title.layout(title.getLeft(), y, title.getRight(), y + title.getMeasuredHeight());
        }
    }
}
