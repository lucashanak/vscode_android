package com.vscodetunnel.app

import android.content.Context
import android.content.Intent

/**
 * Sets up a *local* editor: code-server running in Termux on the phone itself.
 *
 * Worth being clear about why this is a script and a couple of buttons rather than an installer.
 * Bundling the backend into this APK was considered and rejected: it needs a Node binary (code-server
 * requires Node 22 and ships 24, while nodejs-mobile — the in-process route — is still on 18.20.4 from
 * October 2024), which means shipping ~150-250 MB on top of the current ~100 MB and taking on
 * responsibility for Node's security updates. Termux already solves all of that and keeps Node
 * patched. The app's job is to make its setup painless, not to duplicate it.
 *
 * The one thing that cannot be automated, and it is not for want of trying: Termux only accepts
 * commands from another app when `allow-external-apps=true` is present in
 * `~/.termux/termux.properties`, and Termux deliberately forbids external apps from writing that file.
 * So [runInTermux] can never be the whole story on a fresh install, and the copyable script — which
 * works with no permissions, no intents and no cooperation from Termux — is the primary path. The
 * script's first line enables the intent route for afterwards.
 */
object TermuxSetup {
    private const val TAG = "TermuxSetup"
    const val PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** Where a local code-server will listen, and what to enter as the editor profile URL. */
    const val LOCAL_URL = "http://127.0.0.1:8080"

    /**
     * The whole setup, as one paste.
     *
     * Password authentication is left ON deliberately. `--auth none` is tempting for something bound
     * to loopback, but every app on the phone can reach 127.0.0.1, so it would expose the editor —
     * and with it the filesystem — to anything else installed. code-server generates a password on
     * first run; the last line prints it, and it goes in Settings → editor profile so the login is
     * filled automatically.
     */
    val INSTALL_SCRIPT = """
        # 1. Let this app hand commands to Termux later (optional, one time).
        #    Termux will not let an external app write this file, which is why it is here and not
        #    done for you.
        mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties

        # 2. Packages. build-essential and python are needed by code-server's native dependencies.
        pkg update -y && pkg upgrade -y
        pkg install -y nodejs-lts git python build-essential

        # 3. code-server itself.
        npm install --global --unsafe-perm code-server

        # 4. Start it on loopback only.
        code-server --bind-addr 127.0.0.1:8080 &

        # 5. Print the generated password, then put it and $LOCAL_URL into
        #    Settings -> "Save editor profile" so the login fills itself.
        sleep 3 && cat ~/.config/code-server/config.yaml
    """.trimIndent()

    /** Whether Termux is present. Requires the <queries> entry in the manifest to work at all. */
    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Asks Termux to run [command] in a visible session.
     *
     * Returns a description of what happened rather than a bare boolean, because the interesting
     * failure — Termux installed, permission granted, but `allow-external-apps` still absent — is
     * silent from this side: the service accepts the intent and Termux discards it. So a caller that
     * only knew "no exception" would report success for a command that never ran.
     */
    fun runInTermux(context: Context, command: String): String {
        if (!isInstalled(context)) return "termux-not-installed"
        return try {
            val intent = Intent().apply {
                setClassName(PACKAGE, RUN_COMMAND_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", false)
                // "0" keeps the session in the foreground so the user sees the output — and any
                // failure — instead of it happening invisibly.
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }
            context.startService(intent)
            FileLogger.d(TAG, "Handed a command to Termux (${command.length} chars)")
            "sent"
        } catch (t: Throwable) {
            FileLogger.w(TAG, "Termux refused the command: ${t.message}")
            "refused:${t.javaClass.simpleName}"
        }
    }

    /** Brings Termux to the front so a copied script can be pasted into it. */
    fun openTermux(context: Context): Boolean =
        try {
            val launch = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            if (launch == null) false else {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(launch)
                true
            }
        } catch (t: Throwable) {
            FileLogger.w(TAG, "Could not open Termux: ${t.message}")
            false
        }
}
