# Transaction, recovery and files

All state lives in `<game dir>\.zerith-modsync\` (never inside `mods`):

| path | purpose |
|---|---|
| `worker-<hash>.jar` | worker extracted from the mod JAR |
| `pending-update.json` | verified staged update waiting for the worker |
| `staging\<tx>\` | complete verified replacement for `mods` |
| `journal.json` | durable phase of a swap in progress |
| `backups\<time>-<tx>\` | previous `mods` folders (newest `maxBackups` kept) |
| `status.json` | result of the last swap, shown once at next start |
| `cached-manifest.json`, `http-cache.json` | ETag / Last-Modified cache |
| `logs\modsync.log`, `logs\worker.log` | diagnostics (no credentials are ever handled) |
| `worker.lock` | single-worker lock |

## Swap phases (written to the journal *before* the step)
`PLANNED` → `BACKING_UP` (move `mods` → backup) → `BACKED_UP` (move staging → `mods`) → `SWAPPED` (verify) → complete (delete journal/pending/staging, prune backups).

On any exception the worker rolls back: the new folder is discarded and the backup is moved back. If the process dies, the next worker run (or `--recover`) inspects the disk and rolls forward (staging verifies) or restores the backup. A missing backup is never "fixed" by deleting whatever remains in `mods`.

## When Minecraft starts with no mods
This happens only if the machine lost power or the worker was killed in the instant between the two folder moves. Fabric then does not load ModSync, so recover by hand:
```powershell
$w = (Get-ChildItem "<game dir>\.zerith-modsync\worker-*.jar" | Select-Object -First 1).FullName
java -jar $w --recover "<game dir>\.zerith-modsync"
```
If the worker JAR is gone, move the newest `.zerith-modsync\backups\<...>` folder back to `<game dir>\mods`. Do not delete `journal.json`, backups or staging first.

## Known limits
- `mods` and `.zerith-modsync` must be on the same volume (atomic rename); otherwise the swap is refused.
- Windows locks are retried 30 × 1 s; if something (antivirus, a second game instance) keeps a JAR open, the update is abandoned with the old mods intact and retried at the next check.
- Backups are whole copies of the old `mods` folder; they cost disk space (`maxBackups`).
- Manifest signatures are not implemented; integrity relies on HTTPS to GitHub and your repository access controls.
