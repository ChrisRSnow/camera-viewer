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

        const val ROLE_SENDER = "sender"
        const val ROLE_VIEWER = "viewer"
        const val DEFAULT_SNAPSHOT_RETENTION = 20
        const val DEFAULT_REFOCUS_INTERVAL_MINUTES = 5
    }
}
