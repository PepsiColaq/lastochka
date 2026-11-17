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
    const val PREF_AUTO_START = "obhod_auto_start_vpn"
    /** Own Cloudflare zone for the TG bridge (comma separated). */
    const val PREF_CF_USER_DOMAIN = "obhod_cf_user_domain"
    /** Fall back to sending Telegram MTProto through the ByeDPI tunnel. */
    const val PREF_TG_IN_TUNNEL = "obhod_tg_in_tunnel"

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
        // NOTE: no embedded -An here. An "-An" inside the body opened an empty
        // auto group that swallowed every rule after it (old bug).
        cmdArgs = "-U -d1 -s1 -Kt,h",
    )

    /**
     * Fake-packet ladder for networks where plain split/disorder is not
     * enough (МТС/Билайн LTE). Uses `-f` (fake) with a low TTL so the fake
     * ClientHello dies before reaching the real server, plus OOB for hosts
     * that ignore fakes.
     */
    val Aggressive = StrategyPreset(
        id = "aggressive",
        title = "Жёсткая",
        description = "Fake + низкий TTL: для LTE, где сплита мало (МТС/Билайн)",
        cmdArgs = "-U -f1 -t2 -d1+s -s1+s -o1+s -q1 -Kt,h",
    )

    /**
     * Narrow profile: desync only what YouTube needs, leave everything else
     * in passthrough. Use when the broad profile makes other sites flaky.
     */
    val YouTubeOnly = StrategyPreset(
        id = "youtube-only",
        title = "Только YouTube",
        description = "Минимальный десинк для YouTube/Google video. Сайты не трогает",
        cmdArgs = "-U -d1+s -s1+s -q1+s -r1+s -Kt",
    )

    val Alt get() = Universal

    val all: List<StrategyPreset> = listOf(Universal, Soft, Aggressive, YouTubeOnly)

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

    /**
     * Body of the selected preset with the leading `-U` removed.
     *
     * `-A*` switches inside the body are **kept**: Universal uses `-As` as a
     * retry-on-TLS-error ladder, and that is part of the profile that works on
     * Tele2. Only the leading `-U` belongs to the command, not the group.
     */
    private fun presetBody(preset: StrategyPreset): String =
        preset.cmdArgs.removePrefix("-U").trim()

    /** `-U` + one deterministic group chain: discord → gemini → rasp → meta → cf → yt → passthrough. */
    private fun buildCommand(
        preset: StrategyPreset,
        discordFile: File,
        geminiFile: File,
        raspFile: File,
        metaFile: File,
        cfFile: File,
        desyncFile: File,
    ): String {
        val ytPart = presetBody(preset)
        return buildString {
            append("-U ")
            // Cloudflare edges (Discord, schedule API): tunnel + DNS only.
            append("-H ${discordFile.absolutePath} -d1+s -s1+s ")
            // Gemini / Assistant: harsh multisplit breaks the API.
            append("-An -H ${geminiFile.absolutePath} ")
            // Workers / Supabase / Pages: passthrough.
            append("-An -H ${raspFile.absolutePath} ")
            // Instagram / X: soft disorder, their edges RST on multisplit.
            append("-An -H ${metaFile.absolutePath} -d1+s -s1+s ")
            // Telegram CF fronts (decoded): the path that used to be desync-free.
            append("-An -H ${cfFile.absolutePath} -d1+s -s1+s ")
            // Everything YouTube/Google: full preset shape.
            if (ytPart.isNotEmpty()) append("-An -H ${desyncFile.absolutePath} $ytPart ")
            // Everyone else: untouched.
            append("-An")
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
                val desyncFile = File(HostListManager.listsDir(context), "desync.txt").also {
                    it.writeText(desync)
                }
                val discordFile = File(HostListManager.listsDir(context), "discord.txt").also {
                    it.writeText(
                        HostListManager.DISCORD_DOMAINS.map { d -> d.lowercase() }.distinct().sorted()
                            .joinToString("\n"),
                    )
                }
                val geminiFile = File(HostListManager.listsDir(context), "gemini.txt").also {
                    it.writeText(
                        HostListManager.GEMINI_DOMAINS.map { d -> d.lowercase() }.distinct().sorted()
                            .joinToString("\n"),
                    )
                }
                val raspFile = File(HostListManager.listsDir(context), "rasp.txt").also {
                    it.writeText(
                        HostListManager.RASP_PASS_DOMAINS.map { d -> d.lowercase() }.distinct().sorted()
                            .joinToString("\n"),
                    )
                }
                val metaFile = File(HostListManager.listsDir(context), "meta.txt").also {
                    it.writeText(
                        HostListManager.META_DOMAINS.map { d -> d.lowercase() }.distinct().sorted()
                            .joinToString("\n"),
                    )
                }
                // Decoded Cloudflare fronts (see CfProxyDomains). These are the
                // hosts the Telegram bridge actually dials.
                val cfUserDomain = prefs.getString(PREF_CF_USER_DOMAIN, "") ?: ""
                val cfFile = File(HostListManager.listsDir(context), "cfproxy.txt").also {
                    it.writeText(
                        HostListManager.cfProxyDomains(context, cfUserDomain)
                            .distinct().sorted().joinToString("\n"),
                    )
                }
                cmd = buildCommand(
                    preset, discordFile, geminiFile, raspFile, metaFile, cfFile, desyncFile,
                )
                Log.i(TAG, "strategy=${preset.id} groups=discord,gemini,rasp,meta,cf,yt,passthrough")
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
