-- 098 -- the vr snapshot. e:info() on the one family that had not got it.
--
-- WHAT WAS MISSING. conventions.md states the rule universally: every live object in this API answers
-- :info(), the one snapshot escape hatch, the whole state at once as a plain table. The vr entities -- the
-- ghosts, sprites, objects and panels an addon stands in the world -- were the only family that did not.
-- Logging what you had planted cost ten calls per entity, and the profiler paid it.
--
-- Purely additive: nothing retires, nothing changes shape, no addon already written notices.
--
-- WHAT THE SUITE HOLDS IT TO. A snapshot that disagrees with the verbs beside it is worse than none, so
-- every field is checked AGAINST the verb that reads it rather than against a constant. The place comes out
-- flat -- the {gridId, x, y} form p:info() answers -- because a live object inside a snapshot is not a
-- snapshot. And a key is ABSENT when the thing it names is: no tint when none is laid over it, no anchor
-- for one that stands still, exactly as :offset() itself raises there.

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

local function finish()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
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

local function scored()
  local n, total, why = 0, 0, nil
  local g = {}
  function g.want(label, fn)
    local ok, r = pcall(fn)
    if ok and (r == nil) then return end
    total = total + 1
    if ok and r then n = n + 1 else why = why or (label .. " -> " .. tostring(r)) end
  end
  function g.done(what) check(n == total, what .. " (" .. n .. "/" .. total .. ")", why or n) end
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

local GHOST = "gfx/terobjs/arch/logcabin"

local function run()
  -- An entity is ended through the collection of its kind: hafen.vr():ghost():remove(e). There is no
  -- e:destroy(), which is deliberate -- ending a member of a set is the set's verb, everywhere.
  local made = {}
  local function keep(coll, e) made[#made + 1] = {coll, e}; return e end
  local function drop()
    for _, m in ipairs(made) do pcall(function() m[1]:remove(m[2]) end) end
  end

  local cur = hafen.session():current()
  if cur == nil then
    check(false, "a character is logged in", "no current session -- run this in the world")
    return finish()
  end
  local here = cur:player():gob():position()
  if here == nil then
    check(false, "the player has a place to stand things at", "nil position")
    return finish()
  end

  -------------------------------------------------------------------------------------------------
  -- The ten shared readers, each checked AGAINST ITS OWN VERB. A snapshot is only worth having if it
  -- cannot drift from the thing it is a snapshot of.
  -------------------------------------------------------------------------------------------------
  local g1 = section("the shared ten", function()
    local e = keep(hafen.vr():ghost(), hafen.vr():ghost():add(GHOST, here):alpha(0.5):scale(2):rotate(1))
    local i = e:info()
    local g = scored()
    g.want("info() hands back a table", function() return type(i) == "table" end)
    g.want("kind", function() return i.kind == "ghost" end)
    g.want("rotate agrees with :rotate()", function() return i.rotate == e:rotate() end)
    g.want("scale agrees with :scale()", function() return i.scale == e:scale() end)
    g.want("alpha agrees with :alpha()", function() return i.alpha == e:alpha() end)
    g.want("visible agrees with :visible()", function() return i.visible == e:visible() end)
    g.want("clickable agrees with :clickable()", function() return i.clickable == e:clickable() end)
    g.want("exists agrees with :exists()", function() return i.exists == e:exists() end)
    g.want("drawn agrees with :drawn()", function() return i.drawn == e:drawn() end)
    g.want("and the writes really landed", function()
      return (i.alpha == 0.5) and (i.scale == 2) and (i.rotate == 1)
    end)
    g.done("every shared field answers what its own verb does")
    return e
  end)

  -------------------------------------------------------------------------------------------------
  -- The place is FLAT: the {gridId, x, y} form p:info() answers, not a Position object.
  -------------------------------------------------------------------------------------------------
  section("the place", function()
    local e = g1
    if e == nil then return end
    local i = e:info()
    local p = e:position():info()
    local g = scored()
    g.want("position is a plain table", function() return type(i.position) == "table" end)
    g.want("it is not a live object", function() return type(i.position.gridId) == "string" end)
    g.want("gridId agrees with p:info()", function() return i.position.gridId == p.gridId end)
    g.want("x and y agree with p:info()", function()
      return (i.position.x == p.x) and (i.position.y == p.y)
    end)
    g.done("the place comes out as the Position snapshot, not as a Position")
  end)

  -------------------------------------------------------------------------------------------------
  -- ABSENT MEANS UNSET. tint appears only once something is laid over it; anchor and offset only for
  -- one that follows a gob -- which is the same line :offset() itself draws when it raises.
  -------------------------------------------------------------------------------------------------
  section("absent means unset", function()
    local e = g1
    if e == nil then return end
    local g = scored()
    g.want("a standing entity carries no anchor", function() return e:info().anchor == nil end)
    g.want("and no offset, as :offset() itself refuses there", function() return e:info().offset == nil end)
    g.want("no tint before one is laid over it", function() return e:info().tint == nil end)
    e:tint({255, 0, 0, 128})
    g.want("and a tint after", function()
      local t = e:info().tint
      return (type(t) == "table") and (t.r == 255)
    end)
    g.done("a key is there when the thing it names is, and not before")
  end)

  -------------------------------------------------------------------------------------------------
  -- Each kind adds the verbs only it answers, under the key its verb is spelled with. A panel's other
  -- verb, :screen(x, y), projects a point you pass in -- so there is no value of it to photograph.
  -------------------------------------------------------------------------------------------------
  section("what each kind adds", function()
    local g = scored()
    local gh = g1
    if gh ~= nil then
      g.want("a ghost carries res", function() return gh:info().res == GHOST end)
      g.want("and nothing of another kind", function()
        local i = gh:info()
        return (i.mesh == nil) and (i.image == nil) and (i.facing == nil)
      end)
    end
    local w = hafen.ui():window():title("098"):size(120, 40)
    local panel = keep(hafen.vr():widget(), hafen.vr():widget():add(w, here))
    g.want("a panel carries facing", function() return panel:info().facing == panel:facing() end)
    g.want("and no res or mesh", function()
      local i = panel:info()
      return (i.res == nil) and (i.mesh == nil)
    end)
    g.want("kind names the panel, not its collection", function() return panel:info().kind == "panel" end)
    g.done("each kind contributes its own verbs and only those")
  end)

  -------------------------------------------------------------------------------------------------
  -- It is a snapshot and it takes nothing: the one-at-a-time reads are the verbs beside it.
  -------------------------------------------------------------------------------------------------
  section("the arity", function()
    local e = g1
    if e == nil then return end
    local r = refusals()
    r.ask("an argument is refused", function() return e:info(1) end, "takes no arguments")
    r.ask("and it says where the one-at-a-time reads are", function() return e:info(1) end, "verb of its own")
    r.done("info() takes no arguments and says what to use instead")

    local g = scored()
    local before = e:info()
    e:alpha(0.25)
    g.want("the table does not go on updating", function() return before.alpha ~= e:alpha() end)
    g.want("a fresh one does", function() return e:info().alpha == 0.25 end)
    g.done("the snapshot is frozen and the verb is live")
  end)

  -------------------------------------------------------------------------------------------------
  -- A dead entity still answers, which is what makes info() usable in a teardown log.
  -------------------------------------------------------------------------------------------------
  section("after it is gone", function()
    local e = hafen.vr():ghost():add(GHOST, here)
    hafen.vr():ghost():remove(e)
    local g = scored()
    g.want("info() still answers", function() return type(e:info()) == "table" end)
    g.want("and says it is gone", function()
      local i = e:info()
      return (i.exists == false) and (i.drawn == false)
    end)
    g.done("a destroyed entity is still readable, and says so")
  end)

  drop()
  manualCheck("look at the world where you stand: this suite put a log cabin ghost and a small window"
                .. " panel there and removed both before printing this",
              "nothing of the suite's left standing -- no translucent cabin, no floating 098 window")
  finish()
end

hafen.slash():on("t098", run)                  -- the only way in: a suite does not start itself
