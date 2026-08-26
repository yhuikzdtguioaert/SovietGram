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
import java.security.SecureRandom

object TgWsProxyController {
    const val LOCAL_HOST = "127.0.0.1"
    private const val SECRET_PREFIX = "dd"
    private const val RAW_SECRET_LENGTH = 32
    private const val MTPROTO_SECRET_LENGTH = 34
    private val secureRandom = SecureRandom()

    /**
     * Whether the proxy is switched on, read straight out of SharedPreferences.
     *
     * Not [NaConfig.tgWsProxyEnabled], because every ConfigItem holds its
     * compile-time default until [org.telegram.messenger.ApplicationLoader.postInitApplication]
     * fills them in — and that runs from the UI, not from Application.onCreate().
     * The OS can construct the service before it: a notification action or a bare
     * re-delivery in a fresh process both land in onStartCommand() with NaConfig
     * still holding defaults. SharedPreferences have no such window.
     */
    @JvmStatic
    fun isEnabled(): Boolean =
        NaConfig.getPreferences().getBoolean(NaConfig.tgWsProxyEnabled.key, false)

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
        NaConfig.getPreferences().getBoolean(NaConfig.tgWsProxyNotificationEnabled.key, false)

    @JvmStatic
    fun startFromSettings(context: Context, showToast: Boolean): Boolean {
        val port = NaConfig.tgWsProxyPort.Int()
        if (port !in 1..65535) {
            if (showToast) {
                Toast.makeText(context, context.getString(R.string.TgWsProxyInvalidPort), Toast.LENGTH_SHORT).show()
            }
            return false
        }

        val secret = ensureSecretKey()
        setEnabled(true)
        // Both embedded transports own Telegram's single local proxy slot, so
        // VLESS is persistently turned off before TGWS takes it. stopService()
        // also clears the local proxy entry, which is why applyTelegramProxy()
        // below must come after it and never before.
        XrayController.setEnabled(false)
        XrayController.stopService(context)
        val intent = Intent(context, TgWsProxyService::class.java).apply {
            action = TgWsProxyService.ACTION_START
            putExtra(TgWsProxyService.EXTRA_PORT, port)
            putExtra(TgWsProxyService.EXTRA_POOL_SIZE, NaConfig.tgWsProxyPool.Int())
            putExtra(TgWsProxyService.EXTRA_CFPROXY_ENABLED, NaConfig.tgWsProxyCloudflareCdn.Bool())
            putExtra(TgWsProxyService.EXTRA_SECRET_KEY, getRawSecret(secret))
        }
        // Always a foreground service. A plain startService() is killed by the
        // OS within about a minute on Android 8+ and throws outright when the
        // app is in the background, which is exactly what "TGWS stopped
        // working" looked like; the notification preference only controls how
        // visible the notification is, never how the service is started.
        val started = runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { FileLog.e(it) }.isSuccess
        if (!started) {
            // The enabled flag was already persisted above; roll it back so the
            // switch does not stay on pointing at a service that never came up.
            setEnabled(false)
            if (showToast) {
                Toast.makeText(context, context.getString(R.string.TgWsProxyStartFailed), Toast.LENGTH_SHORT).show()
            }
            return false
        }
        applyTelegramProxy(port, secret)
        return true
    }

    @JvmStatic
    fun stop(context: Context) {
        setEnabled(false)
        runCatching {
            context.startService(Intent(context, TgWsProxyService::class.java).apply {
                action = TgWsProxyService.ACTION_STOP
            })
        }
        clearTelegramProxyIfLocal()
    }

    /** Stops only the running service, preserving the enabled preference. */
    @JvmStatic
    fun stopService(context: Context) {
        runCatching {
            context.startService(Intent(context, TgWsProxyService::class.java).apply {
                action = TgWsProxyService.ACTION_STOP
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
     * Re-asserts the Telegram-side proxy pointing at the local engine using the
     * currently persisted port/secret. Used by the service after it self-heals
     * (native engine restart / network change) so a dropped proxy re-attaches.
     * Safe to call repeatedly; it is a no-op when the port is invalid.
     */
    /**
     * Detaches Telegram from the local engine. Called by the service when the
     * engine goes down for good (failed start, stop), so Telegram never keeps
     * routing through a dead 127.0.0.1 port — which looks like "no connection
     * at all" rather than "proxy off".
     */
    @JvmStatic
    fun releaseTelegramProxy() {
        clearTelegramProxyIfLocal()
    }

    @JvmStatic
    fun reapplyTelegramProxy() {
        val port = NaConfig.tgWsProxyPort.Int()
        if (port !in 1..65535) {
            return
        }
        val secret = ensureSecretKey()
        applyTelegramProxy(port, secret)
    }

    @JvmStatic
    fun setEnabled(enabled: Boolean) {
        NaConfig.tgWsProxyEnabled.setConfigBool(enabled)
        NaConfig.getPreferences().edit().putBoolean(NaConfig.tgWsProxyEnabled.key, enabled).commit()
    }

    @JvmStatic
    fun ensureSecretKey(): String {
        val normalized = normalizeSecret(NaConfig.tgWsProxySecret.String())
        if (normalized != NaConfig.tgWsProxySecret.String()) {
            setSecretKey(normalized)
        }
        return normalized
    }

    @JvmStatic
    fun setPort(port: Int) {
        NaConfig.tgWsProxyPort.setConfigInt(port)
        NaConfig.getPreferences().edit().putInt(NaConfig.tgWsProxyPort.key, port).commit()
    }

    @JvmStatic
    fun setPoolSize(poolSize: Int) {
        NaConfig.tgWsProxyPool.setConfigInt(poolSize)
        NaConfig.getPreferences().edit().putInt(NaConfig.tgWsProxyPool.key, poolSize).commit()
    }

    @JvmStatic
    fun setNotificationEnabled(enabled: Boolean) {
        NaConfig.tgWsProxyNotificationEnabled.setConfigBool(enabled)
        NaConfig.getPreferences().edit().putBoolean(NaConfig.tgWsProxyNotificationEnabled.key, enabled).commit()
    }

    /**
     * Applies a notification-visibility change to the live service without
     * restarting the proxy. Going through startFromSettings() would tear the
     * engine down and back up, dropping every connection for a cosmetic change.
     *
     * The new value is not passed along: the service reads the preference itself,
     * which [setNotificationEnabled] has already committed. One source of truth,
     * so a lost extra or a defaulted one cannot resurrect the notification.
     */
    @JvmStatic
    fun applyNotificationVisibility(context: Context) {
        if (!TgWsProxyService.running) {
            return
        }
        runCatching {
            val intent = Intent(context, TgWsProxyService::class.java).apply {
                action = TgWsProxyService.ACTION_UPDATE_NOTIFICATION
            }
            // The service is already foreground; startForegroundService keeps the
            // call legal even when the app is not in the foreground itself.
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { FileLog.e(it) }
    }

    @JvmStatic
    fun setSecretKey(secret: String) {
        val normalized = normalizeSecret(secret)
        NaConfig.tgWsProxySecret.setConfigString(normalized)
        NaConfig.getPreferences().edit().putString(NaConfig.tgWsProxySecret.key, normalized).commit()
    }

    @JvmStatic
    fun reloadSavedSettings() {
        val preferences = NaConfig.getPreferences()
        // The on/off flag belongs here too: the service's recovery paths call this
        // and then restartIfEnabled(), and in a fresh process NaConfig still holds
        // the compile-time default (off), so the proxy quietly failed to come back.
        NaConfig.tgWsProxyEnabled.changed(preferences.getBoolean(NaConfig.tgWsProxyEnabled.key, false))
        NaConfig.tgWsProxyPort.changed(preferences.getInt(NaConfig.tgWsProxyPort.key, 1488))
        NaConfig.tgWsProxyPool.changed(preferences.getInt(NaConfig.tgWsProxyPool.key, 4))
        NaConfig.tgWsProxySecret.changed(preferences.getString(NaConfig.tgWsProxySecret.key, "") ?: "")
        NaConfig.tgWsProxyCloudflareCdn.changed(preferences.getBoolean(NaConfig.tgWsProxyCloudflareCdn.key, true))
        NaConfig.tgWsProxyNotificationEnabled.changed(preferences.getBoolean(NaConfig.tgWsProxyNotificationEnabled.key, false))
    }

    @JvmStatic
    fun generateSecretKey(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return SECRET_PREFIX + bytes.joinToString("") { "%02x".format(it) }
    }

    private fun applyTelegramProxy(port: Int, secret: String) {
        val mtprotoSecret = normalizeSecret(secret)
        removeLocalProxyEntries()
        val proxyInfo = SharedConfig.addProxy(
            SharedConfig.ProxyInfo(LOCAL_HOST, port, "", "", mtprotoSecret)
        )
        SharedConfig.setCurrentProxy(proxyInfo)
        MessagesController.getGlobalMainSettings().edit()
            .putString("proxy_ip", LOCAL_HOST)
            .putString("proxy_pass", "")
            .putString("proxy_user", "")
            .putString("proxy_secret", mtprotoSecret)
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

    private fun normalizeSecret(value: String): String {
        val secret = value.trim().lowercase()
        if (secret.length == MTPROTO_SECRET_LENGTH && secret.startsWith(SECRET_PREFIX) && isValidRawSecret(secret.substring(2))) {
            return secret
        }
        if (secret.length == RAW_SECRET_LENGTH && isValidRawSecret(secret)) {
            return SECRET_PREFIX + secret
        }
        return generateSecretKey()
    }

    private fun getRawSecret(value: String): String {
        val secret = normalizeSecret(value)
        return secret.substring(SECRET_PREFIX.length)
    }

    private fun isValidRawSecret(value: String): Boolean {
        return value.length == RAW_SECRET_LENGTH && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
    }
}
