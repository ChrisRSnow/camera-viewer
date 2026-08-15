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
 * Camera-side: one-shot control requests against the local Android IP
 * Camera app's documented query-parameter controls (`focus_distance`,
 * `rotate`, ...), separate from the actual MJPEG stream connection
 * (MjpegClient). Deliberately NOT appended to the `/video/mjpeg` request
 * itself — the app's docs only demonstrate these on the root `/` path or
 * "control endpoints," not the streaming endpoint, and appending an
 * unexpected query string to `/video/mjpeg` is a plausible way to break
 * that request outright rather than just being ignored. Fired against `/`
 * instead, matching the documented example
 * (`/?torch=on&zoom=2.0`).
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

    /**
     * Nudges the camera to re-run autofocus. Discovered empirically that a
     * stuck-focus lens only cleared when the phone was physically moved —
     * this recreates the same effect (the lens is forced to actually
     * move) without needing a hand on the phone: briefly force a manual
     * distance away from wherever it's currently stuck, then hand control
     * back to auto so it re-evaluates from a different starting point.
     * Just setting `-1` again on its own wasn't assumed sufficient, since
     * "already in auto mode" could plausibly be a no-op for a driver
     * that isn't actively re-scanning.
     */
    suspend fun nudgeRefocus(ip: String, username: String, password: String) {
        // Kept on /info.json specifically (not the root path other
        // controls below use) - this exact endpoint was already confirmed
        // working via real on-device testing, so it's left alone rather
        // than "cleaned up" to match rotate=/mirror='s root-path usage
        // purely for consistency and risking a regression in a feature
        // that already works.
        setParam(ip, username, password, "focus_distance", "0.5", path = "/info.json")
        delay(NUDGE_SETTLE_DELAY_MS)
        setParam(ip, username, password, "focus_distance", "-1", path = "/info.json")
    }

    /**
     * Sets camera rotation (documented as "persisted per-camera," so this
     * only needs calling once per value change, not on every reconnect —
     * called once at the start of CameraDetectionService's detection
     * loop, before the actual stream connection). Root path, matching the
     * app's own documented example (`/?torch=on&zoom=2.0`) — unlike
     * focus_distance above, this one hasn't been proven against
     * /info.json, so it goes where the docs actually demonstrate it.
     */
    suspend fun setRotation(ip: String, username: String, password: String, degrees: Int) {
        setParam(ip, username, password, "rotate", degrees.toString(), path = "/")
    }

    private suspend fun setParam(ip: String, username: String, password: String, key: String, value: String, path: String) =
        withContext(Dispatchers.IO) {
            var conn: HttpsURLConnection? = null
            try {
                val url = URL("https://$ip:${MjpegClient.CAMERA_PORT}$path?$key=$value")
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = trustAllContext.socketFactory
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    val basic = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $basic")
                }
                val code = conn.responseCode
                if (code != 200) Log.w(TAG, "$key=$value returned HTTP $code")
            } catch (e: Exception) {
                // Best-effort — a missed control request just means it'll
                // be retried next cycle (refocus) or on the next connection
                // attempt (rotation), not something to disrupt detection.
                Log.w(TAG, "control request ($key=$value) failed: ${e.message}")
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
