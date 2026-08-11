package com.vscodetunnel.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build

/**
 * Sets up and drives a local editor: code-server running in Termux on the phone itself.
 *
 * Split into two steps for a reason that is Termux's, not a shortcut of mine. Termux ignores commands
 * from other apps until `allow-external-apps=true` is present in `~/.termux/termux.properties`, and it
 * explicitly forbids external apps from writing that file (termux-app commit e302a14c). So exactly one
 * line has to be pasted by hand — [ENABLE_SCRIPT] — and everything after it can be automated.
 *
 * Getting the result back matters as much as sending the command. Without a PendingIntent the failure
 * that actually happens — Termux installed, permission granted, `allow-external-apps` still missing —
 * is invisible from this side: the service accepts the intent and silently drops it, so "no exception"
 * would have been reported as success. Termux returns stdout, stderr and the exit code through the
 * pending intent it is handed, which turns that into a definite answer.
 *
 * Kept general on purpose: [runInTermux] takes any command, so anything else worth handing to a real
 * shell on the phone later — a git clone, a build, a one-off script — needs no new plumbing.
 *
 * Why the backend is not bundled into this APK instead: code-server requires Node 22 and ships 24,
 * while nodejs-mobile, the in-process route that would have avoided Android's restrictions on
 * executing binaries, is still on 18.20.4 from October 2024. Shipping a Node binary as a native
 * library does work, but it costs 150-250 MB on top of ~100 MB, breaks the delta-patch updater, and
 * makes this app responsible for Node's security updates. Termux already does all of that.
 */
object TermuxSetup {
    private const val TAG = "TermuxSetup"
    const val PACKAGE = "com.termux"
    private const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
    private const val BASH = "/data/data/com.termux/files/usr/bin/bash"

    /** Result bundle keys, taken from Termux's own TermuxConstants rather than guessed. */
    private const val EXTRA_RESULT_BUNDLE = "result"
    private const val RESULT_STDOUT = "stdout"
    private const val RESULT_STDERR = "stderr"
    private const val RESULT_EXIT_CODE = "exitCode"
    private const val RESULT_ERRMSG = "errmsg"

    private const val ACTION_RESULT = "com.vscodetunnel.app.TERMUX_RESULT"

    /** Where a local code-server listens, and what goes in the editor profile. */
    const val LOCAL_URL = "http://127.0.0.1:8080"

    /**
     * The one thing that cannot be automated, kept to a single line so pasting it is trivial.
     *
     * `termux-reload-settings` is the part that is easy to leave out and then wonder why nothing works:
     * without it Termux carries on with the properties it read at startup, so the permission appears to
     * have been granted and every intent is still discarded until Termux is restarted.
     */
    val ENABLE_SCRIPT =
        "mkdir -p ~/.termux && echo 'allow-external-apps=true' >> ~/.termux/termux.properties && " +
            "termux-reload-settings && echo OK"

    /**
     * Everything else, sent by the app once the line above has been pasted.
     *
     * Password authentication stays on. `--auth none` is tempting for something bound to loopback, but
     * every app on the phone can reach 127.0.0.1, so it would hand the editor — and the filesystem
     * behind it — to anything else installed. The last line prints the generated password for the
     * saved editor profile.
     */
    val INSTALL_SCRIPT = """
        set -e
        pkg update -y && pkg upgrade -y
        pkg install -y nodejs-lts git python build-essential
        npm install --global --unsafe-perm code-server
        mkdir -p ~/.config/code-server
        code-server --bind-addr 127.0.0.1:8080 &
        sleep 4
        echo '--- put this password and $LOCAL_URL into Settings, "Save editor profile" ---'
        cat ~/.config/code-server/config.yaml
    """.trimIndent()

    /** Whether Termux is present. Needs the <queries> entry in the manifest to work at all. */
    fun isInstalled(context: Context): Boolean =
        try {
            context.packageManager.getPackageInfo(PACKAGE, 0)
            true
        } catch (_: Throwable) {
            false
        }

    /**
     * Runs [command] in Termux and reports what came back.
     *
     * [onResult] receives a short human-readable outcome. It fires on Termux's reply when there is one,
     * and otherwise on a timeout — because the interesting failure produces no reply at all, and a
     * callback that only fires on success would leave the caller unable to say anything truthful.
     *
     * [background] false shows a real session, which is right for a long install the user should watch;
     * true keeps it invisible, which is right for a probe.
     */
    fun runInTermux(
        context: Context,
        command: String,
        label: String,
        background: Boolean = false,
        timeoutMs: Long = 20_000,
        onResult: ((String) -> Unit)? = null,
    ) {
        if (!isInstalled(context)) {
            onResult?.invoke("Termux is not installed")
            return
        }
        val app = context.applicationContext
        val token = ACTION_RESULT + "." + System.nanoTime()
        var settled = false

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (settled) return
                settled = true
                try { app.unregisterReceiver(this) } catch (_: Throwable) {}
                val bundle = intent?.getBundleExtra(EXTRA_RESULT_BUNDLE)
                val exit = bundle?.getInt(RESULT_EXIT_CODE, -1) ?: -1
                val err = bundle?.getString(RESULT_ERRMSG)
                val out = (bundle?.getString(RESULT_STDOUT) ?: "").trim()
                val stderr = (bundle?.getString(RESULT_STDERR) ?: "").trim()
                // Length only for stdout in the log: a session transcript can contain anything the
                // user typed, and this one prints a password by design.
                FileLogger.d(TAG, "[$label] exit=$exit stdout=${out.length}ch " +
                    "stderr=${stderr.length}ch${err?.let { " err=$it" } ?: ""}")
                onResult?.invoke(
                    when {
                        err != null -> "Termux reported: $err"
                        exit == 0 -> "Finished successfully"
                        else -> "Finished with exit code $exit"
                    }
                )
            }
        }

        val filter = IntentFilter(token)
        if (Build.VERSION.SDK_INT >= 33) {
            // Delivery is via our own PendingIntent, so this must not be exported.
            app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            app.registerReceiver(receiver, filter)
        }

        val pending = PendingIntent.getBroadcast(
            app, 0, Intent(token).setPackage(app.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

        try {
            val intent = Intent().apply {
                setClassName(PACKAGE, RUN_COMMAND_SERVICE)
                action = ACTION_RUN_COMMAND
                putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-lc", command))
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
                putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", label)
                putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending)
            }
            context.startService(intent)
            FileLogger.d(TAG, "[$label] handed to Termux (${command.length} chars)")
        } catch (t: Throwable) {
            settled = true
            try { app.unregisterReceiver(receiver) } catch (_: Throwable) {}
            FileLogger.w(TAG, "[$label] Termux refused the intent: ${t.message}")
            onResult?.invoke("Termux refused it: ${t.javaClass.simpleName}")
            return
        }

        // No reply is the diagnosis, not an absence of one: it is what "allow-external-apps is still
        // not set" looks like from outside, so it has to be reported rather than waited on forever.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (settled) return@postDelayed
            settled = true
            try { app.unregisterReceiver(receiver) } catch (_: Throwable) {}
            FileLogger.w(TAG, "[$label] no reply from Termux within ${timeoutMs}ms")
            onResult?.invoke("No reply from Termux — allow-external-apps is probably not set yet")
        }, timeoutMs)
    }

    /** Sends a trivial command to find out whether the intent route works at all. */
    fun probe(context: Context, onResult: (String) -> Unit) =
        runInTermux(context, "echo ready", "probe", background = true, timeoutMs = 8_000, onResult = onResult)

    /** Brings Termux to the front so a copied line can be pasted into it. */
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
