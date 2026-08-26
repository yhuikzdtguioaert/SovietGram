package tw.nekomimi.nekogram.config.cell;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.TextPaint;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.ConfigItem;

/**
 * An integer row driven by a drag bar instead of a dialog. The framework had no numeric cell, so
 * screens with many ranged values had to ask for each one through a text input; this keeps them on
 * one screen. The bound config is always an int - a fractional setting is stored as its percent.
 */
public class ConfigCellSlider extends AbstractConfigCell implements WithBindConfig, WithKey {

    private final ConfigItem bindConfig;
    private final CharSequence title;
    private final int min;
    private final int max;
    private final String suffix;
    private boolean enabled = true;
    private SliderView view;

    public ConfigCellSlider(ConfigItem bind, int min, int max) {
        this(bind, min, max, null, null);
    }

    public ConfigCellSlider(ConfigItem bind, int min, int max, String suffix) {
        this(bind, min, max, suffix, null);
    }

    public ConfigCellSlider(ConfigItem bind, int min, int max, String suffix, CharSequence customTitle) {
        this.bindConfig = bind;
        this.title = customTitle == null ? getString(bind.getKey()) : customTitle;
        this.min = min;
        this.max = max;
        this.suffix = suffix;
    }

    @Override
    public int getType() {
        return CellGroup.ITEM_TYPE_SLIDER;
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
        // The row is never clickable as a whole: the bar inside it takes the touches.
        return false;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (view != null) {
            view.setEnabled(enabled);
        }
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder holder) {
        SliderView cell = (SliderView) holder.itemView;
        this.view = cell;
        cell.bind(this);
        cell.setEnabled(enabled);
    }

    public static SliderView createView(Context context) {
        return new SliderView(context);
    }

    @SuppressLint("ViewConstructor")
    public static class SliderView extends FrameLayout {

        private final SeekBarView seekBar;
        private final TextPaint titlePaint;
        private final TextPaint valuePaint;
        private ConfigCellSlider cell;
        private boolean enabled = true;

        @SuppressLint("ClickableViewAccessibility")
        SliderView(Context context) {
            super(context);

            setWillNotDraw(false);

            titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            titlePaint.setTextSize(dp(16));

            valuePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            valuePaint.setTextSize(dp(16));
            valuePaint.setTextAlign(Paint.Align.RIGHT);

            seekBar = new SeekBarView(context);
            seekBar.setReportChanges(true);
            seekBar.setDelegate((stop, progress) -> {
                if (cell == null) {
                    return;
                }
                int span = cell.max - cell.min;
                int value = cell.min + Math.round(progress * span);
                if (value != cell.bindConfig.Int()) {
                    cell.bindConfig.setConfigInt(value);
                    cell.cellGroup.runCallback(cell.bindConfig.getKey(), value);
                }
                invalidate();
            });
            seekBar.setOnTouchListener((v, event) -> !enabled);
            addView(seekBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38, Gravity.LEFT | Gravity.TOP, 6, 26, 6, 0));
        }

        void bind(ConfigCellSlider cell) {
            this.cell = cell;
            int span = Math.max(1, cell.max - cell.min);
            seekBar.setSeparatorsCount(span + 1);
            seekBar.setProgress((cell.bindConfig.Int() - cell.min) / (float) span);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(72), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (cell == null) {
                return;
            }
            titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            valuePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            titlePaint.setAlpha((int) ((enabled ? 1f : 0.5f) * 255));
            valuePaint.setAlpha((int) ((enabled ? 1f : 0.5f) * 255));

            String value = String.valueOf(cell.bindConfig.Int());
            if (cell.suffix != null) {
                value = value + cell.suffix;
            }
            canvas.drawText(cell.title.toString(), dp(21), dp(21), titlePaint);
            canvas.drawText(value, getMeasuredWidth() - dp(21), dp(21), valuePaint);
        }

        @Override
        public void invalidate() {
            super.invalidate();
            seekBar.invalidate();
        }

        @Override
        public void setEnabled(boolean enabled) {
            super.setEnabled(enabled);
            this.enabled = enabled;
            seekBar.setAlpha(enabled ? 1f : 0.5f);
            invalidate();
        }
    }
}
