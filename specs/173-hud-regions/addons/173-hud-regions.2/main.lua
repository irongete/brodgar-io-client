-- 173.2 — the HUD's bottom-left line and the belt. Self-checking suite.

local ROLES = { "hud.cmdline", "hud.message", "hud.chat" }

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

local session, gameui, belt, sheet, panel
local regions = {}
local finished = false

local function finish()
  if finished then return end
  finished = true
  for _, widget in ipairs({ belt, regions["hud.message"], regions["hud.cmdline"] }) do
    pcall(function() widget:position(nil) end)
  end
  if sheet then pcall(function() sheet:release() end) end
  if panel then pcall(function() panel:destroy() end) end
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
  panel = hafen.ui():window():title("173.2"):size(560, 70):position(40, 160)
  local done = hafen.ui():button():parent(panel):position(8, 8):size(80):text("Done")
  local skip = hafen.ui():button():parent(panel):position(96, 8):size(80):text("Skip")
  hafen.ui():label():parent(panel):position(8, 44):text(text)
  done:on("Pressed", function() after(0, onDone) end)
  skip:on("Pressed", function() after(0, onSkip) end)
end

local function near(a, b) return math.abs(a - b) <= 1 end

local function xy(widget)
  local at = widget:position()
  return at.x .. "," .. at.y
end

-- The HUD's three regions in `session`, each the one Region its role matches, or nil.
local function stand()
  local found, single = {}, 0
  for _, role in ipairs(ROLES) do
    local all = session:ui():matchAll(role)
    if #all == 1 and all[1]:type() == "Region" and all[1]:parent() == gameui then
      single = single + 1
      found[role] = all[1]
    end
  end
  return found, single
end

-- Where the line's bottom is, as the client works it out: the screen's bottom, the top of the HUD's own
-- chat while it is shown, and the top of the belt while it stands at its default place.
local function bottom(withBelt)
  local lowest = gameui:size().h
  local chat = session:ui():match("@ChatUI")
  if chat and chat:parent() == gameui and chat:visible() then lowest = math.min(lowest, chat:position().y) end
  if withBelt and belt:visible() then lowest = math.min(lowest, belt:position().y) end
  return lowest
end

-- Is the belt at the place the client gives it: above the HUD's chat, or GameUI.resize's place without one?
local function beltAtDefault()
  local chat = session:ui():match("@ChatUI")
  if not (chat and chat:parent() == gameui) then return nil end
  local at, size, chatAt = belt:position(), belt:size(), chat:position()
  local wantY = math.min(chatAt.y - size.h, gameui:size().h - size.h)
  return near(at.x, chatAt.x) and near(at.y, wantY)
end

local function typing()
  manualCheck("with hud.cmdline held at (60, 100), press : and type", "the line near the top left")
  finish()
end

local function relogged(waited)
  waited = waited or 0
  session = hafen.session():current()
  gameui = session and session:ui():match("@GameUI")
  local cmdline = gameui and session:ui():match("hud.cmdline")
  if not cmdline and waited < 10 then
    return after(0.25, function() relogged(waited + 0.25) end)
  end
  local at = cmdline and cmdline:position()
  check(at ~= nil and at.x == 60 and at.y == 100,
    "a rule placing hud.cmdline at (60, 100), installed before a relog, places it in the new HUD",
    at and (at.x .. "," .. at.y) or "no hud.cmdline after " .. waited .. " s")
  regions["hud.cmdline"] = cmdline
  prompt("Press : and type a few letters: the line shows near the top left. Esc, then press Done.", typing,
    typing)
end

local function rule()
  sheet = hafen.ui():sheet()
  sheet:rule("hud.cmdline"):position(60, 100)
  sheet:install()
  prompt("Log out to the character list and back in; press Done.", relogged, function()
    manualCheck("log out to the character list and back in (skipped)", "hud.cmdline at (60, 100) in the new HUD")
    prompt("Press : and type a few letters: the line shows near the top left. Esc, then press Done.", typing,
      typing)
  end)
end

local function notice()
  local message = regions["hud.message"]
  message:position(60, 60)
  session:console():run("t173nosuch")
  after(0.5, function()
    local at = message:position()
    check(at.x == 60 and at.y == 60, "hud.message held at (60, 60) still reads it while a notice shows there",
      xy(message))
    manualCheck("look at the top left", "\"t173nosuch: no such command\" there")
    message:position(nil)
    rule()
  end)
end

local function heldBelt()
  belt:position(300, 200)
  after(0.5, function()
    local at, cmdline = belt:position(), regions["hud.cmdline"]:position()
    local lowest = bottom(false)
    check(at.x == 300 and at.y == 200 and near(cmdline.y + 20, lowest),
      "the belt held at (300, 200) reads it half a second later, and hud.cmdline stands 20 above the chat or the"
        .. " screen",
      "belt " .. at.x .. "," .. at.y .. ", hud.cmdline " .. cmdline.y .. " for a bottom at " .. lowest)
    manualCheck("look for the belt", "it stands at (300, 200), none at the bottom of the screen")
    prompt("Look for the belt at (300, 200); press Done, then watch the top left at once (3 seconds).", function()
      belt:position(nil)
      notice()
    end, function()
      belt:position(nil)
      notice()
    end)
  end)
end

local function run()
  finished, pass, fail, manual = false, 0, 0, 0
  regions, sheet = {}, nil
  session = hafen.session():current()
  gameui = session and session:ui():match("@GameUI")
  if not gameui then
    check(false, "a character is in the world", "no HUD -- run :t173 logged in")
    return finish()
  end
  belt = session:ui():match("@NKeyBelt") or session:ui():match("@FKeyBelt")
  local single
  regions, single = stand()
  local line, message = regions["hud.cmdline"], regions["hud.message"]
  check(single == #ROLES and belt ~= nil and xy(line) == xy(message),
    "each hud.* role matches one Region on @GameUI, and hud.cmdline and hud.message read one place",
    single .. " of " .. #ROLES .. (line and message and (", " .. xy(line) .. " / " .. xy(message)) or "")
      .. (belt and "" or ", no belt"))
  if single ~= #ROLES or not belt then return finish() end
  local default = beltAtDefault()
  local lowest = bottom(default ~= false)
  local at = line:position()
  check(near(at.y + 20, lowest),
    "unheld, hud.cmdline stands 20 above the belt's default top, or above the chat or the screen's bottom",
    "hud.cmdline " .. at.x .. "," .. at.y .. " for a bottom at " .. lowest .. " (belt at its default: "
      .. tostring(default) .. ")")
  heldBelt()
end

-- The only way in: a suite does not start itself. A console line holds the character's tree, so the run
-- starts on the next step, where no tree is held.
hafen.console():on("t173", function() after(0, run) end)
