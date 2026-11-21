package com.zerith.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** User configuration (config/zerith-modsync.json). Empty manifestUrl means "use the build-time default". */
public final class Config {
    public boolean enabled = true;
    public String manifestUrl = "";
    public boolean checkOnStartup = true;
    public int checkIntervalMinutes = 15;
    public boolean autoDownload = true;
    public boolean autoApplyAfterExit = true;
    public boolean strictSynchronization = true;
    public boolean verifyHashes = true;
    public boolean createBackup = true;
    public int maxBackups = 3;
    public boolean showNotifications = true;
    public int connectTimeoutSeconds = 10;
    public int downloadTimeoutSeconds = 120;
    public int retryLimit = 3;
    public int maxConcurrentDownloads = 3;
    public List<String> allowedHosts = new ArrayList<>(Policy.GITHUB_HOSTS.stream().sorted().toList());

    public static Config load(Path file) throws IOException {
        if (!Files.exists(file)) {
            Config c = new Config();
            FileOps.writeJson(file, c);
            return c;
        }
        Config c = FileOps.readJson(file, Config.class);
        c.validate();
        return c;
    }

    public void validate() {
        if (checkIntervalMinutes < 5 || checkIntervalMinutes > 1440) throw new IllegalArgumentException("checkIntervalMinutes must be 5..1440");
        if (maxBackups < 1 || maxBackups > 50) throw new IllegalArgumentException("maxBackups must be 1..50");
        if (connectTimeoutSeconds < 1 || connectTimeoutSeconds > 120 || downloadTimeoutSeconds < 5 || downloadTimeoutSeconds > 3600)
            throw new IllegalArgumentException("Timeouts out of range");
        if (retryLimit < 0 || retryLimit > 10) throw new IllegalArgumentException("retryLimit must be 0..10");
        if (maxConcurrentDownloads < 1 || maxConcurrentDownloads > 6) throw new IllegalArgumentException("maxConcurrentDownloads must be 1..6");
        if (!verifyHashes) throw new IllegalArgumentException("verifyHashes=false is not supported: hash verification is mandatory");
        if (allowedHosts == null || allowedHosts.isEmpty()) throw new IllegalArgumentException("allowedHosts must not be empty");
    }

    public String effectiveUrl(String buildDefault) {
        return manifestUrl == null || manifestUrl.isBlank() ? buildDefault : manifestUrl.trim();
    }

    /** Returns a human-readable problem (placeholder URL etc.) or null when synchronization may proceed. */
    public static String urlProblem(String url) {
        if (url == null || url.isBlank()) return "No manifest URL configured";
        String u = url.toUpperCase(Locale.ROOT);
        if (u.contains("OWNER") || u.contains("YOUR_USERNAME") || u.contains("REPLACE_ME")) return "Manifest URL still contains a placeholder (OWNER). Set manifestUrl in config/zerith-modsync.json or rebuild with -PghOwner=<github user>";
        return null;
    }

    public Policy policy() {
        return new Policy("1.21.1", "fabric", new HashSet<>(allowedHosts.stream().map(s -> s.toLowerCase(Locale.ROOT)).toList()),
                512L << 20, 4L << 30, false);
    }
}
