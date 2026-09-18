package io.brodgar.addon;

import org.luaj.vm2.*;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import java.util.ArrayList;
import java.util.List;

/** hafen.client():addons(): every addon the client discovered, and the Addon handle :get(id) mints (156.1). */
final class LuaAddon {
    static final String COLL = "hafen.client():addons()";
    final String id;
    private LuaAddon(String id) { this.id = id; }
    public String toString() { return "Addon(" + id + ")"; }

    static LuaValue collection(final Addon owner) {
        LuaTable extra = new LuaTable();
        extra.set("export", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                LuaCollection.receiver(me, COLL, "export");
                Args.only(a, 1, COLL + ":export");
                LuaValue t = Args.required(a, 2, COLL + ":export", "t");
                if(!t.istable())
                    throw new LuaError(COLL + ":export(t): t must be a table, got " + t.typename());
                if(owner.export != null)
                    throw new LuaError(COLL + ":export(t): already exported — an addon exports once, in its file body");
                owner.export = Crossing.snapshot(t.checktable(), COLL + ":export(t)");
                return me;
            }
        });
        return LuaCollection.create(COLL, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(String id : AddonRegistry.discovered().keySet()) out.add(of(owner, id));
                return out;
            }
            public boolean named() { return true; }
            public String needle(LuaValue member) { LuaAddon h = resolve(member); return (h == null) ? null : h.id; }
            public boolean addressable() { return true; }
            public String keyName() { return "id"; }
            public LuaValue getMember(LuaValue key) {
                if(key.type() != LuaValue.TSTRING)
                    throw new LuaError(COLL + ":get(id): expected an addon id, got " + key.typename());
                return of(owner, key.tojstring());
            }
            public LuaCollection.Missing missing() { return LuaCollection.Missing.MINT; }
        }, extra);
    }

    static LuaValue of(final Addon owner, final String id) {
        return owner.addonHandles.of(id, () -> LuaValue.userdataOf(new LuaAddon(id), meta(owner)));
    }
    static LuaAddon resolve(LuaValue v) {
        if((v == null) || !v.isuserdata()) return null;
        Object o = v.touserdata();
        return (o instanceof LuaAddon) ? (LuaAddon)o : null;
    }
    private static LuaAddon handle(LuaValue self, String method) {
        LuaAddon h = resolve(self);
        if(h == null)
            throw new LuaError("addon:" + method + "() — use a COLON call on an Addon object (" + COLL + ":get(id))");
        return h;
    }

    private static LuaValue meta(final Addon owner) {
        if(owner.addonMeta != null) return owner.addonMeta;
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("addon", methods(owner), "an addon the client discovered"));
        mt.set("__name", LuaValue.valueOf("Addon"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaAddon h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Addon(?)" : h.toString());
            }
        });
        owner.addonMeta = mt;
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        m.set("id", new VarArgFunction() { public Varargs invoke(Varargs a) {
            return LuaValue.valueOf(handle(Args.only(a, 0, "addon:id"), "id").id); } });
        m.set("exists", new VarArgFunction() { public Varargs invoke(Varargs a) {
            String id = handle(Args.only(a, 0, "addon:exists"), "exists").id;
            return LuaValue.valueOf(AddonRegistry.discovered().containsKey(id)); } });
        m.set("info", new VarArgFunction() { public Varargs invoke(Varargs a) {
            String id = handle(Args.only(a, 0, "addon:info"), "info").id;
            AddonRegistry.Discovered d = AddonRegistry.discovered().get(id);
            AddonRegistry.Status s = AddonRegistry.status(id);
            if((d == null) || (s == null)) return LuaValue.NIL;
            LuaTable t = new LuaTable();
            t.set("id", LuaValue.valueOf(id));
            Manifest mf = d.manifest;
            t.set("name", LuaValue.valueOf((mf != null) ? mf.name : id));
            if((mf != null) && (mf.version != null)) t.set("version", LuaValue.valueOf(mf.version));
            if((mf != null) && (mf.author != null)) t.set("author", LuaValue.valueOf(mf.author));
            if((mf != null) && (mf.description != null)) t.set("description", LuaValue.valueOf(mf.description));
            t.set("status", LuaValue.valueOf(s.word));
            if(s.reason != null) t.set("reason", LuaValue.valueOf(s.reason));
            return t; } });
        m.set("api", new VarArgFunction() { public Varargs invoke(Varargs a) {
            String id = handle(Args.only(a, 0, "addon:api"), "api").id;
            Addon lib = AddonRegistry.loaded(id);
            if((lib == null) || (lib.export == null)) return LuaValue.NIL;
            if(!Crossing.minimumMet(owner, lib)) return LuaValue.NIL;
            LuaValue view = owner.apiViews.get(id);
            if(view == null) {
                view = Crossing.copy(lib.export, lib, owner, id, Crossing.Direction.EXPORT, 0);
                owner.apiViews.put(id, view);
            }
            return view; } });
        return m;
    }
}
