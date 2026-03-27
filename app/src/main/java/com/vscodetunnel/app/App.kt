package com.vscodetunnel.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)

        // Catch uncaught exceptions to log
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            FileLogger.e("CRASH", "Uncaught exception on ${thread.name}", throwable)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
