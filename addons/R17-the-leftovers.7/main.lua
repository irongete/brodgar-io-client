-- R17.7 -- the three UI surfaces nothing archived names: the text cache (uc-23), the hit test, the forced
-- cursor and the pointer grab (uw-23), and the bottom of the style cascade (us-19). It runs only from
-- :tR17-7, sends nothing, installs no sheet, and destroys the one widget it builds.

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

local function allRefuse(name, cases, also, got)
  local bad
  for _, c in ipairs(cases) do
    bad = bad or refused(c[1], c[2], c[3])
  end
  ok(name, (bad == nil) and (also ~= false), bad or got)
end

local function read(fn)
  local good, v = pcall(fn)
  if good then return v end
  return nil
end

local function needs(action)
  manual = manual + 1
  say("[manual] " .. action .. ", then type :tR17-7 -- expect: this line becomes a [pass]")
end

local function cache() return read(function() return hafen.client():profiling():textcache() end) or {} end

-- A comparison meets a missing key as nil and raises, and a scoring line is not inside a pcall -- so the
-- three arithmetic questions this file asks are asked through these, which answer false rather than raise.
local function le(a, b) return (type(a) == "number") and (type(b) == "number") and (a <= b) end
local function gt(a, b) return (type(a) == "number") and (type(b) == "number") and (a > b) end
local function diff(a, b)
  if (type(a) == "number") and (type(b) == "number") then return a - b end
  return nil
end

-- ---- uc-23: the cache fills, evicts, and keys on the face as well as the string ------------------------

-- One tag per run, so a second run rasterises strings the first one never held.
local RUN = 0

local FILL, ROWS = 5, 150            -- 750 distinct strings, past a 512-entry ceiling

local function drawing(w, done)
  local f1 = read(function() return hafen.font():get("sans") end)
  local f2 = f1 and read(function() return f1:derive():size(9) end)
  if (f1 == nil) or (f2 == nil) then
    ok("the text cache fills to its ceiling, evicts to stay there, and keys on the face too", false,
       "no font to draw with")
    done()
    return
  end
  local phase, snap = 0, {}
  read(function() return w:font(f1) end)
  local sub
  sub = w:on("Draw", function(ev)
    local g = ev:g()
    phase = phase + 1
    if phase <= FILL then
      for i = 1, ROWS do g:text("r17-" .. RUN .. "-" .. phase .. "-" .. i, 0, 0) end
      if phase == FILL then snap.filled = cache() end
      return
    end
    if phase == FILL + 1 then                       -- the first draw of one string: a miss
      snap.a = cache()
      g:text("r17gen-" .. RUN, 0, 0)
      snap.b = cache()
      return
    end
    if phase == FILL + 2 then                       -- the same string, same face: a hit
      g:text("r17gen-" .. RUN, 0, 0)
      snap.c = cache()
      return
    end
    if phase == FILL + 3 then                       -- the same string, another face: a miss again
      -- The face is passed IN THE DRAW CALL. w:font(h) is the widget's default and the GOut has already
      -- captured it by the time this handler runs, so writing it here would land a frame later and this
      -- draw would be an ordinary hit -- which says nothing about the key.
      g:text("r17gen-" .. RUN, 0, 0, {font = f2})
      snap.d = cache()
      return
    end
    sub:off()
    local f, a, b, c, d = snap.filled, snap.a, snap.b, snap.c, snap.d
    local grew = diff((type(b) == "table") and b.misses, (type(a) == "table") and a.misses)
    local hit = diff((type(c) == "table") and c.hits, (type(b) == "table") and b.hits)
    local again = diff((type(d) == "table") and d.misses, (type(c) == "table") and c.misses)
    ok("the text cache fills to its ceiling, evicts to stay there, and keys on the face as well as the"
       .. " string",
       (type(f) == "table") and le(f.entries, f.maxEntries) and le(f.bytes, f.maxBytes)
         and gt(f.evictions, 0) and (grew == 1) and (hit == 1) and (again == 1)
         and (type(f.total) == "table") and le(1, f.total.owners) and le(f.entries, f.total.entries),
       tostring((type(f) == "table") and f.entries) .. "/"
         .. tostring((type(f) == "table") and f.maxEntries) .. " entries, "
         .. tostring((type(f) == "table") and f.evictions) .. " evictions; one string cost "
         .. tostring(grew) .. " miss, then " .. tostring(hit) .. " hit, then "
         .. tostring(again) .. " miss on another face")
    done()
  end)
end

-- ---- uw-23: the hit test, the forced cursor, the grab ---------------------------------------------------

local function hitTest(s, w)
  local rp = read(function() return w:rootPos() end)
  local sz = read(function() return w:size() end)
  if (type(rp) ~= "table") or (type(sz) ~= "table") then
    ok("the hit test reaches a surface of your own, which no session's own search ever finds", false,
       "the widget answered no place: " .. tostring(rp))
    return
  end
  -- A size is {w=, h=} and a place is {x=, y=}: neither answers the other's keys, and reading one for the
  -- other raises naming the fields it does carry.
  local cx, cy = rp.x + math.floor(sz.w / 2), rp.y + math.floor(sz.h / 2)
  local under = read(function() return hafen.ui():hit(cx, cy) end)
  -- The hit test asks about a POINT ON THE SCREEN and is addressed at nothing, so it reaches the addon
  -- layer; a session's own search is addressed at one character's tree, where a surface of yours is not.
  -- The selector is checked against the widget itself first, so the nil below is the SPLIT and not a typo.
  local sel = "[name=R17-the-leftovers.7/r17probe]"
  local names = read(function() return w:is(sel) end)
  local viaSession = read(function() return s:ui():match(sel) end)
  ok("the hit test reaches a surface of your own, which no session's own search ever finds",
     (under ~= nil) and (read(function() return under:owned() end) == true)
       and (names == true) and (viaSession == nil),
     tostring(under) .. " at " .. cx .. "," .. cy .. "; the selector names it " .. tostring(names)
       .. " and the session's search found " .. tostring(viaSession))
end

local function cursor()
  local m = hafen.ui():mouse()
  local before = read(function() return m:cursor() end)
  local set = read(function() return m:cursor("hand") end)
  local now = read(function() return m:cursor() end)
  read(function() return m:cursor(nil) end)
  local back = read(function() return m:cursor() end)
  -- m:cursor() is YOURS: nil while this addon forces nothing, whoever else may be forcing one, and nil is
  -- the whole of putting it back rather than a second name to restore. A name is asked for its TYPE, so a
  -- number is not a name that merely scans as one.
  allRefuse("a forced cursor is yours alone, reads back as itself, and nil is the whole of putting it back", {
    {"cursor(7)",  function() return m:cursor(7) end,  "name must be a string"},
    {"cursor({})", function() return m:cursor({}) end, "name must be a string"},
  }, (before == nil) and (set ~= nil) and (now == "hand") and (back == nil),
     tostring(before) .. " -> " .. tostring(now) .. " -> " .. tostring(back))
end

local function grab(done)
  local g = read(function() return hafen.ui():mouse():grab() end)
  if g == nil then
    ok("a grab is closed to two keys and ends itself on the Up", false, "the layer handed back no grab")
    done()
    return
  end
  local ups, alive = 0, tostring(g)
  read(function() return g:on("Move", function() end) end)
  read(function() return g:on("Up", function() ups = ups + 1 end) end)
  local bad = refused("grab:on('Down')", function() return g:on("Down", function() end) end,
                      "it has: Move, Up")
  -- The grab is modal while it is held, so the window is short: four seconds of a captured pointer, and
  -- the click that ends it is the one thing only the player can do.
  hafen.timer():after(4.0, function()
    local ended = tostring(g)
    if ups < 1 then read(function() return g:release() end) end
    if (bad == nil) and (ups < 1) then
      needs("click once while the pointer is captured -- the four seconds right after the run starts")
      done()
      return
    end
    ok("a grab is closed to two keys and ends itself on the Up",
       (bad == nil) and (alive == "Grab") and (ended == "Grab(released)"),
       bad or (ups .. " Up, " .. alive .. " -> " .. ended))
    done()
  end)
end

-- ---- us-19: the bottom of the cascade --------------------------------------------------------------------

local SITES = {"chat.frame", "chat.log", "menu.frame", "meter"}

local function styling(w)
  local sheet = hafen.ui():sheet()
  local bad
  for i, key in ipairs(SITES) do
    local r = read(function() return sheet:rule(key) end)
    local set = r and read(function() return r:bg({color = {i, i, i, 255}}) end)
    local i2 = r and read(function() return r:info() end)
    if (r == nil) or (set == nil) or (type(i2) ~= "table") or (type(i2.bg) ~= "table") then
      bad = bad or (key .. " took no rule: " .. tostring(i2))
    elseif read(function() return r:selector() end) ~= key then
      bad = bad or (key .. " reads back as another selector")
    end
    if r ~= nil then read(function() return r:release() end) end     -- nothing is installed, and now nothing is said
  end
  -- ...and the two levels a surface of your own has: the stock under every rule, and the rule on the
  -- widget itself. Both are read back off the widget, and both go with it when it is destroyed.
  local stock = read(function() return w:stock({bg = {color = {10, 20, 30, 255}}, padding = 2}) end)
  local sread = read(function() return w:stock() end)
  local wr = read(function() return w:rule():color({200, 180, 140}) end)
  local wi = wr and read(function() return w:rule():info() end)
  allRefuse("the four site keys and both of a surface's own levels take a rule and read it back", {
    {"sheet:rule(7)", function() return sheet:rule(7) end, "not a number"},
  }, (bad == nil) and (stock ~= nil) and (type(sread) == "table") and (type(sread.bg) == "table")
       and (type(sread.padding) == "table")            -- one number in, all four sides back
       and (sread.padding.l == 2) and (sread.padding.t == 2)
       and (sread.padding.r == 2) and (sread.padding.b == 2)
       and (wr ~= nil) and (type(wi) == "table")
       and (type(wi.color) == "table") and (wi.color.r == 200),
     bad or ("stock padding.l " .. tostring((type(sread) == "table") and (type(sread.padding) == "table")
                                            and sread.padding.l)
             .. ", rule colour " .. tostring(type(wi) == "table" and wi.color and wi.color.r)))
end

-- ---- the run ---------------------------------------------------------------------------------------------

local function finish()
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("tR17-7", function()
  pass, fail, manual = 0, 0, 0
  RUN = RUN + 1
  hafen.timer():after(0, function()
    local s = read(function() return hafen.session():current() end)
    if s == nil then
      fail = fail + 1
      say("[fail] no character is on screen -- log in and run :tR17-7 again")
      finish()
      return
    end
    local w = read(function() return hafen.ui():widget():name("r17probe"):size(24, 16):position(4, 4) end)
    if w == nil then
      fail = fail + 1
      say("[fail] the layer built no surface to draw on -- run :tR17-7 again")
      finish()
      return
    end
    -- The draw phases run on the step, so everything that needs the widget PLACED waits for them.
    drawing(w, function()
      -- The three below run under one pcall: a raise in any of them must still reach the destroy at the
      -- foot of this run, or the surface it built is left standing until the next :reload.
      local good, err = pcall(function()
        hitTest(s, w)
        cursor()
        styling(w)
      end)
      if not good then
        fail = fail + 1
        say("[fail] a check raised before it could be scored -- got: " .. why(err))
      end
      grab(function()
        read(function() return w:destroy() end)
        ok("the surface this run built is gone again",
           read(function() return w:exists() end) == false, "still in the tree")
        finish()
      end)
    end)
  end)
end)
