package com.xtunnel.android.model

import android.content.Context

/**
 * GEO 分流·独立全局配置存储（定稿方案 v2 §2.3）。
 *
 * 代理模式开关：全局（off，route 不生效，现状）/ GEO（on，默认规则：
 * 广告拦截 + 国内直连 + 境外走隧道 + 兜底 proxy）。自定义规则文件本期
 * 不做（方案列「高级」，后续里程碑）。
 *
 * 与分应用（PerAppConfigStore）对称——代理模式也是全局语义，独立 store，
 * 不随 profile 增删改而变。
 */
object RouteConfigStore {
    private const val PREFS = "xtunnel_route"
    private const val KEY_ENABLED = "route_enabled"

    data class Config(
        val enabled: Boolean = false,
    )

    fun load(context: Context): Config =
        Config(enabled = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false))

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .apply()
    }
}