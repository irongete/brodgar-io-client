-- 085.2 -- one colour, one shape. Self-checking suite.
--
-- Every colour this API hands back is the keyed {r=,g=,b=,a=} table, and every colour write takes the
-- table -- keyed or positional -- and nothing else. The three reads that used to be positional are the
-- point of the run, and BOTH directions are asserted on each: .r answers AND [1] does not, since only the
-- pair proves the shape moved rather than widened.
--
-- Going in, three separate parsers each carried the loose branch, so all three are called: a change to one
-- of them alone would leave marker:color(200, 210, 220) and rule:color(200, 210, 220) taking it.
--
-- The overlay, the ghost, the kin and the meter need a character in the world, so the run waits for a
-- bounded window. The marker half mints its own pin and removes it again, so the run asks nothing of the
-- map database it does not put back.

local PROBE = "085.2 probe"                -- the name this suite's own pin answers to, so a leftover is
                                           -- identifiable and the next run sweeps it

local pass, fail, manual = 0, 0, 0
local painter                              -- the HUD overlay this run put up, so a re-run replaces it

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

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function refuses(what, fn, wantMsg)
  local msg = said(fn)
  check((msg ~= nil) and (msg:find(wantMsg, 1, true) ~= nil), what, msg or "<no error>")
end

-- A colour write refusing the loose form: it must fail, it must say what a colour IS, and it must not be
-- the old refusal that asked for three or four numbers. Answers whether the call was taken after all, so
-- a write that should not have landed can be put back.
local function refusesColour(what, fn)
  local msg = said(fn)
  local ok = (msg ~= nil)
    and (msg:find("a colour is a table", 1, true) ~= nil)
    and (msg:find("{", 1, true) ~= nil)
    and (msg:find("expects three or four numbers", 1, true) == nil)
  check(ok, what, msg or "<no error>")
  return msg == nil
end

-- The one output shape. Answers nil, or what is wrong with the value.
local function keyed(c)
  if type(c) ~= "table" then return "a " .. type(c) end
  for _, k in ipairs({"r", "g", "b", "a"}) do
    if type(c[k]) ~= "number" then return "." .. k .. " is " .. type(c[k]) end
  end
  return nil
end

local function is(c, r, g, b)
  return (type(c) == "table") and (c.r == r) and (c.g == g) and (c.b == b)
end

-- One scored pair of verdicts over a set of colour reads: every one is keyed, and none answers [1].
local function shapeOf(label, got)
  local seen, missing, wrong, loose = 0, {}, {}, {}
  for _, e in ipairs(got) do
    if e[2] == nil then
      missing[#missing + 1] = e[1]
    else
      seen = seen + 1
      local bad = keyed(e[2])
      if bad then wrong[#wrong + 1] = e[1] .. " is " .. bad end
      if (type(e[2]) == "table") and (e[2][1] ~= nil) then
        loose[#loose + 1] = e[1] .. "[1] = " .. tostring(e[2][1])
      end
    end
  end
  local reach = ("(%d/%d reached%s)"):format(seen, #got,
    (#missing > 0) and (", no " .. table.concat(missing, ", ")) or "")
  check((seen == #got) and (#wrong == 0), label .. " answer keyed " .. reach,
        (#wrong > 0) and table.concat(wrong, ", ") or ("only " .. seen .. " reached"))
  check((seen == #got) and (#loose == 0), "...and [1] is nil on every one of them " .. reach,
        (#loose > 0) and table.concat(loose, ", ") or ("only " .. seen .. " reached"))
end

-- This suite's own player pin, minted at `p` and taken out again before the run ends: a marker:color() to
-- read and a marker:color(200, 210, 220) to refuse, without a pin of the maintainer's being touched or a
-- setup step being asked for. A leftover from a run that died mid-way is swept first, by name.
local function probePin(p)
  local pins = hafen.map():marker()
  for _ = 1, 8 do                                    -- bounded: a pin that will not go is not swept forever
    local old = pins:find(PROBE)
    if old == nil then break end
    pins:remove(old)
  end
  local mk = pins:add(PROBE, p)
  return (mk ~= nil) and mk:exists() and mk or nil    -- nil while the map database is not up yet
end

local function colours(s, me, p)
  -- ---- out: the three that were positional, and a sample of the ones already keyed -----------------
  local h = hafen.font():get("sans"):derive()
  h:color{200, 210, 220}

  local ov = me:overlay():add("085c")
  ov:color{200, 210, 220}

  local ghost = hafen.vr():ghost():add("gfx/terobjs/arch/logcabin", p)
  ghost:visible(false)                     -- read its tint back without putting a cabin on the screen
  ghost:tint{200, 210, 220}

  local kin = s:kin():list()[1]
  local hp = s:meter():find("hp")
  local mk = probePin(p)

  shapeOf("the three that were positional -- ov:color(), h:color(), e:tint() --",
          {{"ov:color()", ov:color()}, {"h:color()", h:color()}, {"e:tint()", ghost:tint()}})
  shapeOf("the readers that were already keyed",
          {{"kin:color()", kin and kin:color()}, {"meter:color()", hp and hp:color()},
           {"marker:color()", mk and mk:color()}})

  -- ---- in: the loose form is gone at all three parsers ---------------------------------------------
  refusesColour("overlay:color(200, 210, 220) is refused, naming the table",
                function() ov:color(200, 210, 220) end)

  if mk then
    refusesColour("marker:color(200, 210, 220) is refused, naming the table",
                  function() mk:color(200, 210, 220) end)
  else
    check(false, "marker:color(200, 210, 220) is refused, naming the table",
          "the map database had no pin to mint -- re-run once the map is up")
  end

  refusesColour("rule:color(200, 210, 220) is refused, naming the table",
                function() hafen.ui():sheet():rule("tooltip"):color(200, 210, 220) end)

  -- ---- in: both table spellings are taken, and read back keyed either way --------------------------
  ov:color{200, 210, 220}
  local positional = ov:color()
  ov:color{r = 200, g = 210, b = 220}
  local named = ov:color()
  check(is(positional, 200, 210, 220), "ov:color{200, 210, 220} goes in and reads back keyed",
        keyed(positional) or tostring(positional and positional.r))
  check(is(named, 200, 210, 220), "ov:color{r = 200, g = 210, b = 220} goes in and reads back keyed",
        keyed(named) or tostring(named and named.r))

  -- ---- a read passes straight back into a write ----------------------------------------------------
  local from = kin and "kin:color()" or (hp and "meter:color()")
  local src = kin and kin:color() or (hp and hp:color())
  if src then
    local err = said(function() ov:color(src) end)
    local back = ov:color()
    check((err == nil) and is(back, src.r, src.g, src.b),
          "ov:color(" .. from .. ") is one expression", err or tostring(back and back.r))
  else
    check(false, "ov:color(kin:color()) is one expression",
          "neither a kin nor an hp meter to read a colour from -- re-run in the world")
  end

  me:overlay():remove("085c")
  hafen.vr():ghost():remove(ghost)
  if mk then hafen.map():marker():remove(mk) end     -- the database is left exactly as it was found
end

-- The refusal that sits beside this code and must still fire: a font handle a consumer has read is sealed,
-- whatever is being written to it.
local function ownership()
  local h = hafen.font():get("sans"):derive():size(12)
  local w = hafen.ui():widget():size(80, 20):font(h)   -- a consumer reads it here...
  w:destroy()                                          -- ...and it never draws: built and destroyed at once
  refuses("a font handle already in use still refuses h:color, naming :derive()",
          function() h:color{200, 210, 220} end, "h:derive()")
end

-- The one thing a program cannot read back: what the draw context actually painted.
local function squares()
  if painter and painter:exists() then painter:destroy() end
  painter = hafen.ui():overlay()
  painter:onDraw(function(g, w, h)
    g:color(40, 40, 44)
    g:frect(8, 8, 84, 32)
    g:color(200, 60, 60)                 -- loose components: the one place a colour is still written so
    g:frect(14, 14, 12, 12)
    g:color{r = 200, g = 60, b = 60}     -- the same colour, as the table every reader hands back
    g:frect(34, 14, 12, 12)
    g:color(230, 230, 230)
    g:text("085.2", 54, 15)
    g:color()
  end)
  manualCheck("look at the two squares in the top-left corner of the screen, left of the 085.2 label",
              "the two are the SAME red -- a black right-hand square is the bug this task closes")
end

local function run()
  local tries = 0
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    local me = s and s:exists() and s:player() and s:player():gob()
    local p = me and me:position()
    if (tries < 20) and (p == nil) then return end
    t:cancel()
    if p == nil then
      check(false, "a character in the world", "none reached in 10s -- log in and re-run")
    else
      colours(s, me, p)
    end
    ownership()
    squares()
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t085-2", run)   -- the only way in: a suite does not start itself
