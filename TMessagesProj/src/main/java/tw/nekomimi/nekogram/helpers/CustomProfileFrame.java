package tw.nekomimi.nekogram.helpers;

import android.graphics.Bitmap;
import android.graphics.Canvas;

import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.helpers.frame.FrameBlanks;
import tw.nekomimi.nekogram.helpers.frame.FrameContour;
import tw.nekomimi.nekogram.helpers.frame.FrameGraphBuild;
import tw.nekomimi.nekogram.helpers.frame.FrameOutline;
import tw.nekomimi.nekogram.helpers.frame.FramePainter;
import tw.nekomimi.nekogram.helpers.frame.FrameSeam;
import tw.nekomimi.nekogram.helpers.frame.FrameSpec;
import tw.nekomimi.nekogram.helpers.frame.FrameUnwrap;

/**
 * The avatar frame the look on screen wears — the workshop's second gallery.
 *
 * <p>This is the profile's own way in: it reads the frame out of the config, keeps the avatar's
 * outline sampled at the size the avatar is currently drawn at, fetches each layer's picture, and
 * hands all three to a {@link FramePainter}. Everything about how a frame is described and drawn
 * lives in {@link tw.nekomimi.nekogram.helpers.frame} instead, so the studio can draw an arbitrary
 * frame against an arbitrary shape without going anywhere near the config.
 *
 * <p>A layer's picture comes from one of two places, and neither of them is a file on this phone:
 * <ul>
 *     <li>{@code blank:…} — one of eight shapes drawn in code. Nothing is fetched and nothing fails;</li>
 *     <li>an {@code http(s)} URL — fetched once into the cache through {@link WorkshopHelper}, which
 *         means it also gets the proxy fallback for networks that cannot reach GitHub.</li>
 * </ul>
 * That is why a frame needs no media descriptor to travel between users the way a banner does: the
 * spec is a string, the string carries public addresses, and every reader resolves them the same way.
 */
public final class CustomProfileFrame {

    /** Sampling the outline finer than this buys nothing at any avatar size the profile draws. */
    private static final int SAMPLES = FrameOutline.SAMPLES;
    /** Rebuild the outline only when the avatar's side moves by more than this, in pixels. */
    private static final float SIDE_STEP = 8f;

    private static final FramePainter painter = new FramePainter();
    private static final Sources sources = new Sources();

    private static String parsedFrom = "";
    private static FrameSpec parsed = FrameSpec.EMPTY;

    private static long contourKey = Long.MIN_VALUE;

    static {
        // The graph compiler has to know whether a layer's picture moves, and only the app can tell:
        // a blank never does, and a remote one is decided by the bytes once they are in the cache.
        FrameGraphBuild.probe(src -> {
            if (src == null || FrameBlanks.is(src)) {
                return false;
            }
            final File file = Sources.cacheFile(src);
            return file.isFile() && CustomProfileFormat.moving(file.getAbsolutePath());
        });
    }

    private CustomProfileFrame() {
    }

    // ---------------------------------------------------------------- the spec

    /** The frame the look on screen wears, as its raw spec. Empty when it wears none. */
    public static String spec() {
        return CustomProfileHelper.cfgString(NekoConfig.customProfileFrameSpec);
    }

    /** The frame the look on screen wears. */
    public static FrameSpec frame() {
        final String raw = spec();
        if (!raw.equals(parsedFrom)) {
            parsedFrom = raw;
            parsed = FrameSpec.parse(raw);
        }
        return parsed;
    }

    /** Whether there is anything to draw around the avatar. */
    public static boolean has() {
        return CustomProfileHelper.isEnabled() && !frame().isEmpty();
    }

    /** Whether the frame moves, and so whether the view drawing it must ask for the next frame. */
    public static boolean animating() {
        return has() && FramePainter.moving(frame());
    }

    /**
     * Where a frame's pictures come from, for anything that draws a frame other than the profile —
     * the studio's preview and the previews on its canvas. Shared deliberately: they are the same
     * pictures, fetched once, and a second cache would download every one of them again.
     */
    public static FramePainter.Sources sources() {
        return sources;
    }

    /** Reads a spec without touching the config; kept for the workshop's own validity checks. */
    public static FrameSpec parse(@Nullable String json) {
        return FrameSpec.parse(json);
    }

    /** Every picture the frame on screen needs. */
    public static Set<String> assets() {
        return frame().assets();
    }

    // ---------------------------------------------------------------- drawing

    /**
     * Draws the frame around an avatar occupying the given square.
     *
     * @param size  the avatar's side in pixels; the 256-unit space is mapped onto it.
     * @param alpha 0..255, so the frame fades with the avatar it belongs to.
     */
    public static void draw(Canvas canvas, float left, float top, float size, int alpha) {
        draw(canvas, left, top, size, size, alpha);
    }

    /**
     * Draws the frame around an avatar occupying the given box.
     *
     * <p>Width and height separately because the avatar's box is not always square — the profile
     * stretches it as the picture is opened — and a frame drawn square around a rectangle sits
     * beside the avatar rather than on it. The frame's own 256-unit space is mapped onto whatever
     * the box is, so it stretches with the avatar instead of drifting off it.
     */
    public static void draw(Canvas canvas, float left, float top, float width, float height,
                            int alpha) {
        final FrameSpec spec = frame();
        if (canvas == null || spec.isEmpty() || width <= 0 || height <= 0 || alpha <= 0) {
            return;
        }
        // The outline is sampled against the shorter side: it is the avatar's own shape, and the
        // shape is what the profile clips the picture to whatever the box around it is doing.
        if (!prepare(Math.min(width, height))) {
            return;
        }
        painter.draw(canvas, spec, left, top, width, height, FramePainter.toSpace(), 0f, alpha,
                sources);
    }

    /**
     * Keeps the avatar's outline sampled for the shape the look gives it and the size it is drawn
     * at. Both change: the shape when the look changes, the size continuously as the header
     * collapses — so the size is quantised to eight pixels, which is finer than the eye can tell and
     * coarse enough that a scroll does not resample on every frame.
     */
    private static boolean prepare(float size) {
        final int shape = CustomProfileHelper.cfgInt(NekoConfig.customProfileAvatarShape);
        final int radius = CustomProfileHelper.cfgInt(NekoConfig.customProfileAvatarRadius);
        final int smoothing = CustomProfileHelper.cfgInt(NekoConfig.customProfileAvatarSmoothing);
        final String points = CustomProfileHelper.cfgString(NekoConfig.customProfileAvatarPoints);
        long key = 527 + shape;
        key = key * 31 + radius;
        key = key * 31 + smoothing;
        key = key * 31 + points.hashCode();
        key = key * 31 + Math.round(size / SIDE_STEP);
        if (key != contourKey) {
            painter.contour(sample(shape, radius, smoothing, points, size));
            contourKey = key;
        }
        return painter.length() > 0f;
    }

    private static FrameContour sample(int shape, int radius, int smoothing, String points,
                                       float size) {
        return FrameOutline.of(shape, radius, smoothing,
                shape == 8 ? CustomProfileGfx.parsePoints(points) : null, size, SAMPLES);
    }

    /** Drops the decoded layers and the outline. Called when the look changes under them. */
    public static void invalidate() {
        parsedFrom = "";
        parsed = FrameSpec.EMPTY;
        contourKey = Long.MIN_VALUE;
        painter.contour(FrameContour.EMPTY);
        sources.clear();
        FrameSeam.clear();
    }

    // ---------------------------------------------------------------- pictures

    /**
     * Where a layer's picture comes from. A remote one is fetched once and the profile repaints when
     * it lands, which is the same contract a peer's banner has.
     */
    private static final class Sources implements FramePainter.Sources {

        private final Map<String, Bitmap> pictures = new HashMap<>();
        /** Rings already unrolled into strips, kept because unrolling one is a pixel loop. */
        private final Map<String, Bitmap> unwrapped = new HashMap<>();
        /**
         * Pictures that turned out not to be rings. Remembered because the draw path asks for every
         * layer on every frame, and without this a picture that cannot be unrolled would be scanned
         * again sixty times a second — which is not slow, it is fatal: the scan runs on the thread
         * that draws, and the app stops drawing anything at all.
         */
        private final java.util.Set<String> notRings = new java.util.HashSet<>();
        /**
         * Pictures that will not decode, and addresses that would not download. Same reason as
         * {@link #notRings}: this is asked once per layer per frame, so "try again" means trying
         * sixty times a second — a decode of a broken file on the drawing thread, or a download of
         * something that is not there, for as long as the profile is open.
         */
        private final java.util.Set<String> broken =
                java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
        private final Set<String> fetching =
                Collections.newSetFromMap(new ConcurrentHashMap<>());

        @Nullable
        @Override
        public Bitmap bitmap(FrameSpec.Layer layer, boolean corner) {
            if (corner) {
                return picture(layer.corner);
            }
            Bitmap source = picture(layer.src);
            // A frame published as a finished round border has to be unrolled before it can be laid
            // round a square avatar; wrapping the ring itself would only stretch it.
            if (source != null && !source.isRecycled()
                    && layer.round && layer.mode == FrameSpec.MODE_STRIP) {
                final Bitmap strip = unroll(layer.src, source);
                if (strip != null) {
                    source = strip;
                }
            }
            // A ribbon meeting itself shows a hard line unless the picture is mirrored first.
            return (layer.seamless && layer.mode == FrameSpec.MODE_STRIP)
                    ? FrameSeam.seamless(layer.src, source) : source;
        }

        @Nullable
        private Bitmap unroll(String src, Bitmap ring) {
            final Bitmap cached = unwrapped.get(src);
            if (cached != null && !cached.isRecycled()) {
                return cached;
            }
            if (notRings.contains(src)) {
                return null;
            }
            final Bitmap strip = unwrap(ring);
            if (strip == null) {
                notRings.add(src);
                return null;
            }
            unwrapped.put(src, strip);
            return strip;
        }

        /**
         * Finds the ring by scanning one radius outward from the centre for where the picture
         * starts and stops being opaque, then resamples it into a rectangle.
         */
        @Nullable
        private static Bitmap unwrap(Bitmap ring) {
            try {
                final int width = ring.getWidth();
                final int height = ring.getHeight();
                final int side = Math.min(width, height);
                if (side < 6) {
                    return null;
                }
                // Read once and write once. Reading a pixel at a time is a call across into the
                // platform for each of them, and there are hundreds of thousands here — enough to
                // stall the thread that is in the middle of drawing the profile.
                final int[] pixels = new int[width * height];
                ring.getPixels(pixels, 0, width, 0, 0, width, height);

                final float centre = side / 2f;
                final int reach = (int) centre;
                final int middleRow = Math.round(centre);
                final int[] alphas = new int[reach];
                for (int i = 0; i < reach; i++) {
                    final int x = Math.min(width - 1, Math.round(i + centre));
                    alphas[i] = pixels[middleRow * width + x] >>> 24;
                }
                final int[] edges = new int[2];
                if (!FrameUnwrap.ring(alphas, edges)) {
                    return null;
                }
                final int rows = FrameUnwrap.rows(edges[0], edges[1]);
                final int[] strip = new int[FrameUnwrap.COLUMNS * rows];
                final float[] from = new float[2];
                for (int row = 0; row < rows; row++) {
                    for (int column = 0; column < FrameUnwrap.COLUMNS; column++) {
                        FrameUnwrap.source(column, row, FrameUnwrap.COLUMNS, rows,
                                edges[0], edges[1], centre, from);
                        final int x = Math.round(from[0]);
                        final int y = Math.round(from[1]);
                        if (x >= 0 && y >= 0 && x < width && y < height) {
                            strip[row * FrameUnwrap.COLUMNS + column] = pixels[y * width + x];
                        }
                    }
                }
                return Bitmap.createBitmap(strip, FrameUnwrap.COLUMNS, rows, Bitmap.Config.ARGB_8888);
            } catch (Throwable e) {
                FileLog.e("CustomProfileFrame: could not unroll a ring: " + e.getMessage());
                return null;
            }
        }

        @Nullable
        private Bitmap picture(String src) {
            if (src == null || src.isEmpty()) {
                return null;
            }
            if (FrameBlanks.is(src)) {
                return FrameBlanks.bitmap(src);
            }
            if (!src.startsWith("http://") && !src.startsWith("https://")) {
                // A path inside another phone, which is what a frame drawn in the reference's own
                // studio carries. Nothing to load, and nothing to log every frame about either.
                return null;
            }
            final Bitmap cached = pictures.get(src);
            if (cached != null) {
                return cached.isRecycled() ? null : cached;
            }
            if (broken.contains(src)) {
                return null;
            }
            final File file = cacheFile(src);
            if (file.isFile() && file.length() > 0) {
                final Bitmap decoded = CustomProfileGfx.loadScaled(file.getAbsolutePath(), 512);
                if (decoded == null) {
                    // The bytes arrived but are not a picture. Nothing will change that, and the
                    // decode is not cheap enough to attempt on every frame drawn.
                    broken.add(src);
                    return null;
                }
                pictures.put(src, decoded);
                return decoded;
            }
            fetch(src, file);
            return null;
        }

        void clear() {
            pictures.clear();
            unwrapped.clear();
            notRings.clear();
            // Cleared with the rest: the look has changed, and a picture that was unreachable a
            // moment ago is worth one more try now.
            broken.clear();
        }

        private static File cacheFile(String url) {
            final String name = Utilities.MD5(url);
            return new File(ApplicationLoader.getFilesDirFixed(), "custom_profile_frame_"
                    + (name == null ? Integer.toHexString(url.hashCode()) : name) + ".img");
        }

        /**
         * Downloads one layer's picture. Goes through {@link WorkshopHelper}'s own transport rather
         * than a plain connection, so a frame hosted on GitHub still arrives on a network that
         * cannot reach it.
         */
        private void fetch(String url, File target) {
            if (!fetching.add(url)) {
                return;
            }
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    // No sha to check against: a layer is addressed by its URL, not by its hash.
                    final byte[] data = WorkshopHelper.download(url, "");
                    final File temp = new File(target.getParentFile(), target.getName() + ".tmp");
                    try (FileOutputStream out = new FileOutputStream(temp)) {
                        out.write(data);
                    }
                    if (!temp.renameTo(target)) {
                        temp.delete();
                        return;
                    }
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                            .postNotificationName(NotificationCenter.reloadInterface));
                } catch (Throwable e) {
                    // Given up on rather than retried: the draw path asks for this layer on every
                    // frame, so a failure that is not remembered is a download attempt per frame for
                    // as long as the profile stays open.
                    broken.add(url);
                    FileLog.e("CustomProfileFrame: layer unavailable: " + e.getMessage());
                } finally {
                    fetching.remove(url);
                }
            });
        }
    }
}
