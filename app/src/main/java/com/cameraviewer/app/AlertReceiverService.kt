package com.cameraviewer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Viewer-side role: a tiny always-listening HTTP server that receives
 * person-detected alerts POSTed by camera phones running
 * CameraDetectionService, and turns each one into a real notification —
 * "the apps send messages to a viewer phone" from the design discussion.
 * Hand-rolled raw-socket HTTP parsing on purpose, matching the same
 * lightweight approach already used by mjpeg_relay.py and MjpegClient
 * rather than pulling in a servlet/ktor dependency for one tiny endpoint.
 *
 * Bindable (mirrors CameraMonitorService's LocalBinder/StateFlow pattern)
 * purely so MainActivity can show a real "Listening" indicator in the app
 * itself instead of only the OS notification shade.
 */
class AlertReceiverService : Service() {

    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null

    private val binder = LocalBinder()
    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): AlertReceiverService = this@AlertReceiverService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopListening()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID_STATUS,
                buildStatusNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            job = scope.launch { runServer() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopListening()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopListening() {
        job?.cancel()
        job = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _isListening.value = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runServer() {
        try {
            val server = ServerSocket(AlertClient.ALERT_PORT)
            serverSocket = server
            _isListening.value = true
            Log.i(TAG, "listening for camera alerts on :${AlertClient.ALERT_PORT}")
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
            Log.e(TAG, "could not bind alert listener port: ${e.message}")
        } finally {
            _isListening.value = false
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use {
            try {
                val input = it.getInputStream()
                var contentLength = 0
                while (true) {
                    val line = readLine(input)
                    if (line.isEmpty() || line == "\r\n") break
                    if (line.startsWith("Content-Length:", ignoreCase = true)) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val bodyBytes = ByteArray(contentLength)
                var readTotal = 0
                while (readTotal < contentLength) {
                    val n = input.read(bodyBytes, readTotal, contentLength - readTotal)
                    if (n < 0) break
                    readTotal += n
                }
                it.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    flush()
                }

                val json = JSONObject(String(bodyBytes))
                val label = json.optString("label", "camera")
                val cameraIp = json.optString("ip", "")
                fireAlertNotification(label, cameraIp)
            } catch (e: Exception) {
                Log.w(TAG, "malformed alert request: ${e.message}")
            }
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

    private fun fireAlertNotification(label: String, cameraIp: String) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_AUTO_CONNECT, true)
            // Lets the viewer connect straight to THIS camera instead of
            // falling back to generic "first camera found" discovery, which
            // is what made multi-camera setups route to the wrong stream.
            if (cameraIp.isNotBlank()) {
                putExtra(MainActivity.EXTRA_CAMERA_IP, cameraIp)
                putExtra(MainActivity.EXTRA_CAMERA_LABEL, label)
            }
        }
        val pending = PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_ALERTS)
            .setContentTitle(getString(R.string.person_detected_title, label))
            .setContentText(getString(R.string.tap_to_view))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            // Auto-launches straight to the live view (same destination as
            // tapping) without requiring the tap — the officially sanctioned
            // mechanism for "this needs to show itself now" notifications,
            // same category as an incoming-call screen. Deliberately NOT
            // paired with showWhenLocked/turnScreenOn on MainActivity, so a
            // locked device still requires unlocking before the feed is
            // visible — the screen wakes and shows the alert prominently,
            // but doesn't bypass the lock screen.
            .setFullScreenIntent(pending, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_ALERT, notification)
    }

    private fun buildStatusNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.channel_alert_listener_name))
            .setContentText(getString(R.string.listening_for_alerts))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_alert_listener_name), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.channel_incoming_alerts_name), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        private const val TAG = "AlertReceiverService"
        const val ACTION_STOP = "com.cameraviewer.app.action.STOP_LISTENING"
        private const val CHANNEL_STATUS = "alert_listener_status"
        private const val CHANNEL_ALERTS = "incoming_person_alerts"
        private const val NOTIF_ID_STATUS = 5
        private const val NOTIF_ID_ALERT = 6
    }
}
