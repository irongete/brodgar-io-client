package io.brodgar.addon;

import haven.Resource;

import java.util.ArrayList;
import java.util.List;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

/**
 * {@code hafen.resource()} (151) — the client's resources by name, as the {@link LuaCollection} the section
 * object IS. {@code :get(name)} mints an interned {@link LuaResource} for any well-formed name and fetches
 * nothing; {@code :list(filter)}, {@code :count(filter)} and {@code :find(filter)} enumerate what the client
 * <b>holds</b> — {@code Resource.remote().cached()}, which folds the local pool's cache in — by name
 * substring. The two halves are different sets on purpose: the game names every resource, the client has
 * fetched only the ones something asked for, and a handle for a name is how something asks.
 *
 * <p><b>A name is well-formed when it is a path</b>: segments split on {@code /}, none empty (so no
 * leading or trailing {@code /} and no {@code //}), none {@code ..}. Anything else is refused naming the
 * rule it broke, because the pool would enqueue it, an HTTP source would ask the server for it, and the
 * answer would be a failed fetch on a handle whose name was the mistake.
 */
final class ResourceApi {
    private ResourceApi() {
    }

    /** Mount {@code hafen.resource()} in {@code owner}'s env. */
    static void install(LuaTable hafen, final Addon owner) {
        Section.mount(hafen, "resource", collection(owner), null);
    }

    /** The teardown step: every layer write this addon made, released (151.2, {@link ResourceWrites#teardown}). */
    static void teardown(Addon owner) {
        ResourceWrites.teardown(owner);
    }

    /**
     * The name a call handed in, checked: a string, with no empty segment, no {@code ..} segment and no
     * leading {@code /}. Refuses naming the rule. Shared with the write side, so a declaration by name
     * cannot register what a read could not address.
     */
    static String name(LuaValue key, String verb) {
        if(key.type() == LuaValue.TNUMBER)
            throw new LuaError(verb + ": the key is a RESOURCE NAME string (e.g. \"gfx/hud/chr/agi\"),"
                + " not a number");
        if(key.type() != LuaValue.TSTRING)
            throw new LuaError(verb + ": expected a resource name string (e.g. \"gfx/hud/chr/agi\"), got "
                + key.typename());
        String name = key.tojstring();
        if(name.startsWith("/"))
            throw new LuaError(verb + ": a resource name has no leading \"/\" (got \"" + name + "\")");
        for(String seg : name.split("/", -1)) {
            if(seg.isEmpty())
                throw new LuaError(verb + ": a resource name has no empty segment — no \"//\", no trailing"
                    + " \"/\", and it is not empty (got \"" + name + "\")");
            if(seg.equals(".."))
                throw new LuaError(verb + ": a resource name has no \"..\" segment (got \"" + name + "\")");
        }
        return name;
    }

    private static LuaValue collection(final Addon owner) {
        final String how = "hafen.resource()";
        return LuaCollection.create(how, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(Resource r : Resource.remote().cached())
                    out.add(LuaResource.of(owner, r.name));
                return out;
            }

            public int size() {
                return Resource.remote().cached().size();
            }

            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                LuaResource h = LuaResource.resolve(member);
                return (h == null) ? null : h.name;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                return LuaResource.of(owner, name(key, how + ":get(name)"));
            }

            /** Any well-formed name is addressable, fetched or not. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }

            public String keyName() {
                return "name";
            }
        }, null);
    }
}
