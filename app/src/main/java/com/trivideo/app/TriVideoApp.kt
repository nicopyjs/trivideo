package com.trivideo.app

import android.app.Application
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registra cualquier crash en un archivo para poder revisarlo despues sin logcat
 * (Ajustes > Almacenamiento > Android/data/com.trivideo.app/files/crash_log.txt,
 * o conectando el celular por USB en modo MTP).
 */
class TriVideoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                logCrash(throwable)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logCrash(throwable: Throwable) {
        val dir = getExternalFilesDir(null) ?: return
        val stackTrace = StringWriter().also { throwable.printStackTrace(PrintWriter(it)) }.toString()
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        File(dir, "crash_log.txt").appendText("\n[$timestamp]\n$stackTrace\n")
    }
}
