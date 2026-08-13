# 063 — tasks

- [x] **063.1 — A press lands where it was painted.** `WidgetSubs` resolves an **input target** once at
      construction — `LuaWidget.ownedContent(owner, wdg)` → `Owned.widget()`, falling back to the widget
      itself — and `listen`/`deafen` use it, so on a window built by `hafen.ui():window()` the four input
      keys fire from the same leaf `Draw` paints from. `Window.handle` stops discarding
      `super.handle(ev)`'s answer, so `ev:preventDefault()` cancels on a window with chrome. Neither
      addon changes: both come right.
      *Its suite* opens a window with a green square at content `(40,40)–(88,88)` and asserts the press
      it receives falls **inside** it — before this the same press reported 39 px higher, so the square
      is the whole claim. It counts every press reported while the caption is dragged and asserts
      **zero**. It subscribes `MouseDown` on the first captioned client window that appears, cancels a
      press in its top 30 px, records `w:position()` and asserts it did not move — the borrowed half,
      and the proof a cancel now stops the client. One command, a 45 s window, and a gesture nobody made
      scores `[fail] — got: no press in 45 s`. Refusal: `hafen.ui():window():on("Nope", fn)` must raise
      naming the key and listing what a surface answers.
      `[manual]`: the window moved when you dragged its title. The Profiler's six tabs switch when you
      click a **tab**, and clicking the caption switches none. WidgetStack's title drags it and opens
      no Inspector.
      <!-- extra context: addons/profiler/main.lua, addons/widgetstack/main.lua — read only, to verify -->

- [x] **063.2 — The client keeps two addons.** `git rm -r` the fourteen demos (`atlas`, `bags`,
      `cupboard`, `hello`, `hogtest`, `menubutton`, `netdemo`, `optionstest`, `planner`, `stockfilter`,
      `tagger`, `theme`, `timers`, `walker`) and their `bin/addons/` copies. `bin/addons/visionpro` goes
      with them: it has no folder under `addons/`, so it is a leftover of a run nothing sources any more.
      `examples.md` becomes the two tools. Every sentence naming a retired demo goes — the 37 lines the
      spec's grep names, across `docs/addons/api/**`, the guides, `docs/README.md`, plus the `planner`
      line in `specs/ROADMAP.md` and the three Java doc comments citing `hello` (`LuaGOut`, `AssetApi`).
      `DOCUMENTATION.md`'s rule that a page names a bundled demo, and `CLAUDE.md`'s demo paragraph, are
      rewritten for a tree with two.
      *Its suite* registers each of the fourteen retired command names and asserts every one
      **succeeds**: a name another addon already owns is refused and raises naming it, so fourteen clean
      registrations is the in-game proof that nothing else is installed. It then asserts that
      registering `widgetstack` **fails** and that the error names the command — the same rule from the
      other side, which is what makes the fourteen passes mean anything.
      `[manual]`: the AddOns panel lists `profiler`, `widgetstack` and this suite, and nothing else.
      /implement pastes the spec's grep, re-run, returning nothing.

- [ ] **063.3 — `widget:picture()` names the picture a widget shows.** `Resource.Image.tex()`,
      `rawtex()` and `scaled()` file their object under `Resource.this.name` through a new
      `AddonManager.onPicture(...)` weak identity map; `Img` gets a tagged getter for its private `img`;
      `LuaWidget` gains `:picture()` beside `resName` — `string | nil`, the **resting** face, over an
      `instanceof` chain of `Img`, `IButton.up`, `ICheckBox.up`. `widget:res()` is untouched.
      *Its suite* builds `hafen.ui():window():title("T0633")` and reaches its close button by chain,
      `window[title=T0633] @IButton`, asserting `:picture() == "gfx/hud/wnd/lg/cbtnu"` — one of the
      **client's own** widgets, which is the claim that matters. `hafen.ui():image():source(name)` is a
      real `haven.Img`, so it asserts the same name back, then re-points the source and asserts the new
      one — nothing is cached. A label and a window both answer `nil`. Refusal: `w:picture("x")` raises,
      and the message names `widget:res()` and `widget:image()` as the two neighbours it is not.

- [ ] **063.4 — The inspector says everything the widget will answer.** `addons/widgetstack/main.lua`
      grows one `describe(w)` driver — a fixed order of `pcall`ed reads, a line emitted only where the
      read answered — shared by the hover panel and every Inspector window: `:picture()`, `:tooltip()`,
      `:value()`, `:range()`, `:rows()`, `:rowHeight()`, `:cell()`, `:columns()`, `:source()`,
      `:image()`, `:items()`, `:focused()`, `:owned()`, `:style()`. The window is sized for them; the
      `==` hover guard and the walk budget are unchanged, since none of these walks the tree.
      *Its suite* builds the zoo the driver has to describe — a picture control, a slider with a range,
      a dropdown with rows, a text entry holding a value, a tooltipped button, a bare label — and
      asserts each read answers on the widget that has it and `nil` on the label, which is exactly the
      gate deciding whether a line is printed. It then takes the selector the panel would offer for one
      of them and asserts `hafen.ui():find(sel)` hands back that same widget. Refusal:
      `hafen.ui():find("window[title=]")` raises as a parse error — which is why an empty value is never
      offered.
      `[manual]`: hover any window's close button and read `picture: gfx/hud/wnd/lg/cbtnu` in the panel;
      hover a plain label and confirm no empty lines are printed for it.
      <!-- extra context: addons/widgetstack/main.lua -->
