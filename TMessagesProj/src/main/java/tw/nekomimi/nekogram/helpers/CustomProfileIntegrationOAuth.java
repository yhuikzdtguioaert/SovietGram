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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Connects a provider account through the system browser.
 *
 * <p>The provider's own sign-in page is opened in the user's browser — never in a view inside the
 * app. The provider sends the browser back to the SovietGram server, which exchanges the one-time
 * code and keeps the tokens; this class only starts that, then asks the server how it went. Nothing
 * the user types on the provider's page, and no token, ever passes through the app.
 *
 * <p>Yandex Music is the one exception in shape, not in spirit: its token is only shown on a page,
 * so after the browser step the user pastes it (or the address of that page) back here.
 */
public final class CustomProfileIntegrationOAuth {
    private static final long POLL_MS = 2000;
    private static final long GIVE_UP_MS = 10 * 60 * 1000;
    private static final Pattern PASTED_TOKEN = Pattern.compile("access_token=([A-Za-z0-9._~+/-]{16,8192})");

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

    /** Starts the sign-in for {@code service} (3 Yandex Music, 4 Spotify, 6 SoundCloud). */
    public static void begin(BaseFragment fragment, int account, int service, Consumer<JSONObject> connected) {
        new CustomProfileIntegrationOAuth(fragment, account, service, connected).start();
    }

    private boolean alive() {
        return !finished && !fragment.isFinished && fragment.getParentActivity() != null
                && owner > 0 && UserConfig.getInstance(account).getClientUserId() == owner;
    }

    private String path() {
        return "/v1/integration-accounts/" + CustomProfileIntegrations.key(service);
    }

    /** SoundCloud: its own sign-in page opens inside the app and the token is taken from it, nothing to copy. */
    private void startSoundcloud() {
        fragment.presentFragment(new tw.nekomimi.nekogram.settings.CustomProfileSoundcloudSignIn(path() + "/preview",
                new tw.nekomimi.nekogram.settings.CustomProfileSoundcloudSignIn.Listener() {
                    @Override public void onSignedIn(String token, JSONObject who) {
                        if (!alive()) return;
                        final JSONObject payload = new JSONObject();
                        try { payload.put("token", token); } catch (Exception ignored) { return; }
                        confirm(payload, who);
                    }

                    @Override public void onManual() {
                        if (!alive()) return;
                        openBrowser("https://soundcloud.com/signin");
                        askToken(null);
                    }
                }));
    }

    private void start() {
        if (service == 6) {
            startSoundcloud();
            return;
        }
        final JSONObject body = new JSONObject();
        try { body.put("package", ApplicationLoader.applicationContext.getPackageName()); }
        catch (Exception ignored) { }
        final int request = ++generation;
        SovietGramApiClient.postSigned(account, path() + "/oauth/start", body, (response, error) -> {
            if (!alive() || request != generation) return;
            final String url = response == null ? null : response.optString("url", null);
            if (error != null || url == null || !url.startsWith("https://")) {
                fail(error != null && error.contains("oauth_not_configured")
                        ? getString(R.string.CustomProfileIntegrationNotConfigured)
                        : getString(R.string.CustomProfileIntegrationUnavailable));
                return;
            }
            if (!openBrowser(url)) {
                fail(getString(R.string.CustomProfileIntegrationNoBrowser));
                return;
            }
            if (response.optBoolean("manual")) {
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
                dismiss();
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
        final boolean soundcloud = service == 6;
        // Whatever is on the clipboard when the dialog opens was there before the sign-in, so it is
        // only remembered, not used: a token copied on the provider's page afterwards is picked up
        // by the watch below the moment the user comes back, without anything to paste or press.
        staleClip = clipboardText(activity);
        final AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(CustomProfileIntegrations.serviceName(service))
                .setMessage((problem == null ? "" : problem + "\n\n") + getString(soundcloud
                        ? R.string.CustomProfileIntegrationPasteTokenSoundcloud : R.string.CustomProfileIntegrationPasteToken))
                .setView(input)
                .setPositiveButton(getString(R.string.Done), (d, which) -> {
                    final String token = extractToken(input.getText().toString());
                    if (token == null) askToken(getString(R.string.CustomProfileIntegrationUnavailable));
                    else preview(token);
                })
                .setNegativeButton(getString(R.string.Cancel), (d, which) -> cancel());
        if (soundcloud) {
            // SoundCloud shows its token nowhere: a line typed into the address bar of a browser signed in to it reads
            // the token out of the page and offers it in a box to copy. The line is put on the clipboard for that.
            builder.setNeutralButton(getString(R.string.CustomProfileIntegrationCopyScript), (d, which) -> {
                try {
                    final ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    clipboard.setPrimaryClip(ClipData.newPlainText("script", SOUNDCLOUD_SCRIPT));
                } catch (RuntimeException ignored) { }
                askToken(null);
            });
        }
        dialog = builder.create();
        dialog.setOnCancelListener(d -> cancel());
        dialog.show();
        AndroidUtilities.cancelRunOnUIThread(watchClipboard);
        AndroidUtilities.runOnUIThread(watchClipboard, 800);
    }

    private String staleClip = "";

    /** Typed after "javascript:" in the address bar of a browser signed in to soundcloud.com (the prefix is typed by hand: a browser drops it from pasted text). */
    private static final String SOUNDCLOUD_SCRIPT = "prompt('SoundCloud token',document.cookie.match(/oauth_token=([^;]+)/)[1])";

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
        if (service == 6) return t.matches("[0-9]-[0-9]+-[0-9]+-[A-Za-z0-9]{6,64}");
        return t.contains("access_token=") || t.startsWith("y0_") || t.startsWith("AQAAAA");
    }

    private static String extractToken(String pasted) {
        if (pasted == null) return null;
        final String text = pasted.trim();
        final Matcher matcher = PASTED_TOKEN.matcher(text);
        if (matcher.find()) return matcher.group(1);
        return text.matches("[A-Za-z0-9._~+/-]{16,8192}") ? text : null;
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
        finished = true;
        generation++;
        CustomProfileIntegrations.clearCache();
        connected.accept(saved);
    }

    private void cancel() {
        finished = true;
        generation++;
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
        finished = true;
        generation++;
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.cancelRunOnUIThread(watchClipboard);
        dismiss();
        final Activity activity = fragment.getParentActivity();
        if (fragment.isFinished || activity == null) return;
        new AlertDialog.Builder(activity)
                .setTitle(CustomProfileIntegrations.serviceName(service))
                .setMessage(message)
                .setPositiveButton(getString(R.string.OK), null)
                .show();
    }
}
