package com.cameraviewer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Binder
import android.os.IBinder
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service tying discovery + streaming + notifications together —
 * mirrors `runCamera()` in the web dashboard's app.js (discover, connect,
 * reconnect with backoff) but as a proper Android foreground service instead
 * of a browser tab, which is the whole point of building this native app: no
 * browser throttling, no Service Worker/Web Push complexity, real background
 * survival. Person-detection alerts come from the camera-role phone via
 * AlertReceiverService, not from this service — this one just streams video.
 *
 * Lifecycle: discovery (Tailscale API + cert fingerprinting) runs ONCE per
 * start, not on every reconnect — a dropped stream just reconnects to the
 * same IP with exponential backoff, same as the web version. If the camera's
 * Tailscale IP ever changes, restarting monitoring re-runs discovery.
 *
 * Two ways a start can be targeted: generic discovery (manual "Start
 * monitoring" tap, or a bare service restart — finds the first tailnet peer
 * matching the camera app's cert) vs an explicit IP/label passed straight
 * through from a tapped alert (EXTRA_TARGET_IP/EXTRA_TARGET_LABEL), which
 * connects to that specific camera without running discovery at all — the
 * fix for multi-camera routing.
 */
class CameraMonitorService : Service() {

    private val binder = LocalBinder()
    private lateinit var credentialStore: SecureCredentialStore
    private var monitorJob: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring.asStateFlow()

    private var cameraLabel: String = "camera"

    /** The explicit target (if any) the current monitorJob was started with — lets startMonitoring tell "already watching this camera" apart from "a different camera's alert just came in, switch". */
    private var currentTargetIp: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): CameraMonitorService = this@CameraMonitorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        credentialStore = SecureCredentialStore(applicationContext)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }
        startMonitoring(intent?.getStringExtra(EXTRA_TARGET_IP), intent?.getStringExtra(EXTRA_TARGET_LABEL))
        // START_STICKY: if the system kills this service under memory
        // pressure, it's restarted (with a null intent) and falls into the
        // startMonitoring() branch above — monitoring resumes on its own.
        return START_STICKY
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * [targetIp]/[targetLabel] come from a tapped alert (routes to that
     * specific camera). Null means "discover normally" (manual Start
     * monitoring tap on the main screen, or a plain service restart).
     * If already watching a *different* explicit target, switches streams
     * rather than silently ignoring the new one — otherwise tapping camera
     * B's alert while already watching camera A would do nothing.
     */
    private fun startMonitoring(targetIp: String? = null, targetLabel: String? = null) {
        if (monitorJob?.isActive == true) {
            if (targetIp.isNullOrBlank() || targetIp == currentTargetIp) return
            monitorJob?.cancel()
        }
        currentTargetIp = targetIp
        _isMonitoring.value = true
        ServiceCompat.startForeground(
            this,
            NOTIF_ID_STATUS,
            buildStatusNotification("Starting…"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        monitorJob = scope.launch { runMonitoringLoop(targetIp, targetLabel) }
    }

    private fun stopMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
        currentTargetIp = null
        _isMonitoring.value = false
        updateStatus("Stopped")
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private suspend fun runMonitoringLoop(targetIp: String?, targetLabel: String?) {
        val username = credentialStore.cameraUsername
        val password = credentialStore.cameraPassword
        if (username.isNullOrBlank() || password.isNullOrBlank()) {
            updateStatus("Not configured — set it up in Settings")
            return
        }

        val ip: String
        if (!targetIp.isNullOrBlank()) {
            // Routed straight from an alert — no discovery needed, we
            // already know exactly which camera to connect to. Still update
            // lastKnownCameraIp so "View Snapshots" has a camera to ask even
            // if generic discovery is never run on this device.
            ip = targetIp
            cameraLabel = targetLabel?.takeIf { it.isNotBlank() } ?: targetIp
            credentialStore.lastKnownCameraIp = targetIp
        } else {
            val token = credentialStore.tailscaleApiToken
            if (token.isNullOrBlank()) {
                updateStatus("Not configured — set it up in Settings")
                return
            }
            val discovered = discoverCameraIp(token)
            if (discovered == null) {
                updateStatus("Camera not found on tailnet")
                return
            }
            ip = discovered
        }

        val client = MjpegClient()
        var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        // currentCoroutineContext() is itself a suspend function — capture it
        // once here so the plain (non-suspend) isActive lambda below can read
        // .isActive off the captured context instead of calling it directly.
        val loopContext = currentCoroutineContext()

        while (loopContext.isActive) {
            updateStatus("Connecting to $ip…")
            try {
                client.streamFrames(
                    ip = ip,
                    username = username,
                    password = password,
                    isActive = { loopContext.isActive },
                ) { frameBytes ->
                    reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS // reset backoff on a successful frame
                    val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                    if (bitmap != null) {
                        _latestFrame.value = bitmap
                        updateStatus("Live — $cameraLabel")
                    }
                    // A single malformed/partial frame is normal (chunk
                    // boundary landed mid-frame) — decodeByteArray just
                    // returns null and the next frame carries on, same
                    // "silently skip one bad frame" behavior as app.js.
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "stream error: ${e.message}")
                updateStatus("Reconnecting in ${reconnectDelayMs / 1000}s…")
            }
            delay(reconnectDelayMs)
            // Exponential backoff, capped — same reasoning as app.js: don't
            // hammer the camera app's auth rate-limiter while it's down.
            reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
        }
    }

    private suspend fun discoverCameraIp(token: String): String? {
        updateStatus("Discovering camera on tailnet…")
        return try {
            val peers = TailscaleDiscovery.listPeers(token)
            for (peer in peers) {
                if (CameraProber.isCamera(peer.ipv4)) {
                    cameraLabel = peer.hostname
                    credentialStore.lastKnownCameraIp = peer.ipv4
                    return peer.ipv4
                }
            }
            Log.w(TAG, "no tailnet peer matched the camera cert fingerprint; falling back to last-known IP if any")
            credentialStore.lastKnownCameraIp
        } catch (e: Exception) {
            Log.w(TAG, "discovery failed, falling back to last-known IP if any: ${e.message}")
            credentialStore.lastKnownCameraIp
        }
    }

    private fun updateStatus(text: String) {
        _status.value = text
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID_STATUS, buildStatusNotification(text))
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildStatusNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(contentIntent())
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_status_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_status_description)
            },
        )
    }

    companion object {
        private const val TAG = "CameraMonitorService"
        const val ACTION_STOP = "com.cameraviewer.app.action.STOP"
        const val EXTRA_TARGET_IP = "com.cameraviewer.app.extra.TARGET_IP"
        const val EXTRA_TARGET_LABEL = "com.cameraviewer.app.extra.TARGET_LABEL"
        private const val CHANNEL_STATUS = "camera_status"
        private const val NOTIF_ID_STATUS = 1
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
