# 094 — The widget and the thing

Discharges: A-104, A-105, A-106, A-107, A-108, A-109, A-110, A-111, A-112, A-113, A-114.

All eleven are rows in `audit/INVENTORY.md`'s own 094 block. **Four of them come from
`16-consumer-evidence.md`** — not from reading the API, but from reading what `eventstack` and `widgetstack`
actually do to work around it. That is the audit's strongest evidence: shipped code taking the long way.

## What and why

The API has **two address spaces** — the widget tree and the domain objects — and they met in one place and
in one direction. `widget:items()` crossed from a container to the Items inside it, and nothing came back:
no `buff:widget()`, no `meter:widget()`, no `kin:widget()`.

So everything an addon wants to **place relative to the client's own UI** was unreachable. "Draw a badge
over the buff that is about to expire" needs the buff's widget. `s:ui():match(sel)` reaches the *window* by
role, and the thing inside it is a domain object the selector language cannot address. That asymmetry is
also why `ui/items.md` is one of the longest pages in the tree: it is the one place the two spaces meet, so
everything about containers has to be said there.

And a widget could not say four things about itself that the bridge already knew:

- **which session's tree it stands in** — while the model's headline is that the session is the address, and
  a widget is looked up *through* one. `eventstack` mapped every session's root and walked up to 64 parents
  per message, with a bounded memo in front of it because "a per-message walk is a per-message loop".
- **what events it answers** — `LuaWidget.widgetKeys` computes the exact list on every `:on` call *in order
  to build the refusal*. The list existed and only the error message could see it, so the only way to ask
  was to subscribe and catch the failure, which leaves a live subscription behind as the side effect of a
  question.
- **whether your addon built it** — provenance is the rule that decides what you may write, and it was a
  field of the snapshot alone.
- **whether it matches a selector** — so `widgetstack` generated candidate selectors and ran each over the
  whole live tree, reporting "what the answer cost, in tree walks".

## What shipped

**The crossing back** (A-104): `:widget()` on **Buff**, **Meter**, **StudySlot** and **Kin**. Three of them
*are* their widget already — `LuaBuff` wraps a `Buff` and nothing else — so the crossing is one closure. A
Kin is a row of an `SListBox`, reached through its public `getcur(item)`; it answers `nil` when the Kin
window is closed or that row is scrolled out, which is the row's own truth rather than a gap.

**The four facts** (A-105…A-108): `w:session()`, `w:events()`, `w:owned()`, `w:is(sel)`. Named `is` and not
`matches` by **D3**, because 088 took `w:match(sel)` for the search-inside verb; and it is not the `is`
prefix **D1** banned, which is about boolean *properties* — read/write pairs — where this takes an argument
and can never be one.

**The wound tree walks down** (A-109): `wound:children()` and `s:wound():roots()`, both collections,
mirroring `pag:children()` and `s:menugrid():roots()`. One `Source` shared by both directions, over the very
list the collection already builds. A complication whose parent has healed out from under it is a root,
which is how the window draws it too.

**Five smaller ones**: `gob:party()`, the inverse `member:gob()` never had while `gob:kin()`/`kin:gob()` went
both ways (A-110). `hafen.store():list()` / `s:store():list()`, each the names declared in **that** scope, as
a string array — the set a misspelt `:get` already enumerated in its refusal (A-111). `h:type()` on every
font handle, `"builtin"` / `"font"` / `"variant"`, where three shapes wore one name and a helper calling
`h:dispose()` worked for one of them (A-112). `hafen.ui():role()`, a collection of the roles the client
publishes, each with `:name()` and `:selector()` — the same string when it can match a widget and `nil` for
a **render site**, which is valid in a stylesheet and matches no widget by construction (A-113). And
`s:menugrid():get(id)` accepting the short id you gave `:add(id)`, since an addon cannot read its own
manifest id from Lua and the collection knows the owner (A-114).

## One correction to the audit's own count

**A-104 names five types and there can only be four.** `Slot` is out. The other four each hold or reach a
widget of their own; the action bar is **one** widget that paints all 144 slots itself —
`FKeyBelt.draw` → `belt[slot].draw(g.reclip(...))` — so `slot:widget()` would answer the same bar for every
index, which is worse than not existing. `actionbar.md` carries the reason and the way round:
`s:ui():match("hud.belt")` plus `w:rootPos()`.

That is the sixth row of the sweep whose evidence did not survive a recount, after A-086, A-094, A-096,
A-097 and A-101.

## Verified

`:t094` — **5 pass, 0 fail, 1 manual**, confirmed. Clean `ant hafen-client` from an empty `build/classes`.
Fourteen pages updated. The open count went **15 → 4**.
