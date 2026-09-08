-- R03 -- Args refuses what is not a number.
--
-- One mechanism: Args.num asks the VALUE as well as the type (a number here is finite), Args.integer asks
-- the other half (an index, an id, a count or a design pixel is a whole number in range), and Args.optnum is
-- the optional twin. Every door in the bridge calls one of the three -- the five hand-rolled helpers, the
-- raw todouble() draw verbs, the inline type()+toint() parsers and every :get(id) -- so what this asserts is
-- one rule seen from a dozen call sites: nan, inf, 2.7 and 1e10 are refused where each of them used to be
-- silently taken as 0, as 2 or as 1410065408. Each check pcalls the bad calls and reads their messages back.
--
-- The last check is the draw verbs, and it is the one that cannot be answered in the same breath: a painter
-- runs on the NEXT frame, on the render thread. So the probe is installed, scored on a short timer over a
-- bounded window, and the whole block is printed from there -- one command, one paste, nothing manual.
--
-- Run it with :tR03. It starts nothing by itself, and every call it makes is a REFUSAL, so nothing it
-- touches is left changed -- which the read-backs below assert rather than assume.

local out, pass, fail, manual = {}, 0, 0, 0

local function line(s) out[#out + 1] = s end

local function ok(desc, cond, got)
  if cond then
    pass = pass + 1
    line("[pass] " .. desc)
  else
    fail = fail + 1
    line("[fail] " .. desc .. " -- got: " .. tostring(got))
  end
end

-- LuaJ prefixes a bridge refusal with "@chunk.lua:<n>" and a SPACE, and a Lua error with "chunk.lua:<n>:".
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Every call in `fns` must raise, and every message must carry `needle`. Answers true, or false and the
-- first thing that went wrong -- so a check that has something to READ BACK as well is still one line.
local function refused(needle, fns)
  for i = 1, #fns do
    local fine, err = pcall(fns[i])
    if fine then return false, "call " .. i .. " raised nothing" end
    local msg = why(err)
    if not msg:find(needle, 1, true) then return false, "call " .. i .. " said " .. msg end
  end
  return true
end

local function named(sp) return sp and sp:name() or "<none>" end

local function report()
  for _, l in ipairs(out) do hafen.log():write(l) end
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local NAN, INF = 0 / 0, math.huge

-- ---------------------------------------------------------------- the checks

local function run()
  out, pass, fail, manual = {}, 0, 0, 0
  local s = hafen.session():current()
  if not s then
    hafen.log():write("[fail] the suite needs a character in the world -- got: no current session")
    hafen.log():write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end
  local noop, r, got = function() end

  -- 1. The gate itself, on the verb the block's root row names: a delay that is not finite is a timer that
  --    never fires and never dies, because clock >= clock + nan is false forever.
  r, got = refused("must be a finite number",
                   {function() hafen.timer():after(NAN, noop) end,
                    function() hafen.timer():after(INF, noop) end,
                    function() hafen.timer():every(NAN, noop) end})
  ok("a timer delay must be a finite number, nan and inf alike", r, got)

  -- 2. Every :get(key) that used to truncate a fraction into an address.
  r, got = refused("must be a whole number",
                   {function() return s:speed():get(2.7) end,
                    function() return s:world():gob():get(1.5) end,
                    function() return s:kin():get(1.5) end,
                    function() return s:quest():get(1.5) end,
                    function() return s:actionbar():get(1.9) end,
                    function() return s:actionbar():page(1.9) end})
  ok("an index or an id must be a whole number, at every :get", r, got)

  -- 3. ...and the write beside the read, which took 2.7 as speed 2 with nothing said.
  local was = named(s:speed():current())
  r, got = refused("must be a whole number", {function() s:speed():set(2.7) end})
  ok("s:speed():set(2.7) is refused, and the speed is unchanged",
     r and (named(s:speed():current()) == was), got or (was .. " -> " .. named(s:speed():current())))

  -- 4. A Position minted at nan is what every distance and every act verb then carries -- the move verb
  --    floors it to the frame's origin -- so it is refused where a place is made.
  local here = s:world():position(0, 0)
  r, got = refused("must be a finite number",
                   {function() return here:offset(NAN, 0) end,
                    function() return here:offset(0, INF) end,
                    function() return s:world():position(NAN, 0) end})
  ok("a place is made of finite numbers, and p:offset says so", r, got)

  -- 5. A volume passed both bounds at once, because NaN < 0 and NaN > 1 are each false, and multiplied the
  --    clip into the shared master mix.
  local blip = hafen.sound():get("sfx/msg")
  r, got = refused("volume", {function() blip:play(NAN) end, function() blip:play(2) end})
  ok("a volume must be finite, and 0..1 still holds", r, got)

  -- 6. A design pixel is whole, at the doors that hand-rolled the test.
  r, got = refused("must be a",
                   {function() return hafen.ui():hit(NAN, 0) end,
                    function() return hafen.ui():hit(1.5, 0) end,
                    function() return hafen.ui():tipAt(0, INF) end})
  ok("a design pixel must be a whole, finite number", r, got)

  -- 7. The client's own settings. Each of the four reads the PREFERENCE the next launch starts on, so
  --    reading them back here is reading the file: a refusal that wrote nothing is what this asserts.
  local opts = hafen.client():options()
  local vol, gran = opts:audio():masterVolume(), opts:interface():posGran()
  local scale, lights = opts:interface():scale(), opts:video():lightLimit()
  r, got = refused("must be a",
                   {function() opts:audio():masterVolume(NAN) end,
                    function() opts:interface():scale(INF) end,
                    function() opts:interface():posGran(NAN) end,
                    function() opts:video():lightLimit(3.7) end})
  ok("a client setting refuses a non-finite and a fractional value, and none of the four moved",
     r and (opts:audio():masterVolume() == vol) and (opts:interface():posGran() == gran)
       and (opts:interface():scale() == scale) and (opts:video():lightLimit() == lights),
     got or (tostring(opts:interface():scale()) .. ", " .. tostring(opts:video():lightLimit())))

  -- 8. JSON: the parser used to mint an infinity its own encoder refuses, and the store then wrote it as a
  --    bare null, which reads back as an ABSENT KEY -- a saved variable deleted rather than degraded.
  r, got = refused("number",
                   {function() return hafen.json():parse('{"n":1e400}') end,
                    function() return hafen.json():encode({n = INF}) end})
  ok("json refuses an overflowing literal at both ends", r, got)
  local probeVar = hafen.store():get("probe")
  probeVar.r03 = NAN
  r, got = refused("holds nan", {function() hafen.store():flush() end})
  probeVar.r03 = nil
  ok("a saved variable cannot hold nan, and flush names the path", r, got)

  -- 9. The stylesheet's own numbers, where a radius reached the blur furnace as 1410065408.
  local rule = hafen.ui():sheet():rule("*")
  r, got = refused("must be a whole number",
                   {function() rule:glow{color = {0, 0, 0}, radius = 2.5} end,
                    function() rule:glow{color = {0, 0, 0}, radius = 1e10} end})
  ok("a stylesheet glow radius is a whole number an int can hold", r, got)

  -- 10. The world entities, whose clamps all passed NaN through untouched, and the ring, whose cap used to
  --     be asked after the whole table had been resolved and convexity-tested.
  local pg = s:player():gob()
  if pg then
    local ghosts = hafen.virtual():ghost()
    local g = ghosts:add("gfx/terobjs/arch/logcabin", pg:position())
    r, got = refused("must be a finite number",
                     {function() g:alpha(NAN) end, function() g:scale(NAN) end, function() g:rotate(INF) end})
    ghosts:remove(g)
    ok("a look verb refuses a non-finite number rather than clamping it", r, got)
    local ring, p = {}, pg:position()
    for i = 1, 40 do ring[i] = p:offset(i, 0) end
    r, got = refused("at most 32", {function() return hafen.virtual():patch():add(ring, p) end})
    ok("a ring longer than the fragment stage's array is refused before it is read", r, got)
  else
    fail = fail + 2
    line("[fail] the ghost and the ring checks need the player in the world -- got: no player gob")
  end

  -- 11. The draw verbs, which read every coordinate with a bare todouble(): Math.round(NaN) is 0, so a NaN
  --     rectangle was drawn at the origin and a NaN vertex reached the GPU. A painter runs on the NEXT
  --     frame, so the probe is installed here and scored below -- once, and inside a pcall, because a
  --     refusal raised every frame would flood the log with the very message being read.
  local probe = {ran = false, refused = false, msg = "the painter never ran"}
  hafen.ui():overlay():add("r03probe"):draw(function(g)
    if probe.ran then return end
    probe.ran = true
    local fine, err = pcall(function() g:frect(NAN, 0, 10, 10) end)
    probe.refused = not fine
    probe.msg = fine and "<no error>" or why(err)
  end)
  local tries = 0
  local function collect()
    tries = tries + 1
    if not probe.ran and (tries < 10) then      -- a bounded window, ~1s, which is many frames
      hafen.timer():after(0.1, collect)
      return
    end
    hafen.ui():overlay():remove("r03probe")
    ok("g:frect refuses a non-finite coordinate, on the render thread",
       probe.ran and probe.refused and (probe.msg:find("must be a finite number", 1, true) ~= nil), probe.msg)
    report()
  end
  hafen.timer():after(0.1, collect)
end

hafen.console():on("tR03", run)
