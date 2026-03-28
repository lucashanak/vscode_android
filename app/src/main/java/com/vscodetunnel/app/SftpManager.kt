package com.vscodetunnel.app

import android.content.Context
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties
import java.util.Vector

class SftpManager(
    private val context: Context,
    private val webView: WebView
) {
    companion object {
        private const val TAG = "SftpManager"
    }

    private var session: Session? = null
    private var channel: ChannelSftp? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    var isConnected = false; private set

    fun connect(server: SshServer) {
        scope.launch {
            try {
                postJs("showStatus('Connecting...')")
                val jsch = JSch()
                if (server.authMethod == SshServer.AuthMethod.KEY && server.privateKey.isNotBlank()) {
                    jsch.addIdentity("key", server.privateKey.toByteArray(), null, null)
                }
                val sess = jsch.getSession(server.username, server.host, server.port)
                if (server.authMethod == SshServer.AuthMethod.PASSWORD && server.password.isNotBlank()) {
                    sess.setPassword(server.password)
                }
                val config = Properties()
                config["StrictHostKeyChecking"] = "no"
                sess.setConfig(config)
                sess.timeout = 15000
                sess.connect()
                session = sess

                val ch = sess.openChannel("sftp") as ChannelSftp
                ch.connect()
                channel = ch
                isConnected = true

                val home = ch.home
                postJs("showStatus('Connected'); setPath('$home')")
                listDir(home)
            } catch (e: Exception) {
                FileLogger.e(TAG, "SFTP connect failed: $e")
                postJs("showStatus('Connection failed: ${e.message?.replace("'", "\\'")}')")
            }
        }
    }

    fun disconnect() {
        isConnected = false
        try { channel?.disconnect() } catch (_: Exception) {}
        try { session?.disconnect() } catch (_: Exception) {}
        channel = null; session = null
    }

    fun destroy() { disconnect(); scope.cancel() }

    private fun listDir(path: String) {
        scope.launch {
            try {
                val ch = channel ?: return@launch
                @Suppress("UNCHECKED_CAST")
                val entries = ch.ls(path) as Vector<ChannelSftp.LsEntry>
                val items = JSONArray()
                for (entry in entries) {
                    val name = entry.filename
                    if (name == ".") continue
                    val attrs = entry.attrs
                    items.put(JSONObject().apply {
                        put("name", name)
                        put("isDir", attrs.isDir)
                        put("size", attrs.size)
                        put("perms", attrs.permissionsString)
                        put("mtime", attrs.mTime.toLong() * 1000)
                    })
                }
                postJs("renderDir($items)")
            } catch (e: Exception) {
                postJs("showStatus('Error: ${e.message?.replace("'", "\\'")}')")
            }
        }
    }

    private fun postJs(js: String) {
        webView.post { webView.evaluateJavascript(js, null) }
    }

    @Suppress("unused")
    inner class SftpBridge {
        @JavascriptInterface
        fun navigate(path: String) { listDir(path) }

        @JavascriptInterface
        fun downloadFile(remotePath: String, fileName: String) {
            scope.launch {
                try {
                    postJs("showStatus('Downloading $fileName...')")
                    val ch = channel ?: return@launch
                    val dir = File(context.cacheDir, "sftp_downloads")
                    dir.mkdirs()
                    val local = File(dir, fileName)
                    ch.get(remotePath, local.absolutePath)
                    postJs("showStatus('Downloaded: $fileName')")

                    // Share via intent
                    withContext(Dispatchers.Main) {
                        val uri = androidx.core.content.FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", local
                        )
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, "*/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Open $fileName"))
                    }
                } catch (e: Exception) {
                    postJs("showStatus('Download failed: ${e.message?.replace("'", "\\'")}')")
                }
            }
        }

        @JavascriptInterface
        fun uploadFile(remotePath: String) {
            // Trigger file picker on main thread, result handled via callback
            scope.launch(Dispatchers.Main) {
                (context as? MainActivity)?.launchSftpUpload(remotePath)
            }
        }

        @JavascriptInterface
        fun deleteFile(remotePath: String) {
            scope.launch {
                try {
                    channel?.rm(remotePath)
                    postJs("showStatus('Deleted'); refreshDir()")
                } catch (e: Exception) {
                    postJs("showStatus('Delete failed: ${e.message?.replace("'", "\\'")}')")
                }
            }
        }

        @JavascriptInterface
        fun mkdir(path: String) {
            scope.launch {
                try {
                    channel?.mkdir(path)
                    postJs("showStatus('Created'); refreshDir()")
                } catch (e: Exception) {
                    postJs("showStatus('Mkdir failed: ${e.message?.replace("'", "\\'")}')")
                }
            }
        }

        @JavascriptInterface
        fun closeSftp() {
            webView.post { (context as? MainActivity)?.closeSftp() }
        }

        @JavascriptInterface
        fun confirmDelete(remotePath: String, name: String) {
            webView.post {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Delete")
                    .setMessage("Delete $name?")
                    .setPositiveButton("Delete") { _, _ ->
                        scope.launch {
                            try {
                                channel?.rm(remotePath)
                                postJs("showStatus('Deleted'); refreshDir()")
                            } catch (e: Exception) {
                                postJs("showStatus('Delete failed: ${e.message?.replace("'", "\\'")}')")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }

        @JavascriptInterface
        fun promptMkdir(currentPath: String) {
            webView.post {
                val input = android.widget.EditText(context).apply {
                    hint = "Directory name"
                    setPadding(48, 32, 48, 16)
                }
                android.app.AlertDialog.Builder(context)
                    .setTitle("Create Directory")
                    .setView(input)
                    .setPositiveButton("Create") { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotBlank()) {
                            val full = currentPath + (if (currentPath.endsWith("/")) "" else "/") + name
                            scope.launch {
                                try {
                                    channel?.mkdir(full)
                                    postJs("showStatus('Created'); refreshDir()")
                                } catch (e: Exception) {
                                    postJs("showStatus('Mkdir failed: ${e.message?.replace("'", "\\'")}')")
                                }
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    fun uploadFileFromUri(remotePath: String, uri: Uri, fileName: String) {
        scope.launch {
            try {
                postJs("showStatus('Uploading $fileName...')")
                val ch = channel ?: return@launch
                val input = context.contentResolver.openInputStream(uri) ?: return@launch
                ch.put(input, "$remotePath/$fileName")
                input.close()
                postJs("showStatus('Uploaded: $fileName'); refreshDir()")
            } catch (e: Exception) {
                postJs("showStatus('Upload failed: ${e.message?.replace("'", "\\'")}')")
            }
        }
    }
}
