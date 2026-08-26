package sovietgram.com.helper

import androidx.core.content.edit
import com.google.gson.Gson
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.TLRPC
import tw.nekomimi.nekogram.NekoConfig
import sovietgram.com.NaConfig

data class LocalQuoteColorData(
    var colorId: Int?, var emojiId: Long?, var profileColorId: Int?, var profileEmojiId: Long?
)

object LocalPeerColorHelper {
    const val KEY_PREFIX = "useLocalQuoteColorData_"

    private val dataMap = mutableMapOf<Long, LocalQuoteColorData?>()
    private val loadedUsers = mutableSetOf<Long>()

    @JvmStatic
    fun getColorId(user: TLRPC.User): Int? {
        if (!NekoConfig.localPremium.Bool()) return null
        if (!isLocalUser(user.id)) return null
        val data = getDataForUser(user.id) ?: return null
        return data.colorId
    }

    @JvmStatic
    fun getEmojiId(user: TLRPC.User?): Long? {
        if (!NekoConfig.localPremium.Bool()) return null
        if (user == null || !isLocalUser(user.id)) return null
        val data = getDataForUser(user.id) ?: return null
        return data.emojiId
    }

    @JvmStatic
    fun getProfileColorId(user: TLRPC.User): Int? {
        if (!NekoConfig.localPremium.Bool()) return null
        if (!isLocalUser(user.id)) return null
        val data = getDataForUser(user.id) ?: return null
        return data.profileColorId
    }

    @JvmStatic
    fun getProfileEmojiId(user: TLRPC.User?): Long? {
        if (!NekoConfig.localPremium.Bool()) return null
        if (user == null || !isLocalUser(user.id)) return null
        val data = getDataForUser(user.id) ?: return null
        return data.profileEmojiId
    }

    private fun getDataForUser(userId: Long): LocalQuoteColorData? {
        if (userId == 0L) return null
        initForUser(userId)
        return dataMap[userId]
    }

    /**
     * True only for the account the local-premium settings currently belong to. Every logged-in
     * account used to qualify, which — together with the global premium flag these getters read —
     * painted one account's fake colours onto another's own profile. See SovietGramAccountScope.
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
                val legacyJson = NaConfig.useLocalQuoteColorData.String()
                if (legacyJson.isNotEmpty()) {
                    jsonStr = legacyJson
                    NaConfig.getPreferences().edit { putString(userKey, jsonStr) }
                }
            }

            dataMap[userId] = if (!jsonStr.isNullOrEmpty()) {
                gson.fromJson(jsonStr, LocalQuoteColorData::class.java)
            } else {
                null
            }
        } catch (_: Exception) {
            dataMap[userId] = null
        }
    }

    @JvmStatic
    fun apply(colorId: Int, emojiId: Long, profileColorId: Int, profileEmojiId: Long) {
        if (!NekoConfig.localPremium.Bool()) return

        val userId = getCurrentUserId()
        if (userId == 0L) return

        val localData = LocalQuoteColorData(colorId, emojiId, profileColorId, profileEmojiId)
        dataMap[userId] = localData

        val userKey = KEY_PREFIX + userId
        NaConfig.getPreferences().edit { putString(userKey, Gson().toJson(localData)) }
    }
}
