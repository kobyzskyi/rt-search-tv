package dev.rtsearch.tv.update

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import dev.rtsearch.tv.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

data class Release(val version: String, val apkUrl: String, val apkSize: Long, val notes: String)

/** Checks GitHub releases of [BuildConfig.GITHUB_REPO] and installs a newer APK. */
class Updater(private val context: Context) {

    val currentVersion: String = BuildConfig.VERSION_NAME

    /** The latest published release, or null if it has no APK attached. */
    suspend fun latestRelease(): Release? = withContext(Dispatchers.IO) {
        val conn = open("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return@withContext null
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("GitHub returned HTTP ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets") ?: return@withContext null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
                ?: return@withContext null
            Release(
                version = json.optString("tag_name").removePrefix("v"),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size"),
                notes = json.optString("body"),
            )
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(release: Release): Boolean = compareVersions(release.version, currentVersion) > 0

    /** Downloads the release APK into the cache, reporting progress from 0 to 1. */
    suspend fun download(release: Release, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply {
            deleteRecursively()
            mkdirs()
        }
        val file = File(dir, "rt-search-tv-${release.version}.apk")
        val conn = open(release.apkUrl)
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed with HTTP ${conn.responseCode}")
            }
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: release.apkSize
            conn.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** Whether Android allows this app to install packages ("Install unknown apps"). */
    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Hands the APK over to the system package installer. */
    fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "rt-search-tv/$currentVersion")
        }

    companion object {
        /** Compares dotted versions numerically: "1.10.0" is newer than "1.9.2". */
        fun compareVersions(a: String, b: String): Int {
            val pa = a.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val pb = b.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(pa.size, pb.size)) {
                val d = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
                if (d != 0) return d
            }
            return 0
        }
    }
}
