package com.zerith.modsync.core;

import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Test helpers: synthetic JARs, manifests, instances and a tiny local HTTP server. */
final class T {
    static final Installer.Tuning FAST = new Installer.Tuning(2, 10);

    private T() {}

    static Path jar(Path dir, String name, String id, String payload) throws IOException {
        Files.createDirectories(dir);
        Path p = dir.resolve(name);
        try (JarOutputStream j = new JarOutputStream(Files.newOutputStream(p))) {
            j.putNextEntry(new JarEntry("fabric.mod.json"));
            j.write(("{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"1.0\",\"depends\":{\"minecraft\":\"1.21.x\"}}").getBytes(StandardCharsets.UTF_8));
            j.putNextEntry(new JarEntry("payload.txt"));
            j.write(payload.getBytes(StandardCharsets.UTF_8));
        }
        return p;
    }

    static Model.Mod mod(Path jar, String id, String url) throws IOException {
        return new Model.Mod(id, jar.getFileName().toString(), url, FileOps.sha256(jar), Files.size(jar));
    }

    static Model.Manifest manifest(String version, Model.Mod... mods) {
        return new Model.Manifest(1, new Model.Pack("Test", version, "1.21.1", "fabric"), List.of(mods));
    }

    /** game/mods with the given jars; returns game dir. */
    static Path instance(Path root, String... idAndPayload) throws IOException {
        Path game = root.resolve("game");
        Files.createDirectories(game.resolve(".zerith-modsync"));
        Path mods = game.resolve("mods");
        for (int i = 0; i + 1 < idAndPayload.length; i += 2) jar(mods, idAndPayload[i] + ".jar", idAndPayload[i], idAndPayload[i + 1]);
        return game;
    }

    /** Stages jars of the manifest in data/staging/tx and returns the pending transaction. */
    static Model.Pending stage(Path game, String tx, Model.Manifest m, Path jarSource, boolean strict) throws IOException {
        Path data = game.resolve(".zerith-modsync");
        Path staging = data.resolve("staging").resolve(tx);
        Files.createDirectories(staging);
        for (Model.Mod mod : m.mods()) Files.copy(jarSource.resolve(mod.filename()), staging.resolve(mod.filename()));
        String json = FileOps.GSON.toJson(m);
        return new Model.Pending(tx, FileOps.sha256(json.getBytes(StandardCharsets.UTF_8)), staging.toString(),
                game.resolve("mods").toString(), data.resolve("backups").resolve("20260101-000000-" + tx).toString(),
                json, 2_000_000_000L, 3, true, strict);
    }

    /** Symlink, or a Windows junction when symlink privilege is missing. Returns false if neither works. */
    static boolean link(Path link, Path target) {
        try { Files.createSymbolicLink(link, target); return true; } catch (Exception ignored) { }
        try {
            Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0 && Files.exists(link);
        } catch (Exception e) { return false; }
    }

    static HttpServer server() throws IOException {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.start();
        return s;
    }

    static String base(HttpServer s) { return "http://127.0.0.1:" + s.getAddress().getPort(); }

    static Policy loopbackPolicy() {
        return new Policy("1.21.1", "fabric", Set.of("127.0.0.1"), 64L << 20, 1L << 30, true);
    }

    static Downloader downloader(Policy p) { return new Downloader(p, 2, 2, 2, 10); }

    static void serveBytes(HttpServer s, String path, byte[] body) {
        s.createContext(path, ex -> {
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream o = ex.getResponseBody()) { o.write(body); }
        });
    }
}
