package io.brodgar.addon;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * One loaded addon: its {@link Manifest}, folder, Lua environment, and load status. Per-addon
 * environments give each addon its own globals (sandbox hardening arrives in a later phase).
 */
public final class Addon {
    public final Manifest manifest;
    public final Path dir;
    public final Globals env;
    public String error;   // null if the addon loaded cleanly

    Addon(Manifest manifest, Path dir, Globals env) {
        this.manifest = manifest;
        this.dir = dir;
        this.env = env;
    }

    /** Run the addon's Lua files in manifest order. On the first failure, record it and stop. */
    void run() {
        for(String file : manifest.files) {
            try {
                Path fp = dir.resolve(file);
                String src = new String(Files.readAllBytes(fp), StandardCharsets.UTF_8);
                LuaValue chunk = env.load(src, "@" + manifest.id + "/" + file);
                chunk.call();
            } catch(Exception e) {
                error = file + ": " + e.getMessage();
                return;
            }
        }
    }
}
