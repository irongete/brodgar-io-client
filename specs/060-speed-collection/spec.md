# 060 — `hafen.speed` becomes the collection of speeds

## What & why

`hafen.speed()` is the last section that writes through a verb that **addresses a member**.
`:current()` reads the selected speed as a number and `:current(n)` selects one, `:max()` hands back the
highest selectable index, and `:name(n)` the display name of a number. Three things are wrong with it:

- **`:current()` is a distinguished member, not a property.** The grammar says a distinguished member is a
  verb on its collection — `:current()`, `:selected()`, `:leader()` — and that `coll:get(key)` "addresses a
  member rather than reading a property". A member address that also writes is the one place the API does
  it, and it is why `:current(n)` reads as *set the current* to nobody.
- **`:max()` is a bound the caller has to turn back into a range**, and every caller writes the same loop.
- **A speed is nowhere.** It is a number here, a name over there, and an availability rule in a third verb.

Make `hafen.speed()` the collection it already is: the speeds you can pick, the one you are on, and a verb
that picks one.

```lua
for _, sp in ipairs(hafen.speed():list()) do          -- the ones selectable right now
  hafen.log():write(sp:index() .. " " .. sp:name())
end
local cur = hafen.speed():current()
hafen.speed():set(hafen.speed():get("Run"))           -- protected, key `speed.set`
```

## Acceptance criteria

1. `hafen.speed()` **is** the collection (§2.1's *a section of one thing IS that thing*), carrying
   `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(key)` and `:current()`, and nothing else — an
   unknown verb raises naming the section.
2. `:list()` is **exactly the speeds selectable right now**, in crawl→sprint order: empty before the HUD's
   selector streams in, and empty when nothing is selectable at all. `:count()` and `:find()` enumerate the
   same set; a string filter matches the display name as a substring.
3. `:get(key)` addresses **any of the four**, selectable or not, by index `0..3` or by display name (whole,
   case-insensitive); `nil` on a miss and before the selector exists. *The collection enumerates what you
   can pick; `:get` addresses a speed by its key* — one sentence on the page, and the only wrinkle here.
4. A **Speed** is a live interned object: `sp:index()`, `sp:name()`, `sp:available()`, `sp:exists()`,
   `sp:info()`. `hafen.speed():get(2) == hafen.speed():get(2)`, and `hafen.speed():current()` is `==` the
   member of `:list()` it names — so "am I on this one" is the identity test, not a second verb.
5. `hafen.speed():set(x)` is **protected under the new key `speed.set`**, takes what `:get` takes *or* a
   Speed object, sends exactly what a click sends, and returns the collection so writes chain. It raises for:
   an addon that did not declare `speed.set` (naming it), a speed that is not selectable (listing the ones
   that are), an index outside `0..3`, an unknown name, a `nil` or a value of any other type, and no selector.
6. **Hard cut.** `hafen.speed():current(n)`, `:max()` and `:name(n)` each raise naming their replacement, and
   `speed.current` leaves the permission catalogue — a manifest declaring it fails to load.
7. `hello` reads the new surface and `walker` writes it under `speed.set`; neither mentions a retired verb.

## Out of scope

- Any speed **event**. There is none today, the page says so, and nothing here changes that.
- `gob:speed()` — the metres-per-second a body is moving at. A different reading of the same English word,
  on a different page, and it is not touched.
- The `speed-up` / `speed-down` / `speed-set/N` keybindings, which are already reachable as bindings.

## Docs impact

Pages written: `docs/addons/api/speed.md` (rewritten whole), `docs/addons/api/types.md` (a `Speed` snapshot
row), `docs/addons/guides/permissions.md` (the key row), `docs/client/services.md` (the HUD speed selector —
`Speedget`, whose fields this feature reads and which no page maps today).

Derived impact set — `grep -rni "speed\|crawl\|sprint" docs/`, everything outside `api/speed.md`:

| Hit | Verdict |
|---|---|
| `api/README.md:45`, `README.md:43`, `examples.md:193` | link labels for the section — stay true, re-read |
| `guides/permissions.md:37` | **the key row** — `speed.current` → `speed.set` |
| `guides/permissions.md:57` | `speed.*` as a legal group — stays true |
| `api/event.md:283` | "no event for movement speed, read on demand" — stays true |
| `api/gob.md:49`, `api/types.md:30` | `gob:speed()`, a different surface — **not touched** |

## Context files

- `src/io/brodgar/addon/LuaSpeed.java` — 2 *(the whole surface: the member, the collection, `:current`/`:set`)*
- `src/io/brodgar/addon/ActApi.java` — 1
- `src/io/brodgar/addon/LuaBuff.java` — 1 *(the interned-member + `collection(owner)` model to copy)*
- `src/io/brodgar/addon/LuaCollection.java` — 1
- `src/io/brodgar/addon/Section.java` — 1
- `src/io/brodgar/addon/Args.java` — 1
- `src/io/brodgar/addon/Retired.java` — 1
- `src/io/brodgar/addon/Permission.java` — 1
- `src/io/brodgar/addon/AddonManager.java` — 1 *(`installSpeed` call site, `requirePermission`)*
- `src/haven/Speedget.java` — 1, 2
- `docs/addons/api/speed.md` — 1, 2
- `docs/addons/api/conventions.md` — 1, 2
- `docs/addons/api/types.md` — 2
- `docs/addons/guides/permissions.md` — 2
- `docs/addons/api/README.md`, `docs/addons/README.md`, `docs/addons/examples.md` — 2
- `docs/client/services.md` — 1, 2 *(the `Speedget` map is already written: 060.1 read that source, so it paid
  the toll. Check it against `src/`, do not write it twice)*
- `DOCUMENTATION.md` — 2
- `addons/hello/main.lua`, `addons/walker/main.lua`, `addons/walker/manifest.json` — 1, 2 *(moved to the new
  spellings by 060.1; 2 only runs them for its `[manual]` line)*
