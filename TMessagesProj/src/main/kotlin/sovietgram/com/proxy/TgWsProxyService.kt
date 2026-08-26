package sovietgram.com.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.telegram.messenger.AndroidUtilities
import org.telegram.messenger.FileLog
import org.telegram.messenger.R
import org.telegram.ui.LaunchActivity
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class TgWsProxyService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statsJob: Job? = null
    private var watchdogJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var stopInProgress = false
    // Guards against overlapping native-engine restarts triggered from both the
    // watchdog loop and the network-change callback.
    private val restarting = AtomicBoolean(false)
    private var connectivityManager: ConnectivityManager? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile
    private var lastNetworkEventAt = 0L
    // The default network the engine's upstream sockets were opened on. Compared
    // by identity of the Network handle, which is what tells "the same Wi-Fi
    // reporting itself again" from "the phone moved to mobile data".
    @Volatile
    private var currentNetwork: Network? = null
    private var lastPort = 1488
    private var lastPoolSize = 4
    private var lastCfEnabled = true
    // What the notification currently on screen was built with, or null when this
    // instance has posted nothing yet. Only used to notice that the preference
    // flipped since the last post (the two variants live on different channels and
    // therefore under different ids); the preference itself is never cached, see
    // notificationsEnabled().
    private var postedWithNotification: Boolean? = null
    private var lastSecret = ""
    private var lastNotificationText = ""
    private var notificationStartedAt = 0L
    // The id startForeground() actually latched onto. Android ties the
    // foreground state to that exact id, so every later notify()/cancel() must
    // use it verbatim; deriving it from the preference again lets the two drift
    // apart and leaves an orphan the user has to swipe away.
    private var postedNotificationId = 0
    // True once startForeground() has been called for this service instance, so
    // the foreground-service contract is satisfied at most once until teardown.
    private var foregroundStarted = false
    // True while the watchdog sees the local port accepting connections.
    @Volatile
    private var portUp = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        registerNetworkCallback()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        FileLog.e("TgWsProxyService onStartCommand action=$action startId=$startId")
        when (action) {
            ACTION_START -> {
                val port = intent.getIntExtra(EXTRA_PORT, 1488)
                val poolSize = intent.getIntExtra(EXTRA_POOL_SIZE, 4)
                val cfEnabled = intent.getBooleanExtra(EXTRA_CFPROXY_ENABLED, true)
                val secret = intent.getStringExtra(EXTRA_SECRET_KEY).orEmpty()
                // A live notification cannot move between channels, so a flipped
                // preference means the whole thing is rebuilt under the other id.
                if (foregroundStarted && postedWithNotification != notificationsEnabled()) {
                    foregroundStarted = false
                }
                // A previous run can leave a notification behind under either id
                // (an OS kill skips onDestroy, and the Android notification
                // outlives the service instance). Clearing both before the first
                // startForeground() of this instance is what stops a stale
                // visible notification from reappearing after the service was
                // restarted with notifications turned off.
                if (!foregroundStarted) {
                    clearStaleNotifications()
                }
                // startForeground() must happen here, unconditionally and before
                // anything that can fail or block: the controller always uses
                // startForegroundService(), and not honouring it within ~5s is an
                // instant ANR-crash. The preference only picks the channel.
                ensureForeground()
                startProxy(port, poolSize, cfEnabled, secret)
            }
            ACTION_UPDATE_NOTIFICATION -> {
                // Visibility toggled from settings while the proxy is up. Only
                // the notification is rebuilt; bouncing the native engine here
                // would drop every connection for a purely cosmetic change.
                // ensureForeground() resets the text to "Starting…", so the
                // current status is captured first and restored right after.
                val currentText = lastNotificationText
                if (postedWithNotification != notificationsEnabled()) {
                    foregroundStarted = false
                }
                ensureForeground()
                // A no-op while the notification is off — updateNotification()
                // refuses to re-post it, which is the whole point of "off".
                updateNotification(
                    if (portUp) runningText() else currentText.ifBlank { lastNotificationText },
                    force = true
                )
            }
            ACTION_STOP -> {
                // Also reached from the notification's Stop action, which does not
                // go through TgWsProxyController.stop(), so clear the flag here or
                // the settings toggle stays on and the next app start revives it.
                TgWsProxyController.setEnabled(false)
                stopProxy()
            }
            ACTION_RESTART -> {
                if (running && lastSecret.isNotBlank()) {
                    restartProxy()
                } else {
                    TgWsProxyController.reloadSavedSettings()
                    TgWsProxyController.restartIfEnabled(this)
                }
            }
            null -> {
                // Restore the persisted configuration on a bare re-delivery.
                TgWsProxyController.reloadSavedSettings()
                TgWsProxyController.restartIfEnabled(this)
            }
        }
        // START_NOT_STICKY: a failed start must NOT be re-delivered in a loop.
        // Legitimate restarts are driven by the controller/watchdog instead.
        return START_NOT_STICKY
    }

    /**
     * Calls startForeground() exactly once so the FGS contract is met at once.
     *
     * The notification id depends on the visibility preference: a visible and an
     * invisible notification are two different ids on two different channels,
     * because Android refuses to move a posted notification between channels.
     * After a successful startForeground() the *other* id is cancelled, so
     * flipping the preference never leaves a stale notification the user has to
     * swipe away, and turning notifications off never flashes a visible one.
     */
    private fun ensureForeground() {
        if (foregroundStarted) {
            return
        }
        foregroundStarted = true
        if (notificationStartedAt == 0L) {
            notificationStartedAt = System.currentTimeMillis()
        }
        val initial = getString(R.string.TgWsProxyNotificationStarting)
        lastNotificationText = initial
        val enabled = notificationsEnabled()
        val target = notificationId(enabled)
        runCatching {
            startForegroundCompat(target, createNotification(initial, enabled))
            postedNotificationId = target
            postedWithNotification = enabled
        }.onFailure { FileLog.e(it) }
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(otherNotificationId(enabled))
        }
    }

    /**
     * Drops any notification left over from an earlier service instance. Both
     * ids are cleared because the one that survived is not necessarily the one
     * this instance is about to post under.
     */
    private fun clearStaleNotifications() {
        runCatching {
            getSystemService(NotificationManager::class.java)?.let {
                it.cancel(NOTIFICATION_ID)
                it.cancel(NOTIFICATION_ID_SILENT)
            }
        }
    }

    private fun startProxy(port: Int, poolSize: Int, cfEnabled: Boolean, secret: String) {
        if (stopInProgress) {
            return
        }

        lastPort = port
        lastPoolSize = poolSize
        lastCfEnabled = cfEnabled
        lastSecret = secret

        if (running) {
            restartProxy()
            return
        }

        ensureForeground()
        updateNotification(getString(R.string.TgWsProxyNotificationStarting), force = true)
        acquireWakeLock()

        Thread({
            try {
                NativeTgWsProxy.setPoolSize(poolSize)
                NativeTgWsProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                NativeTgWsProxy.setCfProxyConfig(cfEnabled, true, "")
                val result = NativeTgWsProxy.start(TgWsProxyController.LOCAL_HOST, port, "", secret, true)
                if (result != 0) {
                    FileLog.e("TG WS Proxy start error: $result")
                    serviceScope.launch {
                        updateNotification(getString(R.string.TgWsProxyNotificationStartError, result), force = true)
                        delay(3000)
                        stopProxy()
                    }
                }
            } catch (e: Throwable) {
                FileLog.e(e)
                serviceScope.launch {
                    updateNotification(getString(R.string.TgWsProxyNotificationError, e.message.orEmpty()), force = true)
                    delay(3000)
                    stopProxy()
                }
            }
        }, "TgWsProxyStart").apply {
            isDaemon = true
            start()
        }

        running = true
        startWatchdog(port)
        startStats()
    }

    private fun restartProxy() {
        if (!running || lastSecret.isBlank()) {
            return
        }
        serviceScope.launch {
            val port = lastPort
            val poolSize = lastPoolSize
            val cfEnabled = lastCfEnabled
            val secret = lastSecret
            stopNative("restart")
            running = false
            portUp = false
            delay(350)
            startProxy(port, poolSize, cfEnabled, secret)
        }
    }

    private fun stopProxy() {
        if (stopInProgress) {
            return
        }
        stopInProgress = true
        portUp = false
        watchdogJob?.cancel()
        watchdogJob = null
        statsJob?.cancel()
        statsJob = null
        serviceScope.launch {
            updateNotification(getString(R.string.TgWsProxyNotificationStopping), force = true)
            stopNative("stop")
            releaseWakeLock()
            running = false
            stopInProgress = false
            // The local port is gone; detach Telegram from it. Reached from the
            // notification's Stop action too, where nothing else clears it and
            // Telegram would otherwise keep dialling a closed socket.
            AndroidUtilities.runOnUIThread {
                runCatching { TgWsProxyController.releaseTelegramProxy() }
            }
            removeForegroundNotification()
            stopSelf()
        }
    }

    private fun startWatchdog(port: Int) {
        watchdogJob?.cancel()
        watchdogJob = serviceScope.launch {
            // Give the native engine a moment to bind the local port on startup.
            delay(3000)
            var backoffMs = WATCHDOG_BACKOFF_BASE_MS
            var wasListening = false
            var misses = 0
            while (isActive && running && !stopInProgress) {
                val listening = withContext(Dispatchers.IO) {
                    isPortOpen(TgWsProxyController.LOCAL_HOST, port)
                }
                if (listening) {
                    misses = 0
                    if (!wasListening) {
                        portUp = true
                        updateNotification(runningText(), force = true)
                        // The controller points Telegram at the local port before
                        // the native engine has finished binding it, so the first
                        // connection attempts hit a closed socket and Telegram
                        // backs off. Re-assert the proxy now that it is really up.
                        AndroidUtilities.runOnUIThread {
                            runCatching { TgWsProxyController.reapplyTelegramProxy() }
                        }
                    }
                    wasListening = true
                    backoffMs = WATCHDOG_BACKOFF_BASE_MS
                    delay(WATCHDOG_PROBE_INTERVAL_MS)
                    continue
                }
                wasListening = false
                if (!running || stopInProgress) {
                    break
                }
                // One failed probe is not a dead engine. The loopback connect can
                // fail on a device that has just come out of doze, while the
                // engine is rebinding after a network change, or simply because
                // its accept backlog was full for the moment — and restarting on
                // that drops every live connection and reconnects the whole
                // client, which is what "the proxy keeps reconnecting by itself"
                // was. The engine has to miss WATCHDOG_MISSES probes in a row —
                // some twenty seconds of being unreachable — before it counts as
                // gone.
                misses++
                if (misses < WATCHDOG_MISSES) {
                    FileLog.e("TG WS Proxy watchdog: local port $port did not answer ($misses/$WATCHDOG_MISSES)")
                    delay(WATCHDOG_PROBE_INTERVAL_MS)
                    continue
                }
                misses = 0
                portUp = false
                FileLog.e("TG WS Proxy watchdog: local port $port is down, restarting native engine (next backoff ${backoffMs}ms)")
                updateNotification(getString(R.string.TgWsProxyNotificationReconnecting), force = true)
                restartNativeEngine("watchdog")
                delay(backoffMs)
                backoffMs = (backoffMs * 2).coerceAtMost(WATCHDOG_BACKOFF_MAX_MS)
            }
        }
    }

    /**
     * Bounces the native engine in place (keeps the service, wakelock, watchdog
     * and stats loops alive) and re-asserts the Telegram proxy pointing on
     * success. Concurrency-safe: only one restart runs at a time.
     */
    private suspend fun restartNativeEngine(reason: String): Boolean {
        if (!running || stopInProgress || lastSecret.isBlank()) {
            return false
        }
        if (!restarting.compareAndSet(false, true)) {
            FileLog.e("TG WS Proxy restart skipped (reason=$reason): already restarting")
            return false
        }
        try {
            FileLog.e("TG WS Proxy restarting native engine (reason=$reason)")
            portUp = false
            stopNative("restart-$reason")
            if (!running || stopInProgress) {
                return false
            }
            // Let the OS release the local port before rebinding it.
            delay(300)
            if (!running || stopInProgress) {
                return false
            }
            val result = try {
                NativeTgWsProxy.setPoolSize(lastPoolSize)
                NativeTgWsProxy.setCfProxyCacheDir(cacheDir.absolutePath)
                NativeTgWsProxy.setCfProxyConfig(lastCfEnabled, true, "")
                NativeTgWsProxy.start(TgWsProxyController.LOCAL_HOST, lastPort, "", lastSecret, true)
            } catch (e: Throwable) {
                FileLog.e(e)
                -1
            }
            return if (result == 0) {
                FileLog.e("TG WS Proxy native engine restart succeeded (reason=$reason)")
                // Re-attach the Telegram-side proxy on the main thread so a
                // dropped proxy re-points after an IP/network change.
                AndroidUtilities.runOnUIThread {
                    runCatching { TgWsProxyController.reapplyTelegramProxy() }
                }
                true
            } else {
                FileLog.e("TG WS Proxy native engine restart returned $result (reason=$reason)")
                false
            }
        } finally {
            restarting.set(false)
        }
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) {
            return
        }
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // The default-network callback fires again for a network that
                    // is already the default — when it validates, when it regains
                    // internet after a captive-portal check, when the modem
                    // re-registers. Restarting the engine for those is a dropped
                    // connection for nothing, so only an actually different
                    // network counts as a change.
                    if (network == currentNetwork) {
                        return
                    }
                    currentNetwork = network
                    onNetworkChanged("available")
                }

                override fun onLost(network: Network) {
                    // The local loopback port stays open with no network, so the
                    // watchdog cannot see the stale upstream. Nothing to rebind
                    // onto yet either; the next different default network does it.
                    if (network == currentNetwork) {
                        currentNetwork = null
                    }
                }
            }
            // Seeded before registering, because registering immediately calls
            // back with the network that is already the default — and without a
            // value to compare it against that echo reads as a change and bounces
            // the engine a second after it started.
            currentNetwork = runCatching { cm.activeNetwork }.getOrNull()
            cm.registerDefaultNetworkCallback(callback)
            connectivityManager = cm
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        runCatching {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        }
        networkCallback = null
        connectivityManager = null
        currentNetwork = null
    }

    private fun onNetworkChanged(reason: String) {
        if (!running || stopInProgress) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastNetworkEventAt < NETWORK_EVENT_DEBOUNCE_MS) {
            return
        }
        lastNetworkEventAt = now
        serviceScope.launch {
            // Let the new default network settle before rebinding upstream sockets.
            delay(500)
            if (!running || stopInProgress) {
                return@launch
            }
            FileLog.e("TG WS Proxy network changed ($reason), restarting native engine")
            restartNativeEngine("network-$reason")
        }
    }

    /**
     * Refreshes the notification with the engine's own status line while the
     * proxy is up. Only touches it when the watchdog says the local port is
     * listening, so "Starting", "Reconnecting" and error messages are never
     * overwritten by a stale status.
     */
    private fun startStats() {
        statsJob?.cancel()
        statsJob = serviceScope.launch {
            while (isActive) {
                delay(STATS_INTERVAL_MS)
                if (!running || stopInProgress || !portUp) {
                    continue
                }
                // Nothing to refresh while the notification is off, and asking the
                // native engine for its stats string would be pure waste. The text
                // is rebuilt from scratch when the user turns it back on.
                if (!notificationsEnabled()) {
                    continue
                }
                updateNotification(runningText())
            }
        }
    }

    /** The engine's own status string, or the plain running line when it has none. */
    private fun runningText(): String {
        val stats = runCatching { NativeTgWsProxy.getStats() }.getOrNull()
        return if (stats.isNullOrBlank()) getString(R.string.TgWsProxyNotificationRunning) else stats
    }

    private suspend fun stopNative(reason: String) {
        val completed = CompletableDeferred<Unit>()
        Thread({
            try {
                NativeTgWsProxy.stop()
            } catch (e: Throwable) {
                FileLog.e("TG WS Proxy stop failed during $reason")
                FileLog.e(e)
            } finally {
                completed.complete(Unit)
            }
        }, "TgWsProxyStop").apply {
            isDaemon = true
            start()
        }
        withTimeoutOrNull(3000) { completed.await() }
    }

    private fun isPortOpen(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 2000)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun updateNotification(content: String, force: Boolean = false) {
        if (!force && content == lastNotificationText) {
            return
        }
        lastNotificationText = content
        if (!foregroundStarted) {
            // Nothing has been posted yet; notifying now would create an
            // orphan notification that startForeground() cannot adopt.
            return
        }
        if (!notificationsEnabled()) {
            // The text is remembered above (a later flip back to "on" picks it up)
            // but nothing is posted. notify() on the foreground notification's id
            // cancels the FOREGROUND_SERVICE_DEFERRED hold and materialises the
            // notification at once, so this refresh — every 3 seconds from the
            // stats loop — was itself what put the notification back on screen
            // moments after the user switched it off.
            return
        }
        runCatching {
            // postedNotificationId, not notificationId(): updating under a
            // different id would post a second, independent notification
            // alongside the foreground one instead of replacing it.
            getSystemService(NotificationManager::class.java)
                ?.notify(postedNotificationId, createNotification(content, true))
        }
    }

    /**
     * Whether the ongoing notification may be shown, straight from the persisted
     * preference on every single call.
     *
     * Deliberately not cached and deliberately not taken from an intent extra: the
     * in-memory NaConfig copy defaults to "on" until ApplicationLoader's late
     * postInitApplication() fills it in, and an extra defaults to "on" whenever it
     * is missing. Either one turns a notification the user switched off back on,
     * which is precisely what happened after the app was restarted — the settings
     * row read "off" the whole time, because that row reads the preference.
     */
    private fun notificationsEnabled(): Boolean = TgWsProxyController.isNotificationEnabled()

    /**
     * The service must stay in the foreground to survive, so its notification
     * cannot be removed outright. The user preference instead selects a
     * minimum-importance channel, which Android collapses into the silent
     * section without a status-bar icon.
     */
    private fun channelId(enabled: Boolean): String =
        if (enabled) CHANNEL_ID else CHANNEL_ID_SILENT

    /** Id of the notification currently owned by this service. */
    private fun notificationId(enabled: Boolean): Int =
        if (enabled) NOTIFICATION_ID else NOTIFICATION_ID_SILENT

    /** The id of the twin that must be cancelled after a visibility flip. */
    private fun otherNotificationId(enabled: Boolean): Int =
        if (enabled) NOTIFICATION_ID_SILENT else NOTIFICATION_ID

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java) ?: return
            fun channel(id: String, importance: Int) = NotificationChannel(
                id,
                getString(R.string.TgWsProxyNotificationChannel),
                importance
            ).apply {
                description = getString(R.string.TgWsProxyNotificationChannelDescription)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            // The MIN-importance channel this replaces is deleted, or it stays in
            // the app's notification settings for ever as a row that does nothing.
            runCatching { manager.deleteNotificationChannel("sovietgram_tg_ws_proxy_silent") }
            manager.createNotificationChannel(channel(CHANNEL_ID, NotificationManager.IMPORTANCE_LOW))
            // IMPORTANCE_NONE, not MIN: a foreground service is entitled to its
            // notification and Android will not let it run without one, but a
            // channel of no importance is never displayed — which is the only way
            // "notification off" can mean what the user reads it as. MIN still
            // showed the entry, silently, at the bottom of the shade, which is
            // exactly the complaint. The service stays foreground either way.
            manager.createNotificationChannel(channel(CHANNEL_ID_SILENT, NotificationManager.IMPORTANCE_NONE))
        }
    }

    /**
     * Builds the ongoing notification. [enabled] is passed in rather than read
     * here so that a single post is guaranteed to be internally consistent: the
     * channel, the priority and the id it goes out under are all decided from the
     * same value by the caller. Reading the preference three separate times could
     * straddle a flip and produce, say, a low-importance notification posted under
     * the silent id.
     */
    private fun createNotification(content: String, enabled: Boolean): Notification {
        val openIntent = Intent(this, LaunchActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, TgWsProxyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val restartPendingIntent = PendingIntent.getService(
            this,
            3,
            Intent(this, TgWsProxyService::class.java).apply { action = ACTION_RESTART },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId(enabled))
            .setContentTitle(getString(R.string.TgWsProxy))
            .setContentText(content)
            .setSmallIcon(R.drawable.sovietgram_notification)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_popup_sync, getString(R.string.TgWsProxyNotificationRestart), restartPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.TgWsProxyNotificationStop), stopPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(if (enabled) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_MIN)
            // Keeps the silent variant off the lock screen as well, not just out
            // of the status bar.
            .setVisibility(if (enabled) NotificationCompat.VISIBILITY_PRIVATE else NotificationCompat.VISIBILITY_SECRET)
            // A foreground service is always entitled to a notification, so the
            // "off" setting can only make it as invisible as the platform
            // allows: DEFERRED holds it back for 10s (long enough that a short
            // session never shows one at all) and the IMPORTANCE_MIN channel
            // keeps it out of the status bar afterwards.
            .setForegroundServiceBehavior(
                if (enabled) {
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                } else {
                    NotificationCompat.FOREGROUND_SERVICE_DEFERRED
                }
            )
            .setWhen(notificationStartedAt.takeIf { it > 0L } ?: System.currentTimeMillis())
            .setShowWhen(false)
            .build()
    }

    /**
     * Drops the foreground notification. stopForeground(REMOVE) alone is not
     * enough on every OEM ROM — the shade sometimes keeps the entry until the
     * user swipes it — so both ids are cancelled explicitly.
     */
    private fun removeForegroundNotification() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            getSystemService(NotificationManager::class.java)?.let {
                it.cancel(NOTIFICATION_ID)
                it.cancel(NOTIFICATION_ID_SILENT)
            }
        }
        foregroundStarted = false
        postedNotificationId = 0
    }

    private fun startForegroundCompat(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(id, notification)
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) {
            return
        }
        runCatching {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SovietGram:TgWsProxy").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            wakeLock?.takeIf { it.isHeld }?.release()
        }
        wakeLock = null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!running) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        FileLog.e("TgWsProxyService onDestroy")
        watchdogJob?.cancel()
        statsJob?.cancel()
        unregisterNetworkCallback()
        releaseWakeLock()
        serviceScope.cancel()
        foregroundStarted = false
        portUp = false
        running = false
        // The service can be torn down without going through stopProxy() (task
        // swipe, low memory, stopSelf from a failed start); without this the
        // notification survives the service and has to be swiped by hand.
        runCatching {
            getSystemService(NotificationManager::class.java)?.let {
                it.cancel(NOTIFICATION_ID)
                it.cancel(NOTIFICATION_ID_SILENT)
            }
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "sovietgram.com.proxy.START"
        const val ACTION_STOP = "sovietgram.com.proxy.STOP"
        const val ACTION_RESTART = "sovietgram.com.proxy.RESTART"
        const val ACTION_UPDATE_NOTIFICATION = "sovietgram.com.proxy.UPDATE_NOTIFICATION"
        const val EXTRA_PORT = "port"
        const val EXTRA_POOL_SIZE = "pool_size"
        const val EXTRA_CFPROXY_ENABLED = "cfproxy_enabled"
        const val EXTRA_SECRET_KEY = "secret_key"

        private const val CHANNEL_ID = "sovietgram_tg_ws_proxy"
        // The twin used when the user turns the notification off. Its channel is
        // IMPORTANCE_NONE, so nothing is shown anywhere; the id differs from the
        // visible one because a posted notification cannot change channel, and
        // the channel id itself was renamed from the old "_silent" one because an
        // existing channel's importance can never be raised or lowered by the app
        // that created it — a new id is the only way to change what it does.
        private const val CHANNEL_ID_SILENT = "sovietgram_tg_ws_proxy_hidden"
        private const val NOTIFICATION_ID = 1488
        // Separate id for the silent channel: a posted notification cannot be
        // moved between channels, so the two variants must not share an id.
        private const val NOTIFICATION_ID_SILENT = 1489

        // Watchdog probe cadence and native-restart backoff bounds.
        private const val WATCHDOG_PROBE_INTERVAL_MS = 5000L
        // How many probes in a row have to miss before the engine is bounced.
        private const val WATCHDOG_MISSES = 3
        private const val WATCHDOG_BACKOFF_BASE_MS = 1000L
        private const val WATCHDOG_BACKOFF_MAX_MS = 30000L
        private const val NETWORK_EVENT_DEBOUNCE_MS = 2500L
        // Status refresh; fast enough to feel live, slow enough not to wake the
        // notification manager constantly.
        private const val STATS_INTERVAL_MS = 3000L

        @JvmStatic
        @Volatile
        var running: Boolean = false
            private set
    }
}
