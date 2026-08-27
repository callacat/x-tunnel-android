package com.xtunnel.android.model

import java.net.URI

data class XTunnelProfile(
    val name: String,
    val serverUrl: String,
    val token: String,
    val socksListen: String = "socks5://127.0.0.1:11080",
    val metricsListen: String = "",
    val cidr: String = "0.0.0.0/0,::/0",
    val dns: String = "https://doh.pub/dns-query",
    val ech: String = "cloudflare-ech.com",
    val blockPorts: String = "443",
    val connections: Int = 1,
    val insecure: Boolean = false,
    val fallback: Boolean = false,
    // 抗干扰参数（对应 x-tunnel sidecar 的 -ip / -ips / -dns-cache-ttl）
    val dialIPs: String = "",
    val ipStrategy: String = "4,6",
    val dnsCacheTtl: String = "5m",
)

object DefaultProfile {
    // 生产默认结构：token 不写死进仓库，首次由用户填写。
    val production = XTunnelProfile(
        name = "Production",
        serverUrl = "wss://xt.ipyx.eu.cc",
        token = "",
    )

    // 本地联调：连本机 x-tunnel 服务端自测。
    val local = XTunnelProfile(
        name = "Local test",
        serverUrl = "ws://127.0.0.1:18080/tunnel",
        token = "local-test-token",
    )
}

fun XTunnelProfile.validationError(): String? {
    if (name.isBlank()) return "Profile name is required"
    if (token.isBlank()) return "Token is required"
    if (connections !in 1..16) return "Connections must be between 1 and 16"
    if (blockPorts.isBlank()) return "UDP block ports are required"

    val server = runCatching { URI(serverUrl) }.getOrNull()
        ?: return "Server URL is invalid"
    if (server.scheme !in setOf("ws", "wss")) {
        return "Server URL must start with ws:// or wss://"
    }
    if (server.host.isNullOrBlank()) return "Server URL host is required"

    // 抗干扰参数校验：与 sidecar 的 flag 语义保持一致（ip 支持 IP/IP:port/合法主机名，逗号分隔）。
    // 这里只做宽松的非空条目校验，具体格式由 sidecar 权威校验并在运行时详情反馈，避免 UI 误拦合法值（如 IPv6）。
    if (dialIPs.isNotBlank()) {
        val entries = dialIPs.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (entries.isEmpty()) return "Dial IPs must not contain empty entries"
        if (entries.any { it.substringBefore(':').isBlank() }) return "Dial IP entry is invalid"
    }
    if (ipStrategy !in setOf("", "4", "6", "4,6", "6,4")) {
        return "IP strategy must be one of: (empty), 4, 6, 4,6, 6,4"
    }
    runCatching { parseDnsCacheTtlToMillis(dnsCacheTtl) }.getOrElse {
        return "DNS cache TTL is invalid (e.g. 5m, 30s or 0)"
    }

    val socks = runCatching { URI(socksListen) }.getOrNull()
        ?: return "SOCKS listen URL is invalid"
    if (socks.scheme != "socks5") return "SOCKS listen must start with socks5://"
    if (socks.host.isNullOrBlank()) return "SOCKS listen host is required"
    if (socks.port !in 1..65535) return "SOCKS listen port is invalid"

    if (metricsListen.isNotBlank()) {
        val metrics = runCatching { URI("tcp://$metricsListen") }.getOrNull()
            ?: return "Metrics listen address is invalid"
        if (metrics.host.isNullOrBlank() || metrics.port !in 1..65535) {
            return "Metrics listen address is invalid"
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
