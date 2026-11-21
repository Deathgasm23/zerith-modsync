package com.zerith.modsync.cli;

import com.zerith.modsync.core.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Manifest generator / validator.
 *   generate --mods-dir D --owner O --repo R --tag T --output FILE --pack-name N --pack-version V [--mc-version 1.21.1]
 *   validate FILE [--mods-dir D]
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
        Model.Manifest m = new Model.Manifest(1, new Model.Pack(packName, packVersion, mc, "fabric"), mods);
        ManifestValidator.validate(m, Policy.defaults());
        return m;
    }

    private static void generate(Map<String, String> o) throws IOException {
        Model.Manifest m = build(Path.of(req(o, "mods-dir")), req(o, "owner"), req(o, "repo"), req(o, "tag"),
                o.getOrDefault("pack-name", "Zerith Modpack"), req(o, "pack-version"), o.getOrDefault("mc-version", "1.21.1"));
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
        System.out.println("Manifest OK (" + m.mods().size() + " mods)");
    }

    private static String req(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Missing --" + k);
        return v;
    }
}
