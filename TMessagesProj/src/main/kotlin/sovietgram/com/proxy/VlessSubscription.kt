package sovietgram.com.proxy

import android.os.Build
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import tw.nekomimi.nekogram.utils.HttpClient

/**
 * Fetches and decodes a proxy subscription. Two body formats are understood:
 *
 *  - a list of share links (vless://, vmess://, trojan://, ss://, hysteria2://), base64 or plain text;
 *  - a JSON array of finished Xray configs, one per server, which is what providers serve to Happ. Each
 *    entry becomes one server, made of the outbounds in it that carry traffic.
 *
 * Providers often answer only the client they expect. The request therefore goes out the way Happ makes
 * it — its User-Agent, a stable hardware id and the device headers — unless the user switched that off; a
 * provider that checks which app is asking then hands over the real server list instead of a placeholder.
 *
 * Never logs the subscription URL or any of the links it returns — both carry credentials.
 */
object VlessSubscription {

    /** Guards against a hostile/mistyped URL returning a huge body. */
    private const val MAX_BODY_BYTES = 8L * 1024 * 1024

    private const val HAPP_VERSION = "2.7.0"

    class FetchException(message: String) : Exception(message)

    class Result(val servers: List<String>, val info: XraySettings.SubscriptionInfo)

    // The provider is asked at most once in a while for the same link: a panel that registers a device on
    // every request would otherwise be hammered by a few quick taps on "Refresh".
    private const val REUSE_MS = 20_000L
    private var lastUrl = ""
    private var lastAt = 0L
    private var lastResult: Result? = null

    /** Kept for callers that only want the servers. */
    @JvmStatic
    fun fetch(url: String): List<String> = fetchDetailed(url).servers

    /**
     * Downloads [url] and returns the servers it contains with what the provider says about the profile.
     * Blocking — callers must run it off the main thread.
     *
     * @throws FetchException on a bad URL, a transport error, an HTTP error or a body without a server
     *   this build can run.
     */
    @JvmStatic
    fun fetchDetailed(url: String): Result {
        val target = url.trim()
        if (target.isEmpty()) {
            throw FetchException("Empty subscription URL")
        }
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) {
            throw FetchException("Subscription URL must start with http:// or https://")
        }
        synchronized(this) {
            val held = lastResult
            if (held != null && lastUrl == target && System.currentTimeMillis() - lastAt < REUSE_MS) {
                return held
            }
        }
        val settings = XraySettings.snapshot()
        val response = try {
            download(target, settings.maskAsHapp)
        } catch (first: FetchException) {
            // The provider publishes a mirror for when its main address is blocked; try it for the same path.
            val mirror = mirrorOf(target)
                ?: throw first
            try {
                download(mirror, settings.maskAsHapp)
            } catch (_: FetchException) {
                throw first
            }
        }

        val headers = response.second
        val servers = parse(response.first, headers["exclude-filter"])
        if (servers.isEmpty()) {
            throw FetchException("No supported servers in subscription")
        }
        val info = XraySettings.SubscriptionInfo(
            title = decodeHeader(headers["profile-title"]),
            userInfo = headers["subscription-userinfo"].orEmpty(),
            fallbackUrl = (headers["fallback-url"] ?: headers["x-sub-fallback"]).orEmpty(),
            supportUrl = headers["support-url"].orEmpty(),
            webPageUrl = headers["profile-web-page-url"].orEmpty(),
            announce = decodeHeader(headers["announce"]),
            updatedAt = System.currentTimeMillis()
        )
        val result = Result(servers, info)
        synchronized(this) {
            lastUrl = target
            lastAt = System.currentTimeMillis()
            lastResult = result
        }
        return result
    }

    private fun mirrorOf(target: String): String? {
        val base = XraySettings.subscriptionInfo().fallbackUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val http = target.toHttpUrlOrNull() ?: return null
        val path = http.encodedPath + (http.encodedQuery?.let { "?$it" } ?: "")
        return base + path
    }

    private fun download(target: String, maskAsHapp: Boolean): Pair<String, Map<String, String>> {
        return try {
            val builder = Request.Builder().url(target).get()
            if (maskAsHapp) {
                builder.header("User-Agent", "Happ/$HAPP_VERSION/android")
                builder.header("Accept", "*/*")
            } else {
                builder.header("User-Agent", "SovietGram")
            }
            // The device is announced the same way every time, masked or not: a panel that counts
            // devices by these headers then sees one device, instead of one more for every fetch that
            // arrived without them.
            builder.header("x-hwid", XraySettings.hwid())
            builder.header("x-device-os", "Android")
            builder.header("x-ver-os", asciiOnly(Build.VERSION.RELEASE))
            builder.header("x-device-model", asciiOnly(Build.MODEL))
            HttpClient.instance.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    // Only the status code, never the URL.
                    throw FetchException("HTTP ${response.code}")
                }
                val text = response.body?.source()?.let { source ->
                    source.request(MAX_BODY_BYTES + 1)
                    source.buffer.snapshot(
                        minOf(source.buffer.size, MAX_BODY_BYTES).toInt()
                    ).utf8()
                }.orEmpty()
                val headers = HashMap<String, String>()
                for (name in response.headers.names()) {
                    headers[name.lowercase()] = response.header(name).orEmpty()
                }
                text to headers
            }
        } catch (e: FetchException) {
            throw e
        } catch (e: Throwable) {
            throw FetchException(e.javaClass.simpleName)
        }
    }

    private fun asciiOnly(value: String?): String =
        (value ?: "").filter { it.code in 32..126 }.ifEmpty { "unknown" }

    /** Providers send titles and notices as `base64:<text>` so non-ASCII survives a header. */
    private fun decodeHeader(value: String?): String {
        val v = value?.trim().orEmpty()
        if (v.startsWith("base64:", ignoreCase = true)) {
            val bytes = Base64Lite.decode(v.substring(7)) ?: return ""
            return String(bytes, Charsets.UTF_8).trim()
        }
        return v
    }

    @JvmStatic
    @JvmOverloads
    fun parse(body: String, excludeFilter: String? = null): List<String> =
        SubscriptionParser.parse(body, excludeFilter)

    /**
     * Human-readable label for a server row: the remark when it has one, otherwise host:port. Never
     * returns any part of the UUID, a password or the query string.
     */
    @JvmStatic
    fun displayName(uri: String): String = ProxyLinks.displayName(uri)
}
