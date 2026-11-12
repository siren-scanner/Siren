package app.sirenscout

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the user's own laptop over their Tailscale network. The laptop publishes
 * latest.json next to the APK; Android itself checks the APK is signed with the same key as the
 * installed app, and we check its SHA-256 against latest.json before handing it over.
 */
object Updater {
    data class Update(val versionCode: Long, val versionName: String, val apkUrl: String, val sha256: String, val notes: String)

    private const val CHECK_EVERY_MS = 6 * 3600_000L

    fun installedVersionCode(context: Context): Long {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    }

    /** Returns a newer version if the server has one. Throws if the server can't be reached. */
    fun check(context: Context, baseUrl: String): Update? {
        // Some phones can't resolve Tailscale names (e.g. a custom Private DNS bypasses
        // Tailscale's), so fall back to the laptop's Tailscale IP for the default server.
        val candidates = listOf(baseUrl.trimEnd('/')) +
            if (baseUrl.trimEnd('/') == DEFAULT_UPDATE_URL && FALLBACK_UPDATE_URL.isNotEmpty()) listOf(FALLBACK_UPDATE_URL) else emptyList()
        var lastError: Exception? = null
        for (base in candidates) {
            try {
                return check(context, base, get("$base/latest.json"))
            } catch (e: java.net.UnknownHostException) {
                AppLog.i("Update server name didn't resolve, trying its IP")
                lastError = e
            }
        }
        throw lastError ?: IOException("no update server")
    }

    private fun check(context: Context, base: String, body: ByteArray): Update? {
        val json = JSONObject(body.decodeToString())
        val update = Update(
            versionCode = json.getLong("versionCode"),
            versionName = json.getString("versionName"),
            apkUrl = "$base/${json.getString("apk")}",
            sha256 = json.getString("sha256").lowercase(),
            notes = json.optString("notes"),
        )
        Prefs(context).lastUpdateCheck = System.currentTimeMillis()
        return update.takeIf { it.versionCode > installedVersionCode(context) }
    }

    /** Quiet background check on app open, at most every few hours. */
    fun checkIfDue(context: Context): Update? {
        val prefs = Prefs(context)
        if (prefs.updateUrl.isBlank() || System.currentTimeMillis() - prefs.lastUpdateCheck < CHECK_EVERY_MS) return null
        return runCatching { check(context, prefs.updateUrl) }
            .onFailure { AppLog.i("Update check skipped: server not reachable (${it.javaClass.simpleName})") }
            .getOrNull()
    }

    /** Android requires the user to allow each app that installs other APKs, once. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    fun allowInstallsIntent(context: Context) =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /** Downloads, verifies and hands the APK to Android's installer, which asks the user to confirm. */
    fun downloadAndInstall(context: Context, update: Update) {
        val bytes = get(update.apkUrl)
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (hash != update.sha256) throw IOException("download was corrupted (checksum mismatch)")
        val apk = File(context.cacheDir, "update.apk").apply { writeBytes(bytes) }
        AppLog.i("Installing update ${update.versionName}")

        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(context.packageName) }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE, // the installer fills in the result
            )
            session.commit(result.intentSender)
        }
        apk.delete()
    }

    private fun get(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }
}

/** Receives the installer's progress: shows its confirmation screen, then reports the outcome. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
            PackageInstaller.STATUS_SUCCESS -> AppLog.i("Update installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                AppLog.w("Update failed: $message")
                Toast.makeText(context, "Update failed: $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
