package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.net.Uri;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import sovietgram.com.NaConfig;
import sovietgram.com.proxy.TgWsProxyController;
import sovietgram.com.proxy.VlessConfig;
import sovietgram.com.proxy.XrayController;
import tw.nekomimi.nekogram.ui.cells.HeaderCell;
import tw.nekomimi.nekogram.utils.AndroidUtil;

public class BypassBlockingActivity extends BaseNekoSettingsActivity {

    private static final int[] WS_POOL_VALUES = new int[]{2, 4, 6};

    private int headerRow;
    private int tgWsProxyRow;
    private int portRow;
    private int wsPoolRow;
    private int secretKeyRow;
    private int generateSecretKeyRow;
    private int cloudflareCdnRow;
    private int notificationEnabledRow;
    private int shadowRow;

    private int vlessHeaderRow;
    private int vlessEnabledRow;
    private int vlessLinkRow;
    private int vlessSettingsRow;
    private int vlessNotificationRow;
    private int vlessShadowRow;

    @Override
    protected void updateRows() {
        super.updateRows();
        TgWsProxyController.reloadSavedSettings();
        XrayController.reloadSavedSettings();
        headerRow = addRow();
        tgWsProxyRow = addRow("TgWsProxyEnabled");
        if (NaConfig.INSTANCE.getTgWsProxyEnabled().Bool()) {
            portRow = addRow("TgWsProxyPort");
            wsPoolRow = addRow("TgWsProxyPool");
            secretKeyRow = addRow("TgWsProxySecret");
            generateSecretKeyRow = addRow("TgWsProxyGenerateSecretKey");
            cloudflareCdnRow = addRow("TgWsProxyCloudflareCdn");
            notificationEnabledRow = addRow("TgWsProxyNotificationEnabled");
        } else {
            portRow = wsPoolRow = secretKeyRow = generateSecretKeyRow = cloudflareCdnRow = notificationEnabledRow = -1;
        }
        shadowRow = addRow();

        // Vless VPN lives in its own section on this same screen. Its detail rows
        // (link, notification) only exist once the toggle is on, mirroring how the
        // TG WS proxy section above reveals its own settings.
        vlessHeaderRow = addRow();
        vlessEnabledRow = addRow("VlessVpn");
        if (NaConfig.INSTANCE.getVlessEnabled().Bool()) {
            vlessLinkRow = addRow("VlessVpnLink");
            vlessSettingsRow = addRow("VlessSettings");
            vlessNotificationRow = addRow("VlessNotificationEnabled");
        } else {
            vlessLinkRow = vlessSettingsRow = vlessNotificationRow = -1;
        }
        vlessShadowRow = addRow();
    }

    @Override
    protected void onItemClick(View view, int position, float x, float y) {
        Context context = getParentActivity();
        if (context == null) {
            context = view.getContext();
        }
        if (position == tgWsProxyRow) {
            boolean enabled = !TgWsProxyController.isEnabled();
            TgWsProxyController.setEnabled(enabled);
            if (view instanceof TextCheckCell checkCell) {
                checkCell.setChecked(enabled);
            }
            if (enabled) {
                // Mutual exclusivity: only one bypass can own Telegram's proxy.
                if (XrayController.isEnabled()) {
                    XrayController.setEnabled(false);
                    XrayController.stop(context);
                }
                TgWsProxyController.startFromSettings(context, true);
            } else {
                TgWsProxyController.stop(context);
            }
            view.postDelayed(this::refreshRows, 180);
        } else if (position == vlessEnabledRow) {
            handleVlessToggle(context, view);
        } else if (position == vlessLinkRow) {
            showLinkDialog(false);
        } else if (position == vlessSettingsRow) {
            presentFragment(new VlessSettingsActivity());
        } else if (position == vlessNotificationRow) {
            // Toggled off the persisted value, the same one the service reads,
            // so the row and the notification can never disagree.
            boolean enabled = !XrayController.isNotificationEnabled();
            XrayController.setNotificationEnabled(enabled);
            if (view instanceof TextCheckCell checkCell) {
                checkCell.setChecked(enabled);
            }
            // Only the notification is rebuilt; restarting the service here
            // would drop every connection for a purely cosmetic change.
            XrayController.applyNotificationVisibility(context);
        } else if (position == portRow) {
            showPortDialog();
        } else if (position == wsPoolRow) {
            showWsPoolDialog();
        } else if (position == secretKeyRow) {
            showSecretKeyDialog();
        } else if (position == generateSecretKeyRow) {
            TgWsProxyController.setSecretKey(generateSecretKey());
            TgWsProxyController.restartIfEnabled(context);
            listAdapter.notifyDataSetChanged();
        } else if (position == cloudflareCdnRow) {
            NaConfig.INSTANCE.getTgWsProxyCloudflareCdn().toggleConfigBool();
            TgWsProxyController.restartIfEnabled(context);
            if (view instanceof TextCheckCell checkCell) {
                checkCell.setChecked(NaConfig.INSTANCE.getTgWsProxyCloudflareCdn().Bool());
            }
        } else if (position == notificationEnabledRow) {
            // Toggled off the persisted value, the same one the service reads,
            // so the row and the notification can never disagree.
            boolean enabled = !TgWsProxyController.isNotificationEnabled();
            TgWsProxyController.setNotificationEnabled(enabled);
            if (view instanceof TextCheckCell checkCell) {
                checkCell.setChecked(enabled);
            }
            // Only the notification is rebuilt; restarting the service here
            // would drop every connection for a purely cosmetic change.
            TgWsProxyController.applyNotificationVisibility(context);
        }
    }

    @Override
    protected BaseListAdapter createAdapter(Context context) {
        return new ListAdapter(context);
    }

    @Override
    protected String getActionBarTitle() {
        return getString(R.string.BypassBlocking);
    }

    private void refreshRows() {
        updateRows();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void handleVlessToggle(Context context, View view) {
        if (XrayController.isEnabled()) {
            XrayController.stop(context);
            refreshRows();
            return;
        }
        // Without a usable link there is nothing to start, so ask for one first
        // instead of flipping a switch that would immediately bounce back.
        if (!VlessConfig.isValidVlessUrl(XrayController.savedVlessKey())) {
            showLinkDialog(true);
            return;
        }
        enableVlessAndStart(context);
    }

    private void enableVlessAndStart(Context context) {
        // Mutual exclusivity: the TG WS proxy owns the same local proxy slot.
        if (NaConfig.INSTANCE.getTgWsProxyEnabled().Bool()) {
            TgWsProxyController.stop(context);
        }
        if (!XrayController.startFromSettings(context, true)) {
            XrayController.setEnabled(false);
        }
        refreshRows();
    }

    private void showLinkDialog(boolean startAfterSave) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.VlessVpnLink));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor input = createEditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setHint(getString(R.string.VlessVpnLinkHint));
        input.setText(XrayController.savedVlessKey());
        input.setSelection(input.length());
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), dp(4), dp(10), 0));

        builder.setView(layout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Save), null);
        AlertDialog dialog = builder.create();
        // The positive button is wired manually so an invalid link shows an inline
        // error instead of dismissing the dialog and losing what was typed.
        // The listener MUST be attached before showDialog(): that call shows the
        // dialog immediately, and Dialog dispatches its show event exactly once,
        // so a listener registered afterwards never runs — which left the default
        // "dismiss and do nothing" button behaviour and made the toggle look dead.
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
                boolean wasEnabled = XrayController.isEnabled();
                dialog.dismiss();
                Context current = getParentActivity();
                if (current == null) {
                    refreshRows();
                    return;
                }
                if (startAfterSave || wasEnabled) {
                    enableVlessAndStart(current);
                } else {
                    refreshRows();
                }
            });
        });
        showDialog(dialog);
    }

    private String getVlessLinkPreview() {
        String link = XrayController.savedVlessKey();
        if (link.isEmpty()) {
            return getString(R.string.VlessVpnNotConfigured);
        }
        try {
            Uri uri = Uri.parse(link);
            String name = uri.getFragment();
            if (name != null && !name.trim().isEmpty()) {
                return name.trim();
            }
            String host = uri.getHost();
            if (host != null && !host.isEmpty()) {
                return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
            }
        } catch (Exception ignored) {
        }
        return getString(R.string.VlessVpnConfigured);
    }

    private void showPortDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.TgWsProxyPort));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = createEditText(context);
        editText.setInputType(InputType.TYPE_CLASS_NUMBER);
        editText.setText(String.valueOf(NaConfig.INSTANCE.getTgWsProxyPort().Int()));
        editText.setSelection(editText.length());
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), 0, dp(10), 0));

        builder.setView(linearLayout);
        builder.setPositiveButton(getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        // Same ordering requirement as showLinkDialog(): wire the button before
        // the dialog is shown, otherwise the show event has already fired.
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int port;
            try {
                port = Integer.parseInt(editText.getText().toString().trim());
            } catch (Exception ignored) {
                port = 0;
            }
            if (port < 1 || port > 65535) {
                editText.setError("1-65535");
                AndroidUtil.showInputError(editText);
                return;
            }
            TgWsProxyController.setPort(port);
            TgWsProxyController.restartIfEnabled(context);
            listAdapter.notifyItemChanged(portRow);
            dialog.dismiss();
        }));
        showDialog(dialog);
    }

    private void showWsPoolDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        CharSequence[] items = new CharSequence[]{"2", "4", "6"};
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.TgWsProxyPool));
        builder.setItems(items, (dialog, which) -> {
            TgWsProxyController.setPoolSize(WS_POOL_VALUES[which]);
            TgWsProxyController.restartIfEnabled(context);
            listAdapter.notifyItemChanged(wsPoolRow);
        });
        showDialog(builder.create());
    }

    private void showSecretKeyDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        ensureSecretKey();
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.TgWsProxySecretKey));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = createEditText(context);
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setText(NaConfig.INSTANCE.getTgWsProxySecret().String());
        editText.setSelection(editText.length());
        linearLayout.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), 0, dp(10), 0));

        builder.setView(linearLayout);
        builder.setPositiveButton(getString(R.string.OK), (dialog, which) -> {
            String secret = editText.getText().toString().trim();
            TgWsProxyController.setSecretKey(secret.isEmpty() ? generateSecretKey() : secret);
            TgWsProxyController.restartIfEnabled(context);
            listAdapter.notifyItemChanged(secretKeyRow);
        });
        showDialog(builder.create());
    }

    private EditTextBoldCursor createEditText(Context context) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        editText.setHandlesColor(Theme.getColor(Theme.key_chat_TextSelectionCursor, resourcesProvider));
        editText.setBackground(null);
        editText.setLineColors(Theme.getColor(Theme.key_windowBackgroundWhiteInputField, resourcesProvider), Theme.getColor(Theme.key_windowBackgroundWhiteInputFieldActivated, resourcesProvider), Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
        editText.setPadding(0, 0, 0, dp(6));
        editText.requestFocus();
        return editText;
    }

    private void ensureSecretKey() {
        TgWsProxyController.ensureSecretKey();
    }

    private String generateSecretKey() {
        return TgWsProxyController.generateSecretKey();
    }

    private String getSecretPreview() {
        String secret = NaConfig.INSTANCE.getTgWsProxySecret().String();
        if (secret.isEmpty()) {
            return getString(R.string.None);
        }
        if (secret.length() <= 8) {
            return secret;
        }
        return secret.substring(0, 4) + "..." + secret.substring(secret.length() - 4);
    }

    private class ListAdapter extends BaseListAdapter {

        public ListAdapter(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, boolean partial) {
            switch (holder.getItemViewType()) {
                case TYPE_SHADOW: {
                    holder.itemView.setBackground(Theme.getThemedDrawable(mContext, R.drawable.greydivider, Theme.key_windowBackgroundGrayShadow));
                    break;
                }
                case TYPE_HEADER: {
                    HeaderCell cell = (HeaderCell) holder.itemView;
                    cell.setText(getString(position == vlessHeaderRow ? R.string.VlessVpn : R.string.TgWsProxyHeader));
                    break;
                }
                case TYPE_CHECK: {
                    TextCheckCell cell = (TextCheckCell) holder.itemView;
                    if (position == tgWsProxyRow) {
                        // Persisted values, not the in-memory NaConfig copies: the
                        // service reads the same ones, so a row can never show
                        // "off" next to a notification that is on.
                        cell.setTextAndCheck(getString(R.string.TgWsProxy), TgWsProxyController.isEnabled(), false);
                    } else if (position == cloudflareCdnRow) {
                        cell.setTextAndCheck(getString(R.string.TgWsProxyCloudflareCdn), NaConfig.INSTANCE.getTgWsProxyCloudflareCdn().Bool(), false);
                    } else if (position == notificationEnabledRow) {
                        cell.setTextAndCheck(getString(R.string.TgWsProxyNotificationEnabled), TgWsProxyController.isNotificationEnabled(), false);
                    } else if (position == vlessEnabledRow) {
                        // Divider only when the link/notification rows follow it.
                        cell.setTextAndCheck(getString(R.string.VlessVpn), XrayController.isEnabled(), vlessLinkRow != -1);
                    } else if (position == vlessNotificationRow) {
                        cell.setTextAndCheck(getString(R.string.VlessNotificationEnabled), XrayController.isNotificationEnabled(), false);
                    }
                    cell.getCheckBox().setColors(Theme.key_switchTrack, Theme.key_switchTrackChecked, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundWhite);
                    break;
                }
                case TYPE_SETTINGS: {
                    TextSettingsCell cell = (TextSettingsCell) holder.itemView;
                    if (position == portRow) {
                        cell.setTextAndValue(getString(R.string.TgWsProxyPort), String.valueOf(NaConfig.INSTANCE.getTgWsProxyPort().Int()), true);
                    } else if (position == wsPoolRow) {
                        cell.setTextAndValue(getString(R.string.TgWsProxyPool), String.valueOf(NaConfig.INSTANCE.getTgWsProxyPool().Int()), true);
                    } else if (position == secretKeyRow) {
                        cell.setTextAndValue(getString(R.string.TgWsProxySecretKey), getSecretPreview(), true);
                    } else if (position == generateSecretKeyRow) {
                        cell.setTextAndIcon(getString(R.string.TgWsProxyGenerateSecretKey), R.drawable.msg_retry_solar, true);
                    } else if (position == vlessLinkRow) {
                        cell.setTextAndValue(getString(R.string.VlessVpnLink), getVlessLinkPreview(), true);
                    } else if (position == vlessSettingsRow) {
                        // Key, subscription and the server list all live behind this row.
                        cell.setTextAndIcon(getString(R.string.VlessSettings), R.drawable.msg_settings_old, true);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow || position == vlessHeaderRow) {
                return TYPE_HEADER;
            } else if (position == tgWsProxyRow || position == cloudflareCdnRow || position == notificationEnabledRow
                    || position == vlessEnabledRow || position == vlessNotificationRow) {
                return TYPE_CHECK;
            } else if (position == portRow || position == wsPoolRow || position == secretKeyRow
                    || position == generateSecretKeyRow || position == vlessLinkRow
                    || position == vlessSettingsRow) {
                return TYPE_SETTINGS;
            } else if (position == shadowRow || position == vlessShadowRow) {
                return TYPE_SHADOW;
            }
            return TYPE_SHADOW;
        }
    }
}
