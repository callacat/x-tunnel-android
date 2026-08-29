# x-tunnel Android

`x-tunnel-android` is the native Android client for x-tunnel. The target shape is a Kotlin + Jetpack Compose app with an Android `VpnService`, an x-tunnel native sidecar, and a tun2socks data path.

Current status: native preview. The project builds the Android app shell, declares the VPN service, starts the bundled x-tunnel native sidecar, waits for its ready file, establishes an Android TUN interface, and hands the TUN file descriptor to `hev-socks5-tunnel` through its Android JNI API. GitHub Actions CI/release workflows build the sidecar and tun2socks artifacts before assembling APK/AAB assets.

## Build

Requirements:

- JDK 21
- Android SDK with API 36, build-tools 36.0.0, and NDK 27.0.12077973
- Go 1.24.4 when building the x-tunnel sidecar

Local debug build:

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
$env:JAVA_HOME="C:\Program Files\Java\jdk-21"
.\gradlew.bat assembleDebug
```

Build the Android native tun2socks runtime for the supported ABIs:

```powershell
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\scripts\build-hev-android.ps1
```

Build the Android x-tunnel sidecar for the supported ABIs from the sibling core checkout:

```powershell
$env:XTUNNEL_CORE_DIR="C:\Users\liang\GitHub\x-tunnel"
.\scripts\build-core-android.ps1
```

The default native ABI set is `arm64-v8a x86_64`. Override it with `ANDROID_ABIS` when needed:

```powershell
$env:ANDROID_ABIS="arm64-v8a"
```

Then rebuild the APK:

```powershell
.\gradlew.bat assembleDebug
```

Local checks used for this scaffold:

```powershell
.\gradlew.bat --no-daemon --console=plain assembleDebug
.\gradlew.bat --no-daemon --console=plain lintDebug
.\gradlew.bat --no-daemon --console=plain assembleRelease bundleRelease
bash ./scripts/verify-release-assets.sh
```

## Release

Tags matching `vMAJOR.MINOR.PATCH` run `.github/workflows/release.yml`. The release workflow builds the x-tunnel sidecar and tun2socks runtime for `arm64-v8a` and `x86_64`, builds a signed release APK/AAB, generates `SHA256SUMS`, and uploads assets to GitHub Releases.
The Android `versionName` and `versionCode` are derived from the release tag during the workflow.

### 版本号自动递增规范（东哥 2026-08-29 拍板，BLOCKING）

调试包（ci.yml 每次 push 构建）的版本号**禁止手写死**，遵循自增规则：

- **round N = `git rev-list --count HEAD`**（仓库提交总数）。每次 push 新代码，N 自动 +1，无需人工改。
- `versionName` 形态：`0.1.0-round<N>`；`versionCode` = N（单调递增，保证可覆盖安装）。
- **三处同一来源**（env 单源注入）：
  1. `versionName` ← ci.yml 的 `Compute version` step 算好写 `XTUNNEL_ANDROID_VERSION_NAME`，`app/build.gradle.kts` 只读 env（默认 `0.1.0-dev` 仅供本地无 env 兜底，不参与发布）。
  2. APK 文件名 ← ci.yml artifact 名 `x-tunnel-<versionName>-debug`，与 versionName 一致。
  3. 首页显示 ← `RuntimeCard` 动态读 `packageInfo.versionName`，与 versionName 一致。
- 正式 release 例外：`release.yml` 由 tag `vMAJOR.MINOR.PATCH` 派生 versionName/Code，覆盖自增规则。

> 禁止行为：在 `app/build.gradle.kts` 里手写 `orElse("0.1.0-round<N>")` 硬编码轮次。
> 正确做法：本地手动构建不传 env 时用 `0.1.0-dev`；发布一律走 CI（自增或 tag）。

Required repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Scope

This repository is intentionally independent from the Go core repository. CI checks out `6Kmfi6HP/x-tunnel` as an external source when it needs to build the sidecar.

The current runtime ships with a default local-test profile:

- SOCKS5 listen: `socks5://127.0.0.1:11080`
- Server URL: `ws://127.0.0.1:18080/tunnel`
- Token: `local-test-token`

The in-app profile editor persists the selected endpoint locally. It covers profile name, server URL, token, local SOCKS listen URL, connection count, and insecure TLS mode.

## VPN Data Path

The current `VpnDataPathController` verifies whether this native piece is present before it establishes a TUN route:

- `libhev-socks5-tunnel.so`

If it is missing, the service keeps the x-tunnel sidecar running and reports `MissingTun2Socks` in the UI. When present, the service creates the TUN interface, excludes the app UID to avoid sidecar routing loops, writes a tun2socks YAML config, and starts the native forwarding thread.
