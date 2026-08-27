-- 115.1 — the Options window is a menu and two tabs. Self-checking suite.
--
-- Type :t115 in the world. The automated half reads the whole Options window out of the tree and
-- finishes instantly, wherever the window was left: nothing below asks it to be in a particular
-- state. The holder check covers every panel visited so far, so it says more the more of the window
-- you have walked. The [manual] lines then walk it by hand.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a SPACE and no second colon, so the
-- strip has to allow both shapes or the message is scored with its own location glued to the front.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function join(t)
  return "[" .. table.concat(t, ", ") .. "]"
end

local function same(got, want)
  if #got ~= #want then return false end
  for i = 1, #want do
    if got[i] ~= want[i] then return false end
  end
  return true
end

-- The one child of w whose class name is `ty`, or nil. The settings view is built out of the
-- client's own widgets, so its parts are named by what they are rather than by a selector of ours.
-- :children() is a COLLECTION, so :list() is what an ipairs walks.
local function childOf(w, ty)
  for _, ch in ipairs(w:children():list()) do
    if ch:type() == ty then return ch end
  end
  return nil
end

-- The rows of a subject list, top to bottom: each row is a Label inside an item widget, and the
-- item's own y is where the list put it, whatever order the widgets were built in.
local function rowNames(list)
  local rows = {}
  for _, lbl in ipairs(list:matchAll("@Label")) do
    rows[#rows + 1] = {y = lbl:parent():position().y, text = lbl:text() or ""}
  end
  table.sort(rows, function(a, b) return a.y < b.y end)
  local names = {}
  for i = 1, #rows do names[i] = rows[i].text end
  return names
end

local GAME = {"Interface settings", "Video settings", "Audio settings", "Keybindings",
              "Camera", "Voice Chat Integration", "Client"}
-- The same seven, as the client's own class names: what a panel in the holder reports as its type.
local PANELS = {InterfacePanel = true, VideoPanel = true, AudioPanel = true, BindingPanel = true,
                CameraPanel = true, VoiceChatPanel = true, ClientPanel = true}
local MENU = {"Options", "AddOns", "Switch character", "Log out", "Close"}

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t115 scores its own run, not both
  local s = hafen.session():current()
  local win = s and s:ui():match("@OptWnd")
  check(win ~= nil, "the Options window stands in the tree", win)
  if not win then return report() end

  -- The game menu: a bare Panel, where every other panel of this window is a class of its own. Read
  -- by walking the window's own children rather than by :match, which raises where two would answer.
  local menu = childOf(win, "Panel")
  local labels = {}
  for _, ch in ipairs(menu and menu:children():list() or {}) do
    local t = ch:text()
    -- "Visit store" stands only on a Steam client, and says nothing about the shape being checked.
    if t and t ~= "" and t ~= "Visit store" then labels[#labels + 1] = t end
  end
  check(same(labels, MENU), "the menu holds its entries in order " .. join(MENU), join(labels))
  -- Which panel is showing decides the window's caption: the menu carries none, and the two subjects
  -- carry their own. Read whichever is up, so this stands wherever the window was left.
  local CAPS = {Panel = false, SettingsPanel = "Options", AddonPanel = "AddOns"}
  local showing, want = nil, nil
  for _, ch in ipairs(win:children():list()) do
    local c = CAPS[ch:type()]
    if (c ~= nil) and ch:visible() then showing, want = ch:type(), c end
  end
  check(showing ~= nil and win:title() == (want or nil),
        "the caption follows the panel showing (" .. tostring(showing) .. ")", win:title())

  -- The settings view: a tab strip, and one list plus one holder per tab.
  local view = childOf(win, "SettingsPanel")

  -- A panel that has been built stands under the window whether it is showing or not, so one walk
  -- reads every one visited so far. The addon manager is deliberately NOT walked: it keeps its own
  -- Back, and is reached from the menu rather than from the list that replaced the others'.
  local backs, panels = 0, 0
  for _, root in ipairs({menu, view}) do
    if root then
      root:walk(function(w)
        if w:text() == "Back" then backs = backs + 1 end
        if w:type():find("Panel", 1, true) then panels = panels + 1 end
      end)
    end
  end
  check(backs == 0, "no Back stands on the menu or in the settings view, over the "
        .. panels .. " panels built there", backs)

  local strip, tabs = {}, {}
  for _, ch in ipairs(view and view:children():list() or {}) do
    if ch:type() == "TabButton" then strip[#strip + 1] = ch:text() end
    if ch:type() == "Tab" then tabs[#tabs + 1] = ch end
  end
  check(same(strip, {"Game", "AddOns"}), "the tab strip is Game then AddOns", join(strip))

  local game = tabs[1]
  local list = game and childOf(game, "PanelList")
  local holder = game and childOf(game, "Widget")
  check(same(rowNames(list or view), GAME), "the Game tab lists the seven client panels in order "
        .. join(GAME), join(rowNames(list or view)))

  local addonList = tabs[2] and childOf(tabs[2], "PanelList")
  check(addonList ~= nil and #rowNames(addonList) == 0,
        "the AddOns tab lists nothing while no addon has declared an option", addonList)

  -- The holder keeps every panel it has built, hidden, so this reads the whole history of what the
  -- list has been asked for: each one a different subject's own, and the one showing not empty.
  local drawn, held, seen, dup = nil, 0, {}, nil
  for _, ch in ipairs(holder and holder:children():list() or {}) do
    local ty = ch:type()
    held = held + 1
    if seen[ty] then dup = ty end
    seen[ty] = true
    if not PANELS[ty] then dup = dup or ("not a client panel: " .. ty) end
    if ch:visible() then drawn = ch end
  end
  check(drawn ~= nil and drawn:children():count() > 0 and dup == nil and held > 0,
        "the holder draws one non-empty client panel, and every panel it built is a different one ("
        .. held .. " built)",
        dup or (drawn and (drawn:type() .. " with " .. drawn:children():count() .. " widgets")
                or "nothing showing"))

  -- Picking a row from Lua is an ACT on one of the client's own controls, so it is keyed; this suite
  -- declares nothing, and the refusal is what it gets. Every selection below is therefore by hand.
  refuses("driving the subject list from Lua is refused, naming the key it wants",
          function() list:value(list:value()) end, '"widget.value" permission')

  manualCheck("press Ctrl+O", "a menu in two groups -- the two destinations, then the way out --"
              .. " with no text at all in the title bar")
  manualCheck("click Options on that menu", "a Game/AddOns tab strip, under a title bar reading Options")
  manualCheck("click Video settings in the list on the left",
              "that panel drawn to the right of the list, with no Back button under it")
  manualCheck("click AddOns on the game menu",
              "the addon manager with its checkboxes and its own Back, unchanged")
  manualCheck("open Options from the login screen",
              "the same menu, without Switch character and without Log out")
  report()
end

hafen.console():on("t115", run)   -- the only way in: a suite does not start itself
