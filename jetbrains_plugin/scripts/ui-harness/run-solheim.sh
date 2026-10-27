set -uo pipefail
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
: "${IDE_DIR:?Set IDE_DIR to a JCEF-enabled IntelliJ installation}"
: "${PLUGIN_ZIP:?Set PLUGIN_ZIP to the built ZooCode distribution}"
: "${SOLHEIM_RELAY_TOKEN:?Set the same random token as the relay}"
: "${HARNESS_WM_BIN:?Set HARNESS_WM_BIN to openbox or an equivalent wrapper}"
IDE_DIR="$(realpath "$IDE_DIR")"
PLUGIN_ZIP="$(realpath "$PLUGIN_ZIP")"
TEST=p
H="$(mktemp -d "${TMPDIR:-/tmp}/zoo-solheim-e2e.XXXXXXXX")"
echo "Disposable harness and evidence: $H"
mkdir -p "$H/driver/classes/META-INF" "$H/plugins-dist/harness-driver/lib"
"$IDE_DIR/jbr/bin/javac" -cp "$IDE_DIR/lib/*" -d "$H/driver/classes" "$SOURCE_DIR/HarnessDriver.java" || exit 1
cp "$SOURCE_DIR/plugin.xml" "$H/driver/classes/META-INF/plugin.xml"
jar cf "$H/plugins-dist/harness-driver/lib/driver.jar" -C "$H/driver/classes" . || exit 1
unzip -q "$PLUGIN_ZIP" -d "$H/plugins-dist" || exit 1
CFG="$H/cfg/$TEST"
EV="$H/evidence/$TEST"
DEADLINE="${DEADLINE_SECONDS:-780}"

rm -rf "$CFG" "$EV"
mkdir -p "$CFG/config" "$CFG/system" "$CFG/log" "$CFG/plugins" "$EV" "$H/props"

XVFB_NUM=$((300 + RANDOM % 500))
Xvfb ":$XVFB_NUM" -screen 0 1600x1000x24 -nolisten tcp > "$EV/xvfb.log" 2>&1 &
XVFB_PID=$!
sleep 2
cleanup() {
  if [ -n "${CLICKER_PID:-}" ]; then kill "$CLICKER_PID" 2>/dev/null; fi
  if [ -n "${WM_PID:-}" ]; then kill "$WM_PID" 2>/dev/null; fi
  if [ -n "${XVFB_PID:-}" ] && kill -0 "$XVFB_PID" 2>/dev/null; then
    kill -TERM "$XVFB_PID" 2>/dev/null
  fi
  if [ -n "${IDE_PID:-}" ] && kill -0 "$IDE_PID" 2>/dev/null; then
    pkill -TERM -P "$IDE_PID" 2>/dev/null
    kill -TERM "$IDE_PID" 2>/dev/null
    sleep 3
    kill -9 "$IDE_PID" 2>/dev/null
    pkill -9 -P "$IDE_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

export DISPLAY=":$XVFB_NUM"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/xdg-$XVFB_NUM}"
mkdir -p "$XDG_RUNTIME_DIR"
chmod 700 "$XDG_RUNTIME_DIR"
if [ -n "${HARNESS_WM_BIN:-}" ]; then
  "$HARNESS_WM_BIN" --sm-disable > "$EV/window-manager.log" 2>&1 &
  WM_PID=$!
fi

cat > "$H/props/idea-$TEST.properties" <<EOF
idea.config.path=$CFG/config
idea.system.path=$CFG/system
idea.log.path=$CFG/log
idea.plugins.path=$CFG/plugins
EOF

{
  echo "-Xmx${HARNESS_HEAP_MB:-2048}m"
  if [ -n "${HARNESS_CPU_COUNT:-}" ]; then
    echo "-XX:ActiveProcessorCount=$HARNESS_CPU_COUNT"
  fi
  echo "-Didea.trust.all.projects=true"
  echo "-Dide.show.tips.on.startup.default.value=false"
  echo "-Dzoo.harness.mode=${TEST^^}"
  echo "-Dzoo.harness.evidence=$EV"
} > "$H/props/vm-$TEST.vmoptions"

cp -r "$H/plugins-dist/ZooCode" "$CFG/plugins/ZooCode"
cp -r "$H/plugins-dist/harness-driver" "$CFG/plugins/harness-driver"

mkdir -p "$H/workspace/throwaway-project/src"
if [ ! -f "$H/workspace/throwaway-project/src/Main.java" ]; then
  printf 'public class Main { public static void main(String[] a) { System.out.println("throwaway"); } }\n' \
    > "$H/workspace/throwaway-project/src/Main.java"
fi

export IDEA_PROPERTIES="$H/props/idea-$TEST.properties"
export IDEA_VM_OPTIONS="$H/props/vm-$TEST.vmoptions"

click_startup_dialogs() {
  # The user-agreement dialog is borderless, ~600x460, centered on the
  # 1600x1000 Xvfb screen. It blocks frame creation before any in-IDE listener
  # runs, so dismiss it externally: select the confirm checkbox, then Continue.
  for _ in $(seq 1 30); do
    for wid in $(xdotool search --onlyvisible . 2>/dev/null); do
      geom=$(xdotool getwindowgeometry --shell "$wid" 2>/dev/null) || continue
      eval "$geom"
      if [ "${WIDTH:-0}" -ge 550 ] && [ "${WIDTH:-0}" -le 700 ] && [ "${HEIGHT:-0}" -ge 400 ] && [ "${HEIGHT:-0}" -le 520 ]; then
        echo "harness: clicking startup agreement dialog wid=$wid ${WIDTH}x${HEIGHT}+${X}+${Y}"
        xdotool mousemove $((X + 40)) $((Y + HEIGHT * 85 / 100)) click 1
        sleep 1
        xdotool mousemove $((X + WIDTH * 89 / 100)) $((Y + HEIGHT * 93 / 100)) click 1
        sleep 2
      fi
    done
    sleep 3
  done
}
click_startup_dialogs &
CLICKER_PID=$!

(
  cd "$IDE_DIR/bin"
  exec ./idea "$H/workspace/throwaway-project"
) > "$EV/launcher-stdout.log" 2>&1 &
IDE_PID=$!

STATUS=""
for i in $(seq 1 "$DEADLINE"); do
  if [ -f "$EV/status.txt" ]; then
    STATUS="$(cat "$EV/status.txt")"
    break
  fi
  if ! kill -0 "$IDE_PID" 2>/dev/null; then
    STATUS="CRASHED"
    break
  fi
  sleep 1
done
[ -z "$STATUS" ] && STATUS="TIMEOUT"

cleanup
trap - EXIT

echo "TEST-$TEST result: $STATUS (after ${i}s)"
cp "$CFG/log/idea.log" "$EV/idea.log" 2>/dev/null || true
if [ -d "$CFG/system/log" ]; then
  cp -r "$CFG/system/log" "$EV/system-log" 2>/dev/null || true
fi
[ "$STATUS" = PASS ]
