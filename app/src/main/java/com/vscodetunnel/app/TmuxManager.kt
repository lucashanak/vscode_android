package com.vscodetunnel.app

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Properties

/**
 * Manages tmux sessions on a remote server over SSH.
 */
object TmuxManager {
    private const val TAG = "TmuxManager"

    data class TmuxSession(
        val name: String,
        val windows: Int,
        val attached: Boolean,
        val created: String
    ) {
        val statusText: String get() = buildString {
            append("$windows window${if (windows != 1) "s" else ""}")
            if (attached) append(", attached")
            if (created.isNotBlank()) append(" ($created)")
        }
    }

    /**
     * List tmux sessions on the remote server.
     * Returns empty list if tmux is not installed or no sessions exist.
     */
    suspend fun listSessions(server: SshServer): List<TmuxSession> = withContext(Dispatchers.IO) {
        try {
            val output = sshExec(server, "tmux list-sessions -F '#{session_name}|||#{session_windows}|||#{session_attached}' 2>/dev/null")
            output.lines()
                .filter { it.contains("|||") }
                .map { line ->
                    val parts = line.split("|||", limit = 3)
                    TmuxSession(
                        name = parts[0],
                        windows = parts.getOrNull(1)?.toIntOrNull() ?: 1,
                        attached = (parts.getOrNull(2)?.toIntOrNull() ?: 0) > 0,
                        created = ""
                    )
                }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to list tmux sessions: $e")
            emptyList()
        }
    }

    /**
     * Check if tmux is installed on the remote server.
     */
    suspend fun isInstalled(server: SshServer): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = sshExec(server, "command -v tmux 2>/dev/null")
            output.trim().isNotBlank()
        } catch (_: Exception) { false }
    }

    /**
     * Build the tmux command to create or attach to a session.
     * Uses `new-session -A` which creates if not exists, attaches if it does.
     */
    fun buildAttachCommand(sessionName: String): String {
        val safeName = sessionName.replace("'", "'\\''")
        // Enable mouse so touch scroll works (set-option runs after server starts)
        return "tmux new-session -A -s '$safeName' \\; set-option -g mouse on"
    }

    /**
     * Build command to kill a tmux session.
     */
    fun buildKillCommand(sessionName: String): String {
        val safeName = sessionName.replace("'", "'\\''")
        return "tmux kill-session -t '$safeName'"
    }

    /**
     * Kill a tmux session on the remote server.
     */
    suspend fun killSession(server: SshServer, sessionName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            sshExec(server, buildKillCommand(sessionName))
            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to kill tmux session: $e")
            false
        }
    }

    private fun sshExec(server: SshServer, command: String): String {
        val jsch = JSch()
        if (server.authMethod == SshServer.AuthMethod.KEY && server.privateKey.isNotBlank()) {
            jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
        }
        val sess = jsch.getSession(server.username, server.host, server.port)
        if (server.password.isNotBlank()) sess.setPassword(server.password)
        val config = Properties()
        config["StrictHostKeyChecking"] = "no"
        sess.setConfig(config)
        sess.timeout = 10000
        sess.connect()

        try {
            val channel = sess.openChannel("exec") as ChannelExec
            channel.setCommand(command)
            channel.inputStream = null
            val stream = channel.inputStream
            channel.connect()
            val output = stream.bufferedReader().readText().trim()
            channel.disconnect()
            return output
        } finally {
            sess.disconnect()
        }
    }
}
