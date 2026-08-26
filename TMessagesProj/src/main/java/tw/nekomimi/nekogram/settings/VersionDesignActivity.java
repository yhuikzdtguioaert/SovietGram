package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.LaunchActivity;

import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.ui.PopupBuilder;
import sovietgram.com.NaConfig;

/**
 * The design-version screen reached from General settings. Each row pins one part of
 * the interface to the way it looked in v12.3.1 — the last release before upstream
 * rebuilt the shell in 12.4.0.
 */
public class VersionDesignActivity extends BaseNekoSettingsActivity {

    private int chatHeaderDesignRow;
    private int navigationDesignRow;

    @Override
    protected void updateRows() {
        super.updateRows();

        chatHeaderDesignRow = addRow();
        navigationDesignRow = addRow();
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.VersionDesign);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    private String[] areaOptions() {
        return new String[]{
                getString(R.string.AreaDesignLatest),
                getString(R.string.AreaDesignV1231)
        };
    }

    private static final int[] AREA_VALUES = {NaConfig.AREA_DESIGN_LATEST, NaConfig.AREA_DESIGN_12_3_1};

    private static String valueOf(String[] options, int[] values, int current) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                return options[i];
            }
        }
        return options[0];
    }

    private void showSelector(View anchor, int position, ConfigItem item) {
        PopupBuilder builder = new PopupBuilder(anchor);
        builder.setItems(areaOptions(), (index, __) -> {
            int selected = index >= 0 && index < AREA_VALUES.length ? AREA_VALUES[index] : AREA_VALUES[0];
            if (selected != item.Int()) {
                item.setConfigInt(selected);
                listAdapter.notifyItemChanged(position);
                applyDesignChange();
            }
            return kotlin.Unit.INSTANCE;
        });
        builder.show();
    }

    /**
     * Every area flag is read while views are being built, so the whole fragment
     * stack has to be recreated rather than just invalidated.
     */
    private void applyDesignChange() {
        AndroidUtilities.runOnUIThread(() -> {
            if (getParentActivity() instanceof LaunchActivity launchActivity) {
                launchActivity.rebuildAllFragmentsForDesign();
            } else if (parentLayout != null) {
                parentLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
            }
        });
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == chatHeaderDesignRow) {
            showSelector(view, position, NaConfig.INSTANCE.getChatHeaderDesign());
        } else if (position == navigationDesignRow) {
            showSelector(view, position, NaConfig.INSTANCE.getNavigationDesign());
        }
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean payload) {
            if (holder.getItemViewType() == TYPE_SETTINGS) {
                TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                if (position == chatHeaderDesignRow) {
                    cell.setTextAndValue(getString(R.string.ChatHeaderDesign),
                            valueOf(areaOptions(), AREA_VALUES, NaConfig.INSTANCE.getChatHeaderDesign().Int()), true);
                } else if (position == navigationDesignRow) {
                    cell.setTextAndValue(getString(R.string.NavigationDesign),
                            valueOf(areaOptions(), AREA_VALUES, NaConfig.INSTANCE.getNavigationDesign().Int()), false);
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            return TYPE_SETTINGS;
        }
    }
}
