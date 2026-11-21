package com.zerith.modsync.core;

import com.zerith.modsync.cli.Main;
import com.zerith.modsync.worker.WorkerMain;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class WorkerAndCliTest {
    @TempDir Path tmp;

    // ---------------- worker ----------------
    Path game, data, mods, src;
    Model.Manifest manifest;

    void workerSetup() throws Exception {
        game = T.instance(tmp, "zerith-modsync", "v1", "old", "x");
        data = game.resolve(".zerith-modsync");
        mods = game.resolve("mods");
        src = tmp.resolve("src");
        Path self = T.jar(src, "zerith-modsync-2.jar", "zerith-modsync", "v2");
        Path fresh = T.jar(src, "fresh.jar", "fresh", "n");
        manifest = T.manifest("2", T.mod(self, "zerith-modsync", "https://github.com/o/r/a"), T.mod(fresh, "fresh", "https://github.com/o/r/b"));
    }
    Set<String> names(Path d) throws IOException { try (var s = Files.list(d)) { return new TreeSet<>(s.map(p -> p.getFileName().toString()).toList()); } }
    Model.Pending pend(String tx, long pid) throws IOException {
        Model.Pending b = T.stage(game, tx, manifest, src, true);
        Model.Pending p = new Model.Pending(b.transactionId(), b.manifestSha256(), b.stagingDir(), b.modsDir(), b.backupDir(), b.manifestJson(), pid, 3, true, true);
        FileOps.writeJson(data.resolve(Installer.PENDING), p);
        return p;
    }

    @Test void workerAppliesPendingUpdateIncludingSelfUpdate() throws Exception {
        workerSetup();
        pend("tx-w0000001", 2_000_000_000L);
        assertEquals(0, WorkerMain.run(new String[]{data.toString()}, T.FAST));
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods));
        assertFalse(Files.exists(data.resolve(Installer.PENDING)));
    }

    @Test void workerWaitsForGameProcessToExit() throws Exception {
        workerSetup();
        boolean win = System.getProperty("os.name").toLowerCase().contains("win");
        Process game = new ProcessBuilder(win ? List.of("powershell", "-NoProfile", "-Command", "Start-Sleep -Seconds 4") : List.of("sleep", "4")).start();
        pend("tx-w0000002", game.pid());
        ExecutorService ex = Executors.newSingleThreadExecutor();
        Future<Integer> f = ex.submit(() -> WorkerMain.run(new String[]{data.toString()}, T.FAST));
        Thread.sleep(1000);
        assertFalse(f.isDone(), "worker must still be waiting");
        assertEquals(Set.of("old.jar", "zerith-modsync.jar"), names(mods), "mods untouched while the game runs");
        game.destroyForcibly().waitFor();
        assertEquals(0, f.get(30, TimeUnit.SECONDS));
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods));
        ex.shutdownNow();
    }

    @Test void concurrentWorkersApplyExactlyOnce() throws Exception {
        workerSetup();
        pend("tx-w0000003", 2_000_000_000L);
        ExecutorService ex = Executors.newFixedThreadPool(3);
        List<Future<Integer>> fs = new ArrayList<>();
        for (int i = 0; i < 3; i++) fs.add(ex.submit(() -> WorkerMain.run(new String[]{data.toString()}, T.FAST)));
        for (Future<Integer> f : fs) assertEquals(0, f.get(60, TimeUnit.SECONDS));
        ex.shutdownNow();
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods));
        LocalScanner.verify(mods, manifest, true);
        try (var s = Files.list(data.resolve("backups"))) { assertEquals(1, s.count(), "one swap, one backup"); }
    }

    @Test void workerFailureKeepsOldModsAndReportsStatus() throws Exception {
        workerSetup();
        Model.Pending p = pend("tx-w0000004", 2_000_000_000L);
        Files.writeString(Path.of(p.stagingDir()).resolve("fresh.jar"), "corrupted after verification");
        assertEquals(2, WorkerMain.run(new String[]{data.toString()}, T.FAST));
        assertEquals(Set.of("old.jar", "zerith-modsync.jar"), names(mods));
        assertEquals("FAILED", FileOps.readJson(data.resolve(Installer.STATUS), Model.Status.class).state());
        assertFalse(Files.exists(data.resolve(Installer.PENDING)), "broken transaction is not retried forever");
    }

    @Test void workerRecoverModeFinishesInterruptedTransaction() throws Exception {
        workerSetup();
        Model.Pending p = pend("tx-w0000005", 2_000_000_000L);
        assertThrows(Error.class, () -> new Installer(data, T.FAST).hook(ph -> { if (ph.equals("BACKED_UP")) throw new Error("power loss"); }).install(p));
        assertFalse(Files.exists(mods));
        assertEquals(0, WorkerMain.run(new String[]{"--recover", data.toString()}, T.FAST));
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods));
    }

    @Test void customInstanceDirectoryIsHonoured() throws Exception {
        Path custom = tmp.resolve("Some Launcher/instances/pack one");   // spaces, nested, not .minecraft
        game = T.instance(custom, "zerith-modsync", "v1");
        data = game.resolve(".zerith-modsync");
        mods = game.resolve("mods");
        src = tmp.resolve("src");
        Path self = T.jar(src, "zerith-modsync-2.jar", "zerith-modsync", "v2");
        manifest = T.manifest("2", T.mod(self, "zerith-modsync", "https://github.com/o/r/a"));
        pend("tx-w0000006", 2_000_000_000L);
        assertEquals(0, WorkerMain.run(new String[]{data.toString()}, T.FAST));
        assertEquals(Set.of("zerith-modsync-2.jar"), names(mods));
    }

    @Test void workerWithoutPendingOrBadArgsIsHarmless() throws Exception {
        workerSetup();
        assertEquals(0, WorkerMain.run(new String[]{data.toString()}, T.FAST));
        assertEquals(64, WorkerMain.run(new String[]{}, T.FAST));
        assertEquals(Set.of("old.jar", "zerith-modsync.jar"), names(mods));
    }

    // ---------------- manifest CLI ----------------
    Path pack() throws Exception {
        Path d = tmp.resolve("pack");
        T.jar(d, "zerith-modsync-1.0.0.jar", "zerith-modsync", "s");
        T.jar(d, "zeta-mod.jar", "zeta", "z");
        T.jar(d, "alpha-mod.jar", "alpha", "a");
        return d;
    }

    @Test void generatorIsDeterministicSortedAndBuildsReleaseUrls() throws Exception {
        Path d = pack();
        Model.Manifest m1 = Main.build(d, "alice", "zerith-modsync", "v1.0.0", "Pack", "1.0.0", "1.21.1");
        Model.Manifest m2 = Main.build(d, "alice", "zerith-modsync", "v1.0.0", "Pack", "1.0.0", "1.21.1");
        assertEquals(FileOps.GSON.toJson(m1), FileOps.GSON.toJson(m2));
        assertEquals(List.of("alpha-mod.jar", "zerith-modsync-1.0.0.jar", "zeta-mod.jar"), m1.mods().stream().map(Model.Mod::filename).toList());
        assertEquals("https://github.com/alice/zerith-modsync/releases/download/v1.0.0/alpha-mod.jar", m1.mods().get(0).url());
        assertEquals(FileOps.sha256(d.resolve("alpha-mod.jar")), m1.mods().get(0).sha256());
        assertEquals("zerith-modsync", m1.mods().get(1).id());
        assertDoesNotThrow(() -> ManifestValidator.validate(m1, Policy.defaults()));
    }

    @Test void generatorCliWritesAndValidatesFile() throws Exception {
        Path d = pack();
        Path out = tmp.resolve("out/modpack.json");
        assertEquals(0, Main.run(new String[]{"generate", "--mods-dir", d.toString(), "--owner", "alice", "--repo", "zerith-modsync",
                "--tag", "v1.0.0", "--pack-version", "1.0.0", "--output", out.toString()}));
        assertEquals(0, Main.run(new String[]{"validate", out.toString(), "--mods-dir", d.toString()}));
        Files.writeString(d.resolve("zeta-mod.jar"), "changed");
        assertThrows(IOException.class, () -> Main.run(new String[]{"validate", out.toString(), "--mods-dir", d.toString()}));
    }

    @Test void generatorRejectsBadInput() throws Exception {
        Path d = pack();
        T.jar(d, "alpha-copy.jar", "alpha", "dup");
        assertThrows(IOException.class, () -> Main.build(d, "alice", "r", "v1", "P", "1", "1.21.1"));      // duplicate id
        Files.delete(d.resolve("alpha-copy.jar"));
        Files.writeString(d.resolve("forge.jar"), "not fabric");
        assertThrows(IOException.class, () -> Main.build(d, "alice", "r", "v1", "P", "1", "1.21.1"));      // not a Fabric jar
        Files.delete(d.resolve("forge.jar"));
        Files.delete(d.resolve("zerith-modsync-1.0.0.jar"));
        assertThrows(IOException.class, () -> Main.build(d, "alice", "r", "v1", "P", "1", "1.21.1"));      // updater missing
        T.jar(d, "zerith-modsync-1.jar", "zerith-modsync", "a");
        T.jar(d, "zerith-modsync-2.jar", "zerith-modsync", "b");
        assertThrows(IOException.class, () -> Main.build(d, "alice", "r", "v1", "P", "1", "1.21.1"));      // updater twice
        assertThrows(IllegalArgumentException.class, () -> Main.build(d, "OWNER", "r", "v1", "P", "1", "1.21.1"));
        assertThrows(IllegalArgumentException.class, () -> Main.build(d, "al ice", "r", "v1", "P", "1", "1.21.1"));
    }

    @Test void generatorRejectsIncompatibleMinecraftRange() throws Exception {
        Path d = pack();
        Path bad = d.resolve("old.jar");
        try (var j = new java.util.jar.JarOutputStream(Files.newOutputStream(bad))) {
            j.putNextEntry(new java.util.jar.JarEntry("fabric.mod.json"));
            j.write("{\"id\":\"oldmod\",\"version\":\"1\",\"depends\":{\"minecraft\":\"1.20.x\"}}".getBytes());
        }
        assertThrows(IOException.class, () -> Main.build(d, "alice", "r", "v1", "P", "1", "1.21.1"));
    }
}
