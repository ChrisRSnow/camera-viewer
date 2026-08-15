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
 * Sender-side: re-serves AudioCaptureService's one connection to the
 * camera app's audio to any number of remote viewers — same reasoning as
 * VideoRelayServerService, so a viewer never opens its own second
 * connection to the camera app for audio.
 *
 * Route: GET /audio → continuous raw WAV bytes (header once, then PCM
 * samples as they arrive), Content-Type: audio/wav, no Content-Length
 * (open-ended stream), for as long as the client stays connected. Not
 * HTTP chunked-encoded on this side — just a plain streamed body, same
 * style already used for the MJPEG relay's boundary-delimited frames.
 */
class AudioRelayServerService : Service() {

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
            Log.i(TAG, "relaying audio on :$PORT")
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
            Log.e(TAG, "could not bind audio relay port: ${e.message}")
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
                val path = requestLine.split(" ").getOrNull(1)?.substringBefore("?") ?: "/"
                if (path != "/audio") {
                    it.getOutputStream().apply {
                        write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                        flush()
                    }
                    return@use
                }
                streamToClient(it)
            } catch (e: Exception) {
                Log.w(TAG, "relay client error: ${e.message}")
            }
        }
    }

    private suspend fun streamToClient(socket: Socket) {
        val out = socket.getOutputStream()
        out.write(
            "HTTP/1.1 200 OK\r\nContent-Type: audio/wav\r\nConnection: close\r\n\r\n".toByteArray(),
        )
        out.flush()
        LiveAudioBus.chunks.collect { chunk ->
            out.write(chunk)
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
            .setContentTitle(getString(R.string.channel_audio_relay_name))
            .setContentText(getString(R.string.relaying_audio))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_audio_relay_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        private const val TAG = "AudioRelayServerService"
        const val ACTION_STOP = "com.cameraviewer.app.action.STOP_AUDIO_RELAY"
        const val PORT = 8793
        private const val CHANNEL_STATUS = "audio_relay_status"
        private const val NOTIF_ID = 10
    }
}
