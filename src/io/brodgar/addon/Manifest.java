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
    /**
     * The {@code network} block's host allowlist (N2a / D-037): the hosts this addon may reach via
     * {@code hafen.http.*}. A capability-with-config gets its own manifest block (like {@code saved_variables}),
     * not a bare {@code permissions[]} string, because the declaration IS the allowlist. Lower-cased; empty ⇒
     * no {@code network} block ⇒ the addon has no network access. Entries may be an exact host or a
     * {@code *.domain} sub-domain wildcard; the special token {@code "*"} (used only by the internal REPL owner)
     * matches any host. See {@link #usesNetwork()} / {@link #hostAllowed(String)}.
     */
    public final List<String> network;

    /** Whether this addon declared the {@code "actions"} (write/automation) permission — see D-027. */
    public boolean usesActions() {
        return permissions.contains("actions");
    }

    /** Whether this addon declared a non-empty {@code network} block (grants gated {@code hafen.http}) — D-037. */
    public boolean usesNetwork() {
        return !network.isEmpty();
    }

    /**
     * Whether {@code host} is permitted by this addon's {@code network} allowlist (D-037): a case-insensitive
     * exact match, a {@code *.domain} wildcard (matching sub-domains, <b>not</b> the apex — {@code *.example.com}
     * matches {@code a.example.com} but not {@code example.com}), or the internal-owner {@code "*"} allow-all.
     */
    public boolean hostAllowed(String host) {
        if((host == null) || host.isEmpty())
            return false;
        String h = host.toLowerCase(java.util.Locale.ROOT);
        for(String pat : network) {
            if(pat.equals("*"))
                return true;                                   // REPL / internal owner: any host
            if(pat.startsWith("*.")) {
                String suffix = pat.substring(1);              // ".example.com"
                if(h.endsWith(suffix) && (h.length() > suffix.length()))
                    return true;
            } else if(pat.equals(h)) {
                return true;
            }
        }
        return false;
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
                     List<String> permissions, List<String> network) {
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
        this.network = network;
    }

    /**
     * A synthetic manifest for an engine-internal resource owner (e.g. the {@code :lua} REPL), which
     * is not loaded from disk and runs no files. Lets the REPL own events/timers like a real addon.
     */
    static Manifest internal(String id) {
        List<String> none = Collections.emptyList();
        List<SavedVar> novars = Collections.emptyList();
        // The engine-internal owner (the :lua REPL) is the trusted operator console → it declares every
        // permission, so its hafen.act.* verbs are granted (D-027; D-028 — per-addon, no global switch).
        List<String> allperms = Collections.singletonList("actions");
        // The REPL is the trusted operator console → allow-all network too (private IPs stay blocked).
        List<String> allnet = Collections.singletonList("*");
        return new Manifest(id, id, "0", "brodgar", "engine-internal owner", 1, none, none, none, novars, allperms, allnet);
    }

    /** A synthetic manifest declaring NOTHING — the shape of an ordinary read-only addon. Probes only. */
    static Manifest test(String id) {
        List<String> none = Collections.emptyList();
        List<SavedVar> novars = Collections.emptyList();
        return new Manifest(id, id, "0", "brodgar", "probe owner", 1, none, none, none, novars, none, none);
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
                            savedvars(m), strlist(m, "permissions"), networkhosts(m));
    }

    /**
     * Parse the {@code network} block (D-037): an object with a {@code hosts} array of allowlisted host
     * patterns (exact or {@code *.domain}). Absent block ⇒ no network. Host patterns are lower-cased; the
     * {@code "*"} allow-all is reserved for the internal owner and rejected here (a disk manifest must list
     * concrete hosts, so a third-party addon cannot grant itself the whole internet).
     */
    private static List<String> networkhosts(Map<String, Object> m) {
        List<String> out = new ArrayList<String>();
        Object v = m.get("network");
        if(v == null) return out;
        if(!(v instanceof Map)) throw new IllegalArgumentException("'network' must be an object with a 'hosts' array");
        @SuppressWarnings("unchecked")
        Map<String, Object> net = (Map<String, Object>)v;
        Object h = net.get("hosts");
        if(h == null) return out;
        if(!(h instanceof List)) throw new IllegalArgumentException("'network.hosts' must be an array of host strings");
        for(Object o : (List<?>)h) {
            if(!(o instanceof String))
                throw new IllegalArgumentException("'network.hosts' entries must be strings");
            String host = ((String)o).trim().toLowerCase(java.util.Locale.ROOT);
            if(host.isEmpty()) continue;
            if(host.equals("*"))
                throw new IllegalArgumentException("'network.hosts' may not contain \"*\" (list concrete hosts or *.domain)");
            out.add(host);
        }
        return out;
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
