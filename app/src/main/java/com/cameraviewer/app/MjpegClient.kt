package com.cameraviewer.app

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Consumes the camera app's `/video/mjpeg` stream directly over a raw TLS
 * socket — same approach as `mjpeg_relay.py` on the phone-hosted web
 * dashboard, and for the same reason: the camera's cert is self-signed
 * (`CERT_NONE`-equivalent trust), the endpoint requires HTTP Basic Auth on
 * every request, and the response body is a raw `multipart/x-mixed-replace`
 * byte stream with JPEG frames delimited by SOI/EOI markers (0xFFD8/0xFFD9),
 * not something a higher-level HTTP client decodes for you.
 */
class MjpegClient {

    /**
     * Opens the stream and invokes [onFrame] with each JPEG frame's raw bytes
     * until [isActive] returns false or the connection drops (throws).
     * Blocking; call from a background dispatcher (this switches to
     * Dispatchers.IO internally, so callers don't have to).
     */
    suspend fun streamFrames(
        ip: String,
        username: String,
        password: String,
        isActive: () -> Boolean,
        useTls: Boolean = true,
        port: Int = CAMERA_PORT,
        onFrame: suspend (ByteArray) -> Unit,
    ) = withContext(Dispatchers.IO) {
        var socket: Socket? = null
        try {
            socket = if (useTls) openTlsSocket(ip, port) else openPlainSocket(ip, port)
            val input = sendRequest(socket, ip, "/video/mjpeg", username, password)

            var buf = ByteArray(0)
            val readChunk = ByteArray(65536)
            while (isActive()) {
                val n = input.read(readChunk)
                if (n <= 0) throw IOException("upstream closed the connection")
                buf = buf + readChunk.copyOf(n)

                while (true) {
                    val soi = indexOf(buf, SOI, 0)
                    if (soi < 0) {
                        if (buf.size > MAX_BUFFER_BYTES) buf = ByteArray(0)
                        break
                    }
                    val eoi = indexOf(buf, EOI, soi + 2)
                    if (eoi < 0) break
                    val frameEnd = eoi + 2
                    val frame = buf.copyOfRange(soi, frameEnd)
                    buf = buf.copyOfRange(frameEnd, buf.size)
                    onFrame(frame)
                }
            }
        } finally {
            socket?.close()
        }
    }

    private fun openTlsSocket(ip: String, port: Int): SSLSocket {
        val socket = trustAllContext.socketFactory.createSocket() as SSLSocket
        socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        socket.startHandshake()
        return socket
    }

    /**
     * Plain (non-TLS) socket, used only for this app's own video relay
     * (VideoRelayServerService) — not the third-party camera app, which
     * always requires TLS. Same trust model as AlertClient/SnapshotFetcher:
     * plain HTTP between our own app instances, Tailscale membership is the
     * access control (see AlertClient's doc comment).
     */
    private fun openPlainSocket(ip: String, port: Int): Socket {
        val socket = Socket()
        socket.connect(InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = READ_TIMEOUT_MS
        return socket
    }

    /** Sends the GET request and returns the input stream positioned right after the response headers. */
    private fun sendRequest(socket: Socket, host: String, path: String, username: String, password: String): InputStream {
        val basic = Base64.encodeToString("$username:$password".toByteArray(), Base64.NO_WRAP)
        val request = "GET $path HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Authorization: Basic $basic\r\n" +
            "Connection: close\r\n\r\n"
        socket.outputStream.write(request.toByteArray())
        socket.outputStream.flush()

        val input = socket.inputStream
        val statusLine = readLine(input)
        if (!statusLine.contains(" 200 ")) {
            throw IOException("camera returned: $statusLine")
        }
        // Skip headers up to the blank line separating them from the body.
        while (true) {
            val line = readLine(input)
            if (line == "\r\n" || line.isEmpty()) break
        }
        return input
    }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b < 0) break
            sb.append(b.toChar())
            if (prev == '\r'.code && b == '\n'.code) break
            prev = b
        }
        return sb.toString()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (from < 0) return -1
        val last = haystack.size - needle.size
        outer@ for (i in from..last) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    companion object {
        private const val TAG = "MjpegClient"
        const val CAMERA_PORT = 4444
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 15_000
        private const val MAX_BUFFER_BYTES = 2_000_000
        private val SOI = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
        private val EOI = byteArrayOf(0xFF.toByte(), 0xD9.toByte())

        // Self-signed cert, same trust-all approach as CameraProber — by this
        // point the peer has already been fingerprinted as the camera app, so
        // this is just "connect to the thing we already identified," not a
        // fresh trust decision.
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
    }
}
