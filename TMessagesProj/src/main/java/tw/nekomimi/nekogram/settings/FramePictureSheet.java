package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.helpers.frame.FrameBlanks;
import tw.nekomimi.nekogram.helpers.frame.FrameSpec;

/**
 * Where a decoration's picture comes from: one of the eight built-in shapes, an address, or a
 * picture already used elsewhere in this frame.
 *
 * <p>Deliberately no file picker. A picture that lives on this phone cannot travel — every other user
 * would see the frame with a hole in it — and a frame that only works for its author is not a frame.
 * The reference offers one and strips it back out at publish time; there is no publish step here, so
 * the restriction is stated at the point of picking instead.
 */
public final class FramePictureSheet {

    public interface Callback {
        void onPicked(String src);
    }

    private FramePictureSheet() {
    }

    public static void show(BaseFragment fragment, FrameSpec spec, Callback callback) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final List<String> rows = new ArrayList<>();
        final List<String> values = new ArrayList<>();
        rows.add(null);
        values.add(null);
        for (String blank : FrameBlanks.ALL) {
            rows.add(getString("CustomProfileFrameBlank_" + FrameBlanks.name(blank)));
            values.add(blank);
        }
        // Addresses this frame already uses, so a second layer can share one without retyping it.
        final List<String> used = new ArrayList<>();
        for (String asset : spec.assets()) {
            if (!FrameBlanks.is(asset) && FrameSpec.shareable(asset) && !used.contains(asset)) {
                used.add(asset);
            }
        }
        if (!used.isEmpty()) {
            rows.add(null);
            values.add(null);
            for (String asset : used) {
                rows.add(shorten(asset));
                values.add(asset);
            }
        }
        rows.add("");
        values.add("");

        final BottomSheet sheet = new BottomSheet(context, false);
        sheet.setTitle(getString(R.string.CustomProfileFramePicture), true);

        final RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                final int position = holder.getAdapterPosition();
                return position >= 0 && position < rows.size() && rows.get(position) != null;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(viewType == 0
                        ? new HeaderCell(context) : new TextSettingsCell(context));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (rows.get(position) == null) {
                    ((HeaderCell) holder.itemView).setText(getString(position == 0
                            ? R.string.CustomProfileFrameBlanks : R.string.CustomProfileFrameUsed));
                    return;
                }
                final TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if ("".equals(values.get(position))) {
                    cell.setText(getString(R.string.CustomProfileFrameUrl), false);
                } else {
                    cell.setText(rows.get(position),
                            position + 1 < rows.size() && rows.get(position + 1) != null);
                }
            }

            @Override
            public int getItemViewType(int position) {
                return rows.get(position) == null ? 0 : 1;
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        });
        list.setOnItemClickListener((view, position) -> {
            final String value = values.get(position);
            if (value == null) {
                return;
            }
            sheet.dismiss();
            if (value.isEmpty()) {
                askUrl(fragment, context, callback);
            } else {
                callback.onPicked(value);
            }
        });

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 420));
        final TextInfoPrivacyCell note = new TextInfoPrivacyCell(context);
        note.setText(getString(R.string.CustomProfileFramePictureInfo));
        column.addView(note, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT));
        sheet.setCustomView(column);
        fragment.showDialog(sheet);
    }

    private static void askUrl(BaseFragment fragment, Context context, Callback callback) {
        final org.telegram.ui.Components.EditTextBoldCursor input =
                new org.telegram.ui.Components.EditTextBoldCursor(context);
        input.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setCursorColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        input.setBackgroundDrawable(null);
        input.setHint("https://");
        input.setPadding(org.telegram.messenger.AndroidUtilities.dp(4), 0,
                org.telegram.messenger.AndroidUtilities.dp(4), 0);

        final android.widget.FrameLayout wrapper = new android.widget.FrameLayout(context);
        wrapper.addView(input, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT, 0, 22, 8, 22, 8));

        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.CustomProfileFrameUrl))
                .setView(wrapper)
                .setPositiveButton(getString(R.string.Done), (dialog, which) -> {
                    final String url = input.getText().toString().trim();
                    // The same check the renderer applies: anything else simply draws nothing.
                    if (FrameSpec.shareable(url)) {
                        callback.onPicked(url);
                    }
                })
                .setNegativeButton(getString(R.string.Cancel), null)
                .show();
    }

    private static String shorten(String url) {
        final int slash = url.lastIndexOf('/');
        final String name = slash < 0 ? url : url.substring(slash + 1);
        return name.length() > 26 ? name.substring(0, 25) + "…" : name;
    }
}
