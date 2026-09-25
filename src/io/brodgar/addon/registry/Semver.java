package io.brodgar.addon.registry;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>A published version, ordered the hub's way</b> — {@code MAJOR.MINOR.PATCH} with an optional pre-release
 * ({@code 1.2.0-beta.1}); no build metadata, no {@code v} prefix, no leading zeros. It is the hub's own rule
 * (its {@code semver.js}), written here so that "update available" means on this side exactly what "latest"
 * means on that one: a pre-release sorts before the release it precedes, and pre-release identifiers compare
 * numerically where both are numbers, a number before a word otherwise, and a shorter list before a longer
 * one that starts with it.
 *
 * <p>Only the hub's order needs the rule. An addon's own {@code manifest.json} may carry any string as its
 * {@code version} — the client's {@code Manifest} takes it as it is — so a by-hand folder's version is asked
 * {@link #valid} first, and one that is not a version is never compared: it is not behind the hub's, and it
 * meets no minimum. The hub's versions and the install record the hub wrote are versions.
 */
public final class Semver implements Comparable<Semver> {
    private static final Pattern FORM = Pattern.compile(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$");
    private static final Pattern DIGITS = Pattern.compile("^[0-9]+$");

    public final int major, minor, patch;
    /** The pre-release identifiers, dot-split, in order; empty for a release. Unmodifiable. */
    public final List<String> pre;
    private final String text;

    private Semver(int major, int minor, int patch, List<String> pre, String text) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.pre = pre;
        this.text = text;
    }

    /** {@code s} as a version, or {@code null} when it is not one — the hub's {@code parse}. */
    public static Semver parse(String s) {
        if(s == null)
            return null;
        Matcher m = FORM.matcher(s);
        if(!m.matches())
            return null;
        try {
            List<String> pre = (m.group(4) == null) ? Collections.<String>emptyList()
                : Collections.unmodifiableList(Arrays.asList(m.group(4).split("\\.")));
            return new Semver(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                              Integer.parseInt(m.group(3)), pre, s);
        } catch(NumberFormatException e) {
            return null;                      // a run of digits too long for an int is not a version either
        }
    }

    /** Whether {@code s} is a version by the hub's rule. */
    public static boolean valid(String s) {
        return parse(s) != null;
    }

    /**
     * {@code -1}, {@code 0} or {@code 1} as the hub orders {@code a} against {@code b}. Both must be versions;
     * one that is not raises, naming it, because a comparison against a string that is not a version has no
     * answer that could be trusted for "update available".
     */
    public static int compare(String a, String b) {
        Semver x = parse(a), y = parse(b);
        if(x == null)
            throw new IllegalArgumentException("not a version: '" + a + "' (a version is MAJOR.MINOR.PATCH,"
                + " such as 1.2.0, with an optional pre-release such as 1.2.0-beta.1)");
        if(y == null)
            throw new IllegalArgumentException("not a version: '" + b + "' (a version is MAJOR.MINOR.PATCH,"
                + " such as 1.2.0, with an optional pre-release such as 1.2.0-beta.1)");
        return x.compareTo(y);
    }

    @Override
    public int compareTo(Semver o) {
        if(major != o.major) return (major < o.major) ? -1 : 1;
        if(minor != o.minor) return (minor < o.minor) ? -1 : 1;
        if(patch != o.patch) return (patch < o.patch) ? -1 : 1;
        // A pre-release sorts before the release it precedes.
        if(pre.isEmpty() && o.pre.isEmpty()) return 0;
        if(pre.isEmpty()) return 1;
        if(o.pre.isEmpty()) return -1;
        int n = Math.min(pre.size(), o.pre.size());
        for(int i = 0; i < n; i++) {
            int c = compareIdents(pre.get(i), o.pre.get(i));
            if(c != 0)
                return c;
        }
        return Integer.signum(pre.size() - o.pre.size());
    }

    /** Two pre-release identifiers: numerically when both are numbers, a number before a word, else by text. */
    private static int compareIdents(String a, String b) {
        boolean an = DIGITS.matcher(a).matches(), bn = DIGITS.matcher(b).matches();
        if(an && bn) {
            // compared as numbers, however long: strip leading zeros, then the longer run is the larger
            String x = a.replaceFirst("^0+(?=.)", ""), y = b.replaceFirst("^0+(?=.)", "");
            if(x.length() != y.length())
                return (x.length() < y.length()) ? -1 : 1;
            return Integer.signum(x.compareTo(y));
        }
        if(an) return -1;                     // numeric identifiers sort before alphanumeric ones
        if(bn) return 1;
        return Integer.signum(a.compareTo(b));
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof Semver) && (compareTo((Semver)o) == 0);
    }

    @Override
    public int hashCode() {
        int h = (major * 31 + minor) * 31 + patch;
        for(String p : pre)           // a numeric identifier hashes by its value, as compareIdents orders it
            h = h * 31 + (DIGITS.matcher(p).matches() ? p.replaceFirst("^0+(?=.)", "") : p).hashCode();
        return h;
    }

    /** The version as it was written. */
    @Override
    public String toString() {
        return text;
    }
}
