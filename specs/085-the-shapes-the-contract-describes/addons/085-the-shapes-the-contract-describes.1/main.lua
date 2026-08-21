-- 085.1 -- where a shape is written down. Self-checking suite.
--
-- The new page describes the code, so every claim on it is checkable against the running client. What is
-- asserted here is exactly what api/shapes.md states: a 64-bit id crosses as a DECIMAL STRING (and the grid
-- round trip is what the rule exists for), a store table is the third kind of value -- one the bridge owns
-- and you write into -- a name ending Fraction is 0..1, and the anonymous shapes carry the keys the table
-- says they carry.
--
-- A marker and an item need the world and an open container, so the run waits for a bounded window and
-- scores what it reached rather than failing on what the server had not sent.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it
-- did not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local msg = said(fn)
  check((msg ~= nil) and (msg:find(wantMsg, 1, true) ~= nil), what, msg or "<no error>")
end

-- Every key of `want` is present on `t` and holds a number. Answers the offender, or nil.
local function keys(t, want)
  if type(t) ~= "table" then return "not a table: " .. type(t) end
  for _, k in ipairs(want) do
    if type(t[k]) ~= "number" then return "." .. k .. " is " .. type(t[k]) end
  end
  return nil
end

-- The four ids the page says are decimal strings, and the round trip the rule exists for. Scored: the
-- marker half needs a pin the client has recorded, and a run that has none still proves the other three.
local function ids(p)
  local seen, missing, wrong = 0, {}, {}
  local gp = p and p:info()
  local grid = gp and hafen.map():grid():get(gp.gridId)
  local seg = hafen.map():segment():current()
  local mk = hafen.map():marker():list()[1]
  local mi = mk and mk:info()
  local got = {
    {"p:info().gridId", gp and gp.gridId},
    {"grid:id()", grid and grid:id()},
    {"seg:id()", seg and seg:id()},
    {"marker:info().seg", mi and mi.seg},
  }
  for _, e in ipairs(got) do
    if e[2] == nil then
      missing[#missing + 1] = e[1]
    else
      seen = seen + 1
      if type(e[2]) ~= "string" then wrong[#wrong + 1] = e[1] .. " is a " .. type(e[2]) end
    end
  end
  check((seen >= 3) and (#wrong == 0),
        ("every 64-bit id crosses as a decimal string (%d/4 reached%s)")
          :format(seen, (#missing > 0) and (", no " .. table.concat(missing, ", ")) or ""),
        (#wrong > 0) and table.concat(wrong, ", ") or ("only " .. seen .. " reached"))

  -- The round trip the whole rule is for: the string out of a stored position addresses the recorded grid.
  check((grid ~= nil) and (grid:id() == gp.gridId),
        "hafen.map():grid():get(p:info().gridId) answers the grid with that very id",
        (grid == nil) and "no grid recorded for this position" or grid:id())
end

-- The third kind of value: not a snapshot, not a proxy -- the table that goes to disk.
local function store()
  local t = hafen.store():get("probe")
  check(type(t) == "table", "hafen.store():get() hands back a plain table", type(t))

  local walked = pcall(function() for _ in pairs(t) do end end)
  local mark = "085-1-" .. tostring(hafen.time():clock() or 0)
  t.shapeProbe = mark
  local again = hafen.store():get("probe")
  check(walked and (again == t) and (again.shapeProbe == mark),
        "pairs walks it, and a write into it is there on a second get",
        tostring(walked) .. " / " .. tostring(again == t) .. " / " .. tostring(again and again.shapeProbe))
  t.shapeProbe = nil                       -- leave the table exactly as it was found

  refuses("hafen.store():get(\"nosuchvariable\") refuses naming manifest.json",
          function() hafen.store():get("nosuchvariable") end, "manifest.json")
end

-- A name ending Fraction is 0..1, which is the unit rule the page states and the four bold corrections
-- on buff, actionbar, study and wound now link to.
local function fractions()
  local bad = {}
  for _, e in ipairs({{"dayFraction", hafen.time():dayFraction()},
                      {"yearFraction", hafen.time():yearFraction()}}) do
    if type(e[2]) ~= "number" then
      bad[#bad + 1] = e[1] .. " is " .. type(e[2]) .. " -- the astronomy update had not arrived"
    elseif (e[2] < 0) or (e[2] > 1) then
      bad[#bad + 1] = e[1] .. " = " .. tostring(e[2])
    end
  end
  check(#bad == 0, "hafen.time():dayFraction() and :yearFraction() are numbers in 0..1 (2/2)",
        table.concat(bad, ", "))
end

-- The anonymous shapes: {x, y} for a place in a lattice, {cur, max} for a pair of counts.
--
-- The pair of counts has two doors, item:durability() and contents:level(), and the shape is the claim
-- rather than which one produced it -- so the run walks every item on screen (widget:items() is deep, and
-- s:ui():root() is the whole tree) plus one level of nesting, and scores over whichever it reached.
local function shapes(s, p)
  check(keys(p:tileCoord(), {"x", "y"}) == nil, "p:tileCoord() carries x and y",
        keys(p:tileCoord(), {"x", "y"}))

  local root = s:ui():root()
  local seen, from, offender = 0, nil, nil
  local function pair(what, t)
    if t == nil then return end
    from = from or what
    offender = offender or keys(t, {"cur", "max"})
  end
  local function walk(items, deep)
    for _, it in ipairs(items or {}) do
      seen = seen + 1
      pair("item:durability()", it:durability())
      local held = it:contents()
      if held ~= nil then
        pair("contents:level()", held:level())
        if deep then walk(held:items(), false) end
      end
    end
  end
  walk(root and root:items(), true)
  check((from ~= nil) and (offender == nil),
        ("a pair of counts carries cur and max (%s, over %d items)"):format(from or "none reached", seen),
        offender or ("nothing printed a wear row or a fill meter -- carry a worn tool, or open a"
          .. " container that states what it holds, and re-run"))
end

local function run()
  local tries = 0
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    local g = s and s:exists() and s:player() and s:player():gob()
    local p = g and g:position()
    -- The astronomy readers answer nil until the first astro update, a beat behind entering the world.
    if (tries < 20) and ((p == nil) or (hafen.time():dayFraction() == nil)) then return end
    t:cancel()
    if p == nil then
      check(false, "a character in the world", "none reached in 10s -- log in and re-run")
    else
      ids(p)
      shapes(s, p)
    end
    store()
    fractions()
    hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
  end)
end

hafen.slash():register("t085-1", run)   -- the only way in: a suite does not start itself
