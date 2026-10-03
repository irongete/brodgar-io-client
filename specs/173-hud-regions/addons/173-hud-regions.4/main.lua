-- 173.4 — a widget names the addon that built it, and the addon layer has a root. Self-checking suite.

local ID = "173-hud-regions.4"
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

local function message(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Does fn raise, saying want? Answers ok and what it said, for one line over several refusals.
local function raises(fn, want)
  local ok, err = pcall(fn)
  if ok then return false, "<no error>" end
  err = message(err)
  return err:find(want, 1, true) ~= nil, err
end

local probe, button
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, widget in ipairs({ button, probe }) do
    if widget then pcall(function() widget:destroy() end) end
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", message(err))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

-- A built widget enters the tree on the step after the statement that built it: read it a moment later.
local function assertAll(gameui)
  local info = probe:info()
  check(probe:addon() == ID and info and info.addon == ID,
    "a surface named probe answers :addon() and :info().addon with " .. ID,
    tostring(probe:addon()) .. " / " .. tostring(info and info.addon))

  if gameui then
    check(button:addon() == ID and gameui:addon() == nil,
      "a button built into @GameUI answers its builder's id, and @GameUI itself answers nil",
      tostring(button:addon()) .. " / " .. tostring(gameui:addon()))
  else
    check(false, "a button built into @GameUI answers its builder's id, and @GameUI itself answers nil",
      "no character in the world -- run :t173 logged in")
  end

  local root = hafen.ui():root()
  local held = false
  for _, child in ipairs(root:children():list()) do
    if child == probe then held = true end
  end
  local named = root:match("[name=" .. ID .. "/probe]")
  check(probe:parent() == root and held and named == probe,
    "probe:parent() is hafen.ui():root(), its :children() holds probe, and [name=" .. ID .. "/probe] finds it",
    tostring(probe:parent()) .. " / held " .. tostring(held) .. " / " .. tostring(named))

  local rootSays, rootSaid = raises(function() hafen.ui():root(1) end, "takes no arguments")
  local addonSays, addonSaid = raises(function() probe:addon(1) end, "takes no arguments")
  check(root:addon() == nil and root:parent() == nil and rootSays and addonSays,
    "the root answers :addon() and :parent() with nil; hafen.ui():root(1) and probe:addon(1) raise",
    tostring(root:addon()) .. " | " .. rootSaid .. " | " .. addonSaid)

  -- The layer is every addon's: name each other addon a surface of it answers.
  local others, ids, bad = 0, {}, {}
  for _, child in ipairs(root:children():list()) do
    local by = child:addon()
    if by ~= nil and type(by) ~= "string" then bad[#bad + 1] = tostring(by) end
    if type(by) == "string" and by ~= ID then
      others = others + 1
      ids[by] = true
    end
  end
  local list = {}
  for by in pairs(ids) do list[#list + 1] = by end
  table.sort(list)
  check(#bad == 0, ("the layer holds %d surface(s) of other addons (%s), each :addon() a string or nil")
    :format(others, (#list > 0) and table.concat(list, ", ") or "none"), table.concat(bad, ", "))
  finish()
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  probe = hafen.ui():widget():name("probe"):size(80, 40):position(40, 40)
  local session = hafen.session():current()
  local gameui = session and session:ui():match("@GameUI")
  if gameui then
    button = hafen.ui():button():parent(gameui):text("173.4"):size(80):position(40, 100)
  end
  after(0.3, function() assertAll(gameui) end)
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t173", function() after(0, run) end)
