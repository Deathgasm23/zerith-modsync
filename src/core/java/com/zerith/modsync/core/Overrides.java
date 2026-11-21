package com.zerith.modsync.core;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/**
 * config/, options.txt, resourcepacks/, shaderpacks/ synchronization helpers.
 * Semantics: a listed file is (re)applied only when the manifest's hash for it differs from what was last
 * applied (data/applied-files.json). Local edits to unchanged files are kept; unlisted files are never touched.
 */
public final class Overrides {
    public static final String APPLIED = "applied-files.json";

    private Overrides() {}

    public static Map<String, String> loadApplied(Path data) {
        try {
            Path f = data.resolve(APPLIED);
            if (!Files.isRegularFile(f)) return new HashMap<>();
            Model.Applied a = FileOps.readJson(f, Model.Applied.class);
            return a.files() == null ? new HashMap<>() : new HashMap<>(a.files());
        } catch (IOException e) { return new HashMap<>(); }
    }

    public static void saveApplied(Path data, Map<String, String> applied) throws IOException {
        FileOps.writeJson(data.resolve(APPLIED), new Model.Applied(new TreeMap<>(applied)));
    }

    /** Files that must be applied now: the author's hash differs from the last applied one and the local file is not already identical. */
    public static List<Model.OverrideFile> pending(Path game, Path data, Model.Manifest m) throws IOException {
        if (m.overrides() == null) return List.of();
        Map<String, String> applied = loadApplied(data);
        List<Model.OverrideFile> out = new ArrayList<>();
        for (Model.OverrideFile f : m.overrides().files()) {
            if (f.sha256().equalsIgnoreCase(applied.get(f.path()))) continue;
            Path target = target(game, f.path());
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) && FileOps.sha256(target).equalsIgnoreCase(f.sha256())) continue;
            out.add(f);
        }
        return out;
    }

    /** Resolves a manifest path under game, refusing anything that could leave the allowed roots. */
    public static Path target(Path game, String rel) throws IOException {
        String why = ManifestValidator.checkOverridePath(rel);
        if (why != null) throw new IOException("Unsafe overrides path '" + rel + "': " + why);
        Path t = game.resolve(rel).normalize();
        if (!t.startsWith(game.normalize())) throw new IOException("Overrides path escapes the game directory: " + rel);
        return t;
    }

    /**
     * Extracts exactly the needed files from the archive into dest. The archive's file set must equal the manifest list,
     * and every extracted file is size-bounded and SHA-256 checked. Nothing in the archive is ever executed.
     */
    public static void extract(Path zipFile, Model.Overrides listed, List<Model.OverrideFile> needed, Path dest) throws IOException {
        Map<String, Model.OverrideFile> all = new HashMap<>();
        for (Model.OverrideFile f : listed.files()) all.put(f.path(), f);
        Files.createDirectories(dest);
        try (ZipFile zip = new ZipFile(zipFile.toFile())) {
            Set<String> names = new HashSet<>();
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                if (!all.containsKey(e.getName())) throw new IOException("Overrides archive contains an unlisted entry: " + e.getName());
                if (!names.add(e.getName())) throw new IOException("Duplicate archive entry: " + e.getName());
            }
            if (!names.equals(all.keySet())) throw new IOException("Overrides archive does not match its manifest list");
            for (Model.OverrideFile f : needed) {
                Path out = dest.resolve(f.path()).normalize();
                if (!out.startsWith(dest.normalize())) throw new IOException("Path escape: " + f.path());
                Files.createDirectories(out.getParent());
                ZipEntry e = zip.getEntry(f.path());
                MessageDigest md = FileOps.digest();
                long n = 0;
                try (InputStream in = zip.getInputStream(e); OutputStream o = Files.newOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    for (int r; (r = in.read(buf)) > 0; ) {
                        n += r;
                        if (n > f.size()) throw new IOException("Archive entry larger than declared: " + f.path());
                        md.update(buf, 0, r);
                        o.write(buf, 0, r);
                    }
                }
                if (n != f.size() || !HexFormat.of().formatHex(md.digest()).equalsIgnoreCase(f.sha256()))
                    throw new IOException("Hash/size mismatch in overrides archive: " + f.path());
            }
        } catch (ZipException e) {
            throw new IOException("Corrupt overrides archive: " + e.getMessage(), e);
        }
    }

    /** Throws unless every listed file exists under root with the right hash. */
    public static void verifyStaged(Path root, List<Model.OverrideFile> files) throws IOException {
        for (Model.OverrideFile f : files) {
            Path p = root.resolve(f.path());
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS) || !FileOps.sha256(p).equalsIgnoreCase(f.sha256()))
                throw new IOException("Staged override missing or corrupt: " + f.path());
        }
    }

    /** Rejects links anywhere between game and the target's parent. */
    public static void requirePlainParents(Path game, Path target) throws IOException {
        Path cur = game.normalize();
        for (Path seg : cur.relativize(target.getParent())) {
            cur = cur.resolve(seg);
            if (Files.exists(cur, LinkOption.NOFOLLOW_LINKS) && (FileOps.isLinkLike(cur) || !Files.isDirectory(cur, LinkOption.NOFOLLOW_LINKS)))
                throw new IOException("Refusing to write through a link or file: " + cur);
        }
    }
}
