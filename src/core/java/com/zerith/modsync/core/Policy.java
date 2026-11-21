package com.zerith.modsync.core;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Trust policy for manifests and downloads. {@code allowedHosts == null} disables host checks
 * (used by the worker, which never downloads).
 */
public record Policy(String minecraftVersion, String loader, Set<String> allowedHosts,
                     long maxJarBytes, long maxPackBytes, boolean allowLoopbackHttp) {

    public static final Set<String> GITHUB_HOSTS = Set.of("github.com", "raw.githubusercontent.com",
            "objects.githubusercontent.com", "release-assets.githubusercontent.com");

    public static Policy defaults() {
        return new Policy("1.21.1", "fabric", GITHUB_HOSTS, 512L << 20, 4L << 30, false);
    }

    public Policy offline() {
        return new Policy(minecraftVersion, loader, null, maxJarBytes, maxPackBytes, allowLoopbackHttp);
    }

    /** Throws IllegalArgumentException unless the URI is an allowed, credential-free HTTPS URL. */
    public void checkUrl(URI u) {
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (u.getUserInfo() != null) throw new IllegalArgumentException("URL must not contain credentials: " + u);
        boolean loopback = allowLoopbackHttp && scheme.equals("http") && (host.equals("127.0.0.1") || host.equals("localhost"));
        if (!loopback && !scheme.equals("https")) throw new IllegalArgumentException("Only HTTPS URLs are allowed: " + u);
        if (!loopback && u.getPort() != -1 && u.getPort() != 443) throw new IllegalArgumentException("Unexpected port in URL: " + u);
        if (host.isEmpty()) throw new IllegalArgumentException("URL has no host: " + u);
        if (allowedHosts != null && !allowedHosts.contains(host)) throw new IllegalArgumentException("Host not allowed: " + host);
    }
}
