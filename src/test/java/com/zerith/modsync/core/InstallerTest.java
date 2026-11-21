package com.zerith.modsync.core;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class InstallerTest {
    @TempDir Path tmp;

    static final class Crash extends Error { Crash(String m) { super(m); } }

    Path src, game, data, mods;
    Model.Manifest manifest;

    /** Old mods {old, self-old}; new pack {self(new bytes), fresh}. */
    @BeforeEach void setUp() throws Exception {
        game = T.instance(tmp, "old", "old-payload", "zerith-modsync", "v1");
        data = game.resolve(".zerith-modsync");
        mods = game.resolve("mods");
        src = tmp.resolve("src");
        Path self = T.jar(src, "zerith-modsync-2.jar", "zerith-modsync", "v2");
        Path fresh = T.jar(src, "fresh.jar", "fresh", "new");
        manifest = T.manifest("2", T.mod(self, "zerith-modsync", "https://github.com/o/r/x"), T.mod(fresh, "fresh", "https://github.com/o/r/y"));
    }

    Installer inst() { return new Installer(data, T.FAST); }
    Model.Pending pending(String tx) throws IOException {
        Model.Pending p = T.stage(game, tx, manifest, src, true);
        FileOps.writeJson(data.resolve(Installer.PENDING), p);
        return p;
    }
    Set<String> names(Path dir) throws IOException {
        try (var s = Files.list(dir)) { return new TreeSet<>(s.map(x -> x.getFileName().toString()).toList()); }
    }
    Set<String> oldNames() { return Set.of("old.jar", "zerith-modsync.jar"); }

    @Test void replacesWholeFolderBacksUpAndSelfUpdates() throws Exception {
        Model.Pending p = pending("tx-00000001");
        Files.writeString(game.resolve("config.txt"), "untouched");
        Files.createDirectories(game.resolve("saves/world"));
        inst().install(p);
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods), "exactly the manifest files; old mods and extras gone");
        LocalScanner.verify(mods, manifest, true);
        assertTrue(Files.exists(Path.of(p.backupDir()).resolve("old.jar")), "old mods preserved in backup");
        assertEquals(oldNames(), names(Path.of(p.backupDir())));
        assertFalse(Files.exists(inst().journalFile()));
        assertFalse(Files.exists(inst().pendingFile()));
        assertFalse(Files.exists(Path.of(p.stagingDir())));
        assertEquals("SUCCESS", FileOps.readJson(data.resolve(Installer.STATUS), Model.Status.class).state());
        assertEquals("untouched", Files.readString(game.resolve("config.txt")));
        assertTrue(Files.isDirectory(game.resolve("saves/world")));
    }

    @Test void firstInstallWithoutModsFolder() throws Exception {
        FileOps.deleteTree(mods);
        inst().install(pending("tx-00000002"));
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods));
    }

    @Test void rejectsStagingThatDoesNotMatchManifest() throws Exception {
        Model.Pending p = pending("tx-00000003");
        Files.writeString(Path.of(p.stagingDir()).resolve("fresh.jar"), "corrupt");
        assertThrows(IOException.class, () -> inst().install(p));
        assertEquals(oldNames(), names(mods), "live mods untouched");
    }

    @Test void rejectsExtraFileInStaging() throws Exception {
        Model.Pending p = pending("tx-00000004");
        T.jar(Path.of(p.stagingDir()), "sneaky.jar", "sneaky", "x");
        assertThrows(IOException.class, () -> inst().install(p));
        assertEquals(oldNames(), names(mods));
    }

    @Test void failureAfterSwapRollsBackToOldMods() throws Exception {
        Model.Pending p = pending("tx-00000005");
        assertThrows(IOException.class, () -> inst().hook(ph -> { if (ph.equals("SWAPPED")) throw new IllegalStateException("boom"); }).install(p));
        assertEquals(oldNames(), names(mods), "old mods restored");
        assertEquals("old-payload", new String(readEntry(mods.resolve("old.jar"))), "bytes restored");
        assertFalse(Files.exists(inst().journalFile()));
        assertEquals("FAILED", FileOps.readJson(data.resolve(Installer.STATUS), Model.Status.class).state());
        assertFalse(Files.exists(data.resolve("failed/tx-00000005")));
    }

    @Test void failureAfterBackupRollsBack() throws Exception {
        Model.Pending p = pending("tx-00000006");
        assertThrows(IOException.class, () -> inst().hook(ph -> { if (ph.equals("BACKED_UP")) throw new IllegalStateException("boom"); }).install(p));
        assertEquals(oldNames(), names(mods));
    }

    @Test void crashBeforeBackupIsRecovered() throws Exception {
        Model.Pending p = pending("tx-00000007");
        assertThrows(Crash.class, () -> inst().hook(ph -> { if (ph.equals("BACKING_UP")) throw new Crash(ph); }).install(p));
        assertTrue(Files.exists(inst().journalFile()));
        assertTrue(inst().recover());
        assertEquals(oldNames(), names(mods));
        assertFalse(Files.exists(inst().journalFile()));
    }

    @Test void crashBetweenBackupAndSwapRollsForwardFromVerifiedStaging() throws Exception {
        Model.Pending p = pending("tx-00000008");
        assertThrows(Crash.class, () -> inst().hook(ph -> { if (ph.equals("BACKED_UP")) throw new Crash(ph); }).install(p));
        assertFalse(Files.exists(mods), "interrupted: mods currently absent");
        assertTrue(inst().recover());
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods));
        assertEquals(oldNames(), names(Path.of(p.backupDir())));
    }

    @Test void crashBetweenBackupAndSwapWithDamagedStagingRestoresBackup() throws Exception {
        Model.Pending p = pending("tx-00000009");
        assertThrows(Crash.class, () -> inst().hook(ph -> { if (ph.equals("BACKED_UP")) throw new Crash(ph); }).install(p));
        Files.writeString(Path.of(p.stagingDir()).resolve("fresh.jar"), "damaged");
        assertTrue(inst().recover());
        assertEquals(oldNames(), names(mods));
    }

    @Test void crashAfterSwapCompletesOnRecovery() throws Exception {
        Model.Pending p = pending("tx-00000010");
        assertThrows(Crash.class, () -> inst().hook(ph -> { if (ph.equals("SWAPPED")) throw new Crash(ph); }).install(p));
        assertTrue(inst().recover());
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods));
        assertFalse(Files.exists(inst().journalFile()));
        assertFalse(Files.exists(inst().pendingFile()));
    }

    @Test void crashAfterSwapWithBrokenResultRollsBack() throws Exception {
        Model.Pending p = pending("tx-00000011");
        assertThrows(Crash.class, () -> inst().hook(ph -> { if (ph.equals("SWAPPED")) throw new Crash(ph); }).install(p));
        Files.delete(mods.resolve("fresh.jar"));
        assertTrue(inst().recover());
        assertEquals(oldNames(), names(mods));
    }

    @Test void backupMissingNeverDestroysRemainingMods() throws Exception {
        Model.Pending p = pending("tx-00000012");
        assertThrows(Crash.class, () -> inst().hook(ph -> { if (ph.equals("SWAPPED")) throw new Crash(ph); }).install(p));
        Files.delete(mods.resolve("fresh.jar"));
        FileOps.deleteTree(Path.of(p.backupDir()));
        assertThrows(IOException.class, () -> inst().recover());
        assertTrue(Files.exists(mods.resolve("zerith-modsync-2.jar")), "mods left in place for manual recovery");
        assertTrue(Files.exists(inst().journalFile()), "journal kept");
    }

    @Test void backupRetentionKeepsNewestAndNeverDeletesCurrent() throws Exception {
        for (String n : List.of("20250101-000000-a", "20250102-000000-b", "20250103-000000-c", "20250104-000000-d"))
            Files.createDirectories(data.resolve("backups").resolve(n));
        Model.Pending p = pending("tx-00000013");
        inst().install(p);
        Set<String> left = names(data.resolve("backups"));
        assertEquals(3, left.size());
        assertTrue(left.contains(Path.of(p.backupDir()).getFileName().toString()));
    }

    @Test void createBackupFalseDeletesBackupAfterVerifiedSuccess() throws Exception {
        Model.Pending base = pending("tx-00000014");
        Model.Pending p = new Model.Pending(base.transactionId(), base.manifestSha256(), base.stagingDir(), base.modsDir(),
                base.backupDir(), base.manifestJson(), 0, 3, false, true);
        inst().install(p);
        assertFalse(Files.exists(Path.of(p.backupDir())));
        assertEquals(Set.of("fresh.jar", "zerith-modsync-2.jar"), names(mods));
    }

    @Test void nonStrictInstallPreservesCarriedExtras() throws Exception {
        Model.Pending base = pending("tx-00000015");
        Files.copy(mods.resolve("old.jar"), Path.of(base.stagingDir()).resolve("old.jar"));
        Model.Pending p = new Model.Pending(base.transactionId(), base.manifestSha256(), base.stagingDir(), base.modsDir(),
                base.backupDir(), base.manifestJson(), 0, 3, true, false);
        inst().install(p);
        assertEquals(Set.of("fresh.jar", "old.jar", "zerith-modsync-2.jar"), names(mods));
    }

    @Test void pathsOutsideTheInstanceAreRejected() throws Exception {
        Model.Pending p = pending("tx-00000016");
        Path elsewhere = tmp.resolve("elsewhere-mods");
        Files.createDirectories(elsewhere);
        Model.Pending evil = new Model.Pending(p.transactionId(), p.manifestSha256(), p.stagingDir(), elsewhere.toString(), p.backupDir(), p.manifestJson(), 0, 3, true, true);
        assertThrows(IOException.class, () -> inst().install(evil));
        Model.Pending evil2 = new Model.Pending(p.transactionId(), p.manifestSha256(), tmp.toString(), p.modsDir(), p.backupDir(), p.manifestJson(), 0, 3, true, true);
        assertThrows(IOException.class, () -> inst().install(evil2));
        Model.Pending evil3 = new Model.Pending("../../x", p.manifestSha256(), p.stagingDir(), p.modsDir(), p.backupDir(), p.manifestJson(), 0, 3, true, true);
        assertThrows(IOException.class, () -> inst().install(evil3));
        assertEquals(oldNames(), names(mods));
    }

    @Test void tamperedPendingManifestIsRejected() throws Exception {
        Model.Pending p = pending("tx-00000017");
        Model.Pending t = new Model.Pending(p.transactionId(), "0".repeat(64), p.stagingDir(), p.modsDir(), p.backupDir(), p.manifestJson(), 0, 3, true, true);
        assertThrows(IOException.class, () -> inst().install(t));
    }

    @Test void symlinkedModsDirectoryIsRefused() throws Exception {
        Path real = tmp.resolve("real-mods");
        Files.move(mods, real);
        Assumptions.assumeTrue(T.link(mods, real), "neither symlinks nor junctions available");
        Model.Pending p = pending("tx-00000018");
        assertThrows(IOException.class, () -> inst().install(p));
        assertEquals(oldNames(), names(real));
    }

    @Test void lockedFileOnWindowsKeepsOldModsAndFailsSafely() throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").toLowerCase().contains("win"), "Windows-only file-lock semantics");
        Model.Pending p = pending("tx-00000019");
        try (FileChannel locked = FileChannel.open(mods.resolve("old.jar"), StandardOpenOption.READ)) {
            assertThrows(IOException.class, () -> inst().install(p));
        }
        assertEquals(oldNames(), names(mods));
        assertFalse(Files.exists(inst().journalFile()), "abandoned cleanly: nothing was moved");
    }

    private static byte[] readEntry(Path jar) throws IOException {
        try (var z = new java.util.zip.ZipFile(jar.toFile())) { return z.getInputStream(z.getEntry("payload.txt")).readAllBytes(); }
    }
}
