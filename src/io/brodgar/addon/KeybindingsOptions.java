package io.brodgar.addon;

import haven.KeyBinding;
import haven.KeyMatch;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * Keybindings options subsystem (spec 018-client-options, task 018.1) — the unified hotkey surface under
 * {@code hafen.client:options():keybindings()}. Registration goes through {@link HookApi}, which owns the
 * dispatch path ({@link AddonRoot#globtype}), the per-addon teardown ({@link Addon#keybinds}) and the client
 * keybind panel's data.
 *
 * <p><b>Addon hotkeys start unbound.</b> {@code on} takes no default key: an addon names an action and
 * the user assigns the key in the client's keybind panel, matching WoW and the one-key-one-action exclusivity
 * {@link KeyBinding#set} enforces. A default would not be able to steal a key in any case — registration goes
 * through {@link KeyBinding#get}, which never runs that unbind pass — so a clash would just mean the client's
 * binding matches first ({@link AddonRoot} is walked last) and the addon's hotkey silently never fires.
 *
 * <p><b>Name resolution.</b> An addon's own hotkeys live under {@code addon/<addonid>/<name>}, so
 * {@code on("test", ...)} then {@code key("test")} refer to the same binding without the addon ever
 * spelling its own id. A name that has no addon-scoped binding falls back to the raw registry id, which is how
 * a built-in client hotkey is reached ({@code key("inv")}, {@code key("inv", "Ctrl+I")}). The addon scope is
 * tried first so an addon can never be shadowed by a client binding that happens to share its name.
 */
public final class KeybindingsOptions {
    private KeybindingsOptions() {}

    /** Create the keybindings subsystem handle for {@code owner}. */
    public static LuaValue create(final Addon owner) {
        LuaTable kb = new LuaTable();
        LuaTable mt = new LuaTable();
        // Retired.closedIndex, not the methods table itself: kb:get / kb:set would otherwise read as plain nil
        // and fail one call later as "attempt to call a nil value", saying nothing about what replaced them.
        mt.set(LuaValue.INDEX, Retired.closedIndex("keybindings", methods(owner, kb),
            "the keybindings handle answers :on() :key() and :list()"));
        kb.setmetatable(mt);
        return kb;
    }

    /** This addon's namespaced registry id for {@code name}. */
    private static String scoped(Addon owner, String name) {
        return "addon/" + owner.manifest.id + "/" + name;
    }

    /** The binding {@code name} refers to: this addon's own first, else the raw registry id; null if neither. */
    private static KeyBinding resolve(Addon owner, String name) {
        KeyBinding own = KeyBinding.get(scoped(owner, name));
        return (own != null) ? own : KeyBinding.get(name);
    }

    private static LuaTable methods(final Addon owner, final LuaValue handle) {
        LuaTable m = new LuaTable();

        // on(name, fn) — declare a hotkey owned by this addon. It starts UNBOUND: an addon names an
        // action, the user chooses the key (Options > Keybindings), which is the WoW model and the only one
        // consistent with KeyBinding's one-key-one-action exclusivity. An addon-chosen default could not
        // steal a key anyway (KeyBinding.get never runs set()'s unbind pass) — it would simply lose the
        // collision, since the addon root is walked last, leaving a dead hotkey with nothing to explain it.
        // It is a SUBSCRIPTION like every other :on in the API (086.1): the Sub it hands back answers
        // :key() (the name it was registered under) and :off(), which is what unregister used to be. The
        // KeyBinding registry entry survives that ending, so the user's remap outlives a :reload.
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                // a.arg(1) = self (colon call); the arguments start at 2. One argument at a time, through the
                // house helpers: "expects (string, function)" named neither the missing one nor the wrong one.
                LuaValue name = Args.str(a, 2, "keybindings:on", "name",
                    "a hotkey starts unbound; the user assigns the key in Options > Keybindings");
                LuaValue fn = Args.required(a, 3, "keybindings:on", "fn");
                if(!fn.isfunction())
                    throw new LuaError("keybindings:on: fn must be a function — it runs when the user"
                        + " presses the key they bound, got " + fn.typename());
                LuaSub sub = owner.keySubs.add(name.tojstring(), fn);
                sub.tag = HookApi.newKeyBind(owner, name.tojstring(), fn);
                return sub.handle();
            }
        });

        // key(name) / key(name, key) — read a binding's current key as a display string ("Ctrl+M"), or remap it
        // exactly as the keybind panel does (persisted; "None" unbinds). This was the API's LAST get/set pair
        // and it collapses onto one name like every other property: arity is the verb, and the first argument
        // is the ADDRESS of the binding rather than a value being written. A read misses to nil (an unknown or
        // unbound name is a real answer); a WRITE to an unknown name throws, because there is nothing to remap.
        m.set("key", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue name = Args.str(a, 2, "keybindings:key", "name", "the binding's own name");
                LuaValue key = Args.written(a, 3, "keybindings:key", "key");
                KeyBinding b = resolve(owner, name.tojstring());
                if(key == null) {                          // the read arity
                    if(b == null)
                        return LuaValue.NIL;
                    KeyMatch km = b.key();
                    return ((km == null) || (km == KeyMatch.nil)) ? LuaValue.NIL : LuaValue.valueOf(km.name());
                }
                Args.str(key, "keybindings:key", "key", "\"F5\", \"Ctrl+M\", \"None\" to unbind");
                if(b == null)
                    throw new LuaError("keybindings:key: no binding named '" + name.tojstring() + "'");
                KeyMatch km = HookApi.parseKeyMatch(key.tojstring());
                if(km == null)
                    throw new LuaError("keybindings:key: cannot parse key '" + key.tojstring()
                                       + "' (examples: \"F5\", \"Ctrl+M\", \"Shift+Alt+Left\", \"None\")");
                b.set(km);
                return handle;
            }
        });

        // list() — { [id] = key } over the whole registry: this addon's hotkeys, other addons', and the
        // client's own. Unbound entries read "None" (KeyMatch.nil.name()) rather than being omitted, so the
        // table doubles as the set of bindable actions.
        m.set("list", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaTable t = new LuaTable();
                for(KeyBinding b : KeyBinding.all()) {
                    KeyMatch km = b.key();
                    t.set(b.id, LuaValue.valueOf((km == null) ? KeyMatch.nil.name() : km.name()));
                }
                return t;
            }
        });
        return m;
    }
}
