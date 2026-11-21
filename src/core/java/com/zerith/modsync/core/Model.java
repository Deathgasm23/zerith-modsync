package com.zerith.modsync.core;

import java.util.List;

/** Plain data records shared by the mod, the worker and the manifest CLI. */
public final class Model {
    private Model() {}

    public record Pack(String name, String version, String minecraftVersion, String loader) {}

    public record Mod(String id, String filename, String url, String sha256, Long size) {}

    /** One file of the overrides archive, path relative to the game directory (forward slashes). */
    public record OverrideFile(String path, String sha256, long size) {}

    /** config/, options.txt, resourcepacks/, shaderpacks/ content, shipped as one zip release asset. */
    public record Overrides(String filename, String url, String sha256, long size, List<OverrideFile> files) {}

    public record Manifest(int schemaVersion, Pack modpack, List<Mod> mods, Overrides overrides) {
        public Manifest(int schemaVersion, Pack modpack, List<Mod> mods) { this(schemaVersion, modpack, mods, null); }
    }

    /**
     * A fully staged update waiting for the worker. stagingDir == null means "do not swap mods"
     * (overrides-only update). overridesDir/overrides may be null/empty. Paths are re-validated by the worker.
     */
    public record Pending(String transactionId, String manifestSha256, String stagingDir, String modsDir,
                          String backupDir, String manifestJson, long parentPid, int maxBackups,
                          boolean createBackup, boolean strict, String overridesDir, List<OverrideFile> overrides) {
        public Pending(String transactionId, String manifestSha256, String stagingDir, String modsDir,
                       String backupDir, String manifestJson, long parentPid, int maxBackups,
                       boolean createBackup, boolean strict) {
            this(transactionId, manifestSha256, stagingDir, modsDir, backupDir, manifestJson, parentPid,
                    maxBackups, createBackup, strict, null, List.of());
        }
        public boolean swapsMods() { return stagingDir != null; }
        public List<OverrideFile> overrideFiles() { return overrides == null ? List.of() : overrides; }
    }

    /** Durable record of how far a swap progressed. */
    public record Journal(String phase, boolean hadMods, Pending tx) {}

    public record Status(String transactionId, String state, String message, long timestamp) {}

    /** Last applied sha256 per overrides path (data/applied-files.json). */
    public record Applied(java.util.Map<String, String> files) {}
}
