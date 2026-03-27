package com.vscodetunnel.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object GitHubAuth {
    const val CLIENT_ID = "01ab8ac9400c4e429b23"

    private fun HttpURLConnection.readResponseBody(): String {
        return try {
            inputStream.bufferedReader().readText()
        } catch (_: Exception) {
            errorStream?.bufferedReader()?.readText() ?: ""
        }
    }

    suspend fun requestDeviceCode(): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("https://github.com/login/device/code").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
        }
        OutputStreamWriter(conn.outputStream).use {
            it.write("client_id=$CLIENT_ID&scope=read:user+read:org")
            it.flush()
        }
        val body = conn.readResponseBody()
        conn.disconnect()
        JSONObject(body)
    }

    suspend fun pollToken(deviceCode: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("https://github.com/login/oauth/access_token").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            doOutput = true
        }
        val form = "client_id=$CLIENT_ID&device_code=$deviceCode&grant_type=urn:ietf:params:oauth:grant-type:device_code"
        OutputStreamWriter(conn.outputStream).use {
            it.write(form)
            it.flush()
        }
        val body = conn.readResponseBody()
        conn.disconnect()
        JSONObject(body)
    }

    suspend fun getUser(token: String): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL("https://api.github.com/user").openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", "VSCodeTunnel-Android/2.0")
        }
        val body = conn.readResponseBody()
        conn.disconnect()
        JSONObject(body)
    }
}
