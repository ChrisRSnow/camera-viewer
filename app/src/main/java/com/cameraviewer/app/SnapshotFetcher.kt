package com.cameraviewer.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

data class RemoteSnapshot(val filename: String, val timestampMs: Long)

/**
 * Distinguishes "reached the camera but it had no frame ready yet" (503 —
 * the local camera app connection is momentarily reconnecting, a known
 * flaky condition for this project, see ARCHITECTURE.md §1) from a genuine
 * network failure, so the UI can tell the user which one actually
 * happened instead of one generic "failed" message covering both.
 */
enum class ManualCaptureResult { SAVED, NO_FRAME_AVAILABLE, UNREACHABLE }

/**
 * Viewer-side client for SnapshotServerService — the pull counterpart to
 * TailscaleDiscovery/CameraProber's connection style: plain HTTP, same
 * trust model as everything else here (Tailscale membership is the access
 * control, see AlertClient's doc comment).
 */
object SnapshotFetcher {
    private const val TAG = "SnapshotFetcher"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000
    private const val MANUAL_CAPTURE_READ_TIMEOUT_MS = 8_000

    suspend fun list(ip: String): List<RemoteSnapshot> = withContext(Dispatchers.IO) {
        val body = getBytes("http://$ip:${SnapshotServerService.PORT}/snapshots") ?: return@withContext emptyList()
        val arr = JSONArray(String(body))
        (0 until arr.length()).mapNotNull { i ->
            val obj = arr.optJSONObject(i) ?: return@mapNotNull null
            val filename = obj.optString("filename", "")
            if (filename.isEmpty()) return@mapNotNull null
            RemoteSnapshot(filename, obj.optLong("timestampMs", 0L))
        }.sortedByDescending { it.timestampMs }
    }

    suspend fun fetchImage(ip: String, filename: String): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = getBytes("http://$ip:${SnapshotServerService.PORT}/snapshots/$filename") ?: return@withContext null
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /**
     * Triggers the sender to save a snapshot of whatever it's currently
     * seeing right now, regardless of whether a person was detected —
     * the "Manual snapshot" button's backing call. A longer read timeout
     * than other calls here: the sender may wait up to its own
     * CAPTURE_TIMEOUT_MS for a frame before responding.
     */
    suspend fun triggerManualCapture(ip: String): ManualCaptureResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://$ip:${SnapshotServerService.PORT}/snapshots/capture").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = MANUAL_CAPTURE_READ_TIMEOUT_MS
            val code = conn.responseCode
            when (code) {
                HttpURLConnection.HTTP_OK -> ManualCaptureResult.SAVED
                HttpURLConnection.HTTP_UNAVAILABLE -> ManualCaptureResult.NO_FRAME_AVAILABLE
                else -> {
                    Log.w(TAG, "manual snapshot trigger for $ip returned HTTP $code")
                    ManualCaptureResult.UNREACHABLE
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "manual snapshot trigger failed for $ip: ${e.message}")
            ManualCaptureResult.UNREACHABLE
        } finally {
            conn?.disconnect()
        }
    }

    /** Returns true if the sender confirmed deletion (HTTP 200). */
    suspend fun delete(ip: String, filename: String): Boolean = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = URL("http://$ip:${SnapshotServerService.PORT}/snapshots/$filename").openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.w(TAG, "delete failed for $ip/$filename: ${e.message}")
            false
        } finally {
            conn?.disconnect()
        }
    }

    private fun getBytes(url: String): ByteArray? {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "$url returned HTTP ${conn.responseCode}")
                return null
            }
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed for $url: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
