#!/bin/bash
# Restarts the local test server and reconnects the demo client (paper has no /reload any more).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
pkill -f "net.minecraft.client.main.Main.*HoardiCam" || true
docker restart paper-test >/dev/null
until docker exec paper-test rcon-cli list >/dev/null 2>&1; do sleep 2; done
nohup "$DIR/launch-client.sh" > "$HOME/.cache/hoardi-demo/client.log" 2>&1 &
until docker exec paper-test rcon-cli list 2>/dev/null | grep -q HoardiCam; do sleep 2; done
sleep 6   # let the join toasts fade
echo "server + client ready"
