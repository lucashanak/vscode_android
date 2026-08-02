package com.vscodetunnel.app

import android.content.Context
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
    suspend fun listSessions(context: Context, server: SshServer, prompt: JschFactory.HostKeyPrompt?): List<TmuxSession> = withContext(Dispatchers.IO) {
        try {
            val output = sshExec(context, server, "tmux list-sessions -F '#{session_name}|||#{session_windows}|||#{session_attached}' 2>/dev/null", prompt)
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
    suspend fun isInstalled(context: Context, server: SshServer, prompt: JschFactory.HostKeyPrompt?): Boolean = withContext(Dispatchers.IO) {
        try {
            val output = sshExec(context, server, "command -v tmux 2>/dev/null", prompt)
            output.trim().isNotBlank()
        } catch (_: Exception) { false }
    }

    /**
     * Build the tmux command to create or attach to a session.
     * Uses `new-session -A` which creates if not exists, attaches if it does.
     */
    fun buildAttachCommand(sessionName: String): String {
        val safeName = sessionName.replace("'", "'\\''")
        // Enable mouse so touch scroll works (set-option runs after server starts).
        // tmux does not forward OSC 52 by default, so the terminal's clipboard handler never
        // sees a copy unless these two are also set. terminal-features is tmux >= 3.2 only and
        // fails at runtime (harmlessly, but noisily) on 3.0/3.1, so terminal-overrides is also set
        // for the older Ms-capability-based mechanism — belt and suspenders across tmux versions.
        return "tmux new-session -A -s '$safeName' \\; set-option -g mouse on" +
            " \\; set-option -g set-clipboard on \\; set-option -ga terminal-features ',*:clipboard'" +
            " \\; set-option -ga terminal-overrides ',*:Ms=\\E]52;%p1%s;%p2%s\\7'"
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
    suspend fun killSession(context: Context, server: SshServer, sessionName: String, prompt: JschFactory.HostKeyPrompt?): Boolean = withContext(Dispatchers.IO) {
        try {
            sshExec(context, server, buildKillCommand(sessionName), prompt)
            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to kill tmux session: $e")
            false
        }
    }

    private fun sshExec(context: Context, server: SshServer, command: String, prompt: JschFactory.HostKeyPrompt?): String {
        // Identity, password (auth-method gated), timeout, keepalive, compression,
        // CloudflareProxy and host-key verification are all handled by JschFactory. The caller
        // supplies the prompt so a first-sight key on this path can surface a dialog too — see
        // JschFactory's kdoc on why an unprompted first-sight pin here is no longer safe.
        val sess = JschFactory.newSession(context, server, prompt)
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
