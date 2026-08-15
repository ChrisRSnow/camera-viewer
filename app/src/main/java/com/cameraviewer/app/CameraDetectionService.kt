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
 * Camera-side role: runs real person detection against THIS phone's own
 * camera (the "Android IP Camera" app already running locally on it) and
 * POSTs an alert to configured viewer phones when a person is found —
 * "each phone does its own detection and pushes to a viewer" per the design
 * discussed, rather than one central box pulling every camera's stream.
 *
 * Deliberately separate from CameraMonitorService: that class is the
 * VIEWER role (discover a remote camera, stream it, show it on screen).
 * This is the opposite direction — a phone hosting a camera, watching its
 * own feed, telling OTHER phones when something's worth looking at. A
 * single phone could run both services if it's both a camera and a viewer.
 *
 * Multi-camera routing: each alert includes this device's own Tailscale IP
 * (LocalTailscaleIp — reads it directly off the network interfaces, unlike
 * the web dashboard's Termux-based discovery design which hit a genuine
 * /proc/net permission wall; that constraint doesn't apply to a normal
 * installed Android app). The viewer uses that IP to connect to THIS
 * specific camera instead of falling back to generic "first camera found"
 * discovery, so tapping an alert from camera B doesn't land you watching
 * camera A's stream.
 *
 * Bindable (mirrors CameraMonitorService's LocalBinder/StateFlow pattern) so
 * a sender phone's own MainActivity can show its local feed automatically —
 * reusing the exact same decoded frames already being pulled for detection,
 * rather than opening a second independent connection to the local camera
 * app. That matters: the camera app's single-viewer encoder degrades under
 * a second simultaneous connection (see ARCHITECTURE.md §1). This service
 * holds the ONLY connection to the local camera app; every frame it reads
 * is also published to LiveFrameBus, which VideoRelayServerService fans out
 * to any number of remote viewers. Earlier versions had CameraMonitorService
 * (on a viewer phone) connect directly to the sender's camera app over
 * Tailscale — a second simultaneous connection in exactly the same sense,
 * just made remotely instead of locally, and it caused the same encoder
 * degradation after a few minutes on real hardware.
 */
class CameraDetectionService : Service() {

    private lateinit var credentialStore: SecureCredentialStore
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val binder = LocalBinder()
    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()
    private val _status = MutableStateFlow("Idle")
    val status: StateFlow<String> = _status.asStateFlow()
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
    private var orientationMonitor: CameraOrientationMonitor? = null

    inner class LocalBinder : Binder() {
        fun getService(): CameraDetectionService = this@CameraDetectionService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        credentialStore = SecureCredentialStore(applicationContext)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            job?.cancel()
            job = null
            _isRunning.value = false
            stopOrientationMonitor()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive != true) {
            ServiceCompat.startForeground(
                this,
                NOTIF_ID,
                buildNotification("Starting person detection…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            _isRunning.value = true
            job = scope.launch { runDetectionLoop() }
            startOrientationMonitorIfEnabled()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        job?.cancel()
        _isRunning.value = false
        stopOrientationMonitor()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Applies rotation automatically as the phone's physical orientation
     * changes, relative to a user-verified calibration point rather than a
     * fixed sensor→rotate formula — see SecureCredentialStore's field
     * comments for why. Only active when the "Auto-detect camera
     * orientation" Settings toggle is on; otherwise rotation stays exactly
     * as manually set, unchanged by device orientation.
     */
    private fun startOrientationMonitorIfEnabled() {
        if (!credentialStore.cameraAutoRotationEnabled || orientationMonitor != null) return
        orientationMonitor = CameraOrientationMonitor(applicationContext) { bucket ->
            scope.launch { applyAutoRotation(bucket) }
        }.also { it.start() }
    }

    private fun stopOrientationMonitor() {
        orientationMonitor?.stop()
        orientationMonitor = null
    }

    private suspend fun applyAutoRotation(currentBucket: Int) {
        val username = credentialStore.cameraUsername ?: return
        val password = credentialStore.cameraPassword ?: return
        val calibratedBucket = credentialStore.cameraRotationCalibratedBucket
        val calibratedRotation = credentialStore.cameraRotationDegrees
        val rawDelta = if (credentialStore.cameraAutoRotationInverted) {
            calibratedBucket - currentBucket
        } else {
            currentBucket - calibratedBucket
        }
        val delta = ((rawDelta % 360) + 360) % 360
        val newRotation = ((calibratedRotation + delta) % 360 + 360) % 360
        runCatching { CameraControlClient.setRotation(LOOPBACK_IP, username, password, newRotation) }
    }

    private suspend fun runDetectionLoop() {
        val username = credentialStore.cameraUsername
        val password = credentialStore.cameraPassword
        val label = credentialStore.cameraLabel
        if (username.isNullOrBlank() || password.isNullOrBlank() || label.isNullOrBlank()) {
            updateStatus("Not configured — set camera label + login in Settings")
            return
        }
        val targets = credentialStore.alertTargetList()

        val detector = try {
            PersonDetector(applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "failed to load person-detection model", e)
            updateStatus("Failed to load detection model: ${e.message}")
            return
        }

        val client = MjpegClient()
        val loopContext = currentCoroutineContext()
        var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        var framesSinceInference = 0
        var lastRefocusMs = 0L

        // Rotation is corrected here, at the one connection to the actual
        // camera app, so every downstream consumer (this device's own
        // preview, the video relay, and therefore every remote viewer)
        // sees already-correct frames without needing its own rotation
        // logic. A one-shot control request, not part of the stream
        // connection itself — see CameraControlClient's doc comment for
        // why (the docs don't demonstrate rotate= on /video/mjpeg, only
        // the root path/control endpoints, and it's documented as
        // "persisted per-camera" so a one-time call suffices).
        val rotation = credentialStore.cameraRotationDegrees
        if (rotation != 0) {
            runCatching { CameraControlClient.setRotation(LOOPBACK_IP, username, password, rotation) }
        }

        try {
            while (loopContext.isActive) {
                detector.reset()
                updateStatus("Connecting to local camera…")
                try {
                    client.streamFrames(
                        ip = LOOPBACK_IP,
                        username = username,
                        password = password,
                        isActive = { loopContext.isActive },
                    ) { frameBytes ->
                        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
                        updateStatus("Watching — $label")

                        // Full framerate to the relay regardless of the
                        // inference throttle below — remote viewers watch
                        // live video, they don't need it capped to detection
                        // rate. This is the one and only connection to the
                        // local camera app; VideoRelayServerService fans
                        // these same frames out to any number of viewers so
                        // none of them need their own separate connection to
                        // it (see LiveFrameBus for why that mattered).
                        LiveFrameBus.publish(frameBytes)

                        // Periodically nudge the camera app to re-run
                        // autofocus — discovered empirically that this
                        // camera's autofocus can get stuck (only cleared by
                        // physically moving the phone) with no automatic
                        // correction otherwise, which is a real problem for
                        // an unattended security camera. Fire-and-forget on
                        // this service's own scope so a slow/failed nudge
                        // request never stalls frame processing.
                        val now = System.currentTimeMillis()
                        val refocusIntervalMs = credentialStore.refocusIntervalMinutes.coerceAtLeast(1) * 60_000L
                        if (now - lastRefocusMs >= refocusIntervalMs) {
                            lastRefocusMs = now
                            scope.launch {
                                CameraControlClient.nudgeRefocus(LOOPBACK_IP, username, password)
                            }
                        }

                        // Run inference roughly once a second rather than on
                        // every MJPEG frame (~12fps) — plenty for an alert
                        // use case, far less battery/thermal load than
                        // running a full object detector at stream framerate.
                        framesSinceInference++
                        if (framesSinceInference < INFERENCE_EVERY_N_FRAMES) return@streamFrames
                        framesSinceInference = 0

                        val bitmap = BitmapFactory.decodeByteArray(frameBytes, 0, frameBytes.size)
                        if (bitmap != null) _latestFrame.value = bitmap
                        if (bitmap != null && detector.onFrame(bitmap)) {
                            Log.i(TAG, "person detected — alerting ${targets.size} target(s)")
                            fireLocalNotification(label)
                            SnapshotStore.save(applicationContext, bitmap, credentialStore.snapshotRetentionCount)
                            if (targets.isNotEmpty()) {
                                AlertClient.sendAlert(targets, label, LocalTailscaleIp.find())
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "local stream error: ${e.message}")
                    updateStatus("Reconnecting in ${reconnectDelayMs / 1000}s…")
                }
                delay(reconnectDelayMs)
                reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
            }
        } finally {
            detector.close()
        }
    }

    private fun updateStatus(text: String) {
        _status.value = text
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(getString(R.string.detection_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun fireLocalNotification(label: String) {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL_MOTION)
            .setContentTitle(getString(R.string.person_detected_title, label))
            .setContentText(java.text.DateFormat.getTimeInstance().format(java.util.Date()))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID_MOTION, notification)
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, getString(R.string.channel_detection_name), NotificationManager.IMPORTANCE_LOW),
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MOTION, getString(R.string.channel_person_alerts_name), NotificationManager.IMPORTANCE_HIGH),
        )
    }

    companion object {
        private const val TAG = "CameraDetectionService"
        const val ACTION_STOP = "com.cameraviewer.app.action.STOP_DETECTION"
        private const val LOOPBACK_IP = "127.0.0.1"
        private const val CHANNEL_STATUS = "detection_status"
        private const val CHANNEL_MOTION = "person_alerts"
        private const val NOTIF_ID = 3
        private const val NOTIF_ID_MOTION = 4
        private const val INFERENCE_EVERY_N_FRAMES = 12 // ~1 inference/sec at the camera's ~12fps
        private const val INITIAL_RECONNECT_DELAY_MS = 1_000L
        private const val MAX_RECONNECT_DELAY_MS = 30_000L
    }
}
