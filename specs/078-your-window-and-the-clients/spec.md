# 078 — your window, and the client's

## What and why

Eight features led here. `074` lifted the addon layer above the sessions; `076` gave the client an
address; `077` moved the character family onto it. **`ui` and `store` are what is left, and both are
split rather than moved** — which is the consequence of the layer, and the last thing the sequence
has to say.

**Your window and the client's window are not the same thing.** `hafen.ui():window()` builds
something of yours, which since `074.1` parents into `LayerRoot` and is drawn above every session and
above the login screen. `hafen.ui():find("window[title=Inventory]")` reaches a window **the game put
up for one character**. Only a client with one login could pretend those shared a namespace. This is
not an exception to *one canonical way*: they are two operations, and each keeps one spelling.

`store` splits the same way and for the same reason: an account's saved variables are the addon's, one
file whichever character is up; a character's are that character's, and `074.4` already made them
flush when their session ends.

And `AddonManager.host()` finally takes the session it was separated for. `072` split it from
`screen()` so that when this day came, **the list of sites that must grow an argument would already be
known**. It is 105 callers, and the list is the thing that was bought.

### The split is decided verb by verb, and recorded

Which half a verb falls in is not obvious from its name, so `078.1` writes `verbs.md` in this folder:
every `hafen.ui()` verb, its half, and the reason. Three groups, and the middle one is the surprise:

- **Yours — the layer.** `window`, `overlay` and every control constructor (`button`, `label`,
  `entry`, `check`, `radio`, `slider`, `scroll`, `scrollbar`, `dropdown`, `menu`, `list`, `table`,
  `grid`, `image`, `progress`, `separator`). You build it; it is yours; it is above everything.
- **The screen — also global, and not for the layer's reason.** `mouse`, `at(x, y)`, `tipAt(x, y)`,
  `scale`. There is one pointer and one coordinate space: `mouse.md` records that `m:over()` **is**
  `hafen.ui():at(m:x(), m:y())`, and `at` takes an arbitrary screen point. A hit test is a question
  about the screen, not about a login.
- **The client's — the session.** `find`, `all`, `on`, `root`, `widget`, `node`, `inventory`,
  `equipment`. Each reaches a widget the game placed for one character.

**`sheet` is global, and it is the largest single verb at 44 uses.** Its own page says it is for
restyling *the client's own surfaces*, and it is a declaration of rules owned by the addon that
installed it — applied live to whatever matches, in every session. Skinning one character's chat
differently from another's is not a thing anyone wants, and making the sheet addressable would ask
every styling addon to install itself N times.

## Acceptance criteria

1. `verbs.md` exists, classifying every `hafen.ui()` verb as **yours**, **the screen** or **the
   client's**, with the reason for each — and **computing the exact number of `hafen.ui()` and
   `hafen.store()` occurrences that must survive in `docs/`**, so the close is checked against a
   figure this feature derived rather than one anybody guessed.
2. The client's widgets are reached through the session: `session:ui():find(selector)`, `:all`, `:on`,
   `:root`, `:widget`, `:node`, `:inventory`, `:equipment` — **for any session, including one that is
   not drawn.**
3. Your own things stay global and keep working: `hafen.ui():window()` and every constructor, plus
   `sheet`, `mouse`, `at`, `tipAt` and `scale`, which never take a session.
4. `hafen.store()` splits: the account scope stays global; the per-character scope is reached through
   its session and flushes when that session ends, as `074.4` left it.
5. `AddonManager.host()`'s **argumentless form does not exist**. Each of its 105 callers answers with
   a session, with `layer()`, or with `screen()` — decided one at a time, not swept, because some of
   them were only ever the drawn session by accident.
6. The guardrail holds: `grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/`
   is **212 before and 212 after**.
7. **The sequence's closing proof runs**: one window of your own, in the layer, showing the inventory
   of every live session at once.

## Out of scope

- The **`Fonts`** subsystem — 29 files of `haven` rewired internally. It is the fork's most expensive
  debt and it only matters if upstream is merged again; it has nothing to do with sessions.

Nothing else. This feature closes the sequence, so anything it turns up is decided and done here.

## Docs impact

**Written**: all of `api/ui/**` — `README`, `custom`, `drawing`, `edit`, `items`, `lists`, `mouse`,
`native`, `pixels`, `replace`, `selectors`, `widget`, `controls/**` (3 pages) and `style/**` (8
pages) — plus `api/store.md`, `guides/saved-data.md`, `guides/custom-ui.md`, and the cross-cutting
pages whose examples call either: `api/README.md`, `api/conventions.md`, `api/references.md`,
`api/types.md`, `getting-started.md`, `examples.md`, `guides/debugging.md`, `guides/theming.md` —
and the five `078.1` found carrying a moving occurrence and derived per page in `verbs.md`:
`runtime.md`, `guides/events-and-timers.md`, `api/craft.md`, `api/vr/widgets.md`,
`api/event/bus.md`.

**Derived set — the checklist:**

```
grep -rn "hafen\.\(ui\|store\)()" docs/
```

**286 today** — `ui` 263, `store` 23. **This one does not go to zero**, because the layer half keeps
its spelling; the target is the figure `078.1` computes and records in `verbs.md`, and the close
checks against that.

**Derived set — the guardrail:**

```
grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/
```

**212, and it must still be 212.** A split fails by moving too much, and this is the number that
catches it.

## Context files

- `specs/075-globals-without-a-session/namespaces.md` — 1, 2, 3, 4 (read: the classification)
- `specs/078-your-window-and-the-clients/verbs.md` — 1 (writes), 2, 3, 4 (read)
- `src/io/brodgar/addon/UiApi.java` — 1, 2, 4
- `src/io/brodgar/addon/LuaSession.java` — 2, 3
- `src/io/brodgar/addon/LuaWidget.java` — 2
- `src/io/brodgar/addon/Layout.java` — 2, 4
- `src/io/brodgar/addon/Sheet.java` — 1, 2
- `src/io/brodgar/addon/LuaMouse.java` — 1
- `src/io/brodgar/addon/StoreApi.java` — 3
- `src/io/brodgar/addon/AddonManager.java` — 1, 2, 3, 4
- `src/io/brodgar/addon/VrApi.java` — 4
- `src/io/brodgar/addon/CharApi.java` — 4
- `src/io/brodgar/addon/ProfHandle.java` — 4
- `src/io/brodgar/addon/LuaEvent.java` — 4
- `src/io/brodgar/addon/FlowerMenuApi.java` — 4
- `src/io/brodgar/addon/WidgetSubs.java` — 4
- `src/io/brodgar/addon/LuaWidgetEntity.java` — 4
- `src/io/brodgar/addon/VideoOptions.java` — 4
- `src/io/brodgar/addon/Retired.java` — 2, 3, 4
- `src/io/brodgar/addon/Section.java` — 2, 3 (the per-verb `__index` a split section mounts)
- `src/io/brodgar/session/Sessions.java` — 2, 4
- `docs/addons/api/conventions.md` — 1, 2, 3
- `DOCUMENTATION.md` — 1, 2, 3, 4
