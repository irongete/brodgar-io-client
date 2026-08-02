# 033-ui-stylesheet — Spec

## What & why

**One stylesheet replaces the font-scope API.** A selector as the key, a table of properties as the value,
applied live and owned by the addon that installed it.

```lua
local body = hafen.asset("fonts/Inter.ttf"):derive{ size = 12 }
hafen.ui.skin{
  ["*"]            = { font = body },
  ["window.title"] = { font = body:derive{ size = 14, bold = true } },
  ["chat"]         = { color = {200,210,200} },
}
```

**Scope, stated plainly.** This is **C1a**: the sheet engine, the properties `font` and `color`, and the keys
that resolve **at a render site** — the eleven surfaces `016-fonts` already routed. It does **not** yet resolve
a rule against a widget in the tree (`["window[title=Cupboard]"]`) — a different mechanism, and it is **C1b**,
next. The honest delivery here is *the front door and `color`*, not new reach; the reach arrives in C1b and C2.

**Fonts are absorbed, not duplicated.** `hafen.font.setFont(scope,h)` / `reset` / `scopes` are a **hard cut**: a
font becomes one property among others, and the sheet is the single place that says what a surface looks like.
`hafen.font` keeps one job — naming an **engine** font (D-060); a `.ttf` is still `hafen.asset(path):derive{}`.

### Why the key space has two halves (and why C1b is separate)

030 settled (D-067) that **five of the twelve role names classify no widget at all** — `window.title`,
`heading`, `tooltip`, `world.nick`, `world.speech` name a **render site**, not a thing in the tree. So a key
resolves one of two ways:

- **Site keys** — resolved at the draw site, as fonts already do: the eleven routed surfaces (`window.title`,
  `heading`, `tooltip`, `world.*`, `chat`, `button`, `label`, `textentry`, `menu`). **C1a.**
- **Tree keys** — resolved per widget against the live tree: `@Class`, `[title=]`, `[res=]`, roles as *widget*
  matches. **C1b.**

Written identically, cascading identically; only resolution differs. `"default"` does not carry over — its
twin is **`*`**, which here is the fallback for every routed site (C1b widens it to every widget too).

**Conflict between addons gets no new answer**: 016-fonts' owner-tagged stack (D-043) — last applied wins, an
addon's entries pulled on teardown, stock always restorable — is shipped and proven across eleven surfaces, and
is what the sheet reuses. **Cascade**: most specific matching rule → `*` → stock.

## Acceptance criteria

- [ ] `hafen.ui.skin{ ["*"] = {font=h} }` changes the client's text **live**, exactly as `setFont("default",h)`
      did; a second call **replaces** that addon's sheet (an addon owns one); `hafen.ui.skin(nil)` drops it.
- [ ] Every one of the eleven routed surfaces is reachable by its key and matches its old `setFont` behaviour.
- [ ] `color` applies wherever the site draws text, `{r,g,b[,a]}` 0..255 — including the F3d sites whose font is
      not a `Text.Foundry` and which read scalars through `Fonts.style`.
- [ ] A key that is valid grammar but resolves nowhere yet (a tree key here) is **silently inert** — never an
      error at apply time, so a C1b-ready sheet loads today.
- [ ] **Hard cut**: `hafen.font.setFont`, `reset` and `scopes` read `nil`; `hafen.font(name)` still answers for
      the four built-ins; `widget:setFont`/`:resetFont` are untouched here (C1b turns them into `widget:skin`).
- [ ] **Two addons, one surface**: last to apply wins; disabling it falls back to the other, not to stock;
      disabling both restores stock; `:reload` and relog behave.
- [ ] **The sheet is data**: a table loaded from JSON (`hafen.json`), its font strings mapped through
      `hafen.asset` in Lua, applies identically — a theme with no code of its own.
- [ ] The docs publish the **property × key table** (what each site accepts, what it ignores); `hello` checks
      the contract once per login; full regression passes.

## Out of scope

- **Tree keys and `widget:skin{…}`** — C1b, the next feature.
- **`bg`, `border`, `pad`, textures, `Window.Deco`** — C2. Nothing in C1 may change a widget's size.
- Descendant selectors, pseudo-classes, `hover`/`down` states; new roles or classifier changes; layout — E.

## Context files

- `design/21-fonts.md` — the provider, the scope enum, the owned-override stack (D-043), the resolution chain
  this generalises, and `Fonts.style` for the non-`Foundry` sites; `design/22-ui-selectors.md` — the grammar and
  the two key classes (D-067)
- `src/haven/Fonts.java` — `SCOPES`, `foundry`/`style`/`gen`, the owner-tagged stack: the engine half widened
- `src/io/brodgar/addon/FontApi.java` — `setFont`/`reset`/`scopes` (cut) and the teardown sweep to reuse;
  `Selector.java` — parsed selectors as sheet keys; `UiApi.java` — where `hafen.ui.skin` installs
- `docs/addons/api/fonts.md`, `ui.md`, `conventions.md` — the surfaces cut, extended and cross-referenced
- `016-fonts/` — the override stack and every routed site; `026-text-cache/` — why `Fonts.gen()` is a cache key
  and **not** a frame-global clear signal
- `learnings/fonts.md` — **grep, never read whole**; `decisions/fonts.md` (D-043), `widgets-ui.md` (D-067),
  `architecture-api.md` (D-012, D-062)
