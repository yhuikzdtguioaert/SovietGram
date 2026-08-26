package tw.nekomimi.nekogram.helpers;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaPlayer;
import android.text.TextUtils;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import org.telegram.messenger.FileLog;

import tw.nekomimi.nekogram.NekoConfig;

import java.io.File;

/**
 * Plays a look's video banner and video background on the platform's own decoders.
 *
 * <p><b>Why this exists at all.</b> Everything else the look paints goes through the profile's own
 * canvas, and an animation there is an {@link org.telegram.ui.Components.AnimatedFileDrawable},
 * which decodes with the ffmpeg built into the app. That build carries decoders for h264 and gif and
 * for nothing else — verified in {@code libavcodec.a}, where {@code ff_hevc_decoder} is simply
 * absent — while a good share of published banners are HEVC. ffmpeg answers those by producing no
 * frames at all: nothing throws and nothing logs, and the header draws empty. That is the whole of
 * "the theme installs but its banner does nothing".
 *
 * <p>So videos are played the way the reference plugin plays them: a {@link TextureView} fed by a
 * {@link MediaPlayer}, which goes through {@code MediaCodec} and therefore through whatever the
 * device itself can decode — every phone that can play an HEVC video in the gallery can play one
 * here. Animated <em>images</em> (gif, APNG, animated webp) stay on the canvas path, where the still
 * fallback covers the two of those ffmpeg also cannot read.
 *
 * <p><b>Where the views sit.</b> Copied from the reference, because the ordering is what keeps the
 * rest of the header intact:
 * <ul>
 *     <li>the background goes in at index 0, under the list, which therefore has to stop painting its
 *         own opaque colour while this is on screen — see {@code ProfileActivity};</li>
 *     <li>the banner goes in at the header view's own index, i.e. directly <em>under</em> it. The
 *         header then draws over the video, which is what keeps the profile's emoji pattern on top of
 *         a video banner rather than behind it, and the avatar, the name and the action bar are added
 *         after the header and stay above both.</li>
 * </ul>
 *
 * <p>Alpha, dim and the fade are applied here rather than by the caller, since the video is a real
 * view and not something drawn into the caller's canvas — but they are the same three effects, in the
 * same order, computed by the same {@link CustomProfileGfx} calls, so a look's banner reads
 * identically whether it ended up a picture or a video.
 */
public final class CustomProfileVideoLayer {

    private static VideoView banner;
    private static VideoView background;

    /** Whether the caller's canvas should leave the slot alone because a video view is covering it. */
    private static boolean bannerAttached;
    private static boolean backgroundAttached;

    private CustomProfileVideoLayer() {
    }

    /**
     * Whether the banner slot is being painted by a video view this frame, so the header's own draw
     * has nothing to do for it.
     */
    /**
     * Whether the banner is being played by a real view <em>inside this screen</em>.
     *
     * <p>There is one of each view for the whole app, moved to whichever profile is in front. The
     * answer therefore has to name the screen asking: a screen that no longer holds the view must
     * paint the poster itself, and a screen that thinks the view is still its own paints nothing and
     * shows black. That is what happened when two profile screens — the settings tab and a profile —
     * traded the view back and forth as the tabs were switched.
     */
    public static boolean backgroundHandled(@Nullable View root) {
        return backgroundAttached && background != null && background.getParent() == root;
    }

    /** Whether the view being drawn has the banner's own player somewhere above it. */
    public static boolean bannerPlaying(@Nullable View drawing) {
        return bannerAttached && banner != null && shares(drawing, banner);
    }

    public static boolean backgroundPlaying(@Nullable View drawing) {
        return backgroundAttached && background != null && shares(drawing, background);
    }

    /** Whether both views are in the same screen — the player's parent is above the drawing one. */
    private static boolean shares(@Nullable View drawing, VideoView player) {
        if (!(player.getParent() instanceof View container)) {
            return false;
        }
        View walk = drawing;
        for (int i = 0; walk != null && i < 12; i++) {
            if (walk == container) {
                return true;
            }
            walk = walk.getParent() instanceof View next ? next : null;
        }
        return false;
    }

    /**
     * Puts the layer in step with the look on screen: attaches, moves, rebinds or drops each of the
     * two views as the look, the header's height and the profile's own lifecycle require.
     *
     * <p>Cheap enough to call from a layout pass — the common answer is "nothing to do", and every
     * step past that is guarded by whether the value it would write has actually changed.
     *
     * @param root         the profile's root frame, or null to drop everything (a closing screen).
     * @param header       the view painting the header, which the banner is inserted directly under.
     * @param headerHeight how tall that header currently is; the banner view matches it.
     */
    public static void sync(@Nullable FrameLayout root, @Nullable View header, int headerHeight) {
        if (root == null || !CustomProfileHelper.isEnabled()) {
            release();
            return;
        }
        syncBanner(root, header, headerHeight);
        syncBackground(root);
    }

    private static void syncBanner(FrameLayout root, @Nullable View header, int headerHeight) {
        final String path = CustomProfileHelper.videoPath(true);
        if (path == null || headerHeight <= 0) {
            bannerAttached = false;
            detach(banner);
            return;
        }
        if (banner == null) {
            banner = new VideoView(root.getContext());
        }
        attachUnder(root, banner, header,
                new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, headerHeight));
        final FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) banner.getLayoutParams();
        if (params.height != headerHeight) {
            params.height = headerHeight;
            banner.setLayoutParams(params);
        }
        banner.bind(path, CustomProfileHelper.banner(),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerColor),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerAlpha),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerDim),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerFade),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerFadeAngle),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerFadeRadius),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerFadeCenterX),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBannerFadeCenterY));
        bannerAttached = true;
    }

    private static void syncBackground(FrameLayout root) {
        final String path = CustomProfileHelper.videoPath(false);
        if (path == null) {
            backgroundAttached = false;
            detach(background);
            return;
        }
        if (background == null) {
            background = new VideoView(root.getContext());
        }
        attachAtBottom(root, background, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        background.bind(path, CustomProfileHelper.background(),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundColor),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundAlpha),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundDim),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundFade),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundFadeAngle),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundFadeRadius),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundFadeCenterX),
                CustomProfileHelper.cfgInt(NekoConfig.customProfileBackgroundFadeCenterY));
        backgroundAttached = true;
    }

    /**
     * Puts the view immediately below {@code header} and, crucially, recognises when it is already
     * there.
     *
     * <p>The position has to be expressed as a <em>relationship</em> and not as an index, because
     * inserting at the header's index moves the header: ask again next pass and the header now sits
     * one further along, so an index comparison always disagrees. That is not a cosmetic mistake —
     * every layout pass removed and re-added the view, and removing a {@link TextureView} destroys
     * its surface, which stops the player and restarts it. The banner therefore restarted several
     * times a second, showing each new first frame stretched to the view before the crop matrix
     * landed: the "banner jumping, flat then not flat" this was reported as.
     */
    private static void attachUnder(FrameLayout root, VideoView view, @Nullable View header,
                                    FrameLayout.LayoutParams params) {
        final int mine = root.indexOfChild(view);
        if (mine >= 0) {
            final int headerIndex = header == null ? -1 : root.indexOfChild(header);
            final boolean placed = headerIndex < 0
                    ? mine == root.getChildCount() - 1
                    : mine == headerIndex - 1;
            if (placed) {
                return;
            }
            root.removeView(view);
        } else {
            detach(view);
        }
        // Recomputed after the removal above, which shifts everything after it down by one.
        final int headerIndex = header == null ? -1 : root.indexOfChild(header);
        root.addView(view, headerIndex < 0 ? root.getChildCount() : headerIndex, params);
    }

    /** The background sits under every other child, which is an absolute position and so stable. */
    private static void attachAtBottom(FrameLayout root, VideoView view, FrameLayout.LayoutParams params) {
        if (view.getParent() == root && root.indexOfChild(view) == 0) {
            return;
        }
        detach(view);
        root.addView(view, 0, params);
    }

    private static void detach(@Nullable VideoView view) {
        if (view == null) {
            return;
        }
        view.stop();
        if (view.getParent() instanceof ViewGroup parent) {
            parent.removeView(view);
        }
    }

    /** Drops both players and both views. Called as the profile leaves the screen. */
    public static void release() {
        bannerAttached = false;
        backgroundAttached = false;
        detach(banner);
        detach(background);
        banner = null;
        background = null;
    }

    /**
     * One slot's video.
     *
     * <p>The texture starts fully transparent and stays that way until a frame has actually arrived,
     * with the still poster sitting behind it as the view's own background. That is what makes a
     * failure invisible in the right direction: a video the device cannot open leaves the poster on
     * screen, which is the same picture the canvas path would have drawn.
     */
    @SuppressLint("ViewConstructor")
    static final class VideoView extends FrameLayout implements TextureView.SurfaceTextureListener {

        private final TextureView texture;
        private final View dim;
        private final Matrix transform = new Matrix();
        private final Paint fadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private MediaPlayer player;
        private Surface surface;
        private String path = "";
        private boolean firstFrame;
        /** The poster or the flat colour has been put behind the texture at least once. */
        private boolean backdropApplied;
        private Bitmap poster;
        private int fallbackColor;
        private int alphaPercent = 100;
        private int dimPercent;
        private int appliedDim = -1;
        private int fadeMode;
        private int fadeAngle;
        private int fadeRadius = 100;
        private int fadeCenterX = 50;
        private int fadeCenterY = 50;
        private int videoWidth;
        private int videoHeight;

        VideoView(Context context) {
            super(context);
            setWillNotDraw(false);
            setClipChildren(true);
            fadePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));

            texture = new TextureView(context);
            texture.setOpaque(false);
            texture.setAlpha(0f);
            texture.setSurfaceTextureListener(this);
            addView(texture, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            dim = new View(context);
            addView(dim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        void bind(String newPath, @Nullable Bitmap newPoster, int newFallbackColor,
                  int alpha, int dimStrength, int mode, int angle, int radius, int centerX, int centerY) {
            alphaPercent = alpha;
            dimPercent = dimStrength;
            final boolean fadeChanged = mode != fadeMode || angle != fadeAngle || radius != fadeRadius
                    || centerX != fadeCenterX || centerY != fadeCenterY;
            fadeMode = mode;
            fadeAngle = angle;
            fadeRadius = radius;
            fadeCenterX = centerX;
            fadeCenterY = centerY;
            if (fadeChanged) {
                invalidate();
            }
            if (!backdropApplied || newPoster != poster || newFallbackColor != fallbackColor) {
                backdropApplied = true;
                poster = newPoster;
                fallbackColor = newFallbackColor;
                if (newPoster != null && !newPoster.isRecycled()) {
                    setBackground(new BitmapDrawable(getResources(), newPoster));
                } else {
                    setBackgroundColor(newFallbackColor);
                }
            }
            if (dimPercent != appliedDim) {
                appliedDim = dimPercent;
                // A fresh ColorDrawable on every layout pass is a fresh invalidate on every layout
                // pass, for a value that almost never changes.
                dim.setBackgroundColor(CustomProfileGfx.dimColor(dimPercent));
            }
            if (firstFrame) {
                texture.setAlpha(CustomProfileGfx.clamp(alphaPercent, 0, 100) / 100f);
            }
            if (!newPath.equals(path)) {
                path = newPath;
                firstFrame = false;
                backdropApplied = false;
                texture.setAlpha(0f);
                stopPlayer();
                open();
            }
        }

        /** Frees the player but keeps the view usable — a later {@link #bind} opens it again. */
        void stop() {
            stopPlayer();
            path = "";
            firstFrame = false;
            backdropApplied = false;
            texture.setAlpha(0f);
        }

        private void stopPlayer() {
            final MediaPlayer current = player;
            player = null;
            if (current == null) {
                return;
            }
            try {
                current.setSurface(null);
                current.stop();
            } catch (Throwable ignore) {
                // Stopping a player that never reached a started state throws; nothing to do about it.
            }
            try {
                current.release();
            } catch (Throwable ignore) {
            }
        }

        private void open() {
            if (surface == null || TextUtils.isEmpty(path)) {
                return;
            }
            final File file = new File(path);
            if (!file.exists() || file.length() == 0) {
                return;
            }
            try {
                final MediaPlayer created = new MediaPlayer();
                player = created;
                created.setDataSource(path);
                created.setSurface(surface);
                created.setLooping(true);
                // Muted, always: a profile that starts making noise when it is opened is not something
                // a look gets to decide. The reference carries per-look volume keys; we drop them.
                created.setVolume(0f, 0f);
                created.setOnVideoSizeChangedListener((mp, width, height) -> {
                    videoWidth = width;
                    videoHeight = height;
                    applyTransform();
                });
                created.setOnPreparedListener(mp -> {
                    if (player != created) {
                        return;
                    }
                    videoWidth = mp.getVideoWidth();
                    videoHeight = mp.getVideoHeight();
                    applyTransform();
                    try {
                        mp.start();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                });
                created.setOnErrorListener((mp, what, extra) -> {
                    // Nothing to report to the user: the poster is already on screen underneath, so
                    // the look still reads as the author meant it, minus the movement.
                    FileLog.e("CustomProfileVideoLayer: playback failed (" + what + "/" + extra + ")");
                    if (player == created) {
                        stopPlayer();
                    }
                    return true;
                });
                created.prepareAsync();
            } catch (Throwable e) {
                FileLog.e(e);
                stopPlayer();
            }
        }

        /**
         * Centre-crops the video into the view, which is what the canvas path does with a still and
         * therefore what a look's author laid the picture out for.
         */
        private void applyTransform() {
            final int width = getWidth();
            final int height = getHeight();
            if (width <= 0 || height <= 0 || videoWidth <= 0 || videoHeight <= 0) {
                return;
            }
            final float scale = Math.max((float) width / videoWidth, (float) height / videoHeight);
            final float scaledWidth = videoWidth * scale;
            final float scaledHeight = videoHeight * scale;
            transform.reset();
            // The TextureView has already stretched the video to its own bounds, so the matrix is the
            // correction from that stretch to a centre crop, not the crop itself.
            transform.setScale(scaledWidth / width, scaledHeight / height);
            transform.postTranslate((width - scaledWidth) / 2f, (height - scaledHeight) / 2f);
            texture.setTransform(transform);
            texture.invalidate();
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            applyTransform();
        }

        @Override
        public void draw(Canvas canvas) {
            final Shader fade = CustomProfileGfx.fadeShader(fadeMode, fadeAngle, fadeRadius,
                    fadeCenterX, fadeCenterY, getWidth(), getHeight());
            if (fade == null) {
                super.draw(canvas);
                return;
            }
            // Into a layer, for the same reason the canvas path uses one: DST_IN masks whatever is
            // already on the canvas, which without a layer is the whole profile behind this view.
            final int save = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
            super.draw(canvas);
            fadePaint.setShader(fade);
            canvas.drawRect(0, 0, getWidth(), getHeight(), fadePaint);
            fadePaint.setShader(null);
            canvas.restoreToCount(save);
        }

        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
            surface = new Surface(surfaceTexture);
            open();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
            applyTransform();
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
            stopPlayer();
            if (surface != null) {
                surface.release();
                surface = null;
            }
            firstFrame = false;
            texture.setAlpha(0f);
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
            if (firstFrame) {
                return;
            }
            firstFrame = true;
            texture.setAlpha(CustomProfileGfx.clamp(alphaPercent, 0, 100) / 100f);
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            stopPlayer();
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            // A backdrop, not a control: every touch belongs to the profile underneath it.
            return false;
        }

        @Override
        public boolean hasOverlappingRendering() {
            return false;
        }
    }

}
