package tw.nekomimi.nekogram.config.cell;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.ColorPicker;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;

/**
 * A colour row: the name on the left, a swatch on the right, and the stock {@link ColorPicker} in a
 * sheet behind it.
 * <p>
 * The picker itself has no notion of transparency, so when a setting needs it the sheet grows an
 * opacity bar underneath and the two are recombined into one ARGB value. Colours are stored as
 * signed ints, alpha included, so a row without the bar simply keeps its alpha at full.
 */
public class ConfigCellColor extends AbstractConfigCell implements WithBindConfig, WithKey {

    private final ConfigItem bindConfig;
    private final CharSequence title;
    private final boolean withAlpha;
    private final int defaultColor;
    private boolean enabled = true;
    private ColorView view;

    public ConfigCellColor(ConfigItem bind, int defaultColor) {
        this(bind, defaultColor, false, null);
    }

    public ConfigCellColor(ConfigItem bind, int defaultColor, boolean withAlpha) {
        this(bind, defaultColor, withAlpha, null);
    }

    public ConfigCellColor(ConfigItem bind, int defaultColor, boolean withAlpha, CharSequence customTitle) {
        this.bindConfig = bind;
        this.title = customTitle == null ? getString(bind.getKey()) : customTitle;
        this.withAlpha = withAlpha;
        this.defaultColor = defaultColor;
    }

    @Override
    public int getType() {
        return CellGroup.ITEM_TYPE_COLOR;
    }

    @Override
    public ConfigItem getBindConfig() {
        return bindConfig;
    }

    @Override
    public String getKey() {
        return bindConfig == null ? null : bindConfig.getKey();
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (view != null) {
            view.setEnabled(enabled);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        ColorView cell = (ColorView) holder.itemView;
        this.view = cell;
        cell.bind(this);
        cell.setEnabled(enabled);
    }

    /** Opens the picker. Called by the settings screen when the row is tapped. */
    public void onClick(Context context) {
        if (!enabled) {
            return;
        }
        show(context, title, bindConfig.Int(), defaultColor, withAlpha, color -> {
            bindConfig.setConfigInt(color);
            if (view != null) {
                view.invalidate();
            }
            if (cellGroup != null) {
                cellGroup.runCallback(bindConfig.getKey(), color);
            }
        });
    }

    public interface Callback {
        void onPicked(int color);
    }
    /**
     * The sheet. Kept static so callers outside the config framework can reuse it.
     *
     * @param withAlpha whether to show the opacity bar under the wheel
     */
    public static void show(Context context, CharSequence title, int current, int defaultColor,
                            boolean withAlpha, Callback callback) {
        final int[] rgb = {0xFF000000 | (current & 0x00FFFFFF)};
        final int[] alpha = {Color.alpha(current)};

        final BottomSheet sheet = new BottomSheet(context, false);
        sheet.setTitle(title, true);

        final ColorPicker picker = new ColorPicker(context, false, (color, num, applyNow) -> {
            rgb[0] = 0xFF000000 | (color & 0x00FFFFFF);
            callback.onPicked(withAlpha ? (alpha[0] << 24) | (rgb[0] & 0x00FFFFFF) : rgb[0]);
        }) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(dp(300), MeasureSpec.EXACTLY));
            }
        };
        picker.setColor(rgb[0], 0);
        picker.setType(-1, true, 1, 1, false, 0, false);

        if (!withAlpha) {
            sheet.setCustomView(picker);
            sheet.show();
            return;
        }

        final FrameLayout container = new FrameLayout(context);
        container.addView(picker, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 300, Gravity.TOP));

        final AlphaBar bar = new AlphaBar(context, alpha[0], value -> {
            alpha[0] = value;
            callback.onPicked((alpha[0] << 24) | (rgb[0] & 0x00FFFFFF));
        });
        container.addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 56, Gravity.TOP, 0, 300, 0, 0));

        sheet.setCustomView(container);
        sheet.show();
    }

    /** The opacity strip: a percentage on the right and a plain seek bar under it. */
    @SuppressLint("ViewConstructor")
    private static class AlphaBar extends FrameLayout {

        interface Listener {
            void onAlpha(int alpha);
        }

        private final TextPaint labelPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private int alpha;

        AlphaBar(Context context, int initial, Listener listener) {
            super(context);
            setWillNotDraw(false);
            this.alpha = initial;

            labelPaint.setTextSize(dp(15));
            valuePaint.setTextSize(dp(15));
            valuePaint.setTextAlign(Paint.Align.RIGHT);

            final SeekBarView seekBar = new SeekBarView(context);
            seekBar.setReportChanges(true);
            seekBar.setDelegate((stop, progress) -> {
                final int value = Math.round(progress * 255);
                if (value != alpha) {
                    alpha = value;
                    listener.onAlpha(value);
                    invalidate();
                }
            });
            seekBar.setProgress(initial / 255f);
            addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 30, Gravity.TOP, 6, 22, 6, 0));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            labelPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            valuePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            canvas.drawText(getString(R.string.CustomProfileOpacity), dp(21), dp(18), labelPaint);
            canvas.drawText(Math.round(alpha / 255f * 100) + "%", getMeasuredWidth() - dp(21), dp(18), valuePaint);
        }
    }

    public static ColorView createView(Context context) {
        return new ColorView(context);
    }

    /** The row: title on one side, a swatch on the other, over a chequerboard so alpha reads. */
    @SuppressLint("ViewConstructor")
    public static class ColorView extends FrameLayout {

        private static final int SWATCH = 22;

        private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint swatchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint checkerPaint = new Paint();
        private final RectF swatchRect = new RectF();
        private ConfigCellColor cell;
        private boolean enabled = true;

        ColorView(Context context) {
            super(context);
            setWillNotDraw(false);
            titlePaint.setTextSize(dp(16));
        }

        void bind(ConfigCellColor cell) {
            this.cell = cell;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(50), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (cell == null) {
                return;
            }
            titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titlePaint.setAlpha((int) ((enabled ? 1f : 0.5f) * 255));

            final int side = dp(SWATCH);
            final int cy = getMeasuredHeight() / 2;
            final boolean rtl = LocaleController.isRTL;
            final float left = rtl ? dp(21) : getMeasuredWidth() - dp(21) - side;
            swatchRect.set(left, cy - side / 2f, left + side, cy + side / 2f);

            canvas.drawText(cell.title.toString(),
                    rtl ? dp(21) + side + dp(14) : dp(21),
                    cy + dp(6), titlePaint);

            // Two grey squares behind the swatch, so a translucent colour is visibly translucent.
            if (cell.withAlpha) {
                final float half = side / 2f;
                checkerPaint.setColor(0xFFE0E0E0);
                canvas.drawRect(swatchRect, checkerPaint);
                checkerPaint.setColor(0xFFB0B0B0);
                canvas.drawRect(swatchRect.left, swatchRect.top, swatchRect.left + half, swatchRect.top + half, checkerPaint);
                canvas.drawRect(swatchRect.left + half, swatchRect.top + half, swatchRect.right, swatchRect.bottom, checkerPaint);
            }

            swatchPaint.setColor(cell.bindConfig.Int());
            swatchPaint.setAlpha(enabled ? Color.alpha(cell.bindConfig.Int()) : Color.alpha(cell.bindConfig.Int()) / 2);
            canvas.drawRoundRect(swatchRect, dp(5), dp(5), swatchPaint);

            swatchPaint.setColor(Theme.getColor(Theme.key_divider));
            swatchPaint.setAlpha(60);
            swatchPaint.setStyle(Paint.Style.STROKE);
            swatchPaint.setStrokeWidth(Math.max(1, AndroidUtilities.dp(1)));
            canvas.drawRoundRect(swatchRect, dp(5), dp(5), swatchPaint);
            swatchPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            this.enabled = enabled;
            invalidate();
        }
    }

}
