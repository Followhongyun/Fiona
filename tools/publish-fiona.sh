#!/usr/bin/env bash
# 发布一版 Fiona:构建 → 上传 APK → 更新版本清单。
# 用法: ./tools/publish-fiona.sh 48 0.2.3 "更新说明"
set -euo pipefail

CODE="${1:?versionCode，例如 48}"
NAME="${2:?versionName，例如 0.2.3}"
NOTES="${3:-}"

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${FIONA_HOST:-ubuntu@82.157.37.212}"
KEY="${FIONA_KEY:-$HOME/.dsh/mika-relay/relay_ed25519}"
DIR="/home/ubuntu/mika-relay/fiona"
PUBLIC="https://dafeiyu.followhongyun.com:8443"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"

cd "$ROOT"
echo "== 构建 $NAME ($CODE)"
./gradlew :app:assembleRelease -PfionaVersionCode="$CODE" -PfionaVersionName="$NAME" --no-daemon -q

APK="$ROOT/app/build/outputs/apk/release/app-arm64-v8a-release.apk"
SHA=$(shasum -a 256 "$APK" | awk '{print $1}')

echo "== 上传 $(du -h "$APK" | cut -f1)"
scp -q -F /dev/null -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null -i "$KEY" "$APK" "$HOST:$DIR/fiona-arm64.apk"

echo "== 更新清单"
ssh -F /dev/null -o BatchMode=yes -o IdentitiesOnly=yes -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null -i "$KEY" "$HOST" \
  "printf '%s' '{\"versionCode\":$CODE,\"versionName\":\"$NAME\",\"url\":\"$PUBLIC/fiona/fiona-arm64.apk\",\"sha256\":\"$SHA\",\"notes\":\"$NOTES\"}' > $DIR/version.json"

echo "== 已发布 $NAME ($CODE) sha=${SHA:0:12}"
echo "   手机下次启动会自己检查；连着 adb 时也可以直接: adb install -r $APK"
