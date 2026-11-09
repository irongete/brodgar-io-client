package io.brodgar.addon;

import haven.ItemInfo;
import haven.resutil.Curiosity;
import java.util.List;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

/** Live study metadata, tied to the interned Item rather than to a replaceable tooltip record. */
public final class LuaCuriosity {
    /* Retain the Lua value, not just its Java handle: profile-only users must keep Item interning alive. */
    private final LuaValue itemValue;
    private final LuaItem item;
    private static final LuaTable META = meta();

    private LuaCuriosity(LuaValue itemValue) {
        this.itemValue = itemValue;
        this.item = LuaItem.resolve(itemValue);
    }

    static LuaValue of(LuaValue item) {
        return LuaValue.userdataOf(new LuaCuriosity(item), META);
    }

    static Curiosity profile(ItemInfo.Owner owner) {
        List<ItemInfo> info = LuaItem.info(owner);
        return (info == null) ? null : ItemInfo.find(Curiosity.class, info);
    }

    static LuaValue snapshot(Curiosity profile) {
        if(profile == null)
            return LuaValue.NIL;
        LuaTable out = new LuaTable();
        out.set("lp", profile.exp);
        out.set("attention", profile.mw);
        out.set("cost", profile.enc);
        out.set("time", profile.time);
        return out;
    }

    private static LuaCuriosity handle(Varargs args, String verb) {
        Object value = Args.only(args, 0, "curiosity:" + verb).touserdata(LuaCuriosity.class);
        if(value == null)
            throw new LuaError("curiosity:" + verb + "() needs a Curiosity from item:curiosity()");
        return (LuaCuriosity)value;
    }

    private static LuaTable meta() {
        LuaTable methods = new LuaTable();
        methods.set("lp", number("lp", 0));
        methods.set("attention", number("attention", 1));
        methods.set("cost", number("cost", 2));
        methods.set("time", number("time", 3));
        methods.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs args) {
                LuaItem item = handle(args, "exists").item;
                return LuaValue.valueOf(LuaItem.live(item) != null && profile(item.owner) != null);
            }
        });
        methods.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs args) {
                return snapshot(profile(handle(args, "info").item.owner));
            }
        });
        LuaTable meta = new LuaTable();
        meta.set(LuaValue.INDEX, Refusal.closedIndex("curiosity", methods, "an item's live study profile"));
        meta.set("__name", LuaValue.valueOf("Curiosity"));
        return meta;
    }

    private static VarArgFunction number(final String verb, final int field) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs args) {
                Curiosity profile = profile(handle(args, verb).item.owner);
                if(profile == null)
                    return LuaValue.NIL;
                switch(field) {
                case 0: return LuaValue.valueOf(profile.exp);
                case 1: return LuaValue.valueOf(profile.mw);
                case 2: return LuaValue.valueOf(profile.enc);
                default: return LuaValue.valueOf(profile.time);
                }
            }
        };
    }
}
