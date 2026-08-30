package com.xtunnel.android.runtime

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.xtunnel.android.model.PerAppConfigStore
import com.xtunnel.android.model.XTunnelProfile
import hev.htproxy.TProxyService
import java.io.File

class VpnDataPathController(private val service: VpnService) {
    private var tun: ParcelFileDescriptor? = null
    private var tproxy: TProxyService? = null

    fun start(profile: XTunnelProfile): VpnDataPathResult {
        val nativeDir = File(service.applicationInfo.nativeLibraryDir)
        val tun2socks = File(nativeDir, TUN2SOCKS_EXECUTABLE)
        if (!tun2socks.isFile) {
            return VpnDataPathResult(
                state = VpnDataPathState.MissingTun2Socks,
                detail = "缺少 tun2socks 原生运行库: $TUN2SOCKS_EXECUTABLE",
            )
        }

        return runCatching {
            val descriptor = establishTun(profile)
            tun = descriptor
            val configFile = writeTun2SocksConfig(profile)
            TProxyService().also { bridge ->
                bridge.TProxyStartService(configFile.absolutePath, descriptor.fd)
                tproxy = bridge
            }
            VpnDataPathResult(
                state = VpnDataPathState.Running,
                detail = "TUN 已建立；tun2socks 正在转发到 ${profile.socksListen}",
            )
        }.getOrElse { error ->
            close()
            VpnDataPathResult(
                state = VpnDataPathState.Failed,
                detail = error.message ?: error.javaClass.simpleName,
            )
        }
    }

    fun close() {
        runCatching { tproxy?.TProxyStopService() }
        tproxy = null
        tun?.close()
        tun = null
    }

    private fun establishTun(profile: XTunnelProfile): ParcelFileDescriptor {
        val builder = service.Builder()
            .setSession("x-tunnel: ${profile.name}")
            .setMtu(VPN_MTU)
            .addAddress(PRIVATE_V4_CLIENT, PRIVATE_V4_PREFIX)
            .addRoute("0.0.0.0", 0)
            // round41：GEO 模式下 TUN DNS 用国内 DNS（直连可达、不占隧道）。
            // r40 实测 DNS 全走隧道（1.1.1.1:53 ×70 次/分钟），每次连接都叠加
            // 隧道 DNS 往返 → 国内访问感知慢的主因之一。全局模式保持 1.1.1.1。
            .addDnsServer(if (RouteConfigStore.load(service).enabled) GEO_DNS else DEFAULT_DNS)

        // 分应用代理·三模式（定稿方案 v2 §2.1）：off/allow/disallow。
        // x-tunnel 例外：自身在 VPN 外（sidecar 拨号走物理网络），故
        // allow 白名单必须剔除自身防自环（勿照抄 warp-go 的 add(self)）。
        val perApp = PerAppConfigStore.loadFiltered(service)
        when (perApp.mode) {
            PerAppConfigStore.Mode.Allow -> {
                // 白名单：仅勾选应用走隧道，其余（含壳自身与 native 子进程）直连。
                // VpnService 语义：调用过至少一次 addAllowedApplication 即进入白名单；
                // 空名单会退回全局代理（语义反转），硬性要求至少一条。
                require(perApp.packages.isNotEmpty()) { "分应用代理白名单为空，请至少勾选一个应用" }
                perApp.packages.forEach { pkg ->
                    try {
                        builder.addAllowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        // 失效包名跳过（loadFiltered 已过滤，此处防御性兜底）。
                    }
                }
            }
            PerAppConfigStore.Mode.Disallow -> {
                // 黑名单：勾选应用直连，其余走隧道。必须包含壳自身（现状行为），
                // 否则壳/子进程流量会进 TUN 自环。
                val disallowed = perApp.packages + service.packageName
                disallowed.forEach { pkg ->
                    try {
                        builder.addDisallowedApplication(pkg)
                    } catch (_: PackageManager.NameNotFoundException) {
                        // 失效包名跳过。
                    }
                }
            }
            PerAppConfigStore.Mode.Off -> {
                // 全局代理（默认）：所有应用走隧道，仅排除壳自身避免自环（现状）。
                try {
                    builder.addDisallowedApplication(service.packageName)
                } catch (_: PackageManager.NameNotFoundException) {
                    // The current package should exist; keep setup resilient.
                }
            }
        }

        return builder.establish() ?: error("Android 拒绝了 VpnService.Builder.establish()")
    }

    private fun writeTun2SocksConfig(profile: XTunnelProfile): File {
        val endpoint = profile.socksEndpoint()
        val configFile = File(service.filesDir, "runtime/tun2socks.yml")
        configFile.parentFile?.mkdirs()
        configFile.writeText(
            """
            tunnel:
              mtu: $VPN_MTU
              ipv4: $PRIVATE_V4_CLIENT

            socks5:
              port: ${endpoint.port}
              address: ${endpoint.host}
              udp: 'udp'

            misc:
              task-stack-size: 24576
              tcp-buffer-size: 4096
              connect-timeout: 10000
              tcp-read-write-timeout: 300000
              udp-read-write-timeout: 60000
              log-level: warn
            """.trimIndent(),
        )
        return configFile
    }

    data class VpnDataPathResult(
        val state: VpnDataPathState,
        val detail: String,
    )

    companion object {
        const val TUN2SOCKS_EXECUTABLE = "libhev-socks5-tunnel.so"

        private const val VPN_MTU = 1500
        private const val PRIVATE_V4_CLIENT = "172.31.255.2"
        private const val PRIVATE_V4_PREFIX = 30
        private const val DEFAULT_DNS = "1.1.1.1"
        // round41：GEO 模式的 TUN DNS——阿里公共 DNS，境内直连可达，不经隧道。
        private const val GEO_DNS = "223.5.5.5"
    }
}

private data class SocksEndpoint(val host: String, val port: Int)

private fun XTunnelProfile.socksEndpoint(): SocksEndpoint {
    val raw = socksListen.removePrefix("socks5://")
    val authority = raw.substringAfter('@', raw)
    val hostPort = authority.substringBefore('/')
    val host = hostPort.substringBeforeLast(':')
    val port = hostPort.substringAfterLast(':').toIntOrNull()
        ?: error("无效的 SOCKS5 监听端口: $socksListen")
    val trimmedHost = host.trim('[', ']')
    require(trimmedHost.isNotBlank()) { "无效的 SOCKS5 监听主机: $socksListen" }
    val connectHost = when (trimmedHost) {
        "0.0.0.0", "::" -> "127.0.0.1"
        else -> trimmedHost
    }
    return SocksEndpoint(host = connectHost, port = port)
}
