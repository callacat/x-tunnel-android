package com.xtunnel.android.model

import android.content.Context

/**
 * 分应用代理·独立全局配置存储（定稿方案 v2 §2.1）。
 *
 * 三模式（warp-go 对齐）：off=全部应用走隧道（默认，与现状一致）、
 * allow=白名单（勾选的应用走隧道）、disallow=黑名单（勾选的应用直连）。
 *
 * 与 profile 解耦：分应用配置是**全局**的（跨所有 profile），存独立
 * SharedPreferences，不随 profile 增删改而变。加载时过滤失效包名
 * （卸载/重装后包名失效，逐包 try-catch 跳过，验收 #6）。
 */
object PerAppConfigStore {
    private const val PREFS = "xtunnel_per_app"
    private const val KEY_MODE = "per_app_mode"
    private const val KEY_PACKAGES = "per_app_packages"

    /** 分应用模式。默认 off=全量代理，与现状一致（回归门）。 */
    enum class Mode(val raw: String) {
        Off("off"),
        Allow("allow"),
        Disallow("disallow");

        companion object {
            fun fromRaw(raw: String?): Mode =
                entries.firstOrNull { it.raw == raw } ?: Off
        }
    }

    data class Config(
        val mode: Mode = Mode.Off,
        val packages: Set<String> = emptySet(),
    )

    fun load(context: Context): Config {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val mode = Mode.fromRaw(prefs.getString(KEY_MODE, null))
        val packages = prefs.getStringSet(KEY_PACKAGES, emptySet()) ?: emptySet()
        return Config(mode = mode, packages = packages)
    }

    fun save(context: Context, config: Config) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, config.mode.raw)
            .putStringSet(KEY_PACKAGES, config.packages)
            .apply()
    }

    /**
     * 加载并过滤失效包名（已卸载/不可查的包，验收 #6：卸载重装后不崩溃、名单自动清理）。
     * allow 模式必须剔除壳自身（x-tunnel 例外：自身在 VPN 外，白名单含自身会自环）。
     */
    fun loadFiltered(context: Context): Config {
        val raw = load(context)
        val pm = context.packageManager
        val valid = raw.packages.filter { pkg ->
            runCatching {
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }.toSet()
        // allow 模式强制剔除壳自身防自环（方案 §2.1 明确 x-tunnel 例外，勿照抄 warp-go add(self)）。
        val self = context.packageName
        val filtered = if (raw.mode == Mode.Allow) valid - self else valid
        return Config(mode = raw.mode, packages = filtered)
    }

    /** 当前模式的人类可读描述（Dashboard 卡片展示用）。 */
    fun describe(config: Config): String = when (config.mode) {
        Mode.Off -> "全部应用（默认）：所有应用走隧道"
        Mode.Allow -> "白名单：已选 ${config.packages.size} 个应用走隧道，其余直连"
        Mode.Disallow -> "黑名单：已选 ${config.packages.size} 个应用直连，其余走隧道"
    }
}