package com.cameraviewer.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Enumerates tailnet peers and probes each for an open ALERT_PORT (see
 * ViewerProber's own doc comment on why this is a weaker signal than
 * CameraProber's cert check). Pulled out of SettingsActivity so both the
 * manual "Scan tailnet for viewers" button AND the sender role's automatic
 * scan-on-save/scan-on-startup (MainActivity) share one implementation
 * instead of two copies drifting apart.
 */
object ViewerScan {
    suspend fun findViewers(token: String): List<String> = withContext(Dispatchers.IO) {
        val peers = TailscaleDiscovery.listPeers(token)
        peers.map { peer ->
            async { if (ViewerProber.isViewer(peer.ipv4)) peer.ipv4 else null }
        }.awaitAll().filterNotNull()
    }

    /** Merges [found] into the existing comma-separated [currentTargets], deduplicated. */
    fun mergeTargets(currentTargets: String?, found: List<String>): String {
        val existing = currentTargets.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
        return (existing + found).distinct().joinToString(", ")
    }
}
