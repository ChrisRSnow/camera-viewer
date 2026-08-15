package com.cameraviewer.app

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Keystore-backed storage for the Tailscale API token and the camera's Basic
 * Auth credentials. Everything here is sensitive enough (a live API token,
 * a login that gates a home camera feed) to warrant EncryptedSharedPreferences
 * over a plain SharedPreferences file, even though this is a single-user app.
 */
class SecureCredentialStore(context: Context) {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var tailscaleApiToken: String?
        get() = prefs.getString(KEY_TS_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TS_TOKEN, value).apply()

    var cameraUsername: String?
        get() = prefs.getString(KEY_CAM_USER, null)
        set(value) = prefs.edit().putString(KEY_CAM_USER, value).apply()

    var cameraPassword: String?
        get() = prefs.getString(KEY_CAM_PASS, null)
        set(value) = prefs.edit().putString(KEY_CAM_PASS, value).apply()

    /** Last camera successfully discovered — used so the service doesn't have
     * to re-run discovery on every start, only when this is absent or stale. */
    var lastKnownCameraIp: String?
        get() = prefs.getString(KEY_LAST_IP, null)
        set(value) = prefs.edit().putString(KEY_LAST_IP, value).apply()

    /** Display name for THIS phone's own camera, sent in outgoing person-alerts. */
    var cameraLabel: String?
        get() = prefs.getString(KEY_CAM_LABEL, null)
        set(value) = prefs.edit().putString(KEY_CAM_LABEL, value).apply()

    /** Comma-separated Tailscale hostnames/IPs of viewer phones to alert on person detection. */
    var alertTargets: String?
        get() = prefs.getString(KEY_ALERT_TARGETS, null)
        set(value) = prefs.edit().putString(KEY_ALERT_TARGETS, value).apply()

    /**
     * ROLE_SENDER or ROLE_VIEWER, chosen once via MainActivity's first-run
     * prompt. Drives which Settings sections are shown and which service
     * auto-starts — a device isn't restricted from also configuring the
     * other role's fields by hand, this just controls the default UI/behavior.
     */
    var deviceRole: String?
        get() = prefs.getString(KEY_DEVICE_ROLE, null)
        set(value) = prefs.edit().putString(KEY_DEVICE_ROLE, value).apply()

    /** How many person-detection snapshots this camera keeps before deleting the oldest. See SnapshotStore. */
    var snapshotRetentionCount: Int
        get() = prefs.getInt(KEY_SNAPSHOT_RETENTION, DEFAULT_SNAPSHOT_RETENTION)
        set(value) = prefs.edit().putInt(KEY_SNAPSHOT_RETENTION, value).apply()

    /** Minutes between CameraControlClient autofocus nudges. See CameraDetectionService. */
    var refocusIntervalMinutes: Int
        get() = prefs.getInt(KEY_REFOCUS_INTERVAL, DEFAULT_REFOCUS_INTERVAL_MINUTES)
        set(value) = prefs.edit().putInt(KEY_REFOCUS_INTERVAL, value).apply()

    /**
     * How the camera app's raw capture needs rotating to appear correctly
     * on a normally-held viewer phone - depends entirely on how this
     * sender phone is physically mounted (e.g. landscape-mounted needs
     * 90/270). Always one of {0, 90, 180, 270}; enforced by
     * SettingsActivity's cycling button rather than free entry, since the
     * camera app's rotate= parameter is a discrete rotation, not an
     * arbitrary angle.
     */
    var cameraRotationDegrees: Int
        get() = prefs.getInt(KEY_CAMERA_ROTATION, 0)
        set(value) = prefs.edit().putInt(KEY_CAMERA_ROTATION, value).apply()

    /**
     * Whether OrientationEventListener-driven auto-rotation is on. When
     * true, cameraRotationDegrees is applied at cameraRotationCalibratedBucket
     * (the phone's physical orientation when it was set/saved) and shifted
     * by the same relative amount as the phone's orientation changes,
     * rather than reapplied verbatim regardless of orientation. See
     * CameraDetectionService's orientation handling for why this is
     * relative-to-calibration rather than an absolute sensor→rotate
     * formula: front/back camera sensor mounting varies by device, so a
     * fixed formula risks getting the direction backwards on hardware
     * this wasn't tested against.
     */
    var cameraAutoRotationEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROTATION_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ROTATION_ENABLED, value).apply()

    /** The device-orientation bucket (0/90/180/270) active when cameraRotationDegrees was last saved — the calibration point auto-rotation tracks relative to. */
    var cameraRotationCalibratedBucket: Int
        get() = prefs.getInt(KEY_ROTATION_CALIBRATED_BUCKET, 0)
        set(value) = prefs.edit().putInt(KEY_ROTATION_CALIBRATED_BUCKET, value).apply()

    /**
     * Whether the sensor→rotate-value direction should be flipped. Exists
     * because that direction depends on how this specific phone's camera
     * sensor is physically mounted relative to its body, which can't be
     * verified without live on-device testing — this is the escape hatch
     * if auto-rotation turns out to go the wrong way.
     */
    var cameraAutoRotationInverted: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ROTATION_INVERTED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_ROTATION_INVERTED, value).apply()

    /**
     * Viewer-role: whether this phone should ask the sender to drop
     * camera resolution while this phone is on cellular, restoring it
     * when back on Wi-Fi. Off by default — resolution is a property of
     * the shared camera, not per-viewer, so enabling this on one phone
     * affects every other viewer (and the sender's own preview) too if
     * anyone's watching at the same time. See NetworkQualityMonitor.
     */
    var cellularQualityReductionEnabled: Boolean
        get() = prefs.getBoolean(KEY_CELLULAR_QUALITY_REDUCTION, false)
        set(value) = prefs.edit().putBoolean(KEY_CELLULAR_QUALITY_REDUCTION, value).apply()

    /**
     * Sender-role: whether AudioCaptureService/AudioRelayServerService run
     * at all. Off by default — experimental, see AudioCaptureService's
     * doc comment for the real risk (a second simultaneous connection to
     * a camera app with documented single-viewer video encoder fragility;
     * untested whether audio shares any encoder state with video).
     */
    var audioEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUDIO_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_AUDIO_ENABLED, value).apply()

    fun alertTargetList(): List<String> =
        alertTargets.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }

    val isConfigured: Boolean
        get() = !tailscaleApiToken.isNullOrBlank() &&
            !cameraUsername.isNullOrBlank() &&
            !cameraPassword.isNullOrBlank()

    /** Enough to run person detection against this phone's own camera and alert others. */
    val isCameraRoleConfigured: Boolean
        get() = !cameraUsername.isNullOrBlank() &&
            !cameraPassword.isNullOrBlank() &&
            !cameraLabel.isNullOrBlank()

    companion object {
        private const val FILE_NAME = "secure_camera_prefs"
        private const val KEY_TS_TOKEN = "tailscale_api_token"
        private const val KEY_CAM_USER = "camera_username"
        private const val KEY_CAM_PASS = "camera_password"
        private const val KEY_LAST_IP = "last_known_camera_ip"
        private const val KEY_CAM_LABEL = "camera_label"
        private const val KEY_ALERT_TARGETS = "alert_targets"
        private const val KEY_DEVICE_ROLE = "device_role"
        private const val KEY_SNAPSHOT_RETENTION = "snapshot_retention_count"
        private const val KEY_REFOCUS_INTERVAL = "refocus_interval_minutes"
        private const val KEY_CAMERA_ROTATION = "camera_rotation_degrees"
        private const val KEY_AUTO_ROTATION_ENABLED = "camera_auto_rotation_enabled"
        private const val KEY_ROTATION_CALIBRATED_BUCKET = "camera_rotation_calibrated_bucket"
        private const val KEY_AUTO_ROTATION_INVERTED = "camera_auto_rotation_inverted"
        private const val KEY_CELLULAR_QUALITY_REDUCTION = "cellular_quality_reduction_enabled"
        private const val KEY_AUDIO_ENABLED = "audio_enabled"

        const val ROLE_SENDER = "sender"
        const val ROLE_VIEWER = "viewer"
        const val DEFAULT_SNAPSHOT_RETENTION = 20
        const val DEFAULT_REFOCUS_INTERVAL_MINUTES = 5
    }
}
