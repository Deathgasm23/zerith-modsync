package com.zerith.modsync.core;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/** HTTP access with manual, allowlist-checked redirects, bounded retries and streaming SHA-256. */
public final class Downloader {
    public static final class RateLimitedException extends IOException {
        public final long retryAfterSeconds;
        public RateLimitedException(long s) { super("Rate limited, retry after " + s + "s"); retryAfterSeconds = s; }
    }

    private final Policy policy;
    private final HttpClient client;
    private final Duration requestTimeout;
    private final int retries;
    private final long backoffMillis;

    public Downloader(Policy policy, int connectTimeoutSec, int requestTimeoutSec, int retries, long backoffMillis) {
        this.policy = policy;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(connectTimeoutSec))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSec);
        this.retries = retries;
        this.backoffMillis = backoffMillis;
    }

    /** One GET with redirect handling; every hop must pass the policy. Caller closes the body. */
    public HttpResponse<InputStream> open(URI uri, Map<String, String> headers) throws IOException {
        URI cur = uri;
        for (int hop = 0; hop <= 5; hop++) {
            policy.checkUrl(cur);
            HttpRequest.Builder b = HttpRequest.newBuilder(cur).timeout(requestTimeout).header("User-Agent", "Zerith-ModSync/1.0").GET();
            headers.forEach(b::header);
            HttpResponse<InputStream> r;
            try { r = client.send(b.build(), HttpResponse.BodyHandlers.ofInputStream()); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Interrupted"); }
            int s = r.statusCode();
            if (s == 301 || s == 302 || s == 303 || s == 307 || s == 308) {
                String loc = r.headers().firstValue("Location").orElseThrow(() -> new IOException("Redirect without Location"));
                r.body().close();
                cur = cur.resolve(loc);
                continue;
            }
            if (s == 429 || (s == 403 && "0".equals(r.headers().firstValue("x-ratelimit-remaining").orElse("")))) {
                r.body().close();
                long wait = r.headers().firstValue("Retry-After").map(v -> { try { return Long.parseLong(v.trim()); } catch (NumberFormatException e) { return 900L; } }).orElse(900L);
                throw new RateLimitedException(wait);
            }
            return r;
        }
        throw new IOException("Too many redirects: " + uri);
    }

    /** Downloads, hashes and size-checks one mod into target. Retries transient failures with backoff. */
    public void download(Model.Mod mod, Path target, LongConsumer onBytes, BooleanSupplier cancelled) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt <= retries; attempt++) {
            if (attempt > 0) FileOps.sleep(Math.min(30_000, backoffMillis << (attempt - 1)));
            Path part = target.resolveSibling(target.getFileName() + ".part");
            long counted = 0;
            try {
                var r = open(URI.create(mod.url()), Map.of());
                try (InputStream body = r.body()) {
                    if (r.statusCode() == 404) throw new FatalDownload("Release asset missing (HTTP 404): " + mod.filename());
                    if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + mod.filename());
                    long limit = mod.size() != null ? mod.size() : policy.maxJarBytes();
                    long declared = r.headers().firstValueAsLong("Content-Length").orElse(-1);
                    if (declared > limit) throw new FatalDownload("Download larger than allowed: " + mod.filename());
                    MessageDigest md = FileOps.digest();
                    try (InputStream in = body; OutputStream out = Files.newOutputStream(part)) {
                        byte[] buf = new byte[65536];
                        for (int n; (n = in.read(buf)) > 0; ) {
                            if (cancelled.getAsBoolean()) throw new InterruptedIOException("Cancelled");
                            counted += n;
                            if (counted > limit) throw new FatalDownload("Download exceeds size limit: " + mod.filename());
                            md.update(buf, 0, n);
                            out.write(buf, 0, n);
                            onBytes.accept(n);
                        }
                    }
                    if (mod.size() != null && counted != mod.size()) throw new IOException("Size mismatch for " + mod.filename() + ": got " + counted);
                    String sha = HexFormat.of().formatHex(md.digest());
                    if (!sha.equalsIgnoreCase(mod.sha256())) throw new IOException("SHA-256 mismatch for " + mod.filename());
                }
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            } catch (FatalDownload | RateLimitedException | InterruptedIOException e) {
                Files.deleteIfExists(part);
                throw e;
            } catch (IOException e) {
                Files.deleteIfExists(part);
                last = e;
            }
        }
        throw last;
    }

    /** Non-retryable failure. */
    public static final class FatalDownload extends IOException {
        public FatalDownload(String m) { super(m); }
    }
}
