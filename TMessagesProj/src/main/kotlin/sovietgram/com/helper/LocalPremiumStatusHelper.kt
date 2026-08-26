package sovietgram.com.helper

import androidx.core.content.edit
import com.google.gson.Gson
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import tw.nekomimi.nekogram.NekoConfig
import sovietgram.com.NaConfig

data class LocalEmojiStatusData(
    var documentId: Long?, var until: Int?
)

object LocalPremiumStatusHelper {
    const val KEY_PREFIX = "useLocalEmojiStatusData_"

    private val dataMap = mutableMapOf<Long, LocalEmojiStatusData?>()
    private val loadedUsers = mutableSetOf<Long>()

    @JvmStatic
    fun getDocumentId(user: TLRPC.User?): Long? {
        if (!NekoConfig.localPremium.Bool()) return null
        if (user == null || !isLocalUser(user.id)) return null

        val data = getDataForUser(user.id) ?: return null
        val until = data.until ?: 0
        if (until != 0 && until <= (System.currentTimeMillis() / 1000).toInt()) {
            return null
        }
        return data.documentId
    }

    private fun getDataForUser(userId: Long): LocalEmojiStatusData? {
        if (userId == 0L) return null
        initForUser(userId)
        return dataMap[userId]
    }

    /**
     * True only for the account the local-premium settings currently belong to. Every logged-in
     * account used to qualify, so one account's fake emoji status could appear on another's own
     * profile. See SovietGramAccountScope.
     */
    private fun isLocalUser(userId: Long): Boolean =
        tw.nekomimi.nekogram.helpers.SovietGramAccountScope.isOwner(userId)

    private fun getCurrentUserId(): Long {
        return UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId()
    }

    @JvmStatic
    fun initForUser(userId: Long, force: Boolean = false) {
        if (!force && loadedUsers.contains(userId)) return
        loadedUsers.add(userId)

        try {
            val gson = Gson()
            val userKey = KEY_PREFIX + userId

            var jsonStr = NaConfig.getPreferences().getString(userKey, null)

            if (jsonStr.isNullOrEmpty()) {
                val legacyJson = NaConfig.useLocalEmojiStatusData.String()
                if (legacyJson.isNotEmpty()) {
                    jsonStr = legacyJson
                    NaConfig.getPreferences().edit { putString(userKey, jsonStr) }
                }
            }

            dataMap[userId] = if (!jsonStr.isNullOrEmpty()) {
                gson.fromJson(jsonStr, LocalEmojiStatusData::class.java)
            } else {
                null
            }
        } catch (_: Exception) {
            dataMap[userId] = null
        }
    }

    @JvmStatic
    fun apply(status: TLRPC.EmojiStatus?) {
        if (!NekoConfig.localPremium.Bool()) return

        val userId = getCurrentUserId()
        if (userId == 0L) return

        val userKey = KEY_PREFIX + userId

        if (status == null || status is TLRPC.TL_emojiStatusEmpty) {
            dataMap[userId] = null
            NaConfig.getPreferences().edit { putString(userKey, "") }
            return
        }

        var documentId: Long? = null
        var until: Int? = null
        when (status) {
            is TLRPC.TL_emojiStatus -> {
                documentId = status.document_id
                until = if ((status.flags and 1) != 0) status.until else null
            }

            is TLRPC.TL_emojiStatusCollectible -> {
                documentId = status.document_id
                until = if ((status.flags and 1) != 0) status.until else null
            }
        }

        val localData = LocalEmojiStatusData(documentId, until)
        dataMap[userId] = localData
        NaConfig.getPreferences().edit { putString(userKey, Gson().toJson(localData)) }
    }
}
