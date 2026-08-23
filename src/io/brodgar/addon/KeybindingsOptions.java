package io.brodgar.addon;

import haven.KeyBinding;
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
 * {@code on("test", ...)} then {@code binding():get("test")} refer to the same binding without the addon ever
 * spelling its own id. A name that has no addon-scoped binding falls back to the raw registry id, which is how
 * a built-in client hotkey is reached ({@code binding():get("inv")}). The addon scope is tried first so an
 * addon can never be shadowed by a client binding that happens to share its name. That resolution lives on
 * the collection ({@link LuaBinding#collection}), which is what addresses a binding now.
 *
 * <p><b>Two verbs, and each is a whole surface.</b> {@code on} declares a hotkey and hands back a
 * {@link LuaSub}; {@code binding()} is the collection of every {@link KeyBinding} the client knows, whose
 * members carry the read and the write. There is no address-plus-value form beside it — {@code key(name, k)}
 * was one, and a collection plus an object says the same thing with one way to write it.
 */
public final class KeybindingsOptions {
    private KeybindingsOptions() {}

    /** Create the keybindings subsystem handle for {@code owner}. */
    public static LuaValue create(final Addon owner) {
        // Refusal.closedIndex, not the methods table itself: kb:get / kb:set would otherwise read as plain nil
        // and fail one call later as "attempt to call a nil value", saying nothing about what replaced them.
        return OptionsHandle.close(OptionsHandle.open("Options(keybindings)"), "keybindings", methods(owner),
            "the keybindings handle", null);
    }

    private static LuaTable methods(final Addon owner) {
        final LuaValue bindings = LuaBinding.collection(owner);
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

        // binding() — the collection of every KeyBinding the client knows: this addon's hotkeys, other
        // addons' and the client's own, ordered by id. It IS the address (b:key() reads, b:key(k) writes,
        // b:key(nil) reverts), so there is no address-plus-value verb beside it: one collection and one
        // object where a get/set pair and a map-shaped list used to be.
        m.set("binding", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                if(Args.passed(a, 2))
                    throw new LuaError("keybindings:binding() takes no arguments — it IS the collection:"
                        + " :get(id) addresses one binding and :list(filter) / :find(filter) search them");
                return bindings;
            }
        });
        return m;
    }
}
