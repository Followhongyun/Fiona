package com.wangzan101.mika.fiona

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.wangzan101.mika.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.io.File
import java.security.MessageDigest
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fiona updates itself from the owner's own server.
 *
 * Android will not let an ordinary app install a package silently, so the last step is the system
 * installer dialog — one tap. Everything before it (check, download, checksum, hand-off) is automatic,
 * and when adb is around the same APK is pushed with `adb install -r`, which needs no tap at all.
 */
object FionaUpdater {

    private const val TAG = "FionaUpdate"
    private const val DIR = "fiona-updates"
    private const val CHECK_EVERY_MS = 6 * 60 * 60 * 1000L

    data class Manifest(val versionCode: Int, val versionName: String, val url: String, val sha256: String?, val notes: String?)

    /** Release builds hide logcat from us; keep a small trace next to the provisioning files instead. */
    private fun note(ctx: Context, line: String) {
        Log.i(TAG, line)
        runCatching {
            val f = File(ctx.getExternalFilesDir(null) ?: return@runCatching, "fiona-update.log")
            f.appendText("[${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())}] $line\n")
        }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("fiona-update", Context.MODE_PRIVATE)

    /** Check the manifest; when newer, download and hand the APK to the system installer. */
    suspend fun checkAndInstall(ctx: Context, force: Boolean = false) = withContext(Dispatchers.IO) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        if (!force && now - p.getLong("lastCheck", 0) < CHECK_EVERY_MS) return@withContext
        p.edit().putLong("lastCheck", now).apply()
        note(ctx, "check ${BuildConfig.FIONA_UPDATE_URL}")
        val fetched = runCatching { fetch(BuildConfig.FIONA_UPDATE_URL) }
        fetched.exceptionOrNull()?.let { note(ctx, "manifest failed: ${it.message}") }
        val m = fetched.getOrNull() ?: return@withContext
        if (m.versionCode <= BuildConfig.VERSION_CODE) {
            note(ctx, "up to date (${BuildConfig.VERSION_CODE} >= ${m.versionCode})")
            return@withContext
        }
        note(ctx, "update available ${m.versionName} (${m.versionCode})")
        val downloaded = runCatching { download(ctx, m) }
        downloaded.exceptionOrNull()?.let { note(ctx, "download failed: ${it.message}") }
        val file = downloaded.getOrNull() ?: return@withContext
        note(ctx, "downloaded ${file.length()} bytes")
        if (m.sha256 != null && !m.sha256.equals(sha256(file), ignoreCase = true)) {
            note(ctx, "checksum mismatch, discarding")
            file.delete()
            return@withContext
        }
        p.edit().putString("pendingVersion", m.versionName).apply()
        install(ctx, file)
    }

    private fun fetch(url: String): Manifest {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
        }
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = Json.parseToJsonElement(body) as JsonObject
        fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
        val code = (o["versionCode"] as? JsonPrimitive)?.intOrNull ?: error("versionCode missing")
        return Manifest(code, s("versionName") ?: code.toString(), s("url") ?: error("url missing"), s("sha256"), s("notes"))
    }

    private fun download(ctx: Context, m: Manifest): File {
        val dir = File(ctx.cacheDir, DIR).apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "fiona-${m.versionCode}.apk")
        val c = (URL(m.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
        }
        c.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
        c.disconnect()
        return out
    }

    private fun install(ctx: Context, file: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fiona.updates", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        note(ctx, "opening installer")
        runCatching { ctx.startActivity(intent) }
            .onFailure { Log.w(TAG, "installer did not open", it) }
    }

    private fun sha256(f: File): String = MessageDigest.getInstance("SHA-256").run {
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                update(buf, 0, n)
            }
        }
        digest().joinToString("") { "%02x".format(it) }
    }
}
