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
 * <p><b>Addon hotkeys start unbound.</b> {@code register} takes no default key: an addon names an action and
 * the user assigns the key in the client's keybind panel, matching WoW and the one-key-one-action exclusivity
 * {@link KeyBinding#set} enforces. A default would not be able to steal a key in any case — registration goes
 * through {@link KeyBinding#get}, which never runs that unbind pass — so a clash would just mean the client's
 * binding matches first ({@link AddonRoot} is walked last) and the addon's hotkey silently never fires.
 *
 * <p><b>Name resolution.</b> An addon's own hotkeys live under {@code addon/<addonid>/<name>}, so
 * {@code register("test", ...)} then {@code key("test")} refer to the same binding without the addon ever
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
        // Retired.methodIndex, not the methods table itself: kb:get / kb:set would otherwise read as plain nil
        // and fail one call later as "attempt to call a nil value", saying nothing about what replaced them.
        mt.set(LuaValue.INDEX, Retired.methodIndex("keybindings", methods(owner, kb)));
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

        // register(name, fn) — declare a hotkey owned by this addon. It starts UNBOUND: an addon names an
        // action, the user chooses the key (Options > Keybindings), which is the WoW model and the only one
        // consistent with KeyBinding's one-key-one-action exclusivity. An addon-chosen default could not
        // steal a key anyway (KeyBinding.get never runs set()'s unbind pass) — it would simply lose the
        // collision, since the addon root is walked last, leaving a dead hotkey with nothing to explain it.
        // Returns the handle, so registrations chain.
        m.set("register", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                // a.arg(1) = self (colon call); the arguments start at 2.
                LuaValue name = a.arg(2), fn = a.arg(3);
                if(!name.isstring() || !fn.isfunction())
                    throw new LuaError("keybindings:register(name, fn) expects (string, function)"
                                       + " — a hotkey starts unbound; the user assigns the key in Options > Keybindings");
                HookApi.newKeyBind(owner, name.tojstring(), fn);
                return handle;
            }
        });

        // key(name) / key(name, key) — read a binding's current key as a display string ("Ctrl+M"), or remap it
        // exactly as the keybind panel does (persisted; "None" unbinds). This was the API's LAST get/set pair
        // and it collapses onto one name like every other property: arity is the verb, and the first argument
        // is the ADDRESS of the binding rather than a value being written. A read misses to nil (an unknown or
        // unbound name is a real answer); a WRITE to an unknown name throws, because there is nothing to remap.
        m.set("key", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue name = Args.required(a, 2, "keybindings:key", "name");
                if(!name.isstring())
                    throw new LuaError("keybindings:key(name) expects a string");
                LuaValue key = Args.written(a, 3, "keybindings:key", "key");
                KeyBinding b = resolve(owner, name.tojstring());
                if(key == null) {                          // the read arity
                    if(b == null)
                        return LuaValue.NIL;
                    KeyMatch km = b.key();
                    return ((km == null) || (km == KeyMatch.nil)) ? LuaValue.NIL : LuaValue.valueOf(km.name());
                }
                if(!key.isstring())
                    throw new LuaError("keybindings:key(name, key) expects (string, string)");
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

        // unregister(name) — unbind one of THIS addon's hotkeys (client bindings are not the addon's to drop).
        m.set("unregister", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue name = a.arg(2);
                if(!name.isstring())
                    throw new LuaError("keybindings:unregister(name) expects a string");
                HookApi.removeKeyBindsNamed(owner, name.tojstring());
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
