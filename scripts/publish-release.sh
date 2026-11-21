#!/usr/bin/env bash
# Publish a modpack version: build ModSync, validate JARs, generate manifest, create a NEW GitHub Release
# (draft -> verified -> published), then commit/push modpack/modpack.json LAST.
# Usage: scripts/publish-release.sh --owner USER --repo zerith-modsync --version 1.0.1 --mods-dir /path/to/mods \
#          [--overrides-dir DIR] [--branch main] [--pack-name NAME] [--skip-build] [--dry-run]
# --overrides-dir: folder with config/, options.txt, resourcepacks/, shaderpacks/ (shipped as one zip asset)
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT=$(pwd)
fail() { echo; echo "PUBLISH FAILED: $*" >&2; echo "The previously published manifest was not changed." >&2; exit 1; }
step() { echo "==> $*"; }
sha() { if command -v sha256sum >/dev/null; then sha256sum "$1" | cut -d' ' -f1; else shasum -a 256 "$1" | cut -d' ' -f1; fi; }

OWNER=""; REPO=""; VERSION=""; MODS=""; BRANCH="main"; PACK="Zerith Modpack"; SKIP_BUILD=0; DRY=0; OVR=""; PACKZIP=""
while [ $# -gt 0 ]; do
  case "$1" in
    --owner) OWNER="$2"; shift 2;; --repo) REPO="$2"; shift 2;; --version) VERSION="$2"; shift 2;;
    --mods-dir) MODS="$2"; shift 2;; --branch) BRANCH="$2"; shift 2;; --pack-name) PACK="$2"; shift 2;; --overrides-dir) OVR="$2"; shift 2;;
    --pack-zip) PACKZIP="$2"; shift 2;;
    --skip-build) SKIP_BUILD=1; shift;; --dry-run) DRY=1; shift;; *) fail "Unknown argument: $1";;
  esac
done
[ -n "$OWNER" ] && [ -n "$REPO" ] && [ -n "$VERSION" ] || fail "Required: --owner --repo --version and (--mods-dir or --pack-zip)"
if [ -n "$PACKZIP" ]; then
  [ -z "$MODS" ] && [ -z "$OVR" ] || fail "Use either --pack-zip or --mods-dir/--overrides-dir, not both"
  [ -f "$PACKZIP" ] || fail "Pack zip not found: $PACKZIP"
  echo "==> Unpacking pack zip"
  PK="$ROOT/dist/pack-v$VERSION"; rm -rf "$PK"; mkdir -p "$PK"
  if command -v unzip >/dev/null; then rc=0; unzip -q "$PACKZIP" -d "$PK" || rc=$?; [ "$rc" -le 1 ] || fail "Could not unzip (exit $rc)"
  else (cd "$PK" && jar xf "$(cd "$(dirname "$PACKZIP")" && pwd)/$(basename "$PACKZIP")") || fail "Could not extract (need unzip or jar)"; fi
  if find "$PK" -type l | grep -q .; then fail "Pack zip contains symbolic links"; fi
  BASE="$PK"
  if [ ! -d "$BASE/mods" ]; then
    n=$(find "$PK" -mindepth 1 -maxdepth 1 | wc -l)
    [ "$n" = 1 ] && [ -d "$(find "$PK" -mindepth 1 -maxdepth 1)/mods" ] && BASE=$(find "$PK" -mindepth 1 -maxdepth 1)
  fi
  [ -d "$BASE/mods" ] || fail "Pack zip has no 'mods' folder"
  MODS="$BASE/mods"
  OVS="$ROOT/dist/pack-v$VERSION-overrides"; rm -rf "$OVS"; mkdir -p "$OVS"
  for it in "$BASE"/* "$BASE"/.[!.]*; do
    [ -e "$it" ] || continue
    b=$(basename "$it")
    case "$b" in
      mods) ;;
      config|configs) [ -d "$it" ] || fail "'$b' must be a folder"; cp -r "$it" "$OVS/config";;
      resourcepacks|shaderpacks) [ -d "$it" ] || fail "'$b' must be a folder"; cp -r "$it" "$OVS/";;
      options.txt) [ -f "$it" ] || fail "options.txt must be a file"; cp "$it" "$OVS/";;
      *) fail "Unsupported entry in pack zip: '$b'. Allowed: mods, config, resourcepacks, shaderpacks, options.txt";;
    esac
  done
  [ -n "$(ls -A "$OVS")" ] && OVR="$OVS"
fi
[ -n "$MODS" ] || fail "Required: --mods-dir or --pack-zip"
case "$OWNER" in OWNER|YOUR_USERNAME) fail "Owner '$OWNER' is a placeholder";; esac
[[ "$OWNER" =~ ^[A-Za-z0-9][A-Za-z0-9-]{0,38}$ ]] || fail "Invalid owner"
[[ "$REPO" =~ ^[A-Za-z0-9._-]{1,100}$ ]] || fail "Invalid repository name"
[[ "$VERSION" =~ ^[0-9A-Za-z][0-9A-Za-z._-]{0,40}$ ]] || fail "Invalid version"
[ -d "$MODS" ] || fail "Mods directory not found: $MODS"
TAG="v$VERSION"

step "Checking tools and repository"
command -v git >/dev/null || fail "git is not installed"
if [ "$DRY" = 0 ]; then
  command -v gh >/dev/null || fail "GitHub CLI (gh) is not installed (https://cli.github.com/), then run: gh auth login"
  gh auth status >/dev/null 2>&1 || fail "gh is not authenticated. Run: gh auth login"
fi
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || fail "Not inside a git repository (see docs/PUBLISHING.md)"
CUR=$(git rev-parse --abbrev-ref HEAD)
[ "$CUR" = "$BRANCH" ] || fail "Current branch is '$CUR', expected '$BRANCH'"
if [ -n "$(git status --porcelain)" ]; then
  [ "$DRY" = 1 ] && echo "  (dry run: working tree is dirty, ignored)" || fail "Working tree has uncommitted changes."
fi
if [ "$DRY" = 0 ]; then
  REMOTE=$(git remote get-url origin 2>/dev/null) || fail "No 'origin' remote"
  echo "$REMOTE" | grep -Eq "github\.com[:/]$OWNER/$REPO(\.git)?$" || fail "Remote origin ($REMOTE) is not github.com/$OWNER/$REPO"
  git fetch origin "$BRANCH"
  [ "$(git rev-list --count "HEAD..origin/$BRANCH")" = 0 ] || fail "Local branch is behind origin/$BRANCH. Pull first."
  if gh release view "$TAG" --repo "$OWNER/$REPO" >/dev/null 2>&1; then fail "Release $TAG already exists. Choose a new version."; fi
fi

DIST="$ROOT/dist"
if [ "$SKIP_BUILD" = 0 ]; then
  step "Building Zerith ModSync (owner=$OWNER)"
  "$ROOT/scripts/build.sh" --owner "$OWNER" --repo "$REPO" --branch "$BRANCH" || fail "Build failed"
else
  ./gradlew :manifest-cli:cliJar --console=plain -q || fail "Could not build manifest CLI"
fi
GP=$(grep -E '^mod_version=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
SELF="$DIST/zerith-modsync-$GP.jar"
[ -f "$SELF" ] || fail "Built JAR missing: $SELF"
CLI="$ROOT/manifest-cli/build/libs/zerith-modsync-cli.jar"

step "Assembling release folder"
WORK="$DIST/release-$TAG"; rm -rf "$WORK"; mkdir -p "$WORK"
shopt -s nullglob dotglob
for f in "$MODS"/*; do
  b=$(basename "$f")
  [ -f "$f" ] && [[ "$b" == *.jar ]] || fail "Mods directory must contain only .jar files; found: $b"
  if [[ "$b" == zerith-modsync* ]]; then echo "  skipping '$b': the freshly built ModSync JAR is used instead"; continue; fi
  cp "$f" "$WORK/"
done
cp "$SELF" "$WORK/"
MANIFEST="$DIST/modpack-$TAG.json"
step "Generating manifest (validates every JAR, computes SHA-256)"
GEN=(generate --mods-dir "$WORK" --owner "$OWNER" --repo "$REPO" --tag "$TAG" --pack-version "$VERSION" --pack-name "$PACK" --output "$MANIFEST")
VAL=(validate "$MANIFEST" --mods-dir "$WORK")
OVZIP=""
if [ -n "$OVR" ]; then
  [ -d "$OVR" ] || fail "Overrides directory not found: $OVR"
  EXTRA="$DIST/release-$TAG-extra"; rm -rf "$EXTRA"; mkdir -p "$EXTRA"
  OVZIP="$EXTRA/overrides-$TAG.zip"
  GEN+=(--overrides-dir "$OVR" --overrides-zip "$OVZIP"); VAL+=(--overrides-zip "$OVZIP")
fi
java -jar "$CLI" "${GEN[@]}" || fail "Manifest generation failed"
java -jar "$CLI" "${VAL[@]}" || fail "Manifest validation failed"
JARS=("$WORK"/*.jar)
[ -n "$OVZIP" ] && JARS+=("$OVZIP")
echo "  ${#JARS[@]} JARs, manifest: $MANIFEST"

if [ "$DRY" = 1 ]; then
  echo; echo "DRY RUN OK - nothing was sent to GitHub."; echo "Manifest: $MANIFEST"; echo "Assets:   $WORK"; exit 0
fi

step "Creating DRAFT release $TAG and uploading assets"
gh release create "$TAG" "${JARS[@]}" --repo "$OWNER/$REPO" --draft --target "$BRANCH" \
   --title "Modpack $VERSION" --notes "Zerith modpack $VERSION. Assets are referenced by modpack/modpack.json." || fail "Release creation failed"

step "Verifying uploaded assets (names, SHA-256)"
VERIFY="$DIST/verify-$TAG"; rm -rf "$VERIFY"; mkdir -p "$VERIFY"
gh release download "$TAG" --repo "$OWNER/$REPO" --dir "$VERIFY" || fail "Could not download draft assets"
for j in "${JARS[@]}"; do
  n=$(basename "$j")
  [ -f "$VERIFY/$n" ] || fail "Asset missing on GitHub: $n. Draft release $TAG left in place."
  [ "$(sha "$VERIFY/$n")" = "$(sha "$j")" ] || fail "Asset hash mismatch on GitHub: $n. Draft release $TAG left in place."
done
step "Publishing release $TAG"
gh release edit "$TAG" --repo "$OWNER/$REPO" --draft=false || fail "Could not publish release"

step "Committing manifest (last step)"
mkdir -p modpack; cp -f "$MANIFEST" modpack/modpack.json
git add modpack/modpack.json
if git diff --cached --quiet; then echo "  manifest unchanged, nothing to commit"; else
  git commit -m "Publish modpack $VERSION"; git push origin "$BRANCH"
fi

step "Verifying public manifest URL"
URL="https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/modpack/modpack.json"
WANT=$(sha "$MANIFEST"); OK=0
for _ in $(seq 1 30); do
  curl -fsSL "$URL?cb=$RANDOM$RANDOM" -o "$VERIFY/public.json" 2>/dev/null && [ "$(sha "$VERIFY/public.json")" = "$WANT" ] && { OK=1; break; }
  sleep 10
done
[ "$OK" = 1 ] || { echo "WARNING: public manifest did not match after 5 min (CDN cache?). Check $URL manually." >&2; exit 2; }
echo; echo "PUBLISH OK: $TAG (${#JARS[@]} assets)"; echo "Manifest URL: $URL"
