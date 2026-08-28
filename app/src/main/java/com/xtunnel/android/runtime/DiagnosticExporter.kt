package com.xtunnel.android.runtime

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
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
 *  - traffic.json  流量统计（bytes_sent/received 累计）、client_reconnects 计数
 *  - status.json   连接状态（channels up/down/rtt）、runtime_state、pid
 *  - logs.txt      应用内 LogStore 最近日志
 *  - info.txt      基础信息（Android 版本/网络类型/App 版本）
 */
object DiagnosticExporter {

    fun export(context: Context, runtime: XTunnelRuntimeManager): Uri? {
        val exportDir = File(context.filesDir, "diagnostics")
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

            // 3. 基础信息
            val info = buildString {
                appendLine("collected_at: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                appendLine("android_sdk: ${Build.VERSION.SDK_INT}")
                appendLine("android_release: ${Build.VERSION.RELEASE}")
                appendLine("manufacturer: ${Build.MANUFACTURER}")
                appendLine("model: ${Build.MODEL}")
                appendLine("network_type: " + networkType(context))
                appendLine("theme_mode: ${ThemePrefs.load(context).name}")
            }
            zos.putNextEntry(ZipEntry("info.txt"))
            zos.write(info.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        // 经 FileProvider 生成分享 URI（与日志导出同款）
        return FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            zipFile,
        )
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