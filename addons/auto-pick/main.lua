-- Auto Pick -- whenever a radial menu offers a petal captioned "Pick", picks it the instant the ring
-- opens. One key toggles it on or off; assign it in Options > Keybindings > Auto Pick.

local PETAL = "Pick"

-- Per session, so tabbing to a login that never toggled it off still auto-picks for that character
-- (docs/addons/api/flowermenu.md -- a ring is one character's own). Missing entry means "on": the
-- addon's whole point is to act without being asked each time, so the default has to be on.
local off = {}

hafen.client():options():keybindings():on("toggle", function()
  local s = hafen.session():current()
  if not s then return end
  off[s] = not off[s]
  hafen.log():write(off[s] and "auto-pick off" or "auto-picking Pick")
end)

hafen.event():on("FlowerMenuAdded", function(petals, s)
  if off[s] then return end
  for _, p in ipairs(petals) do
    if p:label() == PETAL then
      s:flowermenu():visible(false):select(PETAL)
      return
    end
  end
end)

hafen.event():on("SessionRemoved", function(s) off[s] = nil end)
