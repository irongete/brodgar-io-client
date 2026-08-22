-- 094 -- The widget and the thing. The whole feature's eleven rows, under one command.
--
-- The API has two address spaces -- the widget tree and the domain objects -- and until now they met in ONE
-- place and in ONE direction (widget:items()). So "draw a badge over the buff that is about to expire"
-- needed the buff's widget and there was none: a selector reaches the WINDOW by role, and the thing inside
-- it is a domain object the selector language cannot address.
--
--   A-104  :widget() on Buff, Meter, StudySlot and Kin -- the crossing back           (severe)
--   A-105  w:session()  -- eventstack walked 64 parents per message, with a memo in front of it
--   A-106  w:events()   -- the list existed to BUILD THE REFUSAL and nothing could read it
--   A-107  w:owned()    -- provenance decides what you may write, and was a snapshot field only
--   A-108  w:is(sel)    -- widgetstack ran candidate selectors over the whole tree to find out
--   A-109  wound:children() and s:wound():roots() -- the tree walked up and not down
--   A-110  gob:party()  -- the missing inverse of member:gob(), where gob:kin() had both
--   A-111  store():list() -- the declared set, which the refusal already enumerated
--   A-112  h:type()     -- three handle shapes wore one name and nothing said which
--   A-113  hafen.ui():role() -- the selector language describes itself
--   A-114  s:menugrid():get(id) takes the short id you gave :add(id)
--
-- Almost all of it is readable back, so almost all of it is scored. The reads that need a window open are
-- scored over what the run reached; the one manual is a judgement about a place on screen.

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

-- A group of reads, scored over what the run REACHED: a check whose subject is not up returns nil and is
-- not counted, so a run made with the character sheet closed is honest rather than red.
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

-- The one claim every collection makes (091's rule): the quartet answers and # is refused naming :list().
local function isCollection(c)
  if c == nil then return nil end
  local okc, n = pcall(function() return c:count() end)
  if not okc or (type(n) ~= "number") then return false end
  local okl, l = pcall(function() return c:list() end)
  if not okl or (type(l) ~= "table") or (#l ~= n) then return false end
  return not pcall(function() return #c end)          -- # must be REFUSED
end

local function isWidget(w)
  if w == nil then return nil end
  local ok, t = pcall(function() return w:type() end)
  return ok and (type(t) == "string")
end

local function run()
  local s = hafen.session():current()

  ---------------------------------------------------------------------------------------------------
  -- A-104: the crossing back. Four domain objects that the bridge was already holding a widget for.
  ---------------------------------------------------------------------------------------------------
  section("A-104", function()
    local g = scored()
    g.want("buff:widget()", function()
      local b = s and s:buff():list()[1]
      return b and isWidget(b:widget())
    end)
    g.want("meter:widget()", function()
      local m = s and s:meter():list()[1]
      return m and isWidget(m:widget())
    end)
    g.want("slot:widget() -- a curiosity", function()
      local sl = s and s:study():curiosity():list()[1]     -- 089 renamed it: `slot` is the action bar's
      return sl and isWidget(sl:widget())
    end)
    g.want("meter:widget() is the SAME object twice", function()
      local m = s and s:meter():list()[1]
      if not m then return nil end
      return m:widget() == m:widget()                  -- widgets are interned per addon
    end)
    g.want("the crossing round-trips: w:session() names the character it came from", function()
      local m = s and s:meter():list()[1]
      local w = m and m:widget()
      local back = w and w:session()
      return back and (back:user() == s:user())
    end)
    g.done("a domain object crosses back to the widget that draws it")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-105 / A-106 / A-107 / A-108: the four facts a widget could not say about itself.
  ---------------------------------------------------------------------------------------------------
  section("A-105..108", function()
    local mine = hafen.ui():window():title("094"):size(100, 40)
    local theirs = s and s:ui():matchAll("window")[1]
    local g = scored()
    g.want("w:owned() is true for one you built", function() return mine:owned() == true end)
    g.want("w:owned() is false for one of the client's", function()
      return theirs and (theirs:owned() == false)
    end)
    g.want("w:session() is nil in the layer", function()
      return mine:session() == nil or "the layer belongs to no character"
    end)
    g.want("w:session() names the tree for one of the client's", function()
      return theirs and theirs:session() and (theirs:session():user() == s:user())
    end)
    g.want("w:events() is an array of strings", function()
      local e = mine:events()
      return (type(e) == "table") and (pcall(function() return #e end) == true)
    end)
    g.want("w:events() lists what :on() accepts, and only that", function()
      local e = theirs and theirs:events()
      if (e == nil) or (#e == 0) then return nil end
      local sub = theirs:on(e[1], function() end)      -- a key it named must be accepted
      if sub then sub:off() end
      return not pcall(function() return theirs:on("094NoSuchKey", function() end) end)
    end)
    g.want("w:is(sel) is true for its own role and false for another", function()
      if not theirs then return nil end
      return (theirs:is("window") == true) and (theirs:is("textentry") == false)
    end)
    g.want("w:is(sel) asks about W, where :match(sel) searches its subtree", function()
      local box = hafen.ui():window():title("094is"):size(120, 60)
      hafen.ui():label():text("inside"):parent(box)
      -- :match is INCLUSIVE of w, so the two differ on a selector that names something BELOW it only.
      local r = (box:is("label") == false) and (box:match("label") ~= nil)
                  and (box:is("window") == true) and (box:match("window") == box)
      box:destroy()
      return r
    end)
    mine:destroy()
    g.done("a widget says its session, its events, its provenance and what it matches")
  end)

  section("A-108 refusals", function()
    local mine = hafen.ui():window():title("094r"):size(100, 40)
    local r = refusals()
    r.ask("w:is()", function() return mine:is() end, "selector is required")
    r.ask("w:is(nonsense)", function() return mine:is("!!! not a selector") end, "is not a role")
    mine:destroy()
    r.done("w:is refuses a missing selector and an unparsable one")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-109: the wound tree walks down. Both directions are collections, like the menugrid's.
  ---------------------------------------------------------------------------------------------------
  section("A-109", function()
    local g = scored()
    g.want("s:wound():roots() is a collection", function()
      return s and isCollection(s:wound():roots())
    end)
    g.want("wound:children() is a collection", function()
      local w = s and s:wound():list()[1]
      return w and isCollection(w:children())
    end)
    g.want("every root has no parent, and every child names its parent back", function()
      if not s then return nil end
      for _, w in ipairs(s:wound():roots():list()) do
        if w:parent() ~= nil then return false end
        for _, c in ipairs(w:children():list()) do
          if c:parent() ~= w then return false end     -- interned: identity is the test
        end
      end
      return (s:wound():roots():count() > 0) or nil    -- no wounds: nothing reached
    end)
    g.done("the wound tree walks down as well as up")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-110 / A-111 / A-112 / A-113 / A-114: the five small ones.
  ---------------------------------------------------------------------------------------------------
  section("the five", function()
    local g = scored()
    g.want("gob:party() is the inverse of member:gob()", function()
      local m = s and s:party():list()[1]
      if not m then return nil end
      return m:gob():party() == m                      -- interned both ways
    end)
    g.want("gob:party() is nil for a gob in no party", function()
      local g2 = s and s:world():gob():nearest("terobjs")
      if not g2 then return nil end
      return g2:party() == nil
    end)
    g.want("store():list() answers an array in both halves", function()
      local a, b = hafen.store():list(), s and s:store():list()
      return (type(a) == "table") and (type(b) == "table")
        and (pcall(function() return #a end) == true)
    end)
    g.want("h:type() answers for all three shapes", function()
      local built = hafen.font():get("sans")
      if not built then return nil end
      return (built:type() == "builtin") and (built:derive():size(11):type() == "variant")
    end)
    g.want("hafen.ui():role() is a collection, addressed by name", function()
      local r = hafen.ui():role()
      if not isCollection(r) then return false end
      local w = r:get("window")
      return (w ~= nil) and (w:name() == "window") and (w == r:get("window"))
    end)
    g.want("a widget role carries its selector and a site role does not", function()
      local r = hafen.ui():role()
      return (r:get("window"):selector() == "window") and (r:get("window.title"):selector() == nil)
    end)
    g.want("s:menugrid():get(id) takes the short id of one of your own", function()
      if not s then return nil end
      local pag = s:menugrid():add("094probe"):name("094 probe")
      local back = s:menugrid():get("094probe")
      s:menugrid():remove(pag)
      return back == pag                               -- interned: the same entry, by the short id
    end)
    g.done("the party inverse, the declared set, the font shape, the role table and the short id")
  end)

  ---------------------------------------------------------------------------------------------------
  -- What no program can judge: whether the crossing lands where the thing is DRAWN.
  ---------------------------------------------------------------------------------------------------
  local at = section("the outline", function()
    local m = s and s:meter():list()[1]
    local w = m and m:widget()
    if (w == nil) or (w:rootPos() == nil) then return nil end
    -- "094meter", never "094": in LuaJ a NUMERIC STRING is a number, which the key guard refuses on
    -- purpose -- the same coercion the whole bridge writes type() != TSTRING for.
    hafen.ui():overlay():add("094meter"):draw(function(g)
      local p = w:rootPos()
      if not p then return end
      g:color(255, 90, 90, 220)
      g:rect(p.x - 2, p.y - 2, w:size().w + 4, w:size().h + 4)
      g:color()
    end)
    return true
  end)
  manualCheck("look at the first HUD meter bar" .. (at and "" or " -- none was up, so nothing was drawn"),
              "a red outline exactly around it, drawn from meter:widget():rootPos() and :size(). That is"
                .. " the crossing landing where the thing IS: before 094 a domain object could not name"
                .. " the widget that draws it at all. :reload takes the outline away")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():on("t094", run)                  -- the only way in: a suite does not start itself
