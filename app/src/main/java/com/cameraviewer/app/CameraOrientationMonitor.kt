package com.cameraviewer.app

import android.content.Context
import android.hardware.SensorManager
import android.view.OrientationEventListener

/**
 * Watches the phone's physical orientation via the accelerometer (works
 * from a background Service with no Activity/Window needed, unlike
 * Display.getRotation()) and calls [onBucketChanged] only when the
 * rounded-to-nearest-90° bucket actually changes — sensor readings jitter
 * continuously near the ~45° boundary between buckets, so this exists
 * specifically to debounce that into "the device moved to a new quarter-
 * turn," not "raw noisy sensor value."
 *
 * Buckets follow OrientationEventListener's own convention: 0 = the
 * phone's natural/default orientation, 90/180/270 = rotated that many
 * degrees from it. This says nothing about which way the *camera image*
 * needs correcting for any given bucket — see
 * CameraDetectionService/SecureCredentialStore.cameraRotationCalibratedBucket
 * for why that's tracked relative to a user-verified calibration point
 * instead of a fixed formula.
 */
class CameraOrientationMonitor(context: Context, private val onBucketChanged: (Int) -> Unit) {

    private var lastBucket = -1

    private val listener = object : OrientationEventListener(context, SensorManager.SENSOR_DELAY_NORMAL) {
        override fun onOrientationChanged(orientation: Int) {
            if (orientation == ORIENTATION_UNKNOWN) return // flat, or sensor momentarily can't tell
            val bucket = (((orientation + 45) / 90) * 90) % 360
            if (bucket != lastBucket) {
                lastBucket = bucket
                onBucketChanged(bucket)
            }
        }
    }

    fun start() {
        if (listener.canDetectOrientation()) listener.enable()
    }

    fun stop() {
        listener.disable()
        lastBucket = -1
    }
}
