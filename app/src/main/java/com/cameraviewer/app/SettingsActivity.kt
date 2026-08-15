package com.cameraviewer.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cameraviewer.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.launch

/**
 * Edits everything stored via SecureCredentialStore (Keystore-backed
 * EncryptedSharedPreferences — never written to a plain file, never
 * hardcoded into source): the Tailscale API token + camera login (needed by
 * both roles — see their own field comments), and the camera label + alert
 * targets used for the sender role specifically.
 *
 * Section visibility follows the device's chosen role (set once via
 * MainActivity's first-run prompt): sectionSender is hidden for a viewer
 * device, sectionViewer is hidden for a sender device. This doesn't actually
 * restrict either role's fields from being filled in by hand if the role is
 * unset (shows both) — it only controls the default, role-appropriate view.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var credentialStore: SecureCredentialStore

    // Held in memory and only written to credentialStore on Save, same as
    // every other field on this screen (editRefocusInterval etc.) — not
    // its own persisted-immediately toggle, despite the click-to-cycle UI.
    private var cameraRotationDegrees = 0

    private var cameraDetectionService: CameraDetectionService? = null
    private var cameraDetectionBound = false

    private val cameraDetectionConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            cameraDetectionService = (binder as CameraDetectionService.LocalBinder).getService()
            cameraDetectionBound = true
            observeCameraDetectionService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraDetectionService = null
            cameraDetectionBound = false
        }
    }

    private var alertReceiverService: AlertReceiverService? = null
    private var alertReceiverBound = false

    private val alertReceiverConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            alertReceiverService = (binder as AlertReceiverService.LocalBinder).getService()
            alertReceiverBound = true
            observeAlertReceiverService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            alertReceiverService = null
            alertReceiverBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.settings_title)
        credentialStore = SecureCredentialStore(this)

        binding.editTailscaleToken.setText(credentialStore.tailscaleApiToken.orEmpty())
        binding.editCameraUsername.setText(credentialStore.cameraUsername.orEmpty())
        binding.editCameraPassword.setText(credentialStore.cameraPassword.orEmpty())
        binding.editCameraLabel.setText(credentialStore.cameraLabel.orEmpty())
        binding.editAlertTargets.setText(credentialStore.alertTargets.orEmpty())
        binding.editSnapshotRetention.setText(credentialStore.snapshotRetentionCount.toString())
        binding.editRefocusInterval.setText(credentialStore.refocusIntervalMinutes.toString())
        cameraRotationDegrees = credentialStore.cameraRotationDegrees
        updateRotationButtonText()

        applyRoleVisibility()

        binding.btnSave.setOnClickListener { save() }
        binding.btnScanTailnet.setOnClickListener { scanTailnetForViewers() }
        binding.btnCameraRotation.setOnClickListener {
            cameraRotationDegrees = (cameraRotationDegrees + 90) % 360
            updateRotationButtonText()
        }
        binding.btnToggleDetection.setOnClickListener {
            if (cameraDetectionService?.isRunning?.value == true) stopDetection() else startDetection()
        }
        binding.btnToggleListening.setOnClickListener {
            if (alertReceiverService?.isListening?.value == true) stopListening() else startListening()
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, CameraDetectionService::class.java), cameraDetectionConnection, Context.BIND_AUTO_CREATE)
        bindService(Intent(this, AlertReceiverService::class.java), alertReceiverConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        if (cameraDetectionBound) {
            unbindService(cameraDetectionConnection)
            cameraDetectionBound = false
        }
        if (alertReceiverBound) {
            unbindService(alertReceiverConnection)
            alertReceiverBound = false
        }
        super.onStop()
    }

    /** Keeps btnToggleDetection's label truthful to the service's actual running state, not just whichever button was last tapped here — it can also be running because it auto-started elsewhere (see MainActivity.applyRoleStartupBehavior). */
    private fun observeCameraDetectionService() {
        val svc = cameraDetectionService ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                svc.isRunning.collect { running ->
                    binding.btnToggleDetection.text = getString(
                        if (running) R.string.stop_person_detection else R.string.start_person_detection,
                    )
                }
            }
        }
    }

    /** Same reasoning as observeCameraDetectionService — keeps the label truthful even if listening was started elsewhere (viewer role auto-starts it on launch). */
    private fun observeAlertReceiverService() {
        val svc = alertReceiverService ?: return
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                svc.isListening.collect { listening ->
                    binding.btnToggleListening.text = getString(
                        if (listening) R.string.stop_listening else R.string.start_listening,
                    )
                }
            }
        }
    }

    private fun updateRotationButtonText() {
        binding.btnCameraRotation.text = getString(
            when (cameraRotationDegrees) {
                90 -> R.string.camera_rotation_value_90
                180 -> R.string.camera_rotation_value_180
                270 -> R.string.camera_rotation_value_270
                else -> R.string.camera_rotation_value_0
            },
        )
    }

    /** Role unset (shouldn't normally happen post-first-run) shows both sections rather than hiding everything. */
    private fun applyRoleVisibility() {
        when (credentialStore.deviceRole) {
            SecureCredentialStore.ROLE_SENDER -> binding.sectionViewer.visibility = View.GONE
            SecureCredentialStore.ROLE_VIEWER -> binding.sectionSender.visibility = View.GONE
        }
    }

    private fun save() {
        credentialStore.tailscaleApiToken = binding.editTailscaleToken.text.toString().trim()
        credentialStore.cameraUsername = binding.editCameraUsername.text.toString().trim()
        credentialStore.cameraPassword = binding.editCameraPassword.text.toString()
        credentialStore.cameraLabel = binding.editCameraLabel.text.toString().trim()
        credentialStore.alertTargets = binding.editAlertTargets.text.toString().trim()
        credentialStore.snapshotRetentionCount = binding.editSnapshotRetention.text.toString().trim().toIntOrNull()
            ?.coerceAtLeast(1) ?: SecureCredentialStore.DEFAULT_SNAPSHOT_RETENTION
        credentialStore.refocusIntervalMinutes = binding.editRefocusInterval.text.toString().trim().toIntOrNull()
            ?.coerceAtLeast(1) ?: SecureCredentialStore.DEFAULT_REFOCUS_INTERVAL_MINUTES
        credentialStore.cameraRotationDegrees = cameraRotationDegrees
        // Credentials changed — a cached IP found under old/different
        // credentials shouldn't be trusted until discovery re-confirms it.
        credentialStore.lastKnownCameraIp = null

        binding.textSaveConfirmation.visibility = View.VISIBLE
        Toast.makeText(this, R.string.settings_saved, Toast.LENGTH_SHORT).show()

        // Sender's "auto-poll for listener devices after 1st configuration"
        // behavior — every save, not just tracking a true first-time flag,
        // which naturally covers "after 1st configuration" without extra state.
        if (credentialStore.deviceRole == SecureCredentialStore.ROLE_SENDER &&
            credentialStore.tailscaleApiToken?.isNotBlank() == true
        ) {
            scanTailnetForViewers()
        }

        // Same reasoning for auto-starting detection: no separate "is Android
        // IP Camera running" check needed, CameraDetectionService retries
        // with backoff until its local connection succeeds either way.
        if (credentialStore.deviceRole == SecureCredentialStore.ROLE_SENDER && credentialStore.isCameraRoleConfigured) {
            ContextCompat.startForegroundService(this, Intent(this, CameraDetectionService::class.java))
            ContextCompat.startForegroundService(this, Intent(this, SnapshotServerService::class.java))
            ContextCompat.startForegroundService(this, Intent(this, VideoRelayServerService::class.java))
        }
    }

    /**
     * Enumerates tailnet peers and probes each one for an open ALERT_PORT
     * (ViewerProber — see its own doc comment on why this is a weaker check
     * than CameraProber's cert fingerprint). Matches are merged into the
     * existing Alert targets field, deduplicated, rather than overwriting
     * whatever's already there by hand.
     */
    private fun scanTailnetForViewers() {
        val token = binding.editTailscaleToken.text.toString().trim()
        if (token.isEmpty()) {
            Toast.makeText(this, R.string.settings_scan_tailnet_need_token, Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnScanTailnet.isEnabled = false
        binding.textScanStatus.text = getString(R.string.settings_scan_tailnet_scanning)

        lifecycleScope.launch {
            try {
                val found = ViewerScan.findViewers(token)
                if (found.isEmpty()) {
                    binding.textScanStatus.text = getString(R.string.settings_scan_tailnet_none_found)
                } else {
                    val merged = ViewerScan.mergeTargets(binding.editAlertTargets.text.toString(), found)
                    binding.editAlertTargets.setText(merged)
                    credentialStore.alertTargets = merged
                    binding.textScanStatus.text = getString(R.string.settings_scan_tailnet_found, found.size)
                }
            } catch (e: Exception) {
                binding.textScanStatus.text = getString(R.string.settings_scan_tailnet_failed, e.message ?: e.toString())
            } finally {
                binding.btnScanTailnet.isEnabled = true
            }
        }
    }

    private fun startDetection() {
        save()
        if (!credentialStore.isCameraRoleConfigured) {
            Toast.makeText(this, R.string.settings_incomplete, Toast.LENGTH_SHORT).show()
            return
        }
        ContextCompat.startForegroundService(this, Intent(this, CameraDetectionService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, SnapshotServerService::class.java))
        ContextCompat.startForegroundService(this, Intent(this, VideoRelayServerService::class.java))
    }

    private fun stopDetection() {
        val intent = Intent(this, CameraDetectionService::class.java).apply {
            action = CameraDetectionService.ACTION_STOP
        }
        startService(intent)
    }

    private fun startListening() {
        ContextCompat.startForegroundService(this, Intent(this, AlertReceiverService::class.java))
    }

    private fun stopListening() {
        val intent = Intent(this, AlertReceiverService::class.java).apply {
            action = AlertReceiverService.ACTION_STOP
        }
        startService(intent)
    }
}
