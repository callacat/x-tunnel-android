# Changelog

本项目的所有值得记录的变更都登记在此文件。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
调试版（`0.1.0-round<N>`）不单独立节，由 CI 按提交数自增，
正式版本（tag `vMAJOR.MINOR.PATCH`）才在此登记。

## [未发布]

### 变更
- **内核锚定升级到 v0.5.2（WS Upgrade 头剥离修复，任务 recvvlI1JMNbc7）**：
  - `ci.yml`/`release.yml` 的 `CORE_REF` 由 v0.5.0 锚定到 v0.5.2，sidecar
    从 `callacat/x-tunnel` tag `v0.5.2` 构建。该内核版本修复 HTTP 代理转发
    普通请求时剥离 hop-by-hop 头（含 `Upgrade`/`Connection`）的问题——此前
    内网 WebSocket 服务（WebSSH `/sessions/ws/*`）经系统代理握手被降级为
    普通 GET（服务端 404/403 + 5 秒 HTTP-DIRECT 重连循环），现保留
    `Upgrade`/`Connection` 头透传，WS 握手 101 成立。Android 端走 SOCKS5
    无此转发路径，仅随内核 sidecar 升级保持一致。

## [v0.3.0] - 2026-09-15
### 新增
- 检查更新三态（已是最新/发现新版/失败兜底，GitHub Releases API 镜像链）。
- 日志时间轴统一（sidecar UTC 前缀归一为设备时区）与尾部自动跟随
  （上滑暂停 +「回到底部」FAB）。
- 页面切换 150ms 淡入滑移过渡、状态色 200ms 过渡、GEO 更新进行中态。
- 数据面故障可观测性（Ready 下 sidecar 数据面 Failed 显式呈现+查日志直达）。

## [v0.2.0] - 2026-09-13
### 新增
- 百度中转开关（baidu-relay）：隧道先经百度云 CONNECT 中转再到服务器，
  用于直连被干扰场景（merge: baidu-relay）。
### 变更
- v0.1.47→v0.2.0 之间 round 期的分应用代理/GEO 分流系列增强
  （系统应用分组、搜索过滤、镜像离线更新等）。

## [v0.1.47] - 2026-08-30
### 新增
- 分应用代理 Phase2（白名单模式，PR #4）与 GEO 分流框架
  （自定义规则、自动更新开关）。
### 变更
- UI/UX 修复轮：连接状态指示、独立配置页/日志页、三档主题、
  诊断包导出等（代码注释「点 N」系）。

## [v0.1.2 / v0.1.1 / v0.1.0] - 2026-05-20
### 固定
- 早期基线：arm64 核心 DNS 解析修复、x86 运行时与真实 VPN 配置修复、
  CI 构建顺序修复。
