package com.xtunnel.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import com.xtunnel.android.model.ProfileStore
import com.xtunnel.android.model.XTunnelProfile
import com.xtunnel.android.model.validationError
import com.xtunnel.android.runtime.RuntimeSnapshot
import com.xtunnel.android.runtime.RuntimeState
import com.xtunnel.android.runtime.RuntimeStateStore
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

@Composable
private fun XTunnelApp() {
    MaterialTheme(colorScheme = XTunnelColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            DashboardScreen()
        }
    }
}

private val XTunnelColorScheme = lightColorScheme(
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

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun DashboardScreen() {
    val context = LocalContext.current
    var snapshot by remember { mutableStateOf(RuntimeStateStore.snapshot()) }
    var profile by remember { mutableStateOf(ProfileStore.load(context)) }
    fun fail(message: String) {
        RuntimeStateStore.update(
            RuntimeSnapshot(
                state = RuntimeState.Failed,
                profileName = profile.name,
                detail = message,
            ),
        )
    }
    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            XTunnelVpnService.start(context, profile)
        } else {
            fail("VPN permission was not granted")
        }
    }
    fun startVpn() {
        profile.validationError()?.let { error ->
            fail(error)
            return
        }
        ProfileStore.save(context, profile)
        val intent = VpnService.prepare(context)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            XTunnelVpnService.start(context, profile)
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        startVpn()
    }
    val busy = snapshot.state == RuntimeState.Starting || snapshot.state == RuntimeState.Stopping

    LaunchedEffect(Unit) {
        while (true) {
            snapshot = RuntimeStateStore.snapshot()
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
            StatusCard(snapshot)
            ProfileCard(
                profile = profile,
                onProfileChange = { profile = it },
                onSave = {
                    val error = profile.validationError()
                    if (error != null) {
                        fail(error)
                    } else {
                        ProfileStore.save(context, profile)
                        RuntimeStateStore.update(
                            RuntimeStateStore.snapshot().copy(
                                profileName = profile.name,
                                detail = "Profile saved",
                                updatedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                },
            )
            ActionRow(
                busy = busy,
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
            RuntimeCard(snapshot)
        }
    }
}

@Composable
private fun ProfileCard(
    profile: XTunnelProfile,
    onProfileChange: (XTunnelProfile) -> Unit,
    onSave: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Profile",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = profile.name,
                onValueChange = { onProfileChange(profile.copy(name = it)) },
                label = { Text("Name") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = profile.serverUrl,
                onValueChange = { onProfileChange(profile.copy(serverUrl = it.trim())) },
                label = { Text("Server URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = profile.token,
                onValueChange = { onProfileChange(profile.copy(token = it)) },
                label = { Text("Token") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = profile.socksListen,
                onValueChange = { onProfileChange(profile.copy(socksListen = it.trim())) },
                label = { Text("Local SOCKS") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onProfileChange(
                            profile.copy(connections = (profile.connections - 1).coerceAtLeast(1)),
                        )
                    },
                ) {
                    Text(text = "-")
                }
                Text(
                    modifier = Modifier
                        .weight(1f)
                        .padding(top = 12.dp),
                    text = "Connections ${profile.connections}",
                    fontWeight = FontWeight.SemiBold,
                )
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onProfileChange(
                            profile.copy(connections = (profile.connections + 1).coerceAtMost(16)),
                        )
                    },
                ) {
                    Text(text = "+")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = "Allow insecure TLS",
                )
                Switch(
                    checked = profile.insecure,
                    onCheckedChange = { onProfileChange(profile.copy(insecure = it)) },
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    modifier = Modifier.padding(top = 12.dp),
                    text = "Use TLS fallback",
                )
                Switch(
                    checked = profile.fallback,
                    onCheckedChange = { onProfileChange(profile.copy(fallback = it)) },
                )
            }
            Text(
                text = "Anti-interference (advanced)",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = profile.ech,
                onValueChange = { onProfileChange(profile.copy(ech = it.trim())) },
                label = { Text("ECH domain") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = profile.dialIPs,
                onValueChange = { onProfileChange(profile.copy(dialIPs = it.trim())) },
                label = { Text("CF preferred IPs / hosts (-ip)") },
                placeholder = { Text("e.g. 1.2.3.4 or cf.example.com, comma separated") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = profile.ipStrategy,
                onValueChange = { onProfileChange(profile.copy(ipStrategy = it.trim())) },
                label = { Text("IP stack (-ips)") },
                placeholder = { Text("4 / 6 / 4,6 / 6,4") },
                singleLine = true,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = profile.dnsCacheTtl,
                onValueChange = { onProfileChange(profile.copy(dnsCacheTtl = it.trim())) },
                label = { Text("DNS cache TTL") },
                placeholder = { Text("5m / 30s / 0 (disable)") },
                singleLine = true,
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                onClick = onSave,
            ) {
                Text(text = "Save")
            }
        }
    }
}

@Composable
private fun StatusCard(snapshot: RuntimeSnapshot) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = snapshot.state.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = snapshot.detail,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (snapshot.profileName.isNotBlank()) {
                Text(text = "Profile: ${snapshot.profileName}")
            }
            Text(text = "Data path: ${snapshot.dataPathState.name}")
            Text(text = snapshot.dataPathDetail)
        }
    }
}

@Composable
private fun ActionRow(
    busy: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            modifier = Modifier.weight(1f),
            enabled = !busy,
            onClick = onConnect,
        ) {
            Text(text = "Connect")
        }
        OutlinedButton(
            modifier = Modifier.weight(1f),
            enabled = !busy,
            onClick = onDisconnect,
        ) {
            Text(text = "Disconnect")
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
                text = "Runtime",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = "Android API: ${Build.VERSION.SDK_INT}")
            if (snapshot.controlUrl.isNotBlank()) {
                Text(text = "Control: ${snapshot.controlUrl}")
            }
            snapshot.pid?.let {
                Text(text = "Core PID: $it")
            }
        }
    }
}
