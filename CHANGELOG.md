# Changelog

本项目的所有值得记录的变更都登记在此文件。

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。
调试版（`0.1.0-round<N>`）不单独立节，由 CI 按提交数自增，
正式版本（tag `vMAJOR.MINOR.PATCH`）才在此登记。

## [未发布]

### 新增
- CI UI 截图流水线（`.github/workflows/ui-screenshots.yml`）：debug APK +
  模拟器矩阵截取亮/暗 × 四页共 9 图（task/** 推送触发）。
- debug 构建专用 intent 钩子 `debug_theme`/`debug_screen`
  （`BuildConfig.DEBUG` 门禁，release 无效）。
- 暗色冷启动窗口主题（`values-night/styles.xml`），消除深色模式白闪。

### 变更
- **UI 全面美化（Material 3 令牌化，任务 recvvih6G8BCvC）**：
  - 设计令牌单源化到 `model/XTunnelTheme.kt`：亮/暗调色板、状态语义色
    （`StatusColors`）、间距网格（`XTunnelSpacing` 4dp：4/8/12/16/24）；
    页面文件（`MainActivity.kt`）硬编码 `Color(0x…)` 清零。
  - 补齐 `primaryContainer`/`secondaryContainer`（sky/slate 系），消除
    M3 基线默认紫混入 chip/FAB 的色板冲突。
  - 状态色按 WCAG 实算达标：亮色主题 running/pending 由 3.30/3.19 提升至
    5.02/5.02（AA ≥4.5）。
  - 连接状态页：状态点改几何圆点；状态卡 tonal 层级；主题三档由
    Switch 列表改 `SingleChoiceSegmentedButtonRow`（与分应用页统一）；
    连接/关闭按钮禁用态 α=0.38、高度 ≥48dp、形状对齐卡片。
  - 日志页：「清空」destructive 语义色；等宽字体/尾部跟随/导出链路保持不变
    （长按复制未做——本 Compose 版本无稳定 `SelectionContainer`，CI 实锤）。
  - 配置页：激活配置「当前」tonal 徽标；AppRow 改 `Card(onClick)`
    （ripple 正确裁剪+按压层级）；组标题去 ASCII 装饰线；
    「删除此配置」文字 error 语义色；规则编辑器等宽字体。
  - 关于卡（运行时）：新增 `InfoRow` 键值行，数值等宽对齐。
- 卡片圆角统一 `shapes.medium`（12dp）、列表行 `shapes.small`（8dp）。

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
