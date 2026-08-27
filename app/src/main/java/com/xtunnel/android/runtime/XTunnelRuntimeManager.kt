package com.xtunnel.android.runtime

import android.content.Context
import android.os.Build
import android.net.VpnService
import com.xtunnel.android.model.XTunnelProfile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class XTunnelRuntimeManager private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val runtimeDir = File(appContext.filesDir, "runtime")

    @Volatile
    private var process: Process? = null

    @Volatile
    private var readyInfo: ReadyInfo? = null

    @Volatile
    private var token: String = ""

    @Volatile
    private var dataPathController: VpnDataPathController? = null

    @Synchronized
    fun start(profile: XTunnelProfile, vpnService: VpnService? = null) {
        if (process?.isRunning() == true) {
            RuntimeStateStore.update(
                RuntimeStateStore.snapshot().copy(
                    detail = "已在运行",
                    updatedAtMillis = System.currentTimeMillis(),
                ),
            )
            return
        }

        RuntimeStateStore.update(
            RuntimeSnapshot(
                state = RuntimeState.Starting,
                profileName = profile.name,
                detail = "正在启动 x-tunnel sidecar",
            ),
        )
        LogStore.append(LogStore.Level.Info, "启动隧道：${profile.name}")

        executor.execute {
            runCatching {
                startBlocking(profile, vpnService)
            }.onFailure { error ->
                LogStore.append(LogStore.Level.Error, "启动失败: ${error.message ?: error.javaClass.simpleName}")
                RuntimeStateStore.update(
                    RuntimeSnapshot(
                        state = RuntimeState.Failed,
                        profileName = profile.name,
                        detail = "启动失败：${error.message ?: error.javaClass.simpleName}",
                    ),
                )
                stopBlocking()
            }
        }
    }

    @Synchronized
    fun stop() {
        // 点 6：stop 用独立线程立即执行，避免与 start 排队同一单线程 executor 被启动流程阻塞，
        // 导致「关闭按钮失效」。
        RuntimeStateStore.update(
            RuntimeStateStore.snapshot().copy(
                state = RuntimeState.Stopping,
                detail = "正在停止 x-tunnel sidecar",
                updatedAtMillis = System.currentTimeMillis(),
            ),
        )
        LogStore.append(LogStore.Level.Info, "用户请求停止隧道")
        Thread {
            runCatching { stopBlocking() }
            LogStore.append(LogStore.Level.Info, "隧道已停止，状态已复位")
            RuntimeStateStore.update(RuntimeSnapshot(detail = "已停止"))
        }.apply {
            name = "x-tunnel-stop"
            isDaemon = true
            start()
        }
    }

    private fun startBlocking(profile: XTunnelProfile, vpnService: VpnService?) {
        runtimeDir.mkdirs()
        val executable = File(appContext.applicationInfo.nativeLibraryDir, NATIVE_EXECUTABLE)
        require(executable.isFile) { "Missing native sidecar: ${executable.absolutePath}" }

        val configFile = File(runtimeDir, "profile.json")
        val readyFile = File(runtimeDir, "ready.json")
        val tokenFile = File(runtimeDir, "control-token")
        readyFile.delete()
        tokenFile.delete()
        configFile.writeText(profile.toConfigJson().toString(2))

        val command = listOf(
            executable.absolutePath,
            "-config",
            configFile.absolutePath,
            "-control",
            "127.0.0.1:0",
            "-ready-file",
            readyFile.absolutePath,
            "-control-token-file",
            tokenFile.absolutePath,
        )

        val started = ProcessBuilder(command)
            .directory(runtimeDir)
            .redirectErrorStream(true)
            .start()
        process = started
        consumeOutput(started)

        val ready = waitForReady(readyFile)
        val bearer = tokenFile.readText().trim()
        readyInfo = ready
        token = bearer
        checkHealth(ready.controlUrl)
        val dataPathResult = if (vpnService != null) {
                VpnDataPathController(vpnService).also { dataPathController = it }.start(profile)
            } else {
                VpnDataPathController.VpnDataPathResult(
                    state = VpnDataPathState.NotStarted,
                    detail = "未提供 VPN 服务",
                )
            }
        RuntimeStateStore.update(
            RuntimeSnapshot(
                state = RuntimeState.Ready,
                profileName = profile.name,
                detail = "x-tunnel sidecar 已就绪",
                controlUrl = ready.controlUrl,
                pid = ready.pid,
                dataPathState = dataPathResult.state,
                dataPathDetail = dataPathResult.detail,
            ),
        )
        LogStore.append(LogStore.Level.Info, "隧道就绪 pid=${ready.pid} 数据面=${dataPathResult.state.name}")
    }

    private fun stopBlocking() {
        dataPathController?.close()
        dataPathController = null
        val ready = readyInfo
        val bearer = token
        if (ready != null && bearer.isNotBlank()) {
            runCatching {
                request(
                    method = "POST",
                    target = "${ready.controlUrl}/v1/runtime/stop",
                    bearer = bearer,
                )
            }
        }
        val currentProcess = process
        if (currentProcess != null && currentProcess.isRunning()) {
            currentProcess.destroy()
            if (!currentProcess.waitForExit(2_000L)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    currentProcess.destroyForcibly()
                } else {
                    currentProcess.destroy()
                }
            }
        }
        process = null
        readyInfo = null
        token = ""
    }

    private fun waitForReady(file: File): ReadyInfo {
        val deadline = System.currentTimeMillis() + READY_TIMEOUT_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val currentProcess = process
            if (currentProcess != null && !currentProcess.isRunning()) {
                error("x-tunnel exited before ready")
            }
            if (file.isFile && file.length() > 0L) {
                val json = JSONObject(file.readText())
                return ReadyInfo(
                    pid = json.optInt("pid"),
                    controlUrl = json.getString("control_url"),
                    tokenFile = json.optString("token_file"),
                )
            }
            Thread.sleep(150)
        }
        error("Timed out waiting for x-tunnel ready file")
    }

    private fun checkHealth(controlUrl: String) {
        request("GET", "$controlUrl/v1/health", "")
    }

    private fun request(method: String, target: String, bearer: String): String {
        val connection = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = HTTP_TIMEOUT_MILLIS.toInt()
            readTimeout = HTTP_TIMEOUT_MILLIS.toInt()
            if (bearer.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $bearer")
            }
            if (method == "POST") {
                doOutput = true
            }
        }
        return connection.use { conn ->
            val code = conn.responseCode
            val body = if (code in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else {
                conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            }
            check(code in 200..299) { "control API $method $target failed: HTTP $code $body" }
            body
        }
    }

    private fun consumeOutput(started: Process) {
        LogStore.redirectTo(appContext)
        Thread {
            runCatching {
                started.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        // 点 5：sidecar 输出进入日志页；最近一条同时反映到状态卡详情。
                        LogStore.append(LogStore.Level.Info, line)
                        RuntimeStateStore.update(
                            RuntimeStateStore.snapshot().copy(
                                detail = line.take(MAX_DETAIL_CHARS),
                                updatedAtMillis = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                if (error !is IOException) {
                    val msg = "日志读取失败: ${error.message ?: error.javaClass.simpleName}"
                    LogStore.append(LogStore.Level.Error, msg)
                    RuntimeStateStore.update(
                        RuntimeStateStore.snapshot().copy(
                            detail = msg,
                            updatedAtMillis = System.currentTimeMillis(),
                        ),
                    )
                }
            }
        }.apply {
            name = "x-tunnel-output"
            isDaemon = true
            start()
        }
    }

    private fun XTunnelProfile.toConfigJson(): JSONObject {
        return JSONObject()
            .put("listen", socksListen)
            .put("forward", serverUrl)
            .put("token", this.token)
            .apply { if (metricsListen.isNotBlank()) put("metrics", metricsListen) }
            .put("cidr", cidr)
            // dns/ech 空则不写，避免覆盖 sidecar 默认语义（点 7：不内置域名）
            .apply { if (dns.isNotBlank()) put("dns", dns) }
            .apply { if (ech.isNotBlank()) put("ech", ech) }
            .put("block", blockPorts)
            .put("connections", connections)
            .put("insecure", insecure)
            .put("fallback", fallback)
            // 抗干扰参数：仅显式配置时写入，避免空串覆盖 sidecar 默认语义
            .apply { if (dialIPs.isNotBlank()) put("ip", dialIPs) }
            .apply { if (ipStrategy.isNotBlank()) put("ips", ipStrategy) }
            .apply { if (dnsCacheTtl.isNotBlank()) put("dns_cache_ttl", dnsCacheTtl) }
            .put("dial_timeout", "5s")
            .put("ws_handshake_timeout", "5s")
            .put("reconnect_delay", "1s")
            .put("reconnect_max_delay", "30s")
            .put("reconnect_jitter", "500ms")
            .put("rtt_timeout", "2s")
            .put("dns_timeout", "3s")
            .put("ech_retry_delay", "2s")
            .put("udp_read_timeout", "1s")
            .put("shutdown_timeout", "10s")
            .put("auth_skew", "30s")
            .put("preauth_timeout", "5s")
    }

    private data class ReadyInfo(
        val pid: Int,
        val controlUrl: String,
        val tokenFile: String,
    )

    companion object {
        private const val NATIVE_EXECUTABLE = "libxtunnel.so"
        private const val READY_TIMEOUT_MILLIS = 10_000L
        private const val HTTP_TIMEOUT_MILLIS = 2_000L
        private const val MAX_LOG_LINES = 200
        private const val MAX_DETAIL_CHARS = 160

        @Volatile
        private var instance: XTunnelRuntimeManager? = null

        fun get(context: Context): XTunnelRuntimeManager {
            return instance ?: synchronized(this) {
                instance ?: XTunnelRuntimeManager(context).also { instance = it }
            }
        }
    }
}

private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R {
    return try {
        block(this)
    } finally {
        disconnect()
    }
}

private fun Process.isRunning(): Boolean {
    return try {
        exitValue()
        false
    } catch (_: IllegalThreadStateException) {
        true
    }
}

private fun Process.waitForExit(timeoutMillis: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (!isRunning()) return true
        Thread.sleep(50)
    }
    return !isRunning()
}
