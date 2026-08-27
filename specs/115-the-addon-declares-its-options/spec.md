# 115 — The addon declares its options

## What and why

The Options window is a column of buttons; each opens one panel over the column, and each panel ends in a
*Back*. An addon has no standing in it. It reads and writes the client's own settings through
`hafen.client():options()`, and it declares a hotkey that Keybindings lists under its own name — but an
option of its own has nowhere to live, so an addon with one ships a console command or an undocumented saved
variable and the user reads a README to find it.

This gives the window the shape World of Warcraft's has, and gives an addon's own option the standing its
hotkey already has: the addon names it, the client draws it, stores it and answers reads.

**The menu** (`kb_opt`) becomes a game menu carrying no caption — `Options`, `AddOns`, a gap, `Switch
character`, `Logout`, `Close`. `AddOns` opens today's manager unchanged, every control of it.
`Options` opens what this feature builds.

**The Options view** is two tabs over a list on the left and a panel on the right. **Game** lists the
client's own panels — Interface, Video, Audio, Keybindings, Camera, Voice Chat, Client — and draws the one
selected. **AddOns** lists the addons that declared at least one option and draws that addon's own. No
panel carries a *Back* any more: the list is the navigation, and Escape closes the window.

**The declaration** is a sibling of `keybindings()` under `hafen.client():options()`. The addon names a row
and its type; the client renders the control, persists the value in its own store and hands it back.
Six types: four carry a value — checkbox, slider, dropdown, text field — and two do not, a
**button** running the addon's own function and a **label** stating a line the addon rewrites. The addon
builds no widget and chooses no file, the division `keybindings():on` already draws.

## Acceptance criteria

1. The menu holds those six entries in that order, and shows no caption. At the login screen `Switch
   character` and `Logout` are absent and every other entry stands.
2. `Options` opens the tabbed view. The Game list holds the seven client panels; selecting one draws it on
   the right. No panel anywhere in the window carries a *Back* button.
3. `AddOns` on the menu opens the manager with every control it has today.
4. An addon declares a row of each of the six types. Each of the four carrying a value reads its default
   back, a write from Lua changes what a later read answers, and the value survives `:reload` and a
   restart; the button and the label carry none and persist nothing. A declaration the client cannot
   honour — an unknown type, an out-of-range default, a name already declared — is refused naming the fix.
5. The AddOns list holds exactly the addons with at least one **live** declared option, in registration
   order; an addon that declared none never appears in it. Selecting one draws its options.
6. The two directions meet: moving a control in the panel changes the value Lua reads **and** fires that
   option's `Changed`; a write from Lua moves an open panel's control.

## Out of scope

- **An option here is client-wide**, like every other setting this window edits. A per-character value is
  what an addon's saved variables already are, and that is where it stays.
- **Layout inside an addon's page.** Options are drawn in declaration order, one per row. Grouping,
  ordering and sub-pages would be a second feature; this one ships no half of it.
- **The manager's contents.** It moves to the menu and is otherwise untouched.
- **A search box over the settings**, which WoW has and this does not.

## Docs impact

Pages written: `docs/addons/api/client/addon.md` (the reference), `docs/addons/api/client/README.md` (the
handle in the tree), `docs/addons/api/README.md` (the index),
`docs/addons/guides/hotkeys-and-commands.md` (the third way a user drives an addon by hand), and a new
`docs/client/ui-panels.md` taking `Tabs` and `OptWnd`'s panel model off `docs/client/ui-controls.md`,
which is near its 150-line ceiling.

Derived impact set — the navigation this window's prose names, which is what changes shape:

```text
$ grep -rn "Options ▸\|Options >" docs/
docs/addons/api/client/keybindings.md:31, :38, :83   docs/addons/api/client/README.md:166, :185
docs/addons/api/sound.md:85                          docs/addons/api/ui/edit.md:321
docs/addons/getting-started.md:133                   docs/addons/guides/debugging.md:109, :131, :140
docs/addons/guides/hotkeys-and-commands.md:17, :25   docs/addons/guides/permissions.md:129
docs/addons/runtime.md:151                           docs/client/widget-input.md:76
```

Sixteen sites over eleven pages. Every `Options ▸ <panel>` becomes `Options ▸ Game ▸ <panel>`. The three
reading `Options ▸ AddOns` — `runtime.md:151`, `permissions.md:129`, `debugging.md:109` — are worse than
stale: that path now names the **new tab**, while the manager they describe is reached from the menu. No
grep aimed at the new syntax finds them.

## Context files

- `src/haven/OptWnd.java` — 1, 3
- `src/haven/Tabs.java`, `src/haven/GameUI.java`, `src/haven/LoginScreen.java` — 1
- `src/haven/SListBox.java`, `src/haven/SListWidget.java`, `src/haven/Scrollport.java` — 1, 3
- `src/haven/CheckBox.java`, `src/haven/HSlider.java`, `src/haven/SDropBox.java`, `src/haven/TextEntry.java` — 3
- `src/io/brodgar/addon/ui/AddonPanel.java`, `src/io/brodgar/ui/ClientPanel.java` — 1
- `src/io/brodgar/addon/AddonOptions.java`, `LuaOption.java` — 2, 3 (the handle, the six builders, and the
  Option the panel draws a control from and writes through)
- `src/io/brodgar/addon/OptionsHandle.java`, `KeybindingsOptions.java`, `OptionsMethod.java` — 2
- `src/io/brodgar/addon/Addon.java`, `Args.java`, `Refusal.java`, `Section.java`, `LuaSub.java` — 2
- `src/io/brodgar/addon/LuaCollection.java`, `Subs.java` — 2 (`opts:option()`, and the emitter `Changed` fires on)
- `src/io/brodgar/addon/LuaBinding.java` — 2 (the interned-object-plus-collection shape the Option copies)
- `src/io/brodgar/addon/HttpApi.java`, `LuaHttpRequest.java` — 2 (the built-bare, dispatched-on-purpose builder)
- `src/io/brodgar/addon/LuaKeyBind.java`, `HookApi.java` — 2 (the registry this one is modelled on)
- `src/io/brodgar/addon/AddonManager.java` — 2, 3 (`describeKeyBinds` and its neighbours only)
- `src/haven/Utils.java` — 2 (`getpref*`/`setpref*`, where the value lands)
- `docs/addons/api/client/addon.md` — 3 (the reference for what an addon declares)
- `docs/addons/api/client/README.md`, `docs/addons/api/client/keybindings.md` — 1, 2
- `docs/addons/api/README.md` — 2 · `docs/addons/guides/hotkeys-and-commands.md` — 1, 3
- `docs/addons/runtime.md`, `docs/addons/guides/permissions.md`, `docs/addons/guides/debugging.md` — 1
- `docs/addons/getting-started.md`, `docs/addons/api/sound.md`, `docs/addons/api/ui/edit.md` — 1
- `docs/client/widget-input.md` — 1
- `docs/client/ui-panels.md` — 3 (`Tabs` and `OptWnd`'s panel model; the list, the holder and the swap)
- `docs/client/ui-controls.md`, `docs/client/ui-lists.md`, `docs/client/gameui-windows.md` — 3
- `DOCUMENTATION.md` — 1, 2, 3
