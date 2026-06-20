package com.storyteller_f.dush

import android.content.Context
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.LogcatWriter
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import java.io.File
import java.time.Instant

private const val APP_LOG_FILE = "logs/app.log"

private var isLoggingConfigured = false

@Synchronized
fun configureAppLogging(context: Context) {
    if (isLoggingConfigured) return

    val logFile = File(context.filesDir, APP_LOG_FILE)
    logFile.parentFile?.mkdirs()
    logFile.appendText("${Instant.now()} [INFO] AppLogging: Logging initialized\n")

    Logger.setLogWriters(
        listOf(
            LogcatWriter(),
            AndroidFileLogWriter(logFile),
        ),
    )
    Logger.i(tag = "AppLogging") { "Logging to ${logFile.absolutePath}" }
    isLoggingConfigured = true
}

private class AndroidFileLogWriter(
    private val file: File,
) : LogWriter() {
    private val lock = Any()

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val line = buildString {
            append(Instant.now())
            append(" [")
            append(severity.name)
            append("]")
            if (tag.isNotBlank()) {
                append(" ")
                append(tag)
                append(":")
            }
            append(" ")
            append(message)
            appendLine()
            throwable?.let {
                append(it.stackTraceToString())
                appendLine()
            }
        }

        synchronized(lock) {
            file.appendText(line)
        }
    }
}
