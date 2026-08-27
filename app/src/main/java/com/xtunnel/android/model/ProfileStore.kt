package com.xtunnel.android.model

import android.content.Context

object ProfileStore {
    private const val PREFS = "xtunnel_profile"
    private const val KEY_NAME = "name"
    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_TOKEN = "token"
    private const val KEY_SOCKS_LISTEN = "socks_listen"
    private const val KEY_METRICS_LISTEN = "metrics_listen"
    private const val KEY_CIDR = "cidr"
    private const val KEY_DNS = "dns"
    private const val KEY_ECH = "ech"
    private const val KEY_BLOCK_PORTS = "block_ports"
    private const val KEY_CONNECTIONS = "connections"
    private const val KEY_INSECURE = "insecure"
    private const val KEY_FALLBACK = "fallback"
    private const val KEY_DIAL_IPS = "dial_ips"
    private const val KEY_IP_STRATEGY = "ip_strategy"
    private const val KEY_DNS_CACHE_TTL = "dns_cache_ttl"

    private fun defaults(): XTunnelProfile = DefaultProfile.production

    fun load(context: Context): XTunnelProfile {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return XTunnelProfile(
            name = prefs.getString(KEY_NAME, null) ?: defaults().name,
            serverUrl = prefs.getString(KEY_SERVER_URL, null) ?: defaults().serverUrl,
            token = prefs.getString(KEY_TOKEN, null) ?: defaults().token,
            socksListen = prefs.getString(KEY_SOCKS_LISTEN, null) ?: defaults().socksListen,
            metricsListen = prefs.getString(KEY_METRICS_LISTEN, null) ?: defaults().metricsListen,
            cidr = prefs.getString(KEY_CIDR, null) ?: defaults().cidr,
            dns = prefs.getString(KEY_DNS, null) ?: defaults().dns,
            ech = prefs.getString(KEY_ECH, null) ?: defaults().ech,
            blockPorts = prefs.getString(KEY_BLOCK_PORTS, null) ?: defaults().blockPorts,
            connections = prefs.getInt(KEY_CONNECTIONS, defaults().connections),
            insecure = prefs.getBoolean(KEY_INSECURE, defaults().insecure),
            fallback = prefs.getBoolean(KEY_FALLBACK, defaults().fallback),
            dialIPs = prefs.getString(KEY_DIAL_IPS, null) ?: defaults().dialIPs,
            ipStrategy = prefs.getString(KEY_IP_STRATEGY, null) ?: defaults().ipStrategy,
            dnsCacheTtl = prefs.getString(KEY_DNS_CACHE_TTL, null) ?: defaults().dnsCacheTtl,
        )
    }

    fun save(context: Context, profile: XTunnelProfile) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NAME, profile.name)
            .putString(KEY_SERVER_URL, profile.serverUrl)
            .putString(KEY_TOKEN, profile.token)
            .putString(KEY_SOCKS_LISTEN, profile.socksListen)
            .putString(KEY_METRICS_LISTEN, profile.metricsListen)
            .putString(KEY_CIDR, profile.cidr)
            .putString(KEY_DNS, profile.dns)
            .putString(KEY_ECH, profile.ech)
            .putString(KEY_BLOCK_PORTS, profile.blockPorts)
            .putInt(KEY_CONNECTIONS, profile.connections)
            .putBoolean(KEY_INSECURE, profile.insecure)
            .putBoolean(KEY_FALLBACK, profile.fallback)
            .putString(KEY_DIAL_IPS, profile.dialIPs)
            .putString(KEY_IP_STRATEGY, profile.ipStrategy)
            .putString(KEY_DNS_CACHE_TTL, profile.dnsCacheTtl)
            .apply()
    }
}
