package io.github.dovecoteescapee.byedpi.obhod

import android.app.Application
import android.content.Context
import android.util.Log
import io.github.dovecoteescapee.byedpi.BuildConfig

/**
 * Process entry point. Exists only to install the crash recorder and open a
 * breadcrumb session as early as possible: a crash caused by the foreground
 * service (auto-start, quick tile) never goes through MainActivity, so
 * without this the user would have no evidence at all.
 */
class LastochkaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            Diag.installCrashHandler(this)
            Diag.startSession(this)
            Diag.mark(this, "app created ${BuildConfig.VERSION_NAME}")
        } catch (e: Throwable) {
            Log.w("LastochkaApp", "diagnostics init failed", e)
        }
    }

    companion object {
        /** Crash/run log, safe to call from anywhere. */
        fun report(context: Context): String {
            val crash = Diag.crashLog(context)
            val run = Diag.runLog(context)
            return buildString {
                if (crash.isNotBlank()) {
                    appendLine("Прошлый сбой:")
                    appendLine(crash.lineSequence().take(14).joinToString("\n"))
                    appendLine()
                }
                appendLine("Журнал запуска:")
                append(run.lineSequence().toList().takeLast(14).joinToString("\n"))
            }.trim()
        }
    }
}
