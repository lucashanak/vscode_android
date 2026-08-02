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
import okhttp3.Protocol
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
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
        // Byte-bounded (not chunk-count-bounded) backpressure buffer between the OkHttp
        // WebSocket reader thread (producer) and JSch's read loop (consumer). 4 MB is enough
        // to absorb a real burst — e.g. `cat`-ing a large file into a laggy WebView post queue
        // — without tripping the stall detector below. Worst-case memory is bounded and
        // near-deterministic: 4 MB, plus at most one oversized frame (see the permit cap in
        // onMessage), independent of how large individual WS frames happen to be.
        private const val QUEUE_MAX_BYTES = 4 * 1024 * 1024
        // Must stay comfortably BELOW PING_INTERVAL_S. Waiting here blocks the OkHttp reader
        // thread, which is also the thread that reads pong frames; block it for as long as the
        // ping interval and OkHttp finds `awaitingPong` still set on the next ping and kills the
        // socket with "sent ping but didn't receive pong" — the exact failure this buffer exists
        // to avoid. 10s is still far longer than any genuine burst takes to drain, so only a
        // truly wedged consumer reaches it.
        private const val QUEUE_OFFER_TIMEOUT_MS = 10_000L
        // Poll granularity for the reader side to notice `closed` and return EOF promptly.
        private const val QUEUE_POLL_MS = 500L
        // Primary liveness signal — a mobile/Starlink egress-IP change kills the TCP
        // connection with no RST, so pings are what actually detect it.
        private const val PING_INTERVAL_S = 30L

        /**
         * Shared across every proxy instance, deliberately.
         *
         * OkHttp clients are designed to be shared: each one owns a dispatcher thread pool and a
         * ConnectionPool, and a Cloudflare server on a flaky link builds a new proxy per reconnect
         * attempt. One client per proxy leaked both on every failed tunnel. Nothing here may be
         * shut down per-proxy for the same reason.
         *
         * pingInterval/readTimeout/protocols are per-client rather than per-call, so this instance
         * must carry exactly the settings every proxy needs.
         */
        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1)) // Force HTTP/1.1 — WebSocket upgrade fails over HTTP/2
                // Backstop, not the primary signal: Android Doze can pause OkHttp's ping scheduler,
                // which would otherwise leave a half-open tunnel blocked on read forever.
                .readTimeout(60, TimeUnit.SECONDS)
                .pingInterval(PING_INTERVAL_S, TimeUnit.SECONDS)
                .build()
        }
    }

    /** A buffered inbound chunk plus the [bufferSpace] permits it holds, released once fully consumed. */
    private class Chunk(val bytes: ByteArray, val permits: Int)

    private var webSocket: WebSocket? = null
    private val incomingQueue = LinkedBlockingQueue<Chunk>()
    private val bufferSpace = Semaphore(QUEUE_MAX_BYTES)
    private var wsOutputStream: OutputStream? = null
    private val connectLatch = CountDownLatch(1)
    @Volatile private var connectError: Exception? = null

    /**
     * The tunnel can no longer carry data: set by close(), and by the listener when the socket
     * fails or is closed by the peer. Drives EOF on the input stream.
     */
    @Volatile private var closed = false

    /**
     * Whether close() has already run its teardown. Kept separate from [closed] on purpose:
     * a WebSocket failure sets `closed` from the listener, and a shared `if (closed) return`
     * guard then turned JSch's later close() into a no-op, so teardown was skipped on exactly
     * the path that needs it most.
     */
    @Volatile private var closeRequested = false

    /** InputStream backed by [incomingQueue], with blocking-read semantics matching what JSch expects. */
    private inner class QueueInputStream : InputStream() {
        private var leftover: Chunk? = null
        private var leftoverPos = 0

        override fun read(): Int {
            val single = ByteArray(1)
            val n = read(single, 0, 1)
            return if (n <= 0) -1 else single[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0

            if (leftover == null) {
                while (true) {
                    // Only signal EOF once the queue is fully drained, so a normal close
                    // still delivers any bytes that arrived just before it.
                    if (closed && incomingQueue.isEmpty()) return -1

                    val chunk = try {
                        incomingQueue.poll(QUEUE_POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        // InputStream.read() only declares IOException; don't let a checked
                        // InterruptedException escape into JSch's catch (IOException) blocks.
                        Thread.currentThread().interrupt()
                        return -1
                    } ?: continue
                    if (chunk.bytes.isEmpty()) {
                        bufferSpace.release(chunk.permits)
                        continue
                    }
                    // Park it before touching the caller's buffer. poll() has already removed the
                    // chunk from the queue, so anything thrown between here and the copy (bad
                    // off/len bounds) would strand the chunk's permits forever — permanently
                    // shrinking the backpressure budget and slowly strangling the tunnel.
                    leftover = chunk
                    leftoverPos = 0
                    break
                }
            }

            val chunk = leftover!!
            val n = minOf(chunk.bytes.size - leftoverPos, len)
            System.arraycopy(chunk.bytes, leftoverPos, b, off, n)
            leftoverPos += n
            if (leftoverPos >= chunk.bytes.size) {
                leftover = null
                bufferSpace.release(chunk.permits)
            }
            return n
        }

        override fun close() { this@CloudflareProxy.close() }
    }

    private val inputStreamImpl = QueueInputStream()

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
                val data = bytes.toByteArray()
                // Cap the permit request at the full budget so a single message larger than
                // QUEUE_MAX_BYTES is still admitted (occupying the whole buffer) instead of
                // waiting on a request that could never be satisfied.
                val permits = minOf(data.size, QUEUE_MAX_BYTES)
                try {
                    if (!bufferSpace.tryAcquire(permits, QUEUE_OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        FileLogger.e(
                            TAG,
                            "Closing tunnel: JSch has not drained ${QUEUE_MAX_BYTES / 1024}KB of buffered " +
                                "inbound data in ${QUEUE_OFFER_TIMEOUT_MS}ms — its reader (WebView post " +
                                "queue) appears genuinely stuck, not just briefly backed up"
                        )
                        close()
                        return
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
                // Unbounded queue structurally — backpressure is enforced by bufferSpace above,
                // so this never blocks the reader thread.
                incomingQueue.offer(Chunk(data, permits))
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
                closed = true
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                FileLogger.d(TAG, "WebSocket closing: $code $reason")
                ws.close(code, reason)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                FileLogger.d(TAG, "WebSocket closed: $code $reason")
                closed = true
            }
        }

        webSocket = sharedClient.newWebSocket(reqBuilder.build(), listener)

        val timeoutMs = if (timeout > 0) timeout.toLong() else 15000L
        if (!connectLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            webSocket?.cancel()
            throw Exception("Cloudflare WebSocket connection timeout after ${timeoutMs}ms")
        }
        connectError?.let { throw it }

        // Output stream: each write sends a binary WebSocket frame
        wsOutputStream = object : OutputStream() {
            // Throw rather than return: a silent return tells JSch the bytes were sent when they
            // were discarded, which it can only interpret as a peer that has stopped responding.
            // IOException is the signal it expects for a dead transport and handles cleanly.
            private fun requireOpen() {
                if (closed) throw IOException("Cloudflare WebSocket tunnel is closed")
            }

            override fun write(b: Int) {
                requireOpen()
                webSocket?.send(byteArrayOf(b.toByte()).toByteString())
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                requireOpen()
                webSocket?.send(b.toByteString(off, len))
            }

            override fun flush() {} // WebSocket sends immediately
            override fun close() { this@CloudflareProxy.close() }
        }
    }

    override fun getInputStream(): InputStream = inputStreamImpl
    override fun getOutputStream(): OutputStream = wsOutputStream!!
    override fun getSocket(): Socket? = null // No raw TCP socket — traffic goes over WebSocket

    override fun close() {
        if (closeRequested) return
        closeRequested = true
        closed = true
        FileLogger.d(TAG, "Closing Cloudflare proxy")
        // Safe on an already-failed socket (no-op) and required on a live one; the shared
        // OkHttpClient is never shut down here — other proxies are still using it.
        webSocket?.close(1000, "SSH session ended")
    }
}
