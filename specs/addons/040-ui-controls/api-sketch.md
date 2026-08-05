# 040-ui-controls — API sketch (FOR AGREEMENT, not yet a contract)

<!-- Written at the maintainer's request during /plan review: the Lua spelling of all 18 controls,
     so the names can be agreed BEFORE plan.md and tasks.md are written. Line limit lifted with the
     rest of 040. On approval this folds into spec.md's design spine; the open questions at the
     bottom become decisions in plan.md. Nothing here is built. -->

## 0. What every control gets for free

A control **is a Widget**, so the whole existing surface answers on one with nothing written for it:

```lua
c:position(x, y)   c:size(w, h)    c:parent(w)     c:visible(b)   c:font(h)
c:destroy()        c:type()        c:role()        c:style()      c:rootPos()
c:onClick(fn)      c:onMouseMove(fn)   c:onWheel(fn)   c:onDrop(fn)
```

Everything below is **only** what a control adds on top.

## 1. The vocabulary — 6 new names for 18 controls

| Name | Meaning | Answers on |
|---|---|---|
| `:text()` / `:text(s)` | what the control **displays** | button, label, checkbox, radio, entry |
| `:value()` / `:value(v)` | what the control **holds** | checkbox, radio, slider, entry, progress, scrollbar, list, dropdown |
| `:rows(t)` | the **row source** of a model-backed control | list, dropdown, menu, grid, table |
| `:onChange(fn)` | the value changed | everything with a `:value()` |
| `:onPress(fn)` | it fired, and holds nothing | button |
| `:onSelect(fn)` | a row was chosen from a menu | menu |

Plus two that belong to exactly one control each: `:onSubmit(fn)` (the entry's Enter) and `:onCell(fn)`
(the grid's cell painter).

> **`:text()` already exists** as a read on the Widget type — *"best-effort text for text-bearing widgets
> (Label, Button, Window, TextEntry)"*. This feature does not add a name: it makes the **write** answer on a
> control your addon built, which is R2 (`:position` already reads everywhere and writes with a restore).

## 2. Input

```lua
-- Button ------------------------------------------------------------------
local b = hafen.ui():button()
  :text("Harvest")
  :size(120, 20)
  :position(8, 8)
  :parent(win)
  :onPress(function() hafen.log():write("pressed") end)

b:text()            --> "Harvest"
b:text("Harvest!")  -- chains

-- IButton: the SAME builder; the setter chooses the class -----------------
hafen.ui():button()
  :image(up, down, hover)        -- asset handles, or engine resource names
  :onPress(fn)                   -- 2 or 3 images; `hover` is optional

-- TextEntry ---------------------------------------------------------------
local e = hafen.ui():entry()
  :size(160, 20)
  :value("gonzalo")
  :onChange(function(s) hafen.log():write("now: " .. s) end)
  :onSubmit(function(s) doSearch(s) end)     -- Enter

e:value()           --> "gonzalo"
e:text()            --> "gonzalo"   -- the same string; see open question A
```

## 3. Display

```lua
-- Label --------------------------------------------------------------------
local l = hafen.ui():label():text("Stamina"):position(4, 4)
l:text(("%d%%"):format(n))              -- resizes itself; the container repacks

hafen.ui():label():text("Skills"):font(hafen.font("serif"):size(14))
hafen.ui():label():text("Wear"):image(icon)     -- ILabel: same builder, image setter

-- Img ----------------------------------------------------------------------
hafen.ui():image():source(hafen.asset("logo.png")):position(0, 0)

-- Progress -----------------------------------------------------------------
local p = hafen.ui():progress():size(120, 20):value(0.35)
p:value()           --> 0.35     (0..1; a write outside the range is refused)

-- HRuler -------------------------------------------------------------------
hafen.ui():separator():size(180, 1):position(0, 40)
```

## 4. Selection

```lua
-- CheckBox -----------------------------------------------------------------
local c = hafen.ui():check()
  :text("Show grid")
  :value(true)
  :onChange(function(on) hafen.store():get("cfg").grid = on end)

c:value()           --> true

-- ICheckBox: same builder, four faces --------------------------------------
hafen.ui():check():image(up, down, hoverUp, hoverDown)

-- Radio: ONE control, not a group object + N buttons -----------------------
local r = hafen.ui():radio()
  :rows{"Quality", "Amount", "Name"}
  :value("Amount")
  :onChange(function(pick) sortBy(pick) end)

r:value()           --> "Amount"

-- HSlider ------------------------------------------------------------------
local s = hafen.ui():slider()
  :size(140, 20)
  :range(0, 100)
  :value(50)
  :onChange(function(v, final) preview(v); if final then save(v) end end)
```

`:onChange(v, final)` on the slider is **one callback, not two**: `final` is `false` while dragging and
`true` on release. The engine has two hooks (`changed`/`fchanged`); the API has one name and a flag, which is
the same shape `mods` already has on the mouse callbacks.

## 5. Lists (the model-backed five)

All five take **`:rows(t)`**, a plain Lua array. Two row shapes ship; both are the client's own ready-made
rows, so v1 needs no custom-row surface:

```lua
list:rows{ "Alpha", "Beta", "Gamma" }                                   -- text rows
list:rows{ { icon = "gfx/invobjs/bucket", text = "Bucket" }, … }        -- icon + text rows
```

```lua
-- SListBox -----------------------------------------------------------------
local list = hafen.ui():list()
  :size(200, 160)
  :rowHeight(20)
  :rows(names)
  :onChange(function(row) select(row) end)

list:value()        --> the selected row, or nil
list:rows(other)    -- replacing the table re-renders; {} is an empty list, not an error

-- SDropBox -----------------------------------------------------------------
hafen.ui():dropdown()
  :size(140, 20)
  :rowHeight(18)
  :rows{ "All", "Seeds", "Tools" }
  :value("All")
  :onChange(function(pick) filter(pick) end)

-- SListMenu: fires, holds nothing ------------------------------------------
hafen.ui():menu()
  :size(120, 90)
  :rowHeight(18)
  :rows{ "Rename", "Delete", "Move" }
  :onSelect(function(row) act(row) end)

-- GridList: it DRAWS cells, it does not build row widgets ------------------
hafen.ui():grid()
  :size(200, 200)
  :cell(48, 48)
  :rows(items)
  :onCell(function(g, item, w, h) g:resource(item.res, 0, 0, w, h) end)

-- TableBox -----------------------------------------------------------------
hafen.ui():table()
  :size(300, 200)
  :rowHeight(18)
  :columns{
    { title = "Name",    width = 160, of = function(r) return r.name end },
    { title = "Quality", width = 60,  of = function(r) return tostring(r.q) end },
  }
  :rows(stock)
```

`:onCell(g, item, w, h)` is the `g` wrapper the API already ships — the same object `:onDraw` receives, with
the item and the cell box.

## 6. Scroll

```lua
local sp = hafen.ui():scroll():size(200, 160):position(4, 4):parent(win)

hafen.ui():label():text("row 1"):parent(sp):position(0, 0)
hafen.ui():label():text("row 2"):parent(sp):position(0, 16)
```

`:parent(sp)` puts the child **inside the scrolling area**, not beside the bar — the port's inner container
is an implementation detail the API never hands out. The bar appears when the content is taller than the
port. A bare `hafen.ui():scrollbar()` also exists for driving something yourself: `:range(min, max)`,
`:value(n)`, `:onChange(fn)`.

## 7. The whole roster, one line each

| Builder | Class(es) | Its own verbs |
|---|---|---|
| `hafen.ui():button()` | `Button` / `IButton` | `:text` \| `:image` · `:onPress` |
| `hafen.ui():entry()` | `TextEntry` | `:text` = `:value` · `:onChange` · `:onSubmit` |
| `hafen.ui():label()` | `Label` / `ILabel` | `:text` · `:image` |
| `hafen.ui():image()` | `Img` | `:source` |
| `hafen.ui():progress()` | `Progress` | `:value` |
| `hafen.ui():separator()` | `HRuler` | — |
| `hafen.ui():check()` | `CheckBox` / `ICheckBox` | `:text` \| `:image` · `:value` · `:onChange` |
| `hafen.ui():radio()` | `RadioGroup`+`RadioButton` | `:rows` · `:value` · `:onChange` |
| `hafen.ui():slider()` | `HSlider` | `:range` · `:value` · `:onChange(v, final)` |
| `hafen.ui():scroll()` | `Scrollport`+`Scrollbar` | — (children via `:parent`) |
| `hafen.ui():scrollbar()` | `Scrollbar` | `:range` · `:value` · `:onChange` |
| `hafen.ui():list()` | `SListBox` | `:rows` · `:rowHeight` · `:value` · `:onChange` |
| `hafen.ui():dropdown()` | `SDropBox` | `:rows` · `:rowHeight` · `:value` · `:onChange` |
| `hafen.ui():menu()` | `SListMenu` | `:rows` · `:rowHeight` · `:onSelect` |
| `hafen.ui():grid()` | `GridList` | `:rows` · `:cell` · `:onCell` |
| `hafen.ui():table()` | `TableBox` | `:rows` · `:rowHeight` · `:columns` |

## 8. A whole panel, end to end

Everything above in one addon: a stock filter with a heading, a text field, a radio, a checkbox, a slider
that drives a label, a dropdown, a results list and two buttons.

**Two placement rules carry the whole example.** `:parent(w)` is a **building** verb (D-121) — legal until
the control arms on the tick after the statement that built it, and refused afterwards, where the way to
move something is `:position(x, y)`. And a child is **clipped to its parent's box**, so the window's
`:size` is the content box everything has to fit inside.

```lua
local win = hafen.ui():window()
  :title("Stock filter")
  :size(260, 330)                       -- content box: every child lives inside it
  :position(80, 120)

local refresh                           -- forward: the controls below close over it

hafen.ui():label()
  :parent(win):position(10, 10)
  :text("Search")                       -- a label sizes itself from its text

local search = hafen.ui():entry()
  :parent(win):position(10, 28):size(240, 20)
  :value("")
  :onChange(function() refresh() end)

hafen.ui():separator()
  :parent(win):position(10, 58):size(240, 1)

local sort = hafen.ui():radio()
  :parent(win):position(10, 70)         -- the stack starts HERE; rows go downward
  :rows{ "Quality", "Amount", "Name" }
  :value("Amount")
  :onChange(function() refresh() end)

local hideEmpty = hafen.ui():check()
  :parent(win):position(10, 132)
  :text("Hide empty")
  :value(true)
  :onChange(function() refresh() end)

local qLabel = hafen.ui():label()
  :parent(win):position(10, 158)
  :text("Min quality: 10")

local minq = hafen.ui():slider()
  :parent(win):position(10, 176):size(240, 20)
  :range(0, 100)
  :value(10)
  :onChange(function(v, final)
      qLabel:text(("Min quality: %d"):format(v))     -- live while dragging
      if final then refresh() end                    -- once, on release
    end)

local kind = hafen.ui():dropdown()
  :parent(win):position(10, 204):size(120, 20)
  :rows{ "All", "Seeds", "Tools" }
  :value("All")
  :onChange(function() refresh() end)

local results = hafen.ui():list()
  :parent(win):position(10, 232):size(240, 60)
  :rows{}
  :onChange(function(row) hafen.log():write("picked " .. row) end)

hafen.ui():button()
  :parent(win):position(10, 300):size(80, 20)
  :text("Apply")
  :onPress(function() refresh() end)

hafen.ui():button()
  :parent(win):position(96, 300):size(80, 20)
  :text("Reset")
  :onPress(function()
      search:value("")
      sort:value("Amount")
      hideEmpty:value(true)
      minq:value(10)
      kind:value("All")
      refresh()
    end)

refresh = function()
  results:rows(query{ text    = search:value(),
                      sortBy  = sort:value(),
                      hideEmpty = hideEmpty:value(),
                      minq    = minq:value(),
                      kind    = kind:value() })
end

refresh()
```

**How the radio actually sits in there.** `hafen.ui():radio()` is **one** control, so it takes one
`:position(10, 70)` — the top-left of the whole group — and stacks its three buttons downward from there,
one row height apart. `sort:size()` reads the box of the whole stack, `sort:value()` the chosen label, and
`RadioGroup`/`RadioButton` never appear: the engine's group object already keys its buttons by label
(`RadioGroup.check(String)`), which is exactly what `:value("Amount")` writes through.

Reading state back is the same verb everywhere:

```lua
search:value()      --> ""
sort:value()        --> "Amount"
hideEmpty:value()   --> true
minq:value()        --> 10
results:value()     --> the selected row, or nil
```

## 9. Open questions — these are what needs deciding

**DECIDED at review (2026-08-05):**
- **A → `:value()`.** A text entry's content is `:value()`, the one door. `entry:text()` is **retired**:
  it throws naming `:value()`, rather than reading as a second name for one property.
- **C → `:rows(t)`.** `:items()` stays what it already means — the game Items inside a container.
- **F → the plain words.** `:separator()`, `:dropdown()`, `:menu()`, `:list()` over `:ruler()`,
  `:dropBox()`, `:listMenu()`, `:listBox()`. The engine's class names stay in `:type()`, where a reader
  who wants them can find them.

**Still open:**

**A. `entry:text()` and `entry:value()` are the same string.** Two readers for one property is the dual style
the area removes. Three ways out: *(1)* a text entry's content is **`:value()` only**, and `:text()` keeps
answering there as the pre-existing best-effort read documented as an alias for reads — smallest change,
but the alias is exactly the thing R2 forbids; *(2)* the content is **`:text()` only** and the entry has no
`:value()` — then `:onChange` still fires, but the one-verb-for-a-value claim loses its only string case;
*(3)* keep both and document `:text()` on an entry as **retired**, throwing and naming `:value()`. Recommend
**(3)**: it is the only one that leaves one door, and the area already has the machinery for a refusal that
names its replacement.

**B. `:onPress` vs reusing `:onClick`.** A button would then carry both — `:onClick(x, y, button, mods)`, the
raw mouse event every widget has, and `:onPress()`, the activation (which also fires from the keyboard).
Two callbacks for one gesture looks like a dual style but is not: one is a mouse position, the other an
intent. Recommend keeping both and saying so on the page. The alternative is `:onPress` only, with
`:onClick` refused on a button.

**C. `:rows(t)` because `:items()` is taken.** `widget:items()` already means *the game Items inside a
container* — a completely different thing. `:rows` reads oddly for the grid (which lays items out in rows,
so it is defensible) and for the radio group. Alternatives: `:source(t)`, `:content(t)`, `:entries(t)`.

**D. `:columns{…}` is a table of tables.** R4 says no config table survives on anything constructed. A
column list is data rather than construction config, but it is the one place in this sketch that looks like
the shape 039 deleted. The alternative is a column collection (`t:column():add():title("Name"):width(160)`),
which is heavier but obeys the letter of the rule.

**E. `:image` means two things.** `hafen.ui():image()` builds a picture widget; `button:image(up, down,
hover)` sets a button's faces. Different receivers, so no syntactic clash, but the same word. The picture
widget's own setter is `:source(h)` to avoid `image():image(h)`. Alternative: name the builder
`:picture()` and free `:image(h)` for its content.

**F. `separator` / `dropdown` / `menu` / `list` are not the engine's words.** D-061 says the vocabulary comes
from the engine, and the engine says `HRuler`, `SDropBox`, `SListMenu`, `SListBox`. But `menu` is already a
**selector role** (`MenuGrid`, `FlowerMenu`) and `list` is the verb every collection in the API carries
(`:list(f)`), so both are loaded words. Options per name: keep the plain word, or take the engine's
(`:ruler()`, `:dropBox()`, `:listMenu()`, `:listBox()`).

**G. `:rowHeight` is mandatory in the engine.** `SListBox(sz, itemh)` and friends need it at construction. A
bare builder means a default (the client's own text height) that `:rowHeight(n)` overrides — or the row
source having to arrive before the widget can exist. Recommend the default.
