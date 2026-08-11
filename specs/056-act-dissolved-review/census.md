# 056.1 — The census

> One row per surface `047-flowermenu` and `048-act-dissolved` shipped: page, anchor, what the page claims,
> what `src/` does, verdict. **Both directions.** **Edits no docs page** — §5 is the worklist `056.2` writes
> from. Every zero here was falsified by planting the thing it looks for and catching it.

## Method & ground truth

- **Ground truth is `src/io/brodgar/addon/`**, never a feature spec: a page's wrong sentence is usually
  that spec's own rationale copied verbatim (005.2). The registration oracle is `\.set\("<name>"`; a plain
  section's roster is its own receiver check, `Section.self(self, "<section>", "<verb>")`; the protected
  tier's roster is `requireActions(owner, "<verb>")`, which is the whole gate and reads **22** call sites.
- **Every grep is `-E`, never `-niF`** (005.4: the dead tool prints `0`), and every zero was reproduced as a
  positive first — see §4.
- Behavioural claims about the radial menu were read off `src/haven/FlowerMenu.java`, which is where the
  animation lengths and the input grab actually live.

## 1. The rehoused verbs (`048`)

| Verb | Page · anchor | `src/` | Verdict |
|---|---|---|---|
| `hafen.player():move(p)` | `player.md` §Write (protected: actions) | `CharApi.java:945` | **OK** |
| `hafen.player():hand()` | `player.md` §The Hand | `CharApi.java:962` → `LuaHand.of` | **OK** |
| `hand:item()` | `player.md` §The Hand · `ui/items.md` §Read | `LuaHand.java:111` | **OK** |
| `hand:use(target, mods)` | `player.md#the-hand` (own `###`) | `LuaHand.java:122` | **OK** |
| `gob:click(button, mods)` | `gob.md` §Write (protected: actions) | `LuaGob.java:322` | **OK** |
| `item:use(mods)` | `ui/items.md` §Write (protected: actions) | `LuaItem.java:257` | **OK** |
| `item:take()` | same | `LuaItem.java:268` | **OK** |
| `item:drop(n)` | same | `LuaItem.java:278` | **OK** |
| `item:transfer(n)` | same | `LuaItem.java:289` | **OK** |
| `hafen.world():place(p, angle, button, mods)` | `world.md` §Write (protected: actions) | `WorldApi.java:220` | **OK** |
| `hafen.world():select(p1, p2, mods)` | same | `WorldApi.java:238` | **OK** |
| `widget:send(msg, ...)` | `ui/widget.md` §Send a message (protected: actions) | `LuaWidget.java:561` | **OK** |
| `pag:use()` **and its new gate** | `menugrid.md` §Use (protected: actions) | `LuaPagina.java:299`, gate `:301` | **OK** |

Checked per row, against the method body and its `LuaError` strings, not against the registration alone:
return-the-receiver (all thirteen chain, and each page says so), the argument defaults (`mods` 0 · `n` -1 ·
`button` 1 · `item:take()` refusing any argument), which message carries a modifier field at all
(`ui/items.md:79` — "Only `use` carries modifiers, and that is the wire rather than a style" — matches
`iact`/`itemact` against `take`/`drop`/`transfer`), the three `hand:use` target arms with the Gob arm's
`clickargs()` extension, and the four raises `hand:use` names (no target · empty cursor · dead target · no
map view). `world.md:261`'s "Both raise before you are in the world, and for a Position this session cannot
locate" holds: `LuaPosition.worldArg` (`:197`) refuses an unlocatable place before the map-view check.

**The four rules `048` states as inheritable, read with particular care.** *The gate runs first* (D-213) —
true at all 22 sites and stated user-facingly at `conventions.md:282` ("an error naming the verb, before
anything is sent"); no page claims the argument check comes first. *A client-local write on a departed
thing is inert while a server write raises* (D-217) — true in `src/`, and the two pages that state the
general half are **stale**, W2/W3 below. *`hand()` is `nil` while the cursor is empty* — true, and
`player.md:60-62`/`:78-81` build the whole section on it. *An argument that leaves the client is never
coerced* — held where it was written for (`widget:send`'s `type() != TSTRING`, `:select`'s string-vs-number
split), **not** held by the numeric verbs: F1 below.

## 2. The `047` surfaces

| Surface | Page | `src/` | Verdict |
|---|---|---|---|
| `:list()` · `:count()` · `:gob()` | `flowermenu.md` §Read, §Which object the menu belongs to | `FlowerMenuApi.java:95, :110, :123` | **OK** |
| `:select(label\|n)` · `:cancel()` | `flowermenu.md` §Write (protected: actions) | `:136`, `:146` | **OK** |
| `FlowerMenuOpened` / `FlowerMenuClosed` | `flowermenu.md` §The two events · `event.md` §The radial menu | `AddonManager.java:882/:885` → `FlowerMenuApi.opened/closed` | **OK** |

Four claims were worth opening `haven` for, and all four hold. The **0.25–0.75 s** fade
(`flowermenu.md:75`) is `Cancel(0.25)` / `Chosen(0.75)` (`FlowerMenu.java:186, :154`). *The ring is still
animating open inside `FlowerMenuOpened`, the one window a real click cannot use* (`:107-109`) is
`mousedown` returning `true` while `!anims.isEmpty()` (`:272`). *Fires at the one moment the petal set is
complete* is the seam at the **end** of `added()` (`:254`), after `addVoicePetal` replaces `opts`. *Two
menus answer for the first the client holds* is the recursive walk in `FlowerMenuApi.open()`. The
`walker` claim at `flowermenu.md:111` is true: `:walker petal` arms `FlowerMenuOpened` and picks by
caption, by position and by `:cancel()`.

## 3. Both directions

**Forward — nothing shipped is undocumented.** All **22** protected verbs in `src/` appear in
`guides/actions-and-permissions.md`'s table, which calls itself "the whole set": 12 rows, 22 verbs, **0
missing** (the kin row folds five verbs into "add, rename, re-group and forget"; `kin.md:104-107` names all
five, `kin:endKin()` included). `hafen.world()`'s roster is **12** verbs since `place`/`select` landed, and
`world.md` documents all 12; `hafen.player()`'s closed-index message names five and `player.md` documents
five; `hafen.flowermenu()`'s five are all on its page. `BUS_KEYS` is **28** since `047` and every one of
the 28 is named on `event.md`.

**Backward — no page invents one.** Every colon verb used on the eleven pages under review, against the
registration set (487 names): **170 distinct, 4 raw offenders, 0 real** — `:format` (a Lua string method on
a literal), `:sender` (registered under a computed key, 005's known N/A), and `:same`/`:hide`, both of
which appear only inside a sentence saying the API does **not** have them (`ui/widget.md:37, :170`).

## 4. The deleted-verb check, falsified

| Grep (`-E`, over `docs/`) | Real tree | Planted copy |
|---|---|---|
| `hafen\.act\b` | **0** | 2 |
| `\b(un)?gated\b\|\bgating\b` | **0** | 1 |
| `:menu\(` | 4 — all `hafen.ui():menu()`, the live control | 5 |
| `:flower\b` | **0** | 1 |
| `:enabled\b` | **0** | 1 |
| `:raw\(\|rawTarget` | **0** | 1 |
| `"(mapview\|gameui\|root)"` — `raw`'s target vocabulary | **0** | 2 |
| `act\.md` · `act\(\)` · prose "the act namespace" / "the actions section" | **0** | — |

The plants were written the way a page would write them, appended to one file of a **copy** of `docs/`;
every grep caught its own and the real tree is untouched. Two notes for the §7 derivation: `hafen.act` is
admissible **only** as `hafen\.act\b` — plain `hafen.act` reads **19** on a healthy tree, all
`hafen.actionbar`. And `gated` needs the word boundary: `\bgated\b` reads 0 where bare `gated` reads 2,
both inside the word *propagated*.

## 5. The worklist

| # | Finding | Verdict | Task |
|---|---|---|---|
| W1 | `api/types.md:43` names **`hafen.ui():hand()`** for the cursor and links it to `ui/widget.md`. That spelling was retired by 048.2 — `Retired.java:314` throws on it, naming `hafen.player():hand():item()` — and `ui/widget.md` never documented it. Written by 039.14, on no `048` task's file list | WRONG | 056.2 |
| W2 | `api/ui/widget.md:42` — "every write is a silent no-op that still chains" — is now false: `widget:send` **raises** on a stale widget (`LuaWidget.java:573`), which the same page states at `:202` ("where every other write here is a silent no-op"). The page contradicts itself; `:42` is the unqualified half. Written by 001.4, before `send` existed | WRONG | 056.2 |
| W3 | `api/gob.md:108` — "Once the gob is gone the read answers `nil` and a write does nothing, **like every other method here**" — is false for `gob:click`, which raises (`LuaGob.java:331`), as the same page says at `:77`. Written by 046.1, before `click` existed | WRONG | 056.2 |
| T1 | `conventions.md:279` states the tier universally: "A verb that **sends the server an action the player could have performed** is protected." `ev:resend()`/`ev:send(t)` (`event.md:232-233`) reach the server through `UI.rawWdgmsg` with **no gate** — already filed to area `addons` in `STATE.md`. Either the engine gains the gate or the sentence is scoped; the fix is not this area's, the sentence is | THIN | 056.2 (wording only) |
| OK | §1's thirteen rows · §2's seven · §3's four zero counts · §4's eight zero greps | OK | — |

## 6. Filed to area `addons`

- **F1 — numeric arguments are coerced where the tier says they are not.** `WorldApi.number` (`:453`) and
  `LuaItem.count` (`LuaItem.java:324`) gate on `v.isnumber()`, which is **true for a numeric string** in
  LuaJ, so `hafen.world():place(p, "0.5")` and `item:drop("2")` are accepted and go to the server —
  where `widget:send` refuses exactly this (`type() != TSTRING`, and says why in its comment). It makes
  `world.md:242`'s "a missing or non-number `angle` raises" technically false. Engine, not docs.
- **F2 — a stale javadoc.** `FlowerMenuApi.java:165-166` still says the finder is "Shared with `ActApi`'s
  `flower(label)`, which is the older door onto the same widget"; 048.7 deleted that verb, and the class
  javadoc twelve lines above (`:38-42`) already says so.

## 7. What the census did not decide

The ceiling (`056.3`), the standard's own adjective and §7's list (`056.2`), and every tree-wide count —
links, headings, wrap, the retired-name re-derivation (`056.4`). No verdict above is a statement about a
page's **size** or its links: the anchors named here were resolved by hand against the headings they point
at, which is not the checker `056.3` and `056.4` run.
