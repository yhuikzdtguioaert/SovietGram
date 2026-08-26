package sovietgram.com.proxy

import android.util.Base64
import okhttp3.Request
import tw.nekomimi.nekogram.utils.HttpClient
import java.net.URLDecoder

/**
 * Fetches and decodes a proxy subscription: a URL whose body is a list of share
 * links, usually base64-encoded, one per line.
 *
 * Never logs the subscription URL or any of the links it returns — both carry
 * credentials (the UUID is inside every vless:// URI).
 */
object VlessSubscription {

    /** Guards against a hostile/mistyped URL returning a huge body. */
    private const val MAX_BODY_BYTES = 2L * 1024 * 1024

    class FetchException(message: String) : Exception(message)

    /**
     * Downloads [url] and returns the usable server URIs it contains. Blocking —
     * callers must run it off the main thread.
     *
     * @throws FetchException on a bad URL, a transport error, an HTTP error or a
     *   body that contains no server this build can run.
     */
    @JvmStatic
    fun fetch(url: String): List<String> {
        val target = url.trim()
        if (target.isEmpty()) {
            throw FetchException("Empty subscription URL")
        }
        if (!target.startsWith("http://", true) && !target.startsWith("https://", true)) {
            throw FetchException("Subscription URL must start with http:// or https://")
        }
        val body = try {
            val request = Request.Builder()
                .url(target)
                .header("User-Agent", "SovietGram")
                .get()
                .build()
            HttpClient.instance.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    // Only the status code, never the URL.
                    throw FetchException("HTTP ${response.code}")
                }
                response.body?.source()?.let { source ->
                    source.request(MAX_BODY_BYTES + 1)
                    source.buffer.snapshot(
                        minOf(source.buffer.size, MAX_BODY_BYTES).toInt()
                    ).utf8()
                }.orEmpty()
            }
        } catch (e: FetchException) {
            throw e
        } catch (e: Throwable) {
            throw FetchException(e.javaClass.simpleName)
        }

        val servers = parse(body)
        if (servers.isEmpty()) {
            throw FetchException("No supported servers in subscription")
        }
        return servers
    }

    /**
     * Splits a subscription body into server URIs.
     *
     * The body is normally base64 (padded or not, sometimes URL-safe), but plenty
     * of providers serve the links as plain text, so both are accepted: base64 is
     * attempted first and the raw text is used when it does not decode into
     * anything recognisable.
     */
    @JvmStatic
    fun parse(body: String): List<String> {
        val direct = extractLinks(body)
        if (direct.isNotEmpty()) {
            // Already plain text.
            return direct
        }
        return extractLinks(decodeBase64(body))
    }

    private fun decodeBase64(body: String): String {
        // Strip everything base64 cannot contain (newlines the provider wrapped
        // the payload at, stray whitespace) before decoding, and drop any padding
        // so NO_PADDING/urlsafe variants all take the same path.
        val cleaned = body.filterNot { it.isWhitespace() }.trimEnd('=')
        if (cleaned.isEmpty()) {
            return ""
        }
        val flags = Base64.NO_PADDING or Base64.NO_WRAP
        for (extra in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
            val decoded = runCatching {
                String(Base64.decode(cleaned, flags or extra), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrEmpty()) {
                return decoded
            }
        }
        return ""
    }

    /**
     * Keeps only the lines the bundled core can actually run. VlessConfig builds a
     * vless outbound and nothing else, so vmess/trojan/ss entries — common in
     * mixed subscriptions — are dropped here instead of being offered and then
     * failing to start.
     */
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
            if (!line.startsWith("vless://", true)) {
                continue
            }
            if (VlessConfig.isValidVlessUrl(line)) {
                result.add(line)
            }
        }
        return result.toList()
    }

    /**
     * Human-readable label for a server row: the URI's #remark when it has one,
     * otherwise host:port. Never returns any part of the UUID or query string.
     */
    @JvmStatic
    fun displayName(uri: String): String {
        val trimmed = uri.trim()
        val fragmentIdx = trimmed.indexOf('#')
        if (fragmentIdx >= 0 && fragmentIdx < trimmed.length - 1) {
            val remark = runCatching {
                URLDecoder.decode(trimmed.substring(fragmentIdx + 1), "UTF-8")
            }.getOrElse { trimmed.substring(fragmentIdx + 1) }.trim()
            if (remark.isNotEmpty()) {
                return remark
            }
        }
        var rest = if (fragmentIdx >= 0) trimmed.substring(0, fragmentIdx) else trimmed
        val queryIdx = rest.indexOf('?')
        if (queryIdx >= 0) {
            rest = rest.substring(0, queryIdx)
        }
        val atIdx = rest.lastIndexOf('@')
        if (atIdx >= 0) {
            val authority = rest.substring(atIdx + 1).trim().trimEnd('/')
            if (authority.isNotEmpty()) {
                return authority
            }
        }
        return ""
    }
}
