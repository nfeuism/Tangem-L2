package com.example.tangeml2

import android.content.Context
import com.tangem.Log
import com.tangem.TangemSdkLogger
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 全局日志系统。
 *  - 实现 TangemSdkLogger：捕获 Tangem SDK 内部全部日志（扫卡/APDU/派生/认证/错误）。
 *    经源码确认：Log.logInternal 对第三方 logger 无级别过滤，全量转发。
 *  - app()/err()：记录应用层事件与异常（最具诊断价值的是 catch 块的堆栈）。
 *  - 内存环形缓冲（上限 MAX 行）+ **持久化落盘**（每行追加到 filesDir/logs/persistent.log，
 *    App 重启/被杀也不丢，这是诊断"发送后重启就丢日志"的关键修复）。
 *  - 导出：合并持久化文件全部历史 + 内存缓冲，经 FileProvider 分享。
 *  - 安装崩溃兜底：未捕获异常也会写进日志并落盘。
 *
 * ⚠️ 私钥永在卡内、绝不出现在日志里。但日志会含地址/公钥/交易数据，仅发给信任对象。
 */
object AppLog : TangemSdkLogger {

    private const val MAX = 3000
    private const val PERSIST_MAX_BYTES = 2 * 1024 * 1024 // 2MB，超了轮转
    private val buffer = ConcurrentLinkedDeque<String>()
    private val bufSize = java.util.concurrent.atomic.AtomicInteger(0)
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private var appContext: Context? = null
    private var installed = false

    // 持久化：每行追加到该文件；跨 App 重启保留。
    private val fileLock = Any()
    @Volatile private var persistFile: File? = null

    /** 在 Activity.onCreate 里调用一次：注册 SDK logger + 安装崩溃兜底 + 初始化持久化文件。幂等。 */
    fun install(context: Context) {
        appContext = context.applicationContext
        if (installed) return
        installed = true
        initPersistFile(context)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            err("CRASH", "未捕获异常 @ ${t.name}", e)
            prev?.uncaughtException(t, e)
        }
        Log.addLogger(this)
        app("AppLog", "======== 新会话启动 (持久化日志已启用) ========")
    }

    private fun initPersistFile(context: Context) {
        try {
            val dir = File(context.filesDir, "logs").apply { mkdirs() }
            val f = File(dir, "persistent.log")
            // 轮转：超过上限时保留后半段，避免无限增长
            if (f.exists() && f.length() > PERSIST_MAX_BYTES) {
                val keep = f.readText().takeLast(PERSIST_MAX_BYTES / 2)
                f.writeText("== [日志轮转，保留最近部分] ==\n$keep")
            }
        } catch (_: Throwable) { /* best effort */ }
        persistFile = File(File(context.filesDir, "logs"), "persistent.log")
    }

    // ── Tangem SDK 回调 ──
    override fun log(message: () -> String, level: Log.Level) {
        // 🔒 安全：丢弃 APDU/TLV 字节级日志——可能在加密通道建立前捕获 access code 等敏感字节，
        //    导出分享等于泄露卡密码。只保留人类可读的高层事件级别。
        when (level) {
            Log.Level.Apdu, Log.Level.Tlv, Log.Level.ApduCommand -> return
            else -> add("SDK/${level.name}", runCatching { message() }.getOrDefault("<log error>"))
        }
    }

    // ── 应用层 ──
    fun app(tag: String, msg: String) = add("APP/$tag", msg)

    fun err(tag: String, msg: String, e: Throwable? = null) {
        val trace = e?.let { "\n" + android.util.Log.getStackTraceString(it) } ?: ""
        add("ERR/$tag", msg + trace)
    }

    private fun add(tag: String, msg: String) {
        val line = "${tsFmt.format(Date())} [$tag] $msg"
        buffer.addLast(line)
        // ❗ConcurrentLinkedDeque.size() 是 O(n)，不能每行都调用（否则日志 O(n²) 卡顿）。用原子计数器。
        if (bufSize.incrementAndGet() > MAX) {
            if (buffer.pollFirst() != null) bufSize.decrementAndGet()
        }
        android.util.Log.d("TangemL2", line)
        appendPersist(line)
    }

    /** 每行同步追加到持久化文件（best-effort，永不影响主流程）。 */
    private fun appendPersist(line: String) {
        val f = persistFile ?: return
        synchronized(fileLock) {
            try {
                f.appendText(line + "\n")
            } catch (_: Throwable) { /* best effort：磁盘满/权限等都不能拖垮 App */ }
        }
    }

    fun dump(): String = buffer.joinToString("\n")

    fun tail(n: Int): String = buffer.toList().takeLast(n).joinToString("\n")

    fun clear() {
        buffer.clear()
        bufSize.set(0)
        synchronized(fileLock) {
            try { persistFile?.writeText("") } catch (_: Throwable) {}
        }
        app("AppLog", "日志已清空")
    }

    /**
     * 写入 cacheDir/logs/tangem-l2-log.txt，返回文件供 FileProvider 分享。
     * 内容 = 头部 + **持久化文件全部历史**（含之前会话，这是关键）+ 当前内存缓冲兜底。
     */
    fun writeToFile(context: Context): File {
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        val file = File(dir, "tangem-l2-log.txt")
        val history = synchronized(fileLock) {
            try { persistFile?.takeIf { it.exists() }?.readText() ?: "" } catch (_: Throwable) { "" }
        }
        val body = if (history.isNotBlank()) history else dump()
        file.writeText(header(body) + "\n" + body)
        return file
    }

    private fun header(body: String): String = buildString {
        append("═══ Tangem L2 Recovery 日志 ═══\n")
        append("设备: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n")
        append("Android: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})\n")
        append("导出: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n")
        append("行数: ${body.count { it == '\n' } + 1}（含持久化历史）\n")
        append("─".repeat(50))
    }
}
