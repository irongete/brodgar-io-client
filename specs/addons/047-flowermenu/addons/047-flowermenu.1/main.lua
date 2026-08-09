-- 047.1 — hafen.flowermenu() reads + FlowerMenuOpened/FlowerMenuClosed.
-- Self-checking suite; see specs/addons/TESTING.md.
--
-- An open radial menu grabs mouse AND keyboard, so `:t047-1` cannot be typed while one is up. The command
-- therefore runs in two halves: `:t047-1` asserts everything a program can see with no menu open and ARMS
-- the two handlers, which then assert and print by themselves as the maintainer opens menus; `:t047-1 done`
-- closes the run with the cross-round invariants and the summary.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- ---- the armed half ------------------------------------------------------------------------------

local subs = {}          -- live subscriptions, so a second `:t047-1` does not double-arm
local opens, closes = 0, 0
local pending = false    -- an Opened is waiting for its Closed
local doubles, orphans = 0, 0
local labelled, blank = 0, 0

local function describe(t)
  if type(t) ~= "table" then return tostring(t) end
  local parts = {}
  for i = 1, #t do parts[i] = tostring(t[i]) end
  return "{" .. table.concat(parts, " | ") .. "}"
end

local function onOpened(petals)
  opens = opens + 1
  if pending then doubles = doubles + 1 end
  pending = true
  local bad = nil
  if type(petals) ~= "table" then
    bad = "payload is a " .. type(petals)
  elseif #petals == 0 then
    bad = "empty array"
  else
    for i = 1, #petals do
      if type(petals[i]) ~= "string" then bad = "petal " .. i .. " is a " .. type(petals[i]) end
    end
  end
  check(bad == nil, "FlowerMenuOpened carried the captions: " .. describe(petals), bad)

  local live, cnt = hafen.flowermenu():list(), hafen.flowermenu():count()
  local same = (bad == nil) and (#live == #petals) and (cnt == #petals)
  if same then
    for i = 1, #petals do if live[i] ~= petals[i] then same = false end end
  end
  check(same, "...and :list()/:count() read inside the handler agree with it",
        describe(live) .. " / " .. tostring(cnt))
end

local function onClosed(label)
  closes = closes + 1
  if not pending then orphans = orphans + 1 end
  pending = false
  if label == nil then blank = blank + 1 else labelled = labelled + 1 end
  check((label == nil) or (type(label) == "string"),
        "FlowerMenuClosed carried " .. ((label == nil) and "nothing chosen" or ("\"" .. tostring(label) .. "\"")),
        type(label))
end

local function arm()
  for _, s in ipairs(subs) do s:off() end
  subs = {}
  opens, closes, doubles, orphans, labelled, blank = 0, 0, 0, 0, 0, 0
  pending = false
  subs[1] = hafen.event():on("FlowerMenuOpened", onOpened)
  subs[2] = hafen.event():on("FlowerMenuClosed", onClosed)
  return (subs[1] ~= nil) and (subs[2] ~= nil)
end

-- ---- the two halves ------------------------------------------------------------------------------

local function open()
  pass, fail, manual = 0, 0, 0

  -- With no menu open every read answers, and neither throws.
  local ok, list = pcall(function() return hafen.flowermenu():list() end)
  check(ok and (type(list) == "table") and (#list == 0),
        "with no menu open :list() is an empty array and does not throw", ok and describe(list) or list)
  local okc, cnt = pcall(function() return hafen.flowermenu():count() end)
  check(okc and (cnt == 0), "with no menu open :count() is 0 and does not throw", okc and cnt or cnt)

  -- The grammar at the door.
  refuses("a filter is refused: :list(\"x\") names the door that picks a petal",
          function() return hafen.flowermenu():list("x") end, "hafen.act():flower(label)")
  refuses("...and so is :count(1)", function() return hafen.flowermenu():count(1) end, "takes no arguments")
  refuses("a DOT call is refused, naming the colon form",
          function() return hafen.flowermenu().list() end, "COLON call")
  refuses("the section takes no arguments", function() return hafen.flowermenu(1) end, "takes no arguments")
  refuses("an unknown verb throws naming the section",
          function() return hafen.flowermenu():petals() end, "has no verb")
  check(hafen.flowermenu() == hafen.flowermenu(), "the section object is one per-addon singleton")

  -- The premise this task's events rest on, and the door it moved the menu finder out of.
  eq("hafen.act():flower is still there, unchanged", type(hafen.act().flower), "function")
  refuses("an unknown neighbour of the two new keys still raises",
          function() return hafen.event():on("FlowerMenuOpen", function() end) end, "unknown event")
  check(arm(), "both event keys are accepted by hafen.event():on")

  manualCheck("right-click a TREE and pick a petal",
              "two [pass] lines whose captions are the ring on screen, then one naming the petal you picked")
  manualCheck("right-click the SAME tree again and press Esc",
              "two [pass] lines again, then one saying \"nothing chosen\"")
  manualCheck("open the Kin window, right-click a kin in the list and pick a petal, then run `:t047-1 done`",
              "the client-side menu fires both events too, then the closing summary")
end

local function done()
  check(opens >= 3, "three menus were opened while the handlers were armed", opens)
  eq("every FlowerMenuOpened was followed by exactly one FlowerMenuClosed", closes, opens)
  check((doubles == 0) and (orphans == 0) and (not pending),
        "...one at a time, and none left hanging",
        "overlapping=" .. doubles .. " orphaned=" .. orphans .. " pending=" .. tostring(pending))
  check((labelled >= 1) and (blank >= 1),
        "a pick closed with its label and an Esc closed with nothing",
        "labelled=" .. labelled .. " blank=" .. blank)
  eq("and with every menu gone, :count() is back to 0", hafen.flowermenu():count(), 0)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t047-1", function(args)
  if args and (args[1] == "done") then done() else open() end
end)
