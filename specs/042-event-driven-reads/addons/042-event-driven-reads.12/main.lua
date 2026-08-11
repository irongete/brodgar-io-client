-- 042.12 -- world entities whose ground has not arrived. Self-checking suite; see
-- specs/testing/addon-suite.md and specs/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. RenderApi.armPending() -- the per-tick retry of every pending entity's scene
-- add -- is gone, and the e.pending flag with it. addToScene's catch(Loading l) now registers l (the
-- Waitable MCache.LoadingMap throws) with Resolve, which retries the add once the tile streams in,
-- marshalled onto the tick, owned by the addon so :reload/disable cancels a still-pending add.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. There is no hafen.* read that distinguishes "in the scene" from
-- "logically not hidden" -- :visible() reads the explicit hide/show flag, never the pending-streaming
-- detail (by design: an addon should not have to know a prop is still loading). So whether a ghost
-- actually APPEARS once its ground streams in is only checkable by looking at the screen, and a coordinate
-- far enough to guarantee unstreamed ground is exactly what the [manual] line drives. What CAN be
-- automated: :add returns a working handle synchronously whether the ground is loaded or not (never
-- blocks, never throws Loading out to Lua), and a pending entity destroys cleanly with no error.

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

local function run()
  pass, fail, manual = 0, 0, 0

  local here = hafen.player():gob():position()
  local far = here:offset(1000000, 1000000)   -- far enough that the tile has never streamed in

  local ok1, g1 = pcall(function() return hafen.ghost():add("gfx/terobjs/arch/logcabin", here) end)
  check(ok1 and g1 and g1:exists(), "add over LOADED ground returns a live handle synchronously", g1)
  if ok1 then hafen.ghost():remove(g1) end
  check(not ok1 or not g1:exists(), "removing the loaded-ground ghost reports exists() false", ok1 and g1:exists())

  local ok2, g2 = pcall(function() return hafen.ghost():add("gfx/terobjs/arch/logcabin", far) end)
  check(ok2 and g2 ~= nil, "add over UNSTREAMED ground still returns a handle immediately -- no block, no Loading escaping to Lua", ok2 and tostring(g2))

  local ok3
  if ok2 then
    ok3 = pcall(function() hafen.ghost():remove(g2) end)
    check(ok3 and not g2:exists(), "removing a still-PENDING entity leaves it dead, with no error", ok3 and tostring(g2:exists()))
  end

  local ok4, g3 = pcall(function() return hafen.ghost():add("gfx/terobjs/arch/logcabin", far) end)
  if ok4 then hafen.ghost():remove(g3) end
  local ok5 = ok4 and pcall(function() g3:position(here) end)
  check(ok4 and ok5, "a further :position() call on an already-dead pending handle is a safe no-op", tostring(ok5))

  hafen.timer():after(3, function()
    local stillDead2 = (not ok2) or (not g2:exists())
    local stillDead3 = (not ok4) or (not g3:exists())
    check(stillDead2 and stillDead3, "idle: entities destroyed while pending stay dead -- no late re-add slips in behind them",
          ("g2=%s g3=%s"):format(tostring(stillDead2), tostring(stillDead3)))

    manualCheck("run :t042-12-far, then walk toward the placed ghost (east)",
      "the prop is not visible at first and appears exactly once, the moment the ground streams in -- no flicker, no duplicate")
    manualCheck("run :t042-12-far, then IMMEDIATELY :reload before walking there",
      "no client error/crash; the addon layer reloads cleanly and leaves nothing behind (no leaked slot)")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-12", run)   -- the only way in: a suite does not start itself

-- Convenience for the two [manual] lines above: place one ghost far enough east that its ground has
-- (almost certainly) not streamed in yet, so the maintainer only has to run a command and then either
-- walk there or :reload, instead of typing the placement Lua into :lua by hand.
hafen.slash():register("t042-12-far", function()
  local p = hafen.player():gob():position()
  local far = p:offset(2200, 0)   -- ~200 tiles east
  hafen.ghost():add("gfx/terobjs/arch/logcabin", far)
  hafen.log():write("[042-event-driven-reads.12] placed a ghost ~200 tiles east -- walk there to watch it"
    .. " appear once, or run :reload right now to test a pending cancel (re-run :t042-12-far after"
    .. " reload if the ground was already streamed and it appeared immediately -- try a farther offset)")
end)
