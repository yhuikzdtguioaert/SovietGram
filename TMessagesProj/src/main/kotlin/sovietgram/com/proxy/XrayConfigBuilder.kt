package sovietgram.com.proxy

import org.json.JSONArray
import org.json.JSONObject

/**
 * The Xray config the tunnel runs: one local SOCKS5 inbound, the chosen server's outbound(s), and the
 * options the user switched on in [XraySettings]. A pure function of its inputs, so every config the
 * app can build is something a test can build and a core can be asked to load.
 */
object XrayConfigBuilder {

    private const val FRAGMENT_TAG = "fragment"
    private const val NOISE_TAG = "noise"
    private val TUNNEL_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks")

    @JvmStatic
    fun build(
        rawUrl: String,
        socksPort: Int,
        socksUser: String,
        socksPass: String,
        settings: XrayOptions.Snapshot
    ): String {
        if (socksPort !in 1..65535) {
            throw VlessConfig.ParseException("Invalid SOCKS port: $socksPort")
        }
        val all = try {
            ProxyLinks.outbounds(rawUrl).map { JSONObject(it.toString()) }
        } catch (e: ProxyLinks.LinkException) {
            throw VlessConfig.ParseException(e.message.orEmpty())
        } catch (e: VlessConfig.ParseException) {
            throw e
        } catch (e: Exception) {
            throw VlessConfig.ParseException("Unreadable server entry")
        }
        val proxies = all.filter { it.optString("tag").startsWith("proxy") }.ifEmpty { listOf(all.first()) }
        val helpers = all.filter { it !in proxies }
        if (proxies.none { it.optString("tag") == "proxy" }) {
            proxies.first().put("tag", "proxy")
        }

        var needFragment = false
        var needNoise = false
        for (outbound in proxies) {
            val kind = tune(outbound, settings)
            needFragment = needFragment || kind == Dialer.FRAGMENT
            needNoise = needNoise || kind == Dialer.NOISE
        }

        val inbound = JSONObject().apply {
            put("listen", "127.0.0.1")
            put("port", socksPort)
            put("protocol", "socks")
            put("tag", "socks-in")
            put("settings", JSONObject().apply {
                put("udp", true)
                if (socksUser.isNotEmpty() && socksPass.isNotEmpty()) {
                    put("auth", "password")
                    put("accounts", JSONArray().put(JSONObject().apply {
                        put("user", socksUser)
                        put("pass", socksPass)
                    }))
                } else {
                    put("auth", "noauth")
                }
            })
            // No sniffing: this inbound only carries Telegram's MTProto stream, which is neither HTTP
            // nor TLS, and there are no domain-based routing rules that would need the destination.
        }

        val dnsServer = XrayOptions.DNS_SERVERS[settings.dns]
        val outbounds = JSONArray()
        proxies.forEach { outbounds.put(it) }
        helpers.forEach { outbounds.put(it) }
        outbounds.put(JSONObject().apply {
            put("protocol", "freedom")
            put("tag", "direct")
        })
        outbounds.put(JSONObject().apply {
            put("protocol", "blackhole")
            put("tag", "block")
        })
        if (needFragment) {
            outbounds.put(JSONObject().apply {
                put("tag", FRAGMENT_TAG)
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("fragment", JSONObject().apply {
                        put("packets", settings.fragmentPackets)
                        put("length", settings.fragmentLength)
                        put("interval", settings.fragmentInterval)
                        if (settings.fragmentMaxSplit.isNotBlank()) {
                            put("maxSplit", settings.fragmentMaxSplit)
                        }
                    })
                    if (dnsServer != null) put("domainStrategy", "UseIPv4")
                })
                put("streamSettings", JSONObject().apply {
                    put("sockopt", JSONObject().apply { put("tcpNoDelay", true) })
                })
            })
        }
        if (needNoise) {
            outbounds.put(JSONObject().apply {
                put("tag", NOISE_TAG)
                put("protocol", "freedom")
                put("settings", JSONObject().apply {
                    put("noises", JSONArray().put(JSONObject().apply {
                        put("type", settings.noiseType)
                        put("packet", settings.noisePacket)
                        put("delay", settings.noiseDelay)
                        put("applyTo", settings.noiseApplyTo)
                    }))
                    if (dnsServer != null) put("domainStrategy", "UseIPv4")
                })
            })
        }

        val config = JSONObject().apply {
            put("log", JSONObject().apply { put("loglevel", "warning") })
            // An empty "stats" object plus statsOutboundUplink/Downlink is what makes Xray register the
            // outbound>>>proxy>>>traffic>>> counters the notification reads.
            put("stats", JSONObject())
            put("policy", JSONObject().apply {
                put("system", JSONObject().apply {
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                })
            })
            if (dnsServer != null) {
                put("dns", JSONObject().apply {
                    put("servers", JSONArray().put(dnsServer))
                    put("queryStrategy", "UseIPv4")
                })
            }
            put("inbounds", JSONArray().put(inbound))
            put("outbounds", outbounds)
            if (proxies.size > 1) {
                // A provider's "automatic" entry: all of its servers, the least loaded one carries traffic.
                put("routing", JSONObject().apply {
                    put("domainStrategy", "AsIs")
                    put("balancers", JSONArray().put(JSONObject().apply {
                        put("tag", "auto")
                        put("selector", JSONArray().put("proxy"))
                        put("strategy", JSONObject().apply {
                            put("type", "leastLoad")
                            put("settings", JSONObject().apply {
                                put("expected", 3)
                                put("maxRTT", "1500ms")
                                put("tolerance", 0.05)
                                put("baselines", JSONArray().put("80ms").put("150ms").put("300ms"))
                            })
                        })
                    }))
                    put("rules", JSONArray().put(JSONObject().apply {
                        put("type", "field")
                        put("inboundTag", JSONArray().put("socks-in"))
                        put("balancerTag", "auto")
                    }))
                })
                put("burstObservatory", JSONObject().apply {
                    put("subjectSelector", JSONArray().put("proxy"))
                    put("pingConfig", JSONObject().apply {
                        put("destination", "http://www.gstatic.com/generate_204")
                        put("interval", "30s")
                        put("timeout", "5s")
                        put("sampling", 3)
                    })
                })
            }
        }
        return config.toString()
    }

    private enum class Dialer { NONE, FRAGMENT, NOISE }

    /** Applies the user's options to one proxy outbound and says which helper outbound it now dials through. */
    private fun tune(outbound: JSONObject, s: XrayOptions.Snapshot): Dialer {
        val protocol = outbound.optString("protocol")
        val stream = outbound.optJSONObject("streamSettings")
        val network = stream?.optString("network").orEmpty()
        val udpBased = protocol == "hysteria" || network == "hysteria"

        if (stream != null) {
            for (key in arrayOf("tlsSettings", "realitySettings")) {
                val tls = stream.optJSONObject(key) ?: continue
                val current = tls.optString("fingerprint")
                if (s.fingerprint.isNotEmpty()) {
                    tls.put("fingerprint", s.fingerprint)
                } else if (current.isEmpty()) {
                    // A handshake that looks like a browser's is what keeps DPI from recognising the tunnel.
                    tls.put("fingerprint", "chrome")
                }
            }
        }

        if (s.muxEnabled && protocol in TUNNEL_PROTOCOLS && !udpBased
            && network != "grpc" && network != "xhttp" && !usesVision(outbound)
        ) {
            outbound.put("mux", JSONObject().apply {
                put("enabled", true)
                put("concurrency", s.muxConcurrency)
                put("xudpConcurrency", s.muxXudpConcurrency)
                put("xudpProxyUDP443", s.muxXudpProxyUdp443)
            })
        }

        val wanted = when {
            udpBased && s.noisesEnabled -> Dialer.NOISE
            !udpBased && s.fragmentEnabled -> Dialer.FRAGMENT
            else -> Dialer.NONE
        }
        val withStream = stream ?: JSONObject().also { outbound.put("streamSettings", it) }
        val sockopt = withStream.optJSONObject("sockopt") ?: JSONObject().also { withStream.put("sockopt", it) }
        if (wanted != Dialer.NONE && sockopt.optString("dialerProxy").isEmpty()) {
            sockopt.put("dialerProxy", if (wanted == Dialer.FRAGMENT) FRAGMENT_TAG else NOISE_TAG)
        }
        if (XrayOptions.DNS_SERVERS.containsKey(s.dns) && sockopt.optString("domainStrategy").isEmpty()) {
            sockopt.put("domainStrategy", "UseIPv4")
        }
        if (sockopt.length() == 0) {
            withStream.remove("sockopt")
        }
        return if (sockopt.optString("dialerProxy") == FRAGMENT_TAG) Dialer.FRAGMENT
        else if (sockopt.optString("dialerProxy") == NOISE_TAG) Dialer.NOISE
        else Dialer.NONE
    }

    private fun usesVision(outbound: JSONObject): Boolean {
        val settings = outbound.optJSONObject("settings") ?: return false
        // Newer cores also take the flat form: address, port, id and flow side by side.
        if (settings.optString("flow").isNotEmpty()) return true
        val vnext = settings.optJSONArray("vnext") ?: return false
        for (i in 0 until vnext.length()) {
            val users = vnext.optJSONObject(i)?.optJSONArray("users") ?: continue
            for (j in 0 until users.length()) {
                if (users.optJSONObject(j)?.optString("flow").orEmpty().isNotEmpty()) return true
            }
        }
        return false
    }
}
