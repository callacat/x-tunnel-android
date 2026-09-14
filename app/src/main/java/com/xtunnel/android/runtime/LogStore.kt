package com.xtunnel.android.runtime

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用内日志采集器（点 5）：收集 sidecar 标准输出 + 运行时状态事件。
 * - 内存保留最近 [MAX_IN_MEMORY] 条环形缓冲，供 UI 实时滚动展示；
 * - 同时追加写入 [File]（纯文本，带时间戳），支持导出/分享给东哥。
 */
object LogStore {
    private const val MAX_IN_MEMORY = 500
    private const val LOG_FILE = "x-tunnel.log"

    // UX-R3 第 1 项（东哥 2026-09-14 反馈「日志时间不是手机本地时间」）：
    // SimpleDateFormat 不显式设 TimeZone 时隐式跟随 JVM 默认时区——模拟器/
    // 部分设备 persist.sys.timezone 为 GMT 时显示 UTC（v0.2.0 实锤：CT107
    // 日志 03:17 = CST 11:17，差 8h）。这里显式取系统时区，与手机设置一致。
    // SimpleDateFormat 非线程安全：append/render 来自多线程（traffic/output/
    // 主线程），用 ThreadLocal 隔离，替代旧的「每行每帧 new」写法（性能 + 正确性）。
    private val timeFormat = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    }

    private val lines = CopyOnWriteArrayList<LogLine>()
    private var logFile: File? = null

    data class LogLine(
        val timestampMillis: Long,
        val level: Level,
        val message: String,
    ) {
        fun render(): String =
            "${timeFormatted()} [${level.tag}] $message"

        private fun timeFormatted(): String {
            val format = timeFormat.get()!!
            // 每次显式刷新时区（交叉审查 P2：ThreadLocal 创建时固化会错过
            // 设备换时区；getDefault() 读进程内缓存值，成本可忽略）。
            format.timeZone = TimeZone.getDefault()
            return format.format(Date(timestampMillis))
        }
    }

    enum class Level(val tag: String) {
        Info("INFO"),
        Error("ERROR"),
    }

    fun init(filesDir: File) {
        logFile = File(filesDir, LOG_FILE)
    }

    // sidecar（Go core）stdout 行首自带时间戳：Go log 默认 LstdFlags 输出
    // 「2009/01/23 01:23:23」斜杠日期前缀（core v0.5.0 已核实无 SetFlags/slog，
    // logring 仅透传原始行），且 Android 上 Go time.Local 读不到 /etc/localtime
    // 也无 TZ env → 回退 UTC，比 App 设备时区慢 8h（老马预研 2026-09-14 实锤）。
    // consumeOutput 原样透传时，App 行（设备时区）与 sidecar 行（UTC）双轴交错，
    // 观感=sidecar 慢 8h。
    //
    // 归一（UX-R3 第 1 项）：识别已知前缀格式，剥掉行内时间戳，改用
    // App 统一时间轴（LogLine 自带 timestampMillis + 设备时区 render）。
    // 历史兼容：LogStore 内存缓冲不持久化、UI 不回读旧文件，导出文件为纯文本
    // 追加——自本版本起新行均为设备时区单时间轴，升级前旧文件行保持原样
    // （即方案 TASK-2「按新格式从切换点起算」分支）。
    //   1. Go log 默认/微秒前缀 「2026/09/14 03:17:45(.123456)? message」←主格式
    //   2. 破折号/RFC3339 变体  「2026-09-14[T ]03:17:45[Z]? message」（换格式兜底）
    //   3. 方括号日志前缀       「[2026-09-14 03:17:45] message」（防御性兼容）
    // 未识别格式原样保留（内容不丢，仅去掉行首空白）。
    private val SIDECAR_TS_PATTERNS = listOf(
        Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s"""),
        Regex("""^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:?\d{2})?\s"""),
        Regex("""^\[\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(\.\d+)?\]\s?"""),
    )

    fun normalizeSidecarLine(line: String): String {
        val text = line.trim()
        for (pattern in SIDECAR_TS_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                return text.substring(match.value.length).trim()
            }
        }
        return text
    }

    @Synchronized
    fun append(level: Level, message: String) {
        val line = LogLine(System.currentTimeMillis(), level, message)
        if (lines.size >= MAX_IN_MEMORY) {
            lines.removeAt(0)
        }
        lines.add(line)
        runCatching {
            logFile?.appendText(line.render() + "\n")
        }
    }

    fun redirectTo(context: Context) {
        if (logFile == null) {
            init(context.filesDir)
        }
    }

    fun snapshot(): List<LogLine> = lines.toList()

    fun clear() {
        lines.clear()
        runCatching {
            logFile?.let {
                if (it.exists()) it.writeText("")
            }
        }
    }

    fun exportFile(context: Context): File? {
        if (logFile == null) init(context.filesDir)
        return logFile?.takeIf { it.isFile && it.length() > 0L }
    }
}
