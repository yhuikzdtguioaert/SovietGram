package sovietgram.com

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.core.content.edit
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.ApplicationLoader
import org.telegram.messenger.BuildVars
import org.telegram.messenger.FileLog
import org.telegram.messenger.SharedConfig
import tw.nekomimi.nekogram.NekoConfig
import tw.nekomimi.nekogram.config.ConfigItem
import tw.nekomimi.nekogram.config.ConfigItemKeyLinked
import tw.nekomimi.nekogram.llm.utils.LlmUrlNormalizer
import java.io.ByteArrayInputStream
import java.io.ObjectInputStream


object NaConfig {
    const val DESIGN_VERSION_LATEST = 0
    const val DESIGN_VERSION_12_7_3 = 1273

    // Per-area design versions. 0 is the current 12.9.0 look; 1231 goes back to 12.3.1 —
    // the last release before upstream's 12.4.0 rebuilt the whole shell (glass action bar,
    // floating input island, bottom tab bar replacing the side drawer).
    const val AREA_DESIGN_LATEST = 0
    const val AREA_DESIGN_12_3_1 = 1231

    @Volatile
    private var initialized = false

    @JvmStatic
    fun getPreferences(): SharedPreferences {
        return NekoConfig.getPreferences()
    }

    @JvmStatic
    fun init() {
        if (initialized) return
        synchronized(sync) {
            if (initialized) return
            if (ApplicationLoader.applicationContext == null) return

            loadConfig(false)
            updatePreferredTranslateTargetLangList()
            fixConfig()
            if (!BuildVars.LOGS_ENABLED) {
                showRPCError.setConfigBool(false)
            }
            initialized = true
        }
    }

    val sync = Any()
    private var configLoaded = false
    private val configs = ArrayList<ConfigItem>()

    // Configs
    val showTextBold =
        addConfig(
            "TextBold",
            ConfigItem.configTypeBool,
            true
        )
    val showTextItalic =
        addConfig(
            "TextItalic",
            ConfigItem.configTypeBool,
            true
        )
    val showTextMono =
        addConfig(
            "TextMonospace",
            ConfigItem.configTypeBool,
            true
        )
    val showTextStrikethrough =
        addConfig(
            "TextStrikethrough",
            ConfigItem.configTypeBool,
            true
        )
    val showTextUnderline =
        addConfig(
            "TextUnderline",
            ConfigItem.configTypeBool,
            true
        )
    val showTextQuote =
        addConfig(
            "TextQuote",
            ConfigItem.configTypeBool,
            true
        )
    val showTextSpoiler =
        addConfig(
            "TextSpoiler",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateLink =
        addConfig(
            "TextLink",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateMention =
        addConfig(
            "TextCreateMention",
            ConfigItem.configTypeBool,
            true
        )
    val showTextCreateDate =
        addConfig(
            "TextCreateDate",
            ConfigItem.configTypeBool,
            true
        )
    val showTextRegular =
        addConfig(
            "TextRegular",
            ConfigItem.configTypeBool,
            true
        )
    val showTextTranslate =
        addConfig(
            "TextTranslate",
            ConfigItem.configTypeBool,
            true
        )
    val textStyleOrder =
        addConfig(
            "TextStyleOrder",
            ConfigItem.configTypeString,
            "translate,bold,italic,mono,code,strike,underline,quote,spoiler,link,mention,date,regular"
        )
    val combineMessage =
        addConfig(
            "CombineMessage",
            ConfigItem.configTypeInt,
            0
        )
    val noiseSuppressAndVoiceEnhance =
        addConfig(
            "NoiseSuppressAndVoiceEnhance",
            ConfigItem.configTypeBool,
            false
        )
    val showNoQuoteForward =
        addConfig(
            "NoQuoteForward",
            ConfigItem.configTypeBool,
            false
        )
    val showRepeatAsCopy =
        addConfig(
            "RepeatAsCopy",
            ConfigItem.configTypeBool,
            false
        )
    val doubleTapAction =
        addConfig(
            "DoubleTapAction",
            ConfigItem.configTypeInt,
            3
        )
    val doubleTapActionOut =
        addConfig(
            "DoubleTapActionOut",
            ConfigItem.configTypeInt,
            8
        )
    val showCopyPhoto =
        addConfig(
            "CopyPhoto",
            ConfigItem.configTypeBool,
            false
        )
    val showReactions =
        addConfig(
            "Reactions",
            ConfigItem.configTypeBool,
            true
        )
    val customTitle =
        addConfig(
            "CustomTitle",
            ConfigItem.configTypeString,
            "SovietGram"
        )
    val versionDesign =
        addConfig(
            "VersionDesign",
            ConfigItem.configTypeInt,
            DESIGN_VERSION_LATEST
        )

    @JvmStatic
    fun isLatestDesign(): Boolean = versionDesign.Int() == DESIGN_VERSION_LATEST
    val dateOfForwardedMsg =
        addConfig(
            "DateOfForwardedMsg",
            ConfigItem.configTypeBool,
            false
        )
    val showMessageID =
        addConfig(
            "ShowMessageID",
            ConfigItem.configTypeBool,
            false
        )
    val showRPCError =
        addConfig(
            "ShowRPCError",
            ConfigItem.configTypeBool,
            false
        )
    val zalgoFilter =
        addConfig(
            "ZalgoFilter",
            ConfigItem.configTypeBool,
            false
        )
    val alwaysShowDownloadIcon =
        addConfig(
            "AlwaysShowDownloadIcon",
            ConfigItem.configTypeBool,
            false
        )
    val customEditedMessage =
        addConfig(
            "CustomEditedMessage",
            ConfigItem.configTypeString,
            ""
        )
    val disableProxyWhenVpnEnabled =
        addConfig(
            "DisableProxyWhenVpnEnabled",
            ConfigItem.configTypeBool,
            false
        )
    val tgWsProxyEnabled =
        addConfig(
            "TgWsProxyEnabled",
            ConfigItem.configTypeBool,
            false
        )
    val tgWsProxyPort =
        addConfig(
            "TgWsProxyPort",
            ConfigItem.configTypeInt,
            1488
        )
    val tgWsProxyPool =
        addConfig(
            "TgWsProxyPool",
            ConfigItem.configTypeInt,
            4
        )
    val tgWsProxySecret =
        addConfig(
            "TgWsProxySecret",
            ConfigItem.configTypeString,
            ""
        )
    val tgWsProxyCloudflareCdn =
        addConfig(
            "TgWsProxyCloudflareCdn",
            ConfigItem.configTypeBool,
            true
        )
    val tgWsProxyNotificationEnabled =
        addConfig(
            "TgWsProxyNotificationEnabled",
            ConfigItem.configTypeBool,
            false
        )
    val vlessEnabled =
        addConfig(
            "VlessEnabled",
            ConfigItem.configTypeBool,
            false
        )
    val vlessKey =
        addConfig(
            "VlessKey",
            ConfigItem.configTypeString,
            ""
        )
    val vlessSocksPort =
        addConfig(
            "VlessSocksPort",
            ConfigItem.configTypeInt,
            10808
        )
    val vlessNotificationEnabled =
        addConfig(
            "VlessNotificationEnabled",
            ConfigItem.configTypeBool,
            false
        )
    val vlessSubscriptionUrl =
        addConfig(
            "VlessSubscriptionUrl",
            ConfigItem.configTypeString,
            ""
        )

    /**
     * Cached result of the last subscription refresh: one vless:// URI per line.
     * Only the entries the bundled core can actually run are kept, so every line
     * here is directly usable as the active key.
     */
    val vlessServerList =
        addConfig(
            "VlessServerList",
            ConfigItem.configTypeString,
            ""
        )

    /**
     * Index into [vlessServerList] of the server the user picked, or -1 when the
     * active key was entered by hand. Only a UI hint — the key actually handed to
     * the engine is always [vlessKey].
     */
    val vlessSelectedServer =
        addConfig(
            "VlessSelectedServer",
            ConfigItem.configTypeInt,
            -1
        )
    val notificationIcon =
        addConfig(
            "NotificationIcon",
            ConfigItem.configTypeInt,
            4
        )
    val showSetReminder =
        addConfig(
            "SetReminder",
            ConfigItem.configTypeBool,
            false
        )
    val showOnlineStatus =
        addConfig(
            "ShowOnlineStatus",
            ConfigItem.configTypeBool,
            false
        )
    val showFullAbout =
        addConfig(
            "ShowFullAbout",
            ConfigItem.configTypeBool,
            true
        )
    val typeMessageHintUseGroupName =
        addConfig(
            "TypeMessageHintUseGroupName",
            ConfigItem.configTypeBool,
            false
        )
    val showSendAsUnderMessageHint =
        addConfig(
            "ShowSendAsUnderMessageHint",
            ConfigItem.configTypeBool,
            false
        )
    val hideBotButtonInInputField =
        addConfig(
            "HideBotButtonInInputField",
            ConfigItem.configTypeBool,
            false
        )
    val chatDecoration =
        addConfig(
            "ChatDecoration",
            ConfigItem.configTypeInt,
            0
        )
    val doNotUnarchiveBySwipe =
        addConfig(
            "DoNotUnarchiveBySwipe",
            ConfigItem.configTypeBool,
            false
        )
    val defaultDeleteMenu =
        addConfig(
            "DefaultDeleteMenu",
            ConfigItem.configTypeInt,
            0
        )
    val defaultDeleteMenuBanUsers =
        addConfig(
            "DeleteBanUsers",
            defaultDeleteMenu,
            3,
            false
        )
    val defaultDeleteMenReportSpam =
        addConfig(
            "DeleteReportSpam",
            defaultDeleteMenu,
            2,
            false
        )
    val defaultDeleteMenuDeleteAll =
        addConfig(
            "DeleteAll",
            defaultDeleteMenu,
            1,
            false
        )
    val defaultDeleteMenuDoActionsInCommonGroups =
        addConfig(
            "DoActionsInCommonGroups",
            defaultDeleteMenu,
            0,
            false
        )
    val disableStories =
        addConfig(
            "DisableStories",
            ConfigItem.configTypeBool,
            false
        )
    val useLocalQuoteColorData =
        addConfig(
            "useLocalQuoteColorData",
            ConfigItem.configTypeString,
            ""
        )
    val useLocalEmojiStatusData =
        addConfig(
            "useLocalEmojiStatusData",
            ConfigItem.configTypeString,
            ""
        )
    val disableMarkdown =
        addConfig(
            "DisableMarkdown",
            ConfigItem.configTypeBool,
            false
        )
    val showSmallGIF =
        addConfig(
            "ShowSmallGIF",
            ConfigItem.configTypeBool,
            false
        )
    val disableClickCommandToSend =
        addConfig(
            "DisableClickCommandToSend",
            ConfigItem.configTypeBool,
            false
        )
    val disableDialogsFloatingButton =
        addConfig(
            "DisableDialogsFloatingButton",
            ConfigItem.configTypeBool,
            false
        )
    val centerActionBarTitle =
        addConfig(
            "CenterActionBarTitle",
            ConfigItem.configTypeBool,
            false
        )
    val showQuickReplyInBotCommands =
        addConfig(
            "ShowQuickReplyInBotCommands",
            ConfigItem.configTypeBool,
            false
        )
    val pushServiceType =
        addConfig(
            "PushServiceType",
            ConfigItem.configTypeInt,
            1
        )
    val pushServiceTypeInAppDialog =
        addConfig(
            "PushServiceTypeInAppDialog",
            ConfigItem.configTypeBool,
            false
        )
    val pushServiceTypeUnifiedGateway =
        addConfig(
            "PushServiceTypeUnifiedGateway",
            ConfigItem.configTypeString,
            ""
        )
    val sendMp4DocumentAsVideo =
        addConfig(
            "SendMp4DocumentAsVideo",
            ConfigItem.configTypeBool,
            true
        )
    val disableChannelMuteButton =
        addConfig(
            "DisableChannelMuteButton",
            ConfigItem.configTypeBool,
            false
        )
    val disablePreviewVideoSoundShortcut =
        addConfig(
            "DisablePreviewVideoSoundShortcut",
            ConfigItem.configTypeBool,
            true
        )
    val regexFiltersEnabled =
        addConfig(
            "RegexFilters",
            ConfigItem.configTypeBool,
            false
        )
    val regexFiltersData =
        addConfig(
            "RegexFiltersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val regexFiltersEnableInChats =
        addConfig(
            "RegexFiltersEnableInChats",
            ConfigItem.configTypeBool,
            false
        )
    val regexChatFiltersData =
        addConfig(
            "RegexChatFiltersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val regexFiltersExcludedDialogs =
        addConfig(
            "RegexFiltersExcludedDialogs",
            ConfigItem.configTypeString,
            "[]"
        )
    val blockedChannelsData =
        addConfig(
            "BlockedChannelsData",
            ConfigItem.configTypeString,
            "[]"
        )
    val customFilteredUsersData =
        addConfig(
            "CustomFilteredUsersData",
            ConfigItem.configTypeString,
            "[]"
        )
    val showTimeHint =
        addConfig(
            "ShowTimeHint",
            ConfigItem.configTypeBool,
            false
        )
    val searchHashtagDefaultPageChannel =
        addConfig(
            "SearchHashtagDefaultPageChannel",
            ConfigItem.configTypeInt,
            0
        )
    val searchHashtagDefaultPageChat =
        addConfig(
            "SearchHashtagDefaultPageChat",
            ConfigItem.configTypeInt,
            0
        )
    val enablePanguOnSending =
        addConfig(
            "EnablePanguOnSending",
            ConfigItem.configTypeBool,
            false
        )
    val defaultHlsVideoQuality =
        addConfig(
            "DefaultHlsVideoQuality",
            ConfigItem.configTypeInt,
            0
        )
    val disableBotOpenButton =
        addConfig(
            "DisableBotOpenButton",
            ConfigItem.configTypeBool,
            false
        )
    val customTitleUserName =
        addConfig(
            "CustomTitleUserName",
            ConfigItem.configTypeBool,
            false
        )
    val enhancedVideoBitrate =
        addConfig(
            "EnhancedVideoBitrate",
            ConfigItem.configTypeBool,
            false
        )
    val ActionBarButtonReply =
        addConfig(
            "Reply",
            ConfigItem.configTypeBool,
            false
        )
    val ActionBarButtonEdit =
        addConfig(
            "Edit",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonSelectBetween =
        addConfig(
            "SelectBetween",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonCopy =
        addConfig(
            "Copy",
            ConfigItem.configTypeBool,
            true
        )
    val ActionBarButtonForward =
        addConfig(
            "Forward",
            ConfigItem.configTypeBool,
            true
        )
    val playerDecoder =
        addConfig(
            "VideoPlayerDecoder",
            ConfigItem.configTypeInt,
            1
        )

    // SovietGram
    val enableSaveDeletedMessages =
        addConfig(
            "EnableSaveDeletedMessages",
            ConfigItem.configTypeBool,
            false
        )
    val showDeletedMessagesInChat =
        addConfig(
            "ShowDeletedMessagesInChat",
            ConfigItem.configTypeBool,
            true
        )
    val showDeletedMessagesInChatList =
        addConfig(
            "ShowDeletedMessagesInChatList",
            ConfigItem.configTypeBool,
            false
        )

    /**
     * Order of the chat list on the Deleted Messages screen.
     * `false` (default) = the chat whose messages were deleted most recently comes first.
     */
    val deletedDialogsSortOldestFirst =
        addConfig(
            "DeletedDialogsSortOldestFirst",
            ConfigItem.configTypeBool,
            false
        )

    val enableSaveEditsHistory =
        addConfig(
            "EnableSaveEditsHistory",
            ConfigItem.configTypeBool,
            false
        )
    val saveLocalLastSeen =
        addConfig(
            "SaveLocalLastSeen",
            ConfigItem.configTypeBool,
            false
        )
    val messageSavingSaveMedia =
        addConfig(
            "MessageSavingSaveMedia",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateChats =
        addConfig(
            "SaveMediaInPrivateChats",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPublicChannels =
        addConfig(
            "SaveMediaInPublicChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateChannels =
        addConfig(
            "SaveMediaInPrivateChannels",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPublicGroups =
        addConfig(
            "SaveMediaInPublicGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveMediaInPrivateGroups =
        addConfig(
            "SaveMediaInPrivateGroups",
            ConfigItem.configTypeBool,
            true
        )
    val saveDeletedMessageForBot =
        addConfig(
            "SaveDeletedMessageForBot", // save in bot chats
            ConfigItem.configTypeBool,
            false
        )
    val saveDeletedMessageForBotUser =
        addConfig(
            "SaveDeletedMessageForBotUser", // all messages from bot
            ConfigItem.configTypeBool,
            false
        )
    val customDeletedMark =
        addConfig(
            "CustomDeletedMark",
            ConfigItem.configTypeString,
            ""
        )
    val hidePremiumSection =
        addConfig(
            "HidePremiumSection",
            ConfigItem.configTypeBool,
            false
        )
    val hideHelpSection =
        addConfig(
            "HideHelpSection",
            ConfigItem.configTypeBool,
            true
        )
    val llmApiUrl =
        addConfig(
            "LlmApiUrl",
            ConfigItem.configTypeString,
            ""
        )
    val llmApiKey =
        addConfig(
            "LlmApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmModelName =
        addConfig(
            "LlmModelName",
            ConfigItem.configTypeString,
            ""
        )
    val llmSystemPrompt =
        addConfig(
            "LlmSystemPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val llmUserPrompt =
        addConfig(
            "LlmUserPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderPreset =
        addConfig(
            "LlmProviderPreset",
            ConfigItem.configTypeInt,
            0
        )
    val llmProviderOpenAIKey =
        addConfig(
            "LlmProviderOpenAIKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenAIModel =
        addConfig(
            "LlmProviderOpenAIModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGeminiKey =
        addConfig(
            "LlmProviderGeminiKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGeminiModel =
        addConfig(
            "LlmProviderGeminiModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderXAIKey =
        addConfig(
            "LlmProviderXAIKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderXAIModel =
        addConfig(
            "LlmProviderXAIModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGroqKey =
        addConfig(
            "LlmProviderGroqKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderGroqModel =
        addConfig(
            "LlmProviderGroqModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderDeepSeekKey =
        addConfig(
            "LlmProviderDeepSeekKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderDeepSeekModel =
        addConfig(
            "LlmProviderDeepSeekModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderCerebrasKey =
        addConfig(
            "LlmProviderCerebrasKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderCerebrasModel =
        addConfig(
            "LlmProviderCerebrasModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOllamaCloudKey =
        addConfig(
            "LlmProviderOllamaCloudKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOllamaCloudModel =
        addConfig(
            "LlmProviderOllamaCloudModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenRouterKey =
        addConfig(
            "LlmProviderOpenRouterKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderOpenRouterModel =
        addConfig(
            "LlmProviderOpenRouterModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderVercelAIGatewayKey =
        addConfig(
            "LlmProviderVercelAIGatewayKey",
            ConfigItem.configTypeString,
            ""
        )
    val llmProviderVercelAIGatewayModel =
        addConfig(
            "LlmProviderVercelAIGatewayModel",
            ConfigItem.configTypeString,
            ""
        )
    val llmTemperature =
        addConfig(
            "LlmTemperature",
            ConfigItem.configTypeFloat,
            0.7f
        )
    val llmUseContext =
        addConfig(
            "LlmUseContext",
            ConfigItem.configTypeBool,
            false
        )
    val llmContextSize =
        addConfig(
            "LlmContextSize",
            ConfigItem.configTypeInt,
            2
        )
    val llmUseContextInAutoTranslate =
        addConfig(
            "LlmUseContextInAutoTranslate",
            ConfigItem.configTypeBool,
            false
        )
    val translucentDeletedMessages =
        addConfig(
            "TranslucentDeletedMessages",
            ConfigItem.configTypeBool,
            true
        )
    val enableSeparateArticleTranslator =
        addConfig(
            "EnableSeparateArticleTranslator",
            ConfigItem.configTypeBool,
            false
        )
    val articleTranslationProvider =
        addConfig(
            "ArticleTranslationProvider",
            ConfigItem.configTypeInt,
            1
        )
    val disableCrashlyticsCollection =
        addConfig(
            "DisableCrashlyticsCollection",
            ConfigItem.configTypeBool,
            false
        )
    val showStickersRowToplevel =
        addConfig(
            "ShowStickersRowToplevel",
            ConfigItem.configTypeBool,
            true
        )
    val hideShareButtonInChannel =
        addConfig(
            "HideShareButtonInChannel",
            ConfigItem.configTypeBool,
            false
        )
    val preferredTranslateTargetLang =
        addConfig(
            "PreferredTranslateTargetLang",
            ConfigItem.configTypeString,
            ""
        )
    val telegramUIAutoTranslate =
        addConfig(
            "TelegramUIAutoTranslate",
            ConfigItem.configTypeBool,
            true
        )
    val translatorMode =
        addConfig(
            "TranslatorMode",
            ConfigItem.configTypeInt,
            0 // 0: off; 1: manual only; 2: all
        )
    val translatorModeWithOriginalMigrated =
        addConfig(
            "TranslatorModeWithOriginalMigrated",
            ConfigItem.configTypeBool,
            false
        )
    val notificationIconDefaultMigrated =
        addConfig(
            "NotificationIconDefaultMigrated",
            ConfigItem.configTypeBool,
            false
        )
    val centerActionBarTitleType =
        addConfig(
            "CenterActionBarTitleType",
            ConfigItem.configTypeInt,
            1 // 0: off; 1: always on; 2: settings only; 3: chats only
        )
    val hideArchive =
        addConfig(
            "HideArchive",
            ConfigItem.configTypeBool,
            false
        )
    val confirmAllLinks =
        addConfig(
            "ConfirmAllLinks",
            ConfigItem.configTypeBool,
            false
        )
    val useDeletedIcon =
        addConfig(
            "UseDeletedIcon",
            ConfigItem.configTypeBool,
            true
        )
    val useEditedIcon =
        addConfig(
            "UseEditedIcon",
            ConfigItem.configTypeBool,
            true
        )
    val saveToChatSubfolder =
        addConfig(
            "SaveToChatSubfolder",
            ConfigItem.configTypeBool,
            false
        )
    val silentMessageByDefault =
        addConfig(
            "SilentMessageByDefault",
            ConfigItem.configTypeBool,
            false
        )
    val folderNameAsTitle =
        addConfig(
            "FolderNameAsTitle",
            ConfigItem.configTypeBool,
            false
        )
    val translatorKeepMarkdown =
        addConfig(
            "TranslatorKeepMarkdown",
            ConfigItem.configTypeBool,
            true
        )
    val googleTranslateExp =
        addConfig(
            "GoogleTranslateExp",
            ConfigItem.configTypeBool,
            true
        )
    val springAnimationCrossfade =
        addConfig(
            "SpringAnimationCrossfade",
            ConfigItem.configTypeBool,
            true
        )
    val dontAutoPlayNextVoice =
        addConfig(
            "DontAutoPlayNextVoice",
            ConfigItem.configTypeBool,
            false
        )
    val messageColoredBackground =
        addConfig(
            "MessageColoredBackground",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemBoostGroup =
        addConfig(
            "ChatMenuItemBoostGroup",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemLinkedChat =
        addConfig(
            "ChatMenuItemLinkedChat",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemToBeginning =
        addConfig(
            "ChatMenuItemToBeginning",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemGoToMessage =
        addConfig(
            "ChatMenuItemGoToMessage",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemHideTitle =
        addConfig(
            "ChatMenuItemHideTitle",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemViewDeleted =
        addConfig(
            "ChatMenuItemViewDeleted",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemClearDeleted =
        addConfig(
            "ChatMenuItemClearDeleted",
            ConfigItem.configTypeBool,
            true
        )
    val chatMenuItemDeleteOwnMessages =
        addConfig(
            "ChatMenuItemDeleteOwnMessages",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemForward =
        addConfig(
            "MediaViewerMenuItemForward",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemNoQuoteForward =
        addConfig(
            "MediaViewerMenuItemNoQuoteForward",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemCopyFrame =
        addConfig(
            "MediaViewerMenuItemCopyFrame",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemCopyPhoto =
        addConfig(
            "MediaViewerMenuItemCopyPhoto",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemSetProfilePhoto =
        addConfig(
            "MediaViewerMenuItemSetProfilePhoto",
            ConfigItem.configTypeBool,
            true
        )
    val mediaViewerMenuItemScanQRCode =
        addConfig(
            "MediaViewerMenuItemScanQRCode",
            ConfigItem.configTypeBool,
            true
        )
    val hideReactions =
        addConfig(
            "HideReactions",
            ConfigItem.configTypeBool,
            false
        )
    val performanceClass =
        addConfig(
            "PerformanceClass",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProvider =
        addConfig(
            "TranscribeProvider",
            ConfigItem.configTypeInt,
            0
        )
    val transcribeProviderCfAccountID =
        addConfig(
            "TranscribeProviderCfAccountID",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderCfApiToken =
        addConfig(
            "TranscribeProviderCfApiToken",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderGeminiApiKey =
        addConfig(
            "TranscribeProviderGeminiApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiApiBase =
        addConfig(
            "TranscribeProviderOpenAiApiBase",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiModel =
        addConfig(
            "TranscribeProviderOpenAiModel",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiApiKey =
        addConfig(
            "TranscribeProviderOpenAiApiKey",
            ConfigItem.configTypeString,
            ""
        )
    val transcribeProviderOpenAiPrompt =
        addConfig(
            "TranscribeProviderOpenAiPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val showReplyInPrivate =
        addConfig(
            "ReplyInPrivate",
            ConfigItem.configTypeBool,
            false
        )
    val transcribeProviderGeminiPrompt =
        addConfig(
            "TranscribeProviderGeminiPrompt",
            ConfigItem.configTypeString,
            ""
        )
    val hideDividers =
        addConfig(
            "HideDividers",
            ConfigItem.configTypeBool,
            false
        )
    val iconReplacements =
        addConfig(
            "IconReplacements",
            ConfigItem.configTypeInt,
            0
        )
    val showCopyAsSticker =
        addConfig(
            "CopyPhotoAsSticker",
            ConfigItem.configTypeBool,
            false
        )
    val showAddToStickers =
        addConfig(
            "AddToStickers",
            ConfigItem.configTypeBool,
            false
        )
    val showAddToFavorites =
        addConfig(
            "AddToFavorites",
            ConfigItem.configTypeBool,
            true
        )
    val showTranslateMessageLLM =
        addConfig(
            "TranslateMessageLLM",
            ConfigItem.configTypeBool,
            false
        )
    val shortcutsAdministrators =
        addConfig(
            "ChannelAdministrators",
            ConfigItem.configTypeBool,
            false
        )
    val shortcutsRecentActions =
        addConfig(
            "EventLog",
            ConfigItem.configTypeBool,
            true
        )
    val shortcutsStatistics =
        addConfig(
            "Statistics",
            ConfigItem.configTypeBool,
            false
        )
    val shortcutsPermissions =
        addConfig(
            "ChannelPermissions",
            ConfigItem.configTypeBool,
            false
        )
    val shortcutsMembers =
        addConfig(
            "GroupMembers",
            ConfigItem.configTypeBool,
            false
        )
    val leftBottomButton =
        addConfig(
            "LeftBottomButtonAction",
            ConfigItem.configTypeInt,
            0
        )
    val showTextMonoCode =
        addConfig(
            "TextMonoCode",
            ConfigItem.configTypeBool,
            true
        )
    val showCopyLink =
        addConfig(
            "CopyLink",
            ConfigItem.configTypeBool,
            true
        )
    val preferCommonGroupsTab =
        addConfig(
            "PreferCommonGroupsTab",
            ConfigItem.configTypeBool,
            true
        )
    val groupedMessageMenu =
        addConfig(
            "GroupedMessageMenu",
            ConfigItem.configTypeBool,
            true
        )
    val autoUpdateChannel =
        addConfig(
            "AutoUpdateChannel",
            ConfigItem.configTypeInt,
            1 // 0: off; 1: release; 2: beta
        )
    val premiumItemEmojiStatus =
        addConfig(
            "PremiumItemEmojiStatus",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemEmojiInReplies =
        addConfig(
            "PremiumItemEmojiInReplies",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemCustomColorInReplies =
        addConfig(
            "PremiumItemCustomColorInReplies",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemCustomWallpaper =
        addConfig(
            "PremiumItemCustomWallpaper",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemVideoAvatar =
        addConfig(
            "PremiumItemVideoAvatar",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemStarInReactions =
        addConfig(
            "PremiumItemStarInReactions",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemStickerEffects =
        addConfig(
            "PremiumItemStickerEffects",
            ConfigItem.configTypeBool,
            true
        )
    val premiumItemBoosts =
        addConfig(
            "PremiumItemBoosts",
            ConfigItem.configTypeBool,
            true
        )
    val switchStyle =
        addConfig(
            "SwitchStyle",
            ConfigItem.configTypeInt,
            0 // 0: default; 1: Modern
        )
    val sliderStyle =
        addConfig(
            "SliderStyle",
            ConfigItem.configTypeInt,
            0 // 0: default; 1: Modern
        )
    val ignoreUnreadCount =
        addConfig(
            "IgnoreUnreadCount",
            ConfigItem.configTypeInt,
            getIgnoreMutedCountLegacy()
        )
    val markdownParser =
        addConfig(
            "MarkdownParser",
            ConfigItem.configTypeInt,
            NekoConfig.MARKDOWN_PARSER_NEKO
        )
    val defaultScheduledTime =
        addConfig(
            "DefaultScheduledTime",
            ConfigItem.configTypeInt,
            10
        )
    val keepTranslatorPreferences =
        addConfig(
            "KeepTranslatorPreferences",
            ConfigItem.configTypeBool,
            false
        )
    val usePinnedReactionsChats =
        addConfig(
            "UsePinnedReactionsChats",
            ConfigItem.configTypeBool,
            false
        )
    val pinnedReactionsChats =
        addConfig(
            "PinnedReactionsChats",
            ConfigItem.configTypeString,
            "[]"
        )
    val usePinnedReactionsChannels =
        addConfig(
            "UsePinnedReactionsChannels",
            ConfigItem.configTypeBool,
            false
        )
    val pinnedReactionsChannels =
        addConfig(
            "PinnedReactionsChannels",
            ConfigItem.configTypeString,
            "[]"
        )
    val hideStoriesFromHeader =
        addConfig(
            "HideStoriesFromHeader",
            ConfigItem.configTypeBool,
            true
        )
    val disableAvatarBlur =
        addConfig(
            "DisableAvatarBlur",
            ConfigItem.configTypeBool,
            false
        )
    val disableInAppBrowserGestures =
        addConfig(
            "DisableInAppBrowserGestures",
            ConfigItem.configTypeBool,
            false
        )
    val idDcType =
        addConfig(
            "IdDcType",
            ConfigItem.configTypeInt,
            1
        )
    val fixLinkPreview =
        addConfig(
            "FixLinkPreview",
            ConfigItem.configTypeBool,
            true
        )
    val showAddToBookmark =
        addConfig(
            "ShowAddToBookmark",
            ConfigItem.configTypeBool,
            false
        )
    val sortByUnread =
        addConfig(
            "SortByUnread",
            ConfigItem.configTypeBool,
            false
        )
    val cameraInVideoMessages =
        addConfig(
            "CameraInVideoMessages",
            ConfigItem.configTypeInt,
            1 // 0: front; 1: rear; 2: ask
        )
    val showCopyFrame =
        addConfig(
            "MessageMenuCopyFrame",
            ConfigItem.configTypeBool,
            false
        )
    val deleteChatForBothSides =
        addConfig(
            "DeleteChatForBothSides",
            ConfigItem.configTypeBool,
            true
        )
    val backAnimationStyle =
        addConfig(
            "BackAnimationStyle",
            ConfigItem.configTypeInt,
            0 // 0: Classic, 1: Spring, 2: Predictive Back
        )
    val mainTabsHideTitles =
        addConfig(
            "MainTabsHideTitles",
            ConfigItem.configTypeBool,
            false
        )
    val mainTabsHideContacts =
        addConfig(
            "MainTabsHideContacts",
            ConfigItem.configTypeBool,
            false
        )
    val showNotificationPreviewWhenLocked =
        addConfig(
            "ShowNotificationPreviewWhenLocked",
            ConfigItem.configTypeBool,
            false
        )
    val strokeOnViews =
        addConfig(
            "StrokeOnViews",
            ConfigItem.configTypeBool,
            true
        )
    val hideBottomNavigationBar =
        addConfig(
            "HideBottomNavigationBar",
            ConfigItem.configTypeBool,
            false
        )
    val hideDialogsSearchField =
        addConfig(
            "HideDialogsSearchField",
            ConfigItem.configTypeBool,
            false
        )
    val deepLTranslateKey =
        addConfig(
            "DeepLTranslateKey",
            ConfigItem.configTypeString,
            ""
        )
    val showLastVisitInOwnProfile =
        addConfig(
            "ShowLastVisitInOwnProfile",
            ConfigItem.configTypeBool,
            false
        )
    val lastAppOpenTime =
        addConfig(
            "LastAppOpenTime",
            ConfigItem.configTypeLong,
            0L
        )

    val chatHeaderDesign =
        addConfig(
            "ChatHeaderDesign",
            ConfigItem.configTypeInt,
            AREA_DESIGN_LATEST
        )

    /**
     * The shell design: the account/settings menu AND the bottom of the chat move
     * together. They were one redesign upstream (12.4.0 replaced the side drawer with
     * the tab bar in the same release train that floated the composer into an island),
     * so splitting them only ever produced half-migrated layouts.
     */
    val navigationDesign =
        addConfig(
            "NavigationDesign",
            ConfigItem.configTypeInt,
            AREA_DESIGN_LATEST
        )

    // Which rows the 12.3.1 side drawer shows. Defaults match what that release listed
    // out of the box; the Nagram-only extras stay off so the drawer opens looking stock.
    val drawerItemMyProfile =
        addConfig(
            "DrawerItemMyProfile",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemSetEmojiStatus =
        addConfig(
            "DrawerItemSetEmojiStatus",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemArchivedChats =
        addConfig(
            "DrawerItemArchivedChats",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemNewGroup =
        addConfig(
            "DrawerItemNewGroup",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemNewChannel =
        addConfig(
            "DrawerItemNewChannel",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemContacts =
        addConfig(
            "DrawerItemContacts",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemCalls =
        addConfig(
            "DrawerItemCalls",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemSaved =
        addConfig(
            "DrawerItemSaved",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemSettings =
        addConfig(
            "DrawerItemSettings",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemNSettings =
        addConfig(
            "DrawerItemNSettings",
            ConfigItem.configTypeBool,
            true
        )
    val drawerItemBrowser =
        addConfig(
            "DrawerItemBrowser",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemQrLogin =
        addConfig(
            "DrawerItemQrLogin",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemSessions =
        addConfig(
            "DrawerItemSessions",
            ConfigItem.configTypeBool,
            false
        )
    val drawerItemRestartApp =
        addConfig(
            "DrawerItemRestartApp",
            ConfigItem.configTypeBool,
            false
        )

    // --- SovietGram sync backend ---
    // Legacy single-token slot, kept only so an existing install can be migrated out of it once
    // (see SovietGramTokenStore). A token embeds the telegram id it was issued for, so one slot
    // could only ever authenticate one logged-in account — every other account stayed anonymous
    // and silently unsynced. Emptied by the migration; read nowhere else.
    val sovietGramApiToken =
        addConfig(
            "SovietGramApiToken",
            ConfigItem.configTypeString,
            ""
        )
    // Auto-selected fastest base URL from the remote metadata channel. Refreshed each launch;
    // may be empty until the first successful pick, in which case sync stays silent.
    val sovietGramApiServer =
        addConfig(
            "SovietGramApiServer",
            ConfigItem.configTypeString,
            ""
        )
    val sovietGramApiServerPickedAt =
        addConfig(
            "SovietGramApiServerPickedAt",
            ConfigItem.configTypeLong,
            0L
        )
    // High-water-mark id of the last gift consumed from GET /v1/gifts/inbox. Sent back as the
    // `since` cursor so re-polling never re-materialises a gift already delivered into a chat.
    // Legacy single-account slot; migrated into SovietGramGiftCursors once, then unused.
    val sovietGramGiftInboxCursor =
        addConfig(
            "SovietGramGiftInboxCursor",
            ConfigItem.configTypeLong,
            0L
        )

    // Per-account sync state, each a JSON object keyed by the account's telegram id as a string.
    // Every one of these is per-account for the same reason: the API identifies the caller purely
    // by the token, so state that belongs to one logged-in account must never be read for another.
    //   SovietGramApiTokens    {"<telegram_id>": "<token>"}   the token issued to that account
    //   SovietGramGiftCursors  {"<telegram_id>": <gift id>}   that account's inbox high-water mark
    //   SovietGramAuthAttempts {"<telegram_id>": <epoch ms>}  when we last sent it a /start
    //   SovietGramAuthInstalls {"<telegram_id>": <stamp>}     APK install its token was checked for
    // The attempt map is what makes the handshake idempotent across launches: without it every
    // start of the app re-ran /start for any account still missing a token.
    // The install map is the opposite lever: it holds the package's lastUpdateTime, so a reinstall
    // or an update — the two events that can invalidate stored state — buys every account exactly
    // one fresh verification pass instead of being swallowed by the retry window.
    val sovietGramApiTokens =
        addConfig(
            "SovietGramApiTokens",
            ConfigItem.configTypeString,
            ""
        )
    val sovietGramGiftCursors =
        addConfig(
            "SovietGramGiftCursors",
            ConfigItem.configTypeString,
            ""
        )
    val sovietGramAuthAttempts =
        addConfig(
            "SovietGramAuthAttempts",
            ConfigItem.configTypeString,
            ""
        )
    val sovietGramAuthInstalls =
        addConfig(
            "SovietGramAuthInstalls",
            ConfigItem.configTypeString,
            ""
        )

    // Per-account snapshots of the fake-identity settings — fake premium, the Fragment number and
    // usernames, the fake Stars/TON balances and the whole Custom Profile look:
    //   SovietGramAccountScopes {"<telegram_id>": {"<config key>": <value>, ...}}
    // Those settings live in ordinary global config items, read from hundreds of places, so they
    // cannot be made per-account at the point of use without touching every one of those reads.
    // Instead exactly one account's values are live at a time: switching account saves the outgoing
    // account's values in here and loads the incoming account's over the globals. Every read site,
    // and every settings row bound to a config item, keeps working unchanged and now sees only the
    // current account's identity — which is what "enable it on the account I'm in" means.
    // See SovietGramAccountScope, which owns the format and is the only thing that writes here.
    val sovietGramAccountScopes =
        addConfig(
            "SovietGramAccountScopes",
            ConfigItem.configTypeString,
            ""
        )

    @JvmStatic
    fun isLegacyChatHeader(): Boolean = chatHeaderDesign.Int() == AREA_DESIGN_12_3_1

    @JvmStatic
    fun isLegacyNavigation(): Boolean = navigationDesign.Int() == AREA_DESIGN_12_3_1

    /**
     * Single source of truth for "the bottom tab bar is not shown". Both the explicit
     * HideBottomNavigationBar switch and the 12.3.1 navigation design map onto it: in
     * 12.3.1 there was no tab bar and the side drawer carried every destination.
     */
    @JvmStatic
    fun hideBottomTabs(): Boolean = hideBottomNavigationBar.Bool() || isLegacyNavigation()

    /** Bound to the shell design — see [navigationDesign]. */
    @JvmStatic
    fun isLegacyChatBottom(): Boolean = isLegacyNavigation()

    /**
     * The swipe-from-the-left drawer only exists in the 12.3.1 shell; in the current
     * shell the same destinations live in the tab bar, so opening it there would give
     * two competing navigations.
     */
    @JvmStatic
    fun useSideDrawer(): Boolean = isLegacyNavigation()

    val preferredTranslateTargetLangList = ArrayList<String>()
    fun updatePreferredTranslateTargetLangList() {
        AndroidUtilities.runOnUIThread({
            preferredTranslateTargetLangList.clear()
            val str = preferredTranslateTargetLang.String().trim()

            if (str.isEmpty()) return@runOnUIThread

            val languages = str.replace('-', '_').split(",")
            if (languages.isEmpty() || languages[0].trim().isEmpty()) return@runOnUIThread

            languages.forEach { lang ->
                preferredTranslateTargetLangList.add(lang.trim().lowercase())
            }
        }, 1000)
    }

    private fun getIgnoreMutedCountLegacy(): Int {
        return when {
            getPreferences().getBoolean(
                "IgnoreFolderCount", false
            ) -> NekoConfig.DIALOG_FILTER_EXCLUDE_ALL

            getPreferences().getBoolean(
                "IgnoreMutedCount", true
            ) -> NekoConfig.DIALOG_FILTER_EXCLUDE_MUTED

            else -> NekoConfig.DIALOG_FILTER_EXCLUDE_NONE
        }
    }

    private fun fixConfig() {
        if (ApplicationLoader.applicationContext == null) {
            return
        }
        if (!translatorModeWithOriginalMigrated.Bool()) {
            if (getPreferences().contains(translatorMode.key)) {
                translatorMode.setConfigInt(
                    when (translatorMode.Int()) {
                        0 -> 1
                        1 -> 0
                        else -> 0
                    }
                )
            }
            translatorModeWithOriginalMigrated.setConfigBool(true)
        }
        if (!notificationIconDefaultMigrated.Bool()) {
            if (notificationIcon.Int() == 1) {
                notificationIcon.setConfigInt(4)
            }
            notificationIconDefaultMigrated.setConfigBool(true)
        }
        if (translatorMode.Int() !in 0..2) {
            translatorMode.setConfigInt(0)
        }
        // The Material Design 3 look (value 2) is gone. An install that had it selected would
        // otherwise index past the end of the two-entry selector, so fold it onto Modern.
        if (switchStyle.Int() !in 0..1) {
            switchStyle.setConfigInt(1)
        }
        if (sliderStyle.Int() !in 0..1) {
            sliderStyle.setConfigInt(1)
        }
        if (!getPreferences().contains(idDcType.key) && !getPreferences().getBoolean(
                "ShowIdAndDc", true
            )
        ) {
            idDcType.setConfigInt(0)
        }
        if (!getPreferences().contains(cameraInVideoMessages.key)) {
            val legacyRear = getPreferences().getBoolean("RearVideoMessages", false)
            cameraInVideoMessages.setConfigInt(if (legacyRear) 1 else 0)
        }
        if (!getPreferences().contains(backAnimationStyle.key) &&
            getPreferences().contains("SpringAnimation")
        ) {
            val legacySpring = getPreferences().getBoolean("SpringAnimation", false)
            if (legacySpring) {
                backAnimationStyle.setConfigInt(1) // SPRING
            }
            getPreferences().edit { remove("SpringAnimation") }
        }
        if (!getPreferences().contains(strokeOnViews.key)) {
            strokeOnViews.changed(SharedConfig.getDevicePerformanceClass() != SharedConfig.PERFORMANCE_CLASS_LOW)
        }

        val mainPreferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE)
        if (!mainPreferences.contains("photoHighQualityDefault") && getPreferences().contains("SendHighQualityPhoto")) {
            val highQuality = getPreferences().getBoolean("SendHighQualityPhoto", true)
            mainPreferences.edit {
                putBoolean("photoHighQualityDefault", highQuality)
            }
            SharedConfig.photoHighQualityDefault = highQuality
        }

        val currentLlmApiUrl = llmApiUrl.String()
        val normalizedLlmApiUrl = LlmUrlNormalizer.normalizeBaseUrl(currentLlmApiUrl)
        if (normalizedLlmApiUrl != currentLlmApiUrl) {
            llmApiUrl.setConfigString(normalizedLlmApiUrl)
        }
    }

    private fun resetInvalidConfig(o: ConfigItem, e: RuntimeException) {
        val key = if (o is ConfigItemKeyLinked) o.keyLinked.key else o.key
        FileLog.e("Invalid config value for $key", e)
        o.value = o.defaultValue
        getPreferences().edit { remove(key) }
    }

    private fun addConfig(
        k: String, t: Int, d: Any?
    ): ConfigItem {
        val a = ConfigItem(
            k, t, d
        )
        configs.add(
            a
        )
        return a
    }

    @Suppress("SameParameterValue")
    private fun addConfig(
        k: String, t: ConfigItem, d: Int, e: Any?
    ): ConfigItem {
        val a = ConfigItemKeyLinked(
            k,
            t,
            d,
            e,
        )
        configs.add(
            a
        )
        return a
    }

    fun loadConfig(
        force: Boolean
    ) {
        synchronized(
            sync
        ) {
            if (configLoaded && !force) {
                return
            }
            if (ApplicationLoader.applicationContext == null) {
                return
            }
            for (i in configs.indices) {
                val o = configs[i]
                try {
                    if (o.type == ConfigItem.configTypeBool) {
                        o.value = getPreferences().getBoolean(
                            o.key, o.defaultValue as Boolean
                        )
                    }
                    if (o.type == ConfigItem.configTypeInt) {
                        o.value = getPreferences().getInt(
                            o.key, o.defaultValue as Int
                        )
                    }
                    if (o.type == ConfigItem.configTypeLong) {
                        o.value = getPreferences().getLong(
                            o.key, (o.defaultValue as Long)
                        )
                    }
                    if (o.type == ConfigItem.configTypeFloat) {
                        o.value = getPreferences().getFloat(
                            o.key, (o.defaultValue as Float)
                        )
                    }
                    if (o.type == ConfigItem.configTypeString) {
                        o.value = getPreferences().getString(
                            o.key, o.defaultValue as String
                        )
                    }
                    if (o.type == ConfigItem.configTypeSetInt) {
                        val ss = getPreferences().getStringSet(
                            o.key, HashSet()
                        )
                        val si = HashSet<Int>()
                        for (s in ss!!) {
                            si.add(
                                s.toInt()
                            )
                        }
                        o.value = si
                    }
                    if (o.type == ConfigItem.configTypeMapIntInt) {
                        val cv = getPreferences().getString(
                            o.key, ""
                        )
                        // Log.e("NC", String.format("Getting pref %s val %s", o.key, cv));
                        if (cv!!.isEmpty()) {
                            o.value = HashMap<Int, Int>()
                        } else {
                            try {
                                val data = Base64.decode(
                                    cv, Base64.DEFAULT
                                )
                                val ois = ObjectInputStream(
                                    ByteArrayInputStream(
                                        data
                                    )
                                )
                                o.value = ois.readObject() as HashMap<*, *>
                                if (o.value == null) {
                                    o.value = HashMap<Int, Int>()
                                }
                                ois.close()
                            } catch (_: Exception) {
                                o.value = HashMap<Int, Int>()
                            }
                        }
                    }
                    if (o.type == ConfigItem.configTypeBoolLinkInt) {
                        o as ConfigItemKeyLinked
                        o.changedFromKeyLinked(getPreferences().getInt(o.keyLinked.key, 0))
                    }
                } catch (e: ClassCastException) {
                    resetInvalidConfig(o, e)
                } catch (e: NumberFormatException) {
                    resetInvalidConfig(o, e)
                }
            }
            configLoaded = true
        }
    }

    fun getConfigTypes(): Map<String, Int> {
        synchronized(sync) {
            return configs.associate { it.key to it.type }
        }
    }

    init {
        init()
    }

}
