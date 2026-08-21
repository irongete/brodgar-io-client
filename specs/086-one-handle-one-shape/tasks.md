# 086 — One handle, one shape: tasks

Eight tasks — six for the feature, and **086.7 and 086.8 are arrears from 084 and 085**, which froze
before these two were found. **1 before 2** (the collection lists what task 1 makes) and **1 before 6** (task 1 deletes
three of the handles task 6 would otherwise convert). 3, 4 and 5 are independent of everything.

Every suite keeps to **≤ 15 output lines**, so group: one verdict line per claim, scored
(`3/3 reached`) rather than one line per handle.

- [x] **086.1 — Every `:on` hands back a Sub.** Three registries stop minting plain tables.
      `Subs` grows a per-**subscription** hook — `interface Ended { void ended(LuaSub s); }`, called
      from `off()` and from `clear()` — beside the per-**key** `Idle` a widget already uses, and
      `LuaSub` grows one opaque `Object tag` field for what the emitter registered alongside the
      subscription. `LuaSub` also grows `sub:key()`, one closure over the `key` field it already
      carries. `Addon` gains `watchSubs`, `slashSubs` and `keySubs`, each built with its own `Ended`.
      Then: `UiApi.newSelectorWatch` keeps `selArg`, the event parse, the `LuaSelectorWatch`, both
      registrations and `scanForWatch`, and returns `owner.watchSubs.on(event, fn)` with the watch on
      the `tag` — **the key is the event**, `"appear"` or `"disappear"`, because that is what a person
      would name and the selector already lives on the record; `Ended` calls the existing
      `removeSelectorWatch`. `HookApi.newSlashCommand` keeps every refusal and the one
      engine-lifetime dispatcher — `docs/client/services.md`: a console command is *register only, no
      unregister* — and `Ended` clears `slashHandlers` alone, exactly as `teardownSlashCommands` does.
      `keybindings:register(name, fn)` becomes `keybindings:on(name, fn)`, with the `LuaKeyBind` on
      the `tag` and `Ended` calling `HookApi.removeKeyBindsNamed`; the `KeyBinding` registry entry
      survives a reload, as the page says. `Retired` gains `hafen.slash():register`,
      `keybindings:register` and `keybindings:unregister`. `handle:remove()` needs no row: the handle
      is a `Sub`, and `closedIndex` already answers *a subscription has no verb 'remove'*.
      *Its suite* asserts one shape three times, which is the claim: `hafen.slash():on("t086", fn)`,
      `s:ui():on("window", "appear", fn)` and `kb:on("t086key", fn)` each hand back **userdata**
      whose `tostring` reads `Sub(<key>)`, whose `:key()` is what it registered under, and whose
      `:off()` is idempotent — one scored line over the three. Then the mistake that used to be
      silent: `sub:remove()` must **raise** naming `:off()` on all three, which only a closed
      vocabulary can do. Then the retirements: `hafen.slash():register("x", fn)`,
      `kb:register("x", fn)` and `kb:unregister("x")` must each raise naming their replacement. Its
      refusal: `hafen.slash():on("has space", fn)` must still raise naming *a non-empty word with no
      spaces*, so wrapping the registration in `Subs` did not drop its own validation.
      `[manual]`: two. The suite leaves `:t086` registered and a hotkey bound to it. Type `:t086` in
      the console and report whether it runs; then run the suite's `:t086off` command and type
      `:t086` again, reporting what the console says. Nothing in Lua can make the client deliver a
      keypress or a console line, so this half is yours (`ROADMAP`, filed 061).
      *Audit*: **A-039** — *"`s:ui():on(sel, event, fn)` hands back a `Sub`"* (`audit/ns-ui.md` F2 · ROADMAP 061) ·
      **A-040** — *"`hafen.slash():on(name, fn)` → a `Sub`; `register` retires"* (`audit/ns-slash.md` F1) ·
      **A-041** — *"`keybindings:on(name, fn)` → a `Sub`; `register`/`unregister` retire"* (`audit/ns-client.md` F2).
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: docs/client/services.md (Console.setscmd, findcmd's three tiers), src/io/brodgar/addon/LuaSelectorWatch.java, LuaSlashCommand.java, LuaKeyBind.java -->

- [x] **086.2 — What you registered, you can list.** `Subs` grows one read, `List<LuaSub> live()`,
      walking its copy-on-write lists and skipping dead subs, because `byKey` is private and two
      collections now need it. `hafen.slash()` is **mounted as a `LuaCollection`** over
      `owner.slashSubs`, exactly as `hafen.timer()` is mounted over `owner.timers`, with `on` as the
      `extra` verb: `Source.named()` so a string filter matches the command name, `getMember(name)`
      finding the sub on that key, and `missing()` declaring `NIL` — a command you never registered
      is not a thing to mint. `hafen.event()` gains `:list(filter)` and `:count(filter)` over the
      hub's **three** emitters — `owner.subs`, `owner.actionSubs`, `owner.messageSubs`, in that order
      — and is deliberately not mounted as a collection, because it is a hub for three emitters
      rather than a set of one kind. A-042's `cmd:name()` ships as `sub:key()`: the member of the
      slash collection **is** its subscription, so one verb answers "what did I register this under"
      for the bus, the streams and the three registries alike.
      *Its suite* registers three commands and asserts the collection is one: `hafen.slash():count()`
      is 3, `ipairs(hafen.slash():list())` walks 3 — the array promise `conventions.md` makes —
      `hafen.slash():get("t086b"):key()` is `"t086b"`, `hafen.slash():find("086b")` finds it by the
      **string** filter, and `:get("nosuch")` is nil rather than an error. Then the bus: subscribe to
      `GobAdded` and to a message stream, assert `hafen.event():count()` rises by two and that
      `hafen.event():list(function(s) return s:key() == "GobAdded" end)` finds exactly the one — the
      three-emitter claim, which a bus-only list would fail. Then `sub:off()` on each and the counts
      fall back. Its refusal: `hafen.slash():on("t086c")` with no handler must raise naming `fn`, so
      mounting the section did not lose `Args.required`.
      `[manual]`: none.
      *Audit*: **A-042** — *"`hafen.slash()` becomes a collection of this addon's commands, with `cmd:name()` and
      `:get(name)`"* (`audit/ns-slash.md` F2) · **A-045** — *"`hafen.event():list(filter)` / `:count(filter)`
      and `sub:key()`"* (`audit/ns-event.md` F4).
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: src/io/brodgar/addon/LuaCollection.java (Source.named/needle/noGet/missing, create, receiver), AddonManager.java (the hafen.timer() mount, as the model) -->

- [x] **086.3 — A binding is an object.** `keybindings:list()` returns `{ [id] = key }`, so
      `ipairs` over it walks **nothing** and raises nothing — the one `:list()` in the API that is not
      an array. New `LuaBinding` over `haven.KeyBinding`, interned per addon by binding id on the
      `LuaFightSummary.Cache` pattern, and `keybindings:binding()` hands back a `LuaCollection` over
      `KeyBinding.all()` — this addon's hotkeys, other addons' and the client's own — with
      `Source.named()` on the id so a string filter works and `:get(id)` addresses one. The object
      carries the **three** states `KeyBinding` actually has, which `keybindings:key(name, k)` writes
      only two of: `binding:id()`; `binding:key()`, the effective key as a display string or `nil`
      when unbound; `binding:key("F5")` to assign, with `"None"` unbinding as today;
      **`binding:key(nil)` to put it back on the client's own default** — `KeyBinding.set(null)`, the
      meaning `nil` carries everywhere else in the API, and the write `ROADMAP` (filed 066) records as
      missing; `binding:default()`; `binding:assigned()`; plus `:exists()` and `:info()`. Without the
      last two the undo is unusable — nothing could tell "the user chose F5" from "F5 is the
      default", which is exactly how a save-and-restore turns every default into an assignment.
      `keybindings:list()` and `keybindings:key(name[, k])` retire, each naming
      `keybindings:binding():get(id)`.
      *Its suite* asserts the shape the row exists for: `#kb:binding():list()` equals
      `kb:binding():count()` and a bare `ipairs` walks every one of them — the loop that used to run
      zero times. Then the three states, on a binding the suite registered itself so nothing of the
      user's is disturbed: read `b:default()`, write `b:key("Ctrl+Alt+F9")` and read it back, assert
      `b:assigned()` is true, then `b:key(nil)` and assert `b:assigned()` is false **and**
      `b:key() == b:default()` — the round-trip `ROADMAP` 066 says nothing can do. Then
      `kb:list()` and `kb:key("x")` must each raise naming `:binding()`. Its refusal:
      `b:key("nonsense++")` must still raise naming the examples `"F5"`, `"Ctrl+M"`, `"None"`.
      `[manual]`: one. Open the client's Options window at its keybinding panel while the suite's
      binding is assigned, then after the revert, and report whether the panel shows the assigned key
      and then the default. The panel is the client's own widget and nothing in Lua reads it.
      *Audit*: **A-043** — *"`keybindings:binding()` — a collection of `Binding` — replaces `keybindings:list()`,
      which returns a map that `ipairs` silently skips"* (`audit/ns-client.md` F2). **It also ships
      A-103** — *"`KeyBinding.key` is three-state and the write handles two — a save-and-restore destroys
      the default"* (ROADMAP 066) — which the inventory files under **093**; 086.6 ticks it, because this
      task is where the change lands.
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: src/haven/KeyBinding.java (defkey, key, set, set(), key(), all — and the comment on set about reverting vs disabling), src/haven/KeyMatch.java (nil, name, reduce), docs/client/multi-session.md (KeyBinding.get runs no exclusivity pass) -->

- [x] **086.4 — A timer answers for itself.** `timer.md` documents a filter — *"a function called
      with each handle"* — over a handle whose only verb is `cancel`, so a predicate can test identity
      and nothing else, which `==` already does. `AddonManager.Timer` gains `final boolean repeats`,
      set from `newTimer`'s own argument rather than inferred from `interval > 0`, and `runTimers`
      tests it: that one change is also the whole of `ROADMAP` 070, because `:every(0, fn)` is stored
      today exactly like `:after(0, fn)`, fires once and is dropped, while the page says a repeating
      timer fires at most once per tick. A `t:repeats()` answering `false` there would document the
      defect rather than fix it. The handle becomes **userdata** over the `Timer`, with a per-addon
      metatable, `Retired.closedIndex` and a `__tostring` of `Timer(every 5s)` / `Timer(after 2s,
      fired)`, answering `:interval()` (seconds, `0` for a one-shot), `:repeats()`, `:due()` (seconds
      from now, `nil` once dead), `:alive()`, `:cancel()` unchanged and `:info()`. `Timer.handle`
      holds the userdata, so `hafen.timer():list()` goes on answering by identity as `timer.md`
      promises.
      *Its suite* asserts the filter now means something, which is the claim: with one `:every(5, fn)`
      and one `:after(3, fn)` scheduled, `hafen.timer():count(function(t) return t:repeats() end)` is
      **1**, and the same predicate through `:find` returns the very handle `:every` handed back, by
      `==`. Then the vocabulary: `t:interval()` is 5 and 0 respectively, `t:due()` is between 0 and
      its interval, `t:alive()` is true and false either side of `t:cancel()`, and `t:info()` carries
      the four. Then the two consequences of being userdata: `t.cancel = nil` must **raise**, and
      `t:nosuchverb()` must raise naming the vocabulary. Then `ROADMAP` 070: `hafen.timer():every(0,
      fn)` with a counter, checked by a `hafen.timer():after(1, …)`, must have fired **more than
      once**. Its refusal: `hafen.timer():after(nil, fn)` must still give the house nil message, not
      a type message.
      `[manual]`: none.
      *Audit*: **A-044** — *"the timer handle answers `:interval()` `:repeats()` `:due()` `:alive()` `:info()`, so
      its documented filter means something"* (`audit/ns-timer.md` F1), and the timer's share of **A-038**
      (`audit/03-lifecycle.md`), which 086.6 ticks.
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: src/io/brodgar/addon/AddonManager.java (newTimer, Timer, runTimers, clock, the hafen.timer() mount and its Source) -->

- [x] **086.5 — A file you loaded is an object.** Five handle kinds — image, mesh, data, font and map
      image — stop being tables of per-instance closures and become
      `LuaValue.userdataOf(record, meta(owner))` with a **shared per-addon** metatable whose methods
      resolve the record off `self`, the `LuaGob`/`LuaItem` pattern. Two metatables per kind per
      addon, not one: the **owner's** carries `:dispose()` and the **view** — what
      `AssetApi.imageFor` and `FontApi.handleFor` hand a rule from another addon's sheet — does not,
      because `asset.md` states that freeing an asset is the owner's to do. That distinction exists
      today as "which verbs were `set` on the table" and becomes "which metatable it was minted with".
      `addAssetVerbs` stops setting verbs on a table it is handed and becomes a contributor to the
      methods table; its `Disposer` moves onto the record, since the metatable is shared.
      `LuaImage.resolve`, `LuaMesh.resolve` and `FontHandle`'s marker lookup already accept either the
      wrapper table or the raw userdata, so the table branch becomes dead and goes — **and the forged
      look-alike goes with it**: `{__image = icon.__image, size = function() return {w=999,h=999} end}`
      is no longer a thing `g:image` will take. `FontApi.fontHandle` returns `LuaValue` rather than
      `LuaTable`; `FontHandle.handle`, `LuaImage.handle` and the two per-addon view caches keep their
      jobs and change only what they hold.
      *Its suite* ships its own `dot.png`, `tri.gltf` and `data.json`, so nothing it proves needs the
      world. It asserts each of the five is userdata whose `tostring` names the kind and the file, and
      that the reads still answer — `img:size().w`, `img:type()`, `img:path()`,
      `mdl:bounds().extent.z`, `data:text()`, `h:family()` — one scored line for the shape and one for
      the reads. Then the two things only userdata gives: `img.dispose = nil` must **raise**, and
      `img:sizes()` must raise naming the vocabulary instead of reading `nil`. Then identity, which
      four pages promise: `w:rule():font(h)` followed by `w:style().font == h`, and the same for
      `bg.image`. Then the forgery: `hafen.vr():sprite():add({}, p)` must refuse naming
      `hafen.asset()`. The map image needs the map database, so `grid:image(0)` is scored over a
      bounded `hafen.timer()` window. Its refusal: `hafen.asset():get("no.png")` must still raise
      naming the file, so the sandbox check and the `RAISE` promise survived the rewrite.
      `[manual]`: none.
      *Audit*: the asset half of **A-038** — *"the 11 plain-table handle kinds → userdata with `closedIndex` and
      `__tostring`"* (`audit/03-lifecycle.md`, §Handle types), ticked by 086.6.
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: src/io/brodgar/addon/AssetApi.java (imageHandle, meshHandle, dataHandle, addAssetVerbs, Disposer, imageFor, Entry, resolveAddonAsset), LuaImage.java, LuaMesh.java, FontApi.java (mint, fontHandle, handleFor), FontHandle.java, MapImages.java -->

- [x] **086.6 — A setting is an object.** Everything left. `OptionsHandle.create`'s `opts` table has
      **no metatable at all** — 084 gave the six panels it hands out a closed vocabulary and left the
      handle that hands them out bare, so `hafen.client():options():vidoe()` still reads `nil` today.
      It, the six `*Options`, `ProfHandle`, `ProfScope`, `VrApi`'s entity handle and `HttpApi`'s
      request all become userdata with a `__tostring`. For the nine that already carry a `closedIndex`
      metatable the change is `LuaTable` → userdata and nothing else, because their methods tables
      already live outside the value; `opts` gains a vocabulary for the first time. The vr entity is
      not one of A-038's rows and is included because the feature is named for the claim *every handle
      is one shape*, and because `LuaMesh.resolve`'s comment naming "the two of them that are tables"
      is what this task makes stale. 085's interning is preserved exactly: the seven lazy fields on
      `Addon` hold the userdata now.
      *Its suite* asserts the gap first, because it is the one nobody has seen:
      `hafen.client():options():vidoe()` must **raise** naming the panels. Then the shape, one scored
      line over eleven: `hafen.client():options()`, its six panels, `hafen.client():profiling()` and
      its `:scope("x")`, a vr ghost and an HTTP request are each userdata whose `tostring` names the
      thing. Then that 085's identity survived — `hafen.client():options() == hafen.client():options()`
      and `opts:video() == opts:video()` — and that the reads still answer, `opts:video():fpsLimit()`
      being a number. Then the write that used to be legal: `opts.video = nil` and `e.tint = nil` must
      each **raise**. The request is made against `127.0.0.1` on a port nothing listens on, with the
      host declared in the suite's own `manifest.json` `network` block, so the handle's shape —
      `:header()`, `:timeout()`, `:cancel()`, `req.cancel = nil` raising — is asserted without a
      network. Its refusal: `opts:audio():masterVolume(2)` must still name the `0.0..1.0` range, so
      the conversion did not swallow a range refusal.
      `[manual]`: one. A handle in a saved variable stops round-tripping: the suite writes one into a
      saved variable and calls `hafen.store():flush()`. Report the refusal the console shows, naming
      the path — a plain-table handle was carried and silently degraded before, and this is the
      moment an addon that stored one finds out.
      *Audit*: **A-038** — *"the 11 plain-table handle kinds → userdata with `closedIndex` and `__tostring`"*
      (`audit/03-lifecycle.md`, §Handle types) — this task finishes what 086.4 and 086.5 started, so it
      ticks it. It also ticks **A-103** (`audit/ns-client.md` F2 · ROADMAP 066), whose change shipped in
      086.3 and whose row the inventory files under 093.
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: src/io/brodgar/addon/OptionsHandle.java, AudioOptions.java, CameraOptions.java, ClientOptions.java, InterfaceOptions.java, VideoOptions.java, KeybindingsOptions.java, ProfHandle.java, ProfScope.java, HttpApi.java, LuaHttp.java, VrApi.java (entityHandle), StoreApi.java (carriable) -->

- [x] **086.7 — The optional arguments the sweep did not reach.** 084's own claim — *no LuaJ `bad
      argument` reaches an author*, and *a numeric string fails a number check* — is still false at
      **six sites, eight calls**, all of them the **optional** arguments of action verbs:
      `WorldApi`'s `place` (`button`, `mods`), `click` (`button`, `mods`) and `select` (`mods`),
      `LuaHand`'s `use` (`mods`), `LuaSlot`'s `use` (`mods`), and `VrApi`'s `y` field. Each is
      `a.arg(n).optint(d)` or `.optdouble(d)`, and LuaJ's `opt*` does two wrong things at once: a
      table falls through to `checkint()` and surfaces as *bad argument: number expected, got table*,
      and a numeric **string** scans as a number, so `s:world():place(p, 0, "1", "0")` is **taken**
      and sent. Five of the six are protected verbs, where the coerced value reaches the server.
      `s:world():place` is named by name in 084.4's task text: its required arguments were fixed, its
      optional ones were not, and 084.4's suite asserted exactly the argument that was fixed.
      **The helper already exists and is in the wrong place**: `LuaItem.count(a, i, verb, param, def)` is
      exactly it — `Args.written` for the nil-aware read, the default when the argument is absent,
      `Args.num` when it is present — and it has been private to one file while six others hand-rolled
      LuaJ's `opt*`. **That is why 084's sweep missed them: the pattern existed and was not reachable.**
      Fold it into `Args` as the optional twin of `num`, point `item:drop` and `item:transfer` at the
      moved copy, and give the six sites the same door. An explicit `nil` in a passed slot still refuses through
      `Args.nilRefused`, because `place(p, ang, nil, 0)` passes a fourth argument and so passes a
      third: omitted and explicitly-nil are not the same thing, and 084 already made that the rule.
      *Its suite* declares `world.place`, `world.select`, `gob.click` and `item.use`, and every check
      is an **argument refusal firing before anything is sent**, which is also what proves the grant.
      One scored line over the six sites for the wrong type: each must raise, each message must name
      its own verb and parameter, and **none may contain `"bad argument"`** — the negative is the
      whole assertion, because a message that names the verb is exactly what LuaJ cannot produce.
      Then the coercion, which is the half a type check alone would miss:
      `s:world():place(p, 0, "1", 0)` and `s:world():click(gob, "1")` must each refuse naming a
      number, while `s:world():place(p, 0, 1, 0)` reaches the gate — so the refusal is about the
      type and not about the value. Then the nil: `s:world():place(p, 0, nil, 0)` must give the
      house nil message. Its refusal: `s:world():place(p)` with no angle must still raise naming
      `angle` as a required argument, so adding an optional helper did not loosen a required one.
      `[manual]`: none — every check fires before the wire, so nothing needs a server.
      *Audit*: **A-121** — *"the eight optional arguments of the action verbs still reach LuaJ's
      `optint`/`optdouble`"* (`audit/06-arity-and-nil.md`, `audit/07-errors-and-refusals.md`). It is an
      **arrear**: A-005 and A-009 were ticked for 084 and were not wholly true, and both rows now carry a
      forward pointer to this one.
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: src/io/brodgar/addon/Args.java (num, passed, nilRefused), WorldApi.java (place, click, select and their placeArgs/clickGobArgs/selArgs), LuaHand.java, LuaSlot.java, VrApi.java (the opts.y read), docs/addons/api/world.md (the optional-argument rows), docs/addons/api/ui/items.md, docs/addons/api/actionbar.md -->

- [ ] **086.8 — The last colour reader.** `Stock.color(Object)` builds `{[1], [2], [3], [4]}`, so
      `sheet:stock()` hands back **positional** colours while `Chrome`'s readers — `w:style().bg.color`,
      a glow's, a border's — hand back **keyed** through `AddonManager.color`. Both are the
      stylesheet's own colour read back; both javadocs give the same reason, *"the value in the shape
      the setter takes, so a read round-trips into a write"*, and reach opposite conclusions. So
      `w:style().bg.color.r` answers and `sheet:stock()`'s equivalent is `nil`, which is the silent
      miss 085 exists to kill. `Stock.color` uses `AddonManager.color`; `Stock.color1` and the
      sequence builder follow if they carry the same shape. **085 saw this and wrote it down** as a
      stated exception on `shapes.md` — so this is a judgement being revisited, not a defect being
      caught, and one word from the maintainer keeps the exception instead. The argument for closing
      it: both spellings are legal **input**, so `sheet:load(sheet:stock())` round-trips either way
      and the change costs nothing but the paragraph. `shapes.md` loses its
      *"the one thing that comes back written the other way"* sentence and
      `ui/style/README.md` §The client's own look loses the matching note.
      *Its suite* asserts the two readers now agree, which is the whole of it: walk `sheet:stock()`
      to a surface that carries a colour and assert `.r`, `.g`, `.b`, `.a` answer and `[1]` is
      **nil** — both directions, since only the pair proves the shape moved rather than widened —
      then read the same property through `w:rule()` / `w:style()` on a widget the suite owns and
      assert the two carry the same four numbers under the same four keys. Then the round-trip that
      must not break: `hafen.ui():sheet():load(hafen.ui():sheet():stock()):install()` completes, and
      a colour read back out of it still answers `.r`. Its refusal: one of the two keys whose colour
      the client **walks** must still refuse a single colour, naming `palette` and `generate`, so
      changing the reader did not disturb `Chrome.seqShape`.
      `[manual]`: one. Install the stock sheet the suite round-tripped and report whether the client
      looks unchanged — a program can compare tables, and only an eye can say the window chrome,
      the chat and the tooltips are the colours they were.
      *Audit*: **A-122** — *"`Stock.color` still builds a positional colour, so `sheet:stock()` disagrees with
      `w:style()` on the same stylesheet property"* (`audit/ns-world.md` F2, `audit/12-types-and-shapes.md`).
      An **arrear** of A-020 and A-022, which were ticked for 085 and were not wholly true.
      **Read those `audit/` pages before starting** — each carries the evidence, the cost written in a
      user's own code, and the replacement the one-line row summarises. `/end` ticks and strikes these
      ids in `audit/INVENTORY.md`, and nothing else in that file is touched.
      <!-- extra context: src/io/brodgar/addon/Stock.java (color, color1, sequence), Chrome.java (the toLua readers), AddonManager.java (color), docs/addons/api/shapes.md (the Colours section), docs/addons/api/ui/style/README.md (The client's own look) -->

## When the feature closes

`/end` runs per task and ticks that task's own rows. Across the eight, **eleven** rows are ticked and
struck in `audit/INVENTORY.md`:

| Rows | Ticked by | Note |
|---|---|---|
| A-039, A-040, A-041 | 086.1 | |
| A-042, A-045 | 086.2 | A-042's row carries its strike reason: the member of the slash collection **is** its subscription, so `cmd:name()` shipped as `sub:key()` |
| A-043 | 086.3 | |
| A-044 | 086.4 | |
| **A-038** | 086.6 | begun by 086.4 and 086.5, finished here |
| **A-103** | 086.6 | the change shipped in 086.3; the inventory files the row under **093**, and 086 takes it because 086.3 is where it landed. 093's own `spec.md` must not claim it again |
| **A-121** | 086.7 | an **arrear** of A-005 and A-009 (084) |
| **A-122** | 086.8 | an **arrear** of A-020 and A-022 (085) |

Nothing else in the file is touched. An id never moves.

With 086.1 … 086.5 closed the open count stands at **78**. When the last task closes,
`grep -c '^| ☐' audit/INVENTORY.md` must print **74**, and

```bash
comm -23 <(grep -oE 'A-[0-9]{3}' audit/INVENTORY.md | sort -u) <(grep -rhoE 'A-[0-9]{3}' specs/*/spec.md | sort -u)
```

must no longer name A-038, A-103, A-121 or A-122. The only id that legitimately stays unclaimed for
the whole sweep is **A-011**, struck at Step 0 by D3 before any `spec.md` existed.

Three `specs/ROADMAP.md` lines are covered by this scope and are the maintainer's to strike:
**061** (`hafen.ui():on(sel, event, fn)` is the one `:on` whose handle ends with `:remove()`),
**070** (`hafen.timer():every(0, fn)` fires once and is dropped) and **066** (nothing puts a binding
back on its default — A-103).
