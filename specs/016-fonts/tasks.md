# 016-fonts — Tasks

- [x] 016.1 — Foundation: `haven.Fonts` provider (owner stacks, gen, cascade, fast path) +
      `hafen.font.load/setFont/reset/scopes` + the `"default"` scope (Text.render + Label).
- [x] 016.2 — Own-widget fonts: `font=` opts, `g:text` opts (fast/rich split), `$font`
      custom families.
- [x] 016.3 — `"window.title"` (furnace rebuild + caption cache gen).
- [x] 016.4 — `"button"` (recorded caption recipe + redraw; subclasses covered free).
- [x] 016.5 — `"textentry"` + `"label"` (ReadLine surfaces + the real body-text surface:
      CharWnd.attrf / SListWidget rows / attribute rows).
- [x] 016.6 — `"heading"` (NEW scope — D-043 amendment; 16 sites, furnace pattern).
- [x] 016.7 — `"menu"` + `"tooltip"` + `"chat"` (Fonts.style scalars, the dynamic
      composition scope, foundry-level resolution, info-list rebuilds, the adopted
      `ui/tt/slots-alt`; ChatParser rebuild).
- [x] 016.8 — World scopes: `"world.speech"` (Speaking) + `"world.nick"` (adopted
      `ui/obj/buddy`) — completes the scope enum.
- [x] 016.9 — Per-instance override: `node:setFont/:resetFont` (draw-pass frames,
      `gen ^ stamp`, weak registry) — completes the F-series.
