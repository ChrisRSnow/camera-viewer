package com.cameraviewer.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Camera-side: POSTs a "person detected" alert to one or more viewer phones
 * over Tailscale. Plain HTTP, no auth on the alert endpoint itself — same
 * trust model as the rest of this project (Tailscale network membership IS
 * the access control; see the "no auth on the dashboard page" note in
 * IPCameraDash/ARCHITECTURE.md for the same tradeoff made there).
 *
 * Best-effort by design: a viewer phone being offline/unreachable is a
 * normal, expected condition (not every viewer is watching all the time),
 * so a failed POST to one target is logged and skipped, never thrown —
 * other targets still get tried, and a failed alert never blocks or crashes
 * the camera-side detection loop.
 */
object AlertClient {
    private const val TAG = "AlertClient"
    const val ALERT_PORT = 8790
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 4_000

    /**
     * [cameraIp] is this camera's own Tailscale IP (from LocalTailscaleIp),
     * included so the viewer can connect to this exact camera's stream
     * instead of falling back to generic "first camera found" discovery —
     * the fix for multi-camera routing. Omitted from the payload if unknown.
     */
    suspend fun sendAlert(targets: List<String>, cameraLabel: String, cameraIp: String?) = withContext(Dispatchers.IO) {
        val body = JSONObject().apply {
            put("label", cameraLabel)
            put("ts", System.currentTimeMillis())
            if (!cameraIp.isNullOrBlank()) put("ip", cameraIp)
        }.toString()

        targets.map { target ->
            async { postOne(target.trim(), body) }
        }.awaitAll()
    }

    private fun postOne(target: String, body: String) {
        if (target.isEmpty()) return
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://$target:$ALERT_PORT/alert").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "alert to $target returned HTTP $code")
            }
        } catch (e: Exception) {
            // Expected when the viewer isn't reachable right now — not an error.
            Log.i(TAG, "alert to $target not delivered: ${e.message}")
        } finally {
            conn?.disconnect()
        }
    }
}
