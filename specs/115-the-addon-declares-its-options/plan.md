# 115 — Plan

## Approach

**One window, three top-level panels.** `OptWnd` stays the single window: `GameUI.opts`, `kb_opt`,
`togglewnd` and the login screen's instance all keep pointing at one thing and `chpanel` still swaps them.
`main` becomes the game menu; a new `SettingsPanel` is the tabbed view; `AddonPanel` is the third, reached
from the menu. `show()` still `chpanel(main)`s, so every `Ctrl+O` opens on the menu, and Escape closes the
window because no panel binds it.

**The caption follows the panel.** `chpanel` calls `Window.chcap` — `null` on the menu, `"Options"` and
`"AddOns"` on the other two — behind an equality test: `chcap` is the one caption seam
(`AddonManager.onCaptionChanged`), and re-deriving every `[title=]` match on each swap costs with no gesture
behind it.

**The tabbed view.** `Tabs` gives the strip: two `Tabs.Tab`s, each an `SListBox` over a panel holder.
Rows are `SListWidget.TextItem.of(sz, …)` in an `ItemWidget`, the idiom
`OptWnd.CameraPanel.CamSelector` uses in this file. The Game tab's `items()` is the fixed seven;
the AddOns tab's is `AddonManager.describeOptions()`, re-read on `show()`. Selecting a row builds that
panel into the holder — `PButton.click`'s lazy build, moved onto the list. Keybindings and the AddOns
pages rebuild on every selection, as `PButton`'s `fresh` flag does, so anything declared since is listed.

**The declaration** is `hafen.client():options():addon()` — a per-addon singleton on `Addon`, minted by
`OptionsHandle`, closed with `OptionsHandle.open`/`close` like its five siblings. Six typed builders, one
per control the client draws:

```lua
local opts = hafen.client():options():addon()
local show = opts:boolean("show-timer"):label("Show the timer"):default(true):add()
show:value(false)
show:on("Changed", function(v) end)
opts:button("reset"):label("Reset to defaults"):press(reset):add()
```

**Four carry a value** — `boolean`, `number` (`:range(lo, hi)`), `choice` (`:choices{…}`) and `text` — and
hand back a live interned Option with `value()`/`value(v)`, `on("Changed", fn)`, `default()` and `info()`.
**Two do not**: `button` takes `:press(fn)` and runs it when the row is pressed; `label` takes `:text(s)`
and states a line the addon rewrites. All six take `:label` and `:tooltip` and dispatch on `:add()` —
`Widget.add` is the client's own word for a control joining a panel — so every setter is legal until it
and none after. `opts:option()` is the collection of this addon's own.

**Unprotected, and client-scoped.** An addon's own option writes nothing outside itself, so it needs no
permission key, which also lets one suite prove the round trip with no consent dialog in the way. Values
land in `Utils.setpref*` under `addon/<addonid>/opt/<name>`, mirroring the keybinding id: they survive
`:reload`, a disable and a restart, the addon cannot wipe them, and they are one per client, not per
character.

**The panel and the value are one fact.** Each control reads its value every frame and writes through the
setter Lua does, so a Lua write moves an open control with no event plumbing, the shape `ClientPanel`'s
profiling checkbox has.

## Files to create and modify

| File | What |
|---|---|
| `src/haven/OptWnd.java` | `main` → the six-entry menu; `SettingsPanel` (tabs, lists, holder); every `Back` removed; caption in `chpanel` |
| `src/haven/Window.java` | guard the caption blit in `DefaultDeco.drawframe` (`// addon:`) |
| `src/io/brodgar/addon/AddonOptions.java`, `LuaOption.java` | **new** — the handle and its six builders; the Option object |
| `src/io/brodgar/addon/OptionsHandle.java`, `Addon.java` | wire `addon()`; the per-addon field and its teardown |
| `src/io/brodgar/addon/AddonManager.java` | `describeOptions()` + `OptionGroup`/`OptionEntry`, beside `describeKeyBinds` |
| `src/io/brodgar/addon/ui/AddonOptionsPanel.java` | **new** — renders one addon's declared options |
| `docs/addons/api/client/addon.md` | **new** — the reference; linked from `client/README.md` and `api/README.md` |
| `docs/addons/guides/hotkeys-and-commands.md` | the third way a user drives an addon by hand |
| `docs/client/ui-panels.md` | **new** — `Tabs` (a gap `widgets.md` names) and `OptWnd`'s panel model, **moved off** `ui-controls.md`; both re-pointed |
| the impact set's eleven pages | the `Options ▸` navigation; `spec.md` lists the sites |

## Risks and gotchas

- **`chcap(null)` crashes on the next frame.** `DefaultDeco.checkcap` nulls its own `cap` when `wnd.cap` is
  null and `drawframe` blits `cap.tex()` unguarded — the defect `ROADMAP.md` files at 065. A captionless
  menu is unreachable without it. One `if`.
- **Every control notifies on a programmatic write, and each has its own escape.** `CheckBox`: `set(b)`
  calls `changed(b)` on a real flip — write `ACheckBox.a`, or install the `state` supplier. `TextEntry`:
  `settext` notifies, `rsettext` does not. `HSlider`: `val` is a public field, `changed()`/`fchanged()` are
  hooks. `SDropBox.change(I)` has no lower seam — it sets `sel` *and* rebuilds the closed-box
  widget, so call it and skip only the notify wrapper. `Button.click` runs `action` **last** in `mouseup`,
  so a button row whose handler reloads may destroy the panel it is sitting in.
- **`SListWidget.change(I)` means a real click**, reached by `ItemWidget.mousedown` and the click-away
  deselect alike. A programmatic selection writes `sel` directly.
- **`SListBox` builds row widgets in `tick`, not `items()`** — a row is not a widget until the next frame,
  so nothing reads one back in the call that added it.
- **`VideoPanel` rebuilds its whole column** whenever `ui.gprefs` moves (`resetcf`). The holder holds the
  `VideoPanel`, never anything inside it.
- **`Tabs` does not fold `resize` over its tab list** as `Window` does — `widgets.md` names it a gap.
- **`AddonPanel` extends `OptWnd.Panel`, a non-static inner class**, through `opt.super()`. That shape
  stays; only its entry point moves.
- **The login screen has no session and every addon is loaded**, so both tabs stand and only the two
  `gopts` entries are absent. `video()`/`audio()` still read `nil` before the UI exists.

## Discarded alternatives

- **Two windows.** WoW's shape, but it doubles `GameUI.opts`, `kb_opt`, `LoginScreen`'s instance and the
  `wndc-*` position store to buy one thing the client's own habit has nowhere else: the menu staying
  visible behind Options.
- **Keeping a *Back* on each panel.** The list is the navigation; a Back beside it gives two ways out and
  neither is obvious. Escape closes, as every other window here does.
- **A free-form options page — the addon hands the client a widget.** Rejected with the maintainer: it puts
  the window's look in each addon's hands and leaves each inventing its own persistence, the very thing the
  client is being asked to do. `018-client-options` rejected coupling addons to `OptWnd`'s tree for the
  same reason.
- **`:declare()` as the dispatch.** It names the API, not where the row lands, which is what `http`'s
  `:send()` and a control's `:parent(w)` both do.
- **A progress bar row.** `Progress.val(Supplier<Float>)` is re-read every frame, so it is trivial, but it
  displays rather than configures and live state belongs in the addon's own window. Raised by the
  maintainer, left out on that boundary.
- **Storing values in the addon's saved variables.** They are the addon's own file, wiped when it wipes
  them and gone with the folder; a setting the user set belongs to the client, like a keybinding.
- **One `add(name, type, …)` verb instead of six typed builders.** A single verb cannot carry
  `:range` for a number and `:choices` for a choice without both being ignorable on the wrong type.
- **Letting an addon reach another addon's options** the way `keybindings():binding()` reaches every
  binding. Remapping any key is something a user asks an addon to do; writing another addon's settings
  behind its back is not.
- **A `client.settings` key on the declaration.** That key guards the *client's* settings; an addon's own
  option is its own, so gating it would teach the wrong thing about what the key means.
