package com.cameraviewer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Sender-side: serves saved person-detection snapshots on demand to a
 * viewer that requests them — the pull counterpart to AlertClient's push.
 * Same trust model as AlertReceiverService: plain HTTP, no auth, Tailscale
 * membership is the access control (see AlertClient's doc comment for the
 * reasoning already established elsewhere in this project).
 *
 * Routes:
 *  - GET /snapshots           → JSON array of {filename, timestampMs}, newest first
 *  - GET /snapshots/<file>    → the JPEG bytes, image/jpeg
 *  - DELETE /snapshots/<file> → deletes it, 200 if deleted / 404 if not found
 *  - POST /snapshots/capture  → saves a snapshot of whatever LiveFrameBus
 *    currently holds (the same frame source CameraDetectionService feeds
 *    the video relay from — see its own doc comment), regardless of
 *    whether a person was actually detected. A viewer's "Manual snapshot"
 *    button hits this — 200 if saved, 503 if no frame has arrived within
 *    CAPTURE_TIMEOUT_MS (camera app unreachable/not yet connected).
 * Anything else → 404. <file> is validated against SnapshotStore's strict
 * naming pattern before ever touching the filesystem — the actual defense
 * against a crafted path-traversal filename, not just a formality.
 */
class SnapshotServerService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopServer()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            job = scope.launch { runServer() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopServer() {
        job?.cancel()
        job = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runServer() {
        try {
            val server = ServerSocket(PORT)
            serverSocket = server
            Log.i(TAG, "serving snapshots on :$PORT")
            while (currentCoroutineContext().isActive) {
                val socket = try {
                    server.accept()
                } catch (e: Exception) {
                    if (currentCoroutineContext().isActive) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
                scope.launch { handleClient(socket) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "could not bind snapshot server port: ${e.message}")
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use {
            try {
                val input = it.getInputStream()
                val requestLine = readLine(input)
                // Drain remaining headers — nothing here needs them, but the
                // socket must be read past them before responding.
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty() || line == "\r\n") break
                }

                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val path = parts.getOrNull(1)?.substringBefore("?") ?: "/"
                when {
                    method == "GET" && path == "/snapshots" -> respondJson(it, listJson())
                    method == "POST" && path == "/snapshots/capture" -> handleManualCapture(it)
                    method == "GET" && path.startsWith("/snapshots/") ->
                        respondFile(it, SnapshotStore.fileFor(applicationContext, path.removePrefix("/snapshots/")))
                    method == "DELETE" && path.startsWith("/snapshots/") -> {
                        val deleted = SnapshotStore.delete(applicationContext, path.removePrefix("/snapshots/"))
                        if (deleted) respondEmpty(it, 200) else respondNotFound(it)
                    }
                    else -> respondNotFound(it)
                }
            } catch (e: Exception) {
                Log.w(TAG, "malformed request: ${e.message}")
            }
        }
    }

    private suspend fun handleManualCapture(socket: Socket) {
        val frameBytes = withTimeoutOrNull(CAPTURE_TIMEOUT_MS) { LiveFrameBus.frames.first() }
        if (frameBytes == null) {
            respondEmpty(socket, 503, "No frame available")
            return
        }
        val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
        if (bitmap == null) {
            respondEmpty(socket, 500, "Decode failed")
            return
        }
        val credentialStore = SecureCredentialStore(applicationContext)
        SnapshotStore.save(applicationContext, bitmap, credentialStore.snapshotRetentionCount)
        respondEmpty(socket, 200)
    }

    private fun listJson(): String {
        val arr = JSONArray()
        SnapshotStore.list(applicationContext).forEach { snap ->
            arr.put(
                JSONObject().apply {
                    put("filename", snap.filename)
                    put("timestampMs", snap.timestampMs)
                },
            )
        }
        return arr.toString()
    }

    private fun respondJson(socket: Socket, body: String) {
        val bytes = body.toByteArray()
        socket.getOutputStream().apply {
            write(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    private fun respondFile(socket: Socket, file: java.io.File?) {
        if (file == null) {
            respondNotFound(socket)
            return
        }
        val bytes = file.readBytes()
        socket.getOutputStream().apply {
            write(
                "HTTP/1.1 200 OK\r\nContent-Type: image/jpeg\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    .toByteArray(),
            )
            write(bytes)
            flush()
        }
    }

    private fun respondNotFound(socket: Socket) {
        respondEmpty(socket, 404, "Not Found")
    }

    private fun respondEmpty(socket: Socket, code: Int, reason: String = "OK") {
        socket.getOutputStream().apply {
            write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            flush()
        }
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code) break
            prev = b
        }
        return sb.toString()
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.channel_snapshot_server_name))
            .setContentText(getString(R.string.serving_snapshots))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_snapshot_server_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "SnapshotServerService"
        const val ACTION_STOP = "com.cameraviewer.app.action.STOP_SNAPSHOT_SERVER"
        const val PORT = 8791
        private const val CAPTURE_TIMEOUT_MS = 5_000L
        private const val CHANNEL_STATUS = "snapshot_server_status"
        private const val NOTIF_ID = 7
    }
}
