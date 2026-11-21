package com.zerith.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * Transactional replacement of the whole mods directory.
 *
 * Journal phases: PLANNED -> BACKING_UP -> BACKED_UP -> SWAPPED -> COMPLETE.
 * The phase is written BEFORE the filesystem step it names, so recovery can inspect the disk
 * and decide whether to roll forward or restore the backup.
 */
public final class Installer {
    public static final String JOURNAL = "journal.json", PENDING = "pending-update.json", STATUS = "status.json";

    /** Retry tuning; tests shrink it. */
    public record Tuning(int moveAttempts, long moveSleepMillis) {
        public static Tuning production() { return new Tuning(30, 1000); }
    }

    private final Path data, game, mods;
    private final Tuning tuning;
    private java.util.function.Consumer<String> hook = x -> { };

    private final Policy policy;

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
        Path backup = Path.of(p.backupDir()).toAbsolutePath().normalize();
        Model.Manifest manifest = FileOps.GSON.fromJson(p.manifestJson(), Model.Manifest.class);
        ManifestValidator.validate(manifest, policy);
        if (!FileOps.sha256(p.manifestJson().getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(p.manifestSha256()))
            throw new IOException("Pending manifest hash mismatch");
        FileOps.requireRealDir(staging);
        LocalScanner.verify(staging, manifest, p.strict());
        boolean hadMods = Files.exists(mods, LinkOption.NOFOLLOW_LINKS);
        if (hadMods) {
            FileOps.requireRealDir(mods);
            if (!FileOps.sameStore(mods, staging)) throw new IOException("Staging and mods are on different volumes; refusing non-atomic swap");
        }
        Files.createDirectories(backup.getParent());

        journal("PLANNED", hadMods, p);
        boolean moved = false;
        try {
            if (hadMods) {
                journal("BACKING_UP", true, p);
                FileOps.moveRetry(mods, backup, tuning.moveAttempts(), tuning.moveSleepMillis());
                moved = true;
            }
            journal("BACKED_UP", hadMods, p);
            FileOps.moveRetry(staging, mods, tuning.moveAttempts(), tuning.moveSleepMillis());
            journal("SWAPPED", hadMods, p);
            LocalScanner.verify(mods, manifest, p.strict());
        } catch (IOException | RuntimeException failure) {
            try {
                if (hadMods && !moved) { // old mods never left their place: just abandon the transaction
                    Files.deleteIfExists(journalFile());
                    FileOps.deleteTree(staging);
                    deletePendingFor(p);
                } else rollback(p, hadMods);
            }
            catch (IOException re) { failure.addSuppressed(re); throw new IOException("Update failed and rollback needs manual recovery: " + failure.getMessage(), failure); }
            status(p, "FAILED", String.valueOf(failure.getMessage()));
            throw failure instanceof IOException io ? io : new IOException(failure);
        }
        complete(p, hadMods);
    }

    /** Finishes or rolls back an interrupted transaction. Returns true if a journal was handled. */
    public boolean recover() throws IOException {
        if (!Files.exists(journalFile())) return false;
        Model.Journal j = FileOps.readJson(journalFile(), Model.Journal.class);
        Model.Pending p = j.tx();
        Path staging = checkedPaths(p);
        Path backup = Path.of(p.backupDir()).toAbsolutePath().normalize();
        Model.Manifest manifest = FileOps.GSON.fromJson(p.manifestJson(), Model.Manifest.class);
        boolean modsOk = Files.exists(mods, LinkOption.NOFOLLOW_LINKS) && matches(mods, manifest, p.strict());
        boolean hasMods = Files.exists(mods, LinkOption.NOFOLLOW_LINKS);
        boolean hasBackup = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
        boolean hasStaging = Files.exists(staging, LinkOption.NOFOLLOW_LINKS);

        switch (j.phase()) {
            case "COMPLETE" -> complete(p, j.hadMods());
            case "PLANNED" -> { Files.deleteIfExists(journalFile()); }                       // nothing moved yet
            case "SWAPPED" -> { if (modsOk) complete(p, j.hadMods()); else { rollback(p, j.hadMods()); status(p, "FAILED", "Recovered: new mods did not verify; previous mods restored"); } }
            case "BACKING_UP", "BACKED_UP" -> {
                if (modsOk) complete(p, j.hadMods());                                         // swap finished, journal lagged
                else if (hasMods && !hasBackup && j.hadMods()) Files.deleteIfExists(journalFile()); // backup move never happened; mods intact
                else if (!hasMods && hasStaging && matches(staging, manifest, p.strict())) {  // roll forward
                    FileOps.moveRetry(staging, mods, tuning.moveAttempts(), tuning.moveSleepMillis());
                    if (matches(mods, manifest, p.strict())) complete(p, j.hadMods());
                    else { rollback(p, j.hadMods()); status(p, "FAILED", "Recovered: swap did not verify; previous mods restored"); }
                } else { rollback(p, j.hadMods()); status(p, "FAILED", "Recovered: update abandoned; previous mods restored"); }
            }
            default -> throw new IOException("Unknown journal phase: " + j.phase());
        }
        return true;
    }

    private void rollback(Model.Pending p, boolean hadMods) throws IOException {
        Path backup = Path.of(p.backupDir()).toAbsolutePath().normalize();
        Path failed = data.resolve("failed").resolve(p.transactionId());
        if (hadMods && !Files.exists(backup, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
                // Backup is gone but mods exists: it is the only copy left, never destroy it.
                throw new IOException("Backup missing; leaving mods as is. Manual check required: " + mods);
            }
            throw new IOException("Backup missing and mods absent: " + backup);
        }
        if (Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(failed.getParent());
            FileOps.moveRetry(mods, failed, tuning.moveAttempts(), tuning.moveSleepMillis());
        }
        if (hadMods) FileOps.moveRetry(backup, mods, tuning.moveAttempts(), tuning.moveSleepMillis());
        FileOps.deleteTree(failed);
        FileOps.deleteTree(Path.of(p.stagingDir()));
        Files.deleteIfExists(journalFile());
        deletePendingFor(p);
    }

    private void complete(Model.Pending p, boolean hadMods) throws IOException {
        Path backup = Path.of(p.backupDir()).toAbsolutePath().normalize();
        status(p, "SUCCESS", "Modpack updated");
        Files.deleteIfExists(journalFile());
        deletePendingFor(p);
        FileOps.deleteTree(Path.of(p.stagingDir()));
        try {
            if (!p.createBackup()) FileOps.deleteTree(backup);
            else prune(Math.max(1, p.maxBackups()), backup);
        } catch (IOException ignored) { /* cleanup only; update itself succeeded */ }
    }

    private void deletePendingFor(Model.Pending p) throws IOException {
        if (!Files.exists(pendingFile())) return;
        try {
            if (FileOps.readJson(pendingFile(), Model.Pending.class).transactionId().equals(p.transactionId())) Files.delete(pendingFile());
        } catch (IOException e) { Files.deleteIfExists(pendingFile()); }
    }

    private void prune(int keep, Path protectedBackup) throws IOException {
        Path dir = data.resolve("backups");
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

    /** All transaction paths must be exactly where this instance expects them. */
    private Path checkedPaths(Model.Pending p) throws IOException {
        Path m = Path.of(p.modsDir()).toAbsolutePath().normalize();
        Path s = Path.of(p.stagingDir()).toAbsolutePath().normalize();
        Path b = Path.of(p.backupDir()).toAbsolutePath().normalize();
        if (!m.equals(mods)) throw new IOException("Transaction mods path is not this instance's mods directory");
        if (!s.getParent().equals(data.resolve("staging")) || !b.getParent().equals(data.resolve("backups")))
            throw new IOException("Transaction paths escape the data directory");
        if (!p.transactionId().matches("[A-Za-z0-9-]{8,64}")) throw new IOException("Bad transaction id");
        FileOps.requireRealDir(data);
        return s;
    }
}
