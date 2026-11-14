#!/bin/bash
# Issue #6: run two IDEs against one shared Zoo Code history store (Linux, Xvfb).
# Each IDE runs a parent task that delegates to a subtask. check_history_store.py
# then checks the shared store. Both IDEs use a temporary user.home, so the test
# never touches the real ~/.zoo-code.
#
# Usage: IDE_DIR=/path/to/idea PLUGIN_ZIP=/path/to/ZooCode.zip bash run-history-race.sh
# Optional: HARNESS_WM_BIN (openbox), HARNESS_HEAP_MB, DEADLINE_SECONDS, EVIDENCE_DIR,
#   EXPECT_EXTENSION_VERSION (fail when the Zoo Code extension in the ZIP differs;
#   the ZIP name does not set the extension version)
set -uo pipefail
SOURCE_DIR="$(cd "$(dirname "$0")" && pwd)"
: "${IDE_DIR:?Set IDE_DIR to a JCEF-enabled IntelliJ installation}"
: "${PLUGIN_ZIP:?Set PLUGIN_ZIP to the built ZooCode distribution}"
IDE_DIR="$(realpath "$IDE_DIR")"
PLUGIN_ZIP="$(realpath "$PLUGIN_ZIP")"
PRODUCT_CODE="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["productCode"])' "$IDE_DIR/product-info.json")"
case "$PRODUCT_CODE" in
  WS) IDE_LAUNCHER=webstorm ;;
  IU|IC) IDE_LAUNCHER=idea ;;
  *) echo "Unsupported harness IDE product: $PRODUCT_CODE" >&2; exit 1 ;;
esac
DEADLINE="${DEADLINE_SECONDS:-900}"
H="$(mktemp -d "${TMPDIR:-/tmp}/zoo-history-race.XXXXXXXX")"
EVIDENCE="${EVIDENCE_DIR:-$H/evidence}"
HOME_DIR="$H/home"
STORAGE="$HOME_DIR/.zoo-code/globalStorage"
echo "Disposable harness and evidence: $H"
mkdir -p "$H/driver/classes/META-INF" "$H/plugins-dist/harness-driver/lib" "$EVIDENCE" "$HOME_DIR"

"$IDE_DIR/jbr/bin/javac" -cp "$IDE_DIR/lib/*" -d "$H/driver/classes" "$SOURCE_DIR/HarnessDriver.java" || exit 1
cp "$SOURCE_DIR/plugin.xml" "$H/driver/classes/META-INF/plugin.xml"
# The IDE's JBR has javac but may omit the jar tool. A plugin jar is a zip.
python3 - "$H/driver/classes" "$H/plugins-dist/harness-driver/lib/driver.jar" <<'PY' || exit 1
import pathlib, sys, zipfile
root = pathlib.Path(sys.argv[1])
with zipfile.ZipFile(sys.argv[2], 'w', zipfile.ZIP_DEFLATED) as jar:
    for path in sorted(root.rglob('*')):
        if path.is_file():
            jar.write(path, path.relative_to(root).as_posix())
PY
unzip -q "$PLUGIN_ZIP" -d "$H/plugins-dist" || exit 1
[ -f "$H/plugins-dist/ZooCode/runtime/extension.js" ] || { echo 'Distribution has no release extension runtime' >&2; exit 1; }
EXTENSION_VERSION="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["version"])' "$H/plugins-dist/ZooCode/zoo-code/package.json")" || exit 1
echo "$EXTENSION_VERSION" > "$EVIDENCE/extension-version.txt"
echo "Bundled Zoo Code extension version: $EXTENSION_VERSION"
if [ -n "${EXPECT_EXTENSION_VERSION:-}" ] && [ "$EXTENSION_VERSION" != "$EXPECT_EXTENSION_VERSION" ]; then
  echo "Plugin ZIP bundles Zoo Code extension $EXTENSION_VERSION, expected $EXPECT_EXTENSION_VERSION." >&2
  rm -rf "$H/plugins-dist" "$H/driver"
  exit 1
fi

PIDS=()
cleanup() {
  for pid in "${PIDS[@]}"; do
    pkill -TERM -P "$pid" 2>/dev/null
    kill -TERM "$pid" 2>/dev/null
  done
  sleep 3
  for pid in "${PIDS[@]}"; do
    pkill -9 -P "$pid" 2>/dev/null
    kill -9 "$pid" 2>/dev/null
  done
  # Remove the large copies (plugins, IDE profiles, temporary home). The evidence stays,
  # and it holds a copy of the history items.
  rm -rf "$H/cfg" "$H/plugins-dist" "$H/driver" "$HOME_DIR"
}
trap cleanup EXIT

# One virtual display for both IDEs.
XVFB_NUM=$((300 + RANDOM % 500))
Xvfb ":$XVFB_NUM" -screen 0 1600x1000x24 -nolisten tcp > "$EVIDENCE/xvfb.log" 2>&1 &
PIDS+=($!)
sleep 2
export DISPLAY=":$XVFB_NUM"
export XDG_RUNTIME_DIR="${XDG_RUNTIME_DIR:-/tmp/xdg-$XVFB_NUM}"
mkdir -p "$XDG_RUNTIME_DIR"; chmod 700 "$XDG_RUNTIME_DIR"
if [ -n "${HARNESS_WM_BIN:-}" ]; then
  "$HARNESS_WM_BIN" --sm-disable > "$EVIDENCE/window-manager.log" 2>&1 &
  PIDS+=($!)
fi

# Local provider with the history-race scenario.
python3 "$SOURCE_DIR/streaming_fixture.py" --scenario history-race --chunks 200 \
  --log "$EVIDENCE/provider.jsonl" > "$EVIDENCE/provider-stdout.log" 2> "$EVIDENCE/provider-stderr.log" &
PIDS+=($!)
for _ in $(seq 1 40); do
  curl -fsS http://127.0.0.1:19487/health > /dev/null 2>&1 && break
  sleep 0.25
done
curl -fsS http://127.0.0.1:19487/health > /dev/null 2>&1 || { echo 'Local provider did not become healthy (is port 19487 busy?)' >&2; exit 1; }

click_startup_dialogs() {
  local ev="$1"
  for _ in $(seq 1 30); do
    [ -f "$ev/progress.log" ] && return
    for wid in $(xdotool search --onlyvisible --name '(IntelliJ IDEA|WebStorm) User Agreement' 2>/dev/null); do
      geom=$(xdotool getwindowgeometry --shell "$wid" 2>/dev/null) || continue
      eval "$geom"
      [ -f "$ev/progress.log" ] && return
      if [ "${WIDTH:-0}" -ge 550 ] && [ "${WIDTH:-0}" -le 700 ] && [ "${HEIGHT:-0}" -ge 400 ] && [ "${HEIGHT:-0}" -le 520 ]; then
        xdotool mousemove $((X + 40)) $((Y + HEIGHT * 85 / 100)) click 1
        sleep 1
        xdotool mousemove $((X + WIDTH * 89 / 100)) $((Y + HEIGHT * 93 / 100)) click 1
        sleep 2
      fi
    done
    sleep 3
  done
}

start_ide() {
  local tag="$1"
  local cfg="$H/cfg/$tag" ev="$EVIDENCE/$tag" ws="$H/workspace-$tag"
  mkdir -p "$cfg/config" "$cfg/system" "$cfg/log" "$ev" "$ws/src" "$H/props"
  mkdir -p "$cfg/plugins"
  cp -r "$H/plugins-dist/ZooCode" "$cfg/plugins/ZooCode"
  cp -r "$H/plugins-dist/harness-driver" "$cfg/plugins/harness-driver"
  printf 'public class Main { public static void main(String[] a) { System.out.println("throwaway"); } }\n' > "$ws/src/Main.java"
  cat > "$H/props/idea-$tag.properties" <<EOF
idea.config.path=$cfg/config
idea.system.path=$cfg/system
idea.log.path=$cfg/log
idea.plugins.path=$cfg/plugins
EOF
  {
    echo "-Xmx${HARNESS_HEAP_MB:-1024}m"
    echo "-XX:ActiveProcessorCount=${HARNESS_CPU_COUNT:-2}"
    echo "-Duser.home=$HOME_DIR"
    echo "-Didea.trust.all.projects=true"
    echo "-Didea.initially.ask.config=false"
    echo "-Djb.consents.confirmation.enabled=false"
    echo '-Djb.privacy.policy.text=<!--999.999-->'
    echo "-Dide.show.tips.on.startup.default.value=false"
    echo "-Dide.browser.jcef.extra.args=--disable-dev-shm-usage"
    echo "-Dzoo.harness.mode=P"
    echo "-Dzoo.harness.fixture=true"
    echo "-Dzoo.harness.historyRace=true"
    echo "-Dzoo.harness.historyTag=$tag"
    echo "-Dzoo.harness.evidence=$ev"
  } > "$H/props/vm-$tag.vmoptions"
  click_startup_dialogs "$ev" &
  PIDS+=($!)
  (
    export HOME="$HOME_DIR"
    export IDEA_PROPERTIES="$H/props/idea-$tag.properties" IDEA_VM_OPTIONS="$H/props/vm-$tag.vmoptions"
    export WEBIDE_PROPERTIES="$IDEA_PROPERTIES" WEBIDE_VM_OPTIONS="$IDEA_VM_OPTIONS"
    cd "$IDE_DIR/bin"
    exec "./$IDE_LAUNCHER" "$ws"
  ) > "$ev/launcher-stdout.log" 2>&1 &
  IDE_PIDS[$tag]=$!
  PIDS+=($!)
}

# Wait for a file test or a dead IDE. $1 tag, $2 description, $3 seconds, rest: test command.
wait_for() {
  local tag="$1" what="$2" seconds="$3"; shift 3
  for _ in $(seq 1 "$seconds"); do
    "$@" && return 0
    if ! kill -0 "${IDE_PIDS[$tag]}" 2>/dev/null; then echo "IDE $tag exited before $what" >&2; return 1; fi
    sleep 1
  done
  echo "IDE $tag did not reach $what within ${seconds}s" >&2
  return 1
}
has_status() { [ -f "$EVIDENCE/$1/status.txt" ]; }

declare -A IDE_PIDS
SINCE=$(( $(date +%s) - 2 ))
RESULT=FAIL

# The driver starts each task with a webview message and uses no keyboard, so both IDEs
# can start together. A waits at the provider barrier until B submits, so both delegations
# start together.
start_ide A
start_ide B
ok=1
for tag in A B; do
  wait_for "$tag" 'a result' "$DEADLINE" has_status "$tag" || { ok=0; break; }
  status="$(cat "$EVIDENCE/$tag/status.txt")"
  [ "$status" = PASS ] || { echo "IDE $tag result: $status (see $EVIDENCE/$tag/evidence.json)" >&2; ok=0; break; }
done
if [ "$ok" = 1 ]; then
  mkdir -p "$EVIDENCE/history-store"
  find "$STORAGE" -name history_item.json -newermt "@$SINCE" -print0 2>/dev/null | while IFS= read -r -d '' f; do
    cp "$f" "$EVIDENCE/history-store/$(basename "$(dirname "$f")").json"
  done
  if python3 "$SOURCE_DIR/check_history_store.py" --storage "$STORAGE" --since "$SINCE" \
      --provider-log "$EVIDENCE/provider.jsonl" --report "$EVIDENCE/history-check.json" \
      --ide-log "$H/cfg/A/log/idea.log" --ide-log "$H/cfg/B/log/idea.log"; then
    RESULT=PASS
  fi
fi

for tag in A B; do cp "$H/cfg/$tag/log/idea.log" "$EVIDENCE/idea-$tag.log" 2>/dev/null || true; done
echo "HISTORY-RACE result: $RESULT"
echo "Evidence: $EVIDENCE"
[ "$RESULT" = PASS ]
