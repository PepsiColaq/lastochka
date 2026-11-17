package io.github.dovecoteescapee.byedpi.tgws

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import io.github.dovecoteescapee.byedpi.core.NativeEnv
import io.github.dovecoteescapee.byedpi.obhod.CfProxyDomains
import io.github.dovecoteescapee.byedpi.obhod.HostListManager
import io.github.dovecoteescapee.byedpi.obhod.StrategyPresets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Local MTProto→WebSocket bridge on 127.0.0.1:1082.
 *
 * Transport chain (in order of preference, all decided by the native lib):
 *  1. Cloudflare front  — kws1.<domain>. Needs the domain to resolve *and*
 *     the bridge's own TLS to survive the DPI. The bridge is therefore run
 *     with ALL_PROXY pointing at our ByeDPI SOCKS, so its ClientHello gets
 *     desynced like everything else. Without that the CF path was dialled
 *     direct and the RU DPI reset the handshake.
 *  2. Direct to a DC IP — only the few IPs that survive the blocklist work
 *     (`149.154.167.220`, `91.105.192.100` at the time of writing).
 *
 * Important: do NOT TCP-probe :1082 while Telegram is connected — a bare
 * Socket connect looks like a client, then EOF mid-handshake, and the native
 * pool starts dropping real TG sessions (ping OK, chats dead, no push).
 */
object TgWsBridge {
    private const val TAG = "TgWsBridge"
    const val PORT = 1082
    private const val PREF_SECRET = "tg_ws_secret_key"
    private const val PREF_APPLIED = "tg_ws_proxy_applied"
    private const val WATCH_MS = 8_000L
    private const val PORT_WAIT_MS = 15_000L
    private const val CF_CACHE_MAX_AGE_MS = 6L * 60L * 60L * 1000L

    /**
     * DC redirects. Telegram blocks most DC subnets on RU mobile networks;
     * these two answered at diagnosis time. Exposed as a constant so the UI
     * can show what is actually being used.
     */
    val DEFAULT_DC_IPS = "2:149.154.167.220,4:149.154.167.220,203:91.105.192.100"

    data class ApplyResult(val ok: Boolean, val message: String, val uri: Uri? = null)

    /** Parsed native stats. `null` fields mean the key was absent. */
    data class BridgeStats(
        val active: Int = 0,
        val ws: Int = 0,
        val tcpFallback: Int = 0,
        val cf: Int = 0,
        val bad: Int = 0,
        val err: Int = 0,
        val pool: Int = 0,
        val raw: String = "",
    ) {
        /** Any proven transport: a live WS session, a CF dial or a direct relay. */
        val hasTransport: Boolean get() = ws > 0 || cf > 0 || tcpFallback > 0

        val summary: String get() = buildString {
            append("ws=").append(ws)
            append(" cf=").append(cf)
            if (tcpFallback > 0) append(" dc=").append(tcpFallback)
            if (bad > 0) append(" bad=").append(bad)
            if (err > 0) append(" err=").append(err)
        }
    }

    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    var lastStatus: String = "выкл"
        private set

    /** Wall clock of the last successful start, for honest "up for N s" text. */
    @Volatile
    var startedAtMillis: Long = 0L
        private set

    @Volatile
    var lastStats: BridgeStats? = null
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

    private fun socksPort(prefs: SharedPreferences): Int =
        prefs.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080

    suspend fun start(context: Context, prefs: SharedPreferences): Boolean = startMutex.withLock {
        if (!isEnabled(prefs)) {
            lastStatus = "выкл в настройках"
            Log.i(TAG, "TG WS disabled in prefs")
            return false
        }
        if (running && nativeAlive()) {
            lastStatus = statusFromStats()
            return true
        }

        lastStatus = "запуск…"
        try {
            NativeTgWs.stopProxy()
        } catch (_: Throwable) {
        }
        running = false
        startedAtMillis = 0L
        delay(200)

        val secret = ensureSecret(prefs)
        val port = socksPort(prefs)
        val socksUp = tcpProbeLocal(port)

        // Decode + verify the CF fronts. Feeding the encoded names into the
        // desync list was the original bug; an empty pool means the CF route
        // is closed and we must not waste time on it.
        CfProxyDomains.seedIfMissing(context)
        refreshCfCacheIfStale(context, port)
        val userDomain = prefs.getString(StrategyPresets.PREF_CF_USER_DOMAIN, "") ?: ""
        val liveCf = HostListManager.liveCfProxyDomains(context, userDomain)

        try {
            // Modest pool — large pools + CF blips caused sticky dead sessions.
            NativeTgWs.setPoolSize(8)
            NativeTgWs.setCfProxyCacheDir(context.cacheDir.absolutePath)
            // Only prefer CF when we actually have a live front.
            NativeTgWs.setCfProxyConfig(
                enabled = liveCf.isNotEmpty(),
                priority = liveCf.isNotEmpty(),
                userDomain = userDomain,
            )
            // Route the bridge's own HTTPS/TLS through ByeDPI so its
            // ClientHello is desynced. This is what makes the CF path work
            // on RU DPI; without it the handshake is reset.
            if (socksUp) {
                NativeEnv.setSocksProxy(port)
            } else {
                NativeEnv.clearSocksProxy()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "native config failed", e)
            lastStatus = "ошибка native"
            return false
        }

        Log.i(
            TAG,
            "start cfLive=${liveCf.size} socks=$socksUp dcIps=${DEFAULT_DC_IPS.take(24)}…",
        )

        val codeRef = AtomicInteger(Int.MIN_VALUE)
        val errRef = AtomicReference<Throwable?>(null)
        val starter = thread(name = "tgws-start", isDaemon = true) {
            try {
                codeRef.set(
                    NativeTgWs.startProxy("127.0.0.1", PORT, DEFAULT_DC_IPS, secret, 0),
                )
            } catch (e: Throwable) {
                errRef.set(e)
                codeRef.set(-1)
            }
        }

        var up = false
        val deadline = System.currentTimeMillis() + PORT_WAIT_MS
        var probed = false
        while (System.currentTimeMillis() < deadline) {
            // Prefer stats (no handshake disturbance). One TCP probe only if stats still empty.
            if (nativeAlive()) {
                up = true
                break
            }
            val code = codeRef.get()
            if (code != Int.MIN_VALUE && code != 0 && !starter.isAlive) {
                Log.e(TAG, "StartProxy failed code=$code err=${errRef.get()}")
                lastStatus = "StartProxy=$code"
                return false
            }
            if (code == 0 && !probed) {
                probed = true
                if (tcpProbePort()) {
                    up = true
                    break
                }
            }
            delay(120)
        }

        if (!up) {
            Log.e(TAG, "TG WS not ready after ${PORT_WAIT_MS}ms")
            try {
                NativeTgWs.stopProxy()
            } catch (_: Throwable) {
            }
            NativeEnv.clearSocksProxy()
            lastStatus = "порт не открылся"
            return false
        }

        lastPrefixedSecret = try {
            NativeTgWs.getSecretWithPrefix()
        } catch (_: Throwable) {
            null
        } ?: "dd$secret"
        running = true
        startedAtMillis = System.currentTimeMillis()
        lastStats = readStats()
        lastStatus = statusFromStats()
        Log.i(
            TAG,
            "TG WS up cf=${liveCf.size} socks=$socksUp status=$lastStatus " +
                "secret=${lastPrefixedSecret?.take(4)}…",
        )
        true
    }

    fun startWatchdog(scope: CoroutineScope, context: Context, prefs: SharedPreferences) {
        watchJob?.cancel()
        watchJob = scope.launch(Dispatchers.IO) {
            var fails = 0
            while (isActive && isEnabled(prefs)) {
                delay(WATCH_MS)
                if (!running) continue

                // Never raw-TCP-probe here — that breaks live TG sessions
                val stats = readStats()
                lastStats = stats
                if (stats != null) {
                    fails = 0
                    lastStatus = statusFromStats()
                    continue
                }

                fails++
                Log.w(TAG, "TG WS native not alive (fail=$fails)")
                lastStatus = "нет ответа ($fails)"
                // Need several misses — brief GC/JNI hiccups happen
                if (fails < 3) continue

                Log.w(TAG, "TG WS restarting after $fails failed health checks")
                lastStatus = "рестарт…"
                running = false
                val ok = start(context, prefs)
                fails = if (ok) 0 else fails
                if (!ok) {
                    lastStatus = "не удалось поднять"
                    delay(10_000)
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
        if (!running && !nativeAlive()) {
            lastStatus = "выкл"
            NativeEnv.clearSocksProxy()
            return
        }
        try {
            NativeTgWs.stopProxy()
        } catch (e: Throwable) {
            Log.w(TAG, "TG WS stop", e)
        }
        NativeEnv.clearSocksProxy()
        running = false
        startedAtMillis = 0L
        lastStats = null
        lastStatus = "выкл"
        Log.i(TAG, "TG WS stopped")
    }

    fun isPortOpen(): Boolean = nativeAlive() || tcpProbePort()

    fun proxyUri(prefs: SharedPreferences): Uri {
        val secret = lastPrefixedSecret
            ?: try {
                NativeTgWs.getSecretWithPrefix()
            } catch (_: Throwable) {
                null
            }
            ?: "dd${ensureSecret(prefs)}"
        return Uri.parse("tg://proxy?server=127.0.0.1&port=$PORT&secret=$secret")
    }

    suspend fun prepareApplyInTelegram(
        context: Context,
        prefs: SharedPreferences,
        force: Boolean = false,
    ): ApplyResult {
        if (!isEnabled(prefs)) {
            return ApplyResult(false, "Прокси Telegram выключен в настройках")
        }
        if (!running || !nativeAlive()) {
            val ok = start(context, prefs)
            if (!ok) {
                return ApplyResult(
                    false,
                    "Мост не запустился ($lastStatus). Выкл/вкл обход и снова",
                )
            }
        }
        // Refuse to hand Telegram a URI pointing at a bridge that has no
        // working transport — that is exactly the "ping ok, never connects"
        // state users used to end up in.
        val stats = readStats()
        if (stats != null && !stats.hasTransport && (stats.err > 0 || stats.bad > 0)) {
            return ApplyResult(
                false,
                "Прокси поднят, но соединение с Telegram не проходит (${stats.summary}). " +
                    "Попробуйте другую стратегию или включите «Telegram через туннель».",
            )
        }
        val uri = proxyUri(prefs)
        prefs.edit().putBoolean(PREF_APPLIED, true).apply()
        return ApplyResult(true, "Открываем Telegram… ($lastStatus)", uri)
    }

    fun openInTelegram(context: Context, uri: Uri): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cannot open tg://proxy — install Telegram?", e)
            false
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

    // ---------------------------------------------------------------- health

    /**
     * Real liveness: JNI stats must parse **and** the bridge must still be
     * flagged running. The old version treated any non-null string as alive,
     * so a dead bridge reported "ок" forever.
     */
    private fun nativeAlive(): Boolean = readStats() != null

    private fun readStats(): BridgeStats? {
        val raw = try {
            NativeTgWs.getStats()
        } catch (_: Throwable) {
            null
        } ?: return null
        return parseStats(raw)
    }

    internal fun parseStats(raw: String): BridgeStats {
        val map = HashMap<String, Int>(8)
        for (token in raw.split(' ', '\t', '\n', ',')) {
            val idx = token.indexOf('=')
            if (idx <= 0) continue
            val key = token.substring(0, idx)
            val value = token.substring(idx + 1).toIntOrNull() ?: continue
            map[key] = value
        }
        return BridgeStats(
            active = map["active"] ?: 0,
            ws = map["ws"] ?: 0,
            tcpFallback = map["tcp_fb"] ?: 0,
            cf = map["cf"] ?: 0,
            bad = map["bad"] ?: 0,
            err = map["err"] ?: 0,
            pool = map["pool"] ?: 0,
            raw = raw.trim(),
        )
    }

    private fun statusFromStats(): String {
        val s = lastStats ?: readStats() ?: return "ок :$PORT"
        lastStats = s
        return if (s.hasTransport) "ок · ${s.summary}" else "ждёт маршрут · ${s.summary}"
    }

    /** Only for startup confirmation — not for periodic watchdog. */
    private fun tcpProbePort(): Boolean = tcpProbeLocal(PORT)

    private fun tcpProbeLocal(port: Int): Boolean = try {
        Socket().use { s ->
            s.soTimeout = 300
            s.connect(InetSocketAddress("127.0.0.1", port), 300)
            true
        }
    } catch (_: Exception) {
        false
    }

    // -------------------------------------------------------------- cf cache

    private fun refreshCfCacheIfStale(context: Context, port: Int) {
        val cache = File(context.cacheDir, "cfproxy-domains-cache.txt")
        val fresh = cache.exists() &&
            cache.length() > 32 &&
            System.currentTimeMillis() - cache.lastModified() < CF_CACHE_MAX_AGE_MS
        if (fresh) return
        CoroutineScope(Dispatchers.IO).launch {
            CfProxyDomains.refresh(context, port)
        }
    }

    // ----------------------------------------------------------- diagnostics

    /**
     * One-line, human readable verdict for the UI. Runs DNS checks, so call
     * it from a background dispatcher.
     */
    fun diagnose(context: Context, prefs: SharedPreferences): List<String> {
        val out = mutableListOf<String>()
        val userDomain = prefs.getString(StrategyPresets.PREF_CF_USER_DOMAIN, "") ?: ""
        val all = CfProxyDomains.allDomains(context, CfProxyDomains.userDomains(userDomain))
        val live = HostListManager.liveCfProxyDomains(context, userDomain)
        out += if (live.isEmpty()) {
            "Cloudflare-фронты: 0/${all.size} — путь закрыт, идём напрямую"
        } else {
            "Cloudflare-фронты: ${live.size}/${all.size} живых (${live.first()})"
        }

        val socksUp = tcpProbeLocal(socksPort(prefs))
        out += if (socksUp) "ByeDPI SOCKS: :${socksPort(prefs)} работает" else "ByeDPI SOCKS: не отвечает"

        val stats = readStats()
        out += when {
            !running -> "Мост Telegram: выключен"
            stats == null -> "Мост Telegram: не отвечает"
            stats.hasTransport -> "Мост Telegram: ${stats.summary}"
            else -> "Мост Telegram: поднят, но маршрута нет (${stats.summary})"
        }
        out += "DC-редиректы: $DEFAULT_DC_IPS"
        return out
    }
}
