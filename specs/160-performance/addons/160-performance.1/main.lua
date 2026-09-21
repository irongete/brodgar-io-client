-- 160.1 — Performance: the store, the panel and options:performance(). Self-checking suite.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why. Every string in `want` must be found.
local function refuses(what, fn, want)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  local matched = not ok
  for _, w in ipairs(want) do
    if not (matched and err:find(w, 1, true)) then matched = false end
  end
  check(matched, what, err)
end

-- Several refusals reported as ONE line, so a run this dense stays inside the suite's own line budget.
local function refusesAll(what, cases)
  local bads = {}
  for _, case in ipairs(cases) do
    local ok, err = pcall(case.fn)
    err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
    local matched = not ok
    for _, w in ipairs(case.want) do
      if not (matched and err:find(w, 1, true)) then matched = false end
    end
    if not matched then
      bads[#bads + 1] = case.label .. ": " .. err
    end
  end
  check(#bads == 0, what, table.concat(bads, " | "))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- Read once, at file scope, before :t160 is ever run -- a suite never starts itself. This is what
-- closes the panel<->write loop across a :reload: the manual check below sets the panel through
-- Lua, the maintainer :reloads, and this run's first line reports what class init found on disk.
local flavorAtLoad = hafen.client():options():performance():flavor()

local function run()
  check(type(flavorAtLoad) == "number", "flavor at load: " .. tostring(flavorAtLoad), flavorAtLoad)

  local performance = hafen.client():options():performance()

  -- Remember every value so the suite can restore them before its [summary] line.
  local flavor0, crops0, forage0 = performance:flavor(), performance:crops(), performance:forage()
  local groundBlend0, transitions0 = performance:groundBlend(), performance:transitions()
  local treeEffects0, smoke0 = performance:treeEffects(), performance:smoke()
  local clouds0, rain0, snow0 = performance:clouds(), performance:rain(), performance:snow()
  local wetGround0, seasonTint0 = performance:wetGround(), performance:seasonTint()

  eq("the handle is one object", performance == hafen.client():options():performance(), true)
  eq("tostring names it", tostring(performance), "Options(performance)")

  performance:flavor(37):crops(42):forage(55)
  performance:groundBlend(false):transitions(false):treeEffects(false):smoke(false)
  performance:clouds(false):rain(false):snow(false):wetGround(false):seasonTint(false)
  check(performance:flavor() == 37 and performance:crops() == 42 and performance:forage() == 55
        and performance:groundBlend() == false and performance:transitions() == false
        and performance:treeEffects() == false and performance:smoke() == false
        and performance:clouds() == false and performance:rain() == false and performance:snow() == false
        and performance:wetGround() == false and performance:seasonTint() == false,
        "the round trip of all twelve values",
        string.format("flavor=%s crops=%s forage=%s groundBlend=%s transitions=%s treeEffects=%s"
                       .. " smoke=%s clouds=%s rain=%s snow=%s wetGround=%s seasonTint=%s",
                       tostring(performance:flavor()), tostring(performance:crops()), tostring(performance:forage()),
                       tostring(performance:groundBlend()), tostring(performance:transitions()),
                       tostring(performance:treeEffects()), tostring(performance:smoke()),
                       tostring(performance:clouds()), tostring(performance:rain()), tostring(performance:snow()),
                       tostring(performance:wetGround()), tostring(performance:seasonTint())))

  local chained = performance:rain(false):snow(false)
  eq("chaining: performance:rain(false):snow(false) returns the handle", chained == performance, true)

  refusesAll("a number is refused by type and by wholeness", {
    {label = "flavor(\"50\")", fn = function() performance:flavor("50") end,
     want = {"performance:flavor: percent must be a number"}},
    {label = "flavor(50.5)", fn = function() performance:flavor(50.5) end,
     want = {"must be a whole number", "got 50.5"}},
  })

  refusesAll("a range is refused, naming the bounds and the value", {
    {label = "flavor(101)", fn = function() performance:flavor(101) end, want = {"from 0 to 100, got 101"}},
    {label = "flavor(-1)", fn = function() performance:flavor(-1) end, want = {"got -1"}},
    {label = "crops(0)", fn = function() performance:crops(0) end, want = {"from 1 to 100, got 0"}},
    {label = "forage(0)", fn = function() performance:forage(0) end, want = {"from 1 to 100, got 0"}},
  })
  eq("flavor still reads 37 after the range refusals", performance:flavor(), 37)

  refusesAll("a switch is refused by type, and an explicit nil is refused rather than read", {
    {label = "rain(\"no\")", fn = function() performance:rain("no") end,
     want = {"performance:rain: on must be true or false"}},
    {label = "rain(0)", fn = function() performance:rain(0) end,
     want = {"performance:rain: on must be true or false"}},
    {label = "rain(nil)", fn = function() performance:rain(nil) end, want = {"must not be nil"}},
  })

  refuses("an unknown verb is refused, naming what exists",
          function() performance:foo() end,
          {"performance has no verb 'foo'", "flavor", "seasonTint"})

  refusesAll("arity is guarded: at most one argument, and a COLON call", {
    {label = "performance:flavor(1, 2)", fn = function() performance:flavor(1, 2) end,
     want = {"use a COLON call with at most one argument"}},
    {label = "hafen.client():options().performance()", fn = function() hafen.client():options().performance() end,
     want = {"use a COLON call on the options handle"}},
  })

  performance:flavor(flavor0):crops(crops0):forage(forage0)
  performance:groundBlend(groundBlend0):transitions(transitions0):treeEffects(treeEffects0):smoke(smoke0)
  performance:clouds(clouds0):rain(rain0):snow(snow0):wetGround(wetGround0):seasonTint(seasonTint0)
  check(performance:flavor() == flavor0 and performance:crops() == crops0 and performance:forage() == forage0
        and performance:groundBlend() == groundBlend0 and performance:transitions() == transitions0
        and performance:treeEffects() == treeEffects0 and performance:smoke() == smoke0
        and performance:clouds() == clouds0 and performance:rain() == rain0 and performance:snow() == snow0
        and performance:wetGround() == wetGround0 and performance:seasonTint() == seasonTint0,
        "every remembered value was written back and reads equal", nil)

  manualCheck("open Options / Game, then from :lua run"
              .. " hafen.client():options():performance():flavor(37):rain(false) and reopen the panel,"
              .. " then drag the Flavor objects slider to 60, then :reload and run :t160 again",
              "Options opens on Performance, first in the list; after the write the Flavor objects"
              .. " slider reads 37 % and the Rain box is clear; after the drag performance:flavor()"
              .. " reads 60; and this run's first line then reports \"flavor at load: 60\"")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t160", function() hafen.timer():after(0, run) end)
