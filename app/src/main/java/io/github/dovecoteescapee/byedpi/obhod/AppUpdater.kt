package io.github.dovecoteescapee.byedpi.obhod

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import io.github.dovecoteescapee.byedpi.BuildConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer APK and installs it (user confirms).
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    /** owner/repo — filled after first public release; override via prefs if needed */
    const val DEFAULT_REPO = "PepsiColaq/lastochka"
    private const val PREF_REPO = "obhod_update_repo"
    private const val PREF_LAST_CHECK = "obhod_update_last_check"

    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val htmlUrl: String,
        val notes: String,
    )

    data class CheckResult(
        val ok: Boolean,
        val message: String,
        val release: ReleaseInfo? = null,
    )

    fun repoSlug(context: Context): String {
        val prefs = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE,
        )
        return prefs.getString(PREF_REPO, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.UPDATE_REPO.takeIf { it.isNotBlank() && !it.startsWith("REPLACE_") }
            ?: DEFAULT_REPO
    }

    fun checkForUpdate(context: Context, force: Boolean = false): CheckResult {
        val prefs = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE,
        )
        val last = prefs.getLong(PREF_LAST_CHECK, 0L)
        if (!force && System.currentTimeMillis() - last < TimeUnit.HOURS.toMillis(12)) {
            return CheckResult(true, "Проверка обновлений уже была недавно")
        }
        val repo = repoSlug(context)
        if (repo.startsWith("REPLACE_") || !repo.contains('/')) {
            return CheckResult(false, "Репозиторий обновлений ещё не настроен")
        }
        val socksPort = prefs.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080
        val proxies = buildList {
            if (isLocalSocksOpen(socksPort)) {
                add(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            }
            add(Proxy.NO_PROXY)
        }
        val api = "https://api.github.com/repos/$repo/releases/latest"
        var body: String? = null
        for (proxy in proxies) {
            body = httpGet(api, proxy) ?: continue
            break
        }
        if (body.isNullOrBlank()) {
            return CheckResult(
                false,
                if (isLocalSocksOpen(socksPort)) "Не удалось проверить обновление"
                else "Включите Ласточку и проверьте снова",
            )
        }
        prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
        return try {
            val json = JSONObject(body)
            val tag = json.optString("tag_name", "").removePrefix("v").trim()
            val html = json.optString("html_url", "")
            val notes = json.optString("body", "").take(500)
            val assets = json.optJSONArray("assets") ?: return CheckResult(true, "Обновлений нет")
            var apkUrl = ""
            var codeFromAsset = 0
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                when {
                    name.endsWith(".apk", ignoreCase = true) && apkUrl.isEmpty() -> apkUrl = url
                    name.equals("version.json", ignoreCase = true) -> {
                        val vBody = proxies.firstNotNullOfOrNull { httpGet(url, it) }
                        if (!vBody.isNullOrBlank()) {
                            val vj = JSONObject(vBody)
                            codeFromAsset = vj.optInt("versionCode", 0)
                        }
                    }
                }
            }
            if (apkUrl.isEmpty()) {
                return CheckResult(true, "В релизе нет APK")
            }
            val localCode = BuildConfig.VERSION_CODE
            val localName = BuildConfig.VERSION_NAME.substringBefore('-')
            val hasUpdate = when {
                codeFromAsset > 0 -> codeFromAsset > localCode
                else -> isNewerName(tag, localName)
            }
            if (!hasUpdate) {
                return CheckResult(true, "У вас актуальная версия ${BuildConfig.VERSION_NAME}")
            }
            CheckResult(
                true,
                "Доступна версия $tag",
                ReleaseInfo(
                    versionName = tag.ifBlank { "new" },
                    versionCode = if (codeFromAsset > 0) codeFromAsset else localCode + 1,
                    apkUrl = apkUrl,
                    htmlUrl = html,
                    notes = notes,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "parse release", e)
            CheckResult(false, "Ошибка ответа GitHub")
        }
    }

    fun downloadApk(context: Context, apkUrl: String): File? {
        val prefs = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE,
        )
        val socksPort = prefs.getString("byedpi_proxy_port", null)?.toIntOrNull() ?: 1080
        val proxies = buildList {
            if (isLocalSocksOpen(socksPort)) {
                add(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort)))
            }
            add(Proxy.NO_PROXY)
        }
        val out = File(context.cacheDir, "lastochka-update.apk")
        for (proxy in proxies) {
            try {
                val conn = (URL(apkUrl).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 120_000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Lastochka/${BuildConfig.VERSION_NAME}")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                try {
                    if (conn.responseCode !in 200..299) continue
                    conn.inputStream.use { input ->
                        out.outputStream().use { output -> input.copyTo(output) }
                    }
                    if (out.length() > 100_000) return out
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                Log.w(TAG, "download via $proxy", e)
            }
        }
        return null
    }

    fun installApk(activity: Activity, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(
            activity,
            "${activity.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        return true
    }

    private fun isNewerName(remote: String, local: String): Boolean {
        val r = remote.removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        val l = local.substringBefore('-').split('.').mapNotNull { it.toIntOrNull() }
        val n = maxOf(r.size, l.size)
        for (i in 0 until n) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun isLocalSocksOpen(port: Int): Boolean = try {
        Socket().use { s ->
            s.soTimeout = 400
            s.connect(InetSocketAddress("127.0.0.1", port), 400)
            true
        }
    } catch (_: Exception) {
        false
    }

    private fun httpGet(url: String, proxy: Proxy): String? {
        val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 25_000
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("User-Agent", "Lastochka/${BuildConfig.VERSION_NAME}")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        return try {
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "HTTP ${conn.responseCode} $url")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
