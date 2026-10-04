package tw.nekomimi.nekogram.settings;

import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.view.Gravity;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LineProgressView;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tw.nekomimi.nekogram.helpers.CustomProfileIntegrations;
import tw.nekomimi.nekogram.helpers.SovietGramApiClient;

/**
 * SoundCloud's own sign-in page, shown in the app, and nothing else for the user to do.
 *
 * <p>SoundCloud has no app registration to offer, so its session is the only thing that can say what an
 * account plays. The page signs the user in as it would in any browser; the session token it leaves in the
 * {@code oauth_token} cookie is picked up here, checked with the server (which asks SoundCloud who it
 * belongs to) and handed on. The password is typed into SoundCloud's page and goes nowhere else; once the
 * token is taken the page's cookies and storage are wiped, so the app keeps no SoundCloud session of its own.
 *
 * <p>Google and Apple refuse to sign anyone in from a view inside an app. For those accounts the menu offers
 * to paste a token by hand instead.
 */
public class CustomProfileSoundcloudSignIn extends BaseFragment {

    public interface Listener {
        /** A token the server confirmed, with the account it belongs to. */
        void onSignedIn(String token, JSONObject who);

        /** The user chose to paste a token instead. */
        void onManual();
    }

    private static final String START = "https://soundcloud.com/signin";
    /** A plain mobile Chrome: sign-in pages turn away the "wv" of an embedded view. */
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36";
    private static final Pattern TOKEN_COOKIE = Pattern.compile("(?:^|;\\s*)oauth_token=([^;]+)");
    private static final String[] HOSTS = {
            "https://soundcloud.com", "https://www.soundcloud.com", "https://api-auth.soundcloud.com",
            "https://secure.soundcloud.com", "https://api-v2.soundcloud.com"};
    private static final int MENU_MANUAL = 1;

    private final String previewPath;
    private final Listener listener;
    private WebView web;
    private LineProgressView progress;
    private final Set<String> tried = new HashSet<>();
    private boolean checking;
    private boolean done;

    private final Runnable watch = new Runnable() {
        @Override public void run() {
            if (done || web == null) return;
            look();
            AndroidUtilities.runOnUIThread(this, 1000);
        }
    };

    public CustomProfileSoundcloudSignIn(String previewPath, Listener listener) {
        this.previewPath = previewPath;
        this.listener = listener;
    }

    @SuppressLint({"SetJavaScriptEnabled", "ClickableViewAccessibility"})
    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(CustomProfileIntegrations.serviceName(6));
        final ActionBarMenu menu = actionBar.createMenu();
        final ActionBarMenuItem other = menu.addItem(0, R.drawable.ic_ab_other);
        other.addSubItem(MENU_MANUAL, 0, getString(R.string.CustomProfileIntegrationEnterManually));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                } else if (id == MENU_MANUAL) {
                    done = true;
                    finishFragment();
                    listener.onManual();
                }
            }
        });

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        // A fresh sign-in every time: whatever an earlier visit left behind would sign in as someone else.
        wipe(null);

        web = new WebView(context);
        final WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUserAgentString(USER_AGENT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // Pages stay in this view, and only web addresses are followed: no app links, no files.
                return !"https".equalsIgnoreCase(request.getUrl().getScheme());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (progress != null) progress.setProgress(0.2f, true);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (progress != null) progress.setProgress(1f, true);
                look();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel();
            }
        });

        final int top = ActionBar.getCurrentActionBarHeight()
                + (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0);
        root.addView(web, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT,
                Gravity.TOP, 0, 0, 0, 0));
        ((FrameLayout.LayoutParams) web.getLayoutParams()).topMargin = top;
        progress = new LineProgressView(context);
        progress.setProgressColor(Theme.getColor(Theme.key_featuredStickers_addButton));
        root.addView(progress, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 2, Gravity.TOP, 0, 0, 0, 0));
        ((FrameLayout.LayoutParams) progress.getLayoutParams()).topMargin = top;

        web.loadUrl(START);
        AndroidUtilities.runOnUIThread(watch, 1000);
        return fragmentView;
    }

    /** Looks for the session the page has just created and has the server say whose it is. */
    private void look() {
        if (done || checking || web == null) return;
        final String cookies = CookieManager.getInstance().getCookie("https://soundcloud.com");
        if (cookies == null) return;
        final Matcher matcher = TOKEN_COOKIE.matcher(cookies);
        if (!matcher.find()) return;
        final String token = Uri.decode(matcher.group(1));
        if (!token.matches("[A-Za-z0-9._~+/-]{16,512}") || !tried.add(token)) return;
        checking = true;
        final JSONObject payload = new JSONObject();
        try { payload.put("token", token); } catch (Exception e) { checking = false; return; }
        SovietGramApiClient.postSigned(currentAccount, previewPath, payload, (who, error) -> AndroidUtilities.runOnUIThread(() -> {
            checking = false;
            // A token that is not an account's (the page hands one to visitors too) is just not the one yet.
            if (done || isFinished || error != null || who == null || who.optString("id").isEmpty()) return;
            done = true;
            wipe(web);
            finishFragment();
            listener.onSignedIn(token, who);
        }));
    }

    /** Forgets everything SoundCloud's pages stored in this app. */
    private static void wipe(WebView view) {
        try {
            final CookieManager cookies = CookieManager.getInstance();
            for (String host : HOSTS) {
                final String all = cookies.getCookie(host);
                if (all == null) continue;
                for (String part : all.split(";")) {
                    final int eq = part.indexOf('=');
                    if (eq <= 0) continue;
                    final String name = part.substring(0, eq).trim();
                    cookies.setCookie(host, name + "=; Max-Age=0; Path=/");
                    cookies.setCookie(host, name + "=; Max-Age=0; Path=/; Domain=.soundcloud.com");
                }
            }
            cookies.flush();
            if (view != null) {
                view.evaluateJavascript("try{localStorage.clear();sessionStorage.clear()}catch(e){}", null);
            }
        } catch (RuntimeException ignored) { }
    }

    @Override
    public void onFragmentDestroy() {
        done = true;
        AndroidUtilities.cancelRunOnUIThread(watch);
        if (web != null) {
            wipe(web);
            web.stopLoading();
            web.destroy();
            web = null;
        }
        super.onFragmentDestroy();
    }
}
