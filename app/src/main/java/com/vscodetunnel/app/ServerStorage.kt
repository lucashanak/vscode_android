package com.vscodetunnel.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore

data class PortForward(
    val type: String = "local", // "local" or "remote"
    val localPort: Int = 0,
    val remoteHost: String = "127.0.0.1",
    val remotePort: Int = 0
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("type", type)
        put("localPort", localPort)
        put("remoteHost", remoteHost)
        put("remotePort", remotePort)
    }

    companion object {
        fun fromJson(j: JSONObject) = PortForward(
            type = j.optString("type", "local"),
            localPort = j.optInt("localPort", 0),
            remoteHost = j.optString("remoteHost", "127.0.0.1"),
            remotePort = j.optInt("remotePort", 0)
        )
    }

    override fun toString(): String = if (type == "local") "L$localPort:$remoteHost:$remotePort" else "R$remotePort:$remoteHost:$localPort"
}

data class SshServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val privateKey: String = "",
    /** Optional; only set when the user chose to remember it. Stored encrypted with the key. */
    val keyPassphrase: String = "",
    val startupCommand: String = "",
    val portForwards: List<PortForward> = emptyList(),
    val colorScheme: String = "default",
    val snippets: List<String> = emptyList(),
    val useMosh: Boolean = false,
    val useTmux: Boolean = false,
    val tmuxSessionName: String = "",
    val useCloudflareProxy: Boolean = false,
    val cloudflareToken: String = ""
) {
    enum class AuthMethod { PASSWORD, KEY }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("host", host)
        put("port", port)
        put("username", username)
        put("authMethod", authMethod.name)
        put("password", Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP))
        put("privateKey", Base64.encodeToString(privateKey.toByteArray(), Base64.NO_WRAP))
        put("keyPassphrase", Base64.encodeToString(keyPassphrase.toByteArray(), Base64.NO_WRAP))
        put("startupCommand", startupCommand)
        put("portForwards", JSONArray().apply { portForwards.forEach { put(it.toJson()) } })
        put("colorScheme", colorScheme)
        put("snippets", JSONArray().apply { snippets.forEach { put(it) } })
        put("useMosh", useMosh)
        put("useTmux", useTmux)
        put("tmuxSessionName", tmuxSessionName)
        put("useCloudflareProxy", useCloudflareProxy)
        put("cloudflareToken", Base64.encodeToString(cloudflareToken.toByteArray(), Base64.NO_WRAP))
    }

    companion object {
        fun fromJson(json: JSONObject): SshServer = SshServer(
            id = json.getString("id"),
            name = json.optString("name", ""),
            host = json.getString("host"),
            port = json.optInt("port", 22),
            username = json.getString("username"),
            authMethod = try {
                AuthMethod.valueOf(json.optString("authMethod", "PASSWORD"))
            } catch (_: Exception) { AuthMethod.PASSWORD },
            password = try {
                String(Base64.decode(json.optString("password", ""), Base64.NO_WRAP))
            } catch (_: Exception) { "" },
            privateKey = try {
                String(Base64.decode(json.optString("privateKey", ""), Base64.NO_WRAP))
            } catch (_: Exception) { "" },
            keyPassphrase = try {
                String(Base64.decode(json.optString("keyPassphrase", ""), Base64.NO_WRAP))
            } catch (_: Exception) { "" },
            startupCommand = json.optString("startupCommand", ""),
            portForwards = try {
                val arr = json.optJSONArray("portForwards")
                if (arr != null) (0 until arr.length()).map { PortForward.fromJson(arr.getJSONObject(it)) }
                else emptyList()
            } catch (_: Exception) { emptyList() },
            colorScheme = json.optString("colorScheme", "default"),
            useMosh = json.optBoolean("useMosh", false),
            useTmux = json.optBoolean("useTmux", false),
            tmuxSessionName = json.optString("tmuxSessionName", ""),
            useCloudflareProxy = json.optBoolean("useCloudflareProxy", false),
            cloudflareToken = try {
                String(Base64.decode(json.optString("cloudflareToken", ""), Base64.NO_WRAP))
            } catch (_: Exception) { "" },
            snippets = try {
                val arr = json.optJSONArray("snippets")
                if (arr != null) (0 until arr.length()).map { arr.getString(it) }
                else emptyList()
            } catch (_: Exception) { emptyList() }
        )

        /** Parse "user@host:port" quick-connect string */
        fun fromQuickConnect(input: String): SshServer? {
            val s = input.trim()
            if (s.isBlank()) return null
            // Format: [user@]host[:port]
            val userHost = if ('@' in s) s else return null
            val parts = userHost.split('@', limit = 2)
            val user = parts[0]
            val hostPort = parts[1]
            val hp = hostPort.split(':', limit = 2)
            val host = hp[0]
            val port = if (hp.size > 1) hp[1].toIntOrNull() ?: 22 else 22
            if (host.isBlank() || user.isBlank()) return null
            return SshServer(
                id = System.currentTimeMillis().toString(),
                name = "$user@$host",
                host = host,
                port = port,
                username = user
            )
        }
    }
}

/**
 * Keystore-backed [SharedPreferences] factory for the stores that hold secrets.
 *
 * Every entry point degrades instead of throwing: a device whose Android Keystore is broken
 * or was reset must never take the app down on startup. Degrading is *not* the same as
 * silently falling back to plaintext though - see [hasPersistedData] and the store resolution
 * in [ServerStorage], which distinguishes "nothing stored yet" from "stored but unreadable".
 */
private object SecurePrefs {
    private const val TAG = "SecurePrefs"
    private const val KEY_MIGRATED = "migrated_v1"

    /** Plaintext, secret-free bookkeeping - it only ever holds open-failure counters. */
    private const val STATE_PREFS = "secure_prefs_state"

    /** Separate launches a store must fail to open before it is written off as unrecoverable. */
    private const val FAILURE_THRESHOLD = 2

    private val lock = Any()
    private val opened = HashMap<String, SharedPreferences>()
    private val failedThisSession = HashSet<String>()

    /**
     * Legacy stores already folded into their encrypted counterpart this session.
     *
     * Memoised because the flag lives *inside* the encrypted store: re-reading it means a Tink
     * decrypt on every single [ServerStorage.getServers], and that call happens on the main
     * thread during first render.
     */
    private val migratedThisSession = HashSet<String>()

    /**
     * Stores whose ciphertext [recreate] threw away because it could no longer be decrypted.
     *
     * The loss itself is unavoidable — an AndroidKeyStore key is non-extractable, so once it is
     * gone the ciphertext is unrecoverable — but it must not be *silent*. Without this the rebuilt
     * store opens clean and empty, and the UI cheerfully reports "no saved servers" as though the
     * user had never added any.
     */
    private val wipedThisSession = HashSet<String>()

    /** Whether [name]'s previous contents were discarded by a rebuild this session. */
    fun discardedData(name: String): Boolean = synchronized(lock) { name in wipedThisSession }

    /** Encrypted prefs named [name], or null when the Keystore is unusable on this device. */
    fun open(ctx: Context, name: String): SharedPreferences? = synchronized(lock) {
        opened[name]?.let { return it }
        if (name in failedThisSession) return null

        val app = ctx.applicationContext
        // A dead or rotated master key surfaces here: the keysets stored inside the prefs
        // file are unwrapped during create(), not lazily on first read.
        create(app, name)?.let {
            recordOutcome(app, name, failed = false)
            opened[name] = it
            return it
        }

        // Deliberately NOT destructive on a first failure. Keystore errors can be transient
        // (early/direct boot, OEM quirks) and the ciphertext may well open on the next
        // launch - whereas recreate() is irreversible. Only a failure that survives separate
        // launches is treated as structural, since by then the master key really is gone.
        val failures = recordOutcome(app, name, failed = true)
        if (failures < FAILURE_THRESHOLD) {
            FileLogger.w(TAG, "Encrypted store '$name' failed to open ($failures/$FAILURE_THRESHOLD), leaving it intact for the next launch")
            failedThisSession += name
            return null
        }

        // Only clear the counter if the rebuild actually produced a usable store. Clearing it
        // unconditionally turned the "two separate launches" gate into a destructive wipe every
        // second launch on a permanently broken Keystore: 0 -> 1 -> recreate -> 0 -> 1 -> ...
        val fresh = recreate(app, name, "unopenable on $failures separate launches")
        recordOutcome(app, name, failed = fresh == null)
        if (fresh != null) opened[name] = fresh else failedThisSession += name
        fresh
    }

    /**
     * Whether prefs named [name] have ever been written to disk.
     *
     * Readable without the Keystore, which is the point: when an encrypted store fails to open,
     * this is the only way to tell "the user has data in there that is temporarily unreadable"
     * from "there is nothing to lose". [EncryptedSharedPreferences.create] writes its Tink
     * keyset into the same file, so the file existing means the store was once established.
     */
    fun hasPersistedData(ctx: Context, name: String): Boolean = try {
        val file = java.io.File(ctx.applicationContext.applicationInfo.dataDir, "shared_prefs/$name.xml")
        file.isFile && file.length() > 0
    } catch (t: Throwable) {
        FileLogger.e(TAG, "Could not probe '$name' on disk", t)
        // Assume data exists: the caller uses this to decide whether it may write plaintext,
        // and guessing "empty" is the answer that loses secrets.
        true
    }

    /**
     * Tracks consecutive failed opens of [name] across launches, in plaintext because the
     * encrypted store is by definition unavailable when it matters. Clearing on success is
     * what stops an old transient failure from accumulating into a later spurious wipe.
     *
     * @return the running failure count.
     */
    private fun recordOutcome(ctx: Context, name: String, failed: Boolean): Int = try {
        val state = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        val key = "fail_$name"
        if (!failed) {
            if (state.contains(key)) state.edit().remove(key).apply()
            0
        } else {
            val count = state.getInt(key, 0) + 1
            state.edit().putInt(key, count).commit()
            count
        }
    } catch (t: Throwable) {
        FileLogger.e(TAG, "Could not record open outcome for '$name'", t)
        // Unknown history, so assume the safe end: never enough to justify a wipe.
        if (failed) 1 else 0
    }

    /**
     * Discards the encrypted store for [name] - keysets included - and builds a fresh one.
     *
     * Irreversible, so [open] only calls it after [FAILURE_THRESHOLD] separate launches have
     * failed to open the store. By that point the master key is genuinely gone and the
     * ciphertext is unrecoverable regardless: keeping the file would strand the app in a
     * permanently broken state rather than preserve anything readable.
     */
    private fun recreate(ctx: Context, name: String, reason: String): SharedPreferences? {
        FileLogger.w(TAG, "Recreating encrypted store '$name': $reason")
        // Probe before deleting: afterwards there is no way to tell whether anything was lost, and
        // a rebuilt-but-empty store is indistinguishable from a first run.
        if (hasPersistedData(ctx, name)) {
            wipedThisSession += name
            FileLogger.e(TAG, "Discarding undecryptable contents of '$name'; the data is unrecoverable")
        }
        ctx.deleteSharedPreferences(name)
        create(ctx, name)?.let { return it }

        // Still failing, so the master key itself is at fault. Dropping the alias forces a
        // new one on the next attempt. This also invalidates the other encrypted store -
        // unavoidable, they share the default alias, and it is already unreadable too.
        try {
            KeyStore.getInstance("AndroidKeyStore")
                .apply { load(null) }
                .deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
        } catch (t: Throwable) {
            FileLogger.e(TAG, "Failed to drop master key alias", t)
        }
        ctx.deleteSharedPreferences(name)
        return create(ctx, name).also {
            if (it == null) FileLogger.e(TAG, "Encrypted store '$name' unavailable, falling back to plaintext")
        }
    }

    /**
     * Catches [Throwable] rather than [Exception] on purpose: Tink's static initialisation
     * has been seen to fail with an [Error] on unusual devices, and that must not be fatal.
     */
    private fun create(ctx: Context, name: String): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            ctx,
            name,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (t: Throwable) {
        FileLogger.e(TAG, "Failed to open encrypted prefs '$name'", t)
        null
    }

    /**
     * Folds a legacy plaintext prefs file into [secure] and unlinks it.
     *
     * The decision to migrate is driven by the legacy file's *contents*, not by the
     * "already migrated" flag. The flag alone is not trustworthy in two directions: it cannot
     * be read at all when a value fails to decrypt, and it is set even though the unlink
     * afterwards may have failed - which would leave plaintext secrets on disk that nothing
     * ever cleans up again. An empty (or absent) legacy file is the only proof that the move
     * is genuinely done.
     *
     * [merge] stages entries on the editor and returns how many it staged; it must not commit,
     * and it must resolve conflicts in favour of what is already in [secure], since a replayed
     * migration would otherwise overwrite newer edits with stale plaintext. It returns null to
     * abort - meaning it could not read what is already in [secure], so committing anything would
     * overwrite data it cannot see. Nothing is written or unlinked in that case. The single
     * [SharedPreferences.Editor.commit] below writes the payload and the flag together, so a
     * half-migrated store cannot exist, and the plaintext file is unlinked only after that
     * commit reports success.
     *
     * @return true when [secure] can be trusted to hold the data, false to keep using the
     *   plaintext store for this session.
     */
    fun migrate(
        ctx: Context,
        secure: SharedPreferences,
        legacyName: String,
        merge: (from: SharedPreferences, into: SharedPreferences.Editor) -> Int?
    ): Boolean = synchronized(lock) {
        if (legacyName in migratedThisSession) return true
        val app = ctx.applicationContext
        try {
            val legacy = app.getSharedPreferences(legacyName, Context.MODE_PRIVATE)
            if (legacy.all.isEmpty()) {
                // Nothing left to move, so the encrypted store is authoritative regardless of
                // what the flag says. Repair it with apply() - no secret is at stake and this
                // runs on the main thread.
                if (!guard(legacyName, true) { secure.getBoolean(KEY_MIGRATED, false) }) {
                    secure.edit().putBoolean(KEY_MIGRATED, true).apply()
                }
                migratedThisSession += legacyName
                return true
            }

            val editor = secure.edit()
            val moved = merge(legacy, editor)
            if (moved == null) {
                // Nothing staged is committed, so the encrypted store keeps whatever it holds and
                // the plaintext file stays on disk as the only readable copy. Retried next launch.
                FileLogger.e(TAG, "Not migrating '$legacyName': the encrypted store's current contents are unreadable")
                return false
            }
            if (!editor.putBoolean(KEY_MIGRATED, true).commit()) {
                FileLogger.e(TAG, "Migration of '$legacyName' did not commit, keeping plaintext store")
                return false
            }
            // Only now are the secrets safe to remove from the plaintext file.
            var unlinked = app.deleteSharedPreferences(legacyName)
            if (!unlinked) {
                FileLogger.w(TAG, "Could not unlink '$legacyName', clearing its contents instead")
                unlinked = try { legacy.edit().clear().commit() } catch (t: Throwable) {
                    FileLogger.e(TAG, "Could not clear '$legacyName' either", t)
                    false
                }
            }
            if (!unlinked) {
                // Secrets remain readable in plaintext and the merge will replay next launch.
                // Harmless for the data (merge prefers the encrypted side) but worth shouting
                // about, because it is a live cleartext copy of passwords and private keys.
                FileLogger.e(TAG, "Plaintext store '$legacyName' survived migration - secrets are still on disk in cleartext")
            } else {
                migratedThisSession += legacyName
            }
            FileLogger.d(TAG, "Migrated $moved entries from '$legacyName' into encrypted storage")
            true
        } catch (t: Throwable) {
            FileLogger.e(TAG, "Migration of '$legacyName' failed, keeping plaintext store", t)
            false
        }
    }

    /**
     * Runs [block] against an encrypted store, yielding [fallback] if a value cannot be
     * decrypted or encrypted. Individual entries can still be damaged after the store as a
     * whole opened successfully, and that must not propagate to the UI as a crash.
     */
    fun <T> guard(name: String, fallback: T, block: () -> T): T = try {
        block()
    } catch (t: Throwable) {
        FileLogger.e(TAG, "Encrypted store '$name' rejected an entry", t)
        fallback
    }
}

/** Known host fingerprints (TOFU) */
object KnownHosts {
    private const val PREFS = "known_hosts"

    /**
     * Pins live in ordinary app-private preferences, deliberately *not* in the encrypted store.
     *
     * A fingerprint is public data — it is what `ssh-keygen -lf` prints and what SSHFP records
     * publish — so there is nothing here to keep secret. What matters is integrity, and encryption
     * does not buy that: anyone who can write this app's private files already has app-level
     * access and could just as easily read the encrypted store through the app's own key.
     *
     * Encrypting pins actively made verification *weaker*. A decrypt failure yielded null, and null
     * is indistinguishable from "no pin yet", so a single transient Keystore failure silently
     * downgraded every known host back to trust-on-first-use. Plaintext has no such failure mode:
     * null here genuinely means "never seen this host".
     */
    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A pinned host key, for the management screen. */
    data class Pin(val host: String, val port: Int, val fingerprint: String) {
        /** "host" for the default port, "host:port" otherwise — matches how ssh reports it. */
        val label: String get() = if (port == 22) host else "$host:$port"
    }

    /**
     * Every pin, sorted by host. Needed because a legitimate key rotation otherwise leaves the user
     * stuck: the only route was the alarming change dialog, with no way to inspect or drop a pin.
     */
    fun all(ctx: Context): List<Pin> = prefs(ctx).all.mapNotNull { (key, value) ->
        val fingerprint = value as? String ?: return@mapNotNull null
        // Keys are "host:port"; an IPv6 literal contains colons too, so split from the right.
        val sep = key.lastIndexOf(':')
        if (sep <= 0) return@mapNotNull null
        val port = key.substring(sep + 1).toIntOrNull() ?: return@mapNotNull null
        Pin(key.substring(0, sep), port, fingerprint)
    }.sortedWith(compareBy({ it.host }, { it.port }))

    fun getFingerprint(ctx: Context, host: String, port: Int): String? =
        prefs(ctx).getString("$host:$port", null)

    fun saveFingerprint(ctx: Context, host: String, port: Int, fingerprint: String) {
        // commit(), not apply(): a pin must be durable before the connection it authorises
        // proceeds, otherwise a crash mid-session can lose it and re-TOFU on next launch.
        prefs(ctx).edit().putString("$host:$port", fingerprint).commit()
    }

    fun removeFingerprint(ctx: Context, host: String, port: Int) {
        prefs(ctx).edit().remove("$host:$port").commit()
    }
}

object ServerStorage {
    private const val TAG = "ServerStorage"
    private const val LEGACY_PREFS = "ssh_servers"
    private const val SECURE_PREFS = "ssh_servers_secure"
    private const val KEY_SERVERS = "servers"

    /** Where the servers live, or the fact that right now they live nowhere reachable. */
    private sealed class Store {
        /** Usable for both reads and writes. [encrypted] is false only for a pre-migration plaintext file. */
        class Usable(val prefs: SharedPreferences, val encrypted: Boolean) : Store()

        /**
         * Encrypted servers exist on disk but the Keystore cannot open them at the moment.
         *
         * Not the same as "no servers". Treating it as empty is what made the app look like it
         * had lost the user's data, and writing over it in plaintext orphaned every edit made
         * while degraded: the next successful open reads the encrypted file and the plaintext
         * one is never looked at again, leaving passwords and keys in cleartext forever.
         */
        object Unavailable : Store()
    }

    /**
     * Set when the store could not be reached, cleared on every successful resolve.
     *
     * Exists so a caller can tell an empty [getServers] result apart from an unreadable one and
     * say so in the UI; [serversOrNull] is the typed version of the same distinction.
     */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * Resolved once per process: [SecurePrefs] is sticky per session (a store that failed to
     * open stays failed, a migration that ran stays done), so the answer cannot change, and
     * caching it keeps Keystore work and lock contention off the main-thread render path.
     */
    @Volatile
    private var cached: Store? = null

    private fun resolve(ctx: Context): Store {
        cached?.let { return it }
        val store = synchronized(this) { cached ?: resolveUncached(ctx).also { cached = it } }
        return store
    }

    private fun resolveUncached(ctx: Context): Store {
        val secure = SecurePrefs.open(ctx, SECURE_PREFS)
        if (secure != null) {
            val migrated = SecurePrefs.migrate(ctx, secure, LEGACY_PREFS) { from, into ->
                mergeLegacyInto(from, secure, into)
            }
            if (migrated) {
                // A rebuild after a dead master key opens clean, so the happy path here would
                // otherwise render an empty list and call it success.
                lastError = if (SecurePrefs.discardedData(SECURE_PREFS)) {
                    "Saved servers could not be decrypted after a keystore change and had to be " +
                        "cleared. Please add them again."
                } else {
                    null
                }
                return Store.Usable(secure, encrypted = true)
            }
            // The move could not be committed, so the plaintext file is still the only complete
            // copy and nothing has been encrypted yet. Keeping it as the store is not a new
            // exposure - it is where the secrets already are - but it must not pass unnoticed.
            FileLogger.e(TAG, "Migration into encrypted storage failed; servers stay in the plaintext store this session")
            lastError = "Servers could not be moved into encrypted storage and remain unencrypted on this device."
            return Store.Usable(legacy(ctx), encrypted = false)
        }

        if (SecurePrefs.hasPersistedData(ctx, SECURE_PREFS)) {
            FileLogger.e(TAG, "Encrypted server store exists but will not open; refusing to read or write until it does")
            lastError = "Saved servers are temporarily unavailable: this device's keystore could not unlock them."
            return Store.Unavailable
        }

        // No encrypted store was ever established, so there is nothing to orphan and the
        // plaintext file is the authoritative copy. Degraded, and reported as such.
        FileLogger.e(TAG, "Keystore unusable and no encrypted store exists; falling back to the plaintext store")
        lastError = "This device's keystore is unavailable, so servers are stored unencrypted."
        return Store.Usable(legacy(ctx), encrypted = false)
    }

    private fun legacy(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)

    /**
     * Union of the plaintext and encrypted server lists, keyed by id, encrypted side winning.
     *
     * Preferring the encrypted copy is what makes a replayed migration safe: if the plaintext
     * file survived a previous run (unlink and clear both failed) it is stale, and copying it
     * over wholesale would roll back every edit made since.
     *
     * @return how many entries were staged, or null to abort the migration because the encrypted
     *   list could not be read and merging would therefore overwrite it blind.
     */
    private fun mergeLegacyInto(
        from: SharedPreferences,
        secure: SharedPreferences,
        into: SharedPreferences.Editor
    ): Int? {
        val legacyJson = from.getString(KEY_SERVERS, null)
        if (legacyJson.isNullOrBlank()) return 0

        // Both reads below abort the migration rather than degrade to emptyList(). Treating an
        // unreadable encrypted list as "nothing there" would commit the plaintext copy on top of
        // ciphertext nobody has read - the same escalation from a failed read to permanent loss
        // that serversOrNull() refuses to make, just on the migration path.
        val secureJson = runCatching { secure.getString(KEY_SERVERS, null) }.getOrElse {
            FileLogger.e(TAG, "Encrypted server list could not be decrypted; leaving it untouched", it)
            return null
        }
        val existing = if (secureJson.isNullOrBlank()) emptyList() else parse(secureJson) ?: run {
            FileLogger.e(TAG, "Encrypted server list is corrupt; leaving it untouched")
            return null
        }

        val legacyServers = parse(legacyJson) ?: run {
            // Unparseable plaintext. Carrying the raw string across keeps the unlink below from
            // being what destroys it, and a later read reports it as corrupt rather than absent.
            // A readable encrypted list supersedes this file, so then there is nothing to carry.
            if (existing.isEmpty()) {
                FileLogger.w(TAG, "Plaintext server list is unparseable; preserving it verbatim in encrypted storage")
                into.putString(KEY_SERVERS, legacyJson)
                return 1
            }
            FileLogger.w(TAG, "Discarding unparseable plaintext server list; the encrypted store holds ${existing.size} readable entries")
            return 0
        }
        if (legacyServers.isEmpty()) return 0

        val byId = LinkedHashMap<String, SshServer>()
        legacyServers.forEach { byId[it.id] = it }
        existing.forEach { byId[it.id] = it }

        val arr = JSONArray()
        byId.values.forEach { arr.put(it.toJson()) }
        into.putString(KEY_SERVERS, arr.toString())
        return byId.size
    }

    /**
     * @return the stored list, or null when [json] is present but unparseable.
     *
     * The null matters: treating a corrupt list as empty lets the next save overwrite it, turning
     * a bad read into permanent loss. Callers that genuinely want "absent" semantics say so with
     * `?: emptyList()`.
     */
    private fun parse(json: String?): List<SshServer>? {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { SshServer.fromJson(arr.getJSONObject(it)) }
        } catch (t: Exception) {
            FileLogger.e(TAG, "Stored server list is not parseable", t)
            null
        }
    }

    /**
     * Saved servers, or null when the store cannot be read right now.
     *
     * Callers that render a list should prefer this over [getServers]: null means "unknown",
     * and showing it as "no servers yet" reads to the user as data loss. [lastError] carries a
     * message for that case.
     */
    fun serversOrNull(ctx: Context): List<SshServer>? {
        val store = resolve(ctx) as? Store.Usable ?: return null

        // Deliberately not guard(): its fallback is null, which is also what getString() returns
        // for a key that was never written. Conflating the two is how an undecryptable list gets
        // reported as "no servers" and then overwritten by the next save.
        val read = runCatching { store.prefs.getString(KEY_SERVERS, null) }
        read.exceptionOrNull()?.let {
            FileLogger.e(TAG, "Server list could not be decrypted", it)
            lastError = "Saved servers could not be decrypted on this device."
            return null
        }
        val json = read.getOrNull() ?: return emptyList()

        return parse(json) ?: run {
            lastError = "The saved server list is corrupt. It has been left untouched rather than overwritten."
            null
        }
    }

    fun getServers(ctx: Context): List<SshServer> = serversOrNull(ctx) ?: emptyList()

    /** @return false when nothing was written, in which case [lastError] says why. */
    fun saveServer(ctx: Context, server: SshServer): Boolean {
        val servers = serversOrNull(ctx)?.toMutableList() ?: run {
            FileLogger.e(TAG, "Not saving server '${server.name}': store unreadable")
            return false
        }
        val idx = servers.indexOfFirst { it.id == server.id }
        if (idx >= 0) servers[idx] = server else servers.add(server)
        return persist(ctx, servers)
    }

    /** @return false when nothing was written, in which case [lastError] says why. */
    fun deleteServer(ctx: Context, id: String): Boolean {
        val servers = serversOrNull(ctx) ?: run {
            FileLogger.e(TAG, "Not deleting server '$id': store unreadable")
            return false
        }
        return persist(ctx, servers.filter { it.id != id })
    }

    private fun persist(ctx: Context, servers: List<SshServer>): Boolean {
        val store = resolve(ctx)
        if (store !is Store.Usable) {
            // Writing here would put passwords and private keys in a plaintext file that the
            // next successful Keystore open ignores forever. Losing the edit is the lesser harm.
            FileLogger.e(TAG, "Refusing to persist ${servers.size} servers while the encrypted store is unreadable")
            return false
        }
        if (!store.encrypted) {
            // Allowed only because resolve() proved no encrypted copy exists to orphan, but it
            // is still cleartext credentials - say so on every write rather than once at startup.
            FileLogger.w(TAG, "Persisting ${servers.size} servers to the plaintext store; the keystore is unavailable")
        }
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        return SecurePrefs.guard(SECURE_PREFS, false) {
            store.prefs.edit().putString(KEY_SERVERS, arr.toString()).commit()
        }
    }
}
