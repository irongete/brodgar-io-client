package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.GSprite;
import haven.Indir;
import haven.Inventory;
import haven.KeyBinding;
import haven.KeyMatch;
import haven.MenuGrid;
import haven.Resource;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * <b>An entry an addon added to the action menu</b> ({@code hafen.menugrid():add(id)}, spec
 * {@code 059-menugrid-entries}) — a {@link MenuGrid.Pagina} subclass, so <b>the grid never learns it is
 * different</b>: it sits in the very {@code paginae} set the server's own entries sit in, it is laid out by
 * {@code MenuGrid.cons}, drawn by {@code MenuGrid.draw} and read by every {@link LuaPagina} verb.
 *
 * <p><b>Two virtual methods are the whole seam.</b> The engine reaches everything about an entry through
 * {@code Pagina.button()} and the {@link MenuGrid.PagButton} it returns, so a subclass pair covers it and
 * nothing in {@link MenuGrid} is edited. This class <b>carries every piece of state</b> — owner, id, display
 * name, tooltip, icon — and {@link AddonPagButton} reads {@code pag} and holds none of its own, because
 * {@code PagButton}'s constructor calls the virtual {@code binding()} before a subclass's fields exist.
 *
 * <p><b>The backing {@link Resource} is a stand-in.</b> {@code Resource}'s constructor is private and every
 * instance is pool-managed, so a custom entry cannot mint one: it is constructed over the same local resource
 * {@code MenuGrid}'s own {@code next}/{@code bk} buttons use, and <b>nothing may key on it</b>.
 * {@link LuaPagina} branches {@code resname}, {@code tooltip} and {@code path} onto this class for exactly
 * that reason — a custom entry names itself, by the {@link #id} the addon gave it, rather than its stand-in.
 *
 * <p><b>Nothing here reaches the server.</b> The entry is drawn by this client and a click on it runs Lua
 * (059.3), so {@link AddonPagButton#use} sends no message at all — where the stock button would send
 * {@code "act"} or {@code "use"} — and adding one needs no permission, like a HUD overlay.
 *
 * <p><b>Ownership (P2).</b> Bridge-owned: an entry lives in its addon's owned-resource registry
 * ({@link Addon#menuEntries}) and {@link #teardownEntries} takes every one of them back out of the grid on
 * {@code :reload}/disable/relogin, so the menu is left holding exactly the game's own catalogue.
 *
 * <p><b>Threading.</b> Minting, removing and every setter run on the UI thread (a Lua call); the
 * {@code paginae} set is mutated under its own monitor, as the engine mutates it. The icon is read on the
 * draw thread, where {@link LuaImage#dead} is the volatile guard that keeps a disposed texture off the screen.
 */
public final class AddonPagina extends MenuGrid.Pagina {
    /** The local resource a custom entry is constructed over — {@code MenuGrid}'s own paging arrow. */
    private static final String STANDIN = "gfx/hud/sc-next";
    /** The interior of one grid cell, in device pixels: what the client's own action icons cover. */
    static final Coord CELL = Inventory.sqsz.sub(1, 1);
    /** The prefix every custom identity carries, which is also what tells one from a resource name. */
    static final String PREFIX = "addon/";

    private static Indir<Resource> standin;

    /** The stand-in, loaded once. Local (jar-backed), so the wait is a map lookup after the first call. */
    private static synchronized Indir<Resource> standin() {
        if(standin == null)
            standin = Resource.local().loadwait(STANDIN).indir();
        return standin;
    }

    /** The addon that added this entry, and the only one that may write it. */
    final Addon owner;
    /** The identity — {@code addon/<addon id>/<the id :add was given>}. What {@code pag:res()} answers. */
    final String id;
    /** The display name the grid paints, and its sort key. Never {@code null}. */
    private String name;
    /** The description under the name, or {@code null} — the setter arrives with the click (059.3). */
    private String tooltip;
    /** The addon's own PNG, or {@code null} for an entry that draws an empty cell. */
    private LuaImage icon;
    /** The category this entry hangs under, or {@code null} for the root screen. Any live entry may be one. */
    private MenuGrid.Pagina parent;

    private AddonPagButton button;

    private AddonPagina(Addon owner, MenuGrid scm, String id, String name) {
        super(scm, id, standin());
        this.owner = owner;
        this.id = id;
        this.name = name;
    }

    /**
     * The drawn button, minted once. {@code Pagina.button} is private and {@code Pagina.button()} caches into
     * it, so the subclass caches into its own field instead — the trick {@code MenuGrid}'s {@code next}/
     * {@code bk} use (assigning the private field from an initialiser) is not available from here.
     */
    public MenuGrid.PagButton button() {
        if(button == null)
            button = new AddonPagButton(this);
        return button;
    }

    /**
     * The category this entry hangs under, or {@code null} for the root screen. <b>Read live, never cached</b>:
     * the stock {@code PagButton.parent()} memoises the parent it derived from {@code act().parent}, and a
     * parent that an addon can rewrite cannot go through a memo — so both this and {@link AddonPagButton#parent()}
     * answer from the field on every call, which is what {@code MenuGrid.cons} then walks.
     */
    public MenuGrid.Pagina parent() {
        return parent;
    }

    String name() {
        return name;
    }

    String tooltip() {
        return tooltip;
    }

    LuaImage icon() {
        return icon;
    }

    void name(String s) {
        this.name = s;
        relayout();
    }

    void icon(LuaImage img) {
        this.icon = img;
        relayout();
    }

    /**
     * Hang this entry under {@code par} — one of the addon's own, one of the client's own, or {@code null} for
     * the root screen. <b>The two kinds share one tree</b>: a parent is any live entry, since {@code cons}
     * reaches a category through {@code parent()} alone and does not care which kind answered.
     *
     * <p><b>A cycle is refused before it is written.</b> The walk up from {@code par} is the whole test: an
     * entry that is its own ancestor draws in no screen at all (the closure reaches it, no screen's {@code cons}
     * ever emits it), and the {@code anew} walk at the head of {@code cons} — an unguarded parent chain — would
     * hang outright if a new discovery ever led into one. It is bounded twice over: by that refusal, and by the
     * {@code seen} set here, so a chain can never be walked twice whatever it holds.
     */
    void parent(MenuGrid.Pagina par) {
        Set<MenuGrid.Pagina> seen = new HashSet<MenuGrid.Pagina>();
        for(MenuGrid.Pagina up = par; up != null; ) {
            if(up == this) {
                if(par == this)
                    throw new LuaError("pagina:parent(pagOrNil): \"" + id + "\" cannot hang under itself — a"
                        + " category is simply an entry that has children, and nothing is its own child");
                throw new LuaError("pagina:parent(pagOrNil): \"" + id + "\" cannot hang under "
                    + LuaPagina.label(par) + ", because that entry already hangs under this one — a cycle"
                    + " takes both of them out of the menu, since neither is reachable from the root screen");
            }
            if(!seen.add(up))
                break;
            try {
                up = up.parent();
            } catch(RuntimeException e) {    // Loading — the chain above this point is not readable yet
                break;
            }
        }
        this.parent = par;
        relayout();
    }

    /**
     * Rebuild the grid's layout. {@code updlayout()} and the {@code recons} flag are private, so
     * {@code change(cur)} — which re-runs {@code cons} and re-fills {@code layout} — is the one public door.
     * It resets the page offset, so a setter written while the player is on page 2 of a category puts them
     * back on page 1.
     */
    private void relayout() {
        scm.change(scm.cur);
    }

    /** {@code tostring} for a Java-side log; Lua sees {@link LuaPagina}'s. */
    public String toString() {
        return "AddonPagina(" + id + ")";
    }

    // ---- the button ------------------------------------------------------------------------------------

    /**
     * The drawn half. <b>Every override reads {@code pag}</b> and holds no state of its own: {@code
     * PagButton(Pagina)} assigns {@code res} and then calls the virtual {@code binding()}, so an override that
     * read a field of this class would read it before the constructor had written it — and Java will not warn.
     * The sprite cache below is written lazily, long after construction.
     */
    static final class AddonPagButton extends MenuGrid.PagButton {
        private GSprite spr;
        private LuaImage sprfor;

        AddonPagButton(AddonPagina pag) {
            super(pag);
        }

        private AddonPagina pag() {
            return (AddonPagina)pag;
        }

        public String name() {
            return pag().name;
        }

        /** The grid sorts on this. The stock one reads {@code act()}, which a stand-in cannot answer. */
        public String sortkey() {
            return pag().name;
        }

        public MenuGrid.Pagina parent() {
            return pag().parent();
        }

        /**
         * An unbound, remappable binding of this entry's own. The stock one reaches {@code hotkey()} →
         * {@code act()} → {@code res.flayer(Resource.action)}, which a stand-in has no layer for — and it runs
         * <b>inside the constructor</b>, so leaving it stock is an NPE at mint time rather than at draw time.
         */
        public KeyBinding binding() {
            return KeyBinding.get("scm/" + ((AddonPagina)pag).id, KeyMatch.nil);
        }

        public KeyMatch hotkey() {
            return KeyMatch.nil;
        }

        /** The icon, fitted to the cell. Rebuilt when the addon sets another one; never {@code null}. */
        public GSprite spr() {
            LuaImage img = pag().icon;
            if((spr == null) || (sprfor != img)) {
                spr = new Icon(this, img);
                sprfor = img;
            }
            return spr;
        }

        /**
         * A click. <b>Nothing is sent</b>: a custom entry is the client's, so the stock body — which would
         * message the server with an action path or a session id the server never issued — is replaced by
         * doing nothing at all. The Lua handlers hang here (059.3).
         */
        public void use(MenuGrid.Interaction iact) {
        }
    }

    // ---- the icon --------------------------------------------------------------------------------------

    /**
     * The addon's PNG, drawn in one grid cell. {@code MenuGrid.draw} reclips to {@code spr.sz()} in
     * <b>device</b> pixels, so the sprite covers the cell interior whatever the file's own size is: an image
     * bigger than the cell is scaled down keeping its aspect ratio, a smaller one is drawn at its own size,
     * and either way it is centred. {@link LuaImage#stex} is already the texture at the size it is drawn
     * (an addon's file is authored in design pixels), so nothing here scales twice.
     */
    static final class Icon extends GSprite {
        private final LuaImage img;
        private final Coord dsz, off;

        Icon(GSprite.Owner owner, LuaImage img) {
            super(owner);
            this.img = img;
            Coord isz = (img == null) ? null : img.stex.sz();
            this.dsz = fit(isz);
            this.off = CELL.sub(dsz).div(2);
        }

        /** The size the image is drawn at: shrunk to the cell when it is bigger, its own when it is not. */
        private static Coord fit(Coord sz) {
            if((sz == null) || (sz.x <= 0) || (sz.y <= 0))
                return Coord.z;
            if((sz.x <= CELL.x) && (sz.y <= CELL.y))
                return sz;
            double s = Math.min((double)CELL.x / sz.x, (double)CELL.y / sz.y);
            return Coord.of(Math.max(1, (int)Math.round(sz.x * s)), Math.max(1, (int)Math.round(sz.y * s)));
        }

        public Coord sz() {
            return CELL;
        }

        public void draw(GOut g) {
            LuaImage li = img;
            if((li == null) || li.dead)      // no icon set, or its asset was disposed → an empty cell
                return;
            g.image(li.stex, off, dsz);
        }
    }

    // ---- minting, removing, teardown -------------------------------------------------------------------

    /**
     * {@code hafen.menugrid():add(id)} — mint an entry this addon owns, put it in the grid and hand back its
     * (interned) Pagina object. The id is <b>addon-relative</b>, exactly as an asset path is, and the identity
     * it gets is {@code addon/<addon id>/<id>}: two addons cannot collide, and the {@code /} it carries makes
     * {@code :get()} resolve it by shape like any other resource name.
     */
    static LuaValue add(Addon owner, LuaValue key) {
        String rel = relative(owner, key, "add");
        MenuGrid scm = LuaPagina.grid();
        if(scm == null)
            throw new LuaError("hafen.menugrid():add(id): the action menu is not up yet — add your entries"
                + " from EnterWorld or later, not from Load");
        String id = PREFIX + owner.manifest.id + "/" + rel;
        for(AddonPagina p : owner.menuEntries) {
            if(p.id.equals(id))
                throw new LuaError("hafen.menugrid():add(id): this addon already has an entry with the id \""
                    + rel + "\" — an id is unique within an addon; :remove() that one first, or pick another");
        }
        AddonPagina p = new AddonPagina(owner, scm, id, rel);
        synchronized(scm.paginae) {
            scm.paginae.add(p);
        }
        owner.menuEntries.add(p);
        p.relayout();
        return LuaPagina.of(owner, id);
    }

    /**
     * {@code hafen.menugrid():remove(idOrPagina)} — take one of <b>this addon's</b> entries back out of the
     * menu. The client's own entries and another addon's are refused naming which; removing one that is
     * already gone is inert (D-084), since a removal is a moment rather than a mistake.
     */
    static void remove(Addon owner, LuaValue x) {
        String id = identity(owner, x);
        AddonPagina p = null;
        for(AddonPagina q : owner.menuEntries) {
            if(q.id.equals(id))
                p = q;
        }
        if(p == null)
            return;                     // already gone — inert, and pag:exists() is the question
        detach(p);
        owner.menuEntries.remove(p);
        p.relayout();
    }

    /** Take every entry this addon added back out of the menu (teardown, P2). */
    static void teardownEntries(Addon a) {
        if((a == null) || a.menuEntries.isEmpty())
            return;
        MenuGrid scm = null;
        for(AddonPagina p : new ArrayList<AddonPagina>(a.menuEntries)) {
            detach(p);
            scm = p.scm;
        }
        a.menuEntries.clear();
        if(scm != null)
            scm.change(scm.cur);        // one relayout for the whole sweep
    }

    /**
     * Drop one entry out of the grid it was added to (its own, never {@code AddonManager.gui()}'s), and
     * <b>re-root whatever hung under it</b>: a category that leaves takes no child with it, so the children go
     * back to the root screen rather than under a parent no screen reaches — which would draw the removed
     * category itself back onto the root screen, since {@code cons} walks the closure through {@code parent()}
     * and does not ask whether the parent is still in {@code paginae}. Every custom entry of every addon is in
     * this set, so one pass over it covers a child another addon hung under this category too.
     */
    private static void detach(AddonPagina p) {
        synchronized(p.scm.paginae) {
            p.scm.paginae.remove(p);
            for(MenuGrid.Pagina q : p.scm.paginae) {
                if((q instanceof AddonPagina) && (((AddonPagina)q).parent == p))
                    ((AddonPagina)q).parent = null;
            }
        }
    }

    // ---- the id ----------------------------------------------------------------------------------------

    /**
     * The <b>live</b> entry a write verb is addressing, or a refusal naming <i>whose</i> entry it is. An addon
     * writes the entries it added and nothing else: the client's own are the server's to describe, and another
     * addon's are that addon's.
     */
    static AddonPagina owned(Addon owner, String res, String verb) {
        for(AddonPagina p : owner.menuEntries) {
            if(p.id.equals(res))
                return p;
        }
        String mine = PREFIX + owner.manifest.id + "/";
        if(res.startsWith(mine))
            throw new LuaError("pagina:" + verb + ": \"" + res + "\" is no longer in the menu — this addon"
                + " added it and removed it again (check :exists())");
        if(res.startsWith(PREFIX)) {
            throw new LuaError("pagina:" + verb + ": \"" + res + "\" belongs to " + other(res) + ", not to"
                + " this addon — an addon writes only the entries it added with hafen.menugrid():add(id)");
        }
        throw new LuaError("pagina:" + verb + ": \"" + res + "\" is the client's own entry, not one this addon"
            + " added — the game's catalogue is read-only, and hafen.menugrid():add(id) mints an entry of"
            + " your own that every one of these verbs writes");
    }

    /** The addon id inside a foreign identity, for the refusal above. */
    private static String other(String res) {
        int e = res.indexOf('/', PREFIX.length());
        return (e < 0) ? "another addon" : ("the addon \"" + res.substring(PREFIX.length(), e) + "\"");
    }

    /**
     * {@code :remove}'s argument &rarr; a full identity. A Pagina object answers with its own; a <b>string</b>
     * splits by shape, exactly as {@code :get(key)} does — one starting {@code addon/} is a full identity (the
     * {@code :res()} you read back), anything else is the addon-relative id {@code :add} was given.
     */
    private static String identity(Addon owner, LuaValue x) {
        LuaPagina h = LuaPagina.resolve(x);
        if(h != null)
            return h.res;
        if(x.isnumber() || !x.isstring())
            throw new LuaError("hafen.menugrid():remove(idOrPagina): expected the Pagina object :add() handed"
                + " you, or its id as a string, got " + x.typename());
        String s = x.tojstring();
        if(s.startsWith(PREFIX))
            return s;
        return PREFIX + owner.manifest.id + "/" + relative(owner, x, "remove");
    }

    /**
     * Check an addon-relative id and hand it back. It is spelled like an asset path and refused like one — an
     * absolute id or one climbing out with {@code ..} would name something that is not this addon's — except
     * that nothing is resolved on disk: an id names an entry, not a file.
     */
    private static String relative(Addon owner, LuaValue key, String verb) {
        String call = "hafen.menugrid():" + verb + "(id" + (verb.equals("remove") ? "OrPagina" : "") + ")";
        if(key.isnumber() || !key.isstring())
            throw new LuaError(call + ": expected a string id, addon-relative like an asset path (\"dig\","
                + " \"tools/dig\"), got " + key.typename());
        String s = key.tojstring();
        if(s.isEmpty())
            throw new LuaError(call + ": the id must be a non-empty string, addon-relative like an asset path"
                + " (\"dig\", \"tools/dig\")");
        if(absolute(s))
            throw new LuaError(call + ": the id \"" + s + "\" is absolute — an id is relative to your own"
                + " addon (\"dig\", \"tools/dig\"), and the client's own entries are not yours to name");
        for(String seg : s.split("/", -1)) {
            if(seg.equals(".."))
                throw new LuaError(call + ": the id \"" + s + "\" climbs out of your addon with '..' — an id"
                    + " is relative to your own addon and cannot reach anything else");
            if(seg.isEmpty() || seg.equals("."))
                throw new LuaError(call + ": the id \"" + s + "\" has an empty segment — write it as"
                    + " \"dig\" or \"tools/dig\"");
        }
        return s;
    }

    /** Is this id spelled absolutely? A leading separator, or a Windows-style drive letter. */
    private static boolean absolute(String s) {
        if((s.charAt(0) == '/') || (s.charAt(0) == '\\'))
            return true;
        return (s.length() >= 2) && (s.charAt(1) == ':');
    }
}
