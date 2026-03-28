package com.vscodetunnel.app

import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.ScrollView
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.webkit.WebView
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import com.vscodetunnel.app.AppSettings.keepAliveEnabled
import com.vscodetunnel.app.AppSettings.hapticFeedback
import com.vscodetunnel.app.AppSettings.terminalFontSize
import com.vscodetunnel.app.AppSettings.terminalColorScheme
import com.vscodetunnel.app.AppSettings.terminalScrollback
import com.vscodetunnel.app.AppSettings.keyRepeatDelay
import com.vscodetunnel.app.AppSettings.keyRepeatRate
import com.vscodetunnel.app.AppSettings.defaultSshPort
import com.vscodetunnel.app.AppSettings.defaultSshUser
import com.vscodetunnel.app.AppSettings.defaultStartupCmd
import com.vscodetunnel.app.AppSettings.sshAutoReconnect
import com.vscodetunnel.app.AppSettings.sshReconnectAttempts
import com.vscodetunnel.app.AppSettings.sshConnectTimeout
import com.vscodetunnel.app.AppSettings.suppressSystemKeyboard

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "VSCodeTunnel"
        private const val PREFS_AUTH = "auth"
        private const val PREFS_RECENT = "recent"
        private const val KEY_TOKEN = "token"
        private const val KEY_RECENT_URLS = "urls"
        private const val MAX_RECENT = 5
        private const val STATE_TUNNEL_URL = "tunnel_url"
        private const val STATE_GECKOVIEW_VISIBLE = "geckoview_visible"
        private const val PREFS_SESSION = "session"
        private const val KEY_LAST_URL = "last_url"
        private const val KEY_AUTO_RECONNECT = "auto_reconnect"
        private val APP_VERSION: String get() = BuildConfig.VERSION_NAME
    }

    class TunnelSessionInfo(
        var url: String,
        val session: GeckoSession,
        var label: String
    )

    private lateinit var geckoView: SuppressableGeckoView
    private lateinit var sessionWrapper: View
    private lateinit var geckoContainer: View
    private lateinit var launcherScroll: View
    private lateinit var overlayManager: OverlayManager
    private val tunnelSessions = mutableListOf<TunnelSessionInfo>()
    private var currentSessionIdx = -1
    private var pollJob: Job? = null
    private var authDialog: Dialog? = null
    private var currentTunnelUrl: String? = null
    private var sysKBSuppressed = false

    // SSH
    private lateinit var sshContainer: View
    private lateinit var sshTerminalWebView: WebView
    private lateinit var sshServerList: LinearLayout
    private lateinit var sshEmptyText: TextView
    private var sshSessionManager: SshSessionManager? = null
    private var currentSshServer: SshServer? = null
    private var pendingKeyField: EditText? = null
    private val keyFilePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader()?.readText() ?: ""
                pendingKeyField?.setText(text)
            } catch (e: Exception) {
                showError("Failed to read key file: ${e.message}")
            }
        }
        pendingKeyField = null
    }

    // UI references
    private lateinit var btnGitHubLogin: Button
    private lateinit var loggedInHeader: View
    private lateinit var usernameText: TextView
    private lateinit var deviceCodeSection: View
    private lateinit var deviceCodeText: TextView
    private lateinit var tunnelSection: View
    private lateinit var tunnelList: LinearLayout
    private lateinit var tunnelSpinner: ProgressBar
    private lateinit var tunnelEmptyText: TextView
    private lateinit var errorText: TextView
    private lateinit var urlInput: EditText
    private lateinit var recentSection: View
    private lateinit var recentList: LinearLayout
    private lateinit var updateBanner: View
    private lateinit var updateText: TextView
    private lateinit var updateLink: TextView
    private lateinit var activeSessionsSection: View
    private lateinit var activeSessionList: LinearLayout

    private val authPrefs: SharedPreferences by lazy { getSharedPreferences(PREFS_AUTH, MODE_PRIVATE) }
    private val recentPrefs: SharedPreferences by lazy { getSharedPreferences(PREFS_RECENT, MODE_PRIVATE) }
    private val sessionPrefs: SharedPreferences by lazy { getSharedPreferences(PREFS_SESSION, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootFrame)) { view, windowInsets ->
            if (sysKBSuppressed) {
                // Reactively hide IME whenever it tries to show
                val imeVisible = windowInsets.isVisible(WindowInsetsCompat.Type.ime())
                if (imeVisible) {
                    val controller = WindowInsetsControllerCompat(window, view)
                    controller.hide(WindowInsetsCompat.Type.ime())
                }
                val sysBarInsets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(sysBarInsets.left, sysBarInsets.top, sysBarInsets.right, sysBarInsets.bottom)
            } else {
                val insets = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
                )
                view.setPadding(insets.left, insets.top, insets.right, insets.bottom)
            }
            WindowInsetsCompat.CONSUMED
        }

        bindViews()
        setupListeners()
        setupBackNavigation()

        // Initialize GeckoView engine
        val runtime = GeckoManager.getRuntime(this)
        GeckoManager.installOverlayExtension(runtime)

        geckoView = findViewById(R.id.geckoView)
        sessionWrapper = findViewById(R.id.sessionWrapper)
        geckoContainer = findViewById(R.id.geckoContainer)

        // SSH views
        sshContainer = findViewById(R.id.sshContainer)
        sshTerminalWebView = findViewById(R.id.sshTerminalWebView)

        // Setup overlay manager
        val overlayWebView = findViewById<WebView>(R.id.overlayWebView)
        val cursorDot = findViewById<View>(R.id.cursorDot)
        val floatingToggle = findViewById<Button>(R.id.floatingToggle)
        overlayManager = OverlayManager(geckoView, overlayWebView, cursorDot, floatingToggle,
            onVisibilityChanged = { visible -> onOverlayVisibilityChanged(visible) },
            onBackToMenu = { suspendSession() }
        )
        overlayManager.setup()
        overlayManager.alwaysSuppressInput = suppressSystemKeyboard
        floatingToggle.setOnClickListener { overlayManager.show() }

        // Apply suppress setting immediately so it's ready before any session
        if (suppressSystemKeyboard) {
            geckoView.suppressIME = true
            sysKBSuppressed = true
        }

        // Restore state
        val token = authPrefs.getString(KEY_TOKEN, null)
        if (token != null) {
            showLoggedIn()
        } else {
            showLoggedOut()
        }

        renderRecent()
        renderSshServers()
        loadLastUrlIntoInput()
        checkForUpdate()
        checkAutoReconnect()
    }

    private fun bindViews() {
        launcherScroll = findViewById(R.id.launcherScroll)
        btnGitHubLogin = findViewById(R.id.btnGitHubLogin)
        loggedInHeader = findViewById(R.id.loggedInHeader)
        usernameText = findViewById(R.id.usernameText)
        deviceCodeSection = findViewById(R.id.deviceCodeSection)
        deviceCodeText = findViewById(R.id.deviceCodeText)
        tunnelSection = findViewById(R.id.tunnelSection)
        tunnelList = findViewById(R.id.tunnelList)
        tunnelSpinner = findViewById(R.id.tunnelSpinner)
        tunnelEmptyText = findViewById(R.id.tunnelEmptyText)
        errorText = findViewById(R.id.errorText)
        urlInput = findViewById(R.id.urlInput)
        recentSection = findViewById(R.id.recentSection)
        recentList = findViewById(R.id.recentList)
        updateBanner = findViewById(R.id.updateBanner)
        updateText = findViewById(R.id.updateText)
        updateLink = findViewById(R.id.updateLink)
        activeSessionsSection = findViewById(R.id.activeSessionsSection)
        activeSessionList = findViewById(R.id.activeSessionList)
        sshServerList = findViewById(R.id.sshServerList)
        sshEmptyText = findViewById(R.id.sshEmptyText)
    }

    private fun setupListeners() {
        btnGitHubLogin.setOnClickListener { startGitHubLogin() }
        findViewById<View>(R.id.btnLogout).setOnClickListener { logout() }
        findViewById<View>(R.id.btnRefreshTunnels).setOnClickListener { loadTunnels() }
        findViewById<View>(R.id.btnAddSsh).setOnClickListener { showSshServerDialog(null) }
        findViewById<View>(R.id.btnQuickSsh).setOnClickListener { showQuickConnectDialog() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }

        findViewById<Button>(R.id.btnConnect).setOnClickListener {
            connectTo(urlInput.text.toString().trim())
        }

        urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                connectTo(urlInput.text.toString().trim())
                true
            } else false
        }

        // Debug log buttons
        val logScroll = findViewById<View>(R.id.logScroll)
        val logText = findViewById<TextView>(R.id.logText)

        findViewById<View>(R.id.btnShowLog).setOnClickListener {
            if (logScroll.visibility == View.VISIBLE) {
                logScroll.visibility = View.GONE
            } else {
                logText.text = FileLogger.readLog(this)
                logScroll.visibility = View.VISIBLE
                (logScroll as ScrollView).post {
                    logScroll.fullScroll(View.FOCUS_DOWN)
                }
            }
        }

        findViewById<View>(R.id.btnClearLog).setOnClickListener {
            FileLogger.clearLog(this)
            logText.text = "(cleared)"
        }
    }

    // --- Auth Flow ---

    private fun startGitHubLogin() {
        btnGitHubLogin.visibility = View.GONE
        deviceCodeSection.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val data = GitHubAuth.requestDeviceCode()

                if (data.has("error")) {
                    showError("GitHub error: ${data.optString("error_description", data.optString("error"))}")
                    deviceCodeSection.visibility = View.GONE
                    btnGitHubLogin.visibility = View.VISIBLE
                    return@launch
                }

                val userCode = data.getString("user_code")
                deviceCodeText.text = userCode

                val openUri = data.optString("verification_uri_complete",
                    data.optString("verification_uri", "https://github.com/login/device"))

                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(openUri)))
                } catch (_: Exception) {}

                val interval = (data.optInt("interval", 5)) * 1000L
                pollForToken(data.getString("device_code"), interval)
            } catch (e: Exception) {
                showError("Login failed: $e")
                deviceCodeSection.visibility = View.GONE
                btnGitHubLogin.visibility = View.VISIBLE
            }
        }
    }

    private fun pollForToken(deviceCode: String, interval: Long) {
        pollJob?.cancel()
        pollJob = lifecycleScope.launch {
            while (true) {
                delay(interval)
                try {
                    val data = GitHubAuth.pollToken(deviceCode)

                    if (data.has("access_token")) {
                        val token = data.getString("access_token")
                        authPrefs.edit().putString(KEY_TOKEN, token).apply()
                        deviceCodeSection.visibility = View.GONE
                        showLoggedIn()
                        return@launch
                    }

                    if (data.optString("error") == "expired_token") {
                        showError("Code expired. Please try again.")
                        deviceCodeSection.visibility = View.GONE
                        btnGitHubLogin.visibility = View.VISIBLE
                        return@launch
                    }
                } catch (e: Exception) {
                    showError("Auth polling failed: $e")
                    deviceCodeSection.visibility = View.GONE
                    btnGitHubLogin.visibility = View.VISIBLE
                    return@launch
                }
            }
        }
    }

    private fun showLoggedIn() {
        btnGitHubLogin.visibility = View.GONE
        deviceCodeSection.visibility = View.GONE
        loggedInHeader.visibility = View.VISIBLE
        tunnelSection.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val token = authPrefs.getString(KEY_TOKEN, null) ?: return@launch
                val user = GitHubAuth.getUser(token)
                val login = user.optString("login", "")
                if (login.isNotBlank()) {
                    usernameText.text = "Signed in as $login"
                }
            } catch (_: Exception) {}
        }

        loadTunnels()
    }

    private fun showLoggedOut() {
        btnGitHubLogin.visibility = View.VISIBLE
        loggedInHeader.visibility = View.GONE
        deviceCodeSection.visibility = View.GONE
        tunnelSection.visibility = View.GONE
    }

    private fun logout() {
        authPrefs.edit().remove(KEY_TOKEN).apply()
        pollJob?.cancel()
        pollJob = null
        showLoggedOut()
    }

    // --- Tunnel List ---

    private fun loadTunnels() {
        val token = authPrefs.getString(KEY_TOKEN, null) ?: return

        tunnelList.removeAllViews()
        tunnelEmptyText.visibility = View.GONE
        tunnelSpinner.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val tunnels = TunnelApi.listTunnels(token)
                tunnelSpinner.visibility = View.GONE

                if (tunnels.isEmpty()) {
                    tunnelEmptyText.visibility = View.VISIBLE
                    return@launch
                }

                for (tunnel in tunnels) {
                    addTunnelItem(tunnel)
                }
            } catch (e: TunnelApi.AuthExpiredException) {
                tunnelSpinner.visibility = View.GONE
                authPrefs.edit().remove(KEY_TOKEN).apply()
                showLoggedOut()
                showError("Session expired. Please login again.")
            } catch (e: Exception) {
                tunnelSpinner.visibility = View.GONE
                showError("Failed to load tunnels: $e")
            }
        }
    }

    private fun addTunnelItem(tunnel: TunnelApi.Tunnel) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tunnel_item)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        val dot = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(12)
            }
            setBackgroundResource(if (tunnel.isOnline) R.drawable.status_online else R.drawable.status_offline)
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = tunnel.name
            setTextColor(resources.getColor(R.color.text_white, theme))
            textSize = 15f
        }

        val descView = TextView(this).apply {
            text = tunnel.description
            setTextColor(resources.getColor(
                if (tunnel.isOnline) R.color.status_online else R.color.text_secondary, theme
            ))
            textSize = 12f
        }

        info.addView(nameView)
        info.addView(descView)
        item.addView(dot)
        item.addView(info)

        item.setOnClickListener {
            val url = TunnelApi.buildTunnelUrl(tunnel.name)
            connectTo(url)
        }

        tunnelList.addView(item)
    }

    // --- SSH Servers ---

    private fun renderSshServers() {
        val servers = ServerStorage.getServers(this)
        sshServerList.removeAllViews()

        if (servers.isEmpty()) {
            sshEmptyText.visibility = View.VISIBLE
            return
        }

        sshEmptyText.visibility = View.GONE
        for (server in servers) {
            addSshServerItem(server)
        }
    }

    private fun addSshServerItem(server: SshServer) {
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_tunnel_item)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val nameView = TextView(this).apply {
            text = server.name.ifBlank { "${server.username}@${server.host}" }
            setTextColor(resources.getColor(R.color.text_white, theme))
            textSize = 15f
        }

        val descView = TextView(this).apply {
            text = "${server.username}@${server.host}:${server.port}"
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
        }

        info.addView(nameView)
        info.addView(descView)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val editBtn = TextView(this).apply {
            text = "Edit"
            setTextColor(resources.getColor(R.color.primary, theme))
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { showSshServerDialog(server) }
        }

        val deleteBtn = TextView(this).apply {
            text = "Del"
            setTextColor(resources.getColor(R.color.error, theme))
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener {
                ServerStorage.deleteServer(this@MainActivity, server.id)
                renderSshServers()
            }
        }

        actions.addView(editBtn)
        actions.addView(deleteBtn)

        item.addView(info)
        item.addView(actions)

        item.setOnClickListener { connectSsh(server) }

        sshServerList.addView(item)
    }

    private fun showSshServerDialog(existing: SshServer?) {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        scroll.addView(layout)

        fun addField(hint: String, value: String = "", inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                this.hint = hint
                setText(value)
                this.inputType = inputType
                setTextColor(resources.getColor(R.color.text_primary, theme))
                setHintTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 14f
                background = resources.getDrawable(R.drawable.bg_input, theme)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
        }

        fun addLabel(text: String) {
            layout.addView(TextView(this).apply {
                this.text = text
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8); bottomMargin = dp(2) }
            })
        }

        val nameField = addField("Display name (optional)", existing?.name ?: "")
        val hostField = addField("Host", existing?.host ?: "")
        val portField = addField("Port", (existing?.port ?: defaultSshPort).toString(),
            android.text.InputType.TYPE_CLASS_NUMBER)
        val userField = addField("Username", existing?.username ?: defaultSshUser)
        val passField = addField("Password", existing?.password ?: "",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val keyField = addField("Private key (paste PEM or use file picker)", existing?.privateKey ?: "",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val startupField = addField("Startup command (e.g. cd /app && tmux attach)", existing?.startupCommand ?: "")
        val portFwdField = addField("Port forwards (e.g. L8080:127.0.0.1:80,R3000:localhost:3000)",
            existing?.portForwards?.joinToString(",") ?: "")
        val snippetsField = addField("Snippets (comma-separated commands)",
            existing?.snippets?.joinToString(",") ?: "")

        layout.addView(nameField)
        layout.addView(hostField)
        layout.addView(portField)
        layout.addView(userField)
        layout.addView(passField)
        addLabel("Authentication key (optional)")
        layout.addView(keyField)

        // File picker button for SSH key
        val pickKeyBtn = Button(this).apply {
            text = "Pick key file..."
            setTextColor(resources.getColor(R.color.primary, theme))
            textSize = 13f
            isAllCaps = false
            background = resources.getDrawable(R.drawable.bg_input, theme)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        pickKeyBtn.setOnClickListener {
            pendingKeyField = keyField
            keyFilePickerLauncher.launch(arrayOf("*/*"))
        }
        layout.addView(pickKeyBtn)

        addLabel("Automation")
        layout.addView(startupField)
        addLabel("Port forwarding (L=local, R=remote)")
        layout.addView(portFwdField)
        addLabel("Quick snippets for toolbar")
        layout.addView(snippetsField)

        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(if (existing != null) "Edit Server" else "Add SSH Server")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                val host = hostField.text.toString().trim()
                val user = userField.text.toString().trim()
                if (host.isBlank() || user.isBlank()) {
                    showError("Host and username are required")
                    return@setPositiveButton
                }
                val key = keyField.text.toString().trim()
                val server = SshServer(
                    id = existing?.id ?: System.currentTimeMillis().toString(),
                    name = nameField.text.toString().trim(),
                    host = host,
                    port = portField.text.toString().toIntOrNull() ?: 22,
                    username = user,
                    authMethod = if (key.isNotBlank()) SshServer.AuthMethod.KEY else SshServer.AuthMethod.PASSWORD,
                    password = passField.text.toString(),
                    privateKey = key,
                    startupCommand = startupField.text.toString().trim(),
                    portForwards = parsePortForwards(portFwdField.text.toString()),
                    snippets = snippetsField.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() }
                )
                ServerStorage.saveServer(this, server)
                renderSshServers()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun parsePortForwards(input: String): List<PortForward> {
        if (input.isBlank()) return emptyList()
        return input.split(",").mapNotNull { s ->
            val t = s.trim()
            if (t.length < 2) return@mapNotNull null
            val type = if (t[0] == 'R' || t[0] == 'r') "remote" else "local"
            val parts = t.substring(1).split(":", limit = 3)
            if (parts.size < 3) return@mapNotNull null
            PortForward(type, parts[0].toIntOrNull() ?: 0, parts[1], parts[2].toIntOrNull() ?: 0)
        }
    }

    private fun showQuickConnectDialog() {
        val input = EditText(this).apply {
            hint = "user@host:port"
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setHintTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 16f
            background = resources.getDrawable(R.drawable.bg_input, theme)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(0))
            addView(input)
        }
        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Quick Connect")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val server = SshServer.fromQuickConnect(input.text.toString())
                if (server != null) {
                    // Ask for password
                    showQuickConnectPasswordDialog(server)
                } else {
                    showError("Invalid format. Use: user@host or user@host:port")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showQuickConnectPasswordDialog(server: SshServer) {
        val input = EditText(this).apply {
            hint = "Password (leave empty for key auth)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setHintTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 16f
            background = resources.getDrawable(R.drawable.bg_input, theme)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val container = LinearLayout(this).apply {
            setPadding(dp(24), dp(16), dp(24), dp(0))
            addView(input)
        }
        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Password for ${server.username}@${server.host}")
            .setView(container)
            .setPositiveButton("Connect") { _, _ ->
                val withPass = server.copy(password = input.text.toString())
                connectSsh(withPass)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHostKeyDialog(host: String, fingerprint: String, callback: (Boolean) -> Unit) {
        runOnUiThread {
            AlertDialog.Builder(this, R.style.AppDialogTheme)
                .setTitle("Host Key Changed!")
                .setMessage("The host key for $host has changed.\n\nNew fingerprint:\n$fingerprint\n\nThis could indicate a man-in-the-middle attack. Accept new key?")
                .setPositiveButton("Accept") { _, _ -> callback(true) }
                .setNegativeButton("Reject") { _, _ -> callback(false) }
                .setCancelable(false)
                .show()
        }
    }

    private fun connectSsh(server: SshServer) {
        currentSshServer = server

        val mgr = SshSessionManager(this, sshTerminalWebView,
            onDisconnected = { reason ->
                runOnUiThread {
                    FileLogger.d(TAG, "SSH disconnected: $reason")
                    if (keepAliveEnabled) KeepAliveService.stop(this)
                }
            },
            onHostKeyVerify = { host, fp, cb -> showHostKeyDialog(host, fp, cb) }
        )
        sshSessionManager?.destroy()
        sshSessionManager = mgr

        overlayManager.inputTarget = OverlayManager.InputTarget.SSH_TERMINAL
        overlayManager.sshSessionManager = mgr

        mgr.setupTerminal()
        mgr.connect(server)

        // Send snippets to terminal toolbar after a delay (terminal needs to load first)
        if (server.snippets.isNotEmpty()) {
            sshTerminalWebView.postDelayed({
                val json = org.json.JSONArray(server.snippets).toString()
                sshTerminalWebView.evaluateJavascript("setSnippets($json)", null)
            }, 500)
        }

        // Switch to SSH view
        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        geckoContainer.visibility = View.GONE
        sshContainer.visibility = View.VISIBLE
        findViewById<Button>(R.id.floatingToggle).visibility = View.VISIBLE

        // Start keepalive
        if (keepAliveEnabled) {
            KeepAliveService.start(this, "SSH: ${server.username}@${server.host}")
        }
    }

    private fun disconnectSsh() {
        sshSessionManager?.destroy()
        sshSessionManager = null
        currentSshServer = null
        overlayManager.inputTarget = OverlayManager.InputTarget.VSCODE
        overlayManager.sshSessionManager = null
        if (keepAliveEnabled) {
            KeepAliveService.stop(this)
        }
    }

    // --- Settings ---

    private fun showSettingsDialog() {
        val scroll = ScrollView(this)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(8))
        }
        scroll.addView(layout)

        val colorWhite = resources.getColor(R.color.text_white, theme)
        val colorSec = resources.getColor(R.color.text_secondary, theme)
        val colorPrim = resources.getColor(R.color.text_primary, theme)

        fun section(title: String) {
            layout.addView(TextView(this).apply {
                text = title
                setTextColor(resources.getColor(R.color.primary, theme))
                textSize = 13f; textStyle = android.graphics.Typeface.BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(16); bottomMargin = dp(6) }
            })
            layout.addView(View(this).apply {
                setBackgroundColor(resources.getColor(R.color.divider, theme))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(8) }
            })
        }

        fun check(label: String, checked: Boolean): CheckBox {
            return CheckBox(this).apply {
                text = label; isChecked = checked
                setTextColor(colorPrim); textSize = 14f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
        }

        fun field(hint: String, value: String, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                this.hint = hint; setText(value); this.inputType = inputType
                setTextColor(colorPrim); setHintTextColor(colorSec); textSize = 14f
                background = resources.getDrawable(R.drawable.bg_input, theme)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(6) }
            }
        }

        fun label(text: String) {
            layout.addView(TextView(this).apply {
                this.text = text; setTextColor(colorSec); textSize = 11f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(2) }
            })
        }

        // === APPEARANCE ===
        section("Appearance")
        label("Terminal color scheme")
        val schemes = arrayOf("default", "solarized-dark", "dracula", "monokai", "linux")
        val schemeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, schemes)
            setSelection(schemes.indexOf(terminalColorScheme).coerceAtLeast(0))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) }
        }
        layout.addView(schemeSpinner)
        label("Font size")
        val fontField = field("14", terminalFontSize.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(fontField)
        label("Scrollback lines")
        val scrollbackField = field("10000", terminalScrollback.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(scrollbackField)

        // === KEYBOARD ===
        section("Keyboard")
        val suppressCheck = check("Suppress system keyboard in sessions", suppressSystemKeyboard)
        layout.addView(suppressCheck)
        val hapticCheck = check("Haptic feedback", hapticFeedback)
        layout.addView(hapticCheck)
        label("Key repeat delay (ms)")
        val repeatDelayField = field("400", keyRepeatDelay.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(repeatDelayField)
        label("Key repeat rate (ms)")
        val repeatRateField = field("50", keyRepeatRate.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(repeatRateField)

        // === SSH DEFAULTS ===
        section("SSH Defaults")
        label("Default port")
        val portField = field("22", defaultSshPort.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(portField)
        label("Default username")
        val userField = field("", defaultSshUser)
        layout.addView(userField)
        label("Default startup command")
        val startupField = field("e.g. cd /app && tmux attach", defaultStartupCmd)
        layout.addView(startupField)
        val autoReconnectCheck = check("Auto-reconnect on disconnect", sshAutoReconnect)
        layout.addView(autoReconnectCheck)
        label("Reconnect attempts")
        val attemptsField = field("3", sshReconnectAttempts.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(attemptsField)
        label("Connection timeout (seconds)")
        val timeoutField = field("15", sshConnectTimeout.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(timeoutField)

        // === BACKGROUND ===
        section("Background")
        val keepAliveCheck = check("Keep alive in background (foreground service)", keepAliveEnabled)
        layout.addView(keepAliveCheck)

        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Settings")
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                // Appearance
                terminalColorScheme = schemes[schemeSpinner.selectedItemPosition]
                terminalFontSize = fontField.text.toString().toIntOrNull() ?: 14
                terminalScrollback = scrollbackField.text.toString().toIntOrNull() ?: 10000
                // Keyboard
                suppressSystemKeyboard = suppressCheck.isChecked
                overlayManager.alwaysSuppressInput = suppressSystemKeyboard
                overlayManager.syncInputSuppression() // tell content script to add/remove inputmode="none"
                if (sessionWrapper.visibility == View.VISIBLE && suppressSystemKeyboard) {
                    geckoView.suppressIME = true; sysKBSuppressed = true
                    WindowInsetsControllerCompat(window, geckoView).hide(WindowInsetsCompat.Type.ime())
                } else if (!suppressSystemKeyboard && !overlayManager.isVisible) {
                    geckoView.suppressIME = false; sysKBSuppressed = false
                }
                hapticFeedback = hapticCheck.isChecked
                keyRepeatDelay = repeatDelayField.text.toString().toIntOrNull() ?: 400
                keyRepeatRate = repeatRateField.text.toString().toIntOrNull() ?: 50
                // SSH
                defaultSshPort = portField.text.toString().toIntOrNull() ?: 22
                defaultSshUser = userField.text.toString().trim()
                defaultStartupCmd = startupField.text.toString().trim()
                sshAutoReconnect = autoReconnectCheck.isChecked
                sshReconnectAttempts = attemptsField.text.toString().toIntOrNull() ?: 3
                sshConnectTimeout = timeoutField.text.toString().toIntOrNull() ?: 15
                // Background
                keepAliveEnabled = keepAliveCheck.isChecked
                // Push repeat settings to overlay keyboard
                updateOverlaySettings()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun updateOverlaySettings() {
        // Push key repeat settings to overlay WebView
        val overlayWebView = findViewById<WebView>(R.id.overlayWebView)
        overlayWebView.evaluateJavascript(
            "if(typeof updateRepeatSettings==='function')updateRepeatSettings($keyRepeatDelay,$keyRepeatRate)", null)
    }

    // --- Navigation ---

    private fun connectTo(url: String) {
        if (url.isBlank()) {
            showError("Please enter a URL")
            return
        }

        try {
            Uri.parse(url).also {
                if (it.scheme == null || it.host == null) throw Exception()
            }
        } catch (_: Exception) {
            showError("Invalid URL format")
            return
        }

        saveRecentUrl(url)
        currentTunnelUrl = url
        openTunnel(url)
    }

    private fun openTunnel(url: String) {
        if (!GeckoManager.extensionReady) {
            FileLogger.d(TAG, "Extension not ready, retrying in 500ms...")
            lifecycleScope.launch {
                delay(500)
                runOnUiThread { openTunnel(url) }
            }
            return
        }

        FileLogger.d(TAG, "Opening tunnel: $url")

        // Suspend current session if any (don't close — keep in background)
        if (currentSessionIdx >= 0 && currentSessionIdx < tunnelSessions.size) {
            tunnelSessions[currentSessionIdx].session.setActive(false)
            geckoView.releaseSession()
        }

        val session = GeckoManager.createTunnelSession()
        val runtime = GeckoManager.getRuntime(this)
        session.open(runtime)

        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                FileLogger.d(TAG, "New session requested: $uri")
                return openAuthPopup(uri)
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                FileLogger.d(TAG, "Load request: ${request.uri} trigger=${request.triggerUri}")
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }

            override fun onLocationChange(
                session: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean
            ) {
                if (url == null) return
                runOnUiThread { updateSessionUrl(session, url) }
            }
        }

        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                runOnUiThread { showLauncher() }
            }

            override fun onCrash(session: GeckoSession) {
                FileLogger.e(TAG, "GeckoView session crashed, returning to launcher")
                runOnUiThread { showLauncher() }
            }
        }

        session.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                FileLogger.d(TAG, "Alert: ${prompt.message}")
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onTextPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.TextPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                FileLogger.d(TAG, "Text prompt: ${prompt.message}")
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onBeforeUnloadPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.BeforeUnloadPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
            }

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                FileLogger.d(TAG, "Popup prompt: ${prompt.targetUri}")
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
            }
        }

        session.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                perm: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                FileLogger.d(TAG, "Permission request: ${perm.permission}")
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW)
            }
        }

        setupOverlayMessaging(session)
        overlayManager.inputTarget = OverlayManager.InputTarget.VSCODE
        overlayManager.sshSessionManager = null

        // Extract label from URL (tunnel name + folder)
        val label = url.removePrefix("https://vscode.dev/tunnel/")
            .removePrefix("https://insiders.vscode.dev/tunnel/")
            .ifBlank { url }
        val info = TunnelSessionInfo(url, session, label)
        tunnelSessions.add(info)
        currentSessionIdx = tunnelSessions.size - 1

        // Suppress BEFORE setSession so onCreateInputConnection returns null immediately
        if (suppressSystemKeyboard) {
            sysKBSuppressed = true
            geckoView.suppressIME = true
        }

        geckoView.setSession(session)
        session.loadUri(url)

        // Save for auto-reconnect after app restart
        saveOpenSessionUrls()

        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        sshContainer.visibility = View.GONE
        geckoContainer.visibility = View.VISIBLE
        findViewById<Button>(R.id.floatingToggle).visibility = View.VISIBLE

        if (suppressSystemKeyboard) {
            // Also hide IME in case it was already showing
            val controller = WindowInsetsControllerCompat(window, geckoView)
            controller.hide(WindowInsetsCompat.Type.ime())
            ViewCompat.requestApplyInsets(findViewById(R.id.rootFrame))
        }

        // Start keepalive
        if (keepAliveEnabled) {
            KeepAliveService.start(this, "VS Code: $url")
        }
    }

    private fun setupOverlayMessaging(session: GeckoSession) {
        val extension = GeckoManager.getOverlayExtension() ?: run {
            FileLogger.w(TAG, "Overlay extension not available for messaging")
            return
        }

        val messageDelegate = object : WebExtension.MessageDelegate {
            override fun onConnect(port: WebExtension.Port) {
                FileLogger.d(TAG, "Content script port connected")
                runOnUiThread { overlayManager.setPort(port) }
            }
        }

        session.webExtensionController.setMessageDelegate(extension, messageDelegate, "browser")
    }

    private fun onOverlayVisibilityChanged(visible: Boolean) {
        // Suppress system keyboard: always when setting is on, or when overlay is visible
        val suppress = visible || suppressSystemKeyboard
        sysKBSuppressed = suppress
        geckoView.suppressIME = suppress
        FileLogger.d(TAG, "Overlay visible: $visible, sysKB suppressed: $suppress")
        if (suppress) {
            val controller = WindowInsetsControllerCompat(window, geckoView)
            controller.hide(WindowInsetsCompat.Type.ime())
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.rootFrame))
    }

    private fun openAuthPopup(uri: String): GeckoResult<GeckoSession>? {
        val popupSession = GeckoManager.createTunnelSession()
        FileLogger.d(TAG, "Created popup session for: $uri")

        popupSession.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onNewSession(
                session: GeckoSession,
                uri: String
            ): GeckoResult<GeckoSession>? {
                FileLogger.d(TAG, "Nested popup, opening in browser: $uri")
                try {
                    runOnUiThread {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                    }
                } catch (e: Exception) {
                    FileLogger.e(TAG, "Failed to open nested popup URL", e)
                }
                return null
            }

            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                return GeckoResult.fromValue(AllowOrDeny.ALLOW)
            }
        }

        popupSession.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onCloseRequest(session: GeckoSession) {
                runOnUiThread { dismissAuthDialog() }
            }

            override fun onCrash(session: GeckoSession) {
                FileLogger.e(TAG, "Popup session crashed")
                runOnUiThread { dismissAuthDialog() }
            }
        }

        popupSession.promptDelegate = object : GeckoSession.PromptDelegate {
            override fun onAlertPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.AlertPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.dismiss())
            }

            override fun onPopupPrompt(
                session: GeckoSession,
                prompt: GeckoSession.PromptDelegate.PopupPrompt
            ): GeckoResult<GeckoSession.PromptDelegate.PromptResponse>? {
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
            }
        }

        runOnUiThread {
            try {
                val popupView = GeckoView(this)
                popupView.setSession(popupSession)

                val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar).apply {
                    setContentView(popupView, ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    ))
                    setCancelable(true)
                    setOnDismissListener {
                        popupView.releaseSession()
                        popupSession.close()
                        authDialog = null
                    }
                }

                authDialog?.dismiss()
                authDialog = dialog
                dialog.show()
                FileLogger.d(TAG, "Auth popup dialog shown")
            } catch (e: Exception) {
                FileLogger.e(TAG, "Failed to show auth popup dialog", e)
                popupSession.close()
            }
        }

        return GeckoResult.fromValue(popupSession)
    }

    private fun dismissAuthDialog() {
        authDialog?.dismiss()
        authDialog = null
    }

    private fun showLauncher() {
        overlayManager.hide()
        // Close ALL sessions
        geckoView.releaseSession()
        for (s in tunnelSessions) { s.session.close() }
        tunnelSessions.clear()
        currentSessionIdx = -1
        currentTunnelUrl = null
        // Always allow system keyboard on launcher (for URL input etc.)
        sysKBSuppressed = false
        geckoView.suppressIME = false
        disconnectSsh()
        sessionWrapper.visibility = View.GONE
        geckoContainer.visibility = View.GONE
        sshContainer.visibility = View.GONE
        findViewById<Button>(R.id.floatingToggle).visibility = View.GONE
        launcherScroll.visibility = View.VISIBLE
        saveOpenSessionUrls()
        renderSessionList()
    }

    // --- Session Suspend / Resume ---

    private fun suspendSession() {
        overlayManager.hide()
        // Suspend current tunnel session (keep alive in background)
        if (currentSessionIdx >= 0 && currentSessionIdx < tunnelSessions.size) {
            tunnelSessions[currentSessionIdx].session.setActive(false)
            geckoView.releaseSession()
            FileLogger.d(TAG, "Session suspended: ${tunnelSessions[currentSessionIdx].url}")
        }
        // SSH: keep alive too
        if (currentSshServer != null && sshSessionManager?.isConnected == true) {
            // Already running in background
        }

        currentSessionIdx = -1
        // Always allow system keyboard on launcher
        sysKBSuppressed = false
        geckoView.suppressIME = false
        sessionWrapper.visibility = View.GONE
        geckoContainer.visibility = View.GONE
        sshContainer.visibility = View.GONE
        findViewById<Button>(R.id.floatingToggle).visibility = View.GONE
        launcherScroll.visibility = View.VISIBLE
        saveOpenSessionUrls()
        renderSessionList()
    }

    private fun resumeTunnelSession(idx: Int) {
        if (idx < 0 || idx >= tunnelSessions.size) return
        val info = tunnelSessions[idx]

        // Suspend current if different
        if (currentSessionIdx >= 0 && currentSessionIdx < tunnelSessions.size && currentSessionIdx != idx) {
            tunnelSessions[currentSessionIdx].session.setActive(false)
            geckoView.releaseSession()
        }

        currentSessionIdx = idx
        currentTunnelUrl = info.url

        if (suppressSystemKeyboard) {
            sysKBSuppressed = true
            geckoView.suppressIME = true
        }

        info.session.setActive(true)
        geckoView.setSession(info.session)

        overlayManager.inputTarget = OverlayManager.InputTarget.VSCODE
        overlayManager.sshSessionManager = null

        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        sshContainer.visibility = View.GONE
        geckoContainer.visibility = View.VISIBLE
        findViewById<Button>(R.id.floatingToggle).visibility = View.VISIBLE

        if (suppressSystemKeyboard) {
            val controller = WindowInsetsControllerCompat(window, geckoView)
            controller.hide(WindowInsetsCompat.Type.ime())
            ViewCompat.requestApplyInsets(findViewById(R.id.rootFrame))
        }
        FileLogger.d(TAG, "Session resumed: ${info.url}")
    }

    private fun resumeSshSession() {
        if (sshSessionManager?.isConnected != true) return
        overlayManager.inputTarget = OverlayManager.InputTarget.SSH_TERMINAL
        overlayManager.sshSessionManager = sshSessionManager

        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        geckoContainer.visibility = View.GONE
        sshContainer.visibility = View.VISIBLE
        findViewById<Button>(R.id.floatingToggle).visibility = View.VISIBLE
    }

    private fun closeTunnelSession(idx: Int) {
        if (idx < 0 || idx >= tunnelSessions.size) return
        val info = tunnelSessions[idx]
        if (currentSessionIdx == idx) {
            geckoView.releaseSession()
        }
        info.session.close()
        tunnelSessions.removeAt(idx)
        if (currentSessionIdx == idx) currentSessionIdx = -1
        else if (currentSessionIdx > idx) currentSessionIdx--
        saveOpenSessionUrls()
        renderSessionList()
        if (tunnelSessions.isEmpty() && sshSessionManager?.isConnected != true) {
            if (keepAliveEnabled) KeepAliveService.stop(this)
        }
    }

    private fun renderSessionList() {
        activeSessionList.removeAllViews()
        val hasSessions = tunnelSessions.isNotEmpty() ||
            (sshSessionManager?.isConnected == true && sshContainer.visibility != View.VISIBLE)

        if (!hasSessions) {
            activeSessionsSection.visibility = View.GONE
            return
        }
        activeSessionsSection.visibility = View.VISIBLE

        // Tunnel sessions
        for ((idx, info) in tunnelSessions.withIndex()) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_tunnel_item)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            val label = TextView(this).apply {
                text = info.label
                setTextColor(resources.getColor(R.color.text_white, theme))
                textSize = 13f
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val resumeBtn = TextView(this).apply {
                text = "Resume"
                setTextColor(resources.getColor(R.color.primary, theme))
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { resumeTunnelSession(idx) }
            }

            val closeBtn = TextView(this).apply {
                text = "Close"
                setTextColor(resources.getColor(R.color.error, theme))
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { closeTunnelSession(idx) }
            }

            item.addView(label)
            item.addView(resumeBtn)
            item.addView(closeBtn)
            item.setOnClickListener { resumeTunnelSession(idx) }
            activeSessionList.addView(item)
        }

        // SSH session
        if (sshSessionManager?.isConnected == true && sshContainer.visibility != View.VISIBLE) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_tunnel_item)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            val label = TextView(this).apply {
                val s = currentSshServer
                text = "SSH: ${s?.username ?: ""}@${s?.host ?: ""}"
                setTextColor(resources.getColor(R.color.text_white, theme))
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val resumeBtn = TextView(this).apply {
                text = "Resume"
                setTextColor(resources.getColor(R.color.primary, theme))
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { resumeSshSession() }
            }

            val closeBtn = TextView(this).apply {
                text = "Close"
                setTextColor(resources.getColor(R.color.error, theme))
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
                setOnClickListener { disconnectSsh(); renderSessionList() }
            }

            item.addView(label)
            item.addView(resumeBtn)
            item.addView(closeBtn)
            item.setOnClickListener { resumeSshSession() }
            activeSessionList.addView(item)
        }
    }

    private fun updateSessionUrl(session: GeckoSession, newUrl: String) {
        val info = tunnelSessions.find { it.session === session } ?: return
        // Only track vscode.dev URLs (ignore auth redirects etc.)
        if (!newUrl.contains("vscode.dev")) return
        info.url = newUrl
        info.label = newUrl.removePrefix("https://vscode.dev/tunnel/")
            .removePrefix("https://insiders.vscode.dev/tunnel/")
            .ifBlank { newUrl }
        currentTunnelUrl = newUrl
        // Persist updated URLs
        saveOpenSessionUrls()
        FileLogger.d(TAG, "Session URL updated: $newUrl")
    }

    private fun saveOpenSessionUrls() {
        val urls = tunnelSessions.map { it.url }
        sessionPrefs.edit().putString(KEY_LAST_URL,
            if (urls.isNotEmpty()) org.json.JSONArray(urls).toString() else ""
        ).apply()
    }

    // --- Recent URLs ---

    private fun getRecentUrls(): List<String> {
        val json = recentPrefs.getString(KEY_RECENT_URLS, null) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) { emptyList() }
    }

    private fun saveRecentUrl(url: String) {
        val recent = getRecentUrls().toMutableList()
        recent.remove(url)
        recent.add(0, url)
        while (recent.size > MAX_RECENT) recent.removeAt(recent.lastIndex)
        recentPrefs.edit().putString(KEY_RECENT_URLS, org.json.JSONArray(recent).toString()).apply()
        renderRecent()
    }

    private fun renderRecent() {
        val recent = getRecentUrls()
        if (recent.isEmpty()) {
            recentSection.visibility = View.GONE
            return
        }

        recentSection.visibility = View.VISIBLE
        recentList.removeAllViews()

        for (url in recent) {
            val tv = TextView(this).apply {
                text = url
                setTextColor(resources.getColor(R.color.primary, theme))
                textSize = 13f
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { connectTo(url) }
            }
            recentList.addView(tv)
        }
    }

    private fun loadLastUrlIntoInput() {
        val recent = getRecentUrls()
        if (recent.isNotEmpty()) {
            urlInput.setText(recent[0])
        }
    }

    // --- Error Display ---

    private fun showError(msg: String) {
        errorText.text = msg
        errorText.visibility = View.VISIBLE
        errorText.postDelayed({ errorText.visibility = View.GONE }, 5000)
    }

    // --- Update Check ---

    private lateinit var updateProgress: ProgressBar

    private fun checkForUpdate() {
        updateProgress = findViewById(R.id.updateProgress)

        lifecycleScope.launch {
            try {
                val release = TunnelApi.checkUpdate(APP_VERSION) ?: return@launch
                val version = release.optString("tag_name", "").trimStart('v')
                val assets = release.optJSONArray("assets")
                val downloadUrl = assets?.let {
                    for (i in 0 until it.length()) {
                        val name = it.getJSONObject(i).optString("name", "")
                        if (name.endsWith(".apk")) {
                            return@let it.getJSONObject(i).optString("browser_download_url")
                        }
                    }
                    null
                } ?: return@launch

                updateText.text = "Update available: v$version"
                updateLink.setOnClickListener {
                    downloadAndInstallUpdate(downloadUrl, version)
                }
                updateBanner.visibility = View.VISIBLE
            } catch (_: Exception) {}
        }
    }

    private fun downloadAndInstallUpdate(url: String, version: String) {
        updateLink.visibility = View.GONE
        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = false
        updateProgress.progress = 0
        updateText.text = "Downloading v$version..."

        lifecycleScope.launch {
            try {
                val apkFile = downloadApk(url) { progress ->
                    runOnUiThread {
                        if (progress < 0) {
                            updateProgress.isIndeterminate = true
                        } else {
                            updateProgress.isIndeterminate = false
                            updateProgress.progress = progress
                        }
                    }
                }

                runOnUiThread {
                    updateText.text = "Installing v$version..."
                    updateProgress.visibility = View.GONE
                    installApk(apkFile)
                }
            } catch (e: Exception) {
                FileLogger.e(TAG, "Update download failed: $e")
                runOnUiThread {
                    updateText.text = "Download failed: ${e.message}"
                    updateLink.text = "Retry"
                    updateLink.visibility = View.VISIBLE
                    updateProgress.visibility = View.GONE
                }
            }
        }
    }

    private suspend fun downloadApk(
        url: String,
        onProgress: (Int) -> Unit
    ): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = java.io.File(cacheDir, "updates")
        dir.mkdirs()
        val file = java.io.File(dir, "update.apk")
        if (file.exists()) file.delete()

        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.setRequestProperty("User-Agent", "VSCodeTunnel-Android/${BuildConfig.VERSION_NAME}")
        conn.instanceFollowRedirects = true
        conn.connect()

        // Handle redirects (GitHub releases redirect to S3)
        val responseCode = conn.responseCode
        val actualConn = if (responseCode == 302 || responseCode == 301) {
            val redirectUrl = conn.getHeaderField("Location")
            conn.disconnect()
            val rc = java.net.URL(redirectUrl).openConnection() as java.net.HttpURLConnection
            rc.setRequestProperty("User-Agent", "VSCodeTunnel-Android/${BuildConfig.VERSION_NAME}")
            rc.connect()
            rc
        } else {
            conn
        }

        val totalSize = actualConn.contentLength.toLong()
        var downloaded = 0L

        actualConn.inputStream.use { input ->
            file.outputStream().use { output ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    downloaded += n
                    if (totalSize > 0) {
                        onProgress(((downloaded * 100) / totalSize).toInt())
                    } else {
                        onProgress(-1)
                    }
                }
            }
        }
        actualConn.disconnect()

        FileLogger.d(TAG, "APK downloaded: ${file.length()} bytes")
        file
    }

    private fun installApk(file: java.io.File) {
        try {
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            startActivity(intent)
        } catch (e: Exception) {
            FileLogger.e(TAG, "Install failed: $e")
            showError("Install failed: ${e.message}")
            // Fallback: show file location
            updateText.text = "APK saved to: ${file.absolutePath}"
            updateLink.text = "Open Downloads"
            updateLink.visibility = View.VISIBLE
            updateLink.setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("content://downloads/all_downloads")))
                } catch (_: Exception) {}
            }
        }
    }

    // --- Back Navigation ---

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (authDialog != null) {
                    authDialog?.dismiss()
                    return
                }
                if (overlayManager.isVisible) {
                    overlayManager.hide()
                    return
                }
                if (sshContainer.visibility == View.VISIBLE || geckoContainer.visibility == View.VISIBLE) {
                    suspendSession()
                    return
                }
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        })
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        FileLogger.d(TAG, "Configuration changed: orientation=${newConfig.orientation}, " +
            "screenWidthDp=${newConfig.screenWidthDp}, screenHeightDp=${newConfig.screenHeightDp}, " +
            "smallestScreenWidthDp=${newConfig.smallestScreenWidthDp}, densityDpi=${newConfig.densityDpi}")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_TUNNEL_URL, currentTunnelUrl)
        outState.putBoolean(STATE_GECKOVIEW_VISIBLE, geckoContainer.visibility == View.VISIBLE)
        FileLogger.d(TAG, "onSaveInstanceState: url=$currentTunnelUrl, visible=${geckoContainer.visibility == View.VISIBLE}")
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val wasVisible = savedInstanceState.getBoolean(STATE_GECKOVIEW_VISIBLE, false)
        val savedUrl = savedInstanceState.getString(STATE_TUNNEL_URL)
        FileLogger.d(TAG, "onRestoreInstanceState: url=$savedUrl, wasVisible=$wasVisible")
        if (wasVisible && !savedUrl.isNullOrBlank()) {
            currentTunnelUrl = savedUrl
            openTunnel(savedUrl)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollJob?.cancel()
        authDialog?.dismiss()
        authDialog = null
        geckoView.releaseSession()
        for (s in tunnelSessions) { s.session.close() }
        tunnelSessions.clear()
        sshSessionManager?.destroy()
        sshSessionManager = null
    }

    // --- Auto Reconnect ---

    private fun checkAutoReconnect() {
        val saved = sessionPrefs.getString(KEY_LAST_URL, null)
        if (saved.isNullOrBlank()) return

        // Parse saved URLs (JSON array or single URL)
        val urls = try {
            val arr = org.json.JSONArray(saved)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            listOf(saved) // legacy single URL
        }

        if (urls.isEmpty() || urls.all { it.isBlank() }) return

        // Show reconnect section
        activeSessionsSection.visibility = View.VISIBLE
        activeSessionList.removeAllViews()

        for (url in urls) {
            if (url.isBlank()) continue
            val label = url.removePrefix("https://vscode.dev/tunnel/")
                .removePrefix("https://insiders.vscode.dev/tunnel/")
                .ifBlank { url }

            val item = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_tunnel_item)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(4) }
            }

            val tv = TextView(this).apply {
                text = "Reopen: $label"
                setTextColor(resources.getColor(R.color.text_white, theme))
                textSize = 13f
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val openBtn = TextView(this).apply {
                text = "Open"
                setTextColor(resources.getColor(R.color.primary, theme))
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }

            val dismissBtn = TextView(this).apply {
                text = "Dismiss"
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 13f
                setPadding(dp(8), dp(4), dp(8), dp(4))
            }

            val capturedUrl = url
            openBtn.setOnClickListener {
                sessionPrefs.edit().remove(KEY_LAST_URL).apply()
                activeSessionsSection.visibility = View.GONE
                connectTo(capturedUrl)
            }
            dismissBtn.setOnClickListener {
                sessionPrefs.edit().remove(KEY_LAST_URL).apply()
                activeSessionsSection.visibility = View.GONE
            }
            item.setOnClickListener {
                sessionPrefs.edit().remove(KEY_LAST_URL).apply()
                activeSessionsSection.visibility = View.GONE
                connectTo(capturedUrl)
            }

            item.addView(tv)
            item.addView(openBtn)
            item.addView(dismissBtn)
            activeSessionList.addView(item)
        }
    }

    // --- Utils ---

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
