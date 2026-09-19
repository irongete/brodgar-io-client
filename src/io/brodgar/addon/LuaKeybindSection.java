package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>Section object</b> — one of the client's own sections of Options ▸ Game ▸ Keybindings ("Main menu",
 * "Action bar", …), reached through {@code hafen.client():options():keybindings():section(id)}. The one
 * thing an addon does with it is take the section off the panel: an addon that stands in for a piece of the
 * client — its own action bars with their own hotkeys — otherwise leaves the user two sections that look
 * like the same twelve keys, and the client's is the one they find first.
 *
 * <p><b>Hiding is a hold, not a setting.</b> {@code section:visible(false)} records this addon against the
 * section in {@link HookApi#hiddenKeybindSections}; the panel, rebuilt on every press of its button, paints a
 * section no live addon holds. Nothing persists: the hold goes with the addon's other hotkey state in
 * {@link HookApi#teardownKeyBinds} (disable, {@code :reload}, the watchdog), so a section can never be lost
 * to an addon that is no longer there to explain it. The bindings themselves are untouched — the
 * {@link haven.KeyBinding}s of a hidden section keep their keys and keep firing; only the rows are gone.
 *
 * <p>Unprotected, like every other write here that reaches no preference store: it changes what this
 * client paints in one panel for as long as the addon runs, which is what a widget of the addon's own does.
 *
 * <p>Interned per addon by id, the seven ids being the whole population — {@code keybindings:section("actionbar")}
 * is one object every call, so {@code ==} is the identity test the conventions promise.
 */
public final class LuaKeybindSection {
    /** How the accessor is spelled in Lua, for its own messages and the object's. */
    static final String CALL = "hafen.client():options():keybindings():section(id)";

    /**
     * The section ids, in the order the panel paints them. {@code haven.OptWnd.BindingPanel} asks
     * {@link AddonManager#keybindSectionHidden} by these same strings, so a rename here is a rename there.
     */
    public static final List<String> IDS = Arrays.asList(
        "menu", "map", "camera", "mapwnd", "speed", "actionbar", "combat");

    /** The addon this handle belongs to — whose hold {@code visible(flag)} takes and releases. */
    final Addon owner;
    /** The section id — the whole state of a handle. */
    public final String id;

    private LuaKeybindSection(Addon owner, String id) {
        this.owner = owner;
        this.id = id;
    }

    /** {@code tostring(section)}: {@code Section(actionbar)}. */
    public String toString() {
        return "Section(" + id + ")";
    }

    /** The {@code LuaKeybindSection} behind a Lua value, or {@code null} for anything else. */
    static LuaKeybindSection resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaKeybindSection) ? (LuaKeybindSection)o : null;
    }

    /** The ids as a refusal lists them: {@code "menu", "map", …}. */
    static String idList() {
        StringBuilder sb = new StringBuilder();
        for(String id : IDS)
            sb.append(sb.length() == 0 ? "" : ", ").append('"').append(id).append('"');
        return sb.toString();
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Section handles and their metatable, keyed by id. Seven ids and no more, so the map is
     * the population rather than a weak cache; per addon so no Lua value crosses a sandbox boundary, and
     * gone with the {@link Addon} on {@code :reload}/disable like the handle that owns it.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, LuaValue> live = new HashMap<String, LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The Section object for {@code id}, which the caller has already checked against {@link #IDS}. */
        synchronized LuaValue of(String id) {
            LuaValue v = live.get(id);
            if(v == null) {
                if(mt == null)
                    mt = buildMeta(owner);
                v = LuaValue.userdataOf(new LuaKeybindSection(owner, id), mt);
                live.put(id, v);
            }
            return v;
        }
    }

    /**
     * The body of {@code keybindings:section(id)}: the interned handle for a known id, a refusal naming the
     * ids for anything else. A section is addressed by name rather than reached through a collection
     * because the population is seven fixed rows of one panel — there is nothing to list, count or filter
     * that the refusal does not already say.
     */
    static LuaValue address(Cache cache, Varargs a) {
        Args.only(a, 1, "keybindings:section");
        LuaValue key = Args.str(a, 2, "keybindings:section", "id",
            "one of the client's own sections of Options > Game > Keybindings: " + idList());
        String id = key.tojstring();
        if(!IDS.contains(id))
            throw new LuaError("keybindings:section(id): there is no section '" + id + "' — the client's"
                + " sections of Options > Game > Keybindings are " + idList());
        return cache.of(id);
    }

    // ---- the Section metatable -----------------------------------------------------------------------

    private static LuaValue buildMeta(Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("section", methods(owner),
            "one of the client's own sections of Options > Game > Keybindings"));
        mt.set("__name", LuaValue.valueOf("Section"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaKeybindSection h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Section(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // id() — the section id, its identity: what section(id) was handed.
        m.set("id", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "section:id");
                return LuaValue.valueOf(handle(self, "id").id);
            }
        });
        // visible() / visible(flag) — arity is the verb, and a boolean is a bare adjective. The read is the
        // PANEL's answer, whether it paints the section, which is false while ANY live addon holds it; the
        // write takes and releases THIS addon's hold. So visible(true) under another addon's hold changes
        // nothing and still reads false: a hold is released by whoever took it, and by their teardown.
        m.set("visible", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 1, "section:visible");
                LuaKeybindSection h = handle(self, "visible");
                LuaValue flag = Args.written(a, 2, "section:visible", "flag");
                if(flag == null)
                    return LuaValue.valueOf(!HookApi.keybindSectionHidden(h.id));
                boolean visible = Args.bool(flag, "section:visible", "flag",
                    "false takes the section off the panel, true paints it again");
                HookApi.hideKeybindSection(h.owner, h.id, !visible);
                return self;
            }
        });
        // info() — the one snapshot: the id and the panel's answer.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, "section:info");
                LuaKeybindSection h = handle(self, "info");
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf(h.id));
                t.set("visible", LuaValue.valueOf(!HookApi.keybindSectionHidden(h.id)));
                return t;
            }
        });
        return m;
    }

    private static LuaKeybindSection handle(LuaValue self, String method) {
        LuaKeybindSection h = resolve(self);
        if(h == null)
            throw new LuaError("section:" + method + "() — use a COLON call on a Section object ("
                + CALL + ")");
        return h;
    }
}
