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

echo "[1/2] javac"
javac -nowarn --release 17 -encoding UTF-8 -cp "$JSON_JAR" -d "$WORK/classes" $(find "$SERVER/src" -name '*.java')

echo "[2/2] jar"
(cd "$WORK/classes" && jar xf "$JSON_JAR" org)
printf 'Main-Class: kr.classboard.server.ServerMain\n' > "$WORK/manifest.txt"
mkdir -p "$SERVER/dist"
jar cfm "$SERVER/dist/jdms-server.jar" "$WORK/manifest.txt" -C "$WORK/classes" .
ls -l "$SERVER/dist/jdms-server.jar"
