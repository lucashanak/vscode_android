package com.vscodetunnel.app

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS_NAME = "app_settings"

    private const val KEY_SUPPRESS_SYSKB = "suppress_system_keyboard"
    private const val KEY_KEEPALIVE = "keepalive_enabled"
    private const val KEY_HAPTIC = "haptic_feedback"
    private const val KEY_FONT_SIZE = "terminal_font_size"
    private const val KEY_COLOR_SCHEME = "terminal_color_scheme"
    private const val KEY_SCROLLBACK = "terminal_scrollback"
    private const val KEY_VSCODE_ZOOM = "vscode_zoom_percent"
    private const val KEY_VSCODE_LANGUAGE = "vscode_language"
    private const val KEY_REPEAT_DELAY = "key_repeat_delay"
    private const val KEY_REPEAT_RATE = "key_repeat_rate"
    private const val KEY_DEFAULT_SSH_PORT = "default_ssh_port"
    private const val KEY_DEFAULT_SSH_USER = "default_ssh_user"
    private const val KEY_DEFAULT_STARTUP_CMD = "default_startup_cmd"
    private const val KEY_AUTO_RECONNECT = "ssh_auto_reconnect"
    private const val KEY_RECONNECT_ATTEMPTS = "ssh_reconnect_attempts"
    private const val KEY_CONNECT_TIMEOUT = "ssh_connect_timeout"
    private const val KEY_COMPACT_KEY_HEIGHT = "compact_key_height"
    private const val KEY_WIDE_KEY_HEIGHT = "wide_key_height"
    private const val KEY_TP_SENSITIVITY = "tp_sensitivity"
    private const val KEY_TP_SCROLL_SPEED = "tp_scroll_speed"
    private const val KEY_TP_INVERT_SCROLL = "tp_invert_scroll"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- Keyboard ---
    var Context.suppressSystemKeyboard: Boolean
        get() = prefs(this).getBoolean(KEY_SUPPRESS_SYSKB, true)
        set(value) = prefs(this).edit().putBoolean(KEY_SUPPRESS_SYSKB, value).apply()

    var Context.hapticFeedback: Boolean
        get() = prefs(this).getBoolean(KEY_HAPTIC, false)
        set(value) = prefs(this).edit().putBoolean(KEY_HAPTIC, value).apply()

    var Context.keyRepeatDelay: Int
        get() = prefs(this).getInt(KEY_REPEAT_DELAY, 400)
        set(value) = prefs(this).edit().putInt(KEY_REPEAT_DELAY, value).apply()

    var Context.keyRepeatRate: Int
        get() = prefs(this).getInt(KEY_REPEAT_RATE, 50)
        set(value) = prefs(this).edit().putInt(KEY_REPEAT_RATE, value).apply()

    var Context.compactKeyHeight: Int
        get() = prefs(this).getInt(KEY_COMPACT_KEY_HEIGHT, 82)
        set(value) = prefs(this).edit().putInt(KEY_COMPACT_KEY_HEIGHT, value).apply()

    var Context.wideKeyHeight: Int
        get() = prefs(this).getInt(KEY_WIDE_KEY_HEIGHT, 72)
        set(value) = prefs(this).edit().putInt(KEY_WIDE_KEY_HEIGHT, value).apply()

    var Context.tpSensitivity: Int
        get() = prefs(this).getInt(KEY_TP_SENSITIVITY, 150)
        set(value) = prefs(this).edit().putInt(KEY_TP_SENSITIVITY, value).apply()

    var Context.tpScrollSpeed: Int
        get() = prefs(this).getInt(KEY_TP_SCROLL_SPEED, 20)
        set(value) = prefs(this).edit().putInt(KEY_TP_SCROLL_SPEED, value).apply()

    var Context.tpInvertScroll: Boolean
        get() = prefs(this).getBoolean(KEY_TP_INVERT_SCROLL, true)
        set(value) = prefs(this).edit().putBoolean(KEY_TP_INVERT_SCROLL, value).apply()

    // --- Appearance ---
    var Context.terminalFontSize: Int
        get() = prefs(this).getInt(KEY_FONT_SIZE, 14)
        set(value) = prefs(this).edit().putInt(KEY_FONT_SIZE, value).apply()

    var Context.terminalColorScheme: String
        get() = prefs(this).getString(KEY_COLOR_SCHEME, "default") ?: "default"
        set(value) = prefs(this).edit().putString(KEY_COLOR_SCHEME, value).apply()

    var Context.terminalScrollback: Int
        get() = prefs(this).getInt(KEY_SCROLLBACK, 10000)
        set(value) = prefs(this).edit().putInt(KEY_SCROLLBACK, value).apply()

    var Context.vscodeZoomPercent: Int
        get() = prefs(this).getInt(KEY_VSCODE_ZOOM, 100)
        set(value) = prefs(this).edit().putInt(KEY_VSCODE_ZOOM, value).apply()

    var Context.vscodeLanguage: String
        get() = prefs(this).getString(KEY_VSCODE_LANGUAGE, "") ?: ""
        set(value) = prefs(this).edit().putString(KEY_VSCODE_LANGUAGE, value).apply()

    // --- SSH Defaults ---
    var Context.defaultSshPort: Int
        get() = prefs(this).getInt(KEY_DEFAULT_SSH_PORT, 22)
        set(value) = prefs(this).edit().putInt(KEY_DEFAULT_SSH_PORT, value).apply()

    var Context.defaultSshUser: String
        get() = prefs(this).getString(KEY_DEFAULT_SSH_USER, "") ?: ""
        set(value) = prefs(this).edit().putString(KEY_DEFAULT_SSH_USER, value).apply()

    var Context.defaultStartupCmd: String
        get() = prefs(this).getString(KEY_DEFAULT_STARTUP_CMD, "") ?: ""
        set(value) = prefs(this).edit().putString(KEY_DEFAULT_STARTUP_CMD, value).apply()

    var Context.sshAutoReconnect: Boolean
        get() = prefs(this).getBoolean(KEY_AUTO_RECONNECT, true)
        set(value) = prefs(this).edit().putBoolean(KEY_AUTO_RECONNECT, value).apply()

    var Context.sshReconnectAttempts: Int
        get() = prefs(this).getInt(KEY_RECONNECT_ATTEMPTS, 3)
        set(value) = prefs(this).edit().putInt(KEY_RECONNECT_ATTEMPTS, value).apply()

    var Context.sshConnectTimeout: Int
        get() = prefs(this).getInt(KEY_CONNECT_TIMEOUT, 15)
        set(value) = prefs(this).edit().putInt(KEY_CONNECT_TIMEOUT, value).apply()

    // --- Security ---
    private const val KEY_BIOMETRIC = "biometric_lock"

    var Context.biometricLockEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_BIOMETRIC, false)
        set(value) = prefs(this).edit().putBoolean(KEY_BIOMETRIC, value).apply()

    // --- Background ---
    var Context.keepAliveEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_KEEPALIVE, true)
        set(value) = prefs(this).edit().putBoolean(KEY_KEEPALIVE, value).apply()
}
