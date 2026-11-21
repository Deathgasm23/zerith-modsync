package com.zerith.modsync.core;

import com.google.gson.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.*;

/** Static inspection of a JAR. Never loads or executes anything from it. */
public final class JarInspector {
    private static final Pattern MOD_ID = Pattern.compile("[a-z][a-z0-9_-]{1,63}");

    public record FabricInfo(String id, String version, JsonElement minecraftDepends) {}

    private JarInspector() {}

    public static FabricInfo inspect(Path jar) throws IOException {
        if (!Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS) || FileOps.isLinkLike(jar))
            throw new IOException("Not a regular file: " + jar.getFileName());
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            int count = 0;
            long total = 0;
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry e = en.nextElement();
                String n = e.getName();
                if (++count > 100_000 || n.startsWith("/") || n.contains("\\") || Arrays.asList(n.split("/")).contains(".."))
                    throw new IOException("Suspicious JAR entry in " + jar.getFileName() + ": " + n);
                total += Math.max(0, e.getSize());
                if (total > (4L << 30)) throw new IOException("JAR expands beyond limit: " + jar.getFileName());
            }
            ZipEntry meta = zip.getEntry("fabric.mod.json");
            if (meta == null) throw new IOException("Not a Fabric mod (no fabric.mod.json): " + jar.getFileName());
            if (meta.getSize() > (1 << 20)) throw new IOException("fabric.mod.json too large: " + jar.getFileName());
            String text;
            try (InputStream in = zip.getInputStream(meta)) {
                byte[] b = in.readNBytes((1 << 20) + 1);
                if (b.length > (1 << 20)) throw new IOException("fabric.mod.json too large: " + jar.getFileName());
                text = new String(b, StandardCharsets.UTF_8);
            }
            JsonObject o;
            try { o = JsonParser.parseString(text).getAsJsonObject(); }
            catch (RuntimeException e) { throw new IOException("Invalid fabric.mod.json in " + jar.getFileName()); }
            if (!o.has("id") || !o.get("id").isJsonPrimitive()) throw new IOException("fabric.mod.json has no id: " + jar.getFileName());
            String id = o.get("id").getAsString();
            if (!MOD_ID.matcher(id).matches()) throw new IOException("Invalid mod id '" + id + "' in " + jar.getFileName());
            String version = o.has("version") && o.get("version").isJsonPrimitive() ? o.get("version").getAsString() : "";
            JsonElement mc = o.has("depends") && o.get("depends").isJsonObject() ? o.getAsJsonObject("depends").get("minecraft") : null;
            return new FabricInfo(id, version, mc);
        } catch (ZipException e) {
            throw new IOException("Corrupt JAR " + jar.getFileName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Best-effort check of a Fabric "minecraft" dependency range against a version.
     * Returns null when satisfied or unknown-but-harmless, otherwise a message.
     */
    public static String checkMinecraft(JsonElement dep, String mcVersion) {
        if (dep == null || dep.isJsonNull()) return null;
        List<String> options = new ArrayList<>();
        if (dep.isJsonPrimitive()) options.add(dep.getAsString());
        else if (dep.isJsonArray()) dep.getAsJsonArray().forEach(x -> options.add(x.getAsString()));
        else return null;
        for (String opt : options) {
            Boolean ok = satisfies(opt.trim(), mcVersion);
            if (ok == null || ok) return null;
        }
        return "requires Minecraft " + options;
    }

    /** null = could not parse. Handles space-separated AND terms with =,>=,<=,>,<,~,^ and x-ranges. */
    static Boolean satisfies(String range, String v) {
        if (range.isEmpty() || range.equals("*")) return true;
        for (String term : range.split("\\s+")) {
            Boolean r = term(term, v);
            if (r == null) return null;
            if (!r) return false;
        }
        return true;
    }

    private static Boolean term(String t, String v) {
        try {
            if (t.equals("*")) return true;
            String op = "=";
            for (String o : new String[]{">=", "<=", ">", "<", "=", "~", "^"})
                if (t.startsWith(o)) { op = o; t = t.substring(o.length()); break; }
            String[] parts = t.split("\\.");
            int[] want = new int[parts.length];
            int fixed = parts.length;
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equals("x") || parts[i].equals("X") || parts[i].equals("*")) { fixed = i; break; }
                want[i] = Integer.parseInt(parts[i]);
            }
            int[] have = Arrays.stream(v.split("\\.")).mapToInt(Integer::parseInt).toArray();
            int c = cmp(have, want, Math.min(fixed, want.length), op.equals("=") || op.equals("~") || op.equals("^"));
            return switch (op) {
                case "=" -> fixed < parts.length ? prefix(have, want, fixed) : cmp(have, want, want.length, false) == 0;
                case ">=" -> cmp(have, want, want.length, false) >= 0;
                case "<=" -> cmp(have, want, want.length, false) <= 0;
                case ">" -> cmp(have, want, want.length, false) > 0;
                case "<" -> cmp(have, want, want.length, false) < 0;
                case "~" -> cmp(have, want, want.length, false) >= 0 && prefix(have, want, Math.max(1, want.length - 1));
                case "^" -> cmp(have, want, want.length, false) >= 0 && prefix(have, want, 1);
                default -> null;
            };
        } catch (RuntimeException e) { return null; }
    }

    private static boolean prefix(int[] have, int[] want, int n) {
        for (int i = 0; i < n; i++) if ((i < have.length ? have[i] : 0) != want[i]) return false;
        return true;
    }

    private static int cmp(int[] a, int[] b, int n, boolean unused) {
        for (int i = 0; i < n; i++) {
            int x = i < a.length ? a[i] : 0, y = b[i];
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }
}
