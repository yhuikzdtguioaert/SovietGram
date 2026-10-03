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
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextRadioCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Locale;

import sovietgram.com.NaConfig;
import sovietgram.com.proxy.ProxyLinks;
import sovietgram.com.proxy.SubscriptionParser;
import sovietgram.com.proxy.TgWsProxyController;
import sovietgram.com.proxy.VlessConfig;
import sovietgram.com.proxy.VlessSubscription;
import sovietgram.com.proxy.XrayController;
import sovietgram.com.proxy.XrayOptions;
import sovietgram.com.proxy.XrayPing;
import sovietgram.com.proxy.XraySettings;
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
    private int fakeTlsRow;
    private int fakeTlsDomainRow;
    private int notificationEnabledRow;
    private int shadowRow;

    private int vlessHeaderRow;
    private int vlessEnabledRow;
    private int vlessLinkRow;
    private int vlessUpdateRow;
    private int vlessLinkInfoRow;
    private int vlessServersHeaderRow;
    private int vlessPingRow;
    private int vlessPingTypeRow;
    private int vlessServersStart;
    private int vlessServersEnd;
    private int vlessServersShadowRow;
    private int vlessOptionsHeaderRow;
    private int fragmentRow;
    private int fragmentPacketsRow;
    private int fragmentLengthRow;
    private int fragmentIntervalRow;
    private int fragmentMaxSplitRow;
    private int noisesRow;
    private int noiseTypeRow;
    private int noisePacketRow;
    private int noiseDelayRow;
    private int noiseApplyRow;
    private int muxRow;
    private int muxConcurrencyRow;
    private int muxXudpRow;
    private int muxUdp443Row;
    private int fingerprintRow;
    private int dnsRow;
    private int maskHappRow;
    private int vlessNotificationRow;
    private int vlessOptionsInfoRow;

    private final List<String> servers = new ArrayList<>();
    private XrayOptions.Snapshot options = new XrayOptions.Snapshot(false, "tlshello", "100-200", "10-20", "", false, "rand", "50-100", "10-20", "ip", false, 8, 16, "reject", "", "", true);
    private boolean fetching;

    // Latest ping of each server by its link, in milliseconds; XrayPing.FAILED for no answer. Lives only as long as the screen.
    private final Map<String, Integer> pings = new HashMap<>();
    private int pingRun;
    private int pingDone;
    private int pingTotal;
    private ExecutorService pingPool;

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
            fakeTlsRow = addRow("TgWsProxyFakeTls");
            fakeTlsDomainRow = TgWsProxyController.isFakeTlsEnabled() ? addRow("TgWsProxyFakeTlsDomain") : -1;
            notificationEnabledRow = addRow("TgWsProxyNotificationEnabled");
        } else {
            portRow = wsPoolRow = secretKeyRow = generateSecretKeyRow = cloudflareCdnRow = fakeTlsRow = fakeTlsDomainRow = notificationEnabledRow = -1;
        }
        shadowRow = addRow();

        // Vless VPN lives in its own section on this same screen. Everything below its switch only
        // exists once the switch is on, mirroring how the TG WS proxy section above reveals its own
        // settings, and appears with the list's insert animation.
        vlessHeaderRow = addRow();
        vlessEnabledRow = addRow("VlessVpn");
        vlessLinkRow = vlessUpdateRow = vlessLinkInfoRow = -1;
        vlessServersHeaderRow = vlessServersStart = vlessServersEnd = vlessServersShadowRow = -1;
        vlessPingRow = vlessPingTypeRow = -1;
        vlessOptionsHeaderRow = fragmentRow = fragmentPacketsRow = fragmentLengthRow = fragmentIntervalRow = fragmentMaxSplitRow = -1;
        noisesRow = noiseTypeRow = noisePacketRow = noiseDelayRow = noiseApplyRow = -1;
        muxRow = muxConcurrencyRow = muxXudpRow = muxUdp443Row = -1;
        fingerprintRow = dnsRow = maskHappRow = vlessNotificationRow = vlessOptionsInfoRow = -1;
        servers.clear();
        if (NaConfig.INSTANCE.getVlessEnabled().Bool()) {
            options = XraySettings.snapshot();
            servers.addAll(XrayController.savedServers());
            vlessLinkRow = addRow("VlessVpnLink");
            if (!XrayController.savedSubscriptionUrl().isEmpty()) {
                vlessUpdateRow = addRow();
            }
            vlessLinkInfoRow = addRow();
            if (!servers.isEmpty()) {
                vlessServersHeaderRow = addRow();
                vlessPingRow = addRow();
                vlessPingTypeRow = addRow();
                vlessServersStart = rowCount;
                rowCount += servers.size();
                vlessServersEnd = rowCount;
                vlessServersShadowRow = addRow();
            }
            vlessOptionsHeaderRow = addRow();
            fragmentRow = addRow();
            if (options.getFragmentEnabled()) {
                fragmentPacketsRow = addRow();
                fragmentLengthRow = addRow();
                fragmentIntervalRow = addRow();
                fragmentMaxSplitRow = addRow();
            }
            noisesRow = addRow();
            if (options.getNoisesEnabled()) {
                noiseTypeRow = addRow();
                noisePacketRow = addRow();
                noiseDelayRow = addRow();
                noiseApplyRow = addRow();
            }
            muxRow = addRow();
            if (options.getMuxEnabled()) {
                muxConcurrencyRow = addRow();
                muxXudpRow = addRow();
                muxUdp443Row = addRow();
            }
            fingerprintRow = addRow();
            dnsRow = addRow();
            maskHappRow = addRow();
            vlessNotificationRow = addRow("VlessNotificationEnabled");
        }
        // The last row of the section is always this one, so what a switch reveals or hides is one run of
        // rows between the switch and it. Switched off it is only the thin divider the section ends with.
        vlessOptionsInfoRow = addRow();
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
            // Switching one bypass on switches the other off, and then two sections change at once:
            // that is not one run of rows, so the list is simply reloaded.
            final boolean both = enabled && XrayController.isEnabled();
            if (enabled) {
                // Mutual exclusivity: only one bypass can own Telegram's proxy.
                if (both) {
                    XrayController.setEnabled(false);
                    XrayController.stop(context);
                }
                TgWsProxyController.startFromSettings(context, true);
            } else {
                TgWsProxyController.stop(context);
            }
            final int anchor = position;
            view.postDelayed(() -> {
                if (both) {
                    refreshRows();
                } else {
                    animateRows(anchor, null);
                }
            }, 180);
        } else if (position == vlessEnabledRow) {
            handleVlessToggle(context, view);
        } else if (position == vlessLinkRow) {
            showLinkDialog(false);
        } else if (position == vlessUpdateRow) {
            refreshSubscription();
        } else if (position == vlessPingRow) {
            togglePing();
        } else if (position == vlessPingTypeRow) {
            showPingTypeDialog();
        } else if (vlessServersStart != -1 && position >= vlessServersStart && position < vlessServersEnd) {
            pickServer(context, position - vlessServersStart);
        } else if (position == fragmentRow) {
            toggleOption(fragmentRow, "fragment", !options.getFragmentEnabled(), context);
        } else if (position == noisesRow) {
            toggleOption(noisesRow, "noises", !options.getNoisesEnabled(), context);
        } else if (position == muxRow) {
            toggleOption(muxRow, "mux", !options.getMuxEnabled(), context);
        } else if (position == maskHappRow) {
            boolean enabled = !options.getMaskAsHapp();
            XraySettings.putBoolean("mask_happ", enabled);
            options = XraySettings.snapshot();
            if (view instanceof TextCheckCell checkCell) {
                checkCell.setChecked(enabled);
            }
        } else if (position == fragmentPacketsRow) {
            showChoice(R.string.VlessFragmentPackets, XrayOptions.INSTANCE.getFRAGMENT_PACKETS(), options.getFragmentPackets(),
                    value -> storeOption("fragment_packets", value, position));
        } else if (position == fragmentLengthRow) {
            showRangeDialog(R.string.VlessFragmentLength, options.getFragmentLength(), false,
                    value -> storeOption("fragment_length", value, position));
        } else if (position == fragmentIntervalRow) {
            showRangeDialog(R.string.VlessFragmentInterval, options.getFragmentInterval(), false,
                    value -> storeOption("fragment_interval", value, position));
        } else if (position == fragmentMaxSplitRow) {
            showRangeDialog(R.string.VlessFragmentMaxSplit, options.getFragmentMaxSplit(), true,
                    value -> storeOption("fragment_maxsplit", value, position));
        } else if (position == noiseTypeRow) {
            showChoice(R.string.VlessNoiseType, XrayOptions.INSTANCE.getNOISE_TYPES(), options.getNoiseType(),
                    value -> storeOption("noise_type", value, position));
        } else if (position == noisePacketRow) {
            showTextDialog(R.string.VlessNoisePacket, options.getNoisePacket(),
                    value -> storeOption("noise_packet", value, position));
        } else if (position == noiseDelayRow) {
            showRangeDialog(R.string.VlessNoiseDelay, options.getNoiseDelay(), false,
                    value -> storeOption("noise_delay", value, position));
        } else if (position == noiseApplyRow) {
            showChoice(R.string.VlessNoiseApplyTo, XrayOptions.INSTANCE.getNOISE_APPLY_TO(), options.getNoiseApplyTo(),
                    value -> storeOption("noise_applyto", value, position));
        } else if (position == muxConcurrencyRow) {
            showNumberDialog(R.string.VlessMuxConcurrency, options.getMuxConcurrency(),
                    value -> storeNumber("mux_concurrency", value, position));
        } else if (position == muxXudpRow) {
            showNumberDialog(R.string.VlessMuxXudp, options.getMuxXudpConcurrency(),
                    value -> storeNumber("mux_xudp", value, position));
        } else if (position == muxUdp443Row) {
            showChoice(R.string.VlessMuxUdp443, XrayOptions.INSTANCE.getUDP443_POLICIES(), options.getMuxXudpProxyUdp443(),
                    value -> storeOption("mux_udp443", value, position));
        } else if (position == fingerprintRow) {
            showChoice(R.string.VlessFingerprint, XrayOptions.INSTANCE.getFINGERPRINTS(), options.getFingerprint(),
                    value -> storeOption("fingerprint", value, position));
        } else if (position == dnsRow) {
            List<String> names = new ArrayList<>();
            names.add("");
            names.addAll(XrayOptions.INSTANCE.getDNS_SERVERS().keySet());
            showChoice(R.string.VlessDns, names, options.getDns(), value -> storeOption("dns", value, position));
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
        } else if (position == fakeTlsRow) {
            // The domain row exists only while Fake TLS is on, right below this switch, so what
            // the switch reveals or hides is one run of rows after it.
            final boolean enabled = !TgWsProxyController.isFakeTlsEnabled();
            if (view instanceof TextCheckCell checkCell) {
                checkCell.setChecked(enabled);
            }
            animateRows(position, () -> TgWsProxyController.setFakeTlsEnabled(enabled));
            TgWsProxyController.restartIfEnabled(context);
        } else if (position == fakeTlsDomainRow) {
            showFakeTlsDomainDialog();
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

    /**
     * Rebuilds the rows and animates the difference. Whatever a switch reveals or hides sits directly
     * after it, so the change is one run of rows right below [anchor]; the list's item animator does the rest.
     */
    private void animateRows(int anchor, Runnable change) {
        final int before = rowCount;
        if (change != null) {
            change.run();
        }
        updateRows();
        if (listAdapter == null) {
            return;
        }
        final int after = rowCount;
        if (after > before) {
            listAdapter.notifyItemRangeInserted(anchor + 1, after - before);
        } else if (after < before) {
            listAdapter.notifyItemRangeRemoved(anchor + 1, before - after);
        }
        listAdapter.notifyItemChanged(anchor);
        listAdapter.notifyItemChanged(vlessOptionsInfoRow);
    }

    private void handleVlessToggle(Context context, View view) {
        if (XrayController.isEnabled()) {
            XrayController.stop(context);
            animateRows(vlessEnabledRow, null);
            return;
        }
        // Without a usable server there is nothing to start, so ask for a link first
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
        final int anchor = vlessEnabledRow;
        final boolean both = NaConfig.INSTANCE.getTgWsProxyEnabled().Bool();
        Runnable start = () -> {
            if (!XrayController.startFromSettings(context, true)) {
                XrayController.setEnabled(false);
            }
        };
        if (both) {
            start.run();
            refreshRows();
        } else {
            animateRows(anchor, start);
        }
    }

    private String pingTypeName(int method) {
        switch (method) {
            case XrayPing.HEAD: return getString(R.string.VlessPingHead);
            case XrayPing.TCP: return getString(R.string.VlessPingTcp);
            case XrayPing.ICMP: return getString(R.string.VlessPingIcmp);
            default: return getString(R.string.VlessPingGet);
        }
    }

    private void showPingTypeDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        final int current = XrayPing.method();
        CharSequence[] labels = new CharSequence[4];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = pingTypeName(i) + (i == current ? "  ✓" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.VlessPingType));
        builder.setItems(labels, (dialog, which) -> {
            XrayPing.setMethod(which);
            // Numbers from another way of measuring would sit next to the new name and mislead.
            pings.clear();
            stopPing();
            if (listAdapter != null && vlessPingRow != -1) {
                listAdapter.notifyItemRangeChanged(vlessPingRow, vlessServersEnd - vlessPingRow);
            }
        });
        showDialog(builder.create());
    }

    private void stopPing() {
        pingRun++;
        pingTotal = 0;
        if (pingPool != null) {
            pingPool.shutdownNow();
            pingPool = null;
        }
    }

    private void togglePing() {
        if (pingTotal > 0) {
            stopPing();
            if (listAdapter != null && vlessPingRow != -1) {
                listAdapter.notifyItemChanged(vlessPingRow);
            }
            return;
        }
        if (servers.isEmpty()) {
            return;
        }
        final int run = ++pingRun;
        final int method = XrayPing.method();
        final List<String> snapshot = new ArrayList<>(servers);
        pings.clear();
        pingDone = 0;
        pingTotal = snapshot.size();
        // A handful at a time: every "via proxy" ping starts a core of its own.
        final ExecutorService pool = Executors.newFixedThreadPool(method == XrayPing.GET || method == XrayPing.HEAD ? 4 : 8);
        pingPool = pool;
        if (listAdapter != null) {
            listAdapter.notifyItemRangeChanged(vlessPingRow, vlessServersEnd - vlessPingRow);
        }
        for (String uri : snapshot) {
            pool.execute(() -> {
                final int result = XrayPing.ping(method, uri);
                AndroidUtilities.runOnUIThread(() -> {
                    if (run != pingRun) {
                        return;
                    }
                    pings.put(uri, result);
                    pingDone++;
                    final boolean finished = pingDone >= pingTotal;
                    if (finished) {
                        pingTotal = 0;
                        pool.shutdown();
                        pingPool = null;
                    }
                    if (listAdapter != null && vlessServersStart != -1) {
                        int index = servers.indexOf(uri);
                        if (index >= 0) {
                            listAdapter.notifyItemChanged(vlessServersStart + index);
                        }
                        listAdapter.notifyItemChanged(vlessPingRow);
                    }
                });
            });
        }
    }

    @Override
    public void onFragmentDestroy() {
        stopPing();
        super.onFragmentDestroy();
    }

    private void pickServer(Context context, int index) {
        if (!XrayController.selectServer(context, index)) {
            refreshRows();
            return;
        }
        if (listAdapter != null) {
            listAdapter.notifyItemRangeChanged(vlessServersStart, vlessServersEnd - vlessServersStart);
        }
        BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check, getString(R.string.VlessServerSelected)).show();
    }

    // ------------------------------------------------------------------ subscription link

    private void showLinkDialog(boolean startAfterSave) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.VlessSubLinkTitle));

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor input = createEditText(context);
        input.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(false);
        input.setMinLines(2);
        input.setMaxLines(6);
        input.setHint(getString(R.string.VlessSubLinkHint));
        // The saved subscription is a credential; it is shown only to whoever opens this dialog to change it.
        String saved = XrayController.savedSubscriptionUrl();
        input.setText(saved.isEmpty() ? "" : saved);
        input.setSelection(input.length());
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), dp(4), dp(10), 0));

        builder.setView(layout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Save), null);
        AlertDialog dialog = builder.create();
        // The positive button is wired manually so an invalid link shows an inline error instead of
        // dismissing the dialog and losing what was typed. The listener MUST be attached before
        // showDialog(): that call shows the dialog immediately and the show event fires exactly once.
        dialog.setOnShowListener(ignored -> {
            input.requestFocus();
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String value = input.getText().toString().trim();
                String lower = value.toLowerCase(Locale.US);
                if (lower.startsWith("http://") || lower.startsWith("https://")) {
                    dialog.dismiss();
                    loadSubscription(value, startAfterSave);
                    return;
                }
                List<String> links = SubscriptionParser.parse(value, null);
                if (links.isEmpty()) {
                    input.setError(getString(R.string.VlessSubLinkInvalid));
                    AndroidUtil.showInputError(input);
                    return;
                }
                XrayController.applyLinks(links);
                dialog.dismiss();
                afterServersChanged(startAfterSave);
            });
        });
        showDialog(dialog);
    }

    private void afterServersChanged(boolean startAfterSave) {
        Context current = getParentActivity();
        if (current == null) {
            return;
        }
        if (startAfterSave) {
            enableVlessAndStart(current);
        } else {
            if (XrayController.isEnabled()) {
                XrayController.restartIfEnabled(current);
            }
            // A new server list is not one run of rows right after the link row (the update row, the
            // servers and the shadow are spread over the section, and a same-sized list changes only
            // content), so an insert/remove animation would leave stale or mismatched server rows.
            refreshRows();
        }
    }

    private void refreshSubscription() {
        String url = XrayController.savedSubscriptionUrl();
        if (url.isEmpty()) {
            showLinkDialog(false);
            return;
        }
        loadSubscription(url, false);
    }

    /** Downloads the subscription off the main thread (as Happ would), stores it and picks the server. */
    private void loadSubscription(String url, boolean startAfterSave) {
        Context context = getParentActivity();
        if (context == null || fetching) {
            return;
        }
        fetching = true;
        AlertDialog progress = new AlertDialog(context, AlertDialog.ALERT_TYPE_SPINNER, resourcesProvider);
        progress.setCanCancel(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            VlessSubscription.Result fetched = null;
            String error = null;
            try {
                fetched = VlessSubscription.fetchDetailed(url);
            } catch (Throwable e) {
                // Only the reason is surfaced, never the URL.
                error = e.getMessage();
                if (error == null || error.isEmpty()) {
                    error = e.getClass().getSimpleName();
                }
            }
            final VlessSubscription.Result result = fetched;
            final String failure = error;
            AndroidUtilities.runOnUIThread(() -> {
                fetching = false;
                progress.dismiss();
                if (result == null) {
                    BulletinFactory.of(this).createErrorBulletin(getString(R.string.VlessSubscriptionFailed) + ": " + failure).show();
                    return;
                }
                XrayController.applySubscription(url, result);
                BulletinFactory.of(this).createSimpleBulletin(R.raw.contact_check,
                        LocaleController.formatString(R.string.VlessSubscriptionUpdated, result.getServers().size())).show();
                afterServersChanged(startAfterSave);
            });
        });
    }

    // ------------------------------------------------------------------ connection options

    private void toggleOption(int anchor, String key, boolean value, Context context) {
        animateRows(anchor, () -> {
            XraySettings.putBoolean(key, value);
            options = XraySettings.snapshot();
        });
        XrayController.restartIfEnabled(context);
    }

    private void storeOption(String key, String value, int row) {
        XraySettings.putString(key, value);
        options = XraySettings.snapshot();
        optionChanged(row);
    }

    private void storeNumber(String key, int value, int row) {
        XraySettings.putInt(key, value);
        options = XraySettings.snapshot();
        optionChanged(row);
    }

    private void optionChanged(int row) {
        Context context = getParentActivity();
        if (listAdapter != null) {
            listAdapter.notifyItemChanged(row);
        }
        if (context != null) {
            XrayController.restartIfEnabled(context);
        }
    }

    private interface Pick {
        void on(String value);
    }

    private interface PickNumber {
        void on(int value);
    }

    private void showChoice(int titleRes, List<String> values, String current, Pick pick) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        CharSequence[] labels = new CharSequence[values.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = choiceLabel(titleRes, values.get(i)) + (values.get(i).equals(current) ? "  ✓" : "");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(titleRes));
        builder.setItems(labels, (dialog, which) -> pick.on(values.get(which)));
        showDialog(builder.create());
    }

    private void showChoice(int titleRes, java.util.Collection<String> values, String current, Pick pick) {
        showChoice(titleRes, new ArrayList<>(values), current, pick);
    }

    private String choiceLabel(int titleRes, String value) {
        if (value.isEmpty()) {
            return getString(titleRes == R.string.VlessDns ? R.string.VlessDnsSystem : R.string.VlessFingerprintServer);
        }
        if (titleRes == R.string.VlessDns && !value.isEmpty()) {
            return value.substring(0, 1).toUpperCase(Locale.US) + value.substring(1);
        }
        return value;
    }

    private void showTextDialog(int titleRes, String current, Pick pick) {
        showInputDialog(titleRes, current, false, InputType.TYPE_CLASS_TEXT, value -> value.isEmpty()
                ? getString(R.string.VlessInvalidValue) : null, pick);
    }

    private void showRangeDialog(int titleRes, String current, boolean allowEmpty, Pick pick) {
        showInputDialog(titleRes, current, allowEmpty, InputType.TYPE_CLASS_TEXT, value ->
                (value.isEmpty() && allowEmpty) || XrayOptions.isRange(value) ? null : getString(R.string.VlessInvalidRange), pick);
    }

    private void showNumberDialog(int titleRes, int current, PickNumber pick) {
        showInputDialog(titleRes, String.valueOf(current), false, InputType.TYPE_CLASS_NUMBER, value -> {
            try {
                int n = Integer.parseInt(value);
                return n >= 1 && n <= 1024 ? null : "1-1024";
            } catch (Exception e) {
                return "1-1024";
            }
        }, value -> pick.on(Integer.parseInt(value)));
    }

    private interface Validator {
        String error(String value);
    }

    private void showInputDialog(int titleRes, String current, boolean allowEmpty, int inputType, Validator validator, Pick pick) {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(titleRes));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor input = createEditText(context);
        input.setInputType(inputType | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setText(current);
        input.setSelection(input.length());
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), 0, dp(10), 0));
        builder.setView(layout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String value = input.getText().toString().trim();
            String error = validator.error(value);
            if (error != null) {
                input.setError(error);
                AndroidUtil.showInputError(input);
                return;
            }
            pick.on(value);
            dialog.dismiss();
        }));
        showDialog(dialog);
    }

    // ------------------------------------------------------------------ summaries (never a link or a key)

    private String subscriptionSummary() {
        List<String> parts = new ArrayList<>();
        if (servers.size() > 1) {
            parts.add(LocaleController.formatString(R.string.VlessSubSummaryServers, servers.size()));
        } else if (servers.size() == 1) {
            parts.add(getString(R.string.VlessSubSingleServer));
        }
        return android.text.TextUtils.join(" · ", parts);
    }

    private String linkRowValue() {
        XraySettings.SubscriptionInfo info = XraySettings.subscriptionInfo();
        if (!XrayController.savedSubscriptionUrl().isEmpty() && !info.getTitle().isEmpty()) {
            return info.getTitle();
        }
        String key = XrayController.savedVlessKey();
        if (key.isEmpty()) {
            return getString(R.string.VlessVpnNotConfigured);
        }
        String name = ProxyLinks.displayName(key);
        return name.isEmpty() ? getString(R.string.VlessVpnConfigured) : name;
    }

    private String optionValue(int row) {
        if (row == fragmentPacketsRow) return options.getFragmentPackets();
        if (row == fragmentLengthRow) return options.getFragmentLength();
        if (row == fragmentIntervalRow) return options.getFragmentInterval();
        if (row == fragmentMaxSplitRow) return options.getFragmentMaxSplit().isEmpty() ? getString(R.string.None) : options.getFragmentMaxSplit();
        if (row == noiseTypeRow) return options.getNoiseType();
        if (row == noisePacketRow) return options.getNoisePacket();
        if (row == noiseDelayRow) return options.getNoiseDelay();
        if (row == noiseApplyRow) return options.getNoiseApplyTo();
        if (row == muxConcurrencyRow) return String.valueOf(options.getMuxConcurrency());
        if (row == muxXudpRow) return String.valueOf(options.getMuxXudpConcurrency());
        if (row == muxUdp443Row) return options.getMuxXudpProxyUdp443();
        if (row == fingerprintRow) return choiceLabel(R.string.VlessFingerprint, options.getFingerprint());
        if (row == dnsRow) return choiceLabel(R.string.VlessDns, options.getDns());
        return "";
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

    private void showFakeTlsDomainDialog() {
        Context context = getParentActivity();
        if (context == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(getString(R.string.TgWsProxyFakeTlsDomain));
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        EditTextBoldCursor input = createEditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setSingleLine(true);
        input.setText(TgWsProxyController.fakeTlsDomain());
        input.setSelection(input.length());
        layout.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, dp(8), 0, dp(10), 0));
        builder.setView(layout);
        builder.setNegativeButton(getString(R.string.Cancel), null);
        builder.setPositiveButton(getString(R.string.Save), (dialog, which) -> {
            TgWsProxyController.setFakeTlsDomain(input.getText().toString());
            TgWsProxyController.restartIfEnabled(context);
            if (fakeTlsDomainRow != -1) {
                listAdapter.notifyItemChanged(fakeTlsDomainRow);
            }
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
                    int title = R.string.TgWsProxyHeader;
                    if (position == vlessHeaderRow) {
                        title = R.string.VlessVpn;
                    } else if (position == vlessServersHeaderRow) {
                        title = R.string.VlessServers;
                    } else if (position == vlessOptionsHeaderRow) {
                        title = R.string.VlessConnectionOptions;
                    }
                    cell.setText(getString(title));
                    break;
                }
                case TYPE_INFO_PRIVACY: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == vlessLinkInfoRow) {
                        cell.setFixedSize(0);
                        cell.setText(getString(R.string.VlessSubLinkInfo));
                    } else if (vlessLinkRow != -1) {
                        cell.setFixedSize(0);
                        cell.setText(getString(R.string.VlessConnectionOptionsInfo) + "\n\n" + getString(R.string.VlessMaskHappInfo));
                    } else {
                        // Switched off: only the thin divider the section ends with.
                        cell.setFixedSize(12);
                        cell.setText(null);
                    }
                    break;
                }
                case TYPE_RADIO: {
                    TextRadioCell cell = (TextRadioCell) holder.itemView;
                    int index = position - vlessServersStart;
                    if (index < 0 || index >= servers.size()) {
                        break;
                    }
                    String uri = servers.get(index);
                    String name = ProxyLinks.displayName(uri);
                    if (name.isEmpty()) {
                        name = LocaleController.formatString(R.string.VlessServerFallbackName, index + 1);
                    }
                    boolean checked = uri.equals(XrayController.savedVlessKey());
                    String detail = ProxyLinks.describe(uri);
                    Integer ping = pings.get(uri);
                    if (ping != null) {
                        String shown = ping == XrayPing.FAILED ? getString(R.string.VlessPingTimeout) : ping + " ms";
                        detail = detail.isEmpty() ? shown : shown + " · " + detail;
                    }
                    cell.setTextAndValueAndCheck(name.trim(), detail, checked, false, index < servers.size() - 1);
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
                    } else if (position == fakeTlsRow) {
                        cell.setTextAndCheck(getString(R.string.TgWsProxyFakeTls), TgWsProxyController.isFakeTlsEnabled(), fakeTlsDomainRow != -1);
                    } else if (position == notificationEnabledRow) {
                        cell.setTextAndCheck(getString(R.string.TgWsProxyNotificationEnabled), TgWsProxyController.isNotificationEnabled(), false);
                    } else if (position == vlessEnabledRow) {
                        // Divider only when the link row follows it.
                        cell.setTextAndCheck(getString(R.string.VlessVpn), XrayController.isEnabled(), vlessLinkRow != -1);
                    } else if (position == vlessNotificationRow) {
                        cell.setTextAndCheck(getString(R.string.VlessNotificationEnabled), XrayController.isNotificationEnabled(), false);
                    } else if (position == fragmentRow) {
                        cell.setTextAndCheck(getString(R.string.VlessFragmentation), options.getFragmentEnabled(), options.getFragmentEnabled());
                    } else if (position == noisesRow) {
                        cell.setTextAndCheck(getString(R.string.VlessNoises), options.getNoisesEnabled(), options.getNoisesEnabled());
                    } else if (position == muxRow) {
                        cell.setTextAndCheck(getString(R.string.VlessMux), options.getMuxEnabled(), true);
                    } else if (position == maskHappRow) {
                        cell.setTextAndCheck(getString(R.string.VlessMaskHapp), options.getMaskAsHapp(), true);
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
                    } else if (position == fakeTlsDomainRow) {
                        cell.setTextAndValue(getString(R.string.TgWsProxyFakeTlsDomain), TgWsProxyController.fakeTlsDomain(), true);
                    } else if (position == vlessLinkRow) {
                        cell.setTextAndValue(getString(R.string.VlessSubLinkTitle), linkRowValue(), vlessUpdateRow != -1);
                    } else if (position == vlessUpdateRow) {
                        cell.setTextAndValue(getString(R.string.VlessSubscriptionRefresh), subscriptionSummary(), false);
                    } else if (position == vlessPingRow) {
                        cell.setTextAndValue(getString(R.string.VlessPingServers),
                                pingTotal > 0 ? LocaleController.formatString(R.string.VlessPingRunning, pingDone, pingTotal) : "", true);
                    } else if (position == vlessPingTypeRow) {
                        cell.setTextAndValue(getString(R.string.VlessPingType), pingTypeName(XrayPing.method()), false);
                    } else if (position == fingerprintRow) {
                        cell.setTextAndValue(getString(R.string.VlessFingerprint), optionValue(position), true);
                    } else if (position == dnsRow) {
                        cell.setTextAndValue(getString(R.string.VlessDns), optionValue(position), true);
                    } else {
                        // A sub-option of fragmentation, noises or mux: the last one of its group has no divider.
                        boolean last = position == fragmentMaxSplitRow || position == noiseApplyRow || position == muxUdp443Row;
                        int title = position == fragmentPacketsRow ? R.string.VlessFragmentPackets
                                : position == fragmentLengthRow ? R.string.VlessFragmentLength
                                : position == fragmentIntervalRow ? R.string.VlessFragmentInterval
                                : position == fragmentMaxSplitRow ? R.string.VlessFragmentMaxSplit
                                : position == noiseTypeRow ? R.string.VlessNoiseType
                                : position == noisePacketRow ? R.string.VlessNoisePacket
                                : position == noiseDelayRow ? R.string.VlessNoiseDelay
                                : position == noiseApplyRow ? R.string.VlessNoiseApplyTo
                                : position == muxConcurrencyRow ? R.string.VlessMuxConcurrency
                                : position == muxXudpRow ? R.string.VlessMuxXudp
                                : R.string.VlessMuxUdp443;
                        cell.setTextAndValue(getString(title), optionValue(position), !last);
                    }
                    break;
                }
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == headerRow || position == vlessHeaderRow || position == vlessServersHeaderRow
                    || position == vlessOptionsHeaderRow) {
                return TYPE_HEADER;
            } else if (position == tgWsProxyRow || position == cloudflareCdnRow || position == fakeTlsRow
                    || position == notificationEnabledRow || position == vlessEnabledRow || position == vlessNotificationRow || position == fragmentRow
                    || position == noisesRow || position == muxRow || position == maskHappRow) {
                return TYPE_CHECK;
            } else if (position == vlessLinkInfoRow || position == vlessOptionsInfoRow) {
                return TYPE_INFO_PRIVACY;
            } else if (vlessServersStart != -1 && position >= vlessServersStart && position < vlessServersEnd) {
                return TYPE_RADIO;
            } else if (position == portRow || position == wsPoolRow || position == secretKeyRow
                    || position == fakeTlsDomainRow || position == generateSecretKeyRow || position == vlessLinkRow || position == vlessUpdateRow
                    || position == vlessPingRow || position == vlessPingTypeRow
                    || position == fragmentPacketsRow || position == fragmentLengthRow || position == fragmentIntervalRow
                    || position == fragmentMaxSplitRow || position == noiseTypeRow || position == noisePacketRow
                    || position == noiseDelayRow || position == noiseApplyRow || position == muxConcurrencyRow
                    || position == muxXudpRow || position == muxUdp443Row || position == fingerprintRow
                    || position == dnsRow) {
                return TYPE_SETTINGS;
            }
            return TYPE_SHADOW;
        }
    }
}
