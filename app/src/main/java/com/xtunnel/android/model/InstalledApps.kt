package com.xtunnel.android.model

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

/**
 * 分应用代理·已安装应用扫描：枚举可走隧道的第三方应用（有启动入口 + INTERNET 权限），
 * 按应用名中文排序，供白名单勾选 UI 使用。
 * 需要 QUERY_ALL_PACKAGES 权限（Android 11+），已在 Manifest 声明。
 */
object InstalledApps {
    data class AppEntry(
        val packageName: String,
        val label: String,
    ) {
        fun icon(context: Context): Drawable? =
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
            }.getOrNull()
    }

    /**
     * @param excludes 过滤掉的包名集合（如壳自身，避免误勾）。
     */
    fun scan(context: Context, excludes: Set<String> = emptySet()): List<AppEntry> {
        val pm = context.packageManager
        return runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
        }.getOrDefault(emptyList())
            .asSequence()
            .filter { it.packageName !in excludes }
            .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .filter { hasLauncherIntent(pm, it.packageName) }
            .filter { hasInternetPermission(pm, it.packageName) }
            .map { AppEntry(it.packageName, it.loadLabel(pm).toString().ifBlank { it.packageName }) }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    private fun hasLauncherIntent(pm: PackageManager, packageName: String): Boolean {
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            setPackage(packageName)
        }
        return pm.queryIntentActivities(intent, 0).isNotEmpty()
    }

    // 只收录声明了 INTERNET 权限的应用——无联网权限的勾了白名单也不会产生流量。
    private fun hasInternetPermission(pm: PackageManager, packageName: String): Boolean =
        runCatching {
            pm.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        }.getOrNull()
            ?.requestedPermissions
            ?.contains(android.Manifest.permission.INTERNET) == true
}