package io.github.abdukir.klippercam.net

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    /** For short request/response calls (API, snapshots, signaling). */
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    /** For endless streams (MJPEG, H.264, WebSockets): no read timeout per call, keep-alive pings. */
    val streaming: OkHttpClient = client.newBuilder()
        .readTimeout(15, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
}
