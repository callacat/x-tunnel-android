package com.xtunnel.android.model

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * 分应用代理·已安装应用扫描：枚举可走隧道的第三方应用（有启动入口 + INTERNET 权限），
 * 按应用名中文排序，供白名单勾选 UI 使用。
 *
 * 可见性：Android 11+ 包可见性过滤下，[PackageManager.getInstalledPackages] 只返回
 * Manifest `<queries>` 声明的 MAIN/LAUNCHER 应用 + 系统应用——恰好是选择器要列的集合，
 * 无需 QUERY_ALL_PACKAGES。对齐 warp-go 分应用代理方案。
 */
object InstalledApps {
    data class AppEntry(
        val packageName: String,
        val label: String,
        val system: Boolean = false,
    ) {
        fun icon(context: Context): Drawable? =
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull()
    }

    /**
     * @param excludes 过滤掉的包名集合（如壳自身，避免误勾）。
     */
    @SuppressLint("QueryPermissionsNeeded")
    fun scan(context: Context, excludes: Set<String> = emptySet()): List<AppEntry> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.packageName !in excludes }
            // round45：不再剔除系统应用（东哥反馈：分应用时需要勾选系统应用，
            // 如系统浏览器/应用商店）。有 INTERNET 权限 + launcher 入口的
            // 系统应用同样会产生用户流量，理应可勾选。纯后台组件仍被
            // hasLauncherIntent 过滤。
            .filter { hasInternetPermission(it) }
            .filter { hasLauncherIntent(pm, it.packageName) }
            .map {
                val label = runCatching { it.applicationInfo?.loadLabel(pm)?.toString() }.getOrNull()
                val system = (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0
                AppEntry(it.packageName, label.orEmpty().ifBlank { it.packageName }, system)
            }
            .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
            .toList()
    }

    // 只收录声明了 INTERNET 权限的应用——无联网权限的勾了白名单也不会产生流量。
    private fun hasInternetPermission(pkg: android.content.pm.PackageInfo): Boolean =
        pkg.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true

    // 有 launcher 入口的应用才进选择器（纯后台/系统组件对用户无意义）。
    private fun hasLauncherIntent(pm: PackageManager, packageName: String): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        return pm.queryIntentActivities(intent, 0).isNotEmpty()
    }
}