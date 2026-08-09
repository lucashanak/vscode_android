package com.vscodetunnel.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)

        // Gecko's internal messages — the only place a failed module load is reported — go to
        // logcat, which nothing was reading. One reader in the main process covers every process.
        if (FileLogger.isMain) LogcatBridge.start()

        // Catch uncaught exceptions to log
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLogger.e("CRASH", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
