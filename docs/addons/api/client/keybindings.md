# hafen.client: Keybindings

`hafen.client():options():keybindings()` is the client's hotkey registry: declare your addon's hotkeys, read, remap or reset any binding, yours or the client's own, and take a section of the client's own off the keybindings panel. Declaring, reading and hiding are unprotected. A remap from your code needs [`client.settings`](../../guides/permissions.md). The user assigns a key in Options ▸ Game ▸ Keybindings, or with a [key button](../ui/controls/interactive.md#key-button) on your own page.

```lua
local keybindings = hafen.client():options():keybindings()
local toggle_hotkey = keybindings:on("toggle", function()
  hafen.log():write("toggled")
end)
```

---

| Method | Returns | Permission | Description |
|---|---|---|---|
| `keybindings:on(name, fn)` | [`Sub`](../event/README.md#subscribe) | Unprotected | Declare a hotkey owned by your addon. `fn` runs when it fires. |
| `keybindings:binding()` | [collection](../conventions.md#collections-the-noun-is-the-kind-the-verb-is-how-many) of [Binding](#the-binding-object) | Unprotected | Every binding the client knows: yours, other addons' and its own. Takes no arguments. |
| `keybindings:section(id)` | [Section](#the-section-object) | Unprotected | One of the client's own sections of the panel, by [id](#hiding-a-client-section). An unknown id raises, naming them. |

| Rule | Detail |
|---|---|
| A hotkey is a subscription | `toggle_hotkey:key()` is the name declared, `toggle_hotkey:off()` drops the handler while your addon keeps running. The user's assignment survives, since the binding belongs to the client. |
| `name` | What the user reads beside the key: not empty, remembered under a preference key built from your addon's id and it. A name too long for that key raises here, naming the length. |
| One live hotkey per name | Declaring `on("toggle", …)` again replaces the first: the earlier subscription ends and the new handler takes the key. |
| Where the handler runs | Inside the tree of the character on screen, the tree the key press walked, as a [console command](../console.md#subscribe) does. It reads and writes that character freely. It reaches no other tree, your own windows in the addon layer included ([threading](../threading.md#where-each-handler-runs)). Handed nothing: reach the session you want through [`hafen.session()`](../session.md). |
| Your own windows | Anything that touches a window of yours goes through `hafen.timer():after(0, fn)` at the top of the body, which runs `fn` on the next step holding nothing. |
| An assigned key is consumed | Whatever your handler does: one that raises still eats the press (the error is logged), and the client does not also run its own binding. |

## Addon hotkeys start unbound

`on` takes no default key. Your addon names an action and the user assigns the key in Options ▸ Game ▸ Keybindings. Every addon that declared a hotkey has its own section there, by addon name. [A key button on your own page](#a-key-button-on-your-own-page) assigns it too. An addon-chosen default could not claim a key in use under the client's one-key-one-action rule, so it would lose the collision and never fire. Advertise a suggested key in your README instead (*Suggested key: `Ctrl+H`, assign it in Options ▸ Game ▸ Keybindings ▸ myaddon*). The assignment is persisted by the client and survives `:reload` and restarts. Declaring the same name again picks it back up.

## A key button on your own page

Each row Options ▸ Game ▸ Keybindings gives a hotkey ends in a key button, and your page can hold that button too: [`hafen.ui():keybinding()`](../ui/controls/interactive.md#key-button), joined to one of your hotkeys with `:bind(binding)`.

```lua
local keybindings = hafen.client():options():keybindings()
keybindings:on("toggle", function() hafen.log():write("toggled") end)
local toggle_binding = keybindings:binding():get("toggle")          -- after on(): your own hotkey
hafen.client():options():addon():panel(function(root)
  hafen.ui():keybinding():parent(root):bind(toggle_binding)
end)
```

| Rule | Detail |
|---|---|
| The same edit | A press on it assigns the key as the panel's row does: persisted, and taken off the binding that was assigned it. Each place shows what the other assigned. |
| Unprotected | The press is the user's own remap. `binding:key(key)`, the remap your code makes, stays under `client.settings`. |
| Your own hotkeys | It joins a hotkey your addon declared and has not ended. The client's bindings and other addons' are assigned in the panel. |

## An assigned key answers to you and to nothing else

| Rule | Detail |
|---|---|
| Exact key, exact modifiers | Once assigned, every other binding yields it, including a client hotkey whose own match is looser. The action menu reads `Shift` as *keep the menu open*, so `B` answers `B` and `Shift+B`. Assign `Shift+B` and the menu keeps plain `B`. |
| Unbinding hands the key back | On the next press: the client's binding is never written to, only shadowed while your key stands. |
| A default wins nothing | Your hotkey starts unbound and claims nothing. A collision nobody assigned is settled by the walk, where the client's bindings are offered the press first. |

> **The key is held only while your hotkey is.** The `KeyBinding` and the assignment are the client's and outlive you. The hold does not. Disabling or deleting your addon, `subscription:off()`, or the CPU watchdog disabling your addon releases it. What the key was held off answers again. Declaring the hotkey takes it back, so declare hotkeys at load, not from a later event. The one case the key does not return: it was assigned to something else while you were disabled. That later choice is the user's, and yours is cleared to unbound.

## The binding collection

`keybindings:binding()` holds every binding the client knows, ordered by id, with `:list(filter)`, `:count(filter)`, `:find(filter)` and `:get(id)`. A string filter is a substring test on the id.

```lua
for _, binding in ipairs(keybindings:binding():list()) do
  if binding:key() then hafen.log():write(binding:id() .. " = " .. binding:key()) end
end
```

| Rule | Detail |
|---|---|
| `:get(id)` always hands you a Binding | `binding:exists()` is the question a `nil` would answer. The registry fills in as the client's windows build and addons declare, so an id addressed in your file body is often there after login. The handle is the same object either way. |
| Your names are namespaced | `on("test", fn)` and `binding():get("test")` name the same binding without spelling your addon id. A name not yours is the registry id as written (`binding():get("inv")`). Your scope is tried first, so a client binding never shadows yours. `binding:id()` is the full id: yours read back as `addon/<your-addon-id>/<name>`. |

## The binding object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `binding:id()` | `string` | Unprotected | The registry id, its identity. |
| `binding:key()` | `string \| nil` | Unprotected | The key it fires on, as a display string. `nil` when unbound. |
| `binding:key(key)` | the binding | `client.settings` | Assign a key. `"None"` unbinds. |
| `binding:key(nil)` | the binding | `client.settings` | Put it back on the client's own default. |
| `binding:default()` | `string \| nil` | Unprotected | The key the client gives it. `nil` where that default is unbound. |
| `binding:assigned()` | `boolean` | Unprotected | Whether the current key is the user's choice rather than the client's default. |
| `binding:down()` | `boolean` | Unprotected | Whether its key is held right now ([below](#down-the-key-not-the-hotkey)). |
| `binding:exists()` | `boolean` | Unprotected | Whether anything has declared this id yet. |
| `binding:info()` | `table \| nil` | Unprotected | `{id=, assigned=, down=}`, plus `key` and `default` where bound. `nil` for an undeclared id. |

| Rule | Detail |
|---|---|
| Writing persists | As the same edit a key button makes, in Options ▸ Game ▸ Keybindings or [on your page](#a-key-button-on-your-own-page). `binding:key("Ctrl+I")` on `inv` takes the client's own inventory key. The user undoes it by hand. A write on an undeclared binding raises. A read answers `nil`, except `id()` and the boolean reads, which are `false`. |
| Three states, two read as no key | On the client's default, assigned by the user, or unbound by the user: `key()` collapses the first and last to `nil`, `assigned()` tells them apart. `key(nil)` exists so an addon saving a key and writing it back does not turn a default into an assignment it cannot take off. A default another binding holds the key for reads `nil` too. `default()` answers the key it fires on again once the other lets go. |
| Assigning takes the key off whoever was assigned it | That binding is left unbound with no undo. A binding on the client's default is shadowed, not written: `nil` while your key stands, firing again on release. Where its match is looser it is shadowed for your combination alone and reads its own key (`Shift+B` leaves the menu `B`). Reverting runs no such pass, so two bindings put back on defaults sharing a key both fire. |

```lua
local binding = keybindings:binding():get("toggle")
local saved_key = binding:assigned() and (binding:key() or "None") or nil   -- nil is "on the client's default"
binding:key(saved_key)                                                        -- restores every state, the default included
```

## `down()`: the key, not the hotkey

`on` gives the edge, the moment the key goes down, with no counterpart for it coming up. `binding:down()` is the level, for a key that means something while held (walking, push-to-talk, a modifier of your own).

```lua
local keybindings = hafen.client():options():keybindings()
keybindings:on("sprint", function() end)                    -- declared, so the user has a row to bind
local sprint = keybindings:binding():get("sprint")
hafen.timer():every(0.05, function()
  if sprint:down() then hafen.log():write("held") end       -- every tick it is held, not once when it went down
end)
```

| Rule | Detail |
|---|---|
| Key repeat is no substitute | A held key fires the hotkey at the system's repeat rate. A desktop repeats only the key pressed last (hold `W`, tap `D`, `W` stops). The delay before the first repeat is unreadable. |
| The key, not the handler | A hotkey does not fire while a text field has focus. `down()` still reports the key down. Guard on focus yourself. Starting the poll from the hotkey and stopping it when nothing is held is usually enough. |
| Matched as pressed | Taking up or letting go of `Shift` after the key went down neither makes nor breaks the match. |
| Unbound reads `false` | So does an undeclared binding. |
| Focus loss lets go of everything | A key held through an alt-tab has its release delivered elsewhere, so the client drops the whole set. Come back with it held and it reads up until pressed again. |

## Hiding a client section

Options ▸ Game ▸ Keybindings paints the client's own sections first and one section per addon after them. An addon that stands in for a piece of the client, its own action bars with hotkeys of its own, leaves the user two sections of the same keys, and the client's is the one they find first. `keybindings:section(id):visible(false)` takes the client's section off the panel while your addon runs.

```lua
local keybindings = hafen.client():options():keybindings()
keybindings:section("actionbar"):visible(false)   -- at load: the client's "Action bar" rows are gone
```

| id | Section |
|---|---|
| `menu` | Main menu |
| `map` | Map options |
| `camera` | Camera control |
| `mapwnd` | Map window |
| `speed` | Walking speed |
| `actionbar` | Action bar |
| `combat` | Combat actions |

| Rule | Detail |
|---|---|
| The rows go, the bindings stay | Nothing under a hidden section is unbound or remapped. Its keys keep firing: the action bar's buttons and pages press the character's bar whether or not the client's own bar is drawn. Assign the same keys to your hotkeys and yours shadow them; leave them and they still answer, with no row to say so. State it in your README. |
| A hold, held while your addon is | Hiding persists nothing. Disabling or deleting your addon, `:reload`, or the CPU watchdog disabling it paints the section again. Hide at load, as you declare hotkeys. |
| Painted while nobody holds it | Two addons can hold one section. `visible(true)` releases yours; while another addon still holds it, the section stays hidden and reads `false`. |
| Rebuilt on the next visit | The panel is built each time its button is pressed, so a section hidden or shown while it stands on screen changes on the next press. |

### The Section object

| Method | Returns | Permission | Description |
|---|---|---|---|
| `section:id()` | `string` | Unprotected | The id it was addressed by. |
| `section:visible()` | `boolean` | Unprotected | Whether the panel paints it: `false` while any addon holds it. |
| `section:visible(flag)` | the section | Unprotected | `false` takes your hold on it, `true` releases it. |
| `section:info()` | `table` | Unprotected | `{id=, visible=}`. |

`keybindings:section("actionbar") == keybindings:section("actionbar")`: one object per id for your addon.

## Key strings

The last `+`-separated token is the key, with optional modifiers before it: `Ctrl`/`Control`/`Ctl`, `Shift`, `Alt`/`Meta`, case-insensitive. Named keys: `F1`..`F12`, `Space`, `Enter`/`Return`, `Tab`, `Esc`, `Backspace`, `Delete`, `Insert`, `Home`, `End`, `PageUp`, `PageDown`, `Up`, `Down`, `Left`, `Right`. Anything else is a single character. `"None"` is unbound. Examples: `"F5"`, `"Ctrl+M"`, `"Shift+Alt+Left"`. A key read back is spelled the same way, modifiers in the order `Shift`, `Ctrl`, `Alt`. Modifier matching is exact: `"M"` fires only on a bare `M`.

## Example

```lua
local keybindings = hafen.client():options():keybindings()
local toggle_hotkey = keybindings:on("toggle", function() hafen.log():write("toggled") end)

local toggle_binding = keybindings:binding():get("toggle")
hafen.log():write("my key: " .. tostring(toggle_binding:key()))          -- nil until the user assigns one

local inventory_binding = keybindings:binding():get("inv")
if inventory_binding:exists() then
  hafen.log():write("inventory: " .. tostring(inventory_binding:key()) .. ", assigned: " .. tostring(inventory_binding:assigned()))
end
hafen.log():write("bindings: " .. keybindings:binding():count())
```

Hotkeys are torn down with your addon on reload or disable. `toggle_hotkey:off()` drops one while the addon keeps running. A global hotkey runs through the registry after the client's own bindings. To intercept a mouse event before the widget under it, [subscribe on the widget](../ui/widget.md#subscribing). Keyboard input is not a widget option, so a hotkey is the only door onto a key.

---

## See Also

- [`hafen.client():options()`](README.md) — the rest of the settings surface.
- [Conventions](../conventions.md) — the collection verbs, and what `:get` answers for a key nothing holds.
- [The Widget object](../ui/widget.md#subscribing) — intercepting a mouse event before the widget does.
- [`hafen.console`](../console.md) — a console command, the other way an addon is invoked by hand.
- [`hafen.event`](../event/README.md) — the `Sub` a hotkey hands back, and every other subscription.
