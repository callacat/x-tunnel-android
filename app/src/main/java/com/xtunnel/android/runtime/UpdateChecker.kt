package com.xtunnel.android.runtime

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * UX-R3 第 3 项（东哥 2026-09-14）：检查更新——「无新版提示已是最新；
 * 有新版显示版本号+链接跳浏览器」。
 *
 * 方案（codex 预研 2026-09-14 定稿）：零新增依赖，沿用仓库 HttpURLConnection
 * 先例（XTunnelRuntimeManager.downloadTo）；GitHub Releases API 经镜像链
 * gh-proxy.org → gh-proxy.com → 直连兜底（本机实测：直连 api.github.com 403、
 * 镜像前缀 200 且 JSON 完整）。版本比对口径与 CI release.yml 对齐：
 * `major*1e6 + minor*1e3 + patch`，带 `-` 预发布后缀再 +900。
 */
object UpdateChecker {
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/callacat/x-tunnel-android/releases/latest"

    // 镜像链：gh-proxy 前缀实测可用；空串=直连（海外网络/挂梯环境兜底）。
    // gh-proxy 对大文件有截断风险（预研实测 4.2MB 只回 650KB），本检查为
    // 小 JSON（~2KB）影响可忽略——但仍以「JSON 解析成功且 tag_name 非空且
    // 非 draft/prerelease」判定该镜像有效，防截断假数据。
    private val MIRROR_PREFIXES = listOf("https://gh-proxy.org/", "https://gh-proxy.com/", "")

    /** 检查结果三态（N3 验收判据映射）。 */
    sealed class Result {
        /** 已是最新（或版本不可解析时保守归此态，UI 同时给手动兜底）。 */
        data class Latest(val currentVersion: String) : Result()
        /** 发现新版：latestVersion 为远端 tag（含 v 前缀原样，如 v0.3.0）。 */
        data class NewerVersion(val currentVersion: String, val latestVersion: String) : Result()
        /** 全部镜像失败：网络不可达等。 */
        data object CheckFailed : Result()
    }

    /**
     * 版本数值化：剥 v 前缀 → major.minor.patch 三段。
     * 兼容三种实际形态：`0.2.0`（正式版）、`0.1.0-round456`（CI debug 轮次）、
     * `0.3.0-rc.1`（预发 tag）。任一数值段解析失败返回 null（调用方保守处理）。
     * 口径与 CI release.yml L54-60 完全一致（含 `-` 后缀 +900）。
     */
    fun versionOrdinal(raw: String): Long? {
        val s = raw.trim().removePrefix("v")
        if (s.isEmpty()) return null
        val parts = s.split('.')
        if (parts.size < 3) return null
        val major = parts[0].toIntOrNull() ?: return null
        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].substringBefore('-').toIntOrNull() ?: return null
        var ordinal = major * 1_000_000L + minor * 1_000L + patch
        if (s.contains('-')) ordinal += 900
        return ordinal
    }

    /**
     * 纯函数比对（N3「模拟新版」验收：装高版本 debug 包即可触发 NewerVersion 态）。
     * 当前版本不可解析 → 保守 Latest（不误报新版）；远端 tag 不可解析 → null 由调用方判失败。
     */
    fun compare(currentVersion: String, latestTag: String): Result? {
        val current = versionOrdinal(currentVersion)
        val remote = versionOrdinal(latestTag) ?: return null
        if (current != null && remote > current) {
            return Result.NewerVersion(currentVersion, latestTag)
        }
        return Result.Latest(currentVersion)
    }

    /**
     * 拉取最新 release tag（后台线程调用，禁主线程）。全部镜像失败返回 null。
     */
    fun fetchLatestTag(): String? {
        for (mirror in MIRROR_PREFIXES) {
            val tag = runCatching { fetchTagVia(mirror + LATEST_RELEASE_URL) }.getOrNull()
            if (!tag.isNullOrBlank()) return tag
        }
        return null
    }

    private fun fetchTagVia(url: String): String? {
        // 注意：XTunnelRuntimeManager 文件级 private 的 HttpURLConnection.use
        // 扩展跨文件不可见（交叉审查实锤），此处用显式 try/finally disconnect。
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "x-tunnel-android (update checker)")
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JSONObject(body)
            // 防御：draft/prerelease 不当作「可更新到的最新版」（未公开发布不推给用户）。
            if (obj.optBoolean("draft", false) || obj.optBoolean("prerelease", false)) {
                throw IOException("latest is draft/prerelease")
            }
            return obj.optString("tag_name").takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }
}
