package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.LayoutHelper;

import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import tw.nekomimi.nekogram.helpers.CustomProfileIntegrations;
import tw.nekomimi.nekogram.helpers.SovietGramApiClient;

/** Explicit, consented provider sign-in. No passwords or credentials are stored in a profile. */
public class CustomProfileIntegrationLoginActivity extends BaseFragment {
    private final int service;
    private final long owner;
    private final Consumer<JSONObject> connected;
    private final String state = UUID.randomUUID().toString();
    private final HashSet<Integer> tried = new HashSet<>();
    private WebView web;
    private boolean closed, busy, paused;
    private int generation;

    public CustomProfileIntegrationLoginActivity(int account, int service, Consumer<JSONObject> connected) {
        currentAccount = account;
        owner = UserConfig.getInstance(account).getClientUserId();
        this.service = service;
        this.connected = connected;
    }

    private boolean alive() {
        return !closed && owner > 0 && UserConfig.getInstance(currentAccount).getClientUserId() == owner;
    }

    @Override public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setTitle(CustomProfileIntegrations.serviceName(service));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) { if (id == -1) finishFragment(); }
        });
        FrameLayout root = new FrameLayout(context);
        fragmentView = root;
        try {
            web = new WebView(context);
            web.getSettings().setJavaScriptEnabled(true);
            web.getSettings().setDomStorageEnabled(true);
            web.getSettings().setAllowFileAccess(false);
            web.getSettings().setAllowContentAccess(false);
            web.getSettings().setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
            web.getSettings().setUserAgentString("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36");
            CookieManager.getInstance().setAcceptCookie(true);
            CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
            web.setWebViewClient(new WebViewClient() {
                @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                    return navigate(request.getUrl());
                }
                @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                    return navigate(Uri.parse(url));
                }
                @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
                    navigate(Uri.parse(url));
                }
                @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                    Uri uri = request.getUrl();
                    if ("https".equals(uri.getScheme()) && apiHost(uri.getHost())) {
                        String token = null;
                        for (java.util.Map.Entry<String, String> entry : request.getRequestHeaders().entrySet()) {
                            if (!entry.getKey().equalsIgnoreCase("Authorization")) continue;
                            String authorization = entry.getValue();
                            if (authorization.startsWith("Bearer ")) token = authorization.substring(7);
                            if (authorization.startsWith("OAuth ")) token = authorization.substring(6);
                        }
                        if (token == null && service == 5) token = uri.getQueryParameter("oauth_token");
                        if (token == null && service == 6) token = uri.getQueryParameter("access_token");
                        if (token != null) {
                            final String candidate = token;
                            AndroidUtilities.runOnUIThread(() -> offer(candidate));
                        }
                    }
                    return null;
                }
            });
            root.addView(web, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
            web.loadUrl(switch (service) {
                case 3 -> "https://oauth.yandex.ru/authorize?response_type=token&client_id=23cabbbdc6cd418abb4b39c32c41195d&state=" + state;
                case 4 -> "https://open.spotify.com/";
                case 5 -> "https://soundcloud.com/signin";
                default -> "https://vk.com/";
            });
            AndroidUtilities.runOnUIThread(poll, 1500);
        } catch (RuntimeException unavailable) {
            AndroidUtilities.runOnUIThread(() -> error());
        }
        return root;
    }

    private boolean apiHost(String host) {
        if (host == null) return false;
        host = host.toLowerCase(Locale.US);
        return switch (service) {
            case 4 -> host.equals("api.spotify.com") || host.equals("spclient.wg.spotify.com") || host.equals("api-partner.spotify.com");
            case 5 -> host.equals("api-v2.soundcloud.com") || host.equals("api.soundcloud.com");
            case 6 -> host.equals("api.vk.com");
            default -> false;
        };
    }

    private boolean navigate(Uri uri) {
        if (!alive()) return true;
        String scheme = uri.getScheme();
        if (!"https".equals(scheme)) return true;
        if (service == 3 && ("oauth.yandex.ru".equals(uri.getHost()) || "oauth.yandex.com".equals(uri.getHost()))
                && uri.getFragment() != null) {
            Uri result = Uri.parse("https://callback.invalid/?" + uri.getFragment());
            if (state.equals(result.getQueryParameter("state"))) {
                String token = result.getQueryParameter("access_token");
                if (token != null) { offer(token); return true; }
            }
        }
        return false;
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!alive() || paused || web == null) return;
            Uri uri = Uri.parse(web.getUrl() == null ? "" : web.getUrl());
            String host = uri.getHost();
            boolean trusted = "https".equals(uri.getScheme()) && host != null && (
                    service == 5 && (host.equals("soundcloud.com") || host.endsWith(".soundcloud.com"))
                    || service == 6 && (host.equals("vk.com") || host.endsWith(".vk.com")));
            if (!busy && trusted) {
                // Read the provider's session token only; never read form fields or password storage.
                String script = service == 5
                        ? "(function(){var m=(document.cookie||'').match(/(?:^|;\\s*)oauth_token=([^;]+)/);if(m)return m[1];for(var s of [localStorage,sessionStorage]){for(var i=0;i<s.length;i++){var k=s.key(i);if(!/oauth|token|session|auth/i.test(k))continue;var v=s.getItem(k)||'';var t=v.match(/[0-9]-[0-9]+-[0-9]+-[A-Za-z0-9_-]{6,}/);if(t)return t[0];}}return '';})()"
                        : "(function(){for(var s of [localStorage,sessionStorage]){for(var k of ['access_token','accessToken','vk_access_token']){var t=s.getItem(k);if(t&&/^[A-Za-z0-9._~-]{16,8192}$/.test(t))return t;}}return '';})()";
                web.evaluateJavascript(script, raw -> {
                    if (!alive()) return;
                    try { offer(new JSONArray("[" + raw + "]").optString(0)); }
                    catch (Exception ignored) { }
                });
            }
            AndroidUtilities.runOnUIThread(this, 1500);
        }
    };

    private void offer(String token) {
        if (!alive() || busy || token == null || !token.matches("[A-Za-z0-9._~+/-]{16,8192}={0,2}")) return;
        if (tried.size() >= 64 || !tried.add(token.hashCode())) return;
        busy = true;
        int request = ++generation;
        JSONObject payload = new JSONObject();
        try { payload.put("token", token); } catch (Exception ignored) { return; }
        String path = "/v1/integration-accounts/" + CustomProfileIntegrations.key(service);
        SovietGramApiClient.postSigned(currentAccount, path + "/preview", payload, (body, failure) -> AndroidUtilities.runOnUIThread(() -> {
            if (!alive() || request != generation) return;
            if (failure != null || body == null || body.optString("id").isEmpty()) { busy = false; return; }
            new AlertDialog.Builder(getParentActivity())
                    .setTitle(CustomProfileIntegrations.serviceName(service))
                    .setMessage(getString(R.string.CustomProfileIntegrationUseAccount) + "\n\n" + body.optString("name") + "\n" + body.optString("id"))
                    .setPositiveButton(getString(R.string.Done), (dialog, which) -> {
                        if (!alive()) return;
                        SovietGramApiClient.putSigned(currentAccount, path, payload, (saved, error) -> AndroidUtilities.runOnUIThread(() -> {
                            if (!alive() || request != generation) return;
                            if (error != null || saved == null) { busy = false; error(); return; }
                            CustomProfileIntegrations.clearCache();
                            connected.accept(saved);
                            finishFragment();
                        }));
                    })
                    .setNegativeButton(getString(R.string.Cancel), (dialog, which) -> busy = false)
                    .setOnCancelListener(dialog -> busy = false)
                    .show();
        }));
    }

    private void error() {
        if (alive() && getParentActivity() != null) new AlertDialog.Builder(getParentActivity())
                .setTitle(CustomProfileIntegrations.serviceName(service))
                .setMessage(getString(R.string.CustomProfileIntegrationUnavailable))
                .setPositiveButton(getString(R.string.OK), null).show();
    }

    @Override public void onPause() {
        super.onPause(); paused = true;
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (web != null) web.onPause();
    }
    @Override public void onResume() {
        super.onResume(); paused = false;
        if (web != null) web.onResume();
        AndroidUtilities.cancelRunOnUIThread(poll);
        AndroidUtilities.runOnUIThread(poll, 1500);
    }
    @Override public void onFragmentDestroy() {
        closed = true; generation++;
        AndroidUtilities.cancelRunOnUIThread(poll);
        if (web != null) { web.stopLoading(); web.setWebViewClient(new WebViewClient()); web.destroy(); web = null; }
        tried.clear();
        super.onFragmentDestroy();
    }
}
