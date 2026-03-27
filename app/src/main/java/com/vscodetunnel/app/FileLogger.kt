package com.vscodetunnel.app

import android.content.Context
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

    fun init(context: Context) {
        logFile = File(context.filesDir, FILE_NAME)
        log("I", "FileLogger", "=== App started, version=${BuildConfig.VERSION_NAME} ===")
    }

    fun log(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val file = logFile ?: return
        try {
            val time = dateFormat.format(Date())
            val line = buildString {
                append("$time $level/$tag: $message")
                if (throwable != null) {
                    append("\n  ${throwable::class.java.simpleName}: ${throwable.message}")
                    for (frame in throwable.stackTrace.take(8)) {
                        append("\n    at $frame")
                    }
                }
                append("\n")
            }

            // Truncate if too large
            if (file.exists() && file.length() > MAX_SIZE) {
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
