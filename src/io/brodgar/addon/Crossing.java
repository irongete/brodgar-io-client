package io.brodgar.addon;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.ThreeArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.IdentityHashMap;

/** What crosses between two addons, and how: functions as wrappers, tables as read-only copies, handles not at all (156.3). */
final class Crossing {
    private Crossing() {}
    enum Direction { EXPORT, ARGUMENT, RESULT }

    /** export(t)'s snapshot: a deep copy with raw functions and plain tables; a bad value refuses with its path. */
    static LuaTable snapshot(LuaTable t, String verb) {
        return (LuaTable)snap(t, verb, "", new IdentityHashMap<LuaValue, LuaValue>());
    }
    private static LuaValue snap(LuaValue v, String verb, String path, IdentityHashMap<LuaValue, LuaValue> seen) {
        switch(v.type()) {
        case LuaValue.TNIL: case LuaValue.TBOOLEAN: case LuaValue.TNUMBER: case LuaValue.TSTRING: case LuaValue.TFUNCTION:
            return v;
        case LuaValue.TTABLE: {
            LuaValue done = seen.get(v);
            if(done != null) return done;
            LuaTable out = new LuaTable();
            seen.put(v, out);
            LuaValue k = LuaValue.NIL;
            while(true) {
                Varargs n = v.next(k);
                k = n.arg1();
                if(k.isnil()) break;
                String at = join(path, k);
                out.rawset(snap(k, verb, at, seen), snap(n.arg(2), verb, at, seen));
            }
            return out;
        }
        default:
            throw new LuaError(verb + ": '" + path + "' is a " + kind(v) + " — export functions and plain values"
                + " (strings, numbers, booleans, tables of them)");
        }
    }

    /** One value crossing from {@code from} to {@code to}. {@code label} names the call; {@code pos} the argument (0 = none). */
    static LuaValue copy(LuaValue v, Addon from, Addon to, String label, Direction dir, int pos) {
        return cross(v, from, to, label, dir, pos, "", new IdentityHashMap<LuaValue, LuaValue>(), meta(from, dir));
    }
    private static LuaValue cross(LuaValue v, Addon from, Addon to, String label, Direction dir, int pos, String path,
                                  IdentityHashMap<LuaValue, LuaValue> seen, LuaValue mt) {
        switch(v.type()) {
        case LuaValue.TNIL: case LuaValue.TBOOLEAN: case LuaValue.TNUMBER: case LuaValue.TSTRING:
            return v;
        case LuaValue.TFUNCTION: {
            if(v instanceof Wrapper) {
                Wrapper w = (Wrapper)v;
                return (w.owner == to) ? w.fn : v;              // home again: the raw function; elsewhere: as is
            }
            final LuaValue fn = v;
            final String wlabel = (dir == Direction.EXPORT) ? (label + "." + path) : ("a function of " + from.manifest.id);
            return to.crossWrappers.of(fn, () -> new Wrapper(from, fn, wlabel));
        }
        case LuaValue.TTABLE: {
            LuaValue done = seen.get(v);
            if(done != null) return done;
            ReadOnly out = new ReadOnly();
            seen.put(v, out);
            LuaValue k = LuaValue.NIL;
            while(true) {
                Varargs n = v.next(k);
                k = n.arg1();
                if(k.isnil()) break;
                String at = join(path, k);
                out.rawset(cross(k, from, to, label, dir, pos, at, seen, mt),
                           cross(n.arg(2), from, to, label, dir, pos, at, seen, mt));
            }
            out.setmetatable(mt);
            out.lock();
            return out;
        }
        default:
            throw new LuaError(refusal(label, dir, pos, path, kind(v)));
        }
    }

    /**
     * A crossed table's copy: {@code set} refuses once locked, unconditionally. LuaJ's {@code __newindex}
     * fires only for an ABSENT key (Lua 5.2 semantics) — a copy is built already populated, so every one of
     * its own keys is never absent, and the metatable alone would let a write to an EXISTING field through
     * silently. Locked after the copy is built, so the walk above can still populate it with {@code rawset}.
     */
    static final class ReadOnly extends LuaTable {
        private boolean locked;
        void lock() { locked = true; }
        public void set(LuaValue key, LuaValue value) {
            if(locked)
                getmetatable().get(LuaValue.NEWINDEX).call(this, key, value);
            else
                super.set(key, value);
        }
    }

    private static String refusal(String label, Direction dir, int pos, String path, String kind) {
        String where = (dir == Direction.RESULT)
            ? (path.isEmpty() ? "the result" : ("the result's field '" + path + "'"))
            : (path.isEmpty() ? ("argument " + pos) : ("argument " + pos + "'s field '" + path + "'"));
        String hint = (dir == Direction.RESULT) ? "hand back a value, a table or a function" : "hand it a function";
        return label + ": " + where + " is a " + kind + " — a handle does not cross to another addon; " + hint;
    }

    /** The read-only metatable of a copy: one sentence for an export view, one for a crossed table. */
    private static LuaValue meta(final Addon from, Direction dir) {
        final String msg = (dir == Direction.EXPORT)
            ? (from.manifest.id + "'s export is read-only — it is your copy; a change belongs in the library, through a function it exports")
            : ("a table from " + from.manifest.id + " is read-only here — it is a copy taken as it crossed; answer with a return value");
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.NEWINDEX, new ThreeArgFunction() {
            public LuaValue call(LuaValue t, LuaValue k, LuaValue v) { throw new LuaError(msg); }
        });
        mt.set(LuaValue.METATABLE, LuaValue.valueOf("read-only"));
        return mt;
    }

    static String kind(LuaValue v) {
        Object o = v.isuserdata() ? v.touserdata() : null;
        if(o instanceof LuaCollection) return "collection";
        String s = v.tojstring();
        int at = s.indexOf('(');
        if(at > 0) s = s.substring(0, at);
        return s.isEmpty() ? v.typename() : s;
    }
    static String join(String path, LuaValue key) {
        String k = (key.type() == LuaValue.TNUMBER) ? ("[" + key.tojstring() + "]") : key.tojstring();
        if(path.isEmpty()) return k;
        return (key.type() == LuaValue.TNUMBER) ? (path + k) : (path + "." + k);
    }

    /** Whether {@code receiver}'s manifest minimum on {@code lib}, if it names one, is met by lib's version. */
    static boolean minimumMet(Addon receiver, Addon lib) {
        String id = lib.manifest.id, v = lib.manifest.version;
        for(Manifest.Dependency d : receiver.manifest.allDependencies()) {
            if(!d.id.equals(id) || (d.min == null)) continue;
            if(!io.brodgar.addon.registry.Semver.valid(v)) return false;
            return io.brodgar.addon.registry.Semver.compare(v, d.min) >= 0;
        }
        return true;
    }

    /** A function of {@code owner} as another addon holds it: every call enters owner's door. */
    static final class Wrapper extends VarArgFunction {
        final Addon owner; final LuaValue fn; final String label;
        Wrapper(Addon owner, LuaValue fn, String label) { this.owner = owner; this.fn = fn; this.label = label; }
        public Varargs invoke(Varargs args) {
            Addon caller = AddonManager.current();
            if(caller == null) throw new LuaError(label + ": called outside any addon's code");
            if(!AddonRegistry.isLoaded(owner)) throw new LuaError(label + ": " + owner.manifest.id + " is disabled");
            LuaValue[] in = new LuaValue[args.narg()];
            for(int i = 0; i < in.length; i++)
                in[i] = copy(args.arg(i + 1), caller, owner, label, Direction.ARGUMENT, i + 1);
            Varargs out = AddonManager.callThrough(owner, Addon.C_EXPORT, fn, in, label);
            LuaValue[] back = new LuaValue[out.narg()];
            for(int i = 0; i < back.length; i++)
                back[i] = copy(out.arg(i + 1), owner, caller, label, Direction.RESULT, i + 1);
            return (back.length == 0) ? LuaValue.NONE : LuaValue.varargsOf(back);
        }
        public String tojstring() { return "function: " + label; }
    }
}
