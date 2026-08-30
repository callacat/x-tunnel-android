package com.xtunnel.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.Alignment
import com.xtunnel.android.model.DefaultProfile
import com.xtunnel.android.model.InstalledApps
import com.xtunnel.android.model.PerAppConfigStore
import com.xtunnel.android.model.RouteConfigStore
import com.xtunnel.android.model.ThemeMode
import com.xtunnel.android.model.ThemePrefs
import com.xtunnel.android.model.ProfileStore
import com.xtunnel.android.model.XTunnelProfile
import com.xtunnel.android.model.validationError
import com.xtunnel.android.runtime.DiagnosticExporter
import com.xtunnel.android.runtime.LogStore
import com.xtunnel.android.runtime.RuntimeSnapshot
import com.xtunnel.android.runtime.RuntimeState
import com.xtunnel.android.runtime.RuntimeStateStore
import com.xtunnel.android.runtime.XTunnelRuntimeManager
import com.xtunnel.android.service.XTunnelVpnService
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            XTunnelApp()
        }
    }
}

// 页面枚举（单 Activity 多屏导航）：点 2（配置列表独立页）、点 5（日志独立页）。
private enum class Screen {
    Dashboard,
    Profiles,
    PerApp,
    Logs,
}

@Composable
private fun XTunnelApp() {
    val context = LocalContext.current
    var themeMode by remember { mutableStateOf(ThemePrefs.load(context)) }
    val colorScheme = when (themeMode) {
        ThemeMode.System -> if (androidx.compose.foundation.isSystemInDarkTheme()) darkColors() else lightColors()
        ThemeMode.Light -> lightColors()
        ThemeMode.Dark -> darkColors()
    }
    MaterialTheme(colorScheme = colorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            RootNav(onThemeChange = { mode ->
                themeMode = mode
                ThemePrefs.save(context, mode)
            })
        }
    }
}

@Composable
private fun RootNav(onThemeChange: (ThemeMode) -> Unit) {
    var screen by remember { mutableStateOf(Screen.Dashboard) }

    // 点 8：拦截 Android 手势/系统返回——非首页时返回首页，而非退出 App。
    BackHandler(enabled = screen != Screen.Dashboard) {
        screen = Screen.Dashboard
    }

    when (screen) {
        Screen.Dashboard -> DashboardScreen(
            onOpenProfiles = { screen = Screen.Profiles },
            onOpenPerApp = { screen = Screen.PerApp },
            onOpenLogs = { screen = Screen.Logs },
            onThemeChange = onThemeChange,
        )
        Screen.Profiles -> ProfileListScreen(onBack = { screen = Screen.Dashboard })
        Screen.PerApp -> PerAppScreen(onBack = { screen = Screen.Dashboard })
        Screen.Logs -> LogScreen(onBack = { screen = Screen.Dashboard })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    onOpenProfiles: () -> Unit,
    onOpenPerApp: () -> Unit,
    onOpenLogs: () -> Unit,
    onThemeChange: (ThemeMode) -> Unit,
) {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(RuntimeStateStore.snapshot()) }
    var themeMode by remember { mutableStateOf(ThemePrefs.load(context)) }
    var activeProfile by remember { mutableStateOf(ProfileStore.loadActive(context)) }

    fun fail(message: String) {
        RuntimeStateStore.update(
            RuntimeSnapshot(
                state = RuntimeState.Failed,
                profileName = activeProfile?.name ?: "",
                detail = message,
            ),
        )
    }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            activeProfile?.let { XTunnelVpnService.start(context, it) }
        } else {
            fail("未授予 VPN 权限")
        }
    }

    fun startVpn() {
        val profile = activeProfile
        if (profile == null) {
            fail("请先在「配置」页添加并选择一个服务器配置")
            return
        }
        profile.validationError()?.let { error ->
            fail(error)
            return
        }
        ProfileStore.saveProfiles(
            context,
            ProfileStore.loadProfiles(context),
            profile.name,
        )
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            XTunnelVpnService.start(context, profile)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { startVpn() }

    val busy = snapshot.state == RuntimeState.Starting || snapshot.state == RuntimeState.Stopping
    val running = snapshot.state == RuntimeState.Ready

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = RuntimeStateStore.snapshot()
            activeProfile = ProfileStore.loadActive(context)
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "x-tunnel") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                actions = {
                    TextButton(onClick = onOpenLogs) { Text("日志") }
                    TextButton(onClick = onOpenProfiles) { Text("配置") }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusCard(snapshot, activeProfile)
            ProfileSummaryCard(
                profile = activeProfile,
                locked = running,
                onOpenProfiles = onOpenProfiles,
            )
            PerAppCard(
                profile = activeProfile,
                locked = running,
                onOpenPerApp = onOpenPerApp,
            )
            RouteCard(locked = running)
            ActionRow(
                busy = busy,
                running = running,
                onConnect = {
                    if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        startVpn()
                    }
                },
                onDisconnect = {
                    // round8 修复：UI 关闭直接调 RuntimeManager.stop() 同步杀 sidecar+关 tun，
                    // 不再依赖 startService/stopService 的 Service 生命周期（前后台限制导致
                    // startService 被拒后回退 stopService 又对前台 VPN 服务无效 → 关闭无效）。
                    // 然后 stopService 收尾（移除前台通知、停止 VpnService）。
                    android.widget.Toast.makeText(context, "正在停止隧道…", android.widget.Toast.LENGTH_SHORT).show()
                    XTunnelRuntimeManager.get(context).stop()
                    XTunnelVpnService.stop(context)
                },
            )
            ThemeCard(themeMode, onThemeChange)
            RuntimeCard(snapshot)
        }
    }
}

@Composable
private fun StatusCard(snapshot: RuntimeSnapshot, profile: XTunnelProfile?) {
    // 点 1：明确连接状态指示，配状态色
    val (statusColor, statusText) = when (snapshot.state) {
        RuntimeState.Ready -> Color(0xFF16A34A) to "运行中"
        RuntimeState.Starting -> Color(0xFFD97706) to "连接中"
        RuntimeState.Stopping -> Color(0xFFD97706) to "停止中"
        RuntimeState.Failed -> Color(0xFFB91C1C) to "已失败"
        RuntimeState.Stopped -> Color(0xFF6B7280) to "已停止"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "●  $statusText",
                color = statusColor,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = snapshot.detail,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (snapshot.profileName.isNotBlank()) {
                Text(text = "配置：${snapshot.profileName}")
            }
        }
    }
}

@Composable
private fun ProfileSummaryCard(
    profile: XTunnelProfile?,
    locked: Boolean,
    onOpenProfiles: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "当前配置",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (profile == null) {
                Text("尚未配置服务器，请在「配置」页添加", color = MaterialTheme.colorScheme.error)
            } else {
                Text("名称：${profile.name}")
                Text("服务器：${profile.serverUrl.ifBlank { "（未填写）" }}")
                if (locked) {
                    Text("运行中已锁定配置，停止后可编辑", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenProfiles) { Text("管理配置") }
            }
        }
    }
}

// 分应用代理·Dashboard 入口卡片：展示当前模式与勾选数。
@Composable
private fun PerAppCard(
    profile: XTunnelProfile?,
    locked: Boolean,
    onOpenPerApp: () -> Unit,
) {
    val context = LocalContext.current
    val config = remember { PerAppConfigStore.load(context) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "分应用代理",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (profile == null) {
                Text("请先在「配置」页添加并选择一个服务器配置")
            } else {
                Text(PerAppConfigStore.describe(config))
                if (locked) {
                    Text("运行中已锁定，停止后可调整；修改后需重启连接生效", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpenPerApp) { Text("设置分应用") }
            }
        }
    }
}

// GEO 分流·Dashboard 开关卡片（定稿方案 v2 §2.3）：全局代理 / GEO 分流。
// GEO 分流 = sidecar route 引擎启用（默认规则：广告拦截+国内直连+境外走隧道）。
// round9：加自定义规则编辑 + 自动更新开关（见 CustomRulesDialog）。
@Composable
private fun RouteCard(locked: Boolean) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(RouteConfigStore.load(context)) }
    var showRulesEditor by remember { mutableStateOf(false) }
    var showSourceUrlEditor by remember { mutableStateOf(false) }
    val enabled = config.enabled
    // round40：GEO 运行状态（sidecar 运行时轮询 /v1/route/stats）——
    // 「GEO 库是否下载到本地并应用上」的直接可视化，替代黑盒。
    var routeStatus by remember { mutableStateOf<XTunnelRuntimeManager.RouteStatus?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            routeStatus = XTunnelRuntimeManager.get(context).routeStatus()
            delay(2_000)
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "GEO 分流",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (enabled) "已开启：境内直连、境外走隧道、广告拦截"
                        else "已关闭：全局代理（所有流量走隧道）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Switch(
                    checked = enabled,
                    enabled = !locked,
                    onCheckedChange = { v ->
                        config = config.copy(enabled = v)
                        RouteConfigStore.save(context, config)
                        android.widget.Toast.makeText(context, "已保存，重启连接后生效", android.widget.Toast.LENGTH_SHORT).show()
                    },
                )
            }

            // round40：GEO 运行状态行（sidecar 就绪后展示「GEO 库/规则/命中计数」）
            val status = routeStatus
            if (status != null) {
                val geoReady = status.siteLoaded && status.ipLoaded
                Text(
                    buildString {
                        append("运行状态：")
                        if (!status.enabled) {
                            append("分流引擎未启用")
                        } else {
                            append("规则 ${status.ruleCount} 条 · ")
                            append("GEO 库 ")
                            append(
                                when {
                                    geoReady -> "已就绪"
                                    status.siteLoaded || status.ipLoaded -> "部分加载"
                                    else -> "未加载（下载中或失败，见日志）"
                                },
                            )
                            if (status.fallback.isNotBlank()) append(" · 兜底 ${status.fallback}")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (status.enabled && geoReady) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.enabled) {
                    Text(
                        "命中统计：proxy ${status.proxyHits} · direct ${status.directHits}" +
                            " · reject ${status.rejectedHits} · miss ${status.missHits}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (enabled) {
                // round9：自定义规则 + 自动更新（仅 GEO 开启时展示，与分流语义相关）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自定义规则", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            if (config.customRules.isEmpty()) "未配置自定义规则（可选）"
                            else "已配置 ${config.customRules.size} 条自定义规则",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = { showRulesEditor = true },
                        enabled = !locked,
                    ) { Text("编辑") }
                }

                // round9：规则源 URL（自动更新拉取规则库的远程地址；空 = 用 core 内置默认源）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("规则源 URL", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            if (config.rulesSourceUrl.isBlank()) "使用内置默认规则源"
                            else config.rulesSourceUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    TextButton(
                        onClick = { showSourceUrlEditor = true },
                        enabled = !locked,
                    ) { Text("设置") }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("自动更新规则/GEO 库", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            if (config.autoUpdate) "已开启：${config.updateFrequency.label}更新"
                            else "已关闭：手动更新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = config.autoUpdate,
                        enabled = !locked,
                        onCheckedChange = { v ->
                            config = config.copy(autoUpdate = v)
                            RouteConfigStore.save(context, config)
                        },
                    )
                }

                if (config.autoUpdate) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        RouteConfigStore.UpdateFrequency.entries.forEach { freq ->
                            FilterChip(
                                selected = config.updateFrequency == freq,
                                onClick = {
                                    config = config.copy(updateFrequency = freq)
                                    RouteConfigStore.save(context, config)
                                },
                                label = { Text(freq.label) },
                                enabled = !locked,
                            )
                        }
                    }
                }

                // 手动更新（round43 重构，东哥 r42 后建议「无论开没开代理都可以更新」）：
                //   未连接 → App 直连 GitHub 加速镜像（gh-proxy.org/com）下载 GEO 库到
                //            共享 geo 目录，下次启动 sidecar 即加载；
                //   连接中 → 规则重载 + sidecar /v1/route/geo/update（下载走隧道）。
                // 两种状态都可点；后台线程执行（镜像下载可达分钟级，不能卡 UI）。
                OutlinedButton(
                    onClick = {
                        val runtime = XTunnelRuntimeManager.get(context)
                        android.widget.Toast.makeText(context, "GEO 更新开始…", android.widget.Toast.LENGTH_SHORT).show()
                        Thread {
                            if (locked) {
                                val rulesOk = runCatching { runtime.reloadRules() }.getOrDefault(false)
                                val geoOk = runCatching { runtime.updateGeo() }.getOrDefault(false)
                                android.widget.Toast.makeText(
                                    context,
                                    when {
                                        rulesOk && geoOk -> "已触发更新：GEO 库下载中，稍候看运行状态"
                                        rulesOk -> "规则已重载；GEO 更新触发失败"
                                        else -> "更新触发失败，请查看日志"
                                    },
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            } else {
                                val ok = runCatching { runtime.updateGeoOffline() }.getOrDefault(false)
                                android.widget.Toast.makeText(
                                    context,
                                    if (ok) "GEO 库已通过加速镜像更新，下次连接生效"
                                    else "镜像更新失败，请查看日志或连接后重试",
                                    android.widget.Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }.apply { name = "x-tunnel-geo-offline"; isDaemon = true; start() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (locked) "立即更新规则/GEO 库" else "立即更新 GEO 库（加速镜像直连）")
                }
            }

            if (locked) {
                Text("运行中已锁定，停止后可调整", style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (showRulesEditor) {
        CustomRulesDialog(
            initial = config.customRules,
            onDismiss = { showRulesEditor = false },
            onSave = { rules ->
                config = config.copy(customRules = rules)
                RouteConfigStore.save(context, config)
                showRulesEditor = false
            },
        )
    }

    if (showSourceUrlEditor) {
        SourceUrlDialog(
            initial = config.rulesSourceUrl,
            onDismiss = { showSourceUrlEditor = false },
            onSave = { url ->
                config = config.copy(rulesSourceUrl = url.trim())
                RouteConfigStore.save(context, config)
                showSourceUrlEditor = false
            },
        )
    }
}

// round9：规则源 URL 编辑对话框（自动更新拉取规则库的远程地址）。
@Composable
private fun SourceUrlDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("规则源 URL") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://…/rules.txt") },
                    singleLine = true,
                )
                Text(
                    "留空则使用内置默认规则源。自动更新开启时按频率从此 URL 拉取最新规则库。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// round9：自定义规则编辑对话框（每行一条 `行为,条件`）。
@Composable
private fun CustomRulesDialog(
    initial: List<String>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit,
) {
    var text by remember { mutableStateOf(initial.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义分流规则") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    placeholder = { Text("每行一条，格式：行为,条件\n例：proxy,domain:google.com\ndirect,domain:*.example.com\n支持 domain/geosite/geoip 条件") },
                )
                Text(
                    "行为：proxy=走隧道 direct=直连 reject=拦截\n条件：domain:域名后缀（支持 *.xx 通配） / geosite:分类 / geoip:国家",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val rules = text.lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
                onSave(rules)
            }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
    )
}

// 分应用代理·设置页：三模式选择（off/allow/disallow）+ 应用列表勾选。
// allow=勾选走隧道；disallow=勾选直连；off=全部走隧道（默认）。壳自身从候选剔除（防自环）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PerAppScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var config by remember { mutableStateOf(PerAppConfigStore.loadFiltered(context)) }
    val selfPackage = remember { context.packageName }
    val apps = remember {
        InstalledApps.scan(context, excludes = setOf(selfPackage))
    }
    val running = remember { mutableStateOf(false) }
    // round9：搜索过滤（按应用名/包名模糊匹配）。空串时不过滤，显示全部。
    var query by remember { mutableStateOf("") }
    val filteredApps = remember(apps, query) {
        val q = query.trim()
        if (q.isEmpty()) apps
        else apps.filter {
            it.label.contains(q, ignoreCase = true) ||
                it.packageName.contains(q, ignoreCase = true)
        }
    }

    // 页面可见期间刷新运行态。
    LaunchedEffect(Unit) {
        while (true) {
            running.value = RuntimeStateStore.snapshot().state == RuntimeState.Ready
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("分应用代理") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // 模式选择（三档）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "分应用模式",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    PerAppConfigStore.Mode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { config = config.copy(mode = mode) }
                                .padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(modeLabel(mode), fontWeight = FontWeight.Medium)
                                Text(modeHint(mode), style = MaterialTheme.typography.bodySmall)
                            }
                            Switch(
                                checked = config.mode == mode,
                                onCheckedChange = { if (it) config = config.copy(mode = mode) },
                            )
                        }
                    }
                    if (running.value) {
                        Text(
                            "隧道运行中已锁定；修改需停止后生效，或先停止再改",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Text(
                        "已选 ${config.packages.size} 个应用",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // 应用列表（allow/disallow 模式才展示勾选）
            if (config.mode != PerAppConfigStore.Mode.Off) {
                Text(
                    if (config.mode == PerAppConfigStore.Mode.Allow) "勾选走隧道 / 取消勾选直连的应用"
                    else "勾选直连 / 取消勾选走隧道的应用",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                // round9：搜索框——按应用名/包名实时过滤，清空显示全部。
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用（应用名或包名）…") },
                    singleLine = true,
                )
                if (apps.isEmpty()) {
                    Text("未找到可代理的第三方应用（需已安装且有启动入口与联网权限）")
                } else if (filteredApps.isEmpty()) {
                    Text("无匹配应用，换个关键词试试", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filteredApps, key = { it.packageName }) { app ->
                            AppRow(
                                label = app.label,
                                packageName = app.packageName,
                                icon = app.icon(context),
                                checked = app.packageName in config.packages,
                                onToggle = { checked ->
                                    val pkgs = if (checked) config.packages + app.packageName
                                    else config.packages - app.packageName
                                    config = config.copy(packages = pkgs)
                                },
                            )
                        }
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !running.value && (config.mode == PerAppConfigStore.Mode.Off || config.packages.isNotEmpty()),
                onClick = {
                    PerAppConfigStore.save(context, config)
                    android.widget.Toast.makeText(context, "已保存，重启连接后生效", android.widget.Toast.LENGTH_SHORT).show()
                },
            ) {
                Text("保存并生效")
            }
        }
    }
}

private fun modeLabel(mode: PerAppConfigStore.Mode): String = when (mode) {
    PerAppConfigStore.Mode.Off -> "全部应用（默认）"
    PerAppConfigStore.Mode.Allow -> "白名单"
    PerAppConfigStore.Mode.Disallow -> "黑名单"
}

private fun modeHint(mode: PerAppConfigStore.Mode): String = when (mode) {
    PerAppConfigStore.Mode.Off -> "所有应用走隧道，与现状一致"
    PerAppConfigStore.Mode.Allow -> "勾选的应用走隧道，其余直连"
    PerAppConfigStore.Mode.Disallow -> "勾选的应用直连，其余走隧道"
}

// 单个应用行：图标 + 名称 + 包名 + 勾选框。
@Composable
private fun AppRow(
    label: String,
    packageName: String,
    icon: Drawable?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle(!checked) },
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bitmap = icon?.let {
                runCatching { it.toBitmap(48, 48) }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                )
            } else {
                Spacer(modifier = Modifier.size(40.dp))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(label, fontWeight = FontWeight.Medium)
                Text(
                    packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Checkbox(checked = checked, onCheckedChange = onToggle)
        }
    }
}

@Composable
private fun ActionRow(
    busy: Boolean,
    running: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // round8d 修复：放弃 Material3 Button/OutlinedButton（CT107 实测「连接」能点
        // 「关闭」点不动的语义合并 bug——关闭按钮被空白 clickable View 覆盖吞点击），
        // 改用 Box + Modifier.clickable 自管理点击，彻底绕开编译期语义合并缺陷。
        // round9 修复：加背景色+圆角，解决夜间模式按钮"隐形"（对比度不足）。
        val connectBg = if (!busy && !running) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        val connectFg = if (!busy && !running) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant
        Box(
            modifier = Modifier
                .weight(1f)
                .background(connectBg, RoundedCornerShape(8.dp))
                .clickable(enabled = !busy && !running) { onConnect() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "连接",
                modifier = Modifier.padding(vertical = 14.dp),
                fontWeight = FontWeight.SemiBold,
                color = connectFg,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                .clickable { onDisconnect() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "关闭",
                modifier = Modifier.padding(vertical = 14.dp),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ThemeCard(current: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    // 点 3：跟随系统 / 浅色 / 深色 三档
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "主题",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            ThemeMode.entries.forEach { mode ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(mode.label)
                    Switch(
                        checked = current == mode,
                        onCheckedChange = { if (it) onThemeChange(mode) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RuntimeCard(snapshot: RuntimeSnapshot) {
    val context = LocalContext.current
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "unknown"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "运行时",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            // 八轮修复·版本号显示（round5 生效）：东哥可确认装的哪一版
            Text(text = "版本：$versionName")
            Text(text = "Android API：${Build.VERSION.SDK_INT}")
            if (snapshot.controlUrl.isNotBlank()) {
                Text(text = "控制端口：${snapshot.controlUrl}")
            }
            snapshot.pid?.let {
                Text(text = "核心 PID：$it")
            }
        }
    }
}

// ===== 点 2 + 点 4 + 点 7：配置编辑/管理（独立页） =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var profiles by remember { mutableStateOf(ProfileStore.loadProfiles(context)) }
    var activeName by remember { mutableStateOf(ProfileStore.activeProfileName(context)) }
    var editing by remember { mutableStateOf<XTunnelProfile?>(null) }
    val running = RuntimeStateStore.snapshot().state == RuntimeState.Ready

    val current = editing
    if (current != null) {
        ProfileEditScreen(
            profile = current,
            onSave = { updated ->
                val list = profiles.filter { it.name != current.name } + updated
                ProfileStore.saveProfiles(context, list, activeName ?: updated.name)
                profiles = list
                activeName = ProfileStore.activeProfileName(context)
                editing = null
            },
            onDelete = {
                val list = profiles.filter { it.name != current.name }
                // 删除的是当前激活配置时，激活项回退到剩余第一个（若无则置空）
                val nextActive = if (current.name == activeName) list.firstOrNull()?.name else activeName
                ProfileStore.saveProfiles(context, list, nextActive)
                profiles = list
                activeName = ProfileStore.activeProfileName(context)
                editing = null
            },
            onBack = { editing = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("配置管理") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (profiles.isEmpty()) {
                Text("暂无配置，点击下方「新增配置」添加。默认不内置服务器地址，请自行填写。")
            } else {
                profiles.forEach { p ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = p.name + if (p.name == activeName) "（当前）" else "",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Row {
                                    TextButton(enabled = !running, onClick = {
                                        activeName = p.name
                                        ProfileStore.saveProfiles(context, profiles, p.name)
                                    }) { Text("启用") }
                                    TextButton(onClick = { editing = p }) { Text("编辑") }
                                }
                            }
                            Text("服务器：${p.serverUrl.ifBlank { "（未填写）" }}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (running) {
                Text("隧道运行中，配置已锁定不可修改", style = MaterialTheme.typography.bodySmall)
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !running,
                onClick = { editing = DefaultProfile.newProfile() },
            ) {
                Text("新增配置")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditScreen(
    profile: XTunnelProfile,
    onSave: (XTunnelProfile) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
) {
    var draft by remember { mutableStateOf(profile) }
    val error = draft.validationError()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("编辑配置") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.name,
                onValueChange = { draft = draft.copy(name = it) },
                label = { Text("配置名称") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.serverUrl,
                onValueChange = { draft = draft.copy(serverUrl = it.trim()) },
                label = { Text("服务器地址（ws:// 或 wss://）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.token,
                onValueChange = { draft = draft.copy(token = it) },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.socksListen,
                onValueChange = { draft = draft.copy(socksListen = it.trim()) },
                label = { Text("本地 SOCKS") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.cidr,
                onValueChange = { draft = draft.copy(cidr = it.trim()) },
                label = { Text("CIDR") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.dns,
                onValueChange = { draft = draft.copy(dns = it.trim()) },
                label = { Text("DNS") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.ech,
                onValueChange = { draft = draft.copy(ech = it.trim()) },
                label = { Text("ECH 域名") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.blockPorts,
                onValueChange = { draft = draft.copy(blockPorts = it.trim()) },
                label = { Text("UDP 阻断端口") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.connections.toString(),
                onValueChange = { draft = draft.copy(connections = it.toIntOrNull() ?: 1) },
                label = { Text("连接数（1-16）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("允许不安全 TLS")
                Switch(checked = draft.insecure, onCheckedChange = { draft = draft.copy(insecure = it) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("使用 TLS 回退")
                Switch(checked = draft.fallback, onCheckedChange = { draft = draft.copy(fallback = it) })
            }
            Text(
                text = "抗干扰（高级）",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.dialIPs,
                onValueChange = { draft = draft.copy(dialIPs = it.trim()) },
                label = { Text("优选 IP / 主机（-ip），逗号分隔") },
                placeholder = { Text("如 1.2.3.4 或 cf.example.com") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.ipStrategy,
                onValueChange = { draft = draft.copy(ipStrategy = it.trim()) },
                label = { Text("IP 栈（-ips）") },
                placeholder = { Text("4 / 6 / 4,6 / 6,4") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.dnsCacheTtl,
                onValueChange = { draft = draft.copy(dnsCacheTtl = it.trim()) },
                label = { Text("DNS 缓存 TTL") },
                placeholder = { Text("5m / 30s / 0（禁用）") },
                singleLine = true,
            )

            if (error != null) {
                Text("校验：$error", color = MaterialTheme.colorScheme.error)
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = error == null,
                onClick = { onSave(draft) },
            ) {
                Text("保存")
            }
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onDelete,
            ) {
                Text("删除此配置")
            }
        }
    }
}

// ===== 点 5：日志独立页 + 导出分享 =====

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(LogStore.snapshot()) }

    LaunchedEffect(Unit) {
        while (true) {
            lines = LogStore.snapshot()
            delay(1_000)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("日志") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
                actions = {
                    TextButton(onClick = {
                        // 第 9 点：导出诊断包到 Download 目录（九轮：不依赖网络分享，可文件管理器手动发）
                        val runtime = XTunnelRuntimeManager.get(context)
                        val file = DiagnosticExporter.export(context, runtime) ?: return@TextButton
                        // 存好后仍提供 FileProvider 分享（external files dir 兼容），同时弹 Toast 提示路径
                        val uri = FileProvider.getUriForFile(
                            context, context.packageName + ".fileprovider", file,
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "导出诊断包（已存 Download）"))
                        android.widget.Toast.makeText(
                            context, "诊断包已存：${file.absolutePath}", android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }) { Text("诊断包") }
                    TextButton(onClick = {
                        LogStore.clear()
                        lines = emptyList()
                    }) { Text("清空") }
                    TextButton(onClick = {
                        val file = LogStore.exportFile(context) ?: return@TextButton
                        val uri = FileProvider.getUriForFile(
                            context,
                            context.packageName + ".fileprovider",
                            file,
                        )
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "导出日志"))
                    }) { Text("导出") }
                },
            )
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (lines.isEmpty()) {
                Text("暂无日志")
            } else {
                lines.forEach { line ->
                    Text(
                        text = line.render(),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (line.level == LogStore.Level.Error) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
        }
    }
}

// 点 3：深色配色方案
private fun darkColors() = darkColorScheme(
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
)

// 点 3：浅色配色方案（从原 XTunnelColorScheme 迁移）
private fun lightColors() = lightColorScheme(
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
)