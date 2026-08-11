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
 * Viewer-side client for SnapshotServerService — the pull counterpart to
 * TailscaleDiscovery/CameraProber's connection style: plain HTTP, same
 * trust model as everything else here (Tailscale membership is the access
 * control, see AlertClient's doc comment).
 */
object SnapshotFetcher {
    private const val TAG = "SnapshotFetcher"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

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
