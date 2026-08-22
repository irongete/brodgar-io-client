-- 091 -- A set is a collection. The whole feature's twelve rows, under one command.
--
-- The API's rule is that a relation whose members are objects is a collection: #coll is refused and
-- :count() works. Seventeen relations broke it, and nothing in the naming said which was which --
-- s:study():curiosity() was a collection and s:fight():deck() an array; gob:overlay() a collection
-- and gob:sessions() an array. Worse, four times the two shapes sat ONE VERB APART on one object.
--
-- Four reads went further and handed back arrays of ANONYMOUS TABLES: a recipe's slots, the FEP bar's
-- events, a meter's segments. So `i.name or i.res` was dot-access on a fresh table, a typo read nil
-- with nothing to say so, and num == -1 was a sentinel a reader had to remember.
--
-- And three collections answered a different question from the one they were asked.
-- hafen.sound():count() said "how many are audible" where :get(name) mints any clip.
-- hafen.font():count() said 0 on a fresh addon and 4 after four :get calls -- the addon's history,
-- not the client's fonts. s:speed():list() grew as the character unlocked speeds.
--
-- The proof is the quartet, everywhere: :count() answers, #coll is refused naming :list(), and the
-- object inside answers verbs rather than being indexed into. The reads are scored over a bounded
-- window, because a wound, a recipe and a ring each need the client in a state.

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

local function section(name, fn, ...)
  local ok, r = pcall(fn, ...)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

local function refusals()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.ask(label, fn, want)
    total = total + 1
    local ok, err = pcall(fn)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if (not ok) and (err:find(want, 1, true) ~= nil) then n = n + 1
    else why = why or (label .. " -> " .. err) end
  end
  function g.done(what) check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n) end
  return g
end

local function scored()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.want(label, fn)
    local ok, r = pcall(fn)
    if ok and (r == nil) then return end
    total = total + 1
    if ok and r then n = n + 1 else why = why or (label .. " -> " .. tostring(r)) end
  end
  function g.done(what)
    if total == 0 then
      pass = pass + 1
      hafen.log():write("[pass] " .. what .. " (0/0 reached -- nothing of the kind was up)")
    else
      check(n == total, what .. " (" .. n .. "/" .. total .. " reached)", why or n)
    end
  end
  return g
end

-- The one claim every conversion makes: it answers the quartet, and # is refused naming :list().
local function isCollection(c)
  if c == nil then return nil end
  local okc, n = pcall(function() return c:count() end)
  if not okc or (type(n) ~= "number") then return false end
  local okl, l = pcall(function() return c:list() end)
  if not okl or (type(l) ~= "table") or (#l ~= n) then return false end
  local okf = pcall(function() return c:find(function() return false end) end)
  local sharp = pcall(function() return #c end)          -- must be REFUSED
  return okf and (not sharp)
end

local function run()
  local s = hafen.session():current()

  ---------------------------------------------------------------------------------------------------
  -- A-073 + A-082: nine relations are collections now.
  ---------------------------------------------------------------------------------------------------
  section("A-073", function()
    local g = scored()
    g.want("w:children()", function()
      local w = hafen.ui():window():title("091"):size(80, 40)
      local ok = isCollection(w:children())
      w:destroy()
      return ok
    end)
    g.want("contents:items()", function()
      local inv = s:ui():inventory(); if not inv then return nil end
      local it = inv:items():list()[1]; if not it then return nil end
      local ct = it:contents(); if not ct then return nil end
      return isCollection(ct:items())
    end)
    g.want("w:items()", function()
      local inv = s:ui():inventory(); if not inv then return nil end
      return isCollection(inv:items())
    end)
    g.want("q:conditions()", function()
      local q = s:quest():selected(); if not q then return nil end
      return isCollection(q:conditions())
    end)
    g.want("pag:children()", function()
      local p = s:menugrid():roots():list()[1]; if not p then return nil end
      return isCollection(p:children())
    end)
    g.want("s:menugrid():roots()", function() return isCollection(s:menugrid():roots()) end)
    g.want("seg:markers() shares the marker Source", function()
      local seg = hafen.map():segment():list()[1]; if not seg then return nil end
      return isCollection(seg:markers())
    end)
    g.want("s:fight():deck()", function() return isCollection(s:fight():deck()) end)
    g.want("gob:sessions()", function()
      local me = s:player():gob(); if not me then return nil end
      return isCollection(me:sessions())
    end)
    g.done("nine relations answer the quartet, and # is refused on each")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-075 + A-076 + A-077: the members are OBJECTS, not anonymous tables.
  ---------------------------------------------------------------------------------------------------
  section("the four new object kinds", function()
    local g = scored()
    g.want("meter:segment() is a collection of Segment", function()
      local m = s:meter():list()[1]; if not m then return nil end
      if not isCollection(m:segment()) then return false end
      local seg = m:segment():list()[1]; if not seg then return nil end
      return (type(seg:index()) == "number") and (seg:index() == 1)
        and (type(seg:value()) == "number") and (type(seg:info()) == "table")
    end)
    g.want("a Segment's colour is keyed, like every colour read", function()
      local seg = (s:meter():list()[1] or {}); if not seg.segment then return nil end
      seg = seg:segment():list()[1]; if not seg then return nil end
      local c = seg:color(); if c == nil then return nil end
      return (type(c.r) == "number") and (c[1] == nil)
    end)
    g.want("CraftSpec: :count() answers 1 where the wire said -1", function()
      if not s:craft():exists() then return nil end
      if not isCollection(s:craft():inputs()) then return false end
      local i = s:craft():inputs():list()[1]; if not i then return nil end
      local n = i:count()
      return (type(n) == "number") and (n >= 1) and (type(i:info()) == "table")
    end)
    g.want("a tool carries no count and no flag", function()
      if not s:craft():exists() then return nil end
      local t = s:craft():tools():list()[1]; if not t then return nil end
      return (t:count() == nil) and (t:optional() == nil)
    end)
    g.want("food:fep() nests the way food:info().fep does", function()
      local f = s:char():food(); if not f then return nil end
      local fep = f:fep(); if not fep then return nil end
      local i = f:info()
      return (type(fep:cap()) == "number") and (type(fep:total()) == "number")
        and isCollection(fep:entry())
        and ((i == nil) or (i.fep == nil) or (i.fep.cap == fep:cap()))
    end)
    g.want("food:hunger() is an object", function()
      local f = s:char():food(); if not f then return nil end
      local h = f:hunger(); if not h then return nil end
      return (type(h:level()) == "number") and (type(h:info()) == "table")
    end)
    g.done("a segment, a recipe slot, a food event and the hunger meter are objects")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-078: a Petal is an object, and s:flowermenu() is a collection.
  ---------------------------------------------------------------------------------------------------
  section("A-078", function()
    local g = scored()
    g.want("s:flowermenu() answers the quartet", function() return isCollection(s:flowermenu()) end)
    g.want("a Petal answers rather than being a string", function()
      local p = s:flowermenu():list()[1]; if not p then return nil end
      return (type(p:label()) == "string") and (p:index() == 1) and (p:exists() == true)
    end)
    g.want("s:flowermenu():get(n) takes the 1-based position", function()
      local p = s:flowermenu():list()[1]; if not p then return nil end
      return s:flowermenu():get(1) ~= nil
    end)
    g.done("the ring is a collection of Petals")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-079 + A-080 + A-081: three collections answered a different question from the one asked.
  ---------------------------------------------------------------------------------------------------
  section("A-079/80/81", function()
    local g = scored()
    g.want("s:speed():list() is ALL FOUR", function() return s:speed():count() == 4 end)
    g.want("the selectable ones are a partition", function()
      local a = s:speed():available()
      return isCollection(a) and (a:count() <= 4)
    end)
    g.want("hafen.font():list() is the four built-ins, before any :get", function()
      return hafen.font():count() == 4
    end)
    g.want("hafen.sound():playing() is the audible ones", function()
      return isCollection(hafen.sound():playing())
    end)
    g.done("a list answers the question its collection is about")
  end)

  ---------------------------------------------------------------------------------------------------
  -- Every retired spelling raises AND names its replacement.
  ---------------------------------------------------------------------------------------------------
  section("retired", function()
    local g = refusals()
    g.ask("hafen.sound():list", function() return hafen.sound():list() end, "playing")
    g.ask("hafen.sound():count", function() return hafen.sound():count() end, "playing")
    local m = s:meter():list()[1]
    if m then
      g.ask("meter:value", function() return m:value() end, "segment")
      g.ask("meter:color", function() return m:color() end, "segment")
      g.ask("meter:segments", function() return m:segments() end, "meter:segment()")
    end
    local f = s:char():food()
    if f then
      g.ask("food:cap", function() return f:cap() end, "fep()")
      g.ask("food:total", function() return f:total() end, "fep()")
      g.ask("food:feps", function() return f:feps() end, "entry()")
      g.ask("food:label", function() return f:label() end, "hunger()")
      g.ask("food:efficacy", function() return f:efficacy() end, "hunger()")
    end
    g.done("the deleted reads raise, each naming what says which part it meant")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-074: the three that stay ARRAYS say so, and A-084's return.
  ---------------------------------------------------------------------------------------------------
  section("A-074/84", function()
    local g = scored()
    g.want("pag:categories() is an array of strings", function()
      local p = s:menugrid():roots():list()[1]; if not p then return nil end
      local c = p:categories(); if c == nil then return nil end
      return (type(c) == "table") and (pcall(function() return #c end) == true)
    end)
    g.want("item:slots() is an array of names", function()
      local inv = s:ui():inventory(); if not inv then return nil end
      local it = inv:items():list()[1]; if not it then return nil end
      local sl = it:slots(); if sl == nil then return nil end
      return pcall(function() return #sl end) == true
    end)
    g.want("s:ui():matchAll(sel) is a query result, not a relation", function()
      local a = s:ui():matchAll("window")
      return (type(a) == "table") and (pcall(function() return #a end) == true)
    end)
    g.done("a list of strings, a list of names and a query result stay arrays")
  end)

  manualCheck("open a radial menu on something and run :t091 again",
              "the A-078 line passing 3/3 -- a Petal answering :label() and :index(), where"
                .. " s:flowermenu():list() used to hand back bare caption strings")
  manualCheck("open a crafting recipe and run :t091 again",
              "the CraftSpec lines passing -- :count() answering 1 where the wire says -1, and a tool"
                .. " answering nil for both :count() and :optional()")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():on("t091", run)                  -- the only way in: a suite does not start itself
