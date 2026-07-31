# Learnings — Gap subsystems (markers, radar, slash, kin, craft, quests, wounds, fight)

> Append-only (never rewrite old entries). Index: [../LEARNINGS.md](../LEARNINGS.md). Grep this
> file rather than reading it whole; entries keep their original chronological order and tags.

- **Markers (A1) — the map is a SEPARATE coordinate universe from the live gob world; the bridge is the minimap's
  `sessloc`.** `MCache` (the live terrain, session-local grid coords) and `MapFile` (the persistent on-disk DB,
  *segment* coords) are two different systems linked by the **stable grid id**. A marker stores `seg` (segment id,
  local) + `tc` (segment TILE coord), NOT world units. To move between them use the corner minimap's live
  **`sessloc`** (`MiniMap.Location` = a `Segment` + the segment-tile-coord of session tile 0,0, resolved each tick by
  `SessionLocator`): world→seg = `sessloc.tc + floor(world/tilesz)` (this IS what `MapWnd.MarkButton.FindMark.hit`
  does), seg→world = `(marker.tc − sessloc.tc)*tilesz + tilesz/2`. Both floor (negatives correct — same gotcha as
  1c-2). seg→world is only valid when `marker.seg == sessloc.seg.id` (a marker in another explored area has no world
  coord this session) — so a snapshot's `x,y`/`dist` are **session-local and optional**, while `seg`+`tc` are the
  **persistent anchor** (C4). Don't try to reverse a segment coord to a grid id via `Segment`'s internals (its
  `BMap<Coord,Long>` is private) — expose world `x,y` and let the addon call `hafen.map.gridPos(x,y)` for the
  shareable anchor (D-013, no duplication).
- **Locate the map DB with ZERO edit: `GameUI.mapfile.file` (or `.mmap.file`) — both public, same `MapFile`.** The
  big Map window (`GameUI.mapfile`, a `MapWnd`) and the corner minimap (`GameUI.mmap`, a `MiniMap`/`CornerMap`) are
  created together at login and share one `MapFile` (`public final` on both). Every marker member is public too
  (`MapFile.markers`/`markerseq`/`lock`/`add`/`remove`; `Marker.seg`/`tc`/`nm`; `PMarker.color`/`onmap`;
  `SMarker.res.name`), so the whole subsystem is read/written without touching `haven`. `MiniMap.sessloc`/`file` are
  public; `MiniMap.Location.seg`/`tc` and `Segment.id` are public. (NB: **classloading `MiniMap` headless fails** —
  its static `Resource.loadtex(...)` fields need GL — so `MiniMap.Location`/snapshot paths are in-game-only tests;
  the pure arithmetic + ref map ARE headless via plain `Coord`/`Coord2d`/`MapFile` [`new MapFile(null,"")` does no
  I/O; `PMarker`'s ctor is side-effect-free — no `markerseq` bump].)
- **`MapFile.markerseq` is the perfect poll signal — but disk-loaded markers don't bump it.** `markerseq` (volatile
  int) bumps on `add`/`remove`/`update` and on a **segment merge** (which mutates `mark.seg`/`tc` in place on a
  loader thread under the write lock — hence copy-under-read-lock before snapshotting). It does NOT bump on the live
  grid updates every tick (`MapFile.update(MCache,gc)` is a different path) — so it's a clean "markers changed"
  signal, not per-frame noise. BUT markers loaded from disk in `MapFile.load` are added to the list **directly**
  (no `add()`), so they DON'T bump `markerseq` → **prime** the poll (record the current seq the first time the DB is
  seen, fire only on subsequent changes) and let the initial set be read via `markers.list()`; the server's SMarkers
  (`markobj`) DO stream in via `add()` a beat after login, so those fire `MarkersChanged` normally.
- **Marker refs = a session-scoped `IdentityHashMap<Marker,Long>` + reverse map (the WidgetRef pattern for a
  non-id'd object).** Unlike gobs (a real `long` id) markers have no addon-visible id, and `(seg,tc,nm)` isn't
  unique/stable (merge shifts `tc`). So assign a monotonic `long` on first surface (facade-safe, P1 — no Java object
  to Lua) and resolve back for `remove`; clear it in `init()` (Marker identities are per-session — a relog rebuilds
  the `MapFile`). Guard the maps `synchronized` because `hafen.markers.list()` can be called from the off-thread
  `:lua` REPL as well as the UI thread.
- **`SkillWnd`'s three tabs don't share a shape (A4).** A `Skill` and a `Credo` each carry an internal `nm` token
  (so their name falls back to it while the resource tooltip is Loading), but an **`Experience` has NO name field** —
  its name is *purely* the resource tooltip, so it is nil until the resource resolves (omit the key, don't fabricate).
  Their groupings differ too: skills are `skg.csk`/`nsk` (known/buyable `Group.items`), credos are
  `credos.ccr`/`ncr` (acquired/available) **plus** a distinct *pursued* credo (`pcr` + loose `pcl…pqid` progress
  ints, not a list), and lore is `exps.seen.items`. → Exposed credos as **one composite** (`{acquired, available,
  pursuing, cost}`, like `char.food()`) rather than parallel functions, and the flat tabs (skills/lore) as arrays.
- **A2 radar = a WRITE surface onto an EXISTING client registry — "drive the settings window's model" not "build a new
  one".** `GobIcon.Settings` (`GameUI.iconconf`) already backs the in-client "Icon settings" window; our setters just
  do what its checkboxes do (`set.show/notify = v; conf.dsave()`), so the addon change shows up there and persists —
  and it's **zero `haven` edit** because every member is public. The write model is inherited wholesale: same UI
  thread, same benign race (the loader swaps `settings` wholesale; boolean flags are atomic), same debounced persist.
- **First mutator over the canonical filter — matched-count is the right return, and a `nil` filter = "all".** The
  read/list convention (`nil`=all / string=`name`-substring / predicate) generalises to mutation cleanly: `setVisible/
  setNotify(filter,on)` flip **every** match and return **#matched** (so the addon knows its filter hit something; a
  no-op re-apply still counts, only an actual flip triggers `dsave`). The predicate form is the res-matching escape
  hatch (the string form matches `name` only, per shared `matches`). `nil`=all is powerful (a "hide everything"
  toggle) — documented as such.
- **A client-global registry with no unregister (`Console.setscmd`) → NEVER re-register it per reload; install a
  single engine-lifetime DISPATCHER that routes to current addon state.** (A11 / coverage-gaps C1.) The naïve
  `hafen.slash` would call `Console.setscmd(name, handler)` on every reload → leaked/duplicate commands pointing at
  torn-down handlers. Fix: the first registration of a name installs ONE `Console` command that reads
  `slashHandlers.get(name)` at dispatch time; reload/disable only swaps/drops that map entry (a `slashDispatched`
  set records which names are dispatched and GROWS ONLY — deliberately *not* reset in `init()`, unlike the
  session-scoped hook maps which ARE reset). A dropped name's dispatcher stays and replies "no addon handles it".
  This is the general pattern for **any** engine-lifetime global the bridge writes but can't un-write (registries,
  static maps): *register a stable indirection once, keep the live state in a bridge-owned map, swap the map.*
- **`Console` command dispatch is on the UI thread in-game (input handling), so it can't race the tick.** The `:`
  console reaches `Console.run` via `ConsoleHost.done` (a `ReadLine.Owner` keyboard callback) → UI thread, the same
  thread as the tick/draw — so a slash handler running Lua synchronously never races other Lua (no marshalling
  needed), exactly like the `:lua` REPL. Only a **terminal stdin** build (`Chatwindow`/`HeadlessClient`) dispatches
  off a reader thread; the REPL already accepts that trust/threading profile, so slash inherits it rather than
  inventing a defer.
- **`Console.findcmd(name)` (via the live `ui.cons`) is the collision check** for a would-be new command — it
  resolves the merged static+instance+dir command maps. Guard it (`ui`/`cons` may be null pre-HUD; wrap in
  try/catch) and only run it on the FIRST registration of a name (once our dispatcher is installed, `findcmd` would
  return *it*). Reserved engine names (`lua`/`addons`/`reload`) are also refused explicitly since they share the
  same static `scommands` map and would be silently clobbered by `setscmd`.
- **Kin/buddy (A6) — a widget's own `serial`/dirty counter can UNDER-report; diff a snapshot instead.**
  `BuddyWnd` looked like the easy "poll `serial`" case, but `serial` bumps on `add`/`rm`/`upd` and **NOT on
  `chst`** (the online/offline flip) — the single event a kin-alert addon most wants. So change-detection is a
  **snapshot diff** (id/name/group/online per entry), never the widget's counter. General takeaway: before
  trusting an engine-side change token, check *which* mutations bump it — if any state you expose changes without
  bumping it, diff your own snapshot. (Here the diff is order-sensitive, which is correct: `BuddyWnd` only
  re-sorts on `add`, so any reorder is driven by a real field change worth reporting.)
- **A6 is uimsg-driven, not polled — the opposite of buffs/study, because buddy changes ARE uimsgs.** Buffs/study
  poll because their add/remove is a widget create/`cdestroy` invisible to the 1d-1 inbound-`uimsg` tap. But
  every `BuddyWnd` roster change (`add`/`rm`/`upd`/`chst`) is a targeted `uimsg`, so a `TreeAdapter` whose
  `interested` flags those four msgs + a `refresh` that diffs is strictly better: **zero per-tick work when the
  roster is idle** (the common case). Pick the adapter path (uimsg vs poll) by asking "is the change I care about
  actually delivered as a `uimsg` to this widget?" — for structural widget create/destroy the answer is no (poll);
  for server field updates it's yes (uimsg-driven).
- **Tri-state → boolean is a legitimate "one canonical way" call (A6).** `Buddy.online` is `1`/`0`/`-1`
  (online / offline / hearth-secret-only). Exposing the raw int would be faithful but forces every addon to learn
  the encoding; exposing `online == 1` as a **bool** answers the 95% question ("is this kin online") cleanly. When
  a low-value distinction (the `-1` state, `Buddy.seen`/`notes`) would complicate the common path, expose the
  ergonomic form and **document the deferred nuance** rather than leaking the internal encoding.
- **(020.2) A kin marks MORE THAN ONE gob — their hearth fire carries the buddy attrib too.** The Kin↔Gob link
  is server-side: the `ui/obj/buddy` `GAttrib` (`Buddy.id`) is stamped on the gobs that belong to a kin, and
  that is **not just their body** — a kin's hearth fire (`gfx/terobjs/pow`) has it, which is how it draws their
  name in their kin colour. Found in testing: with no kin online, `kin:gob()` still answered — with a hearth
  fire. So a naive "first gob carrying this id" sweep lets the **`OCache` iteration order** pick the answer
  once body and hearth fire are both in view. `kin:gob()` therefore prefers `isplayer()` and falls back to any
  marked gob; the many-case is the inverse relation
  (`hafen.world.gobs(function(g) return g:kin() == k end)` — 2 near an online kin). **General rule: before
  shipping an X→Y lookup as singular, check the cardinality on the LIVE data — if the engine's mapping is
  1→N, either define which one wins or expose the list; do not let cache order decide.**
- **(020.3) Turning a whole-list event payload into OBJECTS also fixes the consumer's identity problem.**
  `KinChanged` says only *that* the roster changed, so every consumer keeps its own last-state map and diffs
  it — and with the old flat `{id,name,…}` payload the obvious key was the **name**, which a rename silently
  breaks: `hello` reported the renamed kin as one going offline and another coming online. Keying by the
  **interned Kin object** makes the map track identity, not presentation, for free. Payload construction
  mirrors `fireGob` exactly: `int[]` ids in, per-owner array minted only behind `hasSub` (interning is
  per-addon, so the payload can never be shared), and the ids come from the **already-diffed** snapshot so
  change detection stays the `kinListEqual` diff and nothing re-reads `BuddyWnd` for the event. **General
  rule: when a section goes OOP, its events go with it in the same feature — a flat payload beside an object
  API is the dual style D-013 forbids, and the object payload is usually the more correct one anyway.**
- **A8 — widget lists: check swap-vs-mutate before copying, and "copy under `ui`, snapshot outside".** When a
  read walks a widget's `List` fields, don't assume they're all the same to copy. In `Makewindow`, `inputs`/
  `outputs`/`qmod` are **reassigned wholesale** by their uimsgs (`this.inputs = wdgs`) — grabbing the reference
  is race-free (no `ConcurrentModificationException`, worst case a one-frame-stale snapshot). But `tools` is
  mutated **in place** (`tools.add(...)` on the `"tool"` uimsg), so `new ArrayList<>(mw.tools)` **can** CME. All
  those uimsgs run on a Loader thread **under `synchronized(ui)`**, so the safe, uniform fix is to copy **all**
  the lists inside one `synchronized(ui)` block, then build the Lua snapshots **outside** it (resolving resource
  names via `res.get()`, which may `Loading`, must NOT hold the lock) — the same "copy under the lock, snapshot
  outside" discipline the A1 marker read uses with `file.lock`. The static `ui` monitor is reentrant (the addon
  tick / in-game REPL already hold it) and only briefly acquired off the stdin REPL thread → no deadlock.
- **A9-2 — a content-published number can still be read faithfully when the CLIENT defines the marker
  interface.** A8/A7 warned that touching a *content* resource class blows up headless. But a wound's severity
  is not an opaque content class — it's whatever `ItemInfo` in `Wound.info()` implements **`WoundWnd.QuickInfo`**,
  a `public` interface **the client declares**. So the bridge finds it exactly as the client's own
  `WoundList.Item.getqdat` does — iterate `info()`, keep the highest-`qprio` `instanceof WoundWnd.QuickInfo`, call
  `qstr()` — reaching a content-defined value through a client-owned seam (like `Buff.AMeterInfo`/`GItem.NumberInfo`
  in 1d-2). The rule: when the engine names an interface/class for a content-published datum, read through that
  name (`instanceof` + the public accessor), don't reach into the resource. `qstr()` is a **display String**
  (usually a number) → expose it verbatim, `NOT seconds`, `tonumber()` on the addon side — the 1d-2 buff-amount /
  A8 craft-spec faithfulness rule.
- **A9-2 — two ORTHOGONAL adapter decisions: the event SHAPE (single `*Changed` vs an Added/Done split) and the
  TRIGGER (uimsg vs poll). Pick each on its own axis.** *Shape:* quests have a directional lifecycle
  (active→finished) the api-reference wanted surfaced as `QuestAdded`/`QuestDone`, so A9-1 built a stateful
  `questDiff`. Wounds are a set with magnitudes and no canonical "direction" (a wound worsens, heals, spawns a
  complication), and the contract names a single **`WoundChanged`** — so a `KinChanged`-style whole-list payload +
  a pure `woundListEqual` diff is the honest model (put the changing magnitude, `severity`, IN the equality key so
  "got worse" counts). *Trigger:* A6 kin and A9-1 quests are **uimsg-driven** because their key data (online flag,
  quest status) lands whole in the uimsg; A9-2 wounds are **poll-driven** (the 1d-3 study shape) because the key
  datum — `severity` — is resource-derived and **streams in a beat after** the `"wounds"` uimsg, so a uimsg-timed
  refresh reads it as nil and never re-fires. Don't assume "model on X" fixes the mechanism: the same event shape
  (KinChanged) can need a different trigger (poll) when the payload's key field resolves asynchronously.
- **A9-2 — some subsystems are a TREE, not a flat list (`Wound.parentid`/`level`).** Wounds nest (a complication
  under a wound); the client keeps it flat in `WoundList.wounds` but renders a tree via `parentid` (-1 = root) +
  a `treesort`-computed `level`. Expose both raw fields on the snapshot (authoritative `parentid` + convenience
  `level`) so an addon can rebuild the same indentation the panel shows, rather than flattening the structure away.
- **A10 — one server widget can hold several ORTHOGONAL data structures → several focused reads, not one blob.**
  `FightWnd` bundles a palette (`acts`), a keyed layout (`order[]` + `keys[]`), and saved-slot scalars
  (`maxact`/`usesave`/`nsave`). A8 craft used a single `current()` blob because a recipe is one transient unit; a
  persistent char-sheet tab whose parts answer different questions ("what do I know" vs "what's on which hotkey" vs
  "which school is active") reads cleaner as **`maneuvers()`/`deck()`/`summary()`** (the A9 quests `list()`/
  `selected()` precedent). These are distinct PROJECTIONS of the same widget, not two ways to do one thing — so
  "one canonical way" is preserved; `Action.u` legitimately appears in both `maneuvers` (palette) and `deck`
  (layout). Choose the decomposition from whether the widget is one unit (blob) or several sub-structures (focused
  reads), and from whether it's transient (craft `current()`) or persistent (char-sheet tabs → per-concern reads).
- **A10 — expose the RAW engine index the write path will consume, even in a read-only slice.** The deck slot is
  `order[]`'s 0-based index — the same index a future gated `fight.use(slot)` sends, and the same 1d-4 rule for the
  action bar (raw 0..143, not 1-based Lua). Surfacing `slot` now (alongside the human `key` label) means the
  Phase-4 action verb takes exactly what the read handed out — no re-derivation, no 0-vs-1 mismatch — the "one
  canonical index" discipline paying forward from read to action.
