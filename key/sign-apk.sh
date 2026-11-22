#!/usr/bin/env bash
# 对 APK 做 zipalign + 签名，输出可直接安装的包。
# 用法: ./key/sign-apk.sh <unsigned.apk> [out.apk]
set -euo pipefail

APK_IN=${1:?用法: ./key/sign-apk.sh <unsigned.apk> [out.apk]}
APK_OUT=${2:-${APK_IN%.apk}-signed.apk}

HERE=$(cd "$(dirname "$0")" && pwd)
KS="$HERE/mdm-release.keystore"
KS_PASS=${KS_PASS:-android}
KEY_ALIAS=${KEY_ALIAS:-androiddebugkey}
KEY_PASS=${KEY_PASS:-android}

SDK=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}
BT=${BUILD_TOOLS:-$(ls -d "$SDK"/build-tools/* 2>/dev/null | sort -V | tail -1 || true)}
if [ -z "${BT:-}" ] || [ ! -x "$BT/apksigner" ]; then
  echo "找不到 apksigner，请设置 ANDROID_HOME 或 BUILD_TOOLS 指向 build-tools 目录" >&2
  exit 1
fi

TMP=$(mktemp -t mdm-aligned-XXXXXX.apk)
trap 'rm -f "$TMP"' EXIT

"$BT/zipalign" -p -f 4 "$APK_IN" "$TMP"
"$BT/apksigner" sign \
  --ks "$KS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KEY_PASS" \
  --ks-key-alias "$KEY_ALIAS" \
  --out "$APK_OUT" \
  "$TMP"
"$BT/apksigner" verify --print-certs "$APK_OUT" | head -4

echo "已签名: $APK_OUT"
