package app.sirenscout

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/** Shares the installed APK, so the app can be passed phone to phone without the laptop. */
object AppShare {
    fun share(context: Context) {
        runCatching {
            val version = appVersion(context).substringBefore(' ')
            val dir = File(context.cacheDir, "share").apply {
                mkdirs()
                listFiles()?.forEach { it.delete() }
            }
            val apk = File(dir, "Siren-$version.apk")
            File(context.applicationInfo.sourceDir).copyTo(apk, overwrite = true)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            val send = Intent(Intent.ACTION_SEND)
                .setType("application/vnd.android.package-archive")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .apply { clipData = ClipData.newRawUri(apk.name, uri) } // lets the chooser pass on read access
            context.startActivity(Intent.createChooser(send, "Share Siren $version").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            AppLog.w("Sharing the app failed", it)
            Toast.makeText(context, "Couldn't share: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }
}
