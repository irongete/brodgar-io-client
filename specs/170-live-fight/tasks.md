# 170 — The live fight: tasks

> **Closing rule.** A task closes whole. Its report names what was verified and nothing past this feature's
> boundary: no idea for later, no suggestion, no "could also". The one thing a close may report is an in-game
> fact a suite settled against the plan (the give halves, the tap), with the change the plan already decided.

## How to work every task

- Read `spec.md` and `plan.md` first. The edits below are the whole of the change: apply them **in order**, as
  written, byte for byte. Every "Replace" text occurs **exactly once** in the file as the previous task left it;
  every "block from … to …" edit replaces from that line down to the first line after it that is exactly the
  closing line. If a text does not match, stop and report it — never reword to make it fit.
- The Java is at source/target 1.8 (no `var`, no switch expressions). No file under `src/haven/` changes.
- A suite is created under `addons/` exactly as shown; nothing else goes there.

## How every task is verified, in this order

```bash
rm -rf build/classes
ant hafen-client
```

`BUILD SUCCESSFUL`. The clean build is what catches a symbol that left one file and is still used by another
(`LuaOpponent.target` leaves in 170.2).

```bash
python tools/docverbs.py
```

```bash
python tools/refusalverbs.py
```

```bash
python tools/widgetstate.py
```

Each checker is run bare and read by its exit code, which must be `0`: `docverbs` ends with `nothing unresolved`
and `every version the docs state is the client's`, `refusalverbs` with `every verb a refusal names is one its
receiver answers`, `widgetstate` with no field lacking a note.

```bash
cd "$(mktemp -d)" && java -cp "C:/Users/irongete/Desktop/github/brodgar-io-client/lib/brodgar/luaj-jse-3.0.1.jar" luac -p "C:/Users/irongete/Desktop/github/brodgar-io-client/addons/170-live-fight.<N>/main.lua"
```

Exit `0`. It runs in a scratch directory because `luac` writes `luac.out` into the working directory.

```bash
rm -rf bin/addons/170-live-fight.* && cp -r addons/170-live-fight.<N> bin/addons/
```

The maintainer then restarts the client with `ant run` (which packages it), enables the suite on the AddOns
panel (a suite that declares keys raises the consent dialog), logs in, runs `:t170`, does the hand steps the
suite's window asks for, writes the observed result on each `[manual]` line and pastes the whole block back.

---

- [x] **170.1 — A fight's buffs fire their events once each, with one object, and say whose they are.**

`LuaBuff.active` — the predicate behind `buff:exists()`, every buff door's `:list()` and `BuffsAdapter`'s
announcements — also requires the buff's list to stand in the tree and to be one the client draws: the bar,
your list in a fight (`Fightview.buffs`) or an opponent's (`Relation.buffs`), never a relation's `relbuffs`.
`AddonManager.onWidgetDisposed` reports a dying `Buff` to the removal queue, so a relation's `del`, which
destroys its lists under the buffs, fires `BuffRemoved` once with the object `BuffAdded` handed out. New:
`session:fight():buff()` (yours in the fight), `opponent:buff()` (theirs), `buff:opponent()`, every `Buff` verb
refusing a surplus argument, and API edition `1.3`. `docs/client/combat.md` maps `Fightview`, `Fightsess` and
`GiveButton` for the whole feature, and the combat row of `services.md` moves there, corrected.

*Its suite* (no keys) subscribes to `BuffAdded`/`BuffRemoved` before the hand step and classes every payload by
the door that lists it, every half second while the fight lasts. It proves: one object for the fight's door and
its two refusals; openings of both sides arrive, none on the bar; `buff:opponent()` is the target's for theirs
and `nil` for yours; no payload stands outside every door (a `relbuff` would); a surplus argument is refused;
and, after the fight, every fight buff fired `BuffRemoved` exactly once with its `BuffAdded` object and answers
`:exists()` false. Running at all proves an addon declaring `1.3` loads.

`[manual]`: count the icons drawn beside your own character in the fight view — expect the number
`session:fight():buff()` lists.

**The passing log** (11 lines; `<n>` is what the run found):

```text
[pass] this suite declares api_version 1.3 and runs
[pass] session:fight():buff() is one object, and :get and an argument are refused naming why
[pass] buffs of the fight arrived by BuffAdded: <n> yours, <n> the animal's
[pass] none of them is on session:buff(), the bar
[pass] buff:opponent() is the target for the animal's and nil for yours
[pass] no BuffAdded payload stands outside every door: the bar, the fight, the target
[pass] a buff refuses a surplus argument, naming the verb
[manual] count the icons drawn beside your own character in the fight view now -- expect: <n>, the number session:fight():buff() lists
[pass] each of the <n> buffs of the fight fired BuffRemoved once, with the object BuffAdded handed
[pass] each now answers :exists() false, and session:fight():buff() is empty
[summary] 9 pass, 0 fail, 1 manual
```

**Headless pre-check, before the maintainer's run.** After `ant hafen-client`, this jshell script drives the real
`UI`, a `Fightview` built through its own factory, buffs placed with the server's arguments, `new`/`cur`/`tt`/
`del` messages and destroys, with a probe addon subscribed on the bus. Write it to a scratch directory (never the
repo) and run it from there:

```bash
export MSYS_NO_PATHCONV=1
R=C:/Users/irongete/Desktop/github/brodgar-io-client
CP="$R/build/classes;$R/lib/jglob.jar;$R/lib/ext/builtin-res.jar;$R/lib/ext/hafen-res.jar;$R/lib/ext/jogl/jogl-all.jar;$R/lib/ext/jogl/gluegen-rt.jar;$R/lib/brodgar/luaj-jse-3.0.1.jar;$R/lib/brodgar/minimal-json-0.9.5.jar;$R/lib/brodgar/sqlite-jdbc-3.53.4.0.jar;$R/lib/brodgar/Concentus-17318837bf.jar"
mkdir -p savedata && jshell -R-Djava.awt.headless=true -R-Dhaven.savedatadir="$(pwd)/savedata" --class-path "$CP" verify170.jsh > run.txt 2>&1; grep "^>>" run.txt
```

The toolkit stack traces in `run.txt` are harmless. The `>>` lines must read (the object ids vary):

```text
>> --- bar buff + your opening + relation 4711 + their opening + relbuff (3 event(s))
>>    BuffAdded res=gfx/hud/buffs/frame exists=true opponent=nil widget=Buff parent=Bufflist grandparent=RootWidget
>>    BuffAdded res=gfx/hud/buffs/cframe exists=true opponent=nil widget=Buff parent=Bufflist grandparent=Fightview
>>    BuffAdded res=gfx/hud/buffs/cframe-m exists=true opponent=Opponent(4711) widget=Buff parent=Bufflist grandparent=Fightview
>> --- tt on their opening (content changed) (1 event(s))
>> --- server destroys widget 3 (your opening) while the fight goes on (2 event(s))
>>      (BuffRemoved payload == its BuffAdded payload: true)
>> --- relation 4711 deleted (Relation.remove -> Bufflist.destroy) (2 event(s))
>>      (BuffRemoved payload == its BuffAdded payload: true)
>> --- server destroys widget 4 (their opening) (0 event(s))
>> --- server destroys frv (widget 1) (0 event(s))
>> --- after the fades (0 event(s))
```

Three `BuffAdded` (the relbuff announces nothing), one `BuffRemoved` with the same object on `del`, and none
afterwards. The script, `verify170.jsh`:

```java
import haven.*;
import java.lang.reflect.*;
import java.util.*;
import org.luaj.vm2.*;

void say(String s) { System.out.println(">> " + s); System.out.flush(); }

class H {
    static UI u;
    static Object st, addon;
    static Globals g;
    static Method drainEntered, refresh, drainDeaths, enter, leave;
}

Indir<Resource> resof(String nm) {
    Resource r = Resource.local().loadwait(nm);
    return () -> r;
}

Widget.Factory buffOf(String nm) {
    Indir<Resource> ind = resof(nm);
    return (ui, args) -> new Buff(ind);
}

void lua(String src) throws Exception {
    H.enter.invoke(null, H.addon);
    try {
        H.g.load(src, "probe").call();
    } finally {
        H.leave.invoke(null, H.addon);
    }
}

void settle() throws Exception {
    Thread t = new Thread(() -> H.u.queue.drain());
    t.setDaemon(true);
    t.start();
    t.join(5000);
    if(t.isAlive())
        say("!! the command queue did not drain (a command failed)");
}

void pump(String label) throws Exception {
    settle();
    H.drainEntered.invoke(null);
    H.refresh.invoke(null, H.st);
    H.drainDeaths.invoke(null, H.st);
    LuaValue log = H.g.get("LOG");
    say("--- " + label + " (" + log.length() + " event(s))");
    for(int i = 1; i <= log.length(); i++)
        say("   " + log.get(i).tojstring());
    H.g.set("LOG", new LuaTable());
}

void run() throws Exception {
    Class<?> AM = Class.forName("io.brodgar.addon.AddonManager");
    Class<?> SS = Class.forName("io.brodgar.addon.AddonManager$SessionState");
    Class<?> CA = Class.forName("io.brodgar.addon.CharApi");
    Class<?> MF = Class.forName("io.brodgar.addon.Manifest");
    Class<?> AD = Class.forName("io.brodgar.addon.Addon");
    Widget.initnames();   // what the client's startup does: the @RName registry ("frv", "buff", ...)
    say("frv factory=" + Widget.gettype3("frv") + " buff factory=" + Widget.gettype3("buff"));

    // A real UI, with a Session stand-in so AddonManager.state(u) mints a SessionState (it refuses a UI with
    // no Session that is not the layer). Nothing below dereferences the Session except where noted.
    UI u = H.u = new UI(null, new Audio.Root(haven.iosys.audio.DummyAudio.DummySink.instance), Coord.of(1200, 900), null);
    Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
    uf.setAccessible(true);
    sun.misc.Unsafe unsafe = (sun.misc.Unsafe)uf.get(null);
    u.sess = (Session)unsafe.allocateInstance(Session.class);
    io.brodgar.addon.AddonManager.sessionArrived(u);   // the session's pump: SessionState + AddonRoot (queueState non-null)

    Method stateM = AM.getDeclaredMethod("state", UI.class); stateM.setAccessible(true);
    H.st = stateM.invoke(null, u);
    Method qs = AM.getDeclaredMethod("queueState", UI.class); qs.setAccessible(true);
    say("state=" + (H.st != null) + " queueState=" + (qs.invoke(null, u) != null));

    H.drainEntered = AM.getDeclaredMethod("drainEnteredWidgets"); H.drainEntered.setAccessible(true);
    H.refresh = CA.getDeclaredMethod("refreshTreeAdapters", SS); H.refresh.setAccessible(true);
    H.drainDeaths = AM.getDeclaredMethod("drainWidgetDeaths", SS); H.drainDeaths.setAccessible(true);
    H.enter = AM.getDeclaredMethod("enterLua", AD); H.enter.setAccessible(true);
    H.leave = AM.getDeclaredMethod("leaveLua", AD); H.leave.setAccessible(true);

    // A probe addon with the whole hafen table, subscribed on the bus exactly as a published addon would be.
    Method test = MF.getDeclaredMethod("test", String.class); test.setAccessible(true);
    Object man = test.invoke(null, "probe");
    Constructor<?> ac = AD.getDeclaredConstructor(MF, java.nio.file.Path.class, Globals.class); ac.setAccessible(true);
    H.g = org.luaj.vm2.lib.jse.JsePlatform.standardGlobals();
    H.addon = ac.newInstance(man, java.nio.file.Paths.get(System.getProperty("user.dir"), "probe"), H.g);
    Method ih = AM.getDeclaredMethod("installHafen", Globals.class, AD); ih.setAccessible(true);
    ih.invoke(null, H.g, H.addon);
    Field af = AM.getDeclaredField("addons"); af.setAccessible(true);
    @SuppressWarnings("unchecked") List<Object> addons = (List<Object>)af.get(null);
    addons.add(H.addon);
    lua(String.join("\n",
        "LOG, HELD = {}, {}",
        "local function where(buff)",
        "  local w = buff:widget()",
        "  if not w then return 'widget=nil' end",
        "  local p = w:parent()",
        "  local pp = p and p:parent()",
        "  return 'widget=' .. tostring(w:type()) .. ' parent=' .. tostring(p and p:type()) .. ' grandparent=' .. tostring(pp and pp:type())",
        "end",
        "FIRST = {}",
        "local function rec(key)",
        "  return function(buff, session)",
        "    if key == 'BuffAdded' then FIRST[#FIRST + 1] = buff end",
        "    if key == 'BuffRemoved' then",
        "      local same = false",
        "      for _, b in ipairs(FIRST) do if b == buff then same = true end end",
        "      LOG[#LOG + 1] = '  (BuffRemoved payload == its BuffAdded payload: ' .. tostring(same) .. ')'",
        "    end",
        "    HELD[tostring(buff:res())] = buff",
        "    LOG[#LOG + 1] = key .. ' res=' .. tostring(buff:res()) .. ' exists=' .. tostring(buff:exists()) .. ' opponent=' .. tostring(buff:opponent()) .. ' ' .. where(buff)",
        "  end",
        "end",
        "hafen.event():on('BuffAdded', rec('BuffAdded'))",
        "hafen.event():on('BuffRemoved', rec('BuffRemoved'))",
        "hafen.event():on('BuffChanged', rec('BuffChanged'))"));

    // A stand-in for GameUI.buffs (the bar): a Bufflist hung under the root, bound to a server id.
    Bufflist bar = new Bufflist();
    synchronized(u) { u.root.add(bar, Coord.of(0, 0)); }
    u.bind(bar, 50);

    // The combat view, created the way the server creates it: newwdg "frv" (Fightview.$_) placed under a parent.
    // (GameUI.addchild "fight" puts it in urpanel; here the parent is the root -- the entry seam asks only
    // hasparent(ui.root).)
    u.newwidgetp(1, "frv", 0, new Object[] {Coord.z});
    pump("frv created");
    Fightview fv = (Fightview)u.getwidget(1);

    // A bar buff, then YOUR opening (addchild "buff", null gob), a relation (uimsg "new"), THEIR opening
    // (addchild "buff", gobid) and a relbuff (addchild "relbuff", gobid) -- every one through UI.AddWidget.run.
    u.newwidgetp(2, buffOf("gfx/hud/buffs/frame"), 50, new Object[] {});
    u.newwidgetp(3, buffOf("gfx/hud/buffs/cframe"), 1, new Object[] {"buff", null});
    u.uimsg(1, "new", 4711, 0, 10, 20);
    u.newwidgetp(4, buffOf("gfx/hud/buffs/cframe-m"), 1, new Object[] {"buff", 4711});
    u.newwidgetp(5, buffOf("gfx/hud/combat/cool"), 1, new Object[] {"relbuff", 4711});
    pump("bar buff + your opening + relation 4711 + their opening + relbuff");
    say("Fightsess would draw: yours=" + fv.buffs.children(Buff.class).size()
        + " theirs(current=" + (fv.current == null ? "null" : "" + fv.current.gobid) + ")");
    u.uimsg(1, "cur", 4711);
    settle();
    say("after 'cur': current=" + fv.current.gobid + " theirs drawn=" + fv.current.buffs.children(Buff.class).size()
        + " relbuffs (never drawn)=" + fv.current.relbuffs.children(Buff.class).size());

    // BuffChanged on an opening: a content message ("tt") on the opening widget. The fake Session cannot
    // resolve a "ch", so the resource is swapped under it directly and the "tt" is what the tap sees.
    ((Buff)u.getwidget(4)).res = resof("gfx/hud/combat/indframe");
    u.uimsg(4, "tt");
    pump("tt on their opening (content changed)");

    // An opening expires in the middle of the fight: the server destroys that one widget.
    u.destroy(3);
    pump("server destroys widget 3 (your opening) while the fight goes on");

    // The relation ends: uimsg "del" -> Relation.remove() -> buffs.destroy() / relbuffs.destroy().
    u.uimsg(1, "del", 4711);
    pump("relation 4711 deleted (Relation.remove -> Bufflist.destroy)");
    lua("for k, b in pairs(HELD) do LOG[#LOG + 1] = 'held ' .. k .. ' exists=' .. tostring(b:exists()) end");
    pump("exists() of every held buff after 'del'");

    // The server destroys the opening widget itself.
    u.destroy(4);
    pump("server destroys widget 4 (their opening)");

    u.destroy(2);
    pump("server destroys widget 2 (the bar buff)");

    // The fight view goes: UI.destroy(int) destroys every server child first (the shadow tree).
    u.destroy(1);
    pump("server destroys frv (widget 1)");

    // Let the 0.35 s fades finish: the real unlink runs remove() -> the M1 seam, which must not fire twice.
    for(int i = 0; i < 40; i++) {
        synchronized(u) { u.tick(); }
        Thread.sleep(16);
    }
    pump("after the fades");
    lua("for k, b in pairs(HELD) do LOG[#LOG + 1] = 'held ' .. k .. ' exists=' .. tostring(b:exists()) end");
    pump("exists() of every held buff at the end");
}

try {
    run();
} catch(Throwable t) {
    t.printStackTrace(System.out);
}
/exit
```

**The edits, in order**

1. `src/io/brodgar/addon/LuaBuff.java` — the imports
   Replace:

```java
import haven.AddonWidgets;
import haven.Buff;
import haven.Bufflist;
import haven.GameUI;
import haven.GItem;
import haven.ItemInfo;
import haven.Resource;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

```

   with:

```java
import haven.AddonWidgets;
import haven.Buff;
import haven.Bufflist;
import haven.Fightview;
import haven.GameUI;
import haven.GItem;
import haven.ItemInfo;
import haven.Resource;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

```

2. `src/io/brodgar/addon/LuaBuff.java` — the class javadoc's first sentence
   Replace:

```java
 * A <b>Buff object</b> — one buff on the player's buff bar (spec {@code 025-buffs-oop}), the OOP
 * successor of the flat {@code hafen.buffs.list()}/{@code has()} snapshot reader. Built on exactly the

```

   with:

```java
 * A <b>Buff object</b> — one buff the client draws for a character (spec {@code 025-buffs-oop}): on its buff
 * bar, and (170.1) in a fight, where the combat view paints the buffs beside the character and beside each
 * opponent from lists of its own. The OOP successor of the flat {@code hafen.buffs.list()}/{@code has()}
 * snapshot reader. Built on exactly the

```

3. `src/io/brodgar/addon/LuaBuff.java` — the class javadoc's paragraph on `:exists()`
   Replace:

```java
 * deliberately has no answer for (D-060), and which a buff, having a lifetime, does. {@code :exists()} is
 * exactly the predicate {@code :list()} filters on: a current {@link Bufflist} child that is not fading
 * out after a server removal ({@code Buff.dest}, via {@link AddonWidgets#buffDest}).

```

   with:

```java
 * deliberately has no answer for (D-060), and which a buff, having a lifetime, does. {@code :exists()} is
 * exactly the predicate every door's {@code :list()} filters on: a child of a list the client draws, the list
 * standing in the tree, and the buff not fading out after a server removal ({@code Buff.dest}, via
 * {@link AddonWidgets#buffDest}) — see {@link #active}.

```

4. `src/io/brodgar/addon/LuaBuff.java` — every verb counts its arguments, and `opponent` is new
   Replace the whole block from the line that reads `private static LuaTable methods(final Addon owner) {` down to the first line after it that is only `}` indented by 4 spaces (its closing line), both lines included, with:

```java
    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // 170.1: every verb counts its arguments (Args.only). A OneArgFunction dropped a surplus one unseen,
        // where conventions.md promises it raises.
        // res() — the buff's resource name, its stable identity ("paginae/buff/poison"). Mutable: a "ch"
        // uimsg replaces it under a live buff, so this reads through the widget every call.
        m.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                String r = res(handle(Args.only(a, 0, "buff:res"), "res").wdg);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the display name: the resource tooltip, else a server-pushed Name info, else nil.
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                // LuaBuff.-qualified: inside a LuaFunction, a bare name() would be the function's own.
                String n = LuaBuff.name(handle(Args.only(a, 0, "buff:name"), "name").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // amount() — the 0..1 fraction of the buff's own meter frame (Buff.AMeterInfo), nil when the buff
        // publishes none. Content-defined, NOT seconds.
        m.set("amount", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Double am = amount(handle(Args.only(a, 0, "buff:amount"), "amount").wdg);
                return (am == null) ? LuaValue.NIL : LuaValue.valueOf(am.doubleValue());
            }
        });
        // remaining() — the 0..1 fraction of the radial overlay (GItem.MeterInfo): how much of the buff's run
        // is left. Content-defined and nil when the buff publishes none, and NOT seconds: the client has no
        // seconds-based buff timer to read.
        m.set("remaining", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Double c = duration(handle(Args.only(a, 0, "buff:remaining"), "remaining").wdg);
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.doubleValue());
            }
        });
        // number() — the integer badge drawn on the icon (GItem.NumberInfo), e.g. a stack count; nil when
        // the buff publishes none.
        m.set("number", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Integer n = number(handle(Args.only(a, 0, "buff:number"), "number").wdg);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n.intValue());
            }
        });
        // widget() — 094 (A-104): THE CROSSING BACK. The widget tree and the domain objects are two address
        // spaces, and a badge over the buff about to expire needs the buff's widget. The bridge was already
        // holding it -- it IS the handle -- so the crossing is one closure. A buff of a fight answers the
        // widget that HOLDS it, in a list the combat view hides and paints elsewhere.
        m.set("widget", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaBuff h = handle(Args.only(a, 0, "buff:widget"), "widget");
                return LuaWidget.of(owner, h.wdg);
            }
        });
        // exists() — is this buff still on the list it was drawn from? False once the server removes it
        // (including while it fades out), once its list leaves the tree (a fight relation ending), and across
        // a relog. The reads keep working either way, which is what makes a stashed BuffRemoved payload useful.
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(active(handle(Args.only(a, 0, "buff:exists"), "exists").wdg));
            }
        });
        // opponent() — 170.1: whose it is. The Opponent beside whom the combat view draws it; nil for one of
        // your own in a fight and for a buff on the bar. The inverse of opponent:buff().
        m.set("opponent", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return opponentOf(owner, handle(Args.only(a, 0, "buff:opponent"), "opponent").wdg);
            }
        });
        // info() — the one SNAPSHOT escape hatch (the documented Buff table shape), for logging/serialising.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return snapshot(handle(Args.only(a, 0, "buff:info"), "info").wdg);
            }
        });
        return m;
    }
```

5. `src/io/brodgar/addon/LuaBuff.java` — `actives` reads through the shared `live` scan
   Replace the whole block from the line that reads `static List<Buff> actives(String user) {` down to the first line after it that is only `}` indented by 4 spaces (its closing line), both lines included, with:

```java
    static List<Buff> actives(String user) {
        Bufflist bl = bufflist(user);
        return (bl == null) ? new ArrayList<Buff>() : live(bl);
    }
```

6. `src/io/brodgar/addon/LuaBuff.java` — `active` learns the tree and the drawn lists; the helpers after it are new
   Replace the whole block from the line that reads `/** Is {@code b} on the bar right now? The predicate {@link #actives} filters on — {@code :exists()}. */` down to the first line after it that is only `}` indented by 4 spaces (its closing line), both lines included, with:

```java
    /**
     * Is {@code b} a live buff the client draws? The predicate every door's {@code :list()} filters on, the one
     * {@code :exists()} answers and the one {@code CharApi.BuffsAdapter} announces on. Past the fade, three
     * conditions (170.1):
     * <ul>
     *   <li><b>its list stands in the tree.</b> A fight relation's lists are destroyed on {@code "del"}, and
     *       {@code Widget.destroy} unlinks the LIST and leaves every buff linked under it, so a membership scan
     *       alone went on answering true for a relation that had ended;</li>
     *   <li><b>the list is one the client draws</b>: the bar, your own list in a fight, an opponent's list, and
     *       never a relation's {@code relbuffs}, which nothing paints and no door lists;</li>
     *   <li><b>it is a child of that list.</b></li>
     * </ul>
     * A list under the combat view is read under that view's monitor, where the loader thread adds and removes
     * relations. The bar's is read lock-free, as it always was.
     */
    static boolean active(Buff b) {
        if((b == null) || AddonWidgets.buffDest(b))
            return false;
        Bufflist bl = barOf(b);
        if((bl == null) || (bl.ui == null) || !bl.hasparent(bl.ui.root))
            return false;
        if(bl.parent instanceof Fightview) {
            Fightview fv = (Fightview)bl.parent;
            synchronized(LuaWidget.monitor(fv)) {
                return drawn(fv, bl) && holds(bl, b);
            }
        }
        return holds(bl, b);
    }

    /** Is {@code bl} a list the combat view paints, yours or an opponent's? The caller holds the view's monitor. */
    private static boolean drawn(Fightview fv, Bufflist bl) {
        if(bl == fv.buffs)
            return true;
        for(Fightview.Relation rel : fv.lsrel) {
            if(rel.buffs == bl)
                return true;
        }
        return false;
    }

    /** Is {@code b} one of {@code bl}'s own buffs? */
    private static boolean holds(Bufflist bl, Buff b) {
        for(Buff c : bl.children(Buff.class)) {
            if(c == b)
                return true;
        }
        return false;
    }

    /** {@code bl}'s buffs in child order, which is the order drawn, minus any fading out after a server removal. */
    private static List<Buff> live(Bufflist bl) {
        List<Buff> out = new ArrayList<Buff>();
        for(Buff b : bl.children(Buff.class)) {
            if(!AddonWidgets.buffDest(b))
                out.add(b);
        }
        return out;
    }

    /**
     * 170.1: {@code buff:opponent()} — the opponent beside whom the combat view draws {@code b}, as the interned
     * Opponent of the character whose fight it is; {@code NIL} for a buff on the bar, for one of your own in a
     * fight, and for one whose relation has ended. The relation is found under the view's monitor and the
     * handle minted outside it.
     */
    static LuaValue opponentOf(Addon owner, Buff b) {
        Bufflist bl = barOf(b);
        if((bl == null) || !(bl.parent instanceof Fightview))
            return LuaValue.NIL;
        Fightview fv = (Fightview)bl.parent;
        long gobid = 0;
        boolean found = false;
        synchronized(LuaWidget.monitor(fv)) {
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.buffs == bl) && !rel.invalid) {
                    gobid = rel.gobid;
                    found = true;
                    break;
                }
            }
        }
        return found ? LuaOpponent.of(owner, AddonManager.userOf(fv), gobid) : LuaValue.NIL;
    }
```

7. `src/io/brodgar/addon/LuaBuff.java` — the collection becomes three doors over one source
   Replace the whole block from the line that reads `static LuaValue collection(final Addon owner, final String user) {` down to the first line after it that is only `}` indented by 4 spaces (its closing line), both lines included, with:

```java
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.B, new Door(owner, noKey(CharApi.B)) {
            List<Buff> buffs() {
                return actives(user);
            }
        }, null);
    }

    /**
     * 170.1: {@code s:fight():buff()} — yours in the fight, the list the combat view paints beside that character
     * ({@code Fightview.buffs}). Minted once per (addon, session) by {@code CharApi.fight}. Empty out of a fight
     * and before the HUD is up.
     */
    static LuaValue fightCollection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.FT + ":buff()", new Door(owner, noKey(CharApi.FT + ":buff()")) {
            List<Buff> buffs() {
                Fightview fv = LuaOpponent.view(user);
                if(fv == null)
                    return new ArrayList<Buff>();
                synchronized(LuaWidget.monitor(fv)) {
                    return live(fv.buffs);
                }
            }
        }, null);
    }

    /**
     * 170.1: {@code opponent:buff()} — that opponent's openings, the list the combat view paints beside them
     * ({@code Relation.buffs}; the relation's {@code relbuffs} are painted by nothing and are not here). A view
     * minted per call, empty once the fight with them has ended.
     */
    static LuaValue opponentCollection(final Addon owner, final String user, final long gobid) {
        return LuaCollection.create("opponent:buff()", new Door(owner, noKey("opponent:buff()")) {
            List<Buff> buffs() {
                Fightview fv = LuaOpponent.view(user);
                if(fv != null) {
                    synchronized(LuaWidget.monitor(fv)) {
                        for(Fightview.Relation rel : fv.lsrel) {
                            if((rel.gobid == gobid) && !rel.invalid)
                                return live(rel.buffs);
                        }
                    }
                }
                return new ArrayList<Buff>();
            }
        }, null);
    }

    /**
     * One buff door: the live buffs of one list, in the order drawn. There is no {@code :get}, and that is the
     * point of the shape: a buff has no key. Two buffs can share a resource and a {@code "ch"} uimsg replaces
     * {@code Buff.res} under a live one, so a needle is a <i>search</i>, never an address. A string filter
     * matches the res <b>or</b> the display name. Each door passes its own spelling to
     * {@link LuaCollection#create} as a constant, which is what lets {@code tools/refusalverbs.py} see it.
     */
    private abstract static class Door extends LuaCollection.Source {
        private final Addon owner;
        private final String noKey;

        Door(Addon owner, String noKey) {
            this.owner = owner;
            this.noKey = noKey;
        }

        /** The buffs this door lists, in the order drawn. */
        abstract List<Buff> buffs();

        public List<LuaValue> members() {
            List<Buff> active = buffs();
            List<LuaValue> out = new ArrayList<LuaValue>(active.size());
            for(int i = 0; i < active.size(); i++)
                out.add(of(owner, active.get(i)));
            return out;
        }

        // res OR name, as one string the substring test runs over once. The separator is a newline, which no
        // resource name and no display name contains, so a match can never span the two halves.
        public String needle(LuaValue member) {
            LuaBuff h = resolve(member);
            Buff b = (h == null) ? null : h.wdg;
            String res = res(b), nm = LuaBuff.name(b);    // see the note in methods()
            return ((res == null) ? "" : res) + "\n" + ((nm == null) ? "" : nm);
        }

        /** These have a name, so a string filter is a substring test over {@link #needle}. */
        public boolean named() {
            return true;
        }

        public String noGet() {
            return noKey;
        }
    }

    /** The sentence a buff door's missing {@code :get} carries, spelled with the door's own name. */
    private static String noKey(String door) {
        return "a buff has no key, since two can share a resource and the server can replace one under a live"
            + " buff: " + door + ":find(needle) is the search and " + door + ":list()[n] takes a position";
    }
```

8. `src/io/brodgar/addon/LuaOpponent.java` — the imports
   Replace:

```java
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

```

   with:

```java
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

```

9. `src/io/brodgar/addon/LuaOpponent.java` — `opponent:buff()`, before `info`
   Replace:

```java
        // info() — the one SNAPSHOT escape hatch.

```

   with:

```java
        // buff() — 170.1: that opponent's openings, the list the combat view paints beside them. A view minted
        // per call; empty once the fight with them has ended.
        m.set("buff", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:buff"), "buff");
                return LuaBuff.opponentCollection(owner, h.user, h.gobid);
            }
        });
        // info() — the one SNAPSHOT escape hatch.

```

10. `src/io/brodgar/addon/LuaOpponent.java` — `view` is shared with LuaBuff and answers for a view in the tree only
   Replace the whole block from the line that reads `/** <b>That character's</b> combat view, or {@code null} before its HUD is up (it is created with the rest of the HUD). */` down to the first line after it that is only `}` indented by 4 spaces (its closing line), both lines included, with:

```java
    /**
     * <b>That character's</b> combat view, or {@code null} before its HUD is up. {@code GameUI.fv} is never
     * cleared, so a view that has left the tree answers {@code null} too (170.1).
     */
    static Fightview view(String user) {
        GameUI g = AddonManager.gameui(user);
        Fightview fv = (g == null) ? null : g.fv;
        return ((fv == null) || (fv.ui == null) || !fv.hasparent(fv.ui.root)) ? null : fv;
    }
```

11. `src/io/brodgar/addon/CharApi.java` — mint the fight's own buff door beside the manoeuvres
   Replace:

```java
        final LuaValue maneuvers = LuaManeuver.collection(owner, user);

```

   with:

```java
        final LuaValue maneuvers = LuaManeuver.collection(owner, user);
        final LuaValue fightBuffs = LuaBuff.fightCollection(owner, user);

```

12. `src/io/brodgar/addon/CharApi.java` — `session:fight():buff()`, before `target`
   Replace:

```java
        // target() — who THAT character is fighting, nil out of combat. An Opponent, whose :gob() is the

```

   with:

```java
        // buff() — 170.1: yours in the fight, the list the combat view paints beside THAT character, minted
        // once and handed back by identity. Empty out of a fight.
        fight.set("buff", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "buff", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":buff() takes no arguments — it IS the collection of your buffs in"
                        + " the fight, and :find(needle) searches it");
                return fightBuffs;
            }
        });
        // target() — who THAT character is fighting, nil out of combat. An Opponent, whose :gob() is the

```

13. `src/io/brodgar/addon/AddonManager.java` — the disposal seam's javadoc
   Replace:

```java
     * hundred widgets inside it — and the one thing this closes is the gap an addon can see from Lua, which is
     * a widget it built and holds a subscription on.

```

   with:

```java
     * hundred widgets inside it — and the one thing this closes is the gap an addon can see from Lua, which is
     * a widget it built and holds a subscription on.
     *
     * <p><b>And a {@link Buff}</b> (170.1). A fight relation's buff lists are destroyed on {@code "del"}, which
     * reaches every buff under them here and nowhere else: a buff under a dying list never runs
     * {@code remove()}. Reported as a removal, it reaches the buff adapter in the removal drain, which runs ahead
     * of the disposal drain that retires its handle, so {@code BuffRemoved} fires once, with the object
     * {@code BuffAdded} handed out.

```

14. `src/io/brodgar/addon/AddonManager.java` — the disposal seam reports a dying buff
   Replace:

```java
        if((w.parent == null) || !(w instanceof Owned))
            return;
        onWidgetRemoved(w);
    }

```

   with:

```java
        if((w.parent == null) || !((w instanceof Owned) || (w instanceof Buff)))
            return;
        onWidgetRemoved(w);
    }

```

15. `src/io/brodgar/addon/ApiVersion.java` — the edition
   Replace:

```java
    public static final ApiVersion CURRENT = new ApiVersion(1, 2);
```

   with:

```java
    public static final ApiVersion CURRENT = new ApiVersion(1, 3);
```

16. `tools/docverbs.py` — a buff names its opponent, and an opponent hands a buff collection
   Replace:

```python
    ("buff", "widget"): "widget",

```

   with:

```python
    ("buff", "widget"): "widget",
    ("buff", "opponent"): "opponent",
    ("opponent", "buff"): "@collection",

```

17. `tools/refusalverbs.py` — the two new buff doors hand out buffs
   Replace:

```python
    "session:fight():deck()": "deckcard", "session:fight():maneuver()": "maneuver",

```

   with:

```python
    "session:fight():deck()": "deckcard", "session:fight():maneuver()": "maneuver",
    "session:fight():buff()": "buff", "opponent:buff()": "buff",

```

18. `docs/addons/api/buff.md` — the intro
   Replace:

```text
The buffs on one character's buff bar, read through its [session](session.md). `session:buff()` is that character's bar.
```

   with:

```text
The buffs the client draws for one character, read through its [session](session.md). `session:buff()` is that character's buff bar. A fight draws buffs of its own beside the character and beside each opponent, and [`session:fight()`](fight.md) lists them: they are `Buff` objects too.
```

19. `docs/addons/api/buff.md` — the interning rule
   Replace:

```text
It carries its own character: `buff:exists()` is about the bar it stands on. |
```

   with:

```text
It carries its own character: `buff:exists()` is about the list it stands on. |
```

20. `docs/addons/api/buff.md` — the `widget`, `exists` and new `opponent` rows
   Replace:

```text
| `buff:widget()` | [Widget](ui/widget.md) `\| nil` | Unprotected | The widget that draws it: the crossing back into the tree. |
| `buff:exists()` | `boolean` | Unprotected | Whether this buff is still on its bar. Always answers. |

```

   with:

```text
| `buff:widget()` | [Widget](ui/widget.md) `\| nil` | Unprotected | The widget that holds it: the crossing back into the tree. A buff of a fight stands in a list the fight view keeps hidden, so its widget does not mark where the icon is drawn. |
| `buff:exists()` | `boolean` | Unprotected | Whether this buff is still on the list it was drawn from: the bar, or a list of a fight. Always answers. |
| `buff:opponent()` | [`Opponent`](fight.md) `\| nil` | Unprotected | The opponent a buff of a fight is drawn beside. `nil` for a buff on the bar and for one of yours in a fight. |

```

21. `docs/addons/api/buff.md` — the events rule, and a new row after it
   Replace:

```text
| Events | [`BuffAdded`, `BuffRemoved`, `BuffChanged`](event/bus/character.md#character-and-status). Each payload is the `Buff` object. The buffs a character already has arrive as a burst of `BuffAdded` shortly after it enters the world. |
```

   with:

```text
| Events | [`BuffAdded`, `BuffRemoved`, `BuffChanged`](event/bus/character.md#character-and-status), for every buff the client draws: the bar's and a fight's. Each payload is the `Buff` object. The buffs a character already has arrive as a burst of `BuffAdded` shortly after it enters the world. |
| On the bar or in a fight | [`session:fight():buff()`](fight.md#the-fight-in-progress) and an opponent's `:buff()` list a fight's buffs, the same interned objects the events hand. `buff:opponent()` answers `nil` for yours and for a buff on the bar, and `session:buff():find(function(each) return each == buff end)` tells those two apart. |
```

22. `docs/addons/api/buff.md` — See Also
   Replace:

```text
- [Events](event/bus/character.md#character-and-status) — the buff events.
```

   with:

```text
- [Events](event/bus/character.md#character-and-status) — the buff events.
- [`session:fight`](fight.md) — the buffs a fight draws, and whose they are.
```

23. `docs/addons/api/event/bus/character.md` — the `BuffAdded` row
   Replace:

```text
| `BuffAdded` | [`Buff`](../../buff.md) | A buff appears. |
```

   with:

```text
| `BuffAdded` | [`Buff`](../../buff.md) | A buff appears: on the bar, or in a fight beside you or an opponent. |
```

24. `docs/addons/api/event/bus/character.md` — a rule row for a fight's buffs, after the held-slot row
   Replace:

```text
| A held slot fires on both edges | Once when the [hold](../../actionbar.md#hold-a-slot-unprotected) takes the slot, once when it ends and the server's content returns. While held, `slot:res()` is the entry's identity. |
```

   with:

```text
| A held slot fires on both edges | Once when the [hold](../../actionbar.md#hold-a-slot-unprotected) takes the slot, once when it ends and the server's content returns. While held, `slot:res()` is the entry's identity. |
| A fight's buffs | The buffs a fight draws beside you and beside each opponent fire the three buff keys too, and [`buff:opponent()`](../../buff.md) names whose. A fight relation ending takes its buffs with it: each fires `BuffRemoved` once, with the object `BuffAdded` handed. |
```

25. `docs/addons/api/fight.md` — the no-event rule
   Replace:

```text
| Unprotected, no write side, no event | Nothing throws. Read on demand. |
```

   with:

```text
| Unprotected, no write side | Nothing throws. The buffs of a fight fire the [buff events](event/bus/character.md#character-and-status); everything else is read on demand. |
```

26. `docs/addons/api/fight.md` — a new section before the target
   Replace:

```text
## The target

```

   with:

```text
## The fight in progress

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:fight():buff()` | collection | Unprotected | Your buffs in the fight, the ones the client draws beside that character: [Buff](buff.md) objects, `:list(filter)`, `:count(filter)`, `:find(filter)`. No `:get`. |

| Rule | Detail |
|---|---|
| One object | `session:fight():buff()` is the same object every call. Empty out of a fight. |
| The same objects as the events | A buff of a fight fires [`BuffAdded`, `BuffChanged` and `BuffRemoved`](event/bus/character.md#character-and-status) like one on the bar, and the doors here hand the same interned `Buff`. When the fight with an opponent ends, each of its buffs fires `BuffRemoved` once. |
| Not on the bar | [`session:buff()`](buff.md) is the buff bar alone. [`buff:opponent()`](buff.md) names the opponent a buff of a fight is drawn beside. |

## The target

```

27. `docs/addons/api/fight.md` — `target:buff()`
   Replace:

```text
| `target:exists()` | `boolean` | Unprotected | Whether that character is still fighting them. Always answers. |

```

   with:

```text
| `target:exists()` | `boolean` | Unprotected | Whether that character is still fighting them. Always answers. |
| `target:buff()` | collection | Unprotected | Their openings, the buffs drawn beside them: [Buff](buff.md) objects, a view minted per call. Empty once the fight with them has ended. |

```

28. `docs/addons/api/fight.md` — the who-and-nothing-else rule
   Replace:

```text
| Who, and nothing else | Name, health and position belong to the gob. The relation, initiative and openings are on the client and this API does not publish them. |
```

   with:

```text
| Who, and nothing else | Name, health and position belong to the gob, and the openings drawn beside them are `target:buff()`. The relation and the initiative are on the client and this API does not publish them. |
```

29. `docs/addons/api/references.md` — the crossing back
   Replace:

```text
answer `:widget()`, the widget that draws them. A badge over the buff about to expire is one hop.
```

   with:

```text
answer `:widget()`, the widget that draws them. A buff of a fight answers the widget that holds it: the fight view keeps that list hidden and paints the icon elsewhere. A badge over the buff about to expire is one hop.
```

30. `docs/addons/manifest.md` — the version the client implements
   Replace:

```text
This client implements API `1.2`.
```

   with:

```text
This client implements API `1.3`.
```

31. `docs/addons/manifest.md` — what needs `1.3`
   Replace:

```text
| What needs `1.2` | [`gob:outline(color, width)`](api/look.md#outline-unprotected), the ring round an object. Everything else these pages describe is in `1.0`. |
```

   with:

```text
| What needs `1.2` | [`gob:outline(color, width)`](api/look.md#outline-unprotected), the ring round an object. |
| What needs `1.3` | [`session:fight():buff()`](api/fight.md#the-fight-in-progress) and [`buff:opponent()`](api/buff.md), a fight's buffs and whose they are. Everything else these pages describe is in `1.0`. |
```

32. `docs/addons/manifest.md` — the load table
   Replace:

```text
| `"1.0"`, `"1.1"`, `"1.2"` | Its generation, an edition it has. | Loads. |
| `"1.3"` | An edition it has not got. | Out of date: `too new: needs API 1.3 or newer, this client implements 1.2`. |
| `"2.0"` | Another generation. | Out of date: `written for API 2.0, this client implements 1.2`. |
| Nothing | | Out of date: `declares no api_version, this client implements 1.2`. |
```

   with:

```text
| `"1.0"`, `"1.1"`, `"1.2"`, `"1.3"` | Its generation, an edition it has. | Loads. |
| `"1.4"` | An edition it has not got. | Out of date: `too new: needs API 1.4 or newer, this client implements 1.3`. |
| `"2.0"` | Another generation. | Out of date: `written for API 2.0, this client implements 1.3`. |
| Nothing | | Out of date: `declares no api_version, this client implements 1.3`. |
```

33. `docs/addons/manifest.md` — the out-of-date labels
   Replace:

```text
`outdated (API 2.0, client 1.2)`, or `outdated (no api_version, client 1.2)`
```

   with:

```text
`outdated (API 2.0, client 1.3)`, or `outdated (no api_version, client 1.3)`
```

34. `docs/client/combat.md` — the engine map of the fight, new
   Create the file with exactly this content:

```text
# The fight: Fightview, Fightsess and GiveButton

> What the client holds about a fight in progress: the relation to every opponent and its numbers, the buffs
> drawn beside you and beside each opponent, the row of combat actions and their cooldowns, and the messages
> that switch targets, pursue, give and use an action. The deck BUILDER is another widget, in the character
> sheet: [character-sheet.md](character-sheet.md).

## Where it lives

| What | Where |
|---|---|
| The combat view | `Fightview`, `@RName("frv")`, placed by `GameUI.addchild` `place == "fight"` into `urpanel` and held in `GameUI.fv`. ⚠️ **`GameUI.fv` is never cleared**: `GameUI.cdestroy` does not know the field, so a view that has left the tree is still referenced. Ask `fv.hasparent(ui.root)` before trusting it |
| The action row | `Fightsess`, `@RName("fsess")`, placed by `GameUI.addchild` `place == "fsess"` as a **direct child of the `GameUI`**, with no field: `gameui.getchild(Fightsess.class)` finds it. It sizes itself to its parent and draws over the map, centred on the player. The server puts one up for a fight and destroys it after |
| The relations | `Fightview.lsrel`, a `LinkedList<Relation>`. `Relation` is an inner class and **not a widget**: `gobid`, `gst`, `ip`, `oip`, `lastact`, `lastuse`, `invalid`, and two `Bufflist`s, `buffs` and `relbuffs`, whose field initialisers `add` them to the `Fightview` itself |
| The target | `Fightview.current`, set by `setcur` off `uimsg "cur"`. `null` out of a fight, and `null` after a `cur` naming an id `getrel` does not know |
| Your side | `Fightview.buffs` (your buffs in the fight), `Fightview.lastact`/`lastuse` (your last manoeuvre), `Fightview.atkcs`/`atkct` (the global cooldown) |
| Their side | per `Relation`: `buffs` (drawn beside them), `oip` (their IP), `lastact`/`lastuse` (their last manoeuvre) |
| The action slots | `Fightsess.actions`, a public `Action[]` sized by the server's `nact`. `Action{res, cs, ct}`: the manoeuvre and its cooldown's start and end in `Utils.rtime()` seconds |
| The give button | `GiveButton`, `@RName("give")`, with a 2-bit `state`. The relation boxes (`Relbox`, `Mainrel`) copy `rel.gst` into it on every draw |
| The keys | `Fightsess.kb_acts`: ten `KeyBinding`s `fgt/0`..`fgt/9`, labelled "Combat action 1".."Combat action 10" in the options (defaults `1`..`5` and Shift+`1`..`5`). `kb_relcycle`, `fgt-cycle`, is "Switch targets" (Ctrl+Tab; Shift walks the other way) |

## The messages

| Message | Widget | Direction | Arguments | Effect |
|---|---|---|---|---|
| `new` | `Fightview` | in | gob, gst, ip, oip | a new `Relation` goes to the **front** of `lsrel` |
| `del` | `Fightview` | in | gob | `Relation.remove()` destroys its two lists and sets `invalid`; out of `lsrel`, and the target is cleared if it was this one |
| `upd` | `Fightview` | in | gob, gst, ip, oip | `Relation.give(gst)`, `ip`, `oip` |
| `used` | `Fightview` | in | res or null | `Fightview.use`: your `lastact`, and `lastuse = Utils.rtime()` |
| `ruse` | `Fightview` | in | gob, res or null | that relation's `lastact` and `lastuse` |
| `cur` | `Fightview` | in | gob | moves that relation to the front of `lsrel` and `setcur`s it |
| `atkc` | `Fightview` | in | ticks | `atkcs = now`, `atkct = now + ticks * 0.06` |
| `blk`, `atk` | `Fightview` | in | res / res, res | stored in `blk`, `batk`, `iatk`, which nothing in the client reads |
| `act` | `Fightsess` | in | n, res? | `actions[n] = new Action(res)`, or `null` without a res |
| `acool` | `Fightsess` | in | n, ticks | `actions[n].cs = now`, `ct = now + ticks * 0.06` |
| `use` | `Fightsess` | in | n, nb? | the two highlighted frames, `use` and `useb`; nothing in the client says what either means |
| `used` | `Fightsess` | in | — | ignored there. The same name reaches `Fightview`, so a tap on the name alone must test the widget's class |
| `click` | `Fightview` | out | gob, button | a relation box's portrait (`Avaview`) |
| `give` | `Fightview` | out | gob, button | a relation box's `GiveButton`; `mousedown` sends the mouse button |
| `prs` | `Fightview` | out | gob | a relation box's Pursue button |
| `bump` | `Fightview` | out | gob | "Switch targets": `Fightsess.globtype` rotates `lsrel` locally, then bumps the new front |
| `use` | `Fightsess` | out | n, 1, modflags, [place] | a combat key. `place` is the map coordinate under the pointer, floored to `OCache.posres`, and only when the pick hits ground |
| `rel` | `Fightsess` | out | n | the key's release, posted through a `Release` fenced on the render |

## The buffs of a fight

| What | Where |
|---|---|
| Routing | `Fightview.addchild`: `("buff", null)` goes to `Fightview.buffs`, `("buff", gob)` to that relation's `buffs`, `("relbuff", gob)` to its `relbuffs`. Each is a plain `add`, so a buff is a direct child of its list |
| Painting | `Fightsess.draw` alone paints them: `fv.buffs` to the left of the player, `fv.current.buffs` to the right. Another relation's buffs are painted only while it is the target, and `relbuffs` are painted by nothing. The lists themselves are hidden widgets, so a buff's own `Widget.c` does not say where its icon is |
| ⚠️ A relation's end | `Relation.remove()` calls `buffs.destroy()` and `relbuffs.destroy()`. `Widget.destroy` runs `remove()` on the LIST only and `rdispose()` on the buffs, so every buff stays a child of a detached list, never runs `remove()`, and is not `dest`: `buff.hasparent(ui.root)` is the only test that goes false |
| ⚠️ `children(Class)` recurses | On the `Fightview` it reaches every relation's buffs. Read the lists one by one |

## Threading and gotchas

| What | Detail |
|---|---|
| The monitor | The `uimsg`s are applied on a loader thread under `synchronized(ui)`: `lsrel`, `current`, a relation's ints and `Fightsess.actions` are written there. "Switch targets" reorders `lsrel` on the UI thread. Read all of it under the tree's monitor |
| ⚠️ `getrel` throws | `Notfound` for an id not in `lsrel`, so a `del`, `upd` or `ruse` for an unknown gob aborts that message |
| ⚠️ Whose number is whose | `ip` is **yours** and `oip` theirs (`Fightsess` paints `IP: n` left and right), while `Relation.lastact` is **theirs**: the field names mix the two sides |
| ⚠️ The give bits | `GiveButton.draw` paints `state & 1` as the left half (`ol` open, `sl` shut) and `state & 2` as the right half (`or`/`sr`), tinted red at 0, blue at 1, green at 2. The left half is the side the fight view paints as yours |
| ⚠️ A key off the map | `Fightsess.globtype` sends no `use` while the pointer is outside the map view but still records the key as held, so a lone `rel` goes out on key-up |
| ⚠️ `acool` on an empty slot | throws a `NullPointerException` inside `Fightsess.uimsg`; the server never sends one |
| Ticks | `atkc` and `acool` count 0.06 s ticks |
```

35. `docs/client/services.md` — the fight has a page of its own now
   Replace:

```text
> Keybindings, resources, the live fight, buffs, the vitals bars, the speed selector, the belt, the
> action menu, minimap icons, crafting and equipment. Six subjects have a page of their own:
> [audio.md](audio.md), [character-sheet.md](character-sheet.md), [kin-window.md](kin-window.md),
> [chat.md](chat.md), [console.md](console.md) and [prefs-and-options.md](prefs-and-options.md).
```

   with:

```text
> Keybindings, resources, buffs, the vitals bars, the speed selector, the belt, the action menu,
> minimap icons, crafting and equipment. Seven subjects have a page of their own:
> [audio.md](audio.md), [character-sheet.md](character-sheet.md), [combat.md](combat.md),
> [kin-window.md](kin-window.md), [chat.md](chat.md), [console.md](console.md) and
> [prefs-and-options.md](prefs-and-options.md).
```

36. `docs/client/services.md` — the combat row moves to combat.md
   Delete the one line that starts with `| Combat — the live fight |`.

37. `docs/client/README.md` — the services row, and a row for the fight after it
   Replace:

```text
| [services](services.md) | keybindings, `Resource` and code adoption, the live fight, buffs, the vitals bars, the speed selector, the belt, the action menu, minimap icons, crafting, equipment |
```

   with:

```text
| [services](services.md) | keybindings, `Resource` and code adoption, buffs, the vitals bars, the speed selector, the belt, the action menu, minimap icons, crafting, equipment |
| [the fight](combat.md) | `Fightview`, `Fightsess` and `GiveButton`: the relations and their numbers, the buffs drawn beside each side, the action row and its cooldowns, every message in and out, and why `GameUI.fv` outlives its view |
```

38. `addons/170-live-fight.1/manifest.json` — the suite's manifest
   Create the file with exactly this content:

```json
{
  "name": "170.1 — the buffs a fight draws",
  "id": "170-live-fight.1",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 170.1: a fight's buffs fire the buff events once per edge with one object, are listed by session:fight():buff() and target:buff(), say whose with buff:opponent(), and are gone when the fight ends.",
  "api_version": "1.3",
  "files": ["main.lua"]
}
```

39. `addons/170-live-fight.1/main.lua` — the suite
   Create the file with exactly this content:

```lua
-- 170.1 — the buffs a fight draws. Self-checking suite.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local session, panel, sweep
local subs = {}
local seen, order = {}, {}   -- every BuffAdded payload of this character, in arrival order
local fought = {}            -- buff -> "yours" or "theirs", once a door of the fight listed it
local removedCount = {}      -- buff -> how many BuffRemoved it fired
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if sweep then pcall(function() sweep:cancel() end) end
  if panel then pcall(function() panel:destroy() end) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function listed(collection, buff)
  return collection:find(function(each) return each == buff end) ~= nil
end

-- Which door of the fight lists this buff right now: "yours", "theirs" or nil.
local function side(buff)
  local fight = session:fight()
  if listed(fight:buff(), buff) then return "yours" end
  local target = fight:target()
  if target and listed(target:buff(), buff) then return "theirs" end
  return nil
end

-- An opening can come and go between two looks, so every payload is classed while the fight is up.
local function classify()
  for _, buff in ipairs(order) do
    if not fought[buff] then fought[buff] = side(buff) end
  end
end

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("170.1"):size(460, 70)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function ended()
  classify()
  local total, once, gone = 0, 0, 0
  for _, buff in ipairs(order) do
    if fought[buff] then
      total = total + 1
      if removedCount[buff] == 1 then once = once + 1 end
      if not buff:exists() then gone = gone + 1 end
    end
  end
  check(total > 0 and once == total,
    "each of the " .. total .. " buffs of the fight fired BuffRemoved once, with the object BuffAdded handed",
    once .. " of " .. total)
  local left = session:fight():buff():count()
  check(total > 0 and gone == total and left == 0,
    "each now answers :exists() false, and session:fight():buff() is empty",
    gone .. " of " .. total .. " gone, " .. left .. " listed")
  finish()
end

local function fighting()
  classify()
  local target = session:fight():target()
  local yours, theirs, onBar, named, strays = 0, 0, 0, 0, 0
  local sample
  for _, buff in ipairs(order) do
    local where = fought[buff]
    if where == "yours" then yours = yours + 1 elseif where == "theirs" then theirs = theirs + 1 end
    if where and listed(session:buff(), buff) then onBar = onBar + 1 end
    if where == "yours" and buff:opponent() == nil then named = named + 1 end
    if where == "theirs" and target ~= nil and buff:opponent() == target then named = named + 1 end
    if not where and buff:exists() and not listed(session:buff(), buff) then strays = strays + 1 end
    if where and not sample then sample = buff end
  end
  local total = yours + theirs
  check(total > 0, "buffs of the fight arrived by BuffAdded: " .. yours .. " yours, " .. theirs .. " the animal's",
    "none -- press Done once icons show beside you or the animal")
  check(total > 0 and onBar == 0, "none of them is on session:buff(), the bar", onBar .. " on the bar")
  check(total > 0 and named == total, "buff:opponent() is the target for the animal's and nil for yours",
    named .. " of " .. total)
  check(strays == 0, "no BuffAdded payload stands outside every door: the bar, the fight, the target",
    strays .. " outside")
  local surplus = sample and refusal(function() return sample:res(1) end)
  check(surplus ~= nil and surplus:find("takes no arguments", 1, true) ~= nil,
    "a buff refuses a surplus argument, naming the verb", surplus)
  manualCheck("count the icons drawn beside your own character in the fight view now",
    session:fight():buff():count() .. ", the number session:fight():buff() lists")
  prompt("End the fight (win it or walk away), then press Done.", ended, function()
    check(false, "the end of the fight was reached", "skipped")
    finish()
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  seen, order, fought, removedCount, subs = {}, {}, {}, {}, {}
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  check(true, "this suite declares api_version 1.3 and runs")
  local door = session:fight():buff()
  local noGet = refusal(function() return door:get(1) end)
  local surplus = refusal(function() return session:fight():buff(1) end)
  check(door == session:fight():buff() and noGet ~= nil and noGet:find("has no key", 1, true) ~= nil
      and surplus ~= nil and surplus:find("takes no arguments", 1, true) ~= nil,
    "session:fight():buff() is one object, and :get and an argument are refused naming why",
    tostring(noGet) .. " / " .. tostring(surplus))
  subs[#subs + 1] = hafen.event():on("BuffAdded", function(buff, where)
    if where == session and not seen[buff] then
      seen[buff] = true
      order[#order + 1] = buff
      fought[buff] = side(buff)
    end
  end)
  subs[#subs + 1] = hafen.event():on("BuffRemoved", function(buff)
    removedCount[buff] = (removedCount[buff] or 0) + 1
  end)
  sweep = hafen.timer():every(0.5, guarded(classify))
  prompt("Attack ONE chicken or rabbit. Press Done once icons show beside you or it.", fighting, function()
    check(false, "a fight with an animal was reached", "skipped")
    finish()
  end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)
```

---

- [x] **170.2 — The fight lists every opponent, the target as its current one, their numbers, and four events.**

`session:fight():opponent()` is the collection of every relation of the combat view (`Fightview.lsrel`, in its
own order), addressed by gob id, with `:current()` the target; `session:fight():target()` is gone, cut with no
alias (the maintainer's ruling). An opponent gains `:ip()` and `:give()` (both `{mine, theirs}`), a widened
`:info()`, and every verb refusing a surplus argument. `CharApi.FightAdapter` diffs the relations once per frame
and fires `OpponentAdded`, `OpponentRemoved`, `OpponentChanged` and `OpponentSelected` through the new
`AddonManager.fireOpponent`. The fight gets its own event page, `event/bus/fight.md`, and `fight.md` is rewritten
whole around the fight in progress.

*Its suite* (no keys) checks, out of a fight: one collection object, and `:get("x")`, a string filter,
`:get(1.5)`, `:current(1)` and `session:fight():target()` all refused — the last naming `:opponent()`; no
opponent and no `:current()`. It records every opponent event with what the payload read at fire time. In the
fight: `OpponentAdded` came before `OpponentSelected` for the target, both with this session last; the target is
`:current()`, `:get(its id)` and the events' payload, one object; `:ip()` is whole numbers, `:give()` booleans,
`:info()` the same; each `OpponentChanged` moved something; a surplus argument is refused. After it:
`OpponentRemoved` once per opponent with the same object and `:exists()` false inside the handler; the reads
`nil`, `:opening()` empty, `:info()` `{ id }`, the collection empty.

`[manual]`: read the two IP numbers the fight view paints beside you and beside the animal — expect the pair the
suite printed, yours on the left (they may have moved since).

**The passing log** (11 lines):

```text
[pass] session:fight():opponent() is one object, and :get("x"), a string filter, :get(1.5), :current(1) and :target() are refused
[pass] out of a fight there is no opponent and no :current()
[pass] OpponentAdded came before OpponentSelected for the target, each with this session last
[pass] the target is :current(), :get(its gob id) and the payload those events handed: one object
[pass] ip() is two whole numbers, give() two booleans, and info() carries the same
[pass] OpponentChanged fired <n> times, each for an IP or give that moved
[pass] an opponent refuses a surplus argument, naming the verb
[manual] read the two IP numbers the fight view paints now, beside you and beside the animal -- expect: yours <n>, the animal's <n> (they may have moved since)
[pass] OpponentRemoved came once for each opponent added, with the same object and :exists() false in the handler
[pass] after the fight: :ip() and :give() are nil, :opening() is empty, :info() is { id } and no opponent is left
[summary] 9 pass, 0 fail, 1 manual
```

If the manual line reads the other way round (yours on the right), `Relation.ip` is theirs: stop and report it —
the page and the pair name it on that fact.

**The edits, in order**

1. `src/io/brodgar/addon/LuaOpponent.java` — the whole file: the collection, `current`, the new reads, every verb counting its arguments
   Replace the whole file with exactly this content:

```java
package io.brodgar.addon;

import haven.Fightview;
import haven.GameUI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * An <b>Opponent object</b> — one creature a character is fighting: a relation its combat view keeps
 * ({@code Fightview.Relation}), reached through {@code s:fight():opponent()}, the collection of every one of them,
 * whose {@code :current()} is the one the view has picked (170.2).
 *
 * <p><b>The creature is its gob, the relation is this object.</b> Everything about the creature (name, health,
 * position) is read off {@code opponent:gob()}, which is never {@code nil}: an id the object cache does not hold
 * answers a Gob whose {@code :exists()} is false. What the view holds about the RELATION is read here: the IP
 * pair it paints ({@code :ip()}), the two halves of the give button ({@code :give()}) and the openings drawn
 * beside the creature ({@code :opening()}).
 *
 * <p><b>The intern key is the gob id</b> (§2.4). The combat view mints a fresh record whenever a fight starts, so
 * keying on the record would call the same creature two opponents across two fights. <b>And a gob id counts
 * inside one session's object cache</b> (077.4), which is why the account is half the handle: two characters
 * fighting are two fights, each with its own view and its own ids, and id 4711 in one of them is not the
 * creature id 4711 names in the other. Two levels of intern map, on {@code (account, id)}: the {@link LuaGob}
 * shape, and what makes {@code opponent:gob()} resolve in the same cache {@code s:world():gob():get(id)} reads.
 *
 * <p><b>Once the fight with them ends</b> the relation's reads go {@code nil}, {@code :opening()} is empty and
 * {@code :exists()} is false, while {@code :id()} and {@code :gob()} go on answering. A later fight with the
 * same creature hands back the same object.
 *
 * <p><b>Threading.</b> The combat view's records are added, removed and rewritten from a loader thread under the
 * UI monitor, and "Switch targets" reorders them on the UI thread, so every read copies what it needs inside
 * {@link LuaWidget#monitor} and mints its handles outside it.
 */
public final class LuaOpponent {
    /** The account whose fight this is — half the address, and the cache the id resolves in. */
    public final String user;
    /** The opponent's gob id, in that session's own object cache. */
    public final long gobid;

    /**
     * The two halves of the give state {@code Relation.gst}, as {@code GiveButton.draw} paints them: bit 1 the
     * left half, the side the combat view paints as yours, and bit 2 the right.
     */
    static final int MINE = 1, THEIRS = 2;

    private LuaOpponent(String user, long gobid) {
        this.user = user;
        this.gobid = gobid;
    }

    /** {@code tostring(opp)}: {@code Opponent(<gobid>)}. */
    public String toString() {
        return "Opponent(" + gobid + ")";
    }

    /** An interned Opponent object for {@code gobid} <b>in {@code user}'s fight</b>, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, long gobid) {
        return owner.opponents.of(user, gobid);
    }

    /** The {@code LuaOpponent} behind a Lua value, or {@code null} for anything else. */
    static LuaOpponent resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaOpponent) ? (LuaOpponent)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Opponent cache and metatable (its {@link Addon#opponents}), keyed by the <b>account plus</b>
     * the gob id: an id is one session's object cache's, so the same number in two fights is two creatures.
     * Two levels of map, the {@link LuaGob} shape.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Long, Ref>> live = new HashMap<String, Map<Long, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String user, long gobid) {
            drain();
            Map<Long, Ref> byid = live.get(user);
            if(byid == null)
                live.put(user, byid = new HashMap<Long, Ref>());
            Long key = Long.valueOf(gobid);
            Ref r = byid.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byid.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaOpponent(user, gobid), meta());
            byid.put(key, new Ref(v, user, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref or = (Ref)r;
                Map<Long, Ref> byid = live.get(or.user);
                if(byid == null)
                    continue;
                if(byid.get(or.key) == or)     // not already replaced by a fresh handle for the same id
                    byid.remove(or.key);
                if(byid.isEmpty())
                    live.remove(or.user);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final String user;
        final Long key;

        Ref(LuaValue v, String user, Long key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the Opponent metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("opponent", methods(owner),
            "someone you are fighting"));
        mt.set("__name", LuaValue.valueOf("Opponent"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaOpponent h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Opponent(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // 170.2: every verb counts its arguments (Args.only), where a OneArgFunction dropped a surplus one unseen.
        // id() — the opponent's gob id, the only thing the server publishes about them.
        m.set("id", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf((double)handle(Args.only(a, 0, "opponent:id"), "id").gobid);
            }
        });
        // gob() — the creature itself. NEVER nil: an id the object cache does not hold answers a Gob whose
        // :exists() is false, exactly as s:world():gob():get(id) does. Resolved in the object cache of the
        // session whose fight this is (077.4), which is the cache the id came out of.
        m.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:gob"), "gob");
                return LuaGob.of(owner, h.user, h.gobid);
            }
        });
        // exists() — is that character still in a fight with them?
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:exists"), "exists");
                return LuaValue.valueOf(fighting(h.user, h.gobid));
            }
        });
        // ip() — 170.2: the IP pair the view paints, {mine, theirs}. Upstream's Relation.ip is yours (painted on
        // the left) and Relation.oip theirs. nil once the fight with them has ended.
        m.set("ip", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:ip"), "ip");
                int[] n = numbers(h.user, h.gobid);
                return (n == null) ? LuaValue.NIL : ip(n);
            }
        });
        // give() — 170.2: the give button's two halves, {mine, theirs}, as booleans. nil once the fight with them
        // has ended.
        m.set("give", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:give"), "give");
                int[] n = numbers(h.user, h.gobid);
                return (n == null) ? LuaValue.NIL : give(n);
            }
        });
        // opening() — 170.1: that opponent's openings, the list the combat view paints beside them. A view minted
        // per call; empty once the fight with them has ended.
        m.set("opening", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:opening"), "opening");
                return LuaBuff.opponentCollection(owner, h.user, h.gobid);
            }
        });
        // info() — the one SNAPSHOT escape hatch: {id, ip, give} while the fight lasts, {id} after it.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:info"), "info");
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf((double)h.gobid));
                int[] n = numbers(h.user, h.gobid);
                if(n != null) {
                    t.set("ip", ip(n));
                    t.set("give", give(n));
                }
                return t;
            }
        });
        return m;
    }

    private static LuaOpponent handle(LuaValue self, String method) {
        LuaOpponent h = resolve(self);
        if(h == null)
            throw new LuaError("opponent:" + method + "() — use a COLON call on an Opponent object"
                + " (" + CharApi.FO + ":current(), " + CharApi.FO + ":list()[n])");
        return h;
    }

    /** {@code {mine = m, theirs = t}}: a relation's two sides, the shape shapes.md names. */
    private static LuaTable pair(LuaValue mine, LuaValue theirs) {
        LuaTable t = new LuaTable();
        t.set("mine", mine);
        t.set("theirs", theirs);
        return t;
    }

    /** The IP pair out of {@link #numbers}. */
    private static LuaTable ip(int[] n) {
        return pair(LuaValue.valueOf(n[0]), LuaValue.valueOf(n[1]));
    }

    /** The give state out of {@link #numbers}, as its two halves. */
    private static LuaTable give(int[] n) {
        return pair(LuaValue.valueOf((n[2] & MINE) != 0), LuaValue.valueOf((n[2] & THEIRS) != 0));
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /**
     * <b>That character's</b> combat view, or {@code null} before its HUD is up. {@code GameUI.fv} is never
     * cleared, so a view that has left the tree answers {@code null} too (170.1).
     */
    static Fightview view(String user) {
        GameUI g = AddonManager.gameui(user);
        Fightview fv = (g == null) ? null : g.fv;
        return ((fv == null) || (fv.ui == null) || !fv.hasparent(fv.ui.root)) ? null : fv;
    }

    /** Is {@code user} still in a fight with {@code gobid}? The predicate {@code :exists()} answers. */
    static boolean fighting(String user, long gobid) {
        Fightview fv = view(user);
        if(fv == null)
            return false;
        synchronized(LuaWidget.monitor(fv)) {   // lsrel is added to / removed from on a loader thread
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.gobid == gobid) && !rel.invalid)
                    return true;
            }
        }
        return false;
    }

    /**
     * {@code {ip, oip, gst}} of the relation with {@code gobid}, copied under the view's monitor, or {@code null}
     * once the fight with them has ended. The three ints are written from the {@code new}/{@code upd} uimsgs.
     */
    static int[] numbers(String user, long gobid) {
        Fightview fv = view(user);
        if(fv == null)
            return null;
        synchronized(LuaWidget.monitor(fv)) {
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.gobid == gobid) && !rel.invalid)
                    return new int[] {rel.ip, rel.oip, rel.gst};
            }
        }
        return null;
    }

    /** The gob ids of the live relations, in the view's own order, copied under its monitor. */
    static List<Long> ids(String user) {
        List<Long> out = new ArrayList<Long>();
        Fightview fv = view(user);
        if(fv == null)
            return out;
        synchronized(LuaWidget.monitor(fv)) {
            for(Fightview.Relation rel : fv.lsrel) {
                if(!rel.invalid)
                    out.add(Long.valueOf(rel.gobid));
            }
        }
        return out;
    }

    /**
     * {@code s:fight():opponent():current()} — the opponent <b>that character's</b> combat view has picked, or
     * {@code NIL} out of a fight.
     */
    static LuaValue current(Addon owner, String user) {
        Fightview fv = view(user);
        if(fv == null)
            return LuaValue.NIL;
        boolean live;
        Fightview.Relation rel;
        synchronized(LuaWidget.monitor(fv)) {   // `current` is reassigned from the "cur" uimsg off-thread
            rel = fv.current;
            // audit2 B06: and `invalid` is read HERE, inside the same block. Relation.remove() sets it from
            // the message thread under this very monitor, so a read taken after the block was a read of a
            // field with no barrier behind it -- an opponent invalidated in that window came back as live.
            live = (rel != null) && !rel.invalid;
        }
        return live ? of(owner, user, rel.gobid) : LuaValue.NIL;
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code s:fight():opponent()} (170.2) — every opponent THAT character's combat view holds, in the view's own
     * order ({@code Fightview.lsrel}: a new relation joins at the front, the target is moved to the front, and
     * "Switch targets" rotates it). Addressed by gob id, the one thing the server publishes about an opponent; a
     * string filter is refused, since an opponent has no name of its own. {@code :current()} is the
     * distinguished member, the one the view has picked (§2.2). Minted once per (addon, session) by
     * {@code CharApi.fight}.
     */
    static LuaValue collection(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        extra.set("current", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), CharApi.FO, "current");
                if(Args.passed(a, 2))
                    throw new LuaError(CharApi.FO + ":current() takes no argument — it reads the opponent that"
                        + " character's fight has picked");
                return current(owner, user);
            }
        });
        return LuaCollection.create(CharApi.FO, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<Long> ids = ids(user);
                List<LuaValue> out = new ArrayList<LuaValue>(ids.size());
                for(int i = 0; i < ids.size(); i++)
                    out.add(of(owner, user, ids.get(i).longValue()));
                return out;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                long id = Args.integer(key, CharApi.FO + ":get", "gobId", "a GOB ID — an opponent has no name"
                                       + " of its own (opponent:gob():name() is the creature's)",
                                       -Args.EXACT, Args.EXACT);
                return fighting(user, id) ? of(owner, user, id) : LuaValue.NIL;
            }

            /** An opponent is addressed by gob id: it has no name of its own. */
            public String keyName() {
                return "gobId";
            }
        }, extra);
    }
}
```

2. `src/io/brodgar/addon/CharApi.java` — the imports (one)
   Replace:

```java
import haven.FightWnd;

```

   with:

```java
import haven.FightWnd;
import haven.Fightview;

```

3. `src/io/brodgar/addon/CharApi.java` — the imports (two)
   Replace:

```java
import java.util.ArrayList;
import java.util.Collections;

```

   with:

```java
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

```

4. `src/io/brodgar/addon/CharApi.java` — the imports (three)
   Replace:

```java
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;

```

   with:

```java
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;

```

5. `src/io/brodgar/addon/CharApi.java` — the class javadoc stops counting the adapters
   Replace:

```java
 * <p><b>The adapters are one session's</b> (073.3). Each of the nine caches the HUD widgets it has seen, as

```

   with:

```java
 * <p><b>The adapters are one session's</b> (073.3). Each caches the HUD widgets it has seen, as

```

6. `src/io/brodgar/addon/CharApi.java` — the collection's spelling, beside `FT`
   Replace:

```java
    static final String FT = "session:fight()";

```

   with:

```java
    static final String FT = "session:fight()";
    /** {@code s:fight():opponent()} — every opponent of the fight in progress (170.2). */
    static final String FO = "session:fight():opponent()";

```

7. `src/io/brodgar/addon/CharApi.java` — `newAdapters` javadoc
   Replace:

```java
     * <b>The nine change-detection adapters, for one session</b> (073.3) — built when that session's

```

   with:

```java
     * <b>The change-detection adapters, for one session</b> (073.3) — built when that session's

```

8. `src/io/brodgar/addon/CharApi.java` — `newAdapters` builds the fight's adapter too
   Replace:

```java
        List<TreeAdapter> l = new ArrayList<TreeAdapter>(9);

```

   with:

```java
        List<TreeAdapter> l = new ArrayList<TreeAdapter>(10);

```

9. `src/io/brodgar/addon/CharApi.java` — …and adds it last
   Replace:

```java
        l.add(new WoundAdapter(st));
        return Collections.unmodifiableList(l);

```

   with:

```java
        l.add(new WoundAdapter(st));
        l.add(new FightAdapter(st));
        return Collections.unmodifiableList(l);

```

10. `src/io/brodgar/addon/CharApi.java` — `SessionAdapter` javadoc names the combat view
   Replace:

```java
     * <b>The seven readers that reach a HUD by ACCOUNT</b> (092.4, A-089) — the base the state travels on.
     *
     * <p>The other two ({@code MeterAdapter}, {@code BuffsAdapter}) cache the widgets themselves and read
     * through them, so they were addressed already. These seven look their subject up: the character sheet,
     * the belt, the equipory, the roster, the quest log, the wound list. Each of those lookups took

```

   with:

```java
     * <b>The readers that reach a HUD by ACCOUNT</b> (092.4, A-089) — the base the state travels on.
     *
     * <p>The other two ({@code MeterAdapter}, {@code BuffsAdapter}) cache the widgets themselves and read
     * through them, so they were addressed already. These look their subject up: the character sheet, the
     * belt, the equipory, the roster, the quest log, the wound list, the combat view. Each of those lookups took

```

11. `src/io/brodgar/addon/CharApi.java` — the fight adapter, new, before `FepAdapter`
   Replace:

```java
    /**
     * FEP + hunger — the {@link BAttrWnd} (character-sheet "Base Attributes" tab). Located directly via

```

   with:

```java
    /**
     * The fight in progress (170.2) — the relations of THAT character's combat view ({@code Fightview.lsrel}),
     * announced on four edges: {@code OpponentAdded} when a gob id joins the list, {@code OpponentRemoved} when
     * one leaves it or the view leaves the tree, {@code OpponentChanged} when a present opponent's IP pair or give
     * state moves, {@code OpponentSelected} when the target becomes another opponent. Losing the target fires
     * nothing, as for {@code SessionSelected}.
     *
     * <p><b>uimsg-driven.</b> Every change arrives as a {@code Fightview} message ({@code new}, {@code del},
     * {@code upd}, {@code cur}), so {@link #interested} flags those and {@link #refresh} re-reads the whole list
     * once per frame under the view's monitor and diffs it against what was last ANNOUNCED: the one-frame diff
     * contract of {@link TreeAdapter}. "Switch targets" reorders {@code lsrel} with no message, so an order change
     * is not an edge. The server also sends {@code used} to the {@code Fightsess}, so the class is tested.
     *
     * <p>Within one refresh the order is Added, Removed, Changed, Selected: for any one opponent its {@code new}
     * comes before its {@code del}, and a lost target is announced gone before the next one is announced picked.
     */
    private static final class FightAdapter extends SessionAdapter {
        FightAdapter(SessionState st) {
            super(st);
        }

        // gob id -> {ip, oip, gst} as last ANNOUNCED, in announcement order. UI-thread-only; built with its
        // session's state (073.3). Keyed by the gob id, never by a widget: the view outlives every relation.
        private final LinkedHashMap<Long, int[]> announced = new LinkedHashMap<Long, int[]>();
        private long selected = -1;       // the gob id OpponentSelected last named, -1 for none
        private Fightview view;           // the view last read, which is how the removal seam knows it

        public boolean interested(Widget w, String msg) {
            return (w instanceof Fightview)
                && ("new".equals(msg) || "del".equals(msg) || "upd".equals(msg) || "cur".equals(msg));
        }

        public void refresh() {
            sync();
        }

        public void removed(Widget w) {
            if((w != null) && (w == view))
                sync();
        }

        /** Re-read the view and fire the difference, Added, Removed, Changed, Selected. */
        private void sync() {
            String user = user();
            Fightview fv = LuaOpponent.view(user);   // null once it has left the tree
            view = fv;
            LinkedHashMap<Long, int[]> now = new LinkedHashMap<Long, int[]>();
            long current = -1;
            if(fv != null) {
                synchronized(LuaWidget.monitor(fv)) {
                    for(Fightview.Relation rel : fv.lsrel) {
                        if(!rel.invalid)
                            now.put(Long.valueOf(rel.gobid), new int[] {rel.ip, rel.oip, rel.gst});
                    }
                    Fightview.Relation cur = fv.current;
                    if((cur != null) && !cur.invalid)
                        current = cur.gobid;
                }
            }
            for(Map.Entry<Long, int[]> e : now.entrySet()) {
                if(!announced.containsKey(e.getKey())) {
                    announced.put(e.getKey(), e.getValue());
                    fireOpponent("OpponentAdded", user, e.getKey().longValue());
                }
            }
            for(Iterator<Map.Entry<Long, int[]>> i = announced.entrySet().iterator(); i.hasNext();) {
                Map.Entry<Long, int[]> e = i.next();
                if(!now.containsKey(e.getKey())) {
                    i.remove();
                    fireOpponent("OpponentRemoved", user, e.getKey().longValue());
                }
            }
            for(Map.Entry<Long, int[]> e : announced.entrySet()) {
                int[] is = now.get(e.getKey());
                if(!Arrays.equals(e.getValue(), is)) {
                    e.setValue(is);
                    fireOpponent("OpponentChanged", user, e.getKey().longValue());
                }
            }
            if(current != selected) {
                selected = current;
                if(current >= 0)
                    fireOpponent("OpponentSelected", user, current);
            }
        }
    }

    /**
     * FEP + hunger — the {@link BAttrWnd} (character-sheet "Base Attributes" tab). Located directly via

```

12. `src/io/brodgar/addon/CharApi.java` — the fight section's javadoc
   Replace:

```java
     * fight it is in</b>, reached as {@code s:fight()} (077.4). Three projections of that character's
     * combat-schools tab plus one read of its live combat view: {@code :maneuver()} is the collection of what
     * it knows, {@code :deck()} the loaded school's layout as a plain array (§2.3 — a layout is addressed by
     * its own order), {@code :summary()} the scalars around it, and {@code :target()} who it is fighting.

```

   with:

```java
     * fight it is in</b>, reached as {@code s:fight()} (077.4). Three projections of that character's
     * combat-schools tab: {@code :maneuver()} is the collection of what it knows, {@code :deck()} the loaded
     * school's layout as a plain array (§2.3 — a layout is addressed by its own order), {@code :summary()} the
     * scalars around it. And the fight in progress, off its live combat view (170): {@code :opponent()} every
     * opponent with the target as {@code :current()}, {@code :opening()} the buffs drawn beside that character.

```

13. `src/io/brodgar/addon/CharApi.java` — mint the opponents beside the fight's buffs
   Replace:

```java
        final LuaValue fightBuffs = LuaBuff.fightCollection(owner, user);

```

   with:

```java
        final LuaValue fightBuffs = LuaBuff.fightCollection(owner, user);
        final LuaValue opponents = LuaOpponent.collection(owner, user);

```

14. `src/io/brodgar/addon/CharApi.java` — `target()` goes, `opponent()` takes its place
   Replace:

```java
        // target() — who THAT character is fighting, nil out of combat. An Opponent, whose :gob() is the
        // creature, resolved in the session the fight is in.
        fight.set("target", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "target", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":target() takes no arguments — there is one opponent picked,"
                        + " and target:gob() is the creature it names");
                return LuaOpponent.target(owner, user);
            }
        });

```

   with:

```java
        // opponent() — 170.2: every opponent THAT character is fighting, minted once and handed back by
        // identity; :current() is the one its fight has picked. It replaces target(), which is gone.
        fight.set("opponent", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "opponent", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":opponent() takes no arguments — it IS the collection of the"
                        + " opponents: :get(gobId) addresses one and :current() is the target");
                return opponents;
            }
        });

```

15. `src/io/brodgar/addon/AddonManager.java` — the four keys join the bus
   Replace:

```java
        "FlowerMenuAdded", "FlowerMenuRemoved",

```

   with:

```java
        "FlowerMenuAdded", "FlowerMenuRemoved",
        "OpponentAdded", "OpponentRemoved", "OpponentChanged", "OpponentSelected",

```

16. `src/io/brodgar/addon/AddonManager.java` — a near miss names the four
   Replace:

```java
        else if(key.toLowerCase().contains("sdt"))
            hint = " — the key is GobSdtChanged";

```

   with:

```java
        else if(key.toLowerCase().contains("sdt"))
            hint = " — the key is GobSdtChanged";
        else if(key.toLowerCase().startsWith("opponent"))
            hint = " — the fight's opponent keys are OpponentAdded, OpponentRemoved, OpponentChanged and"
                + " OpponentSelected";

```

17. `src/io/brodgar/addon/AddonManager.java` — `fireOpponent`, after `fireSlot`
   Replace:

```java
        if((c != null) && hasSub(c, "ActionbarChanged"))
            fireTo(c, "ActionbarChanged", LuaSlot.of(c, user, index), sessionArg(c, user));
    }

```

   with:

```java
        if((c != null) && hasSub(c, "ActionbarChanged"))
            fireTo(c, "ActionbarChanged", LuaSlot.of(c, user, index), sessionArg(c, user));
    }

    /**
     * Fire an opponent event ({@code OpponentAdded}/{@code OpponentRemoved}/{@code OpponentChanged}/
     * {@code OpponentSelected}, 170.2) whose payload is the interned <b>Opponent</b> for {@code gobid} in
     * {@code user}'s fight. Same shape as {@link #fireSlot}: interning is per addon, so the payload is minted for
     * each owner that subscribes and for nobody else. Change <i>detection</i> is {@code CharApi}'s fight adapter.
     */
    static void fireOpponent(String event, String user, long gobid) {
        for(Addon a : addons) {
            if(hasSub(a, event))
                fireTo(a, event, LuaOpponent.of(a, user, gobid), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaOpponent.of(c, user, gobid), sessionArg(c, user));
    }

```

18. `tools/docverbs.py` — the pages call an opponent `opponent`
   Replace:

```python
    "target": "opponent", "grab": "grab",
```

   with:

```python
    "opponent": "opponent", "target": "opponent", "grab": "grab",
```

19. `tools/refusalverbs.py` — the opponents hand out opponents
   Replace:

```python
    "session:fight():opening()": "buff", "opponent:opening()": "buff",

```

   with:

```python
    "session:fight():opening()": "buff", "opponent:opening()": "buff", "session:fight():opponent()": "opponent",

```

20. `tools/refusalverbs.py` — …and `:current()` is one of them
   Replace:

```python
    ("session:speed()", "current"): "speed",

```

   with:

```python
    ("session:speed()", "current"): "speed",
    ("session:fight():opponent()", "current"): "opponent",

```

21. `docs/addons/api/fight.md` — the whole page: the target becomes the opponents
   Replace the whole file with exactly this content:

````text
# session:fight: Combat Schools and the Fight in Progress

One character's manoeuvre-deck builder (its Martial Arts and Combat Schools tab) and the fight it is in, reached through its [session](session.md). It answers what the character knows, what its loaded school has dealt to each hotkey and what that costs, and, while it fights, every opponent, the numbers the fight paints between you and the buffs drawn beside each side.

```lua
local session = hafen.session():current()                      -- the character on screen
local target = session:fight():opponent():current()            -- nil out of a fight
if target then
  local ip = target:ip()                                       -- { mine = …, theirs = … }
  hafen.log():write("IP " .. ip.mine .. " against " .. ip.theirs .. " on " .. target:id())
end
hafen.event():on("OpponentAdded", function(opponent, fight_session)
  hafen.log():write(fight_session:character() .. " is fighting " .. opponent:id())
end)
```

---

| Rule | Detail |
|---|---|
| One character's | Every character configures its own deck against its own budget and fights its own fight: `hafen.session():get("alt"):fight():opponent():current()` answers about the alt. A hotkey slot and an opponent's gob id count inside one character, so a `DeckCard` and an `Opponent` carry their character beside their key. |
| `opponent:gob()` answers in the login asked through | That character's own world, where the id came from: `opponent:gob():exists()` is that character's line of sight, not the screen's. |
| Before the tab has built, and out of a fight | `:maneuver():list()` and `:deck():list()` are empty and `:summary()` is `nil` until the tab has built. Out of a fight, `:opponent()` and `:opening()` are empty and `:opponent():current()` is `nil`. |
| Unprotected, no write side | Nothing throws. The opponents fire [the fight events](event/bus/fight.md), a fight's buffs the [opening events](event/bus/character.md#character-and-status), and the schools are read on demand. |

## Read

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:fight():maneuver():list(filter)` | `Maneuver[]` | Unprotected | Every manoeuvre and attack that character knows. |
| `session:fight():maneuver():count(filter)` | `number` | Unprotected | How many match. |
| `session:fight():maneuver():find(filter)` | `Maneuver \| nil` | Unprotected | The first that matches. |
| `session:fight():deck()` | collection | Unprotected | The loaded school's layout, the filled hotkey slots: `:list(filter)`, `:count(filter)`, `:find(filter)`. No `:get`. |
| `session:fight():summary()` | `FightSummary \| nil` | Unprotected | The action-point budget and the saved-school slots. |

| Rule | Detail |
|---|---|
| `filter` | A string [filter](conventions.md#the-filter-argument) matches the resource name and the display name. On the deck it matches the manoeuvre in the slot, so one needle finds the same manoeuvre through either door. |
| No `:get` | A manoeuvre is addressed by nothing you hold: a string is a search, a position is `:list()[n]`. `#deck()`, `deck()[n]` and `ipairs(deck())` are [refused](conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many). `deck():list()` is the array. |
| Empty slots are left out | `card:index()` is the position in that list (`deck():list()[n]:index()` is `n` whatever the gaps). `card:wire()` is the hotkey's place in the school, gaps counted. |

## A manoeuvre

| Method | Returns | Permission | Description |
|---|---|---|---|
| `maneuver:res()` | `string \| nil` | Unprotected | The resource name, its identity. |
| `maneuver:name()` | `string \| nil` | Unprotected | The display name. |
| `maneuver:dealable()` | `number` | Unprotected | How many copies that character may deal into a deck. |
| `maneuver:used()` | `number` | Unprotected | How many the loaded school has dealt. |
| `maneuver:exists()` | `boolean` | Unprotected | Whether that character still knows it. Always answers. |
| `maneuver:info()` | [`Maneuver`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

## A deck card

A card is a place in the layout, not the manoeuvre in it. It keeps answering `:wire()` and `:key()` when the hotkey is emptied, while the manoeuvre half goes `nil` and `:exists()` goes `false`.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `card:index()` | `number \| nil` | Unprotected | Its 1-based position in `:deck():list()`. `nil` for an emptied slot, which that list leaves out. |
| `card:wire()` | `number` | Unprotected | The raw 0-based deck index the write path takes. Always answers. |
| `card:key()` | `string \| nil` | Unprotected | The hotkey label the window paints. `nil` for a slot past the labels the window has, since the window paints a fixed set. |
| `card:maneuver()` | `Maneuver \| nil` | Unprotected | The manoeuvre dealt here. |
| `card:res()` | `string \| nil` | Unprotected | That manoeuvre's resource name. |
| `card:name()` | `string \| nil` | Unprotected | That manoeuvre's display name. |
| `card:used()` | `number \| nil` | Unprotected | How many copies the deck holds. |
| `card:exists()` | `boolean` | Unprotected | Whether the slot is filled. Always answers. |
| `card:info()` | [`DeckCard`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

## The summary

| Method | Returns | Permission | Description |
|---|---|---|---|
| `summary:used()` | `number \| nil` | Unprotected | Action points the loaded school spends: the total the window paints beside the cap, the sum of `maneuver:used()` over every known manoeuvre. |
| `summary:maxActions()` | `number \| nil` | Unprotected | The action-point budget. |
| `summary:deckSize()` | `number \| nil` | Unprotected | How many hotkey slots the deck has. |
| `summary:saveCount()` | `number \| nil` | Unprotected | How many saved-school slots that character keeps. |
| `summary:activeSave()` | `number \| nil` | Unprotected | Which of them is loaded, 0-based. |
| `summary:exists()` | `boolean` | Unprotected | Whether that character's tab is still up. |
| `summary:info()` | [`FightSummary`](types/fight.md#maneuver-deckcard-fightsummary) `\| nil` | Unprotected | A plain-table snapshot. |

[`session:study():summary()`](study.md#the-summary) is a summary of the same kind. It is a live object with its own `:exists()` and `:info()`, interned on its window, `nil` while the window is not up.

## The fight in progress

| Method | Returns | Permission | Description |
|---|---|---|---|
| `session:fight():opponent()` | collection | Unprotected | Every opponent that character is fighting, in the order the fight keeps: `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(gobId)`. |
| `session:fight():opponent():current()` | `Opponent \| nil` | Unprotected | The target: the opponent the fight has picked. `nil` out of a fight. |
| `session:fight():opening()` | collection | Unprotected | Your buffs in the fight: the row of icons the client paints over the map, to the left of that character. Not the buff bar. [Buff](buff.md) objects, `:list(filter)`, `:count(filter)`, `:find(filter)`. No `:get`. |

| Rule | Detail |
|---|---|
| One object | `session:fight():opponent()` and `session:fight():opening()` are the same object every call. Both are empty out of a fight. |
| `:get(gobId)` | Takes a gob id, the one thing the server publishes about an opponent. A miss is `nil`. A string [filter](conventions.md#the-filter-argument) is refused naming the function form: an opponent has no name of its own, and the creature's is `opponent:gob():name()`. |
| The order | The fight's own: a new opponent joins at the front and the target is moved to the front, so `:list()[1]` is usually the target but not always. The client's "Switch targets" key reorders the list without a message, and no event says so. |
| `:current()` | The distinguished member, compared with `==`: `session:fight():opponent():current() == opponent`. `:current(x)` raises: it reads. |
| Only while the client draws them | The icon rows exist while the fight lasts. A debuff of yours that outlasts the fight is no longer drawn once it is over: it is not listed, `buff:exists()` is `false`, and `OpeningRemoved` fires for it when the fight ends. If it is still there when the next fight starts, it is drawn again and fires `OpeningAdded` again. |
| The same objects as the events | A buff of a fight fires [`OpeningAdded`, `OpeningChanged` and `OpeningRemoved`](event/bus/character.md#character-and-status), and the doors here hand the same interned `Buff`. When the fight with an opponent ends, each of its buffs fires `OpeningRemoved` once. |
| Whose it is | Each opening event hands the buff, then the opponent it is drawn beside, then the session: `nil` where the buff is yours. `OpeningRemoved` still names the opponent after the fight with them has ended, when [`buff:opponent()`](buff.md) answers `nil`. |
| Not on the bar | [`session:buff()`](buff.md) is the buff bar alone, and `BuffAdded`, `BuffChanged` and `BuffRemoved` are its events. A buff of a fight never fires them. |

```lua
hafen.event():on("OpeningAdded", function(buff, opponent, session)
  local whose = opponent and ("on opponent " .. opponent:id()) or "on you"
  hafen.log():write((buff:name() or buff:res()) .. " " .. whose)
end)
```

## An opponent

| Method | Returns | Permission | Description |
|---|---|---|---|
| `opponent:id()` | `number` | Unprotected | The creature's gob id. Always answers. |
| `opponent:gob()` | [Gob](gob.md) | Unprotected | The creature, in the login whose fight this is. Never `nil`. |
| `opponent:exists()` | `boolean` | Unprotected | Whether that character is still fighting them. Always answers. |
| `opponent:ip()` | [`{mine=, theirs=}`](shapes.md#the-anonymous-shapes) `\| nil` | Unprotected | The initiative the fight paints between you, as whole numbers: `mine` beside your character, `theirs` beside them. |
| `opponent:give()` | [`{mine=, theirs=}`](shapes.md#the-anonymous-shapes) `\| nil` | Unprotected | The give button beside their portrait, as its two halves: `mine` the left, `theirs` the right. Booleans. |
| `opponent:opening()` | collection | Unprotected | Their openings: the row of icons the client paints over the map, to the right of your character while they are the target. [Buff](buff.md) objects, a view minted per call. |
| `opponent:info()` | [`Opponent`](types/fight.md#opponent) | Unprotected | A plain-table snapshot. |

| Rule | Detail |
|---|---|
| The creature is the gob | Name, health and position belong to the gob. What the fight holds about the relation is read here. |
| Once the fight with them ends | `:ip()` and `:give()` read `nil`, `:opening()` is empty, `:exists()` is `false` and `:info()` is `{ id }`. `:id()` and `:gob()` go on answering. |
| `opponent:gob()` is never `nil` | Like [`session:world():gob():get(id)`](gob.md): ask `opponent:gob():exists()`. |
| Identity | Interned on character and gob id: `session:fight():opponent():get(id) == session:fight():opponent():get(id)` and `seen[opponent] = true` work. A later fight with the same creature hands back the same object. |
| Events | [`OpponentAdded`, `OpponentRemoved`, `OpponentChanged` and `OpponentSelected`](event/bus/fight.md) hand this object. |

---

## See Also

- [`hafen.session`](session.md) — the address every read here is reached through.
- [Gob](gob.md) — what `opponent:gob()` hands back, and every read on it.
- [The fight](types/fight.md) — the snapshot shapes `:info()` returns.
- [The fight events](event/bus/fight.md) — an opponent coming and going, its numbers moving, the target changing.
- [`session:buff`](buff.md) — the buff bar, and the `Buff` a fight's buffs are too.
- [`session:actionbar`](actionbar.md) — the other hotkey surface, which is writable.
- [`session:char`](char.md) — the skills that unlock manoeuvres.
````

22. `docs/addons/api/event/bus/fight.md` — the fight's event page, new
   Create the file with exactly this content:

````text
# hafen.event: The Fight

What one character's fight in progress reports: an opponent joining it and leaving it, the numbers between you moving, and the target changing. Every key hands the thing it is about and that character's [`Session`](../../session.md) last. Part of [the catalogue](README.md).

```lua
hafen.event():on("OpponentSelected", function(opponent, fight_session)
  local ip = opponent:ip()
  hafen.log():write(fight_session:character() .. " targets " .. opponent:id()
    .. (ip and (" at IP " .. ip.mine) or ""))
end)
```

---

| Event | Payload | Fires |
|---|---|---|
| `OpponentAdded` | [`Opponent`](../../fight.md#an-opponent) | The character starts fighting a creature. |
| `OpponentRemoved` | [`Opponent`](../../fight.md#an-opponent) | The fight with it ends. The object still answers `:id()` and `:gob()`, and `:exists()` is false. |
| `OpponentChanged` | [`Opponent`](../../fight.md#an-opponent) | Its IP pair or its give state changes. |
| `OpponentSelected` | [`Opponent`](../../fight.md#an-opponent) | It becomes the target, `session:fight():opponent():current()`. |

| Rule | Detail |
|---|---|
| Diff, one frame | The client re-reads the fight once per frame after the server's message, as on [the character page](character.md), so a value that changes and comes back inside one frame fires nothing. |
| The order in one frame | Added, then Removed, then Changed, then Selected. A lost target is announced gone before the next one is announced picked. |
| Losing the target | Fires nothing. `OpponentSelected` fires when another opponent becomes the target, and `OpponentRemoved` says the fight with the last one ended. |
| Not an event | The list's order: the client's "Switch targets" key reorders it without a message. |
| The fight's buffs | Fire the [opening keys](character.md#character-and-status), whose second argument names whose. |
| A session ending | Fires none of these: the fight goes with the session. |

---

## See Also

- [`session:fight`](../../fight.md) — the opponents, their numbers, and the buffs of a fight.
- [The character and the rosters](character.md) — the opening events a fight's buffs fire.
- [`hafen.event()`](../README.md) — subscribing, and why the key set is closed.
````

23. `docs/addons/api/event/bus/README.md` — the page table
   Replace:

```text
| [The chat](chat.md) | A channel appearing, going away or taking the tab, and a line landing in one. |
```

   with:

```text
| [The chat](chat.md) | A channel appearing, going away or taking the tab, and a line landing in one. |
| [The fight](fight.md) | An opponent joining a fight or leaving it, the numbers between you moving, the target changing. |
```

24. `docs/addons/api/event/bus/README.md` — whose character it was
   Replace:

```text
Most events are one character's (meters, buffs, food, study slots, equipment, action bar, wounds, roster, quests, radial menu, chat channels and lines).
```

   with:

```text
Most events are one character's (meters, buffs, food, study slots, equipment, action bar, wounds, roster, quests, radial menu, chat channels and lines, the fight).
```

25. `docs/addons/api/types/fight.md` — the intro
   Replace:

```text
The snapshot shapes off the manoeuvre-deck builder: one manoeuvre, one card in the deck, and the deck's totals.
```

   with:

```text
The snapshot shapes off the manoeuvre-deck builder and the fight in progress: one manoeuvre, one card in the deck, the deck's totals, and an opponent.
```

26. `docs/addons/api/types/fight.md` — an opponent has a shape of its own now
   Replace:

```text
The combat target has no shape of its own: `target:info()` is `{ id }`, and everything else about the creature is read off its [Gob](../gob.md).
```

   with:

```text
## Opponent

From [`opponent:info()`](../fight.md#an-opponent). `{ id = number, ip = { mine = number, theirs = number }?, give = { mine = boolean, theirs = boolean }? }`. `ip` and `give` are absent once the fight with that opponent has ended, and the snapshot is then `{ id }`. Everything about the creature itself is read off its [Gob](../gob.md).
```

27. `docs/addons/api/types/README.md` — the page row
   Replace:

```text
| [The fight](fight.md) | A manoeuvre, a card in the deck, the deck's totals. |
```

   with:

```text
| [The fight](fight.md) | A manoeuvre, a card in the deck, the deck's totals, an opponent. |
```

28. `docs/addons/api/types/README.md` — every shape
   Replace:

```text
| `Pagina` | [The widget layer](ui.md#pagina) |
```

   with:

```text
| `Opponent` | [The fight](fight.md#opponent) |
| `Pagina` | [The widget layer](ui.md#pagina) |
```

29. `docs/addons/api/README.md` — the snapshot pages
   Replace:

```text
| [The fight](types/fight.md) | A manoeuvre, a card in the deck, the deck's totals. |
```

   with:

```text
| [The fight](types/fight.md) | A manoeuvre, a card in the deck, the deck's totals, an opponent. |
```

30. `docs/addons/api/README.md` — the event families
   Replace:

```text
| [The chat](event/bus/chat.md) | A channel appearing, going away or taking the tab, and a line landing in one. |
```

   with:

```text
| [The chat](event/bus/chat.md) | A channel appearing, going away or taking the tab, and a line landing in one. |
| [The fight](event/bus/fight.md) | An opponent joining a fight or leaving it, the numbers between you moving, the target changing. |
```

31. `docs/addons/api/README.md` — the section row
   Replace:

```text
| [`session:fight`](fight.md) | The manoeuvre-deck builder, and who the character is fighting. |
```

   with:

```text
| [`session:fight`](fight.md) | The manoeuvre-deck builder, and the fight in progress: every opponent, the numbers between you, the buffs drawn beside each side. |
```

32. `docs/addons/api/session.md` — the section row
   Replace:

```text
| [`session:fight()`](fight.md) | Its combat schools, its manoeuvre deck, who it is fighting. |
```

   with:

```text
| [`session:fight()`](fight.md) | Its combat schools and manoeuvre deck, and the fight it is in. |
```

33. `docs/addons/api/gob.md` — where a gob comes from
   Replace:

```text
`target:gob()` on the [combat](fight.md) target
```

   with:

```text
`opponent:gob()` on a [combat](fight.md#an-opponent) opponent
```

34. `docs/addons/api/party.md` — See Also
   Replace:

```text
- [`session:fight`](fight.md) — combat, whose target resolves its gob the same way.
```

   with:

```text
- [`session:fight`](fight.md) — combat, whose opponents resolve their gob the same way.
```

35. `docs/addons/api/shapes.md` — a relation's two sides
   Replace:

```text
| `{cur=, max=}` | A pair of counts. | `item:durability()`, `contents:fill()`. |
```

   with:

```text
| `{cur=, max=}` | A pair of counts. | `item:durability()`, `contents:fill()`. |
| `{mine=, theirs=}` | A relation's two sides: yours and the opponent's. | `opponent:ip()`, `opponent:give()`. |
```

36. `docs/addons/api/conventions.md` — the picked edge
   Replace:

```text
| It was picked | `Selected` | `SessionSelected`, `ChannelSelected`. |
```

   with:

```text
| It was picked | `Selected` | `SessionSelected`, `ChannelSelected`, `OpponentSelected`. |
```

37. `docs/addons/manifest.md` — what needs `1.3`
   Replace:

```text
| What needs `1.3` | [`session:fight():opening()`](api/fight.md#the-fight-in-progress), [`buff:opponent()`](api/buff.md) and the [`OpeningAdded`, `OpeningRemoved` and `OpeningChanged`](api/event/bus/character.md#character-and-status) events: a fight's buffs and whose they are. Everything else these pages describe is in `1.0`. |
```

   with:

```text
| What needs `1.3` | The fight in progress on [`session:fight()`](api/fight.md#the-fight-in-progress) and its [`Opening` events](api/event/bus/character.md#character-and-status): its opponents and their numbers, its buffs and [`buff:opponent()`](api/buff.md), and [the fight events](api/event/bus/fight.md). Everything else these pages describe is in `1.0`. |
```

38. `docs/addons/api/buff.md` — the opponent's door by its name
   Replace:

```text
[`session:fight():opening()`](fight.md#the-fight-in-progress) and an opponent's `:opening()` list the same objects
```

   with:

```text
[`session:fight():opening()`](fight.md#the-fight-in-progress) and [`opponent:opening()`](fight.md#an-opponent) list the same objects
```

39. `docs/addons/api/buff.md` — the opponent row's link
   Replace:

```text
| `buff:opponent()` | [`Opponent`](fight.md) `\| nil` |
```

   with:

```text
| `buff:opponent()` | [`Opponent`](fight.md#an-opponent) `\| nil` |
```

40. `addons/170-live-fight.2/manifest.json` — the suite's manifest
   Create the file with exactly this content:

```json
{
  "name": "170.2 — the opponents",
  "id": "170-live-fight.2",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 170.2: session:fight():opponent() lists every opponent with the target as :current(), an opponent reads its IP and give pairs, the four Opponent events fire on their edges, and session:fight():target() is gone.",
  "api_version": "1.3",
  "files": ["main.lua"]
}
```

41. `addons/170-live-fight.2/main.lua` — the suite
   Create the file with exactly this content:

```lua
-- 170.2 — the opponents. Self-checking suite.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local session, panel
local subs = {}
local log = {}   -- every opponent event, in order, with what the payload read at fire time
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if panel then pcall(function() panel:destroy() end) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("170.2"):size(460, 70)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function pairText(value)
  if value == nil then return "-" end
  return tostring(value.mine) .. "/" .. tostring(value.theirs)
end

local function record(key)
  return function(opponent, where)
    log[#log + 1] = { key = key, opponent = opponent, session = where, ip = pairText(opponent:ip()),
                      give = pairText(opponent:give()), exists = opponent:exists() }
  end
end

local function whole(value)
  return type(value) == "number" and value >= 0 and value == math.floor(value)
end

local function ended()
  local added, removed, strangers, alive = {}, {}, 0, 0
  for _, entry in ipairs(log) do
    if entry.key == "OpponentAdded" then added[entry.opponent] = true end
  end
  for _, entry in ipairs(log) do
    if entry.key == "OpponentRemoved" then
      removed[entry.opponent] = (removed[entry.opponent] or 0) + 1
      if not added[entry.opponent] then strangers = strangers + 1 end
      if entry.exists then alive = alive + 1 end
    end
  end
  local total, once, sample = 0, 0, nil
  for opponent in pairs(added) do
    total = total + 1
    if removed[opponent] == 1 then once = once + 1 end
    sample = sample or opponent
  end
  check(total > 0 and once == total and strangers == 0 and alive == 0,
    "OpponentRemoved came once for each opponent added, with the same object and :exists() false in the handler",
    once .. " of " .. total .. ", " .. strangers .. " unknown, " .. alive .. " alive")
  local opponents = session:fight():opponent()
  local info = sample and sample:info()
  check(sample ~= nil and sample:ip() == nil and sample:give() == nil and sample:opening():count() == 0
      and info.ip == nil and info.id == sample:id() and opponents:current() == nil and opponents:count() == 0,
    "after the fight: :ip() and :give() are nil, :opening() is empty, :info() is { id } and no opponent is left",
    sample and (pairText(sample:ip()) .. " " .. opponents:count() .. " left"))
  finish()
end

local function fighting()
  local opponents = session:fight():opponent()
  local target = opponents:current()
  local addedAt, selectedAt, sessions = nil, nil, 0
  for i, entry in ipairs(log) do
    if entry.session ~= session then sessions = sessions + 1 end
    if entry.opponent == target then
      if entry.key == "OpponentAdded" and not addedAt then addedAt = i end
      if entry.key == "OpponentSelected" and not selectedAt then selectedAt = i end
    end
  end
  check(target ~= nil and addedAt ~= nil and selectedAt ~= nil and addedAt < selectedAt and sessions == 0,
    "OpponentAdded came before OpponentSelected for the target, each with this session last",
    tostring(addedAt) .. " / " .. tostring(selectedAt) .. ", " .. sessions .. " with another session")
  local id = target and target:id()
  check(target ~= nil and opponents:get(id) == target
      and opponents:find(function(each) return each == target end) == target,
    "the target is :current(), :get(its gob id) and the payload those events handed: one object", target)
  local ip, give, info = target and target:ip(), target and target:give(), target and target:info()
  check(ip ~= nil and whole(ip.mine) and whole(ip.theirs) and give ~= nil and type(give.mine) == "boolean"
      and type(give.theirs) == "boolean" and info.id == id and info.ip.mine == ip.mine
      and info.give.theirs == give.theirs,
    "ip() is two whole numbers, give() two booleans, and info() carries the same", pairText(ip) .. " " .. pairText(give))
  local changes, repeats, last = 0, 0, {}
  for _, entry in ipairs(log) do
    if entry.key == "OpponentChanged" then
      changes = changes + 1
      local previous = last[entry.opponent]
      if previous and previous.ip == entry.ip and previous.give == entry.give then repeats = repeats + 1 end
    end
    last[entry.opponent] = entry
  end
  check(changes > 0 and repeats == 0, "OpponentChanged fired " .. changes .. " times, each for an IP or give that moved",
    changes .. " fired, " .. repeats .. " with nothing moved -- let a few blows land")
  local surplus = target and refusal(function() return target:ip(1) end)
  check(surplus ~= nil and surplus:find("takes no arguments", 1, true) ~= nil,
    "an opponent refuses a surplus argument, naming the verb", surplus)
  manualCheck("read the two IP numbers the fight view paints now, beside you and beside the animal",
    "yours " .. (ip and ip.mine or "?") .. ", the animal's " .. (ip and ip.theirs or "?") .. " (they may have moved since)")
  prompt("End the fight (win it or walk away), then press Done.", ended, function()
    check(false, "the end of the fight was reached", "skipped")
    finish()
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  log, subs = {}, {}
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  local opponents = session:fight():opponent()
  local refused = {
    refusal(function() return opponents:get("x") end),
    refusal(function() return opponents:list("x") end),
    refusal(function() return opponents:get(1.5) end),
    refusal(function() return opponents:current(1) end),
    refusal(function() return session:fight():target() end),
  }
  local named = 0
  for index = 1, 5 do if refused[index] then named = named + 1 end end
  check(opponents == session:fight():opponent() and named == 5 and refused[5]:find(":opponent()", 1, true) ~= nil,
    "session:fight():opponent() is one object, and :get(\"x\"), a string filter, :get(1.5), :current(1) and :target()"
      .. " are refused", named .. " of 5 refused: " .. tostring(refused[5]))
  check(opponents:count() == 0 and opponents:current() == nil, "out of a fight there is no opponent and no :current()",
    opponents:count())
  for _, key in ipairs({ "OpponentAdded", "OpponentRemoved", "OpponentChanged", "OpponentSelected" }) do
    subs[#subs + 1] = hafen.event():on(key, record(key))
  end
  prompt("Attack ONE chicken or rabbit, let a few blows land, and press Done while you fight.", fighting, function()
    check(false, "a fight with an animal was reached", "skipped")
    finish()
  end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)
```

---

- [x] **170.3 — The combat row, the cooldowns and the manoeuvres used are read and announced.**

New `LuaCombatAction`: `session:fight():action()` is the ten places of the fight's row, one per combat key
(`Fightsess.actions`, found as the HUD's `Fightsess` child), each a `CombatAction` with `:index()`, `:wire()`,
`:empty()`, `:res()`, `:name()`, `:maneuver()`, `:cooldown()` and `:info()`. New on the section:
`session:fight():cooldown()` (the global cooldown) and `session:fight():last()` (your last manoeuvre); new on an
opponent: `:last()`, and `last` in `:info()`. `FightAdapter` also fires `ManeuverUsed(res, opponent | nil)` from
the `lastuse` stamps, once per use; the new `CombatActionAdapter` fires `CombatActionChanged` when a place is set,
cleared or its name resolves, and as the row comes and goes, never on a cooldown.

*Its suite* (no keys) checks, out of a fight: one row object of ten actions, `:get(3)` is `:list()[3]`, index 3,
wire 2; every action empty and reading `nil`, and both section reads `nil`; `:get(0)`, `:get(11)`, `:get("1")` and
a surplus argument refused, the first two naming `action:wire()` and `1..10`. In the fight: every filled action
has a resource and a name and was announced as `:get(its index)` with this session last; `:maneuver()` is the
entry `session:fight():maneuver()` finds; `:find(res)` and `:find(name)` reach it; both cooldowns within 0..1.
After two presses of key 1: `ManeuverUsed` fired for each with the same resource and `nil` as the opponent, and
`:last()` names it; a cooldown ran and no `CombatActionChanged` fired for a place whose manoeuvre stayed; a
`ManeuverUsed` named the animal and its `:last()` agrees. After the fight: all ten empty, each filled one
announced cleared.

`[manual]`: look at the first icon of the row drawn under your character — expect the manoeuvre the suite names.

**The passing log** (13 lines):

```text
[pass] session:fight():action() is one object of ten actions, and :get(3) is :list()[3], index 3, wire 2
[pass] out of a fight every action is :empty() and reads nil, and fight:cooldown() and fight:last() are nil
[pass] :get(0) names action:wire(), :get(11) names 1..10, and :get("1") and a surplus argument are refused
[pass] <n> actions are filled, each with a resource and a name, and CombatActionChanged announced each as :get(its index) with this session last
[pass] a filled action's :maneuver() is the entry session:fight():maneuver() finds by the same resource
[pass] :find(res) and :find(name) reach a filled action
[pass] both cooldowns read within 0..1, and :info() carries the same resource
[manual] look at the first icon of the row drawn under your character -- expect: the manoeuvre <name>
[pass] ManeuverUsed fired for each of your uses, the same resource each time, nil as the opponent, and :last() is it
[pass] a cooldown ran (the highest seen was <n>%) and no CombatActionChanged fired for a place whose manoeuvre stayed
[pass] a ManeuverUsed named the animal as the opponent, and its :last() is that resource
[pass] every action is :empty() again, and CombatActionChanged said so for each that was filled
[summary] 11 pass, 0 fail, 1 manual
```

The hand steps attack a chicken, which fights back: an animal that never strikes leaves the third check of the
second step with nothing to read.

**The edits, in order**

1. `src/io/brodgar/addon/LuaCombatAction.java` — new: the combat row and one place of it
   Create the file with exactly this content:

```java
package io.brodgar.addon;

import haven.FightWnd;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Indir;
import haven.Resource;
import haven.Utils;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A <b>CombatAction object</b> — one place of a character's combat row ({@code s:fight():action()}, 170.3): the
 * slot the client's key "Combat action n" presses. The row is the fight's own ({@code Fightsess.actions}, filled
 * by the server's {@code act} messages while a fight is up), not the deck the schools tab lays out: an edit in
 * the tab is the tab's until it is saved, and the key sends the row's slot number.
 *
 * <p><b>Ten places, always.</b> The client has ten combat keys ({@code Fightsess.kb_acts}), and a slot no key
 * reaches is one no player can use, so the row is those ten whatever the server's own row holds. Out of a fight
 * (no {@code Fightsess} in that character's HUD) every place is {@code :empty()}, and so is a place past the
 * server's row. The length is a literal rather than {@code kb_acts.length}: reading that field would run
 * {@code Fightsess}'s static initializer (textures and a {@code loadwait}) on whatever thread asked first.
 *
 * <p><b>The intern key is the place</b> (account, 0-based slot), the {@link LuaDeckCard} shape: a stashed action
 * follows the key rather than the manoeuvre in it, and the account is half the handle because every character
 * has its own row. Every read re-resolves the row through that character's HUD, copying the slot under the
 * row's monitor, where the loader thread writes {@code act} and {@code acool}.
 */
public final class LuaCombatAction {
    /** The places of the row: the client's own combat keys, "Combat action 1".."Combat action 10". */
    static final int SLOTS = 10;

    /** The account whose row this place is in. */
    public final String user;
    /** The 0-based slot, what {@code action:wire()} answers and the {@code use} message carries. */
    public final int slot;

    private LuaCombatAction(String user, int slot) {
        this.user = user;
        this.slot = slot;
    }

    /** {@code tostring(action)}: {@code CombatAction(<1-based position>)}. */
    public String toString() {
        return "CombatAction(" + (slot + 1) + ")";
    }

    /** An interned CombatAction object for {@code slot} of {@code user}'s row, in {@code owner}'s env. */
    static LuaValue of(Addon owner, String user, int slot) {
        return owner.combatActions.of(user, slot);
    }

    /** The {@code LuaCombatAction} behind a Lua value, or {@code null} for anything else. */
    static LuaCombatAction resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaCombatAction) ? (LuaCombatAction)o : null;
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /** One addon's CombatAction cache and metatable (its {@link Addon#combatActions}), keyed by account and slot. */
    static final class Cache {
        private final Addon owner;
        private final Map<String, Map<Integer, Ref>> live = new HashMap<String, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        synchronized LuaValue of(String user, int slot) {
            drain();
            Map<Integer, Ref> bys = live.get(user);
            if(bys == null)
                live.put(user, bys = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(slot);
            Ref r = bys.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                bys.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaCombatAction(user, slot), meta());
            bys.put(key, new Ref(v, user, key, dead));
            return v;
        }

        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref ar = (Ref)r;
                Map<Integer, Ref> bys = live.get(ar.user);
                if(bys == null)
                    continue;
                if(bys.get(ar.key) == ar)     // not already replaced by a fresh handle for the same place
                    bys.remove(ar.key);
                if(bys.isEmpty())
                    live.remove(ar.user);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    private static final class Ref extends WeakReference<LuaValue> {
        final String user;
        final Integer key;

        Ref(LuaValue v, String user, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.user = user;
            this.key = key;
        }
    }

    // ---- the CombatAction metatable -----------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("action", methods(owner),
            "one place of a character's combat row, the slot a combat key presses"));
        mt.set("__name", LuaValue.valueOf("CombatAction"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaCombatAction h = resolve(self);
                return LuaValue.valueOf((h == null) ? "CombatAction(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // index() — the 1-based position, the number :get(n) takes and "Combat action n" names. Always answers.
        m.set("index", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "action:index"), "index").slot + 1);
            }
        });
        // wire() — the server's 0-based slot number, what the row's messages carry. Always answers.
        m.set("wire", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                return LuaValue.valueOf(handle(Args.only(a, 0, "action:wire"), "wire").slot);
            }
        });
        // empty() — does the place hold nothing? True out of a fight and past the server's row.
        m.set("empty", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:empty"), "empty");
                return LuaValue.valueOf(held(h.user, h.slot) == null);
            }
        });
        // res() — the manoeuvre's resource name, its identity; nil for an empty place or while it resolves.
        m.set("res", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:res"), "res");
                Held s = held(h.user, h.slot);
                String r = (s == null) ? null : AddonManager.resIdent(s.res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // name() — the manoeuvre's display name, the tooltip the row paints; nil for an empty place.
        m.set("name", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:name"), "name");
                Held s = held(h.user, h.slot);
                String n = (s == null) ? null : AddonManager.resTipName(s.res, null);
                return (n == null) ? LuaValue.NIL : LuaValue.valueOf(n);
            }
        });
        // maneuver() — the entry of s:fight():maneuver() with the same resource, nil when the character's list
        // has none (or the place is empty).
        m.set("maneuver", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:maneuver"), "maneuver");
                Held s = held(h.user, h.slot);
                String r = (s == null) ? null : AddonManager.resIdent(s.res);
                if(r == null)
                    return LuaValue.NIL;
                for(FightWnd.Action act : LuaManeuver.actions(h.user)) {
                    if(r.equals(AddonManager.resIdent(act.res)))
                        return LuaManeuver.of(owner, h.user, act);
                }
                return LuaValue.NIL;
            }
        });
        // cooldown() — the 0..1 fraction of this action's cooldown still to run, 0 when it is ready; nil for an
        // empty place. The dark pie the row paints over the icon.
        m.set("cooldown", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:cooldown"), "cooldown");
                Held s = held(h.user, h.slot);
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(left(s.cs, s.ct));
            }
        });
        // info() — the one SNAPSHOT escape hatch: {res, name, cooldown}, nil for an empty place (the Slot shape).
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCombatAction h = handle(Args.only(a, 0, "action:info"), "info");
                Held s = held(h.user, h.slot);
                if(s == null)
                    return LuaValue.NIL;
                LuaTable t = new LuaTable();
                String r = AddonManager.resIdent(s.res), n = AddonManager.resTipName(s.res, null);
                if(r != null)
                    t.set("res", LuaValue.valueOf(r));
                if(n != null)
                    t.set("name", LuaValue.valueOf(n));
                t.set("cooldown", LuaValue.valueOf(left(s.cs, s.ct)));
                return t;
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaCombatAction handle(LuaValue self, String method) {
        LuaCombatAction h = resolve(self);
        if(h == null)
            throw new LuaError("action:" + method + "() — use a COLON call on a CombatAction object ("
                + CharApi.FA + ":get(n), " + CharApi.FA + ":list()[n])");
        return h;
    }

    // ---- the reads ----------------------------------------------------------------------------------

    /** One place's content, copied under the row's monitor. */
    private static final class Held {
        final Indir<Resource> res;
        final double cs, ct;

        Held(Indir<Resource> res, double cs, double ct) {
            this.res = res;
            this.cs = cs;
            this.ct = ct;
        }
    }

    /**
     * <b>That character's</b> combat row, or {@code null} out of a fight: the {@code Fightsess} the server puts
     * up for a fight is a direct child of the HUD with no field of its own, so it is found by class under the
     * HUD's monitor.
     */
    static Fightsess row(String user) {
        GameUI g = AddonManager.gameui(user);
        if(g == null)
            return null;
        synchronized(LuaWidget.monitor(g)) {
            return g.getchild(Fightsess.class);
        }
    }

    /** What place {@code slot} of that character's row holds, or {@code null} for an empty place or no row. */
    private static Held held(String user, int slot) {
        Fightsess fs = row(user);
        if(fs == null)
            return null;
        synchronized(LuaWidget.monitor(fs)) {
            if((slot < 0) || (slot >= fs.actions.length) || (fs.actions[slot] == null))
                return null;
            Fightsess.Action act = fs.actions[slot];
            return new Held(act.res, act.cs, act.ct);
        }
    }

    /**
     * The ten places' manoeuvres, copied under the row's monitor: {@code null} for an empty place, and all
     * {@code null} for no row. What {@code CharApi}'s combat-row adapter diffs.
     */
    static List<Indir<Resource>> contents(Fightsess fs) {
        List<Indir<Resource>> out = new ArrayList<Indir<Resource>>(SLOTS);
        for(int n = 0; n < SLOTS; n++)
            out.add(null);
        if(fs == null)
            return out;
        synchronized(LuaWidget.monitor(fs)) {
            for(int n = 0; (n < SLOTS) && (n < fs.actions.length); n++)
                out.set(n, (fs.actions[n] == null) ? null : fs.actions[n].res);
        }
        return out;
    }

    /** How much of a cooldown from {@code cs} to {@code ct} is left now, as a {@code 0..1} fraction. */
    static double left(double cs, double ct) {
        double now = Utils.rtime();
        if((now >= ct) || (ct <= cs))
            return 0;
        return Math.min(1, (ct - now) / (ct - cs));
    }

    /** {@code s:fight():cooldown()} — the global cooldown left, {@code NIL} out of a fight. */
    static LuaValue globalCooldown(String user) {
        Fightview fv = LuaOpponent.view(user);
        if((fv == null) || (row(user) == null))
            return LuaValue.NIL;
        double cs, ct;
        synchronized(LuaWidget.monitor(fv)) {   // atkcs/atkct are written from the "atkc" uimsg off-thread
            cs = fv.atkcs;
            ct = fv.atkct;
        }
        return LuaValue.valueOf(left(cs, ct));
    }

    /** {@code s:fight():last()} — the resource name of the manoeuvre that character used last, {@code NIL} out of a fight. */
    static LuaValue lastOwn(String user) {
        Fightview fv = LuaOpponent.view(user);
        if((fv == null) || (row(user) == null))
            return LuaValue.NIL;
        Indir<Resource> res;
        synchronized(LuaWidget.monitor(fv)) {   // lastact is written from the "used" uimsg off-thread
            res = fv.lastact;
        }
        String r = (res == null) ? null : AddonManager.resIdent(res);
        return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
    }

    // ---- the collection -----------------------------------------------------------------------------

    /**
     * {@code s:fight():action()} — the ten places of THAT character's combat row, in key order, every one of them
     * a CombatAction whatever it holds (the action bar's shape). {@code :get(n)} takes the 1-based position; 0 is
     * refused naming {@code action:wire()}, and a position past ten naming the range. A string filter matches the
     * manoeuvre's resource and display name, the needle {@code s:fight():maneuver()} matches too. Minted once
     * per (addon, session) by {@code CharApi.fight}.
     */
    static LuaValue collection(final Addon owner, final String user) {
        return LuaCollection.create(CharApi.FA, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>(SLOTS);
                for(int n = 0; n < SLOTS; n++)
                    out.add(of(owner, user, n));
                return out;
            }

            // An EMPTY place has no resource: it matches no string filter rather than refusing the filter.
            public String needle(LuaValue member) {
                LuaCombatAction h = resolve(member);
                Held s = (h == null) ? null : held(h.user, h.slot);
                if(s == null)
                    return "";
                String r = AddonManager.resIdent(s.res), n = AddonManager.resTipName(s.res, null);
                return ((r == null) ? "" : r) + "\n" + ((n == null) ? "" : n);
            }

            /** These have a name, so a string filter is a substring test over {@link #needle}. */
            public boolean named() {
                return true;
            }

            public boolean addressable() {
                return true;
            }

            public LuaValue getMember(LuaValue key) {
                int n = Args.integer(key, CharApi.FA + ":get", "n", "the 1-based position action:index()"
                                     + " answers, 1.." + SLOTS + ": :get(1) is Combat action 1");
                if(n == 0)
                    throw new LuaError(CharApi.FA + ":get(n): the key is the 1-based position action:index()"
                        + " answers, so :get(1) is Combat action 1; the server's 0-based slot number is"
                        + " action:wire()");
                if((n < 1) || (n > SLOTS))
                    throw new LuaError(CharApi.FA + ":get(n): position out of range (1.." + SLOTS + "), got " + n
                        + " — the row is the client's " + SLOTS + " combat keys");
                return of(owner, user, n - 1);
            }

            /** The row is the client's keys: every position in range is a place, holding something or not. */
            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }

            /** The key is the 1-based position {@code action:index()} answers. */
            public String keyName() {
                return "n";
            }
        }, null);
    }
}
```

2. `src/io/brodgar/addon/Addon.java` — the combat row's cache, beside the opponents'
   Replace:

```java
    final LuaOpponent.Cache opponents = new LuaOpponent.Cache(this);

```

   with:

```java
    final LuaOpponent.Cache opponents = new LuaOpponent.Cache(this);
    // 170.3: the combat row, keyed like deckCards by the account plus the place.
    final LuaCombatAction.Cache combatActions = new LuaCombatAction.Cache(this);

```

3. `src/io/brodgar/addon/LuaOpponent.java` — the imports
   Replace:

```java
import haven.Fightview;
import haven.GameUI;

```

   with:

```java
import haven.Fightview;
import haven.GameUI;
import haven.Indir;
import haven.Resource;

```

4. `src/io/brodgar/addon/LuaOpponent.java` — the class javadoc names `:last()`
   Replace:

```java
 * pair it paints ({@code :ip()}), the two halves of the give button ({@code :give()}) and the openings drawn
 * beside the creature ({@code :opening()}).

```

   with:

```java
 * pair it paints ({@code :ip()}), the two halves of the give button ({@code :give()}), the manoeuvre they
 * used last ({@code :last()}) and the openings drawn beside the creature ({@code :opening()}).

```

5. `src/io/brodgar/addon/LuaOpponent.java` — `opponent:last()`, before `buff`
   Replace:

```java
        // opening() — 170.1: that opponent's openings, the list the combat view paints beside them. A view minted

```

   with:

```java
        // last() — 170.3: the resource name of the manoeuvre they used last (Relation.lastact, from the "ruse"
        // uimsg). A string rather than a Maneuver: theirs need not be one this character knows. nil before their
        // first, and once the fight with them has ended.
        m.set("last", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:last"), "last");
                Indir<Resource> res = lastact(h.user, h.gobid);
                String r = (res == null) ? null : AddonManager.resIdent(res);
                return (r == null) ? LuaValue.NIL : LuaValue.valueOf(r);
            }
        });
        // opening() — 170.1: that opponent's openings, the list the combat view paints beside them. A view minted

```

6. `src/io/brodgar/addon/LuaOpponent.java` — `info()` carries `last`
   Replace:

```java
        // info() — the one SNAPSHOT escape hatch: {id, ip, give} while the fight lasts, {id} after it.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:info"), "info");
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf((double)h.gobid));
                int[] n = numbers(h.user, h.gobid);
                if(n != null) {
                    t.set("ip", ip(n));
                    t.set("give", give(n));
                }
                return t;
            }
        });

```

   with:

```java
        // info() — the one SNAPSHOT escape hatch: {id, ip, give, last} while the fight lasts, {id} after it.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaOpponent h = handle(Args.only(a, 0, "opponent:info"), "info");
                LuaTable t = new LuaTable();
                t.set("id", LuaValue.valueOf((double)h.gobid));
                int[] n = numbers(h.user, h.gobid);
                if(n != null) {
                    t.set("ip", ip(n));
                    t.set("give", give(n));
                    Indir<Resource> res = lastact(h.user, h.gobid);
                    String r = (res == null) ? null : AddonManager.resIdent(res);
                    if(r != null)
                        t.set("last", LuaValue.valueOf(r));
                }
                return t;
            }
        });

```

7. `src/io/brodgar/addon/LuaOpponent.java` — the read `lastact`, after `numbers`
   Replace:

```java
    /** The gob ids of the live relations, in the view's own order, copied under its monitor. */

```

   with:

```java
    /**
     * The manoeuvre the relation with {@code gobid} used last, copied under the view's monitor; {@code null}
     * before their first and once the fight with them has ended.
     */
    static Indir<Resource> lastact(String user, long gobid) {
        Fightview fv = view(user);
        if(fv == null)
            return null;
        synchronized(LuaWidget.monitor(fv)) {
            for(Fightview.Relation rel : fv.lsrel) {
                if((rel.gobid == gobid) && !rel.invalid)
                    return rel.lastact;
            }
        }
        return null;
    }

    /** The gob ids of the live relations, in the view's own order, copied under its monitor. */

```

8. `src/io/brodgar/addon/CharApi.java` — the imports
   Replace:

```java
import haven.Fightview;

```

   with:

```java
import haven.Fightsess;
import haven.Fightview;

```

9. `src/io/brodgar/addon/CharApi.java` — the row's spelling, beside `FO`
   Replace:

```java
    static final String FO = "session:fight():opponent()";

```

   with:

```java
    static final String FO = "session:fight():opponent()";
    /** {@code s:fight():action()} — the combat row of the fight in progress (170.3). */
    static final String FA = "session:fight():action()";

```

10. `src/io/brodgar/addon/CharApi.java` — `newAdapters` builds the combat row's adapter too
   Replace:

```java
        List<TreeAdapter> l = new ArrayList<TreeAdapter>(10);

```

   with:

```java
        List<TreeAdapter> l = new ArrayList<TreeAdapter>(11);

```

11. `src/io/brodgar/addon/CharApi.java` — …and adds it last
   Replace:

```java
        l.add(new FightAdapter(st));
        return Collections.unmodifiableList(l);

```

   with:

```java
        l.add(new FightAdapter(st));
        l.add(new CombatActionAdapter(st));
        return Collections.unmodifiableList(l);

```

12. `src/io/brodgar/addon/CharApi.java` — the fight adapter's javadoc gains `ManeuverUsed`
   Replace:

```java
     * <p>Within one refresh the order is Added, Removed, Changed, Selected: for any one opponent its {@code new}
     * comes before its {@code del}, and a lost target is announced gone before the next one is announced picked.
     */
    private static final class FightAdapter extends SessionAdapter {

```

   with:

```java
     * <p><b>{@code ManeuverUsed}</b> (170.3) comes off the {@code used} and {@code ruse} messages: that
     * character's {@code Fightview.lastuse} and each relation's {@code Relation.lastuse} are {@code Utils.rtime()}
     * stamps a use writes afresh, and a side cannot use twice inside one frame (its cooldown), so a per-frame
     * diff of the stamp sees every use, the same manoeuvre twice included. A name still loading holds the stamp
     * back and marks the adapter dirty, so the next frame looks again; a use clearing the last manoeuvre
     * (a {@code null} resource) is taken silently.
     *
     * <p>Within one refresh the order is Added, ManeuverUsed, Removed, Changed, Selected: for any one opponent its
     * {@code new} comes before its uses and its uses before its {@code del}, and a lost target is announced gone
     * before the next one is announced picked.
     */
    private static final class FightAdapter extends SessionAdapter {

```

13. `src/io/brodgar/addon/CharApi.java` — the fight adapter's body, whole
   Replace the whole block from the line that reads `private static final class FightAdapter extends SessionAdapter {` down to the first line after it that is only `}` indented by 4 spaces (its closing line), both lines included, with:

```java
    private static final class FightAdapter extends SessionAdapter {
        FightAdapter(SessionState st) {
            super(st);
        }

        /** One side's last use, as the view holds it: the manoeuvre and the {@code Utils.rtime()} stamp. */
        private static final class Use {
            final Indir<Resource> act;
            final double stamp;

            Use(Indir<Resource> act, double stamp) {
                this.act = act;
                this.stamp = stamp;
            }
        }

        // gob id -> {ip, oip, gst} as last ANNOUNCED, in announcement order. UI-thread-only; built with its
        // session's state (073.3). Keyed by the gob id, never by a widget: the view outlives every relation.
        private final LinkedHashMap<Long, int[]> announced = new LinkedHashMap<Long, int[]>();
        // gob id -> the stamp of the opponent's last use announced (170.3); ownUse is that character's own.
        private final HashMap<Long, Double> theirUse = new HashMap<Long, Double>();
        private double ownUse;
        private long selected = -1;       // the gob id OpponentSelected last named, -1 for none
        private Fightview view;           // the view last read, which is how the removal seam knows it

        public boolean interested(Widget w, String msg) {
            return (w instanceof Fightview)
                && ("new".equals(msg) || "del".equals(msg) || "upd".equals(msg) || "cur".equals(msg)
                    || "used".equals(msg) || "ruse".equals(msg));
        }

        public void refresh() {
            sync();
        }

        public void removed(Widget w) {
            if((w != null) && (w == view))
                sync();
        }

        /** Re-read the view and fire the difference: Added, ManeuverUsed, Removed, Changed, Selected. */
        private void sync() {
            String user = user();
            Fightview fv = LuaOpponent.view(user);   // null once it has left the tree
            view = fv;
            LinkedHashMap<Long, int[]> now = new LinkedHashMap<Long, int[]>();
            HashMap<Long, Use> uses = new HashMap<Long, Use>();
            Use own = null;
            long current = -1;
            if(fv != null) {
                synchronized(LuaWidget.monitor(fv)) {
                    for(Fightview.Relation rel : fv.lsrel) {
                        if(!rel.invalid) {
                            Long id = Long.valueOf(rel.gobid);
                            now.put(id, new int[] {rel.ip, rel.oip, rel.gst});
                            uses.put(id, new Use(rel.lastact, rel.lastuse));
                        }
                    }
                    Fightview.Relation cur = fv.current;
                    if((cur != null) && !cur.invalid)
                        current = cur.gobid;
                    own = new Use(fv.lastact, fv.lastuse);
                }
            }
            for(Map.Entry<Long, int[]> e : now.entrySet()) {
                if(!announced.containsKey(e.getKey())) {
                    announced.put(e.getKey(), e.getValue());
                    fireOpponent("OpponentAdded", user, e.getKey().longValue());
                }
            }
            boolean again = false;
            if((own != null) && (own.stamp != ownUse)) {
                String name = usedName(own.act);
                if(name == null) {
                    again = true;
                } else {
                    ownUse = own.stamp;
                    if(!name.isEmpty())
                        fireManeuverUsed(user, name, -1);
                }
            }
            for(Map.Entry<Long, Use> e : uses.entrySet()) {
                Double was = theirUse.get(e.getKey());
                Use u = e.getValue();
                if((was != null) && (was.doubleValue() == u.stamp))
                    continue;
                String name = usedName(u.act);
                if(name == null) {
                    again = true;
                } else {
                    theirUse.put(e.getKey(), Double.valueOf(u.stamp));
                    if(!name.isEmpty())
                        fireManeuverUsed(user, name, e.getKey().longValue());
                }
            }
            theirUse.keySet().retainAll(uses.keySet());
            if(again)
                st.treeDirty.add(this);   // a name still loading: look again next frame (EquipAdapter's shape)
            for(Iterator<Map.Entry<Long, int[]>> i = announced.entrySet().iterator(); i.hasNext();) {
                Map.Entry<Long, int[]> e = i.next();
                if(!now.containsKey(e.getKey())) {
                    i.remove();
                    fireOpponent("OpponentRemoved", user, e.getKey().longValue());
                }
            }
            for(Map.Entry<Long, int[]> e : announced.entrySet()) {
                int[] is = now.get(e.getKey());
                if(!Arrays.equals(e.getValue(), is)) {
                    e.setValue(is);
                    fireOpponent("OpponentChanged", user, e.getKey().longValue());
                }
            }
            if(current != selected) {
                selected = current;
                if(current >= 0)
                    fireOpponent("OpponentSelected", user, current);
            }
        }

        /** The name a use names: its resource's, "" for none or one that will never load, null while it loads. */
        private static String usedName(Indir<Resource> act) {
            if(act == null)
                return "";
            try {
                Resource r = act.get();
                return (r == null) ? "" : r.name;
            } catch(Loading l) {
                return null;
            } catch(RuntimeException e) {
                return "";
            }
        }
    }

    /**
     * The combat row (170.3) — the ten places of THAT character's {@code Fightsess}, announced as
     * {@code CombatActionChanged} when one is set, cleared or its name resolves, and for every filled place as the
     * row comes and goes with the fight. Not on a cooldown starting ({@code acool}): the moment a manoeuvre is used
     * is {@code ManeuverUsed}, and a cooldown is read live, as the action bar's is.
     *
     * <p>uimsg-driven on {@code act}. The row's arrival is the widget-entry seam ({@link #placed}) and its death the
     * removal seam ({@link #removed}). The diff key is the place's {@code Indir<Resource>}, which the session hands
     * back one per resource id, plus whether its name has resolved; a name still loading marks the adapter dirty
     * again, so the next frame looks again.
     */
    private static final class CombatActionAdapter extends SessionAdapter {
        CombatActionAdapter(SessionState st) {
            super(st);
        }

        // place -> the manoeuvre last ANNOUNCED there, and whether its name had resolved then. UI-thread-only;
        // built with its session's state (073.3). Indexed by place, never keyed by a widget.
        private final List<Indir<Resource>> held = LuaCombatAction.contents(null);
        private final boolean[] named = new boolean[LuaCombatAction.SLOTS];
        private Fightsess row;            // the row last read, which is how the removal seam knows it

        public boolean interested(Widget w, String msg) {
            return (w instanceof Fightsess) && "act".equals(msg);
        }

        public void refresh() {
            sync(LuaCombatAction.row(user()));
        }

        public void placed(Widget w) {
            if(w instanceof Fightsess)
                sync(LuaCombatAction.row(user()));
        }

        public void removed(Widget w) {
            if((w != null) && (w == row))
                sync(null);
        }

        /** Diff the ten places against what was last announced, and fire each that moved. */
        private void sync(Fightsess fs) {
            row = fs;
            List<Indir<Resource>> now = LuaCombatAction.contents(fs);
            String user = user();
            boolean again = false;
            for(int n = 0; n < LuaCombatAction.SLOTS; n++) {
                Indir<Resource> is = now.get(n);
                boolean resolved = (is != null) && (AddonManager.resIdent(is) != null);
                if((is != held.get(n)) || (resolved != named[n])) {
                    held.set(n, is);
                    named[n] = resolved;
                    fireCombatAction(user, n);
                }
                if((is != null) && !resolved)
                    again = true;
            }
            if(again)
                st.treeDirty.add(this);   // a name still loading: look again next frame
        }
    }
```

14. `src/io/brodgar/addon/CharApi.java` — the fight section's javadoc names the row
   Replace:

```java
     * opponent with the target as {@code :current()}, {@code :opening()} the buffs drawn beside that character.

```

   with:

```java
     * opponent with the target as {@code :current()}, {@code :opening()} the buffs drawn beside that character,
     * {@code :action()} the combat row, {@code :cooldown()} the global cooldown and {@code :last()} the
     * manoeuvre that character used last.

```

15. `src/io/brodgar/addon/CharApi.java` — mint the row beside the opponents
   Replace:

```java
        final LuaValue opponents = LuaOpponent.collection(owner, user);

```

   with:

```java
        final LuaValue opponents = LuaOpponent.collection(owner, user);
        final LuaValue actions = LuaCombatAction.collection(owner, user);

```

16. `src/io/brodgar/addon/CharApi.java` — `action`, `cooldown` and `last`, at the end of the section
   Replace:

```java
        return Section.object("fight", fight, FT);

```

   with:

```java
        // action() — 170.3: the combat row, ten places one per combat key, minted once and handed back by identity.
        fight.set("action", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "action", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":action() takes no arguments — it IS the combat row: :get(n) is the"
                        + " place Combat action n presses");
                return actions;
            }
        });
        // cooldown() — 170.3: how much of the global cooldown is left, a 0..1 fraction; nil out of a fight.
        fight.set("cooldown", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "cooldown", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":cooldown() takes no arguments — it reads how much of the global"
                        + " cooldown is left; action:cooldown() is one action's own");
                return LuaCombatAction.globalCooldown(user);
            }
        });
        // last() — 170.3: the resource name of the manoeuvre THAT character used last; nil out of a fight.
        fight.set("last", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "fight", "last", FT);
                if(Args.passed(a, 2))
                    throw new LuaError(FT + ":last() takes no arguments — it reads the manoeuvre that character"
                        + " used last; opponent:last() is theirs");
                return LuaCombatAction.lastOwn(user);
            }
        });
        return Section.object("fight", fight, FT);

```

17. `src/io/brodgar/addon/CharApi.java` — the builder notes no longer say the cooldowns are unpublished
   Replace:

```java
    // OUT-OF-COMBAT configuration surface, distinct from the in-combat Fightview/Fightsess deck, whose
    // live rtime cooldowns this API does not publish. It keeps three data structures:

```

   with:

```java
    // OUT-OF-COMBAT configuration surface, distinct from the in-combat row (Fightsess.actions), which
    // s:fight():action() reads with its cooldowns (LuaCombatAction, 170.3). It keeps three data structures:

```

18. `src/io/brodgar/addon/AddonManager.java` — two keys join the bus
   Replace:

```java
        "OpponentAdded", "OpponentRemoved", "OpponentChanged", "OpponentSelected",

```

   with:

```java
        "OpponentAdded", "OpponentRemoved", "OpponentChanged", "OpponentSelected",
        "ManeuverUsed", "CombatActionChanged",

```

19. `src/io/brodgar/addon/AddonManager.java` — their near misses
   Replace:

```java
        else if(key.toLowerCase().startsWith("opponent"))
            hint = " — the fight's opponent keys are OpponentAdded, OpponentRemoved, OpponentChanged and"
                + " OpponentSelected";

```

   with:

```java
        else if(key.toLowerCase().startsWith("opponent"))
            hint = " — the fight's opponent keys are OpponentAdded, OpponentRemoved, OpponentChanged and"
                + " OpponentSelected";
        else if(key.toLowerCase().contains("manoeuv") || key.toLowerCase().contains("maneuv"))
            hint = " — the key is ManeuverUsed";
        else if(key.toLowerCase().startsWith("combat"))
            hint = " — the key is CombatActionChanged";

```

20. `src/io/brodgar/addon/AddonManager.java` — `fireManeuverUsed` and `fireCombatAction`, after `fireOpponent`
   Replace:

```java
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaOpponent.of(c, user, gobid), sessionArg(c, user));
    }

```

   with:

```java
        if((c != null) && hasSub(c, event))
            fireTo(c, event, LuaOpponent.of(c, user, gobid), sessionArg(c, user));
    }

    /**
     * Fire {@code ManeuverUsed} (170.3): the resource name of the manoeuvre used, then the interned Opponent who
     * used it, or {@code nil} when {@code user}'s own character did ({@code gobid < 0}), then the session.
     * Detection is {@code CharApi}'s fight adapter, one fire per use.
     */
    static void fireManeuverUsed(String user, String res, long gobid) {
        for(Addon a : addons) {
            if(hasSub(a, "ManeuverUsed"))
                fireTo(a, "ManeuverUsed", LuaValue.valueOf(res),
                       (gobid < 0) ? LuaValue.NIL : LuaOpponent.of(a, user, gobid), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "ManeuverUsed"))
            fireTo(c, "ManeuverUsed", LuaValue.valueOf(res),
                   (gobid < 0) ? LuaValue.NIL : LuaOpponent.of(c, user, gobid), sessionArg(c, user));
    }

    /**
     * Fire {@code CombatActionChanged} (170.3) whose payload is the interned <b>CombatAction</b> for place
     * {@code slot} of {@code user}'s combat row. Same shape as {@link #fireSlot}. Detection is {@code CharApi}'s
     * combat-row adapter.
     */
    static void fireCombatAction(String user, int slot) {
        for(Addon a : addons) {
            if(hasSub(a, "CombatActionChanged"))
                fireTo(a, "CombatActionChanged", LuaCombatAction.of(a, user, slot), sessionArg(a, user));
        }
        Addon c = consoleOwner;
        if((c != null) && hasSub(c, "CombatActionChanged"))
            fireTo(c, "CombatActionChanged", LuaCombatAction.of(c, user, slot), sessionArg(c, user));
    }

```

21. `tools/docverbs.py` — the pages call a place of the row `action`
   Replace:

```python
    "opponent": "opponent", "target": "opponent", "grab": "grab",
```

   with:

```python
    "action": "action", "opponent": "opponent", "target": "opponent", "grab": "grab",
```

22. `tools/docverbs.py` — an action names its manoeuvre
   Replace:

```python
    ("opponent", "opening"): "@collection",

```

   with:

```python
    ("opponent", "opening"): "@collection",
    ("action", "maneuver"): "maneuver",

```

23. `tools/refusalverbs.py` — the row hands out actions
   Replace:

```python
"session:fight():opponent()": "opponent",

```

   with:

```python
"session:fight():opponent()": "opponent",
    "session:fight():action()": "action",

```

24. `docs/addons/api/fight.md` — the out-of-a-fight rule
   Replace:

```text
Out of a fight, `:opponent()` and `:opening()` are empty and `:opponent():current()` is `nil`. |
```

   with:

```text
Out of a fight, `:opponent()` and `:opening()` are empty, every `:action()` is `:empty()`, and `:opponent():current()`, `:cooldown()` and `:last()` are `nil`. |
```

25. `docs/addons/api/fight.md` — the events rule
   Replace:

```text
Nothing throws. The opponents fire [the fight events](event/bus/fight.md), a fight's buffs the
```

   with:

```text
Nothing throws. The opponents, the manoeuvres used and the combat row fire [the fight events](event/bus/fight.md), a fight's buffs the
```

26. `docs/addons/api/fight.md` — three rows in the fight's table
   Replace:

```text
| `session:fight():opponent():current()` | `Opponent \| nil` | Unprotected | The target: the opponent the fight has picked. `nil` out of a fight. |

```

   with:

```text
| `session:fight():opponent():current()` | `Opponent \| nil` | Unprotected | The target: the opponent the fight has picked. `nil` out of a fight. |
| `session:fight():action()` | collection | Unprotected | The combat row: ten [actions](#a-combat-action), one per combat key, `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(n)`. |
| `session:fight():cooldown()` | `number \| nil` | Unprotected | How much of the global cooldown is left, a [`0..1` fraction](shapes.md#units): `0` when the character can act. `nil` out of a fight. |
| `session:fight():last()` | `string \| nil` | Unprotected | The resource name of the manoeuvre that character used last. `nil` before its first and out of a fight. |

```

27. `docs/addons/api/fight.md` — the one-object rule, and a rule on `:last()`
   Replace:

```text
| One object | `session:fight():opponent()` and `session:fight():opening()` are the same object every call. Both are empty out of a fight. |
```

   with:

```text
| One object | `session:fight():opponent()`, `:action()` and `:opening()` are each the same object every call. |
| `:last()` | A resource name rather than a [Maneuver](#a-manoeuvre): an opponent's need not be one the character knows, and [`ManeuverUsed`](event/bus/fight.md) hands the same string. |
```

28. `docs/addons/api/fight.md` — `opponent:last()`
   Replace:

```text
| `opponent:opening()` | collection | Unprotected | Their openings: the row of icons the client paints over the map, to the right of your character while they are the target. [Buff](buff.md) objects, a view minted per call. |
```

   with:

```text
| `opponent:last()` | `string \| nil` | Unprotected | The resource name of the manoeuvre they used last. `nil` before their first. |
| `opponent:opening()` | collection | Unprotected | Their openings: the row of icons the client paints over the map, to the right of your character while they are the target. [Buff](buff.md) objects, a view minted per call. |
```

29. `docs/addons/api/fight.md` — what an ended opponent answers
   Replace:

```text
| Once the fight with them ends | `:ip()` and `:give()` read `nil`,
```

   with:

```text
| Once the fight with them ends | `:ip()`, `:give()` and `:last()` read `nil`,
```

30. `docs/addons/api/fight.md` — the opponent's events, and a new section after them
   Replace:

```text
| Events | [`OpponentAdded`, `OpponentRemoved`, `OpponentChanged` and `OpponentSelected`](event/bus/fight.md) hand this object. |

```

   with:

```text
| Events | [`OpponentAdded`, `OpponentRemoved`, `OpponentChanged` and `OpponentSelected`](event/bus/fight.md) hand this object, and [`ManeuverUsed`](event/bus/fight.md) hands it beside a manoeuvre they used. |

## A combat action

The place a combat key presses: `session:fight():action():get(n)` is the one "Combat action n" presses, keys `1` to `5` and Shift+`1` to `5` unless you rebound them. The row is the fight's own, so every place is empty out of a fight.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `action:index()` | `number` | Unprotected | Its 1-based position, the `n` of `:get(n)` and of "Combat action n". Always answers. |
| `action:wire()` | `number` | Unprotected | The server's 0-based slot number. Always answers. |
| `action:empty()` | `boolean` | Unprotected | Whether the place holds nothing. Always answers. |
| `action:res()` | `string \| nil` | Unprotected | The manoeuvre's resource name. |
| `action:name()` | `string \| nil` | Unprotected | Its display name. |
| `action:maneuver()` | [`Maneuver`](#a-manoeuvre) `\| nil` | Unprotected | The same manoeuvre in `session:fight():maneuver()`, `nil` when that list has none. |
| `action:cooldown()` | `number \| nil` | Unprotected | How much of its own cooldown is left, a [`0..1` fraction](shapes.md#units): `0` when it can be used. |
| `action:info()` | [`CombatAction`](types/fight.md#combataction) `\| nil` | Unprotected | A plain-table snapshot. `nil` for an empty place. |

| Rule | Detail |
|---|---|
| Ten places, always | `:list()` is ten actions and `:get(n)` takes `1` to `10`: the client's ten combat keys. `:get(0)` raises naming `action:wire()`, and a position past ten names the range. A place past the server's row is `:empty()`. |
| The fight's row, not the deck | What the server put up for this fight. `session:fight():deck()` is the schools tab's layout, which the tab keeps to itself until it is saved. |
| An empty place | Every reader but `:index()`, `:wire()` and `:empty()` answers `nil`. |
| `filter` | A string matches the manoeuvre's resource and display name, as on `:maneuver()`, so one needle finds the same manoeuvre through either door. |
| Identity | Interned on character and place: `session:fight():action():get(1) == session:fight():action():list()[1]`. A stashed action follows its key from fight to fight. |
| Events | [`CombatActionChanged`](event/bus/fight.md) when a place is set, cleared or its name resolves, never as a cooldown runs. The moment a manoeuvre is used is [`ManeuverUsed`](event/bus/fight.md). |

```

31. `docs/addons/api/fight.md` — See Also
   Replace:

```text
- [The fight events](event/bus/fight.md) — an opponent coming and going, its numbers moving, the target changing.
```

   with:

```text
- [The fight events](event/bus/fight.md) — an opponent coming and going, its numbers moving, the target changing, a manoeuvre used, the combat row changing.
```

32. `docs/addons/api/event/bus/fight.md` — the intro
   Replace:

```text
What one character's fight in progress reports: an opponent joining it and leaving it, the numbers between you moving, and the target changing.
```

   with:

```text
What one character's fight in progress reports: an opponent joining it and leaving it, the numbers between you moving, the target changing, a manoeuvre being used and the combat row changing.
```

33. `docs/addons/api/event/bus/fight.md` — two rows
   Replace:

```text
| `OpponentSelected` | [`Opponent`](../../fight.md#an-opponent) | It becomes the target, `session:fight():opponent():current()`. |

```

   with:

```text
| `OpponentSelected` | [`Opponent`](../../fight.md#an-opponent) | It becomes the target, `session:fight():opponent():current()`. |
| `ManeuverUsed` | `string`, then [`Opponent`](../../fight.md#an-opponent) `\| nil` | A manoeuvre is used: its resource name, and the opponent who used it, `nil` when that character did. |
| `CombatActionChanged` | [`CombatAction`](../../fight.md#a-combat-action) | A place of the combat row is set, cleared or its name resolves, and every filled place as the row comes and goes with the fight. |

```

34. `docs/addons/api/event/bus/fight.md` — the order rule, and two rules after it
   Replace:

```text
| The order in one frame | Added, then Removed, then Changed, then Selected. A lost target is announced gone before the next one is announced picked. |
```

   with:

```text
| The order in one frame | `OpponentAdded`, then `ManeuverUsed`, then `OpponentRemoved`, `OpponentChanged` and `OpponentSelected`. A lost target is announced gone before the next one is announced picked. |
| `ManeuverUsed`, once per use | Every use is announced, the same manoeuvre used twice included. It fires once the name has loaded, so it can trail the use by a frame. `session:fight():last()` and `opponent:last()` read the same name afterwards. |
| `CombatActionChanged` payload | The interned `CombatAction`, so `payload == session:fight():action():get(payload:index())`. Not on a cooldown starting or running: read `action:cooldown()` live, as the action bar's. |
```

35. `docs/addons/api/event/bus/README.md` — the fight row
   Replace:

```text
| [The fight](fight.md) | An opponent joining a fight or leaving it, the numbers between you moving, the target changing. |
```

   with:

```text
| [The fight](fight.md) | An opponent joining a fight or leaving it, the numbers between you moving, the target changing, a manoeuvre used, the combat row changing. |
```

36. `docs/addons/api/README.md` — the event family row
   Replace:

```text
| [The fight](event/bus/fight.md) | An opponent joining a fight or leaving it, the numbers between you moving, the target changing. |
```

   with:

```text
| [The fight](event/bus/fight.md) | An opponent joining a fight or leaving it, the numbers between you moving, the target changing, a manoeuvre used, the combat row changing. |
```

37. `docs/addons/api/README.md` — the snapshot page row
   Replace:

```text
| [The fight](types/fight.md) | A manoeuvre, a card in the deck, the deck's totals, an opponent. |
```

   with:

```text
| [The fight](types/fight.md) | A manoeuvre, a card in the deck, the deck's totals, an opponent, a combat action. |
```

38. `docs/addons/api/types/fight.md` — the intro
   Replace:

```text
one manoeuvre, one card in the deck, the deck's totals, and an opponent.
```

   with:

```text
one manoeuvre, one card in the deck, the deck's totals, an opponent, and a combat action.
```

39. `docs/addons/api/types/fight.md` — the opponent's `last`, and the combat action's shape
   Replace:

```text
From [`opponent:info()`](../fight.md#an-opponent). `{ id = number, ip = { mine = number, theirs = number }?, give = { mine = boolean, theirs = boolean }? }`. `ip` and `give` are absent once the fight with that opponent has ended, and the snapshot is then `{ id }`. Everything about the creature itself is read off its [Gob](../gob.md).
```

   with:

```text
From [`opponent:info()`](../fight.md#an-opponent). `{ id = number, ip = { mine = number, theirs = number }?, give = { mine = boolean, theirs = boolean }?, last = string? }`. `ip`, `give` and `last` are absent once the fight with that opponent has ended, and the snapshot is then `{ id }`; `last` is absent before their first manoeuvre too. Everything about the creature itself is read off its [Gob](../gob.md).

## CombatAction

From [`action:info()`](../fight.md#a-combat-action), `nil` for an empty place. `{ res = string?, name = string?, cooldown = number }`: `cooldown` is the `0..1` fraction of its own cooldown still to run, not seconds, `0` when the action can be used.
```

40. `docs/addons/api/types/README.md` — the page row
   Replace:

```text
| [The fight](fight.md) | A manoeuvre, a card in the deck, the deck's totals, an opponent. |
```

   with:

```text
| [The fight](fight.md) | A manoeuvre, a card in the deck, the deck's totals, an opponent, a combat action. |
```

41. `docs/addons/api/types/README.md` — every shape
   Replace:

```text
| `Condition` | [The character sheet](character.md#quest-and-condition) |
```

   with:

```text
| `CombatAction` | [The fight](fight.md#combataction) |
| `Condition` | [The character sheet](character.md#quest-and-condition) |
```

42. `docs/addons/api/conventions.md` — the word that is the moment
   Replace:

```text
Where none is (`Load`, `Disable`) the word is the moment.
```

   with:

```text
Where none is (`Load`, `Disable`, `ManeuverUsed`) the word is the moment.
```

43. `docs/addons/api/conventions.md` — the index and wire pairs
   Replace:

```text
`card:index()`/`card:wire()` and `speed:index()`/`speed:wire()` are the same pair.
```

   with:

```text
`card:index()`/`card:wire()`, `speed:index()`/`speed:wire()` and `action:index()`/`action:wire()` are the same pair.
```

44. `docs/addons/api/conventions.md` — the row mints an object for every key
   Replace:

```text
`session:world():gob()`, `session:actionbar()`, `keybindings():binding()`
```

   with:

```text
`session:world():gob()`, `session:actionbar()`, `session:fight():action()`, `keybindings():binding()`
```

45. `docs/addons/api/shapes.md` — the two cooldowns
   Replace:

```text
| `slot:cooldown()` | An ability's cooldown meter. |
```

   with:

```text
| `slot:cooldown()` | An ability's cooldown meter. |
| `action:cooldown()`, `session:fight():cooldown()` | A combat action's own cooldown, and the fight's global one: how much of it is left. |
```

46. `docs/addons/api/references.md` — a place of the row, before the nameless kinds
   Replace:

```text
## Named, and nameless: Menugrid, Sound, Buff, Meter

```

   with:

```text
## CombatAction: a place of the combat row

`session:fight():action()` is the ten places of a character's combat row as a 1-based array of `CombatAction`, `:get(n)` the one "Combat action n" presses (`:list()[n] == :get(n)`, what `action:index()` answers). `action:wire()` is the server's 0-based number. The row is the fight's own: every place is `:empty()` out of a fight ([`session:fight`](fight.md#a-combat-action)).

## Named, and nameless: Menugrid, Sound, Buff, Meter

```

47. `docs/addons/manifest.md` — what needs `1.3`
   Replace:

```text
its opponents and their numbers, its buffs and [`buff:opponent()`](api/buff.md), and [the fight events](api/event/bus/fight.md).
```

   with:

```text
its opponents and their numbers, its combat row and cooldowns, the manoeuvres used, its buffs and [`buff:opponent()`](api/buff.md), and [the fight events](api/event/bus/fight.md).
```

48. `addons/170-live-fight.3/manifest.json` — the suite's manifest
   Create the file with exactly this content:

```json
{
  "name": "170.3 — the combat row and the manoeuvres used",
  "id": "170-live-fight.3",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 170.3: session:fight():action() is the ten-place combat row with its reads and cooldowns, CombatActionChanged fires on set and clear only, ManeuverUsed fires once per use for both sides, and both last() reads name the manoeuvre.",
  "api_version": "1.3",
  "files": ["main.lua"]
}
```

49. `addons/170-live-fight.3/main.lua` — the suite
   Create the file with exactly this content:

```lua
-- 170.3 — the combat row and the manoeuvres used. Self-checking suite.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local session, panel, sampler
local subs = {}
local changes = {}   -- every CombatActionChanged: { action, session, res }
local used = {}      -- every ManeuverUsed: { res, opponent, session }
local peak = 0       -- the highest cooldown seen while the key step runs
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if sampler then pcall(function() sampler:cancel() end) end
  if panel then pcall(function() panel:destroy() end) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("170.3"):size(480, 70)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function skipped(what)
  return function()
    check(false, what, "skipped")
    finish()
  end
end

local function filledNow()
  local filled = {}
  for _, action in ipairs(session:fight():action():list()) do
    if not action:empty() then filled[#filled + 1] = action end
  end
  return filled
end

local function fraction(value)
  return type(value) == "number" and value >= 0 and value <= 1
end

local filledAtStart = {}

local function ended()
  local empty = 0
  for _, action in ipairs(session:fight():action():list()) do
    if action:empty() then empty = empty + 1 end
  end
  local cleared = 0
  for _, action in ipairs(filledAtStart) do
    for _, change in ipairs(changes) do
      if change.action == action and change.res == nil then cleared = cleared + 1; break end
    end
  end
  check(empty == 10 and #filledAtStart > 0 and cleared == #filledAtStart,
    "every action is :empty() again, and CombatActionChanged said so for each that was filled",
    empty .. " of 10 empty, " .. cleared .. " of " .. #filledAtStart .. " announced")
  finish()
end

local function pressed()
  if sampler then sampler:cancel(); sampler = nil end
  local fight = session:fight()
  local mine, same = 0, true
  local first
  for _, use in ipairs(used) do
    if use.opponent == nil and use.session == session then
      mine = mine + 1
      first = first or use.res
      if use.res ~= first then same = false end
    end
  end
  check(mine >= 2 and same and fight:last() == first,
    "ManeuverUsed fired for each of your uses, the same resource each time, nil as the opponent, and :last() is it",
    mine .. " of yours, last " .. tostring(fight:last()))
  local spurious = 0
  for index, change in ipairs(changes) do
    if change.stepped and change.res ~= nil and change.res == change.before then spurious = spurious + 1 end
  end
  check(peak > 0 and spurious == 0,
    "a cooldown ran (the highest seen was " .. string.format("%d%%", math.floor(peak * 100)) .. ") and no"
      .. " CombatActionChanged fired for a place whose manoeuvre stayed", peak .. ", " .. spurious .. " spurious")
  local target = fight:opponent():current()
  local theirs
  for _, use in ipairs(used) do
    if use.opponent ~= nil and use.opponent == target then theirs = use.res end
  end
  check(theirs ~= nil and target:last() == theirs,
    "a ManeuverUsed named the animal as the opponent, and its :last() is that resource",
    tostring(theirs) .. " / " .. tostring(target and target:last()))
  prompt("End the fight (win it or walk away), then press Done.", ended, skipped("the end of the fight was reached"))
end

local function fighting()
  local fight = session:fight()
  local actions = fight:action()
  filledAtStart = filledNow()
  local named, announced, sameObject, sessions = 0, 0, true, 0
  for _, action in ipairs(filledAtStart) do
    if action:res() and action:name() then named = named + 1 end
    for _, change in ipairs(changes) do
      if change.action == action then announced = announced + 1; break end
    end
  end
  for _, change in ipairs(changes) do
    if actions:get(change.action:index()) ~= change.action then sameObject = false end
    if change.session ~= session then sessions = sessions + 1 end
  end
  check(#filledAtStart > 0 and named == #filledAtStart and announced == #filledAtStart and sameObject and sessions == 0,
    #filledAtStart .. " actions are filled, each with a resource and a name, and CombatActionChanged announced each"
      .. " as :get(its index) with this session last",
    named .. " named, " .. announced .. " announced, " .. sessions .. " with another session")
  local first = filledAtStart[1]
  local maneuver = first and first:maneuver()
  local wanted = first and fight:maneuver():find(function(each) return each:res() == first:res() end)
  check(first ~= nil and maneuver ~= nil and maneuver == wanted,
    "a filled action's :maneuver() is the entry session:fight():maneuver() finds by the same resource",
    tostring(maneuver) .. " / " .. tostring(wanted))
  check(first ~= nil and actions:find(first:res()) ~= nil and actions:find(first:name()) ~= nil,
    ":find(res) and :find(name) reach a filled action", first and first:res())
  check(fraction(fight:cooldown()) and first ~= nil and fraction(first:cooldown()) and first:info().res == first:res(),
    "both cooldowns read within 0..1, and :info() carries the same resource", tostring(fight:cooldown()))
  local one = actions:get(1)
  manualCheck("look at the first icon of the row drawn under your character",
    one:empty() and "no icon there" or ("the manoeuvre " .. tostring(one:name())))
  for _, change in ipairs(changes) do change.stepped = false end
  sampler = hafen.timer():every(0.05, guarded(function()
    local now = math.max(fight:cooldown() or 0, actions:get(1):cooldown() or 0)
    if now > peak then peak = now end
  end))
  prompt("Press combat key 1, wait for its cooldown to end, press it again, then press Done.", pressed,
    skipped("combat key 1 was pressed twice"))
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  subs, changes, used, peak, filledAtStart = {}, {}, {}, 0, {}
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  local fight = session:fight()
  local actions = fight:action()
  local third = actions:get(3)
  check(actions == fight:action() and actions:count() == 10 and third == actions:list()[3] and third:index() == 3
      and third:wire() == 2 and tostring(third) == "CombatAction(3)",
    "session:fight():action() is one object of ten actions, and :get(3) is :list()[3], index 3, wire 2",
    actions:count() .. " " .. tostring(third))
  local empty = 0
  for _, action in ipairs(actions:list()) do
    if action:empty() and action:res() == nil and action:cooldown() == nil and action:info() == nil then
      empty = empty + 1
    end
  end
  check(empty == 10 and fight:cooldown() == nil and fight:last() == nil,
    "out of a fight every action is :empty() and reads nil, and fight:cooldown() and fight:last() are nil",
    empty .. " empty, " .. tostring(fight:cooldown()) .. " " .. tostring(fight:last()))
  local zero = refusal(function() return actions:get(0) end)
  local eleven = refusal(function() return actions:get(11) end)
  check(zero ~= nil and zero:find("wire()", 1, true) ~= nil and eleven ~= nil and eleven:find("1..10", 1, true) ~= nil
      and refusal(function() return actions:get("1") end) ~= nil and refusal(function() return third:res(1) end) ~= nil,
    ":get(0) names action:wire(), :get(11) names 1..10, and :get(\"1\") and a surplus argument are refused",
    tostring(zero) .. " / " .. tostring(eleven))
  subs[#subs + 1] = hafen.event():on("CombatActionChanged", function(action, where)
    local before
    for index = #changes, 1, -1 do
      if changes[index].action == action then before = changes[index].res; break end
    end
    changes[#changes + 1] = { action = action, session = where, res = action:res(), before = before, stepped = true }
  end)
  subs[#subs + 1] = hafen.event():on("ManeuverUsed", function(res, opponent, where)
    used[#used + 1] = { res = res, opponent = opponent, session = where }
  end)
  prompt("Attack ONE chicken, and press Done once the row of actions shows under your character.", fighting,
    skipped("a fight with an animal was reached"))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)
```

---

- [x] **170.4 — An addon uses a combat action as its key does, under fight.use.**

`action:use(mods, position)`, protected by the new key `fight.use` (and the group `fight.*`): the row's own `use`
and then its `rel`, sent back to back from that character's `Fightsess` inside one `Wire.send`, as a tapped key.
`mods` is optional and first, as in `slot:use(mods)`; `position` is an optional Position, floored as a place is.
Refused before anything is sent: a surplus argument, a bad `mods` (a new `Wire` row, `0..7`), a non-Position, no
fight, an empty place, a place past the server's row. `fight.md` gains its **Write (protected)** section, the
permissions guide its catalogue and group rows.

*Its suite* declares `fight.use`, so it proves the effect and the argument refusals; the gate is the verb's first
statement, read in the code. It records every `use` and `rel` on `hafen.event():action()`. Out of a fight: a
surplus argument and a table for a place are refused, and `use()` is refused naming the fight — nothing sent. In
the fight: an empty action and `mods` 8 are refused, nothing sent; a ready action's `use()` sent exactly
`use {wire, 1, 0}` then `rel {wire}` from the `Fightsess`; within 3 s a cooldown started and `ManeuverUsed` named
it; once ready again, `use(1, your position)` sent `use {wire, 1, 1, that place}` then `rel`.

`[manual]`: watch your character as the suite uses the action — expect it to perform that manoeuvre, twice in all.

**The passing log** (8 lines):

```text
[pass] a surplus argument and a table for a place are refused, and nothing is sent
[pass] out of a fight action:use() is refused naming the fight, and nothing is sent
[pass] an empty action and mods 8 are refused, naming why, and nothing is sent
[pass] action:use() sent exactly use {<wire>, 1, 0} and then rel {<wire>} from the combat row
[pass] the use took: a cooldown started and ManeuverUsed named <res>
[pass] action:use(1, your position) sent use {<wire>, 1, 1, that place} and then rel {<wire>}
[manual] watch your character as the suite used <name> -- expect: it performs that manoeuvre, twice in all
[summary] 6 pass, 0 fail, 1 manual
```

The third check needs a school with at least one empty hotkey; any place past the server's row counts too.

**If "the use took" fails while the two messages were sent**, the server wants the release after the frame, as
the client's own `Fightsess.Release` posts it. That fallback is decided: in `LuaCombatAction`'s `use` dispatch,
replace the line `fs.wdgmsg("rel", Integer.valueOf(n));` with the client's own fence —

```java
                    haven.render.Environment env = fs.ui.getenv();
                    haven.render.Render out = env.render();
                    out.fence(() -> fs.wdgmsg("rel", Integer.valueOf(n)));
                    env.submit(out);
```

— and change the page's "A tap" rule to "The row's `use`, and its release once the frame it was sent in has
rendered", then re-run the suite. Report the fact at the close.

**The edits, in order**

1. `src/io/brodgar/addon/LuaCombatAction.java` — the imports
   Replace:

```java
import haven.FightWnd;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Indir;
import haven.Resource;
import haven.Utils;

```

   with:

```java
import haven.Coord2d;
import haven.FightWnd;
import haven.Fightsess;
import haven.Fightview;
import haven.GameUI;
import haven.Indir;
import haven.OCache;
import haven.Resource;
import haven.Utils;

```

2. `src/io/brodgar/addon/LuaCombatAction.java` — the class javadoc's last paragraph
   Replace:

```java
 * has its own row. Every read re-resolves the row through that character's HUD, copying the slot under the
 * row's monitor, where the loader thread writes {@code act} and {@code acool}.
 */
```

   with:

```java
 * has its own row. Every read re-resolves the row through that character's HUD, copying the slot under the
 * row's monitor, where the loader thread writes {@code act} and {@code acool}.
 *
 * <p><b>{@code :use(mods, position)}</b> (170.4, {@code fight.use}) taps the key: the row's own {@code use}, then
 * its {@code rel}, back to back inside one {@link Wire#send}. The client posts its own release through a render
 * fence only because its {@code use} rides an asynchronous map pick; the connection numbers every message in
 * order, so two sends from one thread under one monitor arrive in that order.
 */
```

3. `src/io/brodgar/addon/LuaCombatAction.java` — `action:use`, the last verb of the table
   Replace:

```java
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
```

   with:

```java
        // use(mods, position) — 170.4, protected ("fight.use"), gated as the FIRST statement (D-213): a tap of the
        // combat key, the row's own "use" and then its "rel", sent back to back from THAT character's Fightsess
        // inside one Wire.send so nothing lands between them. mods (Shift=1, Ctrl=2, Alt=4) is optional and first,
        // as slot:use(mods) takes it; position is an optional Position in that character's world, floored as a
        // place is: the ground the client adds when the key is pressed over the map. Refused before anything is
        // sent: out of a fight, past the server's row, on an empty place.
        m.set("use", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                AddonManager.requirePermission(AddonManager.current(), Permission.FIGHT_USE);
                Args.only(a, 2, "action:use");
                LuaCombatAction h = handle(self, "use");
                int mods = Args.optint(a, 2, "action:use", "mods", "Shift=1, Ctrl=2, Alt=4; a place goes second,"
                                       + " action:use(0, position)", 0);
                Coord2d at = Args.passed(a, 3) ? LuaPosition.worldArg(a, 3, "action:use", "position", h.user) : null;
                if(AddonManager.gameui(h.user) == null)
                    throw new LuaError("action:use(): no game UI (that character is not in the world yet)."
                        + " Nothing was sent.");
                final Fightsess fs = row(h.user);
                if(fs == null)
                    throw new LuaError("action:use(): that character is not in a fight — the combat row is there"
                        + " only while it fights (" + CharApi.FO + ":current() is nil). Nothing was sent.");
                final int n = h.slot;
                final Object[] args = (at == null)
                    ? new Object[] {Integer.valueOf(n), Integer.valueOf(1), Integer.valueOf(mods)}
                    : new Object[] {Integer.valueOf(n), Integer.valueOf(1), Integer.valueOf(mods),
                                    at.floor(OCache.posres)};
                // The dispatch re-reads the row under the monitor Wire holds: the loader thread writes "act" under
                // it, and the row may have lost this place since the look above.
                Wire.send(h.user, "action:use", fs, "use", args, () -> {
                    if(n >= fs.actions.length)
                        throw new LuaError("action:use(): this fight's row has " + fs.actions.length
                            + " actions and this is action " + (n + 1) + " — the client's own key for it sends"
                            + " nothing. Nothing was sent.");
                    if(fs.actions[n] == null)
                        throw new LuaError("action:use(): action " + (n + 1) + " is empty (check action:empty()"
                            + " first). Nothing was sent.");
                    fs.wdgmsg("use", args);
                    fs.wdgmsg("rel", Integer.valueOf(n));
                });
                return self;
            }
        });
        return m;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
```

4. `src/io/brodgar/addon/Permission.java` — the key, after the action bar's
   Replace:

```java
    ACTIONBAR_CLEAR  ("actionbar.clear",    "slot:clear",                     "empty any of your characters'"
                                                                              + " action-bar buttons"),

```

   with:

```java
    ACTIONBAR_CLEAR  ("actionbar.clear",    "slot:clear",                     "empty any of your characters'"
                                                                              + " action-bar buttons"),
    FIGHT_USE        ("fight.use",          "action:use",                     "use combat actions, in the fights of"
                                                                              + " any of your characters"),

```

5. `src/io/brodgar/addon/Wire.java` — the combat key's row, before the chat line's
   Replace:

```java
        // ChatUI.EntryChannel "msg" {text} — what the entry line composes is ONE line of typed characters,

```

   with:

```java
        // Fightsess "use" {n, 1, mods, [place]} (170.4) — a combat key: the button is always 1 and the modifiers a
        // keyboard's. The action menu's "use" is sent with no arguments and never reaches a row.
        SHAPES.put("use", new Shape() {
            public void check(String verb, Object[] a) {
                mods(verb, a, 2);
            }
        });
        // ChatUI.EntryChannel "msg" {text} — what the entry line composes is ONE line of typed characters,

```

6. `docs/addons/api/fight.md` — the reads rule points at the write side
   Replace:

```text
| Unprotected, no write side | Nothing throws. The opponents,
```

   with:

```text
| Reads are unprotected | Nothing throws, and the verbs that act are under [Write (protected)](#write-protected). The opponents,
```

7. `docs/addons/api/fight.md` — a combat action says how it is used
   Replace:

```text
| Events | [`CombatActionChanged`](event/bus/fight.md) when a place is set, cleared or its name resolves, never as a cooldown runs. The moment a manoeuvre is used is [`ManeuverUsed`](event/bus/fight.md). |
```

   with:

```text
| Events | [`CombatActionChanged`](event/bus/fight.md) when a place is set, cleared or its name resolves, never as a cooldown runs. The moment a manoeuvre is used is [`ManeuverUsed`](event/bus/fight.md). |
| Using it | [`action:use(mods, position)`](#write-protected), under `fight.use`. |
```

8. `docs/addons/api/fight.md` — the write section, before See Also
   Replace:

```text

---

## See Also

```

   with:

```text

## Write (protected)

The client sends only shapes a player could compose.

| Method | Returns | Permission | Description |
|---|---|---|---|
| `action:use(mods, position)` | the `CombatAction` | `fight.use` | Use that action, as tapping its combat key does. |

| Rule | Detail |
|---|---|
| Permission | Each key [declared](../guides/permissions.md) in your manifest, or the group `fight.*`. An undeclared key raises naming it. One key covers every character ([a key names the action, not the target](../guides/permissions.md#a-key-names-the-action-not-the-target)). |
| A tap | The row's `use` and then its release, sent back to back from that character's combat row: the key pressed and let go. There is no verb for holding one down. |
| `mods` | Optional and first: Shift = 1, Ctrl = 2, Alt = 4, as for [`slot:use(mods)`](actionbar.md#write-protected). The client's own keys for actions 6 to 10 are Shift+`1` to Shift+`5`, so pressing one of them carries Shift. |
| `position` | Optional and second: a [Position](position.md) in that character's world, the ground the client adds when a key is pressed with the pointer over the map. `action:use(0, position)` passes one without modifiers. |
| Raises before anything is sent | An addon that did not declare `fight.use`, naming the key. A surplus argument. A `mods` that is not a whole number `0..7`, and a `position` that is not a Position. Out of a fight. An empty action (`action:empty()`), and one past the server's row. |
| Asynchronous | The server answers the use: the cooldowns start, [`ManeuverUsed`](event/bus/fight.md) fires and `session:fight():last()` names it, or nothing moves when the server refused it. Read them on the next frames. |
| The outbound stream sees it | Both messages pass [`hafen.event():action()`](event/streams.md#intercepting-an-outbound-action) like the client's own, so a handler can stop or rewrite them. |

---

## See Also

```

9. `docs/addons/api/fight.md` — See Also names the keys
   Replace:

```text
- [`session:actionbar`](actionbar.md) — the other hotkey surface, which is writable.
```

   with:

```text
- [`session:actionbar`](actionbar.md) — the other hotkey surface.
- [Permissions](../guides/permissions.md) — the `fight.*` keys.
```

10. `docs/addons/guides/permissions.md` — the catalogue row
   Replace:

```text
| `actionbar.clear` | [`slot:clear`](../api/actionbar.md#write-protected) | empty any of your characters' action-bar buttons |

```

   with:

```text
| `actionbar.clear` | [`slot:clear`](../api/actionbar.md#write-protected) | empty any of your characters' action-bar buttons |
| `fight.use` | [`action:use`](../api/fight.md#write-protected) | use combat actions, in the fights of any of your characters |

```

11. `docs/addons/guides/permissions.md` — the group row
   Replace:

```text
| `actionbar.*` | `actionbar.use`, `actionbar.res`, `actionbar.clear` |

```

   with:

```text
| `actionbar.*` | `actionbar.use`, `actionbar.res`, `actionbar.clear` |
| `fight.*` | `fight.use` |

```

12. `docs/addons/manifest.md` — what needs `1.3`
   Replace:

```text
its buffs and [`buff:opponent()`](api/buff.md), and [the fight events](api/event/bus/fight.md).
```

   with:

```text
its buffs and [`buff:opponent()`](api/buff.md), [`action:use`](api/fight.md#write-protected), and [the fight events](api/event/bus/fight.md).
```

13. `docs/addons/api/README.md` — the section row
   Replace:

```text
| [`session:fight`](fight.md) | The manoeuvre-deck builder, and the fight in progress: every opponent, the numbers between you, the buffs drawn beside each side. |
```

   with:

```text
| [`session:fight`](fight.md) | The manoeuvre-deck builder, and the fight in progress: every opponent, the numbers between you, the buffs drawn beside each side, the combat row and using it. |
```

14. `addons/170-live-fight.4/manifest.json` — the suite's manifest: it declares the key
   Create the file with exactly this content:

```json
{
  "name": "170.4 — using a combat action",
  "id": "170-live-fight.4",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 170.4: action:use(mods, position) sends the row's use and rel back to back, the use takes, and the refusals come before anything is sent.",
  "api_version": "1.3",
  "permissions": ["fight.use"],
  "files": ["main.lua"]
}
```

15. `addons/170-live-fight.4/main.lua` — the suite
   Create the file with exactly this content:

```lua
-- 170.4 — using a combat action. Self-checking suite; declares "fight.use".

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local session, panel, waiter
local subs = {}
local sent = {}      -- every "use" and "rel" the combat row sends: { msg, widget type, args, place }
local used = {}      -- every ManeuverUsed of this character's own: the resource names
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if waiter then pcall(function() waiter:cancel() end) end
  if panel then pcall(function() panel:destroy() end) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("170.4"):size(480, 70)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function skipped(what)
  return function()
    check(false, what, "skipped")
    finish()
  end
end

local function record(msg)
  return function(event)
    local args = event:args()
    local place = nil
    if msg == "use" and #args >= 4 then place = event:position(4) end
    sent[#sent + 1] = { msg = msg, widget = event:widget():type(), args = args, place = place }
  end
end

local function shape(entry)
  local parts = {}
  for index, value in ipairs(entry.args) do
    parts[index] = type(value) == "table" and "place" or tostring(value)
  end
  return entry.msg .. " {" .. table.concat(parts, ", ") .. "}"
end

-- Poll every 0.2 s until `ready()` answers true, for at most `limit` seconds, then run `go(ok)`.
local function waitFor(limit, ready, go)
  local spent = 0
  waiter = hafen.timer():every(0.2, guarded(function()
    spent = spent + 0.2
    if ready() or spent >= limit then
      waiter:cancel()
      waiter = nil
      go(ready())
    end
  end))
end

local function readyAction()
  local fight = session:fight()
  if (fight:cooldown() or 1) > 0 then return nil end
  return fight:action():find(function(action) return (not action:empty()) and action:cooldown() == 0 end)
end

local function ended()
  finish()
end

local function secondUse(action)
  local wire = action:wire()
  local here = session:player():gob():position()
  local from = #sent
  action:use(1, here)
  local use, rel = sent[from + 1], sent[from + 2]
  check(use ~= nil and use.widget == "Fightsess" and use.args[1] == wire and use.args[2] == 1 and use.args[3] == 1
      and use.place ~= nil and use.place:distance(here) < 11 and rel ~= nil and rel.msg == "rel"
      and rel.args[1] == wire and #sent == from + 2,
    "action:use(1, your position) sent use {" .. wire .. ", 1, 1, that place} and then rel {" .. wire .. "}",
    use and (shape(use) .. " / " .. (rel and shape(rel) or "nothing")) or "nothing")
  manualCheck("watch your character as the suite used " .. tostring(action:name()),
    "it performs that manoeuvre, twice in all")
  prompt("End the fight (win it or walk away), then press Done.", ended, skipped("the end of the fight was reached"))
end

local function firstUse(action)
  local wire, res = action:wire(), action:res()
  local from = #sent
  action:use()
  local use, rel = sent[from + 1], sent[from + 2]
  check(use ~= nil and use.widget == "Fightsess" and use.msg == "use" and use.args[1] == wire and use.args[2] == 1
      and use.args[3] == 0 and #use.args == 3 and rel ~= nil and rel.msg == "rel" and rel.args[1] == wire
      and #sent == from + 2,
    "action:use() sent exactly use {" .. wire .. ", 1, 0} and then rel {" .. wire .. "} from the combat row",
    use and (shape(use) .. " / " .. (rel and shape(rel) or "nothing")) or "nothing")
  local peak = 0
  waitFor(3, function()
    peak = math.max(peak, session:fight():cooldown() or 0, action:cooldown() or 0)
    for _, name in ipairs(used) do
      if name == res then return peak > 0 end
    end
    return false
  end, function(ok)
    check(ok, "the use took: a cooldown started and ManeuverUsed named " .. tostring(res),
      string.format("peak %d%%, %d uses", math.floor(peak * 100), #used))
    waitFor(15, function() return readyAction() == action end, function(ready)
      if not ready then
        check(false, "the action was ready again for a second use", "still cooling down after 15 s")
        return finish()
      end
      secondUse(action)
    end)
  end)
end

local function fighting()
  local actions = session:fight():action()
  local blank
  for _, action in ipairs(actions:list()) do
    if action:empty() then blank = action; break end
  end
  local from = #sent
  local refused = blank and refusal(function() return blank:use() end)
  local badMods = refusal(function() return actions:get(1):use(8) end)
  check(blank ~= nil and refused ~= nil and (refused:find("empty", 1, true) or refused:find("row has", 1, true))
      and badMods ~= nil and badMods:find("0..7", 1, true) ~= nil and #sent == from,
    "an empty action and mods 8 are refused, naming why, and nothing is sent",
    blank and (tostring(refused) .. " / " .. tostring(badMods))
      or "every action holds a manoeuvre -- load a school with an empty hotkey")
  waitFor(10, function() return readyAction() ~= nil end, function(ready)
    if not ready then
      check(false, "an action was ready to use", "none within 10 s")
      return finish()
    end
    firstUse(readyAction())
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  subs, sent, used = {}, {}, {}
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  subs[#subs + 1] = hafen.event():action():on("use", record("use"))
  subs[#subs + 1] = hafen.event():action():on("rel", record("rel"))
  subs[#subs + 1] = hafen.event():on("ManeuverUsed", function(res, opponent, where)
    if opponent == nil and where == session then used[#used + 1] = res end
  end)
  local first = session:fight():action():get(1)
  local surplus = refusal(function() return first:use(0, session:player():gob():position(), 1) end)
  local notPlace = refusal(function() return first:use(0, { x = 1, y = 2 }) end)
  check(surplus ~= nil and surplus:find("at most 2", 1, true) ~= nil and notPlace ~= nil and #sent == 0,
    "a surplus argument and a table for a place are refused, and nothing is sent",
    tostring(surplus) .. " / " .. tostring(notPlace))
  local outside = refusal(function() return first:use() end)
  check(outside ~= nil and outside:find("not in a fight", 1, true) ~= nil and #sent == 0,
    "out of a fight action:use() is refused naming the fight, and nothing is sent", outside)
  prompt("Attack ONE chicken, and press Done once the row of actions shows under your character.", fighting,
    skipped("a fight with an animal was reached"))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)
```

---

- [ ] **170.5 — An addon switches the target, pursues and clicks give, under the fight keys.**

Three protected writes, each gated first by its own key: `session:fight():opponent():set(opponent)` (`fight.set`,
the view's `bump`, the client's "Switch targets"), `session:fight():pursue(opponent)` (`fight.pursue`, `prs`,
the Pursue button) and `session:fight():give(opponent, button)` (`fight.give`, `give` with the mouse button, the
give button; a new `Wire` row bounds it to `1..3`). `:set` takes an Opponent or its gob id, the other two the
Opponent; each refuses anything else, an opponent of another character's fight and one no longer fought, before
sending. `:current(x)`'s refusal now names `:set(opponent)`. The permissions guide's `fight.*` group lists all
four keys.

*Its suite* declares `fight.set`, `fight.pursue` and `fight.give`, and records `bump`, `prs` and `give` on the
outbound stream, cancelling the right-click `give` with `preventDefault` so it never reaches the server (its
meaning is the server's). Out of a fight: a Gob, a string and `nil` are refused, nothing sent. With two animals:
`:set(other)` sent `bump {its id}` and `:current()` and `OpponentSelected` followed; `:set(an id)` switched back;
`:pursue(target)` sent `prs {its id}`; `:give(target)` sent `give {id, 1}`, `:give(target, 3)` `give {id, 3}`,
`:give(target, 4)` refused; the give state `target:give()` reads moved. After the fight, `:set` and `:pursue` refuse
the opponent it had, naming `:exists()`.

`[manual]`: watch your character when the suite presses Pursue — expect it to run after the target. Look at the
give button beside the target's portrait — expect the half the suite names to be the one that changed.

**The passing log** (10 lines):

```text
[pass] :set() and :pursue() refuse a Gob and a string, :give() refuses nil, naming why, and nothing is sent
[pass] :set(other) sent bump {its gob id}, and :current() and OpponentSelected followed
[pass] :set(a gob id) switched back the same way
[pass] :pursue(target) sent prs {its gob id} from the combat view
[manual] watch your character now -- expect: it runs after the animal the fight has picked, as Pursue does
[pass] :give(target) sent give {id, 1}, :give(target, 3) give {id, 3}, and :give(target, 4) is refused
[pass] the give state target:give() reads moved after the left click
[manual] look at the give button beside the target's portrait -- expect: its left half is the one that changed when the suite clicked it
[pass] after the fight, :set() and :pursue() refuse the opponent it had, naming :exists(), and nothing is sent
[summary] 7 pass, 0 fail, 2 manual
```

**If the maintainer reports the other half changed**, the bits are the other way round: swap the two constants
in `LuaOpponent` (`static final int MINE = 2, THEIRS = 1;`), change the class javadoc's "bit 1 the left half"
sentence to "bit 2 the left half", rebuild, and re-run. Nothing else changes: the pages name the halves by side,
not by bit. Report the fact at the close.

**The edits, in order**

1. `src/io/brodgar/addon/Permission.java` — three keys, after `fight.use`
   Replace:

```java
    FIGHT_USE        ("fight.use",          "action:use",                     "use combat actions, in the fights of"
                                                                              + " any of your characters"),

```

   with:

```java
    FIGHT_USE        ("fight.use",          "action:use",                     "use combat actions, in the fights of"
                                                                              + " any of your characters"),
    FIGHT_SET        ("fight.set",          "session:fight():opponent():set", "switch the target, in the fights of any"
                                                                              + " of your characters"),
    FIGHT_PURSUE     ("fight.pursue",       "session:fight():pursue",         "press Pursue beside an opponent's"
                                                                              + " portrait, in the fights of any of"
                                                                              + " your characters"),
    FIGHT_GIVE       ("fight.give",         "session:fight():give",           "click the give button beside an"
                                                                              + " opponent's portrait, in the fights"
                                                                              + " of any of your characters"),

```

2. `src/io/brodgar/addon/Wire.java` — the give button's row, after the combat key's
   Replace:

```java
        // Fightsess "use" {n, 1, mods, [place]} (170.4) — a combat key: the button is always 1 and the modifiers a
        // keyboard's. The action menu's "use" is sent with no arguments and never reaches a row.
        SHAPES.put("use", new Shape() {
            public void check(String verb, Object[] a) {
                mods(verb, a, 2);
            }
        });

```

   with:

```java
        // Fightsess "use" {n, 1, mods, [place]} (170.4) — a combat key: the button is always 1 and the modifiers a
        // keyboard's. The action menu's "use" is sent with no arguments and never reaches a row.
        SHAPES.put("use", new Shape() {
            public void check(String verb, Object[] a) {
                mods(verb, a, 2);
            }
        });
        // Fightview "give" {gob, button} (170.5) — the give button beside a portrait, clicked with a mouse button.
        SHAPES.put("give", new Shape() {
            public void check(String verb, Object[] a) {
                button(verb, a, 1);
            }
        });

```

3. `src/io/brodgar/addon/LuaOpponent.java` — the class javadoc's paragraph on the writes
   Replace:

```java
 * <p><b>Once the fight with them ends</b> the relation's reads go {@code nil}, {@code :opening()} is empty and

```

   with:

```java
 * <p><b>The writes take it</b> (170.5): {@code :set(opponent)} on the collection switches the target, and
 * {@code s:fight():pursue(opponent)} and {@code s:fight():give(opponent, button)} press the two controls of
 * its relation box. {@link #target} turns what they were handed into a gob id and refuses an opponent of
 * another character's fight or one no longer fought; {@link #send} sends from THAT character's view.
 *
 * <p><b>Once the fight with them ends</b> the relation's reads go {@code nil}, {@code :opening()} is empty and

```

4. `src/io/brodgar/addon/LuaOpponent.java` — `:current(x)` names the write
   Replace:

```java
                    throw new LuaError(CharApi.FO + ":current() takes no argument — it reads the opponent that"
                        + " character's fight has picked");

```

   with:

```java
                    throw new LuaError(CharApi.FO + ":current() takes no argument — it reads the opponent that"
                        + " character's fight has picked; switching the target is " + CharApi.FO + ":set(opponent),"
                        + " under the \"fight.set\" permission");

```

5. `src/io/brodgar/addon/LuaOpponent.java` — `:set`, after `:current`
   Replace:

```java
        return LuaCollection.create(CharApi.FO, new LuaCollection.Source() {

```

   with:

```java
        // set(opponent) — 170.5, protected ("fight.set"), gated FIRST (D-213): switch the target, as the client's
        // "Switch targets" key does: the view's "bump" with that gob id, which the server answers with "cur", so
        // :current() follows a frame or more later. Takes an Opponent of this character's fight or the gob id :get
        // takes, as speed's set takes what its :get takes. Returns the collection, so writes chain.
        extra.set("set", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue me = a.arg1();
                AddonManager.requirePermission(AddonManager.current(), Permission.FIGHT_SET);
                LuaCollection.receiver(me, CharApi.FO, "set");
                Args.only(a, 1, CharApi.FO + ":set");
                long id = target(Args.required(a, 2, CharApi.FO + ":set", "opponent"), user, CharApi.FO + ":set",
                                 true);
                send(user, CharApi.FO + ":set", "bump", Integer.valueOf((int)id));
                return me;
            }
        });
        return LuaCollection.create(CharApi.FO, new LuaCollection.Source() {

```

6. `src/io/brodgar/addon/LuaOpponent.java` — the two helpers the writes share, after `current`
   Replace:

```java
    // ---- the collection -----------------------------------------------------------------------------

```

   with:

```java
    /**
     * 170.5: the gob id a write addresses, out of what the caller handed it: an Opponent of THIS character's
     * fight that it is still fighting or, where {@code idToo}, the gob id {@code :get} takes. Refused naming what
     * went wrong, before anything is sent.
     */
    static long target(LuaValue v, String user, String verb, boolean idToo) {
        LuaOpponent h = resolve(v);
        long id;
        if(h != null) {
            if(!h.user.equals(user))
                throw new LuaError(verb + "(opponent): that Opponent is in another character's fight — take it from"
                    + " this session's " + CharApi.FO + ". Nothing was sent.");
            id = h.gobid;
        } else if(idToo && (v.type() == LuaValue.TNUMBER)) {
            id = Args.integer(v, verb, "opponent", "an Opponent, or the gob id " + CharApi.FO + ":get(gobId) takes",
                              -Args.EXACT, Args.EXACT);
        } else {
            String got = (LuaGob.resolve(v) != null)
                ? ("a Gob: the fight names its relation to that creature as an Opponent, " + CharApi.FO
                   + ":get(gob:id())")
                : (v.type() == LuaValue.TNUMBER)
                ? ("a number: pass the Opponent itself, which " + CharApi.FO + ":get(gobId) hands you")
                : v.typename();
            throw new LuaError(verb + "(opponent): expected an Opponent, one " + CharApi.FO + " handed you — got "
                + got + ". Nothing was sent.");
        }
        if(!fighting(user, id))
            throw new LuaError(verb + "(opponent): that character is no longer fighting them (opponent:exists() is"
                + " false). Nothing was sent.");
        return id;
    }

    /** 170.5: send {@code msg} from THAT character's combat view, through the one door every write takes. */
    static void send(String user, String verb, String msg, Object... args) {
        Fightview fv = view(user);
        if(fv == null)
            throw new LuaError(verb + ": that character has no combat view (it is not in the world yet)."
                + " Nothing was sent.");
        Wire.send(user, verb, fv, msg, args);
    }

    // ---- the collection -----------------------------------------------------------------------------

```

7. `src/io/brodgar/addon/CharApi.java` — the fight section's javadoc names the writes
   Replace:

```java
     * {@code :action()} the combat row, {@code :cooldown()} the global cooldown and {@code :last()} the
     * manoeuvre that character used last.

```

   with:

```java
     * {@code :action()} the combat row, {@code :cooldown()} the global cooldown and {@code :last()} the
     * manoeuvre that character used last. Two protected actions press a relation box's controls (170.5):
     * {@code :pursue(opponent)} and {@code :give(opponent, button)}.

```

8. `src/io/brodgar/addon/CharApi.java` — `pursue` and `give`, at the end of the section
   Replace:

```java
        return Section.object("fight", fight, FT);

```

   with:

```java
        // pursue(opponent) — 170.5, protected ("fight.pursue"), gated FIRST (D-213): the Pursue button beside that
        // opponent's portrait, the view's "prs" with its gob id. Takes the Opponent only, as world():click takes
        // the Gob. Returns the section.
        fight.set("pursue", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requirePermission(AddonManager.current(), Permission.FIGHT_PURSUE);
                Section.self(a.arg1(), "fight", "pursue", FT);
                Args.only(a, 1, FT + ":pursue");
                long id = LuaOpponent.target(Args.required(a, 2, FT + ":pursue", "opponent"), user, FT + ":pursue",
                                             false);
                LuaOpponent.send(user, FT + ":pursue", "prs", Integer.valueOf((int)id));
                return a.arg1();
            }
        });
        // give(opponent, button) — 170.5, protected ("fight.give"), gated FIRST (D-213): the give button beside
        // that opponent's portrait, the view's "give" with its gob id and the mouse button, as GiveButton's own
        // press sends it (1 left, the default; 3 right). What either does is the server's. Returns the section.
        fight.set("give", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requirePermission(AddonManager.current(), Permission.FIGHT_GIVE);
                Section.self(a.arg1(), "fight", "give", FT);
                Args.only(a, 2, FT + ":give");
                long id = LuaOpponent.target(Args.required(a, 2, FT + ":give", "opponent"), user, FT + ":give",
                                             false);
                int button = Args.optint(a, 3, FT + ":give", "button", "1 the left button, 3 the right", 1);
                LuaOpponent.send(user, FT + ":give", "give", Integer.valueOf((int)id), Integer.valueOf(button));
                return a.arg1();
            }
        });
        return Section.object("fight", fight, FT);

```

9. `docs/addons/api/fight.md` — the `:current()` rule names the write
   Replace:

```text
`:current(x)` raises: it reads. |
```

   with:

```text
`:current(x)` raises: switching the target is [`:set(opponent)`](#write-protected). |
```

10. `docs/addons/api/fight.md` — three rows in the write table
   Replace:

```text
| `action:use(mods, position)` | the `CombatAction` | `fight.use` | Use that action, as tapping its combat key does. |

```

   with:

```text
| `action:use(mods, position)` | the `CombatAction` | `fight.use` | Use that action, as tapping its combat key does. |
| `session:fight():opponent():set(opponent)` | the collection | `fight.set` | Make that opponent the target, as the client's "Switch targets" key does. |
| `session:fight():pursue(opponent)` | `session:fight()` | `fight.pursue` | Press Pursue beside that opponent's portrait. |
| `session:fight():give(opponent, button)` | `session:fight()` | `fight.give` | Click the give button beside that opponent's portrait. |

```

11. `docs/addons/api/fight.md` — the write rules
   Replace:

```text
| Raises before anything is sent | An addon that did not declare `fight.use`, naming the key. A surplus argument. A `mods` that is not a whole number `0..7`, and a `position` that is not a Position. Out of a fight. An empty action (`action:empty()`), and one past the server's row. |
| Asynchronous | The server answers the use: the cooldowns start, [`ManeuverUsed`](event/bus/fight.md) fires and `session:fight():last()` names it, or nothing moves when the server refused it. Read them on the next frames. |
| The outbound stream sees it | Both messages pass [`hafen.event():action()`](event/streams.md#intercepting-an-outbound-action) like the client's own, so a handler can stop or rewrite them. |
```

   with:

```text
| The opponent | `:set` takes an `Opponent` or its gob id, the key `:get` takes, as [`session:speed():set`](speed.md#write-protected) takes what its `:get` takes. `:pursue` and `:give` take the `Opponent`. |
| `button` | Optional: `1`, the default, is the left button and `3` the right, as a click on the give button sends. What each does is the server's. |
| Raises before anything is sent | An addon that did not declare the verb's key, naming it. A surplus argument. For `action:use`: a `mods` that is not a whole number `0..7`, a `position` that is not a Position, no fight, an empty action (`action:empty()`) and one past the server's row. For the three that take an opponent: anything but an `Opponent` (or, for `:set`, its gob id), one of another character's fight, one that character no longer fights (`opponent:exists()` is `false`), and a `button` outside `1..3`. |
| Asynchronous | The server answers every write. A use: the cooldowns start, [`ManeuverUsed`](event/bus/fight.md) fires and `session:fight():last()` names it. A switch: `:current()` answers the old target until then, and [`OpponentSelected`](event/bus/fight.md) fires when it moves. Nothing moves when the server refused one. |
| The outbound stream sees them | Every message passes [`hafen.event():action()`](event/streams.md#intercepting-an-outbound-action) like the client's own, so a handler can stop or rewrite it. |
```

12. `docs/addons/guides/permissions.md` — three catalogue rows
   Replace:

```text
| `fight.use` | [`action:use`](../api/fight.md#write-protected) | use combat actions, in the fights of any of your characters |

```

   with:

```text
| `fight.use` | [`action:use`](../api/fight.md#write-protected) | use combat actions, in the fights of any of your characters |
| `fight.set` | [`session:fight():opponent():set`](../api/fight.md#write-protected) | switch the target, in the fights of any of your characters |
| `fight.pursue` | [`session:fight():pursue`](../api/fight.md#write-protected) | press Pursue beside an opponent's portrait, in the fights of any of your characters |
| `fight.give` | [`session:fight():give`](../api/fight.md#write-protected) | click the give button beside an opponent's portrait, in the fights of any of your characters |

```

13. `docs/addons/guides/permissions.md` — the group row
   Replace:

```text
| `fight.*` | `fight.use` |
```

   with:

```text
| `fight.*` | `fight.use`, `fight.set`, `fight.pursue`, `fight.give` |
```

14. `docs/addons/manifest.md` — what needs `1.3`
   Replace:

```text
[`action:use`](api/fight.md#write-protected), and [the fight events](api/event/bus/fight.md).
```

   with:

```text
[the writes](api/fight.md#write-protected) that use an action, switch the target, pursue and give, and [the fight events](api/event/bus/fight.md).
```

15. `docs/addons/api/README.md` — the section row
   Replace:

```text
the combat row and using it. |
```

   with:

```text
the combat row, and the writes that act in the fight. |
```

16. `docs/addons/api/references.md` — the thing the fight's writes take, after the combat row
   Replace:

```text
The row is the fight's own: every place is `:empty()` out of a fight ([`session:fight`](fight.md#a-combat-action)).

```

   with:

```text
The row is the fight's own: every place is `:empty()` out of a fight ([`session:fight`](fight.md#a-combat-action)).

## Opponent: who a character is fighting

`session:fight():opponent()` is every creature a character is fighting as `Opponent` objects: `:get(gobId)` one by its gob id, `:current()` the target. The writes that act on one take the `Opponent` (`session:fight():pursue(opponent)`, `session:fight():give(opponent, button)`), and `:set(opponent)` takes its gob id too, as `session:speed():set` takes what its `:get` takes. One of another character's fight is refused ([`session:fight`](fight.md#an-opponent)).

```

17. `addons/170-live-fight.5/manifest.json` — the suite's manifest: it declares the three keys
   Create the file with exactly this content:

```json
{
  "name": "170.5 — switching the target, pursuing, the give button",
  "id": "170-live-fight.5",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 170.5: session:fight():opponent():set, session:fight():pursue and session:fight():give send what the client's own controls send, take what they should, and refuse what they should before anything is sent.",
  "api_version": "1.3",
  "permissions": ["fight.set", "fight.pursue", "fight.give"],
  "files": ["main.lua"]
}
```

18. `addons/170-live-fight.5/main.lua` — the suite
   Create the file with exactly this content:

```lua
-- 170.5 — switching the target, pursuing, the give button. Self-checking suite; declares fight.set, fight.pursue
-- and fight.give.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local session, panel, waiter
local subs = {}
local sent = {}       -- every bump, prs and give the combat view sends: { msg, widget type, args }
local selected = {}   -- every OpponentSelected payload of this character
local stashed         -- an opponent kept past the fight's end
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if waiter then pcall(function() waiter:cancel() end) end
  if panel then pcall(function() panel:destroy() end) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("170.5"):size(500, 70)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function skipped(what)
  return function()
    check(false, what, "skipped")
    finish()
  end
end

-- The wire carries a gob id as a signed 32-bit number, so compare it modulo 2^32.
local function sameId(wire, id)
  return type(wire) == "number" and (wire % 4294967296) == (id % 4294967296)
end

-- Poll every 0.2 s until `ready()` answers true, for at most `limit` seconds, then run `go(ok)`.
local function waitFor(limit, ready, go)
  local spent = 0
  waiter = hafen.timer():every(0.2, guarded(function()
    spent = spent + 0.2
    if ready() or spent >= limit then
      waiter:cancel()
      waiter = nil
      go(ready())
    end
  end))
end

local function ended()
  local fight = session:fight()
  local from = #sent
  local setGone = stashed and refusal(function() return fight:opponent():set(stashed) end)
  local pursueGone = stashed and refusal(function() return fight:pursue(stashed) end)
  check(stashed ~= nil and setGone ~= nil and setGone:find(":exists()", 1, true) ~= nil and pursueGone ~= nil
      and pursueGone:find(":exists()", 1, true) ~= nil and #sent == from,
    "after the fight, :set() and :pursue() refuse the opponent it had, naming :exists(), and nothing is sent",
    tostring(setGone) .. " / " .. tostring(pursueGone))
  finish()
end

local function giving(target)
  local fight = session:fight()
  local id = target:id()
  local before = target:give()
  local from = #sent
  fight:give(target)
  fight:give(target, 3)
  local tooMany = refusal(function() return fight:give(target, 4) end)
  local left, right = sent[from + 1], sent[from + 2]
  check(left ~= nil and left.msg == "give" and sameId(left.args[1], id) and left.args[2] == 1 and right ~= nil
      and right.msg == "give" and sameId(right.args[1], id) and right.args[2] == 3 and tooMany ~= nil
      and tooMany:find("1, 2 or 3", 1, true) ~= nil and #sent == from + 2,
    ":give(target) sent give {id, 1}, :give(target, 3) give {id, 3}, and :give(target, 4) is refused",
    tostring(left and left.args[2]) .. " " .. tostring(right and right.args[2]) .. " / " .. tostring(tooMany))
  waitFor(3, function()
    local now = target:give()
    return before ~= nil and now ~= nil and (now.mine ~= before.mine or now.theirs ~= before.theirs)
  end, function(moved)
    local now = target:give()
    check(moved, "the give state target:give() reads moved after the left click",
      (before and (tostring(before.mine) .. "/" .. tostring(before.theirs)) or "-") .. " -> "
        .. (now and (tostring(now.mine) .. "/" .. tostring(now.theirs)) or "-"))
    local half = (moved and now.mine ~= before.mine) and "left" or "right"
    manualCheck("look at the give button beside the target's portrait",
      "its " .. half .. " half is the one that changed when the suite clicked it")
    stashed = target
    prompt("End the fight (win it or walk away), then press Done.", ended, skipped("the end of the fight was reached"))
  end)
end

local function pursuing(target)
  local fight = session:fight()
  local from = #sent
  fight:pursue(target)
  local prs = sent[from + 1]
  check(prs ~= nil and prs.msg == "prs" and prs.widget == "Fightview" and sameId(prs.args[1], target:id())
      and #prs.args == 1,
    ":pursue(target) sent prs {its gob id} from the combat view", prs and prs.msg or "nothing")
  manualCheck("watch your character now", "it runs after the animal the fight has picked, as Pursue does")
  after(2, function() giving(target) end)
end

local function switching()
  local fight = session:fight()
  local opponents = fight:opponent()
  local first = opponents:current()
  local other = opponents:find(function(each) return each ~= first end)
  if not (first and other) then
    check(false, ":set() switches the target", "one opponent -- attack two animals")
    return finish()
  end
  local from, seen = #sent, #selected
  opponents:set(other)
  local bump = sent[from + 1]
  waitFor(3, function() return opponents:current() == other end, function(ok)
    local named = false
    for index = seen + 1, #selected do
      if selected[index] == other then named = true end
    end
    check(ok and named and bump ~= nil and bump.msg == "bump" and bump.widget == "Fightview"
        and sameId(bump.args[1], other:id()),
      ":set(other) sent bump {its gob id}, and :current() and OpponentSelected followed",
      bump and (bump.msg .. " " .. tostring(opponents:current())) or "nothing")
    local back = #sent
    opponents:set(first:id())
    waitFor(3, function() return opponents:current() == first end, function(again)
      local bumped = sent[back + 1]
      check(again and bumped ~= nil and bumped.msg == "bump" and sameId(bumped.args[1], first:id()),
        ":set(a gob id) switched back the same way", bumped and bumped.msg or "nothing")
      pursuing(first)
    end)
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  subs, sent, selected, stashed = {}, {}, {}, nil
  session = hafen.session():current()
  if not (session and session:ui():match("@GameUI")) then
    check(false, "a character is in the world", "no HUD -- run :t170 logged in")
    return finish()
  end
  for _, msg in ipairs({ "bump", "prs", "give" }) do
    subs[#subs + 1] = hafen.event():action():on(msg, function(event)
      local args = event:args()
      sent[#sent + 1] = { msg = msg, widget = event:widget():type(), args = args }
      -- The right click's meaning is the server's: record it, and keep it from reaching the server.
      if msg == "give" and args[2] == 3 then event:preventDefault() end
    end)
  end
  subs[#subs + 1] = hafen.event():on("OpponentSelected", function(opponent, where)
    if where == session then selected[#selected + 1] = opponent end
  end)
  local fight = session:fight()
  local gob = session:player():gob()
  local refused = {
    refusal(function() return fight:opponent():set(gob) end),
    refusal(function() return fight:opponent():set("x") end),
    refusal(function() return fight:pursue(gob) end),
    refusal(function() return fight:give(nil) end),
  }
  local named = 0
  for index = 1, 4 do if refused[index] then named = named + 1 end end
  check(named == 4 and refused[1]:find("Opponent", 1, true) ~= nil and #sent == 0,
    ":set() and :pursue() refuse a Gob and a string, :give() refuses nil, naming why, and nothing is sent",
    named .. " of 4: " .. tostring(refused[1]))
  prompt("Attack TWO animals (a chicken and another) so both fight you, then press Done.", switching,
    skipped("a fight with two animals was reached"))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t170", function() after(0, run) end)
```
