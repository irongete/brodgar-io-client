-- 173.1 — the combat display's regions. Self-checking suite.

local ID = "173-hud-regions.1"
local ROLES = { "fight.opening.mine", "fight.opening.theirs", "fight.ip.mine", "fight.ip.theirs",
                "fight.cooldown", "fight.last.mine", "fight.last.theirs", "fight.action" }

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

-- A refusal's text without LuaJ's "@main.lua:12 " prefix, or nil when the call did not raise.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local session, gameui, fightsess, frame, panel, follower, sheet, sampler, holdRead
local subs, regions, removed, hidden = {}, {}, {}, {}
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, sub in ipairs(subs) do pcall(function() sub:off() end) end
  subs = {}
  if sampler then pcall(function() sampler:off() end) end
  if sheet then pcall(function() sheet:release() end) end
  if fightsess then
    pcall(function() fightsess:position(nil) end)
    pcall(function() if fightsess:parent() == frame then fightsess:parent(nil) end end)
  end
  if regions["fight.cooldown"] then pcall(function() regions["fight.cooldown"]:position(nil) end) end
  for _, entry in ipairs(hidden) do pcall(function() entry.widget:visible(true) end) end
  for _, widget in ipairs({ frame, follower, panel }) do pcall(function() widget:destroy() end) end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function guarded(fn)
  return function(...)
    local ok, err = pcall(fn, ...)
    if not ok then
      check(false, "the suite ran to its end", (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "")))
      finish()
    end
  end
end

local function after(seconds, fn)
  hafen.timer():after(seconds, guarded(fn))
end

local function prompt(text, onDone, onSkip)
  if panel then panel:destroy() end
  panel = hafen.ui():window():title("173.1"):size(520, 70):position(40, 160)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function skipped(what)
  return function()
    check(false, what, "skipped")
    finish()
  end
end

local function centre(widget)
  local at, size = widget:rootPos(), widget:size()
  return at.x + math.floor(size.w / 2), at.y + math.floor(size.h / 2)
end

local function near(a, b, tolerance) return math.abs(a - b) <= (tolerance or 2) end

-- The client's geometry about one point: the cooldown, the combat row and the two last manoeuvres share an x,
-- and the cooldown and the last manoeuvres a y.
local function geometry()
  local cx, cy = centre(regions["fight.cooldown"])
  local ax = centre(regions["fight.action"])
  local mx, my = centre(regions["fight.last.mine"])
  local tx, ty = centre(regions["fight.last.theirs"])
  local size = regions["fight.cooldown"]:size()
  local ok = size.w > 0 and near(cx, ax) and near(cx, math.floor((mx + tx) / 2)) and near(cy, my) and near(cy, ty)
  return ok, ("cooldown %d,%d (%dx%d)  action x %d  last %d,%d / %d,%d"):format(cx, cy, size.w, size.h, ax, mx, my,
    tx, ty)
end

local function second(waited)
  waited = waited or 0
  local entry, first = hidden[2], hidden[1]
  if entry and not removed[entry.widget] and waited < 10 then
    return after(0.25, function() second(waited + 0.25) end)
  end
  check(entry ~= nil and first ~= nil and entry.widget ~= first.widget and entry.read == false
      and removed[entry.widget] == true,
    "the second fight's fight.opening.mine was hidden by the same handler and fired Removed",
    entry and ("visible " .. tostring(entry.read) .. ", removed " .. tostring(removed[entry.widget]))
      or (#hidden .. " fight(s) seen"))
  finish()
end

-- The server takes the combat display down on its own schedule, a moment after the fight is over: the eight
-- Removed are waited for over a bounded window, and scored over what it reached.
local function ended(waited)
  waited = waited or 0
  local fired, standing = 0, 0
  for _, role in ipairs(ROLES) do
    if removed[regions[role]] then fired = fired + 1 end
    if regions[role]:exists() then standing = standing + 1 end
  end
  if fired < #ROLES and waited < 10 then
    return after(0.25, function() ended(waited + 0.25) end)
  end
  check(fired == #ROLES, "when the fight ended, all eight regions fired Removed",
    fired .. " of " .. #ROLES .. " after " .. waited .. " s; " .. standing .. " still exist, @Fightsess "
      .. (session:ui():match("@Fightsess") and "still stands" or "gone"))
  prompt("Fight once more (a chicken or rabbit) and end it; press Done.", second,
    skipped("a second fight was reached"))
end

local function holding()
  local cooldown = regions["fight.cooldown"]
  local at = cooldown:position()
  local held = holdRead and holdRead.x == 40 and holdRead.y == 40 and at.x == 40 and at.y == 40
  cooldown:position(nil)
  after(0.05, function()
    local ok, got = geometry()
    check(held and ok,
      "fight.cooldown:position(40, 40) read (40, 40) after half a second and held until Done; after"
        .. " :position(nil) the geometry holds again a frame later",
      (holdRead and (holdRead.x .. "," .. holdRead.y) or "-") .. " then " .. at.x .. "," .. at.y .. "; " .. got)
    prompt("End the fight (win it or walk away); press Done.", ended, skipped("the end of the fight was reached"))
  end)
end

local function hold()
  local cooldown = regions["fight.cooldown"]
  cooldown:position(40, 40)
  after(0.5, function()
    holdRead = cooldown:position()
    local opening = regions["fight.opening.mine"]
    check(hidden[1] ~= nil and hidden[1].widget == opening and hidden[1].read == false and opening:visible() == false,
      "fight.opening.mine:visible(false) reads false", hidden[1] and tostring(opening:visible()) or "no Added seen")
    manualCheck("while it holds (40, 40), look at the screen's top left", "the cooldown circle there")
    manualCheck("look round your character", "your openings gone (any you have this fight)")
    prompt("Look at the top left (the cooldown circle) and round your character (no openings); press Done.",
      holding, skipped("the held place was looked at"))
  end)
end

local function refusals()
  local cooldown = regions["fight.cooldown"]
  local said = {
    refusal(function() return cooldown:size(40, 40) end),
    refusal(function() return cooldown:size(40) end),
    refusal(function() return cooldown:resizable(follower) end),
  }
  local named = 0
  for index = 1, 3 do
    if said[index] and said[index]:find("paints it at its own size", 1, true) then named = named + 1 end
  end
  check(named == 3, ":size(w, h), :size(w) and :resizable(h) on a region raise, naming that the client paints it"
    .. " at its own size", named .. " of 3: " .. tostring(said[1]))
  hold()
end

-- The regions follow their painter: the whole display moved, then taken into a surface, then given back.
local function follow()
  local cooldown = regions["fight.cooldown"]
  local base = cooldown:rootPos()
  local steps = {}
  fightsess:position(60, 0)
  after(0.2, function()
    local moved = cooldown:rootPos()
    steps[1] = near(moved.x - base.x, 60) and near(moved.y, base.y)
    fightsess:position(nil)
    local size = gameui:size()
    frame = hafen.ui():widget():parent(gameui):size(size.w, size.h):position(0, 60)
    fightsess:parent(frame)
    after(0.2, function()
      local single = 0
      for _, role in ipairs(ROLES) do
        local found = session:ui():matchAll(role)
        if #found == 1 and found[1]:type() == "Region" and found[1] == regions[role] then single = single + 1 end
      end
      local taken = cooldown:rootPos()
      steps[2] = single == #ROLES and near(taken.y - base.y, 60) and near(taken.x, base.x)
      fightsess:parent(nil)
      frame:destroy()
      frame = nil
      after(0.2, function()
        local back = cooldown:rootPos()
        steps[3] = near(back.x, base.x) and near(back.y, base.y)
        check(steps[1] and steps[2] and steps[3],
          "the regions follow their painter: 60 right after @Fightsess:position(60, 0), each role one Region and 60"
            .. " lower inside a surface moved 60 down, and back where it began after :parent(nil)",
          ("%s %s %s (base %d,%d, inside %d,%d, single %d)"):format(tostring(steps[1]), tostring(steps[2]),
            tostring(steps[3]), base.x, base.y, taken.x, taken.y, single))
        refusals()
      end)
    end)
  end)
end

-- The anchored surface, sampled every frame for two seconds: it stands on its anchor at the region's place
-- of that frame or of the frame before.
local function sample()
  local cooldown = regions["fight.cooldown"]
  local frames, onAnchor, previous = 0, 0, nil
  local worst = "-"
  sampler = hafen.event():on("Update", function()
    local target, at = cooldown:rootPos(), follower:rootPos()
    frames = frames + 1
    local now = near(at.x, target.x, 1) and near(at.y, target.y - 24, 1)
    local before = previous and near(at.x, previous.x, 1) and near(at.y, previous.y - 24, 1)
    if now or before then
      onAnchor = onAnchor + 1
    else
      worst = ("%d,%d vs %d,%d"):format(at.x, at.y, target.x, target.y - 24)
    end
    previous = target
  end)
  after(2, function()
    sampler:off()
    sampler = nil
    check(frames > 0 and onAnchor == frames,
      "the anchored surface stood on its anchor in every frame of two seconds (" .. frames .. " frames)",
      onAnchor .. " of " .. frames .. ", " .. worst)
    local x, y = centre(cooldown)
    local hit = hafen.ui():hit(x, y)
    check(hit == nil or hit:type() ~= "Region", "hafen.ui():hit() at fight.cooldown's centre is not a region",
      hit and hit:type())
    follow()
  end)
end

local function fighting()
  local single, parented = 0, 0
  for _, role in ipairs(ROLES) do
    local found = session:ui():matchAll(role)
    if #found == 1 and found[1]:type() == "Region" then
      single = single + 1
      regions[role] = found[1]
      if found[1]:parent() == gameui then parented = parented + 1 end
    end
  end
  local selector = hafen.ui():role():get("fight.action"):selector()
  check(single == #ROLES and parented == #ROLES and selector == "fight.action",
    "each of the eight roles matches one Region on @GameUI, and the role reads its selector",
    single .. " single, " .. parented .. " on @GameUI, selector " .. tostring(selector))
  if single ~= #ROLES then return finish() end
  local ok, got = geometry()
  check(ok, "unheld, the cooldown, the combat row and the two last manoeuvres keep the client's geometry", got)
  local ip = regions["fight.ip.theirs"]:position()
  check(ip.x == 500 and ip.y == 120, "fight.ip.theirs reads (500, 120): the rule reached a region that appeared"
    .. " after it", ip.x .. "," .. ip.y)
  sample()
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  subs, regions, removed, hidden, holdRead = {}, {}, {}, {}, nil
  session = hafen.session():current()
  gameui = session and session:ui():match("@GameUI")
  if not gameui then
    check(false, "a character is in the world", "no HUD -- run :t173 logged in")
    return finish()
  end
  local status = hafen.client():addons():get(ID):info().status
  check(status == "loaded", "this suite declares api_version \"1.5\" and loaded", status)
  sheet = hafen.ui():sheet()
  sheet:rule("fight.ip.theirs"):position(500, 120)
  sheet:install()
  follower = hafen.ui():widget():name("follower"):size(16, 16)
  subs[#subs + 1] = session:ui():on("fight.cooldown", "Added", function(cooldown)
    sheet:rule("[name=" .. ID .. "/follower]"):anchor{ to = cooldown, at = "topleft", offset = { 0, -24 } }
    local opening = session:ui():match("fight.opening.mine")
    opening:visible(false)
    hidden[#hidden + 1] = { widget = opening, read = opening:visible() }
  end)
  for _, role in ipairs(ROLES) do
    subs[#subs + 1] = session:ui():on(role, "Removed", function(widget) removed[widget] = true end)
  end
  fightsess = nil
  prompt("Attack one chicken or rabbit; press Done while you fight.", function()
    fightsess = session:ui():match("@Fightsess")
    if not fightsess then
      check(false, "a fight is drawn", "no @Fightsess")
      return finish()
    end
    -- A region takes its place from its painter's draw: a combat display another addon keeps hidden paints
    -- nothing, and every region stands still at its birth place. That is a precondition, not this task's check.
    local hiddenBy
    local node = fightsess
    while node do
      if node:visible() == false then hiddenBy = node:type() break end
      node = node:parent()
    end
    if hiddenBy then
      check(false, "the combat display is drawn", "@" .. hiddenBy .. " is hidden -- disable the addon hiding it"
        .. " and run :t173 again")
      return finish()
    end
    fighting()
  end, skipped("a fight with an animal was reached"))
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t173", function() after(0, run) end)
