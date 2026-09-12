package io.brodgar.addon;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>The version of the addon API</b> — the one an addon's {@code api_version} declares it was written
 * against, and the one this client implements ({@link #CURRENT}). Every site that needs a word about a
 * version asks this class: the manifest parses one here ({@link #parse}), the loader decides here whether an
 * addon is out of date ({@link #why}), and the AddOns panel's row reads its label here ({@link #label}).
 *
 * <p>A version is {@code "X.Y"}. <b>X is the generation</b> and moves on a hard cut — a documented name
 * removed or reshaped, so that an addon written against the old one raises. <b>Y is the edition</b> and moves
 * when a release adds a verb, a section or a key. There is no Z: a change that changes no documented contract
 * changes no number. The editions of one generation are additive, so an addon written against {@code 1.3}
 * has everything it declared against on any client implementing {@code 1.3} or later of the same generation,
 * and nothing it needs on a client implementing {@code 1.2} — which is the whole of the rule {@link #why}
 * applies: <b>same X, and Y at most the client's, or the addon is out of date.</b> An addon that declares no
 * version is out of date too, like a WoW {@code .toc} without an {@code ## Interface} line: the loader cannot
 * tell what it was written against, so it does not guess.
 *
 * <p>The number is the API's, never the client's release version: a release that adds nothing to the
 * documented contract leaves it where it was, so an addon is not outdated by a build that changed nothing it
 * could see.
 */
public final class ApiVersion {
    /**
     * The API this client implements. <b>The one literal</b>: {@code tools/docverbs.py} reads it from this
     * line and holds the version the documentation states to it, so a bump is this constant and the page.
     */
    public static final ApiVersion CURRENT = new ApiVersion(1, 0);

    /** The declared form: a generation without a leading zero, a point, an edition that may be {@code 0}. */
    private static final Pattern FORM = Pattern.compile("^([1-9][0-9]*)\\.(0|[1-9][0-9]*)$");

    public final int major, minor;

    ApiVersion(int major, int minor) {
        this.major = major;
        this.minor = minor;
    }

    /**
     * The {@code api_version} field as the manifest carries it, read as a version. {@code null} stays
     * {@code null} — the field is absent, which is a state ({@link #why}) and not an error. A {@code String}
     * of the form {@code "X.Y"} parses. Anything else throws, naming the form and {@link #CURRENT}, and the
     * throw is a manifest error like every other malformed field: a JSON <b>number</b> — {@code Json} hands
     * one over as a {@code Double}, so {@code 1} arrives as {@code 1.0} and {@code 1.10} as {@code 1.1}, which
     * is why the field is a string — a bare {@code "1"}, a {@code "0.1"}, a {@code "01.0"}, a {@code "1.0.0"},
     * a {@code "v1.0"}, or a run of digits too long for an {@code int}.
     */
    public static ApiVersion parse(Object v) {
        if(v == null)
            return null;
        if(!(v instanceof String))
            throw new IllegalArgumentException("'api_version' must be a string, not " + kind(v) + ": write"
                + " the API you wrote against as \"X.Y\", such as \"1.0\" -- this client implements API "
                + CURRENT);
        String s = (String)v;
        Matcher m = FORM.matcher(s);
        if(!m.matches())
            throw new IllegalArgumentException("'api_version' \"" + s + "\" is not of the form \"X.Y\" -- a"
                + " generation and an edition, whole numbers without a leading zero, such as \"1.0\" -- this"
                + " client implements API " + CURRENT);
        try {
            return new ApiVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch(NumberFormatException e) {
            throw new IllegalArgumentException("'api_version' \"" + s + "\" is out of range: each of X and Y"
                + " in \"X.Y\" is at most " + Integer.MAX_VALUE + ", such as \"1.0\" -- this client implements"
                + " API " + CURRENT);
        }
    }

    /** What a JSON value that is not a string is called in the refusal — the reader's own kinds, with an article. */
    private static String kind(Object v) {
        if(v instanceof Number) return "a number";
        if(v instanceof java.util.Map) return "an object";
        if(v instanceof java.util.List) return "an array";
        if(v instanceof Boolean) return "a boolean";
        return "a " + v.getClass().getSimpleName();
    }

    /**
     * <b>Why an addon declaring {@code declared} is out of date on this client</b>, as the sentence the log,
     * the panel's tooltip and the console say — or {@code null} when it is current and loads as any other.
     * Three sentences, one per way of being out of date, and the sentence names both numbers so the reader
     * knows which side to move: {@code null} (absent) → <i>declares no api_version, this client implements
     * 1.0</i>; another generation → <i>written for API 9.0, this client implements 1.0</i>; a later edition of
     * this generation → <i>too new: needs API 1.3 or newer, this client implements 1.0</i>.
     */
    public static String why(ApiVersion declared) {
        if(declared == null)
            return "declares no api_version, this client implements " + CURRENT;
        if(declared.major != CURRENT.major)
            return "written for API " + declared + ", this client implements " + CURRENT;
        if(declared.minor > CURRENT.minor)
            return "too new: needs API " + declared + " or newer, this client implements " + CURRENT;
        return null;
    }

    /**
     * The AddOns panel row's status for an out-of-date addon — {@code outdated (API 9.0, client 1.0)}, or
     * {@code outdated (no api_version, client 1.0)} for one that declares none — or {@code null} when
     * {@code declared} is current, exactly as {@link #why} answers {@code null} for it. The label carries the
     * two numbers and no sentence, because a row has room for a state and the tooltip has room for the why.
     */
    public static String label(ApiVersion declared) {
        if(why(declared) == null)
            return null;
        return "outdated (" + ((declared == null) ? "no api_version" : "API " + declared) + ", client " + CURRENT + ")";
    }

    @Override
    public boolean equals(Object o) {
        if(!(o instanceof ApiVersion))
            return false;
        ApiVersion v = (ApiVersion)o;
        return (v.major == major) && (v.minor == minor);
    }

    @Override
    public int hashCode() {
        return (major * 31) + minor;
    }

    /** The version as it is written: {@code 1.0}. */
    @Override
    public String toString() {
        return major + "." + minor;
    }
}
