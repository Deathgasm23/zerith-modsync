package com.zerith.modsync.core;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.*;
import java.util.*;

/** Hashing, JSON and filesystem helpers. */
public final class FileOps {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private FileOps() {}

    public static String sha256(Path file) throws IOException {
        MessageDigest md = digest();
        byte[] buf = new byte[65536];
        try (InputStream in = Files.newInputStream(file)) {
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    public static String sha256(byte[] data) {
        return HexFormat.of().formatHex(digest().digest(data));
    }

    public static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }

    public static <T> T readJson(Path p, Class<T> type) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            T v = GSON.fromJson(r, type);
            if (v == null) throw new IOException("Empty JSON file: " + p);
            return v;
        } catch (JsonParseException e) {
            throw new IOException("Invalid JSON in " + p + ": " + e.getMessage(), e);
        }
    }

    public static void writeJson(Path p, Object value) throws IOException {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, GSON.toJson(value), StandardCharsets.UTF_8);
        move(tmp, p);
    }

    /** Atomic move when supported, plain move otherwise. */
    public static void move(Path from, Path to) throws IOException {
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(from, to); }
    }

    /** Retries a move to ride out transient Windows locks (antivirus, indexers, lingering handles). */
    public static void moveRetry(Path from, Path to, int attempts, long sleepMillis) throws IOException {
        IOException last = null;
        for (int i = 0; i < attempts; i++) {
            try { move(from, to); return; }
            catch (IOException e) {
                last = e;
                if (i + 1 < attempts) sleep(sleepMillis);
            }
        }
        throw last;
    }

    public static void sleep(long ms) throws IOException {
        try { Thread.sleep(ms); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("Interrupted"); }
    }

    /** Deletes a tree without following symbolic links. */
    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path f, BasicFileAttributes a) throws IOException {
                Files.delete(f); return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.delete(d); return FileVisitResult.CONTINUE;
            }
        });
    }

    /** True for symlinks, junctions and anything that is not a plain directory/file. */
    public static boolean isLinkLike(Path p) throws IOException {
        BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        return a.isSymbolicLink() || a.isOther();
    }

    /** Requires an existing real directory that is not a link. */
    public static void requireRealDir(Path dir) throws IOException {
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing directory: " + dir);
        if (isLinkLike(dir) || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Not a plain directory (link or file rejected): " + dir);
    }

    public static boolean sameStore(Path a, Path b) throws IOException {
        return Files.getFileStore(a).equals(Files.getFileStore(b));
    }
}
