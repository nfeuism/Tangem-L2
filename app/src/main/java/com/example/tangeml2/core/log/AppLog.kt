package com.example.tangeml2.core.log

import android.content.Context
import com.tangem.Log
import com.tangem.TangemSdkLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostics log: in-memory ring buffer + append-only file that survives restarts.
 *
 * Design notes:
 *  - Every write is queued to a single background thread. Logging must never block
 *    the UI thread (the previous implementation appended to disk synchronously on
 *    whatever thread called it, including the main thread).
 *  - APDU/TLV frames are dropped: they can contain access-code material.
 *  - Rotation keeps a bounded tail instead of re-reading multi-megabyte files.
 */
object AppLog : TangemSdkLogger {

    private const val MAX_LINES = 3000
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val ROTATE_KEEP_BYTES = 512L * 1024

    private val buffer = ConcurrentLinkedDeque<String>()
    private val bufferSize = AtomicInteger(0)
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "applog-io").apply { isDaemon = true }
    }

    @Volatile private var persistFile: File? = null
    @Volatile private var installed = false

    /** Registers the Tangem SDK logger, the crash handler and the log file. Idempotent. */
    fun install(context: Context) {
        if (installed) return
        installed = true
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val file = File(dir, "persistent.log")
        rotateIfNeeded(file)
        persistFile = file
        // Capture the previous handler BEFORE installing ours, otherwise the lookup
        // inside the handler would return our own handler and recurse forever.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { err("CRASH", "未捕获异常 @ ${thread.name}", error) }
            previous?.uncaughtException(thread, error)
        }
        Log.addLogger(this)
        app("AppLog", "======== 新会话启动 ========")
    }

    private fun rotateIfNeeded(file: File) {
        runCatching {
            if (file.exists() && file.length() > MAX_FILE_BYTES) {
                val keep = file.readBytes().takeLast(ROTATE_KEEP_BYTES.toInt())
                file.writeBytes("== [日志轮转] ==\n".toByteArray() + keep)
            }
        }
    }

    override fun log(message: () -> String, level: Log.Level) {
        when (level) {
            Log.Level.Apdu, Log.Level.Tlv, Log.Level.ApduCommand -> Unit
            else -> add("SDK/${level.name}", runCatching { message() }.getOrDefault("<log error>"))
        }
    }

    fun app(tag: String, message: String) = add("APP/$tag", message)

    fun err(tag: String, message: String, error: Throwable? = null) {
        val trace = error?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""
        add("ERR/$tag", message + trace)
    }

    private fun add(tag: String, message: String) {
        val line = "${stamp.format(Date())} [$tag] $message"
        buffer.addLast(line)
        if (bufferSize.incrementAndGet() > MAX_LINES) {
            if (buffer.pollFirst() != null) bufferSize.decrementAndGet()
        }
        android.util.Log.d("TangemL2", line)
        val target = persistFile
        if (target != null) {
            io.execute {
                runCatching { target.appendText(line + "\n") }
            }
        }
    }

    fun dump(): String = buffer.joinToString("\n")

    fun tail(lines: Int): String = buffer.toList().takeLast(lines).joinToString("\n")

    fun clear() {
        buffer.clear()
        bufferSize.set(0)
        persistFile?.let { f -> io.execute { runCatching { f.writeText("") } } }
        app("AppLog", "日志已清空")
    }

    /**
     * Flushes pending writes, then snapshots the persisted history into a shareable file.
     * Never throws: diagnostics must not be able to crash the app.
     */
    fun exportToFile(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val out = File(dir, "tangem-l2-log.txt")
        try {
            flush()
            val history = persistFile
                ?.takeIf { it.exists() }
                ?.let { runCatching { it.readText() }.getOrNull() }
                .orEmpty()
            val body = history.ifBlank { dump() }
            out.writeText(header(body) + "\n" + body)
        } catch (e: Throwable) {
            runCatching { out.writeText(header(dump()) + "\n" + dump()) }
        }
        return out
    }

    /** Waits (bounded) for the writer thread to drain so the file is complete. */
    private fun flush() {
        val latch = java.util.concurrent.CountDownLatch(1)
        io.execute { latch.countDown() }
        runCatching { latch.await(2, java.util.concurrent.TimeUnit.SECONDS) }
    }

    private fun header(body: String): String = buildString {
        append("═══ Tangem L2 Recovery 日志 ═══\n")
        append("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        append("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        append("导出: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        append("行数: ${body.count { it == '\n' } + 1}\n")
        append("─".repeat(50))
    }
}
