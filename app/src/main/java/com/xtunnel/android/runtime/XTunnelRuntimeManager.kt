package com.xtunnel.android.runtime

import android.content.Context
import android.os.Build
import android.net.VpnService
import com.xtunnel.android.model.PerAppConfigStore
import com.xtunnel.android.model.RouteConfigStore
import com.xtunnel.android.model.XTunnelProfile
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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

    // 流量累计值缓存（八轮修复·traffic字段-1）：trafficMonitor 每次轮询更新，
    // collectDiagnostics 优先读此缓存，避免导出时机与 /v1/stats 时序不一致导致 -1。
    @Volatile
    private var lastTrafficSent: Long = -1L
    @Volatile
    private var lastTrafficReceived: Long = -1L

    // 停止幂等保护（A.1 通知栏关闭闪退）：ACTION_STOP 分支 stop() + stopSelf()→onDestroy 又 stop()
    // 会起两个 stopBlocking 线程并发 close VpnDataPathController（JNI TProxyStopService 非线程安全）。
    // 用 CAS 保证 stopBlocking 只真正执行一次，其余线程直接返回。
    private val stopInProgress = AtomicBoolean(false)

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

        // A.4 强杀重启残留清理：force-stop 后 sidecar（libxtunnel.so 独立 native 进程）
        // 可能还活着占着 SOCKS5/control 端口，导致二次启动冲突/无法访问外网。
        // 启动前 pkill 掉残留（失败静默——幂等，正常启动无残留时 no-op）。
        runCatching {
            ProcessBuilder("sh", "-c", "pkill -f libxtunnel.so 2>/dev/null || true")
                .redirectErrorStream(true).start().waitFor()
        }

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

        // round9（GEO 自定义规则）：用户配置了自定义规则时，把「默认规则 + 自定义规则」
        // 合并写入 runtimeDir/rules.txt，sidecar 通过 config 的 rules_path 读取。
        // core 的 EnsureRulesFile 只在文件不存在时才写默认模板；这里预写入合并后的
        // 完整规则，保证自定义规则生效时默认规则不丢失（core 会热重载 watch 此文件）。
        writeRulesIfNeeded()

        // round9：每次建立新连接时重置流量缓存基线，避免上一次连接残留值被诊断包读到。
        lastTrafficSent = -1L
        lastTrafficReceived = -1L

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
        // A.1：CAS 防并发——通知栏 ACTION_STOP 与 onDestroy 双 stop 会并发 close JNI，
        // 只允许一个线程真正执行停止流程，其余直接返回（幂等）。
        if (!stopInProgress.compareAndSet(false, true)) {
            return
        }
        try {
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
            // round9 修复：不再清空 lastTraffic 缓存——停止后导出诊断包也能读到
            // 最后累计流量（东哥常关闭后导出，之前 stop 清空导致 traffic 恒 -1）。
            // 下次 start 时 trafficMonitor 会以 -1 基线重新记，startBlocking 无需预清。
        } finally {
            stopInProgress.set(false)
        }
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
                    lastTrafficSent = sent
                    lastTrafficReceived = recv
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
                // round9 修复：catch InterruptedException——stopTrafficMonitor() 调
                // interrupt() 时 sleep 抛 InterruptedException，若未捕获会被 Android 记
                // 为 FATAL EXCEPTION（x-tunnel-traffic 线程未捕获异常），累积后 stop 回调
                // 异常 → 关闭按钮闪退。这里捕获后恢复中断标志并优雅退出。
                try {
                    Thread.sleep(TRAFFIC_POLL_MILLIS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@Thread
                }
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

    // round9：手动触发分流规则重载（调 sidecar control API POST /v1/rules/reload）。
    // 供 RouteCard 的「立即更新规则」按钮调用；隧道未运行/sidecar 未就绪返回 false。
    fun reloadRules(): Boolean {
        val ready = readyInfo
        val bearer = token
        if (ready == null || bearer.isBlank()) return false
        return runCatching {
            request("POST", "${ready.controlUrl}/v1/rules/reload", bearer)
            true
        }.getOrDefault(false)
    }

    // round40：手动触发 GEO 数据库更新（POST /v1/route/geo/update）。
    // sidecar 异步下载（走隧道代理），完成后热加载；结果经 routeStatus().siteLoaded
    // /ipLoaded 轮询可见。sidecar 未就绪返回 false。
    fun updateGeo(): Boolean {
        val ready = readyInfo
        val bearer = token
        if (ready == null || bearer.isBlank()) return false
        return runCatching {
            request("POST", "${ready.controlUrl}/v1/route/geo/update", bearer)
            true
        }.getOrDefault(false)
    }

    // round43：未连接时的 GEO 更新——App 直连 GitHub 加速镜像（gh-proxy.org /
    // gh-proxy.com，国内可达）下载 geosite.dat / geoip-lite.dat 到共享 geo 目录，
    // 下次启动 sidecar 即加载。开没开代理都能更新（东哥建议，2026-08-30）。
    // 返回是否全部成功。
    fun updateGeoOffline(): Boolean {
        val geoDir = File(runtimeDir, "geo").apply { mkdirs() }
        val mirrors = listOf("https://gh-proxy.org/", "https://gh-proxy.com/")
        var allOk = true
        for ((name, url) in listOf(
            "geosite.dat" to "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geosite.dat",
            "geoip-lite.dat" to "https://github.com/MetaCubeX/meta-rules-dat/releases/download/latest/geoip-lite.dat",
        )) {
            val dst = File(geoDir, name)
            val ok = downloadTo(mirrors, url, dst)
            if (!ok) allOk = false
            else LogStore.append(LogStore.Level.Info, "GEO 更新（直连镜像）：$name 已更新")
        }
        return allOk
    }

    private fun downloadTo(mirrors: List<String>, rawUrl: String, dst: File): Boolean {
        for (m in mirrors) {
            runCatching {
                val conn = (URL(m + rawUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15_000
                    readTimeout = 120_000
                    setRequestProperty("User-Agent", "x-tunnel-android (geodata updater)")
                }
                conn.use { c ->
                    if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
                    val tmp = File(dst.absolutePath + ".tmp")
                    c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (tmp.length() < 1024) throw IOException("下载内容过短")
                    tmp.renameTo(dst) || (tmp.copyTo(dst, overwrite = true) && tmp.delete())
                }
                return true
            }.onFailure { e ->
                LogStore.append(LogStore.Level.Error, "GEO 镜像下载失败 $m：${e.message ?: e.javaClass.simpleName}")
            }
        }
        return false
    }

    // round9（GEO 自定义规则）：用户配置了自定义规则时，把「默认规则 + 自定义规则」
    // 合并写入 runtimeDir/rules.txt。无自定义规则时删掉旧文件（避免残留上次规则），
    // 让 core 走默认模板 + 自动下载。失败静默——启动不因规则文件写失败而中断。
    private fun writeRulesIfNeeded() {
        runCatching {
            val rulesFile = File(runtimeDir, RULES_FILE)
            val routeCfg = RouteConfigStore.load(appContext)
            if (!routeCfg.enabled || routeCfg.customRules.isEmpty()) {
                rulesFile.delete()
                return@runCatching
            }
            val merged = DEFAULT_RULES + routeCfg.customRules.joinToString("\n", postfix = "\n")
            rulesFile.writeText(merged)
        }.onFailure { e ->
            LogStore.append(LogStore.Level.Error, "写入自定义规则失败: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // 第 9 点：诊断数据采集——返回 JSON 文本，供导出诊断包（zip）拼装。
    // 含流量统计(/v1/stats)、连接状态(/v1/status)、基础信息（token 脱敏）。
    fun collectDiagnostics(): String {
        val ready = readyInfo
        val bearer = token
        var trafficText: String? = null
        var statusText: String? = null
        var routeStatsText: String? = null
        if (ready != null && bearer.isNotBlank()) {
            trafficText = runCatching { request("GET", "${ready.controlUrl}/v1/stats", bearer) }.getOrNull()
            statusText = runCatching { request("GET", "${ready.controlUrl}/v1/status", bearer) }.getOrNull()
            // §2.3：分流统计（route 引擎启用时才有数据；引擎未启用返回 enabled=false）
            routeStatsText = runCatching { request("GET", "${ready.controlUrl}/v1/route/stats", bearer) }.getOrNull()
        }
        val trafficObj = runCatching { trafficText?.let { JSONObject(it) } }.getOrNull()
        val statusObj = runCatching { statusText?.let { JSONObject(it) } }.getOrNull()
        val routeStatsObj = runCatching { routeStatsText?.let { JSONObject(it) } }.getOrNull()
        // 八轮修复·traffic字段-1：优先用 trafficMonitor 缓存的实时累计值，
        // 其次回退到本次 /v1/stats 拉取结果，避免两者都拿不到时报 -1。
        val cachedSent = lastTrafficSent
        val cachedRecv = lastTrafficReceived
        return JSONObject().apply {
            put("collected_at", System.currentTimeMillis())
            put("process_running", process?.isRunning() == true)
            put("pid", ready?.pid ?: -1)
            put("runtime_state", RuntimeStateStore.snapshot().state.name)
            // 流量（区分断点：↑恒0=数据面不转；↑>0↓=0=服务端不回）
            val t = trafficObj?.optJSONObject("traffic")
            val statsSent = t?.optLong("bytes_sent", -1L) ?: -1L
            val statsRecv = t?.optLong("bytes_received", -1L) ?: -1L
            put("traffic_bytes_sent", if (cachedSent >= 0L) cachedSent else statsSent)
            put("traffic_bytes_received", if (cachedRecv >= 0L) cachedRecv else statsRecv)
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
            // §2.3：分流统计 + GEO 库状态（route 引擎未启用时 enabled=false）
            val routeEnabled = routeStatsObj?.optBoolean("enabled", false) ?: false
            put("route_enabled", routeEnabled)
            val rs = routeStatsObj?.optJSONObject("stats")
            put("route_stats", org.json.JSONObject().apply {
                put("proxy", rs?.optLong("proxy", -1L) ?: -1L)
                put("direct", rs?.optLong("direct", -1L) ?: -1L)
                put("rejected", rs?.optLong("rejected", -1L) ?: -1L)
                put("miss", rs?.optLong("miss", -1L) ?: -1L)
            })
            // round40：GEO 库加载状态（geosite/geoip 是否就绪、类别数、规则条数）——
            // 「GEO 数据是否下载到本地并应用上」的直接证据。
            val geo = routeStatsObj?.optJSONObject("geo")
            put("route_geo", org.json.JSONObject().apply {
                put("site_loaded", geo?.optBoolean("site_loaded", false) ?: false)
                put("site_categories", geo?.optInt("site_categories", 0) ?: 0)
                put("ip_loaded", geo?.optBoolean("ip_loaded", false) ?: false)
                put("ip_categories", geo?.optInt("ip_categories", 0) ?: 0)
                put("ip_prefixes", geo?.optInt("ip_prefixes", 0) ?: 0)
                put("rule_count", geo?.optInt("rule_count", 0) ?: 0)
                put("fallback", geo?.optString("fallback", "") ?: "")
            })
            // 分应用代理当前模式（诊断可见性，辅助排查名单生效问题）
            put("per_app_mode", PerAppConfigStore.load(appContext).mode.raw)
        }.toString(2)
    }

    // round40：GEO 运行状态快照（RouteCard 状态行展示用）。sidecar 未运行返回 null。
    // 数据源 /v1/route/stats 的 {enabled, stats, geo} 三段，供 UI 轮询展示
    // 「GEO 库是否就绪 / 规则条数 / proxy-direct 命中计数」。
    data class RouteStatus(
        val enabled: Boolean,
        val proxyHits: Long,
        val directHits: Long,
        val rejectedHits: Long,
        val missHits: Long,
        val siteLoaded: Boolean,
        val ipLoaded: Boolean,
        val ruleCount: Int,
        val fallback: String,
    )

    fun routeStatus(): RouteStatus? {
        val ready = readyInfo
        val bearer = token
        if (ready == null || bearer.isBlank()) return null
        val body = runCatching { request("GET", "${ready.controlUrl}/v1/route/stats", bearer) }.getOrNull() ?: return null
        return runCatching {
            val obj = JSONObject(body)
            val stats = obj.optJSONObject("stats") ?: JSONObject()
            val geo = obj.optJSONObject("geo") ?: JSONObject()
            RouteStatus(
                enabled = obj.optBoolean("enabled", false),
                proxyHits = stats.optLong("proxy", 0L),
                directHits = stats.optLong("direct", 0L),
                rejectedHits = stats.optLong("rejected", 0L),
                missHits = stats.optLong("miss", 0L),
                siteLoaded = geo.optBoolean("site_loaded", false),
                ipLoaded = geo.optBoolean("ip_loaded", false),
                ruleCount = geo.optInt("rule_count", 0),
                fallback = geo.optString("fallback", ""),
            )
        }.getOrNull()
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
            // GEO 分流（§2.3）：全局开关 on 时启用 sidecar route 引擎。
            // route_enabled=true 时 sidecar 用默认模板 + 自动下载 GEO 库。
            // round9：用户配置了自定义规则时，启动前已写 runtimeDir/rules.txt，
            // 这里传 rules_path 指向它，sidecar 读取"默认+自定义"合并后的规则。
            .put("route_enabled", RouteConfigStore.load(appContext).enabled)
            .apply {
                val routeCfg = RouteConfigStore.load(appContext)
                if (routeCfg.enabled && routeCfg.customRules.isNotEmpty()) {
                    put("rules_path", File(runtimeDir, RULES_FILE).absolutePath)
                }
            }
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
        private const val RULES_FILE = "rules.txt"
        private const val READY_TIMEOUT_MILLIS = 10_000L
        private const val CHANNELS_READY_TIMEOUT_MILLIS = 15_000L
        private const val HTTP_TIMEOUT_MILLIS = 2_000L
        private const val TRAFFIC_POLL_MILLIS = 3_000L
        private const val MAX_LOG_LINES = 200
        private const val MAX_DETAIL_CHARS = 160

        // round9：与 core internal/route/rules.go 的 DefaultRules 镜像一致——写 rules.txt
        // 时先放默认模板，再把用户自定义规则追加到末尾（自定义规则最后匹配，可覆盖默认）。
        private const val DEFAULT_RULES = """# 默认路由规则（每行一条，格式: 行为,条件）
# 行为: proxy = 走 WARP 隧道；direct = 本地直连；reject = 拒绝连接（拦截广告）
REJECT,geosite:category-ads-all
direct,geosite:private
direct,geoip:private
proxy,geosite:google
proxy,geoip:google
proxy,geosite:geolocation-!cn
proxy,geoip:telegram
direct,geosite:cn
direct,geoip:cn
"""

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
