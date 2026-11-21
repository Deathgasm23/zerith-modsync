package com.zerith.modsync.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class ManifestAndScannerTest {
    @TempDir Path tmp;
    static final String URL = "https://github.com/o/r/releases/download/v1/";
    final Policy policy = Policy.defaults();

    Model.Mod m(String id, String file, String sha) { return new Model.Mod(id, file, URL + file, sha, 10L); }
    String h(char c) { return String.valueOf(c).repeat(64); }
    Model.Manifest mf(Model.Mod... mods) { return T.manifest("1", mods); }
    Model.Mod self() { return m("zerith-modsync", "zerith-modsync-1.0.0.jar", h('a')); }
    void bad(Model.Manifest m) { assertThrows(IllegalArgumentException.class, () -> ManifestValidator.validate(m, policy)); }

    // ---------- manifest validation ----------
    @Test void validManifest() { assertDoesNotThrow(() -> ManifestValidator.validate(mf(self(), m("b", "b.jar", h('b'))), policy)); }
    @Test void emptyManifestFailsClosed() { bad(mf()); }
    @Test void missingUpdaterRejected() { bad(mf(m("b", "b.jar", h('b')))); }
    @Test void duplicateIdRejected() { bad(mf(self(), m("b", "b.jar", h('b')), m("b", "c.jar", h('c')))); }
    @Test void duplicateFilenameCaseInsensitiveRejected() { bad(mf(self(), m("b", "b.jar", h('b')), m("c", "B.JAR".replace("JAR", "jar"), h('c')))); }
    @Test void malformedHashRejected() { bad(mf(self(), m("b", "b.jar", "xyz"))); bad(mf(self(), m("b", "b.jar", h('g')))); }
    @Test void traversalAndOddFilenamesRejected() {
        for (String f : List.of("../x.jar", "a/b.jar", "a\\b.jar", "x y.jar", "x.txt", "..jar", "C:evil.jar"))
            bad(mf(self(), m("b", f, h('b'))));
    }
    @Test void wrongLoaderOrMinecraftRejected() {
        bad(new Model.Manifest(1, new Model.Pack("n", "1", "1.21.1", "forge"), List.of(self())));
        bad(new Model.Manifest(1, new Model.Pack("n", "1", "1.20.1", "fabric"), List.of(self())));
        bad(new Model.Manifest(2, new Model.Pack("n", "1", "1.21.1", "fabric"), List.of(self())));
    }
    @Test void nonHttpsOrUntrustedHostRejected() {
        bad(mf(new Model.Mod("zerith-modsync", "a.jar", "http://github.com/a.jar", h('a'), 1L)));
        bad(mf(new Model.Mod("zerith-modsync", "a.jar", "https://evil.example/a.jar", h('a'), 1L)));
        bad(mf(new Model.Mod("zerith-modsync", "a.jar", "https://user:pw@github.com/a.jar", h('a'), 1L)));
        bad(mf(new Model.Mod("zerith-modsync", "a.jar", "file:///etc/passwd", h('a'), 1L)));
    }
    @Test void oversizedPackRejected() {
        bad(mf(new Model.Mod("zerith-modsync", "a.jar", URL + "a.jar", h('a'), policy.maxJarBytes() + 1)));
    }

    // ---------- local scanner / change detection ----------
    Path mods() { return tmp.resolve("mods"); }

    @Test void identicalFolderIsUpToDate() throws Exception {
        Path a = T.jar(mods(), "a.jar", "a", "1");
        assertTrue(new LocalScanner().diff(mods(), mf(T.mod(a, "a", URL + "a.jar"))).isEmpty());
    }
    @Test void detectsMissingFileAndMissingFolder() throws Exception {
        Path src = T.jar(tmp.resolve("src"), "a.jar", "a", "1");
        Model.Manifest m = mf(T.mod(src, "a", URL + "a.jar"));
        assertEquals(List.of("a.jar"), new LocalScanner().diff(mods(), m).missing());
        Files.createDirectories(mods());
        assertEquals(List.of("a.jar"), new LocalScanner().diff(mods(), m).missing());
    }
    @Test void detectsExtraFilesAndDirectories() throws Exception {
        Path a = T.jar(mods(), "a.jar", "a", "1");
        T.jar(mods(), "unauthorized.jar", "x", "1");
        Files.createDirectories(mods().resolve("subdir"));
        Files.writeString(mods().resolve("notes.txt"), "hi");
        var d = new LocalScanner().diff(mods(), mf(T.mod(a, "a", URL + "a.jar")));
        assertEquals(List.of("notes.txt", "subdir", "unauthorized.jar"), d.extra());
        assertTrue(d.needsUpdate(true));
        assertFalse(d.needsUpdate(false), "non-strict mode tolerates extras");
    }
    @Test void detectsRenameByContent() throws Exception {
        Path src = T.jar(tmp.resolve("src"), "new-name.jar", "a", "1");
        Files.copy(src, T.jar(mods(), "tmp.jar", "a", "1").resolveSibling("old-name.jar"));
        Files.delete(mods().resolve("tmp.jar"));
        var d = new LocalScanner().diff(mods(), mf(T.mod(src, "a", URL + "new-name.jar")));
        assertEquals(List.of("old-name.jar -> new-name.jar"), d.renamed());
        assertTrue(d.missing().isEmpty() && d.extra().isEmpty());
        assertTrue(d.needsUpdate(true));
    }
    @Test void detectsSameNameDifferentBytesWithoutVersionChange() throws Exception {
        Path a = T.jar(mods(), "a.jar", "a", "official");
        Model.Manifest m = mf(T.mod(a, "a", URL + "a.jar"));
        LocalScanner sc = new LocalScanner();
        assertTrue(sc.diff(mods(), m).isEmpty());
        T.jar(mods(), "a.jar", "a", "tampered-with-longer-payload");   // same name, same manifest version string
        assertEquals(List.of("a.jar"), sc.diff(mods(), m).modified());
    }
    @Test void detectsCorruptJar() throws Exception {
        Path a = T.jar(mods(), "a.jar", "a", "1");
        Model.Manifest m = mf(T.mod(a, "a", URL + "a.jar"));
        byte[] b = Files.readAllBytes(a);
        b[b.length / 2] ^= 0x55;
        Files.write(a, b);
        assertEquals(List.of("a.jar"), new LocalScanner().diff(mods(), m).modified());
    }
    @Test void symlinkedModsDirectoryIsRejected() throws Exception {
        Path real = tmp.resolve("real");
        Files.createDirectories(real);
        Path link = tmp.resolve("link");
        Assumptions.assumeTrue(T.link(link, real), "neither symlinks nor junctions available");
        assertThrows(java.io.IOException.class, () -> new LocalScanner().diff(link, mf(self())));
    }

    // ---------- jar inspection ----------
    @Test void jarInspectorReadsIdAndRejectsNonFabric() throws Exception {
        Path a = T.jar(tmp, "a.jar", "alpha", "1");
        assertEquals("alpha", JarInspector.inspect(a).id());
        Path plain = tmp.resolve("plain.jar");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(plain))) { z.putNextEntry(new java.util.zip.ZipEntry("x.txt")); z.write(1); }
        assertThrows(java.io.IOException.class, () -> JarInspector.inspect(plain));
        Path junk = tmp.resolve("junk.jar");
        Files.writeString(junk, "not a zip");
        assertThrows(java.io.IOException.class, () -> JarInspector.inspect(junk));
    }
    @Test void zipSlipEntryRejected() throws Exception {
        Path evil = tmp.resolve("evil.jar");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(evil))) {
            z.putNextEntry(new java.util.zip.ZipEntry("fabric.mod.json")); z.write("{\"id\":\"evil\"}".getBytes());
            z.putNextEntry(new java.util.zip.ZipEntry("../../x")); z.write(1);
        }
        assertThrows(java.io.IOException.class, () -> JarInspector.inspect(evil));
    }
    @Test void minecraftRangeMatching() {
        var p = (java.util.function.BiFunction<String, String, Boolean>) JarInspector::satisfies;
        assertTrue(p.apply("1.21.x", "1.21.1")); assertTrue(p.apply(">=1.21", "1.21.1")); assertTrue(p.apply("~1.21", "1.21.1"));
        assertTrue(p.apply(">=1.21 <1.22", "1.21.1")); assertTrue(p.apply("*", "1.21.1")); assertTrue(p.apply("1.21.1", "1.21.1"));
        assertFalse(p.apply("1.20.x", "1.21.1")); assertFalse(p.apply(">=1.22", "1.21.1")); assertFalse(p.apply("<1.21", "1.21.1"));
        assertNull(p.apply("weird!", "1.21.1"));
    }

    // ---------- config ----------
    @Test void configDefaultsAndValidation() throws Exception {
        Path f = tmp.resolve("config/zerith-modsync.json");
        Config c = Config.load(f);
        assertEquals(15, c.checkIntervalMinutes);
        assertTrue(c.strictSynchronization && c.verifyHashes && c.createBackup);
        assertEquals(3, c.maxBackups);
        assertDoesNotThrow(() -> Config.load(f));                        // round-trips
        c.checkIntervalMinutes = 1; assertThrows(IllegalArgumentException.class, c::validate);
        c = new Config(); c.verifyHashes = false; assertThrows(IllegalArgumentException.class, c::validate);
        c = new Config(); c.maxConcurrentDownloads = 99; assertThrows(IllegalArgumentException.class, c::validate);
    }
    @Test void placeholderUrlBlocksSync() {
        assertNotNull(Config.urlProblem("https://raw.githubusercontent.com/OWNER/zerith-modsync/main/modpack/modpack.json"));
        assertNotNull(Config.urlProblem(""));
        assertNull(Config.urlProblem("https://raw.githubusercontent.com/alice/zerith-modsync/main/modpack/modpack.json"));
        assertEquals("https://x/y", new Config().effectiveUrl("https://x/y"));
    }
}
