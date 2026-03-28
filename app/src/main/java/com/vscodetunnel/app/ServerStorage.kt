package com.vscodetunnel.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class SshServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.PASSWORD,
    val password: String = "",
    val privateKey: String = ""
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
            } catch (_: Exception) { "" }
        )
    }
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
