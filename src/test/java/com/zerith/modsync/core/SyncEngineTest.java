package com.zerith.modsync.core;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;

class SyncEngineTest {
    @TempDir Path tmp;
    HttpServer server;
    Policy policy = T.loopbackPolicy();
    Path game, data, mods, src;
    Model.Manifest manifest;
    String manifestJson;
    final AtomicInteger manifestHits = new AtomicInteger(), conditionalHits = new AtomicInteger(), jarHits = new AtomicInteger();
    final AtomicReference<byte[]> a = new AtomicReference<>(), b = new AtomicReference<>();

    @BeforeEach void setUp() throws Exception {
        server = T.server();
        game = T.instance(tmp, "zerith-modsync", "v1", "stale", "x");
        data = game.resolve(".zerith-modsync");
        mods = game.resolve("mods");
        src = tmp.resolve("src");
        Path self = T.jar(src, "zerith-modsync-2.jar", "zerith-modsync", "v2");
        Path other = T.jar(src, "other.jar", "other", "o");
        a.set(Files.readAllBytes(self));
        b.set(Files.readAllBytes(other));
        String base = T.base(server);
        manifest = T.manifest("2", T.mod(self, "zerith-modsync", base + "/a.jar"), T.mod(other, "other", base + "/b.jar"));
        manifestJson = FileOps.GSON.toJson(manifest);
        ctx("/modpack.json", ex -> {
            manifestHits.incrementAndGet();
            if ("\"v1\"".equals(ex.getRequestHeaders().getFirst("If-None-Match"))) {
                conditionalHits.incrementAndGet();
                ex.sendResponseHeaders(304, -1);
                ex.close();
                return;
            }
            byte[] body = manifestJson.getBytes();
            ex.getResponseHeaders().add("ETag", "\"v1\"");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        ctx("/a.jar", ex -> { jarHits.incrementAndGet(); send(ex, a.get()); });
        ctx("/b.jar", ex -> { jarHits.incrementAndGet(); send(ex, b.get()); });
    }

    @AfterEach void tearDown() { server.stop(0); }

    void ctx(String path, com.sun.net.httpserver.HttpHandler h) {
        try { server.removeContext(path); } catch (IllegalArgumentException ignored) { }
        server.createContext(path, h);
    }

    static void send(com.sun.net.httpserver.HttpExchange ex, byte[] body) throws IOException {
        ex.sendResponseHeaders(200, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    SyncEngine engine(Config c) {
        return new SyncEngine(game, c, T.base(server) + "/modpack.json", policy, T.downloader(policy), new SyncEngine.Listener() {});
    }
    SyncEngine engine() { return engine(new Config()); }
    Set<String> names(Path d) throws IOException { try (var s = Files.list(d)) { return new TreeSet<>(s.map(p -> p.getFileName().toString()).toList()); } }

    @Test void stagesCompleteVerifiedReplacementAndNeverTouchesMods() throws Exception {
        Set<String> before = names(mods);
        var r = engine().runOnce();
        assertEquals(SyncEngine.Outcome.STAGED, r.outcome());
        assertEquals(before, names(mods), "live mods untouched while game is running");
        Model.Pending p = FileOps.readJson(data.resolve(Installer.PENDING), Model.Pending.class);
        LocalScanner.verify(Path.of(p.stagingDir()), manifest, true);
        assertEquals(2, jarHits.get(), "all official jars downloaded");
        assertEquals(Set.of("other.jar", "zerith-modsync-2.jar"), names(Path.of(p.stagingDir())));
        new Installer(data, T.FAST, policy).install(p);                         // and the swap yields exactly the manifest
        LocalScanner.verify(mods, manifest, true);
    }

    @Test void upToDateDoesNothingAndAvoidsDownloads() throws Exception {
        for (Model.Mod m : manifest.mods()) Files.copy(src.resolve(m.filename()), (Files.exists(mods) ? mods : Files.createDirectories(mods)).resolve(m.filename()));
        FileOps.deleteTree(mods.resolve("zerith-modsync.jar")); FileOps.deleteTree(mods.resolve("stale.jar"));
        var r = engine().runOnce();
        assertEquals(SyncEngine.Outcome.UP_TO_DATE, r.outcome());
        assertEquals(0, jarHits.get());
        assertFalse(Files.exists(data.resolve(Installer.PENDING)));
    }

    @Test void notModifiedStillDetectsLocalChanges() throws Exception {
        SyncEngine e = engine();
        assertEquals(SyncEngine.Outcome.STAGED, e.runOnce().outcome());
        new Installer(data, T.FAST, policy).install(FileOps.readJson(data.resolve(Installer.PENDING), Model.Pending.class));
        assertEquals(SyncEngine.Outcome.UP_TO_DATE, e.runOnce().outcome());
        assertEquals(1, conditionalHits.get(), "second request used If-None-Match and got 304");
        Files.writeString(mods.resolve("other.jar"), "locally tampered");                 // 304 from server, but local files differ
        var r = e.runOnce();
        assertEquals(SyncEngine.Outcome.STAGED, r.outcome());
        assertTrue(r.diff().modified().contains("other.jar"));
        assertEquals(2, conditionalHits.get());
    }

    @Test void secondPassWithSameStagedUpdateDoesNotRedownload() throws Exception {
        SyncEngine e = engine();
        assertEquals(SyncEngine.Outcome.STAGED, e.runOnce().outcome());
        int hits = jarHits.get();
        assertEquals(SyncEngine.Outcome.ALREADY_PENDING, e.runOnce().outcome());
        assertEquals(hits, jarHits.get());
    }

    @Test void changedManifestReplacesStalePending() throws Exception {
        SyncEngine e = engine();
        e.runOnce();
        Model.Pending first = FileOps.readJson(data.resolve(Installer.PENDING), Model.Pending.class);
        b.set(Files.readAllBytes(T.jar(tmp.resolve("src2"), "other.jar", "other", "o-v2")));
        Model.Mod newOther = T.mod(tmp.resolve("src2/other.jar"), "other", T.base(server) + "/b.jar");
        manifestJson = FileOps.GSON.toJson(T.manifest("2", manifest.mods().get(0), newOther));   // same version string, new bytes
        new java.io.File(data.resolve("http-cache.json").toString()).delete();
        assertEquals(SyncEngine.Outcome.STAGED, e.runOnce().outcome());
        Model.Pending second = FileOps.readJson(data.resolve(Installer.PENDING), Model.Pending.class);
        assertNotEquals(first.transactionId(), second.transactionId());
        assertFalse(Files.exists(Path.of(first.stagingDir())), "old staging cleaned");
    }

    @Test void badSha256FailsClosed() throws Exception {
        b.set("different bytes".getBytes());
        assertThrows(IOException.class, () -> engine().runOnce());
        assertFalse(Files.exists(data.resolve(Installer.PENDING)));
        assertTrue(!Files.exists(data.resolve("staging")) || names(data.resolve("staging")).isEmpty(), "failed staging removed");
        assertTrue(Files.exists(mods.resolve("stale.jar")));
    }

    @Test void truncatedDownloadFailsClosed() throws Exception {
        ctx("/b.jar", ex -> {
            byte[] body = b.get();
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body, 0, body.length / 2);
            ex.close();                                                  // connection dies mid-body
        });
        assertThrows(IOException.class, () -> engine().runOnce());
        assertFalse(Files.exists(data.resolve(Installer.PENDING)));
    }

    @Test void missingReleaseAssetFailsWithoutRetry() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        ctx("/b.jar", ex -> { hits.incrementAndGet(); ex.sendResponseHeaders(404, -1); ex.close(); });
        IOException e = assertThrows(IOException.class, () -> engine().runOnce());
        assertTrue(e.getMessage().contains("404"), e.getMessage());
        assertEquals(1, hits.get());
    }

    @Test void transientServerErrorIsRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        ctx("/b.jar", ex -> {
            if (hits.incrementAndGet() < 3) { ex.sendResponseHeaders(503, -1); ex.close(); } else send(ex, b.get());
        });
        assertEquals(SyncEngine.Outcome.STAGED, engine().runOnce().outcome());
        assertEquals(3, hits.get());
    }

    @Test void httpTimeoutFailsSafely() throws Exception {
        ctx("/b.jar", ex -> { try { Thread.sleep(5000); } catch (InterruptedException ignored) { } ex.close(); });
        Downloader quick = new Downloader(policy, 1, 1, 0, 1);   // 1s request timeout, no retries
        SyncEngine e = new SyncEngine(game, new Config(), T.base(server) + "/modpack.json", policy, quick, new SyncEngine.Listener() {});
        assertThrows(IOException.class, e::runOnce);
        assertTrue(Files.exists(mods.resolve("stale.jar")));
    }

    @Test void invalidManifestFailsClosed() throws Exception {
        manifestJson = "{\"schemaVersion\":1,\"modpack\":{\"name\":\"x\",\"version\":\"1\",\"minecraftVersion\":\"1.21.1\",\"loader\":\"fabric\"},\"mods\":[]}";
        assertThrows(IOException.class, () -> engine().runOnce());
        manifestJson = "not json";
        assertThrows(IOException.class, () -> engine().runOnce());
        assertEquals(0, jarHits.get());
    }

    @Test void redirectToDisallowedHostIsRejected() throws Exception {
        ctx("/b.jar", ex -> {
            ex.getResponseHeaders().add("Location", "http://evil.example/b.jar");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        IOException e = assertThrows(IOException.class, () -> engine().runOnce());
        assertInstanceOf(IllegalArgumentException.class, e.getCause());
    }

    @Test void redirectWithinAllowedHostIsFollowed() throws Exception {
        ctx("/b.jar", ex -> {
            ex.getResponseHeaders().add("Location", "/b2.jar");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        ctx("/b2.jar", ex -> send(ex, b.get()));
        assertEquals(SyncEngine.Outcome.STAGED, engine().runOnce().outcome());
    }

    @Test void rateLimitIsReportedNotRetried() throws Exception {
        AtomicInteger hits = new AtomicInteger();
        ctx("/modpack.json", ex -> { hits.incrementAndGet(); ex.getResponseHeaders().add("Retry-After", "120"); ex.sendResponseHeaders(429, -1); ex.close(); });
        var e = assertThrows(Downloader.RateLimitedException.class, () -> engine().runOnce());
        assertEquals(120, e.retryAfterSeconds);
        assertEquals(1, hits.get());
    }

    @Test void placeholderUrlDoesNothing() throws Exception {
        SyncEngine e = new SyncEngine(game, new Config(), "https://raw.githubusercontent.com/OWNER/zerith-modsync/main/modpack/modpack.json",
                Policy.defaults(), T.downloader(Policy.defaults()), new SyncEngine.Listener() {});
        var r = e.runOnce();
        assertEquals(SyncEngine.Outcome.CONFIG_ERROR, r.outcome());
        assertTrue(Files.exists(mods.resolve("stale.jar")));
    }

    @Test void autoDownloadOffOnlyReports() throws Exception {
        Config c = new Config();
        c.autoDownload = false;
        var r = engine(c).runOnce();
        assertEquals(SyncEngine.Outcome.UPDATE_AVAILABLE, r.outcome());
        assertEquals(0, jarHits.get());
    }

    @Test void nonStrictCarriesExtraFilesIntoStaging() throws Exception {
        Config c = new Config();
        c.strictSynchronization = false;
        engine(c).runOnce();
        Model.Pending p = FileOps.readJson(data.resolve(Installer.PENDING), Model.Pending.class);
        assertTrue(Files.exists(Path.of(p.stagingDir()).resolve("stale.jar")));
        assertFalse(p.strict());
    }

    @Test void transactionInProgressBlocksNewCheck() throws Exception {
        Files.writeString(data.resolve(Installer.JOURNAL), "{}");
        assertEquals(SyncEngine.Outcome.BUSY, engine().runOnce().outcome());
        assertEquals(0, manifestHits.get());
    }

    @Test void insufficientDiskSpaceIsRefused() throws Exception {
        Model.Mod huge = new Model.Mod("zerith-modsync", "zerith-modsync-2.jar", T.base(server) + "/a.jar", manifest.mods().get(0).sha256(), 60L << 20);
        Policy big = new Policy("1.21.1", "fabric", Set.of("127.0.0.1"), Long.MAX_VALUE / 4, Long.MAX_VALUE / 2, true);
        manifestJson = FileOps.GSON.toJson(T.manifest("2", new Model.Mod(huge.id(), huge.filename(), huge.url(), huge.sha256(), Long.MAX_VALUE / 8), manifest.mods().get(1)));
        SyncEngine e = new SyncEngine(game, new Config(), T.base(server) + "/modpack.json", big, T.downloader(big), new SyncEngine.Listener() {});
        IOException ex = assertThrows(IOException.class, e::runOnce);
        assertTrue(ex.getMessage().contains("disk space"), ex.getMessage());
        assertEquals(0, jarHits.get());
    }

    @Test void cancelStopsBetweenChunks() throws Exception {
        byte[] big = new byte[1 << 20];
        ctx("/b.jar", ex -> { ex.sendResponseHeaders(200, big.length); try { ex.getResponseBody().write(big); } catch (IOException ignored) { } ex.close(); });
        Path badSrc = T.jar(tmp.resolve("src3"), "other.jar", "other", "o");
        SyncEngine[] holder = new SyncEngine[1];
        holder[0] = new SyncEngine(game, new Config(), T.base(server) + "/modpack.json", policy, T.downloader(policy), new SyncEngine.Listener() {
            @Override public void progress(int d, int t, long by, long tb) { holder[0].cancel(); }
        });
        assertThrows(IOException.class, holder[0]::runOnce);
        assertFalse(Files.exists(data.resolve(Installer.PENDING)));
    }

    @Test void manifestFetcherRoundTripsValidators() throws Exception {
        ManifestFetcher f = new ManifestFetcher(T.downloader(policy), policy, data);
        var first = f.fetch(URI.create(T.base(server) + "/modpack.json"));
        assertFalse(first.fromCache());
        f.commit(first);
        var second = f.fetch(URI.create(T.base(server) + "/modpack.json"));
        assertTrue(second.fromCache());
        assertEquals(first.manifest(), second.manifest());
    }
}
