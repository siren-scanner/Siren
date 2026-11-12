package app.sirenscout

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash reporter before anything else runs, in every process start. */
class SirenScoutApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReports.install(this)
        AppLog.i("App started, ${appVersion(this)}")
    }
}

/**
 * Short in-memory log of what the app has been doing, included in crash reports and shareable
 * from Settings. Deliberately holds no locations or device details beyond scanner status.
 */
object AppLog {
    private const val MAX_LINES = 300
    private val lines = ArrayDeque<String>()
    private val time = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun i(message: String) = add("I", message)

    fun w(message: String, error: Throwable? = null) {
        Log.w("SirenScout", message, error)
        add("W", message + (error?.let { ": $it" } ?: ""))
    }

    @Synchronized
    private fun add(level: String, message: String) {
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast("${time.format(Date())} $level $message")
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}

object CrashReports {
    private const val KEEP = 10
    private const val PREFS = "crash_reports"
    private val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    private fun dir(context: Context) = File(context.filesDir, "crashes").apply { mkdirs() }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(app, thread, error) }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun write(context: Context, thread: Thread, error: Throwable) {
        val report = buildString {
            appendLine("Siren crash report")
            appendLine(header(context))
            appendLine("Thread: ${thread.name}")
            appendLine()
            appendLine(error.stackTraceToString())
            appendLine("Recent app log:")
            AppLog.snapshot().forEach { appendLine(it) }
        }
        File(dir(context), "crash-${stamp.format(Date())}.txt").writeText(report)
        reports(context).drop(KEEP).forEach { it.delete() }
    }

    /** Newest first. */
    fun reports(context: Context): List<File> =
        dir(context).listFiles { f -> f.name.startsWith("crash-") }.orEmpty().sortedByDescending { it.name }

    /** A crash the user hasn't been told about yet. */
    fun unseen(context: Context): File? {
        val latest = reports(context).firstOrNull() ?: return null
        val seen = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("seen", null)
        return latest.takeIf { it.name != seen }
    }

    fun markSeen(context: Context) {
        val latest = reports(context).firstOrNull() ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("seen", latest.name).apply()
    }

    fun deleteAll(context: Context) {
        reports(context).forEach { it.delete() }
    }

    /** Device addresses are cut to their maker prefix: enough to debug, not to identify a device. */
    fun diagnostics(context: Context) = Redact.text(buildString {
        appendLine("Siren activity log")
        appendLine(header(context))
        ScanHealth.summary(System.currentTimeMillis())?.let { appendLine("Scan health: $it") }
        appendLine()
        AppLog.snapshot().forEach { appendLine(it) }
    })

    private fun header(context: Context) = buildString {
        appendLine("Version: ${appVersion(context)}")
        appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        append("Time: ${Date()}")
    }

    /** Opens Android's share sheet with [text]; the user picks where it goes. */
    fun share(context: Context, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        context.startActivity(Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** "Siren" or "Siren Test", for text that names the app. */
val APP_NAME get() = if (BuildConfig.TEST_BUILD) "Siren Test" else "Siren"

fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
}.getOrDefault("unknown")
