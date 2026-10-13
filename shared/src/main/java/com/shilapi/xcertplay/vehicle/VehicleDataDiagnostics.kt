package com.shilapi.xcertplay.vehicle

import android.content.Context
import java.io.File
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Bounded diagnostics shared by the vehicle source, normalization and iAP2 output layers.
 *
 * Every line uses wall and monotonic time plus one process-local sequence, so a saved report can
 * reconstruct ordering without logging location, device identifiers or complete protocol payloads.
 * File I/O is serialized away from the EVCC Binder and iAP2 control threads.
 */
object VehicleDataDiagnostics {
    private val lock = Any()
    private val fileLock = Any()
    private val recent = ArrayDeque<String>()
    private val writer = Executors.newSingleThreadExecutor { task ->
        Thread(task, "vehicle-data-diagnostics").apply { isDaemon = true }
    }

    private var sequence = 0L
    private var currentLog: File? = null
    private var wallClockMillis: () -> Long = System::currentTimeMillis
    private var elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L }

    fun initialize(context: Context) = initialize(context.filesDir)

    fun record(stage: String, detail: String): String {
        val line = synchronized(lock) {
            val nextSequence = ++sequence
            val safeStage = diagnosticToken(stage, MAX_STAGE_LENGTH).ifBlank { "unknown" }
            val safeDetail = diagnosticText(detail, MAX_DETAIL_LENGTH)
            buildString {
                append(PREFIX)
                append(" seq=").append(nextSequence)
                append(" wallMs=").append(wallClockMillis())
                append(" elapsedMs=").append(elapsedRealtimeMillis())
                append(" stage=").append(safeStage)
                if (safeDetail.isNotEmpty()) append(' ').append(safeDetail)
            }.also { completed ->
                if (recent.size == MAX_RECENT_ENTRIES) recent.removeFirst()
                recent.addLast(completed)
            }
        }
        val target = synchronized(lock) { currentLog }
        if (target != null) writer.execute { append(target, line) }
        return line
    }

    fun report(): String = synchronized(lock) {
        buildString {
            appendLine("vehicleDataTrace format=2 recent=${recent.size} persisted=${currentLog != null}")
            recent.forEach(::appendLine)
        }.trimEnd()
    }

    internal fun awaitIdle(timeoutMillis: Long): Boolean {
        val latch = CountDownLatch(1)
        writer.execute(latch::countDown)
        return latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
    }

    internal fun resetForTests(
        filesDir: File? = null,
        wallClockMillis: () -> Long = System::currentTimeMillis,
        elapsedRealtimeMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    ) {
        awaitIdle(2_000)
        synchronized(lock) {
            sequence = 0L
            recent.clear()
            currentLog = filesDir?.let { File(File(it, LOG_DIRECTORY), CURRENT_LOG_NAME) }
            this.wallClockMillis = wallClockMillis
            this.elapsedRealtimeMillis = elapsedRealtimeMillis
        }
        filesDir?.let { directory ->
            File(directory, LOG_DIRECTORY).mkdirs()
            REPORT_NAMES.forEach { File(File(directory, LOG_DIRECTORY), it).delete() }
        }
    }

    private fun initialize(filesDir: File) {
        synchronized(lock) {
            val directory = File(filesDir, LOG_DIRECTORY)
            directory.mkdirs()
            currentLog = File(directory, CURRENT_LOG_NAME)
        }
    }

    private fun append(target: File, line: String) = synchronized(fileLock) {
        runCatching {
            target.parentFile?.mkdirs()
            if (target.length() + line.length + 1 > MAX_FILE_BYTES) {
                val previous = File(target.parentFile, PREVIOUS_LOG_NAME)
                previous.delete()
                if (!target.renameTo(previous)) target.copyTo(previous, overwrite = true).also { target.delete() }
            }
            target.appendText(line + "\n")
        }
        Unit
    }

    private fun diagnosticToken(value: String, limit: Int): String =
        value.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(limit)

    private fun diagnosticText(value: String, limit: Int): String =
        value.replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex(" {2,}"), " ")
            .replace(SENSITIVE_ASSIGNMENT) { match -> "${match.groupValues[1]}=<redacted>" }
            .trim()
            .take(limit)

    const val PREFIX = "VEHICLE-DATA"
    const val CURRENT_LOG_NAME = "vehicle-data.log"
    const val PREVIOUS_LOG_NAME = "vehicle-data-previous.log"
    val REPORT_NAMES = listOf(PREVIOUS_LOG_NAME, CURRENT_LOG_NAME)

    private const val LOG_DIRECTORY = "logs"
    private const val MAX_RECENT_ENTRIES = 512
    private const val MAX_STAGE_LENGTH = 48
    private const val MAX_DETAIL_LENGTH = 640
    private const val MAX_FILE_BYTES = 768L * 1024L
    private val SENSITIVE_ASSIGNMENT = Regex(
        "(?i)\\b(password|passphrase|token|secret|private[_-]?key|psk|credential|authorization)=" +
            "(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;]+)",
    )
}
