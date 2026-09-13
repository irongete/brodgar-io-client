package io.brodgar.addon.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * <b>One item of the hub's list</b> — an addon as {@code GET /addons/api/addons} describes it, at its latest
 * version: who it is, what it declares, and where its package is. Immutable, and a snapshot: the hub is asked
 * again for a fresh one, never this object.
 *
 * <p>The shape is the hub's item ({@code brodgar-io-addons}, its {@code README.md}), read leniently field by
 * field — a string that is absent reads {@code null}, a list that is absent reads empty, a number that is
 * absent reads {@code 0} — with two exceptions that refuse the whole item: an {@code id} that is not
 * <b>2–64 of {@code a-z 0-9 - _}</b>, the hub's own rule, because the id names a folder under {@code addons/}
 * and the client unpacks into it; and an item that is not an object at all.
 *
 * <p>{@link #apiVersion} is kept <b>as the wire carries it</b> — a {@code String}, a {@code Double} the reader
 * hands a JSON number over as, or {@code null} — so that {@code ApiVersion.parse(Object)} makes the one
 * decision the client makes about every manifest, and its refusal reaches the row that shows the item.
 */
public final class Entry {
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
    /** The package's size in bytes, {@code 0} when the hub did not say. */
    public final long size;

    Entry(String id, String name, String author, String owner, boolean official, String summary, String version,
          Object apiVersion, List<String> permissions, List<String> hosts, long size, String sha256,
          String packageUrl) {
        this.id = id; this.name = name; this.author = author; this.owner = owner; this.official = official;
        this.summary = summary; this.version = version; this.apiVersion = apiVersion;
        this.permissions = permissions; this.hosts = hosts; this.size = size; this.sha256 = sha256;
        this.packageUrl = packageUrl;
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
                         num(m, "size"),
                         str(m, "sha256"),
                         str(m, "package_url"));
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

    private static String str(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return (v instanceof String) ? (String)v : null;
    }

    private static long num(Map<?, ?> m, String key) {
        Object v = m.get(key);
        return (v instanceof Number) ? ((Number)v).longValue() : 0L;
    }

    /** The strings of an array field, anything that is not a string dropped; empty when absent. */
    private static List<String> strs(Map<?, ?> m, String key) {
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
