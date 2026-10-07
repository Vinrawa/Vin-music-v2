package com.vinmusic.innertube

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NPRequest
import org.schabi.newpipe.extractor.downloader.Response as NPResponse
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.concurrent.TimeUnit

private const val TAG = "VIN_NP"

class NewPipeDownloader(private val client: OkHttpClient) : Downloader() {
    override fun execute(request: NPRequest): NPResponse {
        val builder = Request.Builder().url(request.url())
        request.headers().forEach { (k, values) -> values.forEach { builder.addHeader(k, it) } }
        val body = request.dataToSend()
        if (body != null) builder.post(body.toRequestBody("application/json".toMediaType()))
        else if (request.httpMethod() == "POST") builder.post("".toRequestBody())
        // .use{} guarantees the response (and its connection) is returned to the
        // pool even if reading the body throws mid-stream.
        return client.newCall(builder.build()).execute().use { resp ->
            NPResponse(
                resp.code, resp.message,
                resp.headers.toMultimap(),
                resp.body?.string(),
                request.url()
            )
        }
    }
}

object NewPipeInit {
    private var initialized = false

    fun init() {
        if (initialized) return
        Log.d(TAG, "Initializing NewPipeExtractor...")
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                val req = chain.request()
                val existing = req.header("Cookie")
                val missing = mutableListOf<String>()
                if (existing.isNullOrBlank() || !existing.contains("SOCS=")) missing.add("SOCS=CAI")
                if (existing.isNullOrBlank() || !existing.contains("CONSENT=")) missing.add("CONSENT=YES+1")

                val finalCookie = when {
                    existing.isNullOrBlank() -> missing.joinToString("; ")
                    missing.isNotEmpty() -> "$existing; ${missing.joinToString("; ")}"
                    else -> existing
                }

                val newReq = req.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0.0.0")
                    .header("Cookie", finalCookie)
                chain.proceed(newReq.build())
            }
            .build()

        // Init with India content country — improves music availability
        NewPipe.init(
            NewPipeDownloader(client),
            Localization.DEFAULT,
            ContentCountry("IN")
        )
        initialized = true
        Log.d(TAG, "NewPipeExtractor initialized (gl=IN)")
    }
}
