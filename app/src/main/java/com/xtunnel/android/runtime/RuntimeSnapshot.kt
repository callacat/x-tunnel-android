package com.xtunnel.android.runtime

data class RuntimeSnapshot(
    val state: RuntimeState = RuntimeState.Stopped,
    val profileName: String = "",
    val detail: String = "已停止",
    val controlUrl: String = "",
    val pid: Int? = null,
    val dataPathState: VpnDataPathState = VpnDataPathState.NotStarted,
    val dataPathDetail: String = "VPN 数据面未启动",
    val updatedAtMillis: Long = System.currentTimeMillis(),
)

enum class RuntimeState(val label: String) {
    Stopped("已停止"),
    Starting("连接中"),
    Ready("运行中"),
    Stopping("停止中"),
    Failed("失败"),
}

enum class VpnDataPathState(val label: String) {
    NotStarted("未启动"),
    MissingTun2Socks("缺少 tun2socks"),
    Running("运行中"),
    Failed("失败"),
}

object RuntimeStateStore {
    @Volatile
    private var current = RuntimeSnapshot()

    fun snapshot(): RuntimeSnapshot = current

    fun update(snapshot: RuntimeSnapshot) {
        current = snapshot
    }
}
