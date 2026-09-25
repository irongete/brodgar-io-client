package io.brodgar.addon.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * <b>One addon's page</b> — what {@code GET /addons/api/addons/:id} answers: the {@link Entry} the list
 * shows, plus what only the page carries — the links the owner filed, the long description, the screenshots,
 * every live version, newest first, and (168) the addons it needs as the list shows them and the bundles that
 * include it. Immutable and a snapshot, like the item it extends: the hub is asked again for a fresh one.
 *
 * <p>Read as leniently as an item is: a field that is absent reads {@code null}, empty or {@code 0}; the
 * item's own two refusals (an id that is not one, an answer that is not an object) are this class's too, and
 * a version whose {@code version} is not a string, or a member or a bundle that is not an item, is dropped
 * rather than refusing the page.
 */
public final class Detail extends Entry {
    /** Whether the hub hides it from the list — never for what an anonymous client is answered, kept for the record. */
    public final boolean hidden;
    /** The links the owner filed — {@code repo}, {@code homepage}, {@code discord} — in the hub's order, each an http(s) URL. Unmodifiable. */
    public final Map<String, String> links;
    /** The long description as the hub renders it, HTML, or {@code null} while the hub renders none. */
    public final String descriptionHtml;
    /** The screenshots' URLs, in the order the owner arranged them; empty when none. Unmodifiable. */
    public final List<String> images;
    /** The live versions, newest first; the first is the one the item's own fields describe. Unmodifiable. */
    public final List<Version> versions;
    /**
     * 168: the addons its {@link #dependencies} name, as the list shows them, in the manifest's order — a
     * bundle's contents. One the hub does not list is left out, and {@link #dependencies} still names it.
     * Empty when it needs none, and from a hub before 168. Unmodifiable.
     */
    public final List<Entry> members;
    /** 168: the listed bundles whose latest version includes it, each an id and a name. Empty when none. Unmodifiable. */
    public final List<Entry> bundles;

    /** One published version, as the page lists it. */
    public static final class Version {
        public final String version, sha256, changelog, publishedAt, packageUrl;
        /** The {@code api_version} as the wire carries it, exactly as on an {@link Entry}. */
        public final Object apiVersion;
        public final long size, downloads;

        Version(String version, Object apiVersion, long size, String sha256, String changelog, String publishedAt,
                long downloads, String packageUrl) {
            this.version = version; this.apiVersion = apiVersion; this.size = size; this.sha256 = sha256;
            this.changelog = changelog; this.publishedAt = publishedAt; this.downloads = downloads;
            this.packageUrl = packageUrl;
        }

        static Version of(Map<?, ?> m) {
            String v = str(m, "version");
            if(v == null)
                return null;
            return new Version(v, m.get("api_version"), num(m, "size"), str(m, "sha256"), str(m, "changelog"),
                               str(m, "published_at"), num(m, "downloads"), str(m, "package_url"));
        }
    }

    private Detail(Entry e, boolean hidden, Map<String, String> links, String descriptionHtml, List<String> images,
                   List<Version> versions, List<Entry> members, List<Entry> bundles) {
        super(e);
        this.hidden = hidden;
        this.links = links;
        this.descriptionHtml = descriptionHtml;
        this.images = images;
        this.versions = versions;
        this.members = members;
        this.bundles = bundles;
    }

    /** The items of an array field, in order; one that is not an item is dropped. Empty when absent. Unmodifiable. */
    static List<Entry> entries(Map<?, ?> m, String key) {
        Object v = m.get(key);
        if(!(v instanceof List))
            return Collections.emptyList();
        List<Entry> out = new ArrayList<Entry>();
        for(Object o : (List<?>)v) {
            try {
                out.add(Entry.of(o));
            } catch(IllegalArgumentException e) {
                // not an item: dropped, as a version that is not one is
            }
        }
        return Collections.unmodifiableList(out);
    }

    /** A page as {@code Json.parse} hands one over. Raises {@link IllegalArgumentException} as {@link Entry#of} does. */
    public static Detail of(Object doc) {
        Entry e = Entry.of(doc);
        Map<?, ?> m = (Map<?, ?>)doc;
        Map<String, String> links = new LinkedHashMap<String, String>();
        Object l = m.get("links");
        if(l instanceof Map) {
            for(Map.Entry<?, ?> kv : ((Map<?, ?>)l).entrySet()) {
                if((kv.getKey() instanceof String) && (kv.getValue() instanceof String)
                   && !((String)kv.getValue()).isEmpty())
                    links.put((String)kv.getKey(), (String)kv.getValue());
            }
        }
        List<Version> versions = new ArrayList<Version>();
        Object vs = m.get("versions");
        if(vs instanceof List) {
            for(Object o : (List<?>)vs) {
                if(o instanceof Map) {
                    Version v = Version.of((Map<?, ?>)o);
                    if(v != null)
                        versions.add(v);
                }
            }
        }
        return new Detail(e, Boolean.TRUE.equals(m.get("hidden")), Collections.unmodifiableMap(links),
                          str(m, "description_html"), strs(m, "images"), Collections.unmodifiableList(versions),
                          entries(m, "members"), entries(m, "bundles"));
    }
}
