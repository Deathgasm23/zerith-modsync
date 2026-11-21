package com.zerith.modsync.core;

import com.google.gson.JsonParseException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fetches and caches the manifest with ETag / Last-Modified validators. */
public final class ManifestFetcher {
    public record Fetched(Model.Manifest manifest, String json, String etag, String lastModified, boolean fromCache) {}
    private record CacheState(String etag, String lastModified) {}

    private static final long MAX_BYTES = 2L << 20;
    private final Downloader http;
    private final Policy policy;
    private final Path cacheJson, cacheState;

    public ManifestFetcher(Downloader http, Policy policy, Path dataDir) {
        this.http = http;
        this.policy = policy;
        this.cacheJson = dataDir.resolve("cached-manifest.json");
        this.cacheState = dataDir.resolve("http-cache.json");
    }

    /** A 304 yields the cached manifest (fromCache=true). Result is validated; nothing is cached until {@link #commit}. */
    public Fetched fetch(URI url) throws IOException {
        Map<String, String> h = new HashMap<>();
        if (Files.isRegularFile(cacheJson) && Files.isRegularFile(cacheState)) {
            try {
                CacheState c = FileOps.readJson(cacheState, CacheState.class);
                if (c.etag() != null) h.put("If-None-Match", c.etag());
                if (c.lastModified() != null) h.put("If-Modified-Since", c.lastModified());
            } catch (IOException ignored) { /* unconditional fetch */ }
        }
        var r = http.open(url, h);
        try (InputStream in0 = r.body()) {
            if (r.statusCode() == 304 && !h.isEmpty()) {
                String json = Files.readString(cacheJson, StandardCharsets.UTF_8);
                return new Fetched(parse(json), json, null, null, true);
            }
            if (r.statusCode() != 200) throw new IOException("Manifest request failed: HTTP " + r.statusCode());
            long declared = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (declared > MAX_BYTES) throw new IOException("Manifest too large");
            byte[] bytes = in0.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IOException("Manifest too large");
            String json = new String(bytes, StandardCharsets.UTF_8);
            return new Fetched(parse(json), json, r.headers().firstValue("ETag").orElse(null),
                    r.headers().firstValue("Last-Modified").orElse(null), false);
        }
    }

    public void commit(Fetched f) throws IOException {
        if (f.fromCache()) return;
        Files.createDirectories(cacheJson.getParent());
        Files.writeString(cacheJson, f.json(), StandardCharsets.UTF_8);
        FileOps.writeJson(cacheState, new CacheState(f.etag(), f.lastModified()));
    }

    private Model.Manifest parse(String json) throws IOException {
        try {
            Model.Manifest m = FileOps.GSON.fromJson(json, Model.Manifest.class);
            ManifestValidator.validate(m, policy);
            return m;
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new IOException("Invalid manifest: " + e.getMessage(), e);
        }
    }
}
