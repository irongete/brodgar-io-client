package io.brodgar.addon;

import io.brodgar.addon.registry.Entry;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

/**
 * <b>The install record</b> — {@code .registry.json} inside an addon's folder, the one mark that says the hub
 * put it there: the version the hub named, the sha256 of the package it came from, and when. A folder
 * with one is the hub's to update and remove, and the version here is what the update check compares against
 * the hub's latest, semver, because the hub's versions are ordered. A folder without one is the player's own,
 * put there by hand: the client never deletes it, and the update check compares its manifest's
 * {@code version} instead, where that is a version the hub's order reads — a by-hand manifest's need not be.
 * An Update press on it installs the hub's newer version over it, and the folder carries a record from then on.
 *
 * <p>It lives in the folder rather than in a preference because it belongs to the folder: it travels with
 * it under a launcher update and dies with it when the player deletes the folder. It is written
 * <b>last</b> by a stage, so a staged folder without one is incomplete and is swept, never moved in.
 */
public final class InstallRecord {
    /** The file's name inside the addon's folder. */
    public static final String NAME = ".registry.json";

    public final String version, sha256, installedAt;

    InstallRecord(String version, String sha256, String installedAt) {
        this.version = version;
        this.sha256 = sha256;
        this.installedAt = installedAt;
    }

    /**
     * The record inside {@code folder}, or {@code null} when there is none — a by-hand folder. A file that is
     * there but does not read as a record raises, naming the file and why: it is neither a by-hand folder nor
     * a known install, and the caller says which it takes it for.
     */
    public static InstallRecord read(File folder) throws IOException {
        File f = new File(folder, NAME);
        if(!f.isFile())
            return null;
        Object doc;
        try {
            doc = Json.parse(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
        } catch(RuntimeException e) {
            throw new IOException(f + ": " + Refusal.reason(e));
        }
        if(!(doc instanceof Map))
            throw new IOException(f + ": not a JSON object");
        Map<?, ?> m = (Map<?, ?>)doc;
        Object version = m.get("version");
        if(!(version instanceof String) || ((String)version).isEmpty())
            throw new IOException(f + ": 'version' is missing");
        Object sha = m.get("sha256");
        Object at = m.get("installed_at");
        return new InstallRecord((String)version, (sha instanceof String) ? (String)sha : null,
                                 (at instanceof String) ? (String)at : null);
    }

    /** Write the record for {@code e} into {@code folder}: its version, its sha256, and now, in UTC. */
    static void write(File folder, Entry e) throws IOException {
        LuaTable t = new LuaTable();
        t.set("version", LuaValue.valueOf(e.version));
        t.set("sha256", LuaValue.valueOf(e.sha256));
        t.set("installed_at", LuaValue.valueOf(Instant.now().toString()));
        Files.write(new File(folder, NAME).toPath(), Json.write(t).getBytes(StandardCharsets.UTF_8));
    }
}
