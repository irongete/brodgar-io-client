# 084 — Every refusal names the fix: plan

## Approach

Four mechanical passes and one behavioural one, in the order a later feature depends on.

**The two index passes are separate tasks because they prove different things.** The 37
`Retired.methodIndex` types get a better message; the 12 bare-methods-table types get a mechanism they
never had, and a suite that cannot say which it proved has not decomposed anything. Both take
`Retired.closedIndex(entity, methods, hint)` — the same `rawget`, the same `NAMES` lookup, a different
fallthrough. **The hint is written beside the methods table it guards**, not in a central list, so the
roster and the refusal cannot drift apart.

**The collection pass declares rather than branches.** `LuaCollection.Source` grows `noGet()` — the
sentence a keyless collection's missing `:get` carries — and `missing()` returning `NIL`/`MINT`/`RAISE`.
Both are read by `LuaCollection.meta` and by `conventions.md`; neither is implemented per `getMember`.

**The argument pass collapses twelve hand-written type tests onto two helpers.** `Args.str` and
`Args.num` assert `type() == TSTRING`/`TNUMBER`, which is the idiom `SessionApi.getMember` already uses
with the comment saying why: *"in LuaJ a number IS a string and a numeric string IS a number, so the
type is the only test that both refuses 42 and accepts an account literally called '42'."* That one
change closes both directions — `s:kin():add(1234)` starts refusing, `entry:value("42")` stops.

**The silence pass** turns four no-ops into refusals and one into a log, each at the site that
produces it.

Order inside the feature: 1 and 2 are independent of everything; 3 depends on nothing; 4 and 5 are
independent of each other. Any order works; the numbering is the reading order.

## Files to create/modify

**Bridge** — all under `src/io/brodgar/addon/`:

- `Retired.java` — `methodIndex` retires once nothing calls it; `closedIndex` unchanged (1, 2)
- the 34 `Retired.methodIndex` call sites, covering 37 types — `LuaGob`, `LuaItem`, `LuaWidget`,
  `LuaKin`, `LuaMarker`, `LuaPosition`, `LuaSession`, `LuaQuest`, `LuaWound`, `LuaSlot`, `LuaPagina`,
  `LuaCraft`, `LuaSpeed`, `LuaAttr`, `LuaSkill`, `LuaCredo`, `LuaExperience`, `LuaFood`, `LuaManeuver`,
  `LuaDeckCard`, `LuaFightSummary`, `LuaOpponent`, `LuaCondition`, `LuaContents`, `LuaHand`,
  `LuaMapGrid`, `LuaSegment`, `LuaOverlay`, `LuaOverlayToggle`, `LuaPartyMember`, `LuaStudySlot`,
  `LuaHudOverlay`, `KeybindingsOptions`, and `VrApi`'s one `methodIndex(kind, m)` for four kinds (1)
- the 12 bare tables — `LuaBuff`, `LuaMeter`, `LuaSound`, `LuaIconCat`, `LuaMask`, `ProfHandle`,
  `ProfScope`, `AudioOptions`, `CameraOptions`, `ClientOptions`, `InterfaceOptions`, `VideoOptions` (2)
- `LuaCollection.java` — `Source.noGet()`, `Source.missing()`, the `meta` fallthrough (3); the
  `keeps` catch (5)
- `LuaBuff`, `LuaMeter`, `LuaStudySlot`, `MapApi` — their `Source` declarations (3)
- `MapApi.java` — the accessor refusal that names `:get` (3)
- `SessionApi`, `AssetApi`, `FontApi`, `LuaSound`, `LuaKin` — `missing()` declarations (3)
- `Args.java` — `str`, `num` (4)
- `LuaKin`, `LuaItem`, `WorldApi`, `LuaWidget`, `Controls`, `HttpApi`, `HookApi`,
  `KeybindingsOptions`, `AddonManager` — the twelve type tests and the six `checkjstring`/`checkint`
  sites (4)
- `LuaWidget.java` — the `on` ordering (5)
- `SessionApi.java` — `current(s)` raises (5)
- `FontApi.java` / `FontHandle.java` — the ignored `color` refuses (5)
- `StoreApi.java` — the timer write logs (5)

**Pages** — `api/conventions.md` (3, 4, 5) · `api/buff.md`, `meter.md`, `study.md`,
`map/markers.md` (3) · `api/ui/controls/interactive.md` (4) · `api/session.md`, `store.md`,
`font.md` (5).

## Risks and gotchas

**`closedIndex` throws on a FIELD read, not only on a call.** LuaJ routes `x.foo` and `x:foo()` through
the same `__index`, so every place the *bridge itself* probes a handle's field starts throwing.
`LuaImage.resolve` does `v.istable() ? v.get(KEY) : v` — safe, that is a table. The REPL echo
(`AddonManager`'s `:lua` path) and `Json.write` inspect types rather than index, so they are safe too —
but **grep `\.get\(` across the bridge before landing task 1**, because one missed probe is a crash in
a path nobody tests. Metamethods are exempt: `__name`/`__tostring` are `rawget` off the metatable.

**`LuaCollection.keeps`'s catch is doing two jobs.** `catch(RuntimeException e) { return false; }`
covers both a user's `LuaError` and a `haven.Loading` thrown by a not-ready read. Task 5 must catch
`Loading` specifically and let `LuaError` through. Whether a `Loading` actually reaches here is unclear
— the bridge readers already swallow it (`WorldApi`'s `catch(RuntimeException e) { return NIL; }`) — so
the suite must assert **both** directions or the fix can silently remove the guard that mattered.

**`LuaWidget`'s `on` ordering is three statements, not one.** Today: `live(handle(self, "on"))` →
`Args.required` ×2 → `widgetKeys(owner, w)` → `if(w == null) throw`. Moving the null check above
`widgetKeys` changes which message an author sees, and `Controls.keyElsewhere(w, key)` takes that same
`w` — check it tolerates the reorder.

**"A session with no screen of its own" is not `:exists()`.** It is the gap `Sessions.Member.run`
leaves while the UI is taken down and during a character handoff, where `Member.ui` is null while the
member is still in the list. Read `io.brodgar.session.Sessions.byuser` / `anchormember` before
writing the refusal, and keep the three refusals `current(s)` already has.

**The build hides a moved symbol.** Tasks 1 and 2 touch 49 files; `ant hafen-client` is incremental, so
`rm -rf build/classes` before believing a green build.

**Existing `Retired` rows must keep firing.** `closedIndex` looks up `entity + ":" + key` exactly as
`methodIndex` did, so `gob:isplayer`, `item:pos`, `widget:hide` and the rest are unaffected — assert a
sample rather than assuming.

## Discarded alternatives

- **Keeping `methodIndex` for types that might want a feature probe** — a probe (`if hafen.x then`) is
  on the `hafen` table, which has its own index and correctly reads `nil`. Nobody probes for a verb on
  an object they are already holding.
- **One task for all 49 types** — the two populations prove different claims, and a single suite could
  only report "they all throw", losing the one fact worth proving: that a retirement on the twelve now
  fires at all.
- **Giving the eleven plain-table handles a closed vocabulary here** — they have no metatable, so it
  would mean minting userdata for them, which is the whole content of 086. Writing that mechanism twice
  is worse than writing it late.
- **Generating the hint from the methods table at refusal time** — the vocabulary a reader needs is the
  key list *plus* the sentence saying what the type is for, and a generated list drops exactly the half
  that teaches.
- **One `Args.checked(a, i, type, verb, param)` instead of `str` and `num`** — stringly-typed at the
  call site, and two are the only two the API needs.
- **Making the store's timer refuse the way `:flush()` does** — a write nobody asked for must not cost
  an addon the rest of its file. That is `StoreApi`'s own reason and it still holds; logging is the
  half that was missing.
- **Fixing `s:ui():find(sel)`'s unknown-bare-role miss here** — the rename to `:match`/`:matchAll`
  takes that ground in 088, and a refusal written for a verb about to be renamed is written twice.
