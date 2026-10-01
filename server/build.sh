#!/usr/bin/env bash
# Builds server/dist/jdms-server.jar (Java 17+). Only uses files inside server/.
set -euo pipefail

SERVER="$(cd "$(dirname "$0")" && pwd)"
WORK="${TMPDIR:-/tmp}/jdms-server-build"
rm -rf "$WORK"; mkdir -p "$WORK/classes"

# org.json (same JSON API the Android client uses)
JSON_JAR="$SERVER/lib/json-20240303.jar"
if [ ! -f "$JSON_JAR" ]; then
  mkdir -p "$SERVER/lib"
  curl -sSL -o "$JSON_JAR" https://repo1.maven.org/maven2/org/json/json/20240303/json-20240303.jar
fi

# PDFBox renders attachments (PDF, and HWP/Office after LibreOffice converts them) to page images
LIBS=("$JSON_JAR")
for spec in org/apache/pdfbox/pdfbox/2.0.32/pdfbox-2.0.32.jar org/apache/pdfbox/fontbox/2.0.32/fontbox-2.0.32.jar commons-logging/commons-logging/1.2/commons-logging-1.2.jar; do
  f="$SERVER/lib/$(basename "$spec")"
  [ -f "$f" ] || curl -sSL -o "$f" "https://repo1.maven.org/maven2/$spec"
  LIBS+=("$f")
done
CP="$(IFS=:; echo "${LIBS[*]}")"
case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*)
  WIN=(); for f in "${LIBS[@]}"; do WIN+=("$(cygpath -m "$f")"); done
  CP="$(IFS=';'; echo "${WIN[*]}")";;
esac

echo "[1/2] javac"
javac -nowarn --release 17 -encoding UTF-8 -cp "$CP" -d "$WORK/classes" $(find "$SERVER/src" -name '*.java')

echo "[2/2] jar"
for f in "${LIBS[@]}"; do (cd "$WORK/classes" && jar xf "$f"); done
rm -f "$WORK/classes/META-INF/"*.SF "$WORK/classes/META-INF/"*.RSA "$WORK/classes/META-INF/"*.DSA "$WORK/classes/META-INF/MANIFEST.MF"
printf 'Main-Class: kr.classboard.server.ServerMain\n' > "$WORK/manifest.txt"
mkdir -p "$SERVER/dist"
jar cfm "$SERVER/dist/jdms-server.jar" "$WORK/manifest.txt" -C "$WORK/classes" .
ls -l "$SERVER/dist/jdms-server.jar"
