package io.brodgar.addon;

import haven.BuddyWnd;
import haven.GobIcon;
import haven.MapWnd;
import haven.Polity;
import haven.SListWidget;

import java.util.List;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * One <b>row of a client list</b> (164.1) — what {@code widget:rows()}, {@code widget:row()},
 * {@code widget:value()} on a client list and a list's {@code Changed}/{@code Selected} {@code event:value()} hand
 * out, and what {@code widget:value(row)} takes back.
 *
 * <p><b>The list's own row object is the userdata's instance</b>, under the owner's {@link Addon#rowMeta}.
 * {@code widget:value(row)} gives {@code touserdata()} straight to the list ({@link Controls#drive} →
 * {@code AddonWidgets.listHas}/{@code listChange}), which looks for its own row there, so the object cannot be
 * wrapped. LuaJ compares two userdata by instance and metatable, so {@code ==} and a table key hold with no
 * intern cache. A receiver is a Row exactly when its metatable is this addon's.
 *
 * <p><b>The readers answer from the object</b>, never from a row widget: a row scrolled off has none.
 * {@link #text} is what each of the client's four search lists matches in its own {@code searchmatch}, so a
 * {@code Search} handler reads the very string the client searched. A plain row — a {@code String}, a number —
 * crosses as itself, as it always has.
 */
final class LuaRow {
    /** Guards the two metatables 164.1 adds ({@link Addon#rowMeta}, {@link Addon#searchEventMeta}). */
    static final Object META_LOCK = new Object();

    private LuaRow() {
    }

    /** The Lua value for one row of a client list: {@code nil}, a plain value as itself, or a Row. */
    static LuaValue of(Addon owner, Object row) {
        if(row == null)
            return LuaValue.NIL;
        if((row instanceof String) || (row instanceof Number) || (row instanceof Boolean))
            return LuaMarshal.toLua(row);
        return LuaValue.userdataOf(row, meta(owner));
    }

    /**
     * Every row {@code list} holds, as a 1-based array, or {@code nil} while its model cannot answer. Copied
     * under the tree's monitor: {@code Polity.MemberList.tick} refills its list in place.
     */
    static LuaValue all(Addon owner, SListWidget<?, ?> list) {
        List<Object> rows;
        synchronized(LuaWidget.monitor(list)) {
            rows = haven.AddonWidgets.listRows(list);
        }
        if(rows == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        int i = 1;
        for(Object row : rows) {
            if(row != null)
                t.set(i++, of(owner, row));
        }
        return t;
    }

    /**
     * The text a row shows, and the one its list searches by: the field each {@code SSearchBox}'s own
     * {@code searchmatch} reads, one arm per row class the client has a search list of. {@code null} for a row
     * of any other class, and where the read fails — a {@code Polity.Member}'s name is a roster lookup that
     * climbs to the HUD, which a detached panel no longer reaches.
     */
    static String text(Object row) {
        try {
            if(row instanceof BuddyWnd.Buddy) {
                BuddyWnd.Buddy buddy = (BuddyWnd.Buddy)row;
                synchronized(buddy) {                  // the monitor BuddyWnd publishes an "upd" under
                    return buddy.name;
                }
            }
            if(row instanceof Polity.Member)
                return ((Polity.Member)row).name();
            if(row instanceof MapWnd.ListMarker)
                return ((MapWnd.ListMarker)row).mark.nm;
            if(row instanceof GobIcon.SettingsWindow.ListIcon)
                return ((GobIcon.SettingsWindow.ListIcon)row).name;
        } catch(RuntimeException e) {
            return null;
        }
        return null;
    }

    /**
     * The group a row is drawn in, for the row classes that carry one. {@code nil} for every other row, and for
     * a negative group (a polity with no groups gives its members {@code -1}). {@code widget:group()} on the
     * row's widget reads through here.
     */
    static LuaValue group(Object row) {
        int group;
        if(row instanceof BuddyWnd.Buddy) {
            BuddyWnd.Buddy buddy = (BuddyWnd.Buddy)row;
            synchronized(buddy) {
                group = buddy.group;
            }
        } else if(row instanceof Polity.Member) {
            group = ((Polity.Member)row).group;
        } else {
            return LuaValue.NIL;
        }
        return (group < 0) ? LuaValue.NIL : LuaValue.valueOf(group);
    }

    // ---- the per-addon metatable -----------------------------------------------------------------

    static LuaValue meta(final Addon owner) {
        synchronized(META_LOCK) {
            if(owner.rowMeta != null)
                return owner.rowMeta;
            LuaTable mt = new LuaTable();
            mt.set(LuaValue.INDEX, Refusal.closedIndex("row", methods(owner),
                "one row of a client list",
                "widget:value(row) on its own list picks it"));
            mt.set("__name", LuaValue.valueOf("Row"));
            mt.set("__tostring", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    String text = (self.isuserdata() && (self.getmetatable() == owner.rowMeta))
                        ? text(self.touserdata()) : null;
                    return LuaValue.valueOf((text == null) ? "Row" : ("Row(" + text + ")"));
                }
            });
            owner.rowMeta = mt;
            return mt;
        }
    }

    /** The row object behind {@code self}, or the refusal a dotted call or a stranger value earns. */
    private static Object row(Addon owner, LuaValue self, String verb) {
        if(self.isuserdata() && (self.getmetatable() == owner.rowMeta))
            return self.touserdata();
        throw new LuaError("row:" + verb + "() — use a COLON call on a Row: widget:rows()[n], widget:value() on a"
            + " client list, or widget:row() on one of its row widgets");
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        m.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String text = text(row(owner, Args.only(a, 0, "row:text"), "text"));
                return (text == null) ? LuaValue.NIL : LuaValue.valueOf(text);
            }
        });
        m.set("group", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return group(row(owner, Args.only(a, 0, "row:group"), "group"));
            }
        });
        // info() — the one SNAPSHOT escape hatch: {text, group}, an absent value an unset key.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Object row = row(owner, Args.only(a, 0, "row:info"), "info");
                LuaTable t = new LuaTable();
                String text = text(row);
                if(text != null)
                    t.set("text", LuaValue.valueOf(text));
                t.set("group", group(row));
                return t;
            }
        });
        return m;
    }
}
