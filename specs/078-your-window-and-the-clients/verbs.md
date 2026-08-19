# The verbs of `hafen.ui()`, and the number that survives

Every verb `UiApi.installUi` mounts, which half it is in, and why. Written by `078.1` and read by
`078.2`, `078.3` and `078.4` rather than re-derived; it freezes with this folder.

The list is complete and checked both ways: **31 `m.set(...)` calls** in `UiApi.installUi`, 31 verbs
below, no name on one side missing from the other.

**The question is not which `UI` a verb reads today** — today everything reads the drawn session,
because for most of this client's life there was only one. The question is **what the thing it names
belongs to**: the addon, the client, the screen, or one character.

---

## Yours — the layer. 19 verbs, 93 occurrences. Global.

You build it; it is yours; since `074.1` it parents into `LayerRoot` and is drawn above every session.
Nothing here moves, and the work of `078.2` on these is saying on the page why.

| Verb | `docs/` | Why it is yours |
|---|---|---|
| `window` | 22 | builds a `Window` into the layer, configured by chained setters — nothing looked one up |
| `widget` | 6 | the same builder with `window = false`: a bare container of your own, **not a lookup** |
| `overlay` | 5 | mints a `HudOverlay` of yours; see the note below on which tree paints it |
| `button` | 11 | |
| `image` | 7 | |
| `check` | 4 | |
| `progress` | 4 | |
| `dropdown` `entry` `grid` `label` `menu` `radio` `scrollbar` `separator` `slider` `table` | 3 each | the sixteen constructors: each mints a control of yours through `Controls`, and a control is a widget you own |
| `list` `scroll` | 2 each | |

**`widget` is a constructor, not a lookup.** `spec.md`, `plan.md` and `tasks.md` all list it under
*the client's*; the source does not. `m.set("widget", …)` calls `newUi(owner, a, false)` — the very
function `window` calls with `true` — and its refusal reads *"takes no arguments — it is built bare
and configured by chained setters"*. The verb that takes a widget id is `node(id)`, and the one that
takes a selector is `find(sel)`. **Moving `widget` would be the exact failure this feature is warned
against**: a session-addressed constructor would compile, run, and be wrong. It stays global, and its
6 occurrences are survivors.

**`overlay` is global whichever tree ends up painting it.** It is queued as `host().drawafter(...)` —
the *screen's* after-draw, not the layer's — which is why `ROADMAP` carries *"a HUD overlay is an
after-draw of the session's own tree, so it paints under an addon's own windows"* `(filed: 074)`.
That line stands and is not this feature's to fix. What matters here is that neither answer is a
session address: the painter is over what is drawn, and there is one of those.

## The screen — 4 verbs, 43 occurrences. Global, and not for the layer's reason.

There is one pointer and one coordinate space however many characters are logged in. A hit test is a
question about the screen, not about a login, and `072` built `screen()` for exactly these.

| Verb | `docs/` | Reads today | Why it is not a session's |
|---|---|---|---|
| `mouse` | 16 | **`screen()`** already | `LuaMouse` reads `UI.mc` and `UI.modflags()` off `AddonManager.screen()` at all four sites (`x`, `y`, `over`, `mods`). Confirmed, not moved |
| `at` | 17 | `host()`, via `nodeAt` | takes an **arbitrary** screen point. `mouse.md` records that `m:over()` *is* `hafen.ui():at(m:x(), m:y())`, so the pair cannot answer about two different trees |
| `tipAt` | 5 | `host()` | `at`'s sibling and the same question — who would speak for a screen point |
| `scale` | 5 | neither | `Px.factor()` is `UI.scale(1.0)`, and `docs/client/ui-scaling.md` records `UI.scalef` as a `private static final double` read once at class load. It is **the client's, not even the screen's**: there is no `UI` instance in the answer to hand a session to |

So `078.4` turns `at` and `tipAt` from `host()` into `screen()`. That is two of `UiApi`'s 21
`host()` sites decided here; the other nineteen are still `078.4`'s to read one at a time.

## The addon's own declaration — 1 verb, 44 occurrences. Global.

| Verb | `docs/` | Why |
|---|---|---|
| `sheet` | 44 | a **rule declaration** owned by the installing addon, applied live to whatever matches. `Sheet` calls neither `host()` nor `screen()` at all — it reaches per-session state through `state(w.ui)`, off the widget it is dressing, so it already applies in every session. Addressing it would ask every theming addon to install itself once per session for the look it gets once, and nobody wants one character's chat skinned differently from another's |

## The client's — the session. 7 verbs, 92 occurrences. These move.

Each reaches a widget the game placed for one character, so each grows an address.

| Verb | `docs/` | Becomes | Reads today |
|---|---|---|---|
| `find` | 38 | `s:ui():find(sel)` | `host()`, via `selectFirst` |
| `on` | 20 | `s:ui():on(sel, ev, fn)` | `host()`, recorded on the watch at registration |
| `inventory` | 11 | `s:ui():inventory()` | `CharApi.maininv()` |
| `all` | 10 | `s:ui():all(sel)` | `host()`, via `selectAll` |
| `root` | 7 | `s:ui():root()` | `host()`, via `nodeRoot` |
| `equipment` | 3 | `s:ui():equipment()` | `CharApi.equipory()` |
| `node` | 3 | `s:ui():node(id)` | `host()`, via `nodeById` |

**`root` does not grow a layer twin here.** `ROADMAP` carries *"`hafen.ui()` is one namespace over
two trees … an addon can neither select nor hit-test the windows it built itself"* `(filed: 074)`,
and this feature's acceptance criteria do not ask for search verbs on the layer. `078.2` moves the
seven and adds nothing; a reader who wants their own window holds the handle the builder gave them.

---

## The arithmetic

The metric is **occurrences**, `grep -rno`, because a line carrying two calls is one line and two
decisions. The line figure `spec.md` quotes is given beside it so the two are comparable.

Every figure here is **as `078.1` closes**, its own two edits to `mouse.md` and `pixels.md` included.
`spec.md` counted before them and reads two lower; this page is the one the close checks against.

### `hafen.ui()` — 275 occurrences

| | Occurrences |
|---|---|
| Verb-bearing (`hafen.ui():<verb>`) | 272 |
| Bare, as prose naming the section | 3 |

Of the 272: **180 survive** (93 yours + 43 screen + 44 sheet) and **92 move** (the seven above).

The 3 bare ones are `api/ui/README.md`, `api/ui/widget.md` and `api/ui/mouse.md`, each prose calling
`hafen.ui()` the section. The section keeps that name, so all three survive.

**`hafen.ui()` survives 183 times.**

### `hafen.store()` — 23 occurrences

Scope is declared in the manifest, not chosen at the call, so this splits by **which variable each
`get` names**, not by verb. Two pages declare `saved_variables`; the rest use a name incidentally.

| Name | Sites | Scope | Verdict |
|---|---|---|---|
| `settings` | `getting-started.md` ×5, `guides/saved-data.md` ×3, `guides/debugging.md` ×1 | declared bare in both manifests → **per character** | moves, 9 |
| `settings` | `api/store.md:8`, the page's opening example | the archetype per-character name, on the page whose own table says a bare name is per character | moves, 1 |
| `seen` | `guides/saved-data.md:15` | declared `{"scope": "account"}` | stays, 1 |
| `cfg` | `api/http.md` ×2, `api/map/README.md`, `api/map/markers.md`, `api/ui/controls/interactive.md` | an addon's own configuration — a bearer token, a camp pin, a checkbox — none of it a character's | stays, 5 |
| `spot` | `api/world.md` ×3, `api/vr/README.md` | a saved **place**. `namespaces.md` already settles this for `vr`: a durable Position is a grid id and an offset, which is the server's and *means the same place in every session*. `world.md:154` reading one character's position to save it does not make the place theirs | stays, 4 |
| `get(name)`, `flush()` | `api/store.md:33`, `:34` | the section's own verb table | stays, 2 — and `078.3` adds the `session:store()` rows beside them |
| `flush()` | `guides/saved-data.md:79` | prose on forcing a write; the account scope keeps it | stays, 1 |

**`hafen.store()` survives 13 times, and 10 move.**

### The targets

| | Now | After |
|---|---|---|
| `grep -rno "hafen\.\(ui\|store\)()" docs/ \| wc -l` | **298** | **196** |
| `grep -rn "hafen\.\(ui\|store\)()" docs/ \| wc -l` (lines) | **288** | **190** |
| `grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/ \| wc -l` | **212** | **212** |

**102 occurrences move**: 92 `ui`, 10 `store`.

The number that must hold **exactly**, and the one to check first, is not 196 — it is **zero**:

```
grep -rn "hafen\.ui():\(find\|all\|on\|root\|node\|inventory\|equipment\)" docs/
```

196 is what the verdicts predict, and a deviation from it is a claim that must be paid for by naming
one of the two allowances below. It is not licence to drift.

**The two allowances.** `078.2` rewrites `api/ui/README.md` and `078.3` rewrites `api/store.md` and
`guides/saved-data.md`; those three are the pages that *explain the split* rather than merely use
it, so their prose mentions of `hafen.ui()` and `hafen.store()` are editorial. Every other page's
count is structural and follows from the table above. A close that lands off 196 says which of the
three pages moved and by how much, or it is a cut that moved too much.

## The impact set is short by five pages

`spec.md`'s **Written** list names the pages this feature rewrites. Five pages carry a **moving**
occurrence and are not on it — 8 occurrences in all, and each one is a sentence that goes on saying
`hafen.ui():find(...)` after `find` has moved:

| Page | Movers |
|---|---|
| `docs/addons/runtime.md` | 2 |
| `docs/addons/guides/events-and-timers.md` | 2 |
| `docs/addons/api/craft.md` | 2 |
| `docs/addons/api/vr/widgets.md` | 1 |
| `docs/addons/api/event/bus.md` | 1 |

`078.2` revises these five with the twenty it was already given.

Eleven further pages carry `hafen.ui()` or `hafen.store()` without being on the list —
`api/font.md`, `api/world.md`, `api/http.md`, `api/asset.md`, `api/player.md`, `api/vr/README.md`,
`api/client/README.md`, `api/map/README.md`, `api/map/markers.md`, `api/map/drawings.md` and
`guides/permissions.md`. Every one of their occurrences is a **survivor**, so they need no revision
and are discharged here by that reason.

`examples.md` is on the list and carries no occurrence of either name at all.
