package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * A plain settings list for the parts of a look that are not a flat set of config items.
 *
 * <p>The look's palette, its own rows, the order of the profile's rows and the header layout are all
 * lists whose contents change as they are edited — a palette entry can be added, a row moved, a part
 * of the header shown only when its anchor is set. The config-cell framework the rest of the settings
 * use builds a fixed list from named config items, which none of these are, so they get this instead:
 * rows built as data, rebuilt whole on every change, exactly the way
 * {@link CustomProfileActivity} rebuilds its own list.
 */
public abstract class CustomProfileListActivity extends BaseFragment {

    protected static final int TYPE_HEADER = 0;
    protected static final int TYPE_SETTING = 1;
    protected static final int TYPE_CHECK = 2;
    protected static final int TYPE_SHADOW = 3;
    protected static final int TYPE_INFO = 4;
    protected static final int TYPE_SLIDER = 5;

    /** One row. A value or a checked state as it applies; the rest is ignored. */
    protected static final class Row {
        final int type;
        final CharSequence title;
        @Nullable
        CharSequence value;
        boolean checked;
        int valueColor;
        @Nullable
        Runnable onClick;
        @Nullable
        Runnable onLongClick;
        int min;
        int max;
        int number;
        @Nullable
        String suffix;
        @Nullable
        java.util.function.IntConsumer sink;
        /** Run when the finger leaves the bar, for work too heavy to do on every frame. */
        @Nullable
        Runnable onSettled;

        Row(int type, CharSequence title) {
            this.type = type;
            this.title = title;
        }
    }

    protected final List<Row> rows = new ArrayList<>();
    protected RecyclerListView listView;
    private ListAdapter adapter;

    /** Fills {@link #rows}. Called whenever anything changes; must not keep state of its own. */
    protected abstract void buildRows();

    /** A view pinned above the list, or null for a plain list. */
    @Nullable
    protected View createHeader(Context context) {
        return null;
    }

    protected abstract String title();

    protected Row header(CharSequence title) {
        return add(new Row(TYPE_HEADER, title));
    }

    protected Row setting(CharSequence title, @Nullable CharSequence value, @Nullable Runnable onClick) {
        final Row row = new Row(TYPE_SETTING, title);
        row.value = value;
        row.onClick = onClick;
        return add(row);
    }

    protected Row check(CharSequence title, boolean checked, @Nullable Runnable onClick) {
        final Row row = new Row(TYPE_CHECK, title);
        row.checked = checked;
        row.onClick = onClick;
        return add(row);
    }

    /** A number dragged rather than typed; the studio has too many of them for dialogs. */
    protected Row slider(CharSequence title, int value, int min, int max, @Nullable String suffix,
                         java.util.function.IntConsumer sink) {
        return slider(title, value, min, max, suffix, sink, null);
    }

    /**
     * @param onSettled run when the finger is lifted. Anything that writes to disk or repaints the
     *                  rest of the app belongs here rather than in {@code sink}, which runs on every
     *                  frame of the drag.
     */
    protected Row slider(CharSequence title, int value, int min, int max, @Nullable String suffix,
                         java.util.function.IntConsumer sink, @Nullable Runnable onSettled) {
        final Row row = new Row(TYPE_SLIDER, title);
        row.number = value;
        row.min = min;
        row.max = max;
        row.suffix = suffix;
        row.sink = sink;
        row.onSettled = onSettled;
        return add(row);
    }

    protected Row shadow() {
        return add(new Row(TYPE_SHADOW, null));
    }

    protected Row info(CharSequence text) {
        return add(new Row(TYPE_INFO, text));
    }

    private static final java.util.Map<String, Integer> rowIcons = new java.util.HashMap<>();
    private static String iconLocale;

    private static int rowIcon(Row row) {
        String title = String.valueOf(row.title);
        String locale = org.telegram.messenger.LocaleController.getInstance().getCurrentLocaleInfo().shortName;
        if (locale.equals(iconLocale)) return rowIcons.getOrDefault(title, R.drawable.msg_settings);
        iconLocale = locale;
        rowIcons.clear();
        int[][] groups = {
                {R.drawable.msg_delete, R.string.Delete},
                {R.drawable.msg_colors, R.string.CustomProfileExtraRowTitleColor, R.string.CustomProfileExtraRowValueColor, R.string.CustomProfileExtraRowFill},
                {R.drawable.msg_photos, R.string.CustomProfileExtraRowPick, R.string.CustomProfileExtraRowMedia},
                {R.drawable.msg_share, R.string.CustomProfileExtraRowUrl, R.string.CustomProfileIntegrationAccount},
                {R.drawable.msg_photo_text_framed3, R.string.CustomProfileExtraRowTitle, R.string.CustomProfileExtraRowText},
                {R.drawable.msg_contacts, R.string.CustomProfileIntegrationService, R.string.CustomProfileIntegrationConnect, R.string.CustomProfileIntegrationDisconnect},
                {R.drawable.msg_viewchats, R.string.CustomProfileExtraRowOwnOnly},
                {R.drawable.msg_list, R.string.CustomProfileExtraRowType, R.string.CustomProfileExtraRowAdd, R.string.CustomProfileRowUp, R.string.CustomProfileRowDown},
                {R.drawable.msg_photo_settings, R.string.CustomProfileExtraRowHeight, R.string.CustomProfileExtraRowRadius}
        };
        for (int[] group : groups) for (int i = 1; i < group.length; i++) {
            rowIcons.put(org.telegram.messenger.LocaleController.getString(group[i]), group[0]);
        }
        return rowIcons.getOrDefault(title, R.drawable.msg_settings);
    }

    private static final class IconHolder extends RecyclerView.ViewHolder {
        final RecyclerView.ViewHolder content;
        final android.widget.ImageView icon;
        IconHolder(View view, RecyclerView.ViewHolder content, android.widget.ImageView icon) {
            super(view); this.content = content; this.icon = icon;
        }
    }

    private Row add(Row row) {
        rows.add(row);
        return row;
    }

    protected void rebuild() {
        if (listView != null && listView.isComputingLayout()) {
            listView.post(this::rebuild);
            return;
        }
        final List<Row> before = new ArrayList<>(rows);
        rows.clear();
        buildRows();
        if (adapter == null) {
            return;
        }
        if (before.isEmpty() || listView == null || listView.getChildCount() == 0) {
            adapter.notifyDataSetChanged();
            return;
        }
        // Rows that appear or go (the options a switch reveals) slide in and out instead of the whole
        // list being replaced at once.
        final List<Row> after = new ArrayList<>(rows);
        androidx.recyclerview.widget.DiffUtil.calculateDiff(
                new androidx.recyclerview.widget.DiffUtil.Callback() {
                    @Override public int getOldListSize() { return before.size(); }
                    @Override public int getNewListSize() { return after.size(); }
                    @Override public boolean areItemsTheSame(int oldPosition, int newPosition) {
                        final Row a = before.get(oldPosition);
                        final Row b = after.get(newPosition);
                        return a.type == b.type && java.util.Objects.equals(String.valueOf(a.title), String.valueOf(b.title));
                    }
                    @Override public boolean areContentsTheSame(int oldPosition, int newPosition) {
                        final Row a = before.get(oldPosition);
                        final Row b = after.get(newPosition);
                        return a.type != TYPE_SLIDER && a.checked == b.checked && a.number == b.number && a.valueColor == b.valueColor
                                && a.min == b.min && a.max == b.max
                                && java.util.Objects.equals(String.valueOf(a.value), String.valueOf(b.value))
                                && java.util.Objects.equals(a.suffix, b.suffix)
                                && hasDivider(before, oldPosition) == hasDivider(after, newPosition);
                    }
                }, false).dispatchUpdatesTo(adapter);
    }

    private static boolean hasDivider(List<Row> list, int position) {
        return position + 1 < list.size()
                && list.get(position + 1).type != TYPE_SHADOW
                && list.get(position + 1).type != TYPE_INFO
                && list.get(position + 1).type != TYPE_HEADER;
    }

    /**
     * A number, typed rather than dragged. A slider is friendlier for a colour or an opacity, but
     * these are offsets and angles the author wants an exact figure for, and half of them have no
     * sensible range to lay a slider over.
     */
    protected void askNumber(CharSequence title, int current, int min, int max,
                             java.util.function.IntConsumer sink) {
        if (getParentActivity() == null) {
            return;
        }
        final org.telegram.ui.Components.EditTextBoldCursor input =
                new org.telegram.ui.Components.EditTextBoldCursor(getParentActivity());
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 18);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setBackgroundDrawable(null);
        input.setPadding(dp(4), 0, dp(4), 0);
        input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER
                | (min < 0 ? android.text.InputType.TYPE_NUMBER_FLAG_SIGNED : 0));
        input.setText(String.valueOf(current));
        input.setSelection(input.getText().length());

        final FrameLayout wrapper = new FrameLayout(getParentActivity());
        wrapper.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 22, 8, 22, 8));

        new org.telegram.ui.ActionBar.AlertDialog.Builder(getParentActivity())
                .setTitle(title)
                .setMessage(min + " … " + max)
                .setView(wrapper)
                .setPositiveButton(org.telegram.messenger.LocaleController.getString(R.string.Done),
                        (dialog, which) -> {
                            int value;
                            try {
                                value = Integer.parseInt(input.getText().toString().trim());
                            } catch (Throwable ignore) {
                                return;
                            }
                            sink.accept(Math.max(min, Math.min(max, value)));
                            rebuild();
                        })
                .setNegativeButton(org.telegram.messenger.LocaleController.getString(R.string.Cancel), null)
                .show();
    }

    private boolean shownOnce;

    /**
     * A page opened from this one (a single row, a deeper list) changes what this one lists: a row deleted
     * there stayed on screen here until the page was left and opened again.
     */
    @Override
    public void onResume() {
        super.onResume();
        if (shownOnce && adapter != null) rebuild();
        shownOnce = true;
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title());
        if (AndroidUtilities.isTablet()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        fragmentView = root;

        final View header = createHeader(context);

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setVerticalScrollBarEnabled(false);
        // The same rounded cards the other settings screens are drawn in; the cells themselves stay
        // transparent so the card's corners show.
        listView.setSections(true);
        listView.setClipToPadding(false);
        final androidx.recyclerview.widget.DefaultItemAnimator animator =
                new androidx.recyclerview.widget.DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setDelayAnimations(false);
        animator.setAddDuration(250);
        animator.setRemoveDuration(200);
        animator.setMoveDuration(250);
        animator.setInterpolator(org.telegram.ui.Components.CubicBezierInterpolator.EASE_OUT_QUINT);
        listView.setItemAnimator(animator);
        adapter = new ListAdapter(context);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return;
            }
            final Row row = rows.get(position);
            if (row.onClick != null) {
                row.onClick.run();
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position < 0 || position >= rows.size()) {
                return false;
            }
            final Row row = rows.get(position);
            if (row.onLongClick == null) {
                return false;
            }
            row.onLongClick.run();
            return true;
        });
        if (header == null) {
            root.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.MATCH_PARENT));
        } else {
            final android.widget.LinearLayout column = new android.widget.LinearLayout(context);
            column.setOrientation(android.widget.LinearLayout.VERTICAL);
            column.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.WRAP_CONTENT));
            column.addView(listView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 0, 1f));
            root.addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                    LayoutHelper.MATCH_PARENT));
        }

        rebuild();
        return root;
    }

    /**
     * A title, a value and a drag bar. Written here rather than reusing the config framework's slider
     * because these are not config items — they are knobs in a graph and fields in a list.
     */
    private static class SliderRow extends FrameLayout {

        private final org.telegram.ui.Components.SeekBarView bar;
        private final android.text.TextPaint titlePaint = new android.text.TextPaint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.text.TextPaint valuePaint = new android.text.TextPaint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        @Nullable
        private Row row;
        private boolean divider;

        SliderRow(Context context) {
            super(context);
            setWillNotDraw(false);
            titlePaint.setTextSize(dp(16));
            valuePaint.setTextSize(dp(16));
            valuePaint.setTextAlign(android.graphics.Paint.Align.RIGHT);

            bar = new org.telegram.ui.Components.SeekBarView(context);
            bar.setReportChanges(true);
            bar.setDelegate((stop, progress) -> {
                if (row == null) {
                    return;
                }
                final int value = row.min + Math.round(progress * (row.max - row.min));
                if (value != row.number && row.sink != null) {
                    row.number = value;
                    row.sink.accept(value);
                    invalidate();
                }
                if (stop && row.onSettled != null) {
                    row.onSettled.run();
                }
            });
            addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38,
                    android.view.Gravity.TOP, 6, 26, 6, 0));
        }

        void bind(Row row, boolean divider) {
            this.row = row;
            this.divider = divider;
            final int span = Math.max(1, row.max - row.min);
            bar.setProgress((row.number - row.min) / (float) span);
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(dp(66), MeasureSpec.EXACTLY));
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            if (row == null) {
                return;
            }
            titlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            valuePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
            canvas.drawText(String.valueOf(row.title), dp(21), dp(22), titlePaint);
            canvas.drawText(row.number + (row.suffix == null ? "" : row.suffix),
                    getMeasuredWidth() - dp(21), dp(22), valuePaint);
            if (divider) {
                canvas.drawLine(dp(21), getMeasuredHeight() - 1,
                        getMeasuredWidth() - dp(21), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {

        private final Context context;

        ListAdapter(Context context) {
            this.context = context;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            final int position = holder.getAdapterPosition();
            if (position < 0 || position >= rows.size()) {
                return false;
            }
            return rows.get(position).onClick != null;
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final View view = switch (viewType) {
                case TYPE_HEADER -> new HeaderCell(context);
                case TYPE_CHECK -> new TextCheckCell(context);
                case TYPE_SHADOW -> new ShadowSectionCell(context);
                case TYPE_SLIDER -> new SliderRow(context);
                case TYPE_INFO -> new TextInfoPrivacyCell(context);
                default -> new TextSettingsCell(context);
            };
            RecyclerView.ViewHolder content = new RecyclerListView.Holder(view);
            if (viewType == TYPE_SHADOW || viewType == TYPE_INFO || viewType == TYPE_HEADER) return content;
            FrameLayout container = new FrameLayout(context);
            container.addView(view, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT,
                    android.view.Gravity.TOP, 40, 0, 0, 0));
            android.widget.ImageView icon = new android.widget.ImageView(context);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            container.addView(icon, LayoutHelper.createFrame(24, 24, android.view.Gravity.TOP | android.view.Gravity.LEFT, 16, 16, 0, 0));
            container.setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            return new IconHolder(container, content, icon);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final Row row = rows.get(position);
            if (holder instanceof IconHolder iconHolder) {
                iconHolder.icon.setImageResource(rowIcon(row));
                iconHolder.icon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon));
                holder = iconHolder.content;
            }
            final boolean divider = position + 1 < rows.size()
                    && rows.get(position + 1).type != TYPE_SHADOW
                    && rows.get(position + 1).type != TYPE_INFO
                    && rows.get(position + 1).type != TYPE_HEADER;
            switch (row.type) {
                case TYPE_HEADER -> ((HeaderCell) holder.itemView).setText(row.title);
                case TYPE_CHECK -> {
                    final TextCheckCell cell = (TextCheckCell) holder.itemView;
                    final Object shown = cell.getTag(R.id.object_tag);
                    if (shown != null && shown.equals(String.valueOf(row.title)) && cell.isChecked() != row.checked) {
                        cell.setChecked(row.checked);
                    } else {
                        cell.setTextAndCheck(row.title, row.checked, divider);
                    }
                    cell.setTag(R.id.object_tag, String.valueOf(row.title));
                }
                case TYPE_INFO -> ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                case TYPE_SLIDER -> ((SliderRow) holder.itemView).bind(row, divider);
                case TYPE_SHADOW -> {
                }
                default -> {
                    final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    cell.setTextAndValue(row.title, row.value == null ? "" : row.value, divider);
                    cell.setTextValueColor(row.valueColor != 0 ? row.valueColor
                            : Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).type;
        }

        @Override
        public int getItemCount() {
            return rows.size();
        }
    }
}
