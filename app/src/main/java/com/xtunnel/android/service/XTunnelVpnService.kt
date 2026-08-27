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
import android.net.VpnService
import android.os.Build
import com.xtunnel.android.MainActivity
import com.xtunnel.android.R
import com.xtunnel.android.model.DefaultProfile
import com.xtunnel.android.model.XTunnelProfile
import com.xtunnel.android.runtime.XTunnelRuntimeManager

class XTunnelVpnService : VpnService() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForegroundService()
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
        XTunnelRuntimeManager.get(this).stop()
        super.onDestroy()
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

        fun start(context: Context, profile: XTunnelProfile = DefaultProfile.production) {
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
            context.startService(Intent(context, XTunnelVpnService::class.java).setAction(ACTION_STOP))
        }

        private fun Intent?.profileOrDefault(): XTunnelProfile {
            if (this == null) return DefaultProfile.production
            return XTunnelProfile(
                name = getStringExtra(EXTRA_PROFILE_NAME) ?: DefaultProfile.production.name,
                serverUrl = getStringExtra(EXTRA_SERVER_URL) ?: DefaultProfile.production.serverUrl,
                token = getStringExtra(EXTRA_TOKEN) ?: DefaultProfile.production.token,
                socksListen = getStringExtra(EXTRA_SOCKS_LISTEN) ?: DefaultProfile.production.socksListen,
                metricsListen = getStringExtra(EXTRA_METRICS_LISTEN) ?: DefaultProfile.production.metricsListen,
                cidr = getStringExtra(EXTRA_CIDR) ?: DefaultProfile.production.cidr,
                dns = getStringExtra(EXTRA_DNS) ?: DefaultProfile.production.dns,
                ech = getStringExtra(EXTRA_ECH) ?: DefaultProfile.production.ech,
                blockPorts = getStringExtra(EXTRA_BLOCK_PORTS) ?: DefaultProfile.production.blockPorts,
                connections = getIntExtra(EXTRA_CONNECTIONS, DefaultProfile.production.connections),
                insecure = getBooleanExtra(EXTRA_INSECURE, DefaultProfile.production.insecure),
                fallback = getBooleanExtra(EXTRA_FALLBACK, DefaultProfile.production.fallback),
                dialIPs = getStringExtra(EXTRA_DIAL_IPS) ?: DefaultProfile.production.dialIPs,
                ipStrategy = getStringExtra(EXTRA_IP_STRATEGY) ?: DefaultProfile.production.ipStrategy,
                dnsCacheTtl = getStringExtra(EXTRA_DNS_CACHE_TTL) ?: DefaultProfile.production.dnsCacheTtl,
            )
        }
    }
}
