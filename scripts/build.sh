#!/usr/bin/env bash
# Test + build the production (remapped) Zerith ModSync JAR and copy it to dist/.
# Usage: scripts/build.sh [--owner USER] [--repo REPO] [--branch BRANCH] [--skip-tests]
set -euo pipefail
cd "$(dirname "$0")/.."
fail() { echo "ERROR: $*" >&2; exit 1; }

OWNER=""; REPO=""; BRANCH=""; SKIP_TESTS=0
while [ $# -gt 0 ]; do
  case "$1" in
    --owner) OWNER="$2"; shift 2;; --repo) REPO="$2"; shift 2;; --branch) BRANCH="$2"; shift 2;;
    --skip-tests) SKIP_TESTS=1; shift;; *) fail "Unknown argument: $1";;
  esac
done

command -v java >/dev/null || fail "java not found. Install JDK 21 and set JAVA_HOME."
JV=$(java -version 2>&1 | head -1)
MAJOR=$(echo "$JV" | sed -E 's/.*"([0-9]+).*/\1/')
[ "${MAJOR:-0}" -ge 21 ] || fail "JDK 21+ required, found: $JV"
echo "JDK: $JV"

PROPS=()
[ -n "$OWNER" ]  && PROPS+=("-PghOwner=$OWNER")
[ -n "$REPO" ]   && PROPS+=("-PghRepo=$REPO")
[ -n "$BRANCH" ] && PROPS+=("-PghBranch=$BRANCH")

TASKS=(clean build)
[ "$SKIP_TESTS" = 1 ] && TASKS+=(-x test)
TASKS+=(:manifest-cli:cliJar)
./gradlew "${TASKS[@]}" ${PROPS[@]+"${PROPS[@]}"} --console=plain || fail "Gradle build failed"

VERSION=$(grep -E '^mod_version=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
JAR="build/libs/zerith-modsync-$VERSION.jar"
[ -f "$JAR" ] || fail "Production JAR not found: $JAR"

# --- verify metadata inside the JAR ---
LIST=$(unzip -Z1 "$JAR" 2>/dev/null || jar tf "$JAR")
for need in fabric.mod.json worker/worker.jar zerith-modsync-build.properties com/zerith/modsync/ZerithModSyncClient.class; do
  echo "$LIST" | grep -qx "$need" || fail "JAR is missing $need"
done
META=$(unzip -p "$JAR" fabric.mod.json)
echo "$META" | grep -q '"id": *"zerith-modsync"' || fail "Unexpected mod id in fabric.mod.json"
echo "$META" | grep -q "\"version\": *\"$VERSION\"" || fail "fabric.mod.json version is not $VERSION (resources not expanded)"
unzip -p "$JAR" com/zerith/modsync/ZerithModSyncClient.class | grep -aq 'net/minecraft/class_[0-9]' \
  || fail "Client class is not remapped to intermediary (dev JAR?)"

mkdir -p dist
cp -f "$JAR" dist/
OUT="dist/$(basename "$JAR")"
if command -v sha256sum >/dev/null; then SHA=$(sha256sum "$OUT" | cut -d' ' -f1); else SHA=$(shasum -a 256 "$OUT" | cut -d' ' -f1); fi
echo
echo "BUILD OK"
echo "JAR:    $(pwd)/$OUT"
echo "SHA256: $SHA"
