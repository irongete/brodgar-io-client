# 086 — One handle, one shape: plan

## Approach

Six tasks. Three make the registries behave like the rest of the API; three make every handle the
same kind of Lua value.

**The order matters once.** Tasks 1 and 2 turn a watch, a slash command and a hotkey registration
into `Sub`s, which **deletes three of the ten plain-table handles** before task 6 has to convert
them. Doing 6 first would convert three tables that then stop existing. Everything else is
independent.

### The mechanism tasks 1 and 2 add to `Subs`

`Subs` today has an optional per-**key** `Idle` hook, called when a key's last live handler goes; a
widget's `Subs` uses it to deafen the one engine listener it installed. The three registries need a
per-**subscription** hook instead — one watch of many on `"appear"` must remove *its own* record —
so `Subs` grows a sibling:

```java
/** Notified when ONE subscription ends, from off() and from clear(). Null where nothing engine-side
 *  hangs off an individual sub — the bus, the two streams. */
interface Ended { void ended(LuaSub s); }
```

and `LuaSub` grows one field:

```java
/** What the emitter registered alongside this subscription — a selector watch, a slash handler, a
 *  hotkey — or null on the bus. Opaque here: only the Subs that made it reads it back. */
Object tag;
```

`Subs.off(LuaSub)` and `Subs.clear()` both call `ended` for each sub they drop. That is the whole
addition: three registries then hand back real `Sub`s and their teardown runs where it always did.

`LuaSub` also gains `sub:key()` — one closure over the `key` field it already has. It is what makes a
slash `Sub` identifiable as a command and what A-045's filters match on.

### 1 — Every `:on` hands back a Sub

`Addon` gains three `Subs`: `watchSubs`, `slashSubs`, `keySubs`, each built with an `Ended` hook.

- **The watch.** `UiApi.newSelectorWatch` keeps everything it does — `selArg`, the event parse, the
  `LuaSelectorWatch`, the two registrations, `scanForWatch` — and instead of building a table it
  calls `owner.watchSubs.on(event, fn)` and hangs the `LuaSelectorWatch` on the sub's `tag`. The key
  is the **event**, `"appear"` or `"disappear"`, so `sub:key()` answers something a person reads;
  the selector lives on the watch record, which is where it already lives. `Ended` calls the existing
  `removeSelectorWatch(owner, w)`.
- **The slash command.** `HookApi.newSlashCommand` keeps every refusal (reserved name, whitespace,
  the `findcmd` collision check) and the one engine-lifetime dispatcher. `docs/client/services.md`:
  Console commands are *"register only; no unregister"* — so `Ended` drops the handler from
  `slashHandlers` and **leaves the dispatcher installed**, exactly as `teardownSlashCommands` does
  today. The sub's key is the command name; its `tag` is the `LuaSlashCommand`.
- **The hotkey.** `keybindings:register(name, fn)` becomes `keybindings:on(name, fn)` →
  `owner.keySubs.on(name, fn)`, with the `LuaKeyBind` on the `tag`; `Ended` calls
  `HookApi.removeKeyBindsNamed(owner, name)`. The `KeyBinding` registry entry survives, as it does
  today and as the page says — the user's remap outlives a reload.

Retired: `hafen.slash():register` (a section verb — `Retired.moved`'s exact case),
`keybindings:register` and `keybindings:unregister`. `handle:remove()` needs no row: the handle is
now a `Sub`, and `closedIndex` already answers *a subscription has no verb 'remove' — use sub:off()*.

### 2 — What you registered, you can list

`Subs` gains one read — `List<LuaSub> live()`, walking `byKey` and skipping dead subs — because
`byKey` is private and three collections now need it.

- `hafen.slash()` is mounted as a `LuaCollection` over `owner.slashSubs`, the way `hafen.timer()` is
  mounted over `owner.timers`, with `on` as the `extra` verb. Its `Source` declares `named()` so a
  string filter matches the command name, and `getMember(key)` finds the sub whose key is that name.
  `missing()` is `NIL` — a command you never registered is not a thing to mint.
- `hafen.event()` gains `:list(filter)` and `:count(filter)` over the hub's three emitters —
  `owner.subs`, `owner.actionSubs`, `owner.messageSubs`, concatenated in that order. It is **not**
  mounted as a collection: `hafen.event()` is a hub for three emitters, not a set of one kind.

### 3 — A binding is an object

New `LuaBinding` over `haven.KeyBinding`, interned per addon by binding id, on the `LuaFightSummary`
`Cache` pattern. `keybindings:binding()` hands back a `LuaCollection` whose members are every
`KeyBinding.all()` — this addon's, other addons', and the client's own — with `Source.named()` on the
id so a string filter works and `:get(id)` addresses one.

`KeyBinding` is three-valued and the object exposes all three:

| Verb | Reads / writes |
|---|---|
| `binding:id()` | the registry id, its identity |
| `binding:key()` | the **effective** key as a display string, or `nil` when unbound |
| `binding:key("F5")` | assign — `b.set(parseKeyMatch(k))`, and `"None"` unbinds, as today |
| `binding:key(nil)` | **back to the client's own default** — `b.set(null)`, the meaning `nil` carries everywhere else in the API |
| `binding:default()` | the default key as a display string, or `nil` where the default is unbound |
| `binding:assigned()` | whether the key is the user's or the client's — `KeyBinding.set()` |

`keybindings:list()` and `keybindings:key(name[, k])` retire, each naming
`keybindings:binding():get(id)`. `keybindings:on` and the collection are then the whole of the
handle, which is why task 6 finds nothing left on it to preserve.

### 4 — A timer answers for itself

`AddonManager.Timer` gains `final boolean repeats`, set from `newTimer`'s `repeat` argument rather
than inferred from `interval > 0`, and `runTimers` tests it. That is the one-line half of
`ROADMAP` 070: `:every(0, fn)` then reschedules with `due += 0`, so it fires once per tick, which is
what `timer.md` already says a repeating timer does.

The handle becomes userdata over the `Timer` with a per-addon metatable, `closedIndex`, and a
`__tostring` of `Timer(every 5s)` / `Timer(after 2s, fired)`. It answers `:interval()` (seconds,
`0` for a one-shot), `:repeats()`, `:due()` (`t.due - AddonManager.clock`, seconds, `nil` once dead),
`:alive()`, `:cancel()` unchanged, and `:info()`. `Timer.handle` becomes the userdata, so
`hafen.timer():list()` goes on answering by identity, as `timer.md` promises.

### 5 — A file you loaded is an object

Five kinds, one shape: image, mesh, data, font and map image. Each stops being a table of per-instance
closures and becomes `LuaValue.userdataOf(record, meta(owner))` with a **shared per-addon** metatable
whose methods resolve the record off `self` — the `LuaGob`/`LuaItem` pattern, and the reason a handle
in a draw callback stops allocating a closure table.

Two metatables per kind per addon, not one: the **owner's** carries `:dispose()`, the **view**
(`AssetApi.imageFor`, `FontApi.handleFor` — a rule from another addon's sheet) does not, because
`asset.md` states that freeing an asset is the owner's to do. That distinction exists today as "which
verbs were `set` on the table" and becomes "which metatable it was minted with".

`LuaImage.resolve`, `LuaMesh.resolve` and `FontHandle`'s marker lookup already accept **either** the
wrapper table or the raw userdata (`v.istable() ? rawget(KEY) : v`); after this the wrapper *is*
userdata, so the table branch becomes dead and goes, and the forged-look-alike hole goes with it.

### 6 — A setting is an object

Everything left: `OptionsHandle.create`'s `opts` (which has **no metatable at all** — 084 gave the
six panels a vocabulary and left the handle that hands them out bare), the six `*Options`, `ProfHandle`,
`ProfScope`, `VrApi`'s entity handle and `HttpApi`'s request. The six panels, the two profiling
handles and the vr entity already carry a `closedIndex` metatable, so for them the change is
`LuaTable` → userdata plus a `__tostring`: their methods tables already live outside the value.

The vr entity is not in A-038's list and is included anyway, because the feature is named for the
claim *every handle is one shape* and one exception makes the sentence false — and because
`LuaMesh.resolve`'s comment names it and the keybindings handle as "the two of them that are tables",
which this task is what makes stale.

## Files to create/modify

**New:** `src/io/brodgar/addon/LuaBinding.java` (3) · `addons/086-one-handle-one-shape.1` … `.6`

**Bridge**, under `src/io/brodgar/addon/`:

| File | Change | Task |
|---|---|---|
| `Subs` | the `Ended` interface, a fourth constructor, `off`/`clear` call it, `live()` | 1, 2 |
| `LuaSub` | the `tag` field, `sub:key()`, the `closedIndex` hint grows | 1, 2 |
| `Addon` | `watchSubs`, `slashSubs`, `keySubs`; a `LuaBinding.Cache` | 1, 3 |
| `UiApi` | `newSelectorWatch` returns a `Sub`; `removeSelectorWatch` is called from `Ended` | 1 |
| `HookApi` | `newSlashCommand` returns a `Sub`; `teardownSlashCommands` becomes the `Subs` teardown; `removeKeyBindsNamed` is called from `Ended` | 1 |
| `KeybindingsOptions` | `register`→`on`; `unregister`, `list`, `key` retire; `binding()` | 1, 3 |
| `AddonManager` | the `hafen.slash()` mount moves to a collection; `Timer.repeats`; `runTimers`; `newTimer` mints userdata | 2, 4 |
| `HookApi` / `LuaSlashCommand` | `LuaSlashCommand` becomes the sub's `tag`, or is folded into it | 1, 2 |
| `LuaCollection` | nothing — its `Source` already carries `named`, `needle`, `noGet`, `missing` | 2, 3 |
| `AssetApi`, `LuaImage`, `LuaMesh`, `FontApi`, `FontHandle`, `MapImages` | five handle kinds become userdata | 5 |
| `OptionsHandle`, the six `*Options`, `ProfHandle`, `ProfScope`, `VrApi`, `HttpApi`, `LuaHttp` | the rest become userdata | 6 |
| `Retired` | rows for `hafen.slash():register`, `keybindings:register`, `:unregister`, `:list`, `:key` | 1, 3 |

**Pages** — the list and the row each task owns is in `spec.md` §Docs impact.

**Consumers — task 1 breaks four of the five addons, at eleven call sites.** `hafen.slash():register`
and `keybindings:register` are retired verbs after task 1, and every one of these raises at load:

| Addon | Sites |
|---|---|
| `eventstack` | `hafen.slash():register("eventstack", …)`, `keys:register("toggle", …)`, `keys:register("pause", …)` |
| `profiler` | `hafen.slash():register("profiler", …)`, `keys:register("toggle", …)`, `keys:register("pause", …)` |
| `session-manager` | `hafen.slash():register("sessions", …)`, `keybindings():register("next", …)` |
| `widgetstack` | `hafen.slash():register("widgetstack", …)`, `:register("selector", …)`, `keybindings():register("freeze", …)` |

Each becomes `:on(...)`, and each return value that was kept to call `:remove()` on becomes a `Sub`.
`widgetstack`'s comment at its `freeze` binding quotes the old spelling in prose and moves with it.
Task 1 fixes all four in the same commit — `profiler` and `widgetstack` are the two tools `CLAUDE.md`
says are fixed when a change breaks them, and the other two are shipped addons. `clickpath` registers
nothing and is the one that only tasks 5 and 6 can reach.

## Risks and gotchas

**A console command cannot be unregistered.** `docs/client/services.md` states it and
`HookApi.slashDispatched` is built around it: the `Console.setscmd` dispatcher is installed once per
engine lifetime and routes to whatever `slashHandlers.get(cmd)` holds. `Ended` must clear the
**handler**, never try to remove the dispatcher. Getting this wrong looks fine until a `:reload`,
after which the command silently does nothing.

**`Subs.off` does not mark the sub dead — `LuaSub`'s `off` verb does.** `LuaSub.off` sets
`s.alive = false` and then calls `s.subs.off(s)`; `Subs.clear` sets it directly. Any new path that
ends a subscription (an `Ended` hook that ends siblings, a teardown) must keep that pairing, or
`Subs.live()` and `sub:key()` will report a sub that no longer fires.

**`Subs.on` is called from any thread.** A subscription can be made or ended inside a running
handler, on whichever thread that fire is on — which is why `byKey`'s lists are copy-on-write and
`wild` is volatile. `live()` must walk the copy-on-write lists and never sort or mutate them, and the
new `Ended` hook runs on the caller's thread, so `removeSelectorWatch` and `removeKeyBindsNamed` must
already be safe there. They are called from teardown today, which is the UI thread — check before
assuming.

**`scanForWatch` calls back *inside* registration.** An `appear` watch fires for what is already open,
under the `ui` monitor, before `newSelectorWatch` returns. So the handler can run before the `Sub`
exists as a Lua value. Build the sub first and scan second, or the callback sees a handle its own
registration has not produced yet.

**The watch key is the event, and several watches share it.** That is why the hook is per-sub
(`Ended`) and not per-key (`Idle`): `Idle` fires only when the last `"appear"` watch goes, which is
not when one watch is removed.

**`KeyBinding.set(null)` reverts and `set(KeyMatch.nil)` unbinds — and the class already carries an
`// addon:` edit for the second.** Read the comment on `set`: *"Reverting-to-default (null) and
disabling (nil) never steal"* — the exclusivity pass that stops one key driving two actions is
skipped for both. So `binding:key(nil)` needs no core edit; it needs `b.set(null)` and a `Utils`
pref write, which `set` already does.

**`KeyBinding.get` runs none of `set`'s exclusivity pass** (`docs/client/multi-session.md`), so two
*defaults* sharing a key leave both firing. Reverting a binding to its default can therefore
re-create a collision that assigning could not. That is engine behaviour, out of scope, and worth one
sentence on `client/keybindings.md` rather than a silent surprise.

**`:every(0, fn)` repeating changes a running client's behaviour.** Any addon that wrote
`hafen.timer():every(0, fn)` expecting a one-shot starts firing every tick. Nothing under `addons/`
does — grep before landing task 4 — and `timer.md` has always said it repeats, so this is the page
becoming true rather than a new promise.

**A handle in a saved variable stops round-tripping.** `StoreApi`'s `carriable` check walks a saved
table and refuses what cannot be persisted; a plain-table handle is carried today (as a table of
functions, which degrades), and a userdata one is **refused naming the path**. That is the right
answer — a handle never round-tripped — but an addon that stored one will now hear about it. Task 6's
suite should show the refusal rather than let it surprise someone.

**`Json.write` is forgiving and gets better.** A function, userdata or thread is written as its
quoted `tostring()`, so a handle inside a serialised table becomes `"Timer(every 5s)"` instead of an
empty object. Worth one line on `json.md` only if a page claims otherwise; check before writing.

**Identity is documented in five places and must survive.** `client/README.md` ("the same handle every
time"), `timer.md` ("it holds the same handles `:after` and `:every` gave you, so `==` finds"),
`asset.md`/`ui/style/README.md` (`w:style().font == body`, `w:style().bg.image == panel`),
`session.md` and `map/README.md`. `FontApi.handleFor` and `AssetApi.imageFor` already intern per
addon (`reader.assets.fontView` / `imageView`), and `Timer.handle` and `LuaImage.handle` hold the
minted value — keep every one of those fields, just change what they hold.

**`addAssetVerbs` sets verbs on a table it is handed.** With userdata there is no table to set on:
it becomes a contributor to a methods table built once per kind per addon. Its `Disposer` closes over
the record, so the disposer moves onto the record (or a per-record map) rather than the metatable,
which is shared.

**The build hides a moved symbol.** Tasks 5 and 6 change return types across a dozen files
(`FontApi.fontHandle` returns `LuaTable` today); `ant hafen-client` is incremental, so
`rm -rf build/classes` before believing a green build.

**Existing refusals must not be swallowed.** The reserved-slash-name refusal, the
already-a-client-command refusal, the `"cannot parse key"` refusal, `font:derive()`'s
takes-no-arguments refusal and the asset sandbox check (`resolveAddonAsset`) all sit beside the code
these tasks rewrite. Each task's suite asserts one of them still fires.

## Discarded alternatives

- **Giving `Subs` a per-key `Idle` hook for all three registries instead of a per-sub `Ended`.**
  `Idle` fires when a key empties, which is right for a widget deafening one engine listener and
  wrong for a selector watch: several watches share the key `"appear"`, and removing one must remove
  one record. A per-sub hook is the general case and the per-key one is the special one.
- **Keying a selector watch on `selector .. "\0" .. event`, so each watch has its own key.** It makes
  `Idle` sufficient and makes `sub:key()` answer an unreadable composite. The event is the key a
  person would name, and the selector is already on the watch record.
- **Keeping `cmd:name()` as A-042 wrote it, with a `Command` type.** A slash registration is a
  subscription; giving it a second object with a second spelling for "what I registered under" is the
  dual style `CLAUDE.md` forbids. `sub:key()` answers it for the bus, the streams and the three
  registries alike.
- **Mounting `hafen.event()` as a collection.** It is the hub for three emitters and its section
  object would then have to be one of them. Two verbs on the hub say the same thing without pretending
  the bus is the whole of it.
- **Listing a widget's own subscriptions from `hafen.event():list()`.** They belong to the widget and
  die with it; folding them into the hub's list would make the count answer a question nobody asked
  and would keep a destroyed widget's subs visible until the sweep.
- **Leaving `keybindings:key(name, k)` beside the collection as a shortcut.** It is the
  address-plus-value form the collection replaces, and keeping both is two ways to write one thing.
  Its `nil` return for "unbound" and for "no such binding" is also the ambiguity the object removes
  for free.
- **Adding `binding:key(nil)` without `:default()` and `:assigned()`.** The undo would be unusable:
  an addon that saves and restores keys cannot tell "the user chose F5" from "F5 is the default", so
  restoring turns every default into an assignment — which is the defect `ROADMAP` 066 records, not
  a fix for it.
- **Inferring `t:repeats()` from `interval > 0` rather than storing the flag.** It answers `false`
  for `:every(0, fn)`, which is the bug `ROADMAP` 070 records, dressed up as a read. A verb that
  reports a defect faithfully is worse than no verb, because it makes the defect look intended.
- **Making the handles tables with a `closedIndex` metatable rather than userdata**, as the six
  options panels are today. It fixes the typo and leaves the other two: the table is still writable
  from Lua, so an addon can still delete its own `cancel`, and a look-alike is still accepted by
  `LuaImage.resolve`. Userdata is what every other handle in the API already is.
- **One metatable per asset kind, with `:dispose()` on it and a runtime owner check.** The reduced
  view exists so that an addon *cannot* free a file it never loaded; a check inside the verb makes
  that a refusal at call time instead of an absence at read time, which is a weaker guarantee and a
  worse message.
- **Converting the ten plain handles before turning three of them into `Sub`s.** Three of the
  conversions would be thrown away, and the suites that proved them would be archived proving a shape
  that no longer exists.
