package com.xtunnel.android.model

import android.content.Context

/**
 * GEO 分流·独立全局配置存储（定稿方案 v2 §2.3 + round9 扩展）。
 *
 * 代理模式开关：全局（off，route 不生效，现状）/ GEO（on，默认规则：
 * 广告拦截 + 国内直连 + 境外走隧道 + 兜底 proxy）。
 *
 * round9 扩展：
 *   - customRules：用户自定义规则（每行一条 `行为,条件`，如 proxy,domain:google.com、
 *     direct,domain:*.example.com），启动时合并进 rules.txt 传给 sidecar。
 *   - autoUpdate：GEO/规则库自动更新开关。
 *   - updateFrequency：更新频率（daily/weekly）。
 *
 * 与分应用（PerAppConfigStore）对称——代理模式也是全局语义，独立 store，
 * 不随 profile 增删改而变。
 */
object RouteConfigStore {
    private const val PREFS = "xtunnel_route"
    private const val KEY_ENABLED = "route_enabled"
    private const val KEY_CUSTOM_RULES = "route_custom_rules"
    private const val KEY_AUTO_UPDATE = "route_auto_update"
    private const val KEY_UPDATE_FREQ = "route_update_freq"

    enum class UpdateFrequency(val raw: String, val label: String) {
        Daily("daily", "每日"),
        Weekly("weekly", "每周");

        companion object {
            fun fromRaw(raw: String?): UpdateFrequency =
                entries.firstOrNull { it.raw == raw } ?: Daily
        }
    }

    data class Config(
        val enabled: Boolean = false,
        val customRules: List<String> = emptyList(),
        val autoUpdate: Boolean = true,
        val updateFrequency: UpdateFrequency = UpdateFrequency.Daily,
    )

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Config(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            customRules = prefs.getString(KEY_CUSTOM_RULES, null)
                ?.lineSequence()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() && !it.startsWith("#") }
                ?.toList()
                ?: emptyList(),
            autoUpdate = prefs.getBoolean(KEY_AUTO_UPDATE, true),
            updateFrequency = UpdateFrequency.fromRaw(prefs.getString(KEY_UPDATE_FREQ, null)),
        )
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_CUSTOM_RULES, config.customRules.joinToString("\n"))
            .putBoolean(KEY_AUTO_UPDATE, config.autoUpdate)
            .putString(KEY_UPDATE_FREQ, config.updateFrequency.raw)
            .apply()
    }
}