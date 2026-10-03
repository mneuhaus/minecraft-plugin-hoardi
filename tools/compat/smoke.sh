#!/bin/bash
# Smoke-tests the current Hoardi jar on one Paper version in a throwaway container.
#   tools/compat/smoke.sh 1.21.10        (prints PASS/FAIL lines, exit 1 on any failure)
# Uses the demo director plugin as a test driver: it builds a 66-chest Birch network through
# Hoardi's API, fills and sorts it, and builds the setup room (double chests, barrels with
# shelves on top, three shelf woods) and checks network owners. Needs target/Hoardi-*.jar and the
# director jar built.
set -uo pipefail
VERSION="$1"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
HOARDI_JAR=$(ls "$ROOT"/target/Hoardi-*.jar | head -1)
DIRECTOR_JAR="$ROOT/tools/demo-video/director/target/HoardiDemo-1.0.0.jar"
case "$VERSION" in 1.*) JAVA=java21 ;; *) JAVA=java25 ;; esac
NAME="hoardi-compat-${VERSION//./-}"
DATA="$HOME/.cache/hoardi-compat/$VERSION"
LOG="$HOME/.cache/hoardi-compat/$VERSION.log"

rm -rf "$DATA/world" "$DATA/world_nether" "$DATA/world_the_end" "$DATA/plugins"
mkdir -p "$DATA/plugins"
cp "$HOARDI_JAR" "$DIRECTOR_JAR" "$DATA/plugins/"
docker rm -f "$NAME" >/dev/null 2>&1
docker run -d --name "$NAME" -v "$DATA":/data \
  -e EULA=TRUE -e TYPE=PAPER -e VERSION="$VERSION" -e MEMORY=2G -e LEVEL_TYPE=FLAT \
  -e ONLINE_MODE=false -e DIFFICULTY=peaceful -e SPAWN_PROTECTION=0 \
  "itzg/minecraft-server:$JAVA" >/dev/null

fails=0
pass() { echo "PASS $VERSION  $1"; }
fail() { echo "FAIL $VERSION  $1"; fails=$((fails + 1)); }
rcon() { docker exec "$NAME" rcon-cli "$@" 2>/dev/null | sed 's/\x1b\[[0-9;]*m//g; s/§.//g'; }

# grep without -q: an early exit would SIGPIPE docker logs, and pipefail turns that into a miss
for _ in $(seq 1 180); do
  docker logs "$NAME" 2>&1 | grep 'Done (' >/dev/null && break
  docker ps -q -f name="$NAME" | grep -q . || break
  sleep 2
done
if ! docker logs "$NAME" 2>&1 | grep 'Done (' >/dev/null; then
  docker logs "$NAME" > "$LOG" 2>&1
  fail "server did not start (log: $LOG)"
  docker rm -f "$NAME" >/dev/null
  exit 1
fi
sleep 2

plugins=$(rcon plugins)
echo "$plugins" | grep -q "Hoardi" && pass "Hoardi loaded" || fail "Hoardi not loaded: $plugins"
echo "$plugins" | grep -q "HoardiDemo" || fail "test driver not loaded: $plugins"

rcon "demo build" | grep -q "set built" && pass "66-chest network built through the Hoardi API" || fail "demo build"
stats=$(rcon "demo stats")
used=$(echo "$stats" | grep -oE '[0-9]+' | awk '{s+=$1} END{print s+0}')
[ "$used" -gt 500 ] && pass "hoard sorted into the network ($used slots in use)" || fail "hoard not distributed: $stats"
rcon "hoardi networks" | grep -q "Birch network: 66 chest" && pass "console /hoardi networks lists the network" || fail "hoardi networks: $(rcon 'hoardi networks')"
rcon "hoardi sort" | grep -q "Triggered full sort" && pass "console /hoardi sort" || fail "hoardi sort"
rcon "demo setupbuild" | grep -q "setup set built" && pass "setup room: barrels, Oak and Spruce networks" || fail "demo setupbuild"

sleep 4   # shelf previews refresh once per second
shelf_hall=$(rcon "data get block 1000 -60 997 Items")
echo "$shelf_hall" | grep -q "minecraft:" && pass "Birch shelf shows a preview" || fail "Birch shelf has no preview: $shelf_hall"
shelf_barrel=$(rcon "data get block 1005 -59 1099 Items")
echo "$shelf_barrel" | grep -q "minecraft:" && pass "Spruce shelf on a barrel shows a preview" || fail "barrel shelf has no preview: $shelf_barrel"
rcon "hoardi networks" | grep -qi "spruce" && pass "Spruce shelves form their own network" || fail "no separate Spruce network"

owners=$(rcon "demo checkowners")
n_pass=$(echo "$owners" | grep -c '^PASS'); n_fail=$(echo "$owners" | grep -c '^FAIL')
[ "$n_pass" -ge 7 ] && [ "$n_fail" -eq 0 ] \
  && pass "network owners: $n_pass checks (owner, stranger, trust, one network per chest, save+load, shared, owners off)" \
  || fail "network owners: $(echo "$owners" | grep FAIL)"

shelves=$(rcon "demo shelves")
case "$VERSION" in
  1.*|26.1*|26.2*) echo "$shelves" | grep -q "12 shelf types" && pass "all 12 shelf woods recognized" || fail "shelf types: $shelves" ;;
  *) echo "$shelves" | grep -q "POPLAR_SHELF" && pass "Poplar shelf recognized ($(echo "$shelves" | grep -oE '^[0-9]+') types)" || fail "Poplar shelf missing: $shelves" ;;
esac

docker logs "$NAME" > "$LOG" 2>&1
errors=$(grep -iE 'exception|error|severe' "$LOG" | grep -iE 'hoard|de\.hoarder' | grep -v 'Unknown material' | head -5)
[ -z "$errors" ] && pass "no Hoardi errors in the server log" || fail "errors in log: $errors"

docker rm -f "$NAME" >/dev/null
[ "$fails" -eq 0 ]
