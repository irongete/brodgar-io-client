# 023-menugrid-oop — Spec

## What & why
The **action menu** (`MenuGrid`, the 4×4 "scm" grid) is the client's catalogue of everything the
character can *do* — every unlocked pagina, in a category tree. Addons can already **receive** one on
a drop (`{kind="pagina", res=…}`, D-038) but cannot **enumerate** it: no way to ask "what actions do
I have", "which category is it under" — nor to invoke one that has no fixed path. This is the
*menu-ability primitive* deferred in [design/07](../design/07-ui-and-drawing.md#drops-ondrop): a
resolvable handle **and** its `:use()`. OOP from the start (D-056/D-057) — no flat predecessor.

```lua
for i, a in ipairs(hafen.menugrid()) do                    -- every known action, flat
  hafen.log(("%2d %-28s %s"):format(i, a:name() or "?", a:res()))
end
local dig = hafen.menugrid("Dig")                          -- or "paginae/act/dig" (a "/" ⇒ res)
hafen.log(dig:parent():name() .. " ▸ " .. dig:name())      -- "Dig" lives under a category
dig:use()                                                  -- fire it
```

## Decisions taken here (to confirm at review)
- **Callable-only** (D-056): `hafen.menugrid()` = the collection, `(key)` = one interned Pagina.
  **The key is always a string** — resource name or display name — and the server `Pagina.id` is
  never one of them (session-local and opaque, 022). Both strings return the *same interned object*,
  keyed internally by res, so `hafen.menugrid("Dig") == hafen.menugrid("paginae/act/dig")`.
- **No addressing by position.** The catalogue grows on every discovery, so a position is not an
  index (unlike `actionbar`'s fixed 0..143, D-057) but an artefact of one call's ordering. The array
  stays 1-based for `ipairs`; `hafen.menugrid(1)` throws, pointing at the two name forms.
- **A string splits by SHAPE, not by fallback**: contains `/` ⇒ resource name, else ⇒ display name.
  Deterministic, no double scan, no silent cross-talk where a display name hijacks a res lookup. The
  two are not equals and the docs say so: res name **is** the identity and is known early; display
  name is a *search convenience* — it needs the resource fully loaded (`AButton.name`, unlike
  `CachedRes.resnm`) and is **not unique** (first match in sort order wins).
- **Not found ⇒ `nil`**, both forms — unlike `hafen.kin(id)`, whose ids persist. "Not in `paginae`"
  = "you do not have that action"; `:exists()` still answers for a *stashed* handle.
- **The catalogue fills in as resources resolve**: names come from `pag.res().name` under a `Loading`
  guard, so an `OnEnterWorld` scan may be short (self-heals sub-second). Reading `CachedRes.resnm`
  earlier needs a core edit — deferred.
- **Lua class name `Pagina`** — the client's own word (`PagButton` is only its drawn button, not the
  thing); `tostring` = `Pagina(<res>)`. **Reads** `:res :name :path :parent :children :tooltip
  :hotkey :isnew :exists :info`; `:path()` = the `AButton.ad` tokens (empty for a category or an
  id-only pagina).
- **`:use()`, no arguments**, drives the client's own [`PagButton.use`](src/haven/MenuGrid.java:195)
  (wrap-not-reimplement, D-009) — the `"act"`-by-path / `"use"`-by-id wdgmsg a click sends, reaching
  id-only paginae no path can express. **No `mods`**: that method ignores `Interaction.modflags` and
  reads `ui.modflags()` live (plan.md has the trace). Returns **self**; a *category* errors,
  pointing at `:children()`.
- **Collection**: `:find(text)`, `:roots()` (top-level entries), `:list()` (`:info()` snapshots); the
  array is flat, covers **all** paginae, sorted by display name. Resource-backed reads are
  `Loading`-guarded to `nil` — never partial, never a thrown `Loading`.

## Acceptance criteria (verified in-game, one login)
- [ ] `:lua` — `#hafen.menugrid()` is plausible (hundreds) and grows after a new discovery;
      `local a = hafen.menugrid()[1]` then `a == hafen.menugrid(a:res()) and a == hafen.menugrid(a:name())`
      (one object, two keys); `"nope/nope"`/`"Nope"` → `nil`; `hafen.menugrid(1)` errors.
- [ ] Reads: `:name()` matches the in-game tooltip, `:res()` the resource name, `:hotkey()` the letter
      the grid paints under Alt, `:tooltip()` the pagina description text. Tree: `a:parent():name()`
      names the category the button sits in; `cat:children()` lists exactly the buttons that category
      shows; `hafen.menugrid():roots()` = the root screen's set.
- [ ] `:use()` performs the action — `hafen.menugrid("Dig"):use()` enters dig targeting exactly as
      clicking the button does; a category errors with a `:children()` hint.
- [ ] `:info()` snapshots the same fields; `hello` prints a menu summary and re-checks the read
      contract each login, `walker` demos `:use()`; **full regression passes**; `ant hafen-client` clean.

## Out of scope
- **Permissions / gating** — `:use()` lands ungated on purpose; the write-permission model is being
  restructured section-by-section in its own plan, along with **retiring `hafen.act.menu`**, which
  this feature only makes unnecessary.
- **A `MenuChanged` event** ([design/09](../design/09-events-catalog.md)) — poll on `OnUpdate`. **The
  visible 4×4 page** (`layout`/`curbtns`/`cur`) — private. Icons draw with `g:resource` (D-039).
- **Modifiers on `:use()`** — 4 `// addon:` lines on the grid's click/hotkey paths; no use case yet.

## Context files
- `src/haven/MenuGrid.java` — `paginae`, `Pagina` (`id`/`res`/`anew`/`parent()`/`button()`),
  `PagButton` (`name`/`act`/`bind`/`hotkey`/`use`), `paginafor`, `cons` — READ ONLY, no core edit
- `src/haven/GameUI.java` (`public MenuGrid menu`), `src/haven/Session.java` (`CachedRes`) — READ ONLY
- `src/io/brodgar/addon/LuaSlot.java` — the OOP template copied verbatim (cache, metatable, callable
  factory, `:use`); `LuaKin.java` — the variable-size collection with `:find`/`:list`
- `src/io/brodgar/addon/Addon.java`, `AddonManager.java` — the per-addon cache field + install site;
  `design/07-ui-and-drawing.md`; `docs/addons/api/{actions,types,conventions}.md`; `021-actionbar-oop/`
  and `020-kin-oop/` — the two OOP precedents this follows
