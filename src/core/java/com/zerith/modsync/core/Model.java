package com.zerith.modsync.core;

import java.util.List;

/** Plain data records shared by the mod, the worker and the manifest CLI. */
public final class Model {
    private Model() {}

    public record Pack(String name, String version, String minecraftVersion, String loader) {}

    public record Mod(String id, String filename, String url, String sha256, Long size) {}

    public record Manifest(int schemaVersion, Pack modpack, List<Mod> mods) {}

    /** A fully staged update waiting for the worker. Paths are re-validated by the worker. */
    public record Pending(String transactionId, String manifestSha256, String stagingDir, String modsDir,
                          String backupDir, String manifestJson, long parentPid, int maxBackups,
                          boolean createBackup, boolean strict) {}

    /** Durable record of how far a swap progressed. */
    public record Journal(String phase, boolean hadMods, Pending tx) {}

    public record Status(String transactionId, String state, String message, long timestamp) {}
}
