-- 077.4 — the open menu, and the fight. Self-checking suite.
--
-- s:fight() and s:flowermenu() are the last two namespaces to leave `hafen` for the session. The menu is
-- the one that had to be argued rather than repeated: a right-click is a mouse gesture and there is one
-- mouse, but the section IS the open menu, and a menu is a widget in one character's tree.

local pass, fail, manual = 0, 0, 0
local WINDOW = 25                     -- seconds the run waits for a menu to open, over the manual below
local seen, sub                       -- the last FlowerMenuOpened payload, and the subscription holding it
local gen = 0                         -- which run owns the pending window (a re-run before it closes wins)

local function log(s) hafen.log():write(s) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why — every `want` in the message.
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or tostring(err):gsub("^.-%.lua:%d+:%s*", "")
  local said = not ok
  for _, want in ipairs({...}) do
    said = said and (err:find(want, 1, true) ~= nil)
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

local function count(t)
  local n = 0
  for _ in pairs(t) do n = n + 1 end
  return n
end

-- ---------------------------------------------------------------------------------------------------

local function summarise()
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- What every session's menu says about itself, as one line of evidence: the manual below is judged on it.
local function menus()
  local out, ok = {}, true
  for _, s in ipairs(hafen.session():list()) do
    local read, said = pcall(function()
      local m = s:flowermenu()
      local petals = m:list()
      local n = m:count()
      if n ~= #petals then error("count " .. n .. " but " .. #petals .. " petals") end
      local gob = m:gob()
      if (gob ~= nil) and (n == 0) then error("no petals but a gob") end
      return s:user() .. "=" .. n .. ((n > 0) and (" (" .. table.concat(petals, ", ") .. ")") or "")
    end)
    if read then out[#out + 1] = said else ok = false; out[#out + 1] = s:user() .. "=" .. tostring(said) end
  end
  if #out == 0 then out[1] = "<no session in the world>" end
  return ok, table.concat(out, "; ")
end

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if not s then
    log("[fail] a session must be on screen -- got: the login screen")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The fight: four projections of that character's own schools tab and combat view. Each answers, or is
  -- honestly absent before the tab has built / out of combat -- never a throw.
  local f = s:fight()
  local mans, deck = f:maneuver():list(), f:deck()
  check((type(mans) == "table") and (type(deck) == "table"),
        "session:fight() reads that character's schools (" .. count(mans) .. " maneuvers, "
        .. count(deck) .. " cards)", tostring(mans) .. "/" .. tostring(deck))
  local sum = f:summary()
  check((sum == nil) or (type(sum:maxActions()) == "number"),
        "session:fight():summary() answers or is honestly absent",
        (sum == nil) and "nil (the tab has not built)" or sum:maxActions())
  local tgt = f:target()
  check((tgt == nil) or ((type(tgt:id()) == "number") and (tgt:gob() ~= nil)),
        "session:fight():target() answers or is honestly absent",
        (tgt == nil) and "nil (not in a fight)" or tgt:id())

  -- Both hang off the SESSION handle, so each is minted once for this (addon, session) pair.
  check((s:fight() == f) and (s:flowermenu() == s:flowermenu()),
        "both sections are interned on the session handle")

  -- The menu, read on every session the client holds -- the drawn one and the ones nobody is looking at.
  local ok, said = menus()
  check(ok, "every session's flowermenu answers: " .. said, said)

  -- The hard cut: neither loose spelling exists, and each refusal names its replacement.
  refuses("hafen.fight() is retired, naming its replacement",
          function() return hafen.fight() end, "session:fight()")
  refuses("hafen.flowermenu() is retired, naming its replacement",
          function() return hafen.flowermenu() end, "session:flowermenu()")

  -- The keys. This suite declares "flowermenu.select" and NOT "flowermenu.cancel": the one it did not ask
  -- for refuses naming the verb and the key, and the one it did reaches its ARGUMENT refusal -- which is
  -- the grant proved without a petal being picked, since nothing has been sent when it raises.
  refuses("session:flowermenu():cancel() refuses the key it was not granted",
          function() return s:flowermenu():cancel() end,
          "session:flowermenu():cancel", "flowermenu.cancel")
  refuses("session:flowermenu():select() reaches its argument refusal, so the grant is live",
          function() return s:flowermenu():select() end,
          "session:flowermenu():select", "key is required")

  manualCheck("right-click an object to raise a radial menu, then tab to the OTHER session without"
              .. " picking a petal, and re-run",
              "the flowermenu line above names the FIRST account with that menu's petals, while the"
              .. " character on screen has 0 -- a menu open on a character you are not looking at")
  manualCheck("paste the final line of the checklist and guardrail greps",
              "checklist 0, guardrail 212")

  -- The event, over a bounded window that covers the manual above: a menu is put up by a gesture only the
  -- player can make, so the run scores over what it reached rather than over what it could not cause.
  if sub then sub:off() end
  seen, gen = nil, gen + 1
  local mine = gen
  sub = hafen.event():on("FlowerMenuOpened", function(petals) seen = petals end)
  log("[wait] " .. WINDOW .. "s for a FlowerMenuOpened -- right-click something now")
  hafen.timer():after(WINDOW, function()
    if gen ~= mine then return end          -- a re-run took the window over; that run reports, not this
    if sub then sub:off(); sub = nil end
    check(seen ~= nil, "FlowerMenuOpened fired within the window ("
          .. ((seen ~= nil) and table.concat(seen, ", ") or "nothing") .. ")",
          "no menu opened in " .. WINDOW .. "s")
    summarise()
  end)
end

hafen.slash():register("t077-4", run)   -- the only way in: a suite does not start itself
