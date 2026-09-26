package io.github.dovecoteescapee.byedpi.obhod

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.File

/**
 * One «Универсальная» strategy. Telegram uses local WS bridge.
 * Global multisplit (no -H): Discord must get desync; -H+detect groups skipped it.
 * TikTok / RU apps stay off-tunnel via BypassApps.
 */
data class StrategyPreset(
    val id: String,
    val title: String,
    val description: String,
    val cmdArgs: String,
    val useCmdMode: Boolean = true,
)

object StrategyPresets {
    private const val TAG = "StrategyPresets"
    const val PREF_STRATEGY_ID = "obhod_strategy_id"
    const val PREF_AUTO_NETWORK = "obhod_auto_network_strategy"
    const val PREF_TG_WS = "obhod_tg_ws_enabled"

    /** Default — works on Tele2; also a solid baseline for other RU LTE/Wi‑Fi. */
    val Universal = StrategyPreset(
        id = "flowseal-alt",
        title = "Универсальная",
        description = "База для РФ (проверено на Tele2). YouTube + Telegram WS",
        cmdArgs = "-U -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1 -As -d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -S -a1 -Kt,h",
    )

    val Soft = StrategyPreset(
        id = "soft",
        title = "Мягкая",
        description = "Если Универсальная ломает сайты (часто Wi‑Fi / другой оператор)",
        cmdArgs = "-U -d1 -s1 -An -Kt,h",
    )

    val Aggressive = StrategyPreset(
        id = "aggressive",
        title = "Жёсткая",
        description = "Сильнее на LTE, если Универсальной мало (МТС/Билайн и т.п.)",
        cmdArgs = Universal.cmdArgs,
    )

    val Alt get() = Universal

    val all: List<StrategyPreset> = listOf(Universal, Soft, Aggressive)

    fun byId(id: String?): StrategyPreset = when (id) {
        null, "flowseal-alt", "universal", "telegram", "fake-tls" -> Universal
        else -> all.firstOrNull { it.id == id } ?: Universal
    }

    fun recommendForNetwork(context: Context): StrategyPreset {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Universal
            var sawCellular = false
            var sawWifiOrEth = false
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> sawCellular = true
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> sawWifiOrEth = true
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> sawWifiOrEth = true
                }
            }
            when {
                sawCellular && !sawWifiOrEth -> Aggressive
                else -> Universal
            }
        } catch (e: Exception) {
            Log.w(TAG, "recommendForNetwork failed", e)
            Universal
        }
    }

    fun applyToPreferences(
        prefs: SharedPreferences,
        preset: StrategyPreset,
        hostsWhitelist: String,
        context: Context? = null,
    ) {
        var cmd = preset.cmdArgs
        if (context != null) {
            try {
                val full = hostsWhitelist.ifBlank { HostListManager.buildWhitelist(context) }
                val desync = HostListManager.buildDesyncWhitelist(context)
                File(HostListManager.listsDir(context), "whitelist.txt").writeText(full)
                val desyncFile = File(HostListManager.listsDir(context), "desync.txt").also { it.writeText(desync) }
                val discordFile = File(HostListManager.listsDir(context), "discord.txt").also {
                    it.writeText(
                        HostListManager.DISCORD_DOMAINS.map { d -> d.lowercase() }.distinct().sorted().joinToString("\n"),
                    )
                }
                val geminiFile = File(HostListManager.listsDir(context), "gemini.txt").also {
                    it.writeText(
                        HostListManager.GEMINI_DOMAINS.map { d -> d.lowercase() }.distinct().sorted().joinToString("\n"),
                    )
                }
                // Order: Discord mild → Gemini tunnel-only (no desync) → YT multisplit → passthrough
                val ytPart = preset.cmdArgs.removePrefix("-U ").trimStart()
                cmd = "-U " +
                    "-H ${discordFile.absolutePath} -d1+s -s1+s " +
                    "-An -H ${geminiFile.absolutePath} " +
                    "-An -H ${desyncFile.absolutePath} $ytPart " +
                    "-An"
                Log.i(TAG, "strategy=${preset.id} discord+gemini-pass+yt+passthrough")
            } catch (e: Exception) {
                Log.w(TAG, "whitelist write", e)
            }
        }
        prefs.edit()
            .putString(PREF_STRATEGY_ID, preset.id)
            .putBoolean("byedpi_enable_cmd_settings", preset.useCmdMode)
            .putString("byedpi_cmd_args", cmd)
            .putString("byedpi_mode", "vpn")
            .putString("byedpi_hosts_mode", "whitelist")
            .putString(
                "byedpi_hosts_whitelist",
                hostsWhitelist.ifBlank {
                    context?.let { HostListManager.buildWhitelist(it) } ?: ""
                },
            )
            .putBoolean("byedpi_desync_http", true)
            .putBoolean("byedpi_desync_https", true)
            .putBoolean("byedpi_desync_udp", true)
            .putString("dns_ip", prefs.getString("dns_ip", "1.1.1.1") ?: "1.1.1.1")
            .putBoolean("ipv6_enable", false)
            .apply()
    }

    internal fun injectHostsFlag(cmd: String, hostsPath: String): String {
        val h = "-H $hostsPath"
        return "$h " + cmd.replace(Regex("""(-A[^\s]*)"""), "$1 $h")
    }
}
