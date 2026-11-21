package com.zerith.modsync.cli;

import com.zerith.modsync.core.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Manifest generator / validator.
 *   generate --mods-dir D --owner O --repo R --tag T --output FILE --pack-name N --pack-version V [--mc-version 1.21.1]
 *            [--overrides-dir D --overrides-zip FILE]   (D holds config/, options.txt, resourcepacks/, shaderpacks/)
 *   validate FILE [--mods-dir D] [--overrides-zip FILE]
 */
public final class Main {
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    public static int run(String[] args) throws IOException {
        if (args.length == 0) throw new IllegalArgumentException("Usage: generate|validate ...");
        Map<String, String> o = new HashMap<>();
        List<String> pos = new ArrayList<>();
        for (int i = 1; i < args.length; i++) {
            if (args[i].startsWith("--")) {
                if (i + 1 >= args.length) throw new IllegalArgumentException("Missing value for " + args[i]);
                o.put(args[i].substring(2), args[++i]);
            } else pos.add(args[i]);
        }
        return switch (args[0]) {
            case "generate" -> { generate(o); yield 0; }
            case "validate" -> { validate(pos, o); yield 0; }
            default -> throw new IllegalArgumentException("Unknown command: " + args[0]);
        };
    }

    public static Model.Manifest build(Path modsDir, String owner, String repo, String tag, String packName,
                                       String packVersion, String mc) throws IOException {
        return build(modsDir, owner, repo, tag, packName, packVersion, mc, null, null);
    }

    public static Model.Manifest build(Path modsDir, String owner, String repo, String tag, String packName,
                                       String packVersion, String mc, Path overridesDir, Path overridesZip) throws IOException {
        if (!SLUG.matcher(owner).matches() || !SLUG.matcher(repo).matches() || !SLUG.matcher(tag).matches())
            throw new IllegalArgumentException("owner/repo/tag contain unsupported characters");
        if (owner.equalsIgnoreCase("OWNER") || owner.equals("YOUR_USERNAME")) throw new IllegalArgumentException("Owner is still a placeholder");
        FileOps.requireRealDir(modsDir);
        List<Path> jars = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(modsDir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular file: " + p.getFileName());
                if (!p.getFileName().toString().endsWith(".jar")) throw new IOException("Non-JAR file in mods directory: " + p.getFileName());
                jars.add(p);
            }
        }
        jars.sort(Comparator.comparing(p -> p.getFileName().toString()));
        if (jars.isEmpty()) throw new IOException("No JAR files found");
        Map<String, String> idToFile = new HashMap<>();
        List<Model.Mod> mods = new ArrayList<>();
        for (Path jar : jars) {
            String name = jar.getFileName().toString();
            JarInspector.FabricInfo info = JarInspector.inspect(jar);
            String prev = idToFile.put(info.id(), name);
            if (prev != null) throw new IOException("Duplicate mod id '" + info.id() + "' in " + prev + " and " + name);
            String problem = JarInspector.checkMinecraft(info.minecraftDepends(), mc);
            if (problem != null) throw new IOException(name + " " + problem);
            String url = "https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/" + name;
            mods.add(new Model.Mod(info.id(), name, url, FileOps.sha256(jar), Files.size(jar)));
        }
        long selfCount = mods.stream().filter(m -> ManifestValidator.SELF_ID.equals(m.id())).count();
        if (selfCount != 1) throw new IOException("Zerith ModSync JAR must be present exactly once (found " + selfCount + ")");
        Model.Overrides ov = overridesDir == null ? null : buildOverrides(overridesDir, overridesZip, owner, repo, tag);
        Model.Manifest m = new Model.Manifest(1, new Model.Pack(packName, packVersion, mc, "fabric"), mods, ov);
        ManifestValidator.validate(m, Policy.defaults());
        return m;
    }

    /** Zips the overrides folder deterministically (sorted, fixed timestamps) and describes every file. */
    public static Model.Overrides buildOverrides(Path dir, Path zip, String owner, String repo, String tag) throws IOException {
        if (zip == null) throw new IllegalArgumentException("--overrides-zip is required with --overrides-dir");
        FileOps.requireRealDir(dir);
        TreeMap<String, Path> files = new TreeMap<>();
        try (var walk = Files.walk(dir)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (p.equals(dir) || Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) && !FileOps.isLinkLike(p)) continue;
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Links/special files are not allowed in overrides: " + dir.relativize(p));
                String rel = dir.relativize(p).toString().replace(java.io.File.separatorChar, '/');
                String why = ManifestValidator.checkOverridePath(rel);
                if (why != null) throw new IOException("Overrides file '" + rel + "' rejected: " + why);
                files.put(rel, p);
            }
        }
        if (files.isEmpty()) throw new IOException("Overrides directory contains no files");
        Files.createDirectories(zip.toAbsolutePath().getParent());
        List<Model.OverrideFile> list = new ArrayList<>();
        try (var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) {
            for (var e : files.entrySet()) {
                var entry = new java.util.zip.ZipEntry(e.getKey());
                entry.setTimeLocal(java.time.LocalDateTime.of(1980, 1, 1, 0, 0));
                out.putNextEntry(entry);
                Files.copy(e.getValue(), out);
                out.closeEntry();
                list.add(new Model.OverrideFile(e.getKey(), FileOps.sha256(e.getValue()), Files.size(e.getValue())));
            }
        }
        String name = zip.getFileName().toString();
        return new Model.Overrides(name, "https://github.com/" + owner + "/" + repo + "/releases/download/" + tag + "/" + name,
                FileOps.sha256(zip), Files.size(zip), list);
    }

    private static void generate(Map<String, String> o) throws IOException {
        Model.Manifest m = build(Path.of(req(o, "mods-dir")), req(o, "owner"), req(o, "repo"), req(o, "tag"),
                o.getOrDefault("pack-name", "Zerith Modpack"), req(o, "pack-version"), o.getOrDefault("mc-version", "1.21.1"),
                o.containsKey("overrides-dir") ? Path.of(o.get("overrides-dir")) : null,
                o.containsKey("overrides-zip") ? Path.of(o.get("overrides-zip")) : null);
        Path out = Path.of(req(o, "output"));
        Files.createDirectories(out.toAbsolutePath().getParent());
        FileOps.writeJson(out, m);
        System.out.println("Wrote " + out + " (" + m.mods().size() + " mods)");
    }

    private static void validate(List<String> pos, Map<String, String> o) throws IOException {
        if (pos.size() != 1) throw new IllegalArgumentException("validate <manifest.json> [--mods-dir D]");
        Model.Manifest m = FileOps.readJson(Path.of(pos.get(0)), Model.Manifest.class);
        ManifestValidator.validate(m, Policy.defaults());
        if (o.containsKey("mods-dir")) {
            Path dir = Path.of(o.get("mods-dir"));
            for (Model.Mod mod : m.mods()) {
                Path f = dir.resolve(mod.filename());
                if (!Files.isRegularFile(f)) throw new IOException("Missing file: " + mod.filename());
                if (!FileOps.sha256(f).equalsIgnoreCase(mod.sha256())) throw new IOException("Hash mismatch: " + mod.filename());
            }
        }
        if (m.overrides() != null && o.containsKey("overrides-zip")) {
            Path z = Path.of(o.get("overrides-zip"));
            if (!Files.isRegularFile(z) || !FileOps.sha256(z).equalsIgnoreCase(m.overrides().sha256())) throw new IOException("Overrides archive hash mismatch: " + z.getFileName());
        }
        System.out.println("Manifest OK (" + m.mods().size() + " mods" + (m.overrides() == null ? "" : ", " + m.overrides().files().size() + " override files") + ")");
    }

    private static String req(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing --" + k);
        return v;
    }
}
