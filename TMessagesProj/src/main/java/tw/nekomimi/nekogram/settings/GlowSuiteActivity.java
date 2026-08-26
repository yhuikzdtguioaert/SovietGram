package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.Components.RecyclerListView;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSlider;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;

/**
 * The GlowSuite screen: a light behind chat-list avatars and behind reaction bubbles.
 * <p>
 * Three toggles gate the rows under them — the master switch hides everything, and each section's
 * switch hides its own sliders — so rows come and go one at a time through
 * {@link #toggleRow(AbstractConfigCell, AbstractConfigCell, boolean)} rather than through a full
 * rebuild. There is nothing here for the colour: it is read off whatever is being drawn.
 */
@SuppressWarnings("unused")
public class GlowSuiteActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell enabledRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.glowSuiteEnabled, getString(R.string.GlowSuiteAbout)));
    private final AbstractConfigCell dividerEnabled = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerAvatar = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.GlowSuiteHeaderAvatar)));
    private final AbstractConfigCell avatarEnabledRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.glowAvatarEnabled));
    private final AbstractConfigCell avatarIntensityRow = cellGroup.appendCell(new ConfigCellSlider(NekoConfig.glowAvatarIntensity, 0, 255));
    private final AbstractConfigCell avatarRadiusRow = cellGroup.appendCell(new ConfigCellSlider(NekoConfig.glowAvatarRadius, 120, 200, "%"));
    private final AbstractConfigCell avatarPassesRow = cellGroup.appendCell(new ConfigCellSlider(NekoConfig.glowAvatarPasses, 1, 4));
    private final AbstractConfigCell avatarMinSizeRow = cellGroup.appendCell(new ConfigCellSlider(NekoConfig.glowAvatarMinSize, 4, 60, "dp"));
    private final AbstractConfigCell dividerAvatar = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerReaction = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.GlowSuiteHeaderReaction)));
    private final AbstractConfigCell reactionEnabledRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.glowReactionEnabled));
    private final AbstractConfigCell reactionIntensityRow = cellGroup.appendCell(new ConfigCellSlider(NekoConfig.glowReactionIntensity, 0, 255));
    private final AbstractConfigCell reactionRadiusRow = cellGroup.appendCell(new ConfigCellSlider(NekoConfig.glowReactionRadius, 110, 255, "%"));
    private final AbstractConfigCell reactionPassesRow = cellGroup.appendCell(new ConfigCellSlider(NekoConfig.glowReactionPasses, 1, 4));
    private final AbstractConfigCell dividerReaction = cellGroup.appendCell(new ConfigCellDivider());

    public GlowSuiteActivity() {
        // Rows for a section that is switched off start out absent; the callback below puts them back.
        if (!NekoConfig.glowSuiteEnabled.Bool()) {
            hideAll();
        } else {
            if (!NekoConfig.glowAvatarEnabled.Bool()) {
                hideAvatarRows();
            }
            if (!NekoConfig.glowReactionEnabled.Bool()) {
                hideReactionRows();
            }
        }
        addRowsToMap(cellGroup);
    }

    private void hideAll() {
        cellGroup.rows.remove(headerAvatar);
        cellGroup.rows.remove(avatarEnabledRow);
        hideAvatarRows();
        cellGroup.rows.remove(dividerAvatar);
        cellGroup.rows.remove(headerReaction);
        cellGroup.rows.remove(reactionEnabledRow);
        hideReactionRows();
        cellGroup.rows.remove(dividerReaction);
    }

    private void hideAvatarRows() {
        cellGroup.rows.remove(avatarIntensityRow);
        cellGroup.rows.remove(avatarRadiusRow);
        cellGroup.rows.remove(avatarPassesRow);
        cellGroup.rows.remove(avatarMinSizeRow);
    }

    private void hideReactionRows() {
        cellGroup.rows.remove(reactionIntensityRow);
        cellGroup.rows.remove(reactionRadiusRow);
        cellGroup.rows.remove(reactionPassesRow);
    }

    /** Shows or hides everything below the master switch, keeping each section's own state. */
    private void toggleEverything(boolean show) {
        toggleRow(headerAvatar, dividerEnabled, show);
        toggleRow(avatarEnabledRow, headerAvatar, show);
        toggleAvatarRows(show && NekoConfig.glowAvatarEnabled.Bool());
        toggleRow(dividerAvatar, show && NekoConfig.glowAvatarEnabled.Bool() ? avatarMinSizeRow : avatarEnabledRow, show);
        toggleRow(headerReaction, dividerAvatar, show);
        toggleRow(reactionEnabledRow, headerReaction, show);
        toggleReactionRows(show && NekoConfig.glowReactionEnabled.Bool());
        toggleRow(dividerReaction, show && NekoConfig.glowReactionEnabled.Bool() ? reactionPassesRow : reactionEnabledRow, show);
    }

    private void toggleAvatarRows(boolean show) {
        toggleRow(avatarIntensityRow, avatarEnabledRow, show);
        toggleRow(avatarRadiusRow, avatarIntensityRow, show);
        toggleRow(avatarPassesRow, avatarRadiusRow, show);
        toggleRow(avatarMinSizeRow, avatarPassesRow, show);
    }

    private void toggleReactionRows(boolean show) {
        toggleRow(reactionIntensityRow, reactionEnabledRow, show);
        toggleRow(reactionRadiusRow, reactionIntensityRow, show);
        toggleRow(reactionPassesRow, reactionRadiusRow, show);
    }

    /**
     * CellGroup has no notion of a hidden row, so showing one means putting it back into the
     * list right after its neighbour and telling the adapter about it.
     */
    private void toggleRow(AbstractConfigCell row, AbstractConfigCell after, boolean show) {
        if (show) {
            if (!cellGroup.rows.contains(row)) {
                final int index = cellGroup.rows.indexOf(after) + 1;
                cellGroup.rows.add(index, row);
                listAdapter.notifyItemInserted(index);
            }
        } else {
            final int index = cellGroup.rows.indexOf(row);
            if (index >= 0) {
                cellGroup.rows.remove(index);
                listAdapter.notifyItemRemoved(index);
            }
        }
        addRowsToMap(cellGroup);
    }

    @Override
    protected RecyclerListView.SelectionAdapter getListAdapter() {
        return listAdapter;
    }

    @Override
    protected CellGroup getCellGroup() {
        return cellGroup;
    }

    @Override
    protected String getSettingsPrefix() {
        return "glowsuite";
    }

    @SuppressLint("NewApi")
    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NekoConfig.glowSuiteEnabled.getKey())) {
                toggleEverything((Boolean) newValue);
            } else if (key.equals(NekoConfig.glowAvatarEnabled.getKey())) {
                final boolean enabled = (Boolean) newValue;
                toggleAvatarRows(enabled);
                // The divider closing the section has to follow whichever row is now last, and it can
                // only move by leaving the list and coming back so the adapter hears about both ends.
                toggleRow(dividerAvatar, avatarEnabledRow, false);
                toggleRow(dividerAvatar, enabled ? avatarMinSizeRow : avatarEnabledRow, true);
            } else if (key.equals(NekoConfig.glowReactionEnabled.getKey())) {
                final boolean enabled = (Boolean) newValue;
                toggleReactionRows(enabled);
                toggleRow(dividerReaction, reactionEnabledRow, false);
                toggleRow(dividerReaction, enabled ? reactionPassesRow : reactionEnabledRow, true);
            } else {
                // A slider. Those are read straight out of the config on every draw, so the next
                // redraw picks them up on its own — and a drag reports continuously, which makes a
                // refresh per change far too expensive to be worth it.
                return;
            }
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        };

        return superView;
    }

    @Override
    public int getBaseGuid() {
        // 15000 is CustomProfileActivity's. Guids are added to a row index to make a settings-search
        // result id, so two screens sharing a base would collide row for row.
        return 16000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.sovietgram_exclusive;
    }

    @Override
    public String getTitle() {
        return getString(R.string.GlowSuiteTitle);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }
    }
}
