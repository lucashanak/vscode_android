package com.vscodetunnel.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

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

/** Known host fingerprints (TOFU) */
object KnownHosts {
    private const val PREFS_NAME = "known_hosts"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getFingerprint(ctx: Context, host: String, port: Int): String? =
        prefs(ctx).getString("$host:$port", null)

    fun saveFingerprint(ctx: Context, host: String, port: Int, fingerprint: String) =
        prefs(ctx).edit().putString("$host:$port", fingerprint).apply()

    fun removeFingerprint(ctx: Context, host: String, port: Int) =
        prefs(ctx).edit().remove("$host:$port").apply()
}

object ServerStorage {
    private const val PREFS_NAME = "ssh_servers"
    private const val KEY_SERVERS = "servers"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getServers(ctx: Context): List<SshServer> {
        val json = prefs(ctx).getString(KEY_SERVERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { SshServer.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) { emptyList() }
    }

    fun saveServer(ctx: Context, server: SshServer) {
        val servers = getServers(ctx).toMutableList()
        val idx = servers.indexOfFirst { it.id == server.id }
        if (idx >= 0) servers[idx] = server else servers.add(server)
        persist(ctx, servers)
    }

    fun deleteServer(ctx: Context, id: String) {
        val servers = getServers(ctx).filter { it.id != id }
        persist(ctx, servers)
    }

    private fun persist(ctx: Context, servers: List<SshServer>) {
        val arr = JSONArray()
        servers.forEach { arr.put(it.toJson()) }
        prefs(ctx).edit().putString(KEY_SERVERS, arr.toString()).apply()
    }
}
