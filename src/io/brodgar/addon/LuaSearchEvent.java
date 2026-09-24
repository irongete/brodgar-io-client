package io.brodgar.addon;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The {@code event} of one {@code Search} fire (164.1): a client search list is testing one row against what is
 * typed, and every addon holding the key has a say. {@code event:row()} is the row, {@code event:text()} what is
 * typed, {@code event:match()} the verdict as it stands and {@code event:match(keep)} a handler's own.
 *
 * <p>The verdict is one {@link Controls.Verdict} shared by every handler of every addon for this one row, so the
 * outcome does not depend on the order they run in: see there. The row is minted into a {@link LuaRow} on the
 * first {@code event:row()}, inside the handler's own Lua.
 */
final class LuaSearchEvent {
    private final Addon owner;
    private final Object row;
    private final String text;
    private final Controls.Verdict verdict;
    private LuaValue rowObj;

    private LuaSearchEvent(Addon owner, Object row, String text, Controls.Verdict verdict) {
        this.owner = owner;
        this.row = row;
        this.text = text;
        this.verdict = verdict;
    }

    static LuaValue of(Addon owner, Object row, String text, Controls.Verdict verdict) {
        return LuaValue.userdataOf(new LuaSearchEvent(owner, row, text, verdict), meta(owner));
    }

    public String toString() {
        return "Event(search)";
    }

    private static LuaSearchEvent event(LuaValue self, String verb) {
        if(self.isuserdata() && (self.touserdata() instanceof LuaSearchEvent))
            return (LuaSearchEvent)self.touserdata();
        throw new LuaError("event:" + verb + "() — use a COLON call on the event your Search handler received");
    }

    private static LuaValue meta(Addon owner) {
        synchronized(LuaRow.META_LOCK) {
            if(owner.searchEventMeta != null)
                return owner.searchEventMeta;
            LuaTable mt = new LuaTable();
            mt.set(LuaValue.INDEX, Refusal.closedIndex("ev", methods(), "a search event",
                ":match(keep) keeps or drops this row"));
            mt.set("__name", LuaValue.valueOf("Event"));
            mt.set("__tostring", new OneArgFunction() {
                public LuaValue call(LuaValue self) {
                    return LuaValue.valueOf("Event(search)");
                }
            });
            owner.searchEventMeta = mt;
            return mt;
        }
    }

    private static LuaTable methods() {
        LuaTable m = new LuaTable();
        m.set("row", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaSearchEvent e = event(Args.only(a, 0, "event:row"), "row");
                if(e.rowObj == null)
                    e.rowObj = LuaRow.of(e.owner, e.row);
                return e.rowObj;
            }
        });
        m.set("text", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(event(Args.only(a, 0, "event:text"), "text").text);
            }
        });
        // match() reads the verdict as it stands; match(keep) is this handler's say. Arity is the verb.
        m.set("match", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = Args.only(a, 1, "event:match");
                LuaSearchEvent e = event(self, "match");
                LuaValue keep = Args.written(a, 2, "event:match", "keep");
                if(keep == null)
                    return LuaValue.valueOf(e.verdict.current());
                e.verdict.say(Args.bool(keep, "event:match", "keep", "true keeps the row, false drops it"));
                return self;
            }
        });
        return m;
    }
}
