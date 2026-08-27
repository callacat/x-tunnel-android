package com.xtunnel.android.model

import android.content.Context

/**
 * 主题模式三档：跟随系统 / 浅色 / 深色（点 3）。
 * 持久化到 SharedPreferences，重启后保留用户选择。
 */
enum class ThemeMode(val label: String) {
    System("跟随系统"),
    Light("浅色"),
    Dark("深色"),
}

object ThemePrefs {
    private const val PREFS = "xtunnel_theme"
    private const val KEY_MODE = "mode"

    fun load(context: Context): ThemeMode {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
            ?: return ThemeMode.System
        return runCatching { ThemeMode.valueOf(raw) }.getOrDefault(ThemeMode.System)
    }

    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.name)
            .apply()
    }
}