-- 119.5 -- the pages. Self-checking suite.
--
-- This task ships prose, and the one part of prose a program can put to the test is the example: a page
-- whose block does not run, or runs and says something the page does not, is the defect this catches. So
-- the `render()` example from the counters page is pasted in below VERBATIM and run, with this addon's own
-- `hafen.log` shadowed for exactly that call so the line it writes can be read back rather than merely
-- believed. The shadow is a field of the `hafen` table this addon was handed, it is put back on the next
-- line, and nothing outside this addon can see it.
--
-- The rest is the two claims that page's new paragraph makes about the counters, both of them numbers this
-- suite reads back: laying a patch moves `overlayMeshes` and leaves `overlayOutlines` where it was, because
-- a patch is drawn without an outline; and taking one up moves neither, since nothing is built to stop
-- drawing something. Both are duplicated here on purpose -- this suite is the whole verification of this
-- task and assumes no other has ever been run.
--
-- The claims the same task makes about the ENGINE MAP (`docs/client/world-3d.md`) are about upstream
-- classes no Lua call can reach; those are verified by reading the source they map, not from here.
--
-- Nothing here is protected: reading the counters and laying a patch are unprotected verbs, so this suite
-- declares no permission keys and every protected verb must still refuse it.

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

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- ---- the counters page's `render()` example, exactly as the page prints it ---------------------------
local function example()
local r = hafen.client():profiling():render()
hafen.log():write(string.format("%d overlay pieces laid, %d of them outlines",
                        r.overlayMeshes, r.overlayOutlines))
if r.drawSlots then
  hafen.log():write(string.format("%d slots, %d batches, %.1f MB textures",
                          r.drawSlots, r.batches, r.vram.textures.bytes / 1048576))
end
end

-- Run it with the writing end held, so what it printed is a value and not a hope.
local function runExample()
  local printed = {}
  local sink = {}
  sink.write = function(self, msg) printed[#printed + 1] = tostring(msg); return self end
  local real = hafen.log
  hafen.log = function() return sink end
  local ok, err = pcall(example)
  hafen.log = real
  return ok, err, printed
end

local prof, patches, me, start, patch
local restless = false   -- did a settle window run out with the counters still moving?

local function read()
  local r = prof:render()
  return r.overlayMeshes, r.overlayOutlines
end

local function square(centre, r)
  return { centre:offset(-r, -r), centre:offset(r, -r), centre:offset(r, r), centre:offset(-r, r) }
end

-- Wait until both counters stop moving -- three equal readings a quarter-second apart -- then hand back
-- what they settled at. Ground still streaming in builds cuts of its own, and every overlay over a cut
-- whose ground mesh was just rebuilt is re-laid with it; there is nothing to compare until that has
-- stopped. A window that runs out with a number still climbing is recorded rather than used.
local function settle(done)
  local lm, lo, stable, tries = -1, -1, 0, 0
  local tm
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    local m, o = read()
    if (m == lm) and (o == lo) then stable = stable + 1 else stable = 0 end
    lm, lo = m, o
    if (stable >= 2) or (tries >= 40) then
      tm:cancel()
      if stable < 2 then restless = true end
      done(m, o)
    end
  end)
end

local function later(n, done)
  local tries = 0
  local tm
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    if tries >= n then tm:cancel(); local m, o = read(); done(m, o) end
  end)
end

local function note()
  return restless and " (the counters never went quiet -- was the scene loading?)" or ""
end

local base_m, base_o, up_m, up_o
local step2, step3, step4

-- the quiet baseline, with nothing of ours down
function step2(m, o)
  base_m, base_o = m, o
  patch = patches:add(square(start, 6), start)
  settle(step3)
end

-- ...and what one patch under the character cost
function step3(m, o)
  check(m > base_m, "laying one patch built cut overlay meshes",
        "overlayMeshes " .. base_m .. " -> " .. m .. note())
  check(o == base_o, "...and no outline mesh at all, since a patch is drawn without one",
        "overlayOutlines " .. base_o .. " -> " .. o .. note())
  check(patch:drawn(), "the patch it laid is on the terrain being drawn", patch:drawn())
  up_m, up_o = read()
  patches:remove(patch)
  later(12, step4)
end

-- ...and taking it up built nothing
function step4(m, o)
  check((m == up_m) and (o == up_o), "taking one up built nothing over the next three seconds",
        up_m .. "/" .. up_o .. " -> " .. m .. "/" .. o .. note())
  local moved = start:distance(me:position())
  check((moved ~= nil) and (moved < 1.0), "the character stood still through every reading", moved)
  summary()
end

local function run()
  prof = hafen.client():profiling()
  local m, o = read()
  check((type(m) == "number") and (type(o) == "number"),
        "render() answers overlayMeshes and overlayOutlines as numbers",
        tostring(m) .. "/" .. tostring(o))

  local ok, err, printed = runExample()
  check(ok, "the counters page's render() example runs as written", err)
  local line = printed[1] or ""
  check(line:match("^%d+ overlay pieces laid, %d+ of them outlines$") ~= nil,
        "...and prints both counters: \"" .. line .. "\"",
        (line == "") and "it wrote nothing" or line)

  local s = hafen.session():current()
  me = s and s:player():gob()
  start = me and me:position()
  if not start then
    check(false, "a character in the world to lay a patch under", "no session, no player gob, or no place")
    summary()
    return
  end
  patches = hafen.virtual():patch()
  for _, old in ipairs(patches:list()) do patches:remove(old) end   -- the suite owns its own patches
  restless = false
  hafen.log():write("[note] laying one patch and taking it up, reading both counters around each"
                      .. " -- stand still for ~15s")
  settle(step2)
end

hafen.console():on("t119", run)   -- the only way in: a suite does not start itself
