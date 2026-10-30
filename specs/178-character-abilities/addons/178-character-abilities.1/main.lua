-- 178.1 — the character's abilities. Self-checking suite.

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

local function message(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local ABILITIES = { "unarmed", "melee", "ranged", "explore", "stealth", "sewing", "smithing",
                    "masonry", "carpentry", "cooking", "farming", "survive", "lore" }
local IS_ABILITY = {}
for _, name in ipairs(ABILITIES) do IS_ABILITY[name] = true end

local poll

-- The first member that differs from what an Ability must answer, or nil.
local function firstBadAbility(abilities)
  for _, name in ipairs(ABILITIES) do
    local ability = abilities:get(name)
    if not ability then return name .. ": get answered nil" end
    if ability:name() ~= name then return name .. ": name() " .. tostring(ability:name()) end
    if tostring(ability) ~= "Ability(" .. name .. ")" then return name .. ": tostring " .. tostring(ability) end
    local base, comp = ability:base(), ability:composite()
    if type(base) ~= "number" or base < 1 then return name .. ": base() " .. tostring(base) end
    if type(comp) ~= "number" then return name .. ": composite() " .. tostring(comp) end
    local info = ability:info()
    if type(info) ~= "table" or info.base ~= base or info.comp ~= comp then
      return name .. ": info() " .. (type(info) == "table" and (tostring(info.base) .. "/" .. tostring(info.comp)) or tostring(info))
    end
  end
  return nil
end

-- Each refusal must fail and say why; the labels of those that did not.
local function missedRefusals(char)
  local cases = {
    { "get(\"str\") names attr()", function() return char:ability():get("str") end, "session:char():attr()" },
    { "get(\"smithin\") lists the thirteen", function() return char:ability():get("smithin") end, "unarmed, melee, ranged" },
    { "get(3) wants an ability name", function() return char:ability():get(3) end, "expected an ability name" },
    { "ability(1) takes no arguments", function() return char:ability(1) end, "takes no arguments" },
    { ":bogus() names an ability", function() return char:ability():get("smithing"):bogus() end, "a character ability" },
    { "attr():get(\"smithing\") names ability()", function() return char:attr():get("smithing") end, "session:char():ability()" },
  }
  local missed = {}
  for _, case in ipairs(cases) do
    local ok, err = pcall(case[2])
    local text = ok and "<no error>" or message(err)
    if ok or not text:find(case[3], 1, true) then missed[#missed + 1] = case[1] .. " (" .. text .. ")" end
  end
  return missed
end

-- One recipe's quality inputs, resolved against both collections: true when it was scored, false to keep polling.
local function scoreRecipe(session)
  local craft = session:craft()
  if not craft:exists() then return false end
  local inputs = craft:qualityInputs():list()
  if #inputs == 0 then return false end
  local char = session:char()
  local parts, bad, product = {}, nil, 1
  for _, spec in ipairs(inputs) do
    local res = spec:res()
    if res == nil then return false end                   -- still loading: try again
    local name = res:match("^gfx/hud/chr/(.+)$")
    local stat = name and (IS_ABILITY[name] and char:ability():get(name) or char:attr():find(name))
    local comp = stat and stat:composite()
    if not name or not stat or stat:name() ~= name or type(comp) ~= "number" then
      bad = bad or (res .. " -> " .. tostring(name) .. "=" .. tostring(comp))
    else
      parts[#parts + 1] = name .. "=" .. comp
      product = product * comp
    end
  end
  local cap = math.floor(product ^ (1 / #inputs) + 1e-9)
  check(bad == nil, ("recipe %s: every quality input is gfx/hud/chr/<attr or ability> (%s; softcap %d)")
    :format(tostring(craft:recipe()), table.concat(parts, " "), cap), bad)
  return true
end

local function run()
  if poll then poll:cancel() end
  pass, fail, manual = 0, 0, 0
  local session = hafen.session():current()
  local char = session:char()
  local abilities = char:ability()

  local bad = firstBadAbility(abilities)
  check(bad == nil, "all thirteen get(name): name, tostring Ability(name), base >= 1, composite, info", bad)

  local names = {}
  for _, ability in ipairs(abilities:list()) do names[#names + 1] = ability:name() end
  local listed = table.concat(names, ",")
  check(listed == table.concat(ABILITIES, ",") and abilities:count() == #names
          and abilities:find("smith") == abilities:get("smithing"),
        "list() is the thirteen in sheet order, count() == #list(), find(\"smith\") is smithing",
        listed .. " count=" .. tostring(abilities:count()))

  local shared = nil
  for _, attr in ipairs(char:attr():list()) do
    for _, ability in ipairs(abilities:list()) do
      if attr == ability then shared = tostring(attr) end
    end
  end
  check(char:ability() == abilities and abilities:get("smithing") == abilities:get("smithing")
          and shared == nil and tostring(char:attr():get("str")) == "Attr(str)",
        "identity: one collection, interned members, no Ability == an Attr, Attr(str) unchanged",
        shared or tostring(char:attr():get("str")))

  local leaked = {}
  for _, attr in ipairs(char:attr():list()) do
    if IS_ABILITY[attr:name()] then leaked[#leaked + 1] = attr:name() end
  end
  check(#leaked == 0 and #char:attr():list() > 0, "attr():list() holds base attributes only (" .. #char:attr():list() .. ")",
        table.concat(leaked, ","))

  local missed = missedRefusals(char)
  check(#missed == 0, "six refusals fail naming why", table.concat(missed, "; "))

  local function value(name)
    return tostring(abilities:get(name):composite())
  end
  manualCheck(("open any recipe now, within 30 s; then read the Abilities tab, no + pending: smithing=%s carpentry=%s cooking=%s")
    :format(value("smithing"), value("carpentry"), value("cooking")), "equal to the tab")

  local polls = 0                                   -- 60 polls of half a second: the 30 s window
  poll = hafen.timer():every(0.5, function()
    polls = polls + 1
    local current = hafen.session():current()
    local ok, scored = pcall(scoreRecipe, current)
    if not ok then
      check(false, "the recipe check ran", message(scored))
      scored = true
    end
    if scored or polls >= 60 then
      poll:cancel()
      if not scored then check(false, "a recipe with quality inputs was opened within 30 s", "no recipe opened") end
      summary()
    end
  end)
end

-- The only way in: a suite does not start itself.
hafen.console():on("t178", function() hafen.timer():after(0, run) end)
