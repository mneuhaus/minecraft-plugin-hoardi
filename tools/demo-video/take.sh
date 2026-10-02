#!/bin/bash
# Records one take: resets the set, runs the given shots (default: all) and stops when the director logs "done".
# Output: ~/.cache/hoardi-demo/takes/<name>.mov + <name>.marks (recording start + shot marks, epoch millis)
set -euo pipefail
NAME="${1:-take-$(date +%H%M%S)}"; shift || true
SHOTS="${*:-all}"
OUT="$HOME/.cache/hoardi-demo/takes"; mkdir -p "$OUT"
MARKS="$(cd "$(dirname "$0")/../.." && pwd)/test/data/plugins/HoardiDemo/marks.log"
STOP="$OUT/$NAME.stop"; rm -f "$STOP"
docker exec paper-test rcon-cli "demo reset" >/dev/null
sleep 2
: > "$MARKS"
"$HOME/.cache/hoardi-demo/wincap" record "$OUT/$NAME.mov" until "$STOP" 1920 1080 60 &
REC=$!
sleep 1
echo "$(uv run --no-project python -c 'import time;print(int(time.time()*1000))') record" > "$OUT/$NAME.marks"
if [ "$SHOTS" = "all" ]; then docker exec paper-test rcon-cli "demo all" >/dev/null; else docker exec paper-test rcon-cli "demo shot $SHOTS" >/dev/null; fi
until grep -q " done" "$MARKS" 2>/dev/null; do sleep 0.5; done
sleep 0.5; touch "$STOP"; wait $REC
cat "$MARKS" >> "$OUT/$NAME.marks"; rm -f "$STOP"
echo "$OUT/$NAME.mov"
