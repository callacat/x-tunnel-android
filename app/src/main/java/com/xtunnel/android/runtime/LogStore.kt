package com.xtunnel.android.runtime

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 应用内日志采集器（点 5）：收集 sidecar 标准输出 + 运行时状态事件。
 * - 内存保留最近 [MAX_IN_MEMORY] 条环形缓冲，供 UI 实时滚动展示；
 * - 同时追加写入 [File]（纯文本，带时间戳），支持导出/分享给东哥。
 */
object LogStore {
    private const val MAX_IN_MEMORY = 500
    private const val LOG_FILE = "x-tunnel.log"

    private val lines = CopyOnWriteArrayList<LogLine>()
    private var logFile: File? = null

    data class LogLine(
        val timestampMillis: Long,
        val level: Level,
        val message: String,
    ) {
        fun render(): String =
            "${timeFormatted()} [${level.tag}] $message"

        private fun timeFormatted(): String =
            SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(timestampMillis))
    }

    enum class Level(val tag: String) {
        Info("INFO"),
        Error("ERROR"),
    }

    fun init(filesDir: File) {
        logFile = File(filesDir, LOG_FILE)
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