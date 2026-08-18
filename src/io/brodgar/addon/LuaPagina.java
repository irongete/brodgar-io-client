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
 * <b>the section object IS the catalogue</b> (uniform grammar §2.1): {@code s:menugrid()} is the
 * {@link LuaCollection}, {@code s:menugrid():get(key)} is one Pagina.
 *
 * <p><b>A catalogue is one character's</b> (077.3), which is why the account is half the handle. The menu is
 * a widget under one login's HUD and lists what <i>that</i> character has unlocked, so a resource name that
 * names an entry here may name nothing on the alt — and two characters that both know Dig know it through
 * two {@link MenuGrid.Pagina} objects, in two grids. Two levels of intern map, on {@code (account, res)}: the
 * {@link LuaGob} shape. An entry an addon adds goes into the grid it was addressed at, and the same id may
 * stand in each character's menu.
 *
 * <p><b>The key is always a string, and it splits by SHAPE.</b> Contains a {@code /} ⇒ a <b>resource name</b>
 * (the identity — {@code paginae/act/dig}); anything else ⇒ a <b>display name</b> ({@code "Dig"}), a search
 * convenience that needs the resource fully loaded and is <i>not</i> unique (first match in catalogue order
 * wins). Both forms return the very same interned object, since the cache is keyed by resource name. The
 * server's {@code Pagina.id} is never a key — it is session-local and opaque (022 refused it for
 * {@code setbelt "pag"} too) — and neither is a <b>position</b>: the catalogue grows on every discovery, so
 * {@code :get(1)} throws rather than pretending an index exists. A miss is plain {@code nil} (unlike
 * {@code s:kin():get(id)}, whose ids persist): "not in the menu" = "you do not have that action".
 *
 * <p><b>Wraps the account and the resource name.</b> Every method re-resolves through one funnel —
 * {@link AddonManager#gameui(String)}{@code .menu.paginae} → the {@code Pagina} whose {@code res().name}
 * matches — so a stashed handle tracks that character's live catalogue and goes {@code :exists() == false}
 * when the action is revoked (D-012's freshness, verbatim). {@code :info()} is the one snapshot escape
 * hatch.
 *
 * <p><b>The catalogue is the {@code paginae} set PLUS the categories its entries hang under</b>, exactly the
 * closure {@link MenuGrid#cons} walks: the grid's own category buttons are not in {@code paginae} (they live
 * only in the {@code pmap} intern table, reached through {@code parent()}), so without the closure
 * {@code :parent()} would hand back a handle that does not {@code :exists()} and {@code :roots()} would be
 * empty. It is rebuilt per call: copy the set <b>under its monitor</b>, then resolve names <i>outside</i> the
 * monitor ({@code res.get()} can block on the loader), drop what has not resolved yet and sort by the client's
 * own {@code PagButton.sortkey()} so the order matches the grid. Every resource-backed read is
 * {@code Loading}-guarded to {@code nil} — never partial, and no {@code Loading} escapes into Lua — which is
 * why a scan right at {@code SessionEnteredWorld} may be <b>short</b> and fills in sub-second as resources
 * resolve.
 *
 * <p><b>The one verb is {@code :use()}, it is PROTECTED, and it takes no arguments.</b> It drives the client's
 * own {@code MenuGrid.PagButton.use} (wrap-not-reimplement, D-009) — the pure message half, which branches
 * {@code "act"}-by-path vs {@code "use"}-by-id and so reaches an id-only pagina no path can express — rather
 * than {@code MenuGrid.use()}, the widget's click handler, which would also flip the visible page and reset
 * grid state. A <b>category</b> errors instead of sending an empty {@code "act"}, pointing at
 * {@code :children()}. There is no {@code mods} parameter because {@code PagButton.use} ignores
 * {@code Interaction.modflags} and reads {@code ui.modflags()} live, so one could only lie. It commits a real
 * server action, so since 048.5 it sits behind the per-addon {@code menugrid.use} permission like every other verb
 * that does — the READS beside it stay open. It keeps that one key whichever character it is addressed at
 * (077.3): a key names the action, and the player could have tabbed there and clicked the button.
 *
 * <p><b>Userdata + per-addon interning</b> (D-017 / D-045), identical to its three predecessors: the handle
 * crosses as {@code LuaValue.userdataOf(luaPagina, mt)} so Lua cannot scribble on it, and the {@link Cache} on
 * the owning {@link Addon} (weak values + a {@link ReferenceQueue} drained on every access — <i>not</i> a
 * {@code WeakHashMap}, which is weak <i>keys</i>) makes {@code :get(k) == :get(k)} and
 * {@code seen[pag] = true} reliable. Never static: no Lua value crosses a sandbox boundary and the cache dies
 * whole with the {@link Addon} on {@code :reload}.
 *
 * <p><b>Threading.</b> Every read runs on the UI thread (addon tick / REPL / timer / slash command);
 * {@code MenuGrid.paginae} is mutated on the UI thread under its own monitor and is copied under it here. The
 * {@link Cache} map is guarded on its own monitor (UI + REPL threads touch it).
 */
public final class LuaPagina {
    /** The account whose menu this entry is in — half the address, and what makes the name mean one entry. */
    public final String user;
    /** The resource name of the menu entry, in that character's catalogue — and its identity. */
    public final String res;

    private LuaPagina(String user, String res) {
        this.user = user;
        this.res = res;
    }

    /** {@code tostring(pag)} (also the {@code __tostring} answer): {@code Pagina(<res>)}. */
    public String toString() {
        return "Pagina(" + res + ")";
    }

    /** An interned Pagina object for {@code res} <b>in {@code user}'s menu</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, String res) {
        return owner.paginae.of(user, res);
    }

    /** The {@code LuaPagina} behind a Lua value, or {@code null} for anything that is not a Pagina object. */
    static LuaPagina resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaPagina) ? (LuaPagina)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Pagina interning cache and metatable (its {@link Addon#paginae}), keyed by the <b>account
     * plus</b> the resource name: a catalogue is one character's, so the same name reached through two
     * sessions names two entries in two grids and must have two handles. Two levels of map, the
     * {@link LuaGob} shape. Weak values + a {@link ReferenceQueue} drained on every access; the Pagina
     * metatable is built once, lazily. Holds its {@link Addon} because the collection hands out interned
     * handles for the owner.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<String, Ref>> live = new HashMap<String, Map<String, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code (user, res)} — a cache hit, or a freshly minted (inserted) one. */
        synchronized LuaValue of(String user, String res) {
            drain();
            Map<String, Ref> byres = live.get(user);
            if(byres == null)
                live.put(user, byres = new HashMap<String, Ref>());
            Ref r = byres.get(res);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byres.remove(res);
            }
            LuaValue v = LuaValue.userdataOf(new LuaPagina(user, res), meta());
            byres.put(res, new Ref(v, user, res, dead));
            return v;
        }

        /** Drop the map entries whose handle Lua has released (the key + dead ref would leak otherwise). */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref pr = (Ref)r;
                Map<String, Ref> byres = live.get(pr.user);
                if(byres == null)
                    continue;
                if(byres.get(pr.key) == pr)     // not already replaced by a fresh handle for the same res
                    byres.remove(pr.key);
                if(byres.isEmpty())
                    live.remove(pr.user);
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
        final String key;

        Ref(LuaValue v, String user, String key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the Pagina metatable ----------------------------------------------------------------------

    /** The per-addon metatable: {@code __index} = the methods table, plus {@code __tostring}/{@code __name}. */
    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Retired.methodIndex("pagina", methods(owner)));
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
        // slot:res(name) takes). Answers from the handle alone, like slot:index() / kin:id().
        m.set("res", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(handle(self, "res").res);
            }
        });
        // exists() — is this entry still in the menu? False once the action is revoked, and also while its
        // resource has not resolved yet (the catalogue fills in — see the class comment).
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPagina h = handle(self, "exists");
                return LuaValue.valueOf(live(h.user, h.res) != null);
            }
        });
        // name() — the DISPLAY name the grid paints in the tooltip (Resource.AButton.name).
        // name(text) — 059.1: set it, on an entry THIS addon added. Arity is the verb, so the reader above is
        // the same name with no argument; the write refuses on the client's own entries and on another
        // addon's, naming which (the grid's own catalogue is the server's to describe).
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue v = Args.written(a, 2, "pagina:name", "text");
                if(v == null) {
                    String n = dispname(button(self, "name"));
                    return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
                }
                if(v.isnumber() || !v.isstring())
                    throw new LuaError("pagina:name(text): the display name is a string — the one the grid"
                        + " paints over the button and shows in its tooltip; got " + v.typename());
                LuaPagina h = handle(self, "name");
                AddonPagina p = AddonPagina.owned(owner, h.user, h.res, "pagina:name(text)");
                p.name(v.tojstring());
                return self;
            }
        });
        // icon() — the image this entry draws, as your own hafen.asset handle; nil on an entry with no icon
        // set and on every one of the client's own (their art is a .res sprite, not a file of yours).
        // icon(image) — 059.1: draw the addon's own PNG in the cell, fitted to it. A hafen.asset image HANDLE,
        // never a path: the loader is one door and the file is loaded once (see hafen.asset).
        m.set("icon", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue v = Args.written(a, 2, "pagina:icon", "image");
                LuaPagina h = handle(self, "icon");
                String res = h.res;
                if(v == null) {
                    MenuGrid.Pagina p = live(h.user, res);
                    LuaImage li = (p instanceof AddonPagina) ? ((AddonPagina)p).icon() : null;
                    return (li == null) ? LuaValue.NIL : AssetApi.imageFor(owner, li);
                }
                AddonPagina p = AddonPagina.owned(owner, h.user, res, "pagina:icon(image)");
                p.icon(image(v));
                return self;
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
                if(b.pag instanceof AddonPagina)   // a custom entry sends nothing, so it has no tokens (059.1)
                    return new LuaTable();
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
        // tooltip(text) — 059.3: set it, on an entry THIS addon added. The grid paints it under the name once
        // the pointer has rested on the button; an entry with none has a tip that is the name alone.
        m.set("tooltip", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue v = Args.written(a, 2, "pagina:tooltip", "text");
                if(v == null) {
                    String t = tooltip(button(self, "tooltip"));
                    return (t == null) ? LuaValue.NIL : LuaValue.valueOf(t);
                }
                if(v.isnumber() || !v.isstring())
                    throw new LuaError("pagina:tooltip(text): the description is a string — the line the grid"
                        + " paints under the name; got " + v.typename());
                LuaPagina h = handle(self, "tooltip");
                AddonPagina p = AddonPagina.owned(owner, h.user, h.res, "pagina:tooltip(text)");
                p.tooltip(v.tojstring());
                return self;
            }
        });
        // addon() — the id of the addon that ADDED this entry, nil for the client's own. The one reader that
        // answers "whose is this": every write verb here refuses on an entry another addon added, and this is
        // how a handle tells the two apart before trying. Your own entries answer your own manifest id.
        m.set("addon", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPagina h = handle(self, "addon");
                MenuGrid.Pagina p = live(h.user, h.res);
                if(!(p instanceof AddonPagina))
                    return LuaValue.NIL;
                return LuaValue.valueOf(((AddonPagina)p).owner.manifest.id);
            }
        });
        // on(key, fn) — 059.3: run fn(pag) when this entry is clicked, and when pag:use() fires it. The one
        // notification verb, on the object that emits it (D-100): a Sub back, sub:off() to end it, and the whole
        // set dropped on :reload/disable or when the entry is :remove()d. The key set is CLOSED — an entry says
        // one thing — so a misspelling throws at the line that wrote it rather than reading as a subscription
        // that never fires. Only on an entry THIS addon added: the handlers are the owner's code, charged to the
        // owner and torn down with it, so there is nowhere to put another addon's.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaValue keyArg = Args.required(a, 2, "pagina:on", "key");
                LuaValue fnArg = Args.required(a, 3, "pagina:on", "fn");
                if(keyArg.isnumber() || !keyArg.isstring() || !fnArg.isfunction())
                    throw new LuaError("pagina:on(key, fn) expects (string, function)");
                String key = keyArg.tojstring();
                if(!"use".equals(key))
                    throw new LuaError("pagina:on(key, fn): a menu entry has no event '" + key + "' — it has:"
                        + " use, which fires on a left-click and on pag:use()");
                LuaPagina h = handle(self, "on");
                AddonPagina p = AddonPagina.owned(owner, h.user, h.res, "pagina:on(\"use\", fn)");
                return p.subs.on(key, fnArg);
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
        // isNew() — is this entry still flagged as a NEW DISCOVERY (the green flash in the grid)? The flag is
        // cleared by the client when the button is actually used.
        m.set("isNew", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPagina h = handle(self, "isNew");
                MenuGrid.Pagina p = live(h.user, h.res);
                return LuaValue.valueOf((p != null) && (p.anew > 0));
            }
        });
        // parent() — the CATEGORY this entry sits under, as a Pagina object; nil for a root entry (and while
        // the parent's own resource is still Loading).
        // parent(pagOrNil) — 059.2: hang one of THIS addon's entries under a category. The two kinds share one
        // tree, so the parent is any entry in the menu — one of your own, or one of the client's own — and a
        // category is simply an entry that has children. nil is DOCUMENTED here (the root screen), so it is the
        // write and not the read: Args.passed, never Args.written.
        m.set("parent", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaPagina h = handle(self, "parent");
                String res = h.res;
                if(!Args.passed(a, 2)) {
                    MenuGrid.Pagina p = live(h.user, res);
                    if(p == null)
                        return LuaValue.NIL;
                    try {
                        MenuGrid.Pagina par = p.parent();
                        if(par == null)
                            return LuaValue.NIL;
                        String rn = resname(par);
                        return (rn == null) ? LuaValue.NIL : of(owner, h.user, rn);
                    } catch(RuntimeException e) {   // Loading etc.
                        return LuaValue.NIL;
                    }
                }
                LuaValue v = a.arg(2);
                MenuGrid.Pagina par = v.isnil() ? null : category(h.user, v);
                AddonPagina p = AddonPagina.owned(owner, h.user, res, "pagina:parent(pagOrNil)");
                p.parent(par);
                return self;
            }
        });
        // children() — the entries this one is the parent of, i.e. exactly the buttons the grid shows after
        // clicking it. An empty array for a leaf action: "is this a category" is #pag:children() > 0.
        m.set("children", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPagina h = handle(self, "children");
                MenuGrid.Pagina p = live(h.user, h.res);
                if(p == null)
                    return LuaValue.NIL;
                return childrenOf(owner, h.user, p);
            }
        });
        // info() — the one SNAPSHOT escape hatch, for logging/serialising: the same fields as plain values
        // (parent as its RESOURCE NAME, not a handle), absent when they do not resolve.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPagina h = handle(self, "info");
                return snapshot(h.user, h.res);
            }
        });
        // -- the PROTECTED verb: drive the client's own PagButton.use (D-009), return self -----------------
        // use() — exactly what a LEFT-click on that menu button does. It goes through PagButton.use, which is
        // the pure MESSAGE half and branches "act"-by-path vs "use"-by-id internally — which is how it reaches
        // an id-only pagina no path can express. NOT MenuGrid.use(btn, iact, reset): that is the WIDGET's click
        // handler, and for an entry with children it merely changes the visible page and resets the grid's
        // state — side effects an addon call must not have. Makewindow does exactly this from a non-grid
        // widget (src/haven/Makewindow.java:375).
        // NO ARGUMENTS on purpose: PagButton.use never reads Interaction.modflags — it builds the message from
        // ui.modflags() live — so a mods parameter could only lie about the keyboard state (plan.md has the
        // trace).
        // On a CUSTOM entry the same call runs the addon's own on("use", fn) handlers instead, because
        // AddonPagButton.use IS that — one door, and no branch here that could answer differently from a click.
        // 048.5: PROTECTED (D-027/D-028). This verb commits a real server action and shipped unprotected only
        // because 023 predated the tier being applied per subsystem; a verb that acts is behind the "menugrid.use"
        // permission wherever it lives. The gate runs FIRST, before the live-entry lookup (D-213), so an addon
        // that may not act at all is told THAT rather than "not in the menu". The menugrid READS are untouched.
        m.set("use", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                AddonManager.requirePermission(owner, Permission.MENUGRID_USE);
                LuaPagina h = handle(self, "use");
                String res = h.res;
                // THAT character's menu, and THAT character's button: PagButton.use sends through the grid
                // widget's own tree, so an action fired on a session nobody is looking at reaches its server.
                MenuGrid.Pagina p = live(h.user, res);
                if(p == null)
                    throw new LuaError("pagina:use(): \"" + res + "\" is not in the menu — revoked, or the"
                        + " catalogue has not filled it in yet (check :exists())");
                MenuGrid.PagButton b = button(p);
                if(b == null)
                    throw new LuaError("pagina:use(): \"" + res + "\" has not finished loading yet — its"
                        + " resource is still Loading; retry on a later tick");
                if(hasChildren(h.user, p))
                    throw new LuaError("pagina:use(): \"" + res + "\" is a CATEGORY, not an action — there is"
                        + " nothing to send. Use :children() to reach the entries under it.");
                b.use(new MenuGrid.Interaction(1, 0));
                return self;
            }
        });
        return m;
    }

    /**
     * {@code pag:icon(image)}'s argument &rarr; the loaded image. <b>One door, and it is a handle</b>: an icon
     * is a file the addon ships, loaded once through {@code hafen.asset} and drawn many times, so a path
     * string is refused pointing back at the loader rather than quietly loading the file again. An asset of
     * another type says which type it is — the two calls are one file extension apart.
     */
    private static LuaImage image(LuaValue v) {
        LuaImage li = LuaImage.resolve(v);
        if(li != null) {
            if(li.dead)
                throw new LuaError("pagina:icon(image): this asset has been disposed — after a :dispose(),"
                    + " hafen.asset():get(path) loads the file again as a NEW asset");
            return li;
        }
        if(v.isstring() && !v.isnumber())
            throw new LuaError("pagina:icon(image): \"" + v.tojstring() + "\" is a path, and an icon is the"
                + " HANDLE the loader hands back — hafen.asset():get(\"" + v.tojstring() + "\"). A menu entry"
                + " draws a file your addon ships, not one of the client's own resources.");
        String type = assetType(v);
        if((type == null) && (FontHandle.resolve(v) != null))
            type = "font";                    // a BUILT-IN font is a font handle that was never an asset
        if(type != null)
            throw new LuaError("pagina:icon(image): that is a \"" + type + "\" handle, and an icon is an image"
                + " — load a .png with hafen.asset():get(\"dig.png\") and pass that one");
        throw new LuaError("pagina:icon(image): the icon is a hafen.asset image handle"
            + " (hafen.asset():get(\"dig.png\")), got " + v.typename());
    }

    /**
     * {@code pag:parent(pagOrNil)}'s argument &rarr; the <b>live</b> category. It is a Pagina object and not a
     * key: a parent is a place in the menu rather than a name, so it is addressed the way every reference-based
     * verb in this API addresses one, and a string is refused pointing at {@code :get(key)} — which is also the
     * only answer to "it is not in the menu", the case a key could never tell apart from a typo.
     */
    private static MenuGrid.Pagina category(String user, LuaValue v) {
        LuaPagina h = resolve(v);
        if(h == null) {
            if(v.isstring() && !v.isnumber())
                throw new LuaError("pagina:parent(pagOrNil): \"" + v.tojstring() + "\" is a key, and a parent is"
                    + " the Pagina object — " + CharApi.MG + ":get(\"" + v.tojstring() + "\"), or nil for the"
                    + " root screen");
            throw new LuaError("pagina:parent(pagOrNil): the parent is a Pagina object (" + CharApi.MG
                + ":get(key), " + CharApi.MG + ":add(id)) or nil for the root screen, got " + v.typename());
        }
        // 077.3: and it is a category in THIS character's menu. One tree per login, so an entry cannot hang
        // under a screen that is in another character's grid — a name that happens to exist in both would
        // otherwise read as the same place.
        if(!user.equals(h.user))
            throw new LuaError("pagina:parent(pagOrNil): \"" + h.res + "\" is an entry in " + h.user + "'s"
                + " menu, and an entry hangs under a category in its own character's — reach the parent"
                + " through the same session: hafen.session():get(\"" + user + "\"):menugrid():get(key)");
        MenuGrid.Pagina p = live(user, h.res);
        if(p == null)
            throw new LuaError("pagina:parent(pagOrNil): \"" + h.res + "\" is not in the menu — an entry can"
                + " only hang under one that is there (check :exists()), since a screen nothing reaches draws"
                + " nothing");
        return p;
    }

    /** The {@code :type()} of an asset handle that is not an image ({@code null} when it is not one at all). */
    private static String assetType(LuaValue v) {
        if(!v.istable())
            return null;
        try {
            LuaValue t = v.get("type");
            if(!t.isfunction())
                return null;
            LuaValue r = t.call(v);
            return r.isstring() ? r.tojstring() : null;
        } catch(RuntimeException e) {   // a foreign table with a "type" of its own — not an asset at all
            return null;
        }
    }

    // ---- self resolution ---------------------------------------------------------------------------

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaPagina handle(LuaValue self, String method) {
        LuaPagina h = resolve(self);
        if(h == null)
            throw new LuaError("pagina:" + method + "() — use a COLON call on a Pagina object ("
                + CharApi.MG + ":get(key), " + CharApi.MG + ":list()[i])");
        return h;
    }

    /** The LIVE button behind a method's {@code self}: re-resolved every call, {@code null} when unavailable. */
    private static MenuGrid.PagButton button(LuaValue self, String method) {
        LuaPagina h = handle(self, method);
        MenuGrid.Pagina p = live(h.user, h.res);
        return (p == null) ? null : button(p);
    }

    // ---- the menu-grid funnel ----------------------------------------------------------------------

    /** That character's action menu, or {@code null} while it has no HUD (pre-login, mid-{@code :reload}). */
    static MenuGrid grid(String user) {
        GameUI g = AddonManager.gameui(user);   // THAT session's HUD, not the drawn one's
        return (g == null) ? null : g.menu;
    }

    /**
     * A pagina's resource name, or {@code null} while its resource is still {@code Loading}. <b>A custom entry
     * names itself</b> (059.1): its backing {@link Resource} is a stand-in shared by every one of them, so the
     * identity is the id its addon gave it — which is what makes {@code :get}, the intern cache and the
     * catalogue's own deduplication all address a custom entry the way they address a granted one.
     */
    private static String resname(MenuGrid.Pagina p) {
        if(p instanceof AddonPagina)
            return ((AddonPagina)p).id;
        try {
            Resource r = p.res();
            return (r == null) ? null : r.name;
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /** One entry as an error message names it: its identity in quotes, or a phrase when it has no name yet. */
    static String label(MenuGrid.Pagina p) {
        String rn = resname(p);
        return (rn == null) ? "that entry" : ("\"" + rn + "\"");
    }

    /** A pagina's drawn button (the name/act/hotkey carrier), or {@code null} while it is {@code Loading}. */
    private static MenuGrid.PagButton button(MenuGrid.Pagina p) {
        try {
            return p.button();
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    /**
     * The display name — for a custom entry the <b>raw</b> string its addon set, never the escaped one the tip
     * renders ({@code AddonPagButton.name()} quotes {@code RichText}'s markup characters so a {@code $} in a
     * name cannot throw out of the draw thread). Every name-shaped read is this one call, so {@code :name()},
     * {@code :info()}, the display-name lookup and the string filter all speak the string that was written.
     */
    private static String dispname(MenuGrid.PagButton b) {
        if(b == null)
            return null;
        if(b.pag instanceof AddonPagina)
            return ((AddonPagina)b.pag).name();
        try {
            return b.name();
        } catch(RuntimeException e) {   // Loading etc.
            return null;
        }
    }

    private static String tooltip(MenuGrid.PagButton b) {
        if(b == null)
            return null;
        if(b.pag instanceof AddonPagina)      // the stand-in's own pagina layer is not this entry's (059.1)
            return ((AddonPagina)b.pag).tooltip();
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
    private static List<MenuGrid.Pagina> closure(String user) {
        MenuGrid scm = grid(user);
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
    private static List<Entry> catalogue(String user) {
        List<MenuGrid.Pagina> all = closure(user);
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

    /** The live pagina behind a resource name in that character's menu, or {@code null} for no such entry. */
    private static MenuGrid.Pagina live(String user, String res) {
        List<MenuGrid.Pagina> all = closure(user);
        for(int i = 0; i < all.size(); i++) {
            MenuGrid.Pagina p = all.get(i);
            if(res.equals(resname(p)))
                return p;
        }
        return null;
    }

    /** The entries whose {@code parent()} is {@code p} (identity — the client interns paginae in its pmap). */
    private static LuaValue childrenOf(Addon owner, String user, MenuGrid.Pagina p) {
        LuaTable out = new LuaTable();
        List<Entry> cat = catalogue(user);
        int i = 0;
        for(int n = 0; n < cat.size(); n++) {
            Entry e = cat.get(n);
            try {
                if(e.pag.parent() == p)
                    out.set(++i, of(owner, user, e.res));
            } catch(RuntimeException ex) {   // Loading etc. — skip, never throw into Lua
            }
        }
        return out;
    }

    /**
     * Is {@code p} a <b>category</b>, i.e. does anything hang under it? The one question {@code :use()} must
     * ask, and it asks it over the raw {@link #closure} rather than the {@link #catalogue}: a child whose own
     * resource has not resolved yet still makes its parent a category, and there is no name to resolve here.
     */
    private static boolean hasChildren(String user, MenuGrid.Pagina p) {
        List<MenuGrid.Pagina> all = closure(user);
        for(int i = 0; i < all.size(); i++) {
            try {
                if(all.get(i).parent() == p)
                    return true;
            } catch(RuntimeException e) {   // Loading etc. — skip, never throw into Lua
            }
        }
        return false;
    }

    /** {@code pag:info()} — a plain snapshot table; a field the menu cannot answer is simply absent. */
    private static LuaValue snapshot(String user, String res) {
        LuaTable t = new LuaTable();
        t.set("res", LuaValue.valueOf(res));
        MenuGrid.Pagina p = live(user, res);
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
        if(p instanceof AddonPagina) {
            t.set("path", new LuaTable());     // a custom entry sends nothing (059.1)
            t.set("addon", LuaValue.valueOf(((AddonPagina)p).owner.manifest.id));   // whose entry it is (059.3)
        } else if(b != null) {
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
     * {@code s:menugrid()} — <b>that character's</b> catalogue, as the {@link LuaCollection} the section
     * object IS: {@code :get(key)} is one entry by resource or display name, {@code :list(filter)} the whole
     * catalogue as a fresh 1-based array of (interned) Pagina objects in the grid's own sort order,
     * {@code :find(filter)} the first that matches, and {@code :roots()} the entries the menu shows on its
     * root screen — plural, because it is a plain array read with nothing to address into (§2.3). No menu
     * yet ⇒ an empty catalogue, never an error.
     *
     * <p>A <b>string</b> filter matches an entry's <b>display name</b> as a substring; an entry whose resource
     * has not resolved yet has no display name and matches nothing, rather than refusing the filter.
     */
    static LuaValue collection(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        // roots() — the entries with no parent, i.e. what the menu shows on its ROOT screen.
        extra.set("roots", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return childrenOf(owner, user, null);
            }
        });
        return LuaCollection.create(CharApi.MG, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<Entry> cat = catalogue(user);
                List<LuaValue> out = new ArrayList<LuaValue>(cat.size());
                for(int i = 0; i < cat.size(); i++)
                    out.add(of(owner, user, cat.get(i).res));
                return out;
            }

            public String needle(LuaValue member) {
                LuaPagina h = resolve(member);
                String nm = (h == null) ? null : dispname(button(live(user, h.res)));
                return (nm == null) ? "" : nm;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                if(key.isnumber())                  // BEFORE isstring(): in LuaJ a number IS a string
                    throw new LuaError(CharApi.MG + ":get(key): the menu has no positions to address — the"
                        + " catalogue grows on every discovery, so a position is not an index. Use a resource"
                        + " name (\"paginae/act/dig\") or a display name (\"Dig\").");
                if(!key.isstring())
                    throw new LuaError(CharApi.MG + ":get(key): expected a string — one with a '/' is a"
                        + " resource name, any other is a display name; got " + key.typename());
                return find(owner, user, key.tojstring());
            }

            public boolean creatable() {
                return true;
            }

            // add(id) — 059.1: mint an entry of this addon's own and hand it back for its setters (:name,
            // :icon). It is drawn by this client and reaches no server, so it needs no permission — and it is
            // a Pagina like any other, so every reader on this page answers for it.
            public LuaValue addMember(Varargs a) {
                return AddonPagina.add(owner, user, Args.required(a, 2, CharApi.MG + ":add", "id"));
            }

            public boolean destroyable() {
                return true;
            }

            // remove(idOrPagina) — take one of THIS addon's entries out again. The client's own catalogue is
            // not removable: what the server granted is the server's to revoke.
            public void removeMember(LuaValue x) {
                AddonPagina.remove(owner, user, x);
            }
        }, extra);
    }

    /** One entry BY KEY: a {@code /} makes it a resource name, anything else a display name. Miss ⇒ nil. */
    private static LuaValue find(Addon owner, String user, String key) {
        List<Entry> cat = catalogue(user);
        if(key.indexOf('/') >= 0) {                 // SHAPE, not fallback: a res name never scans names
            for(int i = 0; i < cat.size(); i++) {
                if(cat.get(i).res.equals(key))
                    return of(owner, user, key);
            }
            return LuaValue.NIL;
        }
        for(int i = 0; i < cat.size(); i++) {       // first match in catalogue order — names are not unique
            Entry e = cat.get(i);
            String nm = dispname(button(e.pag));
            if((nm != null) && nm.equalsIgnoreCase(key))
                return of(owner, user, e.res);
        }
        return LuaValue.NIL;
    }
}
