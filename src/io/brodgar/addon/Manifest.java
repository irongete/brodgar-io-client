package io.brodgar.addon;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A single addon's {@code manifest.json} (metadata + which Lua files to run). JSON format per
 * {@code specs/addons/decisions.md} D-016; parsed with {@link Json}.
 */
public final class Manifest {
    public final String id, name, version, author, description;
    public final int apiVersion;
    public final List<String> files, dependencies, optionalDependencies;
    /** Saved-variable declarations (name + scope) the engine persists/restores — see {@link SavedVar}. */
    public final List<SavedVar> savedVariables;
    /**
     * Declared permissions (spec 12 / D-027) — capabilities the addon must ask for before the bridge grants
     * them. Currently the only permission is {@code "actions"} (the gated write/automation tier, `hafen.act.*`
     * + the per-subsystem gated verbs). An array so it can grow into finer categories later
     * ({@code "actions.move"}, {@code "actions.items"}, …) without a format change.
     */
    public final List<String> permissions;

    /** Whether this addon declared the {@code "actions"} (write/automation) permission — see D-027. */
    public boolean usesActions() {
        return permissions.contains("actions");
    }

    /**
     * One {@code saved_variables} declaration: a global Lua table the engine persists to JSON and
     * restores on load (D-002/D-023). {@code account} = shared across all characters
     * ({@code savedata/account/<addon>.json}); otherwise per-character
     * ({@code savedata/<genus>_<char>/<addon>.json}).
     */
    public static final class SavedVar {
        public final String name;
        public final boolean account;

        SavedVar(String name, boolean account) {
            this.name = name;
            this.account = account;
        }
    }

    private Manifest(String id, String name, String version, String author, String description,
                     int apiVersion, List<String> files, List<String> dependencies,
                     List<String> optionalDependencies, List<SavedVar> savedVariables,
                     List<String> permissions) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.apiVersion = apiVersion;
        this.files = files;
        this.dependencies = dependencies;
        this.optionalDependencies = optionalDependencies;
        this.savedVariables = savedVariables;
        this.permissions = permissions;
    }

    /**
     * A synthetic manifest for an engine-internal resource owner (e.g. the {@code :lua} REPL), which
     * is not loaded from disk and runs no files. Lets the REPL own events/timers like a real addon.
     */
    static Manifest internal(String id) {
        List<String> none = Collections.emptyList();
        List<SavedVar> novars = Collections.emptyList();
        // The engine-internal owner (the :lua REPL) is the trusted operator console → it holds every
        // permission (still subject to the global master switch, like any addon — D-027).
        List<String> allperms = Collections.singletonList("actions");
        return new Manifest(id, id, "0", "brodgar", "engine-internal owner", 1, none, none, none, novars, allperms);
    }

    /** Read and validate {@code <dir>/manifest.json}. Throws with a clear message on any problem. */
    public static Manifest load(Path dir) throws Exception {
        Path mf = dir.resolve("manifest.json");
        String text = new String(Files.readAllBytes(mf), StandardCharsets.UTF_8);
        Object root = Json.parse(text);
        if(!(root instanceof Map))
            throw new IllegalArgumentException("manifest.json must be a JSON object");
        @SuppressWarnings("unchecked")
        Map<String, Object> m = (Map<String, Object>)root;

        String id = str(m, "id", true);
        String folder = dir.getFileName().toString();
        if(!id.equals(folder))
            throw new IllegalArgumentException("id '" + id + "' must match the folder name '" + folder + "'");

        List<String> files = strlist(m, "files");
        if(files.isEmpty())
            throw new IllegalArgumentException("'files' must list at least one .lua file");

        String name = str(m, "name", false);
        return new Manifest(id, (name != null) ? name : id,
                            str(m, "version", false), str(m, "author", false),
                            str(m, "description", false), intv(m, "api_version", 1),
                            files, strlist(m, "dependencies"), strlist(m, "optional_dependencies"),
                            savedvars(m), strlist(m, "permissions"));
    }

    /**
     * Parse {@code saved_variables}: an array whose entries are either a bare table name (per-character)
     * or {@code {"name": ..., "scope": "account"}} for account-wide storage (D-023). Any scope other
     * than {@code "account"} (incl. absent) is per-character.
     */
    private static List<SavedVar> savedvars(Map<String, Object> m) {
        List<SavedVar> out = new ArrayList<SavedVar>();
        Object v = m.get("saved_variables");
        if(v == null) return out;
        if(!(v instanceof List)) throw new IllegalArgumentException("'saved_variables' must be an array");
        for(Object o : (List<?>)v) {
            if(o instanceof String) {
                out.add(new SavedVar((String)o, false));
            } else if(o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>)o;
                String nm = str(e, "name", true);
                out.add(new SavedVar(nm, "account".equals(e.get("scope"))));
            } else {
                throw new IllegalArgumentException("'saved_variables' entries must be a string or an object");
            }
        }
        return out;
    }

    private static String str(Map<String, Object> m, String key, boolean required) {
        Object v = m.get(key);
        if(v == null) {
            if(required) throw new IllegalArgumentException("missing '" + key + "'");
            return null;
        }
        if(!(v instanceof String)) throw new IllegalArgumentException("'" + key + "' must be a string");
        return (String)v;
    }

    private static int intv(Map<String, Object> m, String key, int def) {
        Object v = m.get(key);
        if(v == null) return def;
        if(!(v instanceof Number)) throw new IllegalArgumentException("'" + key + "' must be a number");
        return ((Number)v).intValue();
    }

    private static List<String> strlist(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<String>();
        Object v = m.get(key);
        if(v == null) return out;
        if(!(v instanceof List)) throw new IllegalArgumentException("'" + key + "' must be an array");
        for(Object o : (List<?>)v)
            if(o instanceof String) out.add((String)o);
        return out;
    }
}
