package io.github.dovecoteescapee.byedpi.obhod

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device diagnostics for a phone we cannot attach adb to.
 *
 * Two jobs:
 *  - [mark] drops a breadcrumb before every risky step, so if the process is
 *    killed (RemoteServiceException, ANR, native abort) the *next* launch can
 *    show exactly which stage it died in.
 *  - [installCrashHandler] records Java/Kotlin uncaught exceptions — the
 *    RemoteServiceException from a missed startForeground() shows up here.
 *
 * Everything is best-effort: a diagnostic that throws is worse than none.
 */
object Diag {
    private const val TAG = "Diag"
    private const val RUN_LOG = "last-run.log"
    private const val CRASH_LOG = "last-crash.log"

    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var sessionStarted = false

    private fun runFile(context: Context) = File(context.filesDir, RUN_LOG)

    private fun crashFile(context: Context) = File(context.filesDir, CRASH_LOG)

    /** Start a fresh breadcrumb log for this process. */
    @Synchronized
    fun startSession(context: Context) {
        if (sessionStarted) return
        sessionStarted = true
        try {
            runFile(context).writeText(
                "# Ласточка ${io.github.dovecoteescapee.byedpi.BuildConfig.VERSION_NAME}\n",
            )
        } catch (e: Throwable) {
            Log.w(TAG, "startSession", e)
        }
    }

    /** Append one line. Never throws. */
    @Synchronized
    fun mark(context: Context?, stage: String) {
        if (context == null) return
        val line = "${stamp.format(Date())}  $stage"
        Log.i(TAG, line)
        try {
            runFile(context).appendText(line + "\n")
        } catch (_: Throwable) {
            // storage full / no permission — ignore
        }
    }

    fun runLog(context: Context): String = try {
        runFile(context).takeIf { it.exists() }?.readText()?.trim().orEmpty()
    } catch (_: Throwable) {
        ""
    }

    fun crashLog(context: Context): String = try {
        crashFile(context).takeIf { it.exists() }?.readText()?.trim().orEmpty()
    } catch (_: Throwable) {
        ""
    }

    fun clear(context: Context) {
        try {
            crashFile(context).delete()
        } catch (_: Throwable) {
        }
    }

    /**
     * Records the fatal exception, then hands it to the previous handler so the
     * platform still shows "приложение остановлено". Without the hand-off the
     * process would hang in a half-dead state.
     */
    fun installCrashHandler(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    appendLine("# ${stamp.format(Date())}  ${thread.name}")
                    appendLine(error.toString())
                    appendLine()
                    appendLine(error.stackTraceToString())
                }
                crashFile(appContext).writeText(text.take(20_000))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
