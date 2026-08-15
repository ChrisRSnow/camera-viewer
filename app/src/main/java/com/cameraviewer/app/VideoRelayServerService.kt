package com.cameraviewer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Sender-side: re-serves the single MJPEG stream CameraDetectionService is
 * already pulling from the local camera app to any number of remote
 * viewers, via LiveFrameBus. This exists specifically so a viewer watching
 * over Tailscale never opens its own direct connection to the camera app —
 * see LiveFrameBus's doc comment for why that was the actual cause of the
 * "stream freezes after a few minutes, both sides stuck on the last frame"
 * bug: the camera app's single-viewer encoder degrading under two
 * simultaneous connections (sender's own detection loop + a remote viewer).
 *
 * Same trust model as AlertReceiverService/SnapshotServerService: plain
 * HTTP, no auth, Tailscale membership is the access control.
 *
 * Routes:
 *  - GET /video/mjpeg → multipart/x-mixed-replace MJPEG stream, one
 *    boundary chunk per frame published to LiveFrameBus, for as long as
 *    the client stays connected.
 *  - POST /quality?level=<value> → sets the camera app's capture
 *    resolution (`low`, `auto`, etc. — see CameraControlClient.setResolution)
 *    for cellular-aware quality (NetworkQualityMonitor, viewer-side). Not
 *    per-viewer — the camera app has exactly one resolution at a time, so
 *    this affects every connected viewer and the sender's own preview,
 *    not just whoever requested it. 200 if applied, 400 if `level` is
 *    missing/empty.
 */
class VideoRelayServerService : Service() {

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
            Log.i(TAG, "relaying video on :$PORT")
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
            Log.e(TAG, "could not bind video relay port: ${e.message}")
        }
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use {
            try {
                val input = it.getInputStream()
                val requestLine = readLine(input)
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty() || line == "\r\n") break
                }
                val parts = requestLine.split(" ")
                val method = parts.getOrNull(0) ?: "GET"
                val rawPath = parts.getOrNull(1) ?: "/"
                val path = rawPath.substringBefore("?")
                when {
                    method == "GET" && path == "/video/mjpeg" -> streamToClient(it)
                    method == "POST" && path == "/quality" -> handleSetQuality(it, rawPath.substringAfter("?", ""))
                    else -> {
                        it.getOutputStream().apply {
                            write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                            flush()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "relay client error: ${e.message}")
            }
        }
    }

    private suspend fun handleSetQuality(socket: Socket, query: String) {
        val level = query.split("&")
            .map { it.split("=", limit = 2) }
            .firstOrNull { it.getOrNull(0) == "level" }
            ?.getOrNull(1)
        val respond: (Int) -> Unit = { code ->
            socket.getOutputStream().apply {
                write("HTTP/1.1 $code ${if (code == 200) "OK" else "Bad Request"}\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
        }
        if (level.isNullOrBlank()) {
            respond(400)
            return
        }
        val credentialStore = SecureCredentialStore(applicationContext)
        val username = credentialStore.cameraUsername
        val password = credentialStore.cameraPassword
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            respond(400)
            return
        }
        runCatching { CameraControlClient.setResolution(LOCAL_CAMERA_IP, username, password, level) }
        respond(200)
    }

    private suspend fun streamToClient(socket: Socket) {
        val out = socket.getOutputStream()
        out.write(
            (
                "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: multipart/x-mixed-replace; boundary=$BOUNDARY\r\n" +
                    "Connection: close\r\n\r\n"
                ).toByteArray(),
        )
        out.flush()
        LiveFrameBus.frames.collect { frame ->
            out.write(
                (
                    "--$BOUNDARY\r\n" +
                        "Content-Type: image/jpeg\r\n" +
                        "Content-Length: ${frame.size}\r\n\r\n"
                    ).toByteArray(),
            )
            out.write(frame)
            out.write("\r\n".toByteArray())
            out.flush()
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
            .setContentTitle(getString(R.string.channel_video_relay_name))
            .setContentText(getString(R.string.relaying_video))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_video_relay_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "VideoRelayServerService"
        const val ACTION_STOP = "com.cameraviewer.app.action.STOP_VIDEO_RELAY"
        const val PORT = 8792
        private const val LOCAL_CAMERA_IP = "127.0.0.1"
        private const val BOUNDARY = "frame"
        private const val CHANNEL_STATUS = "video_relay_status"
        private const val NOTIF_ID = 8
    }
}
