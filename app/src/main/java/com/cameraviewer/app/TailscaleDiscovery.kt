package com.cameraviewer.app

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class TailscalePeer(val hostname: String, val ipv4: String)

/**
 * Lists tailnet devices via the Tailscale REST API, same approach as the
 * laptop-side discovery design for the web dashboard — except this runs
 * directly against api.tailscale.com over the internet rather than the
 * `tailscale` CLI, since Termux/Android has no CLI to shell out to.
 *
 * Auth: API access tokens (tskey-api-...) are presented as HTTP Basic Auth
 * with the token as the username and an empty password — NOT a bearer
 * token. This is easy to get wrong; Tailscale's docs use curl's `-u
 * "$TOKEN:"` form, which is exactly this.
 */
object TailscaleDiscovery {
    private const val TAG = "TailscaleDiscovery"
    private const val DEVICES_URL = "https://api.tailscale.com/api/v2/tailnet/-/devices"

    suspend fun listPeers(apiToken: String): List<TailscalePeer> = withContext(Dispatchers.IO) {
        val conn = URL(DEVICES_URL).openConnection() as HttpURLConnection
        try {
            val basic = Base64.encodeToString("$apiToken:".toByteArray(), Base64.NO_WRAP)
            conn.setRequestProperty("Authorization", "Basic $basic")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                throw TailscaleApiException(
                    "Tailscale API returned HTTP $code" +
                        if (code == 401 || code == 403) " — check the API token in Settings" else "",
                )
            }

            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val devices = JSONObject(body).getJSONArray("devices")
            val peers = mutableListOf<TailscalePeer>()
            for (i in 0 until devices.length()) {
                val device = devices.getJSONObject(i)
                val hostname = device.optString("hostname", device.optString("name", "unknown"))
                val addresses = device.optJSONArray("addresses") ?: continue
                var ipv4: String? = null
                for (j in 0 until addresses.length()) {
                    val addr = addresses.getString(j)
                    // Tailscale IPv4 addresses are always in the 100.64.0.0/10 CGNAT range.
                    if (addr.startsWith("100.")) {
                        ipv4 = addr
                        break
                    }
                }
                if (ipv4 != null) {
                    peers.add(TailscalePeer(hostname, ipv4))
                }
            }
            Log.i(TAG, "discovered ${peers.size} tailnet peer(s) with an IPv4 address")
            peers
        } finally {
            conn.disconnect()
        }
    }
}

class TailscaleApiException(message: String) : Exception(message)
