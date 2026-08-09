package com.vscodetunnel.app

import android.content.Context
import java.io.BufferedOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * A local HTTP server that reproduces the blank-workbench failure under controlled conditions.
 *
 * Everything before this asked vscode.dev what was wrong, one question per release, and the page
 * cannot answer: Gecko reports a failed module load internally rather than through the page's
 * console, logcat gives this app nothing on this device (`seen=0`), and `eval` is blocked by Trusted
 * Types. Seven rounds established *where* it fails — an inline `<script type=module>` of 325 639
 * characters, importing the 17.7 MB workbench bundle, fires an `error` event — and eliminated the
 * body itself (cached bytes equal fresh bytes equal what a healthy desktop downloads), the bytecode
 * cache, CORS cache poisoning, IndexedDB, the viewport, the UA and the locale.
 *
 * So stop measuring the black box and rebuild it. Serving the test pages from here means every
 * factor is separately controllable, the failure can be bisected in one run instead of one release
 * each, and nothing depends on a third party's page. Two ports because two ports are two origins:
 * that makes the cross-origin module import real, with CORS headers this file decides.
 *
 * The matrix, each case an isolated document with its own response headers:
 *
 *   1  1 KB module, same origin                           — does module loading work at all
 *   2  1 KB module, cross origin                          — does a CORS module import work
 *   3  17.7 MB module, same origin                        — does the size alone break it
 *   4  17.7 MB module, cross origin                       — size plus CORS
 *   5  4 + a 325 KB inline module doing the static import — the exact shape of vscode.dev
 *   6  5 under require-trusted-types-for 'script'         — Trusted Types enforcement
 *   7  the REAL bundle, same origin                       — the file's own content and compile cost
 *   8  the REAL bundle, cross origin, 325 KB inline       — the closest reproduction available
 *
 * Cases 1-6 all passed on the affected device, which retires "GeckoView cannot load a large
 * cross-origin module from inside a large inline module" — it does, faultlessly. It also exposed a
 * limit of those cases that has to be said plainly: a generated module is 17.7 MB of comments, so it
 * tests transfer size and almost no compile cost, whereas the real bundle is 17.7 MB of dense
 * minified code. Hence 7 and 8, which serve the genuine file.
 *
 * **Reading 7 and 8 correctly.** The real bundle expects globals that only VS Code's bootstrap sets,
 * so it is *supposed* to throw once it runs. Measured on a working browser, the healthy outcome is:
 *
 *   FAIL:window-error: Error: !!! NLS MISSING: 2055 !!!
 *
 * That is a success for this purpose — it means the module instantiated and executed. The failure
 * that matters is `FAIL:error-event-on-script-element` or `FAIL:resource-error:<script>`, which is
 * instantiation failing, and that is the signature seen on vscode.dev. Without this baseline, a
 * healthy result here reads as a failure.
 *
 * Results come back through an HTTP request to /report, which lands in [FileLogger]. No content
 * script, no extension, no page console, nothing that has already proved unreachable.
 */
object DiagServer {
    private const val TAG = "DiagServer"

    /** Matches the real bundle and the real inline bootstrap, since the point is to reproduce them. */
    private const val BIG_SIZE = 17_695_268
    private const val INLINE_SIZE = 325_639
    private const val SMALL_SIZE = 1_024

    /** Long enough for 17.7 MB over loopback several times over; short enough to fail visibly. */
    private const val CASE_TIMEOUT_MS = 12_000
    private const val CASE_GAP_MS = 14_000

    @Volatile private var portMain = -1
    @Volatile private var portAlt = -1
    @Volatile private var running = false
    @Volatile private var appContext: Context? = null
    private val bundleLock = Any()

    /**
     * Starts both listeners and returns the harness URL, or null if it could not bind.
     *
     * Idempotent: a second call returns the already-running harness rather than binding again.
     */
    fun start(context: Context): String? {
        appContext = context.applicationContext
        if (running && portMain > 0) return "http://127.0.0.1:$portMain/harness"
        return try {
            val main = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            val alt = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
            portMain = main.localPort
            portAlt = alt.localPort
            running = true
            listen(main, "main")
            listen(alt, "alt")
            FileLogger.w(TAG, "Diagnostic server on 127.0.0.1:$portMain (alt origin :$portAlt)")
            "http://127.0.0.1:$portMain/harness"
        } catch (t: Throwable) {
            FileLogger.e(TAG, "Could not start diagnostic server", t)
            running = false
            null
        }
    }

    private fun listen(server: ServerSocket, label: String) {
        Thread({
            try {
                while (running) {
                    val socket = server.accept()
                    // One connection at a time. A diagnostic serving six pages to one browser has no
                    // use for concurrency, and a single thread cannot interleave two 17 MB writes.
                    try {
                        handle(socket)
                    } catch (t: Throwable) {
                        FileLogger.w(TAG, "[$label] request failed: ${t.message}")
                    } finally {
                        try { socket.close() } catch (_: Throwable) {}
                    }
                }
            } catch (t: Throwable) {
                if (running) FileLogger.e(TAG, "[$label] listener stopped", t)
            }
        }, "diag-server-$label").apply { isDaemon = true }.start()
    }

    private fun handle(socket: Socket) {
        val input = socket.getInputStream().bufferedReader()
        val requestLine = input.readLine() ?: return
        // Headers are read and discarded: nothing here varies on them.
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
        }
        val target = requestLine.split(' ').getOrNull(1) ?: "/"
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        val out = BufferedOutputStream(socket.getOutputStream())

        when (path) {
            "/harness" -> sendHtml(out, harnessHtml(), null)
            "/case" -> sendHtml(out, caseHtml(query), if (query["tt"] == "1") TT_CSP else null)
            "/mod.js" -> sendModule(out, (query["size"] ?: "0").toIntOrNull() ?: 0)
            "/real.js" -> sendRealBundle(out)
            "/report" -> {
                val case = query["case"] ?: "?"
                val result = query["r"] ?: "?"
                // The whole point of the exercise arrives on this line.
                FileLogger.w(TAG, "CASE $case → $result")
                sendHtml(out, "ok", null)
            }
            else -> sendHtml(out, "not found", null)
        }
        out.flush()
    }

    private fun parseQuery(raw: String): Map<String, String> =
        raw.split('&').mapNotNull { part ->
            if (part.isEmpty()) null else {
                val k = part.substringBefore('=')
                val v = part.substringAfter('=', "")
                try { k to URLDecoder.decode(v, "UTF-8") } catch (_: Throwable) { k to v }
            }
        }.toMap()

    private const val TT_CSP = "require-trusted-types-for 'script'"

    private fun sendHtml(out: OutputStream, body: String, csp: String?) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = StringBuilder()
            .append("HTTP/1.1 200 OK\r\n")
            .append("Content-Type: text/html; charset=utf-8\r\n")
            .append("Content-Length: ${bytes.size}\r\n")
            .append("Cache-Control: no-store\r\n")
        if (csp != null) head.append("Content-Security-Policy: $csp\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.US_ASCII))
        out.write(bytes)
    }

    /**
     * A valid ES module of very nearly [size] bytes.
     *
     * Streamed in chunks rather than built as one string: a 17.7 MB String plus its UTF-8 copy is
     * exactly the kind of allocation that could make this diagnostic fail for its own reasons.
     * `Access-Control-Allow-Origin` is unconditional so the cross-origin cases test the import rather
     * than the header.
     */
    private fun sendModule(out: OutputStream, size: Int) {
        val prefix = "export const ok = true;\n"
        val padLine = "//" + "p".repeat(97) + "\n"     // exactly 100 bytes
        val padCount = ((size - prefix.length) / padLine.length).coerceAtLeast(0)
        val total = prefix.length + padCount * padLine.length

        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/javascript; charset=utf-8\r\n" +
            "Content-Length: $total\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(prefix.toByteArray(Charsets.US_ASCII))

        // ~64 KB per write, so the browser sees a normal streamed response.
        val batch = StringBuilder()
        repeat(640) { batch.append(padLine) }
        val batchBytes = batch.toString().toByteArray(Charsets.US_ASCII)
        val padBytes = padLine.toByteArray(Charsets.US_ASCII)
        var written = 0
        while (written + 640 <= padCount) {
            out.write(batchBytes)
            written += 640
        }
        while (written < padCount) {
            out.write(padBytes)
            written++
        }
    }

    /**
     * Serves the genuine workbench bundle, fetched once from the CDN and cached on disk.
     *
     * Cases 1-6 all passed on the affected device, which retires "GeckoView cannot load a large
     * cross-origin module" and leaves the real file's own content as the open question — a generated
     * module of padding compiles to almost nothing, whereas 17.7 MB of dense minified code does not.
     * Serving the real bytes from here separates content from origin: if it fails same-origin too, the
     * file is the problem; if it loads, the problem is in how vscode.dev loads it.
     *
     * The URL cannot be hardcoded because its commit changes; the content script records whichever one
     * the page actually requested (see OverlayManager), so the tunnel has to have been opened once.
     */
    private fun sendRealBundle(out: OutputStream) {
        val ctx = appContext
        val url = ctx?.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            ?.getString("last_bundle_url", null)
        if (ctx == null || url.isNullOrEmpty()) {
            FileLogger.w(TAG, "No bundle URL recorded yet — open the tunnel once, then re-run")
            sendHtml(out, "no bundle url", null)
            return
        }
        val cached = java.io.File(ctx.cacheDir, "real-bundle.js")
        synchronized(bundleLock) {
            if (!cached.exists() || cached.length() < 1_000_000) {
                try {
                    val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                        setRequestProperty("User-Agent", CHROME_UA)
                        // Identity encoding: the file is served to the browser uncompressed, so
                        // storing it decoded keeps Content-Length honest without a second pass.
                        setRequestProperty("Accept-Encoding", "identity")
                        connectTimeout = 20_000
                        readTimeout = 60_000
                    }
                    conn.inputStream.use { input ->
                        cached.outputStream().use { file -> input.copyTo(file, 64 * 1024) }
                    }
                    FileLogger.w(TAG, "Fetched real bundle: ${cached.length()} bytes")
                } catch (t: Throwable) {
                    FileLogger.e(TAG, "Could not fetch the real bundle", t)
                    try { cached.delete() } catch (_: Throwable) {}
                    sendHtml(out, "fetch failed", null)
                    return
                }
            }
        }
        val head = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/javascript; charset=utf-8\r\n" +
            "Content-Length: ${cached.length()}\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.US_ASCII))
        cached.inputStream().use { it.copyTo(out, 64 * 1024) }
    }

    private const val CHROME_UA = GeckoManager.CHROME_USER_AGENT

    /** Case parameters, kept next to the matrix in the class comment. */
    private data class Case(
        val id: Int, val size: Int, val cross: Boolean, val inlineBytes: Int, val tt: Boolean,
        val label: String, val real: Boolean = false
    )

    private fun cases() = listOf(
        Case(1, SMALL_SIZE, false, 0, false, "1KB same-origin"),
        Case(2, SMALL_SIZE, true, 0, false, "1KB cross-origin"),
        Case(3, BIG_SIZE, false, 0, false, "17.7MB same-origin"),
        Case(4, BIG_SIZE, true, 0, false, "17.7MB cross-origin"),
        Case(5, BIG_SIZE, true, INLINE_SIZE, false, "17.7MB cross + 325KB inline module"),
        Case(6, BIG_SIZE, true, INLINE_SIZE, true, "case 5 + require-trusted-types-for"),
        // The generated module is 17.7 MB of comments, which tests transfer size and almost no
        // compile cost. 17.7 MB of dense minified code is a different question, and cases 1-6 passing
        // on the device is what makes it the question that matters. These two serve the real file.
        Case(7, 0, false, 0, false, "REAL bundle, same origin", real = true),
        Case(8, 0, true, INLINE_SIZE, false, "REAL bundle, cross origin, 325KB inline", real = true)
    )

    private fun harnessHtml(): String {
        val frames = cases().joinToString(",\n") { c ->
            "  {id:${c.id}, url:'/case?case=${c.id}&size=${c.size}" +
                "&cross=${if (c.cross) 1 else 0}&inline=${c.inlineBytes}" +
                "&tt=${if (c.tt) 1 else 0}&real=${if (c.real) 1 else 0}', label:${quote(c.label)}}"
        }
        // Sequential, not parallel: two 17.7 MB module compilations at once would muddy every result
        // and could fail for reasons that have nothing to do with the question.
        return """
<!doctype html><meta charset="utf-8"><title>module diagnostics</title>
<style>body{font:14px monospace;background:#1e1e1e;color:#ddd;padding:12px}
div{margin:4px 0;padding:4px;border-left:3px solid #555}</style>
<h3>Module load diagnostics</h3><div id=log></div>
<script>
const CASES = [
$frames
];
const log = document.getElementById('log');
function say(t) { const d = document.createElement('div'); d.textContent = t; log.appendChild(d); }
let i = 0;
function next() {
  if (i >= CASES.length) {
    say('--- all cases dispatched; results are in the app log (tag DiagServer) ---');
    fetch('/report?case=all&r=dispatched');
    return;
  }
  const c = CASES[i++];
  say('running case ' + c.id + ': ' + c.label);
  const f = document.createElement('iframe');
  f.style.cssText = 'width:1px;height:1px;border:0;opacity:0.01';
  f.src = c.url;
  document.body.appendChild(f);
  // Each case page reports its own outcome, including its own timeout, so the gap here only has to
  // keep the cases from overlapping.
  setTimeout(() => { try { f.remove(); } catch (e) {} next(); }, $CASE_GAP_MS);
}
next();
</script>
""".trimIndent()
    }

    /**
     * One case: either a plain `<script type=module src=...>` or, for the interesting cases, a large
     * inline module that statically imports the same URL — which is what vscode.dev actually does.
     *
     * Every failure route is covered, because the whole difficulty so far has been failures that
     * report through none of them: the element's `error` event, `window.onerror`, an unhandled
     * rejection, a CSP violation, and a timeout for a load that simply never finishes.
     */
    private fun caseHtml(q: Map<String, String>): String {
        val case = q["case"] ?: "?"
        val size = (q["size"] ?: "0").toIntOrNull() ?: 0
        val cross = q["cross"] == "1"
        val inlineBytes = (q["inline"] ?: "0").toIntOrNull() ?: 0
        val origin = if (cross) "http://127.0.0.1:$portAlt" else ""
        val modUrl = if (q["real"] == "1") "$origin/real.js" else "$origin/mod.js?size=$size"

        val loader = if (inlineBytes > 0) {
            // Padding first so the import statement sits deep inside a large module, as it does in
            // the real bootstrap, rather than on the first line.
            val padding = buildString {
                val line = "//" + "q".repeat(97) + "\n"
                repeat((inlineBytes / line.length).coerceAtLeast(1)) { append(line) }
            }
            """
            <script type="module">
            $padding
            import { ok } from ${quote(modUrl)};
            report(ok ? 'ok:inline-module-imported' : 'odd:imported-but-falsy');
            </script>
            """.trimIndent()
        } else {
            """
            <script type="module" src=${quote(modUrl)}
                    onload="report('ok:script-module-loaded')"
                    onerror="report('FAIL:error-event-on-script-element')"></script>
            """.trimIndent()
        }

        return """
<!doctype html><meta charset="utf-8"><title>case $case</title>
<script>
// Not a module, and deliberately first: this has to be in place before anything below runs.
var done = false;
function report(r) {
  if (done) return;
  done = true;
  // Cache-busting is unnecessary (no-store) but the case id must survive, so it goes in the query.
  new Image().src = '/report?case=$case&r=' + encodeURIComponent(r) + '&t=' + Date.now();
}
window.addEventListener('error', function (e) {
  if (e.target && e.target.tagName) report('FAIL:resource-error:<' + e.target.tagName.toLowerCase() + '>');
  else report('FAIL:window-error:' + (e.message || '').slice(0, 120));
}, true);
window.addEventListener('unhandledrejection', function (e) {
  report('FAIL:rejection:' + String((e.reason && (e.reason.message || e.reason.name)) || e.reason).slice(0, 120));
}, true);
document.addEventListener('securitypolicyviolation', function (e) {
  report('FAIL:csp:' + e.violatedDirective + ':' + String(e.blockedURI || '').slice(0, 60));
}, true);
// A module that never resolves and never errors is the failure shape seen on vscode.dev, so silence
// has to be reported as its own outcome rather than left as an absent line.
setTimeout(function () { report('FAIL:timeout-no-outcome'); }, $CASE_TIMEOUT_MS);
</script>
$loader
""".trimIndent()
    }

    /** JSON/JS string literal, so a URL with & or ' cannot break out of the generated source. */
    private fun quote(s: String): String =
        "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "") + "'"
}
