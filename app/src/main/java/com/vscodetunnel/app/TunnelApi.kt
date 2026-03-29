package com.vscodetunnel.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TunnelApi {
    private const val BASE_URL = "https://global.rel.tunnels.api.visualstudio.com"

    data class Tunnel(
        val name: String,
        val tunnelId: String,
        val isOnline: Boolean,
        val description: String
    )

    suspend fun listTunnels(token: String): List<Tunnel> = withContext(Dispatchers.IO) {
        val params = "global=true&api-version=2023-09-27-preview&includePorts=true"
        val auth = "Github client_id=${GitHubAuth.CLIENT_ID} $token"

        val conn = (URL("$BASE_URL/tunnels?$params").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", auth)
            setRequestProperty("User-Agent", "VSCodeTunnel-Android/2.0")
        }

        val code = conn.responseCode
        if (code == 401 || code == 403) {
            conn.disconnect()
            throw AuthExpiredException()
        }

        val body = try {
            conn.inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            conn.errorStream?.bufferedReader()?.readText() ?: ""
        }
        conn.disconnect()
        parseTunnels(JSONObject(body))
    }

    private fun parseTunnels(json: JSONObject): List<Tunnel> {
        val tunnels = mutableListOf<Tunnel>()
        val value = json.optJSONArray("value") ?: return tunnels

        for (i in 0 until value.length()) {
            val item = value.getJSONObject(i)
            // Handle both nested (clustered) and flat responses
            val innerValue = item.optJSONArray("value")
            if (innerValue != null) {
                for (j in 0 until innerValue.length()) {
                    parseSingleTunnel(innerValue.getJSONObject(j))?.let { tunnels.add(it) }
                }
            } else {
                parseSingleTunnel(item)?.let { tunnels.add(it) }
            }
        }

        // Sort: online first, then alphabetically
        return tunnels.sortedWith(compareByDescending<Tunnel> { it.isOnline }.thenBy { it.name })
    }

    private fun parseSingleTunnel(obj: JSONObject): Tunnel? {
        val name = extractTunnelName(obj) ?: return null
        val isOnline = isTunnelOnline(obj)
        val desc = if (isOnline) "Online" else "Offline"
        return Tunnel(
            name = name,
            tunnelId = obj.optString("tunnelId", name),
            isOnline = isOnline,
            description = desc
        )
    }

    private fun extractTunnelName(obj: JSONObject): String? {
        val name = obj.optString("name", "")
        if (name.isNotBlank()) return name

        val labels = obj.optJSONArray("labels")
        if (labels != null) {
            for (i in 0 until labels.length()) {
                val label = labels.getString(i)
                if (!label.startsWith("_")) return label
            }
        }

        val tunnelId = obj.optString("tunnelId", "")
        return tunnelId.ifBlank { null }
    }

    private fun isTunnelOnline(obj: JSONObject): Boolean {
        val status = obj.optJSONObject("status")
        if (status != null && status.optInt("hostConnectionCount", 0) > 0) return true

        val endpoints = obj.optJSONArray("endpoints")
        return endpoints != null && endpoints.length() > 0
    }

    fun buildTunnelUrl(tunnelName: String): String {
        return "https://vscode.dev/tunnel/${URLEncoder.encode(tunnelName, "UTF-8")}"
    }

    data class PatchStep(val url: String, val size: Long, val from: String, val to: String)

    data class UpdateInfo(
        val version: String,
        val apkUrl: String,
        val apkSize: Long,
        val patchChain: List<PatchStep>,
        val apkSha256: String?
    ) {
        val totalPatchSize: Long get() = patchChain.sumOf { it.size }
        val hasPatch: Boolean get() = patchChain.isNotEmpty()
    }

    private const val REPO = "lucashanak/vscode_android"
    private const val MAX_PATCH_CHAIN = 3

    suspend fun checkUpdate(currentVersion: String): UpdateInfo? = withContext(Dispatchers.IO) {
        try {
            val current = currentVersion.trimStart('v')

            // Fetch last 5 releases to find patch chain
            val conn = (URL("https://api.github.com/repos/$REPO/releases?per_page=5").openConnection() as HttpURLConnection).apply {
                setRequestProperty("User-Agent", "VSCodeTunnel-Android/2.0")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            val body = conn.inputStream.bufferedReader().readText()
            conn.disconnect()
            val releases = org.json.JSONArray(body)
            if (releases.length() == 0) return@withContext null

            // Latest release info
            val latest = releases.getJSONObject(0)
            val latestVer = latest.optString("tag_name", "").trimStart('v')
            if (latestVer.isBlank() || latestVer == current) return@withContext null

            // Parse APK url + sha256 from latest release
            var apkUrl: String? = null
            var apkSize = 0L
            val latestAssets = latest.optJSONArray("assets") ?: return@withContext null
            for (i in 0 until latestAssets.length()) {
                val a = latestAssets.getJSONObject(i)
                if (a.optString("name", "").endsWith(".apk")) {
                    apkUrl = a.optString("browser_download_url", "")
                    apkSize = a.optLong("size", 0)
                }
            }
            if (apkUrl == null) return@withContext null

            val latestBody = latest.optString("body", "")
            val sha256 = Regex("sha256:([a-f0-9]{64})").find(latestBody)?.groupValues?.get(1)

            // Build patch map: from_version → PatchStep for each release
            val patchMap = mutableMapOf<String, PatchStep>()
            for (r in 0 until releases.length()) {
                val rel = releases.getJSONObject(r)
                val assets = rel.optJSONArray("assets") ?: continue
                for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    val name = a.optString("name", "")
                    if (!name.endsWith(".bspatch")) continue
                    val match = Regex("patch-(.*)-to-(.*)\\.bspatch").find(name) ?: continue
                    val from = match.groupValues[1]
                    val to = match.groupValues[2]
                    patchMap[from] = PatchStep(
                        url = a.optString("browser_download_url", ""),
                        size = a.optLong("size", 0),
                        from = from, to = to
                    )
                }
            }

            // Find chain: current → ... → latestVer (max MAX_PATCH_CHAIN steps)
            val chain = mutableListOf<PatchStep>()
            var ver = current
            for (step in 0 until MAX_PATCH_CHAIN) {
                val patch = patchMap[ver] ?: break
                chain.add(patch)
                ver = patch.to
                if (ver == latestVer) break
            }
            // Only use chain if it reaches the latest version
            val validChain = if (ver == latestVer) chain else emptyList()

            UpdateInfo(latestVer, apkUrl, apkSize, validChain, sha256)
        } catch (e: Exception) {
            FileLogger.w("TunnelApi", "checkUpdate failed: $e")
            null
        }
    }

    class AuthExpiredException : Exception("Session expired")
}
