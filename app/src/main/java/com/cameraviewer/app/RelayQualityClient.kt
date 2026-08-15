package com.cameraviewer.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Viewer-side: tells a sender's VideoRelayServerService to change camera
 * resolution — the client half of NetworkQualityMonitor's cellular-aware
 * quality feature. Plain HTTP, no auth, same trust model as the rest of
 * this project (see AlertClient's doc comment). Best-effort: a failed
 * request just means quality doesn't adapt this time, not something to
 * disrupt video playback over.
 */
object RelayQualityClient {
    private const val TAG = "RelayQualityClient"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000

    suspend fun setQuality(ip: String, level: String) = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://$ip:${VideoRelayServerService.PORT}/quality?level=$level").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) Log.w(TAG, "quality=$level to $ip returned HTTP $code")
        } catch (e: Exception) {
            Log.w(TAG, "quality request to $ip failed: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
