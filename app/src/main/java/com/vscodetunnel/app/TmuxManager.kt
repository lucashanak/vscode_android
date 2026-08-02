package com.vscodetunnel.app

import android.content.Context
import android.content.SharedPreferences
import com.jcraft.jsch.ChannelExec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Manages tmux sessions on a remote server over SSH.
 */
object TmuxManager {
    private const val TAG = "TmuxManager"

    // Session-name history, keyed by server id: which names the picker should show by default
    // instead of every session on the host (a shared server can have dozens from other tools).
    //
    // Deliberately a plain prefs file rather than ServerStorage's encrypted store, and that is a
    // considered choice, not an oversight: a session name is not a credential, so it cannot be
    // replayed or authenticated with, and paying the keystore round-trip on every picker open to
    // protect it would be theatre. The trade-off worth naming is that names do routinely carry
    // project or customer names ("acme-migration"), so this file is not free of information about
    // the user's work — it just holds nothing that grants access to anything. Anyone raising the
    // bar here should move the whole history into ServerStorage rather than half-encrypting it.
    private const val HISTORY_PREFS_NAME = "tmux_sessions"
    private const val MAX_HISTORY_PER_SERVER = 20

    private fun historyPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(HISTORY_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Session names this app has actually used on [serverId], most recent first.
     */
    fun knownSessionNames(context: Context, serverId: String): List<String> {
        val raw = historyPrefs(context).getString(serverId, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to parse tmux session history for $serverId: $e")
            emptyList()
        }
    }

    /**
     * Record that [name] was used on [serverId], moving it to the front if already present.
     * Idempotent and capped so the history can't grow without bound. Cheap enough to call
     * straight from dialog construction on the main thread (apply(), not commit()).
     */
    fun rememberSessionName(context: Context, serverId: String, name: String) {
        if (name.isBlank()) return
        val updated = knownSessionNames(context, serverId).toMutableList()
        updated.remove(name)
        updated.add(0, name)
        while (updated.size > MAX_HISTORY_PER_SERVER) updated.removeAt(updated.size - 1)
        historyPrefs(context).edit().putString(serverId, JSONArray(updated).toString()).apply()
    }

    /**
     * Drop [name] from [serverId]'s history, e.g. once the session behind it no longer exists.
     */
    fun forgetSessionName(context: Context, serverId: String, name: String) {
        val current = knownSessionNames(context, serverId).toMutableList()
        if (!current.remove(name)) return
        historyPrefs(context).edit().putString(serverId, JSONArray(current).toString()).apply()
    }

    /**
     * Drop [serverId]'s whole history, for when the server itself is deleted.
     *
     * Without this the entry outlives the server that produced it: the id is never reused, so
     * nothing would ever read or clear it again, and the names would sit in prefs indefinitely.
     */
    fun forgetServer(context: Context, serverId: String) {
        historyPrefs(context).edit().remove(serverId).apply()
    }

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
                    // tmux doesn't forbid "|" in a session name, so a name containing "|||" is
                    // possible in principle. limit=3 means such a name would absorb into the
                    // later fields instead of the reverse, e.g. "a|||b|||2|||0" splits as
                    // ["a", "b", "2|||0"]: the name gets truncated and windows/attached parse
                    // from garbage, but toIntOrNull() below falls back to defaults rather than
                    // crashing or misattaching the row to a different session.
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
            val result = sshExecWithStatus(context, server, buildKillCommand(sessionName), prompt)
            if (!result.succeeded) {
                // A refused kill used to read as success, so the caller dropped the row while the
                // session was still alive on the server. Report the truth instead.
                FileLogger.w(
                    TAG,
                    "kill-session '$sessionName' failed: exit=${result.exitStatus} err=${result.stderr}"
                )
                return@withContext false
            }
            // The session is gone, so it shouldn't keep showing up as a "known" name either.
            forgetSessionName(context, server.id, sessionName)
            true
        } catch (e: Exception) {
            FileLogger.w(TAG, "Failed to kill tmux session: $e")
            false
        }
    }

    /**
     * Outcome of a remote command: what it printed, what it complained about, and how it exited.
     */
    private data class ExecResult(val output: String, val stderr: String, val exitStatus: Int) {
        /**
         * tmux signals a refused command with a non-zero exit and a message on stderr, so both are
         * checked. An [exitStatus] of -1 means the channel closed without one ever arriving — a
         * dropped connection — which is equally not a success we can act on.
         */
        val succeeded: Boolean get() = exitStatus == 0 && stderr.isBlank()
    }

    /** How long to wait for the exec channel to close so its exit status is actually populated. */
    private const val EXIT_STATUS_WAIT_MS = 2_000
    private const val EXIT_STATUS_POLL_MS = 25

    private fun sshExec(context: Context, server: SshServer, command: String, prompt: JschFactory.HostKeyPrompt?): String =
        sshExecWithStatus(context, server, command, prompt).output

    private fun sshExecWithStatus(
        context: Context,
        server: SshServer,
        command: String,
        prompt: JschFactory.HostKeyPrompt?
    ): ExecResult {
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
            val errStream = java.io.ByteArrayOutputStream()
            channel.setErrStream(errStream)
            val stream = channel.inputStream
            channel.connect()
            val output = stream.bufferedReader().readText().trim()
            // Draining stdout to EOF is not the same as the channel being closed, and JSch only
            // fills in exitStatus once it is — reading earlier just gives -1. Bounded so a server
            // that never sends the exit-status message can't park this thread forever.
            var waited = 0
            while (!channel.isClosed && waited < EXIT_STATUS_WAIT_MS) {
                Thread.sleep(EXIT_STATUS_POLL_MS.toLong())
                waited += EXIT_STATUS_POLL_MS
            }
            val status = channel.exitStatus
            channel.disconnect()
            return ExecResult(output, errStream.toString().trim(), status)
        } finally {
            sess.disconnect()
        }
    }
}
