-- 087.1 -- three endings take the teardown vocabulary's own word. Self-checking suite.
--
-- A rule and a sheet are LAYERS this addon took over what the client draws, so they end with
-- :release(); a loaded file is a MEMBER of hafen.asset(), so the collection that owns it is what
-- destroys it. The whole of a rename is that the new spelling ends what the old one ended AND the old
-- one raises naming it -- both halves are checked here, for all three.
--
-- The asset move is not a rename: the ending left the handle's own vocabulary. So the collection has to
-- refuse everything it does not hold, and each refusal has to say WHICH kind of not-holding it is.

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

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why. Answers whether it did, and what it
-- actually said, so a wrong message reads as clearly as a missing one.
local function refuses(fn, want)
  local err = said(fn)
  if err == nil then return false, "<no error>" end
  return err:find(want, 1, true) ~= nil, err
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn)
  local ok, r = pcall(fn)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

local PROBE = "087.1 probe"
local RED = {200, 210, 220}

local function probeWindow()
  return hafen.ui():window():title(PROBE):size(60, 30)
end

-- ---- the layer a widget carries ---------------------------------------------------------------------
--
-- widget:rule() is this addon's own level of the cascade -- a layer taken over one widget -- so ending it
-- is :release(), and the proof is on both sides of the handle: the level this addon wrote is gone
-- (:info() is nil) AND the widget resolves to what the client says again.

local function ruleSection()
  local w = probeWindow()
  local n, bad = 0, nil

  w:rule():color(RED)
  local before = w:style()
  if before and before.color and (before.color.r == 200) then
    n = n + 1
  else
    bad = "the level did not take: " .. tostring(before and before.color and before.color.r)
  end

  w:rule():release()
  if w:rule():info() == nil then
    n = n + 1
  else
    bad = ":info() after :release() is not nil"
  end

  local after = w:style()
  if (after == nil) or (after.color == nil) or (after.color.r ~= 200) then
    n = n + 1
  else
    bad = ":style() still resolves to ours: " .. tostring(after.color.r)
  end

  w:destroy()
  check(n == 3, "w:rule():release() gives the layer back and the widget resolves to the client's own ("
        .. n .. "/3)", bad)
end

-- ---- the sheet the layers belong to -----------------------------------------------------------------
--
-- The same act one level up. :info().installed is the one truth about whether a sheet is applied -- it is
-- derived rather than stored -- so it is what the release has to move.

local function sheetSection()
  local s = hafen.ui():sheet()
  s:rule("tooltip"):color(RED)          -- a site nothing is drawing right now: installed and released in one call
  s:install()
  local on = s:info().installed
  s:release()
  local off = s:info().installed
  s:rule("tooltip"):release()           -- a sheet rule releases too, and leaves the document bare
  check((on == true) and (off == false),
        "hafen.ui():sheet():release() drops the sheet (installed true -> false)",
        tostring(on) .. " -> " .. tostring(off))
end

-- ---- the file the collection owns -------------------------------------------------------------------
--
-- The move that is not a rename: freeing a loaded file is the collection's verb now, so the member count
-- is what answers for it, and the collection comes back so removals chain.

local function assetSection()
  local n0 = hafen.asset():count()
  local img = hafen.asset():get("dot.png")
  local n1 = hafen.asset():count()
  local back = hafen.asset():remove(img)
  local n2 = hafen.asset():count()
  check((n1 == n0 + 1) and (n2 == n0) and (back == hafen.asset()),
        "hafen.asset():remove(a) frees the image and the count falls by one",
        n0 .. " -> " .. n1 .. " -> " .. n2 .. ", back=" .. tostring(back))
end

-- ---- the three spellings that left --------------------------------------------------------------------

local function retiredSection()
  local w = probeWindow()
  local img = hafen.asset():get("dot.png")
  local n, bad = 0, nil

  for _, t in ipairs({
    {"rule:remove()", function() w:rule():remove() end, "rule:release()"},
    {"sheet:drop()", function() hafen.ui():sheet():drop() end, "sheet:release()"},
    {"img:dispose()", function() img:dispose() end, "hafen.asset():remove(a)"},
  }) do
    local ok, err = refuses(t[2], t[3])
    if ok then n = n + 1 else bad = t[1] .. " said: " .. err end
  end

  hafen.asset():remove(img)
  w:destroy()
  check(n == 3, "rule:remove(), sheet:drop() and img:dispose() each raise naming their replacement ("
        .. n .. "/3)", bad)
end

-- ---- what the collection will not take ----------------------------------------------------------------
--
-- The guarantee the move turned into a refusal. A path is the mistake the rest of the API already refuses
-- (you pass the handle, always); a built-in face and a derived variant were never files at all, so this
-- collection has never held one.

local function refusalSection()
  local ok, err = refuses(function() hafen.asset():remove("dot.png") end, "pass the HANDLE, not a path")
  check(ok, "hafen.asset():remove(path) refuses, naming the handle", err)

  local n, bad = 0, nil
  for _, t in ipairs({
    {"a built-in face", function() hafen.asset():remove(hafen.font():get("sans")) end,
     "never loaded from a file"},
    {"a derived variant", function() hafen.asset():remove(hafen.font():get("sans"):derive():size(11)) end,
     "never loaded from a file"},
    {"a table", function() hafen.asset():remove({}) end, "expected an asset handle"},
  }) do
    local good, said2 = refuses(t[2], t[3])
    if good then n = n + 1 else bad = t[1] .. " said: " .. said2 end
  end
  check(n == 3, "hafen.asset():remove() refuses what this collection has never held (" .. n .. "/3)", bad)
end

-- ---- the one image handle that keeps its own ending ---------------------------------------------------
--
-- A minimap drawing wears the same handle shape and the same asset verbs, but it is the member of no
-- collection -- grid:image(lvl) hands it back and img:dispose() frees it. The first ask starts an
-- off-frame render and answers nil, so this asks on a timer for a bounded window and scores what it
-- reached: nothing in the API can cause a grid to finish drawing.

local function drawing()
  local ok, img = pcall(function()
    local g = hafen.session():current():player():gob()
    local grid = hafen.map():grid():get(g:position():info().gridId)
    return grid and grid:image(0)
  end)
  return ok and img or nil
end

local function drawingCheck(img)
  if img == nil then
    check(true, "hafen.asset():remove(mapDrawing) refuses: it is the member of no collection"
          .. " (0/1 reached -- no minimap drawing rendered inside the window)")
    return
  end
  local ok, err = refuses(function() hafen.asset():remove(img) end, "a map drawing is not one of this addon's")
  check(ok, "hafen.asset():remove(mapDrawing) refuses: it is the member of no collection, and"
        .. " img:dispose() is its ending (1/1 reached)", err)
end

local function run()
  pass, fail, manual = 0, 0, 0

  section("rule", ruleSection)
  section("sheet", sheetSection)
  section("asset", assetSection)
  section("retired", retiredSection)
  section("refusal", refusalSection)

  local tries = 0
  local function poll()
    local img = drawing()
    if (img ~= nil) or (tries >= 12) then
      section("drawing", function() drawingCheck(img) end)
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
      return
    end
    tries = tries + 1
    hafen.timer():after(0.25, poll)
  end
  poll()
end

hafen.slash():on("t087-1", run)               -- the only way in: a suite does not start itself
