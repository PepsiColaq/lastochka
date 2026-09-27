package io.github.dovecoteescapee.byedpi.activities

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.dovecoteescapee.byedpi.data.AppStatus
import io.github.dovecoteescapee.byedpi.data.FAILED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.Mode
import io.github.dovecoteescapee.byedpi.data.SENDER
import io.github.dovecoteescapee.byedpi.data.STARTED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.STOPPED_BROADCAST
import io.github.dovecoteescapee.byedpi.data.Sender
import io.github.dovecoteescapee.byedpi.obhod.AppUpdater
import io.github.dovecoteescapee.byedpi.obhod.HostListManager
import io.github.dovecoteescapee.byedpi.obhod.StrategyPresets
import io.github.dovecoteescapee.byedpi.services.ServiceManager
import io.github.dovecoteescapee.byedpi.services.appStatus
import io.github.dovecoteescapee.byedpi.utility.getPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
    }

    private var uiRunning by mutableStateOf(false)
    private var uiStrategyId by mutableStateOf(StrategyPresets.Universal.id)
    private var uiAutoNetwork by mutableStateOf(false)
    private var uiAutoStart by mutableStateOf(false)
    private var uiTgWs by mutableStateOf(true)
    private var uiStatusHint by mutableStateOf("Выключено")
    private var uiListsHint by mutableStateOf("")
    private var uiDarkTheme by mutableStateOf(true)
    private var uiUpdateRelease by mutableStateOf<AppUpdater.ReleaseInfo?>(null)
    private var uiUpdateDownloading by mutableStateOf(false)
    private var uiUpdateProgress by mutableStateOf(0f)
    private var uiUpdateSpeed by mutableStateOf("")
    private var uiUpdateLabel by mutableStateOf("")
    private var uiWhatsNew by mutableStateOf<String?>(null)

    private val vpnRegister =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                doStartService()
            } else {
                Toast.makeText(this, "Нужно разрешение VPN", Toast.LENGTH_SHORT).show()
                refreshUiStatus()
            }
        }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            val senderOrd = intent.getIntExtra(SENDER, -1)
            val sender = Sender.entries.getOrNull(senderOrd)
            when (intent.action) {
                STARTED_BROADCAST, STOPPED_BROADCAST -> refreshUiStatus()
                FAILED_BROADCAST -> {
                    Toast.makeText(
                        context,
                        "Не удалось запустить (${sender?.name ?: "?"})",
                        Toast.LENGTH_SHORT
                    ).show()
                    refreshUiStatus()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getPreferences()
        uiStrategyId = prefs.getString(StrategyPresets.PREF_STRATEGY_ID, StrategyPresets.Universal.id)
            ?: StrategyPresets.Universal.id
        uiAutoNetwork = prefs.getBoolean(StrategyPresets.PREF_AUTO_NETWORK, false)
        uiAutoStart = prefs.getBoolean(StrategyPresets.PREF_AUTO_START, false)
        uiTgWs = prefs.getBoolean(StrategyPresets.PREF_TG_WS, true)
        uiDarkTheme = prefs.getBoolean("obhod_dark_theme", true)
        uiWhatsNew = AppUpdater.consumeWhatsNew(this)

        HostListManager.ensureBundledLists(this)

        val intentFilter = IntentFilter().apply {
            addAction(STARTED_BROADCAST)
            addAction(STOPPED_BROADCAST)
            addAction(FAILED_BROADCAST)
        }
        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, intentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(receiver, intentFilter)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = HostListManager.updateIfNeeded(this@MainActivity)
                withContext(Dispatchers.Main) {
                    uiListsHint = result.message
                }
            } catch (e: Exception) {
                Log.w(TAG, "list update on start", e)
                withContext(Dispatchers.Main) {
                    uiListsHint = "Локальные списки готовы"
                }
            }
        }

        setContent {
            ObhodTheme(dark = uiDarkTheme) {
                ObhodScreen(
                    running = uiRunning,
                    statusHint = uiStatusHint,
                    listsHint = uiListsHint,
                    strategyId = uiStrategyId,
                    autoNetwork = uiAutoNetwork,
                    autoStart = uiAutoStart,
                    tgWs = uiTgWs,
                    darkTheme = uiDarkTheme,
                    updateRelease = uiUpdateRelease,
                    updateDownloading = uiUpdateDownloading,
                    updateProgress = uiUpdateProgress,
                    updateSpeed = uiUpdateSpeed,
                    updateLabel = uiUpdateLabel,
                    whatsNew = uiWhatsNew,
                    onToggle = { toggleBypass() },
                    onStrategyChange = { id ->
                        uiStrategyId = id
                        uiAutoNetwork = false
                        lifecycleScope.launch(Dispatchers.IO) {
                            try {
                                val hosts = HostListManager.buildWhitelist(this@MainActivity)
                                val prefs = getPreferences()
                                prefs.edit().putBoolean(StrategyPresets.PREF_AUTO_NETWORK, false).apply()
                                StrategyPresets.applyToPreferences(
                                    prefs,
                                    StrategyPresets.byId(id),
                                    hosts,
                                    this@MainActivity,
                                )
                            } catch (e: Exception) {
                                Log.e(TAG, "strategy change", e)
                            }
                        }
                    },
                    onAutoNetworkChange = { enabled ->
                        try {
                            uiAutoNetwork = enabled
                            lifecycleScope.launch(Dispatchers.IO) {
                                try {
                                    val prefs = getPreferences()
                                    prefs.edit()
                                        .putBoolean(StrategyPresets.PREF_AUTO_NETWORK, enabled)
                                        .apply()
                                    if (enabled) {
                                        val hosts = HostListManager.buildWhitelist(this@MainActivity)
                                        val rec = StrategyPresets.recommendForNetwork(this@MainActivity)
                                        StrategyPresets.applyToPreferences(
                                            prefs,
                                            rec,
                                            hosts,
                                            this@MainActivity,
                                        )
                                        withContext(Dispatchers.Main) {
                                            uiStrategyId = rec.id
                                            Toast.makeText(
                                                this@MainActivity,
                                                "Авто: ${rec.title}. Перезапустите обход.",
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    } else {
                                        // Turning auto OFF must not leave Aggressive stuck
                                        val hosts = HostListManager.buildWhitelist(this@MainActivity)
                                        StrategyPresets.applyToPreferences(
                                            prefs,
                                            StrategyPresets.Universal,
                                            hosts,
                                            this@MainActivity,
                                        )
                                        withContext(Dispatchers.Main) {
                                            uiStrategyId = StrategyPresets.Universal.id
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "auto network toggle", e)
                                    withContext(Dispatchers.Main) {
                                        uiAutoNetwork = false
                                        Toast.makeText(
                                            this@MainActivity,
                                            "Ошибка авто-режима",
                                            Toast.LENGTH_SHORT,
                                        ).show()
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "auto network toggle outer", e)
                            uiAutoNetwork = false
                            Toast.makeText(this, "Ошибка авто-режима", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onAutoStartChange = { enabled ->
                        uiAutoStart = enabled
                        getPreferences().edit()
                            .putBoolean(StrategyPresets.PREF_AUTO_START, enabled)
                            .apply()
                    },
                    onTgWsChange = { enabled ->
                        uiTgWs = enabled
                        getPreferences().edit()
                            .putBoolean(StrategyPresets.PREF_TG_WS, enabled)
                            .apply()
                        if (enabled && uiRunning) {
                            Toast.makeText(
                                this,
                                "Перезапустите обход, чтобы применить Telegram WS",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                    onApplyTelegram = {
                        io.github.dovecoteescapee.byedpi.tgws.TgWsBridge
                            .offerApplyInTelegram(this, getPreferences(), force = true)
                    },
                    onDarkThemeChange = { dark ->
                        uiDarkTheme = dark
                        getPreferences().edit().putBoolean("obhod_dark_theme", dark).apply()
                    },
                    onRefreshLists = {
                        lifecycleScope.launch(Dispatchers.IO) {
                            val result = HostListManager.updateIfNeeded(this@MainActivity, force = true)
                            if (result.ok) {
                                try {
                                    val prefs = getPreferences()
                                    val hosts = HostListManager.buildWhitelist(this@MainActivity)
                                    StrategyPresets.applyToPreferences(
                                        prefs,
                                        StrategyPresets.byId(uiStrategyId),
                                        hosts,
                                        this@MainActivity,
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "re-apply after list update", e)
                                }
                            }
                            withContext(Dispatchers.Main) {
                                uiListsHint = result.message
                                Toast.makeText(
                                    this@MainActivity,
                                    result.message,
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    },
                    onCheckUpdate = { checkAppUpdate(force = true) },
                    onInstallUpdate = { startUpdateDownload() },
                    onDismissUpdate = { uiUpdateRelease = null },
                    onDismissWhatsNew = { uiWhatsNew = null },
                    onOpenLegacySettings = {
                        if (appStatus.first != AppStatus.Running) {
                            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                        } else {
                            Toast.makeText(
                                this@MainActivity,
                                "Сначала выключите обход",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                )
            }
        }

        refreshUiStatus()
        // Auto-start bypass when user enabled the switch
        if (uiAutoStart && appStatus.first != AppStatus.Running) {
            prepareAndStart()
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val result = AppUpdater.checkForUpdate(this@MainActivity, force = false)
                val release = result.release
                if (release != null) {
                    withContext(Dispatchers.Main) {
                        uiUpdateRelease = release
                        AppUpdater.notifyUpdateAvailable(this@MainActivity, release)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "quiet update check", e)
            }
        }
    }

    private fun checkAppUpdate(force: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = try {
                AppUpdater.checkForUpdate(this@MainActivity, force = force)
            } catch (e: Exception) {
                Log.w(TAG, "update check", e)
                AppUpdater.CheckResult(false, e.message ?: "Ошибка проверки")
            }
            withContext(Dispatchers.Main) {
                val release = result.release
                if (release != null) {
                    uiUpdateRelease = release
                    AppUpdater.notifyUpdateAvailable(this@MainActivity, release)
                } else {
                    Toast.makeText(this@MainActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun startUpdateDownload() {
        val release = uiUpdateRelease ?: return
        if (uiUpdateDownloading) return
        uiUpdateDownloading = true
        uiUpdateProgress = 0f
        uiUpdateSpeed = ""
        uiUpdateLabel = "Скачиваю…"
        lifecycleScope.launch(Dispatchers.IO) {
            AppUpdater.rememberWhatsNew(this@MainActivity, release)
            val apk = AppUpdater.downloadApk(this@MainActivity, release.apkUrl) { p ->
                val frac = if (p.total > 0) {
                    (p.downloaded.toFloat() / p.total.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                uiUpdateProgress = frac
                uiUpdateSpeed = AppUpdater.formatSpeed(p.bytesPerSec)
                uiUpdateLabel =
                    "${AppUpdater.formatBytes(p.downloaded)} / ${AppUpdater.formatBytes(p.total)}"
            }
            withContext(Dispatchers.Main) {
                uiUpdateDownloading = false
                if (apk == null) {
                    uiUpdateLabel = ""
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось скачать. Включите обход и повторите.",
                        Toast.LENGTH_LONG,
                    ).show()
                } else {
                    uiUpdateProgress = 1f
                    uiUpdateLabel = "Готово — установка"
                    val started = AppUpdater.installApk(this@MainActivity, apk)
                    if (!started) {
                        Toast.makeText(
                            this@MainActivity,
                            "Разрешите установку из этого приложения",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshUiStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
    }

    private fun refreshUiStatus() {
        val (status, _) = appStatus
        uiRunning = status == AppStatus.Running
        uiStatusHint = when (status) {
            AppStatus.Halted -> "Выключено"
            AppStatus.Running -> "Ласточка включена"
            AppStatus.Paused -> "На паузе"
        }
    }

    private fun toggleBypass() {
        val (status, _) = appStatus
        when (status) {
            AppStatus.Halted, AppStatus.Paused -> prepareAndStart()
            AppStatus.Running -> ServiceManager.stop(this)
        }
    }

    private fun prepareAndStart() {
        // Don't block VPN start on remote list download — local lists are enough
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val hosts = HostListManager.buildWhitelist(this@MainActivity)
                val prefs = getPreferences()
                val preset = if (uiAutoNetwork) {
                    StrategyPresets.recommendForNetwork(this@MainActivity)
                } else {
                    StrategyPresets.byId(uiStrategyId)
                }
                StrategyPresets.applyToPreferences(prefs, preset, hosts, this@MainActivity)
                withContext(Dispatchers.Main) {
                    uiStrategyId = preset.id
                    val prepare = VpnService.prepare(this@MainActivity)
                    if (prepare != null) {
                        vpnRegister.launch(prepare)
                    } else {
                        doStartService()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "prepareAndStart failed", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось запустить: ${e.message ?: "ошибка"}",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshUiStatus()
                }
            }
        }
    }

    private fun doStartService() {
        ServiceManager.start(this, Mode.VPN)
    }
}

@Composable
private fun ObhodTheme(dark: Boolean, content: @Composable () -> Unit) {
    val colors = if (dark) {
        darkColorScheme(
            primary = Color(0xFF7DCFB6),
            onPrimary = Color(0xFF0A1612),
            secondary = Color(0xFFE8A87C),
            background = Color(0xFF0E1412),
            surface = Color(0xFF1A2220),
            onBackground = Color(0xFFE8EFEC),
            onSurface = Color(0xFFE8EFEC),
            outline = Color(0xFF3A4844),
        )
    } else {
        lightColorScheme(
            primary = Color(0xFF1B4D3E),
            onPrimary = Color(0xFFF4F7F5),
            secondary = Color(0xFFC45C26),
            background = Color(0xFFE8EEF2),
            surface = Color(0xFFF7FAFC),
            onBackground = Color(0xFF14201C),
            onSurface = Color(0xFF14201C),
        )
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun ObhodScreen(
    running: Boolean,
    statusHint: String,
    listsHint: String,
    strategyId: String,
    autoNetwork: Boolean,
    autoStart: Boolean,
    tgWs: Boolean,
    darkTheme: Boolean,
    updateRelease: AppUpdater.ReleaseInfo?,
    updateDownloading: Boolean,
    updateProgress: Float,
    updateSpeed: String,
    updateLabel: String,
    whatsNew: String?,
    onToggle: () -> Unit,
    onStrategyChange: (String) -> Unit,
    onAutoNetworkChange: (Boolean) -> Unit,
    onAutoStartChange: (Boolean) -> Unit,
    onTgWsChange: (Boolean) -> Unit,
    onApplyTelegram: () -> Unit,
    onDarkThemeChange: (Boolean) -> Unit,
    onRefreshLists: () -> Unit,
    onCheckUpdate: () -> Unit,
    onInstallUpdate: () -> Unit,
    onDismissUpdate: () -> Unit,
    onDismissWhatsNew: () -> Unit,
    onOpenLegacySettings: () -> Unit,
) {
    var showStrategyDialog by remember { mutableStateOf(false) }
    val preset = StrategyPresets.byId(strategyId)
    val scheme = MaterialTheme.colorScheme

    val btnScale by animateFloatAsState(
        targetValue = if (running) 1.12f else 1f,
        animationSpec = spring(dampingRatio = 0.65f, stiffness = 320f),
        label = "scale",
    )
    val btnColor by animateColorAsState(
        targetValue = when {
            running -> Color(0xFF1F8A6A)
            darkTheme -> Color(0xFF2A3A36)
            else -> Color(0xFF2C3E50)
        },
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "btn",
    )
    val bgTop = if (darkTheme) Color(0xFF0E1412) else Color(0xFFE8EEF2)
    val bgMid = if (darkTheme) Color(0xFF121A18) else Color(0xFFD5E0E8)
    val bgBot = if (darkTheme) Color(0xFF15201C) else Color(0xFFC5D4C8)
    val muted = if (darkTheme) Color(0xFF9AAEA6) else Color(0xFF5A6A64)
    val cardBg = if (darkTheme) Color(0xCC1A2220) else Color(0xCCF7FAFC)

    if (whatsNew != null) {
        AlertDialog(
            onDismissRequest = onDismissWhatsNew,
            title = { Text("Что нового") },
            text = {
                Text(whatsNew.take(900), fontSize = 14.sp)
            },
            confirmButton = {
                TextButton(onClick = onDismissWhatsNew) { Text("Отлично") }
            },
        )
    }

    if (updateRelease != null) {
        AlertDialog(
            onDismissRequest = { if (!updateDownloading) onDismissUpdate() },
            title = { Text("Обновление ${updateRelease.versionName}") },
            text = {
                Column {
                    Text(
                        "Доступна новая версия Ласточки.",
                        fontWeight = FontWeight.Medium,
                    )
                    if (updateRelease.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("Что нового:", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(updateRelease.notes.take(700), fontSize = 12.sp, color = muted)
                    }
                    if (updateDownloading || updateProgress > 0f) {
                        Spacer(modifier = Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { updateProgress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(updateLabel.ifBlank { "…" }, fontSize = 12.sp, color = muted)
                            Text(updateSpeed, fontSize = 12.sp, color = Color(0xFF7DCFB6))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = onInstallUpdate,
                    enabled = !updateDownloading,
                ) {
                    Text(if (updateDownloading) "Скачиваю…" else "Установить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = onDismissUpdate,
                    enabled = !updateDownloading,
                ) { Text("Позже") }
            },
        )
    }

    if (showStrategyDialog && !autoNetwork) {
        AlertDialog(
            onDismissRequest = { showStrategyDialog = false },
            title = { Text("Стратегия") },
            text = {
                Column {
                    StrategyPresets.all.forEach { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onStrategyChange(item.id)
                                    showStrategyDialog = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = item.id == strategyId,
                                onClick = {
                                    onStrategyChange(item.id)
                                    showStrategyDialog = false
                                },
                            )
                            Column(modifier = Modifier.padding(start = 8.dp)) {
                                Text(item.title, fontWeight = FontWeight.SemiBold)
                                Text(item.description, fontSize = 12.sp, color = muted)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStrategyDialog = false }) {
                    Text("Закрыть")
                }
            },
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(bgTop, bgMid, bgBot)))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "Ласточка",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = scheme.onBackground,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Локальный DPI-обход. Без чужих серверов.",
                textAlign = TextAlign.Center,
                color = muted,
                fontSize = 15.sp,
            )
            Spacer(modifier = Modifier.height(36.dp))

            Text(
                text = statusHint,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                color = if (running) Color(0xFF7DCFB6) else muted,
            )
            Spacer(modifier = Modifier.height(28.dp))

            Button(
                onClick = onToggle,
                modifier = Modifier
                    .size(172.dp)
                    .scale(btnScale)
                    .semantics { contentDescription = "power_toggle" },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = btnColor,
                    contentColor = Color.White,
                ),
                elevation = ButtonDefaults.buttonElevation(
                    defaultElevation = if (running) 12.dp else 6.dp,
                    pressedElevation = 2.dp,
                ),
            ) {
                Text(
                    text = if (running) "ВЫКЛ" else "ВКЛ",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(modifier = Modifier.height(36.dp))

            Surface(
                color = cardBg,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Тёмная тема", fontWeight = FontWeight.SemiBold)
                            Text("Меньше белого на экране", fontSize = 12.sp, color = muted)
                        }
                        Switch(checked = darkTheme, onCheckedChange = onDarkThemeChange)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Автозапуск обхода", fontWeight = FontWeight.SemiBold)
                            Text(
                                "При открытии Ласточки сразу включает VPN",
                                fontSize = 12.sp,
                                color = muted,
                            )
                        }
                        Switch(checked = autoStart, onCheckedChange = onAutoStartChange)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Авто по сети", fontWeight = FontWeight.SemiBold)
                            Text(
                                "Wi‑Fi → Универсальная, LTE → Жёсткая. Не привязано к Tele2: подстройка под тип сети, не к имени оператора.",
                                fontSize = 12.sp,
                                color = muted,
                            )
                        }
                        Switch(checked = autoNetwork, onCheckedChange = onAutoNetworkChange)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Telegram WS", fontWeight = FontWeight.SemiBold)
                            Text(
                                "MTProto через WebSocket — без смены стратегии",
                                fontSize = 12.sp,
                                color = muted,
                            )
                        }
                        Switch(checked = tgWs, onCheckedChange = onTgWsChange)
                    }

                    if (tgWs && running) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(
                            onClick = onApplyTelegram,
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics { contentDescription = "apply_telegram_proxy" },
                        ) {
                            Text("Подключить прокси в Telegram (вручную)")
                        }
                        Text(
                            "Нужно один раз. Потом Ласточка сама не будет открывать Telegram.",
                            fontSize = 11.sp,
                            color = muted,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text("Стратегия обхода сайтов", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (darkTheme) Color(0xFF24302C) else Color(0xFFF0F4F6),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "strategy_picker" }
                            .clickable(enabled = !autoNetwork) {
                                showStrategyDialog = true
                            },
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(
                                text = if (autoNetwork) "${preset.title} (авто)" else preset.title,
                                fontWeight = FontWeight.Medium,
                                color = if (autoNetwork) muted else scheme.onSurface,
                            )
                            Text(preset.description, fontSize = 12.sp, color = muted)
                            if (!autoNetwork) {
                                Text(
                                    "Нажми, чтобы сменить ▾",
                                    fontSize = 11.sp,
                                    color = Color(0xFF7DCFB6),
                                    modifier = Modifier.padding(top = 4.dp),
                                )
                            }
                        }
                    }

                    if (listsHint.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(listsHint, fontSize = 12.sp, color = Color(0xFF7DCFB6))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = onRefreshLists, modifier = Modifier.fillMaxWidth()) {
                Text("Обновить списки (обход ВКЛ)")
            }
            OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                Text("Проверить обновление приложения")
            }
            TextButton(onClick = onOpenLegacySettings) {
                Text("Расширенные настройки")
            }
        }
    }
}
