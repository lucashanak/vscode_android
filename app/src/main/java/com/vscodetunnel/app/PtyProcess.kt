package com.vscodetunnel.app

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.lang.reflect.Field

/**
 * Runs a process in a pseudo-terminal (PTY).
 * Required for programs like mosh-client that need tcgetattr/tcsetattr.
 */
class PtyProcess private constructor(
    private val masterFd: Int,
    val pid: Int
) {
    private val fd: FileDescriptor = createFileDescriptor(masterFd)
    val inputStream: FileInputStream = FileInputStream(fd)
    val outputStream: FileOutputStream = FileOutputStream(fd)

    fun setWindowSize(rows: Int, cols: Int) {
        nativeSetWindowSize(masterFd, rows, cols)
    }

    fun destroy() {
        try { android.system.Os.close(fd) } catch (_: Exception) {}
        try { android.system.Os.kill(pid, 9) } catch (_: Exception) {}
    }

    fun waitFor(): Int {
        val status = IntArray(1)
        try {
            android.system.Os.waitpid(pid, status, 0)
        } catch (_: Exception) {}
        return status[0]
    }

    val isAlive: Boolean get() = try {
        android.system.Os.kill(pid, 0)
        true
    } catch (_: Exception) { false }

    companion object {
        init { System.loadLibrary("pty_helper") }

        fun start(
            cmd: String,
            args: Array<String>,
            env: Array<String>,
            rows: Int = 24,
            cols: Int = 80
        ): PtyProcess {
            val result = nativeForkPty(cmd, args, env, rows, cols)
                ?: throw RuntimeException("Failed to create PTY process")
            return PtyProcess(result[0], result[1])
        }

        @JvmStatic
        private external fun nativeForkPty(
            cmd: String, args: Array<String>, env: Array<String>,
            rows: Int, cols: Int
        ): IntArray?

        @JvmStatic
        external fun nativeSetWindowSize(fd: Int, rows: Int, cols: Int)

        private fun createFileDescriptor(fd: Int): FileDescriptor {
            val fileDescriptor = FileDescriptor()
            val field: Field = FileDescriptor::class.java.getDeclaredField("descriptor")
            field.isAccessible = true
            field.setInt(fileDescriptor, fd)
            return fileDescriptor
        }
    }
}
