package io.brodgar.addon;

import haven.Indir;
import haven.Makewindow;
import haven.Resource;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * A <b>Craft object</b> — the recipe a character has open ({@code s:craft():current()}): what it needs,
 * what it makes, and the button that makes it.
 *
 * <p><b>The intern key is the recipe window</b> (§2.4's <i>exposes only a widget</i> row), and that is what
 * gives {@code :exists()} its meaning: the server builds a fresh window for each recipe, carrying the recipe
 * name on the window itself, so opening another recipe does not <i>change</i> this Craft — it ends it. A
 * stashed Craft reads {@code :exists() == false} from that moment, and {@code :make()} on it refuses rather
 * than crafting whatever is open now.
 *
 * <p><b>The window is the whole address, so no account is added to it</b> (077.3). Every other entity of this
 * family took the account beside its key because an id or an index counts inside one character alone — a
 * widget does not: it stands in exactly one session's tree and names it. So {@code :exists()} <b>walks up
 * from the window</b> to that tree's root rather than comparing against whatever recipe is open on screen,
 * which is what makes a recipe on a character you tabbed away from still open, still readable and still
 * craftable.
 *
 * <p><b>A recipe's slots are DATA, not entities</b> (§2.8). Every update to the inputs rebuilds the whole
 * row of them, so a handle to "the second input" would die on the next hammer-blow and mean nothing across a
 * change of recipe anyway; there is no key to address one by, and nothing to ask about one but its four
 * fields. So {@code :inputs()}, {@code :outputs()}, {@code :qualityInputs()} and {@code :tools()} are plain
 * arrays of plain tables, which is what the grammar reserves for a value.
 *
 * <p><b>{@code :make(all)} is the protected verb</b> and it is the recipe's own: it presses the window's Craft
 * button, or Craft All, exactly as a click would — so it consumes the ingredients like a manual craft. It
 * keeps the one key it has whichever character it is addressed at (077.3) — a key names the action, and the
 * player could have tabbed there and pressed the button — and the send is the <b>window's own</b>
 * {@code wdgmsg}, so it lands in the session that window stands in.
 *
 * <p><b>Threading.</b> The input, output and quality lists are swapped wholesale off the UI thread and the
 * tool list is appended to in place, so all four are copied under the UI monitor and their resource names
 * resolved outside it.
 */
public final class LuaCraft {
    /*
     * 096: THE HANDLE HALF IS GONE, and with it a second implementation of everything below it.
     *
     * D4 flattened this section at 089 -- s:craft() IS the open recipe, because a section that holds exactly
     * one thing is that thing -- and the LuaCraft object was kept on a stated reason: "it is what Refusal
     * keys the old spellings on, and it is still the shape a held handle has." Neither half of that survives
     * a look. Refusal keys on STRINGS (`session:craft():current`, `craft:name`), never on a class; and
     * nothing had handed a LuaCraft out since 089, so no handle was held.
     *
     * What the reason was protecting was 200 lines nothing could reach: an intern cache, a metatable, and a
     * SECOND set of the four recipe reads in the array shape 091 replaced -- beside a SECOND
     * CRAFT_MAKE-gated :make(). A protected write with no call site is a protected write nobody keeps in
     * step, and its refusal quoted a spelling this API no longer has.
     *
     * Deletion is the whole of the fix. The compiler is the proof: every remaining member is reached from
     * ActApi's Section.object("craft", LuaCraft.section(owner, user), CharApi.CR).
     */

    // ---- the SECTION itself (089, A-070) ------------------------------------------------------------

    /**
     * {@code s:craft()} — <b>the section IS the open recipe</b>, not a wrapper around it.
     *
     * <p>{@code conventions.md}: "Where a section holds exactly one thing, the section object <b>is</b> that
     * thing rather than a wrapper around it". {@code s:craft()} holds exactly one thing — the recipe window
     * that is open — and answered it through {@code :current()}, while {@code s:flowermenu()}, the
     * neighbouring "a window this character has open" section, reads {@code :list()}/{@code :count()}/
     * {@code :gob()} directly. One design applied twice, two different ways.
     *
     * <p><b>Every verb re-reads the window</b> ({@link ActApi#makewindow}) rather than holding one, which is
     * what {@code craft.md} already tells the reader to do: the server builds a fresh window for each recipe,
     * so opening another recipe does not change this one, it ends it. With nothing open {@code :exists()} is
     * false and every read is {@code nil} or empty — exactly what {@code s:flowermenu()} does.
     *
     * <p>The {@link LuaCraft} object stays: it is still the shape a held handle has. Nothing hands one
     * out any more.
     */
    static LuaTable section(final Addon owner, final String user) {
        LuaTable m = new LuaTable();
        m.set("recipe", sectionRead(owner, user, "recipe"));
        m.set("inputs", sectionRead(owner, user, "inputs"));
        m.set("outputs", sectionRead(owner, user, "outputs"));
        m.set("qualityInputs", sectionRead(owner, user, "qualityInputs"));
        m.set("tools", sectionRead(owner, user, "tools"));
        m.set("info", sectionRead(owner, user, "info"));
        m.set("exists", sectionRead(owner, user, "exists"));
        // make([all]) — the PROTECTED verb: press Craft, or Craft All. The gate is FIRST (D-213).
        m.set("make", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                Section.self(me, "craft", "make", CharApi.CR);
                AddonManager.requirePermission(AddonManager.current(), Permission.CRAFT_MAKE);
                boolean all = Args.optbool(a, 2, CharApi.CR + ":make", "all",
                                           "make as many as the recipe's inputs allow", false);
                Makewindow mw = ActApi.makewindow(user);
                if(mw == null)
                    throw new LuaError(CharApi.CR + ":make(all): no recipe is open on that character — "
                        + CharApi.CR + ":exists() is the test, and which recipe is open is the player's"
                        + " choice");
                Wire.send(owner, user, CharApi.CR + ":make", mw, "make",
                          Integer.valueOf(all ? 1 : 0));
                return me;
            }
        });
        return m;
    }

    /**
     * {@code :inputs()} / {@code :outputs()} as a <b>collection of {@link LuaCraftSpec}</b> (091, A-076).
     * Empty rather than nil once nothing is open; re-read on every call, because a recipe's slots are data
     * the server rebuilds wholesale.
     */
    private static LuaValue specColl(final Addon owner, final String user, final String verb,
                                     final boolean in) {
        return LuaCollection.create(CharApi.CR + ":" + verb + "()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                Makewindow mw = ActApi.makewindow(user);
                if(mw == null)
                    return out;
                for(Makewindow.Spec spec : specsOf(mw, in))
                    out.add(LuaCraftSpec.of(owner, spec));
                return out;
            }

            /** A slot names what fills it, so a string filter is a substring test over that. */
            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                return LuaCraftSpec.needle(member);
            }

            public String noGet() {
                return "a recipe's slots are data the server rebuilds wholesale, so one has no key:"
                    + " " + CharApi.CR + ":" + verb + "():find(needle) searches the name and the resource,"
                    + " and :list()[n] takes a position";
            }
        }, null);
    }

    /** {@code :qualityInputs()} / {@code :tools()} — the same object, carrying no count and no flag. */
    private static LuaValue resColl(final Addon owner, final String user, final String verb,
                                    final boolean quality) {
        return LuaCollection.create(CharApi.CR + ":" + verb + "()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                Makewindow mw = ActApi.makewindow(user);
                if(mw == null)
                    return out;
                for(Indir<Resource> r : resesOf(mw, quality))
                    out.add(LuaCraftSpec.of(owner, r));
                return out;
            }

            public boolean named() {
                return true;
            }

            public String needle(LuaValue member) {
                return LuaCraftSpec.needle(member);
            }

            public String noGet() {
                return "these are data the server rebuilds wholesale, so one has no key:"
                    + " " + CharApi.CR + ":" + verb + "():find(needle) searches the name and the resource";
            }
        }, null);
    }

    /** One side's slots, copied under the UI monitor (both lists are swapped wholesale off-thread). */
    private static List<Makewindow.Spec> specsOf(Makewindow mw, boolean in) {
        List<Makewindow.Spec> specs = new ArrayList<Makewindow.Spec>();
        synchronized(LuaWidget.monitor(mw)) {
            if(in) {
                for(Makewindow.Input w : mw.inputs)
                    specs.add(w.spec);
            } else {
                for(Makewindow.SpecWidget w : mw.outputs)
                    specs.add(w.spec);
            }
        }
        return specs;
    }

    /** The quality inputs or the tools, copied under the UI monitor. */
    private static List<Indir<Resource>> resesOf(Makewindow mw, boolean quality) {
        List<Indir<Resource>> reses = new ArrayList<Indir<Resource>>();
        synchronized(LuaWidget.monitor(mw)) {
            reses.addAll(quality ? mw.qmod : mw.tools);
        }
        return reses;
    }

    /** One read on the flattened section: {@link Section#self}, then the window, then the answer. */
    private static VarArgFunction sectionRead(final Addon owner, final String user,
                                             final String verb) {
        return new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                Section.self(me, "craft", verb, CharApi.CR);
                if(Args.passed(a, 2))
                    throw new LuaError(CharApi.CR + ":" + verb + "() takes no arguments — it reads the"
                        + " recipe that character has open, and which recipe that is is the player's choice");
                Makewindow mw = ActApi.makewindow(user);
                if("exists".equals(verb))
                    return LuaValue.valueOf(mw != null);
                if((mw == null) && ("info".equals(verb) || "recipe".equals(verb)))
                    return LuaValue.NIL;
                if("recipe".equals(verb)) {
                    String nm = mw.rcpnm;
                    return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
                }
                if("inputs".equals(verb))         return specColl(owner, user, verb, true);
                if("outputs".equals(verb))        return specColl(owner, user, verb, false);
                if("qualityInputs".equals(verb))  return resColl(owner, user, verb, true);
                if("tools".equals(verb))          return resColl(owner, user, verb, false);
                LuaTable t = new LuaTable();      // info()
                // No `recipe` key where the window carries no name: the verb states that absence with nil,
                // and a snapshot field that invents an empty string where its verb says "nothing" is the
                // two of them disagreeing about one fact.
                String nm = mw.rcpnm;
                if(nm != null)
                    t.set("recipe", LuaValue.valueOf(nm));
                t.set("inputs", specList(mw, true));
                t.set("outputs", specList(mw, false));
                t.set("qmod", resList(mw, true));
                t.set("tools", resList(mw, false));
                return t;
            }
        };
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** One side's slots as {@code {res, name, num, opt}} values, copied under the UI monitor. */
    private static LuaTable specList(Makewindow mw, boolean in) {
        List<Makewindow.Spec> specs = new ArrayList<Makewindow.Spec>();
        synchronized(LuaWidget.monitor(mw)) {              // both lists are swapped wholesale off-thread
            if(in) {
                for(Makewindow.Input w : mw.inputs)
                    specs.add(w.spec);
            } else {
                for(Makewindow.SpecWidget w : mw.outputs)
                    specs.add(w.spec);
            }
        }
        LuaTable out = new LuaTable();                     // ...then snapshot outside it (names may Loading)
        int i = 0;
        for(Makewindow.Spec spec : specs)
            out.set(++i, spec(spec));
        return out;
    }

    /** One slot as {@code {res, name, num, opt}}. Loading-guarded. */
    private static LuaValue spec(Makewindow.Spec spec) {
        LuaTable t = new LuaTable();
        // The displayed resource is the constraint (a category, e.g. "any board") when the recipe accepts
        // one, else the concrete item — mirroring the window's own display: that is what fills the slot.
        Indir<Resource> res = (spec.constraint != null) ? spec.constraint.res : spec.item.res;
        String rid = AddonManager.resIdent(res);
        if(rid != null)
            t.set("res", LuaValue.valueOf(rid));
        String nm = AddonManager.resTipName(res, rid);
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        t.set("num", LuaValue.valueOf(spec.num));          // -1 = unspecified (~ 1); exposed faithfully
        boolean opt;
        try {
            opt = spec.opt();                              // reads info() — may Loading before resources land
        } catch(RuntimeException e) {
            opt = false;
        }
        t.set("opt", LuaValue.valueOf(opt));
        return t;
    }

    /** The quality inputs or the tools as {@code {res, name}} values, copied under the UI monitor. */
    private static LuaTable resList(Makewindow mw, boolean quality) {
        List<Indir<Resource>> reses = new ArrayList<Indir<Resource>>();
        synchronized(LuaWidget.monitor(mw)) {              // qmod is swapped, tools is appended to in place
            reses.addAll(quality ? mw.qmod : mw.tools);
        }
        LuaTable out = new LuaTable();
        int i = 0;
        for(Indir<Resource> res : reses)
            out.set(++i, res(res));
        return out;
    }

    /** A bare resource reference as {@code {res, name}} (a quality modifier or a tool). Loading-guarded. */
    private static LuaValue res(Indir<Resource> r) {
        LuaTable t = new LuaTable();
        String rid = AddonManager.resIdent(r);
        if(rid != null)
            t.set("res", LuaValue.valueOf(rid));
        String nm = AddonManager.resTipName(r, rid);
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        return t;
    }
}
