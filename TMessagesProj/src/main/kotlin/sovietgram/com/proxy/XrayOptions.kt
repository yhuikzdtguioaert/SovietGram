package sovietgram.com.proxy

/**
 * The vocabulary of the VPN tuning, with no storage attached: the shape of a settings snapshot, what
 * each option may be, and the defaults. Everything is off by default, and an option that is off adds
 * nothing to the config.
 */
object XrayOptions {

    const val PACKETS_TLS_HELLO = "tlshello"

    /** What the builder works from, so a config is a pure function of its inputs. */
    data class Snapshot(
        val fragmentEnabled: Boolean = false,
        val fragmentPackets: String = PACKETS_TLS_HELLO,
        val fragmentLength: String = "100-200",
        val fragmentInterval: String = "10-20",
        val fragmentMaxSplit: String = "",
        val noisesEnabled: Boolean = false,
        val noiseType: String = "rand",
        val noisePacket: String = "50-100",
        val noiseDelay: String = "10-20",
        val noiseApplyTo: String = "ip",
        val muxEnabled: Boolean = false,
        val muxConcurrency: Int = 8,
        val muxXudpConcurrency: Int = 16,
        val muxXudpProxyUdp443: String = "reject",
        /** "" keeps what each server says; otherwise a uTLS client hello name. */
        val fingerprint: String = "",
        /** "" is the system resolver; otherwise one of [DNS_SERVERS]' keys. */
        val dns: String = "",
        val maskAsHapp: Boolean = true
    )

    val FINGERPRINTS = listOf(
        "", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized"
    )

    /** DoH resolvers dialed directly (+local), by IP so resolving them never needs a resolver or the tunnel. */
    val DNS_SERVERS = linkedMapOf(
        "cloudflare" to "https+local://1.1.1.1/dns-query",
        "google" to "https+local://8.8.8.8/dns-query",
        "yandex" to "https+local://77.88.8.8/dns-query",
        "quad9" to "https+local://9.9.9.9/dns-query"
    )

    val NOISE_TYPES = listOf("rand", "str", "base64", "hex")
    val NOISE_APPLY_TO = listOf("ip", "ipv4", "ipv6")
    val UDP443_POLICIES = listOf("reject", "allow", "skip")
    val FRAGMENT_PACKETS = listOf(PACKETS_TLS_HELLO, "1-3", "1-5")

    /** A "100-200" style range or a single number, the form Xray takes for lengths, delays and counts. */
    @JvmStatic
    fun isRange(value: String): Boolean = Regex("^\\d{1,6}(-\\d{1,6})?$").matches(value.trim())
}
