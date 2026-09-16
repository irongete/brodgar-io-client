# 154 — Tasks

- [x] **154.1 — The tree, restored.** Every page of `docs/addons/**` from `e96c446ae^`, the pages born
      after the sweep kept, and the later commits' hunks re-applied by hand: `session.md` (remembered
      accounts: `:saved`, `:add`, `:forget`, their permissions and error table) and
      `guides/permissions.md` (`session.add`, `session.forget`, the `session.*` group); `api/README.md`,
      `README.md` (`resource`, `materials`, `steam` rows); `references.md` (Resource and Layer);
      `asset/handles.md` (an image asset as an `image`/`tex` picture, `.ogg` and `.res` data assets);
      `sound.md` (`:play` reads the current `audio2` layer); `gob.md`, `look.md`, `types/world.md`
      (materials); `event/bus/character.md` (the two Steam keys, catalogued); `ui/controls/display.md`
      (a resource-named picture); `ui/custom.md`, `ui/widget.md`, `ui/native.md`, `guides/custom-ui.md`
      (the content-box read, `:chrome().frame`/`.content`, `:resizable(true)`, `Close` cancelable,
      `Resized` as `ev:w()/ev:h()`, `remember` keeping an owned window's box). `DOCUMENTATION.md` §6
      applied to every code block under `api/ui/**` and the two UI guides. Criteria 1, 2.
      *Its verification*: `tools/docverbs.py` and `tools/refusalverbs.py` exit 0; every relative link and
      anchor in `docs/addons/**` resolves; every ```lua block that parsed before parses after.
- [x] **154.2 — `api/ui/**`, `guides/custom-ui.md`, `guides/theming.md` to the standard.** Criteria 3, 4.
- [x] **154.3 — The world and the character to the standard**: `world`, `gob`, `look`, `overlay`,
      `placing`, `position`, `player`, `char`, `study`, `buff`, `meter`, `wound`, `quest`, `kin`, `party`,
      `craft`, `fight`, `speed`, `actionbar`, `menugrid`, `flowermenu`, `time`. Criteria 3, 4.
- [x] **154.4 — Events, timers, storage and the client to the standard**: `event/**`, `timer`, `console`,
      `log`, `locale`, `store/**`, `client/**`. Criteria 3, 4.
- [x] **154.5 — Content, network and sessions to the standard**: `asset/**`, `font`, `sound`, `virtual/**`,
      `voice/**`, `map/**`, `http`, `websocket`, `json`, `session`. Criteria 3, 4.
- [ ] **154.6 — The shared pages and the guides to the standard**: `types/**`, `conventions`, `shapes`,
      `references`, `threading`, the top-level pages (`README`, `getting-started`, `manifest`, `panel`,
      `runtime`, `examples`) and `guides/**` (less the two of 154.2). Criteria 3, 4.
