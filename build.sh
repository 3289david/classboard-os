#!/usr/bin/env bash
# Builds dist/ClassBoardOS.apk using only the Android SDK command-line tools (no Gradle).
set -euo pipefail

PROJECT="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_HOME:-${LOCALAPPDATA:-$HOME/AppData/Local}/Android/Sdk}"
SDK="$(cygpath -u "$SDK" 2>/dev/null || echo "$SDK")"
BT="$SDK/build-tools/$(ls "$SDK/build-tools" | sort -V | tail -1)"
PLATFORM="$SDK/platforms/android-34/android.jar"
EXE=""; [ -f "$BT/aapt2.exe" ] && EXE=".exe"
BAT=""; [ -f "$BT/d8.bat" ] && BAT=".bat"

# Build in an ASCII-only temp path (aapt2 on Windows dislikes non-ASCII paths).
WORK="${TMPDIR:-/tmp}/classboard-build"
rm -rf "$WORK"; mkdir -p "$WORK"
cp -r "$PROJECT/app/." "$WORK/app"
cd "$WORK"
mkdir -p out/gen out/classes out/dex

echo "[1/6] resources"
"$BT/aapt2$EXE" compile --dir app/res -o out/res.zip
"$BT/aapt2$EXE" link -o out/base.apk -I "$PLATFORM" --manifest app/AndroidManifest.xml \
  --java out/gen -A app/assets --min-sdk-version 26 --target-sdk-version 34 \
  --version-code 1 --version-name 1.0.0 out/res.zip

echo "[2/6] javac"
find app/src out/gen -name '*.java' > out/sources.txt
javac -nowarn -Xlint:none -source 17 -target 17 -encoding UTF-8 -cp "$PLATFORM" -d out/classes @out/sources.txt 2>&1 | grep -v "warning: \[options\]" || true
[ -f out/classes/kr/classboard/os/MainActivity.class ] || { echo "javac failed"; exit 1; }

echo "[3/6] d8"
"$BT/d8$BAT" --release --min-api 26 --lib "$PLATFORM" --output out/dex $(find out/classes -name '*.class')

echo "[4/6] package"
cp out/base.apk out/unsigned.apk
python - <<'PY'
import zipfile
with zipfile.ZipFile('out/unsigned.apk', 'a', zipfile.ZIP_DEFLATED) as z:
    z.write('out/dex/classes.dex', 'classes.dex')
PY
"$BT/zipalign$EXE" -p -f 4 out/unsigned.apk out/aligned.apk

echo "[5/6] sign"
KS="$PROJECT/keystore/classboard.jks"
if [ ! -f "$KS" ]; then
  mkdir -p "$PROJECT/keystore"
  keytool -genkeypair -keystore "$KS" -alias classboard -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass classboard -keypass classboard -dname "CN=ClassBoard OS, O=School, C=KR" >/dev/null
fi
"$BT/apksigner$BAT" sign --ks "$KS" --ks-pass pass:classboard --key-pass pass:classboard --out out/signed.apk out/aligned.apk

echo "[6/6] output"
mkdir -p "$PROJECT/dist"
cp out/signed.apk "$PROJECT/dist/ClassBoardOS.apk"
"$BT/apksigner$BAT" verify "$PROJECT/dist/ClassBoardOS.apk" && ls -l "$PROJECT/dist/ClassBoardOS.apk"
