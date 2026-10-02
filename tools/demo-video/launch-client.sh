#!/bin/bash
# Starts a vanilla 26.2 client (offline name HoardiCam) straight into the local test server.
set -euo pipefail
BASE="${HOARDI_DEMO_DIR:-$HOME/.cache/hoardi-demo}"
exec uvx portablemc --main-dir "$BASE/main" --work-dir "$BASE/work" --output human \
  start 26.2 -u HoardiCam -s localhost -p 25566 --resolution "${RES:-960x540}"
