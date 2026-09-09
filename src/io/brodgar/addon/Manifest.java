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
     * Declared permissions (spec 12 / D-027) — the protected verbs this addon asks for, one key per verb from
     * the {@link Permission} catalogue ({@code "permissions": ["item.*", "player.move"]}), or a
     * {@code <prefix>.*} group. Parsed and validated at load: an unknown key or the bare {@code "*"} throws,
     * so the addon fails to load rather than silently being granted nothing. What the user consented to is
     * recorded per addon, so a manifest that later asks for more is disabled and asked again
     * ({@code AddonRegistry}).
     */
    public final PermissionSet permissions;
    /**
     * The {@code network} block's host allowlist (N2a / D-037): the hosts this addon may reach via
     * {@code hafen.http.*}. A capability-with-config gets its own manifest block (like {@code saved_variables}),
     * not a bare {@code permissions[]} string, because the declaration IS the allowlist. Lower-cased; empty ⇒
     * no {@code network} block ⇒ the addon has no network access. Entries may be an exact host or a
     * {@code *.domain} sub-domain wildcard; the special token {@code "*"} (used only by the internal REPL owner)
     * matches any host. This is the addon's <b>request</b>; what it may actually reach is the record of what the
     * user approved ({@link Addon#hostGranted}). See {@link #usesNetwork()} / {@link #hostAllowed(String)}.
     */
    public final List<String> network;
    /**
     * <b>Is this the engine-internal owner's manifest?</b> (audit2 B08) — true only for what {@link #internal}
     * mints, and there is no spelling on disk that reaches it. The {@code :lua} REPL passes through no
     * consent dialog, so it has no record to be granted by: this is the exemption the key gate tests
     * ({@link Addon#keyGranted}), the exact counterpart of {@link #anyHost()} on the host side.
     */
    private final boolean internal;

    /** @see #internal */
    public boolean internal() {
        return internal;
    }

    /** Whether this addon declared any protected permission at all (it is then opt-in — D-027/D-028). */
    public boolean declaresPermissions() {
        return !permissions.isEmpty();
    }

    /** Whether this addon declared a non-empty {@code network} block (grants the protected {@code hafen.http}) — D-037. */
    public boolean usesNetwork() {
        return !network.isEmpty();
    }

    /**
     * Whether this manifest carries the internal-owner allow-all token — only {@link #internal} mints it, and
     * {@link #networkhosts} refuses it on disk. The one declaration that reaches a host the user never approved,
     * because the {@code :lua} REPL is the operator's own console and passes through no consent dialog at all.
     */
    public boolean anyHost() {
        return network.contains("*");
    }

    /**
     * Whether {@code origin} is permitted by this addon's declared {@code network} allowlist (D-037) — what
     * this manifest <b>asks</b> for. What an addon may actually reach is what the user approved
     * ({@link Addon#hostGranted}); this answers the manifest's own question, and a refusal reads the two apart.
     */
    public boolean hostAllowed(String origin) {
        return hostMatches(network, origin);
    }

    /**
     * <b>The canonical origin of a url</b> — {@code scheme://host:port}, lower-cased, with the port always
     * written out (audit2 B08, ht-05). This is what the allowlist is matched against and what a refusal
     * names, because a grant is a grant to <b>one origin</b> and not to a bare host: a host the user approved
     * for {@code https} was equally reachable in cleartext, on any port, while the match was
     * {@code URL.getHost()} alone.
     */
    public static String origin(String scheme, String host, int port) {
        String sc = (scheme == null) ? "" : scheme.toLowerCase(java.util.Locale.ROOT);
        String h = (host == null) ? "" : host.toLowerCase(java.util.Locale.ROOT);
        return sc + "://" + h + ":" + ((port > 0) ? port : defaultPort(sc));
    }

    /** The port a scheme speaks when neither a url nor a pattern writes one. */
    public static int defaultPort(String scheme) {
        return "http".equals(scheme) ? 80 : 443;
    }

    /**
     * Whether {@code origin} ({@link #origin}) is matched by the host patterns {@code allow}: a
     * case-insensitive exact host match, a {@code *.domain} wildcard (matching sub-domains, <b>not</b> the
     * apex — {@code *.example.com} matches {@code a.example.com} but not {@code example.com}), or the
     * internal-owner {@code "*"} allow-all.
     *
     * <p><b>The scheme and the port are part of the match</b> (ht-05). A pattern may write either
     * ({@code "http://box.example.com:8080"}); what it leaves out it gets by default, and the default scheme
     * is {@code https} — so {@code "example.com"} grants {@code https://example.com:443} and nothing else,
     * and cleartext has to be asked for by name.
     *
     * <p>Static, and taking the patterns rather than reading a field, because two lists are matched with this
     * rule — the manifest's declaration and the record of what the user granted — and a second copy of
     * "a wildcard covers a sub-domain and not the apex" is the copy that ends up subtly different.
     */
    public static boolean hostMatches(List<String> allow, String origin) {
        if((allow == null) || (origin == null) || origin.isEmpty())
            return false;
        String want = origin.toLowerCase(java.util.Locale.ROOT);
        int ws = want.indexOf("://");
        String wscheme = (ws < 0) ? "" : want.substring(0, ws);
        String wrest = (ws < 0) ? want : want.substring(ws + 3);
        int wc = wrest.lastIndexOf(':');
        String whost = (wc < 0) ? wrest : wrest.substring(0, wc);
        String wport = (wc < 0) ? Integer.toString(defaultPort(wscheme)) : wrest.substring(wc + 1);
        for(String pat : allow) {
            if(pat.equals("*"))
                return true;                                   // REPL / internal owner: any origin
            String p = pat.toLowerCase(java.util.Locale.ROOT);
            int ps = p.indexOf("://");
            String pscheme = (ps < 0) ? "https" : p.substring(0, ps);   // no scheme written means https
            String prest = (ps < 0) ? p : p.substring(ps + 3);
            int pc = prest.lastIndexOf(':');
            String phost = (pc < 0) ? prest : prest.substring(0, pc);
            String pport = (pc < 0) ? Integer.toString(defaultPort(pscheme)) : prest.substring(pc + 1);
            if(!pscheme.equals(wscheme) || !pport.equals(wport))
                continue;
            if(phost.startsWith("*.")) {
                String suffix = phost.substring(1);            // ".example.com"
                if(whost.endsWith(suffix) && (whost.length() > suffix.length()))
                    return true;
            } else if(phost.equals(whost)) {
                return true;
            }
        }
        return false;
    }

    /**
     * <b>A declared pattern read as the origin it stands for</b> — {@code "example.com"} is
     * {@code "https://example.com:443"} and {@code "*.example.com"} is {@code "https://*.example.com:443"}.
     * What {@link #hostsUncovered} measures one list of patterns against another with: containment asks
     * whether the record already grants what the manifest declares, both sides are patterns, so the wanted
     * one has to be spelled the way {@link #hostMatches} reads an origin.
     */
    public static String patternOrigin(String pat) {
        if((pat == null) || pat.isEmpty() || pat.equals("*"))
            return pat;
        String p = pat.toLowerCase(java.util.Locale.ROOT);
        int ps = p.indexOf("://");
        String scheme = (ps < 0) ? "https" : p.substring(0, ps);
        String rest = (ps < 0) ? p : p.substring(ps + 3);
        int pc = rest.lastIndexOf(':');
        String host = (pc < 0) ? rest : rest.substring(0, pc);
        int port = (pc < 0) ? defaultPort(scheme) : parsePort(rest.substring(pc + 1));
        return origin(scheme, host, port);
    }

    /** A port out of a pattern, or {@code -1} when it is not a number (which {@link #networkhosts} refuses). */
    private static int parsePort(String s) {
        try {
            return Integer.parseInt(s);
        } catch(NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Those of {@code want} that no pattern in {@code allow} matches — <b>what a declaration adds</b> to a
     * record of what has already been granted, in declaration order. Empty ⇒ {@code want} is contained by
     * {@code allow}, which is the containment the consent policy tests: an addon whose declared hosts are all
     * covered is asking for nothing new and is not re-prompted.
     *
     * <p>Containment is {@link #hostMatches} applied once per entry, so it is the <b>same</b> wildcard rule the
     * gate asks — a re-prompt fires exactly when the gate would refuse, and never for a host it would allow.
     * Narrowing an approved {@code *.example.com} to {@code a.example.com} therefore adds nothing at all.
     */
    public static List<String> hostsUncovered(List<String> allow, List<String> want) {
        List<String> out = new ArrayList<String>();
        if(want == null)
            return out;
        for(String host : want) {
            if(!hostMatches(allow, patternOrigin(host)))
                out.add(host);
        }
        return out;
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
                     PermissionSet permissions, List<String> network, boolean internal) {
        this.internal = internal;
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
        // permission, so every protected verb is granted to it (D-027; D-028 — per-addon, no global switch).
        // Built from the catalogue rather than from a bare "*", which parses nowhere: the allow-all shape has
        // no spelling a disk manifest could reach for.
        PermissionSet allperms = PermissionSet.all();
        // The REPL is the trusted operator console → allow-all network too (private IPs stay blocked).
        List<String> allnet = Collections.singletonList("*");
        return new Manifest(id, id, "0", "brodgar", "engine-internal owner", 1, none, none, none, novars, allperms, allnet, true);
    }

    /** A synthetic manifest declaring NOTHING — the shape of an ordinary read-only addon. Probes only. */
    static Manifest test(String id) {
        List<String> none = Collections.emptyList();
        List<SavedVar> novars = Collections.emptyList();
        return new Manifest(id, id, "0", "brodgar", "probe owner", 1, none, none, none, novars,
                            PermissionSet.NONE, none, false);
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
        PermissionSet perms = PermissionSet.parse(strlist(m, "permissions"));
        List<String> hosts = networkhosts(m);
        // 093.4 (A-098): the network is a KEY now, and the hosts block is that key's argument. So a manifest
        // that lists hosts and asks for neither is refused HERE, at load, naming the two -- rather than
        // loading and dying at the first hafen.http() call, and rather than reaching the consent dialog with
        // a network declaration the user is shown nothing about.
        if(!hosts.isEmpty() && !perms.has(Permission.HTTP_GET) && !perms.has(Permission.HTTP_POST))
            throw new IllegalArgumentException("'network' declares hosts but no permission asks to reach them"
                + " -- add \"http.get\" (or \"http.post\", or the group \"http.*\") to 'permissions'. The key"
                + " says whether this addon may use the network, and the hosts say where; the user approves"
                + " both in one line when they enable it.");
        return new Manifest(id, (name != null) ? name : id,
                            str(m, "version", false), str(m, "author", false),
                            str(m, "description", false), intv(m, "api_version", 1),
                            files, strlist(m, "dependencies"), strlist(m, "optional_dependencies"),
                            savedvars(m), perms, hosts, false);
    }

    /**
     * Parse the {@code network} block (D-037): an object with a {@code hosts} array of allowlisted patterns,
     * each {@code [scheme://]host[:port]} where the host is exact or a {@code *.domain} wildcard. Absent
     * block ⇒ no network. Patterns are lower-cased; the {@code "*"} allow-all is reserved for the internal
     * owner and rejected here (a disk manifest must list concrete hosts, so a third-party addon cannot grant
     * itself the whole internet).
     *
     * <p><b>A wildcard covers ONE label and no more</b> (audit2 B08, pm-02). {@code "*.com"} used to be a
     * legal one-line declaration matching every {@code .com} host on the internet, because only the bare
     * {@code "*"} was refused; the suffix a wildcard leaves behind must now name at least two labels, so
     * {@code "*.example.com"} stands and {@code "*.com"} is the load error the panel shows.
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
            checkPattern(host);
            out.add(host);
        }
        return out;
    }

    /**
     * Refuse a {@code network.hosts} pattern that does not name one origin: an unknown scheme, a port that is
     * not a number, an empty host, or a wildcard wider than one label (pm-02). Every refusal is the load
     * error the AddOns panel shows, in the words the author needs to fix the line.
     */
    private static void checkPattern(String pat) {
        int ps = pat.indexOf("://");
        String scheme = (ps < 0) ? "https" : pat.substring(0, ps);
        String rest = (ps < 0) ? pat : pat.substring(ps + 3);
        if(!scheme.equals("http") && !scheme.equals("https"))
            throw new IllegalArgumentException("'network.hosts' entry \"" + pat + "\": the scheme must be"
                + " http or https (leave it out for https, which is the default)");
        int pc = rest.lastIndexOf(':');
        String host = (pc < 0) ? rest : rest.substring(0, pc);
        if(pc >= 0) {
            int port = parsePort(rest.substring(pc + 1));
            if((port < 1) || (port > 65535))
                throw new IllegalArgumentException("'network.hosts' entry \"" + pat + "\": the port after"
                    + " the host must be a number from 1 to 65535 (leave it out for the scheme's own)");
        }
        if(host.isEmpty())
            throw new IllegalArgumentException("'network.hosts' entry \"" + pat + "\" names no host");
        if(host.startsWith("*.")) {
            String suffix = host.substring(2);           // "example.com"
            if(suffix.indexOf('.') < 0)
                throw new IllegalArgumentException("'network.hosts' entry \"" + pat + "\": a wildcard covers"
                    + " the sub-domains of ONE domain, and \"" + suffix + "\" is a whole top-level domain —"
                    + " write \"*.<yourdomain>." + suffix + "\", or list the hosts. The user approves this"
                    + " list by reading it, and nobody can read the whole internet.");
        }
        if((host.indexOf('*', 1) >= 0) || (!host.startsWith("*.") && (host.indexOf('*') >= 0)))
            throw new IllegalArgumentException("'network.hosts' entry \"" + pat + "\": a wildcard is the"
                + " whole first label and nothing else — \"*.example.com\", never \"a*.example.com\"");
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
        // audit2 B14 (st-10): ONE ENTRY PER NAME, refused here rather than collapsed downstream. A name
        // declared twice is one table -- installStore mints it once and declaredVar finds the first -- so the
        // second entry named nothing, while every walk of this list answered it: hafen.store():list() said
        // "layout" twice about one table, and a second entry with the OTHER scope silently did not apply.
        // The manifest is where the shape is decided, and every other malformed entry throws here too.
        for(Object o : (List<?>)v) {
            String nm;
            boolean account;
            if(o instanceof String) {
                nm = (String)o;
                account = false;
            } else if(o instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> e = (Map<String, Object>)o;
                nm = str(e, "name", true);
                account = "account".equals(e.get("scope"));
            } else {
                throw new IllegalArgumentException("'saved_variables' entries must be a string or an object");
            }
            for(SavedVar had : out) {
                if(had.name.equals(nm))
                    throw new IllegalArgumentException("'saved_variables' declares \"" + nm + "\" twice --"
                        + " one name is one table, and only the first entry would have taken effect. Declare"
                        + " it once, in the scope you mean.");
            }
            out.add(new SavedVar(nm, account));
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

    /**
     * A string array field. A non-string entry <b>refuses</b>, like every other malformed thing in this
     * file: dropping it silently makes a mistyped {@code files} entry an addon that loads and runs less than
     * it says, and a mistyped {@code permissions} entry an addon whose gate later tells the author to
     * declare what they already tried to.
     */
    private static List<String> strlist(Map<String, Object> m, String key) {
        List<String> out = new ArrayList<String>();
        Object v = m.get(key);
        if(v == null) return out;
        if(!(v instanceof List)) throw new IllegalArgumentException("'" + key + "' must be an array");
        for(Object o : (List<?>)v) {
            if(!(o instanceof String))
                throw new IllegalArgumentException("'" + key + "' entries must be strings");
            out.add((String)o);
        }
        return out;
    }
}
