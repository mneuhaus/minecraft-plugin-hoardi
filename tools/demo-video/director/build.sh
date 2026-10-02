#!/bin/bash
# Builds the demo director against the current Hoardi jar and drops it into the local test server.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
VERSION=$(grep -m1 '<version>' "$ROOT/pom.xml" | sed 's/.*<version>\(.*\)<\/version>.*/\1/')
docker run --rm -v "$ROOT":/app -v ltw-m2:/root/.m2 -w /app/tools/demo-video/director \
  maven:3.9-eclipse-temurin-25 mvn -q package "-Dhoardi.jar=/app/target/Hoardi-$VERSION.jar"
cp "$ROOT/tools/demo-video/director/target/HoardiDemo-1.0.0.jar" "$ROOT/test/data/plugins/"
