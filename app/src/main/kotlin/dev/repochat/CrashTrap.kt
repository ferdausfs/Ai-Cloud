package dev.repochat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Field-crash capture without a third-party crash-reporting backend (none may
 * be added without a privacy-policy change).
 *
 * The default uncaught-exception handler writes a compact, shareable report
 * (version, device, stack trace) to the app's external files directory BEFORE
 * delegating to the platform handler, so the process still closes the normal
 * way. The next launch reads the report and offers Share / Copy in
 * [MainActivity] — the user becomes the log collector with one tap.
 *
 * Report file lives in getExternalFilesDir (survives app updates, no
 * storage permission, wiped on uninstall) with a filesDir fallback.
 */
object CrashTrap {

    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_STACK_CHARS = 12_000

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                reportFile(context).writeText(
                    formatReport(
                        appVersion = resolveAppVersion(context),
                        threadName = thread.name,
                        atMillis = System.currentTimeMillis(),
                        throwable = throwable,
                    ),
                )
            }
            previous?.uncaughtException(thread, throwable)
                ?: Runtime.getRuntime().exit(10)
        }
    }

    /** The last crash report, or null when there is nothing pending. */
    fun read(context: Context): String? = runCatching {
        reportFile(context)
            .takeIf { it.isFile && it.length() > 0 }
            ?.readText()
            ?.trim()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /** Called after the user has seen / shared the report. */
    fun clear(context: Context) {
        runCatching { reportFile(context).delete() }
    }

    fun formatReport(
        appVersion: String,
        threadName: String,
        atMillis: Long,
        throwable: Throwable,
    ): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val stack = sw.toString().take(MAX_STACK_CHARS)
        return buildString {
            appendLine("Ai Cloud crash report")
            appendLine("at: $atMillis (epoch millis)")
            appendLine("app: $appVersion")
            appendLine("android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("thread: $threadName")
            appendLine()
            append(stack)
        }
    }

    private fun resolveAppVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION") info.versionCode.toLong()
        }
        "${info.versionName} (versionCode $code)"
    }.getOrDefault("unknown")

    private fun reportFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }
}
