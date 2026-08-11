package com.cameraviewer.app

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Fingerprints a Tailscale peer as an "Android IP Camera" instance by TLS
 * connecting to its :4444 and checking the server certificate's Subject —
 * the camera app uses a self-signed cert with `O=Android IP Camera`.
 *
 * This needs no credentials at all: the TLS handshake (and the certificate
 * it presents) happens before any HTTP request or Basic Auth header, so a
 * peer can be identified as a camera even before its login is known. A
 * trust-all TrustManager is used deliberately — the cert is self-signed and
 * untrusted by design, and the point here is to *read* the cert, not to
 * validate a chain against it.
 */
object CameraProber {
    private const val TAG = "CameraProber"
    private const val CAMERA_PORT = 4444
    private const val CAMERA_CERT_ORG_MARKER = "O=Android IP Camera"
    private const val CONNECT_TIMEOUT_MS = 4_000

    private val trustAllContext: SSLContext by lazy {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAll), java.security.SecureRandom())
        }
    }

    /** Returns true if the peer at [ip]:4444 presents the camera app's cert. */
    suspend fun isCamera(ip: String): Boolean = withContext(Dispatchers.IO) {
        var socket: SSLSocket? = null
        try {
            socket = trustAllContext.socketFactory.createSocket() as SSLSocket
            socket.connect(java.net.InetSocketAddress(ip, CAMERA_PORT), CONNECT_TIMEOUT_MS)
            socket.soTimeout = CONNECT_TIMEOUT_MS
            socket.startHandshake()

            val cert = socket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: return@withContext false
            val subject = cert.subjectX500Principal.name
            val matched = subject.contains(CAMERA_CERT_ORG_MARKER)
            if (matched) Log.i(TAG, "$ip:$CAMERA_PORT matched camera cert subject: $subject")
            matched
        } catch (e: Exception) {
            // Not a camera, not reachable, or doesn't speak TLS on that port —
            // all of these just mean "this peer isn't the camera," not an error
            // worth surfacing.
            Log.d(TAG, "$ip:$CAMERA_PORT probe failed (not a camera or unreachable): ${e.message}")
            false
        } finally {
            socket?.close()
        }
    }
}
