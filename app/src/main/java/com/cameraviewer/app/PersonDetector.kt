package com.cameraviewer.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.core.BaseOptions
import org.tensorflow.lite.task.vision.detector.ObjectDetector

/**
 * On-device person detection using a real COCO-trained object detector
 * (EfficientDet-Lite0, bundled as `assets/person_detector.tflite` — Google's
 * official metadata-populated Task Library model), NOT a brightness-delta
 * heuristic. This is what actually distinguishes "a person walked past" from
 * "a shadow moved" or "a cat walked past" — MotionDetector can't tell those
 * apart, this can.
 *
 * The bundled model ships its own label file via TFLite metadata, so
 * category labels are resolved as strings ("person", "car", "cat", ...) —
 * matching on the string is robust regardless of the model's internal class
 * index numbering.
 */
class PersonDetector(context: Context) {

    private val detector: ObjectDetector = ObjectDetector.createFromFileAndOptions(
        context,
        MODEL_ASSET,
        ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(BaseOptions.builder().setNumThreads(4).build())
            .setMaxResults(5)
            .setScoreThreshold(MIN_SCORE)
            .build(),
    )

    private var streak = 0
    private var lastAlertAt = 0L

    /** Reset debounce state — call when starting a fresh detection session. */
    fun reset() {
        streak = 0
    }

    /**
     * Feed one decoded frame. Returns true if an alert should fire now: a
     * person was found in this frame AND the previous frame (so a single
     * spurious detection can't fire an alert on its own — same
     * two-consecutive-frames reasoning as MotionDetector), AND the alert
     * cooldown has elapsed.
     */
    fun onFrame(bitmap: Bitmap): Boolean {
        val personFound = try {
            detector.detect(TensorImage.fromBitmap(bitmap))
                .any { detection -> detection.categories.any { it.label.equals("person", ignoreCase = true) } }
        } catch (e: Exception) {
            Log.w(TAG, "inference failed on this frame, skipping: ${e.message}")
            false
        }

        if (!personFound) {
            streak = 0
            return false
        }

        streak++
        if (streak < CONSECUTIVE_FRAMES_REQUIRED) return false

        val now = System.currentTimeMillis()
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) return false
        lastAlertAt = now
        return true
    }

    fun close() = detector.close()

    companion object {
        private const val TAG = "PersonDetector"
        private const val MODEL_ASSET = "person_detector.tflite"
        // Lowered from 0.5 - EfficientDet-Lite0 downscales every frame to a
        // fixed 320x320 before inference, so a distant person (occupying a
        // small fraction of the frame) scores meaningfully lower than a
        // close/large one even when clearly a person - 0.5 was filtering
        // out real, distant detections, not just noise. Trade-off: more
        // willing to flag ambiguous person-shaped things at this threshold.
        private const val MIN_SCORE = 0.35f
        private const val CONSECUTIVE_FRAMES_REQUIRED = 2
        private const val ALERT_COOLDOWN_MS = 30_000L
    }
}
