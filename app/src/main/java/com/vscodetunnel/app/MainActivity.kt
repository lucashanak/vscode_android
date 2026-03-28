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
import com.vscodetunnel.app.AppSettings.defaultSshPort
import com.vscodetunnel.app.AppSettings.defaultSshUser

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
        private val APP_VERSION: String get() = BuildConfig.VERSION_NAME
    }

    private lateinit var geckoView: SuppressableGeckoView
    private lateinit var sessionWrapper: View
    private lateinit var geckoContainer: View
    private lateinit var launcherScroll: View
    private lateinit var overlayManager: OverlayManager
    private var tunnelSession: GeckoSession? = null
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

    private val authPrefs: SharedPreferences by lazy { getSharedPreferences(PREFS_AUTH, MODE_PRIVATE) }
    private val recentPrefs: SharedPreferences by lazy { getSharedPreferences(PREFS_RECENT, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.rootFrame)) { view, windowInsets ->
            if (sysKBSuppressed) {
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
        overlayManager = OverlayManager(geckoView, overlayWebView, cursorDot, floatingToggle) { visible ->
            onOverlayVisibilityChanged(visible)
        }
        overlayManager.setup()
        floatingToggle.setOnClickListener { overlayManager.show() }

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
        sshServerList = findViewById(R.id.sshServerList)
        sshEmptyText = findViewById(R.id.sshEmptyText)
    }

    private fun setupListeners() {
        btnGitHubLogin.setOnClickListener { startGitHubLogin() }
        findViewById<View>(R.id.btnLogout).setOnClickListener { logout() }
        findViewById<View>(R.id.btnRefreshTunnels).setOnClickListener { loadTunnels() }
        findViewById<View>(R.id.btnAddSsh).setOnClickListener { showSshServerDialog(null) }
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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

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

        val nameField = addField("Display name (optional)", existing?.name ?: "")
        val hostField = addField("Host", existing?.host ?: "")
        val portField = addField("Port", (existing?.port ?: defaultSshPort).toString(),
            android.text.InputType.TYPE_CLASS_NUMBER)
        val userField = addField("Username", existing?.username ?: defaultSshUser)
        val passField = addField("Password", existing?.password ?: "",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)

        layout.addView(nameField)
        layout.addView(hostField)
        layout.addView(portField)
        layout.addView(userField)
        layout.addView(passField)

        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(if (existing != null) "Edit Server" else "Add SSH Server")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val host = hostField.text.toString().trim()
                val user = userField.text.toString().trim()
                if (host.isBlank() || user.isBlank()) {
                    showError("Host and username are required")
                    return@setPositiveButton
                }
                val server = SshServer(
                    id = existing?.id ?: System.currentTimeMillis().toString(),
                    name = nameField.text.toString().trim(),
                    host = host,
                    port = portField.text.toString().toIntOrNull() ?: 22,
                    username = user,
                    authMethod = SshServer.AuthMethod.PASSWORD,
                    password = passField.text.toString()
                )
                ServerStorage.saveServer(this, server)
                renderSshServers()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun connectSsh(server: SshServer) {
        currentSshServer = server

        // Setup SSH terminal
        val mgr = SshSessionManager(sshTerminalWebView) { reason ->
            runOnUiThread {
                FileLogger.d(TAG, "SSH disconnected: $reason")
                if (keepAliveEnabled) {
                    KeepAliveService.stop(this)
                }
            }
        }
        sshSessionManager?.destroy()
        sshSessionManager = mgr

        // Configure overlay for SSH
        overlayManager.inputTarget = OverlayManager.InputTarget.SSH_TERMINAL
        overlayManager.sshSessionManager = mgr

        mgr.setupTerminal()
        mgr.connect(server)

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
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }

        val keepAliveCheck = CheckBox(this).apply {
            text = "Keep alive in background"
            isChecked = keepAliveEnabled
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val fontLabel = TextView(this).apply {
            text = "Terminal font size"
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
        }
        val fontField = EditText(this).apply {
            setText(terminalFontSize.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 14f
            background = resources.getDrawable(R.drawable.bg_input, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val portLabel = TextView(this).apply {
            text = "Default SSH port"
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
        }
        val portField = EditText(this).apply {
            setText(defaultSshPort.toString())
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 14f
            background = resources.getDrawable(R.drawable.bg_input, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        val userLabel = TextView(this).apply {
            text = "Default SSH username"
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
        }
        val userField = EditText(this).apply {
            setText(defaultSshUser)
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 14f
            background = resources.getDrawable(R.drawable.bg_input, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }

        layout.addView(keepAliveCheck)
        layout.addView(fontLabel)
        layout.addView(fontField)
        layout.addView(portLabel)
        layout.addView(portField)
        layout.addView(userLabel)
        layout.addView(userField)

        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Settings")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                keepAliveEnabled = keepAliveCheck.isChecked
                terminalFontSize = fontField.text.toString().toIntOrNull() ?: 14
                defaultSshPort = portField.text.toString().toIntOrNull() ?: 22
                defaultSshUser = userField.text.toString().trim()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

        tunnelSession?.close()

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

        tunnelSession = session
        geckoView.releaseSession()
        geckoView.setSession(session)
        session.loadUri(url)

        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        sshContainer.visibility = View.GONE
        geckoContainer.visibility = View.VISIBLE
        findViewById<Button>(R.id.floatingToggle).visibility = View.VISIBLE

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
        sysKBSuppressed = visible
        geckoView.suppressIME = visible
        FileLogger.d(TAG, "Overlay visible: $visible, sysKB suppressed: $visible")
        if (visible) {
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
        geckoView.releaseSession()
        tunnelSession?.close()
        tunnelSession = null
        currentTunnelUrl = null
        sysKBSuppressed = false
        geckoView.suppressIME = false
        disconnectSsh()
        sessionWrapper.visibility = View.GONE
        geckoContainer.visibility = View.GONE
        sshContainer.visibility = View.GONE
        findViewById<Button>(R.id.floatingToggle).visibility = View.GONE
        launcherScroll.visibility = View.VISIBLE
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

    private fun checkForUpdate() {
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
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)))
                    } catch (_: Exception) {}
                }
                updateBanner.visibility = View.VISIBLE
            } catch (_: Exception) {}
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
                if (sshContainer.visibility == View.VISIBLE) {
                    showLauncher()
                    return
                }
                if (geckoContainer.visibility == View.VISIBLE) {
                    showLauncher()
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
        tunnelSession?.close()
        tunnelSession = null
        sshSessionManager?.destroy()
        sshSessionManager = null
    }

    // --- Utils ---

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
