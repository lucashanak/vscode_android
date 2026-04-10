package com.vscodetunnel.app

import com.jcraft.jsch.Proxy
import com.jcraft.jsch.SocketFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * JSch Proxy that tunnels SSH traffic over a Cloudflare Access WebSocket.
 *
 * Cloudflare Tunnel exposes SSH at wss://<host>/__cloudflare_access/ssh/v2
 * where binary WebSocket frames carry raw SSH protocol data bidirectionally.
 * This is what `cloudflared access ssh` does internally.
 */
class CloudflareProxy(
    private val cfHost: String,
    private val cfToken: String = ""
) : Proxy {

    companion object {
        private const val TAG = "CloudflareProxy"
        // Root path — Cloudflare edge routes WebSocket to the tunnel's SSH service.
        // /__cloudflare_access/ssh/v2 is only for browser-rendered terminal.
        private const val CF_SSH_PATH = ""
        private const val PIPE_BUFFER = 65536
    }

    private var webSocket: WebSocket? = null
    private val inputPipe = PipedInputStream(PIPE_BUFFER)
    private val inputWriter = PipedOutputStream(inputPipe)
    private var wsOutputStream: OutputStream? = null
    private val connectLatch = CountDownLatch(1)
    @Volatile private var connectError: Exception? = null
    @Volatile private var closed = false

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    override fun connect(sf: SocketFactory?, host: String, port: Int, timeout: Int) {
        val url = "wss://$cfHost$CF_SSH_PATH"
        FileLogger.d(TAG, "Connecting WebSocket to $url")

        val reqBuilder = Request.Builder().url(url)
        if (cfToken.isNotBlank()) {
            reqBuilder.header("cf-access-token", cfToken)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                FileLogger.d(TAG, "WebSocket connected (${response.code})")
                connectLatch.countDown()
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (closed) return
                try {
                    val data = bytes.toByteArray()
                    synchronized(inputWriter) {
                        inputWriter.write(data)
                        inputWriter.flush()
                    }
                } catch (e: Exception) {
                    FileLogger.w(TAG, "Write to pipe failed: $e")
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                // Cloudflare might send text frames for errors
                FileLogger.w(TAG, "Unexpected text frame: $text")
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                val msg = response?.let { "HTTP ${it.code}: ${it.message}" } ?: t.message
                FileLogger.e(TAG, "WebSocket failure: $msg")
                connectError = Exception("Cloudflare WebSocket failed: $msg", t)
                connectLatch.countDown()
                closePipes()
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                FileLogger.d(TAG, "WebSocket closing: $code $reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                FileLogger.d(TAG, "WebSocket closed: $code $reason")
                closePipes()
            }
        }

        webSocket = client.newWebSocket(reqBuilder.build(), listener)

        val timeoutMs = if (timeout > 0) timeout.toLong() else 15000L
        if (!connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            webSocket?.cancel()
            throw Exception("Cloudflare WebSocket connection timeout after ${timeoutMs}ms")
        }
        connectError?.let { throw it }

        // Output stream: each write sends a binary WebSocket frame
        wsOutputStream = object : OutputStream() {
            override fun write(b: Int) {
                if (closed) return
                webSocket?.send(byteArrayOf(b.toByte()).toByteString())
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                if (closed) return
                webSocket?.send(b.toByteString(off, len))
            }

            override fun flush() {} // WebSocket sends immediately
            override fun close() { this@CloudflareProxy.close() }
        }
    }

    override fun getInputStream(): InputStream = inputPipe
    override fun getOutputStream(): OutputStream = wsOutputStream!!
    override fun getSocket(): Socket? = null // No raw TCP socket — traffic goes over WebSocket

    override fun close() {
        if (closed) return
        closed = true
        FileLogger.d(TAG, "Closing Cloudflare proxy")
        webSocket?.close(1000, "SSH session ended")
        closePipes()
        client.dispatcher.executorService.shutdown()
    }

    private fun closePipes() {
        try { inputWriter.close() } catch (_: Exception) {}
        try { inputPipe.close() } catch (_: Exception) {}
    }
}
