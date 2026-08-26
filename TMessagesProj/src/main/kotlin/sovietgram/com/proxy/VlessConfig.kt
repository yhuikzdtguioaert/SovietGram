package sovietgram.com.proxy

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder

/**
 * Parses a `vless://uuid@host:port?params#name` share URL and builds an
 * Xray-core JSON config string: a single local SOCKS5 inbound plus one VLESS
 * outbound whose streamSettings are mapped from the URL parameters.
 *
 * Never logs or prints the raw URL/UUID; callers must keep it out of logs too.
 */
object VlessConfig {

    private val UUID_REGEX = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Thrown when the supplied vless:// URL cannot be parsed into a usable config. */
    class ParseException(message: String) : Exception(message)

    /**
     * Lightweight validation used by the settings UI. Returns true when [rawUrl]
     * looks like a parseable vless:// URL (correct scheme, a UUID, a host and a
     * valid port). Does not throw.
     */
    @JvmStatic
    fun isValidVlessUrl(rawUrl: String?): Boolean {
        return try {
            parse(rawUrl.orEmpty())
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Builds an Xray JSON config for the given vless URL and local SOCKS port.
     * @throws ParseException on a malformed URL or invalid SOCKS port.
     */
    @JvmStatic
    fun build(rawUrl: String, socksPort: Int): String {
        if (socksPort !in 1..65535) {
            throw ParseException("Invalid SOCKS port: $socksPort")
        }
        val link = parse(rawUrl)

        val inbound = JSONObject().apply {
            put("listen", "127.0.0.1")
            put("port", socksPort)
            put("protocol", "socks")
            put("tag", "socks-in")
            put("settings", JSONObject().apply {
                put("udp", true)
                put("auth", "noauth")
            })
            // No sniffing: this inbound only carries Telegram's MTProto stream,
            // which is neither HTTP nor TLS, and there are no domain-based
            // routing rules that would need the sniffed destination.
        }

        val user = JSONObject().apply {
            put("id", link.uuid)
            put("encryption", link.encryption)
            put("level", 0)
            if (link.effectiveFlow().isNotBlank()) {
                put("flow", link.effectiveFlow())
            }
        }
        val vnext = JSONObject().apply {
            put("address", link.host)
            put("port", link.port)
            put("users", JSONArray().apply { put(user) })
        }
        val outbound = JSONObject().apply {
            put("protocol", "vless")
            put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().apply { put(vnext) })
            })
            put("streamSettings", buildStreamSettings(link))
        }

        val direct = JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        }

        val config = JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            // An empty "stats" object plus statsOutboundUplink/Downlink is what
            // makes Xray register the outbound>>>proxy>>>traffic>>> counters the
            // notification reads. Without both, every counter stays at zero.
            put("stats", JSONObject())
            put("policy", JSONObject().apply {
                put("system", JSONObject().apply {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                })
            })
            put("inbounds", JSONArray().apply { put(inbound) })
            put("outbounds", JSONArray().apply {
                put(outbound)
                put(direct)
            })
        }
        return config.toString()
    }

    private fun buildStreamSettings(link: VlessLink): JSONObject {
        val stream = JSONObject()
        stream.put("network", link.network)
        stream.put("security", link.security)

        when (link.security) {
            "tls" -> stream.put("tlsSettings", JSONObject().apply {
                put("serverName", link.serverName())
                if (link.fingerprint.isNotBlank()) {
                    put("fingerprint", link.fingerprint)
                }
                if (link.alpn.isNotEmpty()) {
                    put("alpn", JSONArray().apply { link.alpn.forEach { put(it) } })
                }
                put("allowInsecure", false)
            })
            "reality" -> stream.put("realitySettings", JSONObject().apply {
                put("serverName", link.serverName())
                put("publicKey", link.publicKey)
                put("shortId", link.shortId)
                put("fingerprint", link.fingerprint.ifBlank { "chrome" })
                if (link.spiderX.isNotBlank()) {
                    put("spiderX", link.spiderX)
                }
            })
            // "none" and unknown => plaintext transport, no security block.
        }

        when (link.network) {
            "ws" -> stream.put("wsSettings", JSONObject().apply {
                put("path", link.path.ifBlank { "/" })
                val hostHeader = link.wsHost()
                if (hostHeader.isNotBlank()) {
                    put("headers", JSONObject().apply { put("Host", hostHeader) })
                }
            })
            "httpupgrade" -> stream.put("httpupgradeSettings", JSONObject().apply {
                put("path", link.path.ifBlank { "/" })
                val hostHeader = link.wsHost()
                if (hostHeader.isNotBlank()) {
                    put("host", hostHeader)
                }
            })
            "grpc" -> stream.put("grpcSettings", JSONObject().apply {
                put("serviceName", link.serviceName)
                put("multiMode", false)
            })
            "xhttp" -> stream.put("xhttpSettings", JSONObject().apply {
                put("path", link.path.ifBlank { "/" })
                val hostHeader = link.wsHost()
                if (hostHeader.isNotBlank()) {
                    put("host", hostHeader)
                }
                if (link.xhttpMode.isNotBlank()) {
                    put("mode", link.xhttpMode)
                }
                if (link.xhttpExtra.isNotBlank()) {
                    // Optional share-link metadata is accepted only when it is
                    // valid JSON, otherwise Xray's safe defaults are used.
                    runCatching { put("extra", JSONObject(link.xhttpExtra)) }
                }
            })
            "tcp" -> {
                // Plain TCP; header type "http" is uncommon for VLESS share links
                // and is intentionally left as the Xray default (none).
            }
            // Unknown networks fall through with just the "network" field set.
        }
        return stream
    }

    private fun parse(rawUrl: String): VlessLink {
        val trimmed = rawUrl.trim()
        if (!trimmed.startsWith("vless://", ignoreCase = true)) {
            throw ParseException("Not a vless:// URL")
        }

        // Parsed by hand instead of java.net.URI on purpose. URI is RFC-2396
        // strict and throws on things that are completely normal in real share
        // links: a remark fragment with spaces or non-ASCII ("#🇳🇱 Server 1",
        // "#Сервер"), and getHost() returns null for hostnames containing an
        // underscore. Both made every such key look "invalid" in the UI.
        var rest = trimmed.substring("vless://".length)

        // Strip the #remark first, then the ?query; the remark is free-form and
        // may itself contain '?' or '@'.
        val fragmentIdx = rest.indexOf('#')
        if (fragmentIdx >= 0) {
            rest = rest.substring(0, fragmentIdx)
        }
        var rawQuery = ""
        val queryIdx = rest.indexOf('?')
        if (queryIdx >= 0) {
            rawQuery = rest.substring(queryIdx + 1)
            rest = rest.substring(0, queryIdx)
        }

        // The UUID never contains '@', so the last '@' separates it from the
        // authority even if the (invalid but seen) userinfo had one.
        val atIdx = rest.lastIndexOf('@')
        if (atIdx < 0) {
            throw ParseException("Missing UUID in vless URL")
        }
        val uuid = decode(rest.substring(0, atIdx)).trim()
        if (!UUID_REGEX.matches(uuid)) {
            throw ParseException("Missing or invalid UUID in vless URL")
        }

        val authority = rest.substring(atIdx + 1).trim().trimEnd('/')
        val host: String
        val portText: String
        if (authority.startsWith("[")) {
            // Bracketed IPv6 literal: [::1]:443
            val closing = authority.indexOf(']')
            if (closing < 0) {
                throw ParseException("Malformed IPv6 host in vless URL")
            }
            host = authority.substring(1, closing).trim()
            val tail = authority.substring(closing + 1)
            portText = if (tail.startsWith(":")) tail.substring(1) else ""
        } else {
            val colon = authority.lastIndexOf(':')
            if (colon < 0) {
                throw ParseException("Missing port in vless URL")
            }
            host = authority.substring(0, colon).trim()
            portText = authority.substring(colon + 1)
        }
        if (host.isEmpty()) {
            throw ParseException("Missing host in vless URL")
        }

        val port = portText.trim().toIntOrNull() ?: -1
        if (port !in 1..65535) {
            throw ParseException("Missing or invalid port in vless URL")
        }

        val params = parseQuery(rawQuery)

        val encryption = params["encryption"]?.ifBlank { "none" } ?: "none"

        val security = when (params["security"]?.lowercase()?.trim()) {
            "tls", "xtls" -> "tls"
            "reality" -> "reality"
            "none", null, "" -> "none"
            else -> "none"
        }

        val network = when ((params["type"] ?: params["network"])?.lowercase()?.trim()) {
            "ws", "websocket" -> "ws"
            "grpc" -> "grpc"
            "httpupgrade" -> "httpupgrade"
            "xhttp", "splithttp" -> "xhttp"
            "tcp", null, "" -> "tcp"
            else -> "tcp"
        }

        val publicKey = params["pbk"]?.trim().orEmpty()
        if (security == "reality" && publicKey.isEmpty()) {
            // Xray refuses to load a reality outbound without a public key. Fail
            // here so the settings UI shows "invalid key" instead of the engine
            // dying a second later with a toggle left switched on.
            throw ParseException("Missing reality public key (pbk) in vless URL")
        }

        val alpn = params["alpn"]?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // grpc uses serviceName; fall back to a path-derived value.
        // parseQuery lowercases keys, so only the lowercase spelling can match.
        val serviceName = (params["servicename"] ?: params["path"])
            ?.trim()
            ?.trimStart('/')
            .orEmpty()

        return VlessLink(
            uuid = uuid,
            host = host,
            port = port,
            encryption = encryption,
            security = security,
            network = network,
            sni = params["sni"]?.trim().orEmpty(),
            hostParam = params["host"]?.trim().orEmpty(),
            path = params["path"]?.trim().orEmpty(),
            flow = params["flow"]?.trim().orEmpty(),
            publicKey = publicKey,
            shortId = params["sid"]?.trim().orEmpty(),
            fingerprint = params["fp"]?.trim().orEmpty(),
            spiderX = params["spx"]?.trim().orEmpty(),
            alpn = alpn,
            serviceName = serviceName,
            xhttpMode = params["mode"]?.trim().orEmpty(),
            xhttpExtra = params["extra"]?.trim().orEmpty()
        )
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, String>()
        for (pair in rawQuery.split("&")) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = if (idx >= 0) pair.substring(0, idx) else pair
            val value = if (idx >= 0) pair.substring(idx + 1) else ""
            val decodedKey = decode(key).lowercase()
            if (decodedKey.isEmpty()) continue
            result[decodedKey] = decode(value)
        }
        return result
    }

    private fun decode(value: String): String {
        return try {
            URLDecoder.decode(value, "UTF-8")
        } catch (_: Exception) {
            value
        }
    }

    private data class VlessLink(
        val uuid: String,
        val host: String,
        val port: Int,
        val encryption: String,
        val security: String,
        val network: String,
        val sni: String,
        val hostParam: String,
        val path: String,
        val flow: String,
        val publicKey: String,
        val shortId: String,
        val fingerprint: String,
        val spiderX: String,
        val alpn: List<String>,
        val serviceName: String,
        val xhttpMode: String,
        val xhttpExtra: String
    ) {
        /** TLS/Reality SNI. */
        fun serverName(): String = sni.ifBlank { hostParam }.ifBlank { host }

        /** HTTP Host header for ws/httpupgrade transports. */
        fun wsHost(): String = hostParam.ifBlank { sni }

        /**
         * XTLS flow is only accepted by Xray on a raw TCP stream secured with
         * TLS or Reality. Share links routinely carry a stale `flow=xtls-rprx-vision`
         * on ws/grpc/xhttp setups; passing it through makes the whole config
         * fail to load, so it is dropped where it cannot apply.
         */
        fun effectiveFlow(): String {
            if (flow.isBlank()) return ""
            return if (network == "tcp" && (security == "tls" || security == "reality")) flow else ""
        }
    }
}
