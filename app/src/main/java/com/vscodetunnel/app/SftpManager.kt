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

    // Recursive folder download
    private fun downloadRecursive(ch: ChannelSftp, remotePath: String, localDir: File) {
        localDir.mkdirs()
        @Suppress("UNCHECKED_CAST")
        val entries = ch.ls(remotePath) as java.util.Vector<ChannelSftp.LsEntry>
        for (entry in entries) {
            val name = entry.filename
            if (name == "." || name == "..") continue
            val rPath = "$remotePath/$name"
            val lPath = File(localDir, name)
            if (entry.attrs.isDir) {
                downloadRecursive(ch, rPath, lPath)
            } else {
                ch.get(rPath, lPath.absolutePath)
            }
        }
    }

    // Recursive folder upload
    fun uploadRecursive(ch: ChannelSftp, localDir: File, remotePath: String) {
        try { ch.mkdir(remotePath) } catch (_: Exception) {}
        localDir.listFiles()?.forEach { f ->
            val rPath = "$remotePath/${f.name}"
            if (f.isDirectory) {
                uploadRecursive(ch, f, rPath)
            } else {
                ch.put(f.absolutePath, rPath)
            }
        }
    }

    // Recursive delete
    private fun deleteRecursive(ch: ChannelSftp, remotePath: String) {
        try {
            // Try as file first
            ch.rm(remotePath)
        } catch (_: Exception) {
            // If fails, it's a directory — recurse
            try {
                @Suppress("UNCHECKED_CAST")
                val entries = ch.ls(remotePath) as java.util.Vector<ChannelSftp.LsEntry>
                for (entry in entries) {
                    val name = entry.filename
                    if (name == "." || name == "..") continue
                    deleteRecursive(ch, "$remotePath/$name")
                }
                ch.rmdir(remotePath)
            } catch (e: Exception) {
                FileLogger.w(TAG, "Delete failed: $remotePath: $e")
            }
        }
    }

    @Suppress("unused")
    inner class SftpBridge {
        @JavascriptInterface
        fun navigate(path: String) { listDir(path) }

        @JavascriptInterface
        fun uploadFiles(remotePath: String) {
            scope.launch(Dispatchers.Main) {
                (context as? MainActivity)?.launchSftpUpload(remotePath)
            }
        }

        @JavascriptInterface
        fun downloadMultiple(itemsJson: String) {
            scope.launch {
                try {
                    val ch = channel ?: return@launch
                    val items = org.json.JSONArray(itemsJson)
                    val dir = File(context.cacheDir, "sftp_downloads")
                    dir.mkdirs()
                    val downloaded = mutableListOf<File>()

                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val name = item.getString("name")
                        val path = item.getString("path")
                        val isDir = item.getBoolean("isDir")
                        postJs("showStatus('Downloading ${i+1}/${items.length()}: $name...'); setProgress(${(i*100)/items.length()})")

                        val local = File(dir, name)
                        if (isDir) {
                            downloadRecursive(ch, path, local)
                        } else {
                            ch.get(path, local.absolutePath)
                        }
                        downloaded.add(local)
                    }

                    postJs("showStatus('Downloaded ${downloaded.size} items'); setProgress(100)")

                    // Share all files
                    withContext(Dispatchers.Main) {
                        val uris = ArrayList<android.net.Uri>()
                        for (f in downloaded) {
                            if (f.isFile) {
                                uris.add(androidx.core.content.FileProvider.getUriForFile(
                                    context, "${context.packageName}.fileprovider", f))
                            }
                        }
                        if (uris.size == 1) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                setDataAndType(uris[0], "*/*")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Open"))
                        } else if (uris.size > 1) {
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).apply {
                                type = "*/*"
                                putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris)
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share ${uris.size} files"))
                        }
                    }
                    postJs("setProgress(0); selected.clear(); updateSelBar()")
                } catch (e: Exception) {
                    postJs("showStatus('Download failed: ${e.message?.replace("'", "\\'")}')")
                    postJs("setProgress(0)")
                }
            }
        }

        @JavascriptInterface
        fun confirmDeleteMultiple(pathsJson: String, count: Int) {
            webView.post {
                android.app.AlertDialog.Builder(context)
                    .setTitle("Delete $count items")
                    .setMessage("Delete $count selected items? This cannot be undone.")
                    .setPositiveButton("Delete") { _, _ ->
                        scope.launch {
                            try {
                                val paths = org.json.JSONArray(pathsJson)
                                val ch = channel ?: return@launch
                                for (i in 0 until paths.length()) {
                                    val path = paths.getString(i)
                                    postJs("showStatus('Deleting ${i+1}/${paths.length()}...'); setProgress(${(i*100)/paths.length()})")
                                    deleteRecursive(ch, path)
                                }
                                postJs("showStatus('Deleted $count items'); setProgress(0); refreshDir()")
                            } catch (e: Exception) {
                                postJs("showStatus('Delete failed: ${e.message?.replace("'", "\\'")}'); setProgress(0)")
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
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
