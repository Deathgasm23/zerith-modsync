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
    }

    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static IllegalArgumentException bad(String s) { return new IllegalArgumentException(s); }
}
