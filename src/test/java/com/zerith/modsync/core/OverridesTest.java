package com.zerith.modsync.core;

import com.sun.net.httpserver.HttpServer;
import com.zerith.modsync.cli.Main;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OverridesTest {
    @TempDir Path tmp;
    static final class Crash extends Error { Crash(String m) { super(m); } }

    Path game, data, mods, ovSrc, zip;
    Model.Manifest manifest;
    Model.Overrides ov;
    Policy policy = T.loopbackPolicy();

    @BeforeEach void setUp() throws Exception {
        game = T.instance(tmp, "zerith-modsync", "v1");
        data = game.resolve(".zerith-modsync");
        mods = game.resolve("mods");
        ovSrc = tmp.resolve("ov");
        write(ovSrc.resolve("options.txt"), "fov:90");
        write(ovSrc.resolve("config/a.json"), "A1");
        write(ovSrc.resolve("config/sub/b.json"), "B1");
        write(ovSrc.resolve("shaderpacks/shader.zip"), "SHADER");
        write(ovSrc.resolve("resourcepacks/pack.zip"), "RP");
        zip = tmp.resolve("out/overrides-v1.zip");
        rebuild("http://127.0.0.1:1/overrides-v1.zip");
    }

    void rebuild(String url) throws IOException {
        Model.Overrides built = Main.buildOverrides(ovSrc, zip, "o", "r", "v1");
        ov = new Model.Overrides(built.filename(), url, built.sha256(), built.size(), built.files());
        manifest = new Model.Manifest(1, new Model.Pack("T", "1", "1.21.1", "fabric"),
                List.of(T.mod(mods.resolve("zerith-modsync.jar"), "zerith-modsync", "http://127.0.0.1:1/self.jar")), ov);
    }

    static void write(Path p, String s) throws IOException { Files.createDirectories(p.getParent()); Files.writeString(p, s); }
    String read(String rel) throws IOException { return Files.readString(game.resolve(rel)); }
    Installer inst() { return new Installer(data, T.FAST, policy); }

    /** Pending that applies `files` (default: all pending) and optionally also swaps mods. */
    Model.Pending pending(String tx, boolean swapMods) throws IOException {
        List<Model.OverrideFile> need = Overrides.pending(game, data, manifest);
        Path ovDir = data.resolve("staging-overrides").resolve(tx);
        Overrides.extract(zip, ov, need, ovDir);
        String json = FileOps.GSON.toJson(manifest);
        String staging = null;
        if (swapMods) {
            Path s = data.resolve("staging").resolve(tx);
            Files.createDirectories(s);
            Files.copy(mods.resolve("zerith-modsync.jar"), s.resolve("zerith-modsync.jar"));
            staging = s.toString();
        }
        Model.Pending p = new Model.Pending(tx, FileOps.sha256(json.getBytes(StandardCharsets.UTF_8)), staging, mods.toString(),
                data.resolve("backups").resolve("20260101-000000-" + tx).toString(), json, 2_000_000_000L, 3, true, true, ovDir.toString(), need);
        FileOps.writeJson(data.resolve(Installer.PENDING), p);
        return p;
    }

    // ---------------------------------------------------------------- validation
    @Test void validOverridesAccepted() { assertDoesNotThrow(() -> ManifestValidator.validate(manifest, policy)); }

    @Test void unsafeOrForbiddenPathsRejected() {
        for (String bad : List.of("../x", "mods/evil.jar", "saves/world/level.dat", "config/../../x", "config//a.json", "/etc/passwd", "config\\a.json",
                "C:/x", "config/a.json ", "config/CON", "config/con.txt", "config/zerith-modsync.json", "Config/ZERITH-MODSYNC.JSON", "options.txt/x",
                "config", "resourcepacks", ".minecraft/x", "config/.."))
            assertNotNull(ManifestValidator.checkOverridePath(bad), bad);
        for (String ok : List.of("options.txt", "config/a.json", "config/sub dir/b (1).toml", "shaderpacks/Complementary Shaders v4.zip", "resourcepacks/Faithful 32x.zip"))
            assertNull(ManifestValidator.checkOverridePath(ok), ok);
    }

    @Test void badOverridesBlockRejected() {
        Model.OverrideFile f = ov.files().get(0);
        java.util.function.Function<Model.Overrides, Model.Manifest> mf = o -> new Model.Manifest(1, manifest.modpack(), manifest.mods(), o);
        assertThrows(IllegalArgumentException.class, () -> ManifestValidator.validate(mf.apply(new Model.Overrides("x.jar", ov.url(), ov.sha256(), ov.size(), ov.files())), policy));
        assertThrows(IllegalArgumentException.class, () -> ManifestValidator.validate(mf.apply(new Model.Overrides(ov.filename(), "https://evil.example/o.zip", ov.sha256(), ov.size(), ov.files())), Policy.defaults()));
        assertThrows(IllegalArgumentException.class, () -> ManifestValidator.validate(mf.apply(new Model.Overrides(ov.filename(), ov.url(), "zz", ov.size(), ov.files())), policy));
        assertThrows(IllegalArgumentException.class, () -> ManifestValidator.validate(mf.apply(new Model.Overrides(ov.filename(), ov.url(), ov.sha256(), 0, ov.files())), policy));
        assertThrows(IllegalArgumentException.class, () -> ManifestValidator.validate(mf.apply(new Model.Overrides(ov.filename(), ov.url(), ov.sha256(), ov.size(), List.of())), policy));
        assertThrows(IllegalArgumentException.class, () -> ManifestValidator.validate(mf.apply(new Model.Overrides(ov.filename(), ov.url(), ov.sha256(), ov.size(),
                List.of(f, new Model.OverrideFile(f.path().toUpperCase(), f.sha256(), f.size())))), policy));
        assertThrows(IllegalArgumentException.class, () -> ManifestValidator.validate(mf.apply(new Model.Overrides(ov.filename(), ov.url(), ov.sha256(), ov.size(),
                List.of(new Model.OverrideFile("mods/x.jar", f.sha256(), 1)))), policy));
    }

    @Test void generatorIsDeterministicAndRejectsForbiddenContent() throws Exception {
        Path z2 = tmp.resolve("out2/overrides-v1.zip");
        Model.Overrides a = Main.buildOverrides(ovSrc, zip, "o", "r", "v1");
        Model.Overrides b = Main.buildOverrides(ovSrc, z2, "o", "r", "v1");
        assertEquals(a.sha256(), b.sha256(), "same input, same archive bytes");
        assertEquals("https://github.com/o/r/releases/download/v1/overrides-v1.zip", a.url());
        write(ovSrc.resolve("mods/evil.jar"), "x");
        assertThrows(IOException.class, () -> Main.buildOverrides(ovSrc, z2, "o", "r", "v1"));
        Files.delete(ovSrc.resolve("mods/evil.jar")); Files.delete(ovSrc.resolve("mods"));
        write(ovSrc.resolve("config/zerith-modsync.json"), "{}");
        assertThrows(IOException.class, () -> Main.buildOverrides(ovSrc, z2, "o", "r", "v1"));
    }

    // ---------------------------------------------------------------- pending detection
    @Test void firstRunAppliesEverythingThenNothingUntilAuthorChanges() throws Exception {
        assertEquals(5, Overrides.pending(game, data, manifest).size());
        inst().install(pending("tx-ov000001", false));
        assertEquals("A1", read("config/a.json"));
        assertEquals("fov:90", read("options.txt"));
        assertEquals("SHADER", read("shaderpacks/shader.zip"));
        assertTrue(Overrides.pending(game, data, manifest).isEmpty());
        // player customizes; author did not change the file -> player's version stays and nothing is pending
        write(game.resolve("options.txt"), "fov:110");
        write(game.resolve("config/a.json"), "my tweak");
        assertTrue(Overrides.pending(game, data, manifest).isEmpty());
        // author changes only config/a.json
        write(ovSrc.resolve("config/a.json"), "A2");
        rebuild(ov.url());
        List<Model.OverrideFile> p = Overrides.pending(game, data, manifest);
        assertEquals(List.of("config/a.json"), p.stream().map(Model.OverrideFile::path).toList());
        inst().install(pending("tx-ov000002", false));
        assertEquals("A2", read("config/a.json"));
        assertEquals("fov:110", read("options.txt"), "options.txt untouched because the author did not change it");
    }

    @Test void identicalLocalFileIsNotPending() throws Exception {
        write(game.resolve("options.txt"), "fov:90");
        assertEquals(4, Overrides.pending(game, data, manifest).size());
    }

    // ---------------------------------------------------------------- extraction
    @Test void extractionRejectsTamperedExtraAndMissingEntries() throws Exception {
        List<Model.OverrideFile> all = ov.files();
        // hash mismatch: manifest claims different bytes than the archive holds
        Model.Overrides lie = new Model.Overrides(ov.filename(), ov.url(), ov.sha256(), ov.size(),
                all.stream().map(f -> f.path().equals("config/a.json") ? new Model.OverrideFile(f.path(), "0".repeat(64), f.size()) : f).toList());
        assertThrows(IOException.class, () -> Overrides.extract(zip, lie, lie.files(), tmp.resolve("x1")));
        // archive has an entry the manifest does not list
        Model.Overrides fewer = new Model.Overrides(ov.filename(), ov.url(), ov.sha256(), ov.size(), all.subList(1, all.size()));
        assertThrows(IOException.class, () -> Overrides.extract(zip, fewer, fewer.files(), tmp.resolve("x2")));
        // manifest lists a file the archive lacks
        Model.Overrides more = new Model.Overrides(ov.filename(), ov.url(), ov.sha256(), ov.size(),
                concat(all, new Model.OverrideFile("config/ghost.json", "a".repeat(64), 1)));
        assertThrows(IOException.class, () -> Overrides.extract(zip, more, more.files(), tmp.resolve("x3")));
        // not a zip
        Path junk = tmp.resolve("junk.zip");
        Files.writeString(junk, "garbage");
        assertThrows(IOException.class, () -> Overrides.extract(junk, ov, all, tmp.resolve("x4")));
        // zip-slip entry is unlisted, hence rejected
        Path evil = tmp.resolve("evil.zip");
        try (var z = new java.util.zip.ZipOutputStream(Files.newOutputStream(evil))) { z.putNextEntry(new java.util.zip.ZipEntry("../../evil.txt")); z.write(1); }
        assertThrows(IOException.class, () -> Overrides.extract(evil, ov, all, tmp.resolve("x5")));
        assertFalse(Files.exists(tmp.resolve("evil.txt")));
    }

    static <T> List<T> concat(List<T> a, T b) { List<T> l = new ArrayList<>(a); l.add(b); return l; }

    // ---------------------------------------------------------------- installer
    @Test void swapPlusOverridesBacksUpOverwrittenFiles() throws Exception {
        write(game.resolve("config/a.json"), "player original");
        Model.Pending p = pending("tx-ov000003", true);
        inst().install(p);
        assertEquals("A1", read("config/a.json"));
        assertEquals("player original", Files.readString(data.resolve("backups-overrides/tx-ov000003/config/a.json")));
        assertEquals("SUCCESS", FileOps.readJson(data.resolve(Installer.STATUS), Model.Status.class).state());
        assertFalse(Files.exists(data.resolve("staging-overrides/tx-ov000003")));
        assertFalse(Files.exists(data.resolve(Installer.JOURNAL)));
    }

    @Test void overridesOnlyUpdateLeavesModsAlone() throws Exception {
        byte[] before = Files.readAllBytes(mods.resolve("zerith-modsync.jar"));
        List<String> phases = new ArrayList<>();
        inst().hook(phases::add).install(pending("tx-ov000004", false));
        assertEquals(List.of("PLANNED", "OVERRIDES"), phases);
        assertArrayEquals(before, Files.readAllBytes(mods.resolve("zerith-modsync.jar")));
        assertFalse(Files.exists(data.resolve("backups")), "no mods backup for overrides-only update");
        assertEquals("B1", read("config/sub/b.json"));
    }

    @Test void failureWhileApplyingRestoresEverything() throws Exception {
        write(game.resolve("config/a.json"), "player original");
        Files.createDirectories(game.resolve("shaderpacks/shader.zip"));          // a directory where a file must go -> apply fails midway
        Model.Pending p = pending("tx-ov000005", true);
        assertThrows(IOException.class, () -> inst().install(p));
        assertEquals("player original", read("config/a.json"), "overwritten file restored");
        assertFalse(Files.exists(game.resolve("options.txt")), "file created by the failed attempt removed");
        assertFalse(Files.exists(game.resolve("config/sub/b.json")));
        assertTrue(Files.isDirectory(game.resolve("shaderpacks/shader.zip")));
        assertEquals(Set.of("zerith-modsync.jar"), names(mods));
        assertFalse(Files.exists(data.resolve(Installer.JOURNAL)));
        assertTrue(Overrides.loadApplied(data).isEmpty(), "failed update must not be recorded as applied");
        assertEquals("FAILED", FileOps.readJson(data.resolve(Installer.STATUS), Model.Status.class).state());
    }

    @Test void recoveryAfterCrashAtOverridesPhaseFinishesTheJob() throws Exception {
        write(game.resolve("config/a.json"), "player original");
        Model.Pending p = pending("tx-ov000007", false);
        assertThrows(Crash.class, () -> inst().hook(ph -> { if (ph.equals("OVERRIDES")) throw new Crash(ph); }).install(p));
        assertTrue(Files.exists(data.resolve(Installer.JOURNAL)));
        assertEquals("player original", read("config/a.json"), "crash happened before any file moved");
        assertTrue(inst().recover());
        assertEquals("A1", read("config/a.json"));
        assertEquals("fov:90", read("options.txt"));
        assertTrue(Overrides.pending(game, data, manifest).isEmpty());
        assertFalse(Files.exists(data.resolve(Installer.JOURNAL)));
    }

    @Test void recoveryResumesHalfAppliedOverrides() throws Exception {
        write(game.resolve("config/a.json"), "player original");
        Model.Pending p = pending("tx-ov000008", false);
        assertThrows(Crash.class, () -> inst().hook(ph -> { if (ph.equals("OVERRIDES")) throw new Crash(ph); }).install(p));
        // emulate: config/a.json already moved to backup and the new one in place; options.txt not yet
        Path bk = data.resolve("backups-overrides/tx-ov000008/config/a.json");
        Files.createDirectories(bk.getParent());
        Files.move(game.resolve("config/a.json"), bk);
        Files.move(data.resolve("staging-overrides/tx-ov000008/config/a.json"), game.resolve("config/a.json"));
        assertTrue(inst().recover());
        assertEquals("A1", read("config/a.json"));
        assertEquals("fov:90", read("options.txt"));
        assertEquals("player original", Files.readString(bk), "backup of the original kept");
    }

    @Test void pendingOverrideNotInManifestOrEscapingIsRejected() throws Exception {
        Model.Pending p = pending("tx-ov000009", false);
        List<Model.OverrideFile> evil = new ArrayList<>(p.overrideFiles());
        evil.add(new Model.OverrideFile("config/not-listed.json", "b".repeat(64), 1));
        assertThrows(IOException.class, () -> inst().install(withOverrides(p, evil)));
        List<Model.OverrideFile> esc = List.of(new Model.OverrideFile("config/../../escape.txt", "b".repeat(64), 1));
        assertThrows(IOException.class, () -> inst().install(withOverrides(p, esc)));
        List<Model.OverrideFile> mod = List.of(new Model.OverrideFile("mods/evil.jar", "b".repeat(64), 1));
        assertThrows(IOException.class, () -> inst().install(withOverrides(p, mod)));
        assertFalse(Files.exists(game.resolve("config/not-listed.json")));
        assertFalse(Files.exists(tmp.resolve("escape.txt")));
    }

    Model.Pending withOverrides(Model.Pending p, List<Model.OverrideFile> files) {
        return new Model.Pending(p.transactionId(), p.manifestSha256(), p.stagingDir(), p.modsDir(), p.backupDir(), p.manifestJson(),
                p.parentPid(), p.maxBackups(), p.createBackup(), p.strict(), p.overridesDir(), files);
    }

    @Test void refusesToWriteThroughLinkedDirectory() throws Exception {
        Path real = tmp.resolve("elsewhere");
        Files.createDirectories(real);
        Assumptions.assumeTrue(T.link(game.resolve("config"), real), "neither symlinks nor junctions available");
        Model.Pending p = pending("tx-ov000010", false);
        assertThrows(IOException.class, () -> inst().install(p));
        try (var s = Files.list(real)) { assertEquals(0, s.count(), "nothing written through the link"); }
    }

    @Test void ownConfigFileIsNeverTouchedByOverrides() throws Exception {
        write(game.resolve("config/zerith-modsync.json"), "{\"enabled\":true}");
        inst().install(pending("tx-ov000011", false));
        assertEquals("{\"enabled\":true}", read("config/zerith-modsync.json"));
    }

    Set<String> names(Path d) throws IOException { try (var s = Files.list(d)) { return new TreeSet<>(s.map(x -> x.getFileName().toString()).toList()); } }

    // ---------------------------------------------------------------- engine end-to-end
    @Test void engineStagesAndAppliesOverridesWithoutRedownloadingMods() throws Exception {
        HttpServer server = T.server();
        try {
            String base = T.base(server);
            AtomicInteger zipHits = new AtomicInteger(), jarHits = new AtomicInteger();
            byte[][] zipBytes = {Files.readAllBytes(zip)};
            String[] json = {null};
            rebuild(base + "/ov.zip");
            json[0] = FileOps.GSON.toJson(manifest);
            server.createContext("/modpack.json", ex -> { byte[] b = json[0].getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
            server.createContext("/ov.zip", ex -> { zipHits.incrementAndGet(); ex.sendResponseHeaders(200, zipBytes[0].length); ex.getResponseBody().write(zipBytes[0]); ex.close(); });
            server.createContext("/self.jar", ex -> { jarHits.incrementAndGet(); ex.sendResponseHeaders(500, -1); ex.close(); });
            SyncEngine engine = new SyncEngine(game, new Config(), base + "/modpack.json", policy, T.downloader(policy), new SyncEngine.Listener() { });

            var r = engine.runOnce();
            assertEquals(SyncEngine.Outcome.STAGED, r.outcome());
            assertEquals(0, jarHits.get(), "mods already match: no JAR is downloaded");
            Model.Pending p = FileOps.readJson(data.resolve(Installer.PENDING), Model.Pending.class);
            assertNull(p.stagingDir());
            assertEquals(5, p.overrideFiles().size());
            assertEquals(SyncEngine.Outcome.ALREADY_PENDING, engine.runOnce().outcome());
            assertEquals(1, zipHits.get());

            inst().install(p);
            assertEquals("B1", read("config/sub/b.json"));
            assertEquals(SyncEngine.Outcome.UP_TO_DATE, engine.runOnce().outcome());
            assertEquals(1, zipHits.get(), "nothing new, nothing downloaded");

            // author edits one file -> only that file is staged
            write(ovSrc.resolve("config/sub/b.json"), "B2");
            rebuild(base + "/ov.zip");
            zipBytes[0] = Files.readAllBytes(zip);
            json[0] = FileOps.GSON.toJson(manifest);
            assertEquals(SyncEngine.Outcome.STAGED, engine.runOnce().outcome());
            Model.Pending p2 = FileOps.readJson(data.resolve(Installer.PENDING), Model.Pending.class);
            assertEquals(List.of("config/sub/b.json"), p2.overrideFiles().stream().map(Model.OverrideFile::path).toList());
            inst().install(p2);
            assertEquals("B2", read("config/sub/b.json"));
            assertEquals("A1", read("config/a.json"));
        } finally { server.stop(0); }
    }

    @Test void engineRejectsTamperedOverridesArchive() throws Exception {
        HttpServer server = T.server();
        try {
            String base = T.base(server);
            rebuild(base + "/ov.zip");
            String json = FileOps.GSON.toJson(manifest);
            byte[] bad = "not the archive".getBytes();
            server.createContext("/modpack.json", ex -> { byte[] b = json.getBytes(); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
            server.createContext("/ov.zip", ex -> { ex.sendResponseHeaders(200, bad.length); ex.getResponseBody().write(bad); ex.close(); });
            SyncEngine engine = new SyncEngine(game, new Config(), base + "/modpack.json", policy, T.downloader(policy), new SyncEngine.Listener() { });
            assertThrows(IOException.class, engine::runOnce);
            assertFalse(Files.exists(data.resolve(Installer.PENDING)));
            assertFalse(Files.exists(game.resolve("options.txt")));
        } finally { server.stop(0); }
    }
}
