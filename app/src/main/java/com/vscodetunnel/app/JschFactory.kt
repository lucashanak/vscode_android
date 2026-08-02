package com.vscodetunnel.app

import android.content.Context
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import android.os.Handler
import android.os.Looper
import com.vscodetunnel.app.AppSettings.sshConnectTimeout
import com.vscodetunnel.app.AppSettings.sshKeepaliveInterval
import java.util.Properties
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Single place where JSch sessions are built.
 *
 * Every SSH path in the app (shell, mosh bootstrap, tmux exec, sftp) must go through here so that
 * host-key verification cannot be bypassed on one of them.
 *
 * The important property: host keys are checked by a [HostKeyRepository] that JSch consults during
 * key exchange, i.e. *before* any credential is sent. The previous approach — setting
 * StrictHostKeyChecking=no and comparing fingerprints after Session.connect() returned — read the
 * key only after the password had already been transmitted to whoever was on the other end.
 */
object JschFactory {
    private const val TAG = "JschFactory"

    /**
     * Blocking confirmation callback for an unknown or changed host key.
     *
     * Invoked on JSch's connect thread during key exchange, so it must block until the user
     * decides. Implementations that show UI have to bounce to the main thread and wait.
     *
     * @param changed false for a first-sight (TOFU) key, true when the pinned key no longer matches
     * @param oldFingerprint the previously pinned fingerprint, only set when [changed]
     * @return true to accept and pin this key
     */
    fun interface HostKeyPrompt {
        fun confirm(
            host: String,
            port: Int,
            fingerprint: String,
            changed: Boolean,
            oldFingerprint: String?
        ): Boolean
    }

    /**
     * Long enough for the user to go and compare the fingerprint, but deliberately under OpenSSH's
     * default `LoginGraceTime` of 120s — waiting longer than the server will is pointless, and
     * answering after the server has hung up just produces a confusing low-level connect error.
     * This is only a backstop against UI that never answers at all; [blockingPrompt] guarantees a
     * reply even when the dialog cannot be shown.
     */
    private const val PROMPT_TIMEOUT_MS = 110_000L

    /**
     * Adapts a UI confirmation into the blocking [HostKeyPrompt] JSch requires.
     *
     * JSch calls the prompt on its connect thread during key exchange, so the wait has to be a real
     * block. [showOnMain] runs on the main thread and must invoke its `respond` callback exactly
     * once. If showing the dialog throws — a finishing Activity gives `BadTokenException` — this
     * answers "reject" rather than leaving the connect thread parked until the timeout.
     */
    fun blockingPrompt(
        showOnMain: (
            host: String,
            port: Int,
            fingerprint: String,
            changed: Boolean,
            oldFingerprint: String?,
            respond: (Boolean) -> Unit
        ) -> Unit
    ): HostKeyPrompt = HostKeyPrompt { host, port, fingerprint, changed, old ->
        val answer = ArrayBlockingQueue<Boolean>(1)
        Handler(Looper.getMainLooper()).post {
            try {
                showOnMain(host, port, fingerprint, changed, old) { ok -> answer.offer(ok) }
            } catch (t: Throwable) {
                FileLogger.e(TAG, "Host key dialog could not be shown, rejecting", t)
                answer.offer(false)
            }
        }
        try {
            answer.poll(PROMPT_TIMEOUT_MS, TimeUnit.MILLISECONDS) ?: run {
                FileLogger.w(TAG, "Host key prompt timed out for $host:$port")
                false
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Build a session for [server], with host-key verification wired in.
     *
     * Does not connect — the caller sets any extra options and calls connect() itself.
     *
     * @param prompt consulted for unknown/changed keys. When null, a first-sight key is pinned
     *   silently (unattended paths such as the tmux session list) but a *changed* key is always
     *   rejected.
     */
    fun newSession(
        context: Context,
        server: SshServer,
        prompt: HostKeyPrompt? = null
    ): Session {
        val jsch = JSch()

        if (server.authMethod == SshServer.AuthMethod.KEY && server.privateKey.isNotBlank()) {
            jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
        }

        jsch.hostKeyRepository = KnownHostsRepository(context, jsch, server.host, server.port, prompt)

        val session = jsch.getSession(server.username, server.host, server.port)

        // Only offer the password when password auth is actually selected — a stale password field
        // on a key-auth server would otherwise be sent to the host.
        if (server.authMethod == SshServer.AuthMethod.PASSWORD && server.password.isNotBlank()) {
            session.setPassword(server.password)
        }

        val config = Properties()
        // Fail closed. Every accept/reject decision is made inside KnownHostsRepository.check(),
        // which runs during KEX; anything it does not return OK for must abort the connection.
        config["StrictHostKeyChecking"] = "yes"
        // Pin the fingerprint hash. It already defaults to sha256, but JSch lets the
        // `jsch.fingerprint_hash` system property override it — and if that ever resolved to md5,
        // the SHA256 sanity check in KnownHostsRepository.check() would reject every host key and
        // no connection would be possible at all. Also keeps stored pins comparable forever.
        config["FingerprintHash"] = "sha256"
        // Cheap win on metered mobile links.
        config["compression.s2c"] = "zlib@openssh.com,zlib,none"
        config["compression.c2s"] = "zlib@openssh.com,zlib,none"
        session.setConfig(config)

        session.timeout = context.sshConnectTimeout.coerceIn(5, 120) * 1000

        val keepalive = context.sshKeepaliveInterval
        if (keepalive > 0) {
            // JSch's interval is in milliseconds.
            session.setServerAliveInterval(keepalive * 1000)
            session.setServerAliveCountMax(3)
        }

        if (server.useCloudflareProxy) {
            session.setProxy(CloudflareProxy(server.host, server.cloudflareToken))
        }

        return session
    }

    /**
     * HostKeyRepository backed by the app's [KnownHosts] pin store.
     *
     * Bound to one server: JSch formats the host argument as "[host]:port" for non-default ports,
     * so the expected host/port are captured here rather than parsed back out.
     */
    private class KnownHostsRepository(
        private val context: Context,
        private val jsch: JSch,
        private val expectedHost: String,
        private val expectedPort: Int,
        private val prompt: HostKeyPrompt?
    ) : HostKeyRepository {

        override fun check(host: String?, key: ByteArray?): Int {
            if (key == null) return HostKeyRepository.NOT_INCLUDED

            val fingerprint = try {
                HostKey(expectedHost, key).getFingerPrint(jsch)
            } catch (e: Exception) {
                FileLogger.e(TAG, "Could not fingerprint host key: $e")
                return HostKeyRepository.NOT_INCLUDED
            }

            // JSch's Util.getFingerPrint returns the literal "???" if hashing throws rather than
            // propagating. Pinning that would be catastrophic: every subsequent key would also
            // fingerprint to "???" and compare equal, silently disabling host verification
            // altogether. Only accept a well-formed digest.
            if (!fingerprint.startsWith("SHA256:") || fingerprint.length < 16) {
                FileLogger.e(TAG, "Refusing malformed host key fingerprint: $fingerprint")
                return HostKeyRepository.NOT_INCLUDED
            }

            val known = KnownHosts.getFingerprint(context, expectedHost, expectedPort)

            if (known == fingerprint) return HostKeyRepository.OK

            if (known == null) {
                // First sight. Fail closed without a prompt: silently pinning here would defeat the
                // whole mechanism, because the *unattended* paths (tmux session list, sftp) often
                // reach a new server before the shell does. Whoever pins first decides, and the
                // shell would then find a matching pin and never ask the user anything.
                if (prompt == null) {
                    FileLogger.w(TAG, "No pin for $expectedHost:$expectedPort and no prompt available")
                    return HostKeyRepository.NOT_INCLUDED
                }
                if (!prompt.confirm(expectedHost, expectedPort, fingerprint, false, null)) {
                    FileLogger.w(TAG, "Host key rejected for $expectedHost:$expectedPort")
                    return HostKeyRepository.NOT_INCLUDED
                }
                KnownHosts.saveFingerprint(context, expectedHost, expectedPort, fingerprint)
                FileLogger.d(TAG, "Pinned host key for $expectedHost:$expectedPort")
                return HostKeyRepository.OK
            }

            // Pinned key no longer matches. Never auto-accept this, even unattended.
            FileLogger.w(TAG, "HOST KEY CHANGED for $expectedHost:$expectedPort")
            val accepted = prompt?.confirm(expectedHost, expectedPort, fingerprint, true, known) ?: false
            if (!accepted) return HostKeyRepository.CHANGED

            KnownHosts.saveFingerprint(context, expectedHost, expectedPort, fingerprint)
            return HostKeyRepository.OK
        }

        override fun add(hostkey: HostKey?, ui: UserInfo?) {
            val hk = hostkey ?: return
            val fingerprint = try {
                hk.getFingerPrint(jsch)
            } catch (e: Exception) {
                return
            }
            KnownHosts.saveFingerprint(context, expectedHost, expectedPort, fingerprint)
        }

        override fun remove(host: String?, type: String?) =
            KnownHosts.removeFingerprint(context, expectedHost, expectedPort)

        override fun remove(host: String?, type: String?, key: ByteArray?) =
            KnownHosts.removeFingerprint(context, expectedHost, expectedPort)

        override fun getKnownHostsRepositoryID(): String = "vscodetunnel-known-hosts"

        // The pin store keeps fingerprints rather than full keys, so there is nothing meaningful to
        // enumerate here. Returning empty is safe: JSch only uses these for listing.
        override fun getHostKey(): Array<HostKey> = emptyArray()

        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }
}
