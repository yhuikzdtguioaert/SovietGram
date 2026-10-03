package sovietgram.com.proxy

import android.content.Context
import sovietgram.com.NaConfig

/**
 * The TG WS proxy and the Vless VPN both own Telegram's single local proxy slot, so they can never
 * run together. The settings screen already switches one off when the other goes on, but the two
 * flags are plain preferences: a restored backup, a synced config or an old install can leave both
 * set, and then both services start and fight over the proxy. This keeps the rule in one place.
 */
object BypassGuard {

    private const val LAST_KEY = "bypassLastEnabled"
    private const val VLESS = "vless"
    private const val TGWS = "tgws"

    /** Remembers who was switched on last, so a conflict found later is settled in its favour. */
    @JvmStatic
    fun markVless() = mark(VLESS)

    @JvmStatic
    fun markTgWs() = mark(TGWS)

    private fun mark(who: String) {
        NaConfig.getPreferences().edit().putString(LAST_KEY, who).commit()
    }

    /**
     * Called once at start, before either proxy is brought back up. When both are flagged on, the
     * one switched on last stays and the other is turned off for good.
     *
     * @return true when something had to be switched off
     */
    @JvmStatic
    fun reconcile(context: Context): Boolean {
        if (!XrayController.isEnabled() || !TgWsProxyController.isEnabled()) {
            return false
        }
        val keepVless = NaConfig.getPreferences().getString(LAST_KEY, VLESS) != TGWS
        if (keepVless) {
            TgWsProxyController.setEnabled(false)
            TgWsProxyController.stopService(context)
        } else {
            XrayController.setEnabled(false)
            XrayController.stopService(context)
        }
        return true
    }
}
