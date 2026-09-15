package com.xtunnel.android.model

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

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

// ===== M3 设计令牌（recvvih6G8BCvC UI 美化·令牌化）=====
// 铁律：页面文件（MainActivity.kt 等）硬编码 Color(0x...) 必须为 0，
// 全部色值/语义状态色/间距阶梯在本文件单源定义；页面只引用
// MaterialTheme.colorScheme.*、XTunnelTheme.statusColors、XTunnelSpacing.*。
// 调色板为 slate 冷灰中性系 + sky 单强调色（design-taste：单一强调、
// 禁暖冷灰混用；补齐 primaryContainer/secondaryContainer 以消除
// M3 基线默认紫 surfaceContainer 混入 chip/FAB 的「AI 默认味」）。

/**
 * 连接状态语义色。失败态直接用 colorScheme.error（亮暗两主题均已 AA 达标，
 * 双定义漂移风险）。所有值按 WCAG 相对亮度计算 ≥4.5:1 于其主题 surface 上。
 */
@Immutable
data class StatusColors(
    val running: Color,
    val pending: Color,
    val stopped: Color,
)

private val LightStatusColors = StatusColors(
    running = Color(0xFF15803D), // 5.02:1 on #FFF（旧 16A34A=3.30 不达标）
    pending = Color(0xFFB45309), // 5.02:1（旧 D97706=3.19 不达标）
    stopped = Color(0xFF4B5563), // 7.56:1（旧 6B7280=4.83，收深一档）
)

private val DarkStatusColors = StatusColors(
    running = Color(0xFF4ADE80), // 8.40:1 on surface #1E293B
    pending = Color(0xFFFBBF24), // 8.76:1
    stopped = Color(0xFF94A3B8), // 5.71:1
)

/** 一套主题 = Material3 调色板 + 语义状态色（成对提供，防止错配）。 */
data class XTunnelColorSet(
    val scheme: ColorScheme,
    val status: StatusColors,
)

/** 点 3：浅色配色方案（自 MainActivity.kt 迁入；page 文件禁色值）。 */
fun lightColors(): XTunnelColorSet = XTunnelColorSet(
    scheme = lightColorScheme(
        primary = Color(0xFF155E75),
        onPrimary = Color.White,
        secondary = Color(0xFF4B5563),
        onSecondary = Color.White,
        background = Color(0xFFF8FAFC),
        onBackground = Color(0xFF111827),
        surface = Color.White,
        onSurface = Color(0xFF111827),
        surfaceVariant = Color(0xFFE5E7EB),
        onSurfaceVariant = Color(0xFF374151),
        outline = Color(0xFF94A3B8),
        error = Color(0xFFB91C1C),
        onError = Color.White,
        primaryContainer = Color(0xFFE0F2FE),
        onPrimaryContainer = Color(0xFF0C4A6E),
        secondaryContainer = Color(0xFFE2E8F0),
        onSecondaryContainer = Color(0xFF1E293B),
    ),
    status = LightStatusColors,
)

/** 点 3：深色配色方案（自 MainActivity.kt 迁入；page 文件禁色值）。 */
fun darkColors(): XTunnelColorSet = XTunnelColorSet(
    scheme = darkColorScheme(
        primary = Color(0xFF38BDF8),
        onPrimary = Color(0xFF0F172A),
        secondary = Color(0xFF94A3B8),
        onSecondary = Color(0xFF0F172A),
        background = Color(0xFF0F172A),
        onBackground = Color(0xFFF1F5F9),
        surface = Color(0xFF1E293B),
        onSurface = Color(0xFFF1F5F9),
        surfaceVariant = Color(0xFF334155),
        onSurfaceVariant = Color(0xFFCBD5E1),
        outline = Color(0xFF64748B),
        error = Color(0xFFF87171),
        onError = Color(0xFF0F172A),
        primaryContainer = Color(0xFF075985),
        onPrimaryContainer = Color(0xFFE0F2FE),
        secondaryContainer = Color(0xFF334155),
        onSecondaryContainer = Color(0xFFE2E8F0),
    ),
    status = DarkStatusColors,
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

/** 应用级主题访问器（契约：颜色一律 colorScheme.* 或 XTunnelTheme 扩展）。 */
object XTunnelTheme {
    val statusColors: StatusColors
        @Composable @ReadOnlyComposable get() = LocalStatusColors.current
}

/** 4dp 基线间距网格（契约 8/12/16/24）：全页垂直节奏/内边距统一从这里取。 */
object XTunnelSpacing {
    val XS = 4.dp   // 日志行距、列表项内 gap
    val SM = 8.dp   // 卡内字段间隔、图标-文字间隔
    val MD = 12.dp  // 卡片间间隔
    val LG = 16.dp  // 页面外边距、卡片内边距
    val XL = 24.dp  // 大节分隔（预留）
    val ICON = 40.dp // 应用列表图标
}
