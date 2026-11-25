package app.sirenscout

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Updates for the public edition, which doesn't install updates itself: on request, ask GitHub
 * for the newest release and open its page. Copies installed from F-Droid are signed with
 * F-Droid's key, so a GitHub APK can't install over them; those update through F-Droid instead.
 */
object GitHubUpdates {
    /** SHA-256 of the project's release signing certificate (public: anyone can read it from the APK). */
    private const val PROJECT_CERT = "fd0c6a60323b7eddeb464690dd9ef104b9d1ae448fb28b09480a15c89f746403"

    val releasesUrl get() = "${BuildConfig.REPO_URL}/releases/latest"

    data class Latest(val version: String, val url: String)

    /** Newest release on GitHub. Throws on network trouble. */
    fun latest(): Latest {
        val repo = BuildConfig.REPO_URL.removePrefix("https://github.com/").trimEnd('/')
        val j = JSONObject(PoliceData.get("https://api.github.com/repos/$repo/releases/latest"))
        return Latest(j.getString("tag_name").removePrefix("v"), j.optString("html_url", releasesUrl))
    }

    /** -1, 0 or 1 comparing dotted versions ("1.0.9" < "1.0.10"); suffixes like "-test" are ignored. */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /** Whether this copy was signed by the project (GitHub releases), so a GitHub APK installs over it. */
    @Suppress("DEPRECATION")
    fun signedByProject(context: Context): Boolean = runCatching {
        val pm = context.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo?.apkContentsSigners
        } else pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures
        sigs.orEmpty().any { s ->
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } == PROJECT_CERT
        }
    }.getOrDefault(true)
}
