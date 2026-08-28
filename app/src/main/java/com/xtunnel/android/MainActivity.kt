package com.xtunnel.android

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.compose.ui.Alignment
import com.xtunnel.android.model.DefaultProfile
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
            onOpenLogs = { screen = Screen.Logs },
            onThemeChange = onThemeChange,
        )
        Screen.Profiles -> ProfileListScreen(onBack = { screen = Screen.Dashboard })
        Screen.Logs -> LogScreen(onBack = { screen = Screen.Dashboard })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    onOpenProfiles: () -> Unit,
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
        Button(
            modifier = Modifier.weight(1f),
            enabled = !busy && !running,
            onClick = onConnect,
        ) {
            Text(text = "连接")
        }
        // 点 6：关闭按钮在运行/忙碌时均可用，接 stop 链路
        OutlinedButton(
            modifier = Modifier.weight(1f),
            enabled = running || busy,
            onClick = onDisconnect,
        ) {
            Text(text = "关闭")
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
                        // 第 9 点：导出诊断包（流量统计 + 连接状态 + 日志 + 基础信息，token 脱敏）
                        val runtime = XTunnelRuntimeManager.get(context)
                        val uri = DiagnosticExporter.export(context, runtime) ?: return@TextButton
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "application/zip"
                            putExtra(Intent.EXTRA_STREAM, uri)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        context.startActivity(Intent.createChooser(share, "导出诊断包"))
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