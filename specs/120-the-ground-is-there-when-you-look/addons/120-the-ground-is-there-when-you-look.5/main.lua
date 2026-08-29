-- 120.5 -- the pages. Self-checking suite.
--
-- What this proves is not a behaviour but a CONTRACT. The two pages this feature writes each carry an
-- example, and an example is the one part of a page a program can hold to the client: it is copied in
-- here character for character, inside a pcall, so a page whose example does not run fails on this line
-- rather than in a reader's own addon months from now. Everything after each block is a claim the page
-- makes in prose beside that example, asserted against the client that has to make it true.
--
-- It runs to the end on its own, in one tick, and asks nothing of the camera or the ground: what is
-- under test is what the pages SAY, and every sentence of that is answerable from the settings and the
-- counters alone. The examples write settings, so what they wrote is put back at the end.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function run()
  local c = hafen.client():options():client()
  local wason, wasrange, wasgrey, wasprof = c:recall(), c:recallRange(), c:recallGrey(), c:profiling()

  ---------------------------------------------------------------------- client(): the profiling example
  local ok1, err1 = pcall(function()
    -- docs/addons/api/client/README.md, under `client()` -- verbatim.
    local c = hafen.client():options():client()

    if not c:profiling() then c:profiling(true) end       -- the write needs client.settings
  end)
  check(ok1, "the client() example runs exactly as its page writes it", err1)
  eq("...and leaves the switch armed, which is the whole of what it does", c:profiling(), true)

  ------------------------------------------------------------------- counters: the render() example
  -- Disarmed first, because the page's claim about this whole table is that it needs nothing armed:
  -- running the example with the profiler ON would prove the reading and not the sentence.
  c:profiling(false)
  eq("the counters are read with the profiler disarmed, as their page says they may be",
     c:profiling(), false)

  local ok2, err2 = pcall(function()
    -- docs/addons/api/client/profiling/counters.md, under `render()` -- verbatim.
    local r = hafen.client():profiling():render()
    hafen.log():write(string.format("%d overlay pieces laid, %d of them outlines",
                            r.overlayMeshes, r.overlayOutlines))
    if r.drawSlots then
      hafen.log():write(string.format("%d slots, %d batches, %.1f MB textures",
                              r.drawSlots, r.batches, r.vram.textures.bytes / 1048576))
    end
  end)
  check(ok2, "the render() example runs exactly as its page writes it", err2)

  local r = hafen.client():profiling():render()
  check((type(r.overlayMeshes) == "number") and (type(r.overlayOutlines) == "number"),
        "the two tallies its first line prints answer numbers, in the scene or out of it",
        tostring(r.overlayMeshes) .. "/" .. tostring(r.overlayOutlines))
  -- The example's own guard is the page's claim: everything in that second line describes the SCENE, so
  -- either drawSlots is there and the rest of them are, or none of them is and the line does not run.
  if r.drawSlots == nil then
    check(true, "drawSlots is absent, so the example's guard holds its second line back", "no scene")
  else
    check((type(r.batches) == "number") and (r.vram ~= nil) and (r.vram.textures ~= nil)
          and (type(r.vram.textures.bytes) == "number"),
          "drawSlots is there, and so is everything its second line reads through the guard",
          tostring(r.batches) .. " batches, vram " .. tostring(r.vram))
  end
  check((type(r.recallGridsHeld) == "number") and (type(r.recallGridsRead) == "number")
        and (type(r.recallCutsDrawn) == "number") and (type(r.recallCutsWanted) == "number"),
        "and the four recall keys its table names answer numbers, the profiler still disarmed",
        tostring(r.recallGridsHeld) .. " held, " .. tostring(r.recallGridsRead) .. " read, "
        .. tostring(r.recallCutsDrawn) .. " of " .. tostring(r.recallCutsWanted) .. " cuts")
  refuses("a counter group takes no argument, as its page says of every one of them",
          function() hafen.client():profiling():render(1) end, "takes no argument")

  ------------------------------------------------------------- client(): the Remembered ground example
  local ok3, err3 = pcall(function()
    -- docs/addons/api/client/README.md, under `client()` > Remembered ground -- verbatim.
    local c = hafen.client():options():client()

    c:recall(true):recallRange(4):recallGrey(true)     -- the write needs client.settings
    hafen.log():write("remembered ground reaches " .. c:recallRange() .. " grids")
  end)
  check(ok3, "the Remembered ground example runs exactly as its page writes it", err3)
  -- What it printed, rebuilt from the page's own expression: the example writes to the log and the log
  -- cannot be read back, so the assertion is on the line that expression makes.
  eq("...and the line it writes is what its page's own expression says",
     "remembered ground reaches " .. c:recallRange() .. " grids", "remembered ground reaches 4 grids")
  eq("the chain reached its first write", c:recall(), true)
  eq("the chain reached its last", c:recallGrey(), true)
  -- Why the chain is legal at all, which the page states above the example: a write hands the handle
  -- back, and the handle is the same one every time it is asked for.
  check(c:recallRange(4) == c, "a write hands back the very handle it was called on", c:recallRange(4))

  ----------------------------------------------------------- what the page says a bad write does here
  refuses("an explicit nil is refused, as the page says of every option",
          function() c:recallRange(nil) end, "must not be nil")
  refuses("a numeric string is refused, as the page says a number option refuses one",
          function() c:recallRange("4") end, "must be a number")
  refuses("a range outside the bounds is refused naming them, exactly as written on the page",
          function() c:recallRange(9) end, "from 1 to 8")
  refuses("a range that is not a whole number of grids is refused the same way",
          function() c:recallRange(2.5) end, "whole number of grids")
  eq("and a refused write leaves the range standing where the page says it stands", c:recallRange(), 4)

  -- Everything the examples wrote, put back.
  c:recall(wason):recallRange(wasrange):recallGrey(wasgrey):profiling(wasprof)
  check((c:recall() == wason) and (c:recallRange() == wasrange) and (c:recallGrey() == wasgrey)
        and (c:profiling() == wasprof),
        "the settings the examples wrote are back where the run found them",
        tostring(c:recall()) .. "/" .. tostring(c:recallRange()) .. "/" .. tostring(c:recallGrey())
        .. "/" .. tostring(c:profiling()))

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t120", run)   -- the only way in: a suite does not start itself
