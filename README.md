# Zerith ModSync

Client-side Fabric mod (Minecraft **1.21.1**, Java 21) that keeps the `mods` folder **exactly** equal to an official modpack manifest hosted in one GitHub repository. Any difference (added, removed, renamed, updated, corrupted, tampered or extra mod) makes it download and verify a complete replacement, then a bundled external worker swaps the whole folder after Minecraft exits, keeping a rollback backup.

## Player quick start
1. Install Fabric Loader for Minecraft 1.21.1 (Fabric API is **not** required).
2. Copy `zerith-modsync-<version>.jar` into the instance's `mods` folder.
3. Start Minecraft. ModSync checks the manifest ~5 s after startup, then every 15 min, in the background.
4. When the chat says *"update downloaded and verified. Restart Minecraft"*, close the game. The worker waits for the process to exit, swaps `mods`, and verifies the result. Start Minecraft again; `mods` now contains exactly the official files.

Why a restart? Fabric loads JARs once at startup and Windows keeps them locked while the game runs, so the new set can only be installed after exit. Nothing is hot-loaded.

## Build
```powershell
.\gradlew.bat clean build                         # compile + tests + remapped JAR in build\libs
.\scripts\build.ps1 -Owner YOUR_USERNAME           # tests, JAR verification, copy to dist\, prints SHA-256
```
```bash
./gradlew clean build
scripts/build.sh --owner YOUR_USERNAME
```
`-Owner`/`--owner` bakes `https://raw.githubusercontent.com/YOUR_USERNAME/zerith-modsync/main/modpack/modpack.json` into the JAR as the default manifest URL. Without it the URL contains `OWNER`, and ModSync refuses to synchronize and shows a configuration error. Players can also override `manifestUrl` in `config/zerith-modsync.json`.

## Configuration (`config/zerith-modsync.json`, created on first start)
| key | default | meaning |
|---|---|---|
| `enabled` | true | master switch |
| `manifestUrl` | `""` | empty = build-time default |
| `checkOnStartup`, `checkIntervalMinutes` | true, 15 | 5–1440 |
| `autoDownload` | true | false = only report that an update exists |
| `autoApplyAfterExit` | true | launch the worker so it applies the update after exit |
| `strictSynchronization` | true | true = `mods` must equal the manifest. false = unlisted extra JARs are carried over (explicit opt-out) |
| `verifyHashes` | true | **must stay true**; `false` is rejected |
| `createBackup`, `maxBackups` | true, 3 | old `mods` kept in `.zerith-modsync/backups` (retention runs only after a verified success) |
| `showNotifications` | true | chat messages (progress uses the action bar) |
| `connectTimeoutSeconds`, `downloadTimeoutSeconds`, `retryLimit`, `maxConcurrentDownloads` | 10, 120, 3, 3 | bounded 1–120 / 5–3600 / 0–10 / 1–6 |
| `allowedHosts` | GitHub hosts | HTTPS hosts downloads and redirects may use |

## Safety model
- The live `mods` folder is never modified while the game runs; only `.zerith-modsync/` is written.
- Manifest must be schema-valid, Minecraft/loader-matching, non-empty, list ModSync itself, use safe filenames, SHA-256 hashes and HTTPS URLs on allowed hosts (redirects re-checked hop by hop). Failures fail closed.
- Every JAR is size-bounded, SHA-256-checked, opened as a ZIP (never executed) and its `fabric.mod.json` id must match the manifest.
- Worker: lock file, waits for the game PID, durable journal (`PLANNED → BACKING_UP → BACKED_UP → SWAPPED`), retries transient Windows locks, rolls back on any failure, recovers interrupted transactions. See [docs/RECOVERY.md](docs/RECOVERY.md).

## Repository layout
`src/core/java` shared logic (also compiled into worker and CLI) · `src/main` Fabric client · `worker/` updater (embedded in the JAR as `worker/worker.jar`) · `manifest-cli/` generator/validator · `modpack/modpack.json` public manifest · `scripts/` build/publish/validate · `docs/` · `IMPLEMENTATION_CHECKLIST.md` status of every requirement.

Publishing a modpack: [docs/PUBLISHING.md](docs/PUBLISHING.md).
