package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.GSprite;
import haven.Indir;
import haven.Inventory;
import haven.ItemInfo;
import haven.KeyBinding;
import haven.KeyMatch;
import haven.MenuGrid;
import haven.Resource;
import haven.RichText;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>An entry an addon added to the action menu</b> ({@code s:menugrid():add(id)}, spec
 * {@code 059-menugrid-entries}) — a {@link MenuGrid.Pagina} subclass, so <b>the grid never learns it is
 * different</b>: it sits in the very {@code paginae} set the server's own entries sit in, it is laid out by
 * {@code MenuGrid.cons}, drawn by {@code MenuGrid.draw} and read by every {@link LuaPagina} verb.
 *
 * <p><b>An entry stands in ONE character's menu</b> (077.3). {@code :add} is addressed at a session and the
 * entry goes into that login's grid, so the {@link #user} it carries is half its address: the same id may
 * stand in each character's menu, and a write verb reaches the one whose session it was addressed through.
 * The list on the {@link Addon} stays flat — an addon's entries are the addon's wherever they stand, and a
 * teardown takes every one of them out — so what 077.3 adds is the account on the lookups, not a second
 * registry.
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
 * <p><b>Nothing here reaches the server.</b> The entry is drawn by this client and a click on it runs Lua, so
 * {@link AddonPagButton#use} sends no message at all — where the stock button would send {@code "act"} or
 * {@code "use"} — and adding one needs no permission, like a HUD overlay. The handlers hang on the {@link #subs}
 * below, which is the <b>same</b> {@link Subs} every other {@code X:on(key, fn)} in the API is delivered
 * through: hold the object, subscribe on it.
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
    /** The account whose menu this entry stands in — the other half of what {@code :add} was addressed at. */
    final String user;
    /** The identity — {@code addon/<addon id>/<the id :add was given>}. What {@code pag:res()} answers. */
    final String id;
    /** The display name the grid paints, and its sort key. Never {@code null}. */
    private String name;
    /** The description the grid paints under the name, or {@code null} for a tip that is the name alone. */
    private String tooltip;
    /** The addon's own PNG, or {@code null} for an entry that draws an empty cell. */
    private LuaImage icon;
    /** The category this entry hangs under, or {@code null} for the root screen. Any live entry may be one. */
    private MenuGrid.Pagina parent;

    /**
     * The Lua handlers on this entry's one key, {@code "use"} — {@code pag:on("use", fn)}. One {@link Subs} per
     * entry, on the entry, because the state belongs on the THING (D-100): an entry nobody listens to costs an
     * empty map, and there is no registry anywhere to keep in step with {@code :remove()}.
     *
     * <p>It charges {@link Addon#C_WIDGET}, what a click on a button costs everywhere else in this API — the
     * grid's own {@code MenuGrid.use} is the caller, exactly as a native {@code Button}'s {@code Pressed} is.
     */
    final Subs subs;

    private AddonPagButton button;

    private AddonPagina(Addon owner, String user, MenuGrid scm, String id, String name) {
        super(scm, id, standin());
        this.owner = owner;
        this.user = user;
        this.id = id;
        this.name = name;
        this.subs = new Subs(owner, Addon.C_WIDGET);
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
     * The description under the name. <b>No relayout</b>, unlike every other setter here: the tip is composed at
     * hover time from {@link AddonPagButton#info()} and nothing about the grid's layout reads it, so rebuilding
     * would cost the player their page offset for a string they cannot even see yet.
     */
    void tooltip(String s) {
        this.tooltip = s;
    }

    /**
     * A left-click on this entry, and {@code pag:use()} — run every handler {@code pag:on("use", fn)} registered,
     * in registration order, each one error-isolated by {@link AddonManager#callLua}. The handler is handed the
     * entry itself, so one function can serve several buttons and still tell which was pressed. An entry nobody
     * subscribed to does nothing at all, which is what makes a bare {@code :add(id)} a legal thing to leave in
     * the menu.
     */
    void fire() {
        if(!subs.has("use"))
            return;
        subs.fire("use", LuaPagina.of(owner, user, id));
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

        /**
         * The label the tip renders, <b>quoted</b>. What the grid paints a name into is {@code RichText}, whose
         * {@code $}, <code>{</code> and <code>}</code> are markup: an unescaped one there is a
         * {@code FormatException} out of {@code rendertt}, on the draw thread, the moment the pointer rests on
         * the button — so a name an addon wrote is escaped once, here, and every reader below answers the raw
         * string it set ({@link LuaPagina#dispname}).
         */
        public String name() {
            return RichText.Parser.quote(pag().name);
        }

        /** The grid sorts on this — the RAW name, not the quoted one. The stock one reads {@code act()}. */
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
         * message the server with an action path or a session id the server never issued — is replaced by the
         * addon's own handlers. This is the one door: the grid's {@code MenuGrid.use(btn, iact, reset)} routes a
         * real left-click here, and {@code pag:use()} drives the same method (D-009), so the two cannot drift.
         */
        public void use(MenuGrid.Interaction iact) {
            pag().fire();
        }

        /**
         * What the long tooltip shows under the name. The stock body reads {@code res.layer(Resource.pagina)},
         * and the <b>stand-in has no pagina layer</b> — so an entry that did not answer here could never say
         * anything below its name, whatever {@code pag:tooltip(text)} was given. A <b>fresh list every call</b>,
         * because {@code rendertt} deletes from the list it is handed.
         */
        public List<ItemInfo> info() {
            List<ItemInfo> out = new ArrayList<ItemInfo>();
            String tt = pag().tooltip;
            if(tt != null)
                out.add(new ItemInfo.Pagina(this, RichText.Parser.quote(tt)));   // markup, as in name()
            return out;
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
     * {@code s:menugrid():add(id)} — mint an entry this addon owns, put it in <b>that character's</b> grid and
     * hand back its (interned) Pagina object. The id is <b>addon-relative</b>, exactly as an asset path is,
     * and the identity it gets is {@code addon/<addon id>/<id>}: two addons cannot collide, and the {@code /}
     * it carries makes {@code :get()} resolve it by shape like any other resource name.
     *
     * <p><b>Unique within an addon and within a character</b> (077.3). One id may stand in each character's
     * menu — an addon that puts its button on both its logins is doing the ordinary thing — so the clash the
     * refusal below names is the same id twice in the <i>same</i> grid.
     */
    static LuaValue add(Addon owner, String user, LuaValue key) {
        String rel = relative(owner, key, "add");
        MenuGrid scm = LuaPagina.grid(user);
        if(scm == null)
            throw new LuaError(CharApi.MG + ":add(id): the action menu is not up yet — add your entries"
                + " from SessionEnteredWorld or later, not from Load");
        reap(owner, user, scm);
        String id = PREFIX + owner.manifest.id + "/" + rel;
        for(AddonPagina p : owner.menuEntries) {
            if(p.id.equals(id) && p.user.equals(user))
                throw new LuaError(CharApi.MG + ":add(id): this addon already has an entry with the id \""
                    + rel + "\" in that character's menu — an id is unique within an addon and character;"
                    + " :remove() that one first, or pick another");
        }
        AddonPagina p = new AddonPagina(owner, user, scm, id, rel);
        synchronized(scm.paginae) {
            scm.paginae.add(p);
        }
        owner.menuEntries.add(p);
        p.relayout();
        BeltHold.entryAdded(p);     // 059.5: and back onto every bar slot this entry is placed in — at login
                                    //   this call IS the restore, run from the addon's own SessionEnteredWorld
        return LuaPagina.of(owner, user, id);
    }

    /**
     * {@code s:menugrid():remove(idOrPagina)} — take one of <b>this addon's</b> entries back out of
     * <b>that character's</b> menu. The client's own entries and another addon's are refused naming which;
     * removing one that is already gone is inert (D-084), since a removal is a moment rather than a mistake,
     * and so is removing one that stands only in another character's menu.
     */
    static void remove(Addon owner, String user, LuaValue x) {
        String id = identity(owner, x);
        reap(owner, user, LuaPagina.grid(user));
        AddonPagina p = null;
        for(AddonPagina q : owner.menuEntries) {
            if(q.id.equals(id) && q.user.equals(user))
                p = q;
        }
        if(p == null)
            return;                     // already gone — inert, and pag:exists() is the question
        detach(p);
        owner.menuEntries.remove(p);
        p.relayout();
    }

    /**
     * Take every entry this addon added back out of <b>every</b> menu it added one to (teardown, P2).
     *
     * <p>077.3: one relayout <b>per grid</b>, not one for the sweep. An addon's entries stand in the menu of
     * each character it added them on, and a grid that is not relayouted goes on drawing a button whose
     * pagina has left {@code paginae} — so the grids are collected by identity and each is told once.
     */
    static void teardownEntries(Addon a) {
        if((a == null) || a.menuEntries.isEmpty())
            return;
        List<MenuGrid> grids = new ArrayList<MenuGrid>();
        for(AddonPagina p : new ArrayList<AddonPagina>(a.menuEntries)) {
            detach(p);
            boolean seen = false;
            for(MenuGrid g : grids)
                seen |= (g == p.scm);
            if(!seen)
                grids.add(p.scm);
        }
        a.menuEntries.clear();
        for(MenuGrid g : grids)
            g.change(g.cur);            // one relayout per grid, for the whole sweep
    }

    /**
     * Drop one entry out of the grid it was added to (its own, never the drawn session's), and
     * <b>re-root whatever hung under it</b>: a category that leaves takes no child with it, so the children go
     * back to the root screen rather than under a parent no screen reaches — which would draw the removed
     * category itself back onto the root screen, since {@code cons} walks the closure through {@code parent()}
     * and does not ask whether the parent is still in {@code paginae}. Every custom entry of every addon is in
     * this set, so one pass over it covers a child another addon hung under this category too.
     *
     * <p>The entry's own handlers go with it ({@link Subs#clear}, P2): the button is out of the grid, so nothing
     * can click it again, and a re-{@code :add} of the same id mints a new entry with a {@link Subs} of its own.
     */
    private static void detach(AddonPagina p) {
        p.subs.clear();
        BeltHold.entryRemoved(p);   // 059.4: and give back every bar slot that was held for it
        // audit2 B01: ...and the KEY BINDING the button minted goes with the entry. KeyBinding.get mints into
        // a process-wide map that had no removal at all, so "scm/addon/<addon>/<id>" -- and whatever the
        // player had assigned to it in Options > Keybindings -- outlived :remove, disable and :reload, and
        // then answered a button that is in no grid. One drop, on the one path every removal takes.
        KeyBinding.unregister("scm/" + p.id);
        synchronized(p.scm.paginae) {
            p.scm.paginae.remove(p);
            for(MenuGrid.Pagina q : p.scm.paginae) {
                if((q instanceof AddonPagina) && (((AddonPagina)q).parent == p))
                    ((AddonPagina)q).parent = null;
            }
        }
    }

    /**
     * <b>Drop the entries this addon left in a menu that is gone.</b> An entry goes into the grid that
     * character had <i>then</i>, and the list on the {@link Addon} outlives it: a relog to character selection
     * and back, or a reconnect, tears the HUD down, and the server places a new {@link MenuGrid} in the one it
     * builds next. What stays on the list is an entry nothing draws and nothing can click, standing in a grid
     * no screen reaches — so it is taken out here, and the id it held is free again.
     *
     * <p>Every lookup by {@code (id, user)} passes through this first, which is what keeps the three of them
     * saying one thing: {@code :add} refuses a duplicate only where there is one to collide with, a write verb
     * refuses an entry that has left the menu (as {@code pag:exists()} already reports, since it resolves
     * through the live grid), and {@code :remove} of one is the inert removal of something already gone.
     *
     * <p><b>The placements stand.</b> {@link #detach} gives back every action-bar slot the entry was holding
     * without forgetting where it belongs, so the {@code :add} the addon makes from its next
     * {@code SessionEnteredWorld} puts the button straight back on the bar — and the hold records the dead HUD
     * left behind go with it, which is what stops the next hold on that slot from carrying a displaced
     * content read off a bar nobody is looking at. No relayout: the grid these entries are leaving is the
     * torn-down one, and the live grid never had them.
     *
     * <p>{@code live} is that account's grid <b>now</b>, and {@code null} means the client cannot tell — that
     * character has no HUD up — so nothing is reaped. An entry is orphaned by a menu that was <b>replaced</b>,
     * never by one that is merely not there to ask.
     */
    private static void reap(Addon owner, String user, MenuGrid live) {
        if(live == null)
            return;
        for(AddonPagina p : owner.menuEntries) {   // copy-on-write: removing inside the walk is safe
            if(p.user.equals(user) && (p.scm != live)) {
                detach(p);
                owner.menuEntries.remove(p);
            }
        }
    }

    // ---- the id ----------------------------------------------------------------------------------------

    /**
     * The <b>live</b> entry a write verb is addressing, or a refusal naming <i>whose</i> entry it is. An addon
     * writes the entries it added and nothing else: the client's own are the server's to describe, and another
     * addon's are that addon's.
     *
     * <p>{@code call} is the whole call being refused, not a bare verb name, so an error names the line that
     * wrote it. {@link #inMenu} is the other resolver — for a verb that <i>places</i> an entry rather than
     * writes it, where whose it is does not matter.
     */
    static AddonPagina owned(Addon owner, String user, String res, String call) {
        reap(owner, user, LuaPagina.grid(user));
        String elsewhere = null;
        for(AddonPagina p : owner.menuEntries) {
            if(!p.id.equals(res))
                continue;
            if(p.user.equals(user))
                return p;
            elsewhere = p.user;      // this addon's own entry, standing in ANOTHER character's menu
        }
        String mine = PREFIX + owner.manifest.id + "/";
        if(res.startsWith(mine)) {
            // audit2 B16 (ab-12): the record has the character, so the refusal SAYS whose menu it is in
            // rather than offering the reader three causes to pick between. The walk above already held
            // `p.user` -- one of the three guesses was a fact this loop could see.
            if(elsewhere != null)
                throw new LuaError(call + ": \"" + res + "\" is in " + elsewhere + "'s menu, not " + user
                    + "'s — an entry is added to one character's menu and belongs to that one. Address the"
                    + " session it was added on, or " + CharApi.MG + ":add(id) it on this one too.");
            throw new LuaError(call + ": \"" + res + "\" is in no character's menu — this addon removed it"
                + " again, or added it to a menu a relogin has since replaced (pag:exists() is the test).");
        }
        if(res.startsWith(PREFIX)) {
            throw new LuaError(call + ": \"" + res + "\" belongs to " + other(res) + ", not to"
                + " this addon — an addon writes only the entries it added with " + CharApi.MG + ":add(id)");
        }
        throw new LuaError(call + ": \"" + res + "\" is the client's own entry, not one this addon"
            + " added — the game's catalogue is read-only, and " + CharApi.MG + ":add(id) mints an entry of"
            + " your own that every one of these verbs writes");
    }

    /**
     * The <b>live</b> entry with identity {@code res} in {@code user}'s menu, <b>whoever added it</b> — the
     * resolver for {@code slot:hold(pag)}, which places an entry and writes nothing on it. The bar is one
     * shared surface: {@code GameUI.Belt.dropthing} takes any addon's entry the player drops, and a verb that
     * lets an addon do the same for them takes the same. One of this addon's own identities still goes through
     * {@link #owned}, which is what names the character whose menu it IS in when it is in one;
     * another addon's is looked up in that character's grid as it stands, so an entry that addon has removed,
     * or one it added on another login, is refused as not being in the menu.
     */
    static AddonPagina inMenu(Addon owner, String user, String res, String call) {
        if(res.startsWith(PREFIX + owner.manifest.id + "/"))
            return owned(owner, user, res, call);
        MenuGrid.Pagina p = LuaPagina.live(user, res);
        if(p instanceof AddonPagina)
            return (AddonPagina)p;
        if(res.startsWith(PREFIX))
            throw new LuaError(call + ": \"" + res + "\" is not in that character's menu — " + other(res)
                + " added it and removed it again, added it on another character, or added it to a menu a"
                + " relogin has since replaced (check :exists())");
        throw new LuaError(call + ": \"" + res + "\" is the client's own entry, and a slot is held for an entry"
            + " an addon added (" + CharApi.MG + ":add(id)). To put one of the game's own actions on the bar,"
            + " assign it: slot:res(name).");
    }

    /** The addon id inside a foreign identity, for the refusals above. */
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
        if(x.type() != LuaValue.TSTRING)   // the TYPE: in LuaJ "42" answers isnumber() and 42 isstring()
            throw new LuaError(CharApi.MG + ":remove(idOrPagina): expected the Pagina object :add() handed"
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
        String call = CharApi.MG + ":" + verb + "(id" + (verb.equals("remove") ? "OrPagina" : "") + ")";
        if(key.type() != LuaValue.TSTRING)   // the TYPE: an id that merely scans as a number ("42") is a string
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
