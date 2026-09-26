package io.github.dovecoteescapee.byedpi.tgws

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import io.github.dovecoteescapee.byedpi.obhod.StrategyPresets
import java.security.SecureRandom

/**
 * Starts local MTProto→WebSocket bridge on 127.0.0.1:1082.
 * Telegram apps are excluded from the VPN and pointed at this proxy via tg://proxy.
 */
object TgWsBridge {
    private const val TAG = "TgWsBridge"
    const val PORT = 1082
    private const val PREF_SECRET = "tg_ws_secret_key"
    private const val PREF_APPLIED = "tg_ws_proxy_applied"

    @Volatile
    var running: Boolean = false
        private set

    private var lastPrefixedSecret: String? = null

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

    @Synchronized
    fun start(context: Context, prefs: SharedPreferences): Boolean {
        if (!isEnabled(prefs)) {
            Log.i(TAG, "TG WS disabled in prefs")
            return false
        }
        if (running) return true
        return try {
            val secret = ensureSecret(prefs)
            NativeTgWs.setPoolSize(4)
            NativeTgWs.setCfProxyCacheDir(context.cacheDir.absolutePath)
            NativeTgWs.setCfProxyConfig(enabled = true, priority = true, userDomain = "")
            val code = NativeTgWs.startProxy("127.0.0.1", PORT, "", secret, 1)
            if (code != 0) {
                Log.e(TAG, "StartProxy failed code=$code")
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

    @Synchronized
    fun stop() {
        if (!running) return
        try {
            NativeTgWs.stopProxy()
        } catch (e: Throwable) {
            Log.w(TAG, "TG WS stop", e)
        }
        running = false
        Log.i(TAG, "TG WS stopped")
    }

    fun proxyUri(prefs: SharedPreferences): Uri {
        val secret = lastPrefixedSecret
            ?: NativeTgWs.getSecretWithPrefix()
            ?: "dd${ensureSecret(prefs)}"
        return Uri.parse("tg://proxy?server=127.0.0.1&port=$PORT&secret=$secret")
    }

    /** Opens Telegram proxy settings only when forced (manual button). Never auto. */
    fun offerApplyInTelegram(context: Context, prefs: SharedPreferences, force: Boolean = false) {
        if (!isEnabled(prefs) || !running) return
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
