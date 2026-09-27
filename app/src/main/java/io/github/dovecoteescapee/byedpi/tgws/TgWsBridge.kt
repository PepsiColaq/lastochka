package io.github.dovecoteescapee.byedpi.tgws

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import io.github.dovecoteescapee.byedpi.obhod.StrategyPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Starts local MTProto→WebSocket bridge on 127.0.0.1:1082.
 * Telegram apps are excluded from the VPN and pointed at this proxy via tg://proxy.
 *
 * Watchdog restarts the native proxy if the listen port dies (common CF blip).
 */
object TgWsBridge {
    private const val TAG = "TgWsBridge"
    const val PORT = 1082
    private const val PREF_SECRET = "tg_ws_secret_key"
    private const val PREF_APPLIED = "tg_ws_proxy_applied"
    private const val WATCH_MS = 4_000L

    @Volatile
    var running: Boolean = false
        private set

    private var lastPrefixedSecret: String? = null
    private var watchJob: Job? = null
    private val startMutex = Mutex()

    val telegramPackages = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "org.thunderdog.challegram",
        "tw.nekomimi.nekogram",
        "xyz.nextalone.nagram",
        "com.exteragram.messenger",
        "org.telegram.messenger.beta",
    )

    fun isEnabled(prefs: SharedPreferences): Boolean =
        prefs.getBoolean(StrategyPresets.PREF_TG_WS, true)

    fun ensureSecret(prefs: SharedPreferences): String {
        val existing = prefs.getString(PREF_SECRET, "")?.trim().orEmpty()
        if (existing.length == 32 && existing.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            return existing
        }
        val generated = ByteArray(16).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.edit().putString(PREF_SECRET, generated).commit()
        return generated
    }

    suspend fun start(context: Context, prefs: SharedPreferences): Boolean = startMutex.withLock {
        if (!isEnabled(prefs)) {
            Log.i(TAG, "TG WS disabled in prefs")
            return false
        }
        if (running && isPortOpen()) return true
        return try {
            if (running) {
                // Stale flag — native died
                try {
                    NativeTgWs.stopProxy()
                } catch (_: Throwable) {
                }
                running = false
            }
            val secret = ensureSecret(prefs)
            NativeTgWs.setPoolSize(8)
            NativeTgWs.setCfProxyCacheDir(context.cacheDir.absolutePath)
            NativeTgWs.setCfProxyConfig(enabled = true, priority = true, userDomain = "")
            val code = NativeTgWs.startProxy("127.0.0.1", PORT, "", secret, 0)
            if (code != 0) {
                Log.e(TAG, "StartProxy failed code=$code")
                running = false
                return false
            }
            // Give native a moment to bind
            delay(200)
            if (!isPortOpen()) {
                Log.e(TAG, "StartProxy ok but port $PORT not listening")
                running = false
                return false
            }
            lastPrefixedSecret = NativeTgWs.getSecretWithPrefix() ?: "dd$secret"
            running = true
            Log.i(TAG, "TG WS listening on 127.0.0.1:$PORT")
            true
        } catch (e: Throwable) {
            Log.e(TAG, "TG WS start failed", e)
            running = false
            false
        }
    }

    fun startWatchdog(scope: CoroutineScope, context: Context, prefs: SharedPreferences) {
        watchJob?.cancel()
        watchJob = scope.launch(Dispatchers.IO) {
            var fails = 0
            while (isActive && isEnabled(prefs)) {
                delay(WATCH_MS)
                if (!running) continue
                if (isPortOpen()) {
                    fails = 0
                    continue
                }
                fails++
                Log.w(TAG, "TG WS port down (fail=$fails) — restarting")
                running = false
                val ok = start(context, prefs)
                if (ok) {
                    fails = 0
                    Log.i(TAG, "TG WS auto-restarted")
                } else if (fails >= 3) {
                    delay(8_000)
                }
            }
        }
    }

    fun stopWatchdog() {
        watchJob?.cancel()
        watchJob = null
    }

    fun stop() {
        stopWatchdog()
        if (!running && !isPortOpen()) return
        try {
            NativeTgWs.stopProxy()
        } catch (e: Throwable) {
            Log.w(TAG, "TG WS stop", e)
        }
        running = false
        Log.i(TAG, "TG WS stopped")
    }

    fun isPortOpen(): Boolean = try {
        Socket().use { s ->
            s.soTimeout = 300
            s.connect(InetSocketAddress("127.0.0.1", PORT), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    fun proxyUri(prefs: SharedPreferences): Uri {
        val secret = lastPrefixedSecret
            ?: NativeTgWs.getSecretWithPrefix()
            ?: "dd${ensureSecret(prefs)}"
        return Uri.parse("tg://proxy?server=127.0.0.1&port=$PORT&secret=$secret")
    }

    /** Opens Telegram proxy settings only when forced (manual button). Never auto. */
    fun offerApplyInTelegram(context: Context, prefs: SharedPreferences, force: Boolean = false) {
        if (!isEnabled(prefs)) return
        if (!running && !isPortOpen()) return
        if (!force && prefs.getBoolean(PREF_APPLIED, false)) {
            Log.i(TAG, "TG proxy already applied — skip auto-open")
            return
        }
        try {
            val intent = Intent(Intent.ACTION_VIEW, proxyUri(prefs)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            prefs.edit().putBoolean(PREF_APPLIED, true).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open tg://proxy — install Telegram?", e)
        }
    }

    fun installedTelegramPackages(context: Context): List<String> {
        val pm = context.packageManager
        return telegramPackages.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (_: PackageManager.NameNotFoundException) {
                false
            }
        }
    }
}
