package io.brodgar.addon;

import haven.Fonts;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code hafen.locale()} — <b>what this client says</b>. One catalogue per addon, loaded as a document,
 * installed and released like a stylesheet, and read back through the strings it did not answer.
 *
 * <pre>
 *   hafen.locale():load(hafen.json():parse(hafen.asset():get("es.json"):text())):install()
 *   for _, m in ipairs(hafen.locale():miss():list()) do
 *     hafen.log():write(m:surface() .. "  " .. m:text())     -- ...and that is your next file
 *   end
 * </pre>
 *
 * <p><b>The section IS the catalogue</b> (§2.1: where a section contains exactly one thing, the section
 * object is that thing). An addon has one, minted with the sandbox and handed back by identity, so
 * {@code hafen.locale()} is the document, the switch and the report at once.
 *
 * <p><b>It writes client-local and its release undoes it</b>, so the section is <b>unprotected</b>: nothing
 * here reaches the server, and nothing it changes outlives a {@code :reload}.
 *
 * <p><b>The model is not translated.</b> A catalogue lands at {@link Fonts#display}, which is the last thing
 * that happens to a string before it becomes a raster — so every string the API hands back or matches on is
 * still the client's own English, and an addon reading a caption reads what the client wrote.
 *
 * <p><b>The misses are the feature's own oracle.</b> Nothing above the render can see a translation, by
 * construction, so the one observable a catalogue has is the set of strings that reached a routed surface
 * and that <i>this</i> catalogue named nothing for. That is also exactly what an author needs to write their
 * first file, which is why it records from an empty catalogue too.
 */
final class LocaleApi {
    private LocaleApi() {
    }

    /**
     * How many {@code (surface, text)} pairs one catalogue's miss set holds. It is fed from inside a render,
     * so it is bounded rather than unbounded: an addon that installs an empty catalogue and leaves it there
     * for a session would otherwise accumulate every string the client has drawn. Past the cap the set stops
     * growing and {@code :install()} starts a fresh round.
     */
    static final int MISSES = 512;

    /** Mint this addon's catalogue and mount {@code hafen.locale()} over it. From {@code installHafen}, once. */
    static void install(LuaTable hafen, Addon owner) {
        Held h = new Held();
        owner.locale = h;
        Section.install(hafen, "locale", methods(h));
    }

    /**
     * Teardown ({@code :reload}/disable/relogin, spec 05): this addon's catalogue stops being displayed and
     * every string it named comes back in the client's own English. The document itself goes with the
     * sandbox that held it.
     */
    static void teardown(Addon a) {
        Held h = a.locale;
        if(h != null)
            h.release();
    }

    // ---- the per-addon hold --------------------------------------------------------------------------

    /**
     * One addon's catalogue as the client holds it: the parsed document, whether it is installed, and what
     * missed. It is the {@link Fonts.Catalogue} the provider stack carries, so the identity of this object is
     * the owner tag — an addon owns at most one, and installing again re-raises the same object.
     */
    static final class Held implements Fonts.Catalogue {
        /** The parsed document, or {@code null} until one is loaded. Volatile: read from inside a render. */
        private volatile Catalogue doc = null;
        /** Whether it is in the provider's stack. Derived from nothing else, and dropped by teardown. */
        private volatile boolean installed = false;
        /** The {@code (surface, text)} pairs this catalogue named nothing for, in the order they arrived. */
        private final Map<String, Miss> misses = new LinkedHashMap<String, Miss>();
        /** {@code hafen.locale():miss()} — the collection over {@link #misses}, minted once. */
        private LuaValue coll;

        public String display(String scope, String text) {
            Catalogue c = doc;
            return (c == null) ? null : c.display(scope, text);
        }

        public void missed(String scope, String text) {
            // A NUL is the separator, and it has to be one: a space would make ("a b", "c") and ("a",
            // "b c") one key, and a chat line beginning with a space is an ordinary chat line.
            String key = scope + "\0" + text;
            synchronized(misses) {
                if(misses.containsKey(key) || (misses.size() >= MISSES))
                    return;
                misses.put(key, new Miss(scope, text));
            }
        }

        /** {@code :install()} — make this catalogue what the client displays, and start a fresh round of misses. */
        void install() {
            if(doc == null)
                throw new LuaError("hafen.locale():install(): there is no catalogue to install —"
                    + " hafen.locale():load(doc) is the door, and load({}) is a catalogue with no entries,"
                    + " which still records everything that missed");
            synchronized(misses) {
                misses.clear();
            }
            installed = true;
            Fonts.installCatalogue(this);
        }

        /** {@code :release()} — stop displaying it. The document is untouched, so {@code :install()} puts it back. */
        void release() {
            installed = false;
            Fonts.releaseCatalogue(this);
        }

        /** {@code :load(doc)} — replace what this catalogue says, whole. An installed one says it at once. */
        void load(Catalogue c) {
            doc = c;
            if(installed)
                Fonts.catalogueChanged();
        }

        /** The misses, in the order they arrived — the collection reads this on every call and holds nothing. */
        List<LuaValue> members() {
            List<LuaValue> out = new ArrayList<LuaValue>();
            synchronized(misses) {
                for(Miss m : misses.values())
                    out.add(m.handle());
            }
            return out;
        }

        int missCount() {
            synchronized(misses) {
                return misses.size();
            }
        }
    }

    // ---- the section's verbs -------------------------------------------------------------------------

    private static LuaTable methods(final Held h) {
        LuaTable m = new LuaTable();
        // load(doc) -- the whole catalogue as DATA, replacing whatever this one said. The only door: an entry
        // is one string with no configuration of its own, so there is no builder twin of sheet:rule(). The
        // document is parsed entirely before a word of it is committed, so a malformed one leaves the addon
        // holding exactly the catalogue it had.
        m.set("load", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Section.self(self, "locale", "load");
                LuaValue doc = Args.required(a, 2, "hafen.locale():load", "doc");
                h.load(Catalogue.parse("hafen.locale():load(doc)", doc));
                return self;
            }
        });
        // install() -- make it what the client displays, live, and start a fresh round of misses. Owned: a
        // :reload or a disable drops it, so the client's own English is always one step away.
        m.set("install", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "locale", "install");
                h.install();
                return self;
            }
        });
        // release() -- give the client its own words back. The same act sheet:release() is: a catalogue is a
        // layer over what the client says, and this hands it back. Inert when nothing was installed.
        m.set("release", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "locale", "release");
                h.release();
                return self;
            }
        });
        // info() -- the snapshot hatch: whether it is in force, what it holds, and how much has missed.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "locale", "info");
                Catalogue c = h.doc;
                LuaTable t = new LuaTable();
                t.set("installed", LuaValue.valueOf(h.installed));
                t.set("entries", LuaValue.valueOf((c == null) ? 0 : c.entries));
                // ...and the patterns are a count of their own: an author who wrote twelve exact entries and
                // three patterns is told both, and a catalogue of nothing but patterns is not "0 entries".
                t.set("patterns", LuaValue.valueOf((c == null) ? 0 : c.patterns()));
                t.set("misses", LuaValue.valueOf(h.missCount()));
                LuaTable ss = new LuaTable();
                int n = 0;
                if(c != null) {
                    for(String s : c.surfaces())
                        ss.set(++n, LuaValue.valueOf(s));
                }
                t.set("surfaces", ss);
                return t;
            }
        });
        // miss() -- the strings that reached a routed surface while this catalogue was installed and that it
        // named nothing for, each with the surface it reached. A set is a collection, so it is one; the
        // members are objects, so :find(fn) is the search and a string filter matches the text.
        m.set("miss", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                Section.self(self, "locale", "miss");
                return misses(h);
            }
        });
        return m;
    }

    /** {@code hafen.locale():miss()} — minted once per addon and handed back by identity. */
    private static synchronized LuaValue misses(final Held h) {
        if(h.coll == null) {
            h.coll = LuaCollection.create("hafen.locale():miss()", new LuaCollection.Source() {
                public List<LuaValue> members() {
                    return h.members();
                }

                public boolean named() {
                    return true;
                }

                public String needle(LuaValue member) {
                    Miss m = Miss.of(member);
                    return (m == null) ? null : m.text;
                }

                public String noGet() {
                    return "a miss is a surface AND a string, so there is no one key that addresses it:"
                        + " hafen.locale():miss():find(fn) is the search and"
                        + " hafen.locale():miss():list()[n] takes a position";
                }
            }, null);
        }
        return h.coll;
    }

    // ---- one miss ------------------------------------------------------------------------------------

    /**
     * One string that reached a routed surface with no entry naming it: the surface, and the string the
     * client drew there. Interned per pair inside its catalogue's set, so the same miss is the same object
     * every time it is read back — and it is minted where the pair is recorded rather than in the render, so
     * a draw allocates one small record and no Lua value at all.
     */
    static final class Miss {
        final String surface;
        final String text;
        private LuaValue self;

        Miss(String surface, String text) {
            this.surface = surface;
            this.text = text;
        }

        /** {@code tostring(m)} → {@code Miss(button, Cancel)}. */
        public String toString() {
            return "Miss(" + surface + ", " + text + ")";
        }

        /** This miss as Lua holds it, minted on the first read. */
        synchronized LuaValue handle() {
            if(self == null)
                self = LuaValue.userdataOf(this, meta());
            return self;
        }

        /** The {@code Miss} behind a Lua value, or {@code null} for anything that is not one. */
        static Miss of(LuaValue v) {
            if((v == null) || !v.isuserdata())
                return null;
            Object o = v.touserdata();
            return (o instanceof Miss) ? (Miss)o : null;
        }

        private static Miss receiver(LuaValue v, String verb) {
            Miss m = of(v);
            if(m == null)
                throw new LuaError("miss:" + verb + "() — use a COLON call on a miss object"
                    + " (hafen.locale():miss():list()[n])");
            return m;
        }

        private static LuaValue meta() {
            LuaTable mt = new LuaTable();
            mt.set(LuaValue.INDEX, Retired.closedIndex("miss", verbs(),
                "a miss is a surface and the string that reached it: :surface() :text() :info()"));
            mt.set("__name", LuaValue.valueOf("Miss"));
            mt.set("__tostring", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    Miss m = of(self);
                    return LuaValue.valueOf((m == null) ? "Miss(?)" : m.toString());
                }
            });
            return mt;
        }

        private static LuaTable verbs() {
            LuaTable m = new LuaTable();
            // surface() -- the locale key this string reached, which is the key an entry for it is written
            // under. Never "*": that is a key you write, not a surface the client draws at.
            m.set("surface", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    return LuaValue.valueOf(receiver(self, "surface").surface);
                }
            });
            // text() -- the string the client drew, in its own English, which is the key an entry names.
            m.set("text", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    return LuaValue.valueOf(receiver(self, "text").text);
                }
            });
            m.set("info", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    Miss m = receiver(self, "info");
                    LuaTable t = new LuaTable();
                    t.set("surface", LuaValue.valueOf(m.surface));
                    t.set("text", LuaValue.valueOf(m.text));
                    return t;
                }
            });
            return m;
        }
    }
}
