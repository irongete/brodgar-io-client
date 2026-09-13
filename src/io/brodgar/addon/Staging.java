package io.brodgar.addon;

import io.brodgar.addon.registry.Entry;
import io.brodgar.addon.registry.Registry;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static io.brodgar.addon.AddonManager.log;

/**
 * <b>Where a package lands before it is an addon</b> — {@code addons/.staging/}, a sibling of every
 * {@code addons/<id>/} so that the move into place is a rename on one volume, and the rule that nothing
 * changes under a running addon: {@link #stage} checks a downloaded package and unpacks it into
 * {@code .staging/<id>/}, {@link #markRemove} leaves the mark {@code .staging/<id>.remove} for a folder that
 * is to go, and {@link #apply} moves what is staged into {@code addons/} and deletes what is marked at the
 * next reload, or at the next start, after every addon has been torn down and before any is loaded.
 *
 * <p><b>Every check comes before anything is kept.</b> The zip's sha256 must be the one the hub's item
 * advertised; every entry must sit under {@code <id>/}, climb nowhere and be relative; the count and the
 * unpacked size are bounded; and the unpacked folder must pass {@code Manifest.load}, the client's own
 * parser. The {@link InstallRecord} is written <b>last</b>, so a folder under {@code .staging/} that has no
 * record is an interrupted stage, and {@link #apply} sweeps it rather than moving it.
 *
 * <p>The folder being replaced or removed goes to {@code .trash/<id>-<millis>} first, and the trash is
 * emptied at the end of every apply, best-effort: on Windows a file a loaded addon held — a font — may still
 * be open, and a move it refuses simply waits for the next apply, which the next start runs before anything
 * is loaded. Both folders are invisible to the addon scan, which lists only folders with a
 * {@code manifest.json} directly inside them. Package-private, as {@code AddonRegistry.addonDir()} is; the
 * registry's statics are the door the panel goes through.
 */
final class Staging {
    private Staging() {}

    /** The two folders, beside the addons. */
    static final String DIR = ".staging", TRASH = ".trash";
    /** The suffix of a removal's mark, {@code .staging/<id>.remove}: an empty file, gone with the folder it names. */
    static final String REMOVE = ".remove";
    /** The most entries a package may unpack to, and the most bytes — the hub's own ceilings, and a little over. */
    static final int MAX_ENTRIES = 2000;
    static final long MAX_UNPACKED = 64L * 1024 * 1024;

    /** {@code addons/.staging/}. */
    static File dir(File addons) {
        return new File(addons, DIR);
    }

    /** Where a download of {@code id} lands: {@code addons/.staging/<id>.zip}. */
    static File zipFor(File addons, String id) {
        return new File(dir(addons), id + ".zip");
    }

    /** The mark that says {@code addons/<id>/} is to go: {@code addons/.staging/<id>.remove}. */
    static File markFor(File addons, String id) {
        return new File(dir(addons), id + REMOVE);
    }

    /**
     * Mark {@code addons/<id>/} for removal: the empty file {@link #markFor} names, which {@link #apply} turns
     * into the folder's deletion and deletes with it. Raises {@link IOException} when the mark cannot be
     * written, naming the file. Nothing else is touched: the folder stays as it is until the apply.
     */
    static void markRemove(File addons, String id) throws IOException {
        File d = dir(addons);
        if(!d.isDirectory() && !d.mkdirs())
            throw new IOException("could not create " + d);
        Files.write(markFor(addons, id).toPath(), new byte[0]);
    }

    /** Take a removal's mark back, if there is one — a stage of the same id is the later word. Best-effort. */
    static void unmark(File addons, String id) {
        File m = markFor(addons, id);
        if(m.isFile() && !m.delete())
            log("staging: could not delete " + m);
    }

    /** The ids marked for removal: every {@code .staging/<id>.remove}, in the folder's order. */
    static Set<String> removals(File addons) {
        Set<String> out = new LinkedHashSet<String>();
        File[] subs = dir(addons).listFiles();
        if(subs == null)
            return out;
        for(File s : subs) {
            String n = s.getName();
            if(s.isFile() && n.endsWith(REMOVE) && (n.length() > REMOVE.length()))
                out.add(n.substring(0, n.length() - REMOVE.length()));
        }
        return out;
    }

    /**
     * Check {@code zip} against {@code e} and unpack it into {@code addons/.staging/<e.id>/}, the record last.
     * Raises {@link IOException} naming the first thing wrong — the two digests, the offending entry, the
     * manifest's own refusal — and leaves <b>nothing</b> under {@code .staging/<id>/} when it does. An earlier
     * stage of the same id is replaced whole. Hands back the staged folder.
     */
    static File stage(File addons, Entry e, File zip) throws IOException {
        String got = sha256(zip);
        if((e.sha256 == null) || !got.equalsIgnoreCase(e.sha256))
            throw new IOException("the package's sha256 is " + got + ", the hub advertised " + e.sha256);
        if((e.version == null) || e.version.isEmpty())
            throw new IOException("the hub's item for " + e.id + " names no version");
        File dest = new File(dir(addons), e.id);
        if(dest.exists())
            deleteTree(dest);
        if(!dest.mkdirs())
            throw new IOException("could not create " + dest);
        try {
            unpack(e.id, zip, dest);
            try {
                Manifest.load(dest.toPath());
            } catch(Exception x) {
                throw new IOException("manifest.json: " + Refusal.reason(x));
            }
            InstallRecord.write(dest, e);
        } catch(IOException x) {
            deleteTree(dest);
            throw x;
        } catch(RuntimeException x) {
            deleteTree(dest);
            throw x;
        }
        return dest;
    }

    /** Every entry of {@code zip} under {@code dest}, each name passed through {@link #relative} first. */
    private static void unpack(String id, File zip, File dest) throws IOException {
        int count = 0;
        long total = 0;
        ZipInputStream in = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
        try {
            byte[] chunk = new byte[16384];
            for(ZipEntry ze = in.getNextEntry(); ze != null; ze = in.getNextEntry()) {
                String name = ze.getName();
                if(++count > MAX_ENTRIES)
                    throw new IOException("the package has more than " + MAX_ENTRIES + " entries");
                String rel = relative(id, name);
                File f = rel.isEmpty() ? dest : new File(dest, rel);
                if(ze.isDirectory() || rel.isEmpty()) {
                    if(!f.isDirectory() && !f.mkdirs())
                        throw new IOException("could not create " + f);
                    continue;
                }
                File parent = f.getParentFile();
                if(!parent.isDirectory() && !parent.mkdirs())
                    throw new IOException("could not create " + parent);
                OutputStream out = new FileOutputStream(f);
                try {
                    int n;
                    while((n = in.read(chunk)) >= 0) {
                        total += n;
                        if(total > MAX_UNPACKED)
                            throw new IOException("the package unpacks to more than " + (MAX_UNPACKED / (1024 * 1024)) + " MB");
                        out.write(chunk, 0, n);
                    }
                } finally {
                    out.close();
                }
            }
        } finally {
            in.close();
        }
        if(count == 0)
            throw new IOException("the package is empty");
    }

    /**
     * An entry's path <b>under</b> {@code <id>/}, {@code /}-joined, or {@code ""} for the folder itself: the
     * package's canonical shape is {@code <id>/manifest.json}, {@code <id>/…}. Refuses, naming the entry, one
     * that is not under {@code <id>/}, that climbs ({@code ..}), or that is absolute — a leading separator,
     * or a drive letter. Both separators are read as one, because the client writes the path on Windows too.
     */
    static String relative(String id, String name) throws IOException {
        if((name == null) || name.isEmpty())
            throw new IOException("an entry of the package has no name");
        if(name.startsWith("/") || name.startsWith("\\") || name.matches("^[A-Za-z]:.*"))
            throw new IOException("the entry '" + name + "' is an absolute path");
        String[] parts = name.split("[/\\\\]", -1);
        StringBuilder rel = new StringBuilder();
        for(int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if(p.equals(".."))
                throw new IOException("the entry '" + name + "' climbs out of the package");
            if(p.isEmpty() || p.equals(".")) {
                if(i == parts.length - 1)
                    continue;                                    // a directory entry's trailing separator
                throw new IOException("the entry '" + name + "' has an empty path segment");
            }
            if(i == 0) {
                if(!p.equals(id))
                    throw new IOException("the entry '" + name + "' is not under " + id + "/");
                continue;
            }
            if(rel.length() > 0)
                rel.append('/');
            rel.append(p);
        }
        return rel.toString();
    }

    /** The lower-case hex SHA-256 of a file. */
    static String sha256(File f) throws IOException {
        MessageDigest md = Registry.sha256();
        InputStream in = new BufferedInputStream(new FileInputStream(f));
        try {
            byte[] chunk = new byte[16384];
            int n;
            while((n = in.read(chunk)) >= 0)
                md.update(chunk, 0, n);
        } finally {
            in.close();
        }
        return Registry.hex(md.digest());
    }

    /**
     * What is staged and complete: id → the record's version, for every {@code .staging/<id>/} that carries
     * a record. A folder without one is not listed — it is {@link #apply}'s to sweep.
     */
    static Map<String, String> pending(File addons) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        File[] subs = dir(addons).listFiles();
        if(subs == null)
            return out;
        for(File s : subs) {
            if(!s.isDirectory())
                continue;
            try {
                InstallRecord rec = InstallRecord.read(s);
                if(rec != null)
                    out.put(s.getName(), rec.version);
            } catch(IOException e) {
                log("staging: " + Refusal.reason(e));
            }
        }
        return out;
    }

    /**
     * Apply every mark and every complete stage, one line in the log per id. <b>The marks first</b>: a
     * marked folder goes to {@code .trash/<id>-<millis>}, an atomic rename, and the mark with it — and a
     * stage of the same id is swept before, because a mark is the player's last word on that id and a folder
     * that is to go is not one to replace. Then the stages: the folder each replaces goes to the trash the
     * same way, the staged one takes its name; a stage without a record is swept. A move the file system
     * refuses — a held file — is logged with its reason and left for the next apply, a folder that was there
     * put back where it was and a mark left standing; the trash is emptied last, best-effort. Runs where no
     * addon is loaded: between a reload's teardown and its load, and at boot.
     */
    static void apply(File addons) {
        File[] subs = dir(addons).listFiles();
        if(subs == null)
            return;
        for(File s : subs) {
            String n = s.getName();
            if(!s.isFile() || !n.endsWith(REMOVE) || (n.length() == REMOVE.length()))
                continue;
            String id = n.substring(0, n.length() - REMOVE.length());
            File stage = new File(dir(addons), id);
            if(stage.isDirectory() && !deleteTree(stage))     // never loaded, so nothing holds it
                log("staging: could not sweep the stage of " + id);
            File live = new File(addons, id);
            try {
                boolean there = live.exists();               // a folder the player deleted by hand is gone already
                if(there)
                    trash(addons, live);
                if(!s.delete())
                    throw new IOException("could not delete " + s);
                if(there)
                    log("removed " + id);
            } catch(IOException e) {
                log("could not remove " + id + ": " + Refusal.reason(e) + " -- restart to apply");
            }
        }
        for(File s : subs) {
            if(!s.isDirectory())                              // a stage a mark swept above reads as none
                continue;
            String id = s.getName();
            InstallRecord rec;
            try {
                rec = InstallRecord.read(s);
            } catch(IOException e) {
                log("staging: " + Refusal.reason(e) + " -- swept");
                deleteTree(s);
                continue;
            }
            if(rec == null) {
                log("staging: swept an incomplete stage of " + id);
                deleteTree(s);
                continue;
            }
            File live = new File(addons, id);
            File old = null;
            try {
                if(live.exists())
                    old = trash(addons, live);
                Files.move(s.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE);
                log("installed " + id + " v" + rec.version);
            } catch(IOException e) {
                log("could not apply " + id + " v" + rec.version + ": " + Refusal.reason(e) + " -- restart to apply");
                if((old != null) && !live.exists()) {
                    try {
                        Files.move(old.toPath(), live.toPath(), StandardCopyOption.ATOMIC_MOVE);
                    } catch(IOException x) {
                        log("could not put " + id + " back from " + old + ": " + Refusal.reason(x));
                    }
                }
            }
        }
        emptyTrash(addons);
    }

    /**
     * Move a live folder into the trash, {@code .trash/<name>-<millis>}, an atomic rename on the one volume;
     * hands back where it went. Raises {@link IOException} when the trash cannot be made or the file system
     * refuses the move — a file of the folder still held.
     */
    private static File trash(File addons, File live) throws IOException {
        File trash = new File(addons, TRASH);
        if(!trash.isDirectory() && !trash.mkdirs())
            throw new IOException("could not create " + trash);
        File old = new File(trash, live.getName() + "-" + System.currentTimeMillis());
        Files.move(live.toPath(), old.toPath(), StandardCopyOption.ATOMIC_MOVE);
        return old;
    }

    /** Delete every leftover download, {@code .staging/*.zip}: at boot, nothing can be in flight. */
    static void sweepDownloads(File addons) {
        File[] subs = dir(addons).listFiles();
        if(subs == null)
            return;
        for(File s : subs) {
            if(s.isFile() && s.getName().endsWith(".zip") && !s.delete())
                log("staging: could not delete " + s);
        }
    }

    /** Delete what the trash holds, best-effort: a folder a held file keeps alive stays for the next apply. */
    private static void emptyTrash(File addons) {
        File[] subs = new File(addons, TRASH).listFiles();
        if(subs == null)
            return;
        for(File s : subs)
            deleteTree(s);
    }

    /** Delete a file or a whole folder, best-effort; answers whether everything went. */
    static boolean deleteTree(File f) {
        boolean ok = true;
        File[] subs = f.listFiles();
        if(subs != null) {
            for(File s : subs)
                ok &= deleteTree(s);
        }
        return f.delete() && ok;
    }
}
