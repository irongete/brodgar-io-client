package io.brodgar.addon;

import haven.BuddyWnd;
import haven.GAttrib;
import haven.Gob;
import haven.SListBox;
import haven.Widget;
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
 * {@code s:kin()} is the collection, {@code s:kin():get(idOrName)} is one Kin.
 *
 * <p><b>Wraps the account and the buddy id.</b> Every method re-resolves through one funnel —
 * {@link CharApi#buddywnd(String)}{@code .find(id)} — so a stashed Kin tracks renames, regroups and
 * online/offline flips, and goes {@code :exists() == false} once the kin is forgotten (D-012's freshness,
 * verbatim). {@code :info()} is the one snapshot escape hatch (today's {@code KinEntry} shape).
 *
 * <p><b>A buddy id counts inside one roster</b> (077.2), which is why the account is half the handle. The
 * Kin window is {@link haven.GameUI#buddies}, one login's HUD, and the server numbers each roster on its
 * own — so id 7 on two characters is two different people, and a handle that carried the id alone would
 * call them one. Two levels of intern map, on {@code (account, id)}: the {@link LuaGob} shape.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to {@link LuaGob}: the handle crosses
 * as {@code LuaValue.userdataOf(luaKin, mt)} so Lua cannot scribble on it, and the {@link Cache} on the
 * owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access — <i>not</i> a
 * {@code WeakHashMap}, which is weak <i>keys</i>) makes {@code :get(id) == :get(id)} and
 * {@code seen[kin] = true} reliable, and makes {@code :list()[n]} literally the same object as
 * {@code :get(<that id>)}. Never static: no Lua value crosses a sandbox boundary and the cache dies
 * whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>The roster is a {@link LuaCollection}, and it is a view.</b> {@code s:kin():list(filter)} builds a
 * fresh array of Kin objects in {@link BuddyWnd} sort order every call; {@code :count}/{@code :find} read the
 * same members without building one. The <b>array</b> is the roster at call time; the <b>Kin objects</b> in it
 * are live.
 *
 * <p><b>Writes</b> ({@code :rename}/{@code :group(g)}/{@code :endKin}/{@code :forget}, and the collection's
 * {@code :add}) keep the {@code requirePermission} gate (D-027/D-028, the {@code kin.*} keys) and drive the client's own
 * {@link BuddyWnd.Buddy} methods (wrap-not-reimplement, D-009); each returns <b>self</b> so they chain.
 * {@code :group} is one name for the pair: {@code kin:group()} reads it and {@code kin:group(g)} writes it.
 *
 * <p><b>Each keeps the ONE key it has, addressed or not</b> (077.2). A key names the <i>action</i>, not the
 * target: a verb is protected when it starts something the player could have performed, and the player could
 * have tabbed to that character and performed it. A second grant per session would mean an addon the user
 * allowed to add kin cannot add kin on an alt, a distinction the user never drew. Nor does the send need the
 * anchor: {@link BuddyWnd.Buddy}'s own methods go through the widget's own tree ({@code Widget.wdgmsg} to
 * that {@code UI}, and so to that {@code Session}), so a write lands on the character it was addressed at
 * whether or not anyone is looking at it.
 *
 * <p><b>Threading.</b> A read or a write is reached from every thread {@code docs/addons/api/threading.md}
 * lists. {@code BuddyWnd.iterator()} copies the list under the window's own lock, so iterating it is
 * snapshot-safe even though the server mutates it from the network thread, and a {@link BuddyWnd.Buddy}'s
 * own {@code name}/{@code online}/{@code group} are read under the <i>buddy's</i> monitor, which is what the
 * network thread publishes them under ({@link CharApi#kinSnapshot}). The two sends walk the widget's parent
 * chain, so each takes that tree's monitor. The {@link Cache} map is guarded on its own monitor.
 */
public final class LuaKin {
    /** The account whose roster this kin is on — half the address, and what makes the id mean one person. */
    public final String user;
    /** The buddy id, in that character's own roster. */
    public final int id;

    private LuaKin(String user, int id) {
        this.user = user;
        this.id = id;
    }

    /** {@code tostring(kin)} (also the {@code __tostring} answer): {@code Kin(<id>)}. */
    public String toString() {
        return "Kin(" + id + ")";
    }

    /** An interned Kin object for {@code id} <b>on {@code user}'s roster</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int id) {
        return owner.kins.of(user, id);
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
     * One addon's Kin interning cache and metatable (its {@link Addon#kins}), keyed by the <b>account plus</b>
     * the buddy id: a buddy id is one roster's own, so two characters both carrying kin 7 carry two different
     * people and must have two handles. Two levels of map, the {@link LuaGob} shape. Weak values + a
     * {@link ReferenceQueue} drained on every access; the Kin metatable is built once, lazily. Holds its
     * {@link Addon} because the protected write verbs need the owner to check the {@code kin.*} permissions
     * against.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code (user, id)} — a cache hit, or a freshly minted (inserted) one. */
        synchronized LuaValue of(String user, int id) {
            drain();
            Map<Integer, Ref> byid = live.get(user);
            if(byid == null)
                live.put(user, byid = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(id);
            Ref r = byid.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byid.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaKin(user, id), meta());
            byid.put(key, new Ref(v, user, key, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref kr = (Ref)r;
                Map<Integer, Ref> byid = live.get(kr.user);
                if(byid == null)
                    continue;
                if(byid.get(kr.key) == kr)      // not already replaced by a fresh handle for the same id
                    byid.remove(kr.key);
                if(byid.isEmpty())
                    live.remove(kr.user);
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
        final String user;
        final Integer key;

        Ref(LuaValue v, String user, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the Kin metatable -----------------------------------------------------------------------

    /**
     * The per-addon metatable: {@code __index} = the methods table through {@link Refusal#closedIndex} (so
     * an unknown verb throws naming what this type does answer), plus {@code __tostring}/{@code __name}.
     */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("kin", methods(owner),
            "someone on your kin list",
            ":rename(name), :group(g), :endKin() and :forget() write; everything else reads"));
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
     * kin). The writers are protected by their {@code kin.*} keys and return <b>self</b> so they chain.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        m.set("id", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:id");
                return LuaValue.valueOf(handle(self, "id").id);
            }
        });
        // widget() — 094 (A-104): THE CROSSING BACK. The widget tree and the domain objects are two address
        // spaces, and until now they met in one place and in one direction (widget:items()). "Highlight the
        // kin row for whoever just came online" needed the row and there was none: s:ui():match() reaches the
        // WINDOW by role and the thing inside it is a domain object the selector language cannot address.
        //   nil when that row is not being drawn -- the window is closed, or the kin is scrolled out of the
        // list, which recycles the widgets of rows it is not showing.
        m.set("widget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:widget");
                Widget row = kinRow(self, "widget");
                return (row == null) ? LuaValue.NIL : LuaWidget.of(owner, row);
            }
        });
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:exists");
                return LuaValue.valueOf(buddy(self, "exists") != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the old KinEntry shape), for logging/serialising.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:info");
                return CharApi.kinSnapshot(buddy(self, "info"));
            }
        });
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:name");
                BuddyWnd.Buddy b = buddy(self, "name");
                if(b == null)
                    return LuaValue.NIL;
                synchronized(b) {                      // audit2 B06: the monitor BuddyWnd publishes under
                    return (b.name == null) ? LuaValue.NIL : LuaValue.valueOf(b.name);
                }
            }
        });
        // group() reads, group(g) WRITES (protected) — one name for the pair the old setGroup made two. The
        // SERVER accepts 0..254 (the client's own 8-colour palette is only what it can DRAW); validate the
        // real range here, before resolving, so the message is the same with or without a live Kin window.
        m.set("group", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                Args.only(a, 1, "kin:group");
                LuaValue group = Args.written(a, 2, "kin:group", "group");
                if(group == null) {
                    BuddyWnd.Buddy b = buddy(self, "group");
                    if(b == null)
                        return LuaValue.NIL;
                    synchronized(b) {                  // audit2 B06: the monitor BuddyWnd publishes under
                        return LuaValue.valueOf(b.group);
                    }
                }
                AddonManager.requirePermission(AddonManager.current(), Permission.KIN_GROUP);
                int g = Args.integer(group, "kin:group", "group", "0.." + MAXGROUP);
                if((g < 0) || (g > MAXGROUP))
                    throw new LuaError("kin:group(group): group must be 0.." + MAXGROUP + ", got " + g);
                LuaKin h = handle(self, "group");
                BuddyWnd bw = kinwnd(self, "group");
                BuddyWnd.Buddy b = require(self, "group");
                // The client's own Buddy.chgrp composes the wdgmsg("grp", id, group) (D-009), so the
                // values it will carry are handed over with it and the wire shape is checked on them.
                Wire.send(owner, h.user, "kin:group", bw, "grp",
                          new Object[] {Integer.valueOf(h.id), Integer.valueOf(g)}, () -> b.chgrp(g));
                return self;
            }
        });
        // color() — PRESENTATION, not identity: the client's palette only has 8 colours, while the server
        // accepts groups 0..254, so a group above the palette simply has no colour to report (nil). The
        // ENGINE draws such a kin in the ungrouped colour rather than throwing (BuddyWnd.gcolor), but that
        // fallback is a DRAW, not an answer: nil is the truthful one here, and group() is what tells two
        // groups above the palette apart. Do not route this through gcolor.
        m.set("color", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:color");
                BuddyWnd.Buddy b = buddy(self, "color");
                if(b == null)
                    return LuaValue.NIL;
                int grp;
                synchronized(b) {                      // audit2 B06: as above
                    grp = b.group;
                }
                return ((grp < 0) || (grp >= BuddyWnd.ncolors)) ? LuaValue.NIL
                    : AddonManager.color(BuddyWnd.gc[grp]);
            }
        });
        // online() — the tri-state (1 online, 0 offline, -1 hearth-secret-only) as the boolean the common
        // "is this kin online" question wants (the deliberate A6 call, kept).
        m.set("online", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:online");
                BuddyWnd.Buddy b = buddy(self, "online");
                if(b == null)
                    return LuaValue.NIL;
                synchronized(b) {                      // audit2 B06: as above
                    return LuaValue.valueOf(b.online == 1);
                }
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
        //   s:world():gob():list(function(g) return g:kin() == k end)
        m.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:gob");
                LuaKin h = handle(self, "gob");
                Gob other = null;
                for(Gob g : AddonManager.allGobs(h.user)) {   // THAT session's cache, under its own lock
                    Integer bid = buddyId(g);
                    if((bid == null) || (bid.intValue() != h.id))
                        continue;
                    if(AddonManager.gobIsPlayer(g))
                        return LuaGob.of(owner, h.user, g.id);
                    if(other == null)
                        other = g;
                }
                return (other == null) ? LuaValue.NIL : LuaGob.of(owner, h.user, other.id);
            }
        });
        // -- protected writes (D-027/D-028): drive the client's own Buddy methods (D-009), return self ------
        m.set("rename", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 1, "kin:rename");
                LuaValue name = Args.required(a, 2, "kin:rename", "name");
                AddonManager.requirePermission(AddonManager.current(), Permission.KIN_RENAME);
                Args.str(name, "kin:rename", "name", "the name YOUR list shows this kin under");
                LuaKin h = handle(self, "rename");
                BuddyWnd bw = kinwnd(self, "rename");
                BuddyWnd.Buddy b = require(self, "rename");
                String n = name.tojstring();
                // Buddy.chname composes the wdgmsg("nick", id, name) (D-009); what a rename field can
                // actually compose -- one typed line, not empty -- is the "nick" row in Wire.
                Wire.send(owner, h.user, "kin:rename", bw, "nick",
                          new Object[] {Integer.valueOf(h.id), n}, () -> b.chname(n));
                return self;
            }
        });
        // endKin() = END KINSHIP (step 1): ends the kinship; the kin stays memorized in the list.
        m.set("endKin", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:endKin");
                AddonManager.requirePermission(AddonManager.current(), Permission.KIN_END);
                LuaKin h = handle(self, "endKin");
                BuddyWnd bw = kinwnd(self, "endKin");
                BuddyWnd.Buddy b = require(self, "endKin");
                Wire.send(owner, h.user, "kin:endKin", bw, "rm",   // "End kinship" → wdgmsg("rm", id)
                          new Object[] {Integer.valueOf(h.id)}, () -> b.endkin());
                return self;
            }
        });
        // forget() = FORGET (step 2): drops a memorized (un-kinned) kin from the list entirely.
        m.set("forget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "kin:forget");
                AddonManager.requirePermission(AddonManager.current(), Permission.KIN_FORGET);
                LuaKin h = handle(self, "forget");
                BuddyWnd bw = kinwnd(self, "forget");
                BuddyWnd.Buddy b = require(self, "forget");
                Wire.send(owner, h.user, "kin:forget", bw, "rm",   // "Forget" → wdgmsg("rm", id)
                          new Object[] {Integer.valueOf(h.id)}, () -> b.forget());
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
            throw new LuaError("kin:" + method + "() — use a COLON call on a Kin object ("
                + CharApi.KN + ":get(idOrName), " + CharApi.KN + ":list()[n])");
        return h;
    }

    /** The LIVE buddy behind a method's {@code self}: re-resolved every call, {@code null} once it is gone. */
    /**
     * <b>The list row that draws this kin</b> (094, A-104), or {@code null}. Two hops, both public: the Kin
     * window's roster is an {@link SListBox}, and {@code getcur(item)} answers the {@code ItemWidget} it is
     * currently rendering for one item.
     *
     * <p><b>{@code null} for a kin that is not on screen</b>, and that is the row's own truth rather than a
     * gap: an {@code SListBox} mints a widget for the rows it is <i>showing</i> and recycles the rest, so a
     * kin scrolled out of view is drawn by nothing. Scroll to it, or close the window, and the answer changes
     * — which is why the crossing hands back a live Widget rather than something held.
     */
    private static Widget kinRow(LuaValue self, String method) {
        LuaKin h = handle(self, method);
        BuddyWnd bw = CharApi.buddywnd(h.user);
        if(bw == null)
            return null;
        BuddyWnd.Buddy b = bw.find(h.id);
        if(b == null)
            return null;
        for(SListBox<?, ?> box : bw.children(SListBox.class)) {
            @SuppressWarnings("unchecked")
            SListBox<Object, ?> raw = (SListBox<Object, ?>)box;
            Widget row = raw.getcur(b);
            if(row != null)
                return row;
        }
        return null;
    }

    private static BuddyWnd.Buddy buddy(LuaValue self, String method) {
        LuaKin h = handle(self, method);
        BuddyWnd bw = CharApi.buddywnd(h.user);
        return (bw == null) ? null : bw.find(h.id);
    }

    /** {@link #buddy} but a guiding error when there is no Kin window, or the kin is off the roster. */
    /**
     * The Kin window {@code self} is addressed at, or the refusal — the write half's own lookup, split off
     * because a send walks that widget's parent chain and so runs under its tree's monitor (audit2 B06).
     */
    private static BuddyWnd kinwnd(LuaValue self, String method) {
        BuddyWnd bw = CharApi.buddywnd(handle(self, method).user);
        if(bw == null)
            throw new LuaError("kin:" + method + "(): no Kin window (that character is not in the world yet)");
        return bw;
    }

    private static BuddyWnd.Buddy require(LuaValue self, String method) {
        LuaKin h = handle(self, method);
        BuddyWnd bw = CharApi.buddywnd(h.user);
        if(bw == null)
            throw new LuaError("kin:" + method + "(): no Kin window (that character is not in the world yet)");
        BuddyWnd.Buddy b = bw.find(h.id);
        if(b == null)
            throw new LuaError("kin:" + method + "(): no such kin — id " + h.id + " is not on "
                + h.user + "'s roster");
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
     * {@code s:kin()} — <b>that character's</b> roster, as the {@link LuaCollection} the section object IS:
     * {@code :get(idOrName)} addresses one kin, {@code :list}/{@code :count}/{@code :find} read the roster in
     * the Kin window's sort order, and the protected {@code :add(secret)} is the "Add kin" field. No Kin
     * window yet (pre-HUD, or mid-{@code :reload}) means an empty roster, never an error.
     *
     * <p>A <b>string</b> filter matches the kin's <b>name</b> as a substring; a kin the window has not named
     * yet matches nothing rather than refusing the filter.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.KN, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                BuddyWnd bw = CharApi.buddywnd(user);
                if(bw != null) {
                    for(BuddyWnd.Buddy b : bw)      // iterator() copies under the BuddyWnd's own lock
                        out.add(of(owner, user, b.id));
                }
                return out;
            }

            public String needle(LuaValue member) {
                BuddyWnd.Buddy b = live(member);
                if(b == null)
                    return "";
                synchronized(b) {                      // audit2 B06: the monitor BuddyWnd publishes under
                    return (b.name == null) ? "" : b.name;
                }
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                return find(owner, user, key, CharApi.KN + ":get(idOrName)");
            }

            public boolean creatable() {
                return true;
            }

            // add(secret) — the Kin window's "Add kin" field: kinning needs the other player's HEARTH SECRET
            // (no add-by-name message exists). Protected, with the one key it has whichever character it is
            // addressed at (077.2), and the send goes through THAT session's own BuddyWnd. It hands back the
            // COLLECTION rather than a Kin, because there is no Kin yet: the server decides whether the secret
            // is valid and the roster changes on a later tick, which is what KinChanged reports.
            public LuaValue addMember(Varargs a) {
                AddonManager.requirePermission(AddonManager.current(), Permission.KIN_ADD);
                // The type, not isstring(): a NUMBER answers isstring() in LuaJ, so the laxer test used to
                // send :add(1234) to the server as the hearth secret "1234" — a wrong value walking through
                // the one type check in this family that guards a protected write.
                LuaValue secret = Args.str(a, 2, CharApi.KN + ":add", "secret",
                                           "the other player's hearth secret");
                String s = secret.tojstring();
                if(s.isEmpty())
                    throw new LuaError(CharApi.KN + ":add(secret): secret must not be empty (the other"
                        + " player's hearth secret)");
                BuddyWnd bw = CharApi.buddywnd(user);
                if(bw == null)
                    throw new LuaError(CharApi.KN + ":add(secret): no Kin window (that character is not in"
                        + " the world yet)");
                // BuddyWnd's own "Add kin" field sends exactly this, and Wire's "bypwd" row is what
                // that field can compose: one typed line.
                Wire.send(owner, user, CharApi.KN + ":add", bw, "bypwd", s);
                // 091/A-084: NOT the collection and not a member. Adding by hearth secret is a round
                // trip -- the server decides whether that secret names anyone -- so there is no Kin to hand
                // back yet, and handing back the roster made s:kin():add(x):name() look like it might work.
                // nil makes the mistake fail at the assignment, one line from where it was made.
                return LuaValue.NIL;
            }

            /**
             * NIL, because the name form is a lookup and answers nil for a name nobody on the roster has —
             * MINT is a promise the collection holds itself to on EVERY key, and this one keeps it for an id
             * only ({@code :get(id)} always hands back a Kin, whose {@code :exists()} is the question).
             */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.NIL;
            }

            /** {@code :add(secret)}: the other player's hearth secret, not a key of the roster. */
            public String addName() {
                return "secret";
            }

            /** Both forms come through the one door: the buddy id addresses, an exact name looks up. */
            public String keyName() {
                return "idOrName";
            }
        }, null);
    }

    /** The live buddy behind a Kin handle, for the filter's needle; {@code null} once it is off the roster. */
    private static BuddyWnd.Buddy live(LuaValue member) {
        LuaKin h = resolve(member);
        if(h == null)
            return null;
        BuddyWnd bw = CharApi.buddywnd(h.user);
        return (bw == null) ? null : bw.find(h.id);
    }

    /**
     * One kin BY KEY: a <b>number</b> is a buddy id and always yields a Kin — an unknown one simply reports
     * {@code :exists() == false}, the same deliberate asymmetry {@code s:world():gob():get(id)} has, so an
     * id read out of a saved file can be held before that character's roster streams in. A <b>string</b> is an exact
     * (case-insensitive) name and answers {@code nil} when nobody on the roster carries it.
     */
    private static LuaValue find(Addon owner, String user, LuaValue key, String where) {
        if(key.type() == LuaValue.TNUMBER)     // by TYPE, so "42" is a name and not an id
            return of(owner, user, Args.integer(key, where, "key", "a buddy id"));
        if(key.isstring()) {
            BuddyWnd bw = CharApi.buddywnd(user);
            if(bw == null)
                return LuaValue.NIL;
            String needle = key.tojstring();
            for(BuddyWnd.Buddy b : bw) {
                String nm;
                synchronized(b) {                      // audit2 B06: as above
                    nm = b.name;
                }
                if((nm != null) && nm.equalsIgnoreCase(needle))
                    return of(owner, user, b.id);
            }
            return LuaValue.NIL;
        }
        throw new LuaError(where + ": expects a kin id (a number) or an exact name (a string)");
    }
}
