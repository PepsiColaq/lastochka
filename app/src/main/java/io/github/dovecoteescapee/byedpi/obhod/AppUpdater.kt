package io.github.dovecoteescapee.byedpi.obhod

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import io.github.dovecoteescapee.byedpi.BuildConfig
import io.github.dovecoteescapee.byedpi.R
import io.github.dovecoteescapee.byedpi.activities.MainActivity
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
    const val DEFAULT_REPO = "PepsiColaq/lastochka"
    private const val PREF_REPO = "obhod_update_repo"
    private const val PREF_LAST_CHECK = "obhod_update_last_check"
    private const val PREF_WHATS_NEW = "obhod_whats_new_pending"
    private const val PREF_WHATS_NEW_CODE = "obhod_whats_new_code"
    private const val PREF_SEEN_WHATS_NEW = "obhod_whats_new_seen_code"

    data class ReleaseInfo(
        val versionName: String,
        val versionCode: Int,
        val apkUrl: String,
        val htmlUrl: String,
        val notes: String,
        val apkSizeBytes: Long = 0L,
    )

    data class CheckResult(
        val ok: Boolean,
        val message: String,
        val release: ReleaseInfo? = null,
    )

    data class DownloadProgress(
        val downloaded: Long,
        val total: Long,
        val bytesPerSec: Long,
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
            val notes = json.optString("body", "").take(1200)
            val assets = json.optJSONArray("assets") ?: return CheckResult(true, "Обновлений нет")
            var apkUrl = ""
            var apkSize = 0L
            var codeFromAsset = 0
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name", "")
                val url = a.optString("browser_download_url", "")
                when {
                    name.endsWith(".apk", ignoreCase = true) && apkUrl.isEmpty() -> {
                        apkUrl = url
                        apkSize = a.optLong("size", 0L)
                    }
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
                    apkSizeBytes = apkSize,
                ),
            )
        } catch (e: Exception) {
            Log.w(TAG, "parse release", e)
            CheckResult(false, "Ошибка ответа GitHub")
        }
    }

    fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): File? {
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
                    val total = conn.contentLengthLong.coerceAtLeast(0L)
                    var downloaded = 0L
                    var windowBytes = 0L
                    var windowStart = System.nanoTime()
                    conn.inputStream.use { input ->
                        out.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                downloaded += n
                                windowBytes += n
                                val now = System.nanoTime()
                                val elapsedNs = now - windowStart
                                if (elapsedNs >= 250_000_000L || downloaded == total) {
                                    val bps = if (elapsedNs > 0) {
                                        (windowBytes * 1_000_000_000L) / elapsedNs
                                    } else {
                                        0L
                                    }
                                    onProgress?.invoke(DownloadProgress(downloaded, total, bps))
                                    windowBytes = 0
                                    windowStart = now
                                }
                            }
                        }
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

    fun rememberWhatsNew(context: Context, release: ReleaseInfo) {
        context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
            .edit()
            .putString(PREF_WHATS_NEW, release.notes.ifBlank { "Обновление ${release.versionName}" })
            .putInt(PREF_WHATS_NEW_CODE, release.versionCode)
            .apply()
    }

    /** Show once after user installs a newer build. */
    fun consumeWhatsNew(context: Context): String? {
        val prefs = context.getSharedPreferences(
            context.packageName + "_preferences",
            Context.MODE_PRIVATE,
        )
        val code = prefs.getInt(PREF_WHATS_NEW_CODE, -1)
        val seen = prefs.getInt(PREF_SEEN_WHATS_NEW, -1)
        if (code != BuildConfig.VERSION_CODE || code == seen) return null
        val notes = prefs.getString(PREF_WHATS_NEW, null)?.takeIf { it.isNotBlank() } ?: return null
        prefs.edit().putInt(PREF_SEEN_WHATS_NEW, code).apply()
        return notes
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

    fun notifyUpdateAvailable(context: Context, release: ReleaseInfo) {
        val channelId = "lastochka_updates"
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "Обновления Ласточки",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Когда выходит новая версия приложения"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            context,
            40,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Доступна Ласточка ${release.versionName}")
            .setContentText("Нажмите, чтобы обновить приложение")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        nm.notify(40, n)
    }

    fun formatSpeed(bytesPerSec: Long): String {
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 0.1) "%.1f МБ/с".format(mb) else "%.0f КБ/с".format(bytesPerSec / 1024.0)
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "?"
        val mb = bytes / (1024.0 * 1024.0)
        return if (mb >= 1) "%.1f МБ".format(mb) else "%.0f КБ".format(bytes / 1024.0)
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
