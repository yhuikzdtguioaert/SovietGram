package tw.nekomimi.nekogram.helpers;

import android.text.TextUtils;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a Custom Profile picture actually <em>is</em>, read off its first bytes.
 *
 * <p>Every slot in a look holds one file, and how it has to be drawn — decoded once as a bitmap, or
 * played frame by frame — is a property of that file and of nothing else. It used to be decided from
 * the mime string a workshop entry declares, which is wrong in both directions and is the whole of
 * "some looks apply and some don't":
 * <ul>
 *     <li>{@code image/webp} was taken to mean an animation. Most published webp banners are ordinary
 *         still pictures, and a still handed to the player decodes to nothing at all — the header came
 *         out blank, with no error anywhere, for every look that ships one;</li>
 *     <li>a legacy asset, declared as a bare sha with no mime at all, was taken to be a still. An
 *         animated webp or an APNG then froze on whatever the bitmap decoder made of its first
 *         frame.</li>
 * </ul>
 *
 * <p>So the file is sniffed instead, which is what the reference plugin does and where these magic
 * numbers come from. The two cases the container alone cannot answer are the ones that matter: a webp
 * is animated only if it carries an {@code ANIM}/{@code ANMF} chunk, and a png only if {@code acTL}
 * appears before the first {@code IDAT}. Both need a look inside the file, and both are the difference
 * between a picture that draws and one that does not.
 *
 * <p>Answers are cached against the file's identity (path, size, mtime) because the draw path asks on
 * every frame and the question cannot change without one of those changing.
 */
public final class CustomProfileFormat {

    /** As much of a file as is read to identify it — enough to reach a png's {@code acTL}. */
    private static final int HEAD_BYTES = 1024 * 1024;

    private static final int CACHE_ENTRIES = 8;

    /** What one file turned out to be. */
    public static final class Info {
        public final String mime;
        public final String extension;
        /**
         * Whether the file has frames — an animation or a video — and so has to be played rather than
         * decoded. The reference calls this "external", after where such a file has to be handed to.
         */
        public final boolean moving;

        Info(String mime, String extension, boolean moving) {
            this.mime = mime;
            this.extension = extension;
            this.moving = moving;
        }

        /** Whether this needs a video decoder specifically, as opposed to an animated-image one. */
        public boolean video() {
            return mime.startsWith("video/");
        }
    }

    private static final Map<String, Info> CACHE = new LinkedHashMap<>(4, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Info> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    /** A miss and a "this is nothing we know" are different answers; both have to be cached. */
    private static final Info UNKNOWN = new Info("", "", false);

    private CustomProfileFormat() {
    }

    /**
     * What the file at {@code path} is, or {@code null} for no file, an empty one, or bytes in no
     * format we can draw.
     */
    @Nullable
    public static Info inspect(@Nullable String path) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        final File file = new File(path);
        if (!file.isFile() || file.length() <= 0) {
            return null;
        }
        final String key = path + ":" + file.lastModified() + ":" + file.length();
        synchronized (CACHE) {
            final Info cached = CACHE.get(key);
            if (cached != null) {
                return cached == UNKNOWN ? null : cached;
            }
        }
        final Info info = read(file);
        synchronized (CACHE) {
            CACHE.put(key, info == null ? UNKNOWN : info);
        }
        return info;
    }

    /** Whether the file at {@code path} has to be played rather than decoded as a bitmap. */
    public static boolean moving(@Nullable String path) {
        final Info info = inspect(path);
        return info != null && info.moving;
    }

    /** Whether the file at {@code path} needs a video decoder. */
    public static boolean video(@Nullable String path) {
        final Info info = inspect(path);
        return info != null && info.video();
    }

    /** The mime of the file at {@code path}, empty when it is not one we recognise. */
    public static String mime(@Nullable String path) {
        final Info info = inspect(path);
        return info == null ? "" : info.mime;
    }

    /**
     * Whether these bytes start a single-frame picture. For callers holding a head of a file rather
     * than the file itself — a webp or a png can only be answered from more of it than they have, so
     * both are reported as moving, which is the safe way round for the size limits this feeds.
     */
    public static boolean still(@Nullable byte[] head, int length) {
        final Info info = inspect(head, length);
        return info != null && !info.moving;
    }

    @Nullable
    private static Info read(File file) {
        final int wanted = (int) Math.min(file.length(), HEAD_BYTES);
        final byte[] head = new byte[wanted];
        int offset = 0;
        try (FileInputStream in = new FileInputStream(file)) {
            while (offset < wanted) {
                final int read = in.read(head, offset, wanted - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
        } catch (Throwable ignore) {
            // An unreadable file is not a format we can name; the caller treats that as "no picture".
            return null;
        }
        return inspect(head, offset);
    }

    /**
     * The formats the reference recognises, in its own order. Anything else is {@code null} rather
     * than a guess: a caller that cannot name a file draws nothing, which is recoverable, where
     * playing a still or decoding a video is not.
     */
    @Nullable
    static Info inspect(@Nullable byte[] data, int length) {
        if (data == null || length < 4) {
            return null;
        }
        if (ascii(data, length, 0, "GIF87a") || ascii(data, length, 0, "GIF89a")) {
            return new Info("image/gif", "gif", true);
        }
        if (length >= 12 && ascii(data, length, 0, "RIFF") && ascii(data, length, 8, "WEBP")) {
            // VP8X carries the animation flag, but the chunk it promises is what actually proves it.
            final boolean animated = contains(data, length, "ANIM") || contains(data, length, "ANMF");
            return new Info("image/webp", "webp", animated);
        }
        if (length >= 12 && ascii(data, length, 4, "ftyp")) {
            if (ascii(data, length, 8, "qt  ")) {
                return new Info("video/quicktime", "mov", true);
            }
            return new Info("video/mp4", "mp4", true);
        }
        if ((data[0] & 0xFF) == 0x1A && (data[1] & 0xFF) == 0x45
                && (data[2] & 0xFF) == 0xDF && (data[3] & 0xFF) == 0xA3) {
            return new Info("video/webm", "webm", true);
        }
        if (length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return new Info("image/jpeg", "jpg", false);
        }
        if (length >= 8 && (data[0] & 0xFF) == 0x89 && ascii(data, length, 1, "PNG")) {
            final boolean animated = movingPng(data, length);
            return new Info(animated ? "image/apng" : "image/png", "png", animated);
        }
        if (length >= 2 && data[0] == 'B' && data[1] == 'M') {
            return new Info("image/bmp", "bmp", false);
        }
        return null;
    }

    /**
     * Whether a png is an APNG: {@code acTL} declares the animation and the format requires it before
     * the first {@code IDAT}, so reaching the image data first settles it as an ordinary png without
     * reading the rest of what may be a very large file.
     */
    private static boolean movingPng(byte[] data, int length) {
        for (int i = 8; i + 8 <= length; i++) {
            if (data[i] == 'I' && data[i + 1] == 'D' && data[i + 2] == 'A' && data[i + 3] == 'T') {
                return false;
            }
            if (data[i] == 'a' && data[i + 1] == 'c' && data[i + 2] == 'T' && data[i + 3] == 'L') {
                return true;
            }
        }
        return false;
    }

    private static boolean ascii(byte[] data, int length, int at, String text) {
        if (at < 0 || at + text.length() > length) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if ((data[at + i] & 0xFF) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean contains(byte[] data, int length, String text) {
        final int last = Math.min(length, data.length) - text.length();
        for (int i = 0; i <= last; i++) {
            if (ascii(data, length, i, text)) {
                return true;
            }
        }
        return false;
    }
}
