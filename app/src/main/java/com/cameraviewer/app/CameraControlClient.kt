package com.cameraviewer.app

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Camera-side: nudges the local Android IP Camera app to re-run autofocus
 * via its documented `focus_distance` query parameter (`-1` = auto,
 * `0..1` = manual), applied live to any request without needing to
 * reconnect the MJPEG stream. Discovered empirically that a stuck-focus
 * lens only cleared when the phone was physically moved — this recreates
 * the same effect (the lens is forced to actually move) without needing
 * a hand on the phone: briefly force a manual distance away from wherever
 * it's currently stuck, then hand control back to auto so it re-evaluates
 * from a different starting point. Just setting `-1` again on its own
 * wasn't assumed sufficient, since "already in auto mode" could plausibly
 * be a no-op for a driver that isn't actively re-scanning.
 *
 * One-shot HTTPS request (not a streaming connection), so this reuses
 * HttpsURLConnection + a trust-all SSLContext rather than MjpegClient's
 * raw-socket approach — same trust model as CameraProber/MjpegClient
 * (self-signed cert, already identified as this specific camera app).
 */
object CameraControlClient {
    private const val TAG = "CameraControlClient"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000
    private const val NUDGE_SETTLE_DELAY_MS = 500L

    suspend fun nudgeRefocus(ip: String, username: String, password: String) {
        setFocusDistance(ip, username, password, "0.5")
        delay(NUDGE_SETTLE_DELAY_MS)
        setFocusDistance(ip, username, password, "-1")
    }

    private suspend fun setFocusDistance(ip: String, username: String, password: String, value: String) =
        withContext(Dispatchers.IO) {
            var conn: HttpsURLConnection? = null
            try {
                val url = URL("https://$ip:${MjpegClient.CAMERA_PORT}/info.json?focus_distance=$value")
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = trustAllContext.socketFactory
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    val basic = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $basic")
                }
                val code = conn.responseCode
                if (code != 200) Log.w(TAG, "focus_distance=$value returned HTTP $code")
            } catch (e: Exception) {
                // Best-effort — a missed refocus nudge just means it'll be
                // hit on the next cycle, not something to disrupt detection.
                Log.w(TAG, "refocus request (focus_distance=$value) failed: ${e.message}")
            } finally {
                conn?.disconnect()
            }
        }

    private val trustAllContext: SSLContext by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
    }
}
