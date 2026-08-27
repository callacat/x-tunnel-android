package com.xtunnel.android.runtime

import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
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
            .addDnsServer(DEFAULT_DNS)

        try {
            builder.addDisallowedApplication(service.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            // The current package should exist; keep setup resilient for unusual test contexts.
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
