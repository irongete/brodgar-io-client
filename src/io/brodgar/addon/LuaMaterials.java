package io.brodgar.addon;

import haven.Gob;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code gob:materials()} — the <b>collection of an object's variable-material slots</b> (spec
 * {@code 152-gob-materials}, task 152.1): {@code :list(filter)} reads them all in wire order, {@code :get(n)}
 * addresses one by its 1-based position, {@code :count}/{@code :find} the usual pair. There is no {@code :add}
 * or {@code :remove}: the set is the server's dressing, and what an addon changes is a slot's <i>material</i>.
 * Its one verb of its own is {@code :release()} (152.3): every slot this addon dressed on the object, back to
 * the server's — the whole-object spelling of {@code slot:release()}.
 *
 * <p>It is a <b>view</b>, {@link LuaOverlay#collection}'s shape: derived from the gob on every call through
 * the adopted {@code lib/vmat} attribute ({@link LuaMaterialSlot#served}) and holding nothing between them,
 * so it cannot outlive the gob and needs no pruning of its own. An object with no variable materials —
 * a composed body, a tree, one whose attribute has not arrived — counts {@code 0}; a gone gob lists nothing.
 * A string filter matches the <b>name of the resource in force</b> on the slot.
 */
final class LuaMaterials {
    /** How the collection is spelled in Lua, for its messages. */
    static final String NAME = "gob:materials()";

    private LuaMaterials() {
    }

    /** Every slot the server dressed on the gob, in wire order — empty for an object with none, or a gone one. */
    private static List<LuaValue> members(Addon owner, String user, long gobId) {
        List<LuaValue> out = new ArrayList<LuaValue>();
        Gob g = AddonManager.getgob(user, gobId);
        int n = LuaMaterialSlot.count(g);
        for(int wire = 0; wire < n; wire++)
            out.add(LuaMaterialSlot.of(owner, user, gobId, wire));
        return out;
    }

    static LuaValue collection(final Addon owner, final String user, final long gobId) {
        return LuaCollection.create(NAME, new LuaCollection.Source() {
            public List<LuaValue> members() {
                return LuaMaterials.members(owner, user, gobId);
            }

            /** The count without minting a handle per slot: the attribute's own size. */
            public int size() {
                return LuaMaterialSlot.count(AddonManager.getgob(user, gobId));
            }

            /** The name in force on the slot; a slot whose material has no name (none today) matches nothing. */
            public String needle(LuaValue member) {
                LuaMaterialSlot h = LuaMaterialSlot.resolve(member);
                String nm = (h == null) ? null : LuaMaterialSlot.inForce(AddonManager.getgob(h.user, h.gob), h.wire);
                return (nm == null) ? "" : nm;
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            // get(n) — one slot by its 1-based position, nil past the count. 0 is the commonest thing a caller
            // thinking in the server's numbers says, so the refusal names the rule and the verb that carries
            // the server's number rather than the range.
            public LuaValue getMember(LuaValue key) {
                int n = Args.integer(key, NAME + ":get", "n", "the 1-based position slot:index() answers");
                if(n < 1)
                    throw new LuaError(NAME + ":get(n): the key is the 1-based position slot:index() answers,"
                        + " so :list()[n] == :get(n) — :get(1) is the first slot. The server's own"
                        + " 0-based number is slot:wire()");
                Gob g = AddonManager.getgob(user, gobId);
                if(n > LuaMaterialSlot.count(g))
                    return LuaValue.NIL;
                return LuaMaterialSlot.of(owner, user, gobId, n - 1);
            }

            /** The key is the 1-based position {@code slot:index()} answers. */
            public String keyName() {
                return "n";
            }
        }, extra(owner, gobId));
    }

    /** The collection's own verbs, beside the conventions' ones {@link LuaCollection} mounts. */
    private static LuaTable extra(final Addon owner, final long gobId) {
        LuaTable m = new LuaTable();
        // release() — every slot THIS addon dressed on the object goes back to the server's material: dropped
        // on every live copy and in GobIntent, so a copy that arrives later draws the server's too. Other
        // addons' slots stay theirs. A no-op that still chains when nothing on the object is yours, and on a
        // gob that is gone.
        m.set("release", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 0, NAME + ":release");
                for(Gob g : AddonManager.gobCopies(gobId))
                    GobMaterials.revert(g, owner);
                GobIntent.releaseMaterials(gobId, owner);
                return self;
            }
        });
        return m;
    }
}
