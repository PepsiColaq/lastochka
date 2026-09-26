package io.github.dovecoteescapee.byedpi.obhod

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Host lists for DPI bypass (Nimbus-style seed/google/exclude).
 * Bundled in assets; refreshable from public community lists.
 *
 * Important: Discord gets DNS/tunnel only (no TLS desync) — desync breaks CF edges.
 * YouTube/Google video get full desync via [buildDesyncWhitelist].
 *
 * App is excluded from the VPN tunnel, so GitHub is often blocked on RU LTE.
 * Updates must go through local ByeDPI SOCKS (127.0.0.1:1080) when Ласточка is on.
 */
object HostListManager {
    private const val TAG = "HostListManager"
    private const val PREF_LAST_UPDATE = "obhod_lists_last_update"
    private const val PREF_REMOTE_URL = "obhod_lists_remote_url"

    private val REMOTE_SEED_URLS = listOf(
        // Flowseal community lists (zapret-discord-youtube) — актуально
        "https://cdn.jsdelivr.net/gh/Flowseal/zapret-discord-youtube@main/lists/list-general.txt",
        "https://cdn.jsdelivr.net/gh/Flowseal/zapret-discord-youtube@main/lists/list-google.txt",
        "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/lists/list-general.txt",
        "https://raw.githubusercontent.com/Flowseal/zapret-discord-youtube/main/lists/list-google.txt",
        // itdoginfo — сервисы по отдельности
        "https://cdn.jsdelivr.net/gh/itdoginfo/allow-domains@main/Services/youtube.lst",
        "https://cdn.jsdelivr.net/gh/itdoginfo/allow-domains@main/Services/meta.lst",
        "https://cdn.jsdelivr.net/gh/itdoginfo/allow-domains@main/Services/twitter.lst",
        "https://cdn.jsdelivr.net/gh/itdoginfo/allow-domains@main/Services/google_ai.lst",
        "https://cdn.jsdelivr.net/gh/itdoginfo/allow-domains@main/Services/telegram.lst",
        "https://cdn.jsdelivr.net/gh/itdoginfo/allow-domains@main/Services/hdrezka.lst",
    )

    const val DEFAULT_REMOTE_SEED_URL =
        "https://cdn.jsdelivr.net/gh/Flowseal/zapret-discord-youtube@main/lists/list-general.txt"

    /** Domains that need TLS desync (YouTube / Meta / AI / etc.). Not Discord/CF. */
    private val DESYNC_EXTRA = listOf(
        // YouTube / Google media
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com",
        "gvt1.com", "gvt2.com",
        "youtube-nocookie.com", "youtubei.googleapis.com", "youtubekids.com",
        "yt3.ggpht.com", "wide-youtube.l.google.com", "youtubeembeddedplayer.googleapis.com",
        // Instagram / Facebook / Threads / Meta
        "instagram.com", "cdninstagram.com", "facebook.com", "fbcdn.net",
        "fb.com", "meta.com", "threads.net", "threads.com", "messenger.com",
        // X / Twitter
        "twitter.com", "x.com", "twimg.com", "t.co", "pscp.tv",
        // Reddit / LinkedIn / Medium / GitHub / Stack
        "reddit.com", "redd.it", "redditmedia.com", "redditstatic.com",
        "linkedin.com", "licdn.com", "medium.com",
        "github.com", "githubusercontent.com", "githubassets.com", "gitlab.com",
        "stackoverflow.com", "stackexchange.com",
        // AI
        "openai.com", "chatgpt.com", "chat.openai.com", "oaistatic.com", "oaiusercontent.com",
        "anthropic.com", "claude.ai",
        "cursor.sh", "cursor.com", "cursorapi.com", "api2.cursor.sh",
        // Gemini / Assistant — soft group only (see StrategyPresets), not harsh multisplit
        "perplexity.ai", "grok.x.ai", "copilot.microsoft.com",
        // Streaming / games / voice
        "twitch.tv", "ttvnw.net", "jtvnw.net", "twitchcdn.net",
        "spotify.com", "scdn.co", "spotifycdn.com", "spoti.fi",
        "netflix.com", "nflxvideo.net", "nflxso.net", "nflximg.net",
        "soundcloud.com", "sndcdn.com",
        "steampowered.com", "steamcommunity.com", "steamstatic.com", "steamcontent.com",
        "epicgames.com", "akamaihd.net", "riotgames.com", "riotcdn.net",
        "ea.com", "origin.com", "playstation.com", "xbox.com", "xboxlive.com",
        // Messaging (non-TG) / Google
        "whatsapp.com", "whatsapp.net", "signal.org", "whispersystems.org",
        // Google base for YouTube ecosystem (Gemini AI hosts use soft group instead)
        "gstatic.com", "googleadservices.com",
        "doubleclick.net", "googlesyndication.com",
        "googleapis.com", "googleusercontent.com",
        // News / misc popular blocked
        "bbc.com", "bbc.co.uk", "nytimes.com", "washingtonpost.com",
        "vimeo.com", "patreon.com",
        "wikipedia.org", "wikimedia.org",
        "zoom.us", "webex.com", "skype.com",
        "dropbox.com", "box.com", "onedrive.live.com",
        "notion.so", "notion.com", "figma.com", "canva.com",
        "adobe.com", "adobelogin.com",
        // CDN for list updates (app excluded from VPN → needs SOCKS + these in whitelist)
        "jsdelivr.net", "cdn.jsdelivr.net", "fastly.jsdelivr.net",
        "githubusercontent.com", "raw.githubusercontent.com",
    )

    /**
     * Google Gemini / Assistant — Tele2 multisplit often hangs on «Подождите».
     * Soft disorder only; must be matched BEFORE YouTube desync group.
     */
    /**
     * Google Gemini / Assistant — harsh YT multisplit breaks these APIs.
     * Matched BEFORE desync group; no TLS desync (tunnel only).
     */
    val GEMINI_DOMAINS = listOf(
        // Core Gemini / AI Studio
        "gemini.google.com", "gemini.google", "bard.google.com",
        "aistudio.google.com", "makersuite.google.com",
        "notebooklm.google.com", "notebooklm.google",
        "labs.google", "labs.google.com",
        "ai.google.dev", "generativeai.google",
        "deepmind.com", "deepmind.google",
        "jules.google", "jules.google.com",
        "stitch.withgoogle.com",
        // Backend PA / API (eligibility + chat)
        "aisandbox-pa.googleapis.com",
        "generativelanguage.googleapis.com",
        "proactivebackend-pa.googleapis.com",
        "geller-pa.googleapis.com",
        "robinfrontend-pa.googleapis.com",
        "playatoms-pa.googleapis.com",
        "alkalimakersuite-pa.googleapis.com",
        "aiplatform.googleapis.com",
        "aida.googleapis.com",
        "antigravity-pa.googleapis.com",
        "antigravity.googleapis.com",
        "antigravity.google",
        "antigravity-unleash.goog",
        "speechs3proto2-pa.googleapis.com",
        "firebaseinstallations.googleapis.com",
        // Assistant
        "assistant.google.com", "embeddedassistant.googleapis.com",
        "speech.googleapis.com", "texttospeech.googleapis.com",
        "translate.googleapis.com", "translation.googleapis.com",
        "growth-pa.googleapis.com",
        "clients6.google.com",
        // Shell hosts (not bare googleapis.com — would soften YouTube)
        "www.google.com", "google.com", "google.ru",
    )

    /**
     * Discord family — tunnel + public DNS resolve only.
     * Must NOT appear in desync (-H) list: fake/split/tlsrec RSTs Cloudflare.
     */
    val DISCORD_DOMAINS = listOf(
        "discord.com", "discordapp.com", "discord.gg", "discord.media",
        "discordapp.net", "discordcdn.com", "discordstatus.com", "discord.gift",
        "discord.co", "dis.gd", "discord.app", "discord.gifts",
        "discordactivities.com", "discordpartygames.com", "discordsays.com",
        "stable.dl2.discordapp.net",
        "gateway.discord.gg", "gateway-us-east1-b.discord.gg",
        "cdn.discordapp.com", "media.discordapp.net", "images-ext-1.discordapp.net",
        "images-ext-2.discordapp.net", "dmcdn.discordapp.com",
        "latency.discord.media", "us-east-1.discord.media", "eu-central-1.discord.media",
        "discord-attachments-uploads-prd.storage.googleapis.com",
    )

    private val TELEGRAM_EXTRA = listOf(
        "telegram.org", "telegram.me", "t.me", "telesco.pe", "tdesktop.com",
        "telegra.ph", "telegram-cdn.org", "cdn-telegram.org", "telegram.space",
        "tg.dev", "fragment.com", "quiz.directory", "legra.ph", "tx.me",
        "telegram.dog", "web.telegram.org", "core.telegram.org",
    )

    fun listsDir(context: Context): File =
        File(context.filesDir, "lists").also { if (!it.exists()) it.mkdirs() }

    fun ensureBundledLists(context: Context) {
        val dir = listsDir(context)
        val names = listOf("seed.txt", "google.txt", "exclude.txt", "auto.txt")
        for (name in names) {
            val out = File(dir, name)
            if (out.exists() && out.length() > 0) continue
            try {
                context.assets.open("lists/$name").use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Missing asset lists/$name", e)
                if (!out.exists()) out.writeText("# $name\n")
            }
        }
    }

    /** Full list for UI / prefs; includes Discord (for diagnostics). */
    fun buildWhitelist(context: Context): String {
        val all = linkedSetOf<String>()
        all.addAll(buildDesyncDomains(context))
        DISCORD_DOMAINS.forEach { all.add(it.lowercase()) }
        GEMINI_DOMAINS.forEach { all.add(it.lowercase()) }
        TELEGRAM_EXTRA.forEach { all.add(it.lowercase()) }
        return all.sorted().joinToString("\n")
    }

    /** Domains that receive ByeDPI TLS desync (-H file). */
    fun buildDesyncWhitelist(context: Context): String =
        buildDesyncDomains(context).sorted().joinToString("\n")

    private fun buildDesyncDomains(context: Context): Set<String> {
        ensureBundledLists(context)
        val dir = listsDir(context)
        val domains = linkedSetOf<String>()
        for (name in listOf("seed.txt", "google.txt", "auto.txt")) {
            parseDomains(File(dir, name)).forEach { domains.add(it) }
        }
        DESYNC_EXTRA.forEach { domains.add(it.lowercase()) }
        TELEGRAM_EXTRA.forEach { domains.add(it.lowercase()) }
        val exclude = parseDomains(File(dir, "exclude.txt")).toSet()
        val discord = DISCORD_DOMAINS.map { it.lowercase() }.toSet()
        val gemini = GEMINI_DOMAINS.map { it.lowercase() }.toSet()
        return domains
            .filter { it !in exclude && it != "localhost" }
            .filter { !it.contains("cloudflare") && it != "cloudfront.net" }
            .filter { it !in discord && !it.contains("discord") }
            .filter { it !in gemini }
            .toSet()
    }

    private fun parseDomains(file: File): List<String> {
        if (!file.exists()) return emptyList()
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { it.lowercase().removePrefix("*.") }
            .map { it.substringBefore("#").trim() }
            .filter { it.contains('.') && !it.contains(' ') }
    }

    data class UpdateResult(val ok: Boolean, val message: String, val lines: Int = 0)

    fun updateIfNeeded(context: Context, force: Boolean = false): UpdateResult {
        ensureBundledLists(context)
        val prefs = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE,
        )
        val last = prefs.getLong(PREF_LAST_UPDATE, 0L)
        val age = System.currentTimeMillis() - last
        if (!force && age < TimeUnit.DAYS.toMillis(3) && last > 0L) {
            val n = buildDesyncDomains(context).size
            return UpdateResult(true, "Списки доменов: $n (актуальны)", n)
        }
        val custom = prefs.getString(PREF_REMOTE_URL, null)?.takeIf { it.isNotBlank() }
        val urls = listOfNotNull(custom) + REMOTE_SEED_URLS
        val socksPort = prefs.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080
        val socksUp = isLocalSocksOpen(socksPort)
        val proxies = buildList {
            if (socksUp) add(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            add(Proxy.NO_PROXY)
        }

        val merged = linkedSetOf<String>()
        var sourcesOk = 0
        for (url in urls) {
            var got = false
            for (proxy in proxies) {
                try {
                    val body = httpGet(url, proxy) ?: continue
                    val domains = parseDomainLines(body)
                    if (domains.size < 3) {
                        Log.w(TAG, "skip $url via $proxy: only ${domains.size} domains")
                        continue
                    }
                    merged.addAll(domains)
                    sourcesOk++
                    got = true
                    Log.i(TAG, "fetched ${domains.size} from $url via $proxy")
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "update from $url via $proxy", e)
                }
            }
            if (!got) Log.w(TAG, "all proxies failed for $url")
        }

        if (sourcesOk == 0 || merged.size < 10) {
            val local = buildDesyncDomains(context).size
            val hint = if (!socksUp) {
                "Не скачалось. Включите Ласточку и нажмите снова · локальные: $local"
            } else {
                "Не удалось скачать списки · локальные: $local"
            }
            return UpdateResult(false, hint, local)
        }

        File(listsDir(context), "auto.txt").writeText(
            "# auto updated ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date())}\n" +
                merged.sorted().joinToString("\n"),
        )
        prefs.edit().putLong(PREF_LAST_UPDATE, System.currentTimeMillis()).apply()
        val total = buildDesyncDomains(context).size
        Log.i(TAG, "lists merged sources=$sourcesOk unique=${merged.size} total=$total")
        return UpdateResult(
            true,
            "Списки обновлены · доменов: $total. Выкл/вкл Ласточку",
            total,
        )
    }

    private fun parseDomainLines(body: String): List<String> =
        body.lineSequence()
            .map { it.trim().lowercase().removePrefix("*.") }
            .map { it.substringBefore("#").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("^") }
            .filter { it.contains('.') && !it.contains(' ') && !it.contains('/') }
            .toList()

    private fun isLocalSocksOpen(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.soTimeout = 400
                s.connect(InetSocketAddress("127.0.0.1", port), 400)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun httpGet(url: String, proxy: Proxy = Proxy.NO_PROXY): String? {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Lastochka/1.0")
            setRequestProperty("Accept", "text/plain,*/*")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "HTTP $code for $url via $proxy")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
