package com.cameraviewer.app

import android.graphics.Bitmap
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.cameraviewer.app.databinding.ActivitySnapshotsBinding
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Viewer-side: browses person-detection snapshots pulled on demand from a
 * camera's SnapshotServerService (see its own doc comment for the routes).
 * Plain dynamically-built rows rather than a RecyclerView — at most ~20
 * items (the configurable retention limit), not worth the extra machinery.
 */
class SnapshotsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySnapshotsBinding
    private var cameraIp: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySnapshotsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        title = getString(R.string.snapshots_title)

        val ip = intent.getStringExtra(EXTRA_CAMERA_IP)
        cameraIp = ip
        if (ip.isNullOrBlank()) {
            binding.textSnapshotsStatus.text = getString(R.string.snapshots_no_camera)
            return
        }

        loadSnapshots(ip)
    }

    private fun loadSnapshots(cameraIp: String) {
        lifecycleScope.launch {
            val remote = try {
                SnapshotFetcher.list(cameraIp)
            } catch (e: Exception) {
                binding.textSnapshotsStatus.text = getString(R.string.snapshots_load_failed, e.message ?: e.toString())
                return@launch
            }

            if (remote.isEmpty()) {
                binding.textSnapshotsStatus.text = getString(R.string.snapshots_empty)
                return@launch
            }
            binding.textSnapshotsStatus.text = ""

            // Fetch every thumbnail concurrently — fine at this scale (≤ the
            // configurable retention limit, default 20), same reasoning as
            // AlertClient fanning out to multiple targets in parallel.
            val thumbnails = remote.map { snap ->
                async { snap to runCatching { SnapshotFetcher.fetchImage(cameraIp, snap.filename) }.getOrNull() }
            }.awaitAll()

            thumbnails.forEach { (snap, bitmap) -> addRow(snap, bitmap) }
        }
    }

    private fun addRow(snap: RemoteSnapshot, bitmap: Bitmap?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = bitmap != null
            isFocusable = bitmap != null
        }

        val thumb = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(72), dp(72))
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageBitmap(bitmap)
            setBackgroundColor(getColor(R.color.controls_background))
        }
        row.addView(thumb)

        val label = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(16)
            }
            setTextColor(getColor(R.color.status_pill_text))
            text = DateFormat.getDateTimeInstance().format(Date(snap.timestampMs))
        }
        row.addView(label)

        if (bitmap != null) {
            row.setOnClickListener { showFullSize(bitmap, snap, row) }
        }

        binding.snapshotList.addView(row)
    }

    private fun showFullSize(bitmap: Bitmap, snap: RemoteSnapshot, row: View) {
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        AlertDialog.Builder(this)
            .setTitle(DateFormat.getDateTimeInstance().format(Date(snap.timestampMs)))
            .setView(imageView)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(R.string.delete_snapshot) { _, _ -> deleteSnapshot(snap, row) }
            .show()
    }

    private fun deleteSnapshot(snap: RemoteSnapshot, row: View) {
        val ip = cameraIp ?: return
        lifecycleScope.launch {
            val ok = runCatching { SnapshotFetcher.delete(ip, snap.filename) }.getOrDefault(false)
            if (ok) {
                binding.snapshotList.removeView(row)
                if (binding.snapshotList.childCount == 0) {
                    binding.textSnapshotsStatus.text = getString(R.string.snapshots_empty)
                }
            } else {
                Toast.makeText(this@SnapshotsActivity, R.string.snapshot_delete_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_CAMERA_IP = "com.cameraviewer.app.extra.SNAPSHOTS_CAMERA_IP"
    }
}
