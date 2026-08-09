package com.vscodetunnel.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object FileLogger {
    private const val TAG = "FileLogger"
    private const val FILE_NAME = "app_debug.log"
    private const val MAX_SIZE = 512 * 1024 // 512KB

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /**
     * Empty in the main process, ":tab0"-style suffix in a GeckoView child process.
     *
     * GeckoView ships services declared with `android:process=":tab0"`, `":socket"`, `":gpu"` and
     * friends *inside our own package*, and Android instantiates the manifest's Application class
     * (`.App`) once per process. So `init` — and therefore the "=== App started ===" banner — runs
     * once per process, not once per app launch. Tagging child-process lines makes the apparent
     * duplication self-explaining instead of looking like a double-init bug.
     */
    private var procSuffix = ""
    private var isMainProcess = true

    /**
     * Whether this is the app's own process rather than one of GeckoView's child processes.
     *
     * Exposed because `init` runs once per process (see above), so anything that must happen exactly
     * once per launch — [LogcatBridge] reads a buffer shared by every process in this UID — needs to
     * ask, not assume.
     */
    val isMain: Boolean get() = isMainProcess

    fun init(context: Context) {
        logFile = File(context.filesDir, FILE_NAME)
        val proc = currentProcessName(context)
        isMainProcess = proc == context.packageName
        procSuffix = if (isMainProcess) "" else proc.substringAfter(':', proc).let { ":$it" }
        log("I", "FileLogger", "=== App started, version=${BuildConfig.VERSION_NAME} " +
            "proc=$proc pid=${android.os.Process.myPid()} ===")
    }

    /**
     * `/proc/self/cmdline` first because it works on every API level we support (minSdk 26) —
     * `Application.getProcessName()` only landed in API 28.
     */
    private fun currentProcessName(context: Context): String {
        try {
            // cmdline is a NUL-separated argv; argv[0] is the name Android gave this process
            val cmdline = File("/proc/self/cmdline").readText()
                .substringBefore('\u0000').trim()
            if (cmdline.isNotEmpty()) return cmdline
        } catch (_: Exception) {
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            android.app.Application.getProcessName()?.let { return it }
        }
        return context.packageName
    }

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val file = logFile ?: return
        try {
            val time = dateFormat.format(Date())
            val line = buildString {
                append("$time $level/$tag$procSuffix: $message")
                if (throwable != null) {
                    append("\n  ${throwable::class.java.simpleName}: ${throwable.message}")
                    for (frame in throwable.stackTrace.take(8)) {
                        append("\n    at $frame")
                    }
                }
                append("\n")
            }

            // Truncate if too large — main process only. This read-then-rewrite is not atomic, and
            // a GeckoView child process (see [procSuffix]) appends to the same file: if a child's
            // O_APPEND write lands between the read and the rewrite, that write is silently lost.
            // Appends themselves are safe (O_APPEND seeks to EOF atomically per write), so confining
            // the rewrite to one process removes the only path that can actually eat log content.
            if (isMainProcess && file.exists() && file.length() > MAX_SIZE) {
                val tail = file.readText().takeLast(MAX_SIZE / 2)
                file.writeText("--- truncated ---\n$tail")
            }

            file.appendText(line)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write log", e)
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        log("D", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        log("E", tag, message, throwable)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        log("W", tag, message, throwable)
    }

    fun readLog(context: Context): String {
        val file = File(context.filesDir, FILE_NAME)
        return if (file.exists()) file.readText() else "(empty log)"
    }

    fun clearLog(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
        log("I", "FileLogger", "=== Log cleared ===")
    }
}
