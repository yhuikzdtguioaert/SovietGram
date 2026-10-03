package sovietgram.com.proxy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns the share links people actually paste into Xray outbounds: vless://, vmess://, trojan://, ss://
 * and hysteria2:// (hy2://), plus the "xray://" form this app stores for a server that came out of a JSON
 * subscription, where the provider published finished Xray outbounds and re-encoding them as links would
 * only lose what the link syntax cannot say.
 *
 * Parsed by hand: java.net.URI throws on remarks with spaces or emoji and on underscores in hosts, both of
 * which are normal in real links. Never logs a link — every one of them carries a credential.
 *
 * A server that would send its traffic unprotected is refused here rather than offered and run: vless and
 * trojan must use TLS or Reality, shadowsocks must name a real cipher, and certificate checks are never
 * switched off, whatever `allowInsecure` or `insecure` a link asks for.
 */
object ProxyLinks {

    const val WRAPPED = "xray://"

    class LinkException(message: String) : Exception(message)

    private val PREFIXES = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://", WRAPPED)

    private val TRANSPORTS = setOf("tcp", "ws", "websocket", "grpc", "httpupgrade", "xhttp", "splithttp")

    @JvmStatic
    fun isSupported(raw: String?): Boolean {
        val t = raw?.trimStart() ?: return false
        return PREFIXES.any { t.startsWith(it, ignoreCase = true) }
    }

    @JvmStatic
    fun isValid(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        return try {
            outbounds(raw).isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /** The outbounds a server runs: one for a share link, several for a provider's "auto" entry. */
    @JvmStatic
    fun outbounds(raw: String): List<JSONObject> {
        val t = raw.trim()
        val lower = t.lowercase()
        return when {
            lower.startsWith("vless://") -> listOf(VlessConfig.outbound(t))
            lower.startsWith("vmess://") -> listOf(vmess(t))
            lower.startsWith("trojan://") -> listOf(trojan(t))
            lower.startsWith("ss://") -> listOf(shadowsocks(t))
            lower.startsWith("hysteria2://") || lower.startsWith("hy2://") -> listOf(hysteria2(t))
            lower.startsWith(WRAPPED) -> unwrap(t).second
            else -> throw LinkException("Unsupported link")
        }
    }

    /** What a row calls the server: the link's remark, or the address when it has none. */
    @JvmStatic
    fun displayName(raw: String): String {
        val t = raw.trim()
        val lower = t.lowercase()
        return try {
            when {
                lower.startsWith(WRAPPED) -> unwrap(t).first
                lower.startsWith("vmess://") -> vmessName(t)
                else -> {
                    val parts = split(t.substring(t.indexOf("://") + 3))
                    parts.name.ifEmpty { parts.authority.substringAfterLast('@') }
                }
            }
        } catch (_: Throwable) {
            ""
        }
    }

    /** "VLESS · Reality · TCP": what is behind a row, in the words a VPN client uses. */
    @JvmStatic
    fun describe(raw: String): String {
        val first = runCatching { outbounds(raw).firstOrNull() }.getOrNull() ?: return ""
        val protocol = when (first.optString("protocol")) {
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "trojan" -> "Trojan"
            "shadowsocks" -> "Shadowsocks"
            "hysteria" -> "Hysteria2"
            else -> first.optString("protocol")
        }
        val stream = first.optJSONObject("streamSettings")
        val security = when (stream?.optString("security")) {
            "reality" -> "Reality"
            "tls" -> "TLS"
            else -> ""
        }
        val network = when (val n = stream?.optString("network").orEmpty()) {
            "", "hysteria" -> ""
            "tcp" -> "TCP"
            "ws" -> "WS"
            "grpc" -> "gRPC"
            "xhttp" -> "XHTTP"
            "httpupgrade" -> "HTTPUpgrade"
            else -> n.uppercase()
        }
        val count = runCatching { outbounds(raw).size }.getOrDefault(1)
        val text = listOf(protocol, security, network).filter { it.isNotEmpty() }.joinToString(" · ")
        return if (count > 1) "$text · ×$count" else text
    }

    // ------------------------------------------------------------------ subscription entries

    /**
     * An entry of a JSON subscription — [name] and the outbounds that carry traffic — as one string, so
     * a server stays a single value everywhere in the app (storage, selection, the running config).
     * Keeps what a proxy depends on through `dialerProxy` too, otherwise the config would not load.
     */
    @JvmStatic
    fun wrap(name: String, outbounds: List<JSONObject>): String {
        val body = JSONObject().apply {
            put("n", name)
            put("o", JSONArray().apply { outbounds.forEach { put(it) } })
        }
        return WRAPPED + Base64Lite.encodeUrl(body.toString().toByteArray(Charsets.UTF_8))
    }

    private fun unwrap(raw: String): Pair<String, List<JSONObject>> {
        val body = JSONObject(String(Base64Lite.decode(raw.substring(WRAPPED.length)) ?: throw LinkException("Bad entry"), Charsets.UTF_8))
        val array = body.optJSONArray("o") ?: throw LinkException("Empty entry")
        val list = ArrayList<JSONObject>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { list.add(it) }
        }
        if (list.isEmpty()) throw LinkException("Empty entry")
        return body.optString("n") to list
    }

    // ------------------------------------------------------------------ vmess

    private fun vmessJson(raw: String): JSONObject? {
        val body = raw.substring("vmess://".length).substringBefore('#').trim()
        if (body.contains('@')) return null
        val bytes = Base64Lite.decode(body) ?: throw LinkException("Bad vmess link")
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun vmessName(raw: String): String {
        val json = vmessJson(raw)
        if (json != null) {
            return json.optString("ps").ifEmpty { json.optString("add") }
        }
        val parts = split(raw.substring("vmess://".length))
        return parts.name.ifEmpty { parts.authority.substringAfterLast('@') }
    }

    private fun vmess(raw: String): JSONObject {
        val json = vmessJson(raw)
        val address: String
        val port: Int
        val id: String
        val alter: Int
        val cipher: String
        val params = LinkedHashMap<String, String>()
        val security: String
        if (json != null) {
            address = json.optString("add").trim()
            port = json.optString("port").trim().toIntOrNull() ?: -1
            id = json.optString("id").trim()
            alter = json.optString("aid").trim().toIntOrNull() ?: 0
            cipher = json.optString("scy").ifBlank { "auto" }
            params["type"] = json.optString("net").ifBlank { "tcp" }
            params["host"] = json.optString("host")
            params["path"] = json.optString("path")
            params["sni"] = json.optString("sni")
            params["alpn"] = json.optString("alpn")
            params["fp"] = json.optString("fp")
            params["servicename"] = json.optString("path")
            security = if (json.optString("tls").equals("tls", true)) "tls" else "none"
        } else {
            val parts = split(raw.substring("vmess://".length))
            id = VlessConfig.decode(parts.userInfo).trim()
            address = parts.host
            port = parts.port
            alter = parts.params["aid"]?.toIntOrNull() ?: 0
            cipher = parts.params["encryption"]?.ifBlank { null } ?: "auto"
            params.putAll(parts.params)
            security = when (parts.params["security"]?.lowercase()) {
                "tls" -> "tls"
                "reality" -> "reality"
                else -> "none"
            }
        }
        if (address.isEmpty() || port !in 1..65535 || id.isEmpty()) throw LinkException("Bad vmess link")
        if ((params["type"] ?: "tcp").lowercase() !in TRANSPORTS) throw LinkException("Unsupported vmess transport")
        return JSONObject().apply {
            put("protocol", "vmess")
            put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("vnext", JSONArray().put(JSONObject().apply {
                    put("address", address)
                    put("port", port)
                    put("users", JSONArray().put(JSONObject().apply {
                        put("id", id)
                        put("alterId", alter)
                        put("security", cipher)
                        put("level", 0)
                    }))
                }))
            })
            put("streamSettings", VlessConfig.streamFromParams(params, address, security))
        }
    }

    // ------------------------------------------------------------------ trojan

    private fun trojan(raw: String): JSONObject {
        val parts = split(raw.substring("trojan://".length))
        val password = credential(parts.userInfo)
        if (password.isEmpty() || parts.host.isEmpty() || parts.port !in 1..65535) {
            throw LinkException("Bad trojan link")
        }
        val security = when (parts.params["security"]?.lowercase()?.trim()) {
            null, "", "tls" -> "tls"
            "reality" -> "reality"
            else -> throw LinkException("Trojan must use TLS")
        }
        return JSONObject().apply {
            put("protocol", "trojan")
            put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", parts.host)
                    put("port", parts.port)
                    put("password", password)
                    put("level", 0)
                }))
            })
            put("streamSettings", VlessConfig.streamFromParams(parts.params, parts.host, security))
        }
    }

    // ------------------------------------------------------------------ shadowsocks

    private fun shadowsocks(raw: String): JSONObject {
        var rest = raw.substring("ss://".length)
        rest = rest.substringBefore('#')
        val query = if (rest.contains('?')) rest.substringAfter('?') else ""
        rest = rest.substringBefore('?').trimEnd('/')
        val plugin = VlessConfig.parseQuery(query)["plugin"].orEmpty()
        if (plugin.isNotBlank()) throw LinkException("Shadowsocks plugins are not supported")

        val credentials: String
        val hostPort: String
        val at = rest.lastIndexOf('@')
        if (at >= 0) {
            val userInfo = rest.substring(0, at)
            hostPort = rest.substring(at + 1)
            credentials = if (userInfo.contains(':')) {
                credential(userInfo)
            } else {
                String(Base64Lite.decode(credential(userInfo)) ?: throw LinkException("Bad ss link"), Charsets.UTF_8)
            }
        } else {
            val decoded = String(Base64Lite.decode(rest) ?: throw LinkException("Bad ss link"), Charsets.UTF_8)
            val inner = decoded.lastIndexOf('@')
            if (inner < 0) throw LinkException("Bad ss link")
            credentials = decoded.substring(0, inner)
            hostPort = decoded.substring(inner + 1)
        }
        val colon = credentials.indexOf(':')
        if (colon <= 0) throw LinkException("Bad ss link")
        val method = credentials.substring(0, colon).trim().lowercase()
        val password = credentials.substring(colon + 1)
        if (method == "none" || method == "plain" || password.isEmpty()) throw LinkException("Shadowsocks needs a cipher")

        val endpoint = splitHostPort(hostPort)
        if (endpoint.first.isEmpty() || endpoint.second !in 1..65535) throw LinkException("Bad ss link")
        return JSONObject().apply {
            put("protocol", "shadowsocks")
            put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("servers", JSONArray().put(JSONObject().apply {
                    put("address", endpoint.first)
                    put("port", endpoint.second)
                    put("method", method)
                    put("password", password)
                    put("level", 0)
                }))
            })
        }
    }

    // ------------------------------------------------------------------ hysteria 2

    private fun hysteria2(raw: String): JSONObject {
        val parts = split(raw.substring(raw.indexOf("://") + 3), allowPortList = true)
        val auth = credential(parts.userInfo)
        if (auth.isEmpty() || parts.host.isEmpty() || parts.port !in 1..65535) throw LinkException("Bad hysteria2 link")
        val sni = parts.params["sni"]?.trim().orEmpty().ifEmpty { parts.host }
        val alpn = parts.params["alpn"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.takeIf { it.isNotEmpty() } ?: listOf("h3")
        val obfs = parts.params["obfs"]?.lowercase().orEmpty()
        if (obfs.isNotEmpty() && obfs != "salamander") throw LinkException("Unsupported hysteria2 obfuscation")
        return JSONObject().apply {
            put("protocol", "hysteria")
            put("tag", "proxy")
            put("settings", JSONObject().apply {
                put("address", parts.host)
                put("port", parts.port)
                put("version", 2)
            })
            put("streamSettings", JSONObject().apply {
                put("network", "hysteria")
                put("hysteriaSettings", JSONObject().apply {
                    put("version", 2)
                    put("auth", auth)
                })
                put("security", "tls")
                put("tlsSettings", JSONObject().apply {
                    put("serverName", sni)
                    put("fingerprint", "chrome")
                    put("alpn", JSONArray().apply { alpn.forEach { put(it) } })
                    put("allowInsecure", false)
                })
                put("finalmask", JSONObject().apply {
                    put("quicParams", JSONObject().apply { put("congestion", "bbr") })
                    if (obfs == "salamander") {
                        put("udp", JSONArray().put(JSONObject().apply {
                            put("type", "salamander")
                            put("settings", JSONObject().apply {
                                put("password", parts.params["obfs-password"].orEmpty())
                            })
                        }))
                    }
                })
            })
        }
    }

    // ------------------------------------------------------------------ url plumbing

    /**
     * A password, key or auth string out of a link's userinfo. Percent-decoded, but a literal '+' stays
     * a plus: URLDecoder reads it as a space, which silently corrupts a password or a base64 userinfo
     * (standard base64 has '+') that a provider wrote unescaped.
     */
    private fun credential(value: String): String = VlessConfig.decode(value.replace("+", "%2B"))

    private class Parts(
        val userInfo: String,
        val authority: String,
        val host: String,
        val port: Int,
        val params: Map<String, String>,
        val name: String
    )

    /** `[userinfo@]host:port[/][?query][#name]`, the shape every scheme above shares. */
    private fun split(afterScheme: String, allowPortList: Boolean = false): Parts {
        var rest = afterScheme
        var name = ""
        val hash = rest.indexOf('#')
        if (hash >= 0) {
            name = VlessConfig.decode(rest.substring(hash + 1)).trim()
            rest = rest.substring(0, hash)
        }
        var query = ""
        val q = rest.indexOf('?')
        if (q >= 0) {
            query = rest.substring(q + 1)
            rest = rest.substring(0, q)
        }
        rest = rest.trimEnd('/')
        val at = rest.lastIndexOf('@')
        val userInfo = if (at >= 0) rest.substring(0, at) else ""
        val authority = if (at >= 0) rest.substring(at + 1) else rest
        var hostPort = authority.trim()
        if (allowPortList) {
            // "host:443,5000-6000": the first port is the one to dial, the rest is port hopping.
            val closing = hostPort.lastIndexOf(':')
            if (closing >= 0) {
                val tail = hostPort.substring(closing + 1).substringBefore(',').substringBefore('-')
                hostPort = hostPort.substring(0, closing + 1) + tail
            }
        }
        val endpoint = splitHostPort(hostPort)
        return Parts(userInfo, authority, endpoint.first, endpoint.second, VlessConfig.parseQuery(query), name)
    }

    private fun splitHostPort(value: String): Pair<String, Int> {
        val v = value.trim().trimEnd('/')
        if (v.startsWith("[")) {
            val closing = v.indexOf(']')
            if (closing < 0) return "" to -1
            val tail = v.substring(closing + 1)
            return v.substring(1, closing) to (if (tail.startsWith(":")) tail.substring(1).toIntOrNull() ?: -1 else -1)
        }
        val colon = v.lastIndexOf(':')
        if (colon < 0) return v to -1
        return v.substring(0, colon) to (v.substring(colon + 1).toIntOrNull() ?: -1)
    }

}

/** Base64 without android.util, so parsing runs anywhere and does not depend on an API level. */
internal object Base64Lite {
    private const val URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** Standard or URL-safe, padded or not, with or without line breaks; null when it is not base64. */
    fun decode(text: String): ByteArray? {
        val cleaned = text.filterNot { it.isWhitespace() }.trimEnd('=')
        if (cleaned.isEmpty()) return ByteArray(0)
        val out = java.io.ByteArrayOutputStream(cleaned.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (ch in cleaned) {
            val v = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun encodeUrl(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
            sb.append(URL[b0 shr 2])
            sb.append(URL[((b0 and 3) shl 4) or (if (b1 >= 0) b1 shr 4 else 0)])
            if (b1 >= 0) sb.append(URL[((b1 and 15) shl 2) or (if (b2 >= 0) b2 shr 6 else 0)])
            if (b2 >= 0) sb.append(URL[b2 and 63])
            i += 3
        }
        return sb.toString()
    }
}
