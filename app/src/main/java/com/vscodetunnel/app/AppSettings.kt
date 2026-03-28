package com.vscodetunnel.app

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val PREFS_NAME = "app_settings"

    // Keys
    private const val KEY_SUPPRESS_SYSKB = "suppress_system_keyboard"
    private const val KEY_KEEPALIVE = "keepalive_enabled"
    private const val KEY_HAPTIC = "haptic_feedback"
    private const val KEY_FONT_SIZE = "terminal_font_size"
    private const val KEY_DEFAULT_SSH_PORT = "default_ssh_port"
    private const val KEY_DEFAULT_SSH_USER = "default_ssh_user"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var Context.suppressSystemKeyboard: Boolean
        get() = prefs(this).getBoolean(KEY_SUPPRESS_SYSKB, true)
        set(value) = prefs(this).edit().putBoolean(KEY_SUPPRESS_SYSKB, value).apply()

    var Context.keepAliveEnabled: Boolean
        get() = prefs(this).getBoolean(KEY_KEEPALIVE, true)
        set(value) = prefs(this).edit().putBoolean(KEY_KEEPALIVE, value).apply()

    var Context.hapticFeedback: Boolean
        get() = prefs(this).getBoolean(KEY_HAPTIC, true)
        set(value) = prefs(this).edit().putBoolean(KEY_HAPTIC, value).apply()

    var Context.terminalFontSize: Int
        get() = prefs(this).getInt(KEY_FONT_SIZE, 14)
        set(value) = prefs(this).edit().putInt(KEY_FONT_SIZE, value).apply()

    var Context.defaultSshPort: Int
        get() = prefs(this).getInt(KEY_DEFAULT_SSH_PORT, 22)
        set(value) = prefs(this).edit().putInt(KEY_DEFAULT_SSH_PORT, value).apply()

    var Context.defaultSshUser: String
        get() = prefs(this).getString(KEY_DEFAULT_SSH_USER, "") ?: ""
        set(value) = prefs(this).edit().putString(KEY_DEFAULT_SSH_USER, value).apply()
}
