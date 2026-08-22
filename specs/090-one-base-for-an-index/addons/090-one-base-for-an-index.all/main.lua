-- 090 -- One base for an index. Two rows, both severe, and both silent for every existing caller:
-- nothing breaks, the answers just change. That is why the feature is alone.
--
-- :index() meant two bases one namespace apart. meter:index() was the 1-based HUD position while
-- slot:index() was "the raw 0-based game index" and sp:index() "the wire number 0..3" -- and the
-- action bar spelled the trap out as a feature: s:actionbar():list()[1] == s:actionbar():get(0). So
--
--   for i = 1, 144 do local slot = s:actionbar():get(i) ... end
--
-- was off by one at every slot, silently: get(1) was a real slot, just the wrong one, and get(144)
-- was nil, so the loop LOOKED like it found 143 and did something to each.
--
-- Now :index() is the 1-based position in :list() everywhere, :wire() carries the raw number the
-- server's own message uses, and :get(n) takes the number :index() answers -- so the invariant a
-- reader assumes on first contact holds: :list()[n] == :get(n).
--
-- The proof is that invariant, checked at both ends and in the middle rather than asserted once, plus
-- the round trip through :wire() and back. get(0) is the commonest thing an addon written before this
-- says, so it must RAISE and name the change rather than answer the wrong slot.

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

local function run()
  local s = hafen.session():current()

  -------------------------------------------------------------------------------------------------
  -- A-072 the invariant. :list()[n] == :get(n), checked at both ends and in the middle.
  -------------------------------------------------------------------------------------------------
  section("A-072 the invariant", function()
    local bar = s:actionbar()
    local l = bar:list()
    if #l == 0 then
      pass = pass + 1
      hafen.log():write("[pass] :list()[n] == :get(n) (0/0 reached -- the bar has not streamed in)")
      return
    end
    local g = scored()
    for _, n in ipairs({1, 2, math.floor(#l / 2), #l - 1, #l}) do
      if (n >= 1) and (n <= #l) then
        g.want("n=" .. n, function() return l[n] == bar:get(n) end)
      end
    end
    g.done("s:actionbar():list()[n] == s:actionbar():get(n), at both ends and the middle")
  end)

  -------------------------------------------------------------------------------------------------
  -- A-071 :index() is the position, :wire() is the raw number, and the two round-trip.
  -------------------------------------------------------------------------------------------------
  section("A-071 the two numbers", function()
    local g = scored()
    g.want("slot:index() is its position", function()
      local l = s:actionbar():list(); if #l == 0 then return nil end
      return (l[1]:index() == 1) and (l[#l]:index() == #l)
    end)
    g.want("slot:wire() is index - 1", function()
      local sl = s:actionbar():get(1); if not sl then return nil end
      return sl:wire() == (sl:index() - 1)
    end)
    g.want("sp:index() is 1..4 and sp:wire() 0..3", function()
      local l = s:speed():list(); if #l == 0 then return nil end
      local sp = l[1]
      return (sp:index() >= 1) and (sp:index() <= 4) and (sp:wire() == sp:index() - 1)
    end)
    g.want("s:speed():get(sp:index()) is that speed", function()
      local sp = s:speed():current(); if not sp then return nil end
      return s:speed():get(sp:index()) == sp
    end)
    g.want("the Speed snapshot agrees with its verbs", function()
      local sp = s:speed():current(); if not sp then return nil end
      local i = sp:info(); if not i then return nil end
      return (i.index == sp:index()) and (i.wire == sp:wire())
    end)
    g.want("card:index() is its position in the deck", function()
      local d = s:fight():deck(); if #d == 0 then return nil end
      return (d[1]:index() == 1) and (d[1]:wire() == 0)
    end)
    g.want("meter:index() was already the position and did not move", function()
      local m = s:meter():list()[1]; if not m then return nil end
      return m:index() == 1
    end)
    g.done("an index is a position and a wire number is the server's, everywhere")
  end)

  -------------------------------------------------------------------------------------------------
  -- The old call must RAISE and name the change, not answer the wrong slot.
  -------------------------------------------------------------------------------------------------
  section("the old call", function()
    local g = refusals()
    g.ask("s:actionbar():get(0)", function() return s:actionbar():get(0) end,
          "1-based position")
    g.ask("s:actionbar():get(0) names :wire()", function() return s:actionbar():get(0) end,
          "slot:wire()")
    g.ask("s:actionbar():get(145)", function() return s:actionbar():get(145) end, "out of range")
    g.ask("s:speed():set(0)", function() return s:speed():set(0) end, "1..4")
    g.done("the number an addon written before this passes raises, naming the change")

    check(s:speed():get(0) == nil,
          "s:speed():get(0) is a plain nil miss, as its page has always said",
          s:speed():get(0))
  end)

  -------------------------------------------------------------------------------------------------
  -- Nothing else moved: the collection quartet and identity are untouched.
  -------------------------------------------------------------------------------------------------
  section("unmoved", function()
    local g = scored()
    g.want("Slot identity still holds", function()
      local a = s:actionbar():get(1); if not a then return nil end
      return a == s:actionbar():get(1)
    end)
    g.want("s:speed():get(name) still works", function()
      local sp = s:speed():get("run"); if not sp then return nil end
      return sp:name():lower():find("run", 1, true) ~= nil
    end)
    g.want("s:actionbar():count() is the whole bar", function()
      local c = s:actionbar():count()
      return (c == 144) or (c == 0)
    end)
    g.done("identity, the name key and the quartet are untouched")
  end)

  manualCheck("put something on the FIRST action-bar slot and run :t090 again",
              "s:actionbar():get(1) naming that thing -- before this feature get(1) was the SECOND"
                .. " slot, and only you can see which button the game drew it on")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():on("t090", run)                  -- the only way in: a suite does not start itself
