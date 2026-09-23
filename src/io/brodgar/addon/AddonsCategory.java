package io.brodgar.addon;

import haven.GSprite;
import haven.ItemInfo;
import haven.KeyBinding;
import haven.KeyMatch;
import haven.MenuGrid;
import haven.Tex;
import haven.TexI;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * <b>The AddOns category</b> — the client's own entry on the action menu's root screen, and the one place every
 * entry an addon adds hangs inside (spec {@code 162-menugrid-addons-category}). A second {@link MenuGrid.Pagina}
 * subclass beside {@link AddonPagina}, over the same seam, so {@link MenuGrid} is not edited.
 *
 * <p><b>It is never put in {@code paginae}.</b> {@code MenuGrid.cons} reaches a category through
 * {@code parent()} alone, exactly as it reaches the game's own, so AddOns is drawn on the root screen while some
 * addon entry answers it as its parent and vanishes with the last one — a parent the tree reaches, never a
 * member of the granted set. {@link AddonPagina#parent()} answers it for an entry whose own field is
 * {@code null}, which is why that field keeps meaning "the top of AddOns".
 *
 * <p><b>One per grid.</b> {@code cons} and {@link LuaPagina} compare a parent by identity, so every entry of one
 * menu must answer the very same object. The map holds it <b>weakly</b>: the category holds its grid
 * ({@code Pagina.scm}), so a strong value would keep every relogged-away {@code MenuGrid} alive through the
 * key it names. What keeps it alive is each {@link AddonPagina} of that grid, which holds it from the moment it
 * is minted; a grid with no entry left may lose it and mint a fresh one the next time an entry is added.
 *
 * <p><b>Nothing here reaches the server.</b> Its button sends nothing on a click (the grid opens a category
 * through {@code MenuGrid.use}, which never calls {@code PagButton.use} for one that has children), and
 * {@link BeltHold#dropped} swallows it on the action bar.
 */
final class AddonsCategory extends MenuGrid.Pagina {
    /** The identity: the namespace every addon identity {@code addon/<id>/<rel>} already lives under. */
    static final String ID = "addon/";
    /** The label the grid paints, and its sort key. A literal, as the grid's own {@code Back} is. */
    static final String NAME = "AddOns";
    /** The line under the name in the long tooltip, and what {@code pag:tooltip()} answers. */
    static final String TIP = "What your addons add to the menu";

    private static final Map<MenuGrid, WeakReference<AddonsCategory>> byGrid =
        new WeakHashMap<MenuGrid, WeakReference<AddonsCategory>>();

    /** That grid's AddOns, minted on first use. */
    static synchronized AddonsCategory of(MenuGrid scm) {
        WeakReference<AddonsCategory> r = byGrid.get(scm);
        AddonsCategory c = (r == null) ? null : r.get();
        if(c == null)
            byGrid.put(scm, new WeakReference<AddonsCategory>(c = new AddonsCategory(scm)));
        return c;
    }

    private static Tex dolmen;
    private static boolean dolmenRead;

    /**
     * The blue dolmen, read once from the {@code icon.png} {@code build.xml} copies beside {@code haven.Client}
     * — the file the window icon is read from. Sampled LINEAR, since the cell draws it scaled down. {@code null}
     * when the file is not there, which draws an empty cell rather than failing the draw thread.
     */
    static synchronized Tex dolmen() {
        if(!dolmenRead) {
            dolmenRead = true;
            try(InputStream in = haven.Client.class.getResourceAsStream("icon.png")) {
                BufferedImage img = (in == null) ? null : ImageIO.read(in);
                if(img != null)
                    dolmen = LuaGOut.smooth(new TexI(img));
            } catch(IOException e) {
                dolmen = null;
            }
        }
        return dolmen;
    }

    private Button button;

    private AddonsCategory(MenuGrid scm) {
        super(scm, ID, AddonPagina.standin());
    }

    /** The drawn button, minted once — {@code Pagina.button} is private, so the subclass caches its own. */
    public MenuGrid.PagButton button() {
        if(button == null)
            button = new Button(this);
        return button;
    }

    /** The root screen: AddOns hangs under nothing. */
    public MenuGrid.Pagina parent() {
        return null;
    }

    public String toString() {
        return "AddonsCategory(" + ID + ")";
    }

    /**
     * The drawn half. Like {@link AddonPagina.AddonPagButton}, every override reads {@code pag} or a constant:
     * {@code PagButton(Pagina)} calls the virtual {@code binding()} before a subclass field exists, and the
     * stock one would throw on the stand-in, which has no {@code action} layer.
     */
    static final class Button extends MenuGrid.PagButton {
        private GSprite spr;

        Button(AddonsCategory pag) {
            super(pag);
        }

        public String name() {
            return NAME;
        }

        public String sortkey() {
            return NAME;
        }

        public MenuGrid.Pagina parent() {
            return null;
        }

        /** An unbound, remappable binding of its own, {@code scm/addon/}: the shape every entry mints. */
        public KeyBinding binding() {
            return KeyBinding.get(AddonPagina.bindId(ID), KeyMatch.nil);
        }

        public KeyMatch hotkey() {
            return KeyMatch.nil;
        }

        public GSprite spr() {
            if(spr == null)
                spr = new AddonPagina.Icon(this, dolmen());
            return spr;
        }

        /** Nothing: a category is opened by the grid, and the server has never heard of this one. */
        public void use(MenuGrid.Interaction iact) {
        }

        /** A fresh list every call: {@code rendertt} deletes from the one it is handed. */
        public List<ItemInfo> info() {
            List<ItemInfo> out = new ArrayList<ItemInfo>();
            out.add(new ItemInfo.Pagina(this, TIP));
            return out;
        }
    }
}
