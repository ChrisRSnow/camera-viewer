package com.cameraviewer.app

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import java.io.File
import java.io.FileOutputStream

data class Snapshot(val filename: String, val timestampMs: Long, val file: File)

/**
 * Stores person-detection still images on the sender phone (app-private
 * files dir, not shared storage — nothing else on the device needs access,
 * and SnapshotServerService is the only intended way anything else reads
 * these). Filenames are strictly "snapshot_<epochMillis>.jpg" so they sort
 * correctly by name alone and so incoming filenames from the network (see
 * SnapshotServerService) can be validated against a fixed pattern before
 * ever touching the filesystem — this is the actual security boundary
 * against path traversal, not just a naming convention.
 */
object SnapshotStore {
    private const val TAG = "SnapshotStore"
    private const val DIR_NAME = "snapshots"
    private const val JPEG_QUALITY = 85
    private val FILENAME_PATTERN = Regex("""snapshot_(\d+)\.jpg""")

    private fun dir(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { mkdirs() }

    /** Saves [bitmap] as a new snapshot, then deletes the oldest ones beyond [retentionCount]. */
    fun save(context: Context, bitmap: Bitmap, retentionCount: Int) {
        val timestamp = System.currentTimeMillis()
        val file = File(dir(context), "snapshot_$timestamp.jpg")
        try {
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        } catch (e: Exception) {
            Log.w(TAG, "failed to save snapshot: ${e.message}")
            return
        }
        enforceRetention(context, retentionCount)
    }

    /** Newest first. */
    fun list(context: Context): List<Snapshot> =
        dir(context).listFiles()
            ?.mapNotNull { f -> parseFilename(f.name)?.let { ts -> Snapshot(f.name, ts, f) } }
            ?.sortedByDescending { it.timestampMs }
            ?: emptyList()

    /** Returns the file for [filename] only if it matches the strict naming pattern and actually exists — never trust a network-supplied filename otherwise. */
    fun fileFor(context: Context, filename: String): File? {
        if (!FILENAME_PATTERN.matches(filename)) return null
        val file = File(dir(context), filename)
        return if (file.exists()) file else null
    }

    /** Deletes [filename] if (and only if) it matches the strict naming pattern and exists. Returns true if a file was actually deleted. */
    fun delete(context: Context, filename: String): Boolean {
        val file = fileFor(context, filename) ?: return false
        return file.delete()
    }

    private fun parseFilename(name: String): Long? =
        FILENAME_PATTERN.matchEntire(name)?.groupValues?.get(1)?.toLongOrNull()

    private fun enforceRetention(context: Context, retentionCount: Int) {
        val keep = retentionCount.coerceAtLeast(1)
        val all = list(context)
        if (all.size <= keep) return
        all.drop(keep).forEach { stale ->
            if (!stale.file.delete()) Log.w(TAG, "failed to delete stale snapshot: ${stale.filename}")
        }
    }
}
