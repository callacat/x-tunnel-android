#!/usr/bin/env bash
# UI 截图采集脚本（ui-screenshots.yml 在 android-emulator-runner 内调用）。
# 必须独立成文件：CI run 34981306598 实锤 AER 的 script 参数按【行】拆分
# 逐条交 /usr/bin/sh -c 执行——多行状态传递与 heredoc 均失效，且 dash
# 不支持 set -o pipefail。AER script 只留一行：bash scripts/本文件。
#
# 截图钩子契约（MainActivity.debugExtras，仅 debug 构建有效）：
#   debug_theme=light|dark  debug_screen=Dashboard|Profiles|PerApp|Logs
set -euo pipefail

OUT=ui-screenshots
mkdir -p "$OUT"
PKG=com.xtunnel.android.debug
# debug 构建 applicationIdSuffix=".debug"，Activity 类名不变。
ACT="${PKG}/com.xtunnel.android.MainActivity"
APK=$(find apk -name '*.apk' | head -1)
test -n "$APK"
# -g 预授予全部运行时权限（POST_NOTIFICATIONS 等），防首启权限弹窗污染截图。
adb install -r -g "$APK"
# 后台收全量 logcat；脚本/模拟器异常终止时文件已含故障前日志
# （action 拆机后 adb 不可达，必须运行期收集）。
adb logcat -v time > "$OUT/logcat.txt" 2>&1 &
LOGCAT_PID=$!

nav() {
  # 每个状态先 force-stop，保证冷启动、状态间零残留。
  adb shell am force-stop "$PKG"
  STATUS=$(adb shell am start -W -n "$ACT" \
    --es debug_theme "$1" --es debug_screen "$2")
  echo "$STATUS"
  echo "$STATUS" | grep -q "Status: ok"
  # 等过渡动画/首帧稳定。
  sleep 3
}

shot() {
  timeout 60 adb exec-out screencap -p > "$OUT/$1.png"
  test -s "$OUT/$1.png"
}

for theme in light dark; do
  for screen in Dashboard Profiles PerApp Logs; do
    nav "$theme" "$screen"
    shot "${theme}_${screen}"
    if [ "$theme" = dark ] && [ "$screen" = Dashboard ]; then
      # 额外一张：Dashboard（dark）上滑到底，覆盖「运行时/关于」卡区域；
      # pixel_7 1080x2400 中列坐标，换设备 profile 需换算。
      timeout 30 adb shell input swipe 540 1400 540 400 300
      sleep 1
      shot dark_Dashboard_scrolled
    fi
  done
done

kill "$LOGCAT_PID" 2>/dev/null || true
ls -l "$OUT"
N=$(find "$OUT" -name '*.png' | wc -l)
test "$N" -eq 9 || { echo "expect 9 png, got $N"; exit 1; }
