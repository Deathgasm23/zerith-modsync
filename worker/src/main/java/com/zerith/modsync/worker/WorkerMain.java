package com.zerith.modsync.worker;

import com.zerith.modsync.core.*;

import java.io.IOException;
import java.nio.channels.*;
import java.nio.file.*;
import java.time.Instant;

/**
 * Standalone updater. Usage: worker.jar [--recover] &lt;data-directory&gt;
 * Waits for the Minecraft process to exit, then applies (or recovers) the pending transaction.
 */
public final class WorkerMain {
    public static void main(String[] args) {
        System.exit(run(args, Installer.Tuning.production()));
    }

    /** Returns 0 on success/nothing to do, 2 on failure. Package-visible for tests via {@link #run}. */
    public static int run(String[] args, Installer.Tuning tuning) {
        boolean recoverOnly = args.length == 2 && args[0].equals("--recover");
        if (args.length != (recoverOnly ? 2 : 1)) {
            System.err.println("Usage: worker.jar [--recover] <data-directory>");
            return 64;
        }
        Path data = Path.of(args[args.length - 1]).toAbsolutePath().normalize();
        try {
            if (!Files.isDirectory(data)) return 0;
            try (FileChannel ch = FileChannel.open(data.resolve("worker.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock lock = ch.tryLock()) {
                if (lock == null) { log(data, "Another worker holds the lock; exiting"); return 0; }
                return locked(data, recoverOnly, tuning);
            } catch (OverlappingFileLockException e) {
                return 0;
            }
        } catch (IOException | RuntimeException e) {
            log(data, "Worker error: " + e);
            return 2;
        }
    }

    private static int locked(Path data, boolean recoverOnly, Installer.Tuning tuning) throws IOException {
        Installer installer = new Installer(data, tuning);
        Path pending = installer.pendingFile();
        if (!recoverOnly && !Files.isRegularFile(pending) && !Files.exists(installer.journalFile())) return 0;

        Model.Pending p = Files.isRegularFile(pending) ? FileOps.readJson(pending, Model.Pending.class) : null;
        if (!recoverOnly && p != null) waitForExit(p.parentPid());

        if (installer.recover()) log(data, "Recovered an interrupted transaction");
        if (recoverOnly || !Files.isRegularFile(pending)) return 0;

        p = FileOps.readJson(pending, Model.Pending.class);
        try {
            installer.install(p);
            log(data, "Update " + p.transactionId() + " applied");
            return 0;
        } catch (IOException | RuntimeException e) {
            log(data, "Update " + p.transactionId() + " failed: " + e);
            if (!Files.exists(data.resolve(Installer.STATUS)) || !isFailedFor(data, p))
                FileOps.writeJson(data.resolve(Installer.STATUS), new Model.Status(p.transactionId(), "FAILED", String.valueOf(e.getMessage()), System.currentTimeMillis()));
            Files.deleteIfExists(pending);   // never retry the same broken transaction in a loop
            return 2;
        }
    }

    private static boolean isFailedFor(Path data, Model.Pending p) {
        try {
            Model.Status s = FileOps.readJson(data.resolve(Installer.STATUS), Model.Status.class);
            return p.transactionId().equals(s.transactionId()) && "FAILED".equals(s.state());
        } catch (IOException e) { return false; }
    }

    private static void waitForExit(long pid) throws IOException {
        var h = ProcessHandle.of(pid);
        if (h.isPresent()) {
            try { h.get().onExit().get(); }
            catch (Exception e) { Thread.currentThread().interrupt(); throw new IOException("Interrupted while waiting for Minecraft", e); }
        }
        FileOps.sleep(2000);   // let the OS release JAR handles
    }

    private static void log(Path data, String msg) {
        try {
            Files.createDirectories(data.resolve("logs"));
            Files.writeString(data.resolve("logs/worker.log"), Instant.now() + " " + msg + System.lineSeparator(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { System.err.println(msg); }
    }
}
