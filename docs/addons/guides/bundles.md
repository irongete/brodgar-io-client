# Bundles

A bundle is an addon that packs others: its manifest says `"bundle": true` and names them in `dependencies`. The hub lists it with every other addon, its card saying how many it includes, and its **Bundles** chip shows the bundles alone. The client's **AddOns ▸ Browse** tab lists it the same way, and installs it with everything it includes. Use one to ship a set of addons that work together, each set up the way the set wants it.

## The manifest

```json
{ "id": "my-ui", "name": "My UI", "version": "1.0.0", "author": "you", "api_version": "1.0",
  "files": ["main.lua"],
  "description": "The map and the chat, laid out to work together",
  "bundle": true,
  "dependencies": ["mymap>=1.1.0", "mychat>=1.1.0"] }
```

| Rule | Detail |
|---|---|
| `"bundle": true` | Marks it as a bundle, on the hub and in the client's Browse tab: its card says how many addons it includes, the **Bundles** chip lists the bundles alone, and its page shows the card of each addon it includes. The page of each of those says which bundles it is part of. |
| `dependencies` | What it includes, at least one. Name the version you tested with as each minimum: installing the bundle installs the hub's latest of every one missing, and updates one the hub installed below it. |
| Its own permissions | None, unless its own code calls a protected verb. The permissions of the addons it includes are asked for in one dialog when the bundle is installed or ticked. |
| Its files | Run after every dependency's, like any addon's ([libraries](libraries.md)). A bundle with nothing to set up still names one file. |

## Setting its addons up

A bundle changes nothing inside another addon: an addon's options, store and widgets are its own. An addon that lets a bundle choose where it starts exports a function for it, and the bundle calls it from its file body. The player may have turned that addon off, and the bundle loads all the same, so it asks `:api()` first:

```lua
-- my-ui/main.lua
local map = hafen.client():addons():get("mymap"):api()
if map then   -- nil while the player has it turned off
  map.preset{ place = { at = "topright", offset = { -8, 8 } }, size = { width = 300, height = 300 } }
end
```

What such a function takes is the addon's own to document. The addons of the maintainer's repository follow one convention:

| Convention | Why |
|---|---|
| Starting values only | Where the player moves or sizes the addon is theirs, and the addon keeps it over the bundle's values. |
| Screen pixels | A bundle is laid out for the screen at any interface scale: the addon divides by [`hafen.ui():scale()`](../api/ui/pixels.md). |
| An unknown key is logged and skipped | A bundle written for a later version of the addon still loads. |
| A bad value raises | The bundle's author reads why at load, as `<id>.preset: <reason>`. |

The look is the bundle's own sheet: a rule naming another addon's surface dresses it over that addon's stock ([theming](theming.md#reaching-another-addons-surfaces)).

```lua
hafen.ui():sheet():rule("[name=mymap/panel]"):border{ box = "gfx/hud/wnd", mode = "tile" }:sheet():install()
```

## What the player does

| Step | What happens |
|---|---|
| **Install** on the bundle | A window lists what it installs with it, what is already there and what it cannot install ([the manager](../panel.md#installing-an-addon-that-needs-others)). |
| **Install** in that window | One dialog asks for the permissions of every addon that declares one. **Enable** enables the bundle and all of its addons, and everything is downloaded. |
| **Reload UI** | It all lands and loads, the dependencies first. |
| Ticking the bundle on **Installed** | Ticks every addon it includes that is unticked, asking in one dialog for their permissions. |
| Unticking one of its addons | Unticks that one alone. The bundle still loads, with the others: to it, that addon is absent, and its `:api()` is `nil`. |
| **Update** on the bundle | A new version that includes another addon installs it, and enables it while the bundle is enabled, through the same one dialog. The addons already there keep their boxes. |

---

## See Also

- [Libraries](libraries.md) — exporting the function a bundle calls, and the two dependency lists.
- [The manifest](../manifest.md) — `bundle` and `dependencies`.
- [The AddOns manager](../panel.md#browse) — the Bundles chip, and installing a bundle.
- [Theming](theming.md) — dressing another addon's surfaces.
