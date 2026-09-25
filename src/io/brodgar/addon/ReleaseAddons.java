package io.brodgar.addon;

import haven.Utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static io.brodgar.addon.AddonManager.log;

/**
 * <b>The addons a client release carries</b> — {@code release-addons/} beside the client jar, one folder per
 * addon, the ones {@code etc/release-addons} named when the release was built. They are not the player's
 * {@code addons/}: a launcher unpacks every release over the client's folder, replacing what the zip holds, so a
 * release carrying them in {@code addons/} would put its own copy back, at every update, over one the player
 * had updated from the hub, edited or deleted. They are <b>offered</b> instead, each once: at a start,
 * {@link #install} copies into {@code addons/} every one the client has not offered before and whose id has no
 * folder there yet, and remembers every id it offered, installed or not — so one the player deletes is never
 * put back, and one that was already there is left as it is.
 *
 * <p><b>An addon installed from the release arrives with its permissions granted</b>: the keys and the hosts its
 * copy in {@code release-addons/} declares are recorded as consented ({@link AddonRegistry#recordConsent}), as
 * if the player had approved them in the consent dialog. Whoever made the release made the client, and the copy
 * granted is the one that release carried, read from the client's own folder — never from {@code addons/},
 * where anything may stand. Nothing else is decided here: the enabled set is not touched, so an id the player
 * turned off before stays off, and a new one is enabled as every new addon is. From then on the folder is the
 * player's, like any other put there by hand: the AddOns manager updates it from the hub, and a later version
 * asking for more is asked about as any addon's is.
 *
 * <p>Runs once a start, from {@code AddonManager.boot}, after the hub's stages are applied and before anything
 * loads, so the first load finds the folder and its consent together. A copy is made under
 * {@code addons/.staging/} and moved into place whole, so an interrupted one is never scanned as an addon — the
 * next apply sweeps it, since it carries no install record — and an id whose copy failed is not remembered, so
 * the next start tries it again.
 */
final class ReleaseAddons {
    private ReleaseAddons() {}

    /** The folder beside the client jar a release carries its addons in. */
    static final String DIR = "release-addons";
    /** The ids the releases have offered this client, installed or found already there. */
    private static final String PREF_OFFERED = "addons/release.offered";

    /** {@code release-addons/} beside the client jar. */
    static File dir() {
        try {
            return Utils.srcpath(AddonManager.class).resolveSibling(DIR).toFile();
        } catch(RuntimeException e) {
            return new File(DIR);
        }
    }

    /** {@link #install(File, File)} from {@link #dir} into the addons folder. */
    static void install() {
        install(dir(), AddonRegistry.addonDir());
    }

    /**
     * Offer every addon in {@code from} the client has not offered before: copy it into {@code addons} where no
     * folder of its id stands and no stage of the hub's waits for one, record what its manifest declares as
     * consented, and remember the id either way. One whose manifest does not parse is skipped with a log line
     * and not remembered, so a later release carrying it mended offers it. No {@code from} folder is a client
     * that carries none — a development build — and nothing happens.
     */
    static void install(File from, File addons) {
        File[] subs = from.listFiles(File::isDirectory);
        if(subs == null)
            return;
        Arrays.sort(subs, (x, y) -> x.getName().compareTo(y.getName()));
        Set<String> offered = offered();
        Set<String> now = new LinkedHashSet<String>(offered);
        for(File src : subs) {
            String id = src.getName();
            if(offered.contains(id) || !new File(src, "manifest.json").isFile())
                continue;
            Manifest m;
            try {
                m = Manifest.load(src.toPath());
            } catch(Exception e) {
                log("release: " + id + " is not installed: " + Refusal.reason(e));
                continue;
            }
            File live = new File(addons, id);
            if(live.exists() || new File(Staging.dir(addons), id).exists()) {
                now.add(id);                  // the player's already, or the hub's on its way: left as it is
                log("release: " + id + " is in addons/ already, and stays as it is");
                continue;
            }
            try {
                copyIn(src, live, addons);
            } catch(IOException e) {
                log("release: could not install " + id + ": " + Refusal.reason(e) + " -- tried again at the next start");
                continue;
            }
            now.add(id);
            if(m.permissions.isEmpty())
                log("release: installed " + id + " v" + m.version);
            else if(AddonRegistry.recordConsent(id, m.permissions, m.network))
                log("release: installed " + id + " v" + m.version + ", its permissions granted: " + m.permissions);
            else
                log("release: installed " + id + " v" + m.version + ", its permissions not granted");
        }
        if(!now.equals(offered))
            Utils.setprefsl(PREF_OFFERED, now);
    }

    /** The ids offered so far, in the order they were. */
    private static Set<String> offered() {
        List<String> l = Utils.getprefsl(PREF_OFFERED, new String[0]);
        return (l == null) ? new LinkedHashSet<String>() : new LinkedHashSet<String>(l);
    }

    /**
     * Copy {@code src} to {@code live} through {@code addons/.staging/<id>.release}, moved into place whole.
     * Raises naming what failed, and leaves nothing behind when it does.
     */
    private static void copyIn(File src, File live, File addons) throws IOException {
        File tmp = new File(Staging.dir(addons), live.getName() + ".release");
        if(tmp.exists() && !Staging.deleteTree(tmp))
            throw new IOException("could not clear " + tmp);
        try {
            copy(src, tmp);
            Files.move(tmp.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch(IOException e) {
            Staging.deleteTree(tmp);
            throw e;
        }
    }

    /** A file, or a folder and everything under it. */
    private static void copy(File from, File to) throws IOException {
        if(from.isDirectory()) {
            if(!to.isDirectory() && !to.mkdirs())
                throw new IOException("could not create " + to);
            File[] subs = from.listFiles();
            if(subs == null)
                throw new IOException("could not list " + from);
            for(File s : subs)
                copy(s, new File(to, s.getName()));
        } else {
            Files.copy(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
