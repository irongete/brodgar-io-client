-- 039.16 -- the close of the uniform API. Self-checking suite; see specs/addons/TESTING.md.
--
-- Every other suite in this feature proves one section. This one proves the two things none of them
-- can, because both are properties of the WHOLE surface:
--
--   * COMPLETENESS. 33 sections, each callable, each handing back the SAME object every call, each
--     refusing an unknown verb by name -- and every retired spelling in the migration throwing a
--     message that names its replacement. The sweep runs over the TABLE, not over the rows one task
--     happened to touch, because a name that moved with nothing to catch it is a porting error
--     nobody is ever told about: it reads as plain nil and fails one line later saying nothing.
--
--   * THE CATEGORICAL COST CLAIM. A section object is minted once and handed back by identity, so
--     naming one costs nothing -- and a zero is only worth the number beside it that is NOT zero. So
--     every zero here is measured with the same harness that reads a real figure one line later, in
--     the same run, from the same addon. That is the whole discipline: a flag can read zero, and so
--     can a broken meter.
--
-- WHAT IT DELIBERATELY DOES NOT CLAIM. "Frame time before and after" would need the pre-feature
-- client, and two logins are not one scene -- an A/B across two builds measures the world it was
-- taken in. What one client CAN measure is this feature's own per-frame cost: the same widget, on
-- the same scene, with the new grammar in its draw callback and then without it. ':t039-16 cost'
-- reports both numbers rather than asserting a difference away.
--
-- READ-ONLY. It declares no permissions, writes no client setting and stores nothing. The widget the
-- cost round builds is its own and is destroyed before the round closes. The cost round does call
-- p:reset(), which empties the profiler's frame ring -- if you were recording something, read it
-- first.

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---- the surface, as data -------------------------------------------------------------------------
-- The 33 sections that survive the feature (34 before it: hafen.gob folded into hafen.world():gob()).
local SECTIONS = {
  "act", "actionbar", "asset", "buff", "char", "client", "craft", "event", "fight", "font",
  "ghost", "hook", "http", "json", "kin", "log", "map", "menugrid", "meter", "party",
  "player", "quest", "render", "slash", "sound", "speed", "store", "study", "time", "timer",
  "ui", "world", "wound",
}

-- Every retired SECTION spelling: the four section names that changed, and the dotted sub-verbs that
-- became colon calls. A retired name is a field read that throws, so the check is the read itself.
local RETIRED_TOP = { "events", "gob", "quests", "wounds" }

local RETIRED_DOTTED = {
  ["act"]     = { "clickGob", "enabled", "flower", "item", "menu", "moveTo", "place", "raw", "select", "useItemOn" },
  ["char"]    = { "attr", "attrs", "credos", "experiences", "food", "lp", "skill", "skills", "skillsAvailable", "weight" },
  ["client"]  = { "options", "profiling" },
  ["craft"]   = { "current", "make" },
  ["fight"]   = { "deck", "maneuvers", "summary" },
  ["ghost"]   = { "list", "new" },
  ["hook"]    = { "action", "grab", "input", "message" },
  ["http"]    = { "get", "post" },
  ["json"]    = { "encode", "parse" },
  ["map"]     = { "grid", "icons", "markers", "overlay", "overlays", "segment", "segments" },
  ["party"]   = { "leader", "member", "members" },
  ["render"]  = { "object", "sprite" },
  ["slash"]   = { "register" },
  ["speed"]   = { "get", "max", "name", "set" },
  ["store"]   = { "flush" },
  ["study"]   = { "slots", "summary" },
  ["time"]    = { "clock", "dayFraction", "isNight", "moon", "season", "yearFraction" },
  ["timer"]   = { "after", "every" },
  ["ui"]      = { "all", "at", "equipment", "hand", "inventory", "mouse", "node", "on", "overlay", "skin", "widget", "window" },
  ["world"]   = { "count", "fromGridPos", "gobs", "grid", "gridPos", "height", "nearest", "placeAngle",
                  "placeGrid", "screenToWorld", "snapAngle", "snapPlace", "tile", "tileToGrid",
                  "tileToWorld", "within", "worldToTile" },
}

-- The retired ENTITY spellings, with the door each one is reached through. A resolver that answers nil
-- means the state is not available in this run (no world, an empty inventory), and the round says how
-- many of the 47 it reached rather than pretending it swept them all. The sprite / object / ghost rows
-- want an asset and a place, so they stay with 039.8's suite, which builds all three.
local RETIRED_ENTITY = {
  { "gob",         { "isplayer", "overlays", "pos" },
    function() return hafen.world():gob():get(1) end },                     -- :get is never nil
  { "widget",      { "hide", "pos", "rootpos", "show", "skin" },
    function() return hafen.ui():root() end },
  { "keybindings", { "get", "set" },
    function() return hafen.client():options():keybindings() end },
  { "kin",         { "endkin", "setGroup" },
    function() return hafen.kin():get(0) end },                             -- by number, never nil
  { "slot",        { "set" },
    function() return hafen.actionbar():get(0) end },
  { "uioverlay",   { "remove" },
    function() return hafen.ui():overlay() end },                           -- destroyed below
  { "grid",        { "mtime", "overlays", "pos", "sc" },
    function()
      local me = hafen.player():gob()
      return me and me:exists() and hafen.world():grid():at(me:position()) or nil
    end },
  { "segment",     { "grids" },
    function() return hafen.map():segment():current() end },
  { "marker",      { "anchor", "dist", "onmap", "pos", "tc" },
    function() return hafen.map():marker():list()[1] end },
  { "pagina",      { "isnew" },
    function() return hafen.menugrid():list()[1] end },
  { "item",        { "pos", "slot" },
    function()
      local inv = hafen.ui():inventory()
      return inv and inv:items()[1] or nil
    end },
  { "overlay",     { "clickable", "move", "onClick", "pos" },
    function()
      local me = hafen.player():gob()
      return me and me:exists() and me:overlay():add("t039-16") or nil      -- removed below
    end },
}
local ENTITY_ROWS = 47   -- every entity row in the migration, reachable here or not

-- ---- allocation, measured the way 039.2 measured a Position -----------------------------------------
-- memory() is pull-only, so it answers whether or not the profiler is armed. A collection mid-measure
-- makes the delta meaningless rather than wrong, so it is retried and reported honestly if it never
-- settles. Unlike 039.2's, this one keeps a delta of ZERO: that is the answer it exists to read.
local function perCall(fn, n)
  for _ = 1, 4 do
    local m0 = hafen.client():profiling():memory()
    for _ = 1, n do fn() end
    local m1 = hafen.client():profiling():memory()
    if (m1.gcCount == m0.gcCount) and (m1.heapUsed >= m0.heapUsed) then
      return (m1.heapUsed - m0.heapUsed) / n
    end
  end
  return nil
end

local function median(t)
  table.sort(t)
  local n = #t
  if n == 0 then return nil end
  if (n % 2) == 1 then return t[(n + 1) / 2] end
  return (t[n / 2] + t[n / 2 + 1]) / 2
end

-- ---- the completeness sweep -------------------------------------------------------------------------
local function sweepSections()
  local same, bad = 0, nil
  for _, name in ipairs(SECTIONS) do
    local t = hafen[name]
    local ok, a = pcall(function() return t() end)
    local _, b = pcall(function() return t() end)
    if (type(t) ~= "table") or (not ok) then
      bad = bad or ("hafen." .. name .. " is not a callable table: " .. tostring(a))
    elseif (a == nil) or (a ~= b) then
      bad = bad or ("hafen." .. name .. "() hands back a DIFFERENT object each call")
    else
      same = same + 1
    end
  end
  check((same == #SECTIONS) and (bad == nil),
        ("all %d sections are callable and hand back the SAME object every call (%d/%d)")
          :format(#SECTIONS, same, #SECTIONS), bad)

  -- An unknown verb throws NAMING the section, everywhere. A section that pointed __index straight at
  -- its methods table would read nil here and fail one call later as "attempt to call a nil value" --
  -- which is the defect this feature found three separate times.
  local named, miss = 0, nil
  for _, name in ipairs(SECTIONS) do
    local ok, err = pcall(function() return hafen[name]().noSuchVerbHere end)
    if (not ok) and (tostring(err):find("has no verb", 1, true) ~= nil) then
      named = named + 1
    else
      miss = miss or ("hafen." .. name .. "() -> " .. (ok and "<no error>" or tostring(err)))
    end
  end
  check(named == #SECTIONS,
        ("an unknown verb on a section object throws naming the section (%d/%d)")
          :format(named, #SECTIONS), miss)
end

-- A retired spelling must throw, and the message must name a replacement in the NEW grammar -- which
-- at the section tier is always a called section, so "():" is the mechanical test. A message that only
-- repeated the old name would be a diagnosis where a fix is wanted.
local function sweepRetiredSections()
  local seen, rows, bad = 0, 0, nil
  local function one(what, fn)
    rows = rows + 1
    local ok, err = pcall(fn)
    if (not ok) and (tostring(err):find("():", 1, true) ~= nil) then
      seen = seen + 1
    else
      bad = bad or (what .. " -> " .. (ok and "<no error, it reads as plain nil>" or tostring(err)))
    end
  end
  for _, name in ipairs(RETIRED_TOP) do
    one("hafen." .. name, function() return hafen[name] end)
  end
  for section, verbs in pairs(RETIRED_DOTTED) do
    for _, verb in ipairs(verbs) do
      one("hafen." .. section .. "." .. verb, function() return hafen[section][verb] end)
    end
  end
  check((seen == rows) and (bad == nil),
        ("every retired section spelling throws naming a replacement in the new grammar (%d/%d)")
          :format(seen, rows), bad)
end

local function sweepRetiredEntities()
  local seen, reached, absent, bad = 0, 0, {}, nil
  for _, row in ipairs(RETIRED_ENTITY) do
    local entity, verbs, door = row[1], row[2], row[3]
    local ok, obj = pcall(door)
    if (not ok) or (obj == nil) then
      absent[#absent + 1] = entity
    else
      for _, verb in ipairs(verbs) do
        reached = reached + 1
        local fine, err = pcall(function() return obj[verb] end)
        -- An entity's replacement is a colon verb on the entity, so the message names a call.
        if (not fine) and (tostring(err):find("(", 1, true) ~= nil) then
          seen = seen + 1
        else
          bad = bad or (entity .. ":" .. verb .. " -> "
                          .. (fine and "<no error, it reads as plain nil>" or tostring(err)))
        end
      end
      if entity == "uioverlay" then obj:destroy() end
      if entity == "overlay" then hafen.player():gob():overlay():remove("t039-16") end
    end
  end
  local note = (#absent == 0) and "every door answered"
                 or ("not reachable this run: " .. table.concat(absent, " "))
  check((seen == reached) and (bad == nil),
        ("every retired entity spelling this run could reach throws naming its replacement (%d of %d rows; %s)")
          :format(seen, ENTITY_ROWS, note), bad)
end

-- ---- the categorical zero, with the number that is not zero beside it -------------------------------
local function sweepCost()
  local N = 20000
  local sectionB = perCall(function() return hafen.world() end, N)
  local valueB = perCall(function() return hafen.world():position(100, 100) end, N)
  if sectionB and valueB and (valueB > 0) then
    check(sectionB < 1,
          ("naming a section allocates %.2f bytes a call, against %.0f for the call beside it that mints"
            .. " a value -- so the zero is the shape, not the meter"):format(sectionB, valueB),
          ("%.2f vs %.0f"):format(sectionB, valueB))
  elseif sectionB and valueB then
    check(false, "naming a section allocates nothing, measured against a call that allocates",
          ("the falsifier read %.2f bytes a call, so a zero beside it would prove nothing"):format(valueB))
  else
    manualCheck("run ':t039-16' again",
                "the heap moved under a collection while the allocation was being measured, so the"
                  .. " numbers would have been noise; a second run usually settles")
  end

  -- What a Position costs against the {x, y} table it replaced -- the feature's one new type, in the
  -- verb that runs per frame. Reported, not assumed.
  local me = hafen.player():gob()
  if me and me:exists() then
    local posB = perCall(function() return me:position() end, N)
    local tabB = perCall(function() local q = me:position(); return { x = q:x(), y = q:y() } end, N)
    if posB and tabB and (posB > 0) and (tabB > 0) then
      check(posB <= tabB,
            ("gob:position() allocates %.0f bytes a call, against %.0f for the same read plus the"
              .. " {x, y} table it replaced"):format(posB, tabB), ("%.0f vs %.0f"):format(posB, tabB))
    else
      manualCheck("run ':t039-16' again",
                  "the heap moved while gob:position() was being measured; a second run usually settles")
    end
  else
    manualCheck("log in and run ':t039-16'",
                "gob:position() measured against the {x, y} table it replaced -- it needs a player")
  end
end

-- ---- the cost round: the draw callback, and the frame it runs in ------------------------------------
-- Staged, because a per-frame figure needs frames, and it BUILDS the scene it measures (a widget of its
-- own, two states of one callback) rather than reading whatever happened to be on screen.
local function myRow()
  for _, r in ipairs(hafen.client():profiling():addons()) do
    if r.id == "039-uniform-api.16" then return r end
  end
  return nil
end

local function frameMedian(n)
  local ms = {}
  for _, f in ipairs(hafen.client():profiling():history(n)) do ms[#ms + 1] = f.ms end
  return median(ms), #ms
end

local function costRound()
  pass, fail, manual = 0, 0, 0
  local p = hafen.client():profiling()
  if p:frame().frameno == nil then
    manualCheck("arm profiling (Options > Client > Profiling, or ':profiler on') and run ':t039-16 cost'",
                "the draw-callback cost and the frame-time medians; the frame sampling answers empty"
                  .. " while the switch is off, so this round has nothing to read")
    return summary()
  end

  local w = hafen.ui():widget():position(2, 2):size(1, 1)   -- its own scene, built by the check
  local cheap = function() local _ = hafen.world() end                  -- names a section, per frame
  local work = function()                                               -- the other state of one addon
    for i = 1, 200 do local _ = hafen.world():position(i, i) end
  end
  w:onDraw(cheap)
  p:reset()

  local deadline = hafen.timer():after(6.0, function()
    if w:exists() then w:destroy() end
    check(false, "the cost round finished", "it never reached its last stage -- the client stopped"
            .. " drawing, or a stage errored; nothing below this line ran")
    summary()
  end)

  hafen.timer():after(1.0, function()
    local r, cheapMs, drawCalls = myRow(), nil, nil
    local cheapFrame = frameMedian(15)
    if r then cheapMs, drawCalls = r.msAvg, r.calls and r.calls.draw end

    w:onDraw(work)
    p:reset()
    hafen.timer():after(1.0, function()
      local r2 = myRow()
      local workMs = r2 and r2.msAvg
      if cheapMs and workMs and drawCalls then
        -- The scene is built: the callback IS running this frame, so a small number is a measurement
        -- rather than a callback that never fired.
        check((drawCalls >= 1) and (workMs > cheapMs) and (cheapMs < 0.05),
              ("a draw callback naming a section costs %.4f ms a frame, against %.4f ms for the same"
                .. " widget doing work (draw calls this frame: %d)"):format(cheapMs, workMs, drawCalls),
              ("%.4f vs %.4f, draw calls %s"):format(cheapMs, workMs, tostring(drawCalls)))
      else
        check(false, "the addon's own profiling row was found in both states",
              "no row for 039-uniform-api.16 -- profiling was armed but nothing was attributed")
      end

      w:destroy()
      p:reset()
      hafen.timer():after(1.0, function()
        deadline:cancel()
        local bare, n = frameMedian(15)
        local with = cheapFrame
        if with and bare and (n >= 15) then
          -- Indistinguishable, with the tolerance stated rather than hidden: frame time jitters, so a
          -- fixed millisecond bound would be a claim about the framerate rather than about the code.
          local slack = math.max(0.5, 0.15 * math.max(with, bare))
          check(math.abs(with - bare) <= slack,
                ("median frame time over 15 frames: %.2f ms with the callback on the scene, %.2f ms"
                  .. " with it gone (within %.2f)"):format(with, bare, slack),
                ("%.2f vs %.2f, over the %.2f slack"):format(with, bare, slack))
        else
          check(false, "15 frames were sampled in each state",
                ("only %d frames in the ring -- the client was not drawing"):format(n or 0))
        end
        summary()
      end)
    end)
  end)
end

-- ---- the main run -----------------------------------------------------------------------------------
local function run(args)
  if args and (args[1] == "cost") then return costRound() end
  pass, fail, manual = 0, 0, 0

  sweepSections()
  sweepRetiredSections()
  sweepRetiredEntities()
  sweepCost()

  manualCheck("arm profiling and run ':t039-16 cost'",
              "3 lines, staged over ~3s: the draw callback naming a section costs about nothing a frame"
                .. " while the same widget doing work costs measurably more, and the two frame-time"
                .. " medians agree")
  summary()
end

hafen.slash():register("t039-16", run)
