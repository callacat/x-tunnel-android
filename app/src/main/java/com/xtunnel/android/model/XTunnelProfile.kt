package com.xtunnel.android.model

import java.net.URI

data class XTunnelProfile(
    val name: String,
    val serverUrl: String,
    val token: String,
    val socksListen: String = "socks5://127.0.0.1:11080",
    val metricsListen: String = "",
    val cidr: String = "0.0.0.0/0,::/0",
    // 点 7：不内置任何真实域名；dns/ech 留空，由用户在高级设置按需填写（空则走 sidecar 默认）。
    val dns: String = "",
    val ech: String = "",
    val blockPorts: String = "443",
    val connections: Int = 1,
    val insecure: Boolean = false,
    val fallback: Boolean = false,
    // 抗干扰参数（对应 x-tunnel sidecar 的 -ip / -ips / -dns-cache-ttl）
    val dialIPs: String = "",
    val ipStrategy: String = "4,6",
    val dnsCacheTtl: String = "5m",
    // 百度中转（baidu-tunnel，recvv22hIoqhoe）：开启后经百度云 CONNECT 隧道连接服务端，
    // 用于 CF 直连被干扰的场景（内核 websocket_front_proxy 能力，默认参数=实测可用值）。
    // ⚠ 开启时优选 IP 不生效：内核会用优选 IP 改写 CONNECT 目标为 CF IP → 百度 503。
    val baiduRelay: Boolean = false,
    val baiduServer: String = DEFAULT_BAIDU_SERVER,
    val baiduConnectHost: String = DEFAULT_BAIDU_CONNECT_HOST,
    val baiduHeaders: Map<String, String> = DEFAULT_BAIDU_HEADERS,
) {
    companion object {
        const val DEFAULT_BAIDU_SERVER = "cloudnproxy.baidu.com:443"
        const val DEFAULT_BAIDU_CONNECT_HOST = "sptest.baidu.com"
        // 默认请求头 = 2026-09-13 实测放行值（baiduboxapp 伪装 UA + X-T5-Auth）；全部可配置，不写死在逻辑里。
        val DEFAULT_BAIDU_HEADERS: Map<String, String> = linkedMapOf(
            "X-T5-Auth" to "482857715",
            "User-Agent" to "okhttp/3.11.0 Dalvik/2.1.0 (Linux; Build/RKQ1.200826.002) baiduboxapp/11.0.5.12 (Baidu; P1 11)",
            "Proxy-Connection" to "keep-alive",
            "Connection" to "keep-alive",
        )
    }
}

object DefaultProfile {
    // 点 7：不内置任何真实服务器地址/域名/token。新 profile 一律为空模板，由用户填写。
    private var counter = 0

    fun newProfile(seed: Int? = null): XTunnelProfile {
        val n = seed ?: ++counter
        return XTunnelProfile(
            name = "新的配置 $n",
            serverUrl = "",
            token = "",
            ipStrategy = "4,6",
            dnsCacheTtl = "5m",
        )
    }

    // 旧数据迁移：不预置地址，仅作空模板占位。
    val blank = newProfile(0).copy(name = "新配置")
}

// 配置身份标识：仅用于 UI 列表按名称去重校验（用户可自由重命名）。
fun XTunnelProfile.profileId(): String = name

/**
 * 校验配置是否可保存/启动（点 4：提示语中文化）。
 * serverUrl 允许为空——留空仅供保存草稿，启动时由 UI 侧「填写服务器地址」再拦。
 */
fun XTunnelProfile.validationError(): String? {
    if (name.isBlank()) return "配置名称不能为空"
    if (token.isBlank()) return "Token 不能为空"
    if (connections !in 1..16) return "连接数须在 1 到 16 之间"
    if (blockPorts.isBlank()) return "UDP 阻断端口不能为空"

    if (serverUrl.isBlank()) return "服务器地址不能为空"

    val server = runCatching { URI(serverUrl) }.getOrNull()
        ?: return "服务器地址格式无效"
    if (server.scheme !in setOf("ws", "wss")) {
        return "服务器地址须以 ws:// 或 wss:// 开头"
    }
    if (server.host.isNullOrBlank()) return "服务器地址缺少主机名"

    // 抗干扰参数校验：与 sidecar 的 flag 语义保持一致（ip 支持 IP/IP:port/合法主机名，逗号分隔）。
    // 这里只做宽松的非空条目校验，具体格式由 sidecar 权威校验并在运行时详情反馈，避免 UI 误拦合法值（如 IPv6）。
    if (dialIPs.isNotBlank()) {
        val entries = dialIPs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return "优选 IP 不能包含空条目"
        if (entries.any { it.substringBefore(':').isBlank() }) return "优选 IP 条目无效"
    }
    if (ipStrategy !in setOf("", "4", "6", "4,6", "6,4")) {
        return "IP 栈须为：留空、4、6、4,6、6,4 之一"
    }
    // 百度中转：开启时校验必填项（host:port 格式宽松，权威校验在 sidecar）。
    if (baiduRelay) {
        if (baiduServer.isBlank()) return "百度中转已开启：中转服务器不能为空"
        if (!baiduServer.contains(':')) return "百度中转服务器须为 主机:端口 格式"
        if (baiduHeaders.keys.any { it.isBlank() }) return "百度中转请求头包含空名称"
    }
    runCatching { parseDnsCacheTtlToMillis(dnsCacheTtl) }.getOrElse {
        return "DNS 缓存 TTL 无效（如 5m、30s 或 0）"
    }

    val socks = runCatching { URI(socksListen) }.getOrNull()
        ?: return "本地 SOCKS 地址无效"
    if (socks.scheme != "socks5") return "本地 SOCKS 须以 socks5:// 开头"
    if (socks.host.isNullOrBlank()) return "本地 SOCKS 缺少主机名"
    if (socks.port !in 1..65535) return "本地 SOCKS 端口无效"

    if (metricsListen.isNotBlank()) {
        val metrics = runCatching { URI("tcp://$metricsListen") }.getOrNull()
            ?: return "Metrics 监听地址无效"
        if (metrics.host.isNullOrBlank() || metrics.port !in 1..65535) {
            return "Metrics 监听地址无效"
        }
    }

    return null
}

// 解析 sidecar 的 Go duration 字符串（如 5m/30s/500ms/0），返回毫秒；用于 UI 侧预校验。
// 仅做校验用途，准确性以 sidecar 的 time.ParseDuration 为准。
fun parseDnsCacheTtlToMillis(ttl: String): Long {
    val trimmed = ttl.trim()
    if (trimmed == "0") return 0L
    val match = Regex("^(\\d+)(ms|s|m|h)?$").find(trimmed)
        ?: throw IllegalArgumentException("invalid duration: $ttl")
    val value = match.groupValues[1].toLong()
    return when (match.groupValues[2]) {
        "ms" -> value
        "s" -> value * 1_000L
        "m" -> value * 60_000L
        "h" -> value * 3_600_000L
        else -> throw IllegalArgumentException("invalid duration unit: $ttl")
    }
}
