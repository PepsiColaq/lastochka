package io.github.dovecoteescapee.byedpi.services

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxy
import io.github.dovecoteescapee.byedpi.core.ByeDpiProxyPreferences
import io.github.dovecoteescapee.byedpi.core.TProxyService
import io.github.dovecoteescapee.byedpi.data.*
import io.github.dovecoteescapee.byedpi.obhod.CfProxyDomains
import io.github.dovecoteescapee.byedpi.obhod.Diag
import io.github.dovecoteescapee.byedpi.obhod.HostListManager
import io.github.dovecoteescapee.byedpi.obhod.StrategyPresets
import io.github.dovecoteescapee.byedpi.utility.*
import io.github.dovecoteescapee.byedpi.tgws.TgWsBridge
import io.github.dovecoteescapee.byedpi.obhod.BypassApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class ByeDpiVpnService : LifecycleVpnService() {
    private val byeDpiProxy = ByeDpiProxy()
    private var proxyJob: Job? = null
    private var tunFd: ParcelFileDescriptor? = null
    private val mutex = Mutex()
    private var stopping: Boolean = false
    private var starting: Boolean = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    companion object {
        private val TAG: String = ByeDpiVpnService::class.java.simpleName
        private const val FOREGROUND_SERVICE_ID: Int = 1
        private const val NOTIFICATION_CHANNEL_ID: String = "ByeDPIVpn"

        private var status: ServiceStatus = ServiceStatus.Disconnected
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.vpn_channel_name,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return when (val action = intent?.action) {
            START_ACTION, RESUME_ACTION -> {
                // Android may deliver several start intents in a row (quick
                // tile, widget, auto-start). Two concurrent start() bodies
                // raced on the mutex and the loser threw "Proxy fields not
                // null", which used to take the whole process down.
                if (!starting) {
                    starting = true
                    lifecycleScope.launch {
                        try {
                            start()
                        } catch (e: Throwable) {
                            Log.e(TAG, "start() crashed", e)
                            safelyMarkFailed()
                        } finally {
                            starting = false
                        }
                    }
                }
                START_STICKY
            }

            PAUSE_ACTION -> {
                lifecycleScope.launch {
                    try {
                        pause()
                    } catch (e: Throwable) {
                        Log.e(TAG, "pause() crashed", e)
                    }
                }
                START_STICKY
            }

            STOP_ACTION -> {
                lifecycleScope.launch {
                    try {
                        stop()
                    } catch (e: Throwable) {
                        Log.e(TAG, "stop() crashed", e)
                    }
                }
                START_NOT_STICKY
            }

            else -> {
                Log.w(TAG, "Unknown action: $action")
                START_NOT_STICKY
            }
        }
    }

    /**
     * Best-effort "give up" path. If the exception happened before
     * startForeground(), Android would otherwise kill the process with
     * RemoteServiceException; stopping ourselves first is the clean exit.
     */
    private suspend fun safelyMarkFailed() {
        try {
            startForeground()
        } catch (_: Throwable) {
        }
        try {
            updateStatus(ServiceStatus.Failed)
        } catch (_: Throwable) {
        }
        try {
            stopSelf()
        } catch (_: Throwable) {
        }
    }

    override fun onRevoke() {
        Log.i(TAG, "VPN revoked")
        lifecycleScope.launch { stop() }
    }

    private suspend fun start() {
        Log.i(TAG, "Starting")
        Diag.startSession(this)
        Diag.mark(this, "service.start begin status=$status")

        if (status == ServiceStatus.Connected) {
            Log.w(TAG, "VPN already connected")
            Diag.mark(this, "service.start skipped: already connected")
            return
        }

        // CRITICAL: startForeground() must happen within ~5 s of
        // startForegroundService(), otherwise Android kills the whole process
        // with RemoteServiceException — the user sees "app crashes on launch".
        // 1.0.7 called it *after* awaitSocksReady() (up to 5 s), so this is
        // done first, unconditionally, before any slow work.
        try {
            startForeground()
            Diag.mark(this, "service.start foreground ok")
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed", e)
            Diag.mark(this, "service.start foreground FAILED: $e")
        }

        try {
            // Proxy and TUN setup block; keep them off the main thread.
            Diag.mark(this, "service.start proxy+tun begin")
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    startProxy()
                    Diag.mark(this@ByeDpiVpnService, "service.start proxy up")
                    // hev-socks5-tunnel needs ByeDPI listening. Without this wait
                    // the TUN could come up before :1080 existed and the first
                    // connections went nowhere.
                    awaitSocksReady()
                    startTun2Socks()
                    Diag.mark(this@ByeDpiVpnService, "service.start tun up")
                }
            }
            // TG WS + watchdog (CF blips used to leave Telegram on a dead proxy)
            val prefs = getPreferences()
            if (TgWsBridge.isEnabled(prefs) && !tgInTunnel(prefs)) {
                Diag.mark(this, "service.start tg bridge begin")
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val ok = TgWsBridge.start(this@ByeDpiVpnService, prefs)
                        Log.i(TAG, "TG WS start result=$ok running=${TgWsBridge.running}")
                        if (ok) {
                            TgWsBridge.startWatchdog(lifecycleScope, this@ByeDpiVpnService, prefs)
                        }
                    } catch (e: Throwable) {
                        // The bridge is a nice-to-have: never let it fail the VPN.
                        Log.e(TAG, "TG WS bridge crashed", e)
                    }
                }
            } else if (tgInTunnel(prefs)) {
                Log.i(TAG, "Telegram routed through the tunnel (WS bridge off)")
                TgWsBridge.stop()
            }
            updateStatus(ServiceStatus.Connected)
            startForeground()
            registerNetworkCallback()
            Diag.mark(this, "service.start done: connected")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start VPN", e)
            Diag.mark(this, "service.start FAILED: ${e.javaClass.simpleName}: ${e.message}")
            updateStatus(ServiceStatus.Failed)
            stop()
        }
    }

    /** Poll until the local ByeDPI SOCKS accepts, or give up after 5 s. */
    private suspend fun awaitSocksReady() {
        val port = getPreferences().getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val ok = try {
                Socket().use { s ->
                    s.soTimeout = 300
                    s.connect(InetSocketAddress("127.0.0.1", port), 300)
                    true
                }
            } catch (_: Exception) {
                false
            }
            if (ok) {
                Log.i(TAG, "SOCKS :$port ready")
                return
            }
            delay(100)
        }
        Log.w(TAG, "SOCKS :$port not ready after 5s, starting TUN anyway")
    }

    private fun tgInTunnel(prefs: android.content.SharedPreferences): Boolean =
        prefs.getBoolean(StrategyPresets.PREF_TG_IN_TUNNEL, false)

    /**
     * Wi-Fi ↔ LTE changes the DPI profile. Re-applying the strategy and
     * restarting the bridge keeps the app working on both, instead of
     * silently degrading until the user toggles the switch.
     */
    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (status != ServiceStatus.Connected) return
                    val prefs = getPreferences()
                    if (!prefs.getBoolean(StrategyPresets.PREF_AUTO_NETWORK, false)) return
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            CfProxyDomains.invalidate()
                            val rec = StrategyPresets.recommendForNetwork(this@ByeDpiVpnService)
                            val hosts = HostListManager.buildWhitelist(this@ByeDpiVpnService)
                            StrategyPresets.applyToPreferences(
                                prefs, rec, hosts, this@ByeDpiVpnService,
                            )
                            Log.i(TAG, "network change -> strategy ${rec.id}; restarting")
                            mutex.withLock {
                                stopTun2Socks()
                                stopProxy()
                            }
                            delay(300)
                            mutex.withLock {
                                startProxy()
                                awaitSocksReady()
                                startTun2Socks()
                            }
                            if (TgWsBridge.isEnabled(prefs) && !tgInTunnel(prefs)) {
                                TgWsBridge.stop()
                                val ok = TgWsBridge.start(this@ByeDpiVpnService, prefs)
                                if (ok) {
                                    TgWsBridge.startWatchdog(
                                        lifecycleScope, this@ByeDpiVpnService, prefs,
                                    )
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "network change restart failed", e)
                        }
                    }
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "registerDefaultNetworkCallback failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val cb = networkCallback ?: return
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        }
        networkCallback = null
    }

    private suspend fun pause() {
        Log.i(TAG, "Pausing")
        if (status != ServiceStatus.Connected) {
            Log.w(TAG, "Pause ignored, status=$status")
            return
        }
        mutex.withLock {
            stopping = true
            try {
                stopTun2Socks()
                stopProxy()
                TgWsBridge.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pause VPN", e)
            } finally {
                stopping = false
            }
        }
        updateStatus(ServiceStatus.Paused)
        startForeground()
    }

    private fun startForeground() {
        val notification: Notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                FOREGROUND_SERVICE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(FOREGROUND_SERVICE_ID, notification)
        }
    }

    private suspend fun stop() {
        Log.i(TAG, "Stopping")

        unregisterNetworkCallback()
        mutex.withLock {
            stopping = true
            try {
                stopTun2Socks()
                stopProxy()
                TgWsBridge.stop()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to stop VPN", e)
            } finally {
                stopping = false
            }
        }

        updateStatus(ServiceStatus.Disconnected)
        stopSelf()
    }

    private suspend fun startProxy() {
        Log.i(TAG, "Starting proxy")

        if (proxyJob != null) {
            Log.w(TAG, "Proxy fields not null")
            throw IllegalStateException("Proxy fields not null")
        }

        val preferences = getByeDpiPreferences()

        proxyJob = lifecycleScope.launch(Dispatchers.IO) {
            val startedAt = System.currentTimeMillis()
            val code = try {
                byeDpiProxy.startProxy(preferences)
            } catch (e: Throwable) {
                Log.e(TAG, "proxy threw", e)
                -1
            }
            val aliveMs = System.currentTimeMillis() - startedAt

            // startProxy only returns when the proxy exits. The old code called
            // stop() right here, which joins this very job (self-join → ANR /
            // frozen service). Do the teardown on a separate coroutine.
            Log.i(TAG, "Proxy loop exited code=$code after ${aliveMs}ms")
            Diag.mark(this@ByeDpiVpnService, "proxy exited code=$code after ${aliveMs}ms")
            if (stopping) return@launch

            // Dying within a couple of seconds means it never worked at all:
            // bad -H list, port already taken, unparsable args. Reporting
            // "выключено" here made the app look like it refused to switch on.
            val neverWorked = code != 0 && aliveMs < 3_000
            lifecycleScope.launch {
                if (neverWorked) {
                    Log.e(TAG, "proxy never came up (code=$code)")
                    updateStatus(ServiceStatus.Failed)
                } else {
                    updateStatus(ServiceStatus.Disconnected)
                }
                stop()
            }
        }

        Log.i(TAG, "Proxy started")
    }

    private suspend fun stopProxy() {
        Log.i(TAG, "Stopping proxy")

        try {
            byeDpiProxy.stopProxy()
        } catch (e: Throwable) {
            Log.w(TAG, "stopProxy threw", e)
        }

        // Never join our own job: if stop() was invoked from the proxy job's
        // own coroutine (which is what happens when the proxy loop exits), the
        // join would wait for itself and hang the service until ANR.
        val job = proxyJob
        val self = coroutineContext[Job]
        if (job != null && job !== self && job.isActive) {
            val finished = withTimeoutOrNull(2_000) { job.join() }
            if (finished == null) {
                Log.w(TAG, "proxyJob did not finish in 2s, cancelling")
                job.cancel()
            }
        }
        proxyJob = null

        Log.i(TAG, "Proxy stopped")
    }

    private fun startTun2Socks() {
        Log.i(TAG, "Starting tun2socks")

        if (tunFd != null) {
            throw IllegalStateException("VPN field not null")
        }

        val sharedPreferences = getPreferences()
        val port = sharedPreferences.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080
        // Fake-IP DNS inside hev (mapdns) — no UDP DNS through SOCKS/ByeDPI.
        // Some networks choke on fake-IP; dns_mode=system falls back to a real
        // resolver so the user is not stuck with a broken tunnel.
        val useMapDns = sharedPreferences.getBoolean("obhod_dns_mapdns", true)
        val dns = if (useMapDns) {
            "198.18.0.2"
        } else {
            sharedPreferences.getString("dns_ip", "1.1.1.1")?.ifBlank { "1.1.1.1" } ?: "1.1.1.1"
        }
        val ipv6 = sharedPreferences.getBoolean("ipv6_enable", false)

        val tun2socksConfig = buildString {
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("socks5:")
            appendLine("  address: 127.0.0.1")
            appendLine("  port: $port")
            appendLine("  udp: udp")
            if (useMapDns) {
                appendLine("mapdns:")
                appendLine("  address: 198.18.0.2")
                appendLine("  port: 53")
                appendLine("  network: 100.64.0.0")
                appendLine("  netmask: 255.192.0.0")
                appendLine("  cache-size: 10000")
            }
        }

        val configPath = try {
            File.createTempFile("config", "tmp", cacheDir).apply {
                writeText(tun2socksConfig)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create config file", e)
            throw e
        }

        val fd = createBuilder(dns, ipv6, useMapDns).establish()
            ?: throw IllegalStateException("VPN connection failed")

        this.tunFd = fd

        val started = TProxyService.TProxyStartService(configPath.absolutePath, fd.fd)
        if (!started) {
            throw IllegalStateException("hev-socks5-tunnel failed to start")
        }

        Log.i(TAG, "Tun2Socks started (dns=$dns mapdns=$useMapDns)")
    }

    private fun stopTun2Socks() {
        Log.i(TAG, "Stopping tun2socks")

        TProxyService.TProxyStopService()

        try {
            File(cacheDir, "config.tmp").delete()
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to delete config file", e)
        }

        tunFd?.close() ?: Log.w(TAG, "VPN not running")
        tunFd = null

        Log.i(TAG, "Tun2socks stopped")
    }

    private fun getByeDpiPreferences(): ByeDpiProxyPreferences =
        ByeDpiProxyPreferences.fromSharedPreferences(getPreferences())

    private fun updateStatus(newStatus: ServiceStatus) {
        Log.d(TAG, "VPN status changed from $status to $newStatus")

        status = newStatus

        setStatus(
            when (newStatus) {
                ServiceStatus.Connected -> AppStatus.Running
                ServiceStatus.Paused -> AppStatus.Paused

                ServiceStatus.Disconnected,
                ServiceStatus.Failed -> {
                    proxyJob = null
                    AppStatus.Halted
                }
            },
            Mode.VPN
        )

        val intent = Intent(
            when (newStatus) {
                ServiceStatus.Connected -> STARTED_BROADCAST
                ServiceStatus.Paused -> STARTED_BROADCAST
                ServiceStatus.Disconnected -> STOPPED_BROADCAST
                ServiceStatus.Failed -> FAILED_BROADCAST
            }
        )
        intent.putExtra(SENDER, Sender.VPN.ordinal)
        sendBroadcast(intent)
    }

    private fun createNotification(): Notification =
        createConnectionNotification(
            this,
            NOTIFICATION_CHANNEL_ID,
            R.string.notification_title,
            if (status == ServiceStatus.Paused) {
                R.string.vpn_notification_paused
            } else {
                R.string.vpn_notification_content
            },
            ByeDpiVpnService::class.java,
            paused = status == ServiceStatus.Paused,
        )

    private fun createBuilder(dns: String, ipv6: Boolean, mapDns: Boolean = true): Builder {
        Log.d(TAG, "DNS: $dns (mapDns=$mapDns)")
        val builder = Builder()
        builder.setSession("Ласточка")
        builder.setConfigureIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
        )

        builder.addAddress("10.10.10.10", 32)
            .addRoute("0.0.0.0", 0)
            .setMtu(1500)

        if (ipv6) {
            builder.addAddress("fd00::1", 128)
                .addRoute("::", 0)
        }

        if (mapDns) {
            // Fake-IP resolver lives inside hev; the system must send DNS there.
            builder.addDnsServer("198.18.0.2")
        } else if (dns.isNotBlank()) {
            builder.addDnsServer(dns)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        // Let apps that want underlay opt out; excluded packages never see TRANSPORT_VPN
        builder.allowBypass()

        builder.addDisallowedApplication(applicationContext.packageName)

        // Telegram uses local MTProto WS on :1082 — it must bypass the tun
        // ONLY while the bridge is actually running. In "Telegram through the
        // tunnel" mode we keep it inside so MTProto rides the ByeDPI desync.
        if (!tgInTunnel(getPreferences())) {
            for (pkg in TgWsBridge.telegramPackages) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: Exception) {
                    // not installed
                }
            }
        }

        // TikTok works on underlay; -U / desync breaks it inside the tunnel
        for (pkg in BypassApps.all) {
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: Exception) {
                // not installed
            }
        }

        return builder
    }
}
