-- R06 -- one entry into Lua.
--
-- One mechanism: every call that runs a chunk of this addon's code goes through one door, which holds the
-- addon's own lock for the length of the entry. What that buys is checked from both sides of the door here.
-- From INSIDE a tree -- a screen overlay's painter, which paints in the addon layer and holds the layer's
-- guard -- every verb that reads or writes a widget of another tree refuses, reads and writes alike, and
-- the layer's own widgets answer freely. From the STEP, holding no tree, the same reads answer everywhere.
-- Beside them, the surfaces this block moved onto their own guards -- the profiler's rows and scopes, the
-- pointer's picture, a font variant, the map's pins, a catalogue -- answer without raising.
--
-- Everything below is read-only, a refusal, or a write it puts back before it reports: the pointer picture
-- is cleared, the catalogue is released, and the window and the overlay are destroyed. Run it with :tR06.
-- It starts nothing by itself.
--
-- The checks run on the engine step rather than inside the console handler. A console line runs under the
-- monitor of the tree it was typed into, and the window this builds is in the addon layer -- a second tree,
-- which is the one thing a handler holding one may not reach. hafen.timer():after(0, fn) is the step, and
-- it holds neither.
--
-- One run is about two seconds: the painter is read one beat later, because an overlay paints on the next
-- frame, and the ground under a screen point is read back from the GPU a frame or two after that.

local out, pass, fail, manual = {}, 0, 0, 0
local KEY = "r06probe"
local SCOPE = "r06"
local NESTED = "one tree monitor at a time"

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

local function todo(action, expect)
  manual = manual + 1
  line("[manual] " .. action .. " -- expect: " .. expect)
end

-- LuaJ prefixes a bridge refusal with "@chunk.lua:<n>" and a SPACE, and a Lua error with "chunk.lua:<n>:".
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Every call in `fns` must raise, and every message must carry `needle`.
local function refused(needle, fns)
  for i = 1, #fns do
    local fine, err = pcall(fns[i])
    if fine then return false, "call " .. i .. " raised nothing" end
    local msg = why(err)
    if not msg:find(needle, 1, true) then return false, "call " .. i .. " said " .. msg end
  end
  return true
end

local win, root, me, painted, ground
local probe = {}

-- --------------------------------------------------------- inside a tree: the painter

-- Runs in the pass that paints the screen overlay, which is the addon layer's. Everything it can learn is
-- recorded and nothing is reported from here: a verdict written in a painter would be written every frame.
local function paint()
  if painted then return end
  painted = true
  probe.stepping = hafen.client():stepping()
  probe.crossed, probe.crosswhy = refused(NESTED, {
    function() return root:position() end,     -- a READ of another tree is a second guard too
    function() return root:visible(true) end,  -- ...and so, as it always was, is a write
    function() return me:ui():match("*") end,  -- ...and so is the door that walks it
  })
  probe.own = pcall(function()
    local p = win:position()                   -- the layer IS this painter's tree: it answers
    win:visible(true)
    return p
  end)
  probe.logged = pcall(function()
    hafen.log():write("[r06] a line written from inside a painter")
  end)
end

-- --------------------------------------------------------- holding no tree: the step

local function stepChecks()
  local r, got

  ok("a painter reaches its own tree and refuses a second one, at a read as at a write",
     probe.crossed == true, probe.crosswhy)
  ok("...and the layer's own widgets answer inside that very painter", probe.own == true, probe.own)
  ok("the painter is not the step, and the step is",
     (probe.stepping == false) and (hafen.client():stepping() == true),
     "painter=" .. tostring(probe.stepping) .. " step=" .. tostring(hafen.client():stepping()))
  ok("a log line written from inside a painter is taken without raising", probe.logged == true, probe.logged)

  -- The profiler's own reads: a row table, a frame table and a scope that chains. They answer disarmed --
  -- empty rather than absent -- and none of the three may raise out of a read verb.
  local p = hafen.client():profiling()
  local rows, frame
  local fine, err = pcall(function()
    p:scope(SCOPE):begin()
    rows = p:addons()
    frame = p:frame()
    p:scope(SCOPE):finish()
  end)
  ok("the profiler's rows, its frame and a named scope answer without raising",
     fine and (type(rows) == "table") and (type(frame) == "table"),
     fine and ("rows=" .. type(rows) .. " frame=" .. type(frame)) or why(err))

  -- The pointer: one override for the client, and a read answers only your own.
  local m = hafen.ui():mouse()
  m:cursor("hand")
  local held = m:cursor()
  m:cursor(nil)
  -- `true`, not a number: in LuaJ a number answers isstring(), so the guard this refusal comes from
  -- reads 5 as the cursor named "5" and takes it. A boolean is a value no reading of it accepts.
  r, got = refused("must be a cursor name", {function() return m:cursor(true) end})
  ok("the forced pointer reads back your own name, clears whole, and refuses a name that is not one",
     (held == "hand") and (m:cursor() == nil) and r,
     got or ("held=" .. tostring(held) .. " after=" .. tostring(m:cursor())))

  -- A font variant is written and read back on the same handle; the shared face it came from is not.
  local base = hafen.font():get("serif")
  local v = base:derive():size(13)
  r, got = refused("this font is shared", {function() return base:size(13) end})
  ok("a font variant takes a size and answers it, and the shared face it came from refuses", r and (v:size() == 13),
     got or ("size=" .. tostring(v:size())))

  -- The map's pins: read without waiting on the database's own writer, so these answer whatever it is doing.
  local mk = hafen.map():marker()
  ok("the map's markers answer a count and a list without waiting on the database",
     (type(mk:count()) == "number") and (type(mk:list()) == "table"),
     "count=" .. tostring(mk:count()) .. " list=" .. type(mk:list()))

  -- A catalogue: what it says is in force only between install and release, and info() says which it is.
  local loc = hafen.locale()
  loc:load({}):install()
  local on = loc:info().installed
  local misses = loc:miss():list()
  loc:release()
  ok("a catalogue installs and releases, info() follows it, and its misses read back",
     (on == true) and (loc:info().installed == false) and (type(misses) == "table"),
     "installed=" .. tostring(on) .. "/" .. tostring(loc:info().installed))

  ok("the ground under a screen point is read back and handed to its own callback",
     (ground ~= nil) and (type(ground) == "boolean" or ground.x ~= nil),
     tostring(ground))

  todo("read the chat for the line the painter wrote", "one line reading \"[r06] a line written from"
       .. " inside a painter\", and no error line beside it")
end

-- --------------------------------------------------------- the run

local function report()
  if win then pcall(function() win:destroy() end) end
  win = nil
  pcall(function() hafen.ui():overlay():remove(KEY) end)
  pcall(function() hafen.ui():mouse():cursor(nil) end)
  pcall(function() hafen.locale():release() end)
  for _, l in ipairs(out) do hafen.log():write(l) end
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

local function run()
  out, pass, fail, manual = {}, 0, 0, 0
  painted, win, ground, probe = false, nil, nil, {}
  me = hafen.session():current()
  root = me and me:ui():root()
  if not root then
    line("[fail] no character is in the world -- log one in, wait for the HUD, and run :tR06 again")
    return report()
  end

  -- The screen point the ground is read under: the middle of the view, which is over terrain whenever the
  -- character is standing on any. Asked here, on the step, because the answer lands on the click pass.
  local sz = root:size()
  pcall(function()
    me:world():screenToWorld({x = sz.w / 2, y = sz.h / 2}, function(p)
      ground = (p ~= nil) and p or false
    end)
  end)

  win = hafen.ui():window():title("R06"):position(40, 40):size(120, 60)
  hafen.ui():overlay():add(KEY):draw(function() paint() end)

  -- The report and the cleanup run whatever the checks do: a throw there would otherwise leave the window
  -- standing and print nothing at all, which reads as a suite that did not run.
  hafen.timer():after(2, function()
    local fine, err = pcall(stepChecks)
    if not fine then
      fail = fail + 1
      line("[fail] the checks raised -- got: " .. why(err))
    end
    report()
  end)
end

hafen.console():on("tR06", function() hafen.timer():after(0, run) end)
