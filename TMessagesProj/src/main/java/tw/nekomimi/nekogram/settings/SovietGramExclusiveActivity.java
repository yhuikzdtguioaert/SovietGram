package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;

import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.RecyclerListView;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.CellGroup;
import tw.nekomimi.nekogram.config.cell.AbstractConfigCell;
import tw.nekomimi.nekogram.config.cell.ConfigCellDivider;
import tw.nekomimi.nekogram.config.cell.ConfigCellHeader;
import tw.nekomimi.nekogram.config.cell.ConfigCellSelectBox;
import tw.nekomimi.nekogram.config.cell.ConfigCellText;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextCheck;
import tw.nekomimi.nekogram.config.cell.ConfigCellTextInput;
import tw.nekomimi.nekogram.helpers.CustomProfileHelper;
import tw.nekomimi.nekogram.helpers.WorkshopHelper;
import tw.nekomimi.nekogram.helpers.ServerFragmentHelper;
import tw.nekomimi.nekogram.helpers.SovietGramSync;

/**
 * Home for features that only exist between SovietGram users — things that have no
 * counterpart in stock Telegram and are not experiments waiting to be promoted.
 * The local premium toggle used to sit in {@link NekoExperimentalSettingsActivity};
 * it moved here unchanged, only its label was reworded.
 */
@SuppressWarnings("unused")
public class SovietGramExclusiveActivity extends BaseNekoXSettingsActivity {

    private ListAdapter listAdapter;

    private final CellGroup cellGroup = new CellGroup(this);

    private final AbstractConfigCell headerServer = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.SovietGramServer)));
    private final AbstractConfigCell localPremiumRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.localPremium));
    private final AbstractConfigCell fakeStarsRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.fakeStars));
    private final AbstractConfigCell fakeStarsAmountRow = cellGroup.appendCell(new ConfigCellTextInput(null, NekoConfig.fakeStarsAmount, "1000", null, SovietGramExclusiveActivity::sanitizeStars));
    private final AbstractConfigCell serverTonRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.serverTon));
    private final AbstractConfigCell serverTonAmountRow = cellGroup.appendCell(new ConfigCellTextInput(null, NekoConfig.serverTonAmount, "100", null, SovietGramExclusiveActivity::sanitizeTon));
    private final AbstractConfigCell serverFragmentRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.serverFragment));
    private final AbstractConfigCell serverFragmentPhoneRow = cellGroup.appendCell(new ConfigCellTextInput(null, NekoConfig.serverFragmentPhone, "88800000000", null, ServerFragmentHelper::sanitizePhone));
    private final AbstractConfigCell serverFragmentUsernamesRow = cellGroup.appendCell(new ConfigCellTextInput(null, NekoConfig.serverFragmentUsernames, "durov, telegram", null, ServerFragmentHelper::sanitizeUsernames));
    // Deliberately last: CellGroup decides whether a row draws a divider by looking at the row that
    // follows it, so keeping an unconditional row right before the divider means the optional amount
    // rows can come and go without any of their neighbours needing a rebind.
    private final AbstractConfigCell localGiftSenderRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.localGiftSender));
    private final AbstractConfigCell dividerServer = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerMemes = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.SovietGramMemes)));
    private final AbstractConfigCell memeFrameRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.memeFrameEnabled, getString(R.string.MemeFrameInfo)));
    private final AbstractConfigCell dividerMemes = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerOther = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.SovietGramOther)));
    private final AbstractConfigCell voiceChangerRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.voiceChangerEnabled, getString(R.string.voiceChangerInfo)));
    private final AbstractConfigCell voiceChangerPresetRow = cellGroup.appendCell(new ConfigCellSelectBox(null, NekoConfig.voiceChangerPreset, new String[]{
            getString(R.string.VoiceChangerNormal),
            getString(R.string.VoiceChangerHigh),
            getString(R.string.VoiceChangerChipmunk),
            getString(R.string.VoiceChangerHelium),
            getString(R.string.VoiceChangerLow),
            getString(R.string.VoiceChangerBass),
            getString(R.string.VoiceChangerMonster),
            getString(R.string.VoiceChangerRobot),
    }, null));
    private final AbstractConfigCell dividerOther = cellGroup.appendCell(new ConfigCellDivider());

    private final AbstractConfigCell headerCustomization = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.CustomProfileCategory)));
    // Only the switch lives here. The look itself is edited from the profile page (⋮ → Настроить
    // профиль), the same place the reference plugin puts it, so there is no sub-screen to open.
    private final AbstractConfigCell customProfileRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.customProfileEnabled, getString(R.string.CustomProfileAbout)));
    private final AbstractConfigCell workshopRow = cellGroup.appendCell(new ConfigCellText("CustomProfileWorkshop", () -> presentFragment(new WorkshopActivity())));
    // The workshop's second gallery: avatar frames. Same screen, same sections — installing from it
    // changes only the frame, so a frame can be worn with any look.
    private final AbstractConfigCell framesRow = cellGroup.appendCell(new ConfigCellText("CustomProfileFrames",
            () -> presentFragment(new WorkshopActivity(WorkshopHelper.KIND_FRAME))));
    // The editor for the same thing. Beside the gallery rather than inside it: a frame is as often
    // drawn from nothing as it is installed and then changed.
    private final AbstractConfigCell frameStudioRow = cellGroup.appendCell(
            new ConfigCellText("CustomProfileFrameStudio", () -> presentFragment(new FrameStudioActivity())));
    private final AbstractConfigCell glowSuiteRow = cellGroup.appendCell(new ConfigCellText("GlowSuiteTitle", () -> presentFragment(new GlowSuiteActivity())));
    private final AbstractConfigCell dividerCustomization = cellGroup.appendCell(new ConfigCellDivider());

    public SovietGramExclusiveActivity() {
        // The amount fields only make sense once their toggle is on, so they start out absent
        // and are inserted/removed by the callback below.
        if (!NekoConfig.fakeStars.Bool()) {
            cellGroup.rows.remove(fakeStarsAmountRow);
        }
        if (!NekoConfig.serverTon.Bool()) {
            cellGroup.rows.remove(serverTonAmountRow);
        }
        if (!NekoConfig.serverFragment.Bool()) {
            cellGroup.rows.remove(serverFragmentPhoneRow);
            cellGroup.rows.remove(serverFragmentUsernamesRow);
        }
        if (!NekoConfig.voiceChangerEnabled.Bool()) {
            cellGroup.rows.remove(voiceChangerPresetRow);
        }
        if (!NekoConfig.customProfileEnabled.Bool()) {
            cellGroup.rows.remove(workshopRow);
            cellGroup.rows.remove(framesRow);
            cellGroup.rows.remove(frameStudioRow);
        }
        addRowsToMap(cellGroup);
    }

    /**
     * The amount is stored as a string (that is all ConfigCellTextInput can write back), so keep
     * digits only and clamp to what a star balance can hold. Empty input falls back to zero.
     */
    private static String sanitizeStars(String input) {
        String digits = input == null ? "" : input.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return "0";
        }
        try {
            return String.valueOf(Long.parseLong(digits));
        } catch (NumberFormatException e) {
            return String.valueOf(Long.MAX_VALUE);
        }
    }

    /**
     * TON is fractional, so unlike stars this one keeps a single decimal separator. Everything
     * past the first dot is dropped rather than rejected, which is what the keyboard produces
     * when someone taps "." twice.
     */
    private static String sanitizeTon(String input) {
        String cleaned = input == null ? "" : input.replace(',', '.').replaceAll("[^0-9.]", "");
        int dot = cleaned.indexOf('.');
        if (dot >= 0) {
            cleaned = cleaned.substring(0, dot + 1) + cleaned.substring(dot + 1).replace(".", "");
        }
        if (cleaned.isEmpty() || cleaned.equals(".")) {
            return "0";
        }
        try {
            double value = Double.parseDouble(cleaned);
            if (value < 0 || Double.isNaN(value) || Double.isInfinite(value)) {
                return "0";
            }
        } catch (NumberFormatException e) {
            return "0";
        }
        return cleaned;
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
        return "exclusive";
    }

    @SuppressLint("NewApi")
    @Override
    public View createView(Context context) {
        View superView = super.createView(context);

        listAdapter = new ListAdapter(context);
        listView.setAdapter(listAdapter);

        setupDefaultListeners();

        cellGroup.callBackSettingsChanged = (key, newValue) -> {
            if (key.equals(NekoConfig.localPremium.getKey())) {
                // Same refresh the toggle triggered from Experimental: the premium flag
                // feeds avatars, dialog filters and a lot of gated UI, none of which
                // re-reads the config on its own.
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.mainUserInfoChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
                SovietGramSync.scheduleProfilePush();
            } else if (key.equals(NekoConfig.fakeStars.getKey())) {
                toggleRow(fakeStarsAmountRow, fakeStarsRow, (Boolean) newValue);
                notifyStarBalanceChanged();
                SovietGramSync.scheduleProfilePush();
            } else if (key.equals(NekoConfig.serverTon.getKey())) {
                toggleRow(serverTonAmountRow, serverTonRow, (Boolean) newValue);
                notifyStarBalanceChanged();
                SovietGramSync.scheduleProfilePush();
            } else if (key.equals(NekoConfig.serverFragment.getKey())) {
                final boolean enabled = (Boolean) newValue;
                toggleRow(serverFragmentPhoneRow, serverFragmentRow, enabled);
                toggleRow(serverFragmentUsernamesRow, serverFragmentPhoneRow, enabled);
                ServerFragmentHelper.onSettingsChanged();
            } else if (key.equals(NekoConfig.serverFragmentPhone.getKey()) || key.equals(NekoConfig.serverFragmentUsernames.getKey())) {
                ServerFragmentHelper.onSettingsChanged();
            } else if (key.equals(NekoConfig.fakeStarsAmount.getKey()) || key.equals(NekoConfig.serverTonAmount.getKey())) {
                notifyStarBalanceChanged();
                SovietGramSync.scheduleProfilePush();
            } else if (key.equals(NekoConfig.voiceChangerEnabled.getKey())) {
                toggleRow(voiceChangerPresetRow, voiceChangerRow, (Boolean) newValue);
            } else if (key.equals(NekoConfig.customProfileEnabled.getKey())) {
                toggleRow(workshopRow, customProfileRow, (Boolean) newValue);
                toggleRow(framesRow, workshopRow, (Boolean) newValue);
                toggleRow(frameStudioRow, framesRow, (Boolean) newValue);
                CustomProfileHelper.onSettingsChanged();
            }
        };

        return superView;
    }

    /**
     * CellGroup has no notion of a hidden row, so showing one means putting it back into the
     * list right after its toggle and telling the adapter about it.
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

    /**
     * Every star counter in the app listens for this; without it the wallet keeps showing the
     * previous number until something else forces a reload.
     */
    private void notifyStarBalanceChanged() {
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            NotificationCenter.getInstance(a).postNotificationName(NotificationCenter.starBalanceUpdated);
        }
    }

    @Override
    public int getBaseGuid() {
        return 14000;
    }

    @Override
    public int getDrawable() {
        return R.drawable.sovietgram_exclusive;
    }

    @Override
    public String getTitle() {
        return getString(R.string.SovietGramExclusive);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }
    }
}
