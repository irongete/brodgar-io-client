package io.brodgar.addon;

import org.luaj.vm2.LuaError;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * <b>The one containment check</b>: a name that came from data — a manifest {@code files} entry, an asset
 * path, a {@code .gltf}'s external URI, the character folder the server named — turned into the file it
 * <b>really</b> means inside a root the client owns, or refused.
 *
 * <p><b>Why one.</b> Four sites used to answer this question and only two of them asked anything: the
 * loader had a lexical check, the {@code .gltf} loader had a second copy of it with its own message, and
 * the {@code files} list and the store's scope key had none at all. Four answers to one question is four
 * chances for a hole and, when one of them is fixed, three copies that are not. So there is one answer, and
 * every path a name from data can name goes through it — which is also what lets the refusal be worded once.
 *
 * <p><b>It follows links, and that is the point.</b> A lexical {@code normalize()} + {@code startsWith} says
 * a name is contained when it is <i>spelled</i> as contained, and a directory link shipped inside an addon
 * folder is spelled exactly that way while pointing anywhere on the disk. So the check is made on the
 * <b>real</b> path — {@code toRealPath()} on both sides — and a link out of the root is refused like any
 * other path out of it.
 *
 * <p><b>A name that does not exist yet still gets an answer</b>, because most callers here name a file
 * before it is written and one of them names a file the addon merely claims to ship. So {@link #real}
 * canonicalises the deepest ancestor that <i>does</i> exist and appends the rest: the part that exists is
 * where a link could be, the part that does not cannot be one. What comes back is a legal name, never a
 * promise that a file is there — a missing file is the <b>caller's</b> refusal, in the caller's own words,
 * after this one said the name is legal.
 *
 * <p><b>An internal {@code a/../b} stays legal.</b> The name is normalised before it is resolved, so what a
 * caller reads is the path this returns rather than the spelling it passed, and the two cannot disagree.
 *
 * <p>Not instantiable, and it holds nothing: one question, one static answer.
 */
final class Inside {
    private Inside() {}

    /**
     * The real path {@code name} means inside {@code root}, or a {@link LuaError} saying it is not inside.
     * {@code verb} names the caller in the message, the way every other refusal in this bridge does
     * ({@code "hafen.asset"}, {@code "manifest 'files'"}, {@code "store"}).
     *
     * <p>Refused, in this order: an empty name; a name no filesystem can spell; an absolute, drive-lettered
     * or UNC name, before the disk is touched at all; and finally a name whose real path is not under the
     * root's real path — which is where a link lands. A name equal to the root itself is inside it.
     */
    static Path inside(Path root, String name, String verb) {
        Path base = real(root);
        String what = what(base);
        if((name == null) || name.isEmpty())
            throw new LuaError(verb + ": path must be a non-empty string, relative to '" + what + "'");
        Path rel;
        try {
            rel = Paths.get(name);
        } catch(RuntimeException e) {              // InvalidPathException — a name no filesystem can spell
            throw new LuaError(verb + ": invalid path '" + name + "'");
        }
        // A Windows drive-less "/foo" and a drive-relative "C:foo" are not isAbsolute, and both have a root.
        if(rel.isAbsolute() || (rel.getRoot() != null))
            throw new LuaError(verb + ": '" + name + "' is absolute — every path here is relative to '"
                + what + "'");
        Path p = real(base.resolve(rel));
        if(!p.startsWith(base))
            throw new LuaError(verb + ": '" + name + "' is not inside '" + what + "'");
        return p;
    }

    /**
     * The canonical path of {@code p}: {@code toRealPath()} of the deepest ancestor that exists, with the
     * segments below it appended. Every link in the part that exists is followed; the part that does not
     * exist cannot be a link, so appending it is exact. A path with no existing ancestor at all — nothing
     * a real root produces — answers its own absolute, normalised spelling.
     */
    private static Path real(Path p) {
        Path abs = p.toAbsolutePath().normalize();
        Path head = abs, tail = null;
        while(head != null) {
            try {
                Path r = head.toRealPath();
                return (tail == null) ? r : r.resolve(tail);
            } catch(IOException | RuntimeException e) {        // not there (yet), or not readable
                Path fn = head.getFileName();
                if(fn != null)
                    tail = (tail == null) ? fn : fn.resolve(tail);
                head = head.getParent();
            }
        }
        return abs;
    }

    /** What the root <b>is</b>, as the refusal names it: the folder's own name, which is what a caller checks. */
    private static String what(Path base) {
        Path fn = base.getFileName();
        return (fn == null) ? base.toString() : fn.toString();
    }
}
