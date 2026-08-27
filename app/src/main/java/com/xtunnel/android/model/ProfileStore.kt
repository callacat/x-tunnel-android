package com.xtunnel.android.model

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 多 profile 存储（点 2）：以 JSON 数组持久化全部用户配置，支持增删改 + 当前选中项。
 * 点 7：不再预置任何真实服务器地址/token——首次运行时为空模板，全部由用户填写。
 */
object ProfileStore {
    private const val PREFS = "xtunnel_profile"
    private const val KEY_PROFILES = "profiles"
    private const val KEY_ACTIVE_NAME = "active_profile"

    fun loadProfiles(context: Context): List<XTunnelProfile> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_PROFILES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { index ->
                fromJson(array.getJSONObject(index))
            }
        }.getOrDefault(emptyList())
    }

    fun activeProfileName(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_NAME, null)
    }

    fun loadActive(context: Context): XTunnelProfile? {
        val profiles = loadProfiles(context)
        if (profiles.isEmpty()) return null
        val activeName = activeProfileName(context)
        return profiles.firstOrNull { it.name == activeName } ?: profiles.first()
    }

    fun saveProfiles(context: Context, profiles: List<XTunnelProfile>, activeName: String?) {
        val array = JSONArray()
        profiles.forEach { array.put(toJson(it)) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PROFILES, array.toString())
            .putString(KEY_ACTIVE_NAME, activeName ?: profiles.firstOrNull()?.name)
            .apply()
    }

    fun toJson(profile: XTunnelProfile): JSONObject = JSONObject().apply {
        put("name", profile.name)
        put("server_url", profile.serverUrl)
        put("token", profile.token)
        put("socks_listen", profile.socksListen)
        put("metrics_listen", profile.metricsListen)
        put("cidr", profile.cidr)
        put("dns", profile.dns)
        put("ech", profile.ech)
        put("block_ports", profile.blockPorts)
        put("connections", profile.connections)
        put("insecure", profile.insecure)
        put("fallback", profile.fallback)
        put("dial_ips", profile.dialIPs)
        put("ip_strategy", profile.ipStrategy)
        put("dns_cache_ttl", profile.dnsCacheTtl)
    }

    fun fromJson(json: JSONObject): XTunnelProfile = json.let {
        XTunnelProfile(
            name = it.optString("name"),
            serverUrl = it.optString("server_url"),
            token = it.optString("token"),
            socksListen = it.optString("socks_listen", DefaultProfile.newProfile(1).socksListen),
            metricsListen = it.optString("metrics_listen", ""),
            cidr = it.optString("cidr", DefaultProfile.newProfile(1).cidr),
            dns = it.optString("dns", DefaultProfile.newProfile(1).dns),
            ech = it.optString("ech", DefaultProfile.newProfile(1).ech),
            blockPorts = it.optString("block_ports", DefaultProfile.newProfile(1).blockPorts),
            connections = it.optInt("connections", 1),
            insecure = it.optBoolean("insecure", false),
            fallback = it.optBoolean("fallback", false),
            dialIPs = it.optString("dial_ips", ""),
            ipStrategy = it.optString("ip_strategy", "4,6"),
            dnsCacheTtl = it.optString("dns_cache_ttl", "5m"),
        )
    }
}