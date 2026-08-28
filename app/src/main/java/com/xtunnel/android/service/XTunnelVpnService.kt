package com.xtunnel.android.service

import android.app.Notification
import android.app.Notification.Builder
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.ConnectivityManager
import android.net.Network
import android.net.VpnService
import android.os.Build
import com.xtunnel.android.MainActivity
import com.xtunnel.android.R
import com.xtunnel.android.model.DefaultProfile
import com.xtunnel.android.model.XTunnelProfile
import com.xtunnel.android.runtime.LogStore
import com.xtunnel.android.runtime.XTunnelRuntimeManager

class XTunnelVpnService : VpnService() {
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // 点 6 修复：真正停止 sidecar 进程后再停服务。此前只 stopSelf()，
                // 未杀 sidecar 子进程（进程残留、日志无 graceful shutdown 记录）。
                // RuntimeManager.stop 内部会 kill 子进程 + 复位状态。
                XTunnelRuntimeManager.get(this).stop()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundService()
                registerNetworkCallback()
                XTunnelRuntimeManager.get(this).start(intent.profileOrDefault(), this)
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        XTunnelRuntimeManager.get(this).stop()
        stopSelf()
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        XTunnelRuntimeManager.get(this).stop()
        super.onDestroy()
    }

    // 八轮修复·网络切换监听：飞行模式开关 = 蜂窝网络重连（IPv4/IPv6 路由变化）。
    // 将「网络丢失/恢复」事件记入 LogStore → 诊断包可见，辅助定位「切换后无法访问外网」。
    // 不盲目 stop+start 打断连接（根因已在服务端 DNS 回包侧），先记录事件供分析。
    private fun registerNetworkCallback() {
        unregisterNetworkCallback()
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                LogStore.append(LogStore.Level.Info, "网络可用: ${describeNetwork(cm, network)}")
            }
            override fun onLost(network: Network) {
                LogStore.append(LogStore.Level.Info, "网络丢失（可能飞行模式切换）: ${describeNetwork(cm, network)}")
            }
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) {
                LogStore.append(LogStore.Level.Info, "网络能力变化: ${describeNetwork(cm, network)}")
            }
        }
        networkCallback = cb
        // 用默认网络回调监听网络切换（飞行模式开关 = 蜂窝重连）。
        // API < 24（minSdk 23）不监听（东哥真机 Android 15 无影响）。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            cm.registerDefaultNetworkCallback(cb)
        } else {
            LogStore.append(LogStore.Level.Info, "网络切换监听需要 Android 7.0+，本机不启用")
        }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { cb ->
            runCatching {
                val cm = getSystemService(ConnectivityManager::class.java)
                cm?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null
    }

    private fun describeNetwork(cm: ConnectivityManager, network: Network): String {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
        val transport = when {
            caps == null -> "unknown"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        return "$transport"
    }

    private fun startForegroundService() {
        ensureNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.vpn_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, XTunnelVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(getString(R.string.vpn_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_vpn_key),
                    getString(R.string.disconnect),
                    stopIntent,
                ).build(),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "xtunnel_vpn"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_START = "com.xtunnel.android.action.START"
        private const val ACTION_STOP = "com.xtunnel.android.action.STOP"
        private const val EXTRA_PROFILE_NAME = "profile_name"
        private const val EXTRA_SERVER_URL = "server_url"
        private const val EXTRA_TOKEN = "token"
        private const val EXTRA_SOCKS_LISTEN = "socks_listen"
        private const val EXTRA_METRICS_LISTEN = "metrics_listen"
        private const val EXTRA_CIDR = "cidr"
        private const val EXTRA_DNS = "dns"
        private const val EXTRA_ECH = "ech"
        private const val EXTRA_BLOCK_PORTS = "block_ports"
        private const val EXTRA_CONNECTIONS = "connections"
        private const val EXTRA_INSECURE = "insecure"
        private const val EXTRA_FALLBACK = "fallback"
        private const val EXTRA_DIAL_IPS = "dial_ips"
        private const val EXTRA_IP_STRATEGY = "ip_strategy"
        private const val EXTRA_DNS_CACHE_TTL = "dns_cache_ttl"

        fun start(context: Context, profile: XTunnelProfile) {
            val intent = Intent(context, XTunnelVpnService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_PROFILE_NAME, profile.name)
                .putExtra(EXTRA_SERVER_URL, profile.serverUrl)
                .putExtra(EXTRA_TOKEN, profile.token)
                .putExtra(EXTRA_SOCKS_LISTEN, profile.socksListen)
                .putExtra(EXTRA_METRICS_LISTEN, profile.metricsListen)
                .putExtra(EXTRA_CIDR, profile.cidr)
                .putExtra(EXTRA_DNS, profile.dns)
                .putExtra(EXTRA_ECH, profile.ech)
                .putExtra(EXTRA_BLOCK_PORTS, profile.blockPorts)
                .putExtra(EXTRA_CONNECTIONS, profile.connections)
                .putExtra(EXTRA_INSECURE, profile.insecure)
                .putExtra(EXTRA_FALLBACK, profile.fallback)
                .putExtra(EXTRA_DIAL_IPS, profile.dialIPs)
                .putExtra(EXTRA_IP_STRATEGY, profile.ipStrategy)
                .putExtra(EXTRA_DNS_CACHE_TTL, profile.dnsCacheTtl)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            // 六轮修复：App 内点「停止」按钮从 Activity context 出发，若 App 在后台
            // （VPN 运行中切走/权限弹窗切后台）调 startService 会抛 IllegalStateException
            // → 闪退。改 stopService（不区分前后台，触发 onDestroy → RuntimeManager.stop）。
            context.stopService(Intent(context, XTunnelVpnService::class.java))
        }

        private fun Intent?.profileOrDefault(): XTunnelProfile {
            val fallback = DefaultProfile.blank
            if (this == null) return fallback
            return XTunnelProfile(
                name = getStringExtra(EXTRA_PROFILE_NAME) ?: fallback.name,
                serverUrl = getStringExtra(EXTRA_SERVER_URL) ?: fallback.serverUrl,
                token = getStringExtra(EXTRA_TOKEN) ?: fallback.token,
                socksListen = getStringExtra(EXTRA_SOCKS_LISTEN) ?: fallback.socksListen,
                metricsListen = getStringExtra(EXTRA_METRICS_LISTEN) ?: fallback.metricsListen,
                cidr = getStringExtra(EXTRA_CIDR) ?: fallback.cidr,
                dns = getStringExtra(EXTRA_DNS) ?: fallback.dns,
                ech = getStringExtra(EXTRA_ECH) ?: fallback.ech,
                blockPorts = getStringExtra(EXTRA_BLOCK_PORTS) ?: fallback.blockPorts,
                connections = getIntExtra(EXTRA_CONNECTIONS, fallback.connections),
                insecure = getBooleanExtra(EXTRA_INSECURE, fallback.insecure),
                fallback = getBooleanExtra(EXTRA_FALLBACK, fallback.fallback),
                dialIPs = getStringExtra(EXTRA_DIAL_IPS) ?: fallback.dialIPs,
                ipStrategy = getStringExtra(EXTRA_IP_STRATEGY) ?: fallback.ipStrategy,
                dnsCacheTtl = getStringExtra(EXTRA_DNS_CACHE_TTL) ?: fallback.dnsCacheTtl,
            )
        }
    }
}
