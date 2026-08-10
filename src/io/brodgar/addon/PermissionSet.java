package io.brodgar.addon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What one addon's {@code manifest.json} declared in its {@code "permissions"} array, parsed and validated
 * against the {@link Permission} catalogue: the entries <b>as written</b> (an exact key, or a
 * {@code <prefix>.*} group) plus the set of catalogue keys they actually grant.
 *
 * <p>A group matches on the key's own dot segments — {@code item.*} reaches every {@code item.<verb>} and can
 * never reach {@code itemx.y} — so a prefix is a real group rather than a substring. The bare {@code "*"}
 * parses <b>nowhere</b>: the allow-all shape has no spelling at all rather than a privileged one, and the
 * trusted {@code :lua} REPL owner is built from {@link #all()} instead ({@link Manifest#internal}). An entry
 * that is neither a known key nor a known group <b>throws</b>, listing the whole vocabulary — the addon then
 * fails to load and the AddOns panel shows the reason, the ordinary path for every malformed manifest field.
 * A typo must not quietly grant nothing and fail at the first call, which is the silent downgrade the
 * catalogue exists to make impossible.
 *
 * <p>Immutable. The declared entries are what the user is shown (a group reads as one line); the granted set
 * is what the gate and the consent record compare — a manifest that swaps {@code item.use} for {@code item.*}
 * has widened, whatever its entry count says.
 */
public final class PermissionSet {
    /** The declaration with nothing in it — an ordinary read-only addon. */
    public static final PermissionSet NONE =
        new PermissionSet(Collections.<String>emptyList(), EnumSet.noneOf(Permission.class));

    private final List<String> entries;
    private final Set<Permission> granted;

    private PermissionSet(List<String> entries, Set<Permission> granted) {
        this.entries = Collections.unmodifiableList(entries);
        this.granted = Collections.unmodifiableSet(granted);
    }

    /** Every permission in the catalogue — the trusted engine-internal owner's declaration. */
    public static PermissionSet all() {
        List<String> entries = new ArrayList<String>();
        for(Permission p : Permission.values())
            entries.add(p.key);
        return new PermissionSet(entries, EnumSet.allOf(Permission.class));
    }

    /**
     * Parse and validate a manifest's {@code "permissions"} array. Blank entries are skipped; duplicates
     * collapse. Throws {@link IllegalArgumentException} naming the whole vocabulary on the bare {@code "*"},
     * an unknown key or an unknown group.
     */
    public static PermissionSet parse(List<String> declared) {
        if((declared == null) || declared.isEmpty())
            return NONE;
        List<String> entries = new ArrayList<String>();
        EnumSet<Permission> granted = EnumSet.noneOf(Permission.class);
        for(String raw : declared) {
            String e = raw.trim();
            if(e.isEmpty())
                continue;
            if(e.equals("*"))
                throw new IllegalArgumentException("'permissions' may not contain \"*\": declare the keys this"
                    + " addon needs, or a \"<prefix>.*\" group. " + vocabulary());
            if(e.endsWith(".*")) {
                String prefix = e.substring(0, e.length() - 2) + ".";
                boolean any = false;
                for(Permission p : Permission.values()) {
                    if(p.key.startsWith(prefix)) {
                        granted.add(p);
                        any = true;
                    }
                }
                if(!any)
                    throw new IllegalArgumentException("'permissions' entry \"" + e + "\" is not a permission"
                        + " group: no key starts with \"" + prefix + "\". " + vocabulary());
            } else {
                Permission p = Permission.byKey(e);
                if(p == null)
                    throw new IllegalArgumentException("'permissions' entry \"" + e + "\" is not a permission."
                        + " " + vocabulary());
                granted.add(p);
            }
            if(!entries.contains(e))
                entries.add(e);
        }
        return entries.isEmpty() ? NONE : new PermissionSet(entries, granted);
    }

    /** The catalogue, spelled for an error message: every key, plus how a group is written. */
    private static String vocabulary() {
        return "Valid permissions: " + Permission.keyList()
            + " — or a group, a key's prefix followed by \".*\" (item.*, kin.*).";
    }

    /** Whether this declaration grants {@code p} (by an exact key or by a group that covers it). */
    public boolean has(Permission p) {
        return (p != null) && granted.contains(p);
    }

    /** Whether the addon declared nothing at all. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** How many entries were declared — a group counts as the ONE line the user reads, not as its members. */
    public int size() {
        return entries.size();
    }

    /** The entries exactly as declared, in manifest order ({@code item.*}, {@code player.move}). */
    public List<String> entries() {
        return entries;
    }

    /** The catalogue keys this declaration grants. Unmodifiable. */
    public Set<Permission> granted() {
        return granted;
    }

    /** The granted keys as strings, in catalogue order — what the consent record persists. */
    public List<String> keys() {
        List<String> out = new ArrayList<String>();
        for(Permission p : Permission.values())
            if(granted.contains(p))
                out.add(p.key);
        return out;
    }

    /** The declared entries, comma-separated — the AddOns panel's row tooltip. */
    public String toString() {
        return String.join(", ", entries);
    }

    /** Convenience for the probes and the synthetic manifests: parse a literal list of entries. */
    static PermissionSet of(String... entries) {
        return parse(Arrays.asList(entries));
    }
}
