package com.zerith.modsync;

import com.zerith.modsync.core.*;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Background orchestration: periodic checks, worker deployment/launch, status reporting. No Minecraft types. */
final class ModSyncService implements AutoCloseable {
    interface Notifier {
        void overlay(String text);     // transient action-bar style message
        void important(String text);   // chat message
    }

    private final Path game, data, configFile;
    private final Notifier notifier;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Zerith ModSync");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean workerLaunched = new AtomicBoolean();
    private volatile Config cfg;
    private volatile SyncEngine engine;
    private volatile Path workerJar;
    private volatile String lastImportant = "";
    private long lastProgressMillis;

    ModSyncService(Path gameDir, Notifier notifier) {
        this.game = gameDir.toAbsolutePath().normalize();
        this.data = game.resolve(".zerith-modsync");
        this.configFile = game.resolve("config").resolve("zerith-modsync.json");
        this.notifier = notifier;
    }

    void start() {
        try {
            Files.createDirectories(data.resolve("logs"));
            cfg = Config.load(configFile);
        } catch (IOException | RuntimeException e) {
            log("Configuration error: " + e);
            notifier.important("Zerith ModSync: configuration error (" + e.getMessage() + "). Mods untouched.");
            return;
        }
        String url = cfg.effectiveUrl(buildDefaultUrl());
        try {
            workerJar = deployWorker();
            reportPreviousStatus();
            if (Files.exists(data.resolve(Installer.JOURNAL))) launchWorker("--recover");
        } catch (IOException | RuntimeException e) {
            log("Startup error: " + e);
        }
        Runtime.getRuntime().addShutdownHook(new Thread(this::onShutdown, "Zerith ModSync shutdown"));
        if (!cfg.enabled) return;
        String problem = Config.urlProblem(url);
        if (problem != null) {
            log("Not synchronizing: " + problem);
            notifier.important("Zerith ModSync: " + problem);
            return;
        }
        Policy policy = cfg.policy();
        Downloader http = new Downloader(policy, cfg.connectTimeoutSeconds, cfg.downloadTimeoutSeconds, cfg.retryLimit, 1000);
        engine = new SyncEngine(game, cfg, url, policy, http, new SyncEngine.Listener() {
            @Override public void status(String m) { log(m); }
            @Override public void progress(int d, int t, long b, long tb) { onProgress(d, t, b, tb); }
        });
        long period = cfg.checkIntervalMinutes;
        scheduler.scheduleWithFixedDelay(this::check, cfg.checkOnStartup ? 5 : period, period, TimeUnit.MINUTES);
    }

    private void check() {
        try {
            notifier.overlay("Zerith ModSync: checking for updates...");
            SyncEngine.Result r = engine.runOnce();
            switch (r.outcome()) {
                case UP_TO_DATE -> { log("Up to date"); notifier.overlay("Zerith ModSync: modpack is up to date"); }
                case UPDATE_AVAILABLE -> important("Zerith ModSync: update available (automatic download is off).");
                case STAGED, ALREADY_PENDING -> {
                    important("Zerith ModSync: update downloaded and verified. Restart Minecraft to apply it.");
                    if (cfg.autoApplyAfterExit) launchWorker(null);
                }
                case CONFIG_ERROR -> important("Zerith ModSync: " + r.detail());
                case BUSY -> log("Skipped: " + r.detail());
            }
        } catch (Downloader.RateLimitedException e) {
            log("Rate limited: " + e.getMessage());
        } catch (IOException | RuntimeException e) {
            log("Check failed: " + e);
            notifier.overlay("Zerith ModSync: update check failed; your mods were not changed");
        }
    }

    private void onProgress(int done, int total, long bytes, long totalBytes) {
        long now = System.currentTimeMillis();
        if (now - lastProgressMillis < 1000 && done < total) return;
        lastProgressMillis = now;
        String pct = totalBytes > 0 ? " (" + Math.min(100, bytes * 100 / totalBytes) + "%)" : "";
        notifier.overlay("Zerith ModSync: downloading " + done + "/" + total + pct);
    }

    private void important(String msg) {
        if (cfg.showNotifications && !msg.equals(lastImportant)) { lastImportant = msg; notifier.important(msg); }
    }

    private void reportPreviousStatus() throws IOException {
        Path s = data.resolve(Installer.STATUS);
        if (!Files.exists(s)) return;
        Model.Status st = FileOps.readJson(s, Model.Status.class);
        notifier.important("SUCCESS".equals(st.state())
                ? "Zerith ModSync: modpack updated successfully."
                : "Zerith ModSync: the last update failed; your previous mods were kept (" + st.message() + ").");
        Files.move(s, data.resolve("status-acknowledged.json"), StandardCopyOption.REPLACE_EXISTING);
    }

    private void onShutdown() {
        try {
            if (cfg != null && cfg.autoApplyAfterExit && Files.exists(data.resolve(Installer.PENDING))) launchWorker(null);
        } catch (RuntimeException ignored) { }
    }

    /** Extracts the bundled worker to a content-addressed name so a running old worker never blocks replacement. */
    private Path deployWorker() throws IOException {
        try (InputStream in = ModSyncService.class.getResourceAsStream("/worker/worker.jar")) {
            if (in == null) throw new IOException("Bundled worker missing from mod JAR");
            byte[] bytes = in.readAllBytes();
            Path target = data.resolve("worker-" + FileOps.sha256(bytes).substring(0, 12) + ".jar");
            if (!Files.isRegularFile(target) || Files.size(target) != bytes.length) {
                Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
                Files.write(tmp, bytes);
                FileOps.move(tmp, target);
            }
            try (var s = Files.list(data)) {
                for (Path p : s.toList()) {
                    String n = p.getFileName().toString();
                    if (n.startsWith("worker-") && n.endsWith(".jar") && !p.equals(target)) { try { Files.deleteIfExists(p); } catch (IOException ignored) { } }
                }
            }
            return target;
        }
    }

    private void launchWorker(String flag) {
        if (flag == null && !workerLaunched.compareAndSet(false, true)) return;
        try {
            if (workerJar == null) return;
            String exe = System.getProperty("os.name").toLowerCase().contains("win") ? "javaw.exe" : "java";
            Path javaBin = Path.of(System.getProperty("java.home"), "bin", exe);
            var cmd = new java.util.ArrayList<String>(java.util.List.of(javaBin.toString(), "-jar", workerJar.toString()));
            if (flag != null) cmd.add(flag);
            cmd.add(data.toString());
            new ProcessBuilder(cmd).directory(game.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(data.resolve("logs/worker-stdout.log").toFile())).start();
            log("Worker launched" + (flag == null ? "" : " " + flag));
        } catch (IOException | RuntimeException e) {
            workerLaunched.set(false);
            log("Could not launch worker: " + e);
        }
    }

    private String buildDefaultUrl() {
        try (InputStream in = ModSyncService.class.getResourceAsStream("/zerith-modsync-build.properties")) {
            Properties p = new Properties();
            if (in != null) p.load(in);
            return p.getProperty("default.manifest.url", "");
        } catch (IOException e) { return ""; }
    }

    private synchronized void log(String msg) {
        try {
            Path f = data.resolve("logs/modsync.log");
            Files.createDirectories(f.getParent());
            if (Files.exists(f) && Files.size(f) > (1 << 20)) Files.move(f, data.resolve("logs/modsync.log.old"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(f, Instant.now() + " " + msg + System.lineSeparator(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    @Override public void close() {
        if (engine != null) engine.cancel();
        scheduler.shutdownNow();
    }
}
