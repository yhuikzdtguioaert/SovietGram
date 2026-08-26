package tw.nekomimi.nekogram.utils

import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object HttpClient {

    @JvmField
    val MEDIA_TYPE_JSON: MediaType = "application/json; charset=utf-8".toMediaType()

    val instance: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val llmInstance: OkHttpClient by lazy {
        instance.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * For `POST /v1/media`: a banner or background is megabytes, not the few hundred bytes every
     * other API call sends. The shared 30-second call budget would abort a perfectly healthy upload
     * on mobile data long before the body finished going out.
     *
     * The budget covers the worst case the route accepts — 50MB of video, which is 67MB base64 on
     * the wire — over a slow mobile link, plus the server writing it out in 512KB rows. The read
     * and write timeouts are per socket operation, so they only fire on a link that has actually
     * stalled; `callTimeout` is the one that bounds the whole upload, and 20 minutes for 67MB means
     * giving up below roughly 56 KB/s.
     *
     * It has to stay under the upload signature's own 30-minute freshness window: a transfer this
     * client is still willing to finish must not arrive with a signature the server calls stale,
     * because the client would have spent the entire upload to be told `bad_signature`.
     */
    val uploadInstance: OkHttpClient by lazy {
        instance.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .callTimeout(1200, TimeUnit.SECONDS)
            .build()
    }

    val transcribeInstance: OkHttpClient by lazy {
        llmInstance.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(180, TimeUnit.SECONDS)
            .build()
    }
}
