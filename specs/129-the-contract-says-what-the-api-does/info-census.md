# 129 — the `:info()` census

The evidence behind the rule 129.3 rewrites. `CLAUDE.md` said every live object answers `:info()`
*"with no exception"* and `virtual/README.md` called it *"the one escape hatch every live object in this
API carries"*. This is the count that says what is true instead.

## What was counted, and how

A **closed vocabulary** is a receiver whose `__index` is `Refusal.closedIndex` — the shape that answers a
verb it carries and raises on every other name, naming the type and listing what it does answer.
`AssetApi.fileMeta` and `OptionsHandle.close` are wrappers over it and their call sites count as their own
vocabularies; entities built from a variable (`VirtualApi.entityHandle`'s `kind`) or by concatenation
(`AddonManager`'s message stream) are expanded by hand, from the call sites.

A vocabulary answers `:info()` when the methods table its own `closedIndex` guards carries an `info` verb —
read per vocabulary rather than per file, because a file can hold two (`AddonManager` holds the stream and
the timer, `AssetApi` the image, the mesh and the data file, and only the mesh has one).

**Two shapes are outside this count**, and are treated at the end: a **section**, and a **collection**. Both
build the same refusal themselves rather than hanging `closedIndex` off a table, and neither is a thing a
read hands back.

| | Count |
|---|---|
| closed vocabularies | 83 |
| answer `:info()` | 59 |
| do not, and a category exempts them | 7 |
| do not, and none does — **open rows** | 17 |

## The exempt: three categories, and what each is

A **builder** is constructed bare, configured by chained setters and dispatched on purpose. Its state is
what you have set so far, and the dispatch is where it goes; there is nothing to snapshot that the setters
have not just been handed.

A **snapshot** is already the thing `:info()` would return: it was minted from one moment and does not
re-resolve, so a copy of it would be the same table by another verb.

A **carrier of an ending** holds nothing but the fact that it has not ended. Its vocabulary is the ending
and the name it was registered under, and `:exists()`/`:off()` already answers the only question there is.

| Vocabulary | Declared in | Category | Why |
|---|---|---|---|
| `option` (a row being declared) | `AddonOptions.java` | builder | dispatched by `:add()`; every setter is legal until then and none after |
| `request` | `HttpApi.java` | builder | dispatched by `:send()`; the reply is the `res` below |
| `ev` | `LuaEvent.java` | snapshot | the payload of one fire, minted for that moment and never re-read; its one live receiver is `:widget()`, and a snapshot holds no live objects |
| `res` | `LuaHttpResult.java` | snapshot | the whole outcome of a finished request, fixed when it arrived |
| `sub` | `LuaSub.java` | carrier of an ending | `:key()` and `:off()`, and its page says so in those words |
| `grab` | `LuaGrab.java` | carrier of an ending | `:on()` and `:release()` — a hold, not a thing |
| `scope` | `ProfScope.java` | carrier of an ending | `:begin()`, `:finish()`, `:name()` |

`option` names **two** vocabularies: the builder above, and the declared row below, which answers
`:info()`. Both refusals begin `option has no verb`.

## The open rows

None of the three categories reaches these. They are what the other half of the argument — giving `:info()`
to what lacks it — would start from, and the *Why it is open* column is the whole of the case either way.

| Vocabulary | Declared in | Why it is open |
|---|---|---|
| `image` | `AssetApi.java` | the mesh beside it answers `:info()` and this does not; one family, two answers |
| `data` | `AssetApi.java` | the same, for a data file: `:text()`, `:type()` and `:path()` and no copy of them |
| `font` | `FontApi.java` | eight reads describing a face, and no way to take them at once |
| `hafen.ui():mouse()` | `LuaMouse.java` | eight live reads of the pointer — position, modifiers, cursor — and the strongest candidate here |
| `uioverlay` | `LuaHudOverlay.java` | `:key()` and `:exists()` are a snapshot already written, with no verb to hand it over |
| `profiling` | `ProfHandle.java` | every counter is a table already; what is missing is the one call that takes them together |
| `options` | `OptionsHandle.java` | the door to the six panels and your own; a snapshot of it is the panels' snapshots |
| `interface` | `InterfaceOptions.java` | a panel of settings, each a read — the shape `:info()` exists for |
| `video` | `VideoOptions.java` | the same |
| `audio` | `AudioOptions.java` | the same |
| `camera` | `CameraOptions.java` | the same |
| `client` | `ClientOptions.java` | the same |
| `keybindings` | `KeybindingsOptions.java` | the same |
| `addon options` | `AddonOptions.java` | the same, over the rows your own addon declared |
| `session:player()` | `CharApi.java` | its reads are `:gob()` and `:hand()`, both objects, and a snapshot holds no objects |
| `hand` | `LuaHand.java` | the same: `:item()` is an object, so a snapshot would carry the item's snapshot or nothing |
| `hafen.event():<name>()` | `AddonManager.java` | its only verb is `:on()`; there is no state to copy, so the row is open in form only |

The last three are open by the letter and answered by the argument beside them: a snapshot holds no live
objects, which is why `GobInfo` has no `hitbox` and an item's snapshot has no `container`. A `player:info()`
or a `hand:info()` would carry the id of the thing and nothing else.

## The 59 that answer

| Vocabulary | Declared in | | Vocabulary | Declared in |
|---|---|---|---|---|
| `attr` | `LuaAttr.java` | | `option` (declared) | `LuaOption.java` |
| `binding` | `LuaBinding.java` | | `overlay` | `LuaOverlay.java` |
| `buff` | `LuaBuff.java` | | `pagina` | `LuaPagina.java` |
| `cat` | `LuaIconCat.java` | | `panel` | `VirtualApi.java` |
| `channel` | `LuaChannel.java` | | `partymember` | `LuaPartyMember.java` |
| `condition` | `LuaCondition.java` | | `patch` | `VirtualApi.java` |
| `contents` | `LuaContents.java` | | `petal` | `LuaPetal.java` |
| `craftspec` | `LuaCraftSpec.java` | | `placing` | `LuaPlacing.java` |
| `credo` | `LuaCredo.java` | | `position` | `LuaPosition.java` |
| `deckcard` | `LuaDeckCard.java` | | `quest` | `LuaQuest.java` |
| `experience` | `LuaExperience.java` | | `role` | `LuaRole.java` |
| `fep` | `LuaFep.java` | | `rule` | `LuaRule.java` |
| `fepentry` | `LuaFepEntry.java` | | `segment` (a meter's) | `LuaMeterSegment.java` |
| `fightsummary` | `LuaFightSummary.java` | | `segment` (the map's) | `LuaSegment.java` |
| `food` | `LuaFood.java` | | `session` | `LuaSession.java` |
| `ghost` | `VirtualApi.java` | | `sheet` | `LuaSheet.java` |
| `gob` | `LuaGob.java` | | `skill` | `LuaSkill.java` |
| `grid` | `LuaMapGrid.java` | | `slot` | `LuaSlot.java` |
| `hunger` | `LuaHunger.java` | | `sound` | `LuaSound.java` |
| `item` | `LuaItem.java` | | `speed` | `LuaSpeed.java` |
| `kin` | `LuaKin.java` | | `sprite` | `VirtualApi.java` |
| `maneuver` | `LuaManeuver.java` | | `studyslot` | `LuaStudySlot.java` |
| `mapimage` | `MapImages.java` | | `studysummary` | `LuaStudySummary.java` |
| `marker` | `LuaMarker.java` | | `timer` | `AddonManager.java` |
| `mask` | `LuaMask.java` | | `toggle` | `LuaOverlayToggle.java` |
| `mesh` | `AssetApi.java` | | `widget` | `LuaWidget.java` |
| `message` | `LuaMessage.java` | | `widgetoverlay` | `LuaWidgetOverlay.java` |
| `meter` | `LuaMeter.java` | | `wound` | `LuaWound.java` |
| `miss` | `LocaleApi.java` | | | |
| `object` | `VirtualApi.java` | | | |
| `opponent` | `LuaOpponent.java` | | | |

`segment` also names two vocabularies — a meter's band and the map's segment — and both answer.

## The two shapes outside the count

A **section** is the door: a per-addon singleton reached as `hafen.<name>()`, whose verbs are the API's own
surface rather than the state of a thing. The rule speaks of what a *read hands back*, so it does not reach
a section — and several carry `:info()` anyway (`hafen.locale()`, `s:craft()`), where the section object is
itself the thing being described.

A **collection** is a set. Its members are what it holds, `:list()` is the array of them and `:count()` the
size, so the copy `:info()` would make is `:list()` by another name. One vocabulary, shared by every
collection, built in `LuaCollection.java`.

## What the rule says after this

Both statements name the three categories and stop claiming an absolute: a builder, a snapshot and a carrier
of an ending carry no `:info()` by design, and a live object that lacks one otherwise lacks it by omission
rather than by rule. The seventeen rows above are that omission, listed.

`conventions.md`'s own sentence — *"A point-in-time copy is what `:info()` gives you, and nothing else
does"* — states what the verb is rather than who carries it, and nothing in this census contradicts it.
