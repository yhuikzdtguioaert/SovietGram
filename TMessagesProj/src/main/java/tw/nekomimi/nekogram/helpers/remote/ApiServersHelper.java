package tw.nekomimi.nekogram.helpers.remote;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.Request;
import okhttp3.Response;
import sovietgram.com.NaConfig;
import tw.nekomimi.nekogram.helpers.SovietGramBadges;
import tw.nekomimi.nekogram.utils.HttpClient;

/**
 * Picks the SovietGram sync API base URL out of the remote metadata channel.
 *
 * <p>The channel message format is one JSON payload after the tag:
 * <pre>
 *     #apiservers
 *     {"servers":["https://a.example.com","https://b.example.com"]}
 * </pre>
 *
 * <p>On every app launch the message list is refreshed via MTProto ({@link BaseRemoteHelper}),
 * then every listed base URL is probed with a GET to {@code /v1/health} and the fastest
 * one that answers 200 within the timeout wins. The winner is stored in
 * {@link NaConfig#sovietGramApiServer} so any request made before the first probe finishes
 * still has a URL to talk to — the cached URL from the previous launch.
 *
 * <p>Never blocks the main thread. The whole selection is a fire-and-forget, and callers
 * that want to know when it's ready subscribe via {@link #onSelected(Runnable)}.
 */
public class ApiServersHelper extends BaseRemoteHelper {

    private static final String TAG = "apiservers";
    /** How long each health probe is allowed to take. */
    private static final long PROBE_TIMEOUT_MS = 2500L;
    /** Refresh the metadata + re-probe at most this often, unless forced. */
    private static final long MIN_REFRESH_INTERVAL_MS = 60L * 1000L;

    private static volatile ApiServersHelper instance;
    private final Object listenerLock = new Object();
    private final ArrayList<Runnable> pendingSelectListeners = new ArrayList<>();
    private volatile boolean picking = false;
    private volatile long lastPickAt = 0L;

    private ApiServersHelper() {
    }

    public static ApiServersHelper getInstance() {
        ApiServersHelper local = instance;
        if (local == null) {
            synchronized (ApiServersHelper.class) {
                local = instance;
                if (local == null) {
                    instance = local = new ApiServersHelper();
                }
            }
        }
        return local;
    }

    @Override
    protected String getTag() {
        return TAG;
    }

    /** One server per message means the default of 10 would cap the pool, so fetch more. */
    @Override
    protected int getMessagesLimit() {
        return 50;
    }

    @Override
    protected void onError(String text, Delegate delegate) {
        FileLog.e("[ApiServers] load error: " + text);
        picking = false;
    }

    /** Blocking-safe URL for HTTP callers. Empty string until the first pick lands. */
    public static String baseUrl() {
        return NaConfig.INSTANCE.getSovietGramApiServer().String().trim();
    }

    /**
     * Refreshes the server list from the channel and picks the fastest one.
     * Cheap to call on every launch — a hot cache short-circuits within
     * {@value #MIN_REFRESH_INTERVAL_MS} ms.
     */
    public void refresh(boolean force) {
        final long now = System.currentTimeMillis();
        if (!force && (now - lastPickAt) < MIN_REFRESH_INTERVAL_MS) {
            return;
        }
        if (picking) return;
        picking = true;
        load();
    }

    /** Runs {@code r} on the UI thread once a base URL has been selected. */
    public void onSelected(Runnable r) {
        if (r == null) return;
        if (!TextUtils.isEmpty(baseUrl())) {
            AndroidUtilities.runOnUIThread(r);
            return;
        }
        synchronized (listenerLock) {
            pendingSelectListeners.add(r);
        }
    }

    @Override
    protected void onLoadSuccess(ArrayList<JSONObject> responses, Delegate delegate) {
        super.onLoadSuccess(responses, delegate);
        final List<String> urls = parseServers(responses);
        if (urls.isEmpty()) {
            picking = false;
            return;
        }
        Executors.newSingleThreadExecutor().execute(() -> {
            final String picked = pickFastest(urls);
            picking = false;
            if (TextUtils.isEmpty(picked)) {
                // No candidate answered. Whatever was cached stays cached, and pending listeners stay
                // pending: a later refresh in this session can still hand them a live server.
                FileLog.d("sovietgram api: no server answered /v1/health out of " + urls.size());
                return;
            }
            lastPickAt = System.currentTimeMillis();
            NaConfig.INSTANCE.getSovietGramApiServer().setConfigString(picked);
            NaConfig.INSTANCE.getSovietGramApiServerPickedAt().setConfigLong(lastPickAt);
            final ArrayList<Runnable> toRun;
            synchronized (listenerLock) {
                toRun = new ArrayList<>(pendingSelectListeners);
                pendingSelectListeners.clear();
            }
            for (Runnable r : toRun) {
                AndroidUtilities.runOnUIThread(r);
            }
            // Lists that are the same for everybody and wanted the moment a name is drawn. Asked for
            // here because until this point there was no server to ask, and a badge that turns up
            // ten minutes into the session may as well not exist.
            AndroidUtilities.runOnUIThread(() -> SovietGramBadges.sync(true));
        });
    }

    /**
     * Collects server URLs across <b>all</b> channel messages tagged {@code #apiservers}. Each
     * message must be valid JSON — anything else is dropped by
     * {@link BaseRemoteHelper}. Every message contributes independently, so servers can be
     * published one-per-message:
     * <pre>
     *     #apiservers {"server":"https://a.example.com"}           (one URL per message)
     *     #apiservers {"servers":["https://a...","https://b..."]}  (list, still supported)
     * </pre>
     * URLs are de-duplicated, then shuffled so probing doesn't always start with the same host.
     */
    private static List<String> parseServers(List<JSONObject> payloads) {
        final java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (payloads != null) {
            for (JSONObject payload : payloads) {
                if (payload == null) continue;
                addUrl(out, payload.optString("server", null));
                final JSONArray arr = payload.optJSONArray("servers");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        addUrl(out, arr.optString(i, ""));
                    }
                }
            }
        }
        final ArrayList<String> list = new ArrayList<>(out);
        Collections.shuffle(list);
        return list;
    }

    /** Normalizes and appends a candidate URL, skipping blanks and non-http(s) values. */
    private static void addUrl(java.util.Set<String> out, String raw) {
        if (raw == null) return;
        final String url = raw.trim();
        if (url.isEmpty()) return;
        if (!url.startsWith("https://") && !url.startsWith("http://")) return;
        out.add(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
    }

    /**
     * Fires {@code /v1/health} at every candidate in parallel and returns whichever answered
     * first with a 200. Losers are cancelled. Returns {@code null} when nothing answers within the
     * timeout — <b>never</b> a candidate that failed its probe.
     *
     * <p>Caching an unprobed host would be worse than caching nothing: the URL is what
     * {@link #baseUrl()} hands to every API call and what makes {@link #onSelected(Runnable)} fire
     * immediately on the next launch, so one dead entry turns into a client that believes it has a
     * server and fails every single request against it. With {@code null}, the caller leaves the
     * previously cached URL alone — a probe can fail simply because the network blinked — and the
     * next refresh probes again.
     */
    @Nullable
    private String pickFastest(List<String> urls) {
        if (urls.size() == 1) {
            return probeOne(urls.get(0)) ? urls.get(0) : null;
        }
        final AtomicReference<String> winner = new AtomicReference<>(null);
        final CountDownLatch latch = new CountDownLatch(1);
        final ArrayList<okhttp3.Call> calls = new ArrayList<>();
        for (String url : urls) {
            final Request req = new Request.Builder()
                    .url(url + "/v1/health")
                    .get()
                    .header("User-Agent", "SovietGram/health")
                    .build();
            final okhttp3.Call call = HttpClient.INSTANCE.getInstance().newCall(req);
            calls.add(call);
            call.enqueue(new okhttp3.Callback() {
                @Override
                public void onFailure(okhttp3.Call c, IOException e) {
                    // silent: another candidate may still win
                }

                @Override
                public void onResponse(okhttp3.Call c, Response response) {
                    try (Response r = response) {
                        if (r.isSuccessful() && winner.compareAndSet(null, url)) {
                            latch.countDown();
                        }
                    }
                }
            });
        }
        try {
            latch.await(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        for (okhttp3.Call c : calls) {
            if (!c.isCanceled()) c.cancel();
        }
        return winner.get();
    }

    private boolean probeOne(String url) {
        final Request req = new Request.Builder().url(url + "/v1/health").get().build();
        try (Response r = HttpClient.INSTANCE.getInstance().newCall(req).execute()) {
            return r.isSuccessful();
        } catch (Throwable e) {
            return false;
        }
    }
}
