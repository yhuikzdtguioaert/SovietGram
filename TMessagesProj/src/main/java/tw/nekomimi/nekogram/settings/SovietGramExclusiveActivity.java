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
import tw.nekomimi.nekogram.helpers.SovietGramApiClient;
import tw.nekomimi.nekogram.helpers.SovietGramBadges;
import tw.nekomimi.nekogram.helpers.SovietGramSync;
import tw.nekomimi.nekogram.config.cell.ConfigCellColor;

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
    // Only for this phone: whoever's profile is opened, its banner and background videos stay silent.
    // The look of the whole app: Telegram's own, or the MAX messenger's with the colour way of choice.
    private final AbstractConfigCell customInterfaceRow = cellGroup.appendCell(new ConfigCellSelectBox(null, sovietgram.com.NaConfig.INSTANCE.getCustomInterface(), new String[]{
            getString(R.string.CustomInterfaceDefault),
            getString(R.string.CustomInterfaceMax),
    }, null));
    private final AbstractConfigCell maxColorWayRow = cellGroup.appendCell(new ConfigCellSelectBox(null, sovietgram.com.NaConfig.INSTANCE.getMaxColorWay(),
            sovietgram.com.maxui.MaxInterface.colorWayNames(), null));
    private final AbstractConfigCell muteProfileSoundsRow = cellGroup.appendCell(new ConfigCellTextCheck(NekoConfig.muteProfileSounds, getString(R.string.MuteProfileSoundsInfo)));
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

    // Only for people who wear a SovietGram supporter or developer badge. The rows are present only
    // while the server has confirmed that: the options change what other people see, so the server
    // checks the badge itself before it stores anything, whatever this screen shows.
    private final AbstractConfigCell headerSupports = cellGroup.appendCell(new ConfigCellHeader(getString(R.string.SovietGramForSupports)));
    private final AbstractConfigCell supportIconColorRow = cellGroup.appendCell(new ConfigCellText("SovietGramSupportIconColor", this::pickSupportIconColor));
    private final AbstractConfigCell supportIconColorResetRow = cellGroup.appendCell(new ConfigCellText("SovietGramSupportIconColorReset", () -> saveSupportIconColor(null)));
    private final AbstractConfigCell dividerSupports = cellGroup.appendCell(new ConfigCellDivider());
    private boolean supportRowsVisible = true;
    private int pendingSupportColor = -1;
    private int pendingSupportAccount = -1;
    private final Runnable pushSupportColor = () -> {
        if (pendingSupportColor >= 0 && pendingSupportAccount == UserConfig.selectedAccount) {
            saveSupportIconColor(pendingSupportColor);
        }
    };

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
        // The colour ways only mean something while the Max interface is on.
        if (sovietgram.com.NaConfig.INSTANCE.getCustomInterface().Int() != sovietgram.com.maxui.MaxInterface.INTERFACE_MAX) {
            cellGroup.rows.remove(maxColorWayRow);
        }
        if (!NekoConfig.customProfileEnabled.Bool()) {
            cellGroup.rows.remove(workshopRow);
            cellGroup.rows.remove(framesRow);
            cellGroup.rows.remove(frameStudioRow);
        }
        // Hidden until the server says this account holds a badge (a cached list answers right away).
        if (!SovietGramBadges.has(UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId())) {
            setSupportRows(false);
        }
        addRowsToMap(cellGroup);
    }

    private void setSupportRows(boolean show) {
        if (show == supportRowsVisible) {
            return;
        }
        supportRowsVisible = show;
        if (show) {
            int index = cellGroup.rows.indexOf(dividerCustomization) + 1;
            cellGroup.rows.add(index++, headerSupports);
            cellGroup.rows.add(index++, supportIconColorRow);
            cellGroup.rows.add(index++, supportIconColorResetRow);
            cellGroup.rows.add(index, dividerSupports);
        } else {
            cellGroup.rows.remove(headerSupports);
            cellGroup.rows.remove(supportIconColorRow);
            cellGroup.rows.remove(supportIconColorResetRow);
            cellGroup.rows.remove(dividerSupports);
        }
        addRowsToMap(cellGroup);
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    /** Asks the server, which is the authority on who holds a badge, whether to show the section. */
    private void checkSupportEligibility() {
        final int account = UserConfig.selectedAccount;
        if (!SovietGramApiClient.isReady(account)) {
            return;
        }
        SovietGramApiClient.get(account, "/v1/badge-prefs", (body, error) -> {
            if (body == null || account != UserConfig.selectedAccount) {
                return;
            }
            setSupportRows(body.optBoolean("eligible"));
        });
    }

    private void pickSupportIconColor() {
        if (getParentActivity() == null) {
            return;
        }
        final long self = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        final int current = SovietGramBadges.colorOf(self);
        final int start = 0xFF000000 | (current >= 0 ? current : 0x4FA4E8);
        final int account = UserConfig.selectedAccount;
        ConfigCellColor.show(getParentActivity(), getString(R.string.SovietGramSupportIconColor), start, start, false, color -> {
            // The wheel reports every frame of a drag; save once the finger has rested, for the
            // account the colour was picked on.
            pendingSupportColor = color & 0xFFFFFF;
            pendingSupportAccount = account;
            org.telegram.messenger.AndroidUtilities.cancelRunOnUIThread(pushSupportColor);
            org.telegram.messenger.AndroidUtilities.runOnUIThread(pushSupportColor, 700);
        });
    }

    /** @param color 0xRRGGBB, or null to go back to the default colour. */
    private void saveSupportIconColor(Integer color) {
        final int account = UserConfig.selectedAccount;
        final long self = UserConfig.getInstance(account).getClientUserId();
        pendingSupportColor = -1;
        final org.json.JSONObject body = new org.json.JSONObject();
        try {
            body.put("icon_color", color == null ? org.json.JSONObject.NULL
                    : String.format(java.util.Locale.US, "#%06X", color & 0xFFFFFF));
        } catch (org.json.JSONException e) {
            return;
        }
        SovietGramApiClient.putSigned(account, "/v1/badge-prefs", body, (response, error) -> {
            if (response == null) {
                if (error != null && error.contains("not_a_badge_holder")) {
                    setSupportRows(false);
                }
                return;
            }
            SovietGramBadges.setColor(self, color == null ? SovietGramBadges.NO_COLOR : color);
        });
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
        checkSupportEligibility();

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
            } else if (key.equals(sovietgram.com.NaConfig.INSTANCE.getCustomInterface().getKey())
                    || key.equals(sovietgram.com.NaConfig.INSTANCE.getMaxColorWay().getKey())) {
                sovietgram.com.maxui.MaxInterface.onSettingsChanged();
                toggleRow(maxColorWayRow, customInterfaceRow, sovietgram.com.maxui.MaxInterface.isMax());
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
