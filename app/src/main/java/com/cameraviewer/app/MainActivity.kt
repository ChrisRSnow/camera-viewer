package com.cameraviewer.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cameraviewer.app.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var credentialStore: SecureCredentialStore

    private var lastSnapshotBitmap: Bitmap? = null
    private var audioPlayer: android.media.MediaPlayer? = null

    // Client-side digital zoom/pan on imageFeed — purely a local display
    // transform on the already-received frame (View.scaleX/scaleY/
    // translationX/Y), not a request to the sender's real camera zoom.
    // Simpler and has none of remote zoom's shared-camera-state complications
    // (multiple viewers, or the sender's own preview/detection, would all be
    // affected together by a real camera zoom change) — the tradeoff is this
    // is genuinely just magnifying pixels already sent, not new detail.
    private var zoomScale = 1f
    private var panX = 0f
    private var panY = 0f

    private val scaleGestureDetector by lazy {
        ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    zoomScale = (zoomScale * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
                    applyZoomTransform()
                    return true
                }
            },
        )
    }

    private val panGestureDetector by lazy {
        GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                // SimpleOnGestureListener.onDown() returns false by default,
                // which can stop the detector from reliably forwarding the
                // rest of the gesture (onScroll/onDoubleTap) — override to
                // consume it.
                override fun onDown(e: MotionEvent): Boolean = true

                override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if (zoomScale <= 1f) return false
                    panX -= distanceX
                    panY -= distanceY
                    applyZoomTransform()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    zoomScale = 1f
                    panX = 0f
                    panY = 0f
                    applyZoomTransform()
                    return true
                }
            },
        )
    }

    private var service: CameraMonitorService? = null
    private var bound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as CameraMonitorService.LocalBinder
            service = localBinder.getService()
            bound = true
            observeService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            bound = false
        }
    }

    private var alertReceiverService: AlertReceiverService? = null
    private var alertReceiverBound = false

    private val alertReceiverConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as AlertReceiverService.LocalBinder
            alertReceiverService = localBinder.getService()
            alertReceiverBound = true
            observeAlertReceiverService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            alertReceiverService = null
            alertReceiverBound = false
        }
    }

    private var cameraDetectionService: CameraDetectionService? = null
    private var cameraDetectionBound = false

    private val cameraDetectionConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as CameraDetectionService.LocalBinder
            cameraDetectionService = localBinder.getService()
            cameraDetectionBound = true
            observeCameraDetectionService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraDetectionService = null
            cameraDetectionBound = false
        }
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* no-op: monitoring works either way, it just won't show alerts without this */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        credentialStore = SecureCredentialStore(this)

        // Keep the screen on while the live feed is actually being looked
        // at — this is the only screen that shows it. Tied to the window
        // being foregrounded, so it stops applying on its own the moment
        // this activity is backgrounded; nothing to explicitly release.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnViewSnapshots.setOnClickListener {
            // On a sender, lastKnownCameraIp is never set — that field only
            // gets populated by the viewer's remote-connect flow, and a
            // sender never connects to a remote camera "as a viewer" in that
            // sense. But SnapshotServerService binds all interfaces
            // (including loopback), so a sender can just query itself at
            // 127.0.0.1 through the exact same SnapshotFetcher/
            // SnapshotsActivity code a viewer uses against a remote camera —
            // no separate "local snapshots" code path needed.
            val ip = if (credentialStore.deviceRole == SecureCredentialStore.ROLE_SENDER) {
                "127.0.0.1"
            } else {
                credentialStore.lastKnownCameraIp
            }
            startActivity(
                Intent(this, SnapshotsActivity::class.java).apply {
                    putExtra(SnapshotsActivity.EXTRA_CAMERA_IP, ip)
                },
            )
        }

        binding.btnStartStop.setOnClickListener {
            if (service?.isMonitoring?.value == true) {
                stopMonitoringService()
            } else {
                startMonitoringService()
            }
        }

        binding.btnManualSnapshot.setOnClickListener { triggerManualSnapshot() }
        binding.imgLastSnapshot.setOnClickListener { showLastSnapshotFullSize() }
        binding.btnToggleAudio.setOnClickListener { toggleAudio() }
        setupZoomGestures()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        handleAutoConnectIntent(intent)

        if (credentialStore.deviceRole == null) {
            showRolePicker()
        } else {
            applyRoleStartupBehavior()
        }
    }

    /**
     * One-time, non-cancelable choice of what this device is for — drives
     * which Settings sections show and what auto-starts on future launches.
     * Not a permanent restriction: either role's fields can still be filled
     * in by hand later if a device ends up needing both.
     */
    private fun showRolePicker() {
        AlertDialog.Builder(this)
            .setTitle(R.string.role_picker_title)
            .setMessage(R.string.role_picker_message)
            .setCancelable(false)
            .setPositiveButton(R.string.role_sender) { _, _ -> chooseRole(SecureCredentialStore.ROLE_SENDER) }
            .setNegativeButton(R.string.role_viewer) { _, _ -> chooseRole(SecureCredentialStore.ROLE_VIEWER) }
            .show()
    }

    private fun chooseRole(role: String) {
        credentialStore.deviceRole = role
        applyRoleStartupBehavior()
        // Either role needs at least the Tailscale token + camera login filled
        // in before it's actually useful — send them there right away rather
        // than leaving them on an unconfigured main screen.
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /**
     * Viewer: AlertReceiverService needs no configuration at all to run (see
     * its own onStartCommand — no credential checks), so "start listening
     * right away" means literally every launch, not just after setup.
     * Sender: silently re-scans for viewers on every launch too, using
     * whatever's already saved — the visible/manual version of this same
     * scan lives in SettingsActivity for after a fresh config change. Also
     * auto-starts CameraDetectionService whenever camera-role fields are
     * filled in — no separate "is Android IP Camera actually running" check
     * needed here, since CameraDetectionService already retries with backoff
     * until its local connection succeeds (see its own runDetectionLoop),
     * so starting it unconditionally when configured has the same effect as
     * starting it only once the camera app is confirmed running, and also
     * self-heals if that app gets closed and reopened later.
     */
    private fun applyRoleStartupBehavior() {
        // "Start monitoring" connects to a REMOTE camera — meaningless on a
        // sender-only device, which already shows its own local feed
        // automatically via CameraDetectionService. Tapping it there would
        // just try (and fail) to discover some other camera on the tailnet.
        // Same reasoning as btnStartStop: manually snapshotting a *remote*
        // camera is a viewer-role concept, meaningless on a sender-only
        // device (which has no "currently watched" camera in this sense).
        val isSender = credentialStore.deviceRole == SecureCredentialStore.ROLE_SENDER
        binding.btnStartStop.visibility = if (isSender) View.GONE else View.VISIBLE
        binding.btnManualSnapshot.visibility = if (isSender) View.GONE else View.VISIBLE
        binding.btnToggleAudio.visibility = if (isSender) View.GONE else View.VISIBLE
        when (credentialStore.deviceRole) {
            SecureCredentialStore.ROLE_VIEWER ->
                ContextCompat.startForegroundService(this, Intent(this, AlertReceiverService::class.java))
            SecureCredentialStore.ROLE_SENDER -> {
                ensureSenderServicesRunning()
                val token = credentialStore.tailscaleApiToken
                if (!token.isNullOrBlank()) {
                    lifecycleScope.launch {
                        val found = runCatching { ViewerScan.findViewers(token) }.getOrDefault(emptyList())
                        if (found.isNotEmpty()) {
                            credentialStore.alertTargets = ViewerScan.mergeTargets(credentialStore.alertTargets, found)
                        }
                    }
                }
            }
        }
    }

    /**
     * Explicitly (re-)starts the sender-side services, not just binds to
     * them. Matters specifically because MainActivity.onStart() binds to
     * CameraDetectionService with BIND_AUTO_CREATE, which recreates the
     * service object if the OS killed it while backgrounded — but binding
     * alone never calls onStartCommand(), so a bind-only recreate leaves
     * the detection loop never actually started: the service exists,
     * reports its default "Idle" status forever, and never connects,
     * until something else happens to call startForegroundService again.
     * Calling this from onStart() (as well as applyRoleStartupBehavior on
     * launch) closes that gap — cheap and safe to call repeatedly, since
     * CameraDetectionService's onStartCommand already no-ops if a
     * connection is already active.
     */
    private fun ensureSenderServicesRunning() {
        if (credentialStore.deviceRole != SecureCredentialStore.ROLE_SENDER || !credentialStore.isCameraRoleConfigured) return
        ContextCompat.startForegroundService(this, Intent(this, CameraDetectionService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, SnapshotServerService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, VideoRelayServerService::class.java))
        if (credentialStore.audioEnabled) {
            ContextCompat.startForegroundService(this, Intent(this, AudioCaptureService::class.java))
            ContextCompat.startForegroundService(this, Intent(this, AudioRelayServerService::class.java))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // MainActivity is launchMode="singleTop" so a tapped alert notification
        // re-delivers here instead of spawning a second instance.
        setIntent(intent)
        handleAutoConnectIntent(intent)
    }

    /**
     * Tapping a "person detected" alert notification jumps straight to
     * watching, not just opening the app — and specifically to the camera
     * that alerted (EXTRA_CAMERA_IP), when present, rather than whichever
     * camera generic discovery happens to find first. This is what makes
     * multi-camera setups route correctly.
     */
    private fun handleAutoConnectIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_AUTO_CONNECT, false) != true) return
        startMonitoringService(intent.getStringExtra(EXTRA_CAMERA_IP), intent.getStringExtra(EXTRA_CAMERA_LABEL))
    }

    override fun onStart() {
        super.onStart()
        // Bind (not start) so the UI can observe an already-running service
        // without forcing monitoring to start just from opening the app.
        bindService(Intent(this, CameraMonitorService::class.java), connection, Context.BIND_AUTO_CREATE)
        // Same reasoning for the listening-status indicator — binding alone
        // doesn't start the listener (that's applyRoleStartupBehavior's
        // explicit startForegroundService call), it just lets this screen
        // observe whatever state it's actually in.
        bindService(Intent(this, AlertReceiverService::class.java), alertReceiverConnection, Context.BIND_AUTO_CREATE)
        // Sender's local feed — same "binding doesn't start it" reasoning.
        // Unlike the two binds above, this one is paired with an explicit
        // restart call every time (ensureSenderServicesRunning, below) —
        // see its own doc comment for why binding alone isn't sufficient
        // here specifically (a bind-triggered recreate after the OS killed
        // the service left it stuck reporting "Idle" forever).
        bindService(Intent(this, CameraDetectionService::class.java), cameraDetectionConnection, Context.BIND_AUTO_CREATE)
        ensureSenderServicesRunning()
        refreshLastSnapshotThumbnail()
    }

    override fun onStop() {
        if (bound) {
            unbindService(connection)
            bound = false
        }
        if (alertReceiverBound) {
            unbindService(alertReceiverConnection)
            alertReceiverBound = false
        }
        if (cameraDetectionBound) {
            unbindService(cameraDetectionConnection)
            cameraDetectionBound = false
        }
        // Audio playback is tied to this screen being visible, not a
        // background service like video/alerts — leaving it running while
        // backgrounded would just leak the MediaPlayer with nothing
        // showing it's still active.
        stopAudio()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (!credentialStore.isConfigured) {
            binding.textStatus.text = getString(R.string.settings_incomplete)
        }
    }

    private fun startMonitoringService(targetIp: String? = null, targetLabel: String? = null) {
        if (!credentialStore.isConfigured) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }
        val intent = Intent(this, CameraMonitorService::class.java).apply {
            if (!targetIp.isNullOrBlank()) {
                putExtra(CameraMonitorService.EXTRA_TARGET_IP, targetIp)
                putExtra(CameraMonitorService.EXTRA_TARGET_LABEL, targetLabel)
            }
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopMonitoringService() {
        val stopIntent = Intent(this, CameraMonitorService::class.java).apply {
            action = CameraMonitorService.ACTION_STOP
        }
        startService(stopIntent)
    }

    private fun observeService() {
        val svc = service ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.status.collect { text -> binding.textStatus.text = text }
                }
                launch {
                    svc.latestFrame.collect { bitmap ->
                        if (bitmap != null) binding.imageFeed.setImageBitmap(bitmap)
                    }
                }
                launch {
                    svc.isMonitoring.collect { monitoring ->
                        binding.btnStartStop.text = getString(
                            if (monitoring) R.string.stop_monitoring else R.string.start_monitoring,
                        )
                    }
                }
            }
        }
    }

    private fun setupZoomGestures() {
        binding.imageFeed.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            panGestureDetector.onTouchEvent(event)
            true
        }
    }

    private fun applyZoomTransform() {
        val view = binding.imageFeed
        val maxPanX = (view.width * (zoomScale - 1) / 2f).coerceAtLeast(0f)
        val maxPanY = (view.height * (zoomScale - 1) / 2f).coerceAtLeast(0f)
        panX = panX.coerceIn(-maxPanX, maxPanX)
        panY = panY.coerceIn(-maxPanY, maxPanY)
        view.scaleX = zoomScale
        view.scaleY = zoomScale
        view.translationX = panX
        view.translationY = panY
    }

    /**
     * "Manual snapshot" button: asks the currently-watched camera to save
     * a snapshot of whatever it's seeing right now, regardless of whether
     * a person is actually in frame — for "I want a record of this moment"
     * rather than waiting on detection. Saved on the sender's side (same
     * SnapshotStore/retention as automatic ones), so it shows up in View
     * Snapshots like any other.
     */
    private fun triggerManualSnapshot() {
        val ip = credentialStore.lastKnownCameraIp
        if (ip.isNullOrBlank()) {
            Toast.makeText(this, R.string.manual_snapshot_no_camera, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val result = SnapshotFetcher.triggerManualCapture(ip)
            val messageRes = when (result) {
                ManualCaptureResult.SAVED -> R.string.manual_snapshot_saved
                ManualCaptureResult.NO_FRAME_AVAILABLE -> R.string.manual_snapshot_no_frame
                ManualCaptureResult.UNREACHABLE -> R.string.manual_snapshot_failed
            }
            Toast.makeText(this@MainActivity, messageRes, Toast.LENGTH_SHORT).show()
            if (result == ManualCaptureResult.SAVED) refreshLastSnapshotThumbnail()
        }
    }

    /**
     * Small corner thumbnail of the most recent snapshot from whichever
     * camera is currently known (credentialStore.lastKnownCameraIp) — tap
     * to view full size, same dialog style as SnapshotsActivity's row tap.
     * Self-gating rather than explicitly role-hidden: a sender-only device
     * has no "currently watched camera" in this sense, so lastKnownCameraIp
     * is naturally unset there and this just silently stays hidden.
     * Best-effort — any failure (no camera yet, unreachable, no snapshots)
     * just leaves the thumbnail hidden rather than showing an error.
     */
    private fun refreshLastSnapshotThumbnail() {
        // Same reasoning as btnViewSnapshots: a sender has no
        // lastKnownCameraIp of its own, so query itself at 127.0.0.1.
        val ip = if (credentialStore.deviceRole == SecureCredentialStore.ROLE_SENDER) {
            "127.0.0.1"
        } else {
            credentialStore.lastKnownCameraIp
        }
        if (ip.isNullOrBlank()) {
            binding.imgLastSnapshot.visibility = View.GONE
            return
        }
        lifecycleScope.launch {
            val bitmap = runCatching {
                val newest = SnapshotFetcher.list(ip).firstOrNull() ?: return@runCatching null
                SnapshotFetcher.fetchImage(ip, newest.filename)
            }.getOrNull()
            if (bitmap != null) {
                lastSnapshotBitmap = bitmap
                binding.imgLastSnapshot.setImageBitmap(bitmap)
                binding.imgLastSnapshot.visibility = View.VISIBLE
            } else {
                binding.imgLastSnapshot.visibility = View.GONE
            }
        }
    }

    private fun showLastSnapshotFullSize() {
        val bitmap = lastSnapshotBitmap ?: return
        val imageView = android.widget.ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    /**
     * "Listen to audio" button — plays the currently-watched camera's mic
     * audio via MediaPlayer pointed straight at the sender's
     * AudioRelayServerService URL. MediaPlayer supports HTTP streaming
     * sources natively (including open-ended/live ones), so this needs no
     * manual buffering/AudioTrack handling — it parses the WAV container
     * and plays progressively as bytes arrive, same as it would for a
     * radio-station-style continuous stream. Requires the sender's
     * experimental "Enable audio" Settings toggle to actually be on;
     * otherwise the connection is simply refused (AudioRelayServerService
     * isn't running) and playback fails to prepare.
     */
    private fun toggleAudio() {
        if (audioPlayer != null) {
            stopAudio()
            return
        }
        val ip = credentialStore.lastKnownCameraIp
        if (ip.isNullOrBlank()) {
            Toast.makeText(this, R.string.audio_no_camera, Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnToggleAudio.text = getString(R.string.stop_audio)
        audioPlayer = android.media.MediaPlayer().apply {
            setAudioAttributes(
                android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ ->
                Toast.makeText(this@MainActivity, R.string.audio_start_failed, Toast.LENGTH_SHORT).show()
                stopAudio()
                true
            }
            try {
                setDataSource("http://$ip:${AudioRelayServerService.PORT}/audio")
                prepareAsync()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, R.string.audio_start_failed, Toast.LENGTH_SHORT).show()
                stopAudio()
            }
        }
    }

    private fun stopAudio() {
        audioPlayer?.apply {
            runCatching { stop() }
            release()
        }
        audioPlayer = null
        binding.btnToggleAudio.text = getString(R.string.listen_to_audio)
    }

    /**
     * Shows the sender's own local camera feed automatically — reuses the
     * same imageFeed/textStatus views CameraMonitorService already updates
     * for the viewer role. Safe to share: in normal single-role usage only
     * one of the two services is ever actually running and producing
     * updates, so there's no real conflict over who's "driving" the view.
     */
    private fun observeCameraDetectionService() {
        val svc = cameraDetectionService ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    svc.status.collect { text -> binding.textStatus.text = text }
                }
                launch {
                    svc.latestFrame.collect { bitmap ->
                        if (bitmap != null) binding.imageFeed.setImageBitmap(bitmap)
                    }
                }
            }
        }
    }

    private fun observeAlertReceiverService() {
        val svc = alertReceiverService ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                svc.isListening.collect { listening ->
                    binding.textListeningStatus.text = getString(
                        if (listening) R.string.listening_status_on else R.string.listening_status_off,
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_AUTO_CONNECT = "com.cameraviewer.app.extra.AUTO_CONNECT"
        const val EXTRA_CAMERA_IP = "com.cameraviewer.app.extra.CAMERA_IP"
        const val EXTRA_CAMERA_LABEL = "com.cameraviewer.app.extra.CAMERA_LABEL"
        private const val MIN_ZOOM = 1f
        private const val MAX_ZOOM = 5f
    }
}
