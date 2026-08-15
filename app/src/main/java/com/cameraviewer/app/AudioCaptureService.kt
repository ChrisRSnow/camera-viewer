package com.cameraviewer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Sender-side: the one connection to the local camera app's /audio
 * endpoint (16-bit PCM mono WAV, 44.1kHz, chunked transfer-encoding —
 * verified against the camera app's own docs), publishing raw bytes to
 * LiveAudioBus for AudioRelayServerService to fan out to viewers.
 *
 * **Experimental, off by default** (SecureCredentialStore.audioEnabled):
 * this is a *second* simultaneous connection to the camera app on top of
 * CameraDetectionService's video connection, and this camera app has
 * documented single-viewer *video* encoder fragility under multiple
 * connections (§1). Whether audio capture shares any encoder state with
 * video internally isn't known — untested on real hardware — so this is
 * opt-in specifically so it can be turned off immediately if enabling it
 * destabilizes the video connection, rather than risking that regression
 * for everyone by default.
 *
 * HttpsURLConnection rather than a raw socket (unlike MjpegClient) —
 * chunked transfer-encoding needs decoding, and Java's URL connection
 * classes do that transparently via getInputStream(), where a raw socket
 * would need it hand-rolled.
 */
class AudioCaptureService : Service() {

    private lateinit var credentialStore: SecureCredentialStore
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        credentialStore = SecureCredentialStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            job = null
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification("Starting audio capture…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            job = scope.launch { runCaptureLoop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runCaptureLoop() {
        val username = credentialStore.cameraUsername
        val password = credentialStore.cameraPassword
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            updateStatus("Not configured")
            return
        }

        var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        val loopContext = currentCoroutineContext()

        while (loopContext.isActive) {
            updateStatus("Connecting to local camera audio…")
            try {
                streamAudio(username, password) { loopContext.isActive }
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "audio stream error: ${e.message}")
            }
            updateStatus("Reconnecting in ${reconnectDelayMs / 1000}s…")
            delay(reconnectDelayMs)
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }
    }

    private suspend fun streamAudio(username: String, password: String, isActive: () -> Boolean) =
        withContext(Dispatchers.IO) {
            var conn: HttpsURLConnection? = null
            try {
                val url = java.net.URL("https://$LOOPBACK_IP:${MjpegClient.CAMERA_PORT}/audio")
                conn = (url.openConnection() as HttpsURLConnection).apply {
                    sslSocketFactory = trustAllContext.socketFactory
                    hostnameVerifier = HostnameVerifier { _, _ -> true }
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    val basic = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
                    setRequestProperty("Authorization", "Basic $basic")
                }
                if (conn.responseCode != 200) throw java.io.IOException("camera returned HTTP ${conn.responseCode}")
                updateStatus("Listening")
                val input = conn.inputStream
                val buf = ByteArray(CHUNK_SIZE)
                while (isActive()) {
                    val n = input.read(buf)
                    if (n <= 0) throw java.io.IOException("upstream closed the connection")
                    LiveAudioBus.publish(buf.copyOf(n))
                }
            } finally {
                conn?.disconnect()
            }
        }

    private fun updateStatus(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.channel_audio_capture_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_audio_capture_name), NotificationManager.IMPORTANCE_LOW),
        )
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

    companion object {
        private const val TAG = "AudioCaptureService"
        const val ACTION_STOP = "com.cameraviewer.app.action.STOP_AUDIO_CAPTURE"
        private const val LOOPBACK_IP = "127.0.0.1"
        private const val CHUNK_SIZE = 4096
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
        private const val CHANNEL_STATUS = "audio_capture_status"
        private const val NOTIF_ID = 9
    }
}
