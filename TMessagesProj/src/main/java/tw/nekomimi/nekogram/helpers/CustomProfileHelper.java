package tw.nekomimi.nekogram.helpers;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.net.Uri;
import android.text.TextUtils;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedFileDrawable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

import tw.nekomimi.nekogram.NekoConfig;
import tw.nekomimi.nekogram.config.ConfigItem;

/**
 * State around the Custom Profile settings: the picked pictures, the decoded bitmaps behind them and
 * the clipboard export. The drawing itself lives in {@link CustomProfileGfx} and
 * {@link CustomProfileNameFx}; this class is what the settings screen and {@code ProfileActivity}
 * talk to.
 * <p>
 * Bitmaps are decoded once and held until a setting changes, because the profile header redraws on
 * every scroll frame and decoding there would drop frames on any picture worth using.
 * <p>
 * <b>Whose look is being drawn.</b> A profile header paints either the local user's own settings or
 * another SovietGram user's, pulled from the backend by {@link SovietGramProfileSync}. Only one
 * profile screen draws at a time, so the open screen announces whose it is through
 * {@link #setDrawingLook} and every value read here goes through {@link #cfgInt} /
 * {@link #cfgBool}, which answer from the peer's blob when there is one and from
 * {@link NekoConfig} otherwise. Nothing outside those two accessors may read a
 * {@code customProfile*} item on the draw path, or a peer's profile would be painted with the local
 * user's settings.
 */
public final class CustomProfileHelper {

    private static final String BANNER_FILE = "custom_profile_banner";
    private static final String BACKGROUND_FILE = "custom_profile_background";
    private static final String FONT_FILE = "custom_profile_font";
    /** The bubble keeps its own file: it can carry a different typeface from the name. */
    private static final String THOUGHT_FONT_FILE = "custom_profile_thought_font";
    private static final String EXPORT_HEADER = "SovietGram Custom Profile v1";
    private static final int MAX_SIDE = 1280;

    private static final PorterDuffXfermode DST_IN = new PorterDuffXfermode(PorterDuff.Mode.DST_IN);
    private static final PorterDuffXfermode SRC_ATOP = new PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP);

    /** Set while the user's own profile is the screen being drawn. See {@link #setDrawingLook}. */
    private static boolean drawingMyProfile;

    /** The peer whose profile is the screen being drawn, or 0 when it is the own one. */
    private static long drawingPeerId;

    /** That peer's synced look, or null when the own settings are the source. */
    @Nullable
    private static JSONObject remoteLook;

    private static final Paint overlayPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** Everything the clipboard export round-trips, in a fixed order the import relies on. */
    private static final ConfigItem[] EXPORTED = {
            NekoConfig.customProfileEnabled,
            NekoConfig.customProfileBannerType,
            NekoConfig.customProfileBannerColor,
            NekoConfig.customProfileBannerMedia,
            NekoConfig.customProfileBannerAlpha,
            NekoConfig.customProfileBannerDim,
            NekoConfig.customProfileBannerFade,
            NekoConfig.customProfileBannerFadeAngle,
            NekoConfig.customProfileBannerFadeRadius,
            NekoConfig.customProfileBannerFadeCenterX,
            NekoConfig.customProfileBannerFadeCenterY,
            NekoConfig.customProfileShowEmoji,
            NekoConfig.customProfileGradientRadial,
            NekoConfig.customProfileGradientCount,
            NekoConfig.customProfileGradientColor1,
            NekoConfig.customProfileGradientColor2,
            NekoConfig.customProfileGradientColor3,
            NekoConfig.customProfileGradientAngle,
            NekoConfig.customProfileGradientRadius,
            NekoConfig.customProfileGradientCenterX,
            NekoConfig.customProfileGradientCenterY,
            NekoConfig.customProfileBackgroundType,
            NekoConfig.customProfileBackgroundColor,
            NekoConfig.customProfileBackgroundMedia,
            NekoConfig.customProfileBackgroundAlpha,
            NekoConfig.customProfileBackgroundDim,
            NekoConfig.customProfileBackgroundFade,
            NekoConfig.customProfileBackgroundFadeAngle,
            NekoConfig.customProfileBackgroundFadeRadius,
            NekoConfig.customProfileBackgroundFadeCenterX,
            NekoConfig.customProfileBackgroundFadeCenterY,
            NekoConfig.customProfileBlocksEnabled,
            NekoConfig.customProfileBlocksColor,
            NekoConfig.customProfileBlocksAlpha,
            NekoConfig.customProfileBlocksBlur,
            NekoConfig.customProfileAvatarShape,
            NekoConfig.customProfileAvatarPoints,
            NekoConfig.customProfileAvatarRadius,
            NekoConfig.customProfileAvatarSmoothing,
            NekoConfig.customProfileAvatarAlpha,
            NekoConfig.customProfileAvatarDim,
            NekoConfig.customProfileAvatarFade,
            NekoConfig.customProfileAvatarFadeRadius,
            NekoConfig.customProfileStoryRing,
            NekoConfig.customProfileNameColorEnabled,
            NekoConfig.customProfileNameColor,
            NekoConfig.customProfileTextColorEnabled,
            NekoConfig.customProfileTextColor,
            NekoConfig.customProfileNameGlow,
            NekoConfig.customProfileNameGlowColor,
            NekoConfig.customProfileNameGlowRadius,
            NekoConfig.customProfileNameGlowStrength,
            NekoConfig.customProfileNameFx,
            NekoConfig.customProfileNameFxSpeed,
            NekoConfig.customProfileNameFxAngle,
            NekoConfig.customProfileNameFxColor1,
            NekoConfig.customProfileNameFxColor2,
            NekoConfig.customProfileNameFont,
            NekoConfig.customProfileNameFontMedia,
            NekoConfig.customProfileNameSize,
            NekoConfig.customProfileFrameSpec,
            NekoConfig.customProfilePalette,
            NekoConfig.customProfileExtraBlocks,
            NekoConfig.customProfileHeaderLayout,
            NekoConfig.customProfileHeaderConfig,
            NekoConfig.customProfileBlockOrder,
            NekoConfig.customProfileHiddenSections,
            NekoConfig.customProfileThoughtText,
            NekoConfig.customProfileThoughtTextColor,
            NekoConfig.customProfileThoughtBackground,
            NekoConfig.customProfileThoughtFont,
            NekoConfig.customProfileThoughtFontCopy,
            NekoConfig.customProfileThoughtFontMedia,
            // The frame's authoring graph and the studio's own settings. A viewer draws none of
            // them — they draw the compiled spec above — but they are part of what the user set, so
            // they travel with the rest and come back on a new device with the look intact.
            NekoConfig.customProfileFrameGraph,
            NekoConfig.customProfileFrameCanvasSkin,
            NekoConfig.customProfileFrameCanvasTheme,
            NekoConfig.customProfileFrameCanvasCustom,
            NekoConfig.customProfileFrameWireLine,
            NekoConfig.customProfileFrameWireDodge,
    };

    /**
     * What one look may weigh, a little under the server's own ceiling so the rest of the push has
     * room. A look this large is not a normal one — the graph alone can reach 64KB and everything
     * else together rarely passes 40KB — so this is a backstop, not a working limit.
     */
    private static final int MAX_LOOK_BYTES = 180 * 1024;

    /**
     * What is given up, in order, when a look will not fit. Only things a viewer never draws are on
     * this list, and the graph is first because it is both the largest and the one that can be built
     * again from the spec that stays behind.
     */
    private static final ConfigItem[] DROPPABLE = {
            NekoConfig.customProfileFrameGraph,
            NekoConfig.customProfileFrameCanvasCustom,
    };

    /**
     * Everything the look consists of, for {@link SovietGramAccountScope} to swap when the user
     * changes account: the exported styling plus the picked-picture paths and the bundled font, which
     * the export leaves out (they are local files with nothing to publish) but which are just as much
     * part of one account's look as the colours are.
     */
    static ConfigItem[] scopedItems() {
        final ConfigItem[] local = {
                NekoConfig.customProfileBannerPath,
                NekoConfig.customProfileBackgroundPath,
                NekoConfig.customProfileNameFontPath,
                NekoConfig.customProfileThoughtFontPath,
        };
        final ConfigItem[] all = new ConfigItem[EXPORTED.length + local.length];
        System.arraycopy(EXPORTED, 0, all, 0, EXPORTED.length);
        System.arraycopy(local, 0, all, EXPORTED.length, local.length);
        return all;
    }

    private static Bitmap bannerBitmap;
    private static Bitmap backgroundBitmap;
    /** The outline last parsed, and the string it came from. See {@link #avatarPoints()}. */
    private static String avatarPointsFrom = "";
    private static float[] avatarPointsParsed = new float[0];
    private static String bannerLoadedFrom;
    private static String backgroundLoadedFrom;
    private static AnimatedFileDrawable videoDrawable;
    private static String videoLoadedFrom;
    private static AnimatedFileDrawable backgroundVideoDrawable;
    private static String backgroundVideoLoadedFrom;

    /**
     * The peer's banner and background as local files, worked out once when the look is announced
     * rather than on every frame — resolving them involves the filesystem and, the first time, a
     * download. Null means the look declares no picture for that slot, or its bytes have not landed
     * yet; either way the slot degrades to the flat colour until {@link #onRemoteMediaReady} says
     * otherwise. Only ever non-null while {@link #remoteLook} is.
     */
    @Nullable
    private static String remoteBannerPath;
    @Nullable
    private static String remoteBackgroundPath;
    /** The peer's own font file, fetched like their pictures. Null until it lands, or if none. */
    @Nullable
    private static String remoteFontPath;
    /** The peer's bubble typeface, when the bubble has one of its own. */
    @Nullable
    private static String remoteThoughtFontPath;

    private CustomProfileHelper() {
    }

    // ---------------------------------------------------------------- the look being drawn

    /**
     * One setting's value in the look currently being drawn. For a peer that is whatever their blob
     * carries under the config key; a key their build never sent falls back to the value the item was
     * <em>declared</em> with, deliberately not to the local user's — otherwise every setting the peer
     * left out would be filled in from whoever is looking at them.
     */
    static int cfgInt(ConfigItem item) {
        final JSONObject look = remoteLook;
        if (look == null) {
            return item.Int();
        }
        return look.optInt(item.getKey(), item.defaultValue instanceof Integer value ? value : 0);
    }

    /** {@link #cfgInt} for a boolean setting. */
    static boolean cfgBool(ConfigItem item) {
        final JSONObject look = remoteLook;
        if (look == null) {
            return item.Bool();
        }
        return look.optBoolean(item.getKey(), item.defaultValue instanceof Boolean value && value);
    }

    /**
     * {@link #cfgInt} for a string setting.
     * <p>
     * In practice these are file paths, and a peer's blob carries none of them — a path on somebody
     * else's phone means nothing here. So this answers empty for a peer, and whatever reads it falls
     * back the same way it would for a local look with nothing picked.
     */
    static String cfgString(ConfigItem item) {
        final JSONObject look = remoteLook;
        if (look == null) {
            return item.String();
        }
        return look.optString(item.getKey(), item.defaultValue instanceof String value ? value : "");
    }

    /** Whether the look on screen is the local user's own rather than a peer's. */
    public static boolean drawingOwnLook() {
        return remoteLook == null;
    }

    /** Whether a custom look — anybody's — is what the open screen is painting. */
    private static boolean drawingLook() {
        return drawingMyProfile || remoteLook != null;
    }

    public static boolean isEnabled() {
        return drawingLook() && cfgBool(NekoConfig.customProfileEnabled);
    }

    /**
     * The banner type actually drawable for the look on screen.
     * <p>
     * A picked picture is a local file, so a peer's look cannot name one. What it can name is where the
     * picture was downloaded from, which is the case for every look installed from the workshop — see
     * {@link CustomProfileMedia}. So a peer's picture or video banner is kept whenever their copy of
     * the asset is already on disk, and degrades to the flat colour their look also carries while it is
     * not: either because they picked the picture out of their own gallery (nothing to fetch) or
     * because the fetch has not landed yet (one repaint away). Without this the local user's own
     * picture would be painted onto someone else's header.
     */
    private static int bannerType() {
        return pictureType(cfgInt(NekoConfig.customProfileBannerType),
                remoteLook != null && remoteBannerPath == null,
                picturePath(NekoConfig.customProfileBannerPath, true));
    }

    /** {@link #bannerType} for the list background, which supports the same picture and video types. */
    private static int backgroundType() {
        return pictureType(cfgInt(NekoConfig.customProfileBackgroundType),
                remoteLook != null && remoteBackgroundPath == null,
                picturePath(NekoConfig.customProfileBackgroundPath, false));
    }

    /**
     * Settles a picture slot's type against the file it will actually be drawn from.
     * <p>
     * The stored 3 (picture) or 4 (animation) is a claim made when the look was installed, and it can
     * be wrong in either direction — a workshop entry's declared mime cannot tell a still webp from an
     * animated one, and a legacy entry declares no mime at all. Wrong either way is invisible rather
     * than obviously broken: a still opened by the player yields no frames and the header draws empty,
     * an animation opened by the bitmap decoder freezes on frame one. So the file decides, which is
     * what the reference does — it stores no type for this at all and sniffs every time.
     *
     * @param unfetched a peer's look whose picture has not landed yet, which degrades to their colour.
     */
    private static int pictureType(int stored, boolean unfetched, @Nullable String path) {
        if (stored != 3 && stored != 4) {
            return stored;
        }
        if (unfetched) {
            return 1;
        }
        if (TextUtils.isEmpty(path)) {
            return stored;
        }
        return CustomProfileFormat.moving(path) ? 4 : 3;
    }

    /** True when the header has anything of its own to paint, so callers can skip the whole path. */
    public static boolean hasBanner() {
        return isEnabled() && bannerType() != 0;
    }

    public static boolean hasBackground() {
        return isEnabled() && backgroundType() != 0;
    }

    /** Whether the profile emoji pattern stays on top of the banner. Read for the look on screen. */
    public static boolean showEmoji() {
        return cfgBool(NekoConfig.customProfileShowEmoji);
    }

    /** Whether the name carries a glow, which needs the text view on a software layer to show up. */
    public static boolean nameGlowEnabled() {
        return cfgBool(NekoConfig.customProfileNameGlow);
    }

    /**
     * Paints the banner, complete with opacity, dim and fade, into a box of the given size.
     *
     * @param parent the view being drawn into; an animated banner needs it to schedule its own frames.
     */
    public static void drawBanner(Canvas canvas, float width, float height, @Nullable View parent) {
        if (!hasBanner() || width <= 0 || height <= 0) {
            return;
        }
        if (CustomProfileVideoLayer.bannerPlaying(parent)) {
            // A video view is sitting over exactly this rect with the same alpha, dim and fade
            // already applied to it. Painting the poster underneath would only show through
            // wherever the look asked for transparency.
            return;
        }
        final int type = bannerType();
        final AnimatedFileDrawable animation = type == 4 ? video(parent) : null;
        // Having a player is not the same as having a picture. It reports a failure to open the file
        // by simply never producing a frame — nothing throws, nothing logs — so drawing it because it
        // exists is how a header ends up blank. This build's ffmpeg carries no HEVC decoder and no
        // animated-webp or APNG one, and a good share of published banners are exactly those, so the
        // case is the common one rather than a corner. When the player says it failed, the still
        // decoder draws instead: for a video that is its first frame, through the platform's own
        // decoders, which do know those formats.
        final boolean playing = animation != null && animation.hasBitmap();
        final Bitmap picture = stillWanted(type, playing, animation) ? banner() : null;
        CustomProfileGfx.drawFaded(canvas, width, height,
                cfgInt(NekoConfig.customProfileBannerFade),
                cfgInt(NekoConfig.customProfileBannerFadeAngle),
                cfgInt(NekoConfig.customProfileBannerFadeRadius),
                cfgInt(NekoConfig.customProfileBannerFadeCenterX),
                cfgInt(NekoConfig.customProfileBannerFadeCenterY),
                cfgInt(NekoConfig.customProfileBannerAlpha),
                cfgInt(NekoConfig.customProfileBannerDim),
                () -> {
                    if (playing) {
                        animation.setBounds(0, 0, (int) width, (int) height);
                        animation.draw(canvas);
                    } else {
                        CustomProfileGfx.drawBannerContent(canvas, width, height, type, picture);
                    }
                });
    }

    public static void drawBackground(Canvas canvas, float width, float height, @Nullable View parent) {
        if (!hasBackground() || width <= 0 || height <= 0) {
            return;
        }
        if (CustomProfileVideoLayer.backgroundPlaying(parent)) {
            return;
        }
        final int type = backgroundType();
        final AnimatedFileDrawable animation = type == 4 ? backgroundVideo(parent) : null;
        final boolean playing = animation != null && animation.hasBitmap();
        final Bitmap picture = stillWanted(type, playing, animation) ? background() : null;
        CustomProfileGfx.drawFaded(canvas, width, height,
                cfgInt(NekoConfig.customProfileBackgroundFade),
                cfgInt(NekoConfig.customProfileBackgroundFadeAngle),
                cfgInt(NekoConfig.customProfileBackgroundFadeRadius),
                cfgInt(NekoConfig.customProfileBackgroundFadeCenterX),
                cfgInt(NekoConfig.customProfileBackgroundFadeCenterY),
                cfgInt(NekoConfig.customProfileBackgroundAlpha),
                cfgInt(NekoConfig.customProfileBackgroundDim),
                () -> {
                    if (playing) {
                        animation.setBounds(0, 0, (int) width, (int) height);
                        animation.draw(canvas);
                    } else {
                        CustomProfileGfx.drawBackgroundContent(canvas, width, height, type, picture);
                    }
                });
    }

    /**
     * The look's background with no player involved, drawn into whatever canvas the caller hands over.
     * <p>
     * This is what {@link CustomProfileBlocks} frosts. It differs from {@link #drawBackground} in the
     * two ways a backdrop has to: it ignores the video layer, since a frosted copy of a video is its
     * poster rather than nothing, and it takes no parent view, since nothing here schedules frames.
     */
    static void drawBackdrop(Canvas canvas, float width, float height) {
        if (!hasBackground() || width <= 0 || height <= 0) {
            return;
        }
        final int type = backgroundType();
        final Bitmap picture = type == 3 || type == 4 ? background() : null;
        CustomProfileGfx.drawFaded(canvas, width, height,
                cfgInt(NekoConfig.customProfileBackgroundFade),
                cfgInt(NekoConfig.customProfileBackgroundFadeAngle),
                cfgInt(NekoConfig.customProfileBackgroundFadeRadius),
                cfgInt(NekoConfig.customProfileBackgroundFadeCenterX),
                cfgInt(NekoConfig.customProfileBackgroundFadeCenterY),
                cfgInt(NekoConfig.customProfileBackgroundAlpha),
                cfgInt(NekoConfig.customProfileBackgroundDim),
                () -> CustomProfileGfx.drawBackgroundContent(canvas, width, height, type, picture));
    }

    /**
     * Everything {@link #drawBackdrop} would paint, as one number, so a cached copy of it can be told
     * to be stale. The decoded picture is in there by identity: a look swapped for another one whose
     * settings happen to match is still a different picture.
     */
    static long backgroundSignature() {
        long signature = backgroundType();
        signature = signature * 31 + cfgInt(NekoConfig.customProfileBackgroundColor);
        signature = signature * 31 + cfgInt(NekoConfig.customProfileBackgroundAlpha);
        signature = signature * 31 + cfgInt(NekoConfig.customProfileBackgroundDim);
        signature = signature * 31 + cfgInt(NekoConfig.customProfileBackgroundFade);
        signature = signature * 31 + cfgInt(NekoConfig.customProfileBackgroundFadeAngle);
        signature = signature * 31 + cfgInt(NekoConfig.customProfileBackgroundFadeRadius);
        signature = signature * 31 + cfgInt(NekoConfig.customProfileBackgroundFadeCenterX);
        signature = signature * 31 + cfgInt(NekoConfig.customProfileBackgroundFadeCenterY);
        signature = signature * 31 + System.identityHashCode(background());
        return signature;
    }

    /**
     * Whether the slot needs its still decoded for this frame.
     * <p>
     * Not merely "the player has no frame yet": a video that is about to play normally would then pay
     * for a {@code MediaMetadataRetriever} seek on the draw thread every time a profile opens, to
     * produce a bitmap thrown away a frame later. So the still is asked for only once the player has
     * actually reported that it could not open the file, and for the two frames before it says so the
     * header draws what it drew before this — nothing.
     */
    private static boolean stillWanted(int type, boolean playing, @Nullable AnimatedFileDrawable animation) {
        if (playing) {
            return false;
        }
        if (type == 3) {
            return true;
        }
        return type == 4 && (animation == null || animation.decoderFailed());
    }

    /**
     * The banner's picture, decoded once per file.
     * <p>
     * A decode that comes back with nothing is remembered as such — the path is recorded either way —
     * because this is called from {@code onDraw}. Retrying on the next frame instead means the whole
     * decode runs again sixty times a second for as long as the profile is open, and for a video slot
     * that decode is a {@code MediaMetadataRetriever} seek. {@link #onSettingsChanged} clears the
     * record, so a look that changes is decoded afresh.
     */
    @Nullable
    public static Bitmap banner() {
        final String path = picturePath(NekoConfig.customProfileBannerPath, true);
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        if (!path.equals(bannerLoadedFrom) || (bannerBitmap != null && bannerBitmap.isRecycled())) {
            bannerBitmap = CustomProfileGfx.loadScaled(path, MAX_SIDE);
            bannerLoadedFrom = path;
        }
        return bannerBitmap;
    }

    /** {@link #banner} for the list background. */
    @Nullable
    public static Bitmap background() {
        final String path = picturePath(NekoConfig.customProfileBackgroundPath, false);
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        if (!path.equals(backgroundLoadedFrom)
                || (backgroundBitmap != null && backgroundBitmap.isRecycled())) {
            backgroundBitmap = CustomProfileGfx.loadScaled(path, MAX_SIDE);
            backgroundLoadedFrom = path;
        }
        return backgroundBitmap;
    }

    /**
     * The file one slot's picture is in for the look being drawn: the own picked file, or the peer's
     * fetched copy of whatever their look points at. {@link #bannerType} and {@link #backgroundType}
     * already keep the picture branches off a look with nothing on disk; this is the second lock on the
     * one mistake that would be visible to somebody else — the local user's picture on their header.
     */
    @Nullable
    private static String picturePath(ConfigItem item, boolean banner) {
        if (remoteLook == null) {
            return item.String();
        }
        return banner ? remoteBannerPath : remoteBackgroundPath;
    }

    /**
     * Works out which files the peer's look paints from, fetching them if this is the first sight of
     * them. Called whenever the look changes and when a fetch lands, never from a draw.
     */
    private static void resolveRemoteMedia() {
        remoteBannerPath = null;
        remoteBackgroundPath = null;
        remoteFontPath = null;
        remoteThoughtFontPath = null;
        if (remoteLook == null) {
            return;
        }
        // Only where the file is, never what it is: the descriptor's mime cannot tell a still webp
        // from an animated one, so that half of the answer comes from the fetched file itself.
        remoteBannerPath = CustomProfileMedia.peerPath(remoteLook, true);
        remoteBackgroundPath = CustomProfileMedia.peerPath(remoteLook, false);
        remoteFontPath = CustomProfileMedia.peerFontPath(remoteLook);
        remoteThoughtFontPath = CustomProfileMedia.peerThoughtFontPath(remoteLook);
    }

    /**
     * The font file the name should be drawn with: the peer's fetched copy when their look is on
     * screen, our own picked file otherwise.
     * <p>
     * A path cannot be synced — it names a file on somebody else's phone — so the look carries a
     * descriptor for the bytes instead and this resolves it to whatever landed in our cache. Empty
     * while the fetch is in flight, which draws the name in the view's own font for a moment rather
     * than in a wrong one.
     */
    public static String fontPath() {
        if (remoteLook == null) {
            return NekoConfig.customProfileNameFontPath.String();
        }
        return remoteFontPath == null ? "" : remoteFontPath;
    }

    /**
     * The font file the thought bubble should be drawn with, resolved exactly as {@link #fontPath()}
     * resolves the name's. Only asked for when the bubble has stopped copying the name's typeface.
     */
    public static String thoughtFontPath() {
        if (remoteLook == null) {
            return NekoConfig.customProfileThoughtFontPath.String();
        }
        return remoteThoughtFontPath == null ? "" : remoteThoughtFontPath;
    }

    /**
     * A peer's picture finished downloading. Re-resolves the slots and asks the screen to repaint, so
     * the header that has been showing the look's flat colour picks the picture up.
     */
    static void onRemoteMediaReady() {
        AndroidUtilities.runOnUIThread(() -> {
            if (remoteLook == null) {
                return; // that profile is no longer on screen; the next open resolves from the cache
            }
            resolveRemoteMedia();
            bannerLoadedFrom = null;
            backgroundLoadedFrom = null;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface);
        });
    }

    /**
     * The clip for a custom avatar outline, or null when the stock rounded rect should be kept. Only
     * the profile screen whose look is being drawn asks for it, so avatars elsewhere are untouched.
     *
     * @param inset the story-ring inset the caller already applied, so the shape lines up with it.
     */
    @Nullable
    public static Path avatarShapePath(View view, float inset, int width, int height) {
        if (!isEnabled() || width <= 0 || height <= 0) {
            return null;
        }
        final int shape = cfgInt(NekoConfig.customProfileAvatarShape);
        if (shape == 0) {
            return null;
        }
        return CustomProfileGfx.shapePath(shape,
                inset, inset, width - inset, height - inset,
                cfgInt(NekoConfig.customProfileAvatarRadius),
                cfgInt(NekoConfig.customProfileAvatarSmoothing),
                shape == 8 ? avatarPoints() : null);
    }

    /**
     * The look's free-form avatar outline, parsed once per value rather than on every frame — this is
     * read from {@code onDraw} and the outlines run to a few dozen points.
     */
    private static float[] avatarPoints() {
        final String raw = cfgString(NekoConfig.customProfileAvatarPoints);
        if (!raw.equals(avatarPointsFrom)) {
            avatarPointsFrom = raw;
            avatarPointsParsed = CustomProfileGfx.parsePoints(raw);
        }
        return avatarPointsParsed;
    }

    /**
     * Opens the layer the avatar's opacity, dim and fade are applied in, and returns the save count to
     * hand back to {@link #endAvatarLayer}. Without a layer the fade's DST_IN would mask everything
     * already on the canvas rather than just the avatar. Returns -1 when nothing needs a layer.
     */
    public static int beginAvatarLayer(Canvas canvas, View view, int width, int height) {
        if (!isEnabled() || width <= 0 || height <= 0) {
            return -1;
        }
        final int alpha = CustomProfileGfx.clamp(cfgInt(NekoConfig.customProfileAvatarAlpha), 0, 100);
        if (alpha >= 100 && cfgInt(NekoConfig.customProfileAvatarDim) <= 0
                && cfgInt(NekoConfig.customProfileAvatarFade) <= 0) {
            return -1;
        }
        return canvas.saveLayerAlpha(0, 0, width, height, (int) (alpha / 100f * 255));
    }

    /** Paints the dim and the edge fade into the layer, then closes it. */
    public static void endAvatarLayer(Canvas canvas, int saveCount, int width, int height) {
        if (saveCount < 0) {
            return;
        }
        final int dim = cfgInt(NekoConfig.customProfileAvatarDim);
        if (dim > 0) {
            overlayPaint.setShader(null);
            overlayPaint.setColor(CustomProfileGfx.dimColor(dim));
            // SRC_ATOP, so the darkening lands on the avatar and not on the corners of the box it sits
            // in. It matters for every shape that is not the square one: a dimmed heart drawn SRC_OVER
            // comes out as a dark rectangle with a heart in it.
            overlayPaint.setXfermode(SRC_ATOP);
            canvas.drawRect(0, 0, width, height, overlayPaint);
            overlayPaint.setXfermode(null);
        }
        final Shader shader = CustomProfileGfx.avatarFadeShader(cfgInt(NekoConfig.customProfileAvatarFade),
                cfgInt(NekoConfig.customProfileAvatarFadeRadius), width, height);
        if (shader != null) {
            overlayPaint.setColor(0xFF000000);
            overlayPaint.setShader(shader);
            overlayPaint.setXfermode(DST_IN);
            canvas.drawRect(0, 0, width, height, overlayPaint);
            overlayPaint.setShader(null);
            overlayPaint.setXfermode(null);
        }
        canvas.restoreToCount(saveCount);
    }

    /**
     * Dresses a name's paint for one frame: colour, animated shader, pulse alpha and glow. Always
     * paired with {@link #clearNamePaint}, because the paint belongs to the view and is reused.
     */
    public static void applyNamePaint(Paint paint, int width, int height) {
        if (paint == null) {
            return;
        }
        if (cfgBool(NekoConfig.customProfileNameColorEnabled)) {
            paint.setColor(cfgInt(NekoConfig.customProfileNameColor));
        }
        paint.setShader(CustomProfileNameFx.shaderFor(width, height));
        final float alpha = CustomProfileNameFx.alphaFor();
        if (alpha < 1f) {
            paint.setAlpha((int) (paint.getAlpha() * alpha));
        }
        CustomProfileNameFx.applyGlow(paint);
    }

    public static void clearNamePaint(Paint paint) {
        if (paint != null) {
            paint.setShader(null);
            paint.clearShadowLayer();
        }
    }

    /**
     * Remaps one theme colour for the profile whose look is being drawn, or returns {@code fallback}
     * untouched.
     * <p>
     * The original plugin reached into each view with reflection and overwrote any field whose name
     * looked like a background; going through the single colour lookup the screen already funnels
     * every paint through gets the same result without depending on field names.
     */
    public static int themedColor(int key, int fallback) {
        if (!isEnabled()) {
            return fallback;
        }
        // The look's own palette first: it names theme keys outright, so it outranks the two fixed
        // rules below, which are our shorthand for the handful of keys a look usually wants changed.
        final Integer painted = CustomProfilePalette.colorFor(key);
        if (painted != null) {
            return painted;
        }
        if (cfgBool(NekoConfig.customProfileBlocksEnabled) && isBlockKey(key)) {
            final int alpha = CustomProfileGfx.clamp(cfgInt(NekoConfig.customProfileBlocksAlpha), 0, 100);
            final int color = cfgInt(NekoConfig.customProfileBlocksColor);
            return alpha >= 100
                    ? color
                    : (color & 0x00FFFFFF) | ((int) (alpha / 100f * 255) << 24);
        }
        if (cfgBool(NekoConfig.customProfileTextColorEnabled) && isTextKey(key)) {
            return cfgInt(NekoConfig.customProfileTextColor);
        }
        return fallback;
    }

    /** The surfaces the profile's rows and section cards are painted on. */
    private static boolean isBlockKey(int key) {
        return key == Theme.key_windowBackgroundWhite
                || key == Theme.key_windowBackgroundUnchecked
                || key == Theme.key_graySection;
    }

    /**
     * Only the copy sitting on top of those surfaces. The name is deliberately absent: it has its own
     * colour, gradient and effects, and taking this branch would silently override all three.
     */
    private static boolean isTextKey(int key) {
        return key == Theme.key_windowBackgroundWhiteBlackText
                || key == Theme.key_windowBackgroundWhiteGrayText
                || key == Theme.key_windowBackgroundWhiteGrayText2
                || key == Theme.key_windowBackgroundWhiteGrayText3
                || key == Theme.key_windowBackgroundWhiteValueText
                || key == Theme.key_actionBarDefaultSubtitle
                || key == Theme.key_avatar_subtitleInProfileBlue
                || key == Theme.key_graySectionText;
    }

    /**
     * Whether the stock story ring has to stand down. It is a circle built from arcs placed by angle,
     * so on any non-circular avatar it would float around the shape rather than follow it; in that
     * case {@link #drawStoryRing} draws a ring that does follow it instead.
     */
    public static boolean overridesStoryRing() {
        return isEnabled()
                && cfgBool(NekoConfig.customProfileStoryRing)
                && cfgInt(NekoConfig.customProfileAvatarShape) != 0;
    }

    /** The replacement ring, traced around whatever shape the avatar is currently clipped to. */
    public static void drawStoryRing(Canvas canvas, View view, int width, int height, boolean unread) {
        if (!overridesStoryRing() || width <= 0 || height <= 0) {
            return;
        }
        final float inset = AndroidUtilities.dpf2(2.5f);
        final Path path = CustomProfileGfx.shapePath(cfgInt(NekoConfig.customProfileAvatarShape),
                inset, inset, width - inset, height - inset,
                cfgInt(NekoConfig.customProfileAvatarRadius),
                cfgInt(NekoConfig.customProfileAvatarSmoothing));
        CustomProfileGfx.drawStoryRing(canvas, path, unread, width, height);
    }

    /**
     * Called by the profile screen as it becomes the one on screen, to say whose look to paint: the
     * own settings, or {@code peerId}'s as the sync layer last saw them. The settings have no per-peer
     * notion of their own, so without this a custom shape would follow onto everybody's profile.
     */
    public static void setDrawingLook(boolean myProfile, long peerId) {
        drawingMyProfile = myProfile;
        drawingPeerId = myProfile ? 0 : peerId;
        remoteLook = drawingPeerId == 0 ? null : SovietGramProfileSync.remoteCustomProfile(drawingPeerId);
        resolveRemoteMedia();
    }

    /**
     * Re-reads the peer's look, since the pull that carries it usually lands after the screen opened.
     *
     * @return whether the look changed, so the caller can restyle rather than redraw the same thing.
     */
    public static boolean refreshDrawingLook() {
        if (drawingPeerId == 0) {
            return false;
        }
        final JSONObject current = SovietGramProfileSync.remoteCustomProfile(drawingPeerId);
        if (current == remoteLook) {
            return false;
        }
        remoteLook = current;
        resolveRemoteMedia();
        return true;
    }

    /** Called as the screen stops being the one on screen; nothing may keep painting its look after. */
    public static void clearDrawingLook() {
        drawingMyProfile = false;
        drawingPeerId = 0;
        remoteLook = null;
        resolveRemoteMedia();
        // The frosted copy is of a look that is no longer on screen, and it is a full-screen bitmap.
        CustomProfileBlocks.invalidate();
        CustomProfileFrame.invalidate();
        CustomProfileThought.invalidate();
        CustomProfilePalette.invalidate();
        CustomProfileRows.invalidate();
        CustomProfileHeaderLayout.invalidate();
        CustomProfileExtraRows.invalidate();
    }

    /**
     * Drops the decoded bitmaps and tells the open profile screens to repaint. Called from the
     * settings screen on every change, so it has to stay cheap when nothing actually moved.
     */
    public static void onSettingsChanged() {
        bannerLoadedFrom = null;
        backgroundLoadedFrom = null;
        bannerBitmap = null;
        backgroundBitmap = null;
        CustomProfileBlocks.invalidate();
        CustomProfileFrame.invalidate();
        CustomProfileThought.invalidate();
        CustomProfilePalette.invalidate();
        CustomProfileRows.invalidate();
        CustomProfileHeaderLayout.invalidate();
        CustomProfileExtraRows.invalidate();
        AndroidUtilities.runOnUIThread(() ->
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.reloadInterface));
        // Every custom-profile edit funnels through here (the toggle, the look editor, workshop
        // presets, clipboard import, reset), so this is the one place that has to tell the sync
        // backend the look changed. Debounced + deduplicated, so a picture-only change (whose path
        // is not synced) or a rapid slider drag costs at most one no-op comparison.
        SovietGramSync.scheduleProfilePush();
    }

    /**
     * The custom-profile settings as a flat JSON object keyed by config key — the shape the sync
     * backend stores under {@code custom_profile} and hands back to other clients. Only the styling
     * settings travel; the picked pictures ({@link NekoConfig#customProfileBannerPath} and the
     * background path) are local files with no server storage, so they are deliberately excluded,
     * exactly the set {@link #exportToClipboard()} round-trips. Values keep their native JSON type
     * (boolean / number / string) so the import side can read them straight back.
     */
    public static JSONObject exportProfileJson() {
        final JSONObject json = new JSONObject();
        for (ConfigItem item : EXPORTED) {
            try {
                switch (item.type) {
                    case ConfigItem.configTypeBool -> json.put(item.getKey(), item.Bool());
                    case ConfigItem.configTypeInt -> json.put(item.getKey(), item.Int());
                    case ConfigItem.configTypeLong -> json.put(item.getKey(), (long) item.Long());
                    case ConfigItem.configTypeFloat -> json.put(item.getKey(), (double) item.Float());
                    default -> json.put(item.getKey(), item.String());
                }
            } catch (JSONException e) {
                FileLog.e(e);
            }
        }
        return withinBudget(json);
    }

    /**
     * Trims a look until the server will take it.
     *
     * <p>Only the parts nobody draws are given up, so what a viewer sees is never quietly changed:
     * a look that is too big loses its editing state rather than its banner. If it still does not
     * fit after that, it is left alone and refused — silently dropping something that is drawn would
     * be worse than the look not syncing at all, because it would look like it had.
     */
    private static JSONObject withinBudget(JSONObject json) {
        if (size(json) <= MAX_LOOK_BYTES) {
            return json;
        }
        for (ConfigItem item : DROPPABLE) {
            json.remove(item.getKey());
            if (size(json) <= MAX_LOOK_BYTES) {
                FileLog.e("CustomProfile: look trimmed to fit, dropped " + item.getKey());
                return json;
            }
        }
        FileLog.e("CustomProfile: look is " + size(json) + " bytes and will not fit; "
                + "the server will refuse it");
        return json;
    }

    private static int size(JSONObject json) {
        return json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    }

    /**
     * The same blob, but for whichever account is pushing it. Only one account's look is live in the
     * config items at a time ({@link SovietGramAccountScope}); the others are read out of their stored
     * snapshots, so a push that covers several logged-in accounts publishes each one's own look
     * instead of the current account's to all of them.
     */
    public static JSONObject exportProfileJson(int account) {
        return SovietGramAccountScope.isLive(account)
                ? exportProfileJson()
                : withinBudget(SovietGramAccountScope.export(account, EXPORTED));
    }

    /**
     * Copies the picked picture into our own storage — the picker hands out a {@code content://} URI
     * whose permission does not survive a restart.
     *
     * @return the stored path, or {@code null} if the copy failed.
     */
    @Nullable
    public static String importMedia(Uri uri, boolean banner) {
        if (uri == null) {
            return null;
        }
        try (InputStream in = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            final String path = importStream(in, banner);
            if (path != null) {
                // The previous look's asset is no longer what this slot holds, so peers must stop
                // being sent after it immediately — and a gallery picture exists nowhere but this
                // phone until we host it ourselves, which is what the upload is for. It runs in the
                // background and records the new descriptor only if this is still the current pick.
                CustomProfileMedia.forget(banner);
                CustomProfileMedia.publishAsync(banner, path);
            }
            return path;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** Same destination as {@link #importMedia}, for a picture that arrived as bytes. */
    public static String importBytes(byte[] data, boolean banner) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (InputStream in = new ByteArrayInputStream(data)) {
            return importStream(in, banner);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    /** The largest font file worth keeping; the reference refuses the same size. */
    private static final int MAX_FONT_BYTES = 5 * 1024 * 1024;

    /**
     * Stores the font file a look brought with it and returns its path, or null when there is nothing
     * usable. One file per account, same as the pictures.
     *
     * <p>Only checked for size, not parsed: whether the platform can actually make a typeface out of
     * it is {@link CustomProfileNameFx}'s problem, and it already falls back to the view's own font.
     */
    @Nullable
    public static String importFont(@Nullable byte[] data) {
        return importFont(data, true);
    }

    /**
     * Reads a font the user picked out of the gallery. Bounded by the same size the byte form is:
     * this ends up being uploaded so that other users see the look as designed.
     */
    /**
     * The bytes behind a picked {@code content://}, bounded so a video chosen by mistake cannot be
     * pulled into memory whole. Null when nothing usable came back.
     */
    @Nullable
    public static byte[] readUri(@Nullable Uri uri) {
        if (uri == null) {
            return null;
        }
        try (InputStream in = ApplicationLoader.applicationContext
                .getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
                if (out.size() > SovietGramApiClient.MAX_IMAGE_BYTES) {
                    return null;
                }
            }
            return out.toByteArray();
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    @Nullable
    public static String importFont(@Nullable Uri uri, boolean forName) {
        if (uri == null) {
            return null;
        }
        try (InputStream in = ApplicationLoader.applicationContext
                .getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > MAX_FONT_BYTES) {
                    return null;
                }
            }
            return importFont(bytes.toByteArray(), forName);
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        }
    }

    @Nullable
    public static String importFont(@Nullable byte[] data, boolean forName) {
        if (data == null || data.length == 0 || data.length > MAX_FONT_BYTES) {
            return null;
        }
        final File target = new File(ApplicationLoader.getFilesDirFixed(),
                (forName ? FONT_FILE : THOUGHT_FONT_FILE) + SovietGramAccountScope.fileSuffix());
        final File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(data);
        } catch (Exception e) {
            FileLog.e(e);
            temp.delete();
            return null;
        }
        target.delete();
        if (!temp.renameTo(target)) {
            temp.delete();
            return null;
        }
        return target.getAbsolutePath();
    }

    /**
     * Writes to the one file per slot <em>per account</em>, through a temp file so a half-finished
     * copy never replaces a working picture. The account suffix matters: the stored path is part of
     * the account's own look, so without it two accounts would both point at the same file and the
     * second pick would silently replace the first one's picture.
     * Returns the path, or null when nothing usable arrived.
     */
    private static String importStream(InputStream source, boolean banner) {
        final String name = (banner ? BANNER_FILE : BACKGROUND_FILE) + SovietGramAccountScope.fileSuffix();
        final File target = new File(ApplicationLoader.getFilesDirFixed(), name);
        final File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (InputStream in = source;
             FileOutputStream out = new FileOutputStream(temp)) {
            final byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        } catch (Exception e) {
            FileLog.e(e);
            temp.delete();
            return null;
        }
        if (temp.length() == 0) {
            temp.delete();
            return null;
        }
        target.delete();
        if (!temp.renameTo(target)) {
            temp.delete();
            return null;
        }
        // Same file name every time, so an already-decoded bitmap has to be dropped explicitly — and a
        // player still holding the previous file has to let go of it, or it keeps playing what was
        // just replaced.
        release(banner);
        onSettingsChanged();
        return target.getAbsolutePath();
    }
    /**
     * The animated banner. Playing it through {@link AnimatedFileDrawable} rather than a
     * {@code TextureView} keeps it inside the header's own draw pass, so it clips, fades and dims
     * exactly like a still picture does and there is no second view to keep in sync while scrolling.
     */
    @Nullable
    public static AnimatedFileDrawable video(View parent) {
        if (!isEnabled() || bannerType() != 4) {
            return null;
        }
        final String path = picturePath(NekoConfig.customProfileBannerPath, true);
        // Videos belong to CustomProfileVideoLayer, which plays them on the platform's decoders.
        // What is left for this player is the animated images: gif, which the bundled ffmpeg does
        // decode, and APNG and animated webp, which it does not and which therefore fall through to
        // their first frame — still the picture the look asked for, minus the movement.
        if (CustomProfileFormat.video(path)) {
            return null;
        }
        return animation(true, path, parent);
    }

    /** {@link #video} for the list background, whose animation is a second, independent player. */
    @Nullable
    public static AnimatedFileDrawable backgroundVideo(View parent) {
        if (!isEnabled() || backgroundType() != 4) {
            return null;
        }
        final String path = picturePath(NekoConfig.customProfileBackgroundPath, false);
        if (CustomProfileFormat.video(path)) {
            return null;
        }
        return animation(false, path, parent);
    }

    // ---------------------------------------------------------------- the video layer

    /**
     * The file the video layer should play for one slot, or null when the slot is not a video: no
     * picture, a still, or an animated image, all of which the canvas path draws itself.
     */
    @Nullable
    static String videoPath(boolean banner) {
        if (!isEnabled()) {
            return null;
        }
        if ((banner ? bannerType() : backgroundType()) != 4) {
            return null;
        }
        final String path = picturePath(banner
                ? NekoConfig.customProfileBannerPath : NekoConfig.customProfileBackgroundPath, banner);
        return TextUtils.isEmpty(path) || !CustomProfileFormat.video(path) ? null : path;
    }

    /**
     * Keeps the video layer in step with the look and with the header's current height. Called from
     * the profile's layout pass, which is where the header's geometry is settled — the views this
     * moves are real children of the profile, not something drawn into a canvas.
     */
    public static void syncVideoLayer(@Nullable FrameLayout root, @Nullable View header, int headerHeight) {
        CustomProfileVideoLayer.sync(root, header, headerHeight);
    }

    /**
     * Whether the list has to stop painting its own opaque background because the look's video
     * background is behind it.
     */
    public static boolean videoBackgroundAttached(@Nullable View root) {
        return CustomProfileVideoLayer.backgroundHandled(root);
    }



    /**
     * The player for one slot, opened on first use and kept until the file behind it changes or
     * {@link #releaseVideo()} drops it. Both slots are handled here because a peer's look can animate
     * either one and the bookkeeping is identical.
     */
    @Nullable
    private static AnimatedFileDrawable animation(boolean banner, @Nullable String path, View parent) {
        if (TextUtils.isEmpty(path)) {
            return null;
        }
        AnimatedFileDrawable drawable = banner ? videoDrawable : backgroundVideoDrawable;
        final String loadedFrom = banner ? videoLoadedFrom : backgroundVideoLoadedFrom;
        if (drawable == null || !path.equals(loadedFrom)) {
            release(banner);
            try {
                final File file = new File(path);
                if (!file.exists() || file.length() == 0) {
                    return null;
                }
                drawable = new AnimatedFileDrawable(file, true, 0, 0, null, null, null, 0, 0, true, null);
                drawable.setAllowDecodeSingleFrame(true);
            } catch (Throwable e) {
                FileLog.e(e);
                release(banner);
                return null;
            }
            if (banner) {
                videoDrawable = drawable;
                videoLoadedFrom = path;
            } else {
                backgroundVideoDrawable = drawable;
                backgroundVideoLoadedFrom = path;
            }
        }
        drawable.setParentView(parent);
        drawable.start();
        return drawable;
    }

    /** Drops both players. Called as a profile stops being on screen, and on reset. */
    public static void releaseVideo() {
        release(true);
        release(false);
        CustomProfileVideoLayer.release();
    }

    private static void release(boolean banner) {
        final AnimatedFileDrawable drawable = banner ? videoDrawable : backgroundVideoDrawable;
        if (drawable != null) {
            drawable.stop();
            drawable.recycle();
        }
        if (banner) {
            videoDrawable = null;
            videoLoadedFrom = null;
        } else {
            backgroundVideoDrawable = null;
            backgroundVideoLoadedFrom = null;
        }
    }

    /**
     * Writes every setting out as {@code key=value} lines behind a marker line. Plain text rather
     * than JSON so a user can eyeball what they are about to paste into a chat.
     */
    public static void exportToClipboard() {
        final StringBuilder builder = new StringBuilder(EXPORT_HEADER).append('\n');
        for (ConfigItem item : EXPORTED) {
            builder.append(item.getKey()).append('=').append(item.String()).append('\n');
        }
        try {
            final ClipboardManager clipboard = (ClipboardManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("label", builder.toString()));
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    /**
     * Reads settings back off the clipboard. Unknown keys and unparsable values are skipped rather
     * than aborting, so a paste from a newer build still applies whatever it does understand.
     *
     * @return whether anything was applied.
     */
    public static boolean importFromClipboard() {
        String text = null;
        try {
            final ClipboardManager clipboard = (ClipboardManager) ApplicationLoader.applicationContext
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            final ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0 && clip.getItemAt(0).getText() != null) {
                text = clip.getItemAt(0).getText().toString();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        if (text == null || !text.contains(EXPORT_HEADER)) {
            return false;
        }
        int applied = 0;
        for (String line : text.split("\n")) {
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            final String key = line.substring(0, eq).trim();
            final String value = line.substring(eq + 1).trim();
            for (ConfigItem item : EXPORTED) {
                if (!item.getKey().equals(key)) {
                    continue;
                }
                if (apply(item, value)) {
                    applied++;
                }
                break;
            }
        }
        if (applied > 0) {
            // An imported look brings its own frame; whatever the studio last drew is not it.
            NekoConfig.customProfileFrameGraph.setConfigString("");
            onSettingsChanged();
        }
        return applied > 0;
    }

    private static boolean apply(ConfigItem item, String value) {
        final Object parsed = item.checkConfigFromString(value);
        if (parsed == null) {
            return false;
        }
        switch (parsed) {
            case Boolean b -> item.setConfigBool(b);
            case Integer i -> item.setConfigInt(i);
            case String s -> item.setConfigString(s);
            default -> {
                return false;
            }
        }
        return true;
    }

    /**
     * Puts every setting back to the value it was declared with, pictures included.
     * <p>
     * Except the master switch, which is left exactly as the user had it. It is in
     * {@link #EXPORTED} because a look travels with it, but resetting it is not what "reset the
     * look" means to anybody: our switch is declared off, so the reset was turning the whole feature
     * off and the screen it was pressed on went blank. The reference has the same key in its own
     * reset list and gets away with it because its default is <em>on</em> — the switch survives there
     * too, which is the behaviour being matched here.
     */
    public static void resetAll() {
        final boolean enabled = NekoConfig.customProfileEnabled.Bool();
        for (ConfigItem item : EXPORTED) {
            if (item == NekoConfig.customProfileEnabled) {
                continue;
            }
            switch (item.defaultValue) {
                case Boolean b -> item.setConfigBool(b);
                case Integer i -> item.setConfigInt(i);
                case String s -> item.setConfigString(s);
                default -> {
                }
            }
        }
        NekoConfig.customProfileEnabled.setConfigBool(enabled);
        NekoConfig.customProfileBannerPath.setConfigString("");
        NekoConfig.customProfileBackgroundPath.setConfigString("");
        // The look's own font file is part of the look, and the typeface index above has just gone
        // back to 0 — leaving the path behind would keep a dead file pinned to every later push.
        NekoConfig.customProfileNameFontPath.setConfigString("");
        NekoConfig.customProfileThoughtFontPath.setConfigString("");
        // The frame is gone with the spec above; the graph that described it would only be rebuilt
        // into an empty one on the next open, so it goes now.
        NekoConfig.customProfileFrameGraph.setConfigString("");
        releaseVideo();
        onSettingsChanged();
    }
}
