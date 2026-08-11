-- 047.2 — hafen.flowermenu():select(label|n) and :cancel(), behind the "actions" permission.
-- Self-checking suite; see specs/testing/addon-suite.md.
--
-- This suite declares NO permissions (a suite never may), so it proves the gated half the only way it can: by
-- asserting the REFUSAL, while the ungated reads answer in the same run. It stands alone -- it drives nothing
-- but its own right-click and asks for no other addon.
--
-- An open menu grabs mouse AND keyboard, so `:t047-2` cannot be typed while one is up. The command runs in two
-- halves: `:t047-2` asserts everything a program can see with no menu open and ARMS a handler; the handler then
-- asserts, WITH A REAL MENU IN FRONT OF IT, that the gate still refuses -- which is the one thing the no-menu
-- half cannot show, because a refusal with nothing open could always have been "there was nothing to pick".
-- `:t047-2 done` closes the run.

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

local GATE = ": this addon did not declare the \"actions\" permission"

-- ---- the armed half: the same gate, with a menu REALLY on screen ---------------------------------

local subs, seen = {}, 0

local function onOpened(petals)
  seen = seen + 1
  if seen > 1 then return end        -- one menu is the whole proof; a second right-click stays quiet

  local live, cnt = hafen.flowermenu():list(), hafen.flowermenu():count()
  local agreed = (type(petals) == "table") and (#petals > 0) and (#live == #petals) and (cnt == #petals)
  if agreed then
    for i = 1, #petals do if live[i] ~= petals[i] then agreed = false end end
  end
  check(agreed, "with a menu really open the ungated reads answer: :list()/:count() agree with the ring",
        tostring(cnt) .. " vs " .. tostring(type(petals) == "table" and #petals))

  local first = (type(petals) == "table") and petals[1] or "?"
  refuses("...and :select(1) still refuses on the permission, with a real petal AT position 1",
          function() return hafen.flowermenu():select(1) end, "hafen.flowermenu():select" .. GATE)
  refuses("...and :select(\"" .. tostring(first) .. "\") too, with that exact caption on the ring",
          function() return hafen.flowermenu():select(first) end, "hafen.flowermenu():select" .. GATE)
  refuses("...and :cancel() too, with the ring still on screen to cancel",
          function() return hafen.flowermenu():cancel() end, "hafen.flowermenu():cancel" .. GATE)
  refuses("...and the older door, hafen.act():flower, refuses on the same permission",
          function() return hafen.act():flower(first) end, "hafen.act():flower" .. GATE)
end

local function arm()
  for _, s in ipairs(subs) do s:off() end
  subs, seen = {}, 0
  subs[1] = hafen.event():on("FlowerMenuOpened", onOpened)
  return subs[1] ~= nil
end

-- ---- the two halves ------------------------------------------------------------------------------

local function open()
  pass, fail, manual = 0, 0, 0

  -- The ungated half still answers, in the very run where the gated half refuses. That contrast IS the claim.
  local ok, list = pcall(function() return hafen.flowermenu():list() end)
  local okc, cnt = pcall(function() return hafen.flowermenu():count() end)
  check(ok and okc and (type(list) == "table") and (#list == 0) and (cnt == 0),
        "the reads are ungated: with no menu open :list() is {} and :count() is 0 for this undeclared addon",
        tostring(ok) .. "/" .. tostring(okc) .. " " .. tostring(cnt))

  -- The gate itself, on both new verbs, named so the author knows which manifest line is missing.
  refuses("hafen.flowermenu():select is gated and names itself",
          function() return hafen.flowermenu():select("Chop") end, "hafen.flowermenu():select" .. GATE)
  refuses("hafen.flowermenu():cancel is gated and names itself",
          function() return hafen.flowermenu():cancel() end, "hafen.flowermenu():cancel" .. GATE)
  refuses("the gate fires BEFORE the argument check: a bare :select() still refuses on the permission",
          function() return hafen.flowermenu():select() end, GATE)
  refuses("...and before the menu lookup: with nothing open it is still the permission that refuses",
          function() return hafen.flowermenu():select(1) end, GATE)

  -- The two verbs really are on the section, and the grammar holds at their door too.
  check((type(hafen.flowermenu().select) == "function") and (type(hafen.flowermenu().cancel) == "function"),
        "both verbs exist on the section object",
        type(hafen.flowermenu().select) .. "/" .. type(hafen.flowermenu().cancel))
  refuses("a DOT call is refused before the gate, naming the colon form",
          function() return hafen.flowermenu().select("x") end, "COLON call")
  refuses("an unknown verb still throws naming the section",
          function() return hafen.flowermenu():choose(1) end, "has no verb")
  refuses("a filter on :list() now names the door that picks a petal",
          function() return hafen.flowermenu():list("x") end, "hafen.flowermenu():select(label)")

  -- Coexistence: this feature adds a door, it does not take one away (maintainer directive).
  eq("hafen.act():flower is still there and still a function", type(hafen.act().flower), "function")
  refuses("...and still behind the same permission",
          function() return hafen.act():flower("Chop") end, "hafen.act():flower" .. GATE)

  check(arm(), "a FlowerMenuOpened handler is armed to re-assert the gate against a live menu")

  manualCheck("right-click a TREE (or anything), let the ring appear, press Esc, then run `:t047-2 done`",
              "five [pass] lines printed by the handler while the ring is up, then the summary")
end

local function done()
  check(seen >= 1, "a real menu opened and closed while the handler was armed", seen)
  eq("and with it gone, :count() is back to 0", hafen.flowermenu():count(), 0)
  for _, s in ipairs(subs) do s:off() end
  subs = {}
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t047-2", function(args)
  if args and (args[1] == "done") then done() else open() end
end)
