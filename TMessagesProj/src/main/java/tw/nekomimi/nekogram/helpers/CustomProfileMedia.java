package tw.nekomimi.nekogram.helpers;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.text.TextUtils;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.BulletinFactory;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;
import tw.nekomimi.nekogram.helpers.remote.ApiServersHelper;

/**
 * The banner and background pictures of a Custom Profile look, on the wire.
 *
 * <p>A picked picture is a file in our own storage, and a file cannot be synced. So each slot also
 * carries a <b>descriptor</b> — where the same bytes can be fetched from, and what they are — which
 * rides along in the synced blob like any other setting and lets the receiving client fetch them.
 * There are two kinds:
 * <ul>
 *     <li><b>our API</b> ({@code src:"api"}): the owner uploaded the bytes to {@code POST /v1/media}
 *         and the descriptor carries only their sha. This is what makes a picture out of the user's
 *         own gallery syncable at all — it has no public source anywhere else.</li>
 *     <li><b>a public host</b> ({@code url}): what a workshop look already has, its assets being
 *         GitHub release downloads. Kept as the fallback for anything too big for our own upload.</li>
 * </ul>
 *
 * <p>An API descriptor deliberately stores no absolute URL. The boxes are disposable and every
 * client picks whichever one answers a health probe fastest ({@link ApiServersHelper}), so a URL
 * baked in here would stop resolving for everybody the moment the fleet changed. The sha is joined
 * onto the reader's <em>own</em> current base instead, at fetch time.
 *
 * <p>Downloads are lazy: the first draw that needs a peer's picture finds nothing cached, starts one
 * fetch and paints the look's flat colour meanwhile; the fetch announces itself when it lands and the
 * header repaints. Failures back off, so a dead host costs one request a minute rather than one a
 * frame. The sha is checked against the bytes on arrival — it is content-addressed on both hosts, so
 * a truncated transfer can never be installed as somebody's banner.
 */
public final class CustomProfileMedia {

    private static final String CACHE_PREFIX = "cp_media_";

    /** How long a fetched picture is kept before the next successful download sweeps it. */
    private static final long CACHE_TTL_MS = 14L * 24 * 60 * 60 * 1000;

    /** A host that just failed is not asked again within this window. */
    private static final long RETRY_AFTER_MS = 60 * 1000L;

    /** Only http(s) — a descriptor is remote data, and must never be able to name a local file. */
    private static final String[] ALLOWED_SCHEMES = {"https://", "http://"};

    /** Marks a descriptor whose bytes we host ourselves. */
    private static final String SRC_API = "api";

    /** The download route on our own API; the sha completes it. */
    private static final String API_MEDIA_PATH = "/v1/media/";

    private static final Set<String> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, Long> failedAt = new ConcurrentHashMap<>();

    private CustomProfileMedia() {
    }

    // ---------------------------------------------------------------- the descriptor

    /** Where one slot's picture can be fetched from, and what it is. */
    public static final class Ref {
        public final String sha;
        /** Absolute http(s) source, or empty when the bytes live on our own API. */
        public final String url;
        public final String mime;
        /** The uploading server's own verdict, {@code photo} or {@code video}, when it gave one. */
        public final String kind;
        /** Whether the bytes are ours to serve, i.e. the URL is built from the current box. */
        public final boolean api;

        Ref(String sha, String url, String mime, String kind, boolean api) {
            this.sha = sha == null ? "" : sha.toLowerCase();
            this.url = url == null ? "" : url;
            this.mime = mime == null ? "" : mime;
            this.kind = kind == null ? "" : kind;
            this.api = api;
        }

    }

    /**
     * The descriptor for a workshop asset a peer can fetch straight from the host it came from.
     *
     * <p>Empty when it cannot be pointed at: no public URL, or no sha to check the arriving bytes
     * against. Both happen in practice — the older works declare an asset as a bare sha against the
     * workshop's own file endpoint — and the caller publishes the bytes to our own API instead.
     */
    public static String describe(@Nullable WorkshopHelper.MediaRef ref) {
        if (ref == null || !isHex(ref.sha) || TextUtils.isEmpty(ref.url) || !allowedScheme(ref.url)) {
            return "";
        }
        try {
            final JSONObject json = new JSONObject();
            json.put("sha", ref.sha);
            json.put("url", ref.url);
            json.put("mime", ref.mime);
            return json.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    /**
     * The descriptor for a picture we uploaded, built from what {@code POST /v1/media} answered.
     * Carries the sha, the mime the server sniffed and its photo/video verdict — no URL, see the
     * class comment.
     */
    private static String describeUpload(@Nullable JSONObject uploaded) {
        if (uploaded == null) {
            return "";
        }
        final String sha = uploaded.optString("sha", "").trim();
        if (!isHex(sha)) {
            return "";
        }
        try {
            final JSONObject json = new JSONObject();
            json.put("src", SRC_API);
            json.put("sha", sha);
            json.put("mime", uploaded.optString("mime", "").trim().toLowerCase());
            json.put("kind", uploaded.optString("kind", "").trim().toLowerCase());
            return json.toString();
        } catch (Throwable e) {
            FileLog.e(e);
            return "";
        }
    }

    /**
     * Records where the picture just installed into a slot came from, so it syncs.
     *
     * @param descriptor {@link #describe} / {@link #describeUpload} output, or empty to say the slot
     *                   has no shareable source.
     */
    public static void remember(boolean banner, String descriptor) {
        remember(banner ? SLOT_BANNER : SLOT_BACKGROUND, descriptor);
    }

    /** {@link #remember(boolean, String)} for any slot, including the look's own font. */
    public static void remember(int slot, String descriptor) {
        item(slot).setConfigString(descriptor == null ? "" : descriptor);
    }

    /**
     * Says the slot's picture has no shareable source. Peers then fall back to the look's flat colour
     * instead of being pointed at whatever the previous look was hosting.
     */
    public static void forget(boolean banner) {
        remember(banner, "");
    }

    /**
     * Drops the descriptor for any slot. Called before a replacement is uploaded so readers stop
     * being sent after the file that is being replaced, rather than showing it until the new one
     * lands.
     */
    public static void forget(int slot) {
        remember(slot, "");
    }

    /**
     * The four slots a look can host bytes for. Most of this class does not care which one it is
     * handling — a descriptor, a cache file and a fetch are the same work for a banner and for a
     * typeface — so the slot travels as one of these rather than as a boolean, which only ever
     * described the two picture slots. The name and the bubble keep separate typeface slots because
     * a look can put a different font in each. A fifth covers everything that is not one of the
     * four: a row's own picture, of which a look can carry many.
     */
    static final int SLOT_BANNER = 0;
    static final int SLOT_BACKGROUND = 1;
    public static final int SLOT_FONT = 2;
    /** The bubble's own typeface, when it is not copying the name's. */
    public static final int SLOT_THOUGHT_FONT = 3;
    public static final int SLOT_OTHER = 4;

    private static ConfigItem item(boolean banner) {
        return item(banner ? SLOT_BANNER : SLOT_BACKGROUND);
    }

    private static ConfigItem item(int slot) {
        return switch (slot) {
            case SLOT_BANNER -> NekoConfig.customProfileBannerMedia;
            case SLOT_FONT -> NekoConfig.customProfileNameFontMedia;
            case SLOT_THOUGHT_FONT -> NekoConfig.customProfileThoughtFontMedia;
            default -> NekoConfig.customProfileBackgroundMedia;
        };
    }

    /** What the upload route calls this slot; one of its {@code slot} enum values. */
    private static String slotName(int slot) {
        return switch (slot) {
            case SLOT_BANNER -> "banner";
            case SLOT_FONT, SLOT_THOUGHT_FONT -> "font";
            case SLOT_OTHER -> "other";
            default -> "background";
        };
    }

    /**
     * The descriptor a peer's synced look carries for one slot, or {@code null} when it carries none.
     * Parsed defensively: this is third-party JSON, so the sha has to be plain hex (it becomes a cache
     * file name) and a {@code url} that is not plainly http(s) is dropped rather than handed to a
     * downloader.
     */
    @Nullable
    private static Ref parse(@Nullable JSONObject look, boolean banner) {
        return parse(look, banner ? SLOT_BANNER : SLOT_BACKGROUND);
    }

    @Nullable
    private static Ref parse(@Nullable JSONObject look, int slot) {
        return look == null ? null : parseDescriptor(look.optString(item(slot).getKey(), ""));
    }

    /** {@link #parse} for one raw descriptor string, whoever it belongs to. */
    @Nullable
    private static Ref parseDescriptor(@Nullable String raw) {
        if (TextUtils.isEmpty(raw) || "null".equals(raw)) {
            return null;
        }
        try {
            final JSONObject json = new JSONObject(raw);
            final String sha = json.optString("sha", "").trim();
            if (!isHex(sha)) {
                // Without a hash there is nothing to check the bytes against, and they land in the
                // slot the profile paints from.
                return null;
            }
            final String mime = json.optString("mime", "").trim().toLowerCase();
            final String kind = json.optString("kind", "").trim().toLowerCase();
            if (SRC_API.equals(json.optString("src", "").trim())) {
                return new Ref(sha, "", mime, kind, true);
            }
            final String url = json.optString("url", "").trim();
            if (TextUtils.isEmpty(url) || !allowedScheme(url)) {
                return null;
            }
            return new Ref(sha, url, mime, kind, false);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    private static boolean allowedScheme(String url) {
        for (String scheme : ALLOWED_SCHEMES) {
            if (url.regionMatches(true, 0, scheme, 0, scheme.length())) {
                return true;
            }
        }
        return false;
    }

    /** A sha is also the cache file's name, so anything but plain hex is refused outright. */
    private static boolean isHex(String value) {
        if (value.length() < 16 || value.length() > 128) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f') && (c < 'A' || c > 'F')) {
                return false;
            }
        }
        return true;
    }

    // ---------------------------------------------------------------- publishing our own

    /** The longest edge an oversized still is scaled down to before it is uploaded. */
    private static final int MAX_UPLOAD_DIMENSION = 2048;

    /**
     * Bumped per slot on every pick, so an upload that lands after the user has already replaced the
     * picture cannot record a descriptor for bytes the slot no longer holds.
     */
    private static final AtomicInteger bannerGeneration = new AtomicInteger();
    private static final AtomicInteger backgroundGeneration = new AtomicInteger();
    private static final AtomicInteger fontGeneration = new AtomicInteger();
    private static final AtomicInteger thoughtFontGeneration = new AtomicInteger();

    /**
     * Uploads a slot's picture to our own API and returns the descriptor to store against it, or
     * {@code null} when it could not be published (no account able to talk to the API, a transfer
     * failure, or bytes too large to shrink into the limit).
     *
     * <p>Blocking on purpose: every caller is already off the main thread and has to know whether the
     * bytes landed before it decides what the slot's descriptor should be.
     */
    @WorkerThread
    @Nullable
    public static String publish(boolean banner, @Nullable byte[] data, @Nullable String mimeHint) {
        return publish(banner ? SLOT_BANNER : SLOT_BACKGROUND, data, mimeHint);
    }

    /**
     * {@link #publish(boolean, byte[], String)} for any slot.
     *
     * <p>A font is uploaded exactly as it is. The shrink-to-fit path below re-encodes an oversized
     * still as a JPEG, which for a typeface would upload a picture of nothing — so the font slot goes
     * straight to the transport and is simply refused if it does not fit, which no real font comes
     * close to.
     */
    @WorkerThread
    @Nullable
    public static String publish(int slot, @Nullable byte[] data, @Nullable String mimeHint) {
        final int account = liveAccount();
        if (account < 0 || data == null || data.length == 0) {
            return null;
        }
        final byte[] payload = isFont(slot) ? data : fitForUpload(data);
        if (payload == null) {
            reportTooLarge(data.length, limitFor(data));
            return null;
        }
        if (isFont(slot) && payload.length > SovietGramApiClient.MAX_IMAGE_BYTES) {
            reportTooLarge(payload.length, SovietGramApiClient.MAX_IMAGE_BYTES);
            return null;
        }
        try {
            final JSONObject response = SovietGramApiClient.uploadMedia(
                    account, slotName(slot),
                    payload == data ? mimeHint : "image/jpeg", payload);
            final String descriptor = describeUpload(response);
            return TextUtils.isEmpty(descriptor) ? null : descriptor;
        } catch (Throwable e) {
            // A failed upload is not a failed install: the look is already applied locally, it just
            // does not travel yet. Logged without a stack trace — the usual cause is a dead box.
            final String message = e.getMessage();
            FileLog.e("CustomProfileMedia: upload failed: " + message);
            // Size is the one failure the user can do something about, and the one the server can
            // see differently from us — a box configured below our own ceiling, or nginx refusing
            // the body before the API is asked. Worth saying out loud rather than leaving the
            // picture quietly local.
            if (message != null && message.contains("too_large")) {
                reportTooLarge(payload.length, limitFor(payload));
            }
            return null;
        }
    }

    /**
     * The size of the last picture we told the user was too large, or {@code -1}.
     *
     * <p>Publishing is retried whenever the look is pushed, so without this the same picture would
     * announce itself again on every push. Comparing sizes rather than keeping a flag means a
     * <em>different</em> oversized picture still gets its own message.
     */
    private static final AtomicLong reportedTooLarge = new AtomicLong(-1);

    /**
     * Tells the user their picture is too big to publish, at most once per picture.
     *
     * <p>The alternative is what this replaces: the look applies locally, nothing is said, and the
     * banner is simply invisible to everyone else — indistinguishable from a bug. It is a warning
     * rather than a failure, because the look itself did apply.
     *
     * @param limit the ceiling that actually applied, which is not the same for a still as for an
     *              animation — quoting the wrong one would send somebody off to shrink a video to a
     *              size that was never going to be asked of it.
     */
    private static void reportTooLarge(long bytes, int limit) {
        if (reportedTooLarge.getAndSet(bytes) == bytes) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> BulletinFactory.global().createErrorBulletin(
                LocaleController.formatString(R.string.CustomProfileMediaTooLarge,
                        AndroidUtilities.formatFileSize(bytes),
                        AndroidUtilities.formatFileSize(limit))).show());
    }

    /**
     * Publishes the file now sitting in a slot, in the background, and records the descriptor if it
     * is still the current picture when the upload lands. This is the gallery-pick path: the picker
     * hands over a file and nothing else, so there is no source to point a peer at until we host the
     * bytes ourselves.
     */
    public static void publishAsync(boolean banner, @Nullable String path) {
        publishAsync(banner ? SLOT_BANNER : SLOT_BACKGROUND, path);
    }

    /** {@link #publishAsync(boolean, String)} for any slot, the look's own font included. */
    public static void publishAsync(int slot, @Nullable String path) {
        if (TextUtils.isEmpty(path)) {
            return;
        }
        final AtomicInteger generation = switch (slot) {
            case SLOT_BANNER -> bannerGeneration;
            case SLOT_FONT -> fontGeneration;
            case SLOT_THOUGHT_FONT -> thoughtFontGeneration;
            default -> backgroundGeneration;
        };
        final int mine = generation.incrementAndGet();
        // Which account's look this is. The Custom Profile settings are global config items holding
        // one account's values at a time, so an account switch mid-upload would otherwise write this
        // descriptor into somebody else's look.
        final long owner = SovietGramAccountScope.owner();
        Utilities.globalQueue.postRunnable(() -> {
            final String descriptor = publish(slot, read(new File(path)), null);
            if (TextUtils.isEmpty(descriptor)) {
                return;
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (generation.get() != mine || !SovietGramAccountScope.isOwner(owner)) {
                    return;
                }
                remember(slot, descriptor);
                SovietGramSync.scheduleProfilePush();
            });
        });
    }

    /**
     * Makes sure the live look's picture slots carry a descriptor <em>we</em> serve, uploading the
     * local file when they do not. Cheap and idempotent: it looks at two config strings and, in the
     * healthy case, at two file names.
     *
     * <p>This is what rescues looks installed before there was anywhere to upload to. A workshop asset
     * declared as a bare sha against the workshop's own file endpoint used to be described as
     * {@code {"sha":…,"url":""}} — a descriptor naming no host at all, which every reader drops, so the
     * owner saw their banner and nobody else ever did. Those slots hold a perfectly good local file;
     * publishing it now is all it takes, and no picture has to be picked again.
     *
     * <p>A slot pointing at a public URL is healed too, not left alone. Those are the workshop's own
     * assets, which are GitHub release downloads: reachable for some readers and not for others, which
     * is exactly how a look ends up transferring for one peer and not the next. Once the bytes are on
     * our own API the picture no longer depends on a host we do not run — and if the upload cannot
     * happen (too large, no token yet) the descriptor is left as it was, so the URL stays as the
     * fallback it is.
     *
     * <p>Only the live account's look can be healed: the slots are global config items holding one
     * account's values at a time, and another account's stored snapshot names files this one may not
     * even have. Each account is therefore healed while it is the live one — which is what happens the
     * first time it pushes anything after a switch.
     */
    public static void ensurePublished() {
        if (!NekoConfig.customProfileEnabled.Bool()) {
            return;
        }
        ensureSlot(SLOT_BANNER);
        ensureSlot(SLOT_BACKGROUND);
        ensureSlot(SLOT_FONT);
        ensureSlot(SLOT_THOUGHT_FONT);
    }

    /** Both typeface slots behave the same everywhere but in which config item they read. */
    private static boolean isFont(int slot) {
        return slot == SLOT_FONT || slot == SLOT_THOUGHT_FONT;
    }

    private static void ensureSlot(int slot) {
        if (slot == SLOT_FONT) {
            // The font is in use when the typeface index points at the bundled file, which is the
            // one index that means "the file this look ships" rather than a family everybody has.
            if (NekoConfig.customProfileNameFont.Int() != FONT_BUNDLED) {
                return;
            }
        } else if (slot == SLOT_THOUGHT_FONT) {
            // The bubble only has a font of its own when it has stopped copying the name's.
            if (NekoConfig.customProfileThoughtFontCopy.Bool()
                    || NekoConfig.customProfileThoughtFont.Int() != FONT_BUNDLED) {
                return;
            }
        } else {
            final int type = (slot == SLOT_BANNER
                    ? NekoConfig.customProfileBannerType : NekoConfig.customProfileBackgroundType).Int();
            if (type != 3 && type != 4) {
                return; // the slot paints a colour or a gradient; there is no picture to publish
            }
        }
        final Ref current = parseDescriptor(item(slot).String());
        if (current != null && current.api) {
            return; // already ours to serve, and content-addressed, so it cannot go stale
        }
        final String path = switch (slot) {
            case SLOT_BANNER -> NekoConfig.customProfileBannerPath.String();
            case SLOT_FONT -> NekoConfig.customProfileNameFontPath.String();
            case SLOT_THOUGHT_FONT -> NekoConfig.customProfileThoughtFontPath.String();
            default -> NekoConfig.customProfileBackgroundPath.String();
        };
        if (TextUtils.isEmpty(path)) {
            return;
        }
        final File file = new File(path);
        if (!file.exists()) {
            return;
        }
        if (file.length() > SovietGramApiClient.MAX_VIDEO_BYTES && !stillFile(file)) {
            // Over the limit and an animation, so there is nothing to try: only a still can be
            // scaled down to fit. Checked from the file's first bytes rather than by reading it,
            // because publishing is retried on every push and reading tens of megabytes to reach
            // the same answer each time is the kind of waste nobody would notice.
            if (current == null) {
                // Nothing at all serves these bytes to anyone else — unlike a slot still holding a
                // public URL, which does reach some viewers and is left alone as the fallback it is.
                reportTooLarge(file.length(), SovietGramApiClient.MAX_VIDEO_BYTES);
            }
            return;
        }
        publishAsync(slot, path);
    }

    /** The typeface index that means "the file this look ships"; see WorkshopStyle. */
    private static final int FONT_BUNDLED = 7;

    /**
     * The account whose look is the live one and which can reach the API, or {@code -1}.
     *
     * <p>The picture in a slot belongs to whoever owns the live config values
     * ({@link SovietGramAccountScope}); uploading it under another account's token would file it
     * under the wrong identity.
     */
    private static int liveAccount() {
        for (int account : SovietGramTokenStore.accountsWithToken()) {
            if (SovietGramAccountScope.isLive(account) && SovietGramApiClient.isReady(account)) {
                return account;
            }
        }
        return -1;
    }

    /** A slot's file as bytes, or {@code null} when it is missing or unreadable. */
    @Nullable
    private static byte[] read(File file) {
        try {
            final long length = file.length();
            if (!file.exists() || length == 0) {
                return null;
            }
            // Four times the largest upload of any kind: a still over the limit is scaled down by
            // fitForUpload, so a camera original has to be readable even though it will never be
            // published at that size — but a file this far past it would only be read into memory
            // to be refused, and on a phone that is how an out-of-memory kill starts.
            if (length > 4L * SovietGramApiClient.MAX_MEDIA_BYTES) {
                reportTooLarge(length, SovietGramApiClient.MAX_MEDIA_BYTES);
                return null;
            }
            final byte[] data = new byte[(int) length];
            try (FileInputStream in = new FileInputStream(file)) {
                int offset = 0;
                while (offset < data.length) {
                    final int read = in.read(data, offset, data.length - offset);
                    if (read <= 0) {
                        break;
                    }
                    offset += read;
                }
                if (offset != data.length) {
                    return null;
                }
            }
            return data;
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * The ceiling these bytes have to fit under.
     *
     * <p>Two limits rather than one because the two kinds cost different things. A still is shrunk
     * to fit, so its limit only has to clear what {@link #fitForUpload} can produce out of a 2048px
     * JPEG — a generous limit there buys nothing and only widens what one upload costs in memory on
     * both ends. An animation is published exactly as picked, so its limit is the whole budget the
     * user has to work within, and it is set from what published animations actually weigh.
     */
    private static int limitFor(byte[] data) {
        return isStill(data) ? SovietGramApiClient.MAX_IMAGE_BYTES : SovietGramApiClient.MAX_VIDEO_BYTES;
    }

    /**
     * The bytes to actually upload: the originals when they fit, a scaled-down JPEG when they are a
     * still that does not, {@code null} when nothing can be done.
     *
     * <p>A phone camera picture is routinely bigger than the API's limit, and refusing it would mean
     * exactly the pictures users pick most often never sync. Only stills are re-encoded — running an
     * animation through a bitmap decoder would silently publish its first frame as the whole banner —
     * so an oversized video stays local.
     */
    @Nullable
    private static byte[] fitForUpload(byte[] data) {
        final int limit = limitFor(data);
        if (data.length <= limit) {
            return data;
        }
        if (!isStill(data)) {
            return null;
        }
        try {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            final BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight);
            final Bitmap bitmap = BitmapFactory.decodeByteArray(data, 0, data.length, options);
            if (bitmap == null) {
                return null;
            }
            try {
                for (int quality = 85; quality >= 55; quality -= 15) {
                    final ByteArrayOutputStream out = new ByteArrayOutputStream(512 * 1024);
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                        return null;
                    }
                    if (out.size() <= limit) {
                        return out.toByteArray();
                    }
                }
                return null;
            } finally {
                bitmap.recycle();
            }
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        }
    }

    /**
     * A single frame, i.e. something that can be re-encoded without losing an animation. Answered by
     * {@link CustomProfileFormat} rather than by a JPEG/PNG check of its own, which called every webp
     * an animation — so a still webp over the image limit was refused instead of being scaled to fit —
     * and called every png a still, which would have published an APNG's first frame as the whole
     * banner.
     */
    private static boolean isStill(byte[] data) {
        return CustomProfileFormat.still(data, data.length);
    }

    /**
     * {@link #isStill} for a file, so an oversized animation can be recognised without reading all of
     * it. Answers {@code false} for a file it cannot read or cannot name, which is the safe way round:
     * the caller then treats it as unshrinkable rather than loading it to find out.
     */
    private static boolean stillFile(File file) {
        final CustomProfileFormat.Info info = CustomProfileFormat.inspect(file.getAbsolutePath());
        return info != null && !info.moving;
    }

    private static int sampleSize(int width, int height) {
        int sample = 1;
        while (Math.max(width, height) / sample > MAX_UPLOAD_DIMENSION) {
            sample *= 2;
        }
        return sample;
    }

    // ---------------------------------------------------------------- the peer's copy

    /**
     * Whether a peer's look declares a picture for the slot at all. Cheap — no file or network work —
     * so the draw path can use it to decide whether the picture types are worth keeping.
     */
    public static boolean peerHasMedia(@Nullable JSONObject look, boolean banner) {
        return parse(look, banner) != null;
    }

    /**
     * The peer's picture as a local file, fetching it first if need be.
     *
     * @return the path once the bytes are on disk, or {@code null} while they are not — the caller
     *         paints the rest of the look and gets a repaint when the fetch lands.
     */
    /**
     * The peer's font file, fetched the same way their banner is. Null while it is not on disk,
     * which the name simply draws through in the reader's own font until the fetch lands.
     */
    @Nullable
    public static String peerFontPath(@Nullable JSONObject look) {
        return peerPath(look, SLOT_FONT);
    }

    /** The peer's bubble typeface, fetched exactly as their name's is. */
    @Nullable
    public static String peerThoughtFontPath(@Nullable JSONObject look) {
        return peerPath(look, SLOT_THOUGHT_FONT);
    }

    @Nullable
    public static String peerPath(@Nullable JSONObject look, boolean banner) {
        return peerPath(look, banner ? SLOT_BANNER : SLOT_BACKGROUND);
    }

    @Nullable
    private static String peerPath(@Nullable JSONObject look, int slot) {
        final Ref ref = parse(look, slot);
        if (ref == null) {
            return null;
        }
        final File file = cacheFile(ref.sha);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }
        fetch(ref, file);
        return null;
    }

    /**
     * Uploads bytes that belong to no slot and hands back the descriptor for them.
     *
     * <p>What the slots are for is that a look has exactly one banner and exactly one typeface, so a
     * config item can hold the descriptor. A look's own rows are a list, each with a picture of its
     * own, so the descriptor is kept in the row instead — see {@code CustomProfileExtraRows}. The
     * bytes are content-addressed either way, so two rows carrying the same picture cost one upload.
     */
    @Nullable
    public static String publishLoose(@Nullable byte[] data, @Nullable String mimeHint) {
        final String descriptor = publish(SLOT_OTHER, data, mimeHint);
        return TextUtils.isEmpty(descriptor) ? null : descriptor;
    }

    /**
     * A descriptor resolved to a file on this phone, fetching it first if need be.
     *
     * @return the path once the bytes are here, or {@code null} while they are not — the caller
     *         draws nothing and gets a repaint when the fetch lands.
     */
    @Nullable
    public static String pathFor(@Nullable String descriptor) {
        final Ref ref = parseDescriptor(descriptor);
        if (ref == null) {
            return null;
        }
        final File file = cacheFile(ref.sha);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }
        fetch(ref, file);
        return null;
    }

    private static File cacheFile(String sha) {
        return new File(ApplicationLoader.getFilesDirFixed(), CACHE_PREFIX + sha.toLowerCase());
    }

    /**
     * Where to fetch a descriptor's bytes from right now.
     *
     * <p>For our own API this is resolved per fetch rather than stored, because the base URL is
     * whichever box won the last health race and changes across launches.
     */
    private static String source(Ref ref) {
        if (!ref.api) {
            return ref.url;
        }
        final String base = ApiServersHelper.baseUrl();
        return TextUtils.isEmpty(base) ? "" : base + API_MEDIA_PATH + ref.sha;
    }

    /**
     * Downloads one descriptor's bytes into the cache, once. Deduplicated by sha, so the several draws
     * that happen before it lands start one request, and backed off after a failure.
     */
    private static void fetch(Ref ref, File target) {
        final String key = ref.sha.toLowerCase();
        final Long failed = failedAt.get(key);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_AFTER_MS) {
            return;
        }
        final String url = source(ref);
        if (TextUtils.isEmpty(url)) {
            // No box selected yet. Backed off like a failure so the draw path does not spin, and the
            // next attempt after the window will have one.
            failedAt.put(key, System.currentTimeMillis());
            return;
        }
        if (!inFlight.add(key)) {
            return;
        }
        Utilities.globalQueue.postRunnable(() -> {
            boolean ok = false;
            try {
                final byte[] data = WorkshopHelper.download(url, ref.sha);
                ok = write(data, target);
            } catch (Throwable e) {
                FileLog.e("CustomProfileMedia: fetch failed for " + ref.sha + ": " + e.getMessage());
            } finally {
                inFlight.remove(key);
            }
            if (ok) {
                failedAt.remove(key);
                sweep();
                // The header already drew the look's flat colour; this is what brings the picture on.
                CustomProfileHelper.onRemoteMediaReady();
            } else {
                failedAt.put(key, System.currentTimeMillis());
            }
        });
    }

    /** Through a temp file, so a half-finished download is never picked up as a complete picture. */
    private static boolean write(byte[] data, File target) {
        if (data == null || data.length == 0) {
            return false;
        }
        final File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
        } catch (Throwable e) {
            FileLog.e(e);
            temp.delete();
            return false;
        }
        target.delete();
        if (!temp.renameTo(target)) {
            temp.delete();
            return false;
        }
        return true;
    }

    /** Drops cached pictures nothing has asked for in a fortnight. Runs after a download, off-thread. */
    private static void sweep() {
        try {
            final File[] files = ApplicationLoader.getFilesDirFixed().listFiles();
            if (files == null) {
                return;
            }
            final long cutoff = System.currentTimeMillis() - CACHE_TTL_MS;
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith(CACHE_PREFIX) && file.lastModified() < cutoff) {
                    file.delete();
                }
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
