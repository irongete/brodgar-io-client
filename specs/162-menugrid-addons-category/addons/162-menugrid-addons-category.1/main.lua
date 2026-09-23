-- 162.1 — the AddOns category: every addon entry hangs inside it. Self-checking suite.

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

local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function holds(collection, member)
  for _, entry in ipairs(collection:list()) do
    if entry == member then return true end
  end
  return false
end

local function checks(menugrid, leaf, cat, child)
  local addons = leaf:parent()
  local res = addons and addons:res()
  local name = addons and addons:name()
  check(res == "addon/" and name == "AddOns", "a fresh :add hangs under AddOns (addon/, AddOns)",
        tostring(res) .. ", " .. tostring(name))
  if not addons then return end

  local byRes, byName = menugrid:get("addon/"), menugrid:get("AddOns")
  check(holds(menugrid:roots(), byRes) and not holds(menugrid:roots(), leaf) and byRes == byName,
        ":roots() holds AddOns and not the entry; get(\"addon/\") == get(\"AddOns\")",
        tostring(byRes) .. " / " .. tostring(byName))

  check(addons:addon() == nil and addons:exists() and holds(addons:children(), leaf),
        "AddOns: :addon() nil, :exists(), :children() holds the entry",
        tostring(addons:addon()) .. ", " .. tostring(addons:exists()))

  child:parent(cat)
  check(child:parent() == cat and cat:parent() == addons, "a category of yours nests inside AddOns",
        tostring(child:parent()) .. " / " .. tostring(cat:parent()))

  leaf:parent(cat)
  leaf:parent(nil)
  local afterNil = leaf:parent()
  leaf:parent(cat)
  leaf:parent(addons)
  check(afterNil == addons and leaf:parent() == addons, ":parent(nil) and :parent(addons) both put it back",
        tostring(afterNil) .. " / " .. tostring(leaf:parent()))

  local game
  for _, root in ipairs(menugrid:roots():list()) do
    if root ~= addons then game = root; break end
  end
  if game then
    refuses("a game category as parent is refused naming AddOns", function() leaf:parent(game) end, "AddOns")
  else
    check(false, "a game category as parent is refused naming AddOns", "no game root in the menu")
  end

  refuses("a write on AddOns is refused as the client's own", function() addons:name("x") end, "client's own")

  menugrid:remove(cat)
  check(child:parent() == addons, "removing your category puts its child back under AddOns",
        tostring(child:parent()))
end

local function run()
  pass, fail, manual = 0, 0, 0
  local session = hafen.session():current()
  if not session then
    check(false, "a character is on screen", "nil session")
  else
    local menugrid = session:menugrid()
    for _, id in ipairs({ "t162/leaf", "t162/cat", "t162/child" }) do
      menugrid:remove(id)                        -- a leftover of an earlier run; inert when gone
    end
    local leaf = menugrid:add("t162/leaf"):name("t162 leaf")
    local cat = menugrid:add("t162/cat"):name("t162 cat")
    local child = menugrid:add("t162/child"):name("t162 child")
    local ok, err = pcall(checks, menugrid, leaf, cat, child)
    if not ok then check(false, "the checks ran to the end", why(err)) end
    menugrid:remove("t162/cat")
    menugrid:remove("t162/child")
    manualCheck("open the action menu on its root screen and click AddOns",
                "one AddOns button with the blue dolmen, opening onto the entry t162 leaf")
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t162", function()
  hafen.timer():after(0, run)                    -- off the typed tree's monitor before the menu is touched
end)
