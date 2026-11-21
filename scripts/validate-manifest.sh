#!/usr/bin/env bash
# Usage: scripts/validate-manifest.sh [manifest.json] [mods-dir]
set -euo pipefail
cd "$(dirname "$0")/.."
MANIFEST="${1:-modpack/modpack.json}"
CLI=manifest-cli/build/libs/zerith-modsync-cli.jar
[ -f "$CLI" ] || ./gradlew :manifest-cli:cliJar --console=plain -q
ARGS=(-jar "$CLI" validate "$MANIFEST")
[ -n "${2:-}" ] && ARGS+=(--mods-dir "$2")
exec java "${ARGS[@]}"
