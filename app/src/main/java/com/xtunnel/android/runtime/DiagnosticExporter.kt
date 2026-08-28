package com.xtunnel.android.runtime

import android.content.Context
import android.os.Build
import com.xtunnel.android.model.ThemePrefs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 第 9 点：诊断包导出（参照 warp-go debugdiag 机制）。
 * 一键打包关键数据为 zip，供东哥导出后转发老马分析，区分「客户端数据面不转」vs「服务端不回」。
 *
 * 打包内容（token 一律脱敏）：
 *  - diagnostics.json  流量统计（bytes_sent/received 累计）、client_reconnects 计数、channels
 *  - logs.txt          应用内 LogStore 最近日志
 *  - crash-logcat.txt  崩溃日志（AndroidRuntime FATAL EXCEPTION 段 + 最近错误行）
 *  - info.txt          基础信息（Android 版本/网络类型/App 版本/commit）
 */
object DiagnosticExporter {

    fun export(context: Context, runtime: XTunnelRuntimeManager): File? {
        // 九轮修复：诊断包存 App 专属外部 Download 目录（getExternalFilesDir/Download），
        // 文件管理器可直接访问、不依赖 FileProvider 网络分享（东哥可手动发文件）。
        val exportDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            ?: File(context.filesDir, "diagnostics").also { it.mkdirs() }
        exportDir.mkdirs()
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val zipFile = File(exportDir, "x-tunnel-diag-${stamp}.zip")

        ZipOutputStream(zipFile.outputStream().buffered()).use { zos ->
            // 1. 流量统计 + 连接状态（由 RuntimeManager 采集，token 已脱敏）
            val diag = runtime.collectDiagnostics()
            zos.putNextEntry(ZipEntry("diagnostics.json"))
            zos.write(diag.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2. 应用内日志
            val logLines = LogStore.snapshot().joinToString("\n") { it.render() }
            zos.putNextEntry(ZipEntry("logs.txt"))
            zos.write(logLines.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 2b. 崩溃日志（八轮修复：收集 FATAL EXCEPTION 段 + 最近错误行）
            val crashLines = collectCrashLogcat()
            zos.putNextEntry(ZipEntry("crash-logcat.txt"))
            zos.write(crashLines.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            // 3. 基础信息
            val info = buildString {
                appendLine("collected_at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("android_sdk: ${Build.VERSION.SDK_INT}")
                appendLine("android_release: ${Build.VERSION.RELEASE}")
                appendLine("manufacturer: ${Build.MANUFACTURER}")
                appendLine("model: ${Build.MODEL}")
                appendLine("network_type: " + networkType(context))
                appendLine("theme_mode: ${ThemePrefs.load(context).name}")
                appendLine("app_version: " + appVersion(context))
            }
            zos.putNextEntry(ZipEntry("info.txt"))
            zos.write(info.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        // 直接返回文件路径（存 Download 目录，文件管理器可访问），不再依赖 FileProvider 分享。
        return zipFile
    }

    // 八轮修复·诊断包收崩溃日志：dump logcat 本应用进程的 crash 段。
    // minSdk 23 无 READ_LOGS 权限时只能抓到自身 UID 的 logcat（Android 4.1+ 允许读自身进程日志）。
    private fun collectCrashLogcat(): String {
        return try {
            val pid = android.os.Process.myPid()
            val process = ProcessBuilder(
                "logcat", "-d", "-v", "threadtime",
                "--pid=$pid",
                "AndroidRuntime:E", "libc:F", "DEBUG:F", "*:S",
            ).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            // 截取最近 200 行，避免 zip 过大
            val lines = out.lineSequence().toList()
            val recent = lines.takeLast(200)
            if (recent.isEmpty()) "（无本进程崩溃日志）" else recent.joinToString("\n")
        } catch (e: Exception) {
            "无法收集 logcat: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    private fun appVersion(context: Context): String {
        return try {
            val pm = context.packageManager
            val info = pm.getPackageInfo(context.packageName, 0)
            info.versionName + " (code ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode})"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun networkType(context: Context): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val n = cm.activeNetwork
            val caps = if (n != null) cm.getNetworkCapabilities(n) else null
            when {
                caps == null -> "unknown"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "other"
            }
        } catch (e: Exception) {
            "unknown"
        }
    }
}