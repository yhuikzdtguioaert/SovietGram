package tw.nekomimi.nekogram.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.widget.EditText;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.function.Consumer;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects a provider account through the system browser.
 *
 * <p>Spotify opens the provider in the system browser, then polls a server OAuth state. The
 * server exchanges the authorization code and stores the provider token bundle.
 *
 * <p>Yandex Music uses a manual token, previewed and saved only after confirmation.
 */
public final class CustomProfileIntegrationOAuth {
    // UI-thread only: providers may coexist as saved integrations, but only one
    // sign-in dialog/browser attempt per Telegram account owns callbacks at a time.
    private static final Map<Integer, CustomProfileIntegrationOAuth> ACTIVE = new HashMap<>();
    private static final long POLL_MS = 2000;
    private static final long GIVE_UP_MS = 10 * 60 * 1000;
    private static final Pattern PASTED_TOKEN = Pattern.compile("access_token=([A-Za-z0-9._~+/-]{16,8192}={0,2})");

    private final BaseFragment fragment;
    private final int account;
    private final long owner;
    private final int service;
    private final Consumer<JSONObject> connected;
    private AlertDialog dialog;
    private String state;
    private long startedAt;
    private boolean finished;
    private int generation;

    private CustomProfileIntegrationOAuth(BaseFragment fragment, int account, int service, Consumer<JSONObject> connected) {
        this.fragment = fragment;
        this.account = account;
        this.owner = UserConfig.getInstance(account).getClientUserId();
        this.service = service;
        this.connected = connected;
    }

    /** Starts the sign-in for {@code service} (3 Yandex Music, 4 Spotify). */
    public static void begin(BaseFragment fragment, int account, int service, Consumer<JSONObject> connected) {
        if (fragment == null || fragment.isFinished || fragment.getParentActivity() == null) return;
        final CustomProfileIntegrationOAuth previous = ACTIVE.get(account);
        if (previous != null) previous.cancel();
        final CustomProfileIntegrationOAuth next = new CustomProfileIntegrationOAuth(fragment, account, service, connected);
        ACTIVE.put(account, next);
        next.start();
    }

    private boolean alive() {
        return !finished && !fragment.isFinished && fragment.getParentActivity() != null
                && ACTIVE.get(account) == this && UserConfig.selectedAccount == account
                && owner > 0 && UserConfig.getInstance(account).getClientUserId() == owner;
    }

    /** Invalidates client callbacks; cancelling an already-open provider callback needs server support. */
    public static void cancelPending(int account) {
        final CustomProfileIntegrationOAuth pending = ACTIVE.get(account);
        if (pending != null) pending.cancel();
    }

    private String path() {
        return "/v1/integration-accounts/" + CustomProfileIntegrations.key(service);
    }

    private void start() {
        if (service != 3 && service != 4) {
            fail(getString(R.string.CustomProfileIntegrationUnavailable));
            return;
        }
        final JSONObject body = new JSONObject();
        try { body.put("package", ApplicationLoader.applicationContext.getPackageName()); }
        catch (Exception ignored) { }
        final int request = ++generation;
        SovietGramApiClient.postSigned(account, path() + "/oauth/start", body, (response, error) -> {
            if (!alive() || request != generation) {
                final String abandonedState = response == null ? "" : response.optString("state");
                if (abandonedState.matches("[A-Za-z0-9_-]{32,64}")) {
                    SovietGramApiClient.deleteSigned(account, "/v1/integration-accounts/oauth/" + abandonedState,
                            (ignoredBody, ignoredError) -> { /* A late start response still owns a server state. */ });
                }
                return;
            }
            final String url = response == null ? null : response.optString("url", null);
            if (error != null || url == null || !url.startsWith("https://")) {
                fail(error != null && error.contains("oauth_not_configured")
                        ? getString(R.string.CustomProfileIntegrationNotConfigured)
                        : getString(R.string.CustomProfileIntegrationUnavailable));
                return;
            }
            final boolean manual = service == 3 && response.optBoolean("manual");
            state = response.optString("state");
            if (!manual && !state.matches("[A-Za-z0-9_-]{32,64}")) {
                fail(getString(R.string.CustomProfileIntegrationUnavailable));
                return;
            }
            if (!openBrowser(url)) {
                fail(getString(R.string.CustomProfileIntegrationNoBrowser));
                return;
            }
            if (manual) {
                askToken(null);
            } else {
                state = response.optString("state");
                startedAt = android.os.SystemClock.elapsedRealtime();
                showWaiting();
                AndroidUtilities.runOnUIThread(poll, POLL_MS);
            }
        });
    }

    private boolean openBrowser(String url) {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) return false;
        try {
            final Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            activity.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException e) {
            return false;
        }
    }

    // ------------------------------------------------------------ server-side sign-in

    private void showWaiting() {
        final Activity activity = fragment.getParentActivity();
        if (activity == null) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(CustomProfileIntegrations.serviceName(service))
                .setMessage(getString(R.string.CustomProfileIntegrationWaiting))
                .setNegativeButton(getString(R.string.Cancel), (d, which) -> cancel())
                .create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> cancel());
        dialog.show();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!alive()) {
                // The screen went away while the browser had the foreground: nothing left to wait for.
                cancel();
                return;
            }
            if (state == null) return;
            if (android.os.SystemClock.elapsedRealtime() - startedAt > GIVE_UP_MS) {
                dismiss();
                fail(getString(R.string.CustomProfileIntegrationUnavailable));
                return;
            }
            final int request = generation;
            SovietGramApiClient.get(account, "/v1/integration-accounts/oauth/" + state, (body, error) -> {
                if (!alive() || request != generation) return;
                final String status = body == null ? "" : body.optString("status");
                if ("done".equals(status)) {
                    dismiss();
                    finish(body);
                } else if ("error".equals(status)) {
                    dismiss();
                    fail("denied".equals(body.optString("error"))
                            ? getString(R.string.CustomProfileIntegrationDenied)
                            : getString(R.string.CustomProfileIntegrationUnavailable));
                } else {
                    // Pending, or a hiccup in the network while the browser has the foreground.
                    AndroidUtilities.runOnUIThread(this, POLL_MS);
                }
            });
        }
    };

    // ------------------------------------------------------------ pasted token (Yandex Music)

    private void askToken(String problem) {
        final Activity activity = fragment.getParentActivity();
        if (!alive() || activity == null) return;
        final EditText input = new EditText(activity);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint(getString(R.string.CustomProfileIntegrationPasteHint));
        // Whatever is on the clipboard when the dialog opens was there before the sign-in, so it is
        // only remembered, not used: a token copied on the provider's page afterwards is picked up
        // by the watch below the moment the user comes back, without anything to paste or press.
        staleClip = clipboardText(activity);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(CustomProfileIntegrations.serviceName(service))
                .setMessage((problem == null ? "" : problem + "\n\n") + getString(R.string.CustomProfileIntegrationPasteToken))
                .setView(input)
                .setPositiveButton(getString(R.string.Done), (d, which) -> {
                    final String token = extractToken(input.getText().toString());
                    if (token == null) askToken(getString(R.string.CustomProfileIntegrationUnavailable));
                    else preview(token);
                })
                .setNegativeButton(getString(R.string.Cancel), (d, which) -> cancel());
        dialog = builder.create();
        dialog.setOnCancelListener(d -> cancel());
        dialog.show();
        AndroidUtilities.cancelRunOnUIThread(watchClipboard);
        AndroidUtilities.runOnUIThread(watchClipboard, 800);
    }

    private String staleClip = "";

    private static String clipboardText(Context context) {
        try {
            final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            final ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                return clip.getItemAt(0).getText().toString();
            }
        } catch (RuntimeException ignored) { }
        return "";
    }

    /** Takes a token copied after the dialog opened and goes straight on to confirming the account. */
    private final Runnable watchClipboard = new Runnable() {
        @Override public void run() {
            final Activity activity = fragment.getParentActivity();
            if (!alive() || dialog == null || activity == null || !dialog.isShowing()) return;
            final String text = clipboardText(activity);
            // Only something that is plainly a Yandex token is taken on its own; anything else a user
            // happens to copy stays on the clipboard and is never sent anywhere.
            final String token = text.equals(staleClip) || !looksLikeToken(text) ? null : extractToken(text);
            if (token != null) {
                dismiss();
                preview(token);
                return;
            }
            AndroidUtilities.runOnUIThread(this, 800);
        }
    };

    private boolean looksLikeToken(String text) {
        final String t = text == null ? "" : text.trim();
        return t.contains("access_token=") || t.startsWith("y0_") || t.startsWith("AQAAAA");
    }

    private static String extractToken(String pasted) {
        if (pasted == null) return null;
        final String text = pasted.trim();
        final Matcher matcher = PASTED_TOKEN.matcher(text);
        if (matcher.find()) return matcher.group(1);
        return text.matches("[A-Za-z0-9._~+/-]{16,8192}={0,2}") && text.length() <= 8192 ? text : null;
    }

    private void preview(String token) {
        final JSONObject payload = new JSONObject();
        try { payload.put("token", token); } catch (Exception ignored) { return; }
        final int request = ++generation;
        SovietGramApiClient.postSigned(account, path() + "/preview", payload, (who, error) -> {
            if (!alive() || request != generation) return;
            if (error != null || who == null || who.optString("id").isEmpty()) {
                askToken(getString(R.string.CustomProfileIntegrationUnavailable));
                return;
            }
            confirm(payload, who);
        });
    }

    /** Shows whose account the token belongs to and links it once the user says so. */
    private void confirm(JSONObject payload, JSONObject who) {
        final Activity activity = fragment.getParentActivity();
        if (!alive() || activity == null) return;
        dialog = new AlertDialog.Builder(activity)
                .setTitle(CustomProfileIntegrations.serviceName(service))
                .setMessage(getString(R.string.CustomProfileIntegrationUseAccount) + "\n\n"
                        + who.optString("name") + "\n" + who.optString("id"))
                .setPositiveButton(getString(R.string.Done), (d, which) -> save(payload))
                .setNegativeButton(getString(R.string.Cancel), (d, which) -> cancel())
                .create();
        dialog.setOnCancelListener(d -> cancel());
        dialog.show();
    }

    private void save(JSONObject payload) {
        if (!alive()) return;
        final int request = ++generation;
        SovietGramApiClient.putSigned(account, path(), payload, (saved, error) -> {
            if (!alive() || request != generation) return;
            if (error != null || saved == null) fail(getString(R.string.CustomProfileIntegrationUnavailable));
            else finish(saved);
        });
    }

    // ------------------------------------------------------------ outcomes

    private void finish(JSONObject saved) {
        if (saved == null || saved.optString("id").isEmpty()) {
            fail(getString(R.string.CustomProfileIntegrationUnavailable));
            return;
        }
        finished = true;
        generation++;
        ACTIVE.remove(account, this);
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.cancelRunOnUIThread(watchClipboard);
        dismiss();
        CustomProfileIntegrations.clearCache();
        connected.accept(saved);
    }

    private void cancel() {
        final String abandonedState = state;
        state = null;
        if (abandonedState != null && abandonedState.matches("[A-Za-z0-9_-]{32,64}")) {
            SovietGramApiClient.deleteSigned(account, "/v1/integration-accounts/oauth/" + abandonedState,
                    (body, error) -> { /* Best effort: disconnect/replacement also invalidates this state. */ });
        }
        finished = true;
        generation++;
        ACTIVE.remove(account, this);
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.cancelRunOnUIThread(watchClipboard);
        dismiss();
    }

    private void dismiss() {
        if (dialog != null) {
            try { dialog.dismiss(); } catch (RuntimeException ignored) { }
            dialog = null;
        }
    }

    private void fail(String message) {
        cancel();
        final Activity activity = fragment.getParentActivity();
        if (fragment.isFinished || activity == null) return;
        new AlertDialog.Builder(activity)
                .setTitle(CustomProfileIntegrations.serviceName(service))
                .setMessage(message)
                .setPositiveButton(getString(R.string.OK), null)
                .show();
    }
}
