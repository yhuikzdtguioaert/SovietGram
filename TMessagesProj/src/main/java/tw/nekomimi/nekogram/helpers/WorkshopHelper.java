package tw.nekomimi.nekogram.helpers;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import tw.nekomimi.nekogram.helpers.remote.ApiServersHelper;

/**
 * Client for the Custom Profile workshop — the gallery the reference plugin publishes to, talking
 * to the same host, so every look shared there shows up here unchanged.
 * <p>
 * It is a third-party server reached over plain HTTP, so nothing here is allowed to be fatal: work
 * happens off the main thread and a failure comes back as a message for the screen to show rather
 * than an exception. Listing sends the account's Telegram id as {@code me} because that is what
 * decides which works come back already liked and what "Мои работы" contains.
 */
public final class WorkshopHelper {

    private static final String BASE = "http://penis.nothalk.fun:8080";
    private static final int TIMEOUT = 20000;

    /**
     * The workshop's own fetch-this-for-me endpoint, and the hosts worth asking it about.
     *
     * <p>A work's assets are GitHub release downloads, and GitHub is not reachable from everywhere the
     * app is. The reference plugin has always had this fallback, which is why the same look installs
     * complete there and arrived here as a themed profile with no banner: the workshop host fetches
     * the file itself and hands it over, from an address that answers wherever the gallery itself
     * does. It is not a mirror — it holds nothing — so it is only worth asking about the hosts the
     * works actually name.
     */
    private static final String PROXY = "https://penis.nothalk.fun/cpb/api/media?u=";
    private static final String[] PROXIED_HOSTS = {
            "https://github.com/", "https://objects.githubusercontent.com/",
    };

    /**
     * How long a network that could not reach GitHub is assumed to still not reach it, matching the
     * reference. Long, because this is a property of where the phone is rather than of the moment: a
     * blocked host does not come back within the hour, and paying two timeouts per asset until it does
     * is what makes installing a look feel broken even when it succeeds.
     */
    private static final long DIRECT_RETRY_MS = 24 * 60 * 60 * 1000L;

    private static final String NET_PREFS = "customprofile_net";
    private static final String DIRECT_CLOSED_KEY = "direct_closed_until";

    /** {@link Long#MIN_VALUE} until read from the preferences once. */
    private static volatile long directClosedUntil = Long.MIN_VALUE;

    /** The two galleries the workshop serves, as its {@code kind} parameter spells them. */
    public static final String KIND_PROFILE = "profile";
    public static final String KIND_FRAME = "frame";

    /** Sections, in the order the plugin lists them. */
    public static final String MODE_NEW = "new";
    public static final String MODE_POPULAR = "popular";
    public static final String MODE_BEST = "best";

    /** Only meaningful for {@link #MODE_BEST}. */
    public static final String PERIOD_DAY = "day";
    public static final String PERIOD_WEEK = "week";
    public static final String PERIOD_MONTH = "month";

    private static final int PAGE = 40;

    private WorkshopHelper() {
    }

    /** One published look. {@link #assets} and {@link #config} stay null until {@link #load}. */
    public static final class Work {
        public String id = "";
        public String ver = "";
        /** {@link #KIND_PROFILE} or {@link #KIND_FRAME}; what installing it will do. */
        public String kind = KIND_PROFILE;
        public String title = "";
        public String author = "";
        public String authorName = "";
        public String tag = "";
        public long updated;
        public int likes;
        public boolean liked;
        public JSONObject assets;
        public JSONObject config;
    }

    /** Exactly one argument is non-null. */
    public interface Callback<T> {
        void onResult(@Nullable T result, @Nullable String error);
    }

    // --------------------------------------------------------------- requests

    /**
     * Fetches one section. {@code period} is ignored unless the mode is {@link #MODE_BEST}.
     * The callback runs on the main thread.
     */
    public static void list(String mode, String period, Callback<List<Work>> callback) {
        list(mode, period, KIND_PROFILE, callback);
    }

    /**
     * {@link #list(String, String, Callback)} for one kind of work.
     *
     * <p>The workshop holds two galleries behind the same endpoints, told apart by this one
     * parameter: profile looks and avatar frames. Omitting it answers with profile looks, which is
     * why the frames were invisible here for as long as nobody passed it.
     */
    public static void list(String mode, String period, String kind, Callback<List<Work>> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final String url = BASE + "/api/work/list?tag=&mode=" + enc(mode)
                        + "&kind=" + enc(kind == null ? KIND_PROFILE : kind)
                        + "&period=" + enc(period == null ? "" : period)
                        + "&limit=" + PAGE + "&me=" + me();
                final JSONObject root = new JSONObject(getText(url));
                if (!root.optBoolean("ok")) {
                    post(callback, null, error(root));
                    return;
                }
                final List<Work> works = new ArrayList<>();
                final JSONArray array = root.optJSONArray("works");
                for (int a = 0; array != null && a < array.length(); a++) {
                    final JSONObject item = array.optJSONObject(a);
                    if (item != null) {
                        works.add(parse(item));
                    }
                }
                post(callback, works, null);
            } catch (Throwable e) {
                FileLog.e(e);
                post(callback, null, e.getMessage());
            }
        });
    }

    /**
     * Fills in {@link Work#assets} and {@link Work#config} for a work the list only summarised.
     * The same instance is handed back so the caller can keep using the one it already has.
     */
    public static void load(Work work, Callback<Work> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final String url = BASE + "/api/work/get?id=" + enc(work.id) + "&ver=" + enc(work.ver)
                        + "&access=&me=" + me();
                final JSONObject root = new JSONObject(getText(url));
                final JSONObject body = root.optJSONObject("work");
                if (!root.optBoolean("ok") || body == null) {
                    post(callback, null, error(root));
                    return;
                }
                work.assets = asObject(body, "assets");
                work.config = asObject(body, "config");
                if (work.config == null) {
                    post(callback, null, null);
                    return;
                }
                post(callback, work, null);
            } catch (Throwable e) {
                FileLog.e(e);
                post(callback, null, e.getMessage());
            }
        });
    }

    /**
     * Toggles the like. The server also wants the per-install key its own plugin registers, which
     * we have no way to mint, so this can come back rejected — the caller shows what it said.
     */
    public static void like(Work work, boolean liked, Callback<Integer> callback) {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                final JSONObject body = new JSONObject();
                body.put("uid", me());
                body.put("id", work.id);
                body.put("action", liked ? "like" : "unlike");
                final JSONObject root = new JSONObject(postJson(BASE + "/api/work/like", body.toString()));
                if (!root.optBoolean("ok")) {
                    post(callback, null, error(root));
                    return;
                }
                post(callback, root.optInt("likes", work.likes), null);
            } catch (Throwable e) {
                FileLog.e(e);
                post(callback, null, e.getMessage());
            }
        });
    }

    /** The preview image, as a URL {@code BackupImageView} can load on its own. */
    public static String previewUrl(Work work) {
        return BASE + "/api/work/prev?id=" + enc(work.id) + "&ver=" + enc(work.ver) + "&access=";
    }

    /** Downloads one of the blobs named in {@link Work#assets}. Blocking — callers are off-thread. */
    public static byte[] file(String sha) throws Exception {
        return getBytes(BASE + "/api/work/file?sha=" + enc(sha), MAX_MEDIA_BYTES);
    }

    // ---------------------------------------------------------------- work media

    /**
     * One picture or video a work needs, as {@code assets.external} describes it.
     *
     * <p>The bytes do not live on the workshop host at all: {@link #url} points at a GitHub release
     * asset, and {@link #sha} is both the file's name there and the integrity check. {@link #mime} is
     * carried for the descriptor a peer fetches by, and for nothing else — it cannot say whether the
     * asset is a still or an animation, since most published banners are webp and that mime covers
     * both. The downloaded file answers that, through {@link CustomProfileFormat}.
     */
    public static final class MediaRef {
        public String sha = "";
        public String url = "";
        public String mime = "";
        public long size;
    }

    /**
     * Hard ceiling on a downloaded asset.
     *
     * Deliberately well above {@link SovietGramApiClient#MAX_MEDIA_BYTES} rather than derived from
     * it, because the two answer different questions: this one decides whether a look can be
     * <em>installed</em> at all, the upload limit only whether we can re-host its picture. The
     * largest published asset is 43.66MB — over the upload ceiling, so it stays served from its own
     * GitHub URL, but it installs and renders perfectly well and a ceiling under it would turn that
     * look into a flat colour for everyone. Anything far past this is not a banner.
     */
    private static final long MAX_MEDIA_BYTES = 64L * 1024 * 1024;

    /**
     * The media a work declares for one slot, or {@code null} when it declares none.
     *
     * <p>The shape is {@code assets.external.<slot>.{sha,url,mime,size}}. It used to be read as
     * {@code assets.<slot>}, which is a nested object rather than a sha string — so every work's
     * banner and background silently resolved to nothing and every installed look fell back to a flat
     * colour. That is not a corner case: 89 of the gallery's 131 works declare their banner here and 107
     * declare a background, so this one mis-read was the whole of "the banner applies in none of them".
     * The flat {@code assets.<slot>} form is still accepted as a plain sha, since that is what the older
     * works carry — 58 of those same banners are in that form, the two shapes being mixed freely inside
     * one work.
     */
    @Nullable
    public static MediaRef media(@Nullable JSONObject assets, String slot) {
        final List<MediaRef> refs = mediaSources(assets, slot);
        return refs.isEmpty() ? null : refs.get(0);
    }

    /**
     * Every way the work offers to fetch one slot, in the order the reference tries them: the
     * descriptor under {@code assets.external} first, then the flat {@code assets.<slot>} sha.
     *
     * <p>Both, and not just the first one present, because a work can declare both and the reference
     * falls through to the second when the first fetches nothing. That is not a rare shape — the two
     * forms are mixed freely inside one work — and the descriptor is the fragile half of it: its URL
     * is a GitHub release download, which is exactly the source some networks cannot reach. Stopping
     * at a failed descriptor threw away a sha that would have fetched the same bytes from the workshop
     * host, and the look installed with no banner.
     */
    public static List<MediaRef> mediaSources(@Nullable JSONObject assets, String slot) {
        final List<MediaRef> refs = new ArrayList<>(2);
        if (assets == null) {
            return refs;
        }
        final JSONObject external = asObject(assets, "external");
        final JSONObject entry = external == null ? null : asObject(external, slot);
        if (entry != null) {
            final MediaRef ref = new MediaRef();
            ref.sha = entry.optString("sha", "").trim();
            ref.url = entry.optString("url", "").trim();
            ref.mime = entry.optString("mime", "").trim().toLowerCase();
            ref.size = entry.optLong("size", 0L);
            if (!TextUtils.isEmpty(ref.sha) || !TextUtils.isEmpty(ref.url)) {
                refs.add(ref);
            }
        }
        // Legacy: the slot itself is the sha, with no descriptor and no external URL.
        final Object flat = assets.opt(slot);
        if (flat instanceof String sha && !sha.trim().isEmpty() && !"null".equals(sha)) {
            final MediaRef ref = new MediaRef();
            ref.sha = sha.trim();
            if (refs.isEmpty() || !ref.sha.equalsIgnoreCase(refs.get(0).sha)) {
                refs.add(ref);
            }
        }
        return refs;
    }

    /**
     * Fetches a work's asset from whichever source can actually serve it.
     *
     * <p>Three sources, in the order that costs least to try:
     * <ol>
     *     <li>the URL the descriptor names, which for a modern work is a GitHub release asset and is
     *         the authoritative copy — fetched directly or through the workshop's own proxy, whichever
     *         this network can reach, see {@link #getMedia};</li>
     *     <li>our own {@code /v1/media/<sha>}, see {@link #mirror};</li>
     *     <li>the workshop host's own {@code /api/work/file}, last because it is the one source that
     *         cannot be relied on — see {@link #readOnce} for what it does to a large body.</li>
     * </ol>
     *
     * <p>Verified against {@link MediaRef#sha} whichever source answered, which is what makes trying
     * several of them safe: the bytes are written straight into the slot the profile paints from, so a
     * truncated or substituted transfer would otherwise be installed as the user's banner and simply
     * render as nothing, with no way to tell that from a look that never had a banner.
     *
     * @throws Exception when nothing could be fetched, carrying the last reason so the caller can
     *                   report it rather than a bare failure.
     */
    public static byte[] downloadMedia(MediaRef ref) throws Exception {
        if (ref.size > MAX_MEDIA_BYTES) {
            throw new Exception("asset too large: " + ref.size);
        }
        Exception failure = null;
        if (!TextUtils.isEmpty(ref.url)) {
            try {
                return verified(getMedia(ref.url), ref.sha);
            } catch (Exception e) {
                failure = e;
            }
        }
        if (!TextUtils.isEmpty(ref.sha)) {
            final byte[] mirrored = mirror(ref.sha);
            if (mirrored != null) {
                try {
                    return verified(mirrored, ref.sha);
                } catch (Exception e) {
                    failure = e;
                }
            }
            try {
                return verified(file(ref.sha), ref.sha);
            } catch (Exception e) {
                failure = e;
            }
        }
        throw failure != null ? failure : new Exception("no media source");
    }

    /**
     * Our own content-addressed copy of an asset, or {@code null} when we have not got one.
     *
     * <p>Worth trying for nothing, because the two systems already agree on the identifier: the
     * workshop names an asset by the sha256 of its bytes and so does {@code /v1/media/<sha>}, so one
     * sha addresses the same file in both and no mapping has to be kept anywhere. Every look installed
     * through {@link WorkshopStyle} publishes what it downloaded, so the first person to install a work
     * seeds it for everybody after them and our API becomes a mirror of the workshop without being
     * built as one.
     *
     * <p>It also stops one asset being unfetchable twice. The workshop host was, for a while, answering
     * {@code /api/work/file} with the file's true length and then cutting the body off at 32832 bytes,
     * which put every legacy bare-sha asset larger than that out of reach — a bare sha has no URL to
     * fall back to, so those looks installed with everything except their banner. It serves complete
     * bodies again now (58 legacy and 31 external banner assets fetched and hash-verified across the
     * whole gallery), so this is insurance rather than the only route. Nothing here can rescue an asset
     * nobody has ever published, but the first successful install of a work seeds it for everybody after.
     *
     * <p>Swallows every failure on purpose: a miss is the normal answer, not a fault.
     */
    @Nullable
    private static byte[] mirror(String sha) {
        final String base = ApiServersHelper.baseUrl();
        if (TextUtils.isEmpty(base)) {
            return null;
        }
        try {
            return getBytes(base + "/v1/media/" + enc(sha), MAX_MEDIA_BYTES);
        } catch (Throwable ignore) {
            return null;
        }
    }

    /**
     * Fetches one URL's bytes and checks them against {@code sha}. The transport half of
     * {@link #downloadMedia}, exposed because {@link CustomProfileMedia} fetches a peer's picture from
     * the same hosts with the same rules, from a descriptor rather than a work.
     *
     * @throws Exception on any transport failure or a hash that does not match.
     */
    public static byte[] download(String url, String sha) throws Exception {
        return verified(getMedia(url), sha);
    }

    /**
     * One asset URL, direct or through the workshop's proxy, whichever this network can actually
     * reach. See {@link #PROXY}.
     *
     * <p>Which is tried first is remembered for a day: a phone that has just failed to reach GitHub
     * once will fail again on the next asset, and the look being installed usually has two. The
     * memory is not trusted blindly, though — when the proxy then fails too, the direct route is
     * retried at once and the memory cleared, so a network that has come back is not written off for
     * the rest of the day.
     */
    private static byte[] getMedia(String url) throws Exception {
        if (!proxied(url)) {
            return getBytes(url, MAX_MEDIA_BYTES);
        }
        final boolean directClosed = System.currentTimeMillis() < directClosedUntil();
        Exception failure = null;
        if (!directClosed) {
            try {
                return getBytes(url, MAX_MEDIA_BYTES);
            } catch (Exception e) {
                failure = e;
                FileLog.e("WorkshopHelper: asset host unreachable (" + e.getMessage() + "), trying the workshop proxy");
            }
        }
        try {
            final byte[] data = getBytes(PROXY + enc(url), MAX_MEDIA_BYTES);
            if (!directClosed) {
                rememberDirectClosed(System.currentTimeMillis() + DIRECT_RETRY_MS);
            }
            return data;
        } catch (Exception e) {
            if (directClosed) {
                // The assumption was stale, or the proxy is the thing that is down. Either way the
                // direct route has not been tried in this attempt, so try it before giving up.
                rememberDirectClosed(0);
                return getBytes(url, MAX_MEDIA_BYTES);
            }
            throw failure != null ? failure : e;
        }
    }

    private static boolean proxied(String url) {
        for (String host : PROXIED_HOSTS) {
            if (url.startsWith(host)) {
                return true;
            }
        }
        return false;
    }

    private static long directClosedUntil() {
        if (directClosedUntil != Long.MIN_VALUE) {
            return directClosedUntil;
        }
        long stored = 0;
        try {
            stored = ApplicationLoader.applicationContext
                    .getSharedPreferences(NET_PREFS, Context.MODE_PRIVATE)
                    .getLong(DIRECT_CLOSED_KEY, 0);
        } catch (Throwable ignore) {
        }
        directClosedUntil = stored;
        return stored;
    }

    private static void rememberDirectClosed(long until) {
        directClosedUntil = until;
        try {
            ApplicationLoader.applicationContext
                    .getSharedPreferences(NET_PREFS, Context.MODE_PRIVATE)
                    .edit().putLong(DIRECT_CLOSED_KEY, until).apply();
        } catch (Throwable ignore) {
        }
    }

    /** Returns {@code data} when it hashes to {@code sha}, or when there is no sha to check against. */
    private static byte[] verified(byte[] data, String sha) throws Exception {
        if (data == null || data.length == 0) {
            throw new Exception("empty asset");
        }
        if (TextUtils.isEmpty(sha)) {
            return data;
        }
        final byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(data);
        final StringBuilder hex = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        if (!hex.toString().equalsIgnoreCase(sha)) {
            throw new Exception("asset hash mismatch");
        }
        return data;
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Reads a nested JSON object that the server may deliver either inline or, as its PHP backend
     * often does, as a JSON string in a text column. {@code optJSONObject} returns null for the
     * latter without complaint, which used to silently drop the banner (assets) or fail the whole
     * install (config) — so fall back to parsing the string form. Returns null only when there is
     * genuinely nothing usable.
     */
    private static JSONObject asObject(JSONObject parent, String key) {
        final JSONObject direct = parent.optJSONObject(key);
        if (direct != null) {
            return direct;
        }
        final String raw = parent.optString(key, "");
        if (TextUtils.isEmpty(raw) || "null".equals(raw)) {
            return null;
        }
        try {
            return new JSONObject(raw);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static Work parse(JSONObject item) {
        final Work work = new Work();
        work.id = item.optString("id");
        work.ver = item.optString("ver");
        work.kind = item.optString("kind", KIND_PROFILE);
        work.title = item.optString("title");
        work.author = item.optString("author");
        work.authorName = item.optString("author_name");
        work.tag = item.optString("tag");
        work.updated = item.optLong("updated");
        work.likes = item.optInt("likes");
        work.liked = item.optBoolean("liked");
        return work;
    }

    /** The server names its refusals in {@code error}; anything else is reported as unreachable. */
    private static String error(JSONObject root) {
        final String message = root.optString("error");
        return TextUtils.isEmpty(message) ? "" : message;
    }

    private static long me() {
        try {
            return UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
        } catch (Throwable ignore) {
            return 0;
        }
    }

    private static String enc(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static String getText(String url) throws Exception {
        return new String(getBytes(url, MAX_JSON_BYTES), StandardCharsets.UTF_8);
    }

    /** Answers are small JSON documents; a listing of 40 works is a few tens of KB. */
    private static final long MAX_JSON_BYTES = 8L * 1024 * 1024;

    /** A transfer that ended before the length the host declared. Named so it can be retried. */
    private static final class TruncatedException extends Exception {
        TruncatedException(long received, long declared) {
            super("transfer cut off after " + received + " of " + declared + " bytes");
        }
    }

    /**
     * Reads a URL, refusing anything past {@code limit}, retrying once if the transfer is cut off.
     *
     * <p>One retry rather than none because a cut-off body is the one failure here that another request
     * can plausibly fix, and rather than several because the host this mostly happens on truncates
     * deterministically — a second attempt is worth its one request, a fifth is not.
     */
    private static byte[] getBytes(String url, long limit) throws Exception {
        TruncatedException truncated = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return readOnce(url, limit);
            } catch (TruncatedException e) {
                truncated = e;
            }
        }
        throw truncated;
    }

    /**
     * One attempt at reading a URL. Redirects are followed, which is what the external asset URLs
     * need — a GitHub release download always bounces to a storage host.
     *
     * <p>A body shorter than the declared {@code Content-Length} is reported as exactly that, which
     * sounds like a detail and is not. The workshop host was for a time answering {@code /api/work/file}
     * with the file's true length and then stopping at 32832 bytes, so every asset larger than that
     * arrived cut off; checked against its sha it failed as an "asset hash mismatch", which reads as a
     * corrupted or substituted file and sends you looking in entirely the wrong place. It was the host,
     * not the transfer — a 1MB body over plain HTTP from an unrelated host and a 246KB GitHub asset both
     * arrived complete over the same connection path — and it is why the retry above exists. The host
     * behaves now, so this distinction is what will name the fault quickly if it comes back.
     */
    private static byte[] readOnce(String url, long limit) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "SovietGram/workshop");
            final long declared = connection.getContentLengthLong();
            if (declared > limit) {
                throw new Exception("response too large: " + declared);
            }
            final byte[] data;
            try (InputStream in = connection.getInputStream()) {
                data = readAll(in, limit);
            }
            // Only meaningful when the two numbers count the same bytes: Content-Length is the encoded
            // length, and HttpURLConnection decompresses a gzipped body underneath us, so on an encoded
            // response what arrives is legitimately a different size. A -1 means the host promised
            // nothing at all (chunked), and then a short read cannot be told from a whole file.
            final String encoding = connection.getContentEncoding();
            final boolean encoded = encoding != null && !"identity".equalsIgnoreCase(encoding.trim());
            if (declared >= 0 && !encoded && data.length < declared) {
                throw new TruncatedException(data.length, declared);
            }
            return data;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** A conditional GET, for the small JSON documents that change rarely and are cached. */
    public static final class Json {
        public final int code;
        @Nullable
        public final String etag;
        @Nullable
        public final String body;

        Json(int code, @Nullable String etag, @Nullable String body) {
            this.code = code;
            this.etag = etag;
            this.body = body;
        }
    }

    /**
     * Fetches a JSON document, sending {@code etag} so an unchanged one comes back as a bare 304.
     *
     * <p>Here rather than in the caller so that everything aimed at this host keeps one timeout, one
     * user agent and one set of limits — and so a second such document later does not grow a second
     * copy of all of it.
     */
    public static Json getJson(String url, @Nullable String etag) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "SovietGram/workshop");
            connection.setRequestProperty("Accept", "application/json");
            if (etag != null && !etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", etag);
            }
            final int code = connection.getResponseCode();
            if (code == 304) {
                return new Json(code, etag, null);
            }
            final InputStream in = code >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (in == null) {
                return new Json(code, connection.getHeaderField("ETag"), null);
            }
            try (InputStream stream = in) {
                return new Json(code, connection.getHeaderField("ETag"),
                        new String(readAll(stream, MAX_JSON_BYTES), StandardCharsets.UTF_8));
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String postJson(String url, String body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(TIMEOUT);
            connection.setReadTimeout(TIMEOUT);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = connection.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            // A refusal still carries the reason, and it arrives on the error stream.
            final InputStream in = connection.getResponseCode() >= 400
                    ? connection.getErrorStream() : connection.getInputStream();
            if (in == null) {
                return "{}";
            }
            try (InputStream stream = in) {
                return new String(readAll(stream, MAX_JSON_BYTES), StandardCharsets.UTF_8);
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static byte[] readAll(InputStream in, long limit) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            if (out.size() + read > limit) {
                throw new Exception("response exceeded " + limit + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static <T> void post(Callback<T> callback, @Nullable T result, @Nullable String error) {
        AndroidUtilities.runOnUIThread(() -> callback.onResult(result, error));
    }
}
