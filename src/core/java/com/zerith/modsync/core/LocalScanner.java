package com.zerith.modsync.core;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Compares a local mods directory with a manifest. Hashes are cached by (size, mtime). */
public final class LocalScanner {

    public record Diff(List<String> missing, List<String> modified, List<String> extra, List<String> renamed) {
        public boolean isEmpty() { return missing.isEmpty() && modified.isEmpty() && extra.isEmpty() && renamed.isEmpty(); }

        /** Non-strict mode tolerates unmanaged extra files. */
        public boolean needsUpdate(boolean strict) {
            return !missing.isEmpty() || !modified.isEmpty() || !renamed.isEmpty() || (strict && !extra.isEmpty());
        }

        public String summary() {
            return "missing=" + missing + " modified=" + modified + " extra=" + extra + " renamed=" + renamed;
        }
    }

    private record Cached(long size, long mtime, String sha) {}

    private final Map<Path, Cached> cache = new HashMap<>();

    public Diff diff(Path mods, Model.Manifest manifest) throws IOException {
        Map<String, Model.Mod> expected = new TreeMap<>();
        for (Model.Mod m : manifest.mods()) expected.put(m.filename(), m);

        List<String> missing = new ArrayList<>(), modified = new ArrayList<>(), extra = new ArrayList<>(), renamed = new ArrayList<>();
        Map<String, String> localHashes = new TreeMap<>();   // regular files only
        Set<String> present = new HashSet<>();

        if (Files.exists(mods, LinkOption.NOFOLLOW_LINKS)) {
            FileOps.requireRealDir(mods);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(mods)) {
                for (Path p : ds) {
                    String name = p.getFileName().toString();
                    present.add(name);
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (!a.isRegularFile()) { extra.add(name); continue; }   // dirs, links, specials are never managed
                    if (expected.containsKey(name)) {
                        String sha = hash(p, a);
                        localHashes.put(name, sha);
                        if (!sha.equalsIgnoreCase(expected.get(name).sha256())) modified.add(name);
                    } else {
                        extra.add(name);
                    }
                }
            }
        }
        for (String name : expected.keySet()) if (!present.contains(name)) missing.add(name);

        // Rename detection: an extra file whose bytes equal a missing manifest file.
        if (!missing.isEmpty() && !extra.isEmpty()) {
            for (Iterator<String> it = extra.iterator(); it.hasNext(); ) {
                String x = it.next();
                Path p = mods.resolve(x);
                BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!a.isRegularFile()) continue;
                String sha = hash(p, a);
                for (Iterator<String> mi = missing.iterator(); mi.hasNext(); ) {
                    String want = mi.next();
                    if (expected.get(want).sha256().equalsIgnoreCase(sha)) {
                        renamed.add(x + " -> " + want);
                        it.remove();
                        mi.remove();
                        break;
                    }
                }
            }
        }
        Collections.sort(missing); Collections.sort(modified); Collections.sort(extra); Collections.sort(renamed);
        return new Diff(missing, modified, extra, renamed);
    }

    private String hash(Path p, BasicFileAttributes a) throws IOException {
        Path key = p.toAbsolutePath().normalize();
        long mt = a.lastModifiedTime().toMillis(), size = a.size();
        Cached c = cache.get(key);
        if (c != null && c.size == size && c.mtime == mt) return c.sha;
        String sha = FileOps.sha256(p);
        cache.put(key, new Cached(size, mt, sha));
        return sha;
    }

    /** Throws unless dir holds exactly (strict) or at least (non-strict) the manifest files with correct hashes. */
    public static void verify(Path dir, Model.Manifest m, boolean strict) throws IOException {
        Diff d = new LocalScanner().diff(dir, m);
        if (d.needsUpdate(strict)) throw new IOException("Directory does not match manifest: " + d.summary());
    }
}
