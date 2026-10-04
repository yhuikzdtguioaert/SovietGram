package sovietgram.com.proxy

import sovietgram.com.NaConfig
import sovietgram.com.proxy.XrayOptions.Snapshot

/**
 * The tuning a VPN client such as Happ offers next to the server list: fragmentation, noises, mux and a
 * few connection options. All of it is off by default and a setting that is off adds nothing to the
 * config, so a server runs exactly as its provider published it until the user opts in.
 *
 * Stored as plain preferences, read from there and not from [NaConfig] items, for the reason
 * [XrayController.isEnabled] spells out: the service can start in a fresh process before the config
 * items are loaded.
 */
object XraySettings {

    private const val P = "xs_"

    private fun prefs() = NaConfig.getPreferences()

    @JvmStatic
    fun snapshot(): Snapshot {
        val d = Snapshot()
        val p = prefs()
        return Snapshot(
            fragmentEnabled = p.getBoolean(P + "fragment", d.fragmentEnabled),
            fragmentPackets = p.getString(P + "fragment_packets", d.fragmentPackets) ?: d.fragmentPackets,
            fragmentLength = p.getString(P + "fragment_length", d.fragmentLength) ?: d.fragmentLength,
            fragmentInterval = p.getString(P + "fragment_interval", d.fragmentInterval) ?: d.fragmentInterval,
            fragmentMaxSplit = p.getString(P + "fragment_maxsplit", d.fragmentMaxSplit) ?: d.fragmentMaxSplit,
            noisesEnabled = p.getBoolean(P + "noises", d.noisesEnabled),
            noiseType = p.getString(P + "noise_type", d.noiseType) ?: d.noiseType,
            noisePacket = p.getString(P + "noise_packet", d.noisePacket) ?: d.noisePacket,
            noiseDelay = p.getString(P + "noise_delay", d.noiseDelay) ?: d.noiseDelay,
            noiseApplyTo = p.getString(P + "noise_applyto", d.noiseApplyTo) ?: d.noiseApplyTo,
            muxEnabled = p.getBoolean(P + "mux", d.muxEnabled),
            muxConcurrency = p.getInt(P + "mux_concurrency", d.muxConcurrency),
            muxXudpConcurrency = p.getInt(P + "mux_xudp", d.muxXudpConcurrency),
            muxXudpProxyUdp443 = p.getString(P + "mux_udp443", d.muxXudpProxyUdp443) ?: d.muxXudpProxyUdp443,
            fingerprint = p.getString(P + "fingerprint", d.fingerprint) ?: d.fingerprint,
            dns = p.getString(P + "dns", d.dns) ?: d.dns,
            maskAsHapp = p.getBoolean(P + "mask_happ", d.maskAsHapp)
        )
    }

    @JvmStatic
    fun putBoolean(key: String, value: Boolean) {
        prefs().edit().putBoolean(P + key, value).commit()
    }

    @JvmStatic
    fun putString(key: String, value: String) {
        prefs().edit().putString(P + key, value.trim()).commit()
    }

    @JvmStatic
    fun putInt(key: String, value: Int) {
        prefs().edit().putInt(P + key, value).commit()
    }

    // ----- the identity a subscription server is shown (see VlessSubscription) -----

    /**
     * The device identity a provider panel counts against its device limit (Happ allows a handful, some
     * panels twenty). It is chosen once and then never changes, whatever happens to the app:
     *
     *  - it is kept in its own preferences file as well as in the settings, so wiping or re-importing
     *    the settings cannot lose it;
     *  - the first time it is derived from the install's ANDROID_ID, which survives reinstalling and
     *    clearing data, so even a fresh install announces itself as the same device;
     *  - an id that is already registered is never replaced by a derived one.
     */
    @JvmStatic
    @Synchronized
    fun hwid(): String {
        val p = prefs()
        val own = org.telegram.messenger.ApplicationLoader.applicationContext
            .getSharedPreferences("sg_device_identity", android.content.Context.MODE_PRIVATE)
        var id = own.getString("hwid", "") ?: ""
        if (id.length < 16) {
            id = p.getString(P + "hwid", "") ?: ""
        }
        if (id.length < 16) {
            id = derivedHwid()
        }
        if ((own.getString("hwid", "") ?: "") != id) {
            own.edit().putString("hwid", id).commit()
        }
        if ((p.getString(P + "hwid", "") ?: "") != id) {
            p.edit().putString(P + "hwid", id).commit()
        }
        return id
    }

    private fun derivedHwid(): String {
        val androidId = runCatching {
            android.provider.Settings.Secure.getString(
                org.telegram.messenger.ApplicationLoader.applicationContext.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            )
        }.getOrNull().orEmpty()
        val bytes = if (androidId.length >= 8) {
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(("sovietgram-hwid:$androidId").toByteArray(Charsets.UTF_8))
        } else {
            ByteArray(8).also { java.security.SecureRandom().nextBytes(it) }
        }
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    // ----- what the last subscription refresh told us about itself -----

    class SubscriptionInfo(
        val title: String,
        val userInfo: String,
        val fallbackUrl: String,
        val supportUrl: String,
        val webPageUrl: String,
        val announce: String,
        val updatedAt: Long
    )

    @JvmStatic
    fun subscriptionInfo(): SubscriptionInfo {
        val p = prefs()
        return SubscriptionInfo(
            title = p.getString(P + "sub_title", "") ?: "",
            userInfo = p.getString(P + "sub_userinfo", "") ?: "",
            fallbackUrl = p.getString(P + "sub_fallback", "") ?: "",
            supportUrl = p.getString(P + "sub_support", "") ?: "",
            webPageUrl = p.getString(P + "sub_web", "") ?: "",
            announce = p.getString(P + "sub_announce", "") ?: "",
            updatedAt = p.getLong(P + "sub_updated", 0L)
        )
    }

    @JvmStatic
    fun saveSubscriptionInfo(info: SubscriptionInfo?) {
        val e = prefs().edit()
        if (info == null) {
            e.remove(P + "sub_title").remove(P + "sub_userinfo").remove(P + "sub_fallback")
                .remove(P + "sub_support").remove(P + "sub_web").remove(P + "sub_announce")
                .remove(P + "sub_updated")
        } else {
            e.putString(P + "sub_title", info.title).putString(P + "sub_userinfo", info.userInfo)
                .putString(P + "sub_fallback", info.fallbackUrl).putString(P + "sub_support", info.supportUrl)
                .putString(P + "sub_web", info.webPageUrl).putString(P + "sub_announce", info.announce)
                .putLong(P + "sub_updated", info.updatedAt)
        }
        e.commit()
    }
}
