# 016-fonts — Spec

## What & why
The complete **per-addon typography system** (`hafen.font.*`, D-043): load private font
handles (built-ins or bundled TTF/OTF), apply them to the addon's own drawing, and install
**owned overrides** on named client surfaces — the full scope enum from `"default"` through
the UI chrome (`window.title`, `heading`, `button`, `textentry`, `label`, `menu`,
`tooltip`, `chat`) to the world (`world.speech`, `world.nick`) — plus a **per-instance**
override on any WidgetNode. Resolution chain: instance → scope → `"default"` cascade →
stock; everything owner-tagged, last-wins, reverted on `:reload`/disable.
Design: [design/21-fonts.md](../design/21-fonts.md).

## Acceptance criteria (verified in-game — every scope togglable independently in one login)
- [x] `hafen.font.load` (built-in / sandboxed TTF; AWT-registered so `$font` works),
      handle `:family/:size/:derive`; `setFont/reset/scopes`; last-wins proven live
      (a stacked mono override drops back to the first).
- [x] Own drawing (isolated, nothing to revert): `font=` on windows/widgets, per-call
      `g:text(str,x,y,{font=,color=})`, two fonts on one line via `$font` — positional
      coords kept (additive opts, zero breakage).
- [x] Chrome scopes, each a distinct render-site pattern: `window.title` (furnace rebuild +
      caption cache), `button` (recorded caption recipe + SIWidget redraw; `Charlist.df`
      stays stock by design), `textentry` (+ the console command line), `label` (the
      explicit-foundry labels AND the real body-text surface — `CharWnd.attrf` and the
      `SListWidget` list rows), `heading` (a NEW scope, D-043 amendment — 16 sites, two
      stock sizes under one scope), `menu` (petals re-centred on resize + grid key letters),
      `tooltip` (the `ItemInfo` engine + published-code rows via the **dynamic composition
      scope** + foundry-level resolution + the adopted `ui/tt/slots-alt` local copy),
      `chat` (a rebuilt `ChatParser` foundry — URLs stay clickable; scrollback re-flows).
- [x] World scopes (complete the enum): `world.speech` (Speaking; frame auto-sizes — large
      fonts safe) and `world.nick` (adopted `ui/obj/buddy` v4 published code; group colour
      kept).
- [x] Per-instance `node:setFont(h)/:resetFont()`: one window restyles (caption + labels +
      buttons), siblings stock; beats scope and default; a label created AFTER the override
      still restyles (the `gen ^ stamp` frame mechanism); weak-keyed registry — closed
      windows evaporate; composes with a live `"default"` override.
- [x] The `"default"` cascade reaches every scope; each site keeps its own stock size
      unless the handle carries one; teardown restores the exact stock objects everywhere.

## Out of scope
- Layout: geometry never follows a font (row heights/field textures are stock-sized —
  prefer size-less overrides); `emissiveTexture`-style extras N/A; upstream refresh of the
  two version-pinned resource adoptions happens via `get-code` when the server bumps them.

## Context files
- `design/21-fonts.md` — the F-series design; D-043 (+ its amendment) in `../decisions/fonts.md`
- `src/haven/Fonts.java` — the provider (registry, gen, style, dynamic scope, frames)
- `src/io/brodgar/addon/FontApi.java`, `FontHandle.java` — the Lua surface
- Routed sites: `src/haven/Text.java`, `Label.java`, `Window.java`, `Button.java`,
  `TextEntry.java`, `ConsoleHost.java`, `CharWnd.java`, `SListWidget.java`,
  `FlowerMenu.java`, `MenuGrid.java`, `ChatUI.java`, `ItemInfo.java`, `Speaking.java`,
  `Widget.java`/`UI.java` (the F5 frames)
- `src/haven/res/ui/tt/slots_alt/`, `src/haven/res/ui/obj/buddy/` — the adopted resource code
- `docs/addons/api/fonts.md` — shipped surface
- `../015-widget-introspection/` — the WidgetNode F5 rides on
