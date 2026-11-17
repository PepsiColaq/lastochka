package io.github.dovecoteescapee.byedpi.obhod

import android.content.Context
import android.util.Log
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cloudflare front domains used by the Telegram MTProto→WS bridge.
 *
 * Upstream (Flowseal/tg-ws-proxy) ships these names **obfuscated**: every
 * letter is shifted by the number of letters in the name and the TLD is
 * replaced with `.co.uk`. The native bridge decodes them itself, but the
 * Kotlin side used to feed the raw encoded strings into the ByeDPI `-H`
 * desync list. No ClientHello ever contains those names, so the CF path went
 * out **without** desync and the RU DPI reset the handshake — that is why
 * Telegram hung forever while the local ping looked fine.
 *
 * [decode] restores the real hostnames, [workingDomains] keeps only the ones
 * that actually resolve, and the result goes into the desync list.
 */
object CfProxyDomains {
    private const val TAG = "CfProxyDomains"

    /** Upstream replaces the last four characters (`.com`) with this. */
    private const val REAL_TLD = ".co.uk"
    private const val ENCODED_TLD = ".com"

    /** Subdomains the bridge dials (see upstream CfProxy docs). */
    val KWS_LABELS = listOf("kws1", "kws2", "kws203")

    private val resolutionCache = ConcurrentHashMap<String, Boolean>()

    /**
     * Decode one **upstream** entry. Mirrors `_dd()` from
     * `tg-ws-proxy/proxy/config.py` exactly.
     *
     * Important: in the upstream file `.com` *is* the encoding marker, so
     * every `.com` entry is treated as encoded. Never pass user-supplied
     * domains here — use [userDomains], which keeps them verbatim.
     */
    fun decode(raw: String): String {
        val s = raw.trim().lowercase()
        if (s.isEmpty()) return s
        if (!s.endsWith(ENCODED_TLD)) return s
        val body = s.removeSuffix(ENCODED_TLD)
        if (body.isEmpty()) return s
        val n = body.count { it.isLetter() }
        val decoded = body.map { c ->
            when {
                c in 'a'..'z' -> ('a' + (((c - 'a') - n) % 26 + 26) % 26)
                c in 'A'..'Z' -> ('A' + (((c - 'A') - n) % 26 + 26) % 26)
                else -> c
            }
        }.joinToString("")
        return decoded + REAL_TLD
    }

    /**
     * Parse a user-supplied domain list (settings field, comma/space/semicolon
     * separated). Entries are used verbatim: the user's own Cloudflare zone is
     * already a real hostname and must never be de-obfuscated.
     */
    fun userDomains(raw: String): List<String> =
        raw.split(',', ';', ' ', '\n', '\r')
            .map { it.trim().lowercase() }
            .filter { it.contains('.') && !it.startsWith('.') && !it.endsWith('.') }
            .distinct()

    /**
     * All real domains from the bundled list plus the runtime cache.
     * `userExtra` entries (settings) are appended verbatim.
     */
    fun allDomains(context: Context, userExtra: List<String> = emptyList()): List<String> {
        val out = linkedSetOf<String>()
        userExtra.forEach { d ->
            val t = d.trim().lowercase()
            if (t.contains('.')) out.add(t)
        }
        for (raw in readBundled(context)) out.add(decode(raw))
        for (raw in readCache(context)) out.add(decode(raw))
        return out.filter { it.isNotBlank() }.distinct()
    }

    /**
     * Domains that resolve right now. Empty list means the CF path is closed
     * and the bridge should go direct instead of burning time on a cooldown.
     */
    fun workingDomains(
        context: Context,
        userExtra: List<String> = emptyList(),
    ): List<String> {
        val candidates = allDomains(context, userExtra)
        if (candidates.isEmpty()) return emptyList()
        val alive = resolveAll(candidates)
        if (alive.isNotEmpty()) {
            Log.i(TAG, "CF fronts alive: ${alive.size}/${candidates.size} -> ${alive.take(3)}")
        } else {
            Log.w(TAG, "CF fronts: 0/${candidates.size} resolvable, disabling CF path")
        }
        return alive
    }

    /**
     * Resolve every candidate in parallel under a single deadline. Doing this
     * sequentially cost one DNS timeout per domain (20 × 5 s worst case) and
     * stalled the bridge start.
     */
    private fun resolveAll(domains: List<String>): List<String> {
        val pool = Executors.newFixedThreadPool(8)
        return try {
            val futures = domains.map { d -> d to pool.submit(Callable { resolves(d) }) }
            val deadline = System.currentTimeMillis() + 2_500L
            futures.mapNotNull { (domain, future) ->
                val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(50L)
                val ok = try {
                    future.get(remaining, TimeUnit.MILLISECONDS)
                } catch (_: Exception) {
                    false
                }
                domain.takeIf { ok }
            }
        } finally {
            pool.shutdownNow()
        }
    }

    /** True when at least one CF front answers DNS. */
    fun anyWorking(context: Context, userExtra: List<String> = emptyList()): Boolean =
        workingDomains(context, userExtra).isNotEmpty()

    /** `kws1.<domain>` for every live front — used by diagnostics. */
    fun probeHosts(context: Context, userExtra: List<String> = emptyList()): List<String> =
        workingDomains(context, userExtra).map { "$KWS_LABELS.first().$it" }

    /** Drop the resolution cache (call after the network changes). */
    fun invalidate() = resolutionCache.clear()

    private fun resolves(domain: String): Boolean {
        resolutionCache[domain]?.let { return it }
        // The apex of these zones intentionally has no A record; what
        // matters is that the zone answers and the kws host resolves.
        val host = "${KWS_LABELS.first()}.$domain"
        val ok = try {
            InetAddress.getAllByName(host).isNotEmpty()
        } catch (_: Exception) {
            false
        }
        if (ok) {
            resolutionCache[domain] = true
        }
        return ok
    }

    /** Refresh the on-disk cache from GitHub through the local SOCKS. */
    fun refresh(
        context: Context,
        socksPort: Int,
        url: String = "https://raw.githubusercontent.com/Flowseal/tg-ws-proxy/main/.github/cfproxy-domains.txt",
    ): Boolean {
        val proxies = buildList {
            if (socksAlive(socksPort)) {
                add(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            }
            add(Proxy.NO_PROXY)
        }
        for (proxy in proxies) {
            val body = httpGet(url, proxy) ?: continue
            val lines = body.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
            if (lines.size < 3) continue
            val cache = cacheFile(context)
            cache.writeText(body)
            resolutionCache.clear()
            Log.i(TAG, "CF cache refreshed via $proxy (${lines.size} entries)")
            return true
        }
        return false
    }

    fun readBundled(context: Context): List<String> = try {
        context.assets.open("lists/cfproxy-domains.txt").bufferedReader().use { r ->
            r.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .toList()
        }
    } catch (e: Exception) {
        Log.w(TAG, "bundled CF list missing", e)
        emptyList()
    }

    fun readCache(context: Context): List<String> {
        val f = cacheFile(context)
        if (!f.exists() || f.length() < 16) return emptyList()
        return try {
            f.readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun hasCache(context: Context): Boolean {
        val f = cacheFile(context)
        return f.exists() && f.length() > 16
    }

    /** Seed from assets only when there is no usable cache yet. */
    fun seedIfMissing(context: Context) {
        if (hasCache(context)) return
        val bundled = readBundled(context)
        if (bundled.isEmpty()) return
        runCatching { cacheFile(context).writeText(bundled.joinToString("\n")) }
    }

    private fun cacheFile(context: Context) = File(context.cacheDir, "cfproxy-domains-cache.txt")

    private fun socksAlive(port: Int): Boolean = try {
        Socket().use { s ->
            s.soTimeout = 400
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun httpGet(url: String, proxy: Proxy): String? {
        val conn = (URL(url).openConnection(proxy) as java.net.HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Lastochka-tgws")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
