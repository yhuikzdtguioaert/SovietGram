package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;
import android.util.Base64;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.FileLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import tw.nekomimi.nekogram.helpers.remote.ApiServersHelper;
import tw.nekomimi.nekogram.utils.HttpClient;

/**
 * HTTPS client for the SovietGram sync API.
 *
 * <p>Every authenticated call names the account it speaks for. The bearer token is that account's
 * base64url-encoded 128-byte token, issued once by one of the two Telegram bots, and the server
 * derives the caller's telegram id from the token itself — the request never carries an id. That is
 * exactly why the account has to be explicit here: passing the wrong one would silently write one
 * account's data under another's identity.
 *
 * <p>Writes are additionally signed:
 * <pre>
 *     X-Timestamp: unix SECONDS (integer, as a string)
 *     X-Signature: lowercase-hex(HMAC-SHA256(
 *         key   = raw 128 token bytes,
 *         msg   = METHOD "\n" PATH "\n" TIMESTAMP "\n" sha256hex(body)
 *     ))
 * </pre>
 * The window is 30 seconds — 30 minutes for {@code POST /v1/media}, whose body takes long enough
 * to send that the transfer itself would otherwise outlast it; the server declares that per route.
 * Accepted signatures are recorded server-side (used_signatures table) to reject replays. Both the
 * timestamp unit (seconds, not milliseconds) and the signature encoding (lowercase hex, not
 * base64url) must match the server's verifyWriteSignature exactly.
 */
public final class SovietGramApiClient {

    /**
     * Shared pool for all API traffic. Core size 0 so an idle client keeps no threads alive, max 4
     * so a burst (profile push for several accounts plus a gift poll) still overlaps instead of
     * queueing behind one socket.
     */
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            0, 4, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
            r -> {
                final Thread t = new Thread(r, "SovietGramApi");
                t.setDaemon(true);
                return t;
            });

    private SovietGramApiClient() {
    }

    /** True when {@code account} can make an authenticated call: it holds a token and we know a server. */
    public static boolean isReady(int account) {
        return SovietGramTokenStore.hasToken(account) && !TextUtils.isEmpty(ApiServersHelper.baseUrl());
    }

    public interface Callback {
        void onResult(@Nullable JSONObject body, @Nullable String error);
    }

    public static void get(int account, String path, Callback cb) {
        request(account, "GET", path, null, true, cb);
    }

    public static void postSigned(int account, String path, JSONObject body, Callback cb) {
        request(account, "POST", path, body, true, cb);
    }

    public static void putSigned(int account, String path, JSONObject body, Callback cb) {
        request(account, "PUT", path, body, true, cb);
    }

    public static void deleteSigned(int account, String path, Callback cb) {
        request(account, "DELETE", path, null, true, cb);
    }

    /**
     * Unauthenticated POST, used only for the bootstrap {@code POST /v1/auth/challenge} call the
     * client makes before it owns a token. Sends no Authorization header and no write signature;
     * the matching server route is declared {@code public} and skips the whole auth/signature stack.
     * Still needs a base URL, so callers should have a server selected first.
     */
    public static void postPublic(String path, @Nullable JSONObject body, Callback cb) {
        request(-1, "POST", path, body, false, cb);
    }

    /**
     * Unauthenticated GET, for the lists that are the same for everybody — the badges, at the time
     * of writing. No token, so it works before an account has one and costs the server nothing to
     * serve from cache. Still needs a base URL.
     */
    public static void getPublic(String path, Callback cb) {
        request(-1, "GET", path, null, false, cb);
    }

    // ---------------------------------------------------------------- picture upload

    /** The slots {@code POST /v1/media} accepts; anything else is sent as "other". */
    private static final String SLOT_OTHER = "other";

    /**
     * Ceiling on a still, matching the server's own (routes/media.ts).
     *
     * <p>Sized off what the still path can actually produce rather than off what a gallery holds:
     * {@link tw.nekomimi.nekogram.helpers.CustomProfileMedia} scales anything oversized to 2048px
     * and re-encodes it as JPEG at quality 85 down to 55, so a photograph lands well under 3MB and
     * anything over this is shrunk rather than refused. Measured against the published population:
     * of 151 stills, the median is 80KB and the largest is 6.88MB, so nothing real is turned away.
     */
    public static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    /**
     * Ceiling on an animation, matching the server's own (routes/media.ts).
     *
     * <p>Higher than {@link #MAX_IMAGE_BYTES} because nothing can shrink one: a video picked from
     * the gallery is copied into the slot byte for byte, and running it through a bitmap decoder
     * would publish its first frame as the whole banner. So unlike the still limit, this really is
     * the budget somebody making a look has to work within.
     *
     * <p>Which is why it is set from the published population rather than a round number. Of the 72
     * animated banners and backgrounds in the workshop: median 1.68MB, p90 8.38MB, and then a thin
     * tail of 12.34, 16.22, 21.99, 28.46 and 43.66MB. At 32MB this covers 71 of the 72 — everything
     * anybody has published except one 43.66MB background.
     *
     * <p>Going higher is not free, and 32 is where the two costs cross. The upload builds its base64
     * envelope in one buffer, so the phone holds the file and about a third again while it sends:
     * some 75MB at this ceiling, which is a lot but survivable, where covering that last file would
     * be over a hundred and would kill the app outright on a low-end phone. The server itself accepts
     * 50MB, so raising this further is a client-side memory decision and nothing else — and the file
     * it would buy is reachable anyway, from the URL the look carries and through the workshop's own
     * proxy when that host is blocked. Going lower starts cutting into p90.
     */
    public static final int MAX_VIDEO_BYTES = 32 * 1024 * 1024;

    /**
     * The largest upload of any kind, which is what the transport itself has to be sized against —
     * the base64 envelope below, the server's body limit, and the box's memory all key off this
     * rather than off the per-kind limits, since none of them know a still from an animation until
     * the bytes have already been received.
     */
    public static final int MAX_MEDIA_BYTES = MAX_VIDEO_BYTES;

    /** A mime is pasted into a JSON string literal, so only this plain shape is passed through. */
    private static final java.util.regex.Pattern MIME_RE =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9.+-]{0,30}/[a-z0-9][a-z0-9.+-]{0,30}$");

    /**
     * Uploads one picture and answers the server's {@code 201} body
     * ({@code sha}, {@code kind}, {@code slot}, {@code mime}, {@code size}, {@code path}).
     *
     * <p>Blocking, and deliberately so: every caller is already on a worker thread and has to know
     * whether the bytes landed before it decides what to record against the slot.
     *
     * <p>The bytes travel base64 inside a JSON envelope rather than as a raw octet-stream body. The
     * write signature covers the body and the path but <em>not</em> the query string, so slot and
     * mime passed as query parameters would be unsigned and rewritable in flight. The cost is a
     * third more bytes on the wire, and the envelope is built straight into its final buffer a
     * block at a time — a video at {@link #MAX_MEDIA_BYTES} would otherwise hold the source, a
     * complete base64 copy and the envelope at once, some 88MB of a phone's heap for one banner.
     *
     * @param mime what we believe the bytes are; only a hint, the server sniffs them itself.
     */
    @WorkerThread
    public static JSONObject uploadMedia(int account, String slot, @Nullable String mime, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("empty_media");
        }
        if (data.length > MAX_MEDIA_BYTES) {
            throw new IOException("media_too_large");
        }

        final StringBuilder head = new StringBuilder("{\"slot\":\"").append(mediaSlot(slot)).append('"');
        final String cleanMime = mime == null ? "" : mime.trim().toLowerCase();
        if (MIME_RE.matcher(cleanMime).matches()) {
            head.append(",\"mime\":\"").append(cleanMime).append('"');
        }
        head.append(",\"data\":\"");

        final byte[] prefix = head.toString().getBytes(StandardCharsets.US_ASCII);
        final byte[] suffix = "\"}".getBytes(StandardCharsets.US_ASCII);
        final byte[] body = new byte[prefix.length + base64Length(data.length) + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        encodeBase64Into(data, body, prefix.length);
        System.arraycopy(suffix, 0, body, body.length - suffix.length, suffix.length);

        return readJson(account, execute(account, "POST", "/v1/media", body, true,
                HttpClient.INSTANCE.getUploadInstance()), true);
    }

    /** How many base64 characters {@code length} bytes encode to, padded to a multiple of four. */
    private static int base64Length(int length) {
        return (length + 2) / 3 * 4;
    }

    /**
     * Encodes {@code data} as base64 into {@code out} at {@code offset}.
     *
     * <p>Done in blocks whose size is a multiple of three, which is what makes this equal to
     * encoding the whole array in one call: a group of three bytes is what becomes four base64
     * characters, so a block boundary that respects it never pads mid-stream. Only the final,
     * possibly short block can pad, exactly as it would on its own.
     */
    private static void encodeBase64Into(byte[] data, byte[] out, int offset) {
        final int block = 3 * 512 * 1024;
        int at = offset;
        for (int read = 0; read < data.length; read += block) {
            final int len = Math.min(block, data.length - read);
            // Base64.encode allocates its own array, so this is one 2.7MB temporary per block
            // rather than one 67MB temporary for the whole picture.
            final byte[] encoded = Base64.encode(data, read, len, Base64.NO_WRAP);
            System.arraycopy(encoded, 0, out, at, encoded.length);
            at += encoded.length;
        }
    }

    private static String mediaSlot(@Nullable String slot) {
        if ("banner".equals(slot) || "background".equals(slot)) {
            return slot;
        }
        return SLOT_OTHER;
    }

    /** Dispatch on the shared background pool so the call site doesn't have to think about threads. */
    private static void request(int account, String method, String path, @Nullable JSONObject body, boolean authed, Callback cb) {
        final byte[] bodyBytes = body == null ? null : body.toString().getBytes(StandardCharsets.UTF_8);
        EXECUTOR.execute(() -> {
            try {
                final JSONObject json = readJson(account,
                        execute(account, method, path, bodyBytes, authed, HttpClient.INSTANCE.getInstance()), authed);
                deliver(cb, json, null);
            } catch (Throwable e) {
                if (!(e instanceof ApiError)) {
                    // An HTTP error is the server's answer, not a client fault; the caller logs it
                    // with its own context. Anything else is worth a stack trace.
                    FileLog.e(e);
                }
                deliver(cb, null, e.getMessage());
            }
        });
    }

    /** An error the server answered with, carrying the message the callback reports. */
    private static final class ApiError extends IOException {
        ApiError(String message) {
            super(message);
        }
    }

    /**
     * Reads one response into JSON, or throws with the message the caller reports.
     *
     * <p>The 401/403 branch is what keeps a token the server no longer accepts from staying stored
     * forever, which would leave every sync feature for that account dead for good.
     */
    @WorkerThread
    private static JSONObject readJson(int account, Response response, boolean authed) throws IOException {
        try (Response r = response) {
            final ResponseBody rb = r.body();
            final String text = rb == null ? "" : rb.string();
            if (!r.isSuccessful()) {
                if (authed && (r.code() == 401 || r.code() == 403)) {
                    SovietGramAuthHelper.getInstance().onTokenRejected(account);
                }
                throw new ApiError("HTTP " + r.code() + ": " + text);
            }
            if (TextUtils.isEmpty(text)) {
                return new JSONObject();
            }
            try {
                return new JSONObject(text);
            } catch (JSONException e) {
                throw new ApiError("bad_response");
            }
        }
    }

    private static void deliver(Callback cb, JSONObject body, String error) {
        if (cb == null) return;
        org.telegram.messenger.AndroidUtilities.runOnUIThread(() -> cb.onResult(body, error));
    }

    @WorkerThread
    private static Response execute(int account, String method, String path, @Nullable byte[] bodyBytes,
                                    boolean authed, OkHttpClient http) throws IOException {
        final String base = ApiServersHelper.baseUrl();
        if (TextUtils.isEmpty(base)) {
            throw new IOException("api_not_ready");
        }
        if (!path.startsWith("/")) path = "/" + path;

        final boolean isWrite = !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method);

        final Request.Builder req = new Request.Builder()
                .url(base + path)
                .header("User-Agent", "SovietGram/api");

        if (authed) {
            final String token = SovietGramTokenStore.tokenForAccount(account);
            if (TextUtils.isEmpty(token)) {
                throw new IOException("api_not_ready");
            }
            req.header("Authorization", "Bearer " + token);
            if (isWrite) {
                // unix SECONDS — the server's ±30s window compares against Math.floor(Date.now()/1000);
                // sending milliseconds here would always read as far-future and be rejected as stale.
                final long ts = System.currentTimeMillis() / 1000L;
                final String sig = sign(token, method.toUpperCase(), path, ts,
                        bodyBytes == null ? new byte[0] : bodyBytes);
                req.header("X-Timestamp", String.valueOf(ts));
                req.header("X-Signature", sig);
            }
        }

        final RequestBody rb = bodyBytes == null
                ? (isWrite ? RequestBody.create(new byte[0], HttpClient.MEDIA_TYPE_JSON) : null)
                : RequestBody.create(bodyBytes, HttpClient.MEDIA_TYPE_JSON);

        switch (method.toUpperCase()) {
            case "GET":
                req.get();
                break;
            case "POST":
                req.post(rb != null ? rb : RequestBody.create(new byte[0], HttpClient.MEDIA_TYPE_JSON));
                break;
            case "PUT":
                req.put(rb != null ? rb : RequestBody.create(new byte[0], HttpClient.MEDIA_TYPE_JSON));
                break;
            case "DELETE":
                if (rb != null) req.delete(rb);
                else req.delete();
                break;
            default:
                throw new IOException("unsupported method: " + method);
        }
        return http.newCall(req.build()).execute();
    }

    /**
     * HMAC-SHA256 write signature. The HMAC key is the RAW 128 token bytes, not the base64url
     * string — matches the server's {@code rawTokenBytes()} exactly. The message is the four
     * canonical fields joined by newlines, with the body reduced to a lowercase sha256 hex to
     * keep the signed material bounded regardless of payload size.
     */
    @VisibleForSigning
    static String sign(String tokenB64Url, String method, String path, long timestamp, byte[] body) {
        try {
            final byte[] rawTokenBytes = Base64.decode(tokenB64Url, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            final String bodyHash = sha256Hex(body);
            final String msg = method + "\n" + path + "\n" + timestamp + "\n" + bodyHash;
            final Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(rawTokenBytes, "HmacSHA256"));
            final byte[] sig = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            // Lowercase hex, NOT base64url: the server validates X-Signature against ^[0-9a-f]{64}$.
            return hex(sig);
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    /** Lowercase hex encoding, matching the server's digest('hex') / createHmac(...).digest('hex'). */
    private static String hex(byte[] data) {
        final StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16));
            sb.append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }

    /** Marker for sign() — internal but reused by unit tests. */
    @interface VisibleForSigning {
    }
}
