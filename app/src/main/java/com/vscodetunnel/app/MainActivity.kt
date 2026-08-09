package com.vscodetunnel.app

// androidx, not android.app: the framework AlertDialog ignores Material3 theme attributes, so
// AppDialogTheme's colorPrimary never reached the buttons and CANCEL/SAVE rendered in Material's
// default purple at roughly 1.2:1 contrast — measured from a screenshot, effectively invisible.
import androidx.appcompat.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.widget.FrameLayout
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import com.vscodetunnel.app.AppSettings.sshKeepaliveInterval
import com.vscodetunnel.app.AppSettings.sshOsc52ClipboardRead
import com.vscodetunnel.app.AppSettings.tunnelKeepaliveInterval
import com.vscodetunnel.app.AppSettings.tunnelStaleRefreshMin
import com.vscodetunnel.app.AppSettings.suppressSystemKeyboard
import com.vscodetunnel.app.AppSettings.biometricLockEnabled
import com.vscodetunnel.app.AppSettings.vscodeZoomPercent
import com.vscodetunnel.app.AppSettings.vscodeLanguage
import com.vscodetunnel.app.AppSettings.compactKeyHeight
import com.vscodetunnel.app.AppSettings.wideKeyHeight
import com.vscodetunnel.app.AppSettings.tpSensitivity
import com.vscodetunnel.app.AppSettings.tpScrollSpeed
import com.vscodetunnel.app.AppSettings.tpInvertScroll
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt

class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "VSCodeTunnel"

        /**
         * How long a gap makes the next reload press a fresh attempt rather than an escalation.
         *
         * Consecutive presses against a wedged workbench come seconds apart; a tap tomorrow should
         * not inherit today's position on the ladder and skip straight to clearing the CDN cache.
         */
        private const val RUNG_RESET_MS = 120_000L
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
        private const val KEY_LAST_TUNNEL_ACTIVE = "last_tunnel_active_ms"
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
    private lateinit var floatingTouchpad: FloatingTouchpad
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

        // Hide navigation bar globally — maximizes screen space
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.navigationBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Biometric lock
        if (biometricLockEnabled) {
            val rootFrame = findViewById<View>(R.id.rootFrame)
            rootFrame.visibility = View.INVISIBLE
            showBiometricPrompt {
                rootFrame.visibility = View.VISIBLE
            }
        }

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
            onBackToMenu = { suspendSession() },
            onReloadPage = { reloadCurrentTunnel("keyboard button") }
        )
        overlayManager.setup()
        overlayManager.alwaysSuppressInput = suppressSystemKeyboard
        var lastToggleClickTime = 0L
        var pendingSingleClick: Runnable? = null
        val toggleHandler = android.os.Handler(android.os.Looper.getMainLooper())
        floatingToggle.setOnClickListener {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastToggleClickTime < 300) {
                // Double-click → toggle color invert (sunlight readability)
                pendingSingleClick?.let { toggleHandler.removeCallbacks(it) }
                pendingSingleClick = null
                lastToggleClickTime = 0
                overlayManager.toggleColorInvert()
            } else {
                lastToggleClickTime = now
                // Defer the single-click action so a quick second tap can cancel it
                val r = Runnable {
                    pendingSingleClick = null
                    if (floatingTouchpad.visibility == View.VISIBLE) hideFloatingTouchpad()
                    overlayManager.show()
                }
                pendingSingleClick = r
                toggleHandler.postDelayed(r, 300)
            }
        }
        floatingToggle.setOnLongClickListener {
            pendingSingleClick?.let { toggleHandler.removeCallbacks(it) }
            pendingSingleClick = null
            showFloatingTouchpad()
            true
        }

        // Setup floating touchpad
        floatingTouchpad = FloatingTouchpad(this, overlayManager)
        floatingTouchpad.visibility = View.GONE
        floatingTouchpad.onClose = { hideFloatingTouchpad() }
        val rootFrame = findViewById<FrameLayout>(R.id.rootFrame)
        rootFrame.addView(floatingTouchpad, FrameLayout.LayoutParams(1, 1)) // sized later in updateSize()
        rootFrame.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            if (floatingTouchpad.visibility == View.VISIBLE) {
                floatingTouchpad.updateSize()
                floatingTouchpad.clampToParent()
            }
        }

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
        findViewById<View>(R.id.btnKeyGen).setOnClickListener { showKeyGenDialog() }
        findViewById<View>(R.id.btnSettings).setOnClickListener { showSettingsDialog() }
        findViewById<View>(R.id.btnCheckUpdate).setOnClickListener { checkForUpdate() }
        findViewById<View>(R.id.btnExit).setOnClickListener {
            finishAffinity()
            android.os.Process.killProcess(android.os.Process.myPid())
        }

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
        // serversOrNull rather than getServers: null means the store could not be read, which is
        // not the same as having no servers. Rendering the usual empty state for it tells the user
        // their servers are gone while the encrypted file sits intact on disk.
        val servers = ServerStorage.serversOrNull(this)
        sshServerList.removeAllViews()

        if (servers == null) {
            sshEmptyText.text = ServerStorage.lastError
                ?: "Saved servers are temporarily unavailable."
            sshEmptyText.visibility = View.VISIBLE
            return
        }

        // A readable but degraded store (servers left unencrypted) still has a lastError, and the
        // list itself is fine — so that one goes to the transient error strip instead of replacing
        // the list. Once per process, because this renders on every save, delete and resume.
        if (!storageWarningShown) {
            ServerStorage.lastError?.let {
                storageWarningShown = true
                showError(it)
            }
        }

        if (servers.isEmpty()) {
            sshEmptyText.text = "No saved servers"
            sshEmptyText.visibility = View.VISIBLE
            return
        }

        sshEmptyText.visibility = View.GONE
        for (server in servers) {
            addSshServerItem(server)
        }
    }

    /** Keeps the degraded-storage warning from re-firing on every [renderSshServers]. */
    private var storageWarningShown = false

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
                if (ServerStorage.deleteServer(this@MainActivity, server.id)) {
                    // Only once the server is really gone: on a failed delete the id is still live
                    // and its session-name history is still wanted.
                    TmuxManager.forgetServer(this@MainActivity, server.id)
                } else {
                    showError(ServerStorage.lastError ?: "Could not delete this server.")
                }
                // Re-render either way: on failure the row is still there, and leaving it looking
                // deleted would invite the user to assume it is gone.
                renderSshServers()
            }
        }

        val filesBtn = TextView(this).apply {
            text = "Files"
            setTextColor(resources.getColor(R.color.primary, theme))
            textSize = 13f
            setPadding(dp(8), dp(4), dp(8), dp(4))
            setOnClickListener { openSftp(server) }
        }

        actions.addView(filesBtn)
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

        // Hints carry an example; the label above each field carries the name. Repeating the name
        // in both just doubles the text on an already tall dialog.
        val nameField = addField("e.g. work laptop", existing?.name ?: "")
        val hostField = addField("example.com or 10.0.0.5", existing?.host ?: "")
        val portField = addField("22", (existing?.port ?: defaultSshPort).toString(),
            android.text.InputType.TYPE_CLASS_NUMBER)
        val userField = addField("e.g. root", existing?.username ?: defaultSshUser)
        val passField = addField("leave empty when using a key", existing?.password ?: "",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val keyField = addField("Private key (paste PEM or use file picker)", existing?.privateKey ?: "",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE)
        val keyPassField = addField("Key passphrase (if the key is encrypted)", existing?.keyPassphrase ?: "",
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val startupField = addField("Startup command (e.g. cd /app && tmux attach)", existing?.startupCommand ?: "")
        val portFwdField = addField("Port forwards (e.g. L8080:127.0.0.1:80,R3000:localhost:3000)",
            existing?.portForwards?.joinToString(",") ?: "")
        val snippetsField = addField("Snippets (comma-separated commands)",
            existing?.snippets?.joinToString(",") ?: "")

        // Labels, not just hints. A hint vanishes the moment the field has a value, so a saved
        // server showed five unlabelled boxes — a name, a host, a number, a username and six
        // password dots, with nothing saying which was which.
        addLabel("Display name (optional)"); layout.addView(nameField)
        addLabel("Host"); layout.addView(hostField)
        addLabel("Port"); layout.addView(portField)
        addLabel("Username"); layout.addView(userField)
        addLabel("Password"); layout.addView(passField)
        addLabel("Authentication key (optional)")
        layout.addView(keyField)
        layout.addView(keyPassField)

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

        addLabel("Connection protocol")
        val moshCheck = CheckBox(this).apply {
            text = "Use Mosh (UDP, survives network switches)"
            isChecked = existing?.useMosh ?: false
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        layout.addView(moshCheck)

        addLabel("Session persistence")
        val tmuxCheck = CheckBox(this).apply {
            text = "Use tmux (persistent sessions)"
            isChecked = existing?.useTmux ?: false
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        layout.addView(tmuxCheck)

        val tmuxNameField = EditText(this).apply {
            hint = "Session name (default: main)"
            setText(existing?.tmuxSessionName ?: "")
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setHintTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 14f
            background = resources.getDrawable(R.drawable.bg_input, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            visibility = if (existing?.useTmux == true) View.VISIBLE else View.GONE
        }
        tmuxCheck.setOnCheckedChangeListener { _, checked ->
            tmuxNameField.visibility = if (checked) View.VISIBLE else View.GONE
        }
        layout.addView(tmuxNameField)

        addLabel("Cloudflare Tunnel")
        val cfCheck = CheckBox(this).apply {
            text = "Route SSH via Cloudflare Tunnel (WebSocket proxy)"
            isChecked = existing?.useCloudflareProxy ?: false
            setTextColor(resources.getColor(R.color.text_primary, theme))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }
        layout.addView(cfCheck)

        val cfTokenField = EditText(this).apply {
            hint = "CF Access token (optional, for Zero Trust)"
            setText(existing?.cloudflareToken ?: "")
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setHintTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 14f
            background = resources.getDrawable(R.drawable.bg_input, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
            visibility = if (existing?.useCloudflareProxy == true) View.VISIBLE else View.GONE
        }
        cfCheck.setOnCheckedChangeListener { _, checked ->
            cfTokenField.visibility = if (checked) View.VISIBLE else View.GONE
        }
        layout.addView(cfTokenField)

        // Built with create() and an overridden button listener rather than setPositiveButton's
        // own, because the builder's listener always dismisses. A save that did not land has to
        // leave the dialog up with the user's input still in it — same for the blank-field check,
        // which previously threw the whole form away to show its message.
        val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle(if (existing != null) "Edit Server" else "Add SSH Server")
            .setView(scroll)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create()
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val host = hostField.text.toString().trim()
            val user = userField.text.toString().trim()
            if (host.isBlank() || user.isBlank()) {
                // Toast, not showError: the error strip lives in the Activity behind this dialog.
                android.widget.Toast.makeText(
                    this, "Host and username are required", android.widget.Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
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
                keyPassphrase = keyPassField.text.toString(),
                startupCommand = startupField.text.toString().trim(),
                portForwards = parsePortForwards(portFwdField.text.toString()),
                snippets = snippetsField.text.toString().split(",").map { it.trim() }.filter { it.isNotBlank() },
                useMosh = moshCheck.isChecked,
                useTmux = tmuxCheck.isChecked,
                tmuxSessionName = tmuxNameField.text.toString().trim(),
                useCloudflareProxy = cfCheck.isChecked,
                cloudflareToken = cfTokenField.text.toString().trim()
            )
            if (!ServerStorage.saveServer(this, server)) {
                android.widget.Toast.makeText(
                    this,
                    ServerStorage.lastError ?: "Could not save this server.",
                    android.widget.Toast.LENGTH_LONG
                ).show()
                return@setOnClickListener
            }
            dialog.dismiss()
            renderSshServers()
        }
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

    private fun showKeyGenDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        val types = arrayOf("ED25519", "RSA (4096)")
        val typeSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, types)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val commentField = EditText(this).apply {
            hint = "Comment (e.g. android@phone)"
            setTextColor(resources.getColor(R.color.text_primary, theme))
            setHintTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 14f
            background = resources.getDrawable(R.drawable.bg_input, theme)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        layout.addView(TextView(this).apply {
            text = "Key type"
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
        })
        layout.addView(typeSpinner)
        layout.addView(commentField)

        AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Generate SSH Key")
            .setView(layout)
            .setPositiveButton("Generate") { _, _ ->
                val isEd = typeSpinner.selectedItemPosition == 0
                val comment = commentField.text.toString().trim().ifBlank { "android" }
                generateSshKey(isEd, comment)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateSshKey(ed25519: Boolean, comment: String) {
        lifecycleScope.launch {
            try {
                // Generated through JSch rather than java.security on purpose. PublicKey.getEncoded()
                // returns X.509 SubjectPublicKeyInfo DER, which is *not* the OpenSSH wire format;
                // labelling it "ssh-ed25519 <der>" produced a line sshd silently ignores, so every
                // key this dialog ever handed out was unusable in authorized_keys. writePublicKey
                // emits the real "ssh-ed25519 AAAA... comment" line.
                val type = if (ed25519) com.jcraft.jsch.KeyPair.ED25519 else com.jcraft.jsch.KeyPair.RSA
                val (pubLine, privPem, loadable) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val kp = com.jcraft.jsch.KeyPair.genKeyPair(
                        com.jcraft.jsch.JSch(), type, if (ed25519) 256 else 4096
                    )
                    // dispose() in a finally: it zeroes the key material, and a throw from either
                    // write would otherwise leave the private key sitting in memory.
                    val (pubOut, privOut) = try {
                        java.io.ByteArrayOutputStream().also { kp.writePublicKey(it, comment) } to
                            java.io.ByteArrayOutputStream().also { kp.writePrivateKey(it) }
                    } finally {
                        kp.dispose()
                    }
                    val priv = privOut.toString("UTF-8")
                    // A private key the app's own loader cannot read is worse than no key at all —
                    // the user would paste it into a server and get a generic connection failure.
                    val ok = try {
                        com.jcraft.jsch.KeyPair.load(
                            com.jcraft.jsch.JSch(), priv.toByteArray(), null
                        ).also { it.dispose() }
                        true
                    } catch (e: Exception) {
                        FileLogger.e(TAG, "Generated private key does not load back", e)
                        false
                    }
                    Triple(pubOut.toString("UTF-8").trim(), priv, ok)
                }

                // Show result
                runOnUiThread {
                    val resultLayout = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                    }
                    val pubText = TextView(this@MainActivity).apply {
                        text = "Public key (add to ~/.ssh/authorized_keys):"
                        setTextColor(resources.getColor(R.color.text_secondary, theme))
                        textSize = 12f
                    }
                    val pubField = EditText(this@MainActivity).apply {
                        setText(pubLine)
                        setTextColor(resources.getColor(R.color.text_primary, theme))
                        textSize = 11f
                        setTextIsSelectable(true)
                        maxLines = 4
                    }
                    val privText = TextView(this@MainActivity).apply {
                        text = if (loadable) {
                            "\nPrivate key (paste into server SSH key field):"
                        } else {
                            "\n⚠ This private key could not be read back by the app's own SSH " +
                                "library — do not rely on it, generate another one.\n" +
                                "Private key (paste into server SSH key field):"
                        }
                        setTextColor(resources.getColor(
                            if (loadable) R.color.text_secondary else R.color.error, theme))
                        textSize = 12f
                    }
                    val privField = EditText(this@MainActivity).apply {
                        setText(privPem)
                        setTextColor(resources.getColor(R.color.text_primary, theme))
                        textSize = 11f
                        setTextIsSelectable(true)
                        maxLines = 6
                    }
                    resultLayout.addView(pubText)
                    resultLayout.addView(pubField)
                    resultLayout.addView(privText)
                    resultLayout.addView(privField)

                    val scroll = ScrollView(this@MainActivity)
                    scroll.addView(resultLayout)

                    AlertDialog.Builder(this@MainActivity, R.style.AppDialogTheme)
                        .setTitle("SSH Key Generated")
                        .setView(scroll)
                        .setPositiveButton("Copy Public Key") { _, _ ->
                            TerminalClipboard.copy(this@MainActivity, pubLine)
                            showError("Public key copied to clipboard")
                        }
                        .setNeutralButton("Copy Private Key") { _, _ ->
                            TerminalClipboard.copy(this@MainActivity, privPem, sensitive = true)
                            showError("Private key copied to clipboard")
                        }
                        .setNegativeButton("Close", null)
                        .show()
                }
            } catch (e: Exception) {
                runOnUiThread { showError("Key generation failed: ${e.message}") }
            }
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

    private fun showHostKeyDialog(
        host: String,
        port: Int,
        fingerprint: String,
        changed: Boolean,
        oldFingerprint: String?,
        callback: (Boolean) -> Unit
    ) {
        // onHostKeyVerify fires on JSch's connect thread during key exchange (before any
        // credential is sent), and callback must be invoked exactly once no matter how the
        // dialog is dismissed.
        var resolved = false
        fun resolve(accept: Boolean) {
            if (resolved) return
            resolved = true
            callback(accept)
        }

        // Everything below runs on the main thread, in exactly one runnable, with the try/catch
        // *inside* it. That placement is the point: a background auto-reconnect can reach here
        // while the Activity is going away, and show() on a dead window throws BadTokenException.
        // If that throw escaped a runnable already executing on the main Looper it would be an
        // uncaught exception and kill the process — taking the blocked JSch thread with it long
        // before its prompt timeout could answer. Caught here, we just reject.
        val show = Runnable {
            if (isFinishing || isDestroyed) {
                FileLogger.w(TAG, "Host key prompt for $host:$port while finishing, rejecting")
                resolve(false)
                return@Runnable
            }
            try {
                if (!changed) {
                    // First sight of this host (TOFU). Low-friction confirm — accepting is what
                    // lets the connection proceed.
                    AlertDialog.Builder(this, R.style.AppDialogTheme)
                        .setTitle("New Host Key")
                        .setMessage(
                            "First connection to $host:$port.\n\n" +
                                "Fingerprint (SHA256):\n$fingerprint\n\n" +
                                "Trust this key and continue?"
                        )
                        .setPositiveButton("Trust & Connect") { _, _ -> resolve(true) }
                        .setNegativeButton("Cancel") { _, _ -> resolve(false) }
                        .setOnCancelListener { resolve(false) }
                        .setCancelable(true)
                        .show()
                } else {
                    // Pinned key no longer matches. Always alarming, never auto-accepted.
                    AlertDialog.Builder(this, R.style.AppDialogTheme)
                        .setTitle("⚠ Host Key Changed!")
                        .setMessage(
                            "The host key for $host:$port has changed. This can happen after a legitimate " +
                                "server reinstall, or it can mean someone is intercepting your connection " +
                                "(man-in-the-middle attack).\n\n" +
                                "These fingerprints are SHA256 hashes of the host key — compare them against " +
                                "the server's actual key with `ssh-keygen -lf <hostkey file>` before accepting.\n\n" +
                                "Previously known fingerprint:\n$oldFingerprint\n\n" +
                                "New fingerprint:\n$fingerprint\n\n" +
                                "Only accept if you are certain this change is expected."
                        )
                        .setPositiveButton("Accept New Key") { _, _ -> resolve(true) }
                        .setNegativeButton("Reject") { _, _ -> resolve(false) }
                        .setOnCancelListener { resolve(false) }
                        .setCancelable(true)
                        .show()
                }
            } catch (t: Throwable) {
                FileLogger.e(TAG, "Could not show host key dialog, rejecting", t)
                resolve(false)
            }
        }

        // Callers arrive both ways and this must not care: JschFactory.blockingPrompt has already
        // hopped to the main thread, while a direct onHostKeyVerify call is on JSch's. Run inline
        // when already on main rather than posting a second runnable.
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) show.run()
        else runOnUiThread(show)
    }

    /**
     * The blocking host-key confirmation the non-shell SSH paths (tmux, sftp, mosh) hand to
     * [JschFactory]. Without one those paths fail closed on any server that has no pin yet.
     *
     * Only builds the prompt — the block happens inside confirm(), on whichever background
     * thread JSch runs key exchange on, so this is safe to call from the main thread.
     */
    private fun hostKeyPrompt(): JschFactory.HostKeyPrompt =
        JschFactory.blockingPrompt { host, port, fingerprint, changed, old, respond ->
            showHostKeyDialog(host, port, fingerprint, changed, old, respond)
        }

    /**
     * Asks for the passphrase of [server]'s encrypted private key.
     *
     * [callback] is invoked exactly once, with null when the user cancels or the dialog cannot be
     * shown. The entry is checked against the key itself before being accepted, so a typo is
     * reported here instead of turning into an opaque authentication failure at the server.
     */
    private fun showKeyPassphraseDialog(server: SshServer, retry: Boolean, callback: (String?) -> Unit) {
        var resolved = false
        fun resolve(passphrase: String?) {
            if (resolved) return
            resolved = true
            callback(passphrase)
        }

        // Same shape as showHostKeyDialog: one runnable on the main thread with the try/catch
        // inside it, because a background auto-reconnect can reach here while the Activity is
        // going away and show() would then throw BadTokenException on the main Looper.
        val show = Runnable {
            if (isFinishing || isDestroyed) {
                FileLogger.w(TAG, "Passphrase prompt for ${server.host} while finishing, cancelling")
                resolve(null)
                return@Runnable
            }
            try {
                val layout = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(24), dp(8), dp(24), dp(0))
                }
                val input = EditText(this).apply {
                    hint = "Passphrase"
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    setTextColor(resources.getColor(R.color.text_primary, theme))
                    setHintTextColor(resources.getColor(R.color.text_secondary, theme))
                    textSize = 16f
                    background = resources.getDrawable(R.drawable.bg_input, theme)
                    setPadding(dp(16), dp(12), dp(16), dp(12))
                }
                val rememberCheck = CheckBox(this).apply {
                    text = "Remember this passphrase"
                    setTextColor(resources.getColor(R.color.text_primary, theme))
                    textSize = 14f
                    // Only offered for a stored server — ticking it on an ad-hoc quick-connect
                    // would silently add a new entry to the server list.
                    visibility = if (ServerStorage.getServers(this@MainActivity).any { it.id == server.id }) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }
                layout.addView(input)
                layout.addView(rememberCheck)

                val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
                    .setTitle(if (retry) "Key passphrase — try again" else "Key passphrase")
                    .setMessage(
                        if (retry) {
                            "The remembered passphrase did not open the private key for " +
                                "${server.username}@${server.host}."
                        } else {
                            "The private key for ${server.username}@${server.host} is encrypted."
                        }
                    )
                    .setView(layout)
                    .setPositiveButton("Unlock", null)
                    .setNegativeButton("Cancel", null)
                    .create()
                // Covers Cancel, back and outside taps in one place; the resolve-once guard makes
                // it a no-op after a successful unlock has already answered.
                dialog.setOnDismissListener { resolve(null) }
                dialog.show()
                val unlockBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                unlockBtn.setOnClickListener {
                    val passphrase = input.text.toString()
                    // isPassphraseValid runs the key's KDF (bcrypt for OPENSSH keys), which is
                    // deliberately slow — on the main thread that is a visible freeze, so it goes
                    // to IO. The button is disabled meanwhile so an impatient double-tap cannot
                    // queue a second KDF pass.
                    unlockBtn.isEnabled = false
                    lifecycleScope.launch {
                        val valid = withContext(Dispatchers.IO) {
                            JschFactory.isPassphraseValid(server.privateKey, passphrase)
                        }
                        unlockBtn.isEnabled = true
                        if (!valid) {
                            android.widget.Toast.makeText(
                                this@MainActivity, "Wrong passphrase", android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        if (rememberCheck.visibility == View.VISIBLE && rememberCheck.isChecked &&
                            !ServerStorage.saveServer(this@MainActivity, server.copy(keyPassphrase = passphrase))
                        ) {
                            // The connection can still go ahead with what was typed; only the
                            // remembering failed, and saying nothing would make it look remembered.
                            android.widget.Toast.makeText(
                                this@MainActivity,
                                ServerStorage.lastError ?: "Could not remember this passphrase.",
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                        resolve(passphrase)
                        dialog.dismiss()
                    }
                }
            } catch (t: Throwable) {
                FileLogger.e(TAG, "Could not show passphrase dialog, cancelling", t)
                resolve(null)
            }
        }

        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) show.run()
        else runOnUiThread(show)
    }

    /**
     * Resolves the key passphrase for [server] and hands [onReady] a copy of the server that can be
     * connected with — the passphrase travels in [SshServer.keyPassphrase], which [JschFactory]
     * picks up, so paths that take no passphrase argument of their own (the tmux exec channels)
     * work too.
     *
     * Asks only when there is something to ask: a key-auth server whose key is actually encrypted
     * and whose remembered passphrase — if any — no longer opens it. Nothing is persisted unless
     * the user ticks "Remember".
     *
     * Non-blocking and main-thread safe, unlike the prompt [SshSessionManager] drives for the shell
     * — these are one-shot user-initiated connects, so a cancel simply means [onReady] never runs.
     */
    private fun withKeyPassphrase(server: SshServer, onReady: (SshServer) -> Unit) {
        val encrypted = server.authMethod == SshServer.AuthMethod.KEY &&
            server.privateKey.isNotBlank() &&
            JschFactory.isKeyEncrypted(server.privateKey)
        if (!encrypted) {
            onReady(server)
            return
        }

        lifecycleScope.launch {
            // A remembered passphrase can go stale — the key was replaced, or its passphrase
            // changed. Trusting it blindly makes these one-shot paths sail past the prompt and die
            // inside addIdentity as "invalid privatekey", with no way for the user to correct it.
            // SshSessionManager.resolveKeyPassphrase already validates for the shell path; this
            // keeps Files, mosh and the tmux picker honest too. The check runs a KDF, hence IO.
            val stored = server.keyPassphrase
            val storedWorks = stored.isNotBlank() && withContext(Dispatchers.IO) {
                JschFactory.isPassphraseValid(server.privateKey, stored)
            }
            if (storedWorks) {
                onReady(server)
                return@launch
            }
            showKeyPassphraseDialog(server, retry = stored.isNotBlank()) { passphrase ->
                if (passphrase == null) {
                    showError("Key passphrase required")
                } else {
                    onReady(server.copy(keyPassphrase = passphrase))
                }
            }
        }
    }

    /**
     * Lists the pinned host keys and lets them be dropped.
     *
     * Verification happens during key exchange and fails closed, so a server that legitimately
     * rotates its key otherwise leaves the user with nothing but the alarming change dialog and no
     * way to see or clear what is stored.
     *
     * @param onClosed lets the caller refresh a pin count it is displaying.
     */
    private fun showKnownHostsDialog(onClosed: () -> Unit = {}) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(0))
        }

        // The full fingerprint is shown rather than truncated because comparing it against the
        // server is the only way a user can tell a real pin from an impostor's.
        layout.addView(TextView(this).apply {
            text = "Fingerprints are SHA-256 hashes of the host's public key. Check one against " +
                "the server with `ssh-keygen -lf <hostkey file>`."
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            textSize = 12f
            setPadding(0, 0, 0, dp(10))
        })

        val listLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val maxListHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
        val listScroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    widthMeasureSpec,
                    MeasureSpec.makeMeasureSpec(maxListHeight, MeasureSpec.AT_MOST)
                )
            }
        }.apply { addView(listLayout) }
        layout.addView(listScroll)

        val dialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Known host keys")
            .setView(layout)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnDismissListener { onClosed() }

        fun renderPins() {
            listLayout.removeAllViews()
            val pins = KnownHosts.all(this)

            if (pins.isEmpty()) {
                listLayout.addView(TextView(this).apply {
                    text = "No pinned host keys yet"
                    setTextColor(resources.getColor(R.color.text_secondary, theme))
                    textSize = 14f
                    setPadding(0, 0, 0, dp(8))
                })
                return
            }

            for (pin in pins) {
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.bg_tunnel_item)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(4) }
                }
                // Weighted column, so the 50-odd characters of fingerprint wrap inside the row
                // instead of pushing Remove past the edge of the dialog.
                val text = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                    )
                }
                text.addView(TextView(this).apply {
                    this.text = pin.label
                    setTextColor(resources.getColor(R.color.text_white, theme))
                    textSize = 14f
                })
                text.addView(TextView(this).apply {
                    this.text = pin.fingerprint
                    setTextColor(resources.getColor(R.color.text_secondary, theme))
                    textSize = 11f
                })
                val removeBtn = TextView(this).apply {
                    this.text = "Remove"
                    setTextColor(resources.getColor(R.color.error, theme))
                    textSize = 13f
                    setPadding(dp(8), dp(4), dp(8), dp(4))
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity, R.style.AppDialogTheme)
                            .setTitle("Remove pinned key?")
                            .setMessage(
                                "The next connection to ${pin.label} will be trusted on first " +
                                    "sight again, so a substituted key would be accepted with only " +
                                    "the usual new-host prompt. Do this after a key change you " +
                                    "expected — not to get past an unexplained one."
                            )
                            .setPositiveButton("Remove") { _, _ ->
                                KnownHosts.removeFingerprint(this@MainActivity, pin.host, pin.port)
                                renderPins()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
                row.addView(text)
                row.addView(removeBtn)
                listLayout.addView(row)
            }
        }

        renderPins()
        dialog.show()
    }

    private var moshSessionManager: MoshSessionManager? = null

    private fun connectSsh(server: SshServer) {
        if (server.useTmux) {
            showTmuxSessionPicker(server)
            return
        }
        doConnectSsh(server)
    }

    private fun showTmuxSessionPicker(unlocked: SshServer) = withKeyPassphrase(unlocked) { server ->
        // The session list runs its own SSH exec before any shell exists, so an encrypted key has
        // to be opened here rather than by SshSessionManager's prompt further down.
        val progressDialog = AlertDialog.Builder(this, R.style.AppDialogTheme)
            .setTitle("Loading tmux sessions...")
            .setView(ProgressBar(this).apply {
                setPadding(dp(24), dp(16), dp(24), dp(16))
            })
            .setCancelable(true)
            .show()

        lifecycleScope.launch {
            val sessions = TmuxManager.listSessions(this@MainActivity, server, hostKeyPrompt()).toMutableList()
            progressDialog.dismiss()

            // Sessions this app has created or attached on this server. A server shared with other
            // tooling can carry dozens of foreign sessions (one deployment here has 29), which used
            // to bury every control in the dialog, so those are hidden behind "Show all" by default.
            val known = (TmuxManager.knownSessionNames(this@MainActivity, server.id) + server.tmuxSessionName)
                .filter { it.isNotBlank() }
                .toSet()
            var showAll = known.isEmpty()

            val layout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(16), dp(24), dp(0))
            }

            val dialog = AlertDialog.Builder(this@MainActivity, R.style.AppDialogTheme)
                .setTitle("tmux sessions on ${server.host}")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .create()

            // The session list is the only part allowed to grow. Everything else — the new-session
            // row above it, the toggle and "Connect without tmux" below — stays outside this
            // ScrollView so it is reachable no matter how many sessions the server has.
            val listLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
            }
            val maxListHeight = (resources.displayMetrics.heightPixels * 0.45f).toInt()
            val listScroll = object : ScrollView(this@MainActivity) {
                override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                    // AT_MOST, not a fixed height: a two-session list must not reserve half a screen.
                    super.onMeasure(
                        widthMeasureSpec,
                        MeasureSpec.makeMeasureSpec(maxListHeight, MeasureSpec.AT_MOST)
                    )
                }
            }.apply { addView(listLayout) }

            val toggle = TextView(this@MainActivity).apply {
                setTextColor(resources.getColor(R.color.primary, theme))
                textSize = 13f
                setPadding(0, dp(10), 0, dp(4))
            }

            // New session row — the most common action, so it sits above the list and never depends
            // on scrolling.
            val newSessionLayout = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, 0, 0, dp(8))
            }
            val nameInput = EditText(this@MainActivity).apply {
                hint = "Session name"
                setText(server.tmuxSessionName.ifBlank { "main" })
                setTextColor(resources.getColor(R.color.text_primary, theme))
                setHintTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 14f
                background = resources.getDrawable(R.drawable.bg_input, theme)
                setPadding(dp(12), dp(8), dp(12), dp(8))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(8)
                }
            }
            val createBtn = Button(this@MainActivity).apply {
                text = "New"
                setTextColor(resources.getColor(R.color.primary, theme))
                textSize = 13f
                isAllCaps = false
                background = resources.getDrawable(R.drawable.bg_input, theme)
                setOnClickListener {
                    val name = nameInput.text.toString().trim().ifBlank { "main" }
                    TmuxManager.rememberSessionName(this@MainActivity, server.id, name)
                    dialog.dismiss()
                    doConnectSsh(server.copy(tmuxSessionName = name))
                }
            }
            newSessionLayout.addView(nameInput)
            newSessionLayout.addView(createBtn)

            fun attach(name: String) {
                TmuxManager.rememberSessionName(this@MainActivity, server.id, name)
                dialog.dismiss()
                doConnectSsh(server.copy(tmuxSessionName = name))
            }

            // Re-rendered in place on toggle and after a kill, so the dialog never has to be
            // dismissed and rebuilt (which would lose the toggle state and the typed name).
            fun renderList() {
                listLayout.removeAllViews()
                val visible = if (showAll) sessions else sessions.filter { it.name in known }

                if (visible.isEmpty()) {
                    listLayout.addView(TextView(this@MainActivity).apply {
                        text = if (sessions.isEmpty()) {
                            "No existing tmux sessions"
                        } else {
                            "No sessions from this app — tap “Show all (${sessions.size})” below"
                        }
                        setTextColor(resources.getColor(R.color.text_secondary, theme))
                        textSize = 14f
                        setPadding(0, 0, 0, dp(8))
                    })
                }

                for (s in visible) {
                    val item = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                        setBackgroundResource(R.drawable.bg_tunnel_item)
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(4) }
                    }
                    val label = TextView(this@MainActivity).apply {
                        text = "${s.name}  —  ${s.statusText}"
                        setTextColor(resources.getColor(R.color.text_white, theme))
                        textSize = 14f
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val killBtn = TextView(this@MainActivity).apply {
                        text = "Kill"
                        setTextColor(resources.getColor(R.color.error, theme))
                        textSize = 13f
                        setPadding(dp(8), dp(4), dp(8), dp(4))
                        setOnClickListener { btn ->
                            // Each kill opens its own SSH session, so a second tap while the first
                            // is in flight would connect twice and race over the same row.
                            btn.isEnabled = false
                            btn.alpha = 0.5f
                            lifecycleScope.launch {
                                val killed = TmuxManager.killSession(
                                    this@MainActivity, server, s.name, hostKeyPrompt()
                                )
                                if (killed) {
                                    // Only now is the row really gone; killSession reports the
                                    // remote exit status, so a refused kill leaves it listed.
                                    sessions.remove(s)
                                    renderList()
                                } else {
                                    btn.isEnabled = true
                                    btn.alpha = 1f
                                    android.widget.Toast.makeText(
                                        this@MainActivity,
                                        "Could not kill ${s.name} — it is still running",
                                        android.widget.Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                        }
                    }
                    item.addView(label)
                    item.addView(killBtn)
                    item.setOnClickListener { attach(s.name) }
                    listLayout.addView(item)
                }

                val hidden = sessions.size - sessions.count { it.name in known }
                // With no known names there is nothing to filter down to, so "Show fewer" could
                // only ever empty the list — offer no control at all rather than one whose single
                // effect is to make the dialog look broken. It reappears on the next visit, once
                // creating or attaching a session has given `known` something in it.
                toggle.visibility = if (known.isNotEmpty() && hidden > 0) View.VISIBLE else View.GONE
                toggle.text = if (showAll) "Show fewer" else "Show all (${sessions.size})"
            }

            toggle.setOnClickListener {
                showAll = !showAll
                renderList()
            }

            layout.addView(newSessionLayout)
            layout.addView(listScroll)
            layout.addView(toggle)

            // Without tmux button
            layout.addView(TextView(this@MainActivity).apply {
                text = "Connect without tmux"
                setTextColor(resources.getColor(R.color.text_secondary, theme))
                textSize = 13f
                setPadding(0, dp(12), 0, dp(8))
                setOnClickListener {
                    dialog.dismiss()
                    doConnectSsh(server.copy(useTmux = false))
                }
            })

            renderList()
            dialog.show()
        }
    }

    private fun doConnectSsh(server: SshServer) {
        currentSshServer = server

        if (server.useMosh) {
            connectMosh(server)
            return
        }

        // Both managers drive the same sshTerminalWebView, and returning to the launcher only
        // suspends a session rather than destroying it. A leftover mosh session would keep
        // writing its own output into this terminal, so it has to go.
        moshSessionManager?.destroy()
        moshSessionManager = null

        val mgr = SshSessionManager(this, sshTerminalWebView,
            onDisconnected = { reason ->
                runOnUiThread {
                    FileLogger.d(TAG, "SSH disconnected: $reason")
                    if (keepAliveEnabled) KeepAliveService.stop(this)
                }
            },
            onHostKeyVerify = { host, port, fingerprint, changed, oldFingerprint, cb ->
                showHostKeyDialog(host, port, fingerprint, changed, oldFingerprint, cb)
            },
            onPassphraseRequired = { srv, retry, cb ->
                showKeyPassphraseDialog(srv, retry, cb)
            }
        )
        sshSessionManager?.destroy()
        sshSessionManager = mgr

        overlayManager.inputTarget = OverlayManager.InputTarget.SSH_TERMINAL
        overlayManager.sshSessionManager = mgr
        overlayManager.sshTerminalWebView = sshTerminalWebView

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

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun connectMosh(unlocked: SshServer) = withKeyPassphrase(unlocked) { server ->
        currentSshServer = server

        // A suspended SSH session from a previous server is still alive and still owns
        // sshTerminalWebView — its shell output and reconnect notices would interleave with this
        // mosh session's, and onResume() would keep force-reconnecting it. Tear it down first.
        sshSessionManager?.destroy()
        sshSessionManager = null

        // Setup terminal WebView (reuse SSH terminal)
        sshTerminalWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
        }
        sshTerminalWebView.setBackgroundColor(0xFF1E1E1E.toInt())
        // Suppress Android's native context menu
        sshTerminalWebView.setOnLongClickListener { true }
        sshTerminalWebView.isLongClickable = false
        sshTerminalWebView.isHapticFeedbackEnabled = false
        SshSessionManager.setupTwoFingerScroll(sshTerminalWebView)

        val mgr = MoshSessionManager(this, sshTerminalWebView) { reason ->
            runOnUiThread {
                FileLogger.d(TAG, "Mosh disconnected: $reason")
                if (reason == "Mosh binary not available") {
                    // Fallback to SSH
                    val sshServer = server.copy(useMosh = false)
                    connectSsh(sshServer)
                    return@runOnUiThread
                }
                if (keepAliveEnabled) KeepAliveService.stop(this)
            }
        }
        moshSessionManager?.destroy()
        moshSessionManager = mgr

        // For Mosh, overlay sends input to mosh process
        overlayManager.inputTarget = OverlayManager.InputTarget.SSH_TERMINAL
        // Create a thin wrapper so overlay can call sendInput on mosh
        overlayManager.sshSessionManager = null
        overlayManager.moshSessionManager = mgr
        overlayManager.sshTerminalWebView = sshTerminalWebView

        // Load terminal HTML first, then connect mosh
        sshTerminalWebView.removeJavascriptInterface("Android")
        sshTerminalWebView.addJavascriptInterface(
            object {
                @android.webkit.JavascriptInterface
                fun onTerminalInput(data: String) { mgr.sendInput(data) }
                @android.webkit.JavascriptInterface
                fun onTerminalReady(cols: Int, rows: Int) {
                    FileLogger.d(TAG, "Mosh terminal ready: ${cols}x$rows")
                    val fontSize = terminalFontSize
                    if (fontSize != 14) {
                        sshTerminalWebView.post {
                            sshTerminalWebView.evaluateJavascript("setFontSize($fontSize)", null)
                        }
                    }
                    mgr.resize(cols, rows)
                }
                @android.webkit.JavascriptInterface
                fun onTerminalResize(cols: Int, rows: Int) { mgr.resize(cols, rows) }
                @android.webkit.JavascriptInterface
                fun copyToClipboard(text: String) {
                    TerminalClipboard.copy(this@MainActivity, text)
                }
                @android.webkit.JavascriptInterface
                fun getClipboard(): String = TerminalClipboard.read(this@MainActivity)
                @android.webkit.JavascriptInterface
                fun osc52ReadAllowed(): Boolean = sshOsc52ClipboardRead
                @android.webkit.JavascriptInterface
                fun haptic() {
                    if (!hapticFeedback) return
                    val vibrator = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                        val vm = getSystemService(VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager
                        vm.defaultVibrator
                    } else {
                        @Suppress("DEPRECATION")
                        getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator
                    }
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        vibrator.vibrate(android.os.VibrationEffect.createOneShot(5, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(5)
                    }
                }
                @android.webkit.JavascriptInterface
                fun exportScrollback(content: String) {}
                @android.webkit.JavascriptInterface
                fun openUrl(url: String) {
                    try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
                    catch (_: Exception) {}
                }
            }, "Android"
        )
        sshTerminalWebView.loadUrl("file:///android_asset/terminal/terminal.html")

        // Delay connect until terminal is loaded
        sshTerminalWebView.postDelayed({
            sshTerminalWebView.evaluateJavascript("window.isMosh = true", null)
            mgr.connect(server, hostKeyPrompt())
        }, 500)

        if (server.snippets.isNotEmpty()) {
            sshTerminalWebView.postDelayed({
                val json = org.json.JSONArray(server.snippets).toString()
                sshTerminalWebView.evaluateJavascript("setSnippets($json)", null)
            }, 600)
        }

        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        geckoContainer.visibility = View.GONE
        sshContainer.visibility = View.VISIBLE
        findViewById<View>(R.id.sftpContainer).visibility = View.GONE
        findViewById<Button>(R.id.floatingToggle).visibility = View.VISIBLE

        if (keepAliveEnabled) {
            KeepAliveService.start(this, "Mosh: ${server.username}@${server.host}")
        }
    }

    private fun disconnectSsh() {
        sshSessionManager?.destroy()
        sshSessionManager = null
        moshSessionManager?.destroy()
        moshSessionManager = null
        currentSshServer = null
        overlayManager.inputTarget = OverlayManager.InputTarget.VSCODE
        overlayManager.sshSessionManager = null
        overlayManager.moshSessionManager = null
        overlayManager.sshTerminalWebView = null
        sftpManager?.destroy()
        sftpManager = null
        if (keepAliveEnabled) {
            KeepAliveService.stop(this)
        }
    }

    // --- SFTP ---
    private var sftpManager: SftpManager? = null
    private var pendingSftpUploadPath: String? = null
    private val sftpUploadLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty() && pendingSftpUploadPath != null) {
            for (uri in uris) {
                val name = getFileNameFromUri(uri)
                sftpManager?.uploadFileFromUri(pendingSftpUploadPath!!, uri, name)
            }
        }
        pendingSftpUploadPath = null
    }

    private fun getFileNameFromUri(uri: Uri): String {
        // Try DISPLAY_NAME from ContentResolver (most reliable)
        try {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        val name = cursor.getString(idx)
                        if (!name.isNullOrBlank()) return name
                    }
                }
            }
        } catch (_: Exception) {}
        // Fallback: extract from path
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    fun launchSftpUpload(remotePath: String) {
        pendingSftpUploadPath = remotePath
        sftpUploadLauncher.launch(arrayOf("*/*"))
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private fun openSftp(unlocked: SshServer) = withKeyPassphrase(unlocked) { server ->
        val sftpWebView = findViewById<WebView>(R.id.sftpWebView)
        val sftpContainer = findViewById<View>(R.id.sftpContainer)

        val mgr = SftpManager(this, sftpWebView)
        sftpManager?.destroy()
        sftpManager = mgr

        sftpWebView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
        }
        sftpWebView.setBackgroundColor(0xFF1E1E1E.toInt())
        sftpWebView.addJavascriptInterface(mgr.SftpBridge(), "Android")
        sftpWebView.loadUrl("file:///android_asset/sftp/sftp.html")

        mgr.connect(server, hostKeyPrompt())

        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        geckoContainer.visibility = View.GONE
        sshContainer.visibility = View.GONE
        findViewById<View>(R.id.sftpContainer).visibility = View.GONE
        sftpContainer.visibility = View.VISIBLE
        findViewById<Button>(R.id.floatingToggle).visibility = View.GONE
    }

    fun closeSftp() {
        sftpManager?.destroy()
        sftpManager = null
        sessionWrapper.visibility = View.GONE
        findViewById<View>(R.id.sftpContainer).visibility = View.GONE
        launcherScroll.visibility = View.VISIBLE
    }

    // --- Settings ---

    private fun showSettingsDialog() {
        // Fullscreen dialog for settings
        val dialog = Dialog(this, android.R.style.Theme_DeviceDefault_NoActionBar)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(resources.getColor(R.color.background, theme))
            fitsSystemWindows = true
        }

        // Top bar with title + save/cancel
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(resources.getColor(R.color.surface, theme))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            elevation = 4f
        }
        val titleTv = TextView(this).apply {
            text = "Settings"
            setTextColor(resources.getColor(R.color.text_white, theme))
            textSize = 20f; setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val cancelBtn = Button(this).apply {
            text = "Cancel"; isAllCaps = false; textSize = 14f
            setTextColor(resources.getColor(R.color.text_secondary, theme))
            background = null
            setOnClickListener { dialog.dismiss() }
        }
        val saveBtn = Button(this).apply {
            text = "Save"; isAllCaps = false; textSize = 14f
            setTextColor(resources.getColor(R.color.primary, theme))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = null
        }
        topBar.addView(titleTv)
        topBar.addView(cancelBtn)
        topBar.addView(saveBtn)
        root.addView(topBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(24))
        }
        scroll.addView(layout)
        root.addView(scroll)

        val colorSec = resources.getColor(R.color.text_secondary, theme)
        val colorPrim = resources.getColor(R.color.text_primary, theme)

        fun section(title: String) {
            layout.addView(TextView(this).apply {
                text = title
                setTextColor(resources.getColor(R.color.primary, theme))
                textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(20); bottomMargin = dp(6) }
            })
            layout.addView(View(this).apply {
                setBackgroundColor(resources.getColor(R.color.divider, theme))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply { bottomMargin = dp(10) }
            })
        }

        fun check(label: String, checked: Boolean): CheckBox {
            return CheckBox(this).apply {
                text = label; isChecked = checked
                setTextColor(colorPrim); textSize = 15f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
        }

        fun field(hint: String, value: String, inputType: Int = android.text.InputType.TYPE_CLASS_TEXT): EditText {
            return EditText(this).apply {
                this.hint = hint; setText(value); this.inputType = inputType
                setTextColor(colorPrim); setHintTextColor(colorSec); textSize = 15f
                background = resources.getDrawable(R.drawable.bg_input, theme)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
        }

        fun label(text: String) {
            layout.addView(TextView(this).apply {
                this.text = text; setTextColor(colorSec); textSize = 12f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(3) }
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
            ).apply { bottomMargin = dp(8) }
        }
        layout.addView(schemeSpinner)
        label("Font size")
        val fontField = field("14", terminalFontSize.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(fontField)
        label("Scrollback lines")
        val scrollbackField = field("10000", terminalScrollback.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(scrollbackField)
        label("VSCode zoom % (80=more content, restart required)")
        val zoomField = field("100", vscodeZoomPercent.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(zoomField)
        label("VSCode language")
        val languages = arrayOf("Auto (system)", "en", "cs", "de", "fr", "es", "zh", "ja", "ko", "ru", "pt")
        val languageValues = arrayOf("", "en", "cs", "de", "fr", "es", "zh", "ja", "ko", "ru", "pt")
        val langSpinner = android.widget.Spinner(this).apply {
            adapter = android.widget.ArrayAdapter(this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item, languages)
            setSelection(languageValues.indexOf(vscodeLanguage).coerceAtLeast(0))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        layout.addView(langSpinner)

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
        label("Compact key height (px, default 82)")
        val compactHeightField = field("82", compactKeyHeight.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(compactHeightField)
        label("Wide/tablet key height (px, default 72)")
        val wideHeightField = field("72", wideKeyHeight.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(wideHeightField)
        label("Touchpad sensitivity % (default 150)")
        val tpSensField = field("150", tpSensitivity.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(tpSensField)
        label("Touchpad scroll speed % (default 20, lower=slower)")
        val tpScrollField = field("20", tpScrollSpeed.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(tpScrollField)
        val invertScrollCheck = check("Invert scroll (natural scrolling)", tpInvertScroll)
        layout.addView(invertScrollCheck)

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
        label("Keepalive interval (seconds, 0 = disabled)")
        val keepaliveField = field("60", sshKeepaliveInterval.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(keepaliveField)
        val osc52ReadCheck = check("Allow remote host to read clipboard (OSC 52)", sshOsc52ClipboardRead)
        layout.addView(osc52ReadCheck)
        label("Off by default: a compromised host can silently read whatever was last copied — passwords, OTPs — with no trace in the terminal.")

        // === SECURITY ===
        section("Security")
        val biometricCheck = check("Biometric lock on app start", biometricLockEnabled)
        layout.addView(biometricCheck)
        val knownHostsBtn = Button(this).apply {
            text = "Known host keys (${KnownHosts.all(this@MainActivity).size})"
            isAllCaps = false; textSize = 14f
            setTextColor(resources.getColor(R.color.text_white, theme))
            setBackgroundColor(resources.getColor(R.color.surface_variant, theme))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) }
            setOnClickListener {
                showKnownHostsDialog {
                    // Pins can have been dropped in there; the count behind it would read stale.
                    text = "Known host keys (${KnownHosts.all(this@MainActivity).size})"
                }
            }
        }
        layout.addView(knownHostsBtn)

        // === BACKGROUND ===
        section("Background")
        val keepAliveCheck = check("Keep alive in background (foreground service)", keepAliveEnabled)
        layout.addView(keepAliveCheck)
        label("VSCode tunnel keepalive interval (seconds, 0 = disabled)")
        val tunnelKeepaliveField = field("30", tunnelKeepaliveInterval.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(tunnelKeepaliveField)

        // === MAINTENANCE ===
        section("Maintenance")
        label("Reload stale VS Code after N min in background (0 = off)")
        val staleRefreshField = field("10", tunnelStaleRefreshMin.toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        layout.addView(staleRefreshField)
        label("Full reset is the fallback if a reload doesn't recover it. It signs you out of " +
            "VS Code and re-downloads the editor (~70 MB), so it isn't done automatically.")
        label("Module diagnostics loads six locally-served test pages that vary one factor each — " +
            "module size, same vs cross origin, a large inline module, and Trusted Types — to find " +
            "which one breaks the editor. Takes about 90 seconds; results go to the log as " +
            "\"CASE n → …\". Nothing is sent anywhere: the pages are served by this app to itself.")
        val diagBtn = Button(this).apply {
            text = "Run module diagnostics (~90 s)"
            isAllCaps = false; textSize = 14f
            setTextColor(resources.getColor(R.color.text_white, theme))
            setBackgroundColor(resources.getColor(R.color.surface_variant, theme))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            layoutParams = lp
            setOnClickListener {
                val url = DiagServer.start()
                if (url == null) {
                    android.widget.Toast.makeText(this@MainActivity,
                        "Could not start the diagnostic server — see the log.",
                        android.widget.Toast.LENGTH_LONG).show()
                } else {
                    isEnabled = false; text = "Running — watch the log"
                    openTunnel(url)
                }
            }
        }
        layout.addView(diagBtn)

        val clearCacheBtn = Button(this).apply {
            text = "Reset VS Code (signs you out)"
            isAllCaps = false; textSize = 14f
            setTextColor(resources.getColor(R.color.text_white, theme))
            setBackgroundColor(resources.getColor(R.color.surface_variant, theme))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(4)
            layoutParams = lp
            setOnClickListener {
                isEnabled = false; text = "Resetting..."
                GeckoManager.clearBrowsingData(this@MainActivity) {
                    runOnUiThread {
                        text = "Reset done"
                        android.widget.Toast.makeText(this@MainActivity,
                            "VS Code reset. Reopen the tunnel — you'll need to sign in again.",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        layout.addView(clearCacheBtn)

        saveBtn.setOnClickListener {
            // Appearance
            terminalColorScheme = schemes[schemeSpinner.selectedItemPosition]
            terminalFontSize = fontField.text.toString().toIntOrNull() ?: 14
            terminalScrollback = scrollbackField.text.toString().toIntOrNull() ?: 10000
            val newZoom = (zoomField.text.toString().toIntOrNull() ?: 100).coerceIn(50, 200)
            if (newZoom != vscodeZoomPercent) {
                vscodeZoomPercent = newZoom
                android.widget.Toast.makeText(this, "Use Exit button to restart app and apply zoom", android.widget.Toast.LENGTH_LONG).show()
            }
            val newLang = languageValues[langSpinner.selectedItemPosition]
            if (newLang != vscodeLanguage) {
                vscodeLanguage = newLang
                GeckoManager.setLocale(newLang)
            }
            // Keyboard
            suppressSystemKeyboard = suppressCheck.isChecked
            overlayManager.alwaysSuppressInput = suppressSystemKeyboard
            overlayManager.syncInputSuppression()
            if (sessionWrapper.visibility == View.VISIBLE && suppressSystemKeyboard) {
                geckoView.suppressIME = true; sysKBSuppressed = true
                WindowInsetsControllerCompat(window, geckoView).hide(WindowInsetsCompat.Type.ime())
            } else if (!suppressSystemKeyboard && !overlayManager.isVisible) {
                geckoView.suppressIME = false; sysKBSuppressed = false
            }
            hapticFeedback = hapticCheck.isChecked
            keyRepeatDelay = repeatDelayField.text.toString().toIntOrNull() ?: 400
            keyRepeatRate = repeatRateField.text.toString().toIntOrNull() ?: 50
            compactKeyHeight = (compactHeightField.text.toString().toIntOrNull() ?: 82).coerceIn(40, 200)
            wideKeyHeight = (wideHeightField.text.toString().toIntOrNull() ?: 72).coerceIn(30, 150)
            tpSensitivity = (tpSensField.text.toString().toIntOrNull() ?: 150).coerceIn(10, 500)
            tpScrollSpeed = (tpScrollField.text.toString().toIntOrNull() ?: 20).coerceIn(1, 1000)
            tpInvertScroll = invertScrollCheck.isChecked
            // SSH
            defaultSshPort = portField.text.toString().toIntOrNull() ?: 22
            defaultSshUser = userField.text.toString().trim()
            defaultStartupCmd = startupField.text.toString().trim()
            sshAutoReconnect = autoReconnectCheck.isChecked
            sshReconnectAttempts = attemptsField.text.toString().toIntOrNull() ?: 3
            sshConnectTimeout = timeoutField.text.toString().toIntOrNull() ?: 15
            sshKeepaliveInterval = (keepaliveField.text.toString().toIntOrNull() ?: 60).coerceIn(0, 600)
            sshOsc52ClipboardRead = osc52ReadCheck.isChecked
            // Security
            biometricLockEnabled = biometricCheck.isChecked
            keepAliveEnabled = keepAliveCheck.isChecked
            tunnelKeepaliveInterval = (tunnelKeepaliveField.text.toString().toIntOrNull() ?: 30).coerceIn(0, 600)
            tunnelStaleRefreshMin = (staleRefreshField.text.toString().toIntOrNull() ?: 10).coerceIn(0, 360)
            overlayManager.syncKeepalive()
            // Push repeat settings
            updateOverlaySettings()
            dialog.dismiss()
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun updateOverlaySettings() {
        val overlayWebView = findViewById<WebView>(R.id.overlayWebView)
        overlayWebView.evaluateJavascript(
            "if(typeof updateRepeatSettings==='function')updateRepeatSettings($keyRepeatDelay,$keyRepeatRate)", null)
        overlayWebView.evaluateJavascript(
            "if(typeof updateKeyHeight==='function')updateKeyHeight($compactKeyHeight,$wideKeyHeight)", null)
        overlayWebView.evaluateJavascript(
            "if(typeof updateTouchpad==='function')updateTouchpad($tpSensitivity,$tpScrollSpeed,${if(tpInvertScroll) "true" else "false"})", null)
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

        // Cold-start staleness check. There is no live page to reload here, so this drops
        // vscode.dev's own document cache before loading — narrowly. It deliberately does NOT use
        // the full reset: that would take the stored sign-in and the service worker's ~72 MB
        // precache with it, and neither can be the problem. Scoping by base domain also spares the
        // workbench bundle automatically, since that is served from main.vscode-cdn.net.
        val thresholdMin = tunnelStaleRefreshMin
        if (thresholdMin > 0 && !coldStartStaleHandled) {
            coldStartStaleHandled = true
            val lastActive = sessionPrefs.getLong(KEY_LAST_TUNNEL_ACTIVE, 0L)
            val stale = lastActive == 0L ||
                (System.currentTimeMillis() - lastActive) >= thresholdMin * 60_000L
            if (stale) {
                val why = if (lastActive == 0L) "no timestamp (fresh install/upgrade)"
                    else "idle ${(System.currentTimeMillis() - lastActive) / 1000}s"
                FileLogger.d(TAG, "Cold-start: refreshing $url document cache ($why)")
                GeckoManager.clearTunnelDocumentCache(this) {
                    runOnUiThread { openTunnel(url) }
                }
                return
            }
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

        // Wrapped so load failures get logged. A delegate is a single slot and this one is ours, so
        // the logging has to be layered on here rather than set in GeckoManager.
        session.navigationDelegate = GeckoManager.withLoadErrorLogging(
        object : GeckoSession.NavigationDelegate {
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
        })

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
        overlayManager.sshTerminalWebView = null

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
        session.autofillDelegate = AutofillBridge(geckoView)
        session.loadUri(url)

        // Save for auto-reconnect after app restart
        saveOpenSessionUrls()
        sessionPrefs.edit().putLong(KEY_LAST_TUNNEL_ACTIVE, System.currentTimeMillis()).apply()

        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        sshContainer.visibility = View.GONE
        findViewById<View>(R.id.sftpContainer).visibility = View.GONE
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

    private fun showFloatingTouchpad() {
        // Hide full overlay if open
        if (overlayManager.isVisible) overlayManager.hide()
        floatingTouchpad.loadSettings()
        floatingTouchpad.updateSize()
        floatingTouchpad.visibility = View.VISIBLE
        floatingTouchpad.post { floatingTouchpad.restorePosition() }
        overlayManager.showCursorOnly()
    }

    private fun hideFloatingTouchpad() {
        floatingTouchpad.visibility = View.GONE
        overlayManager.hideCursorOnly()
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
                popupView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                popupView.setSession(popupSession)
                popupSession.autofillDelegate = AutofillBridge(popupView)

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
        if (floatingTouchpad.visibility == View.VISIBLE) hideFloatingTouchpad()
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
        findViewById<View>(R.id.sftpContainer).visibility = View.GONE
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
        findViewById<View>(R.id.sftpContainer).visibility = View.GONE
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
        overlayManager.sshTerminalWebView = null

        launcherScroll.visibility = View.GONE
        sessionWrapper.visibility = View.VISIBLE
        sshContainer.visibility = View.GONE
        findViewById<View>(R.id.sftpContainer).visibility = View.GONE
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
        if (sshSessionManager?.isConnected != true && moshSessionManager?.isConnected != true) return
        overlayManager.inputTarget = OverlayManager.InputTarget.SSH_TERMINAL
        overlayManager.sshSessionManager = sshSessionManager
        overlayManager.moshSessionManager = moshSessionManager

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
            (sshSessionManager?.isConnected == true && sshContainer.visibility != View.VISIBLE) ||
            (moshSessionManager?.isConnected == true && sshContainer.visibility != View.VISIBLE)

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

        // SSH or Mosh session
        val hasTerminalSession = (sshSessionManager?.isConnected == true || moshSessionManager?.isConnected == true) &&
            sshContainer.visibility != View.VISIBLE
        if (hasTerminalSession) {
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
                val proto = if (moshSessionManager?.isConnected == true) "Mosh" else "SSH"
                val tmuxInfo = if (s?.useTmux == true) " [tmux: ${s.tmuxSessionName.ifBlank { "main" }}]" else ""
                text = "$proto: ${s?.username ?: ""}@${s?.host ?: ""}$tmuxInfo"
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
                val update = TunnelApi.checkUpdate(APP_VERSION) ?: return@launch

                val dlSize = if (update.hasPatch) update.totalPatchSize else update.apkSize
                val sizeStr = formatBytes(dlSize)
                val steps = if (update.hasPatch) " (${update.patchChain.size}x patch $sizeStr)" else " ($sizeStr)"
                val patchInfo = steps
                updateText.text = "Update available: v${update.version}$patchInfo"
                updateLink.setOnClickListener {
                    downloadAndInstallUpdate(update)
                }
                updateBanner.visibility = View.VISIBLE
            } catch (_: Exception) {}
        }
    }

    private fun downloadAndInstallUpdate(update: TunnelApi.UpdateInfo) {
        updateLink.visibility = View.GONE
        updateProgress.visibility = View.VISIBLE
        updateProgress.isIndeterminate = false
        updateProgress.progress = 0

        lifecycleScope.launch {
            try {
                val apkFile = if (update.hasPatch) {
                    try {
                        applyPatchChain(update)
                    } catch (e: Throwable) {
                        FileLogger.e(TAG, "Delta update failed, falling back to full APK: $e")
                        runOnUiThread { updateText.text = "Patch failed, downloading full APK..." }
                        downloadFullApk(update)
                    }
                } else {
                    downloadFullApk(update)
                }

                runOnUiThread {
                    updateText.text = "Installing v${update.version}..."
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

    private suspend fun downloadFullApk(update: TunnelApi.UpdateInfo): java.io.File {
        runOnUiThread { updateText.text = "Downloading v${update.version}..." }
        return downloadFile(update.apkUrl, "update.apk") { progress, dl, total ->
            runOnUiThread {
                if (progress < 0) updateProgress.isIndeterminate = true
                else {
                    updateProgress.isIndeterminate = false; updateProgress.progress = progress
                    updateText.text = "Downloading ${formatBytes(dl)} / ${formatBytes(total)}"
                }
            }
        }
    }

    private suspend fun applyPatchChain(update: TunnelApi.UpdateInfo): java.io.File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val chain = update.patchChain
            val total = chain.size
            var currentApk = java.io.File(applicationInfo.sourceDir)

            for ((idx, step) in chain.withIndex()) {
                // Download patch
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateText.text = "Downloading patch ${idx + 1}/$total (${step.from} → ${step.to})..."
                    updateProgress.isIndeterminate = false
                    updateProgress.progress = 0
                }
                val patchFile = downloadFile(step.url, "patch_${idx}.bspatch") { progress, dl, sz ->
                    runOnUiThread {
                        if (progress < 0) updateProgress.isIndeterminate = true
                        else {
                            updateProgress.isIndeterminate = false
                            updateProgress.progress = progress
                        }
                    }
                }

                // Apply patch
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    updateText.text = "Applying patch ${idx + 1}/$total..."
                    updateProgress.isIndeterminate = true
                }
                val outputApk = java.io.File(cacheDir, "updates/step_${idx}.apk")
                outputApk.parentFile?.mkdirs()
                if (outputApk.exists()) outputApk.delete()

                io.sigpipe.jbsdiff.Patch.patch(
                    currentApk.readBytes(),
                    patchFile.readBytes(),
                    outputApk.outputStream()
                )
                patchFile.delete()

                if (outputApk.length() < 1_000_000) {
                    throw Exception("Patched APK step ${idx + 1} too small (${outputApk.length()} bytes)")
                }
                FileLogger.d(TAG, "Patch step ${idx + 1}/$total: ${currentApk.length()} → ${outputApk.length()} bytes")

                // Clean up previous intermediate file (not the installed APK)
                if (idx > 0) currentApk.delete()
                currentApk = outputApk
            }

            // Rename final result
            val finalApk = java.io.File(cacheDir, "updates/update.apk")
            if (finalApk.exists()) finalApk.delete()
            currentApk.renameTo(finalApk)

            // Verify SHA-256 of final APK
            if (update.apkSha256 != null) {
                val actualHash = sha256(finalApk)
                if (actualHash != update.apkSha256) {
                    FileLogger.e(TAG, "Chain patch hash mismatch: expected=${update.apkSha256}, got=$actualHash")
                    finalApk.delete()
                    throw Exception("Patched APK hash mismatch after ${total} steps")
                }
                FileLogger.d(TAG, "Patch chain hash verified: $actualHash")
            }

            finalApk
        }

    private suspend fun applyBsPatch(patchFile: java.io.File): java.io.File =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val oldApk = java.io.File(applicationInfo.sourceDir)
            val newApk = java.io.File(cacheDir, "updates/update.apk")
            if (newApk.exists()) newApk.delete()
            newApk.parentFile?.mkdirs()

            io.sigpipe.jbsdiff.Patch.patch(
                oldApk.readBytes(),
                patchFile.readBytes(),
                newApk.outputStream()
            )

            if (newApk.length() < 1_000_000) {
                throw Exception("Patched APK too small (${newApk.length()} bytes)")
            }
            newApk
        }

    private suspend fun downloadFile(
        url: String,
        filename: String,
        onProgress: (progress: Int, downloaded: Long, total: Long) -> Unit
    ): java.io.File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val dir = java.io.File(cacheDir, "updates")
        dir.mkdirs()
        val file = java.io.File(dir, filename)
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
                        onProgress(((downloaded * 100) / totalSize).toInt(), downloaded, totalSize)
                    } else {
                        onProgress(-1, downloaded, 0)
                    }
                }
            }
        }
        actualConn.disconnect()

        FileLogger.d(TAG, "Downloaded $filename: ${file.length()} bytes")
        file
    }

    private fun sha256(file: java.io.File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes <= 0 -> "?"
        bytes < 1_048_576 -> "${bytes / 1024} KB"
        else -> "%.1f MB".format(bytes / 1_048_576.0)
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
                if (findViewById<View>(R.id.sftpContainer).visibility == View.VISIBLE) {
                    closeSftp()
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
        // Save session URLs before closing (for auto-reconnect on restart)
        saveOpenSessionUrls()
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

    override fun onStop() {
        // Persist URLs when app goes to background (in case of kill)
        saveOpenSessionUrls()
        val now = System.currentTimeMillis()
        lastBackgroundTimeMs = now
        // Persist for cold-start staleness check after process death
        if (sessionWrapper.visibility == View.VISIBLE && currentTunnelUrl != null) {
            sessionPrefs.edit().putLong(KEY_LAST_TUNNEL_ACTIVE, now).apply()
        }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        maybeAutoRefreshStaleTunnel()
        // Deliberate: this resets the retry counter and force-reconnects, so even a session the
        // user was already told had failed gets one more try. Reopening the app is exactly when
        // someone wants their terminal back — don't "fix" this into a no-op.
        sshSessionManager?.onAppResumed()
    }

    override fun onPause() {
        sshSessionManager?.onAppPaused()
        super.onPause()
    }

    private var lastBackgroundTimeMs: Long = 0L
    private var autoRefreshInFlight = false
    private var coldStartStaleHandled = false

    private fun maybeAutoRefreshStaleTunnel() {
        val thresholdMin = tunnelStaleRefreshMin
        if (thresholdMin <= 0) return
        if (autoRefreshInFlight) return
        if (lastBackgroundTimeMs == 0L) return
        if (sessionWrapper.visibility != View.VISIBLE) return
        if (geckoContainer.visibility != View.VISIBLE) return
        val url = currentTunnelUrl ?: return
        if (currentSessionIdx < 0 || currentSessionIdx >= tunnelSessions.size) return

        val idleMs = System.currentTimeMillis() - lastBackgroundTimeMs
        if (idleMs < thresholdMin * 60_000L) return

        // Reload, don't clear. What actually dies over a long idle is in-page state: VS Code's
        // reconnection logic sets a *static* permanent-failure flag, so every existing connection
        // dies and every new one dies on construction too. A reload gives it a fresh JS context and
        // a fresh reconnection token. Nothing in the cache is stale — the workbench assets are
        // commit-pinned with a one-year max-age, so a new release is a new URL, and the entry
        // document revalidates on its own after 150s. Clearing here used to also destroy the stored
        // sign-in and ~72 MB of service worker precache. See docs/vscode-cache.md.
        if (!hasValidatedNetwork()) {
            // Reloading without a working route is worse than waiting. VS Code decrypts its stored
            // credentials with a key half fetched over the network, and that fetch gives up after
            // ~1.4s — on failure it *deletes* the credentials. Waking from doze often beats the
            // radio, so an eager reload can turn a recoverable hang into a forced re-login.
            FileLogger.d(TAG, "Tunnel idle ${idleMs / 1000}s but no validated network yet — deferring reload")
            return
        }

        FileLogger.d(TAG, "Tunnel idle ${idleMs / 1000}s ≥ ${thresholdMin}min — reloading VS Code session")
        autoRefreshInFlight = true
        lastBackgroundTimeMs = System.currentTimeMillis()
        val idx = currentSessionIdx
        if (idx in tunnelSessions.indices && tunnelSessions[idx].url == url) {
            tunnelSessions[idx].session.reload()
        } else {
            FileLogger.w(TAG, "Session index moved while reloading; skipping")
        }
        autoRefreshInFlight = false
    }

    /**
     * Reload the active tunnel page. The recovery step for a wedged session, per
     * docs/vscode-cache.md — nothing in the cache is stale, what dies is in-page state.
     *
     * Unlike the automatic path this does NOT wait for a validated network: the user asked for it
     * explicitly and is looking at the screen, so refusing silently would be worse than trying and
     * failing. The network state is logged so a failure is still explicable afterwards.
     */
    /** Position on the reload button's escalation ladder — see [reloadCurrentTunnel]. */
    private var reloadRung = 0
    private var lastReloadPressAt = 0L

    private fun reloadCurrentTunnel(reason: String) {
        val idx = currentSessionIdx
        if (idx !in tunnelSessions.indices) {
            FileLogger.w(TAG, "Reload requested ($reason) but no active tunnel session")
            return
        }
        val info = tunnelSessions[idx]
        FileLogger.d(TAG, "Reloading tunnel ($reason): ${info.url} validatedNetwork=${hasValidatedNetwork()}")

        // Ask the page to describe itself first: the reload destroys the broken state, so this is
        // the only moment that evidence exists.
        //
        // This used to wait 5s to clear the content script's own 4.5s probe cap, on the reasoning
        // that a request which never settles was the prime suspect and cutting the probe short would
        // drop the field worth having. That has been overtaken twice. The stall is now pinned to an
        // inline module script that fails outright, not to a hanging request; and the wait had a
        // cost that showed up in a real log — the user backgrounded the app during those 5s, the
        // session changed, and the reload was dropped entirely. A button that does nothing is worse
        // than a slightly thinner snapshot, and the auth probe that matters settles in ~300ms.
        // Repeated presses escalate. Nothing escalates on its own — each rung needs a deliberate tap.
        //
        // The user already presses this button more than once when the workbench is wedged ("2x
        // refresh button to neopravil"), and every one of those presses did the identical thing, so
        // pressing again could never do more than the first press did. Now each press goes one step
        // further, and which step restores the workbench is itself the finding:
        //
        //   1. reload                     — the page's own state
        //   2. drop the CDN asset cache   — the cached bundle. Never once replaced by any automatic
        //                                   path: reloads reuse it and the cold-start clear is scoped
        //                                   to vscode.dev, a different base domain.
        //   3. point at the full reset    — DOM storage, service worker, auth. Not done here: it
        //                                   costs the sign-in, so it stays a deliberate act in
        //                                   Settings rather than something a third tap can trigger.
        //
        // A press well after the previous one is a fresh problem rather than an escalation, so the
        // ladder resets — otherwise a tap tomorrow would inherit today's position.
        val now = System.currentTimeMillis()
        if (now - lastReloadPressAt > RUNG_RESET_MS) reloadRung = 0
        lastReloadPressAt = now
        val rung = reloadRung
        reloadRung = (reloadRung + 1).coerceAtMost(2)
        FileLogger.w(TAG, "Recovery rung ${rung + 1}/3 ($reason)")

        // Ask the page to describe itself first: the reload destroys the broken state, so this is
        // the only moment that evidence exists.
        //
        // This used to wait 5s to clear the content script's own 4.5s probe cap, on the reasoning
        // that a request which never settles was the prime suspect and cutting the probe short would
        // drop the field worth having. That has been overtaken twice. The stall is now pinned to an
        // inline module script that fails outright, not to a hanging request; and the wait had a
        // cost that showed up in a real log — the user backgrounded the app during those 5s, the
        // session changed, and the reload was dropped entirely. A button that does nothing is worse
        // than a slightly thinner snapshot, and the auth probe that matters settles in ~300ms.
        overlayManager.requestDiag("beforeReload")
        geckoView.postDelayed({
            val stillIdx = currentSessionIdx
            if (stillIdx !in tunnelSessions.indices || tunnelSessions[stillIdx].url != info.url) {
                FileLogger.w(TAG, "Session changed while capturing diag; reload skipped")
                return@postDelayed
            }
            when (rung) {
                0 -> tunnelSessions[stillIdx].session.reload()
                1 -> GeckoManager.clearCdnAssetCache(this) {
                    runOnUiThread {
                        val i = currentSessionIdx
                        if (i in tunnelSessions.indices && tunnelSessions[i].url == info.url) {
                            tunnelSessions[i].session.reload()
                        }
                        android.widget.Toast.makeText(
                            this, "Cleared cached VS Code assets — reloading",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                else -> {
                    tunnelSessions[stillIdx].session.reload()
                    android.widget.Toast.makeText(
                        this,
                        "Still stuck. Next step: Settings → \"Reset VS Code\" (signs you out).",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
        }, 1500)
    }

    /**
     * Whether there is a route that actually carries traffic, not merely an interface that exists.
     *
     * `onAvailable` fires before validation completes, and a reload issued in that window can cost
     * the user their stored sign-in (see the note at the call site), so this asks for
     * NET_CAPABILITY_VALIDATED specifically.
     */
    private fun hasValidatedNetwork(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
            val openAction = {
                // Remove this URL from saved list, keep others
                val remaining = urls.filter { it != capturedUrl && it.isNotBlank() }
                if (remaining.isEmpty()) {
                    sessionPrefs.edit().remove(KEY_LAST_URL).apply()
                } else {
                    sessionPrefs.edit().putString(KEY_LAST_URL, org.json.JSONArray(remaining).toString()).apply()
                }
                connectTo(capturedUrl)
                // Refresh the reconnect list (remove opened item)
                item.visibility = View.GONE
                if (remaining.isEmpty()) activeSessionsSection.visibility = View.GONE
            }
            openBtn.setOnClickListener { openAction() }
            item.setOnClickListener { openAction() }
            dismissBtn.setOnClickListener {
                // Remove just this URL
                val remaining = urls.filter { it != capturedUrl && it.isNotBlank() }
                if (remaining.isEmpty()) {
                    sessionPrefs.edit().remove(KEY_LAST_URL).apply()
                    activeSessionsSection.visibility = View.GONE
                } else {
                    sessionPrefs.edit().putString(KEY_LAST_URL, org.json.JSONArray(remaining).toString()).apply()
                }
                item.visibility = View.GONE
            }

            item.addView(tv)
            item.addView(openBtn)
            item.addView(dismissBtn)
            activeSessionList.addView(item)
        }
    }

    // --- Biometric ---

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val executor = androidx.core.content.ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(this, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                    finish()
                }
            }
            override fun onAuthenticationFailed() {}
        })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("VS Code Tunnel")
            .setSubtitle("Authenticate to access the app")
            .setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
            .build()
        prompt.authenticate(info)
    }

    // --- Utils ---

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
