package com.zerith.modsync.core;

import java.net.URI;
import java.util.*;
import java.util.regex.Pattern;

public final class ManifestValidator {
    public static final String SELF_ID = "zerith-modsync";
    static final Pattern SHA = Pattern.compile("[0-9a-fA-F]{64}");
    static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_.-]{0,127}");
    /** Conservative: GitHub rewrites other characters in asset names, and Windows dislikes them. */
    static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,150}\\.jar");

    private ManifestValidator() {}

    /** Fails closed with IllegalArgumentException on anything unexpected. */
    public static void validate(Model.Manifest m, Policy policy) {
        if (m == null || m.schemaVersion() != 1) throw bad("Unsupported or missing schemaVersion (expected 1)");
        Model.Pack p = m.modpack();
        if (p == null || blank(p.name()) || blank(p.version())) throw bad("modpack.name and modpack.version are required");
        if (!policy.minecraftVersion().equals(p.minecraftVersion())) throw bad("Manifest targets Minecraft " + p.minecraftVersion() + ", expected " + policy.minecraftVersion());
        if (p.loader() == null || !policy.loader().equalsIgnoreCase(p.loader())) throw bad("Manifest loader is not " + policy.loader());
        if (m.mods() == null || m.mods().isEmpty()) throw bad("Manifest has no mods (refusing to wipe the mods folder)");
        if (m.mods().size() > 1000) throw bad("Too many mods in manifest");
        Set<String> ids = new HashSet<>(), files = new HashSet<>();
        long total = 0;
        boolean self = false;
        for (Model.Mod mod : m.mods()) {
            if (mod == null) throw bad("Null mod entry");
            if (mod.id() == null || !ID.matcher(mod.id()).matches()) throw bad("Invalid mod id: " + mod.id());
            if (!ids.add(mod.id())) throw bad("Duplicate mod id: " + mod.id());
            String f = mod.filename();
            if (f == null || !FILE.matcher(f).matches() || f.contains("..")) throw bad("Unsafe filename: " + f);
            if (!files.add(f.toLowerCase(Locale.ROOT))) throw bad("Duplicate filename: " + f);
            if (mod.sha256() == null || !SHA.matcher(mod.sha256()).matches()) throw bad("Invalid SHA-256 for " + f);
            try { policy.checkUrl(URI.create(Objects.requireNonNull(mod.url(), "url missing"))); }
            catch (RuntimeException e) { throw bad("Bad URL for " + f + ": " + e.getMessage()); }
            if (mod.size() != null) {
                if (mod.size() < 1 || mod.size() > policy.maxJarBytes()) throw bad("Invalid size for " + f);
                total += mod.size();
            }
            self |= SELF_ID.equals(mod.id());
        }
        if (total > policy.maxPackBytes()) throw bad("Pack exceeds size limit");
        if (!self) throw bad("Manifest must include the updater itself (" + SELF_ID + ")");
        if (m.overrides() != null) validateOverrides(m.overrides(), policy);
    }

    static final Pattern ZIP = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,150}[.]zip");
    static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9()@=,'+_\\[\\]-][A-Za-z0-9 .()@=,'+_\\[\\]-]*");
    static final Set<String> ROOTS = Set.of("config", "resourcepacks", "shaderpacks");
    static final Set<String> RESERVED = Set.of("con", "prn", "aux", "nul", "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    public static final String OWN_CONFIG = "config/zerith-modsync.json";

    /** Returns null if the path is a safe overrides target, else the reason. */
    public static String checkOverridePath(String path) {
        if (path == null || path.isEmpty() || path.length() > 240) return "bad length";
        if (path.equals("options.txt")) return null;
        String[] seg = path.split("/", -1);
        if (seg.length < 2 || !ROOTS.contains(seg[0])) return "only options.txt, config/, resourcepacks/ and shaderpacks/ may be synchronized";
        for (int i = 1; i < seg.length; i++) {
            String x = seg[i];
            if (x.isEmpty() || x.equals(".") || x.equals("..") || !SEGMENT.matcher(x).matches() || x.endsWith(" ") || x.endsWith("."))
                return "unsafe path segment '" + x + "'";
            String base = x.contains(".") ? x.substring(0, x.indexOf('.')) : x;
            if (RESERVED.contains(base.toLowerCase(Locale.ROOT))) return "reserved name '" + x + "'";
        }
        if (path.equalsIgnoreCase(OWN_CONFIG)) return "the updater's own config cannot be overridden";
        return null;
    }

    private static void validateOverrides(Model.Overrides o, Policy policy) {
        if (o.filename() == null || !ZIP.matcher(o.filename()).matches()) throw bad("Invalid overrides archive filename: " + o.filename());
        try { policy.checkUrl(URI.create(Objects.requireNonNull(o.url(), "url missing"))); }
        catch (RuntimeException e) { throw bad("Bad overrides URL: " + e.getMessage()); }
        if (o.sha256() == null || !SHA.matcher(o.sha256()).matches()) throw bad("Invalid SHA-256 for overrides archive");
        if (o.size() < 1 || o.size() > policy.maxPackBytes()) throw bad("Invalid overrides archive size");
        if (o.files() == null || o.files().isEmpty() || o.files().size() > 20_000) throw bad("Overrides must list 1..20000 files");
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (Model.OverrideFile f : o.files()) {
            if (f == null) throw bad("Null overrides entry");
            String why = checkOverridePath(f.path());
            if (why != null) throw bad("Overrides path '" + f.path() + "': " + why);
            if (!seen.add(f.path().toLowerCase(Locale.ROOT))) throw bad("Duplicate overrides path: " + f.path());
            if (f.sha256() == null || !SHA.matcher(f.sha256()).matches()) throw bad("Invalid SHA-256 for " + f.path());
            if (f.size() < 0 || f.size() > (1L << 30)) throw bad("Invalid size for " + f.path());
            total += f.size();
        }
        if (total > policy.maxPackBytes()) throw bad("Overrides exceed size limit");
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static IllegalArgumentException bad(String s) { return new IllegalArgumentException(s); }
}
