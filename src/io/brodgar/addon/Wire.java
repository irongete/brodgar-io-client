package io.brodgar.addon;

import haven.Coord;
import haven.MCache;
import haven.UI;
import haven.Widget;

import org.luaj.vm2.LuaError;

import java.util.HashMap;
import java.util.Map;

/**
 * <b>The one way this API puts a message on the wire</b> (audit2 B07) — every send an addon makes comes
 * through {@link #send}, and nothing under {@code io.brodgar.addon} calls {@link Widget#wdgmsg} itself.
 *
 * <p><b>Why one door.</b> A send used to be written where the verb was: thirteen files each resolved their
 * own target, each took (or forgot) the tree's monitor, and none of them bounded either the shape of the
 * message or the rate it went out at. So the same three mistakes were available at every one of them —
 * a message addressed through another character's widget, a button or a modifier field the mouse has no
 * way of producing, and a hundred identical writes in the frame a Lua loop runs in. Those are properties of
 * <i>sending</i> rather than of any one verb, so they live here, once, and a verb states only what it
 * sends.
 *
 * <p><b>The four things this door does</b>, in this order:
 *
 * <ol>
 *   <li><b>It resolves the tree of {@code user}</b> — the character the verb was addressed at — and refuses
 *       a session the client no longer holds. Every verb here already names a character, because a message
 *       leaves through one session's socket and no other.</li>
 *   <li><b>It refuses a widget that is not in that tree.</b> {@code Widget.wdgmsg} walks the parent chain
 *       to whichever {@code UI} owns the widget, so a target read out of one character's tree and sent for
 *       another leaves through the wrong session — a mistake with no symptom at the call site.</li>
 *   <li><b>It takes that tree's monitor</b> through {@link LuaWidget#monitorOf}, which is the acquisition
 *       every write in this layer makes, and the one place the client's "never two UI monitors at once"
 *       rule is held to.</li>
 *   <li><b>It checks the SHAPE of the message and the RATE it goes out at</b> — the two halves of
 *       <i>an addon can only send what a player could</i>. {@link #SHAPES} is the shape half, one row per
 *       message; the frame bound below is the rate half.</li>
 * </ol>
 *
 * <p><b>The rate bound: one send per frame, per character, per verb.</b> A Lua loop runs entirely inside
 * one frame, so a hundred turns of it used to be a hundred messages on the wire in the time a player makes
 * one gesture. The second send of a verb in a frame is refused naming {@code once per frame}; the addon
 * drives a run of them from a timer or from the event that follows the last one, which is the rate a hand
 * has. <b>The character is part of the key</b> because this client draws more than one: a player at two
 * characters can walk both in the same frame, and a bound that forgot which one was addressed would refuse
 * the second character's write for the first character's.
 *
 * <p><b>The shape table is DATA</b>, one row per message the API composes. A row is applied to the
 * arguments the API built; where the client's own method builds them ({@code D-009}, wrap-not-reimplement)
 * the caller hands over the values it is about to pass, and where it composes none at all it hands
 * {@code null} and there is nothing here to check. A message with no row passes unchecked, and so does
 * <b>every</b> message of the one verb whose name is the CALLER's — {@link #ESCAPE}, the documented escape
 * hatch: its rows would be about some other widget's message of the same name, and the permission tier is
 * what stands in front of it instead.
 */
final class Wire {
    private Wire() {
    }

    /**
     * <b>Send {@code msg} from {@code w}, as {@code user}, for {@code owner}</b> — the bare
     * {@link Widget#wdgmsg}, gated. {@code verb} is the Lua verb doing the sending
     * ({@code "hafen.world():place"}), and it names both the refusals and the rate bound's key.
     */
    static void send(Addon owner, String user, String verb, Widget w, String msg, Object... args) {
        gate(owner, user, verb, w, msg, args, null);
    }

    /**
     * <b>The same door, for a send the CLIENT'S OWN method makes</b> ({@code D-009}, wrap-not-reimplement)
     * — {@code Buddy.chname}, {@code EntryChannel.send}, {@code Speedget.set}, {@code FlowerMenu.choose},
     * {@code PagButton.use}. Those compose the message themselves, which is exactly why they are called
     * rather than re-encoded, so {@code args} is what the caller is about to hand them (or {@code null}
     * where it hands them nothing) and {@code dispatch} is the call itself, made under the tree's monitor.
     */
    static void send(Addon owner, String user, String verb, Widget w, String msg, Object[] args,
                     Runnable dispatch) {
        if(dispatch == null)
            throw new IllegalArgumentException("dispatch");
        gate(owner, user, verb, w, msg, args, dispatch);
    }

    /** The whole gate. {@code dispatch == null} is the bare {@code w.wdgmsg(msg, args)}. */
    private static void gate(Addon owner, String user, String verb, Widget w, String msg, Object[] args,
                             Runnable dispatch) {
        UI u = AddonManager.sessionui(user);
        if(u == null)
            throw new LuaError(verb + ": that session is gone (s:exists() is false). Nothing was sent.");
        if((w != null) && (w.ui != u))
            throw new LuaError(verb + ": that widget is in another character's tree — a message leaves"
                + " through whichever session owns the widget it is sent from, so this one would reach the"
                + " wrong server. Read the target out of the session you addressed (s:ui()). Nothing was"
                + " sent.");
        synchronized(LuaWidget.monitorOf(u)) {
            if((args != null) && !ESCAPE.equals(verb)) {
                Shape s = SHAPES.get(msg);
                if(s != null)
                    s.check(verb, args);
            }
            bound(owner, user, verb);
            if(dispatch != null)
                dispatch.run();
            else
                w.wdgmsg(msg, args);
        }
    }

    /**
     * The rate half: refuse a second send of {@code verb} for {@code user} in the frame the first went out
     * in. The frame is {@link AddonManager#frame}, which the layer's own step advances — the same beat the
     * engine clock and every {@code Update} run on.
     */
    private static void bound(Addon owner, String user, String verb) {
        String key = verb + "@" + user;   // a verb carries no "@", so the pair is unambiguous
        long now = AddonManager.frame;
        Long last = owner.lastSend.get(key);
        if((last != null) && (last.longValue() == now))
            throw new LuaError(verb + ": once per frame — this addon has already sent through this verb on"
                + " that character this frame. A write goes out at the rate a hand makes one, so a run of"
                + " them is driven from a timer (hafen.timer():every(0.2, fn)) or from the event that"
                + " follows the last one, never from a loop. Nothing was sent.");
        owner.lastSend.put(key, Long.valueOf(now));
    }

    // ---- the shape table -----------------------------------------------------------------------------
    // One row per message the API composes. Each row is the answer to one question: what could the
    // player's own mouse, entry line or kin window have produced here? Nothing states an upper bound the
    // server enforces -- the server's rules are the server's, and it has the last word on every one of
    // these -- so a row bounds what the CLIENT is able to compose, which is what the block's invariant
    // ("an addon can only send what a player could") actually says.

    /**
     * <b>The escape hatch</b> — {@code widget:send(msg, ...)}, the one verb whose message name and arguments
     * are the caller's rather than this API's. A row is keyed by the message NAME, and a name is not unique
     * across the tree: a button's {@code "click"} carries nothing at all where the map view's carries a
     * button and a modifier field, so a row applied here would refuse a send that is perfectly well formed.
     * It goes through everything else this door does and past the table.
     */
    private static final String ESCAPE = "widget:send";

    /** What a row does: raise naming {@code verb} when {@code args} is a shape no player composes. */
    private interface Shape {
        void check(String verb, Object[] args);
    }

    /** The widest whole line a chat entry composes — no line a player types comes near it. */
    private static final int TEXT = 512;
    /** The widest nickname the kin window's own field composes. */
    private static final int NAME = 64;
    /** The widest hearth secret the "Add kin" field composes. */
    private static final int SECRET = 128;
    /** The highest group the server accepts, which is {@code LuaKin}'s bound too. */
    private static final int GROUP = 254;
    /** How many slots the action bar has, which is {@code LuaSlot.SLOTS}. */
    private static final int BELT = 144;
    /** How many speeds the selector has. */
    private static final int SPEEDS = 4;

    private static final Map<String, Shape> SHAPES = new HashMap<String, Shape>();

    static {
        // MapView "place" {rc, angle, button, mods} — a placement is a click, so its button and modifiers
        // are a mouse's.
        SHAPES.put("place", new Shape() {
            public void check(String verb, Object[] a) {
                button(verb, a, 2);
                mods(verb, a, 3);
            }
        });
        // MapView "click" {pc, mc, button, mods, …} — the same pair, two slots along. AddonManager.order
        // composes its own and hands null, so a walk reaches this row with nothing to check.
        SHAPES.put("click", new Shape() {
            public void check(String verb, Object[] a) {
                button(verb, a, 2);
                mods(verb, a, 3);
            }
        });
        // MapView "sel" {tc1, tc2, mods} — a drag, and a drag happens on ONE SCREEN. The corners are tile
        // coords, so the extent is bounded by the client's own grid (MCache.cmaps, 100 tiles square),
        // which is comfortably more than a screen holds at any zoom and far less than everything explored.
        SHAPES.put("sel", new Shape() {
            public void check(String verb, Object[] a) {
                mods(verb, a, 2);
                Coord c1 = coord(a, 0), c2 = coord(a, 1);
                if((c1 == null) || (c2 == null))
                    return;
                int w = Math.abs(c2.x - c1.x) + 1, h = Math.abs(c2.y - c1.y) + 1;
                if((w > MCache.cmaps.x) || (h > MCache.cmaps.y))
                    throw new LuaError(verb + ": the area is " + w + "x" + h + " tiles, and a drag spans at"
                        + " most " + MCache.cmaps.x + "x" + MCache.cmaps.y + " — one screenful, which is all"
                        + " a player can select in one gesture. Select it in strips. Nothing was sent.");
            }
        });
        // GItem "itemact" {mods} and MapView "itemact" {pc, mc, mods, …} — one message, two arities, told
        // apart by where the modifier field sits.
        SHAPES.put("itemact", new Shape() {
            public void check(String verb, Object[] a) {
                mods(verb, a, (a.length == 1) ? 0 : 2);
            }
        });
        // GItem "iact" {grab, mods}.
        SHAPES.put("iact", new Shape() {
            public void check(String verb, Object[] a) {
                mods(verb, a, 1);
            }
        });
        // GItem "drop"/"transfer" {grab, n} — n is a count out of the stack, or -1 for all of it.
        Shape count = new Shape() {
            public void check(String verb, Object[] a) {
                int n = integer(a, 1);
                if(n < -1)
                    throw new LuaError(verb + ": n must be -1 (the whole stack) or a count of 0 or more,"
                        + " got " + n + ". Nothing was sent.");
            }
        };
        SHAPES.put("drop", count);
        SHAPES.put("transfer", count);
        // GameUI "setbelt" {n, …} and "belt" {n, button, mods} — a slot the bar actually has.
        SHAPES.put("setbelt", new Shape() {
            public void check(String verb, Object[] a) {
                slot(verb, a);
            }
        });
        SHAPES.put("belt", new Shape() {
            public void check(String verb, Object[] a) {
                slot(verb, a);
                button(verb, a, 1);
                mods(verb, a, 2);
            }
        });
        // ChatUI.EntryChannel "msg" {text} — what the entry line composes is ONE line of typed characters,
        // so a control character is a shape no keyboard puts there and a novel is not a chat line.
        SHAPES.put("msg", new Shape() {
            public void check(String verb, Object[] a) {
                line(verb, "text", string(a, 0), TEXT);
            }
        });
        // BuddyWnd "nick" {id, name} — the kin window's own rename field, which composes one line too, and
        // refuses an empty one exactly as the Add kin field does.
        SHAPES.put("nick", new Shape() {
            public void check(String verb, Object[] a) {
                line(verb, "name", string(a, 1), NAME);
            }
        });
        // BuddyWnd "grp" {id, group} — the range the server accepts, not the eight colours it draws.
        SHAPES.put("grp", new Shape() {
            public void check(String verb, Object[] a) {
                int g = integer(a, 1);
                if((g < 0) || (g > GROUP))
                    throw new LuaError(verb + ": group must be 0.." + GROUP + ", got " + g
                        + ". Nothing was sent.");
            }
        });
        // BuddyWnd "rm" {id} — the two kin endings send it; a buddy id is a whole number the roster holds.
        SHAPES.put("rm", new Shape() {
            public void check(String verb, Object[] a) {
                int id = integer(a, 0);
                if(id < 0)
                    throw new LuaError(verb + ": that kin has no id on this roster. Nothing was sent.");
            }
        });
        // BuddyWnd "bypwd" {secret} — the Add kin field, one typed line.
        SHAPES.put("bypwd", new Shape() {
            public void check(String verb, Object[] a) {
                line(verb, "secret", string(a, 0), SECRET);
            }
        });
        // Makewindow "make" {0|1} — Craft, or Craft All, and there is no third button.
        SHAPES.put("make", new Shape() {
            public void check(String verb, Object[] a) {
                int n = integer(a, 0);
                if((n != 0) && (n != 1))
                    throw new LuaError(verb + ": the button is 0 (Craft) or 1 (Craft All), got " + n
                        + ". Nothing was sent.");
            }
        });
        // Speedget "set" {n} — one of the four the selector has.
        SHAPES.put("set", new Shape() {
            public void check(String verb, Object[] a) {
                int n = integer(a, 0);
                if((n < 0) || (n >= SPEEDS))
                    throw new LuaError(verb + ": the speed is one of the four the selector has, got " + n
                        + ". Nothing was sent.");
            }
        });
    }

    // ---- the row's own vocabulary --------------------------------------------------------------------

    /** A mouse has three buttons, and the message carries which one at {@code i}. */
    private static void button(String verb, Object[] a, int i) {
        int b = integer(a, i);
        if((b < 1) || (b > 3))
            throw new LuaError(verb + ": button must be 1, 2 or 3 — the buttons a mouse has, 1 being left"
                + " and 3 right. Got " + b + ". Nothing was sent.");
    }

    /** The modifier bitfield is the three keys the client itself names: Shift=1, Ctrl=2, Alt=4. */
    private static void mods(String verb, Object[] a, int i) {
        int m = integer(a, i);
        if((m & ~7) != 0)
            throw new LuaError(verb + ": mods is a bitfield of Shift=1, Ctrl=2 and Alt=4, so 0..7 — those"
                + " are the three keys the client sends. Got " + m + ". Nothing was sent.");
    }

    /** The action bar has a fixed number of slots, and the wire number at {@code a[0]} names one of them. */
    private static void slot(String verb, Object[] a) {
        int n = integer(a, 0);
        if((n < 0) || (n >= BELT))
            throw new LuaError(verb + ": the action bar has " + BELT + " slots, and slot:index() addresses"
                + " them 1.." + BELT + ". Got the wire slot " + n + ". Nothing was sent.");
    }

    /**
     * One typed line: not empty, no longer than {@code max}, and nothing in it the keyboard cannot put
     * there. A newline is the shape that matters — the client's own entry line commits on Enter, so a
     * string carrying one is two lines the player had no way of composing as one.
     */
    private static void line(String verb, String param, String s, int max) {
        if(s == null)
            return;
        if(s.isEmpty())
            throw new LuaError(verb + ": " + param + " is empty — there is nothing to say, and the client's"
                + " own entry line refuses one too. Nothing was sent.");
        if(s.length() > max)
            throw new LuaError(verb + ": " + param + " is " + s.length() + " characters, and one typed line"
                + " is at most " + max + ". Nothing was sent.");
        for(int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if((c < ' ') || (c == 0x7f))
                throw new LuaError(verb + ": " + param + " carries a control character (a newline, a tab)"
                    + " at position " + (i + 1) + " — an entry line composes ONE line of typed characters."
                    + " Nothing was sent.");
        }
    }

    /** The whole number at {@code i}, or {@code 0} where the slot holds something else (nothing to bound). */
    private static int integer(Object[] a, int i) {
        Object o = ((a == null) || (i < 0) || (i >= a.length)) ? null : a[i];
        return (o instanceof Number) ? ((Number)o).intValue() : 0;
    }

    /** The string at {@code i}, or {@code null} where the slot holds something else. */
    private static String string(Object[] a, int i) {
        Object o = ((a == null) || (i < 0) || (i >= a.length)) ? null : a[i];
        return (o instanceof String) ? (String)o : null;
    }

    /** The coord at {@code i}, or {@code null} where the slot holds something else. */
    private static Coord coord(Object[] a, int i) {
        Object o = ((a == null) || (i < 0) || (i >= a.length)) ? null : a[i];
        return (o instanceof Coord) ? (Coord)o : null;
    }
}
