package io.brodgar.addon.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <b>One item of the hub's list</b> — an addon as {@code GET /addons/api/addons} describes it, at its latest
 * version: who it is, what it declares, where its package is, and what the hub's own page shows beside it —
 * the tags, the icon, the downloads and the dates. Immutable, and a snapshot: the hub is asked again for a
 * fresh one, never this object.
 *
 * <p>The shape is the hub's item ({@code brodgar-io-addons}, its {@code README.md}), read leniently field by
 * field — a string that is absent reads {@code null}, a list that is absent reads empty, a number that is
 * absent reads {@code 0} — with two exceptions that refuse the whole item: an {@code id} that is not
 * <b>2–64 of {@code a-z 0-9 - _}</b>, the hub's own rule, because the id names a folder under {@code addons/}
 * and the client unpacks into it; and an item that is not an object at all.
 *
 * <p>{@link #apiVersion} is kept <b>as the wire carries it</b> — a {@code String}, a {@code Double} the reader
 * hands a JSON number over as, or {@code null} — so that {@code ApiVersion.parse(Object)} makes the one
 * decision the client makes about every manifest, and its refusal reaches the row that shows the item. The
 * three dates are kept the same way, ISO-8601 strings as the hub writes them: the panel formats them, and a
 * date that does not parse is shown as nothing rather than refused.
 */
public class Entry {
    /** The hub's rule for an id: it is also a folder name under {@code addons/}. */
    static final Pattern ID = Pattern.compile("^[a-z0-9_-]{2,64}$");

    public final String id, name, author, owner, summary, version, sha256, packageUrl;
    public final boolean official;
    /** The {@code api_version} field as the wire carries it: a {@code String}, a {@code Double} or {@code null}. */
    public final Object apiVersion;
    /** The permission entries the manifest declared, as written; empty when it declared none. Unmodifiable. */
    public final List<String> permissions;
    /** The {@code network.hosts} the manifest declared; empty when none. Unmodifiable. */
    public final List<String> hosts;
    /** The tags the owner filed it under, in the hub's order; empty when none. Unmodifiable. */
    public final List<String> tags;
    /** The URL of its icon — {@code icon.png} at the package's root, served by the hub — or {@code null}. */
    public final String icon;
    /** The package's size in bytes, {@code 0} when the hub did not say. */
    public final long size;
    /** How many times the hub has served a package of it, every version counted. */
    public final long downloads;
    /** When the latest version was published, when a version last was, and when the addon was made: ISO-8601, or {@code null}. */
    public final String publishedAt, updatedAt, createdAt;
    /** Its manifest's {@code "bundle": true} (168): it packs the addons its {@link #dependencies} name. */
    public final boolean bundle;
    /** Its manifest's {@code dependencies} and {@code optional_dependencies}, in order; empty when none. Unmodifiable. */
    public final List<Dependency> dependencies, optionalDependencies;

    /** One entry of a dependency list, as the hub hands it over: an id, and the minimum version it names or {@code null}. */
    public static final class Dependency {
        public final String id, min;

        Dependency(String id, String min) {
            this.id = id;
            this.min = min;
        }

        /** As the manifest writes it: {@code <id>}, or {@code <id>>=<min>}. */
        public String toString() {
            return (min == null) ? id : (id + ">=" + min);
        }
    }

    Entry(String id, String name, String author, String owner, boolean official, String summary, String version,
          Object apiVersion, List<String> permissions, List<String> hosts, List<String> tags, String icon,
          long size, long downloads, String sha256, String packageUrl, String publishedAt, String updatedAt,
          String createdAt, boolean bundle, List<Dependency> dependencies, List<Dependency> optionalDependencies) {
        this.id = id; this.name = name; this.author = author; this.owner = owner; this.official = official;
        this.summary = summary; this.version = version; this.apiVersion = apiVersion;
        this.permissions = permissions; this.hosts = hosts; this.tags = tags; this.icon = icon;
        this.size = size; this.downloads = downloads; this.sha256 = sha256; this.packageUrl = packageUrl;
        this.publishedAt = publishedAt; this.updatedAt = updatedAt; this.createdAt = createdAt;
        this.bundle = bundle; this.dependencies = dependencies; this.optionalDependencies = optionalDependencies;
    }

    /** The same item, field for field: what {@link Detail} builds on. */
    Entry(Entry e) {
        this(e.id, e.name, e.author, e.owner, e.official, e.summary, e.version, e.apiVersion, e.permissions,
             e.hosts, e.tags, e.icon, e.size, e.downloads, e.sha256, e.packageUrl, e.publishedAt, e.updatedAt,
             e.createdAt, e.bundle, e.dependencies, e.optionalDependencies);
    }

    /**
     * An item as {@code Json.parse} hands one over: a {@code Map} of the hub's fields. Raises
     * {@link IllegalArgumentException} naming what is wrong with an item that is not one — the caller turns
     * that into the list's own refusal, because a hub that hands out such an item is not the hub the client
     * was written against, and half a list read from it is not an answer.
     */
    public static Entry of(Object item) {
        if(!(item instanceof Map))
            throw new IllegalArgumentException("an item of the list is not an object");
        Map<?, ?> m = (Map<?, ?>)item;
        String id = str(m, "id");
        if((id == null) || !ID.matcher(id).matches())
            throw new IllegalArgumentException("an item's id " + ((id == null) ? "is missing" : "'" + id + "'")
                + " is not 2-64 of a-z 0-9 - _");
        String name = str(m, "name");
        return new Entry(id,
                         (name == null) ? id : name,
                         str(m, "author"),
                         str(m, "owner"),
                         Boolean.TRUE.equals(m.get("official")),
                         str(m, "summary"),
                         str(m, "version"),
                         m.get("api_version"),
                         strs(m, "permissions"),
                         strs(m, "network_hosts"),
                         strs(m, "tags"),
                         str(m, "icon"),
                         num(m, "size"),
                         num(m, "downloads"),
                         str(m, "sha256"),
                         str(m, "package_url"),
                         str(m, "published_at"),
                         str(m, "updated_at"),
                         str(m, "created_at"),
                         Boolean.TRUE.equals(m.get("bundle")),
                         deps(m, "dependencies"),
                         deps(m, "optional_dependencies"));
    }

    /**
     * A dependency list as the hub writes it, {@code [{ "id", "min" }]}, read leniently like every other field:
     * an entry that is not an object with a string {@code id} is dropped, and a {@code min} that is not a
     * string reads {@code null}. Empty when absent -- a hub from before 168 says nothing, and nothing is needed.
     */
    static List<Dependency> deps(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if(!(v instanceof List))
            return Collections.emptyList();
        List<Dependency> out = new ArrayList<Dependency>();
        for(Object o : (List<?>)v) {
            if(!(o instanceof Map))
                continue;
            String id = str((Map<?, ?>)o, "id");
            if((id != null) && !id.isEmpty())
                out.add(new Dependency(id, str((Map<?, ?>)o, "min")));
        }
        return Collections.unmodifiableList(out);
    }

    /** Every item of a list, in order — the {@code items} of a hub answer. */
    public static List<Entry> list(Object items) {
        if(!(items instanceof List))
            throw new IllegalArgumentException("'items' is not an array");
        List<Entry> out = new ArrayList<Entry>();
        for(Object o : (List<?>)items)
            out.add(of(o));
        return out;
    }

    /** Who to show as the author: the author the owner wrote, else the owner — the hub's own page does the same. */
    public String by() {
        return ((author != null) && !author.isEmpty()) ? author : owner;
    }

    static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return (v instanceof String) ? (String)v : null;
    }

    static long num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number)v).longValue() : 0L;
    }

    /** The strings of an array field, anything that is not a string dropped; empty when absent. */
    static List<String> strs(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if(!(v instanceof List))
            return Collections.emptyList();
        List<String> out = new ArrayList<String>();
        for(Object o : (List<?>)v)
            if(o instanceof String)
                out.add((String)o);
        return Collections.unmodifiableList(out);
    }

    @Override
    public String toString() {
        return id + " v" + version;
    }
}
