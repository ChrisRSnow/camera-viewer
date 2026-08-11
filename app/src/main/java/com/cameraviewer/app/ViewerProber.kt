package com.cameraviewer.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Weaker counterpart to CameraProber: AlertReceiverService listens on
 * ALERT_PORT with plain HTTP and no distinguishing certificate (see its own
 * doc comment — deliberately unauthenticated, Tailscale membership IS the
 * access control), so there's no cryptographic fingerprint to check the way
 * CameraProber checks the camera app's TLS cert subject. The best available
 * signal is just "something is listening on this port" — a plain TCP
 * connect-and-close, no data sent. This can false-positive on any other
 * device that happens to have ALERT_PORT open, which in practice is
 * unlikely on a home tailnet, but is a materially weaker guarantee than
 * CameraProber's cert check.
 */
object ViewerProber {
    private const val TAG = "ViewerProber"
    private const val CONNECT_TIMEOUT_MS = 3_000

    /** Returns true if something is listening on [ip]:ALERT_PORT. */
    suspend fun isViewer(ip: String): Boolean = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(ip, AlertClient.ALERT_PORT), CONNECT_TIMEOUT_MS)
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "$ip:${AlertClient.ALERT_PORT} probe failed (not a viewer or unreachable): ${e.message}")
            false
        }
    }
}
