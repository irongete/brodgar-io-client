package io.brodgar.addon;

import haven.GameUI;
import haven.KeyMatch;
import haven.MenuGrid;
import haven.Resource;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A <b>Pagina object</b> — one entry of the <b>action menu</b> ({@link MenuGrid}, the 4×4 "scm" grid), which is
 * the client's catalogue of everything the character can <i>do</i> (spec {@code 023-menugrid-oop}). Built on
 * exactly the mechanism {@link LuaGob} (017), {@link LuaKin} (020) and {@link LuaSlot} (021) established;
 * <b>arity is the verb on the namespace itself</b>: {@code hafen.menugrid()} is the catalogue,
 * {@code hafen.menugrid(key)} is one Pagina.
 *
 * <p><b>The key is always a string, and it splits by SHAPE.</b> Contains a {@code /} ⇒ a <b>resource name</b>
 * (the identity — {@code paginae/act/dig}); anything else ⇒ a <b>display name</b> ({@code "Dig"}), a search
 * convenience that needs the resource fully loaded and is <i>not</i> unique (first match in catalogue order
 * wins). Both forms return the very same interned object, since the cache is keyed by resource name. The
 * server's {@code Pagina.id} is never a key — it is session-local and opaque (022 refused it for
 * {@code setbelt "pag"} too) — and neither is a <b>position</b>: the catalogue grows on every discovery, so
 * {@code hafen.menugrid(1)} throws rather than pretending an index exists. A miss is plain {@code nil} (unlike
 * {@code hafen.kin(id)}, whose ids persist): "not in the menu" = "you do not have that action".
 *
 * <p><b>Wraps only the resource name.</b> Every method re-resolves through one funnel —
 * {@link AddonManager#gui()}{@code .menu.paginae} → the {@code Pagina} whose {@code res().name} matches — so a
 * stashed handle tracks the live catalogue and goes {@code :exists() == false} when the action is revoked
 * (D-012's freshness, verbatim). {@code :info()} is the one snapshot escape hatch.
 *
 * <p><b>The catalogue is the {@code paginae} set PLUS the categories its entries hang under</b>, exactly the
 * closure {@link MenuGrid#cons} walks: the grid's own category buttons are not in {@code paginae} (they live
 * only in the {@code pmap} intern table, reached through {@code parent()}), so without the closure
 * {@code :parent()} would hand back a handle that does not {@code :exists()} and {@code :roots()} would be
 * empty. It is rebuilt per call: copy the set <b>under its monitor</b>, then resolve names <i>outside</i> the
 * monitor ({@code res.get()} can block on the loader), drop what has not resolved yet and sort by the client's
 * own {@code PagButton.sortkey()} so the order matches the grid. Every resource-backed read is
 * {@code Loading}-guarded to {@code nil} — never partial, and no {@code Loading} escapes into Lua — which is
 * why a scan right at {@code OnEnterWorld} may be <b>short</b> and fills in sub-second as resources resolve.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to its three predecessors: the handle
 * crosses as {@code LuaValue.userdataOf(luaPagina, mt)} so Lua cannot scribble on it, and the {@link Cache} on
 * the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access — <i>not</i> a
 * {@code WeakHashMap}, which is weak <i>keys</i>) makes {@code hafen.menugrid(k) == hafen.menugrid(k)} and
 * {@code seen[pag] = true} reliable. Never static: no Lua value crosses a sandbox boundary and the cache dies
 * whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>Threading.</b> Every read runs on the UI thread (addon tick / REPL / timer / slash command);
 * {@code MenuGrid.paginae} is mutated on the UI thread under its own monitor and is copied under it here. The
 * {@link Cache} map is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaPagina {
    /** The resource name of the menu entry — the whole state of a handle, and its identity. */
    public final String res;

    private LuaPagina(String res) {
        this.res = res;
    }

    /** {@code tostring(pag)} (also the {@code __tostring} answer): {@code Pagina(<res>)}. */
    public String toString() {
        return "Pagina(" + res + ")";
    }

    /** An interned Pagina object for {@code res} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, String res) {
        return owner.paginae.of(res);
    }

    /** The {@code LuaPagina} behind a Lua value, or {@code null} for anything that is not a Pagina object. */
    static LuaPagina resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaPagina) ? (LuaPagina)o : null;
    }

    // ---- the per-addon intern cache + metatables ---------------------------------------------------

    /**
     * One addon's Pagina interning cache and metatables (its {@link Addon#paginae}). Weak values + a
     * {@link ReferenceQueue} drained on every access; the Pagina metatable and the catalogue's are built once,
     * lazily. Holds its {@link Addon} because the collection hands out interned handles for the owner.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Ref> live = new HashMap<String, Ref>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt, listMt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code res} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(String res) {
            drain();
            Ref r = live.get(res);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                live.remove(res);
            }
            LuaValue v = LuaValue.userdataOf(new LuaPagina(res), meta());
            live.put(res, new Ref(v, res, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref pr = (Ref)r;
                if(live.get(pr.key) == pr)      // not already replaced by a fresh handle for the same res
                    live.remove(pr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }

        /** The shared metatable every catalogue table gets ({@code __index} = find/roots/list). */
        synchronized LuaValue listMeta() {
            if(listMt == null)
                listMt = buildListMeta(owner);
            return listMt;
        }
    }

    /** A weak handle reference that remembers its map key, so the {@link ReferenceQueue} drain can unmap it. */
    private static final class Ref extends WeakReference<LuaValue> {
        final String key;

        Ref(LuaValue v, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.key = key;
        }
    }

    // ---- the Pagina metatable ----------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, methods(owner));
        mt.set("__name", LuaValue.valueOf("Pagina"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPagina h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Pagina(?)" : h.toString());
            }
        });
        return mt;
    }

    /**
     * The method set. Every reader but {@code :res()} re-resolves the entry in the live catalogue and answers
     * {@code nil} once it is gone (or while its resource is still {@code Loading}); {@code :res()} answers from
     * the handle alone, so a stashed handle still names itself after the action is revoked.
     */
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // res() — the resource name this Pagina addresses, and its IDENTITY (the intern key, and the name
        // slot:set() takes). Answers from the handle alone, like slot:index() / kin:id().
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "res").res);
            }
        });
        // exists() — is this entry still in the menu? False once the action is revoked, and also while its
        // resource has not resolved yet (the catalogue fills in — see the class comment).
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists").res) != null);
            }
        });
        // name() — the DISPLAY name the grid paints in the tooltip (Resource.AButton.name).
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String n = dispname(button(self, "name"));
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // path() — the action tokens the "act" message carries (AButton.ad), as a 1-based array. EMPTY for a
        // category (nothing to send) and for an id-only pagina (which is invoked by id, not by path) — which
        // is exactly why :use() must go through the client's own PagButton.use rather than a path message.
        m.set("path", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MenuGrid.PagButton b = button(self, "path");
                if(b == null)
                    return LuaValue.NIL;
                try {
                    String[] ad = b.act().ad;
                    LuaTable out = new LuaTable();
                    for(int i = 0; i < ad.length; i++)
                        out.set(i + 1, LuaValue.valueOf(ad[i]));
                    return out;
                } catch(RuntimeException e) {   // Loading etc.
                    return LuaValue.NIL;
                }
            }
        });
        // tooltip() — the pagina layer's description text (the long tip under the name), nil when the
        // resource carries none.
        m.set("tooltip", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String t = tooltip(button(self, "tooltip"));
                return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
            }
        });
        // hotkey() — the single letter the grid paints over the button while Alt is held, as a string; nil
        // when the binding needs a modifier or has no printable key. Same derivation as the grid's own
        // PagButton.bindchr (private there, so restated rather than reached for).
        m.set("hotkey", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String k = hotkey(button(self, "hotkey"));
                return (k == null) ? LuaValue.NIL : LuaValue.valueOf(k);
            }
        });
        // isnew() — is this entry still flagged as a NEW DISCOVERY (the green flash in the grid)? The flag is
        // cleared by the client when the button is actually used.
        m.set("isnew", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MenuGrid.Pagina p = live(handle(self, "isnew").res);
                return LuaValue.valueOf((p != null) && (p.anew > 0));
            }
        });
        // parent() — the CATEGORY this entry sits under, as a Pagina object; nil for a root entry (and while
        // the parent's own resource is still Loading).
        m.set("parent", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MenuGrid.Pagina p = live(handle(self, "parent").res);
                if(p == null)
                    return LuaValue.NIL;
                try {
                    MenuGrid.Pagina par = p.parent();
                    if(par == null)
                        return LuaValue.NIL;
                    String rn = resname(par);
                    return (rn == null) ? LuaValue.NIL : of(owner, rn);
                } catch(RuntimeException e) {   // Loading etc.
                    return LuaValue.NIL;
                }
            }
        });
        // children() — the entries this one is the parent of, i.e. exactly the buttons the grid shows after
        // clicking it. An empty array for a leaf action: "is this a category" is #pag:children() > 0.
        m.set("children", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                MenuGrid.Pagina p = live(handle(self, "children").res);
                if(p == null)
                    return LuaValue.NIL;
                return childrenOf(owner, p);
            }
        });
        // info() — the one SNAPSHOT escape hatch, for logging/serialising: the same fields as plain values
        // (parent as its RESOURCE NAME, not a handle), absent when they do not resolve.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(handle(self, "info").res);
            }
        });
        return m;
    }

    // ---- self resolution ---------------------------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaPagina handle(LuaValue self, String method) {
        LuaPagina h = resolve(self);
        if(h == null)
            throw new LuaError("pagina:" + method + "() — use a COLON call on a Pagina object (hafen.menugrid(key), hafen.menugrid()[i])");
        return h;
    }

    /** The LIVE button behind a method's {@code self}: re-resolved every call, {@code null} when unavailable. */
    private static MenuGrid.PagButton button(LuaValue self, String method) {
        MenuGrid.Pagina p = live(handle(self, method).res);
        return (p == null) ? null : button(p);
    }

    // ---- the menu-grid funnel ----------------------------------------------------------------------

    /** The live action menu, or {@code null} before the HUD exists (pre-login, mid-{@code :reload}). */
    private static MenuGrid grid() {
        GameUI g = AddonManager.gui();
        return (g == null) ? null : g.menu;
    }

    /** A pagina's resource name, or {@code null} while its resource is still {@code Loading}. */
    private static String resname(MenuGrid.Pagina p) {
        try {
            Resource r = p.res();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** A pagina's drawn button (the name/act/hotkey carrier), or {@code null} while it is {@code Loading}. */
    private static MenuGrid.PagButton button(MenuGrid.Pagina p) {
        try {
            return p.button();
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    private static String dispname(MenuGrid.PagButton b) {
        if(b == null)
            return null;
        try {
            return b.name();
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    private static String tooltip(MenuGrid.PagButton b) {
        if(b == null)
            return null;
        try {
            Resource.Pagina pg = b.res.layer(Resource.pagina);
            return (pg == null) ? null : pg.text;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** The letter the grid paints over the button under Alt — {@code MenuGrid.PagButton.bindchr}, restated. */
    private static String hotkey(MenuGrid.PagButton b) {
        if(b == null)
            return null;
        try {
            KeyMatch k = b.bind.key();
            if((k == null) || (k.modmatch != 0))
                return null;
            char c = k.chr;
            if((c == 0) && (k.keyname != null) && (k.keyname.length() == 1))
                c = k.keyname.charAt(0);
            return (c == 0) ? null : String.valueOf(c);
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /**
     * Every pagina the character knows <b>plus the categories they hang under</b> — the same closure
     * {@link MenuGrid#cons} walks. The {@code paginae} set is copied <b>under its own monitor</b> and nothing
     * is resolved while holding it ({@code res.get()} can block on the loader); the parent walk then runs
     * outside, guarded, so a {@code Loading} parent merely truncates that branch this call.
     */
    private static List<MenuGrid.Pagina> closure() {
        MenuGrid scm = grid();
        if(scm == null)
            return Collections.emptyList();
        List<MenuGrid.Pagina> open;
        synchronized(scm.paginae) {
            open = new ArrayList<MenuGrid.Pagina>(scm.paginae);
        }
        Set<MenuGrid.Pagina> seen = new HashSet<MenuGrid.Pagina>(open);
        for(int i = 0; i < open.size(); i++) {      // grows as parents are appended
            try {
                MenuGrid.Pagina par = open.get(i).parent();
                if((par != null) && seen.add(par))
                    open.add(par);
            } catch(RuntimeException e) {           // Loading etc. — this branch fills in next call
            }
        }
        return open;
    }

    /** One catalogue row: the live pagina, its resource name (the identity) and the grid's own sort key. */
    private static final class Entry {
        final MenuGrid.Pagina pag;
        final String res;
        final String sort;

        Entry(MenuGrid.Pagina pag, String res, String sort) {
            this.pag = pag;
            this.res = res;
            this.sort = sort;
        }
    }

    /**
     * The catalogue: the {@link #closure} with names resolved, unresolved entries dropped, deduplicated by
     * resource name and sorted by {@code PagButton.sortkey()} — the very comparator {@code MenuGrid.updlayout}
     * uses, so the order matches the grid. The sort key falls back to the resource name when only <i>it</i>
     * has resolved, and ties break on the resource name so the order is total and stable.
     */
    private static List<Entry> catalogue() {
        List<MenuGrid.Pagina> all = closure();
        List<Entry> out = new ArrayList<Entry>(all.size());
        Set<String> seen = new HashSet<String>();
        for(int i = 0; i < all.size(); i++) {
            MenuGrid.Pagina p = all.get(i);
            String rn = resname(p);
            if((rn == null) || !seen.add(rn))
                continue;
            out.add(new Entry(p, rn, sortkey(p, rn)));
        }
        Collections.sort(out, new Comparator<Entry>() {
            public int compare(Entry a, Entry b) {
                int c = a.sort.compareTo(b.sort);
                return (c != 0) ? c : a.res.compareTo(b.res);
            }
        });
        return out;
    }

    private static String sortkey(MenuGrid.Pagina p, String fallback) {
        MenuGrid.PagButton b = button(p);
        if(b == null)
            return fallback;
        try {
            return b.sortkey();
        } catch(RuntimeException e) {   // Loading etc.
            return fallback;
        }
    }

    /** The live pagina behind a resource name, or {@code null} when the menu has no such entry (yet). */
    private static MenuGrid.Pagina live(String res) {
        List<MenuGrid.Pagina> all = closure();
        for(int i = 0; i < all.size(); i++) {
            MenuGrid.Pagina p = all.get(i);
            if(res.equals(resname(p)))
                return p;
        }
        return null;
    }

    /** The entries whose {@code parent()} is {@code p} (identity — the client interns paginae in its pmap). */
    private static LuaValue childrenOf(Addon owner, MenuGrid.Pagina p) {
        LuaTable out = new LuaTable();
        List<Entry> cat = catalogue();
        int i = 0;
        for(int n = 0; n < cat.size(); n++) {
            Entry e = cat.get(n);
            try {
                if(e.pag.parent() == p)
                    out.set(++i, of(owner, e.res));
            } catch(RuntimeException ex) {   // Loading etc. — skip, never throw into Lua
            }
        }
        return out;
    }

    /** {@code pag:info()} — a plain snapshot table; a field the menu cannot answer is simply absent. */
    private static LuaValue snapshot(String res) {
        LuaTable t = new LuaTable();
        t.set("res", LuaValue.valueOf(res));
        MenuGrid.Pagina p = live(res);
        t.set("exists", LuaValue.valueOf(p != null));
        if(p == null)
            return t;
        t.set("isnew", LuaValue.valueOf(p.anew > 0));
        MenuGrid.PagButton b = button(p);
        String n = dispname(b);
        if(n != null)
            t.set("name", LuaValue.valueOf(n));
        String tt = tooltip(b);
        if(tt != null)
            t.set("tooltip", LuaValue.valueOf(tt));
        String hk = hotkey(b);
        if(hk != null)
            t.set("hotkey", LuaValue.valueOf(hk));
        if(b != null) {
            try {
                String[] ad = b.act().ad;
                LuaTable path = new LuaTable();
                for(int i = 0; i < ad.length; i++)
                    path.set(i + 1, LuaValue.valueOf(ad[i]));
                t.set("path", path);
            } catch(RuntimeException e) {   // Loading etc.
            }
        }
        try {
            MenuGrid.Pagina par = p.parent();
            String pn = (par == null) ? null : resname(par);
            if(pn != null)
                t.set("parent", LuaValue.valueOf(pn));
        } catch(RuntimeException e) {       // Loading etc.
        }
        return t;
    }

    // ---- the collection ----------------------------------------------------------------------------

    /**
     * {@code hafen.menugrid()} — the whole catalogue as a fresh <b>1-based</b> Lua array of (interned) Pagina
     * objects in the grid's own sort order, carrying the shared collection metatable
     * ({@code find}/{@code roots}/{@code list}). The methods live on the metatable, off the array part, so
     * {@code #} and {@code ipairs} are exact. No menu yet ⇒ an empty catalogue, never an error.
     */
    private static LuaValue collection(Addon owner) {
        LuaTable out = new LuaTable();
        List<Entry> cat = catalogue();
        for(int i = 0; i < cat.size(); i++)
            out.set(i + 1, of(owner, cat.get(i).res));
        out.setmetatable(owner.paginae.listMeta());
        return out;
    }

    /** The catalogue metatable: {@code __index} = {@code find}/{@code roots}/{@code list}, plus {@code __name}. */
    private static LuaValue buildListMeta(final Addon owner) {
        LuaTable m = new LuaTable();
        // find(text) — every entry whose DISPLAY NAME contains text (case-insensitive), in catalogue order.
        // The plural search half of the namespace: hafen.menugrid(name) is the exact single lookup.
        m.set("find", new TwoArgFunction() {
            public LuaValue call(LuaValue self, LuaValue text) {
                if(!text.isstring())
                    throw new LuaError("menu:find(text): text must be a string (a substring of the display name)");
                String needle = text.tojstring().toLowerCase();
                LuaTable out = new LuaTable();
                List<Entry> cat = catalogue();
                int i = 0;
                for(int n = 0; n < cat.size(); n++) {
                    Entry e = cat.get(n);
                    String nm = dispname(button(e.pag));
                    if((nm != null) && nm.toLowerCase().contains(needle))
                        out.set(++i, of(owner, e.res));
                }
                return out;
            }
        });
        // roots() — the entries with no parent, i.e. what the menu shows on its ROOT screen.
        m.set("roots", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return childrenOf(owner, null);
            }
        });
        // list() — the whole catalogue as :info() SNAPSHOTS (plain tables), for logging/serialising.
        m.set("list", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaTable out = new LuaTable();
                List<Entry> cat = catalogue();
                for(int i = 0; i < cat.size(); i++)
                    out.set(i + 1, snapshot(cat.get(i).res));
                return out;
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, m);
        mt.set("__name", LuaValue.valueOf("Menu"));
        return mt;
    }

    /** One entry BY KEY: a {@code /} makes it a resource name, anything else a display name. Miss ⇒ nil. */
    private static LuaValue find(Addon owner, String key) {
        List<Entry> cat = catalogue();
        if(key.indexOf('/') >= 0) {                 // SHAPE, not fallback: a res name never scans names
            for(int i = 0; i < cat.size(); i++) {
                if(cat.get(i).res.equals(key))
                    return of(owner, key);
            }
            return LuaValue.NIL;
        }
        for(int i = 0; i < cat.size(); i++) {       // first match in catalogue order — names are not unique
            Entry e = cat.get(i);
            String nm = dispname(button(e.pag));
            if((nm != null) && nm.equalsIgnoreCase(key))
                return of(owner, e.res);
        }
        return LuaValue.NIL;
    }

    /**
     * {@code hafen.menugrid} itself: a <b>callable table</b> ({@code __call}) with arity dispatch, so
     * {@code hafen.menugrid()} / {@code hafen.menugrid(key)} work while indexing it reads as plain {@code nil}
     * — the OOP-from-the-start shape {@code hafen.gob} (D-044), {@code hafen.kin} (D-056) and
     * {@code hafen.actionbar} (D-057) established.
     */
    static LuaValue factory(final Addon owner) {
        LuaTable menu = new LuaTable();
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.CALL, new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue key = a.arg(2);            // arg1 = the callable table itself
                if(key.isnil())
                    return collection(owner);
                if(key.isnumber())                  // BEFORE isstring(): in LuaJ a number IS a string
                    throw new LuaError("hafen.menugrid(key): the menu has no positions to address — the"
                        + " catalogue grows on every discovery, so a position is not an index. Use a resource"
                        + " name (\"paginae/act/dig\") or a display name (\"Dig\").");
                if(key.isstring())
                    return find(owner, key.tojstring());
                throw new LuaError("hafen.menugrid([key]): no argument = the whole catalogue, a string with a"
                    + " '/' = one entry by resource name, any other string = one entry by display name");
            }
        });
        menu.setmetatable(mt);
        return menu;
    }
}
