#!/usr/bin/env bash
# Build VoxPilot.jar = app (fat entry) + embedded agent jar at /agent/voxpilot-agent.jar
#
# IMPORTANT: The agent is loaded into the Forge MDK run/mods folder, which uses
# official (Mojang) mappings at runtime. Embed the *dev* agent jar (not reobf),
# or ClientRuntime will NoSuchMethodError Minecraft.m_91087_().
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

VERSION="${VOXPILOT_VERSION:-1.2.5}"
OUT_DIR="$ROOT/build/dist"
APP_CLASSES="$ROOT/build/app-classes"
AGENT_DIR="$ROOT/agent-src"

echo "==> Building Forge agent (official-mapped devJar)..."
(
  cd "$AGENT_DIR"
  chmod +x gradlew
  ./gradlew --no-daemon clean devJar jar
)

# Prefer classifier "dev" (official mappings) for MDK embedding.
AGENT_JAR=""
if ls "$AGENT_DIR"/build/libs/voxpilot-agent-*-dev.jar >/dev/null 2>&1; then
  AGENT_JAR="$(ls -1 "$AGENT_DIR"/build/libs/voxpilot-agent-*-dev.jar | head -1)"
else
  echo "dev jar missing; falling back to plain jar (may break MDK)" >&2
  AGENT_JAR="$(ls -1 "$AGENT_DIR"/build/libs/voxpilot-agent-*.jar | grep -v 'sources\|javadoc' | head -1)"
fi
if [[ -z "${AGENT_JAR}" || ! -f "${AGENT_JAR}" ]]; then
  echo "Agent jar not found under agent-src/build/libs" >&2
  ls -la "$AGENT_DIR"/build/libs || true
  exit 1
fi
echo "    agent: $AGENT_JAR"

echo "==> Compiling app (dev.voxpilot.app)..."
rm -rf "$APP_CLASSES"
mkdir -p "$APP_CLASSES"
find app-src -name "*.java" > "$ROOT/build/sources.list"
javac --release 17 -encoding UTF-8 -d "$APP_CLASSES" @"$ROOT/build/sources.list"

mkdir -p "$APP_CLASSES/agent"
cp "$AGENT_JAR" "$APP_CLASSES/agent/voxpilot-agent.jar"

if [[ -d examples ]]; then
  mkdir -p "$APP_CLASSES/examples"
  cp -r examples/. "$APP_CLASSES/examples/" || true
fi

echo "==> Packaging VoxPilot.jar (version $VERSION)..."
mkdir -p "$OUT_DIR"
MANIFEST="$ROOT/build/MANIFEST.MF"
cat > "$MANIFEST" <<EOF
Manifest-Version: 1.0
Main-Class: dev.voxpilot.app.Main
Implementation-Title: VoxPilot
Implementation-Version: $VERSION
EOF
jar cfm "$OUT_DIR/VoxPilot.jar" "$MANIFEST" -C "$APP_CLASSES" .
cp "$OUT_DIR/VoxPilot.jar" "$ROOT/VoxPilot.jar"

echo "==> Done: $OUT_DIR/VoxPilot.jar"
ls -la "$OUT_DIR/VoxPilot.jar" "$ROOT/VoxPilot.jar"
jar tf "$OUT_DIR/VoxPilot.jar" | head -20
