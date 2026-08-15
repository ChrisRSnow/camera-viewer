package com.cameraviewer.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
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
            val ip = credentialStore.lastKnownCameraIp
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
        when (credentialStore.deviceRole) {
            SecureCredentialStore.ROLE_VIEWER ->
                ContextCompat.startForegroundService(this, Intent(this, AlertReceiverService::class.java))
            SecureCredentialStore.ROLE_SENDER -> {
                if (credentialStore.isCameraRoleConfigured) {
                    ContextCompat.startForegroundService(this, Intent(this, CameraDetectionService::class.java))
                    ContextCompat.startForegroundService(this, Intent(this, SnapshotServerService::class.java))
                    ContextCompat.startForegroundService(this, Intent(this, VideoRelayServerService::class.java))
                }
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
        // Sender's local feed — same "binding doesn't start it" reasoning;
        // applyRoleStartupBehavior is what actually starts detection.
        bindService(Intent(this, CameraDetectionService::class.java), cameraDetectionConnection, Context.BIND_AUTO_CREATE)
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
    }
}
