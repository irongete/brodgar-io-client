# Object References and Identity

How object references, live handles, and identity checks behave in the addon Lua environment.

---

## 1. Object Identity & Equality (`==`)

Core engine objects (`Session`, `Gob`, `Widget`) are interned per addon. Comparing two handles pointing to the same engine entity returns `true`:

```lua
local session = hafen.session():current()
local object_one = session:world():gob():nearest("terobjs/tree")
local object_two = session:world():gob():get(object_one:id())

-- Identity check: evaluates to true
if object_one == object_two then
  hafen.log():write("Both handles refer to the exact same game object.")
end
```

Because identity holds, you can use `Gob` or `Widget` handles directly as keys in Lua tables:

```lua
local tracked_objects = {}

hafen.event():on("GobAdded", function(game_object)
  tracked_objects[game_object] = os.time()
end)
```

---

## 2. Staleness and Liveness

When an entity leaves the world or a UI window closes, the corresponding handle becomes **stale**:
* Calling `:exists()` on a stale handle returns `false`.
* Calling data methods on a stale handle returns `nil` (or default empty tables).
* Calling write actions on a stale handle will safely fail without crashing the client.

```lua
local target_gob = session:world():gob():nearest("terobjs/tree")

-- Check liveness before performing actions
if target_gob and target_gob:exists() then
  local position = target_gob:position()
  hafen.log():write("Target located at: " .. position:x() .. ", " .. position:y())
end
```

---

## 3. Reference Types Summary

| Reference Type | Source | Liveness Behavior |
|---|---|---|
| `Session` | `hafen.session():current()` | Live until account disconnects. |
| `Gob` | `session:world():gob()` | Live until object despawns or leaves render distance. |
| `Widget` | `hafen.ui():window()` / `session:ui():match()` | Live until window or widget is closed/destroyed. |
| `Position` | `gob:position()` / `world:position(x, y)` | Immutable coordinate snapshot. |
| `Item` | `session:ui():inventory():items()` | Live while item remains in inventory. |
| `Asset` | `hafen.asset():get(...)` | Persistent while addon remains loaded. |
