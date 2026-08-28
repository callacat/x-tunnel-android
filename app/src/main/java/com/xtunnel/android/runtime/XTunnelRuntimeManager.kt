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

    // 流量监控线程（三轮根因）：周期拉 /v1/stats，把字节增量记入 LogStore，
    // 区分「数据面不转（恒 0）」vs「转了但服务端不回」。空转即停。
    @Volatile
    private var trafficMonitor: Thread? = null

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

        // 点 1（日志增强）：启动前记录关键连接参数，用于定位「仅 IPv4 仍无法连接」断点。
        // 只记非敏感字段（IP 栈/优选 IP/ECH 域名），token 绝不落日志（avoid 明文泄漏）。
        LogStore.append(
            LogStore.Level.Info,
            "启动参数：server=${profile.serverUrl} ipStack=${profile.ipStrategy.ifBlank { "默认" }}" +
                " dialIPs=${profile.dialIPs.ifBlank { "无" }} ech=${profile.ech.ifBlank { "无" }}" +
                " dns=${profile.dns.ifBlank { "默认" }} fallback=${profile.fallback} insecure=${profile.insecure}",
        )

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
        // 三轮根因：等数据通道 smux 真正就绪再启动数据面（而不是 ready.json 一出现就启）。
        waitForChannelsReady(ready.controlUrl, bearer)
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
        startTrafficMonitor(ready.controlUrl, bearer)
    }

    private fun stopBlocking() {
        stopTrafficMonitor()
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

    // 流量监控：周期拉 /v1/stats，把字节增量记入 LogStore，空转即自动停止。
    private fun startTrafficMonitor(controlUrl: String, bearer: String) {
        stopTrafficMonitor()
        trafficMonitor = Thread {
            var lastSent = -1L
            var lastRecv = -1L
            var zeroRounds = 0
            while (process?.isRunning() == true) {
                runCatching {
                    val body = request("GET", "$controlUrl/v1/stats", bearer)
                    val obj = JSONObject(body)
                    val traffic = obj.optJSONObject("traffic") ?: JSONObject()
                    val sent = traffic.optLong("bytes_sent", 0L)
                    val recv = traffic.optLong("bytes_received", 0L)
                    if (lastSent < 0L) {
                        LogStore.append(LogStore.Level.Info, "流量基线 bytes_sent=$sent bytes_received=$recv")
                    } else {
                        val ds = sent - lastSent
                        val dr = recv - lastRecv
                        if (ds > 0 || dr > 0) {
                            zeroRounds = 0
                            LogStore.append(LogStore.Level.Info, "流量 ↑$ds B ↓$dr B（累计 ↑$sent ↓$recv）")
                        } else {
                            zeroRounds++
                        }
                    }
                    lastSent = sent
                    lastRecv = recv
                }.onFailure { e ->
                    LogStore.append(LogStore.Level.Error, "流量监控失败: ${e.message ?: e.javaClass.simpleName}")
                }
                // 30 轮（约 10 分钟）无流量增量视为空闲，自动停止刷屏。
                if (zeroRounds >= 30) {
                    LogStore.append(LogStore.Level.Info, "流量监控空闲 10 分钟，自动停止")
                    return@Thread
                }
                Thread.sleep(TRAFFIC_POLL_MILLIS)
            }
        }.apply {
            name = "x-tunnel-traffic"
            isDaemon = true
            start()
        }
    }

    private fun stopTrafficMonitor() {
        trafficMonitor?.let { runCatching { it.interrupt() } }
        trafficMonitor = null
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

    // 三轮根因修复（2026-08-28 东哥反馈「连上但上不了网」）：ready.json 只表示
    // sidecar 进程/控制面就绪，smux 数据通道要等 DNS 解析 + v2 协议协商完成（约 1~2s）。
    // 若立刻启动数据面，首批流量全部撞「无可用 smux 通道」→ 用户打不开外网。
    // 此处轮询 /v1/status，等 client.channels 至少一个 up=true 再放行，超时降级放行
    // （避免卡死：真失败走 sidecar 自身重连，App 记录超时日志供排查）。
    private fun waitForChannelsReady(controlUrl: String, bearer: String) {
        val deadline = System.currentTimeMillis() + CHANNELS_READY_TIMEOUT_MILLIS
        var loggedWaiting = false
        while (System.currentTimeMillis() < deadline) {
            val status = runCatching { request("GET", "$controlUrl/v1/status", bearer) }.getOrNull()
            val hasUp = status?.let { body ->
                runCatching {
                    val obj = JSONObject(body)
                    val client = obj.optJSONObject("client")
                    val channels = client?.optJSONArray("channels")
                    (0 until (channels?.length() ?: 0)).any { i ->
                        channels?.getJSONObject(i)?.optBoolean("up", false) == true
                    }
                }.getOrDefault(false)
            } ?: false
            if (hasUp) {
                LogStore.append(LogStore.Level.Info, "数据通道已就绪（smux up），启动数据面")
                return
            }
            if (!loggedWaiting) {
                LogStore.append(LogStore.Level.Info, "等待数据通道就绪（smux up）…")
                loggedWaiting = true
            }
            Thread.sleep(200)
        }
        LogStore.append(LogStore.Level.Error, "等待数据通道就绪超时（≥${CHANNELS_READY_TIMEOUT_MILLIS}ms），降级放行数据面")
    }

    // 第 9 点：诊断数据采集——返回 JSON 文本，供导出诊断包（zip）拼装。
    // 含流量统计(/v1/stats)、连接状态(/v1/status)、基础信息（token 脱敏）。
    fun collectDiagnostics(): String {
        val ready = readyInfo
        val bearer = token
        var trafficText: String? = null
        var statusText: String? = null
        if (ready != null && bearer.isNotBlank()) {
            trafficText = runCatching { request("GET", "${ready.controlUrl}/v1/stats", bearer) }.getOrNull()
            statusText = runCatching { request("GET", "${ready.controlUrl}/v1/status", bearer) }.getOrNull()
        }
        val trafficObj = runCatching { trafficText?.let { JSONObject(it) } }.getOrNull()
        val statusObj = runCatching { statusText?.let { JSONObject(it) } }.getOrNull()
        return JSONObject().apply {
            put("collected_at", System.currentTimeMillis())
            put("process_running", process?.isRunning() == true)
            put("pid", ready?.pid ?: -1)
            put("runtime_state", RuntimeStateStore.snapshot().state.name)
            // 流量（区分断点：↑恒0=数据面不转；↑>0↓=0=服务端不回）
            val t = trafficObj?.optJSONObject("traffic")
            put("traffic_bytes_sent", t?.optLong("bytes_sent", -1L) ?: -1L)
            put("traffic_bytes_received", t?.optLong("bytes_received", -1L) ?: -1L)
            val counters = trafficObj?.optJSONObject("counters")
            put("client_reconnects_total", counters?.optLong("client_reconnects_total", -1L) ?: -1L)
            // 连接状态（channels up/down）
            val channels = statusObj?.optJSONObject("client")?.optJSONArray("channels")
            val chArray = org.json.JSONArray()
            for (i in 0 until (channels?.length() ?: 0)) {
                val c = channels?.getJSONObject(i)
                chArray.put(org.json.JSONObject().apply {
                    put("channel", c?.optInt("channel", -1) ?: -1)
                    put("up", c?.optBoolean("up", false) ?: false)
                    put("rtt_seconds", c?.optDouble("rtt_seconds", -1.0) ?: -1.0)
                })
            }
            put("channels", chArray)
        }.toString(2)
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
        private const val CHANNELS_READY_TIMEOUT_MILLIS = 15_000L
        private const val HTTP_TIMEOUT_MILLIS = 2_000L
        private const val TRAFFIC_POLL_MILLIS = 3_000L
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
