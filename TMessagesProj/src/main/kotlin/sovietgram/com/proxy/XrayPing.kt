package sovietgram.com.proxy

import org.json.JSONObject
import sovietgram.com.NaConfig
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/**
 * How fast a server answers, measured the four ways Happ offers. The two "via proxy" ways send a real
 * request through the server and so also prove that it works; TCP and ICMP only prove the host is
 * reachable and say nothing of the protocol on top, but they cost next to nothing.
 */
object XrayPing {

    const val GET = 0
    const val HEAD = 1
    const val TCP = 2
    const val ICMP = 3

    /** Same address Happ pings by default: it answers 204 with no body from anywhere. */
    private const val URL = "https://cp.cloudflare.com/generate_204"
    private const val TIMEOUT_MS = 6000

    /** Tells a failed ping from a measured one. */
    const val FAILED = -1

    @JvmStatic
    fun method(): Int = NaConfig.getPreferences().getInt(NaConfig.vlessPingMethod.key, GET).coerceIn(GET, ICMP)

    @JvmStatic
    fun setMethod(method: Int) {
        NaConfig.vlessPingMethod.setConfigInt(method.coerceIn(GET, ICMP))
        NaConfig.getPreferences().edit().putInt(NaConfig.vlessPingMethod.key, method.coerceIn(GET, ICMP)).commit()
    }

    /** Milliseconds the server took, or [FAILED]. Blocks; call it off the main thread. */
    @JvmStatic
    fun ping(method: Int, raw: String): Int {
        return try {
            when (method) {
                HEAD -> viaProxy("HEAD", raw)
                TCP -> tcp(raw)
                ICMP -> icmp(raw)
                else -> viaProxy("GET", raw)
            }
        } catch (_: Throwable) {
            FAILED
        }
    }

    private fun viaProxy(verb: String, raw: String): Int {
        val result = NativeXrayBridge.ping(probeConfig(raw), verb, URL, TIMEOUT_MS)
        return result.toIntOrNull()?.coerceAtLeast(0) ?: FAILED
    }

    /**
     * The real tunnel config with the local inbound, routing and statistics taken off: the probe
     * binds no port, so it cannot collide with a running tunnel or with another probe, and with
     * nothing to route by, the request goes out of the first proxy outbound, the server itself.
     */
    private fun probeConfig(raw: String): String {
        val config = JSONObject(XrayConfigBuilder.build(raw, 10808, "", "", XraySettings.snapshot()))
        for (key in arrayOf("inbounds", "routing", "burstObservatory", "stats", "policy")) {
            config.remove(key)
        }
        return config.toString()
    }

    private fun endpoint(raw: String): Pair<String, Int>? {
        val outbound = ProxyLinks.outbounds(raw).firstOrNull() ?: return null
        val settings = outbound.optJSONObject("settings") ?: return null
        val node = settings.optJSONArray("vnext")?.optJSONObject(0)
            ?: settings.optJSONArray("servers")?.optJSONObject(0)
            ?: settings
        val host = node.optString("address").ifEmpty { return null }
        val port = node.optInt("port", 0)
        return if (port in 1..65535) host to port else null
    }

    private fun tcp(raw: String): Int {
        val (host, port) = endpoint(raw) ?: return FAILED
        // Name lookup is not what the server is being judged on.
        val address = InetAddress.getByName(host)
        Socket().use { socket ->
            val started = System.nanoTime()
            socket.connect(InetSocketAddress(address, port), TIMEOUT_MS)
            return ((System.nanoTime() - started) / 1_000_000L).toInt()
        }
    }

    private fun icmp(raw: String): Int {
        val (host, _) = endpoint(raw) ?: return FAILED
        // Apps cannot open raw sockets, but the system's own ping binary may send an echo.
        val process = ProcessBuilder("ping", "-c", "1", "-W", "4", host).redirectErrorStream(true).start()
        try {
            val output = process.inputStream.bufferedReader().readText()
            val match = Regex("time[=<]\\s*([0-9.]+)\\s*ms").find(output) ?: return FAILED
            return match.groupValues[1].toDouble().toInt().coerceAtLeast(1)
        } finally {
            process.destroy()
        }
    }
}
