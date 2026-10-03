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

    /** Stable per install: panels count devices by this, so it must not change between refreshes. */
    @JvmStatic
    @Synchronized
    fun hwid(): String {
        val p = prefs()
        var id = p.getString(P + "hwid", "") ?: ""
        if (id.length < 16) {
            val raw = ByteArray(8)
            java.security.SecureRandom().nextBytes(raw)
            id = raw.joinToString("") { "%02x".format(it) }
            p.edit().putString(P + "hwid", id).commit()
        }
        return id
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
