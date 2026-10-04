package sovietgram.com.maxui

import android.util.SparseIntArray
import org.telegram.messenger.AndroidUtilities
import org.telegram.ui.ActionBar.Theme
import sovietgram.com.NaConfig

/**
 * "Custom Interface": the MAX messenger's look on top of Telegram.
 *
 * Telegram asks [Theme.getColor] for every colour it draws; while the Max interface is on, the colours that
 * have a MAX counterpart are answered from MAX's own palette ([MaxPalette], read off its theme tables)
 * instead of from the Telegram theme. The mapping from Telegram's keys to MAX's tokens is [MAP].
 */
object MaxInterface {

    const val INTERFACE_DEFAULT = 0
    const val INTERFACE_MAX = 1

    /** Read on every colour lookup, so it is a plain field. */
    @JvmField
    @Volatile
    var active = false

    private var way = -1
    private var dark = false
    private var table = IntArray(0)

    /** Telegram colour key -> MAX token in the low 16 bits, and (alpha + 1) in the high bits when the alpha is forced. */
    private val map = SparseIntArray()

    @JvmStatic
    fun isMax(): Boolean = active

    @JvmStatic
    fun colorWay(): Int = NaConfig.maxColorWay.Int().coerceIn(0, MaxPalette.WAYS.size - 1)

    /** Names of the colour ways, as shown in the settings. */
    @JvmStatic
    fun colorWayNames(): Array<String> = MaxPalette.WAYS

    /** Reads the setting. Called at start and whenever it changes. */
    @JvmStatic
    fun reload() {
        active = NaConfig.customInterface.Int() == INTERFACE_MAX
        way = -1
        // MAX's bubbles have 16dp corners. Only the value in memory is changed; the user's own choice stays in the
        // preferences and comes back when the Max interface is switched off.
        org.telegram.messenger.SharedConfig.bubbleRadius = if (active) 16 else
            org.telegram.messenger.MessagesController.getGlobalMainSettings().getInt("bubbleRadius", 17)
    }

    /** The settings changed: re-read them and have every screen take the colours again. */
    @JvmStatic
    fun onSettingsChanged() {
        reload()
        AndroidUtilities.runOnUIThread { Theme.refreshThemeColors() }
    }

    /** A MAX token's colour for the current theme, for drawing that has no Telegram colour key. */
    @JvmStatic
    fun tokenColor(token: Int): Int {
        val currentDark = Theme.isCurrentThemeDark()
        val currentWay = colorWay()
        if (currentWay != way || currentDark != dark || table.isEmpty()) {
            way = currentWay
            dark = currentDark
            table = MaxPalette.table(way, dark)
        }
        return table[token]
    }

    @JvmStatic
    fun has(key: Int): Boolean {
        ensureMap()
        return map.indexOfKey(key) >= 0
    }

    @JvmStatic
    fun color(key: Int): Int {
        val entry = map.get(key, -1)
        if (entry == -1) {
            return 0
        }
        val currentDark = Theme.isCurrentThemeDark()
        if (key == Theme.key_chats_unreadCounterMuted || key == Theme.key_topics_unreadCounterMuted) {
            // The chat list paints this fill opaque, so MAX's translucent muted counter becomes the grey it looks like
            // over its surface, with the same white digits as the other counter.
            return if (currentDark) 0xFF5A5B60.toInt() else 0xFFA9ABB1.toInt()
        }
        val currentWay = colorWay()
        if (currentWay != way || currentDark != dark || table.isEmpty()) {
            way = currentWay
            dark = currentDark
            table = MaxPalette.table(way, dark)
        }
        val token = entry and 0xFFFF
        val value = table[token]
        val alpha = (entry ushr 16) - 1
        return if (alpha >= 0) (value and 0x00FFFFFF) or (alpha shl 24) else value
    }

    private fun ensureMap() {
        if (map.size() == 0) {
            synchronized(map) {
                if (map.size() == 0) {
                    fill()
                }
            }
        }
    }

    private fun put(key: Int, token: Int, alpha: Int = -1) {
        map.put(key, token or ((alpha + 1) shl 16))
    }

    private fun fill() {
        val t = MaxTokens
        // ---- surfaces and dividers
        put(Theme.key_windowBackgroundWhite, t.BACKGROUND_PRIMARY)
        put(Theme.key_windowBackgroundGray, t.BACKGROUND_SURFACE)
        put(Theme.key_graySection, t.BACKGROUND_SURFACE)
        put(Theme.key_graySectionText, t.TEXT_SECONDARY)
        // MAX draws no lines between list rows.
        put(Theme.key_divider, t.DIVIDER_PRIMARY, 0)
        put(Theme.key_listSelector, t.TEXT_PRIMARY, 0x14)
        put(Theme.key_dialogBackground, t.FLOAT_MODAL)
        put(Theme.key_dialogBackgroundGray, t.BACKGROUND_SURFACE)
        put(Theme.key_dialogGrayLine, t.DIVIDER_PRIMARY)
        put(Theme.key_dialogShadowLine, t.DIVIDER_SECONDARY)
        put(Theme.key_dialogTopBackground, t.FLOAT_MODAL)
        put(Theme.key_iv_background, t.BACKGROUND_PRIMARY)
        put(Theme.key_iv_backgroundGray, t.BACKGROUND_SURFACE)

        // ---- action bar
        put(Theme.key_actionBarDefault, t.BACKGROUND_PRIMARY)
        put(Theme.key_actionBarDefaultIcon, t.ICON_PRIMARY)
        put(Theme.key_actionBarDefaultTitle, t.TEXT_PRIMARY)
        put(Theme.key_actionBarDefaultSubtitle, t.TEXT_SECONDARY)
        put(Theme.key_actionBarDefaultSelector, t.TEXT_PRIMARY, 0x14)
        put(Theme.key_actionBarWhiteSelector, t.TEXT_PRIMARY, 0x14)
        put(Theme.key_actionBarDefaultSearch, t.TEXT_PRIMARY)
        put(Theme.key_actionBarDefaultSearchPlaceholder, t.TEXT_MUTE)
        put(Theme.key_actionBarDefaultSubmenuBackground, t.FLOAT_MODAL)
        put(Theme.key_actionBarDefaultSubmenuItem, t.TEXT_PRIMARY)
        put(Theme.key_actionBarDefaultSubmenuItemIcon, t.ICON_SECONDARY)
        put(Theme.key_actionBarDefaultSubmenuSeparator, t.DIVIDER_PRIMARY)
        put(Theme.key_actionBarActionModeDefault, t.BACKGROUND_PRIMARY)
        put(Theme.key_actionBarActionModeDefaultTop, t.BACKGROUND_PRIMARY)
        put(Theme.key_actionBarActionModeDefaultIcon, t.ICON_PRIMARY)
        put(Theme.key_actionBarActionModeDefaultSelector, t.TEXT_PRIMARY, 0x14)
        put(Theme.key_actionBarTabActiveText, t.TEXT_THEMED)
        put(Theme.key_actionBarTabUnactiveText, t.TEXT_TERTIARY)
        put(Theme.key_actionBarTabLine, t.STROKE_THEMED)
        put(Theme.key_actionBarTabSelector, t.TEXT_PRIMARY, 0x14)

        // ---- texts on the main surface
        put(Theme.key_windowBackgroundWhiteBlackText, t.TEXT_PRIMARY)
        put(Theme.key_windowBackgroundWhiteGrayText, t.TEXT_TERTIARY)
        put(Theme.key_windowBackgroundWhiteGrayText2, t.TEXT_SECONDARY)
        put(Theme.key_windowBackgroundWhiteGrayText3, t.TEXT_SECONDARY)
        put(Theme.key_windowBackgroundWhiteGrayText4, t.TEXT_TERTIARY)
        put(Theme.key_windowBackgroundWhiteGrayText5, t.TEXT_SECONDARY)
        put(Theme.key_windowBackgroundWhiteGrayText6, t.TEXT_SECONDARY)
        put(Theme.key_windowBackgroundWhiteGrayText7, t.TEXT_MUTE)
        put(Theme.key_windowBackgroundWhiteGrayText8, t.TEXT_MUTE)
        put(Theme.key_windowBackgroundWhiteHintText, t.TEXT_MUTE)
        put(Theme.key_windowBackgroundWhiteGrayIcon, t.ICON_SECONDARY)
        put(Theme.key_windowBackgroundWhiteBlueText, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteBlueText2, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteBlueText3, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteBlueText4, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteBlueText5, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteBlueText6, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteBlueText7, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteBlueHeader, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteBlueButton, t.BUTTON_PRIMARY)
        put(Theme.key_windowBackgroundWhiteBlueIcon, t.ICON_THEMED)
        put(Theme.key_windowBackgroundWhiteValueText, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteLinkText, t.TEXT_THEMED)
        put(Theme.key_windowBackgroundWhiteLinkSelection, t.BUTTON_PRIMARY, 0x40)
        put(Theme.key_windowBackgroundWhiteGreenText, t.TEXT_POSITIVE)
        put(Theme.key_windowBackgroundWhiteGreenText2, t.TEXT_POSITIVE)
        put(Theme.key_windowBackgroundWhiteRedText3, t.TEXT_NEGATIVE)
        put(Theme.key_windowBackgroundWhiteRedText4, t.TEXT_NEGATIVE)
        put(Theme.key_text_RedRegular, t.TEXT_NEGATIVE)
        put(Theme.key_text_RedBold, t.TEXT_NEGATIVE)
        put(Theme.key_windowBackgroundWhiteInputField, t.STROKE_TERTIARY)
        put(Theme.key_windowBackgroundWhiteInputFieldActivated, t.STROKE_THEMED)

        // ---- controls
        put(Theme.key_switchTrack, t.CONTROLS_INACTIVE)
        put(Theme.key_switchTrackChecked, t.CONTROLS_ACTIVE)
        put(Theme.key_switchTrackBlue, t.CONTROLS_INACTIVE)
        put(Theme.key_switchTrackBlueChecked, t.CONTROLS_ACTIVE)
        put(Theme.key_switch2Track, t.CONTROLS_INACTIVE)
        put(Theme.key_switch2TrackChecked, t.CONTROLS_ACTIVE)
        put(Theme.key_checkbox, t.BUTTON_PRIMARY)
        put(Theme.key_checkboxCheck, t.BUTTON_PRIMARY_CONTRAST)
        put(Theme.key_checkboxSquareBackground, t.BUTTON_PRIMARY)
        put(Theme.key_checkboxSquareCheck, t.BUTTON_PRIMARY_CONTRAST)
        put(Theme.key_checkboxSquareUnchecked, t.STROKE_SECONDARY)
        put(Theme.key_dialogCheckboxSquareBackground, t.BUTTON_PRIMARY)
        put(Theme.key_dialogCheckboxSquareCheck, t.BUTTON_PRIMARY_CONTRAST)
        put(Theme.key_dialogCheckboxSquareUnchecked, t.STROKE_SECONDARY)
        put(Theme.key_dialogRoundCheckBox, t.BUTTON_PRIMARY)
        put(Theme.key_dialogRoundCheckBoxCheck, t.BUTTON_PRIMARY_CONTRAST)
        put(Theme.key_dialogRadioBackground, t.STROKE_SECONDARY)
        put(Theme.key_dialogRadioBackgroundChecked, t.BUTTON_PRIMARY)
        put(Theme.key_dialogLineProgress, t.BUTTON_PRIMARY)
        put(Theme.key_dialogLineProgressBackground, t.CONTROLS_INACTIVE)
        put(Theme.key_featuredStickers_addButton, t.BUTTON_PRIMARY)
        put(Theme.key_featuredStickers_addButtonPressed, t.STATES_BUTTON_PRIMARY_PRESSED)
        put(Theme.key_featuredStickers_buttonText, t.BUTTON_PRIMARY_CONTRAST)
        put(Theme.key_featuredStickers_unread, t.BUTTON_PRIMARY)

        // ---- dialogs
        put(Theme.key_dialogTextBlack, t.TEXT_PRIMARY)
        put(Theme.key_dialogTextLink, t.TEXT_THEMED)
        put(Theme.key_dialogLinkSelection, t.BUTTON_PRIMARY, 0x40)
        put(Theme.key_dialogTextRed, t.TEXT_NEGATIVE)
        put(Theme.key_dialogTextBlue, t.TEXT_THEMED)
        put(Theme.key_dialogTextBlue2, t.TEXT_THEMED)
        put(Theme.key_dialogTextBlue4, t.TEXT_THEMED)
        put(Theme.key_dialogTextGray, t.TEXT_SECONDARY)
        put(Theme.key_dialogTextGray2, t.TEXT_SECONDARY)
        put(Theme.key_dialogTextGray3, t.TEXT_SECONDARY)
        put(Theme.key_dialogTextGray4, t.TEXT_TERTIARY)
        put(Theme.key_dialogTextHint, t.TEXT_MUTE)
        put(Theme.key_dialogInputField, t.STROKE_TERTIARY)
        put(Theme.key_dialogInputFieldActivated, t.STROKE_THEMED)
        put(Theme.key_dialogButton, t.TEXT_THEMED)
        put(Theme.key_dialogButtonSelector, t.BUTTON_PRIMARY, 0x1A)
        put(Theme.key_dialogIcon, t.ICON_SECONDARY)
        put(Theme.key_dialogRedIcon, t.ICON_NEGATIVE)
        put(Theme.key_dialogSearchBackground, t.INPUT_BACKGROUND)
        put(Theme.key_dialogSearchHint, t.TEXT_MUTE)
        put(Theme.key_dialogSearchIcon, t.ICON_SECONDARY)
        put(Theme.key_dialogSearchText, t.TEXT_PRIMARY)
        put(Theme.key_dialogFloatingButton, t.BUTTON_PRIMARY)
        put(Theme.key_dialogFloatingButtonPressed, t.STATES_BUTTON_PRIMARY_PRESSED)
        put(Theme.key_dialogFloatingIcon, t.BUTTON_PRIMARY_CONTRAST)

        // ---- chat list
        put(Theme.key_chats_name, t.TEXT_PRIMARY)
        put(Theme.key_chats_nameArchived, t.TEXT_PRIMARY)
        put(Theme.key_chats_secretName, t.TEXT_POSITIVE)
        put(Theme.key_chats_secretIcon, t.ICON_POSITIVE)
        put(Theme.key_chats_message, t.TEXT_SECONDARY)
        put(Theme.key_chats_messageArchived, t.TEXT_SECONDARY)
        put(Theme.key_chats_message_threeLines, t.TEXT_SECONDARY)
        put(Theme.key_chats_draft, t.TEXT_NEGATIVE)
        put(Theme.key_chats_nameMessage, t.TEXT_PRIMARY)
        put(Theme.key_chats_nameMessageArchived, t.TEXT_PRIMARY)
        put(Theme.key_chats_nameMessage_threeLines, t.TEXT_PRIMARY)
        put(Theme.key_chats_attachMessage, t.TEXT_SECONDARY)
        put(Theme.key_chats_actionMessage, t.TEXT_THEMED)
        put(Theme.key_chats_date, t.TEXT_TERTIARY)
        put(Theme.key_chats_pinnedIcon, t.ICON_TERTIARY)
        put(Theme.key_chats_sentCheck, t.ICON_THEMED)
        put(Theme.key_chats_sentReadCheck, t.ICON_THEMED)
        put(Theme.key_chats_sentClock, t.ICON_TERTIARY)
        put(Theme.key_chats_sentError, t.BUTTON_NEGATIVE)
        put(Theme.key_chats_verifiedBackground, t.BUTTON_PRIMARY)
        put(Theme.key_chats_verifiedCheck, t.BUTTON_PRIMARY_CONTRAST)
        put(Theme.key_chats_muteIcon, t.ICON_MUTE)
        put(Theme.key_chats_mentionIcon, t.COUNTER_CONTRAST)
        put(Theme.key_chats_unreadCounter, t.COUNTER_THEMED)
        put(Theme.key_chats_unreadCounterMuted, t.COUNTER_MUTE)
        put(Theme.key_chats_unreadCounterText, t.COUNTER_CONTRAST)
        put(Theme.key_chats_onlineCircle, t.ICON_POSITIVE)
        put(Theme.key_chats_actionBackground, t.BUTTON_PRIMARY)
        put(Theme.key_chats_actionPressedBackground, t.STATES_BUTTON_PRIMARY_PRESSED)
        put(Theme.key_chats_actionIcon, t.BUTTON_PRIMARY_CONTRAST)
        put(Theme.key_chats_tabUnreadActiveBackground, t.COUNTER_THEMED)
        put(Theme.key_chats_tabUnreadUnactiveBackground, t.COUNTER_MUTE)
        put(Theme.key_topics_unreadCounter, t.COUNTER_THEMED)
        put(Theme.key_topics_unreadCounterMuted, t.COUNTER_MUTE)

        // ---- avatars
        put(Theme.key_avatar_text, t.TEXT_PRIMARY_INVERSE_STATIC)
        put(Theme.key_avatar_backgroundRed, t.AVATAR_CHAT_CORAL_A)
        put(Theme.key_avatar_background2Red, t.AVATAR_CHAT_CORAL_B)
        put(Theme.key_avatar_backgroundOrange, t.AVATAR_CHAT_ORANGE_A)
        put(Theme.key_avatar_background2Orange, t.AVATAR_CHAT_ORANGE_B)
        put(Theme.key_avatar_backgroundViolet, t.AVATAR_CHAT_VIOLET_A)
        put(Theme.key_avatar_background2Violet, t.AVATAR_CHAT_VIOLET_B)
        put(Theme.key_avatar_backgroundGreen, t.AVATAR_CHAT_GREEN_A)
        put(Theme.key_avatar_background2Green, t.AVATAR_CHAT_GREEN_B)
        put(Theme.key_avatar_backgroundCyan, t.AVATAR_CHAT_SKY_A)
        put(Theme.key_avatar_background2Cyan, t.AVATAR_CHAT_SKY_B)
        put(Theme.key_avatar_backgroundBlue, t.AVATAR_DARK_SKY_A)
        put(Theme.key_avatar_background2Blue, t.AVATAR_DARK_SKY_B)
        put(Theme.key_avatar_backgroundPink, t.AVATAR_ORCHID_A)
        put(Theme.key_avatar_background2Pink, t.AVATAR_ORCHID_B)

        // ---- chat
        put(Theme.key_chat_wallpaper, t.CHAT_GROUND)
        put(Theme.key_chat_wallpaper_gradient_to1, t.CHAT_GROUND)
        put(Theme.key_chat_wallpaper_gradient_to2, t.CHAT_GROUND)
        put(Theme.key_chat_wallpaper_gradient_to3, t.CHAT_GROUND)
        put(Theme.key_chat_inBubble, t.BUBBLES_INCOMING_BACKGROUND_BUBBLE)
        put(Theme.key_chat_inBubbleSelected, t.BUBBLES_INCOMING_BACKGROUND_BUBBLE)
        put(Theme.key_chat_outBubble, t.BUBBLES_OUTGOING_BACKGROUND_BUBBLE)
        put(Theme.key_chat_outBubbleSelected, t.BUBBLES_OUTGOING_BACKGROUND_BUBBLE)
        put(Theme.key_chat_outBubbleGradient1, t.BUBBLES_OUTGOING_BACKGROUND_BUBBLE)
        put(Theme.key_chat_outBubbleGradient2, t.BUBBLES_OUTGOING_BACKGROUND_BUBBLE)
        put(Theme.key_chat_outBubbleGradient3, t.BUBBLES_OUTGOING_BACKGROUND_BUBBLE)
        put(Theme.key_chat_messageTextIn, t.BUBBLES_INCOMING_TEXT_BODY)
        put(Theme.key_chat_messageTextOut, t.BUBBLES_OUTGOING_TEXT_BODY)
        put(Theme.key_chat_messageLinkIn, t.BUBBLES_INCOMING_TEXT_LINK)
        put(Theme.key_chat_messageLinkOut, t.BUBBLES_OUTGOING_TEXT_LINK)
        put(Theme.key_chat_inTimeText, t.BUBBLES_INCOMING_TEXT_TIME)
        put(Theme.key_chat_inTimeSelectedText, t.BUBBLES_INCOMING_TEXT_TIME)
        put(Theme.key_chat_outTimeText, t.BUBBLES_OUTGOING_TEXT_TIME)
        put(Theme.key_chat_outTimeSelectedText, t.BUBBLES_OUTGOING_TEXT_TIME)
        put(Theme.key_chat_inViews, t.BUBBLES_INCOMING_TEXT_TIME)
        put(Theme.key_chat_inViewsSelected, t.BUBBLES_INCOMING_TEXT_TIME)
        put(Theme.key_chat_outViews, t.BUBBLES_OUTGOING_TEXT_TIME)
        put(Theme.key_chat_outViewsSelected, t.BUBBLES_OUTGOING_TEXT_TIME)
        put(Theme.key_chat_outSentCheck, t.BUBBLES_OUTGOING_ICON_READ_STATUS)
        put(Theme.key_chat_outSentCheckSelected, t.BUBBLES_OUTGOING_ICON_READ_STATUS)
        put(Theme.key_chat_outSentCheckRead, t.BUBBLES_OUTGOING_ICON_READ_STATUS)
        put(Theme.key_chat_outSentCheckReadSelected, t.BUBBLES_OUTGOING_ICON_READ_STATUS)
        put(Theme.key_chat_outSentClock, t.BUBBLES_OUTGOING_ICON_READ_STATUS)
        put(Theme.key_chat_outSentClockSelected, t.BUBBLES_OUTGOING_ICON_READ_STATUS)
        put(Theme.key_chat_inSentClock, t.BUBBLES_INCOMING_ICON_READ_STATUS)
        put(Theme.key_chat_inSentClockSelected, t.BUBBLES_INCOMING_ICON_READ_STATUS)
        put(Theme.key_chat_inReplyLine, t.BUBBLES_INCOMING_TEXT_ACTION)
        put(Theme.key_chat_outReplyLine, t.BUBBLES_OUTGOING_TEXT_REPLY_NAME)
        put(Theme.key_chat_outReplyLine2, t.BUBBLES_OUTGOING_TEXT_REPLY_NAME)
        put(Theme.key_chat_inReplyNameText, t.BUBBLES_INCOMING_TEXT_REPLY_NAME)
        put(Theme.key_chat_outReplyNameText, t.BUBBLES_OUTGOING_TEXT_REPLY_NAME)
        put(Theme.key_chat_inReplyMessageText, t.BUBBLES_INCOMING_TEXT_REPLY_BODY)
        put(Theme.key_chat_outReplyMessageText, t.BUBBLES_OUTGOING_TEXT_REPLY_BODY)
        put(Theme.key_chat_inReplyMediaMessageText, t.BUBBLES_INCOMING_TEXT_REPLY_BODY)
        put(Theme.key_chat_outReplyMediaMessageText, t.BUBBLES_OUTGOING_TEXT_REPLY_BODY)
        put(Theme.key_chat_inForwardedNameText, t.BUBBLES_INCOMING_TEXT_FORWARD_NAME)
        put(Theme.key_chat_outForwardedNameText, t.BUBBLES_OUTGOING_TEXT_FORWARD_NAME)
        put(Theme.key_chat_inViaBotNameText, t.BUBBLES_INCOMING_TEXT_FORWARD_NAME)
        put(Theme.key_chat_outViaBotNameText, t.BUBBLES_OUTGOING_TEXT_FORWARD_NAME)
        put(Theme.key_chat_inSiteNameText, t.BUBBLES_INCOMING_TEXT_ACTION)
        put(Theme.key_chat_outSiteNameText, t.BUBBLES_OUTGOING_TEXT_REPLY_NAME)
        put(Theme.key_chat_inPreviewLine, t.BUBBLES_INCOMING_TEXT_ACTION)
        put(Theme.key_chat_outPreviewLine, t.BUBBLES_OUTGOING_TEXT_REPLY_NAME)
        put(Theme.key_chat_inAudioTitleText, t.BUBBLES_INCOMING_TEXT_BODY)
        put(Theme.key_chat_outAudioTitleText, t.BUBBLES_OUTGOING_TEXT_BODY)
        put(Theme.key_chat_inAudioPerformerText, t.BUBBLES_INCOMING_TEXT_BODY_SECONDARY)
        put(Theme.key_chat_outAudioPerformerText, t.BUBBLES_OUTGOING_TEXT_BODY_SECONDARY)
        put(Theme.key_chat_inAudioDurationText, t.BUBBLES_INCOMING_TEXT_BODY_SECONDARY)
        put(Theme.key_chat_outAudioDurationText, t.BUBBLES_OUTGOING_TEXT_BODY_SECONDARY)
        put(Theme.key_chat_inFileNameText, t.BUBBLES_INCOMING_TEXT_BODY)
        put(Theme.key_chat_outFileNameText, t.BUBBLES_OUTGOING_TEXT_BODY)
        put(Theme.key_chat_inFileInfoText, t.BUBBLES_INCOMING_TEXT_BODY_SECONDARY)
        put(Theme.key_chat_outFileInfoText, t.BUBBLES_OUTGOING_TEXT_BODY_SECONDARY)
        put(Theme.key_chat_inContactNameText, t.BUBBLES_INCOMING_TEXT_BODY)
        put(Theme.key_chat_outContactNameText, t.BUBBLES_OUTGOING_TEXT_BODY)
        put(Theme.key_chat_inContactPhoneText, t.BUBBLES_INCOMING_TEXT_BODY_SECONDARY)
        put(Theme.key_chat_outContactPhoneText, t.BUBBLES_OUTGOING_TEXT_BODY_SECONDARY)
        put(Theme.key_chat_inMenu, t.BUBBLES_INCOMING_ICON_READ_STATUS)
        put(Theme.key_chat_outMenu, t.BUBBLES_OUTGOING_ICON_READ_STATUS)
        put(Theme.key_chat_serviceBackground, t.CAPSULE_BACKGROUND)
        put(Theme.key_chat_serviceText, t.TEXT_PRIMARY_INVERSE_STATIC)
        put(Theme.key_chat_serviceLink, t.TEXT_PRIMARY_INVERSE_STATIC)
        put(Theme.key_chat_serviceIcon, t.TEXT_PRIMARY_INVERSE_STATIC)
        put(Theme.key_chat_goDownButton, t.FLOAT_PRIMARY_FLAT)
        put(Theme.key_chat_goDownButtonCounter, t.COUNTER_CONTRAST)
        put(Theme.key_chat_goDownButtonCounterBackground, t.COUNTER_THEMED)
        put(Theme.key_chat_unreadMessagesStartBackground, t.BACKGROUND_SURFACE)
        put(Theme.key_chat_unreadMessagesStartText, t.TEXT_SECONDARY)
        put(Theme.key_chat_textSelectBackground, t.BUTTON_PRIMARY, 0x40)
        put(Theme.key_chat_messagePanelBackground, t.WRITEBAR_INPUT_FLAT)
        put(Theme.key_chat_messagePanelText, t.WRITEBAR_INPUT_TEXT)
        put(Theme.key_chat_messagePanelHint, t.TEXT_MUTE)
        put(Theme.key_chat_messagePanelCursor, t.BUTTON_PRIMARY)
        put(Theme.key_chat_messagePanelIcons, t.ICON_SECONDARY)
        put(Theme.key_chat_messagePanelSend, t.BUTTON_PRIMARY)
        put(Theme.key_chat_topPanelBackground, t.FLOAT_PRIMARY_FLAT)
        put(Theme.key_chat_topPanelTitle, t.TEXT_PRIMARY)
        put(Theme.key_chat_topPanelMessage, t.TEXT_SECONDARY)
        put(Theme.key_chat_topPanelClose, t.ICON_SECONDARY)
        put(Theme.key_chat_topPanelLine, t.BUTTON_PRIMARY)
        put(Theme.key_chat_replyPanelIcons, t.ICON_THEMED)
        put(Theme.key_chat_replyPanelName, t.TEXT_THEMED)
        put(Theme.key_chat_replyPanelLine, t.BUTTON_PRIMARY)
        put(Theme.key_chat_replyPanelClose, t.ICON_SECONDARY)
        put(Theme.key_chat_emojiPanelBackground, t.WRITEBAR_EMOJI_AREA)
        put(Theme.key_chat_emojiPanelShadowLine, t.DIVIDER_SECONDARY)
        put(Theme.key_chat_emojiPanelIcon, t.ICON_SECONDARY)
        put(Theme.key_chat_emojiBottomPanelIcon, t.ICON_SECONDARY)
        put(Theme.key_chat_emojiPanelIconSelected, t.ICON_THEMED)
        put(Theme.key_chat_botKeyboardButtonBackground, t.BUTTON_SECONDARY)
        put(Theme.key_chat_botKeyboardButtonText, t.TEXT_PRIMARY)

        // ---- main tabs and glass surfaces
        put(Theme.key_glass_defaultIcon, t.ICON_PRIMARY)
        put(Theme.key_glass_defaultText, t.TEXT_PRIMARY)
        put(Theme.key_glass_tabSelected, t.TABBAR_ACTIVE)
        put(Theme.key_glass_tabSelectedText, t.TEXT_THEMED)
        put(Theme.key_glass_tabUnselected, t.TABBAR_INACTIVE)

        // ---- profile
        put(Theme.key_profile_title, t.TEXT_PRIMARY)
        put(Theme.key_profile_status, t.TEXT_SECONDARY)
        put(Theme.key_profile_tabText, t.TEXT_TERTIARY)
        put(Theme.key_profile_tabSelectedText, t.TEXT_PRIMARY)
        put(Theme.key_profile_tabSelectedLine, t.BUTTON_PRIMARY)
        put(Theme.key_profile_verifiedBackground, t.BUTTON_PRIMARY)
        put(Theme.key_profile_verifiedCheck, t.BUTTON_PRIMARY_CONTRAST)

        // ---- the rest
        put(Theme.key_undo_background, t.FLOAT_POPUP_FLAT)
        put(Theme.key_undo_infoColor, t.TEXT_PRIMARY_INVERSE_STATIC)
        put(Theme.key_undo_cancelColor, t.TEXT_PRIMARY_INVERSE_STATIC)
        put(Theme.key_inappPlayerBackground, t.FLOAT_PRIMARY_FLAT)
        put(Theme.key_inappPlayerTitle, t.TEXT_PRIMARY)
        put(Theme.key_inappPlayerPerformer, t.TEXT_SECONDARY)
        put(Theme.key_inappPlayerPlayPause, t.ICON_THEMED)
        put(Theme.key_inappPlayerClose, t.ICON_SECONDARY)
    }
}
