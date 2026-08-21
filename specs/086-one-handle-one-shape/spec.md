# 086 — One handle, one shape

Discharges: A-038, A-039, A-040, A-041, A-042, A-043, A-044, A-045.

## What and why

084 gave every **object** a closed vocabulary, so a typo names the fix. 085 gave every **value** one
shape. This feature is the third kind: the thing a verb hands back so you can act on it later — a
timer, a command, a watch, a request, a loaded file, a settings panel. Ten of those are still plain
Lua tables with **no metatable at all**, so a typo on one reads `nil` and fails a call later as
*attempt to call a nil value*, naming nothing.

**The ten.** `hafen.client():options()` itself (`OptionsHandle.create`'s `opts` — 084 gave the six
panels it hands out a vocabulary and left the handle that hands them out bare), a timer
(`AddonManager.newTimer`), a slash command (`HookApi.newSlashCommand`), a selector watch
(`UiApi.newSelectorWatch`), an HTTP request (`HttpApi.newHttpRequest`), an image, a mesh and a data
file (`AssetApi`), a font handle (`FontApi.mint`) and a map image (`MapImages.handleFor`). Nine more
are `LuaTable` **with** a `closedIndex` metatable — the six `*Options`, `ProfHandle`, `ProfScope` and
`VrApi`'s entity handle — so a typo refuses, but the table is still writable from Lua and prints as
`table: 0x…`.

Three consequences, all silent:

- `tostring(h)` is unreadable, so a log line of an addon's own handles says nothing. `Session(alice)`
  and `Sub(GobAdded)` both print something a person can use.
- **An addon can break its own teardown.** `timer.cancel = nil`, `icon.dispose = nil` and
  `req.cancel = nil` are all legal, and each leaks the resource the API promises to release.
- **A look-alike is accepted.** `{__image = icon.__image, size = function() return {w=999,h=999} end}`
  is taken by `g:image`, because `LuaImage.resolve` reads the marker field off whatever table it is
  given, and it then lies about its size wherever it is passed.

**And three registries do not use the API's one notification verb.** `CLAUDE.md` states the
invariant — *"One notification verb: `X:on(key, fn)` → a `Sub`, ended with `sub:off()`"* — and
`LuaSub`'s own javadoc says *"`:off()` is the one left, wherever the subscription came from"*. Three
of the five sites disagree:

| Spelling | Hands back | Ended with |
|---|---|---|
| `hafen.event():on`, `w:on`, `pag:on`, `grab:on`, `stream:on` | a `Sub` | `sub:off()` |
| `s:ui():on(sel, event, fn)` | a plain table | `handle:remove()` |
| `hafen.slash():register(name, fn)` | a plain table | `handle:remove()` |
| `keybindings:register(name, fn)` | the keybindings handle | `keybindings:unregister(name)` |

A user who learned the bus writes `cmd:off()` and gets `attempt to call a nil value` — no metatable,
so no message and no fix.

**And the registries cannot be read back.** `hafen.timer()`, `hafen.sound()`, `hafen.asset()` and
`hafen.vr():ghost()` all answer "what of mine is live". `hafen.slash()` carries `register` and
nothing else; `hafen.event()` carries `on`, `action` and `message`; and a timer handle carries
`cancel` alone — so `timer.md`'s documented filter, *"a function called with each handle"*, has
nothing to test but identity, which `==` already does. **Documented surface that nothing can drive.**

**And one `:list()` is a map.** `keybindings:list()` returns `{ [id] = key }` while
`conventions.md` §Collections says `:list(filter)` is "a plain array of members". So

```lua
for i, k in ipairs(hafen.client():options():keybindings():list()) do   -- iterates NOTHING
```

runs zero times, raises nothing, and looks exactly like "no bindings". It takes no filter either.

## The decisions this feature takes

Five calls the audit rows did not make. Each is reversible with one word.

1. **A registry's member IS its `Sub`, so A-042's `cmd:name()` is `sub:key()`.** The audit asked for
   a `Command` type with `:name()` *and* for `sub:key()` on the bus. Those are one verb: what a
   subscription was registered under. `hafen.slash()` becomes a collection whose members are the
   addon's own slash `Sub`s, `:get(name)` addresses one, and `sub:key()` is its name. One new verb
   instead of one new type and one new verb.

2. **`:off()` stays on the subscription even where a collection exists.** D2 chose "wherever a
   collection exists, the destroy verb is on it" — that governs *destroying a thing*.
   `CLAUDE.md` names `sub:off()` as the one ending for a *subscription*, and a slash command is a
   subscription that happens to be enumerable. The two rules do not collide, and 087 is where the
   rest of the teardown family is sorted.

3. **`hafen.event():list(filter)` covers the hub's three emitters** — the bus, `:action()` and
   `:message()` — because "what am I listening to" is one question and `hafen.event()` is the hub for
   exactly those three. A widget's own subscriptions belong to the widget and stay out; the other
   half, if it is ever wanted, is `w:sub()`.

4. **A `Binding` gets the three states `KeyBinding` actually has.** `KeyBinding` is three-valued —
   `key == null` means *use the default*, `key == KeyMatch.nil` means *unbound*, anything else is an
   assignment — and `keybindings:key(name, k)` writes only the last two, so nothing puts a binding
   back on its default and a caller that saves a key and writes it back **turns a default into an
   assignment** (`ROADMAP`, filed 066). A write with no undo is a surface this feature would be
   shipping unfinished, so `binding:key(nil)` reverts to the default, and `binding:default()` and
   `binding:assigned()` make the state readable — without them the undo is unusable, because nothing
   can tell "the user chose F5" from "F5 is the default".

5. **`hafen.timer():every(0, fn)` starts repeating.** `AddonManager.Timer` encodes "one-shot" as
   `interval <= 0`, so `:every(0, fn)` is stored indistinguishably from `:after(0, fn)`, fires once
   and is dropped (`ROADMAP`, filed 070) — while `timer.md` says a repeating timer fires at most once
   per tick, which reads as *every tick*. A `t:repeats()` that answered `false` there would document
   the bug rather than fix it, so the flag becomes its own field and `:every(0, fn)` fires every
   tick, as the page already says.

## Two arrears from 084 and 085

A `NNN-` folder freezes when it closes, so a defect found in a shipped feature's own surface has no
home but the feature still in flight. These two are 084's and 085's, they discharge **no inventory
id**, and each closes an acceptance criterion its own feature wrote and did not fully reach.

**084 — eight optional arguments never reached the house helpers.** 084.4 collapsed twelve
hand-written type tests onto `Args.str`/`Args.num` and 084.7 swept fourteen more, and between them
they left every **optional** argument of the action verbs on LuaJ's own `optint`/`optdouble`:
`s:world():place`'s `button` and `mods`, `s:world():click`'s two, `s:world():select`'s `mods`,
`hand:use`'s `mods`, `slot:use`'s `mods`, and a `y` field in `VrApi`. Each breaks both of 084's own
criteria: a table there gives LuaJ's raw *bad argument: number expected, got table* (AC5), and a
numeric string is silently coerced, so `s:world():place(p, 0, "1", "0")` is **taken** (AC6). Five of
the six sites are protected verbs, where the coerced value goes to the server. `s:world():place` is
named in 084.4's own task text — its required arguments were fixed and its optional ones were not,
and 084.4's suite asserted exactly the argument that was fixed.

**085 — one colour reader still answers positionally.** `Stock.color(Object)` builds
`{[1], [2], [3], [4]}`, so `sheet:stock()` hands back positional colours while `Chrome`'s readers —
`w:style().bg.color`, a glow, a border — hand back keyed through `AddonManager.color`. Both are the
stylesheet's own colour read back, and both javadocs give the same reason: *"the value in the shape
the setter takes, so a read round-trips into a write"*. 085 **saw this and wrote it down** as a
stated exception on `shapes.md`, which makes it a judgement call rather than a miss — but both
spellings are legal as input, so handing back keyed changes no round-trip at all and costs only the
exception paragraph. One-canonical-way argues one side of it and nothing argues the other.

## Acceptance criteria

1. **No handle in the API is a plain table.** Every one is userdata with a closed vocabulary and a
   `__tostring`, so an unknown key raises naming the fix and `tostring(h)` names the thing.
2. **A handle cannot be written to from Lua**, and a hand-built look-alike is refused by the verb
   that would have taken it.
3. **Identity survives.** `w:style().font == body`, `w:style().bg.image == panel`,
   `hafen.timer():list()` holding the very handles `:after` gave you, and
   `hafen.client():options() == hafen.client():options()` all still hold.
4. **Every `:on` in the API hands back a `Sub`** — `s:ui():on`, `hafen.slash():on` and
   `keybindings:on` included — and every one is ended with `sub:off()`.
5. **`sub:key()` answers what a subscription was registered under**, on all of them.
6. **`hafen.slash()` is a collection** of this addon's commands: `:list(filter)`, `:count(filter)`,
   `:find(filter)`, `:get(name)`, and a string filter matches the command name.
7. **`hafen.event():list(filter)` / `:count(filter)`** answer over this addon's subscriptions on the
   bus and the two streams.
8. **A timer answers `:interval()`, `:repeats()`, `:due()`, `:alive()` and `:info()`**, so
   `hafen.timer():count(function(t) return t:repeats() end)` answers a real question — and
   `:every(0, fn)` repeats.
9. **`keybindings:binding()` is a collection of `Binding` objects** with `:id()`, `:key()`,
   `:key(k)`, `:key(nil)`, `:default()`, `:assigned()`, `:exists()` and `:info()`; `ipairs` over
   `:list()` walks every one.
10. **Every retired spelling raises naming its replacement** — `hafen.slash():register`,
    `keybindings:register`, `keybindings:unregister`, `keybindings:list`, `keybindings:key`, and
    `handle:remove()` on what is now a `Sub`.
11. **No optional argument of an action verb coerces or falls through to LuaJ.** A table raises
    naming verb and parameter, a numeric string is refused, and an explicit `nil` where a value
    belongs refuses — at all six sites, before anything reaches the wire.
12. **`sheet:stock()` hands back keyed colours**, so the stylesheet's two readers agree and
    `shapes.md` loses its exception paragraph.
13. **Nothing an addon owns leaks.** A `:reload` after subscribing, registering and loading drops
    every sub, command, hotkey, timer, request and asset, exactly as today.

## Out of scope

- **The teardown family's other ten spellings** — `:remove()`, `:destroy()`, `:drop()`,
  `:dispose()`, `:release()`, `:stop()`, `:cancel()`, `:finish()`, and whether an ending returns the
  receiver or `nil`. That is 087, and it is the whole of it. This feature moves exactly the three
  endings that are **subscriptions**, because `CLAUDE.md` already names their verb.
- **A widget's own subscriptions as a collection** (`w:sub()`). `hafen.event()` is the hub for three
  emitters and this lists those three; a widget's are the widget's, and nothing today asks for them.
- **`hafen.font():list()` enumerating the four built-ins** rather than what the addon has asked for
  (091). It is a collection's *membership* question, not a handle's shape.
- **`KeyBinding.get` running none of `set`'s exclusivity pass**, so two defaults sharing a key leave
  both firing (`docs/client/multi-session.md`). That is an engine behaviour under the binding, not
  the shape of the handle over it.
- **Making `hafen.event()` itself a collection.** It is a hub for three emitters, not a set of one
  kind; it gains two verbs.
- **The permission tier over any of this.** D5 chose that the tier does what the page says, and
  A-097 puts **`client.settings`** over "persisted config and every hotkey" in 093 — which is exactly
  the write this feature reshapes, `keybindings:key(name, k)` becoming `binding:key(k)` and gaining
  `binding:key(nil)`. It ships unprotected here, as `keybindings:key` is today. **One thing 093 needs
  to know and A-097's row does not say**: `KeybindingsOptions` does not go through `OptionsMethod`, so
  A-097's "gated once inside `OptionsMethod.onWrite`" never covered hotkeys — after this feature the
  second gate site is `LuaBinding`'s `key` write, and it is one site rather than the two
  (`keybindings:key`, `keybindings:register`) it would have been.

## Docs impact

**Written:** `api/slash.md` · `api/timer.md` · `api/event/README.md` · `api/client/keybindings.md` ·
`api/client/README.md` · `api/client/profiling/README.md` · `api/ui/replace.md` · `api/ui/custom.md` ·
`api/asset.md` · `api/font.md` · `api/http.md` · `api/map/drawings.md` · `api/conventions.md` ·
`api/references.md` · `api/vr/README.md` · `guides/hotkeys-and-commands.md` ·
`guides/events-and-timers.md` · `guides/custom-ui.md` · `guides/debugging.md`.

**Derived impact set.** The prose names of this surface, greped across the whole of `docs/`:

```
grep -rlE "slash\(\):register|keybindings\(\)|:unregister|observer handle|selector watch|s:ui\(\):on|timer handle|command handle|hafen\.event\(\)|table: 0x|the handle \|" docs/
```

**40 pages.** The verdicts:

| Page and row | Verdict |
|---|---|
| `slash.md` §Register, §The command handle | **rewrite** — `:on(name, fn)` → a `Sub`; the section becomes a collection; the one-row handle table goes |
| `client/keybindings.md` — the whole method table | **rewrite** — `:on`, `binding()`, and the three states |
| `ui/replace.md` the `s:ui():on` row and §Watching for a widget | **revise** — the handle is a `Sub` |
| `ui/custom.md` §Observer handles | **delete** — there is no observer handle any more; the section's inbound anchor (`custom.md#observer-handles`, from `ui/replace.md`) re-points to `event/README.md#subscribe` |
| `timer.md` §The timer handle, §Read what is scheduled | **rewrite** — five verbs, and `:every(0, fn)` repeats |
| `event/README.md` §Subscribe | **revise** — `sub:key()`, `:list(filter)`, `:count(filter)` |
| `conventions.md` §Snapshots vs handles, §Collections | **revise** — a handle is userdata with a closed vocabulary; nothing is a plain table but a snapshot and the store's own |
| `asset.md`, `font.md`, `map/drawings.md`, `http.md` | **revise** — each handle's `tostring`, and that a typo on one now raises |
| `client/README.md`, `client/profiling/README.md`, `vr/README.md` | **revise** — same |
| `references.md` | **revise** — the "you pass the handle" list gains what a handle now is |
| `guides/hotkeys-and-commands.md`, `guides/events-and-timers.md`, `guides/custom-ui.md`, `guides/debugging.md` | **revise** — every worked example that registers, watches or schedules |
| `event/bus.md`, `event/streams.md`, `ui/selectors.md`, `ui/edit.md`, `ui/native.md`, `ui/drawing.md`, `vr/ghosts.md`, `vr/models.md`, `vr/widgets.md`, `getting-started.md`, `README.md`, `guides/permissions.md`, `guides/reading-the-world.md`, `guides/saved-data.md`, `actionbar.md`, `buff.md`, `char.md`, `craft.md`, `flowermenu.md`, `menugrid.md`, `meter.md`, `player.md`, `speed.md`, `study.md` | **discharge** — each hit is `hafen.event()` used correctly, or the words "the handle" in an unrelated row |

**No `docs/client/` page is created.** `docs/client/services.md` already maps both engine seams this
feature leans on: Console commands are *"register only; no unregister"*, with the three-tier
`findcmd` shadowing that is why the bridge checks before installing; and the keybinding registry.
`docs/client/multi-session.md` covers `KeyBinding.get` and the exclusivity pass. If task 3 has to read
`KeyBinding` beyond what those two say, it adds the gotcha to `services.md`.

## Closing the inventory

`audit/INVENTORY.md` is the sweep's working sheet and the only thing that knows when the sweep is
done. **At `/end`, and only after the maintainer's verification, this feature ticks its own rows:**

- For each of A-038 … A-045 the box becomes `☒` and the id is struck: `| ☒ | ~~**A-038**~~ | … |`.
- **A-042's row gains its strike reason**: the member is the `Sub` itself, so `cmd:name()` shipped as
  `sub:key()` — the collection landed, the second spelling did not.
- Nothing else in the file is touched. An id never moves.

Then:

```bash
grep -c '^| ☐' audit/INVENTORY.md
```

```bash
comm -23 <(grep -oE 'A-[0-9]{3}' audit/INVENTORY.md | sort -u) <(grep -rhoE 'A-[0-9]{3}' specs/*/spec.md | sort -u)
```

After 085 the first prints `83`. After this feature it must print **75**, and the second must no
longer name any id between A-038 and A-045.

## Context files

Under `src/io/brodgar/addon/`, tagged with the tasks that need each:

- `Subs` (`on`, `off`, `clear`, `byKey`, the `Cats` and `Idle` interfaces, the four constructors),
  `LuaSub` (`subs`, `key`, `fn`, `alive`, `self`, `handle()`, `resolve`, `self(...)`, `meta`),
  `Addon` (`subs`, `actionSubs`, `messageSubs`, `subMeta`, `selectorWatches`, `slashCommands`,
  `keybinds`) — 1, 2
- `UiApi` (`newSelectorWatch`, `removeSelectorWatch`, `scanForWatch`, `state(wu).selectorWatches`),
  `LuaSelectorWatch` — 1
- `HookApi` (`newSlashCommand`, `dispatchSlash`, `teardownSlashCommands`, `slashDispatched`,
  `slashHandlers`, `isReservedSlash`, `hasWhitespace`, `removeKeyBindsNamed`, `teardownKeyBinds`,
  `parseKeyMatch`), `LuaSlashCommand`, `LuaKeyBind` — 1, 2, 3
- `KeybindingsOptions` (`create`, `methods`, `on`, `binding`), `LuaBinding` (`COLL`, `id`, `of`,
  `resolve`, `binding()`, `keyName`, `collection`, `Cache`, `methods`), `haven/KeyBinding` (`defkey`,
  `key`, `set`, `set()`, `key()`, `get`, `all`, and the existing `// addon:` unbind),
  `haven/KeyMatch` (`nil`, `name`, `reduce`, `restore`) — 3, 6
- `LuaCollection` (`Source`, `named()`, `needle()`, `noGet()`, `missing()`, `create`, `receiver`) —
  2, 3
- `AddonManager` (`newTimer`, `Timer`, `runTimers`, `clock`, `timerMeta`, and the `hafen.timer()` mount
  with its `timerVerbs` and `Source`), `Addon` (`timerMeta`) — 4
- `AssetApi` (`imageHandle`, `meshHandle`, `dataHandle`, `addAssetVerbs`, `Disposer`, `imageFor`,
  `Entry`, `teardownAssets`), `LuaImage` (`KEY`, `resolve`, `handle`, `dead`), `LuaMesh` (`KEY`,
  `resolve`), `FontApi` (`mint`, `fontHandle`, `handleFor`, `property`), `FontHandle` (`KEY`,
  `handle`, `draft`, `used`), `MapImages` (`handleFor`, its `size`/`info` verbs), `Retired` (`closedIndex`) — 5
- `OptionsHandle` (`install`, `create`), `AudioOptions`, `CameraOptions`, `ClientOptions`,
  `InterfaceOptions`, `VideoOptions`, `KeybindingsOptions`, `ProfHandle`, `ProfScope`, `HttpApi`
  (`newHttpRequest`), `LuaHttp`, `VrApi` (`entityHandle`), `Retired` (`closedIndex`, `moved`,
  `movedObj`, `put`) — 6
- `AddonRegistry` (the teardown sequence: the order every owned resource is released in, and where each
  `teardown*` is called from) — 1, 4, 5, 6
- `Args`, `Section` — every task

Pages, by task:

- **1** — `api/slash.md`, `api/client/keybindings.md`, `api/ui/replace.md`, `api/ui/custom.md`,
  `api/event/README.md`, `api/conventions.md`, `guides/hotkeys-and-commands.md`,
  `guides/custom-ui.md`
- **2** — `api/slash.md`, `api/event/README.md`, `api/conventions.md`, `guides/debugging.md`
- **3** — `api/client/keybindings.md`, `guides/hotkeys-and-commands.md`
- **4** — `api/timer.md`, `guides/events-and-timers.md`
- **5** — `api/asset.md`, `api/font.md`, `api/map/drawings.md`, `api/references.md`,
  `api/ui/style/README.md`
- **6** — `api/client/README.md`, `api/client/profiling/README.md`, `api/http.md`, `api/vr/README.md`,
  `api/conventions.md`
- Every task: `DOCUMENTATION.md`, and `audit/INVENTORY.md` for the ids `/end` ticks.

A suite reaches a live handle of every kind it proves, and the reach spellings are on the reference
pages rather than in the bridge: `api/README.md`, `api/slash.md`, `api/timer.md`,
`api/client/keybindings.md`, `api/client/profiling/README.md`, `api/asset.md`, `api/font.md`,
`api/http.md`, `api/map/drawings.md`, `api/ui/replace.md`, `api/vr/README.md`, and
`guides/permissions.md` for whether a verb it calls is gated.

Consumers to keep running: `addons/eventstack` (`hafen.client():options():keybindings()`),
`addons/profiler` (`hafen.client():profiling()`, `:options():client()`), `addons/session-manager`,
`addons/widgetstack` and `addons/clickpath` — 1, 3, 4, 6.
