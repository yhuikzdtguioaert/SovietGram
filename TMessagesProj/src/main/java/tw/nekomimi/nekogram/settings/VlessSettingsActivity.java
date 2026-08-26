package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextRadioCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

import sovietgram.com.proxy.VlessConfig;
import sovietgram.com.proxy.VlessSubscription;
import sovietgram.com.proxy.XrayController;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AndroidUtil;

/**
 * VLESS connection settings: a hand-entered key, a subscription URL, and the
 * list of servers the last subscription refresh returned.
 *
 * Whichever server is picked simply becomes the stored VLESS key, so the tunnel
 * start path in XrayController is reused untouched.
 *
 * Nothing here ever renders a key, a UUID or the subscription URL itself — those
 * rows show a "configured / not configured" summary only.
 */
public class VlessSettingsActivity extends BaseNekoSettingsActivity {

    private int keyHeaderRow;
    private int keyRow;
    private int keyShadowRow;

    private int subHeaderRow;
    private int subUrlRow;
    private int subRefreshRow;
    private int subInfoRow;

    private int serversHeaderRow;
    private int serversStartRow;
    private int serversEndRow;
    private int serversShadowRow;

    private final List<String> servers = new ArrayList<>();

    private boolean refreshing;

    @Override
    protected void updateRows() {
        super.updateRows();
        XrayController.reloadSavedSettings();
        servers.clear();
        servers.addAll(XrayController.savedServers());

        keyHeaderRow = addRow();
        keyRow = addRow("VlessKey");
        keyShadowRow = addRow();

        subHeaderRow = addRow();
        subUrlRow = addRow("VlessSubscriptionUrl");
        subRefreshRow = addRow("VlessSubscriptionRefresh");
        subInfoRow = addRow();

        if (servers.isEmpty()) {
            serversHeaderRow = serversStartRow = serversEndRow = serversShadowRow = -1;
        } else {
            serversHeaderRow = addRow();
            serversStartRow = rowCount;
            rowCount += servers.size();
            serversEndRow = rowCount;
            serversShadowRow = addRow();
        }
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.VlessSettings);
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        if (position == keyRow) {
            showKeyDialog();
        } else if (position == subUrlRow) {
            showSubscriptionUrlDialog();
        } else if (position == subRefreshRow) {
            refreshSubscription();
        } else if (serversStartRow != -1 && position >= serversStartRow && position < serversEndRow) {
            selectServer(position - serversStartRow);
        }
    }

    private void refreshRows() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void selectServer(int index) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        // The picked URI becomes the active key; restarting a live tunnel is
        // XrayController's job, so nothing is duplicated here.
        if (!XrayController.selectServer(context, index)) {
            refreshRows();
            return;
        }
        refreshRows();
        BulletinFactory.of(this).createSimpleBulletin(
                R.raw.contact_check,
                getString(R.string.VlessServerSelected)
        ).show();
    }
    /**
     * Downloads and decodes the configured subscription off the main thread, then
     * caches the servers it contains. Progress and failures use the same
     * AlertDialog/Bulletin pair as the neighbouring settings screens.
     */
    private void refreshSubscription() {
        Context context = getParentActivity();
        if (context == null || refreshing) {
            return;
        }
        String url = XrayController.savedSubscriptionUrl();
        if (url.isEmpty()) {
            // Nothing to fetch yet; ask for the URL rather than failing silently.
            showSubscriptionUrlDialog();
            return;
        }

        refreshing = true;
        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER, resourcesProvider);
        progress.setCanCancel(false);
        progress.show();

        Utilities.globalQueue.postRunnable(() -> {
            List<String> fetched = null;
            String error = null;
            try {
                fetched = VlessSubscription.fetch(url);
            } catch (Throwable e) {
                // Only the failure reason is surfaced, never the URL.
                error = e.getMessage();
                if (error == null || error.isEmpty()) {
                    error = e.getClass().getSimpleName();
                }
            }
            List<String> result = fetched;
            String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                refreshing = false;
                progress.dismiss();
                if (result == null) {
                    BulletinFactory.of(this).createErrorBulletin(
                            getString(R.string.VlessSubscriptionFailed) + ": " + failure
                    ).show();
                    return;
                }
                XrayController.setServers(result);
                refreshRows();
                BulletinFactory.of(this).createSimpleBulletin(
                        R.raw.contact_check,
                        LocaleController.formatString(R.string.VlessSubscriptionUpdated, result.size())
                ).show();
            });
        });
    }

    private void showKeyDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.VlessVpnLink));

        EditTextBoldCursor input = createEditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setHint(getString(R.string.VlessVpnLinkHint));
        input.setText(XrayController.savedVlessKey());
        input.setSelection(input.length());

        builder.setView(wrap(context, input));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Save), null);
        AlertDialog dialog = builder.create();
        // The listener has to be attached before the dialog is shown: showDialog()
        // shows it immediately and the show event fires exactly once, so a later
        // registration never runs and the button keeps its default dismiss.
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (!VlessConfig.isValidVlessUrl(value)) {
                    input.setError(getString(R.string.VlessKeyInvalid));
                    AndroidUtil.showInputError(input);
                    return;
                }
                XrayController.setVlessKey(value);
                XrayController.restartIfEnabled(context);
                dialog.dismiss();
                refreshRows();
            });
        });
        showDialog(dialog);
    }

    private void showSubscriptionUrlDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.VlessSubscriptionUrl));

        EditTextBoldCursor input = createEditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setHint(getString(R.string.VlessSubscriptionUrlHint));
        input.setText(XrayController.savedSubscriptionUrl());
        input.setSelection(input.length());

        builder.setView(wrap(context, input));
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Save), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                if (!value.isEmpty()
                        && !value.toLowerCase().startsWith("http://")
                        && !value.toLowerCase().startsWith("https://")) {
                    input.setError(getString(R.string.VlessSubscriptionUrlInvalid));
                    AndroidUtil.showInputError(input);
                    return;
                }
                XrayController.setSubscriptionUrl(value);
                dialog.dismiss();
                refreshRows();
                if (!value.isEmpty()) {
                    refreshSubscription();
                }
            });
        });
        showDialog(dialog);
    }

    private LinearLayout wrap(Context context, View input) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.addView(input, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), dp(4), dp(10), 0));
        return layout;
    }

    private EditTextBoldCursor createEditText(Context context) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor, resourcesProvider));
        editText.setBackground(null);
        editText.setLineColors(
                Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider),
                Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider),
                Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        editText.setPadding(0, 0, 0, dp(6));
        editText.requestFocus();
        return editText;
    }

    /** Never the key itself — only whether one is stored. */
    private String keySummary() {
        return XrayController.savedVlessKey().isEmpty()
                ? getString(R.string.VlessVpnNotConfigured)
                : getString(R.string.VlessVpnConfigured);
    }

    /** Never the URL itself — a subscription link is a credential. */
    private String subscriptionSummary() {
        return XrayController.savedSubscriptionUrl().isEmpty()
                ? getString(R.string.VlessVpnNotConfigured)
                : getString(R.string.VlessVpnConfigured);
    }
    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_SHADOW: {
                    holder.itemView.setBackground(Theme.getThemedDrawable(
                            mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    if (position == keyHeaderRow) {
                        cell.setText(getString(R.string.VlessVpnLink));
                    } else if (position == subHeaderRow) {
                        cell.setText(getString(R.string.VlessSubscription));
                    } else {
                        cell.setText(getString(R.string.VlessServers));
                    }
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    cell.setText(getString(R.string.VlessSubscriptionInfo));
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == keyRow) {
                        cell.setTextAndValue(getString(R.string.VlessVpnLink), keySummary(), false);
                    } else if (position == subUrlRow) {
                        cell.setTextAndValue(getString(R.string.VlessSubscriptionUrl), subscriptionSummary(), true);
                    } else if (position == subRefreshRow) {
                        cell.setTextAndIcon(getString(R.string.VlessSubscriptionRefresh), R.drawable.msg_retry_solar, false);
                    }
                    break;
                }
                case TYPE_RADIO: {
                    TextRadioCell cell = (TextRadioCell) holder.itemView;
                    int index = position - serversStartRow;
                    if (index < 0 || index >= servers.size()) {
                        break;
                    }
                    String uri = servers.get(index);
                    String name = VlessSubscription.displayName(uri);
                    if (name.isEmpty()) {
                        name = LocaleController.formatString(R.string.VlessServerFallbackName, index + 1);
                    }
                    // The active server is whichever URI is currently the stored
                    // key, so a hand-entered key that matches still shows checked.
                    boolean checked = uri.equals(XrayController.savedVlessKey());
                    cell.setTextAndCheck(name, checked, index < servers.size() - 1);
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == keyHeaderRow || position == subHeaderRow || position == serversHeaderRow) {
                return TYPE_HEADER;
            } else if (position == keyRow || position == subUrlRow || position == subRefreshRow) {
                return TYPE_SETTINGS;
            } else if (position == subInfoRow) {
                return TYPE_INFO_PRIVACY;
            } else if (serversStartRow != -1 && position >= serversStartRow && position < serversEndRow) {
                return TYPE_RADIO;
            }
            return TYPE_SHADOW;
        }
    }
}
