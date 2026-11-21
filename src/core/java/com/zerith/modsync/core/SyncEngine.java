package com.zerith.modsync.core;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * One synchronization pass: fetch, compare, stage a complete verified replacement and write the
 * pending transaction. It never touches the live mods directory.
 */
public final class SyncEngine {
    public interface Listener {
        default void status(String message) {}
        default void progress(int doneFiles, int totalFiles, long doneBytes, long totalBytes) {}
    }

    public enum Outcome { UP_TO_DATE, STAGED, ALREADY_PENDING, UPDATE_AVAILABLE, CONFIG_ERROR, BUSY }

    public record Result(Outcome outcome, LocalScanner.Diff diff, Model.Manifest manifest, String detail) {}

    private final Path game, mods, data;
    private final Config cfg;
    private final String url;
    private final Policy policy;
    private final Downloader http;
    private final Listener listener;
    private final LocalScanner scanner = new LocalScanner();
    private final AtomicBoolean busy = new AtomicBoolean();
    private volatile boolean cancelled;

    public SyncEngine(Path gameDir, Config cfg, String manifestUrl, Policy policy, Downloader http, Listener listener) {
        this.game = gameDir.toAbsolutePath().normalize();
        this.mods = game.resolve("mods");
        this.data = game.resolve(".zerith-modsync");
        this.cfg = cfg;
        this.url = manifestUrl;
        this.policy = policy;
        this.http = http;
        this.listener = listener;
    }

    public void cancel() { cancelled = true; }
    public Path dataDir() { return data; }

    public Result runOnce() throws IOException {
        String problem = Config.urlProblem(url);
        if (problem != null) return new Result(Outcome.CONFIG_ERROR, null, null, problem);
        if (!busy.compareAndSet(false, true)) return new Result(Outcome.BUSY, null, null, "A check is already running");
        try {
            cancelled = false;
            FileOps.requireRealDir(game);
            Files.createDirectories(data);
            FileOps.requireRealDir(data);
            if (Files.exists(data.resolve(Installer.JOURNAL))) return new Result(Outcome.BUSY, null, null, "A transaction is being applied or needs recovery");
            return pass();
        } finally {
            busy.set(false);
        }
    }

    private Result pass() throws IOException {
        listener.status("Checking for modpack updates...");
        ManifestFetcher fetcher = new ManifestFetcher(http, policy, data);
        ManifestFetcher.Fetched f = fetcher.fetch(URI.create(url));
        Model.Manifest manifest = f.manifest();
        LocalScanner.Diff diff = scanner.diff(mods, manifest);
        boolean strict = cfg.strictSynchronization;
        boolean modsChanged = diff.needsUpdate(strict);
        List<Model.OverrideFile> ov = Overrides.pending(game, data, manifest);
        Path pendingFile = data.resolve(Installer.PENDING);
        String manifestSha = FileOps.sha256(f.json().getBytes(StandardCharsets.UTF_8));

        if (!modsChanged && ov.isEmpty()) {
            discardPending(pendingFile);
            cleanupStaging(null, null);
            fetcher.commit(f);
            return new Result(Outcome.UP_TO_DATE, diff, manifest, "Modpack is up to date");
        }
        listener.status("Modpack differences detected: " + diff.summary() + " overrides=" + ov.size());

        if (Files.exists(pendingFile)) {
            try {
                Model.Pending old = FileOps.readJson(pendingFile, Model.Pending.class);
                boolean modsReady = old.stagingDir() == null ? !modsChanged : (modsChanged && Files.isDirectory(Path.of(old.stagingDir())));
                boolean ovReady = old.overrideFiles().isEmpty() ? ov.isEmpty() : (!ov.isEmpty() && Files.isDirectory(Path.of(old.overridesDir())));
                if (old.manifestSha256().equals(manifestSha) && modsReady && ovReady) {
                    cleanupStaging(old.stagingDir(), old.overridesDir());
                    return new Result(Outcome.ALREADY_PENDING, diff, manifest, "Verified update already staged");
                }
            } catch (IOException | RuntimeException ignored) { /* fall through, restage */ }
            discardPending(pendingFile);
        }
        cleanupStaging(null, null);
        if (!cfg.autoDownload) return new Result(Outcome.UPDATE_AVAILABLE, diff, manifest, "autoDownload is disabled");

        String tx = UUID.randomUUID().toString();
        Path staging = data.resolve("staging").resolve(tx);
        Path ovDir = data.resolve("staging-overrides").resolve(tx);
        Path ovZip = data.resolve("staging-overrides").resolve(tx + ".zip");
        try {
            checkDisk(manifest, modsChanged, ov);
            if (modsChanged) {
                Files.createDirectories(staging);
                stage(manifest, staging);
                if (!strict) copyExtras(diff, staging);
            }
            if (!ov.isEmpty()) {
                Files.createDirectories(ovDir.getParent());
                Model.Overrides o = manifest.overrides();
                listener.status("Downloading overrides archive...");
                http.download(new Model.Mod("overrides", o.filename(), o.url(), o.sha256(), o.size()), ovZip, n -> { }, () -> cancelled);
                Overrides.extract(ovZip, o, ov, ovDir);
                Files.delete(ovZip);
                Overrides.verifyStaged(ovDir, ov);
            }
            listener.status("Verifying staged files...");
            if (modsChanged) LocalScanner.verify(staging, manifest, strict);
            String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
            Model.Pending p = new Model.Pending(tx, manifestSha, modsChanged ? staging.toString() : null, mods.toString(),
                    data.resolve("backups").resolve(stamp + "-" + tx).toString(), f.json(),
                    ProcessHandle.current().pid(), cfg.maxBackups, cfg.createBackup, strict,
                    ov.isEmpty() ? null : ovDir.toString(), ov);
            FileOps.writeJson(pendingFile, p);
            fetcher.commit(f);
        } catch (IOException | RuntimeException e) {
            FileOps.deleteTree(staging);
            FileOps.deleteTree(ovDir);
            Files.deleteIfExists(ovZip);
            throw e;
        }
        return new Result(Outcome.STAGED, diff, manifest, "Update staged; restart required");
    }

    private void stage(Model.Manifest manifest, Path staging) throws IOException {
        int total = manifest.mods().size();
        long totalBytes = manifest.mods().stream().mapToLong(m -> m.size() == null ? 0 : m.size()).sum();
        AtomicInteger done = new AtomicInteger();
        AtomicLong bytes = new AtomicLong();
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(cfg.maxConcurrentDownloads, total), r -> {
            Thread t = new Thread(r, "Zerith ModSync download");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<?>> fs = new ArrayList<>();
            for (Model.Mod m : manifest.mods()) {
                fs.add(pool.submit(() -> {
                    Path target = staging.resolve(m.filename());
                    http.download(m, target, n -> listener.progress(done.get(), total, bytes.addAndGet(n), totalBytes), () -> cancelled);
                    JarInspector.FabricInfo info = JarInspector.inspect(target);
                    if (!info.id().equals(m.id()))
                        throw new IOException("Mod id mismatch in " + m.filename() + ": manifest says " + m.id() + ", jar says " + info.id());
                    listener.progress(done.incrementAndGet(), total, bytes.get(), totalBytes);
                    return null;
                }));
            }
            for (Future<?> f : fs) {
                try { f.get(); }
                catch (ExecutionException e) {
                    pool.shutdownNow();
                    Throwable c = e.getCause();
                    throw c instanceof IOException io ? io : new IOException(c);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("Interrupted");
        } finally {
            pool.shutdownNow();
        }
    }

    private void copyExtras(LocalScanner.Diff diff, Path staging) throws IOException {
        for (String x : diff.extra()) {
            Path src = mods.resolve(x);
            if (Files.isRegularFile(src, LinkOption.NOFOLLOW_LINKS)) Files.copy(src, staging.resolve(x));
        }
    }

    private void checkDisk(Model.Manifest m, boolean mods, List<Model.OverrideFile> ov) throws IOException {
        long need = 0;
        if (mods) for (Model.Mod mod : m.mods()) need += mod.size() != null ? mod.size() : 32L << 20;
        if (!ov.isEmpty()) need += m.overrides().size() + ov.stream().mapToLong(Model.OverrideFile::size).sum();
        Files.createDirectories(data);
        long free = Files.getFileStore(data).getUsableSpace();
        if (free < need + (64L << 20)) throw new IOException("Not enough disk space: need ~" + (need >> 20) + " MiB, free " + (free >> 20) + " MiB");
    }

    private void discardPending(Path pendingFile) throws IOException {
        if (!Files.exists(pendingFile)) return;
        try {
            Model.Pending old = FileOps.readJson(pendingFile, Model.Pending.class);
            deleteUnder(old.stagingDir(), data.resolve("staging"));
            deleteUnder(old.overridesDir(), data.resolve("staging-overrides"));
        } catch (IOException ignored) { /* corrupt pending: just remove it */ }
        Files.deleteIfExists(pendingFile);
    }

    private static void deleteUnder(String dir, Path parent) throws IOException {
        if (dir == null) return;
        Path s = Path.of(dir).toAbsolutePath().normalize();
        if (s.getParent().equals(parent)) FileOps.deleteTree(s);
    }

    /** Removes staging directories that no live transaction refers to (crash leftovers). */
    private void cleanupStaging(String keepMods, String keepOverrides) throws IOException {
        sweep(data.resolve("staging"), keepMods);
        sweep(data.resolve("staging-overrides"), keepOverrides);
    }

    private void sweep(Path dir, String keep) throws IOException {
        if (!Files.isDirectory(dir)) return;
        Path keepP = keep == null ? null : Path.of(keep).toAbsolutePath().normalize();
        try (var s = Files.list(dir)) {
            for (Path p : s.toList()) if (!p.toAbsolutePath().normalize().equals(keepP)) FileOps.deleteTree(p);
        }
    }
}
