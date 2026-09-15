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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.minimumInteractiveComponentSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
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
import com.xtunnel.android.model.LocalStatusColors
import com.xtunnel.android.model.ThemeMode
import com.xtunnel.android.model.ThemePrefs
import com.xtunnel.android.model.XTunnelSpacing
import com.xtunnel.android.model.XTunnelTheme
import com.xtunnel.android.model.darkColors
import com.xtunnel.android.model.lightColors
import com.xtunnel.android.model.ProfileStore
import com.xtunnel.android.model.XTunnelProfile
import com.xtunnel.android.model.validationError
import com.xtunnel.android.runtime.DiagnosticExporter
import com.xtunnel.android.runtime.LogStore
import com.xtunnel.android.runtime.RuntimeSnapshot
import com.xtunnel.android.runtime.RuntimeState
import com.xtunnel.android.runtime.RuntimeStateStore
import com.xtunnel.android.runtime.UpdateChecker
import com.xtunnel.android.runtime.VpnDataPathState
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

// ===== CI 截图导航钩子（recvvih6G8BCvC UI 美化）=====
// debug 构建专用 intent extras：debug_theme=light|dark 强制主题、
// debug_screen=Dashboard|Profiles|PerApp|Logs 直达页面。
// BuildConfig.DEBUG 门禁：用户构建（release）完全无效，不改信息架构。
private fun debugExtras(context: android.content.Context): Bundle? =
    if (BuildConfig.DEBUG) (context as? Activity)?.intent?.extras else null

private fun debugScreen(bundle: Bundle?): Screen =
    bundle?.getString("debug_screen")?.let { name ->
        runCatching { Screen.valueOf(name) }.getOrNull()
    } ?: Screen.Dashboard

@Composable
private fun XTunnelApp() {
    val context = LocalContext.current
    val debugBundle = remember { debugExtras(context) }
    var themeMode by remember {
        mutableStateOf(
            when (debugBundle?.getString("debug_theme")) {
                "light" -> ThemeMode.Light
                "dark" -> ThemeMode.Dark
                else -> ThemePrefs.load(context)
            },
        )
    }
    val colorSet = when (themeMode) {
        ThemeMode.System -> if (androidx.compose.foundation.isSystemInDarkTheme()) darkColors() else lightColors()
        ThemeMode.Light -> lightColors()
        ThemeMode.Dark -> darkColors()
    }
    // 调色板与状态色成对下发（model/XTunnelTheme.kt 单源，page 文件零硬编码色）。
    CompositionLocalProvider(LocalStatusColors provides colorSet.status) {
        MaterialTheme(colorScheme = colorSet.scheme) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                RootNav(
                    initialScreen = debugScreen(debugBundle),
                    onThemeChange = { mode ->
                        themeMode = mode
                        ThemePrefs.save(context, mode)
                    },
                )
            }
        }
    }
}

@Composable
private fun RootNav(
    initialScreen: Screen = Screen.Dashboard,
    onThemeChange: (ThemeMode) -> Unit,
) {
    var screen by remember { mutableStateOf(initialScreen) }

    // 点 8：拦截 Android 手势/系统返回——非首页时返回首页，而非退出 App。
    BackHandler(enabled = screen != Screen.Dashboard) {
        screen = Screen.Dashboard
    }

    // UX-R3 第 4 项·动效（codex 预研 D / claude 预研 3.3）：页面切换从硬切屏
    // 改 150ms 淡入+轻滑过渡（系统动画关闭时 Compose 自动降级为瞬切）。
    AnimatedContent(
        targetState = screen,
        // togetherWith 扩展在 CI 的 animation 版本下解析异常（receiver
        // mismatch，第二轮审查修复）→ 显式 ContentTransform 构造。
        transitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn(tween(150)) +
                    slideInHorizontally(tween(150)) { it / 8 },
                initialContentExit = fadeOut(tween(120)) +
                    slideOutHorizontally(tween(150)) { -it / 8 },
            )
        },
        label = "screen",
    ) { target ->
        when (target) {
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
                .padding(XTunnelSpacing.LG)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.MD),
        ) {
            StatusCard(snapshot, activeProfile, onOpenLogs = onOpenLogs)
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
                stopping = snapshot.state == RuntimeState.Stopping,
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
private fun StatusCard(
    snapshot: RuntimeSnapshot,
    profile: XTunnelProfile?,
    onOpenLogs: () -> Unit,
) {
    // 点 1：明确连接状态指示，配状态色。
    // UX-R3 第 4 项·动效（codex 预研 B）：1s 轮询下的状态翻转不再硬切——
    // 状态色 200ms 过渡；中间态（连接中/停止中）状态点呼吸 alpha 0.35↔1，
    // 终态静态。只动 alpha/颜色，不动布局尺寸。
    val statusColors = XTunnelTheme.statusColors
    val (targetColor, statusText) = when (snapshot.state) {
        RuntimeState.Ready -> statusColors.running to "运行中"
        RuntimeState.Starting -> statusColors.pending to "连接中"
        RuntimeState.Stopping -> statusColors.pending to "停止中"
        RuntimeState.Failed -> MaterialTheme.colorScheme.error to "已失败"
        RuntimeState.Stopped -> statusColors.stopped to "已停止"
    }
    val statusColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(200),
        label = "statusColor",
    )
    // 状态点呼吸动画（infiniteTransition）在 CI 端类型推断失败且终态空转
    // 耗电（审查 P2）——本轮移除，保留状态色过渡即可表达「进行中」。
    // 层级：状态卡是全局唯一"主卡"，用 surfaceVariant 色调与其余 surface 卡
    // 区分（redesign：卡片只在需要表达层级时出现差异）；状态点改几何圆点，
    // 不再依赖字体字形「●」（跨设备字号/基线不可控）。
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(XTunnelSpacing.LG),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(statusColor, CircleShape),
                )
                Spacer(modifier = Modifier.size(XTunnelSpacing.SM))
                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.headlineSmall,
                    // 主卡状态字保留全页唯一一处半粗强调（字重限 2-3 档）。
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                text = snapshot.detail,
                style = MaterialTheme.typography.bodyMedium,
            )
            // UX-R3·N-new 可观测性：sidecar 就绪但数据面 Failed 时，此前 UI
            // 仍显示「运行中」（state=Ready 只看 sidecar），数据面故障被完全
            // 掩盖、现场无排查线索（真机三测 BLOCKER 的放大器）。显式呈现。
            if (snapshot.dataPathState != VpnDataPathState.Running &&
                snapshot.dataPathState != VpnDataPathState.NotStarted
            ) {
                Text(
                    text = "数据面：${snapshot.dataPathState.label}（${snapshot.dataPathDetail}）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (snapshot.profileName.isNotBlank()) {
                Text(
                    text = "配置：${snapshot.profileName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // UX-R3 第 4 项·交互（claude 预研 Dashboard-2）：失败态打通
            // 「看到失败 → 查日志」路径，不用回顶栏找入口。
            if (snapshot.state == RuntimeState.Failed) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onOpenLogs) { Text("查看日志") }
                }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(XTunnelSpacing.LG),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM),
        ) {
            Text(
                text = "当前配置",
                style = MaterialTheme.typography.titleMedium,
            )
            if (profile == null) {
                Text(
                    "尚未配置服务器，请在「配置」页添加",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            } else {
                Text("名称：${profile.name}", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "服务器：${profile.serverUrl.ifBlank { "（未填写）" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(XTunnelSpacing.LG),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM),
        ) {
            Text(
                text = "分应用代理",
                style = MaterialTheme.typography.titleMedium,
            )
            if (profile == null) {
                Text("请先在「配置」页添加并选择一个服务器配置", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text(PerAppConfigStore.describe(config), style = MaterialTheme.typography.bodyMedium)
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
    // UX-R3 第 4 项·按钮（codex 预研 C）：GEO 更新是长耗时异步（镜像下载可达
    // 分钟级），按钮加进行中态防连点、给持续反馈（替代纯 Toast 一闪而过）。
    var geoUpdating by remember { mutableStateOf(false) }
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
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(XTunnelSpacing.LG),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "GEO 分流",
                        style = MaterialTheme.typography.titleMedium,
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
                        horizontalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM),
                    ) {
                        RouteConfigStore.UpdateFrequency.entries.forEach { freq ->
                            FilterChip(
                                // chip 视觉高 32dp，补足 48dp 最小触控目标（WCAG/契约判据 2）。
                                modifier = Modifier.minimumInteractiveComponentSize(),
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
                        if (!geoUpdating) {
                            val runtime = XTunnelRuntimeManager.get(context)
                            geoUpdating = true
                            // UX-R3 顺带修既有隐患：Toast 在无名 daemon 线程构造会抛
                            // 「Can't create handler inside thread that has not called
                            // Looper.prepare()」——全部 Toast 改 post 回主线程。
                            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                            mainHandler.post {
                                android.widget.Toast.makeText(context, "GEO 更新开始…", android.widget.Toast.LENGTH_SHORT).show()
                            }
                            Thread {
                                try {
                                    if (locked) {
                                        val rulesOk = runCatching { runtime.reloadRules() }.getOrDefault(false)
                                        val geoOk = runCatching { runtime.updateGeo() }.getOrDefault(false)
                                        mainHandler.post {
                                            android.widget.Toast.makeText(
                                                context,
                                                when {
                                                    rulesOk && geoOk -> "已触发更新：GEO 库下载中，稍候看运行状态"
                                                    rulesOk -> "规则已重载；GEO 更新触发失败"
                                                    else -> "更新触发失败，请查看日志"
                                                },
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    } else {
                                        val ok = runCatching { runtime.updateGeoOffline() }.getOrDefault(false)
                                        mainHandler.post {
                                            android.widget.Toast.makeText(
                                                context,
                                                if (ok) "GEO 库已通过加速镜像更新，下次连接生效"
                                                else "镜像更新失败，请查看日志或连接后重试",
                                                android.widget.Toast.LENGTH_SHORT,
                                            ).show()
                                        }
                                    }
                                } finally {
                                    // Compose snapshot state 线程安全写：后台完成即复位按钮态。
                                    geoUpdating = false
                                }
                            }.apply { name = "x-tunnel-geo-offline"; isDaemon = true; start() }
                        }
                    },
                    enabled = !geoUpdating,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (geoUpdating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.size(XTunnelSpacing.SM))
                    }
                    Text(
                        when {
                            geoUpdating -> "更新中…"
                            locked -> "立即更新规则/GEO 库"
                            else -> "立即更新 GEO 库（加速镜像直连）"
                        },
                    )
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
            Column(verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM)) {
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
            Column(verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    // 规则是 DSL 文本：等宽对齐 + 可选中（与日志页同口径），
                    // 语法列对齐可读性↑。
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
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
                .padding(XTunnelSpacing.LG),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.MD),
        ) {
            // 模式选择（三档）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(
                    modifier = Modifier.padding(XTunnelSpacing.LG),
                    verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM),
                ) {
                    Text(
                        "分应用模式",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    // UX-R3 第 4 项·交互（claude 预研 P2）：三互斥模式各挂一个
                    // Switch 是 radio 语义错用（关不掉、互斥关系不可见）——改 M3
                    // SingleChoiceSegmentedButtonRow（选中态强、天然互斥）。
                    // 窄屏防溢出：label 单行不折行。
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        PerAppConfigStore.Mode.entries.forEachIndexed { index, mode ->
                            SegmentedButton(
                                selected = config.mode == mode,
                                onClick = { config = config.copy(mode = mode) },
                                enabled = !running.value,
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = PerAppConfigStore.Mode.entries.size,
                                ),
                            ) {
                                Text(
                                    modeLabel(mode),
                                    style = MaterialTheme.typography.labelMedium,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Text(
                        modeHint(config.mode),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    Text("未找到可代理的应用（需已安装、有启动入口与联网权限）")
                } else if (filteredApps.isEmpty()) {
                    Text("无匹配应用，换个关键词试试", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(
                        modifier = Modifier.weight(1f, fill = true),
                        verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.XS),
                    ) {
                        // round45：第三方在前、系统应用在后（InstalledApps 已按此排序），
                        // 首次出现处插分组标题行——系统应用可见可勾（东哥 r44 反馈）。
                        var lastSystem: Boolean? = null
                        filteredApps.forEach { app ->
                            if (app.system != lastSystem) {
                                lastSystem = app.system
                                item(key = "header-${app.system}", contentType = "header") {
                                    // 组标题去 ASCII 装饰线（「—— xx ——」靠字符拼视觉
                                    // 是字形 hack）→ 标准 label 层级。
                                    Text(
                                        if (app.system) "系统应用" else "第三方应用",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = XTunnelSpacing.SM),
                                    )
                                }
                            }
                            item(key = app.packageName, contentType = "app") {
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
    PerAppConfigStore.Mode.Off -> "全部应用"
    PerAppConfigStore.Mode.Allow -> "白名单"
    PerAppConfigStore.Mode.Disallow -> "黑名单"
}

private fun modeHint(mode: PerAppConfigStore.Mode): String = when (mode) {
    // SegmentedButton 窄屏 label 缩短为「全部应用」，「默认」语义移到 hint
    // （第二轮审查 P2-5）。
    PerAppConfigStore.Mode.Off -> "所有应用走隧道（默认，与现状一致）"
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
    // UI 美化：改 M3 语义 Card(onClick)——旧「Card 外套 clickable」把 ripple
    // 画在卡片裁剪外（方形角溢出），且无按压高度反馈；Card(onClick) 由 M3
    // 管裁剪+ripple+按压态，内部 Checkbox 仍各自消费点击（无双触发）。
    // 形状用 shapes.small（内层元素紧圆角，与 medium 容器卡形成层级）。
    Card(
        onClick = { onToggle(!checked) },
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(XTunnelSpacing.MD),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bitmap = icon?.let {
                runCatching { it.toBitmap(48, 48) }.getOrNull()
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(XTunnelSpacing.ICON),
                )
            } else {
                Spacer(modifier = Modifier.size(XTunnelSpacing.ICON))
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = XTunnelSpacing.MD),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
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
    stopping: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(XTunnelSpacing.MD),
    ) {
        // round8d 修复：放弃 Material3 Button/OutlinedButton（CT107 实测「连接」能点
        // 「关闭」点不动的语义合并 bug——关闭按钮被空白 clickable View 覆盖吞点击），
        // 改用 Box + Modifier.clickable 自管理点击，彻底绕开编译期语义合并缺陷。
        // round9 修复：加背景色+圆角，解决夜间模式按钮"隐形"（对比度不足）。
        // UX-R3 第 4 项·动效（codex 预研 A + round8 铁律）：按压反馈只用
        // graphicsLayer 缩放（不进布局、不加语义节点、content lambda 零改动），
        // 避免重蹈语义合并破坏 clickable 的覆辙。busy 态文案给进行中反馈。
        // UI 美化：形状对齐卡片中圆角（shapes.medium）；禁用态按 M3 标准
        // onSurface α=0.38（原 100% onSurfaceVariant 看着像可点，Missing States）；
        // 垂直内边距上 4dp 网格（LG=16，含文字 ≥48dp 触控高）。
        val connectSource = remember { MutableInteractionSource() }
        val connectPressed by connectSource.collectIsPressedAsState()
        val disconnectSource = remember { MutableInteractionSource() }
        val disconnectPressed by disconnectSource.collectIsPressedAsState()
        val connectEnabled = !busy && !running
        val connectBg = if (connectEnabled) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant
        val connectFg = if (connectEnabled) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .graphicsLayer {
                    scaleX = if (connectPressed && connectEnabled) 0.97f else 1f
                    scaleY = if (connectPressed && connectEnabled) 0.97f else 1f
                }
                .background(connectBg, MaterialTheme.shapes.medium)
                .clickable(
                    interactionSource = connectSource,
                    enabled = connectEnabled,
                ) { onConnect() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (busy && !stopping) "连接中…" else "连接",
                modifier = Modifier.padding(vertical = XTunnelSpacing.LG),
                fontWeight = FontWeight.SemiBold,
                color = connectFg,
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .graphicsLayer {
                    scaleX = if (disconnectPressed) 0.97f else 1f
                    scaleY = if (disconnectPressed) 0.97f else 1f
                }
                .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
                .clickable(interactionSource = disconnectSource) { onDisconnect() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (stopping) "停止中…" else "关闭",
                modifier = Modifier.padding(vertical = XTunnelSpacing.LG),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun ThemeCard(current: ThemeMode, onThemeChange: (ThemeMode) -> Unit) {
    // 点 3：跟随系统 / 浅色 / 深色 三档。
    // UI 美化：三互斥档原用 3 个 Switch（radio 语义错用——关不掉、互斥不可见；
    // UX-R3 已在分应用模式修正同类问题）→ 全站统一 SingleChoiceSegmentedButtonRow，
    // 选中态强、天然互斥；组件规格跨页一致。
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(XTunnelSpacing.LG),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM),
        ) {
            Text(
                text = "主题",
                style = MaterialTheme.typography.titleMedium,
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier.fillMaxWidth(),
            ) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = current == mode,
                        onClick = { onThemeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = ThemeMode.entries.size,
                        ),
                    ) {
                        Text(mode.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
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
    // UX-R3 第 3 项（东哥 2026-09-14 反馈「检查更新按钮行为不对」）：
    // 旧行为=点击无条件跳浏览器下载页，不做版本比对。新行为三态：
    //   无新版 → 提示「已是最新版」；有新版 → 显示版本号+「前往下载」跳浏览器；
    //   网络失败 → Toast 兜底。GitHub Releases API 经镜像链拉取（见 UpdateChecker）。
    var updateChecking by remember { mutableStateOf(false) }
    var updateResult by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var showDialog by remember { mutableStateOf(false) }
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun runUpdateCheck() {
        if (updateChecking) return
        updateChecking = true
        // 网络 IO 禁主线程：后台 daemon 线程 + Handler 回主线程更新状态
        // （沿用 downloadTo 的裸线程先例；本仓 UI 层无协程/无 OkHttp）。
        Thread {
            val latest = UpdateChecker.fetchLatestTag()
            val result = if (latest == null) {
                UpdateChecker.Result.CheckFailed
            } else {
                UpdateChecker.compare(versionName, latest) ?: UpdateChecker.Result.CheckFailed
            }
            mainHandler.post {
                updateChecking = false
                updateResult = result
                // 全态弹窗（审查 P2：失败态也要「手动打开下载页」兜底，
                // 窄网环境 api.github.com/镜像全挂但浏览器可达时可自救）。
                showDialog = true
            }
        }.apply { name = "x-tunnel-update-check"; isDaemon = true; start() }
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
            Spacer(modifier = Modifier.height(8.dp))
            // 检查更新（东哥 2026-08-30 拍板「跳浏览器挑 APK，不做静默下载」
            // + UX-R3 2026-09-14 修正「先比对版本，再决定提示还是跳转」）。
            // loading 态（codex 预研 C 项）：检查中禁用+转圈，防连点。
            OutlinedButton(
                onClick = { runUpdateCheck() },
                enabled = !updateChecking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (updateChecking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("检查中…")
                } else {
                    Text("检查更新")
                }
            }
        }
    }

    // 三态结果弹窗（codex 预研 1.4 文案口径，对齐 N3 验收判据；
    // 第二轮审查 P2 补强：失败态「手动打开下载页」兜底、unknown 不拼 v）。
    val result = updateResult
    if (showDialog && result != null) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("检查更新") },
            text = {
                Text(
                    when (result) {
                        is UpdateChecker.Result.Latest -> {
                            val ver = displayVersion(result.currentVersion)
                            if (ver.startsWith("v")) "已是最新版 $ver" else "已是最新版"
                        }
                        is UpdateChecker.Result.NewerVersion ->
                            "发现新版本 ${result.latestVersion}（当前 ${displayVersion(result.currentVersion)}）"
                        UpdateChecker.Result.CheckFailed ->
                            "检查更新失败，请检查网络后重试。\n也可以直接打开下载页查看最新版本。"
                    },
                )
            },
            confirmButton = {
                // 有新版/检查失败都给跳浏览器路径（复用 openReleasesPage，
                // 含无浏览器 Toast 兜底——东哥 8-30 拍板链路不变）。
                if (result is UpdateChecker.Result.NewerVersion ||
                    result is UpdateChecker.Result.CheckFailed
                ) {
                    TextButton(onClick = {
                        showDialog = false
                        openReleasesPage(context)
                    }) { Text(if (result is UpdateChecker.Result.NewerVersion) "前往下载" else "打开下载页") }
                } else {
                    TextButton(onClick = { showDialog = false }) { Text("确定") }
                }
            },
            dismissButton = if (result is UpdateChecker.Result.NewerVersion) {
                @Composable {
                    TextButton(onClick = { showDialog = false }) { Text("以后再说") }
                }
            } else {
                null
            },
        )
    }
}

// DOWNLOAD_PAGE_URL 是「检查更新」跳转的下载页：GitHub Releases latest
// （302 到最新正式版 tag 页）。正式版发布在 callacat/x-tunnel-android，
// 与 CI release.yml 上传产物同一个仓库。
private const val DOWNLOAD_PAGE_URL = "https://github.com/callacat/x-tunnel-android/releases/latest"

// displayVersion：统一版本写法——数字开头才加 v 前缀；「unknown」等
// 兜底串原样显示（交叉审查 P2：避免「vunknown」文案）。
private fun displayVersion(version: String): String =
    if (version.firstOrNull()?.isDigit() == true) "v$version" else version

// openReleasesPage 用系统浏览器打开下载页；设备无浏览器（罕见，如纯系统镜像）
// 时 Toast 兜底，不让点击无响应。
private fun openReleasesPage(context: android.content.Context) {
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse(DOWNLOAD_PAGE_URL))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        android.widget.Toast.makeText(
            context,
            "未找到可用的浏览器，请手动访问：$DOWNLOAD_PAGE_URL",
            android.widget.Toast.LENGTH_LONG,
        ).show()
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
                .padding(XTunnelSpacing.LG)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.MD),
        ) {
            if (profiles.isEmpty()) {
                // 空状态也要"有话说"：显式字级+次要色（redesign：empty state 是
                // 被浪费的构图位，不是缺省帧）。
                Text(
                    "暂无配置，点击下方「新增配置」添加。默认不内置服务器地址，请自行填写。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                profiles.forEach { p ->
                    val isActive = p.name == activeName
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    ) {
                        Column(modifier = Modifier.padding(XTunnelSpacing.LG)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // 激活配置：名称+小徽标（tonal chip），替代旧「（当前）」
                                // 拼字符串——状态一眼可辨，不靠读文案。
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(p.name, style = MaterialTheme.typography.titleMedium)
                                    if (isActive) {
                                        Spacer(modifier = Modifier.size(XTunnelSpacing.SM))
                                        Text(
                                            "当前",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier
                                                .background(
                                                    MaterialTheme.colorScheme.primaryContainer,
                                                    MaterialTheme.shapes.small,
                                                )
                                                .padding(
                                                    horizontal = XTunnelSpacing.SM,
                                                    vertical = XTunnelSpacing.XS,
                                                ),
                                        )
                                    }
                                }
                                Row {
                                    TextButton(enabled = !running && !isActive, onClick = {
                                        activeName = p.name
                                        ProfileStore.saveProfiles(context, profiles, p.name)
                                    }) { Text("启用") }
                                    TextButton(onClick = { editing = p }) { Text("编辑") }
                                }
                            }
                            Text(
                                "服务器：${p.serverUrl.ifBlank { "（未填写）" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (running) {
                Text(
                    "隧道运行中，配置已锁定不可修改",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        // 表单垂直节奏：字段间隔 SM=8（M3 text field 自带 label 留白，
        // 字段间无需更大；节标题仍靠 titleMedium 区分层级）。
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(XTunnelSpacing.LG)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.SM),
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
                Text("允许不安全 TLS", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = draft.insecure, onCheckedChange = { draft = draft.copy(insecure = it) })
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("使用 TLS 回退", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = draft.fallback, onCheckedChange = { draft = draft.copy(fallback = it) })
            }
            Text(
                text = "抗干扰（高级）",
                style = MaterialTheme.typography.titleMedium,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = draft.dialIPs,
                onValueChange = { draft = draft.copy(dialIPs = it.trim()) },
                label = { Text("优选 IP / 主机（-ip），逗号分隔") },
                placeholder = { Text("如 1.2.3.4 或 cf.example.com") },
                singleLine = true,
                enabled = !draft.baiduRelay,
                supportingText = if (draft.baiduRelay) {
                    { Text("百度中转开启时优选 IP 不生效", color = MaterialTheme.colorScheme.error) }
                } else null,
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

            // ===== 百度中转（recvv22hIoqhoe）=====
            Text(
                text = "百度中转（高级）",
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("启用百度中转", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = draft.baiduRelay, onCheckedChange = { draft = draft.copy(baiduRelay = it) })
            }
            Text(
                text = "开启后隧道先经百度云 CONNECT 中转再到服务器，用于直连被干扰（优选 IP 会被忽略）的场景；关闭则直连，行为与旧版一致。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (draft.baiduRelay) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.baiduServer,
                    onValueChange = { draft = draft.copy(baiduServer = it.trim()) },
                    label = { Text("中转服务器（主机:端口）") },
                    placeholder = { Text(XTunnelProfile.DEFAULT_BAIDU_SERVER) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = draft.baiduConnectHost,
                    onValueChange = { draft = draft.copy(baiduConnectHost = it.trim()) },
                    label = { Text("CONNECT 伪装 Host（留空=用服务器域名）") },
                    placeholder = { Text(XTunnelProfile.DEFAULT_BAIDU_CONNECT_HOST) },
                    singleLine = true,
                )
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = baiduHeadersToText(draft.baiduHeaders),
                    onValueChange = { draft = draft.copy(baiduHeaders = baiduHeadersFromText(it)) },
                    label = { Text("中转请求头（每行 名称: 值）") },
                    placeholder = { Text("X-T5-Auth: …\nUser-Agent: …") },
                    minLines = 3,
                    maxLines = 6,
                )
            }

            if (error != null) {
                Text(
                    "校验：$error",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                enabled = error == null,
                onClick = { onSave(draft) },
            ) {
                Text("保存")
            }
            // 破坏性动作用 error 语义描边按钮（M3 destructive 惯例），与「保存」
            // 主操作拉开视觉层级；行为不变。
            OutlinedButton(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                onClick = onDelete,
                contentColor = MaterialTheme.colorScheme.error,
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

    // UX-R3 第 2 项（自动滚动）：新日志到达时自动滚到底部（follow）；
    // 用户手动上滑查看历史时暂停 follow，出现「回到底部」浮动按钮。
    val listState = rememberLazyListState()
    var followTail by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        while (true) {
            lines = LogStore.snapshot()
            delay(1_000)
        }
    }

    // 用户手动滚动（非程序滚到底引发）时暂停 follow；滚回底部时恢复。
    // 注意：不能用 animateScrollToItem 做自动跟随——动画中间帧「最后一条暂不可见」
    // 会被本判定误读为用户上滑，follow 被自己打断（reaper 续跑复查修正）。
    // 自动跟随改用 scrollToItem（瞬时、无中间帧），本判定只认用户手势。
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            Triple(
                listState.isScrollInProgress,
                info.visibleItemsInfo.lastOrNull()?.index ?: -1,
                info.totalItemsCount,
            )
        }.collect { (scrolling, lastVisible, total) ->
            if (total == 0) return@collect
            when {
                scrolling && lastVisible < total - 1 -> followTail = false
                !scrolling && lastVisible >= total - 1 -> followTail = true
            }
        }
    }

    // follow 开启且尾部有新日志 → 瞬时滚到最后一行（key 取尾部内容，
    // 避免 1s 轮询空转触发）。
    val tail = lines.lastOrNull()
    LaunchedEffect(lines.size, tail?.timestampMillis, tail?.message, followTail) {
        if (followTail && lines.isNotEmpty()) {
            listState.scrollToItem(lines.lastIndex)
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
                    // UI 美化：不可逆动作给 error 语义色（行为不变，仅显性化危险级）。
                    TextButton(onClick = {
                        LogStore.clear()
                        lines = emptyList()
                        followTail = true
                    }) { Text("清空", color = MaterialTheme.colorScheme.error) }
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
        // UX-R3 第 4 项（布局/交互）：旧实现 Column+verticalScroll 全量渲染 500 条
        // 且每行每帧 new SimpleDateFormat（预研 P0 性能硬伤）——改 LazyColumn+
        // itemsIndexed 惰性渲染 + LogLine.render() 复用 ThreadLocal formatter。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = XTunnelSpacing.LG, vertical = XTunnelSpacing.SM),
        ) {
            if (lines.isEmpty()) {
                Text(
                    "暂无日志",
                    modifier = Modifier.align(Alignment.Center),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // UI 美化·日志判据（契约 MUST DO 5）：长文本保持等宽字体与可复制性——
                // 加 SelectionContainer（长按/拖选复制），等宽与等级色不变，功能零回退。
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(XTunnelSpacing.XS),
                    ) {
                        itemsIndexed(lines) { _, line ->
                            Text(
                                text = line.render(),
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                ),
                                color = if (line.level == LogStore.Level.Error) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                }
                // 上滑翻历史后出现「回到底部」浮动按钮（恢复 follow）。
                AnimatedVisibility(
                    visible = !followTail,
                    enter = fadeIn() + slideInVertically { it / 2 },
                    exit = fadeOut() + slideOutVertically { it / 2 },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp),
                ) {
                    // 纯文字 FAB（content lambda 重载）：material-icons-core 不在
                    // 本仓 classpath，且 material3 的 text=/icon= 重载要求成对传
                    // icon（CI 实锤 None of the following candidates）。
                    ExtendedFloatingActionButton(
                        onClick = { followTail = true },
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text("回到底部")
                    }
                }
            }
        }
    }
}

// ===== 百度中转：请求头 Map <-> 「每行 名称: 值」编辑文本 =====

private fun baiduHeadersToText(headers: Map<String, String>): String =
    headers.entries.joinToString("\n") { (k, v) -> "$k: $v" }

private fun baiduHeadersFromText(text: String): Map<String, String> {
    val map = LinkedHashMap<String, String>()
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return@forEach
        val idx = trimmed.indexOf(':')
        val key = trimmed.substring(0, maxOf(idx, 0)).trim()
        if (key.isEmpty() || idx < 0) return@forEach
        map[key] = trimmed.substring(idx + 1).trim()
    }
    return map
}
