-- 089 -- A word means one thing II: the character sheet. The whole feature's suite, under one command.
--
-- Thirteen spellings meant more than one thing on the surface a user reads most, and two of them were
-- silently wrong. man:available() was a COUNT where sp:available() is a boolean, and 0 is truthy in Lua,
-- so `if man:available() then deck:add(man) end` took the branch with NONE dealable. And credo:quest()
-- was a COUNT named like an object, so s:quest():get(credo:quest()) compiled, ran, and addressed a quest
-- by a number -- either nil or a real but unrelated one.
--
-- Each row is proved the way a rename is proved: the new spelling answers what the old one did, the old
-- one RAISES and names its replacement, and the words the renames free still mean what they were freed
-- FOR. The deletions are proved by the comparison that replaces them -- s:party():leader() == member is
-- exact because the members are interned, which is the whole reason a per-member flag was removable.
--
-- The refusals need nothing of the world: they are Retired rows firing off metatables, so they run on the
-- login screen. The reads need a character, a wound, a buff, a credo and the rest, so they are scored over
-- a bounded window and the report says what the run reached.

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

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn, ...)
  local ok, r = pcall(fn, ...)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

-- A GROUP of refusals, scored as one line. A refusal is a check like any other: the call must fail, and
-- fail SAYING what to write instead -- so the group records the first spelling that did neither, which is
-- the one thing a bare count would not tell you.
local function refusals()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.ask(label, fn, want)
    total = total + 1
    local ok, err = pcall(fn)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if (not ok) and (err:find(want, 1, true) ~= nil) then
      n = n + 1
    else
      why = why or (label .. " -> " .. err)
    end
  end
  function g.done(what)
    check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n)
  end
  return g
end

-- SCORED: a claim that needs a receiver only the client can produce. It counts what it reached and says
-- so, rather than failing for a wound the character has not got.
local function scored()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.want(label, fn)                 -- fn returns true (reached and right) / false (reached and
    local ok, r = pcall(fn)                  -- WRONG) / nil (not reached)
    if ok and (r == nil) then return end     -- nothing to score
    total = total + 1
    if ok and r then n = n + 1 else why = why or (label .. " -> " .. tostring(ok and r or r)) end
  end
  function g.done(what)
    if total == 0 then
      hafen.log():write("[pass] " .. what .. " (0/0 reached -- nothing of the kind was up)")
      pass = pass + 1
    else
      check(n == total, what .. " (" .. n .. "/" .. total .. " reached)", why or n)
    end
  end
  return g
end

local function first(coll)
  local ok, l = pcall(function() return coll:list() end)
  return (ok and l and l[1]) or nil
end

local function run()
  local s = hafen.session():current()

  ---------------------------------------------------------------------------------------------------
  -- A-058 :available() means one thing. The count, the partition and the predicate are three words.
  ---------------------------------------------------------------------------------------------------
  section("A-058", function()
    local g = scored()
    g.want("man:dealable()", function()
      local m = first(s:fight():maneuver()); if not m then return nil end
      return type(m:dealable()) == "number"
    end)
    g.want("skill():buyable() is a collection", function()
      local b = s:char():skill():buyable()
      return (type(b:count()) == "number") and (#b:list() == b:count())
    end)
    g.want("sp:available() is still a boolean", function()
      local sp = first(s:speed()); if not sp then return nil end
      return type(sp:available()) == "boolean"
    end)
    g.done("a count, a partition and a predicate are three words")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-059 / A-060 / A-061 three words that meant three things.
  ---------------------------------------------------------------------------------------------------
  section("A-059/60/61", function()
    local g = scored()
    g.want("credo:questsDone() is a count", function()
      local c = s:char():credo():pursuing(); if not c then return nil end
      local d, t = c:questsDone(), c:questTotal()
      return (type(d) == "number") and (type(t) == "number") and (d <= t)
    end)
    g.want("credo:rank()", function()
      local c = s:char():credo():pursuing(); if not c then return nil end
      return type(c:rank()) == "number"
    end)
    g.want("w:depth()", function()
      local w = first(s:wound()); if not w then return nil end
      return type(w:depth()) == "number"
    end)
    g.want("contents:fill()", function()
      local it = first(s:ui():inventory():items()); if not it then return nil end
      local ct = it:contents(); if not ct then return nil end
      local f = ct:fill(); if f == nil then return nil end
      return (type(f.cur) == "number") and (type(f.max) == "number")
    end)
    g.want("card:index()", function()
      local c = first(s:fight():deck()); if not c then return nil end
      return type(c:index()) == "number"
    end)
    g.want("s:study():curiosity() is a collection", function()
      return type(s:study():curiosity():count()) == "number"
    end)
    g.done("a count, a depth, a rank, a fill and an index each say what they are")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-067 / A-068 the distinguished member is an identity test, not a flag on the member.
  ---------------------------------------------------------------------------------------------------
  section("A-067/68", function()
    local g = scored()
    g.want("s:party():leader() == member", function()
      local m = s:party():leader(); if not m then return nil end
      return (s:party():leader() == m) and (s:party():get(m:id()) == m)
    end)
    g.want("s:quest():selected() == q", function()
      local q = s:quest():selected(); if not q then return nil end
      return (s:quest():selected() == q) and (s:quest():get(q:id()) == q)
    end)
    g.want("s:char():credo():pursuing() == credo", function()
      local c = s:char():credo():pursuing(); if not c then return nil end
      return s:char():credo():pursuing() == c
    end)
    g.want("the chain that replaces the collection's :cost()", function()
      local c = s:char():credo():pursuing(); if not c then return nil end
      return type(c:cost()) == "number"
    end)
    g.done("the distinguished member is an identity comparison, and it is exact")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-062 / A-063 / A-065 / A-066 a name says its unit and its source.
  ---------------------------------------------------------------------------------------------------
  section("A-062/63/65/66", function()
    local g = scored()
    g.want("buff:remaining() is a 0..1 fraction", function()
      local b = first(s:buff()); if not b then return nil end
      local r = b:remaining(); if r == nil then return nil end
      return (r >= 0) and (r <= 1)
    end)
    g.want("slot:hold() reads the hold", function()
      local sl = s:actionbar():get(0); if not sl then return nil end
      local h = sl:hold()
      return (h == nil) or (h:res() ~= nil)          -- nil for a slot the server owns, an entry for ours
    end)
    g.want("cond:tooltip() is separate from :description()", function()
      local q = s:quest():selected(); if not q then return nil end
      local c = q:conditions()[1]; if not c then return nil end
      return (c:tooltip() == nil) or (type(c:tooltip()) == "string")
    end)
    g.done("a fraction, a hold and a tooltip each say what they are")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-070 the section IS the open recipe. It answers with nothing open, which a wrapper could not.
  ---------------------------------------------------------------------------------------------------
  section("A-070", function()
    local e = s:craft():exists()
    local okShape = (type(e) == "boolean")
      and (type(s:craft():inputs()) == "table")
      and (type(s:craft():outputs()) == "table")
      and (type(s:craft():tools()) == "table")
      and (type(s:craft():qualityInputs()) == "table")
    local okClosed = e or ((s:craft():recipe() == nil) and (#s:craft():inputs() == 0))
    check(okShape and okClosed,
          "s:craft() IS the recipe: it answers with nothing open, as s:flowermenu() does"
            .. " (exists=" .. tostring(e) .. ")",
          okShape and "a read was wrong with nothing open" or "a read had the wrong shape")
  end)

  ---------------------------------------------------------------------------------------------------
  -- A-069 a boolean reads as a bare adjective, and NO verb this suite reaches begins with `is`.
  ---------------------------------------------------------------------------------------------------
  section("A-069", function()
    local g = scored()
    g.want("hafen.time():night()", function()
      local n = hafen.time():night(); if n == nil then return nil end
      return type(n) == "boolean"
    end)
    g.want("gob:player() is a boolean, not a Gob", function()
      local me = s:player():gob(); if not me then return nil end
      return type(me:player()) == "boolean"
    end)
    g.want("pag:unseen()", function()
      local p = first(s:menugrid()); if not p then return nil end
      return type(p:unseen()) == "boolean"
    end)
    g.want("the ones that never moved still read bare", function()
      local k = first(s:kin())
      return (k == nil) and nil or (type(k:online()) == "boolean")
    end)
    g.done("a boolean reads as a bare adjective")
  end)

  ---------------------------------------------------------------------------------------------------
  -- Every retired spelling raises AND names its replacement. This is the half that needs no world.
  ---------------------------------------------------------------------------------------------------
  section("retired", function()
    local g = refusals()
    g.ask("s:char():skill():available", function() return s:char():skill():available() end, "buyable")
    g.ask("s:char():credo():cost", function() return s:char():credo():cost() end, "pursuing")
    g.ask("s:study():slot", function() return s:study():slot() end, "curiosity")
    g.ask("s:craft():current", function() return s:craft():current() end, "the section IS the open recipe")
    g.ask("s:craft():name", function() return s:craft():name() end, "recipe")
    g.ask("hafen.time():isNight", function() return hafen.time():isNight() end, "night")
    g.done("the section and collection spellings raise, each naming its replacement")

    local m = refusals()
    local function member(coll) local x = first(coll); return x end
    local mm = member(s:fight():maneuver())
    if mm then m.ask("man:available", function() return mm:available() end, "dealable") end
    local cr = s:char():credo():pursuing()
    if cr then
      m.ask("credo:quest", function() return cr:quest() end, "questsDone")
      m.ask("credo:level", function() return cr:level() end, "rank")
      m.ask("credo:pursuing", function() return cr:pursuing() end, "identity comparison")
    end
    local wd = member(s:wound())
    if wd then m.ask("wound:level", function() return wd:level() end, "depth") end
    local bf = member(s:buff())
    if bf then m.ask("buff:duration", function() return bf:duration() end, "remaining") end
    local pm = s:party():leader()
    if pm then m.ask("partymember:leader", function() return pm:leader() end, "identity comparison") end
    local qq = s:quest():selected()
    if qq then m.ask("quest:selected", function() return qq:selected() end, "identity comparison") end
    local sl = s:actionbar():get(0)
    if sl then m.ask("slot:pagina", function() return sl:pagina() end, "hold") end
    local me = s:player():gob()
    if me then m.ask("gob:isPlayer", function() return me:isPlayer() end, "player") end
    local pg = member(s:menugrid())
    if pg then m.ask("pagina:isNew", function() return pg:isNew() end, "unseen") end
    m.done("the entity spellings raise, each naming its replacement")
  end)

  ---------------------------------------------------------------------------------------------------
  -- The words the renames FREED still mean what they were freed for.
  ---------------------------------------------------------------------------------------------------
  section("freed", function()
    local g = scored()
    g.want(":quest() means an object", function()
      local q = s:quest():selected(); if not q then return nil end
      local c = q:conditions()[1]; if not c then return nil end
      return c:quest() == q
    end)
    g.want("credo:questId() is the quest's id, not a count", function()
      local c = s:char():credo():pursuing(); if not c then return nil end
      local id = c:questId(); if id == nil then return nil end
      return id ~= c:questsDone()
    end)
    g.want("item:cell() still means a PLACE", function()
      local it = first(s:ui():inventory():items()); if not it then return nil end
      local c = it:cell(); if c == nil then return nil end
      return (type(c.x) == "number") and (type(c.y) == "number")
    end)
    g.done("the freed words still mean what they were freed for")
  end)

  manualCheck("open a crafting recipe and run :t089 again",
              "the s:craft() line passing with exists=true, and the recipe name it prints matching the"
                .. " window's title -- with nothing open it passes with exists=false, which is the half"
                .. " a wrapper could not do")
  manualCheck("read the consent dialog for this addon",
              "the line beside craft.make reading session:craft():make, not session:craft():current():make")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():on("t089", run)                  -- the only way in: a suite does not start itself
