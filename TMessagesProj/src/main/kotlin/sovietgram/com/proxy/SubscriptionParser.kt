package sovietgram.com.proxy

import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns a subscription body into servers. Pure, so it can be run on any text a provider might send.
 *
 * A body is either a JSON array of finished Xray configs (what providers serve to Happ: one config per
 * server) or a list of share links, base64 or plain.
 */
object SubscriptionParser {

    /**
     * Splits a subscription body into servers: a JSON array of Xray configs, or share links (the body is
     * normally base64, padded or not, sometimes URL-safe, but plenty of providers serve plain text).
     *
     * @param excludeFilter a regex over server names; matching servers are left out, as Happ does with
     *   the provider's `Exclude-Filter` header.
     */
    @JvmStatic
    @JvmOverloads
    fun parse(body: String, excludeFilter: String? = null): List<String> {
        val text = body.trimStart('﻿', ' ', '\n', '\r', '\t')
        val found = when {
            text.startsWith("[") || text.startsWith("{") -> parseJson(text).ifEmpty { extractLinks(text) }
            else -> extractLinks(text).ifEmpty { extractLinks(decodeBase64(text)) }
        }
        return applyFilter(found, excludeFilter)
    }

    private fun applyFilter(servers: List<String>, filter: String?): List<String> {
        val pattern = filter?.trim().orEmpty()
        if (pattern.isEmpty()) return servers
        // The filter comes from the provider's headers: a long or nested pattern is how a catastrophic
        // backtrack is written, so only a short one is run, and only over a bounded part of each name.
        if (pattern.length > 300) return servers
        val regex = runCatching { Regex(pattern) }.getOrNull() ?: return servers
        val kept = servers.filterNot { regex.containsMatchIn(ProxyLinks.displayName(it).take(120)) }
        // A filter that would leave nothing is the provider's mistake, not a reason to show an empty list.
        return kept.ifEmpty { servers }
    }

    // ------------------------------------------------------------------ JSON (Xray configs)

    private val CARRIERS = setOf("vless", "vmess", "trojan", "shadowsocks", "hysteria")

    private fun parseJson(text: String): List<String> {
        val configs = ArrayList<JSONObject>()
        try {
            if (text.startsWith("[")) {
                val array = JSONArray(text)
                for (i in 0 until array.length()) {
                    array.optJSONObject(i)?.let { configs.add(it) }
                }
            } else {
                configs.add(JSONObject(text))
            }
        } catch (_: Throwable) {
            return emptyList()
        }
        val result = LinkedHashSet<String>()
        for (config in configs) {
            val entry = entryOf(config) ?: continue
            if (ProxyLinks.isValid(entry)) {
                result.add(entry)
            }
        }
        return result.toList()
    }

    /** One server out of one Xray config: its proxy outbounds, and what they dial through. */
    private fun entryOf(config: JSONObject): String? {
        val outbounds = config.optJSONArray("outbounds") ?: return null
        val all = ArrayList<JSONObject>()
        for (i in 0 until outbounds.length()) {
            outbounds.optJSONObject(i)?.let { all.add(it) }
        }
        fun carries(o: JSONObject): Boolean {
            val protocol = o.optString("protocol")
            if (protocol !in CARRIERS) return false
            // Hysteria 1 is a different protocol the bundled core does not speak.
            if (protocol == "hysteria" && o.optJSONObject("settings")?.optInt("version", 2) != 2) return false
            return true
        }
        var proxies = all.filter { carries(it) && it.optString("tag").startsWith("proxy") }
        if (proxies.isEmpty()) {
            proxies = all.filter { carries(it) }.take(1)
        }
        if (proxies.isEmpty()) return null

        val kept = ArrayList<JSONObject>(proxies)
        var index = 0
        while (index < kept.size) {
            val dialer = kept[index].optJSONObject("streamSettings")?.optJSONObject("sockopt")
                ?.optString("dialerProxy").orEmpty()
            if (dialer.isNotEmpty() && kept.none { it.optString("tag") == dialer }) {
                all.firstOrNull { it.optString("tag") == dialer }?.let { kept.add(it) }
            }
            index++
        }
        val name = config.optString("remarks").trim().ifEmpty {
            config.optString("name").trim()
        }
        return ProxyLinks.wrap(name, kept)
    }

    // ------------------------------------------------------------------ share links

    private fun decodeBase64(body: String): String {
        val bytes = Base64Lite.decode(body) ?: return ""
        return String(bytes, Charsets.UTF_8)
    }

    /** Keeps the lines that are a link the bundled core can run. */
    private fun extractLinks(text: String): List<String> {
        if (text.isEmpty()) {
            return emptyList()
        }
        val result = LinkedHashSet<String>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim().trim('\r', '﻿')
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue
            }
            if (line.startsWith(ProxyLinks.WRAPPED, true) || !ProxyLinks.isSupported(line)) {
                continue
            }
            if (ProxyLinks.isValid(line)) {
                result.add(line)
            }
        }
        return result.toList()
    }

}
