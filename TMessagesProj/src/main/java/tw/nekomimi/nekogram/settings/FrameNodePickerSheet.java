package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.helpers.frame.FrameGraphType;

/**
 * "Add a node": every kind there is, grouped by what it is for.
 *
 * <p>A sheet rather than a menu because there are two dozen of them and each needs a line saying
 * what it does — a graph editor whose nodes are named but not explained is not usable by anybody who
 * did not write it.
 */
public final class FrameNodePickerSheet {

    public interface Callback {
        void onPicked(int type);
    }

    private FrameNodePickerSheet() {
    }

    public static void show(BaseFragment fragment, Callback callback) {
        final Context context = fragment.getParentActivity();
        if (context == null) {
            return;
        }
        final List<Object> rows = new ArrayList<>();
        final List<FrameGraphType.Kind> kinds = new ArrayList<>();
        for (int category = 0; category < FrameGraphType.CATEGORY_SLUGS.length; category++) {
            FrameGraphType.inCategory(category, kinds);
            if (kinds.isEmpty()) {
                continue;
            }
            rows.add(getString("FrameCategory" + FrameGraphType.CATEGORY_SLUGS[category]));
            rows.addAll(new ArrayList<>(kinds));
        }

        final BottomSheet sheet = new BottomSheet(context, false);
        sheet.setTitle(getString(R.string.CustomProfileFrameAddNode), true);

        final RecyclerListView list = new RecyclerListView(context);
        list.setLayoutManager(new LinearLayoutManager(context));
        list.setAdapter(new RecyclerListView.SelectionAdapter() {
            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                final int position = holder.getAdapterPosition();
                return position >= 0 && position < rows.size()
                        && rows.get(position) instanceof FrameGraphType.Kind;
            }

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                return new RecyclerListView.Holder(viewType == 0
                        ? new HeaderCell(context) : new TextSettingsCell(context));
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                final Object row = rows.get(position);
                if (row instanceof FrameGraphType.Kind kind) {
                    ((TextSettingsCell) holder.itemView).setTextAndValue(
                            FrameStudioActivity.nodeName(kind.type),
                            FrameStudioActivity.nodeHint(kind.type),
                            position + 1 < rows.size()
                                    && rows.get(position + 1) instanceof FrameGraphType.Kind);
                } else {
                    ((HeaderCell) holder.itemView).setText(String.valueOf(row));
                }
            }

            @Override
            public int getItemViewType(int position) {
                return rows.get(position) instanceof FrameGraphType.Kind ? 1 : 0;
            }

            @Override
            public int getItemCount() {
                return rows.size();
            }
        });
        list.setOnItemClickListener((view, position) -> {
            if (rows.get(position) instanceof FrameGraphType.Kind kind) {
                sheet.dismiss();
                callback.onPicked(kind.type);
            }
        });

        final LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(list, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 420));
        sheet.setCustomView(column);
        fragment.showDialog(sheet);
    }
}
