package com.zerith.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Transactional update of the game instance: whole-folder replacement of mods (optional) followed by
 * per-file application of overrides (config/, options.txt, resourcepacks/, shaderpacks/).
 *
 * Journal phases: PLANNED -> BACKING_UP -> BACKED_UP -> SWAPPED -> OVERRIDES -> COMPLETE.
 * The phase is written BEFORE the filesystem step it names, so recovery can inspect the disk
 * and decide whether to roll forward or restore backups.
 */
public final class Installer {
    public static final String JOURNAL = "journal.json", PENDING = "pending-update.json", STATUS = "status.json";

    /** Retry tuning; tests shrink it. */
    public record Tuning(int moveAttempts, long moveSleepMillis) {
        public static Tuning production() { return new Tuning(30, 1000); }
    }

    private final Path data, game, mods;
    private final Tuning tuning;
    private final Policy policy;
    private java.util.function.Consumer<String> hook = x -> { };

    public Installer(Path dataDir, Tuning tuning) { this(dataDir, tuning, Policy.defaults().offline()); }

    /** The policy decides which manifest shapes are acceptable (tests inject loopback HTTP). */
    public Installer(Path dataDir, Tuning tuning, Policy policy) {
        this.policy = policy.offline();
        this.data = dataDir.toAbsolutePath().normalize();
        this.game = data.getParent();
        this.mods = game.resolve("mods");
        this.tuning = tuning;
    }

    /** Test seam: invoked right after each journal phase is persisted. */
    public Installer hook(java.util.function.Consumer<String> h) { this.hook = h; return this; }

    public Path pendingFile() { return data.resolve(PENDING); }
    public Path journalFile() { return data.resolve(JOURNAL); }

    /** Applies a pending transaction. Throws if it failed (after rolling back where possible). */
    public void install(Model.Pending p) throws IOException {
        Path staging = checkedPaths(p);
        Model.Manifest manifest = FileOps.GSON.fromJson(p.manifestJson(), Model.Manifest.class);
        ManifestValidator.validate(manifest, policy);
        if (!FileOps.sha256(p.manifestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(p.manifestSha256()))
            throw new IOException("Pending manifest hash mismatch");
        boolean swap = p.swapsMods();
        List<Model.OverrideFile> ov = p.overrideFiles();
        boolean hadMods = false;
        if (swap) {
            FileOps.requireRealDir(staging);
            LocalScanner.verify(staging, manifest, p.strict());
            hadMods = Files.exists(mods, LinkOption.NOFOLLOW_LINKS);
            if (hadMods) {
                FileOps.requireRealDir(mods);
                if (!FileOps.sameStore(mods, staging)) throw new IOException("Staging and mods are on different volumes; refusing non-atomic swap");
            }
            Files.createDirectories(backupPath(p).getParent());
        }
        if (!ov.isEmpty()) {
            Set<String> listed = new HashSet<>();
            if (manifest.overrides() != null) for (Model.OverrideFile f : manifest.overrides().files()) listed.add(f.path() + "|" + f.sha256().toLowerCase(Locale.ROOT));
            for (Model.OverrideFile f : ov) {
                Overrides.target(game, f.path());
                if (!listed.contains(f.path() + "|" + f.sha256().toLowerCase(Locale.ROOT))) throw new IOException("Pending override is not in the manifest: " + f.path());
            }
            FileOps.requireRealDir(overridesDir(p));
            Overrides.verifyStaged(overridesDir(p), ov);
        }

        journal("PLANNED", hadMods, p);
        boolean moved = false;
        try {
            if (swap) {
                Path backup = backupPath(p);
                if (hadMods) {
                    journal("BACKING_UP", true, p);
                    FileOps.moveRetry(mods, backup, tuning.moveAttempts(), tuning.moveSleepMillis());
                    moved = true;
                }
                journal("BACKED_UP", hadMods, p);
                FileOps.moveRetry(staging, mods, tuning.moveAttempts(), tuning.moveSleepMillis());
                journal("SWAPPED", hadMods, p);
                LocalScanner.verify(mods, manifest, p.strict());
            }
            if (!ov.isEmpty()) {
                journal("OVERRIDES", hadMods, p);
                applyOverrides(p);
            }
        } catch (IOException | RuntimeException failure) {
            try {
                if (swap && hadMods && !moved) { // old mods never left their place: just abandon the transaction
                    Files.deleteIfExists(journalFile());
                    FileOps.deleteTree(staging);
                    FileOps.deleteTree(overridesPath(p));
                    deletePendingFor(p);
                } else rollback(p, hadMods);
            } catch (IOException re) {
                failure.addSuppressed(re);
                throw new IOException("Update failed and rollback needs manual recovery: " + failure.getMessage(), failure);
            }
            status(p, "FAILED", String.valueOf(failure.getMessage()));
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
        complete(p);
    }

    /** Finishes or rolls back an interrupted transaction. Returns true if a journal was handled. */
    public boolean recover() throws IOException {
        if (!Files.exists(journalFile())) return false;
        Model.Journal j = FileOps.readJson(journalFile(), Model.Journal.class);
        Model.Pending p = j.tx();
        Path staging = checkedPaths(p);
        Model.Manifest manifest = FileOps.GSON.fromJson(p.manifestJson(), Model.Manifest.class);
        boolean swap = p.swapsMods();
        boolean hasMods = Files.exists(mods, LinkOption.NOFOLLOW_LINKS);
        boolean modsOk = swap && hasMods && matches(mods, manifest, p.strict());
        boolean hasBackup = swap && Files.exists(backupPath(p), LinkOption.NOFOLLOW_LINKS);
        boolean hasStaging = swap && Files.exists(staging, LinkOption.NOFOLLOW_LINKS);

        switch (j.phase()) {
            case "COMPLETE" -> complete(p);
            case "PLANNED" -> Files.deleteIfExists(journalFile());                       // nothing moved yet
            case "OVERRIDES" -> forward(p, j.hadMods());                                 // swap (if any) was verified before this phase
            case "SWAPPED" -> {
                if (modsOk) forward(p, j.hadMods());
                else { rollback(p, j.hadMods()); status(p, "FAILED", "Recovered: new mods did not verify; previous mods restored"); }
            }
            case "BACKING_UP", "BACKED_UP" -> {
                if (!swap) throw new IOException("Inconsistent journal");
                if (modsOk) forward(p, j.hadMods());                                      // swap finished, journal lagged
                else if (hasMods && !hasBackup && j.hadMods()) Files.deleteIfExists(journalFile()); // backup move never happened; mods intact
                else if (!hasMods && hasStaging && matches(staging, manifest, p.strict())) {  // roll forward
                    FileOps.moveRetry(staging, mods, tuning.moveAttempts(), tuning.moveSleepMillis());
                    if (matches(mods, manifest, p.strict())) forward(p, j.hadMods());
                    else { rollback(p, j.hadMods()); status(p, "FAILED", "Recovered: swap did not verify; previous mods restored"); }
                } else { rollback(p, j.hadMods()); status(p, "FAILED", "Recovered: update abandoned; previous mods restored"); }
            }
            default -> throw new IOException("Unknown journal phase: " + j.phase());
        }
        return true;
    }

    /** Roll forward from a verified swap: apply overrides (idempotent), then complete; roll everything back on failure. */
    private void forward(Model.Pending p, boolean hadMods) throws IOException {
        try {
            if (!p.overrideFiles().isEmpty()) {
                journal("OVERRIDES", hadMods, p);
                applyOverrides(p);
            }
        } catch (IOException | RuntimeException e) {
            rollback(p, hadMods);
            status(p, "FAILED", "Recovered: overrides could not be applied (" + e.getMessage() + "); previous state restored");
            return;
        }
        complete(p);
    }

    // ------------------------------------------------------------------ overrides

    /** Idempotent: safe to re-run after a crash. Old files are moved (not deleted) into backups-overrides/. */
    private void applyOverrides(Model.Pending p) throws IOException {
        Path root = overridesPath(p), backupRoot = overridesBackup(p);
        for (Model.OverrideFile f : p.overrideFiles()) {
            Path target = Overrides.target(game, f.path());
            Path staged = root.resolve(f.path());
            Overrides.requirePlainParents(game, target);
            boolean targetIsFile = Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS);
            if (!Files.exists(staged, LinkOption.NOFOLLOW_LINKS)) {              // already applied before a crash?
                if (targetIsFile && FileOps.sha256(target).equalsIgnoreCase(f.sha256())) continue;
                throw new IOException("Staged override vanished: " + f.path());
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                if (!targetIsFile) throw new IOException("Refusing to replace a non-regular file: " + f.path());
                Path b = backupRoot.resolve(f.path());
                if (Files.exists(b, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Unexpected existing backup for " + f.path());
                Files.createDirectories(b.getParent());
                FileOps.moveRetry(target, b, tuning.moveAttempts(), tuning.moveSleepMillis());
            }
            Files.createDirectories(target.getParent());
            FileOps.moveRetry(staged, target, tuning.moveAttempts(), tuning.moveSleepMillis());
        }
        for (Model.OverrideFile f : p.overrideFiles()) {
            Path t = Overrides.target(game, f.path());
            if (!Files.isRegularFile(t, LinkOption.NOFOLLOW_LINKS) || !FileOps.sha256(t).equalsIgnoreCase(f.sha256()))
                throw new IOException("Override did not verify after applying: " + f.path());
        }
    }

    /** Puts every overwritten file back and removes files this transaction created. */
    private void restoreOverrides(Model.Pending p) throws IOException {
        if (p.overrideFiles().isEmpty()) return;
        Path backupRoot = overridesBackup(p);
        IOException first = null;
        for (Model.OverrideFile f : p.overrideFiles()) {
            try {
                Path t = Overrides.target(game, f.path());
                Path b = backupRoot.resolve(f.path());
                if (Files.exists(b, LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.exists(t, LinkOption.NOFOLLOW_LINKS)) Files.delete(t);
                    Files.createDirectories(t.getParent());
                    FileOps.moveRetry(b, t, tuning.moveAttempts(), tuning.moveSleepMillis());
                } else if (Files.isRegularFile(t, LinkOption.NOFOLLOW_LINKS) && FileOps.sha256(t).equalsIgnoreCase(f.sha256())) {
                    Files.delete(t);   // created by this transaction (identical files are never staged)
                }
            } catch (IOException e) { if (first == null) first = e; else first.addSuppressed(e); }
        }
        if (first != null) throw first;
    }

    // ------------------------------------------------------------------ rollback / completion

    private void rollback(Model.Pending p, boolean hadMods) throws IOException {
        restoreOverrides(p);
        if (p.swapsMods()) {
            Path backup = backupPath(p);
            Path failed = data.resolve("failed").resolve(p.transactionId());
            if (hadMods && !Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.exists(mods, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Backup missing; leaving mods as is. Manual check required: " + mods); // never destroy the only copy
                throw new IOException("Backup missing and mods absent: " + backup);
            }
            if (Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(failed.getParent());
                FileOps.moveRetry(mods, failed, tuning.moveAttempts(), tuning.moveSleepMillis());
            }
            if (hadMods) FileOps.moveRetry(backup, mods, tuning.moveAttempts(), tuning.moveSleepMillis());
            FileOps.deleteTree(failed);
            FileOps.deleteTree(Path.of(p.stagingDir()));
        }
        FileOps.deleteTree(overridesPath(p));
        FileOps.deleteTree(overridesBackup(p));
        Files.deleteIfExists(journalFile());
        deletePendingFor(p);
    }

    private void complete(Model.Pending p) throws IOException {
        if (!p.overrideFiles().isEmpty()) {
            Map<String, String> applied = Overrides.loadApplied(data);
            for (Model.OverrideFile f : p.overrideFiles()) applied.put(f.path(), f.sha256().toLowerCase(Locale.ROOT));
            Overrides.saveApplied(data, applied);
        }
        status(p, "SUCCESS", "Modpack updated");
        Files.deleteIfExists(journalFile());
        deletePendingFor(p);
        if (p.swapsMods()) FileOps.deleteTree(Path.of(p.stagingDir()));
        FileOps.deleteTree(overridesPath(p));
        try {
            int keep = Math.max(1, p.maxBackups());
            if (p.swapsMods()) {
                if (!p.createBackup()) FileOps.deleteTree(backupPath(p)); else prune(data.resolve("backups"), keep, backupPath(p));
            }
            if (!p.createBackup()) FileOps.deleteTree(overridesBackup(p)); else prune(data.resolve("backups-overrides"), keep, overridesBackup(p));
        } catch (IOException ignored) { /* cleanup only; update itself succeeded */ }
    }

    private void deletePendingFor(Model.Pending p) throws IOException {
        if (!Files.exists(pendingFile())) return;
        try {
            if (FileOps.readJson(pendingFile(), Model.Pending.class).transactionId().equals(p.transactionId())) Files.delete(pendingFile());
        } catch (IOException e) { Files.deleteIfExists(pendingFile()); }
    }

    private void prune(Path dir, int keep, Path protectedBackup) throws IOException {
        if (!Files.isDirectory(dir)) return;
        List<Path> all;
        try (var s = Files.list(dir)) { all = s.filter(Files::isDirectory).sorted(Comparator.comparing((Path x) -> x.getFileName().toString()).reversed()).toList(); }
        for (int i = keep; i < all.size(); i++) if (!all.get(i).equals(protectedBackup)) FileOps.deleteTree(all.get(i));
    }

    private boolean matches(Path dir, Model.Manifest m, boolean strict) {
        try { LocalScanner.verify(dir, m, strict); return true; } catch (IOException e) { return false; }
    }

    private void journal(String phase, boolean hadMods, Model.Pending p) throws IOException {
        FileOps.writeJson(journalFile(), new Model.Journal(phase, hadMods, p));
        hook.accept(phase);
    }

    private void status(Model.Pending p, String state, String msg) throws IOException {
        FileOps.writeJson(data.resolve(STATUS), new Model.Status(p.transactionId(), state, msg, System.currentTimeMillis()));
    }

    private Path backupPath(Model.Pending p) { return Path.of(p.backupDir()).toAbsolutePath().normalize(); }
    private Path overridesPath(Model.Pending p) {
        return p.overridesDir() == null ? data.resolve("staging-overrides").resolve("none") : Path.of(p.overridesDir()).toAbsolutePath().normalize();
    }
    private Path overridesDir(Model.Pending p) { return overridesPath(p); }
    private Path overridesBackup(Model.Pending p) { return data.resolve("backups-overrides").resolve(p.transactionId()); }

    /** All transaction paths must be exactly where this instance expects them. Returns the mods staging dir (null if none). */
    private Path checkedPaths(Model.Pending p) throws IOException {
        if (p.transactionId() == null || !p.transactionId().matches("[A-Za-z0-9-]{8,64}")) throw new IOException("Bad transaction id");
        Path m = Path.of(p.modsDir()).toAbsolutePath().normalize();
        if (!m.equals(mods)) throw new IOException("Transaction mods path is not this instance's mods directory");
        Path s = null;
        if (p.swapsMods()) {
            s = Path.of(p.stagingDir()).toAbsolutePath().normalize();
            Path b = backupPath(p);
            if (!s.getParent().equals(data.resolve("staging")) || !b.getParent().equals(data.resolve("backups")))
                throw new IOException("Transaction paths escape the data directory");
        }
        if (p.overridesDir() != null && !Path.of(p.overridesDir()).toAbsolutePath().normalize().getParent().equals(data.resolve("staging-overrides")))
            throw new IOException("Overrides staging path escapes the data directory");
        if (!p.overrideFiles().isEmpty() && p.overridesDir() == null) throw new IOException("Overrides listed without a staging directory");
        FileOps.requireRealDir(data);
        return s;
    }
}
