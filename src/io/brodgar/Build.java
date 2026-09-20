package io.brodgar;

import haven.Utils;

/**
 * <b>What this build of the client is</b>: the product's name, the name it gives the game's servers, and the
 * version {@code publish.ps1} stamped into the jar. The version is the one {@code build.xml} writes into
 * {@code buildinfo} beside the git revision — {@code 158.2-beta} for a beta, {@code 159} for a release, and
 * {@code dev} for every build the script did not name. Every site that says which client this is asks here,
 * so the window title, the wire and the registry's user agent cannot disagree about it.
 */
public final class Build {
    public static final String PRODUCT = "Brodgar client";
    /**
     * The name the game's servers see, as {@code Config.confid}: the session handshake sends
     * {@code "Hafen/Brodgar.io"} the way the official client sends {@code Hafen/<its id>} and Hurricane
     * {@code Hafen/Hurricane}; the HTTP user agent, the {@code conf.id} pair sent after a login and the
     * {@code haven.conf} comment of an uploaded screenshot carry the same word. A {@code config.client-id} in
     * a {@code local-boot.properties} still overrides it, as upstream allows — that file is gitignored, so no
     * release carries one.
     */
    public static final String ID = "Brodgar.io";
    /** The version of a build {@code publish.ps1} did not name, and of a jar without a {@code buildinfo}. */
    public static final String DEV = "dev";

    private Build() {}

    /** The version {@code build.xml} recorded, {@link #DEV} when it recorded none. */
    public static String version() {
        Object version = Utils.useragent.get("jar.version");
        return ((version == null) || version.toString().isEmpty()) ? DEV : version.toString();
    }

    /**
     * The product and its version, worded as the release tag is: {@code Brodgar client v158.2-beta},
     * {@code Brodgar client v159} — and {@code Brodgar client dev} for a build with no tag to name.
     */
    public static String label() {
        String version = version();
        return PRODUCT + " " + (version.equals(DEV) ? version : ("v" + version));
    }
}
