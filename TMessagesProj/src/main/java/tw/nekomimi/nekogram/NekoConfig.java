package tw.nekomimi.nekogram;

import static tw.nekomimi.nekogram.config.ConfigItem.configTypeBool;
import static tw.nekomimi.nekogram.config.ConfigItem.configTypeFloat;
import static tw.nekomimi.nekogram.config.ConfigItem.configTypeInt;
import static tw.nekomimi.nekogram.config.ConfigItem.configTypeLong;
import static tw.nekomimi.nekogram.config.ConfigItem.configTypeMapIntInt;
import static tw.nekomimi.nekogram.config.ConfigItem.configTypeSetInt;
import static tw.nekomimi.nekogram.config.ConfigItem.configTypeString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.util.Pair;

import com.radolyn.ayugram.utils.AyuGhostUtils;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;

import java.io.ByteArrayInputStream;
import java.io.ObjectInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tw.nekomimi.nekogram.config.ConfigItem;

@SuppressLint("ApplySharedPref")
@SuppressWarnings("unused")
public class NekoConfig {

    public static final int TABLET_AUTO = 0;
    public static final int TABLET_ENABLE = 1;

    public static final int DIALOG_FILTER_EXCLUDE_NONE = 0;
    public static final int DIALOG_FILTER_EXCLUDE_MUTED = 1;
    public static final int DIALOG_FILTER_EXCLUDE_ALL = 2;

    public static final int MARKDOWN_PARSER_TELEGRAM = 0;
    public static final int MARKDOWN_PARSER_NEKO = 1;

    public static final int DRAWER_BACKGROUND_DEFAULT = 0;
    public static final int DRAWER_BACKGROUND_AVATAR = 1;
    public static final int DRAWER_BACKGROUND_BIG_AVATAR = 2;
    public static final int DRAWER_BACKGROUND_WALLPAPER = 3;

    public static final int DNS_TYPE_DEFAULT = 0;
    public static final int DNS_TYPE_NAX = 1;
    public static final int DNS_TYPE_SYSTEM = 2;
    public static final int DNS_TYPE_CUSTOM_DOH = 3;

    public static final int ID_TYPE_HIDDEN = 0;
    public static final int ID_TYPE_API = 1;
    public static final int ID_TYPE_BOT_API = 2;

    private static SharedPreferences preferences;

    public static SharedPreferences getPreferences() {
        if (preferences == null) {
            preferences = ApplicationLoader.applicationContext.getSharedPreferences("nkmrcfg", Context.MODE_PRIVATE);
        }
        return preferences;
    }

    public static final Object sync = new Object();

    private static boolean configLoaded = false;
    private static final ArrayList<ConfigItem> configs = new ArrayList<>();
    public static final ArrayList<DatacenterInfo> datacenterInfos = new ArrayList<>(5);

    // Configs
    public static ConfigItem unreadBadgeOnBackButton = addConfig("unreadBadgeOnBackButton", configTypeBool, false);
    public static ConfigItem useCustomEmoji = addConfig("useCustomEmoji", configTypeBool, false);
    public static ConfigItem repeatConfirm = addConfig("repeatConfirm", configTypeBool, true);
    public static ConfigItem disableInstantCamera = addConfig("DisableInstantCamera", configTypeBool, true);
    public static ConfigItem showSeconds = addConfig("showSeconds", configTypeBool, false);

    // From NekoConfig
    public static ConfigItem useIPv6 = addConfig("IPv6", configTypeBool, false);
    public static ConfigItem hidePhone = addConfig("HidePhone", configTypeBool, false);
    public static ConfigItem ignoreBlocked = addConfig("IgnoreBlocked", configTypeBool, false);
    public static ConfigItem tabletMode = addConfig("TabletMode", configTypeInt, 0);

    public static ConfigItem typeface = addConfig("TypefaceUseDefault", configTypeBool, false);
    public static ConfigItem nameOrder = addConfig("NameOrder", configTypeInt, 1);
    public static ConfigItem mapPreviewProvider = addConfig("MapPreviewProvider", configTypeInt, 0);
    public static ConfigItem forceBlurInChat = addConfig("forceBlurInChat", configTypeBool, false);
    public static ConfigItem chatBlueAlphaValue = addConfig("forceBlurInChatAlphaValue", configTypeInt, 127);
    public static ConfigItem showAddToSavedMessages = addConfig("showAddToSavedMessages", configTypeBool, true);
    public static ConfigItem showReport = addConfig("showReport", configTypeBool, false);
    public static ConfigItem showViewHistory = addConfig("showViewHistory", configTypeBool, true);
    public static ConfigItem showAdminActions = addConfig("showAdminActions", configTypeBool, true);
    public static ConfigItem showChangePermissions = addConfig("showChangePermissions", configTypeBool, true);
    public static ConfigItem showDeleteDownloadedFile = addConfig("showDeleteDownloadedFile", configTypeBool, true);
    public static ConfigItem showMessageDetails = addConfig("showMessageDetails", configTypeBool, true);
    public static ConfigItem showTranslate = addConfig("showTranslate", configTypeBool, true);
    public static ConfigItem showRepeat = addConfig("showRepeat", configTypeBool, true);
    public static ConfigItem showShareMessages = addConfig("showShareMessages", configTypeBool, false);
    public static ConfigItem showMessageHide = addConfig("showMessageHide", configTypeBool, false);

    public static ConfigItem actionBarDecoration = addConfig("ActionBarDecoration", configTypeInt, 0);
    public static ConfigItem stickerSize = addConfig("stickerSize", configTypeFloat, 14.0f);
    public static ConfigItem unlimitedFavedStickers = addConfig("UnlimitedFavoredStickers", configTypeBool, false);
    public static ConfigItem unlimitedPinnedDialogs = addConfig("UnlimitedPinnedDialogs", configTypeBool, false);
    public static ConfigItem openArchiveOnPull = addConfig("OpenArchiveOnPull", configTypeBool, false);
    public static ConfigItem hideKeyboardOnChatScroll = addConfig("HideKeyboardOnChatScroll", configTypeBool, false);
    public static ConfigItem useSystemEmoji = addConfig("EmojiUseDefault", configTypeBool, false);
    public static ConfigItem rearVideoMessages = addConfig("RearVideoMessages", configTypeBool, false);
    public static ConfigItem hideAllTab = addConfig("HideAllTab", configTypeBool, false);

    public static ConfigItem sortByUnread = addConfig("sort_by_unread", configTypeBool, false);
    public static ConfigItem sortByUnmuted = addConfig("sort_by_unmuted", configTypeBool, true);
    public static ConfigItem sortByUser = addConfig("sort_by_user", configTypeBool, true);
    public static ConfigItem sortByContacts = addConfig("sort_by_contacts", configTypeBool, true);

    public static ConfigItem disableSystemAccount = addConfig("DisableSystemAccount", configTypeBool, false);
    public static ConfigItem skipOpenLinkConfirm = addConfig("SkipOpenLinkConfirm", configTypeBool, false);

    public static ConfigItem showIdAndDc = addConfig("ShowIdAndDc", configTypeBool, true);

    public static ConfigItem cachePath = addConfig("cache_path", configTypeString, "");
    public static ConfigItem customSavePath = addConfig("customSavePath", configTypeString, "SovietGram");

    public static ConfigItem translationProvider = addConfig("translationProvider", configTypeInt, 1);
    public static ConfigItem translateToLang = addConfig("TransToLang", configTypeString, ""); // "" -> translate to current language (MessageTrans.kt & Translator.kt)
    public static ConfigItem translateInputLang = addConfig("TransInputToLang", configTypeString, "en");
    public static ConfigItem googleCloudTranslateKey = addConfig("GoogleCloudTransKey", configTypeString, "");

    public static ConfigItem disableNotificationBubbles = addConfig("disableNotificationBubbles", configTypeBool, false);

    public static ConfigItem tabsTitleType = addConfig("TabTitleType", configTypeInt, NekoXConfig.TITLE_TYPE_TEXT);
    public static ConfigItem confirmAVMessage = addConfig("ConfirmAVMessage", configTypeBool, false);
    public static ConfigItem askBeforeCall = addConfig("AskBeforeCalling", configTypeBool, true);
    public static ConfigItem disableNumberRounding = addConfig("DisableNumberRounding", configTypeBool, false);

    public static ConfigItem dnsType = addConfig("DnsType", configTypeInt, DNS_TYPE_DEFAULT);
    public static ConfigItem customDoH = addConfig("CustomDoH", configTypeString, "");

    public static ConfigItem mediaPreview = addConfig("MediaPreview", configTypeBool, true);

    public static ConfigItem disableVibration = addConfig("DisableVibration", configTypeBool, false);
    public static ConfigItem autoPauseVideo = addConfig("AutoPauseVideo", configTypeBool, false);
    public static ConfigItem disableProximityEvents = addConfig("DisableProximityEvents", configTypeBool, false);

    public static ConfigItem ignoreContentRestrictions = addConfig("ignoreContentRestrictions", configTypeBool, true);
    public static ConfigItem useChatAttachMediaMenu = addConfig("UseChatAttachEnterMenu", configTypeBool, true);
    public static ConfigItem disableLinkPreviewByDefault = addConfig("DisableLinkPreviewByDefault", configTypeBool, false);
    public static ConfigItem sendCommentAfterForward = addConfig("SendCommentAfterForward", configTypeBool, true);
    public static ConfigItem disableTrending = addConfig("DisableTrending", configTypeBool, true);
    public static ConfigItem dontSendGreetingSticker = addConfig("DontSendGreetingSticker", configTypeBool, true);
    public static ConfigItem hideTimeForSticker = addConfig("HideTimeForSticker", configTypeBool, false);
    public static ConfigItem takeGIFasVideo = addConfig("TakeGIFasVideo", configTypeBool, false);
    public static ConfigItem maxRecentStickerCount = addConfig("maxRecentStickerCount", configTypeInt, 20);
    public static ConfigItem disableSwipeToNext = addConfig("disableSwipeToNextChannel", configTypeBool, false);
    public static ConfigItem disableSwipeToNextTopic = addConfig("disableSwipeToNextTopic", configTypeBool, false);
    public static ConfigItem disableChoosingSticker = addConfig("disableChoosingSticker", configTypeBool, false);
    public static ConfigItem hideGroupSticker = addConfig("hideGroupSticker", configTypeBool, false);
    public static ConfigItem rememberAllBackMessages = addConfig("rememberAllBackMessages", configTypeBool, false);
    public static ConfigItem hideSendAsChannel = addConfig("hideSendAsChannel", configTypeBool, false);
    public static ConfigItem showSpoilersDirectly = addConfig("showSpoilersDirectly", configTypeBool, false);

    public static ConfigItem disableAutoDownloadingWin32Executable = addConfig("Win32ExecutableFiles", configTypeBool, true);
    public static ConfigItem disableAutoDownloadingArchive = addConfig("ArchiveFiles", configTypeBool, true);

    public static ConfigItem customAudioBitrate = addConfig("customAudioBitrate", configTypeInt, 32);
    public static ConfigItem enhancedFileLoader = addConfig("enhancedFileLoader", configTypeBool, false);
    public static ConfigItem uploadBoost = addConfig("uploadBoost", configTypeBool, false);
    public static ConfigItem useOSMDroidMap = addConfig("useOSMDroidMap", configTypeBool, false);
    public static ConfigItem mapDriftingFixForGoogleMaps = addConfig("mapDriftingFixForGoogleMaps", configTypeBool, true);

    public static ConfigItem localPremium = addConfig("localPremium", configTypeBool, false);
    // Purely client side: the number below replaces whatever the server reports as the star
    // balance. Stored as a string because ConfigCellTextInput writes back through setConfigString.
    public static ConfigItem fakeStars = addConfig("fakeStars", configTypeBool, false);
    public static ConfigItem fakeStarsAmount = addConfig("fakeStarsAmount", configTypeString, "1000");
    // Same idea for the TON wallet; the amount here is decimal TON, not nanotons.
    public static ConfigItem serverTon = addConfig("serverTon", configTypeBool, false);
    public static ConfigItem serverTonAmount = addConfig("serverTonAmount", configTypeString, "100");
    public static ConfigItem localGiftSender = addConfig("localGiftSender", configTypeBool, false);
    // Fabricated Fragment identity for the own account, client side only: the phone below replaces
    // the one the server reported, and every name in the comma separated list is added to the own
    // user as a non-editable username, which is exactly how the client recognises a collectible one.
    public static ConfigItem serverFragment = addConfig("serverFragment", configTypeBool, false);
    public static ConfigItem serverFragmentPhone = addConfig("serverFragmentPhone", configTypeString, "");
    public static ConfigItem serverFragmentUsernames = addConfig("serverFragmentUsernames", configTypeString, "");
    // The rewritten user object is the one UserConfig persists, so the real phone and username list
    // are parked here as JSON before the first rewrite and read back when the toggle goes off.
    public static ConfigItem serverFragmentBackup = addConfig("serverFragmentBackup", configTypeString, "");

    // Reshapes the microphone signal before Opus sees it, so voice messages can be sent in a
    // different voice. Off by default: it changes every voice message that is recorded afterwards.
    public static ConfigItem voiceChangerEnabled = addConfig("voiceChangerEnabled", configTypeBool, false);
    public static ConfigItem voiceChangerPreset = addConfig("voiceChangerPreset", configTypeInt, 0);

    // Turns ".рамка" sent as a reply to a photo into a framed meme. Off by default because it takes
    // over a message the user typed, and nothing else in the app claims that command.
    public static ConfigItem memeFrameEnabled = addConfig("memeFrameEnabled", configTypeBool, false);

    // Animates every character that enters or leaves a message input field. Off by default so the
    // input behaves like stock Telegram until the user asks for it. Every value below is an int
    // because the settings screen drives them from sliders; scaleStart is a percent, not a factor.
    public static ConfigItem textAnimation = addConfig("textAnimation", configTypeBool, false);
    public static ConfigItem textAnimationAllLines = addConfig("textAnimationAllLines", configTypeBool, true);
    public static ConfigItem textAnimationIgnoreSpaces = addConfig("textAnimationIgnoreSpaces", configTypeBool, true);
    public static ConfigItem textAnimationDuration = addConfig("textAnimationDuration", configTypeInt, 300);
    public static ConfigItem textAnimationBlur = addConfig("textAnimationBlur", configTypeBool, true);
    public static ConfigItem textAnimationBlurDuration = addConfig("textAnimationBlurDuration", configTypeInt, 300);
    public static ConfigItem textAnimationBlurRadius = addConfig("textAnimationBlurRadius", configTypeInt, 10);
    public static ConfigItem textAnimationBlurTextDelay = addConfig("textAnimationBlurTextDelay", configTypeInt, 20);
    public static ConfigItem textAnimationSlide = addConfig("textAnimationSlide", configTypeBool, true);
    public static ConfigItem textAnimationSlideDistance = addConfig("textAnimationSlideDistance", configTypeInt, 20);
    public static ConfigItem textAnimationScale = addConfig("textAnimationScale", configTypeBool, false);
    public static ConfigItem textAnimationScaleStart = addConfig("textAnimationScaleStart", configTypeInt, 0);
    public static ConfigItem textAnimationRotate = addConfig("textAnimationRotate", configTypeBool, false);
    public static ConfigItem textAnimationRotateAngle = addConfig("textAnimationRotateAngle", configTypeInt, -15);
    public static ConfigItem textAnimationDelete = addConfig("textAnimationDelete", configTypeBool, true);
    public static ConfigItem textAnimationParticleStyle = addConfig("textAnimationParticleStyle", configTypeInt, 0);
    public static ConfigItem textAnimationParticleCount = addConfig("textAnimationParticleCount", configTypeInt, 5);
    public static ConfigItem textAnimationParticleSpeed = addConfig("textAnimationParticleSpeed", configTypeInt, 50);
    public static ConfigItem textAnimationParticleSpread = addConfig("textAnimationParticleSpread", configTypeInt, 50);
    public static ConfigItem textAnimationParticleSize = addConfig("textAnimationParticleSize", configTypeInt, 50);
    public static ConfigItem textAnimationCursor = addConfig("textAnimationCursor", configTypeBool, true);
    public static ConfigItem textAnimationCursorSpeed = addConfig("textAnimationCursorSpeed", configTypeInt, 25);
    public static ConfigItem textAnimationCursorWidth = addConfig("textAnimationCursorWidth", configTypeInt, 5);
    public static ConfigItem textAnimationLiquidCursor = addConfig("textAnimationLiquidCursor", configTypeBool, false);
    public static ConfigItem textAnimationLiquidScale = addConfig("textAnimationLiquidScale", configTypeInt, 15);
    public static ConfigItem textAnimationSelectionEffect = addConfig("textAnimationSelectionEffect", configTypeInt, 0);
    public static ConfigItem textAnimationSelectionStretch = addConfig("textAnimationSelectionStretch", configTypeInt, 60);
    public static ConfigItem textAnimationSelectionSide = addConfig("textAnimationSelectionSide", configTypeInt, 50);

    public static ConfigItem usePersianCalendar = addConfig("UsePersianCalendar", configTypeBool, false);
    public static ConfigItem displayPersianCalendarByLatin = addConfig("DisplayPersianCalendarByLatin", configTypeBool, false);

    public static ConfigItem minimizedStickerCreator = addConfig("minimizedStickerCreator", configTypeBool, false);

    // --- Ghost Mode ---
    public static ConfigItem sendReadMessagePackets = addConfig("sendReadMessagePackets", configTypeBool, true);
    public static ConfigItem sendReadStoriesPackets = addConfig("sendReadStoriesPackets", configTypeBool, true);
    public static ConfigItem sendOnlinePackets = addConfig("sendOnlinePackets", configTypeBool, true);
    public static ConfigItem sendUploadProgress = addConfig("sendUploadProgress", configTypeBool, true);
    public static ConfigItem sendOfflinePacketAfterOnline = addConfig("sendOfflinePacketAfterOnline", configTypeBool, false);
    public static ConfigItem markReadAfterSend = addConfig("markReadAfterSend", configTypeBool, true);
    public static ConfigItem showGhostInDrawer = addConfig("showGhostInDrawer", configTypeBool, false);

    // Header look of the 12.3.1 side drawer. DRAWER_BACKGROUND_* above are the accepted values;
    // the blur/darken pair only applies while the avatar is the background.
    public static ConfigItem largeAvatarInDrawer = addConfig("largeAvatarInDrawer", configTypeInt, DRAWER_BACKGROUND_DEFAULT);
    public static ConfigItem avatarBackgroundBlur = addConfig("avatarBackgroundBlur", configTypeBool, false);
    public static ConfigItem avatarBackgroundDarken = addConfig("avatarBackgroundDarken", configTypeBool, false);
    public static ConfigItem showGhostModeStatus = addConfig("showGhostModeStatus", configTypeBool, false);

    // --- Locked Status ---
    public static ConfigItem sendReadMessagePacketsLocked = addConfig("sendReadMessagePacketsLocked", configTypeBool, false);
    public static ConfigItem sendReadStoriesPacketsLocked = addConfig("sendReadStoriesPacketsLocked", configTypeBool, false);
    public static ConfigItem sendOnlinePacketsLocked = addConfig("sendOnlinePacketsLocked", configTypeBool, false);
    public static ConfigItem sendUploadProgressLocked = addConfig("sendUploadProgressLocked", configTypeBool, false);
    public static ConfigItem sendOfflinePacketAfterOnlineLocked = addConfig("sendOfflinePacketAfterOnlineLocked", configTypeBool, false);
    // --- Ghost Mode ---

    // --- Custom Profile ---
    // Repaints the profile header: a banner behind the name, a shaped avatar, coloured text and a
    // few effects on top. Off by default — it replaces a screen the user knows.
    //
    // Colours are stored as signed ARGB ints. Alpha, dim and every "strength" value is a percent,
    // angles are degrees, and centres are a percent of the side they run along, so that every one of
    // them can be driven by the same integer slider.
    public static ConfigItem customProfileEnabled = addConfig("customProfileEnabled", configTypeBool, false);

    // Banner: 0 none, 1 solid, 2 gradient, 3 picture, 4 animation.
    public static ConfigItem customProfileBannerType = addConfig("customProfileBannerType", configTypeInt, 0);
    public static ConfigItem customProfileBannerColor = addConfig("customProfileBannerColor", configTypeInt, 0xFF3390EC);
    public static ConfigItem customProfileBannerPath = addConfig("customProfileBannerPath", configTypeString, "");
    // Where the banner file came from, as JSON: {"src":"api","sha","mime","kind"} for a picture the
    // owner uploaded to our own API, {"sha","url","mime"} for a workshop asset that is already hosted
    // publicly. A local file cannot be shared, but this pointer can — it is what lets another
    // SovietGram user fetch the same banner instead of seeing a flat colour. Empty only when the
    // picture could not be published anywhere. See CustomProfileMedia.
    public static ConfigItem customProfileBannerMedia = addConfig("customProfileBannerMedia", configTypeString, "");
    public static ConfigItem customProfileBannerAlpha = addConfig("customProfileBannerAlpha", configTypeInt, 100);
    public static ConfigItem customProfileBannerDim = addConfig("customProfileBannerDim", configTypeInt, 0);
    // Fade: 0 none, 1 linear, 2 radial. The radius is a percent of the box's longest side and runs to
    // 200, where the gradient reaches well past the edges and only its middle shows; both fade kinds
    // use it. The centre is where the fade starts from, as a percent of each side.
    public static ConfigItem customProfileBannerFade = addConfig("customProfileBannerFade", configTypeInt, 0);
    public static ConfigItem customProfileBannerFadeAngle = addConfig("customProfileBannerFadeAngle", configTypeInt, 180);
    public static ConfigItem customProfileBannerFadeRadius = addConfig("customProfileBannerFadeRadius", configTypeInt, 100);
    public static ConfigItem customProfileBannerFadeCenterX = addConfig("customProfileBannerFadeCenterX", configTypeInt, 50);
    public static ConfigItem customProfileBannerFadeCenterY = addConfig("customProfileBannerFadeCenterY", configTypeInt, 50);
    public static ConfigItem customProfileShowEmoji = addConfig("customProfileShowEmoji", configTypeBool, true);

    // Gradient used by banner type 2. Radial when the toggle is on, linear otherwise. The centre only
    // moves the radial one — a linear gradient is always struck through the middle of the box, which
    // is what the reference does too.
    public static ConfigItem customProfileGradientRadial = addConfig("customProfileGradientRadial", configTypeBool, false);
    public static ConfigItem customProfileGradientCount = addConfig("customProfileGradientCount", configTypeInt, 2);
    public static ConfigItem customProfileGradientColor1 = addConfig("customProfileGradientColor1", configTypeInt, 0xFF2B5876);
    public static ConfigItem customProfileGradientColor2 = addConfig("customProfileGradientColor2", configTypeInt, 0xFF4E4376);
    public static ConfigItem customProfileGradientColor3 = addConfig("customProfileGradientColor3", configTypeInt, 0xFF8E2DE2);
    public static ConfigItem customProfileGradientAngle = addConfig("customProfileGradientAngle", configTypeInt, 0);
    public static ConfigItem customProfileGradientRadius = addConfig("customProfileGradientRadius", configTypeInt, 100);
    public static ConfigItem customProfileGradientCenterX = addConfig("customProfileGradientCenterX", configTypeInt, 50);
    public static ConfigItem customProfileGradientCenterY = addConfig("customProfileGradientCenterY", configTypeInt, 50);

    // The picture behind the whole list, drawn under the rows rather than only under the header.
    // Types match the banner's: 0 none, 1 solid, 3 picture, 4 animation.
    public static ConfigItem customProfileBackgroundType = addConfig("customProfileBackgroundType", configTypeInt, 0);
    public static ConfigItem customProfileBackgroundColor = addConfig("customProfileBackgroundColor", configTypeInt, 0xFF000000);
    public static ConfigItem customProfileBackgroundPath = addConfig("customProfileBackgroundPath", configTypeString, "");
    /** {@link #customProfileBannerMedia} for the list background. */
    public static ConfigItem customProfileBackgroundMedia = addConfig("customProfileBackgroundMedia", configTypeString, "");
    public static ConfigItem customProfileBackgroundAlpha = addConfig("customProfileBackgroundAlpha", configTypeInt, 100);
    public static ConfigItem customProfileBackgroundDim = addConfig("customProfileBackgroundDim", configTypeInt, 0);
    public static ConfigItem customProfileBackgroundFade = addConfig("customProfileBackgroundFade", configTypeInt, 0);
    public static ConfigItem customProfileBackgroundFadeAngle = addConfig("customProfileBackgroundFadeAngle", configTypeInt, 180);
    public static ConfigItem customProfileBackgroundFadeRadius = addConfig("customProfileBackgroundFadeRadius", configTypeInt, 100);
    public static ConfigItem customProfileBackgroundFadeCenterX = addConfig("customProfileBackgroundFadeCenterX", configTypeInt, 50);
    public static ConfigItem customProfileBackgroundFadeCenterY = addConfig("customProfileBackgroundFadeCenterY", configTypeInt, 50);

    // The rows themselves: their colour, how see-through they are, and how far the recolour reaches.
    public static ConfigItem customProfileBlocksEnabled = addConfig("customProfileBlocksEnabled", configTypeBool, false);
    public static ConfigItem customProfileBlocksColor = addConfig("customProfileBlocksColor", configTypeInt, 0xFF1C1C1E);
    public static ConfigItem customProfileBlocksAlpha = addConfig("customProfileBlocksAlpha", configTypeInt, 100);
    public static ConfigItem customProfileBlocksBlur = addConfig("customProfileBlocksBlur", configTypeInt, 0);

    // Avatar: 0 circle, 1 rounded square, 2 square, 3 hexagon, 4 pentagon, 5 star, 6 heart, 7 flower.
    // 8 the free-form outline in customProfileAvatarPoints — the shape a look draws by hand, which
    // is what 71% of the published gallery uses; it is not offered in the picker because there is
    // nowhere in the app to draw one, only to wear one that arrived with a look.
    public static ConfigItem customProfileAvatarShape = addConfig("customProfileAvatarShape", configTypeInt, 0);
    /**
     * The free-form avatar outline as the reference stores it: a JSON array of {@code [x, y]} pairs,
     * each a fraction of the avatar's box. Empty unless the look on screen carries one.
     */
    public static ConfigItem customProfileAvatarPoints = addConfig("customProfileAvatarPoints", configTypeString, "");
    public static ConfigItem customProfileAvatarRadius = addConfig("customProfileAvatarRadius", configTypeInt, 18);
    public static ConfigItem customProfileAvatarSmoothing = addConfig("customProfileAvatarSmoothing", configTypeInt, 0);
    public static ConfigItem customProfileAvatarAlpha = addConfig("customProfileAvatarAlpha", configTypeInt, 100);
    public static ConfigItem customProfileAvatarDim = addConfig("customProfileAvatarDim", configTypeInt, 0);
    // How far the avatar's rim is faded out, as a percent: 0 leaves it crisp, 100 makes the outermost
    // pixels fully transparent. The radius is where the feather starts, as a percent of the way out
    // from the centre — the picture is untouched inside it. Same pair the reference uses.
    public static ConfigItem customProfileAvatarFade = addConfig("customProfileAvatarFade", configTypeInt, 0);
    public static ConfigItem customProfileAvatarFadeRadius = addConfig("customProfileAvatarFadeRadius", configTypeInt, 50);
    public static ConfigItem customProfileStoryRing = addConfig("customProfileStoryRing", configTypeBool, true);

    // Name: colour, glow, animation, typeface and size.
    public static ConfigItem customProfileNameColorEnabled = addConfig("customProfileNameColorEnabled", configTypeBool, false);
    public static ConfigItem customProfileNameColor = addConfig("customProfileNameColor", configTypeInt, 0xFFFFFFFF);
    public static ConfigItem customProfileTextColorEnabled = addConfig("customProfileTextColorEnabled", configTypeBool, false);
    public static ConfigItem customProfileTextColor = addConfig("customProfileTextColor", configTypeInt, 0xFFFFFFFF);
    public static ConfigItem customProfileNameGlow = addConfig("customProfileNameGlow", configTypeBool, false);
    public static ConfigItem customProfileNameGlowColor = addConfig("customProfileNameGlowColor", configTypeInt, 0xFF3390EC);
    public static ConfigItem customProfileNameGlowRadius = addConfig("customProfileNameGlowRadius", configTypeInt, 12);
    public static ConfigItem customProfileNameGlowStrength = addConfig("customProfileNameGlowStrength", configTypeInt, 6);
    // Name effect: 0 none, 1 pulse, 2 gradient, 3 shimmer, 4 rainbow, 5 neon, 6 fire, 7 ice.
    public static ConfigItem customProfileNameFx = addConfig("customProfileNameFx", configTypeInt, 0);
    public static ConfigItem customProfileNameFxSpeed = addConfig("customProfileNameFxSpeed", configTypeInt, 100);
    public static ConfigItem customProfileNameFxAngle = addConfig("customProfileNameFxAngle", configTypeInt, 0);
    public static ConfigItem customProfileNameFxColor1 = addConfig("customProfileNameFxColor1", configTypeInt, 0xFF3390EC);
    public static ConfigItem customProfileNameFxColor2 = addConfig("customProfileNameFxColor2", configTypeInt, 0xFFB388FF);
    // Typeface: 0 the view's own, 1 bold, 2 serif, 3 monospace, 4 sans, 5 light, 6 condensed,
    // 7 the font file at customProfileNameFontPath — which is how a workshop look brings its own font
    // along. 7 with no readable file falls back to 0.
    public static ConfigItem customProfileNameFont = addConfig("customProfileNameFont", configTypeInt, 0);
    public static ConfigItem customProfileNameFontPath = addConfig("customProfileNameFontPath", configTypeString, "");
    /**
     * {@link #customProfileBannerMedia} for that font file — where a peer can fetch the same bytes.
     * <p>
     * Without it the typeface index synced and the file did not, so a look that ships its own font
     * was the one part of it nobody else ever saw: every viewer read the name in their own font while
     * the owner saw the look as designed.
     */
    public static ConfigItem customProfileNameFontMedia = addConfig("customProfileNameFontMedia", configTypeString, "");

    /**
     * The avatar frame the look wears, as the workshop's own {@code frame_spec}: a JSON array of up
     * to eight layers. Empty for no frame.
     * <p>
     * The whole frame travels in this one string and needs no media descriptor beside it: a layer's
     * picture is either one of the eight {@code blank:} shapes, which are drawn in code, or a public
     * URL every reader can fetch for itself. See {@link tw.nekomimi.nekogram.helpers.CustomProfileFrame}.
     */
    public static ConfigItem customProfileFrameSpec = addConfig("customProfileFrameSpec", configTypeString, "");

    /**
     * The same frame as a node graph — the authoring state the studio edits.
     * <p>
     * Deliberately <b>not</b> exported. A reader has no use for it: it can be tens of kilobytes of
     * node positions and unused branches, and the only thing that can be drawn from it is the spec
     * beside it, which already travels. It is in the account scope, though, so it follows the user
     * from account to account like the picked-picture paths do.
     * <p>
     * The spec is authoritative when the two disagree — installing a frame from the workshop writes
     * the spec alone, and the graph is rebuilt from it rather than the other way round.
     */
    public static ConfigItem customProfileFrameGraph = addConfig("customProfileFrameGraph", configTypeString, "");

    /**
     * How the studio's node canvas is painted: 0 follows the app's theme, 1 dark, 2 light, 3 the
     * user's own colours, 4 one of the themes the server offers. These five are settings of this
     * device rather than part of the look, so none of them is exported.
     */
    public static ConfigItem customProfileFrameCanvasSkin = addConfig("customProfileFrameCanvasSkin", configTypeInt, 0);
    /** Which server theme the canvas uses, when the skin is set to one. */
    public static ConfigItem customProfileFrameCanvasTheme = addConfig("customProfileFrameCanvasTheme", configTypeInt, 0);
    /** The user's own canvas colours, as the same JSON map of roles a server theme comes in. */
    public static ConfigItem customProfileFrameCanvasCustom = addConfig("customProfileFrameCanvasCustom", configTypeString, "");
    /** How wires are drawn: 0 curved, 1 straight, 2 right-angled. */
    public static ConfigItem customProfileFrameWireLine = addConfig("customProfileFrameWireLine", configTypeInt, 0);
    /** Whether wires route around the nodes they would otherwise cross. */
    public static ConfigItem customProfileFrameWireDodge = addConfig("customProfileFrameWireDodge", configTypeBool, false);

    // The thought: a small bubble beside the avatar with a line of the user's own text. The most
    // used part of the reference's looks — 83% of the published gallery carries one.
    /**
     * The look's palette: a JSON map of theme colour key names to colours, which the profile screen
     * resolves every colour through. Empty unless a look carries one.
     */
    public static ConfigItem customProfilePalette = addConfig("customProfilePalette", configTypeString, "");

    /** The rows a look invents for itself, as the reference's own list of custom blocks. */
    public static ConfigItem customProfileExtraBlocks = addConfig("customProfileExtraBlocks", configTypeString, "");

    /** The header layout a look asks for: 0 as Telegram draws it, 1 left-hand, 4 hand-anchored. */
    public static ConfigItem customProfileHeaderLayout = addConfig("customProfileHeaderLayout", configTypeInt, 0);
    /** The anchors of a hand-made header layout, as the reference's own {@code anchor_*} map. */
    public static ConfigItem customProfileHeaderConfig = addConfig("customProfileHeaderConfig", configTypeString, "");

    /** The order a look wants the profile's own rows in, as the reference's list of row ids. */
    public static ConfigItem customProfileBlockOrder = addConfig("customProfileBlockOrder", configTypeString, "");
    /** The rows a look hides, by the same ids. */
    public static ConfigItem customProfileHiddenSections = addConfig("customProfileHiddenSections", configTypeString, "");

    public static ConfigItem customProfileThoughtText = addConfig("customProfileThoughtText", configTypeString, "");
    public static ConfigItem customProfileThoughtTextColor = addConfig("customProfileThoughtTextColor", configTypeInt, 0xFFFFFFFF);
    public static ConfigItem customProfileThoughtBackground = addConfig("customProfileThoughtBackground", configTypeInt, 0xCC0A0A1D);
    /** Their font list, as {@link #customProfileNameFont}: 0 default, 7 the look's own file. */
    public static ConfigItem customProfileThoughtFont = addConfig("customProfileThoughtFont", configTypeInt, 0);
    /** On by default, exactly as there: the bubble reads in the name's typeface unless told otherwise. */
    public static ConfigItem customProfileThoughtFontCopy = addConfig("customProfileThoughtFontCopy", configTypeBool, true);
    /**
     * The bubble's own font file, when it is not copying the name's. A path on this phone, so it is
     * not exported; {@link #customProfileThoughtFontMedia} is what travels.
     */
    public static ConfigItem customProfileThoughtFontPath = addConfig("customProfileThoughtFontPath", configTypeString, "");
    /** The descriptor other users fetch the bubble's font by. See {@link #customProfileNameFontMedia}. */
    public static ConfigItem customProfileThoughtFontMedia = addConfig("customProfileThoughtFontMedia", configTypeString, "");
    public static ConfigItem customProfileNameSize = addConfig("customProfileNameSize", configTypeInt, 100);
    // --- Custom Profile ---

    // --- GlowSuite ---
    // A soft light drawn behind avatars in the chat list and behind reaction bubbles. The look comes
    // from one radial gradient stamped a few times rather than from a blur, so it costs nothing per
    // frame once its shader is built.
    //
    // Intensity is 0..255 to match the gradient's own alpha, radius is a percent of whatever it is
    // drawn behind, and passes is how many times the gradient is stamped.
    public static ConfigItem glowSuiteEnabled = addConfig("glowSuiteEnabled", configTypeBool, false);

    public static ConfigItem glowAvatarEnabled = addConfig("glowAvatarEnabled", configTypeBool, true);
    public static ConfigItem glowAvatarIntensity = addConfig("glowAvatarIntensity", configTypeInt, 210);
    public static ConfigItem glowAvatarRadius = addConfig("glowAvatarRadius", configTypeInt, 200);
    public static ConfigItem glowAvatarPasses = addConfig("glowAvatarPasses", configTypeInt, 2);
    // Anything smaller than this is left alone: on a tiny avatar the glow reads as a smudge.
    public static ConfigItem glowAvatarMinSize = addConfig("glowAvatarMinSize", configTypeInt, 10);

    public static ConfigItem glowReactionEnabled = addConfig("glowReactionEnabled", configTypeBool, true);
    public static ConfigItem glowReactionIntensity = addConfig("glowReactionIntensity", configTypeInt, 195);
    public static ConfigItem glowReactionRadius = addConfig("glowReactionRadius", configTypeInt, 250);
    public static ConfigItem glowReactionPasses = addConfig("glowReactionPasses", configTypeInt, 2);
    // --- GlowSuite ---

    static {
        init();
    }

    public static void init() {
        loadConfig(false);
    }

    public static ConfigItem addConfig(String k, int t, Object d) {
        ConfigItem a = new ConfigItem(k, t, d);
        configs.add(a);
        return a;
    }

    public static void loadConfig(boolean force) {
        synchronized (sync) {
            if (configLoaded && !force) {
                return;
            }
            if (ApplicationLoader.applicationContext == null) {
                return;
            }
            for (int i = 0; i < configs.size(); i++) {
                ConfigItem o = configs.get(i);

                try {
                    if (o.type == configTypeBool) {
                        o.value = getPreferences().getBoolean(o.key, (boolean) o.defaultValue);
                    }
                    if (o.type == configTypeInt) {
                        o.value = getPreferences().getInt(o.key, (int) o.defaultValue);
                    }
                    if (o.type == configTypeLong) {
                        o.value = getPreferences().getLong(o.key, (Long) o.defaultValue);
                    }
                    if (o.type == configTypeFloat) {
                        o.value = getPreferences().getFloat(o.key, (Float) o.defaultValue);
                    }
                    if (o.type == configTypeString) {
                        o.value = getPreferences().getString(o.key, (String) o.defaultValue);
                    }
                    if (o.type == configTypeSetInt) {
                        Set<String> ss = getPreferences().getStringSet(o.key, new HashSet<>());
                        HashSet<Integer> si = new HashSet<>();
                        for (String s : ss) {
                            si.add(Integer.parseInt(s));
                        }
                        o.value = si;
                    }
                    if (o.type == configTypeMapIntInt) {
                        String cv = getPreferences().getString(o.key, "");
                        if (cv.isEmpty()) {
                            o.value = new HashMap<Integer, Integer>();
                        } else {
                            try {
                                byte[] data = Base64.decode(cv, Base64.DEFAULT);
                                ObjectInputStream ois = new ObjectInputStream(
                                        new ByteArrayInputStream(data));
                                o.value = ois.readObject();
                                if (o.value == null) {
                                    o.value = new HashMap<Integer, Integer>();
                                }
                                ois.close();
                            } catch (Exception e) {
                                o.value = new HashMap<Integer, Integer>();
                            }
                        }
                    }
                } catch (ClassCastException | NumberFormatException e) {
                    FileLog.e("Invalid config value for " + o.key, e);
                    o.value = o.defaultValue;
                    getPreferences().edit().remove(o.key).apply();
                }
            }
            for (int a = 1; a <= 5; a++) {
                datacenterInfos.add(new DatacenterInfo(a));
            }
            configLoaded = true;
        }
    }

    public static class DatacenterInfo {

        public int id;

        public long pingId;
        public long ping;
        public boolean checking;
        public boolean available;
        public long availableCheckTime;

        public DatacenterInfo(int i) {
            id = i;
        }
    }

    public static boolean fixDriftingForGoogleMaps() {
        return !useOSMDroidMap.Bool() && mapDriftingFixForGoogleMaps.Bool();
    }

    // --- Ghost Mode ---
    public static boolean isGhostModeActive() {
        for (Pair<ConfigItem, ConfigItem> pair : ghostToggleItems) {
            ConfigItem item = pair.first;
            ConfigItem lockedItem = pair.second;
            if (!lockedItem.Bool()) {
                boolean currentValue = item.Bool();
                boolean isGhostState = (item == sendOfflinePacketAfterOnline) == currentValue;

                if (!isGhostState) {
                    return false;
                }
            }
        }
        return true;
    }

    public static void setGhostMode(boolean enabled) {
        for (Pair<ConfigItem, ConfigItem> pair : ghostToggleItems) {
            ConfigItem item = pair.first;
            ConfigItem lockedItem = pair.second;
            if (!lockedItem.Bool()) {
                boolean targetValue = (item == sendOfflinePacketAfterOnline) == enabled;
                item.setConfigBool(targetValue);
            }
        }
    }

    public static void toggleGhostMode() {
        boolean newState = !isGhostModeActive();
        setGhostMode(newState);

        boolean sendOnlineNow = !newState && !sendOfflinePacketAfterOnlineLocked.Bool() && sendOfflinePacketAfterOnline.Bool();
        AyuGhostUtils.performStatusRequest(sendOnlineNow);
    }

    private static final List<Pair<ConfigItem, ConfigItem>> ghostToggleItems = Arrays.asList(
            new Pair<>(sendReadMessagePackets, sendReadMessagePacketsLocked),
            new Pair<>(sendReadStoriesPackets, sendReadStoriesPacketsLocked),
            new Pair<>(sendOnlinePackets, sendOnlinePacketsLocked),
            new Pair<>(sendUploadProgress, sendUploadProgressLocked),
            new Pair<>(sendOfflinePacketAfterOnline, sendOfflinePacketAfterOnlineLocked)
    );
    // --- Ghost Mode ---

    public static Map<String, Integer> getConfigTypes() {
        synchronized (sync) {
            Map<String, Integer> types = new HashMap<>();
            for (ConfigItem o : configs) {
                types.put(o.getKey(), o.type);
            }
            return types;
        }
    }
}
