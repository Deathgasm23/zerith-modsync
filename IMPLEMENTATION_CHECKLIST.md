# Zerith ModSync — Implementation Checklist
Audit commit: none (directory is not a git repository; nothing was initialized or pushed) | Audit date: 2026-11-21

## Audit summary
- Baseline: ~360 dense lines (package `dev.zerith.modsync`, 7 trivial tests, Fabric API hard dependency, no startup recovery, 304 skipped the local scan). `./gradlew build` was green. Decision: rewrite under `com.zerith.modsync`, keep the Loom/worker architecture.
- Versions checked against maven.fabricmc.net on 2026-11-21: Loom 1.14.10 (`fabric-loom-remap`), Fabric Loader 0.20.1, Yarn 1.21.1+build.6, Gradle 9.2.1 wrapper. Fabric API removed (unused).
- Architecture: `src/core/java` (shared) → mod (`src/main`), `worker/` fat JAR embedded as `worker/worker.jar`, `manifest-cli/` fat JAR.
- Environment: Windows 11, JDK 21 + 25, `git` present, `gh` NOT installed. No remote/push/release was made.

## Mandatory functionality
- [x] Manifest schema/validator (schema, MC/loader, dup ids/files, hashes, HTTPS+host allowlist, sizes, updater present, empty rejected) — `core/ManifestValidator.java` — `ManifestAndScannerTest` (9 tests)
- [x] Change detection: missing/extra/renamed/modified/corrupt/same-version-changed-bytes; strict & non-strict — `core/LocalScanner.java` — `ManifestAndScannerTest`, `SyncEngineTest.notModifiedStillDetectsLocalChanges`
- [x] Async-safe fetch: timeouts, retry/backoff, ETag/304 (still scans locally), rate-limit (no retry), manual redirect allowlist — `Downloader`, `ManifestFetcher` — `SyncEngineTest` (local HTTP server)
- [x] Staged parallel download of ALL official JARs, SHA-256 + size + ZIP/fabric.mod.json id check, disk-space check, cancellation, no re-download when same update already staged — `SyncEngine` — `SyncEngineTest`
- [x] Whole-folder swap with journal, backup, rollback, crash recovery at every phase, Windows lock retry, never destroys last copy — `Installer` — `InstallerTest` (19 tests incl. lock test on Windows)
- [x] External worker: lock, waits for game PID, concurrency, `--recover`, failure status, self-update via manifest — `worker/WorkerMain` — `WorkerAndCliTest`; real fat JAR extracted from production JAR run on Windows with spaces in path and a live parent process (manual e2e, scratchpad script)
- [x] Config (`config/zerith-modsync.json`), placeholder-URL guard, bounded values, `verifyHashes=false` refused — `core/Config` — `ManifestAndScannerTest`
- [x] Symlink/junction refusal for mods dir; path-escape rejection for transactions — `FileOps.requireRealDir`, `Installer.checkedPaths` — `InstallerTest` (junction on Windows)
- [x] Manifest generator/validator CLI, deterministic, release URLs, rejects dup/non-Fabric/missing-or-duplicate updater/incompatible MC — `manifest-cli/Main` — `WorkerAndCliTest`; Gradle tasks `generateManifest`/`validateManifest`
- [x] Production remapped JAR with embedded worker, expanded fabric.mod.json, build-time manifest URL — `dist/zerith-modsync-1.0.0.jar` — `scripts/build.ps1` and `build.sh` both pass; identical SHA-256
- [x] Scripts: build/validate/publish (.ps1/.sh) — `scripts/` — build ×2 run OK; publish `-DryRun` run OK in a scratch git copy (ps1 + sh); placeholder owner and missing `gh` rejected
- [x] Overrides (config/, options.txt, resourcepacks/, shaderpacks/) — per-file apply when author changes it, zip asset, path allowlist, backup/rollback/recovery, overrides-only updates — `core/Overrides.java`, `Installer.applyOverrides` — `OverridesTest` (17 tests); user-approved scope extension (original spec said never touch these)
- [x] Docs: README, docs/PUBLISHING.md, docs/RECOVERY.md, RELEASE_NOTES.md
- [ ] Fabric client behaviour inside a real Minecraft 1.21.1 client (entrypoint, chat/action-bar messages, worker auto-launch, shutdown hook) — compiled and remapped but NOT run in-game — P1 UNVERIFIED
- [ ] Publish script GitHub half (`gh release create --draft`, asset download-verify, `release edit`, manifest push, raw URL check) — written, never executed (`gh` absent, no authorization) — P1 UNVERIFIED
- [ ] Worker launch from the game on Linux/macOS — UNVERIFIED (code uses `java` instead of `javaw`) — P2

## Bugs and recovery risks
- [ ] Power loss/kill between the two folder moves leaves Minecraft with no mods (ModSync then cannot self-recover); manual `worker --recover` documented in docs/RECOVERY.md — inherent to a swap while the game is closed — Severity P1 (mitigated, documented)
- [ ] Different-volume `mods`/`.zerith-modsync` is refused rather than copied — Severity P2
- [x] Fixed vs baseline: 304 skipped local scan; placeholder URL not blocked; interrupted journal never recovered; mtime-based backup pruning
- [ ] Hashing cache keyed on size+mtime: a same-size same-mtime forged file would not be rehashed in one session (restart rehashes) — P3

## Tests and delivery
- [x] `./gradlew clean build` (JDK 21): 90 tests, 0 failures, 0 skipped
- [x] `scripts\build.ps1` (JDK 25 as Gradle JVM, JDK 21 toolchain) and `scripts/build.sh`: BUILD OK
- [ ] Linux/macOS runtime — UNVERIFIED
- [ ] Real GitHub round-trip — UNVERIFIED

## Optional Claude recommendations (not approved, not implemented)
- [ ] Pinned Ed25519 manifest signature — Benefit: survives repo/account compromise of raw hosting — Complexity: medium
- [ ] In-game toast/screen instead of chat messages — Complexity: medium
- [ ] Cross-volume copy fallback for the swap — Complexity: medium

## Progress log
- 2026-11-21 — audit, full rewrite to `com.zerith.modsync`, core/worker/CLI/mod, scripts, docs — 73 unit/integration tests green, production JAR built, worker e2e on Windows — blockers: in-game run and GitHub publish not performed
