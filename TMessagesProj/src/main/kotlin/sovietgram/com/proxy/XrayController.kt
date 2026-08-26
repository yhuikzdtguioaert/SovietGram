package sovietgram.com.proxy

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import org.telegram.messenger.FileLog
import org.telegram.messenger.MessagesController
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import sovietgram.com.NaConfig

object XrayController {
    const val LOCAL_HOST = "127.0.0.1"

    // The local SOCKS port is fixed; it is no longer user-configurable.
    const val SOCKS_PORT = 10808

    /**
     * Whether the tunnel is switched on, read straight out of SharedPreferences.
     *
     * Not [NaConfig.vlessEnabled], because every ConfigItem holds its compile-time
     * default until [org.telegram.messenger.ApplicationLoader.postInitApplication]
     * fills them in — and that runs from the UI, not from Application.onCreate().
     * The OS can construct the service before it: a notification action or a bare
     * re-delivery in a fresh process both land in onStartCommand() with NaConfig
     * still holding defaults. SharedPreferences have no such window.
     */
    @JvmStatic
    fun isEnabled(): Boolean =
        NaConfig.getPreferences().getBoolean(NaConfig.vlessEnabled.key, false)

    /**
     * Whether the ongoing notification may be shown, from SharedPreferences for
     * the same reason as [isEnabled]. Off by default: the service needs the
     * notification, the user does not, so it is only shown to somebody who asked
     * for it. Reading the stale in-memory copy is exactly how a notification the
     * user had switched off came back by itself after the app was restarted, with
     * the settings row still showing "off".
     */
    @JvmStatic
    fun isNotificationEnabled(): Boolean =
        NaConfig.getPreferences().getBoolean(NaConfig.vlessNotificationEnabled.key, false)

    @JvmStatic
    fun savedVlessKey(): String = NaConfig.vlessKey.String().trim()

    @JvmStatic
    fun startFromSettings(context: Context, showToast: Boolean): Boolean {
        val port = SOCKS_PORT
        val key = NaConfig.vlessKey.String().trim()
        if (key.isEmpty() || !VlessConfig.isValidVlessUrl(key)) {
            // No usable key: nothing to start. The settings UI asks for one before
            // reaching this point, so a failure here means the stored key is bad.
            if (showToast) {
                Toast.makeText(context, context.getString(R.string.VlessKeyInvalid), Toast.LENGTH_SHORT).show()
            }
            return false
        }

        // Both embedded transports own Telegram's local proxy setting.
        // Persistently turn TGWS off before VLESS takes the slot.
        setEnabled(true)
        TgWsProxyController.setEnabled(false)
        TgWsProxyController.stopService(context)

        val intent = Intent(context, XrayService::class.java).apply {
            action = XrayService.ACTION_START
            putExtra(XrayService.EXTRA_PORT, port)
            putExtra(XrayService.EXTRA_VLESS_KEY, key)
        }
        // Always started as a foreground service. A plain startService() is
        // killed by the OS within about a minute on Android 8+, which is exactly
        // what "I turn it on and it switches itself off" looked like; the
        // notification preference only controls how much the notification says.
        // Starting a service from the background throws on Android 8+/12+
        // (IllegalStateException / ForegroundServiceStartNotAllowedException).
        // This runs from ApplicationLoader on cold start too, where the process
        // may not be foreground yet, so a failure must not take the app down.
        val started = runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { FileLog.e(it) }.isSuccess
        if (!started) {
            // The enabled flag was already persisted above; roll it back so the
            // switch does not stay on pointing at a service that never came up.
            setEnabled(false)
            if (showToast) {
                Toast.makeText(
                    context,
                    context.getString(R.string.VlessStartFailed, ""),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return false
        }
        applyTelegramProxy(port)
        return true
    }

    @JvmStatic
    fun stop(context: Context) {
        setEnabled(false)
        runCatching {
            context.startService(Intent(context, XrayService::class.java).apply {
                action = XrayService.ACTION_STOP
            })
        }
        clearTelegramProxyIfLocal()
    }

    /** Stops only the running service, preserving the enabled preference. */
    @JvmStatic
    fun stopService(context: Context) {
        runCatching {
            context.startService(Intent(context, XrayService::class.java).apply {
                action = XrayService.ACTION_STOP
            })
        }
        clearTelegramProxyIfLocal()
    }

    @JvmStatic
    fun restartIfEnabled(context: Context) {
        if (isEnabled()) {
            startFromSettings(context, false)
        }
    }

    /**
     * Re-asserts the Telegram-side SOCKS proxy pointing at the local Xray engine
     * using the currently persisted port. Used by the service after it self-heals
     * (native engine restart / network change). No-op when the port is invalid.
     */
    @JvmStatic
    fun reapplyTelegramProxy() {
        applyTelegramProxy(SOCKS_PORT)
    }

    /**
     * Detaches Telegram from the local engine. Called by the service when the
     * engine goes down for good (failed start, stop), so Telegram never keeps
     * routing through a dead 127.0.0.1 port — which looks like "no connection
     * at all" rather than "VLESS off".
     */
    @JvmStatic
    fun releaseTelegramProxy() {
        clearTelegramProxyIfLocal()
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        NaConfig.vlessEnabled.setConfigBool(enabled)
        NaConfig.getPreferences().edit().putBoolean(NaConfig.vlessEnabled.key, enabled).commit()
    }

    @JvmStatic
    fun setVlessKey(key: String) {
        val normalized = key.trim()
        NaConfig.vlessKey.setConfigString(normalized)
        NaConfig.getPreferences().edit().putString(NaConfig.vlessKey.key, normalized).commit()
        // Keep the "selected server" marker honest: a key typed by hand clears it,
        // a key that happens to be one of the cached servers points back at it.
        setSelectedServer(savedServers().indexOf(normalized))
    }

    @JvmStatic
    fun setNotificationEnabled(enabled: Boolean) {
        NaConfig.vlessNotificationEnabled.setConfigBool(enabled)
        NaConfig.getPreferences().edit().putBoolean(NaConfig.vlessNotificationEnabled.key, enabled).commit()
    }

    @JvmStatic
    fun savedSubscriptionUrl(): String = NaConfig.vlessSubscriptionUrl.String().trim()

    @JvmStatic
    fun setSubscriptionUrl(url: String) {
        val normalized = url.trim()
        NaConfig.vlessSubscriptionUrl.setConfigString(normalized)
        NaConfig.getPreferences().edit().putString(NaConfig.vlessSubscriptionUrl.key, normalized).commit()
    }

    /** Servers cached by the last subscription refresh, in subscription order. */
    @JvmStatic
    fun savedServers(): List<String> {
        val raw = NaConfig.vlessServerList.String()
        if (raw.isBlank()) {
            return emptyList()
        }
        return raw.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Replaces the cached server list. The selection index is re-pointed at the
     * entry that is currently active so a refresh that reorders the subscription
     * does not leave the row highlighting an unrelated server.
     */
    @JvmStatic
    fun setServers(servers: List<String>) {
        val joined = servers.joinToString("\n")
        NaConfig.vlessServerList.setConfigString(joined)
        NaConfig.getPreferences().edit().putString(NaConfig.vlessServerList.key, joined).commit()
        val active = savedVlessKey()
        setSelectedServer(if (active.isEmpty()) -1 else servers.indexOf(active))
    }

    /** Index of the picked server in [savedServers], or -1 for a hand-entered key. */
    @JvmStatic
    fun selectedServerIndex(): Int = NaConfig.vlessSelectedServer.Int()

    @JvmStatic
    fun setSelectedServer(index: Int) {
        NaConfig.vlessSelectedServer.setConfigInt(index)
        NaConfig.getPreferences().edit().putInt(NaConfig.vlessSelectedServer.key, index).commit()
    }

    /**
     * Makes the server at [index] the active one. The picked URI simply becomes
     * the stored VLESS key, so startFromSettings()/the service read it through
     * the same path a hand-entered key takes — there is no second config source.
     * Returns false when the index no longer exists.
     */
    @JvmStatic
    fun selectServer(context: Context, index: Int): Boolean {
        val servers = savedServers()
        if (index !in servers.indices) {
            return false
        }
        setVlessKey(servers[index])
        setSelectedServer(index)
        // Only bounce the tunnel when it is actually up; picking a server with
        // the toggle off just stores it for the next start.
        restartIfEnabled(context)
        return true
    }

    /**
     * Applies a notification-visibility change to the live service without
     * restarting the tunnel. Going through startFromSettings() would tear the
     * engine down and back up, dropping every connection for a cosmetic change.
     *
     * The new value is not passed along: the service reads the preference itself,
     * which [setNotificationEnabled] has already committed. One source of truth,
     * so a lost extra or a defaulted one cannot resurrect the notification.
     */
    @JvmStatic
    fun applyNotificationVisibility(context: Context) {
        if (!XrayService.running) {
            return
        }
        runCatching {
            val intent = Intent(context, XrayService::class.java).apply {
                action = XrayService.ACTION_UPDATE_NOTIFICATION
            }
            // The service is already foreground; startForegroundService keeps the
            // call legal even when the app is not in the foreground itself.
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { FileLog.e(it) }
    }

    @JvmStatic
    fun reloadSavedSettings() {
        val preferences = NaConfig.getPreferences()
        NaConfig.vlessEnabled.changed(preferences.getBoolean(NaConfig.vlessEnabled.key, false))
        NaConfig.vlessKey.changed(preferences.getString(NaConfig.vlessKey.key, "") ?: "")
        NaConfig.vlessSocksPort.changed(preferences.getInt(NaConfig.vlessSocksPort.key, 10808))
        NaConfig.vlessNotificationEnabled.changed(preferences.getBoolean(NaConfig.vlessNotificationEnabled.key, false))
        NaConfig.vlessSubscriptionUrl.changed(preferences.getString(NaConfig.vlessSubscriptionUrl.key, "") ?: "")
        NaConfig.vlessServerList.changed(preferences.getString(NaConfig.vlessServerList.key, "") ?: "")
        NaConfig.vlessSelectedServer.changed(preferences.getInt(NaConfig.vlessSelectedServer.key, -1))
    }

    private fun applyTelegramProxy(port: Int) {
        removeLocalProxyEntries()
        val proxyInfo = SharedConfig.addProxy(
            SharedConfig.ProxyInfo(LOCAL_HOST, port, "", "", "")
        )
        SharedConfig.setCurrentProxy(proxyInfo)
        MessagesController.getGlobalMainSettings().edit()
            .putString("proxy_ip", LOCAL_HOST)
            .putString("proxy_pass", "")
            .putString("proxy_user", "")
            .putString("proxy_secret", "")
            .putInt("proxy_port", port)
            .putBoolean("proxy_enabled", true)
            .putBoolean("proxy_enabled_calls", false)
            .apply()
    }

    private fun clearTelegramProxyIfLocal() {
        val preferences = MessagesController.getGlobalMainSettings()
        val proxyAddress = preferences.getString("proxy_ip", "")
        if (proxyAddress == LOCAL_HOST) {
            SharedConfig.setCurrentProxy(null)
            removeLocalProxyEntries()
        }
    }

    private fun removeLocalProxyEntries() {
        SharedConfig.loadProxyList()
        if (SharedConfig.proxyList.removeAll { it.address == LOCAL_HOST }) {
            SharedConfig.saveProxyList()
        }
    }
}
