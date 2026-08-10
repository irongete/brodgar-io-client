package io.brodgar.addon;

import haven.BuddyWnd;
import haven.GAttrib;
import haven.Gob;
import haven.res.ui.obj.buddy.Buddy;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Kin object</b> — the OOP successor of the flat kin table (spec {@code 020-kin-oop}),
 * built on exactly the {@link LuaGob} mechanism 017 established. <b>The section object IS the roster</b>
 * (uniform grammar §2.1 — a section that contains exactly one thing <i>is</i> that thing):
 * {@code hafen.kin()} is the collection, {@code hafen.kin():get(idOrName)} is one Kin.
 *
 * <p><b>Wraps only the buddy id.</b> Every method re-resolves through one funnel —
 * {@link CharApi#buddywnd()}{@code .find(id)} — so a stashed Kin tracks renames, regroups and
 * online/offline flips, and goes {@code :exists() == false} once the kin is forgotten (D-012's freshness,
 * verbatim). {@code :info()} is the one snapshot escape hatch (today's {@code KinEntry} shape).
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to {@link LuaGob}: the handle crosses
 * as {@code LuaValue.userdataOf(luaKin, mt)} so Lua cannot scribble on it, and the {@link Cache} on the
 * owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access — <i>not</i> a
 * {@code WeakHashMap}, which is weak <i>keys</i>) makes {@code :get(id) == :get(id)} and
 * {@code seen[kin] = true} reliable, and makes {@code :list()[n]} literally the same object as
 * {@code :get(<that id>)}. Never static: no Lua value crosses a sandbox boundary and the cache dies
 * whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>The roster is a {@link LuaCollection}, and it is a view.</b> {@code hafen.kin():list(filter)} builds a
 * fresh array of Kin objects in {@link BuddyWnd} sort order every call; {@code :count}/{@code :find} read the
 * same members without building one. The <b>array</b> is the roster at call time; the <b>Kin objects</b> in it
 * are live.
 *
 * <p><b>Writes</b> ({@code :rename}/{@code :group(g)}/{@code :endKin}/{@code :forget}, and the collection's
 * {@code :add}) keep the {@code requireActions} gate (D-027/D-028) and drive the client's own
 * {@link BuddyWnd.Buddy} methods (wrap-not-reimplement, D-009); each returns <b>self</b> so they chain.
 * {@code :group} is one name for the pair: {@code kin:group()} reads it and {@code kin:group(g)} writes it.
 *
 * <p><b>Threading.</b> Every read/write runs on the UI thread (addon tick / REPL / timer / slash command);
 * {@code BuddyWnd.iterator()} copies the list under the window's own lock, so iterating it is snapshot-safe
 * even though the server mutates it from the network thread. The {@link Cache} map is guarded on its own
 * monitor (UI + REPL threads touch it).
 */
public final class LuaKin {
    /** The buddy id — the whole state of a handle. */
    public final int id;

    private LuaKin(int id) {
        this.id = id;
    }

    /** {@code tostring(kin)} (also the {@code __tostring} answer): {@code Kin(<id>)}. */
    public String toString() {
        return "Kin(" + id + ")";
    }

    /** An interned Kin object for {@code id} in {@code owner}'s env — the one way a Kin reaches Lua. */
    static LuaValue of(Addon owner, int id) {
        return owner.kins.of(id);
    }

    /** The {@code LuaKin} behind a Lua value, or {@code null} for anything that is not a Kin object. */
    static LuaKin resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaKin) ? (LuaKin)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Kin interning cache and metatable (its {@link Addon#kins}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the Kin metatable is built once, lazily. Holds its
     * {@link Addon} because the protected write verbs need the owner to check the {@code actions} permission
     * against.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<Integer, Ref> live = new HashMap<Integer, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code id} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(int id) {
            drain();
            Integer key = Integer.valueOf(id);
            Ref r = live.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaKin(id), meta());
            live.put(key, new Ref(v, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref kr = (Ref)r;
                if(live.get(kr.key) == kr)      // not already replaced by a fresh handle for the same id
                    live.remove(kr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final Integer key;

        Ref(LuaValue v, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Kin metatable -----------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("kin", methods(owner)));
        mt.set("__name", LuaValue.valueOf("Kin"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaKin h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Kin(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. Each reader re-resolves the buddy and answers {@code nil} once it is off the roster;
     * {@code :id()} is the exception (it answers from the handle alone, so it still works for a forgotten
     * kin). The writers are {@code actions}-protected and return <b>self</b> so they chain.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        m.set("id", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "id").id);
            }
        });
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(buddy(self, "exists") != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the old KinEntry shape), for logging/serialising.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return CharApi.kinSnapshot(buddy(self, "info"));
            }
        });
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BuddyWnd.Buddy b = buddy(self, "name");
                return ((b == null) || (b.name == null)) ? LuaValue.NIL : LuaValue.valueOf(b.name);
            }
        });
        // group() reads, group(g) WRITES (protected) — one name for the pair the old setGroup made two. The
        // SERVER accepts 0..254 (the client's own 8-colour palette is only what it can DRAW); validate the
        // real range here, before resolving, so the message is the same with or without a live Kin window.
        m.set("group", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue group = Args.written(a, 2, "kin:group", "group");
                if(group == null) {
                    BuddyWnd.Buddy b = buddy(self, "group");
                    return (b == null) ? LuaValue.NIL : LuaValue.valueOf(b.group);
                }
                AddonManager.requireActions(owner, "kin:group");
                if(!group.isnumber())
                    throw new LuaError("kin:group(group): group must be a number (0.." + MAXGROUP + ")");
                int g = group.toint();
                if((g < 0) || (g > MAXGROUP))
                    throw new LuaError("kin:group(group): group must be 0.." + MAXGROUP + ", got " + g);
                require(self, "group").chgrp(g);                      // wdgmsg("grp", id, group)
                return self;
            }
        });
        // color() — PRESENTATION, not identity: the client's palette only has 8 colours, while the server
        // accepts groups 0..254, so a group above the palette simply has no colour to report (nil).
        m.set("color", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BuddyWnd.Buddy b = buddy(self, "color");
                if((b == null) || (b.group < 0) || (b.group >= BuddyWnd.gc.length))
                    return LuaValue.NIL;
                return AddonManager.color(BuddyWnd.gc[b.group]);
            }
        });
        // online() — the tri-state (1 online, 0 offline, -1 hearth-secret-only) as the boolean the common
        // "is this kin online" question wants (the deliberate A6 call, kept).
        m.set("online", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                BuddyWnd.Buddy b = buddy(self, "online");
                return (b == null) ? LuaValue.NIL : LuaValue.valueOf(b.online == 1);
            }
        });
        // gob() — the sweeping half of the Kin <-> Gob link (020.2). The buddy id lives ON the gob (the
        // server's `ui/obj/buddy` attrib), not the other way round, so there is nothing to look up: we scan
        // the object cache for the gob carrying THIS id. nil is AMBIGUOUS on purpose — offline, out of view,
        // or simply not streamed in yet; see the docs. No index is kept (spec's Out of scope): the attrib is
        // set and cleared by the server outside GobAdded/GobRemoved, so a lifecycle-driven cache would go
        // silently wrong, and being merely slow beats being wrong.
        //
        // MORE THAN ONE gob can carry the mark: a kin's HEARTH FIRE has it too (that is how it draws their
        // name in their kin colour), so with both in view the raw sweep order would decide the answer. We
        // therefore PREFER THE PLAYER BODY — "where is this kin" is the question :gob() answers — and only
        // fall back to another marked gob (typically the hearth fire of a kin who is offline) when no body
        // is loaded. For ALL of them, filter the world by the inverse instead:
        //   hafen.world.gobs(function(g) return g:kin() == k end)
        m.set("gob", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                int id = handle(self, "gob").id;
                Gob other = null;
                for(Gob g : AddonManager.allGobs()) {   // copied under the OCache lock
                    Integer bid = buddyId(g);
                    if((bid == null) || (bid.intValue() != id))
                        continue;
                    if(AddonManager.gobIsPlayer(g))
                        return LuaGob.of(owner, g.id);
                    if(other == null)
                        other = g;
                }
                return (other == null) ? LuaValue.NIL : LuaGob.of(owner, other.id);
            }
        });
        // -- protected writes (D-027/D-028): drive the client's own Buddy methods (D-009), return self ------
        m.set("rename", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue name) {
                AddonManager.requireActions(owner, "kin:rename");
                if(!name.isstring())
                    throw new LuaError("kin:rename(name): name must be a string");
                require(self, "rename").chname(name.tojstring());     // wdgmsg("nick", id, name)
                return self;
            }
        });
        // endKin() = END KINSHIP (step 1): ends the kinship; the kin stays memorized in the list.
        m.set("endKin", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                AddonManager.requireActions(owner, "kin:endKin");
                require(self, "endKin").endkin();                     // "End kinship" → wdgmsg("rm", id)
                return self;
            }
        });
        // forget() = FORGET (step 2): drops a memorized (un-kinned) kin from the list entirely.
        m.set("forget", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                AddonManager.requireActions(owner, "kin:forget");
                require(self, "forget").forget();                     // "Forget" → wdgmsg("rm", id)
                return self;
            }
        });
        return m;
    }

    /** The highest group the server accepts (confirmed by the H&amp;H developers) — NOT the palette's 0..7. */
    private static final int MAXGROUP = 254;

    // ---- self resolution -------------------------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaKin handle(LuaValue self, String method) {
        LuaKin h = resolve(self);
        if(h == null)
            throw new LuaError("kin:" + method + "() — use a COLON call on a Kin object (hafen.kin():get(idOrName), hafen.kin():list()[n])");
        return h;
    }

    /** The LIVE buddy behind a method's {@code self}: re-resolved every call, {@code null} once it is gone. */
    private static BuddyWnd.Buddy buddy(LuaValue self, String method) {
        int id = handle(self, method).id;
        BuddyWnd bw = CharApi.buddywnd();
        return (bw == null) ? null : bw.find(id);
    }

    /** {@link #buddy} but a guiding error when there is no Kin window, or the kin is off the roster. */
    private static BuddyWnd.Buddy require(LuaValue self, String method) {
        int id = handle(self, method).id;
        BuddyWnd bw = CharApi.buddywnd();
        if(bw == null)
            throw new LuaError("kin:" + method + "(): no Kin window (not in the world yet)");
        BuddyWnd.Buddy b = bw.find(id);
        if(b == null)
            throw new LuaError("kin:" + method + "(): no such kin — id " + id + " is not on your roster");
        return b;
    }

    // ---- the Kin <-> Gob link ---------------------------------------------------------------------

    /**
     * The buddy id the server has marked {@code g} with, or {@code null} when the gob carries no
     * {@code ui/obj/buddy} attrib — <b>the</b> primitive behind both {@code gob:kin()} and {@code kin:gob()}.
     * Server-authoritative: the client never infers kinship from a gob, it is told.
     *
     * <p><b>Why the fallback.</b> The fast path is one {@code getattr} against our adopted local copy of the
     * resource's class ({@link Buddy}, pinned {@code @FromResource(name="ui/obj/buddy", version=4)}). If the
     * server ever ships v5 the pin stops matching, the local class is no longer installed
     * ({@code Resource.java:1556} drops it) and the <i>resource's own</i> class is loaded instead — a
     * different {@link Class} with the same name, so {@code getattr(Buddy.class)} would answer {@code null}
     * for every gob and {@code gob:kin()} would go <b>silently blind</b>. So a miss falls back to scanning
     * the gob's attribs BY CLASS NAME and reading {@code id} reflectively: a version bump degrades this to
     * slow, never to wrong.
     */
    static Integer buddyId(Gob g) {
        if(g == null)
            return null;
        Buddy b = g.getattr(Buddy.class);
        if(b != null)
            return Integer.valueOf(b.id);
        return byname(g);
    }

    /** The fully-qualified name every {@code ui/obj/buddy} class has, whichever loader produced it. */
    private static final String BUDDYCL = "haven.res.ui.obj.buddy.Buddy";
    /** {@code Gob.attr} (package-private) and the {@code id} field of each foreign Buddy class seen. */
    private static Field attrf;
    private static boolean reflectok = true;
    private static final Map<Class<?>, Field> idfs = new HashMap<Class<?>, Field>();

    /** {@link #buddyId}'s fallback: find the attrib whose class is <i>named</i> Buddy and read its id. */
    private static synchronized Integer byname(Gob g) {
        if(!reflectok)
            return null;
        try {
            if(attrf == null) {
                attrf = Gob.class.getDeclaredField("attr");
                attrf.setAccessible(true);
            }
            @SuppressWarnings("unchecked")
            Map<Class<? extends GAttrib>, GAttrib> attr = (Map<Class<? extends GAttrib>, GAttrib>)attrf.get(g);
            if(attr == null)
                return null;
            for(GAttrib a : attr.values()) {
                if((a == null) || !a.getClass().getName().equals(BUDDYCL))
                    continue;
                Field idf = idfs.get(a.getClass());
                if(idf == null) {
                    idf = a.getClass().getDeclaredField("id");
                    idf.setAccessible(true);
                    idfs.put(a.getClass(), idf);
                }
                return Integer.valueOf(idf.getInt(a));
            }
            return null;
        } catch(NoSuchFieldException e) {        // the engine moved: stop paying for the attempt
            reflectok = false;
            return null;
        } catch(RuntimeException e) {            // ConcurrentModification, access denied, …
            return null;
        } catch(IllegalAccessException e) {
            reflectok = false;
            return null;
        }
    }

    // ---- the roster ------------------------------------------------------------------------------

    /**
     * {@code hafen.kin()} — the roster, as the {@link LuaCollection} the section object IS: {@code :get(idOrName)}
     * addresses one kin, {@code :list}/{@code :count}/{@code :find} read the roster in the Kin window's sort
     * order, and the protected {@code :add(secret)} is the "Add kin" field. No Kin window yet (pre-HUD, or
     * mid-{@code :reload}) ⇒ an empty roster, never an error.
     *
     * <p>A <b>string</b> filter matches the kin's <b>name</b> as a substring; a kin the window has not named
     * yet matches nothing rather than refusing the filter.
     */
    static LuaValue collection(final Addon owner) {
        return LuaCollection.create("hafen.kin()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                BuddyWnd bw = CharApi.buddywnd();
                if(bw != null) {
                    for(BuddyWnd.Buddy b : bw)      // iterator() copies under the BuddyWnd's own lock
                        out.add(of(owner, b.id));
                }
                return out;
            }

            public String needle(LuaValue member) {
                BuddyWnd.Buddy b = live(member);
                return ((b == null) || (b.name == null)) ? "" : b.name;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                return find(owner, key, "hafen.kin():get(idOrName)");
            }

            public boolean creatable() {
                return true;
            }

            // add(secret) — the Kin window's "Add kin" field: kinning needs the other player's HEARTH SECRET
            // (no add-by-name message exists). Protected. It hands back the COLLECTION rather than a Kin, because
            // there is no Kin yet: the server decides whether the secret is valid and the roster changes on a
            // later tick, which is what KinChanged reports.
            public LuaValue addMember(Varargs a) {
                AddonManager.requireActions(owner, "hafen.kin():add");
                LuaValue secret = Args.required(a, 2, "hafen.kin():add", "secret");
                if(!secret.isstring())
                    throw new LuaError("hafen.kin():add(secret): secret must be a string (the other player's"
                        + " hearth secret)");
                String s = secret.tojstring();
                if(s.isEmpty())
                    throw new LuaError("hafen.kin():add(secret): secret must not be empty (the other player's"
                        + " hearth secret)");
                BuddyWnd bw = CharApi.buddywnd();
                if(bw == null)
                    throw new LuaError("hafen.kin():add(secret): no Kin window (not in the world yet)");
                bw.wdgmsg("bypwd", s);      // BuddyWnd :504/:509 — exactly what the "Add kin" button sends
                return a.arg1();
            }
        }, null);
    }

    /** The live buddy behind a Kin handle, for the filter's needle; {@code null} once it is off the roster. */
    private static BuddyWnd.Buddy live(LuaValue member) {
        LuaKin h = resolve(member);
        BuddyWnd bw = CharApi.buddywnd();
        return ((h == null) || (bw == null)) ? null : bw.find(h.id);
    }

    /**
     * One kin BY KEY: a <b>number</b> is a buddy id and always yields a Kin — an unknown one simply reports
     * {@code :exists() == false}, the same deliberate asymmetry {@code hafen.world():gob():get(id)} has, so an
     * id read out of a saved file can be held before the roster streams in. A <b>string</b> is an exact
     * (case-insensitive) name and answers {@code nil} when nobody on the roster carries it.
     */
    private static LuaValue find(Addon owner, LuaValue key, String where) {
        if(key.isnumber())                     // isnumber FIRST: in LuaJ isstring() is true for numbers too
            return of(owner, key.toint());
        if(key.isstring()) {
            BuddyWnd bw = CharApi.buddywnd();
            if(bw == null)
                return LuaValue.NIL;
            String needle = key.tojstring();
            for(BuddyWnd.Buddy b : bw) {
                if((b.name != null) && b.name.equalsIgnoreCase(needle))
                    return of(owner, b.id);
            }
            return LuaValue.NIL;
        }
        throw new LuaError(where + ": expects a kin id (a number) or an exact name (a string)");
    }
}
