-- 063.3 -- widget:picture() names the picture a widget shows. Self-checking suite.
--
-- Run :t063-3. Everything happens inside the one call and the whole verdict prints at once: it builds what
-- it needs, reads it, and destroys it again before it returns, so nothing is left on screen.
--
-- What it proves:
--   * the read answers on one of the CLIENT's own widgets -- every window carries a close button this addon
--     did not build, and its resting face is "gfx/hud/wnd/lg/cbtnu". That is the claim that matters: the
--     picture is named because the client showed it, not because an addon told the client which one.
--   * it is a DIFFERENT read from widget:res(), which is untouched -- the same close button carries no res.
--   * nothing is cached: a picture control re-pointed at a second resource names the second one.
--   * a widget that shows no picture answers nil, and so does one that has left the tree.
--   * it takes no argument, and the refusal names the two verbs that DO choose a picture.

local pass, fail, manual = 0, 0, 0

local function log(s)
  hafen.log():write(s)
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function why(err)
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every one of the words asked for.
local function refuses(what, fn, ...)
  local want = { ... }
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for i = 1, #want do
    if not err:find(want[i], 1, true) then said = false end
  end
  check(said, what, err)
end

-- ============================================================================================= the run

local TITLE = "T0633"                      -- this run's window, named so the chain below matches one thing
local CBTN  = "gfx/hud/wnd/lg/cbtnu"       -- the resting face of every window's close button
local CHAIN = "window[title=" .. TITLE .. "] @IButton"
local P1    = "gfx/hud/buttons/addu"       -- two of the client's own pictures, one re-pointed onto the other
local P2    = "gfx/hud/buttons/subu"

-- Every read below goes through this, so a widget that died mid-run reports as a read that did not answer
-- rather than tearing the run down.
local function read(w, verb)
  local ok, v = pcall(function() return w[verb](w) end)
  if not ok then return nil, why(v) end
  return v, nil
end

local function eq(what, w, verb, want)
  local got, err = read(w, verb)
  check(got == want, what .. " (" .. tostring(want) .. ")", err or got)
end

local function run()
  pass, fail, manual = 0, 0, 0

  local win = hafen.ui():window():title(TITLE):size(220, 90):position(180, 180)
  local img = hafen.ui():image():source(P1)
  local lbl = hafen.ui():label():text("no picture here")

  -- ---- one of the client's own widgets, inside a window this addon merely opened ------------------------
  local ok, btn = pcall(function() return hafen.ui():find(CHAIN) end)
  check(ok and (btn ~= nil), "the close button of this window is reachable by chain (" .. CHAIN .. ")",
        ok and "nil -- no @IButton under the window: is its chrome the client's own deco?" or why(btn))
  if ok and btn then
    eq("...and widget:picture() names its resting face -- a picture NO addon chose", btn, "picture", CBTN)
    eq("...while widget:res() on that same button is untouched: two different reads", btn, "res", nil)
  else
    fail = fail + 2
    log("[fail] ...and widget:picture() names its resting face -- got: no button to read")
    log("[fail] ...while widget:res() on that same button is untouched -- got: no button to read")
  end

  -- ---- a picture control: the name comes back, and it follows the picture ------------------------------
  eq("a picture control names the resource it was given", img, "picture", P1)
  eq("...and widget:source() reads back exactly the string it was given", img, "source", P1)
  pcall(function() img:source(P2) end)
  eq("...re-pointed, it names the NEW one: the read caches nothing", img, "picture", P2)

  -- ---- what shows no picture says so -------------------------------------------------------------------
  eq("a label shows no picture", lbl, "picture", nil)
  eq("...and neither does a window: its art belongs to the chrome inside it", win, "picture", nil)

  -- ---- the arity, and the two verbs that DO choose a picture -------------------------------------------
  refuses("widget:picture(v) is refused, naming the two neighbours it is not",
          function() lbl:picture("x") end, "widget:res()", "widget:image(up, down)")

  -- ---- and once it has left the tree -------------------------------------------------------------------
  pcall(function() img:destroy() end)
  eq("a widget that has left the tree answers nil, like every other read", img, "picture", nil)

  pcall(function() lbl:destroy() end)
  pcall(function() win:destroy() end)
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t063-3", run)      -- the only way in: a suite does not start itself
