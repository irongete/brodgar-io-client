-- R17.4 -- the profiler's frame-sampling half: pf-19's five verbs, the shape each answers while the switch
-- is armed and the answer each gives while it is not. It runs only from :tR17-4 and puts the switch back
-- exactly where it found it, in the same run.

local pass, fail, manual = 0, 0, 0

local function say(s) hafen.log():write(s) end

local function ok(name, cond, got)
  if cond then
    pass = pass + 1
    say("[pass] " .. name)
  else
    fail = fail + 1
    say("[fail] " .. name .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a space and no colon, and a Lua-level error
-- adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both: the class is
-- [^\n] rather than . so a traceback under the message cannot be eaten as a third prefix.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?[^\n]-%.lua:%d+:?[ \t]*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

local function refused(label, fn, needle)
  local good, err = pcall(fn)
  if good then return label .. ": <no error>" end
  local msg = why(err)
  if msg:find(needle, 1, true) ~= nil then return nil end
  return label .. ": " .. msg
end

local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

local function needs(action)
  manual = manual + 1
  say("[manual] " .. action .. ", then type :tR17-4 -- expect: this line becomes a [pass]")
end

local function len(t) return (type(t) == "table") and #t or -1 end

-- A comparison meets a missing key as nil and raises, and a scoring line is not inside a pcall -- so the
-- ordering questions this file asks are asked through this one, which answers false rather than raise.
local function le(a, b) return (type(a) == "number") and (type(b) == "number") and (a <= b) end

-- Every key in `want` present on `t` and of the type named. `t` must be a table to begin with.
local function shaped(t, want)
  if type(t) ~= "table" then return "not a table: " .. tostring(t) end
  for k, ty in pairs(want) do
    if type(t[k]) ~= ty then
      return k .. " is " .. type(t[k]) .. ", not " .. ty
    end
  end
  return nil
end

-- ---- the switch is off: an empty table, never nil, and every one of them read-only -------------------

local ARMED_ONLY = {"frame", "history", "addons", "widgets", "passes", "gl", "overhead"}

local function whileOff(p)
  local bad
  for _, v in ipairs(ARMED_ONLY) do
    local t = read(function() return p[v](p) end)
    if type(t) ~= "table" then
      bad = bad or (v .. "() answered " .. type(t) .. " rather than a table")
    elseif next(t) ~= nil then
      bad = bad or (v .. "() answered a table with something in it while the switch is off")
    end
    if v ~= "history" then     -- history(n) is the one that takes an argument
      bad = bad or refused(v .. "(1)", function() return p[v](p, 1) end, "is read-only")
    end
  end
  ok("with the switch off the seven sampling verbs answer an empty table, and each refuses an argument",
     bad == nil, bad or "seven empty tables")
end

-- ---- the switch is on: the shapes the pages document ------------------------------------------------

local function frame(p)
  local f = read(function() return p:frame() end)
  local bad = shaped(f, {frameno = "number", t = "number", fps = "number", ms = "number",
                         msAvg = "number", msMin = "number", msMax = "number", msP95 = "number",
                         idle = "number", phases = "table", ui = "number", addons = "number"})
  -- An absent key means NOT MEASURED and never zero, which is why the GPU pair is checked as a pair
  -- rather than demanded: it lands several frames after the frame it belongs to.
  local f2 = read(function() return p:frame() end)
  local h = read(function() return p:history(5) end)
  ok("frame() carries the frame the page names, and history(n) is that shape oldest first",
     (bad == nil) and ((f.gpuMs == nil) == (f.gpuFrameno == nil))
       and (type(f2) == "table") and le(f.frameno, f2.frameno)
       and (len(h) >= 1) and (len(h) <= 5) and (type(h[1]) == "table")
       and le(h[1].frameno, h[len(h)].frameno),
     bad or (len(h) .. " frames, at " .. tostring(f.frameno) .. ", gpu " .. tostring(f.gpuMs)))
end

local function addons(p)
  local rows = read(function() return p:addons() end) or {}
  local mine, bad = nil, nil
  for _, r in ipairs(rows) do
    bad = bad or shaped(r, {id = "string", ms = "number", msAvg = "number", msPeak = "number",
                            calls = "table", cost = "table", scopes = "table"})
    if r.id == "R17-the-leftovers.4" then mine = r end
  end
  -- This suite has just spent Lua time on the step, so it is one of the addons the split names -- and
  -- `calls` is the category table the page lists, not a number.
  ok("addons() rows carry the id, the two averages and the call split, this suite among them",
     (bad == nil) and (mine ~= nil) and (type(mine.calls.timers) == "number"),
     bad or (len(rows) .. " addons, mine " .. tostring(mine and mine.ms)))
end

local function widgets(p)
  local w = read(function() return p:widgets() end)
  local bad = shaped(w, {byType = "table", top = "table", total = "table"})
  local row = (bad == nil) and w.byType[1] or nil
  ok("widgets() splits the tree by type and by widget, and the total counts what was measured",
     (bad == nil) and le(0, w.total.count)
       and ((row == nil) or (shaped(row, {type = "string", count = "number", selfMs = "number"}) == nil)),
     bad or (tostring(w.total.count) .. " widgets, " .. len(w.byType) .. " types, "
             .. len(w.top) .. " named"))
end

local NAMED_PASS = {shadow = true, scene = true, ui2d = true}

local function passes(p, tries, done)
  local ps = read(function() return p:passes() end) or {}
  -- The rows are the newest frame whose GL timestamps have come BACK, so they are empty for the first
  -- frames after arming. That is a wait, not an answer: retry over a bounded window and score what came.
  if (len(ps) < 1) and (tries < 12) then
    hafen.timer():after(0.5, function() passes(p, tries + 1, done) end)
    return
  end
  if len(ps) < 1 then
    ok("passes() names the fixed three, CPU and GPU side by side", false,
       "no GL timestamp resolved in 6 s -- the driver may not answer timer queries here")
    done()
    return
  end
  local bad
  for _, r in ipairs(ps) do
    bad = bad or shaped(r, {name = "string", cpuMs = "number", gpuMs = "number"})
    if (bad == nil) and not NAMED_PASS[r.name] then bad = "an unnamed pass: " .. r.name end
  end
  ok("passes() names the fixed three, CPU and GPU side by side",
     (bad == nil) and (len(ps) <= 3), bad or (len(ps) .. " passes, first " .. tostring(ps[1].name)))
  done()
end

local function gl(p)
  local g = read(function() return p:gl() end)
  local bad = shaped(g, {drawCalls = "number", programBinds = "number", vertices = "number",
                         triangles = "number", frameno = "number"})
  ok("gl() counts what the client handed the driver, for the frame it names",
     (bad == nil) and le(0, g.drawCalls) and le(0, g.triangles),
     bad or (tostring(g.drawCalls) .. " draws at frame " .. tostring(g.frameno)))
end

-- ---- the run ----------------------------------------------------------------------------------------

local function finish()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("tR17-4", function()
  pass, fail, manual = 0, 0, 0
  hafen.timer():after(0, function()
    local p = hafen.client():profiling()
    local switch = hafen.client():options():client()
    local was = read(function() return switch:profiling() end)
    if was == nil then
      fail = fail + 1
      say("[fail] the client options answered no profiling switch -- run :tR17-4 again")
      finish()
      return
    end
    -- The switch is the one thing here that outlives the run, so it is read first and written back last.
    if was then
      pass = pass + 1
      say("[pass] the switch was already armed, so the off half is the maintainer's own reading")
    else
      whileOff(p)
    end
    if read(function() return switch:profiling(true) end) == nil then
      needs("grant this suite the client.settings permission, or arm Options > Client > Profiling by hand")
      finish()
      return
    end
    -- Arming is next-frame and the first valid sample is the frame after that, so the reads wait a beat.
    hafen.timer():after(1.0, function()
      frame(p)
      addons(p)
      widgets(p)
      gl(p)
      passes(p, 1, function()
        read(function() return switch:profiling(was) end)
        ok("the switch is back where the run found it",
           read(function() return switch:profiling() end) == was, "left armed")
        finish()
      end)
    end)
  end)
end)
