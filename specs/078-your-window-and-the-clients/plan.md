# 078 — plan

## Approach

Four tasks. The first decides and records; the second and third each move one half of one namespace;
the fourth deletes the accessor the whole sequence has been walking towards and runs the closing
proof.

### The inventory comes first, because this checklist does not reach zero

Every earlier feature in this sequence could be bracketed by *"this grep is 195 and must become 0"*.
This one cannot: the layer half of `hafen.ui()` keeps its spelling, and so does the account half of
`hafen.store()`. So `078.1` writes `verbs.md` — every verb, its half, its reason, **and the arithmetic
that turns those verdicts into the exact number of occurrences that must survive**. The close checks
against a figure this feature derived. A target anyone guessed would be a target that cannot fail.

### The three halves, and why the middle one exists

**Yours — the layer.** `window`, `overlay`, and the sixteen control constructors. `074.1` already
parents `hafen.ui():window()` into `LayerRoot`; nothing about these moves, and the task's work is
saying on the page why they do not.

**The screen — global, and not for the layer's reason.** `mouse`, `at(x, y)`, `tipAt(x, y)`, `scale`.
`mouse.md` records that `m:over()` **is** `hafen.ui():at(m:x(), m:y())`, and `at` takes an arbitrary
screen point rather than reading the pointer. There is one pointer and one coordinate space, so a hit
test is a question about the screen. These are `screen()` sites, and `072` built that name for exactly
them — the danger is that a sweep sees "reaches a widget tree" and moves them onto a session.

**The client's — the session.** `find`, `all`, `on`, `root`, `widget`, `node`, `inventory`,
`equipment`. Each reaches a widget the game placed for one character, so each grows an address.

**`sheet` stays global**, and it is the largest verb at 44 uses. Its page says it restyles *the
client's own surfaces*; it is a rule declaration owned by the installing addon, applied live to
whatever matches. Making it addressable would ask every theming addon to install itself once per
session to get the look it already gets once.

### `host()`, and why its callers are read one at a time

105 callers — `UiApi` 21, `AddonManager` 19, `VrApi` 17, `Retired` 6, `ProfHandle` 4, `Layout` 4,
`CharApi` 4, `LuaEvent` 3, `FlowerMenuApi` 3, and the rest in ones and twos. That list is what `072`
bought by separating `host()` from `screen()`, and it is a **list of questions, not a list of edits**:
some of those sites are the drawn session only by accident and are really `layer()` or `screen()`.
`VrApi`'s seventeen are the clearest suspicion — `075.3` made entities world-shaped, so a `host()`
read left there is probably asking about the scene, which is `screenView()`'s subject.

The argumentless form is deleted last, in `078.4`, so the build is the proof: a site nobody converted
does not compile. That is `072.3`'s shape, reused because it worked.

### `store`

The account scope is the addon's — one file whichever character is up — and keeps `hafen.store()`.
The per-character scope is that character's and is reached through its session. `074.4` already made
it flush when its session ends, hung on the same `UI` death `073` hangs state release on, so what
moves here is the address and not the lifecycle.

## Files to create and modify

| File | Task | What |
|---|---|---|
| `specs/078-…/verbs.md` | 1 (writes), 2–4 (read) | every verb, its half, its reason, and the surviving-count arithmetic |
| `src/io/brodgar/addon/UiApi.java` | 1, 2, 4 | the split; 21 `host()` callers |
| `src/io/brodgar/addon/LuaSession.java` | 2, 3 | the `ui` and `store` handles, interned as `076` interns `world` |
| `src/io/brodgar/addon/Sheet.java` | 1, 2 | stays global; the page says why |
| `src/io/brodgar/addon/LuaMouse.java` | 1 | `screen()`, confirmed rather than moved |
| `src/io/brodgar/addon/StoreApi.java` | 3 | the scope split |
| `src/io/brodgar/addon/AddonManager.java` | 1–4 | `host(UI)`; the argumentless form deleted in 4 |
| `src/io/brodgar/addon/{VrApi,CharApi,ProfHandle,Layout,LuaEvent,FlowerMenuApi,WidgetSubs,LuaWidgetEntity,VideoOptions}.java` | 4 | the remaining callers, one decision each |
| `src/io/brodgar/addon/Retired.java` | 2, 3, 4 | the moved spellings |
| `docs/addons/api/ui/**` | 1, 2 | 20 pages |
| `docs/addons/api/store.md`, `guides/saved-data.md` | 3 | |
| `docs/addons/{api/README,api/conventions,api/references,api/types}.md`, `getting-started.md`, `examples.md`, `guides/{custom-ui,debugging,theming}.md` | 1–3 | the cross-cutting examples |

## Risks and gotchas

- **The danger here is moving too much, not too little.** Every earlier feature failed safe: an
  unmoved spelling threw. This one has verbs that legitimately keep their spelling, so a sweep that
  addresses `mouse` or `sheet` produces working code that is wrong. The guardrail is 212 and the
  survivor count is `verbs.md`'s; both are checked at every task boundary.
- **`hafen.ui():on(sel, "appear", fn)` scans the live tree at registration.** Addressed at a session,
  it scans that session's tree — including one not drawn, whose widgets exist. Its handle ends with
  `:remove()` rather than `sub:off()`, which is a wart the page already carries; do not fix it here
  and do not let the move hide it.
- **A session that has not reached the world has no `GameUI`**, so `session:ui():inventory()` answers
  the nothing it answers before login rather than throwing — `076`'s sections already answer
  *nil-shaped rather than throwing*, and these follow.
- **`Retired` reads `host()` six times**, which means the retirement machinery itself is a caller.
  Convert it deliberately: a refusal that needs a session to be phrased is a refusal that cannot fire
  before there is one.
- **`ant hafen-client` is incremental**; `rm -rf build/classes` first, and especially before believing
  `078.4`, whose whole claim is that the build fails without the three tasks before it.

## Discarded alternatives

- **Making `sheet` addressable per session** — it is a rule declaration owned by the addon, applied to
  whatever matches. Addressing it would ask every theming addon to install itself once per session to
  get the look it gets once today, and nobody wants one character's chat skinned differently from
  another's.
- **Moving `mouse`, `at` and `tipAt` onto the session because they reach a widget tree** — they answer
  about a screen point, and `mouse.md` already records that `m:over()` *is* `hafen.ui():at(m:x(),
  m:y())`. There is one pointer. `072` built `screen()` for this and separating it from `host()` was
  the whole point.
- **One `hafen.ui()` that takes an optional session** — an optional argument is two spellings of one
  verb wearing one name, and it would put "your window" and "the client's window" back in the same
  namespace, which is the confusion this feature exists to end.
- **Sweeping `host()`'s 105 callers mechanically** — the list is questions, not edits. `VrApi`'s
  seventeen in particular are suspect after `075.3` made entities world-shaped, and a sweep would
  freeze an accident into an address.
- **Deleting the argumentless `host()` first** — nothing would compile until every task was done,
  which is one task pretending to be four. Deleting it last makes the build the proof.
- **Setting the checklist target by estimate** — this is the one feature whose grep does not reach
  zero, and a target nobody derived is a target that cannot fail. `078.1` computes it from the
  verdicts.
