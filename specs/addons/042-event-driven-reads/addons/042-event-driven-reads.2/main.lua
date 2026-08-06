-- 042.2 -- buffs, and the fade that is not a removal. Self-checking suite; see specs/addons/TESTING.md
-- and specs/addons/042-event-driven-reads/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. CharApi's BuffsAdapter.poll() is gone: BuffAdded now fires from the widget-
-- placement seam (M3) the moment a buff widget is added to the Bufflist. BuffRemoved fires from the
-- one-line // addon: tap Buff.reqdestroy() now carries (042.2) -- at the exact moment the server's
-- destroy sets Buff.dest, NOT 0.35s later when the fade animation actually unlinks the widget (the
-- removal seam, M1, still fires then too, but the adapter's cache-membership guard makes that second
-- firing a no-op). BuffChanged is UNCHANGED -- it was already event-driven off the "ch"/"tt" uimsg.
--
-- WHAT THIS SUITE CANNOT AUTOMATE. Which physical buff appears/expires is the maintainer's own action
-- (eat something, wait it out), and Lua has no way to read Buff.dest directly or to observe the animation
-- unlinking the widget 0.35s later -- so the exact "announced at dest, not late" claim rests on a
-- [manual] read of this log: the "BuffRemoved fired" line must appear the instant the icon starts
-- fading, not after the fade finishes, and never a second time for the same buff.

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

-- Fired for as long as the suite is installed, so a manual action after :t042-2's own summary still shows up.
local added, removed, changed = 0, 0, 0
-- buff object -> how many BuffAdded it has seen without a matching BuffRemoved yet. Weak keys: a buff
-- this table is the only thing still holding must not pin it (the suite is not the owner of a buff's
-- lifetime, only an observer).
local upCount = setmetatable({}, {__mode = "k"})

hafen.event():on("BuffAdded", function(b)
  added = added + 1
  local n = (upCount[b] or 0) + 1
  upCount[b] = n
  check(n == 1, ("BuffAdded fires once per buff before its BuffRemoved (res=%s)"):format(tostring(b:res())), n)
  hafen.log():write(("BuffAdded fired (#%d) -- res=%s name=%s"):format(added, tostring(b:res()), tostring(b:name())))
end)

hafen.event():on("BuffRemoved", function(b)
  removed = removed + 1
  local n = upCount[b]
  if n ~= nil then   -- only provable for a buff whose BuffAdded this suite itself observed
    check(n == 1, ("BuffRemoved fires exactly once, after its own BuffAdded (res=%s)"):format(tostring(b:res())), n)
    upCount[b] = 0
  end
  check(b:exists() == false, "a removed buff's payload reports :exists() false", b:exists())
  check(b:res() ~= nil, "a removed buff's payload still answers :res()", b:res())
  hafen.log():write(("BuffRemoved fired (#%d) -- res=%s name=%s exists=%s")
    :format(removed, tostring(b:res()), tostring(b:name()), tostring(b:exists())))
end)

hafen.event():on("BuffChanged", function(b)
  changed = changed + 1
  hafen.log():write(("BuffChanged fired (#%d) -- res=%s amount=%s"):format(changed, tostring(b:res()), tostring(b:amount())))
end)

local function run()
  pass, fail, manual = 0, 0, 0
  local baseAdded, baseRemoved, baseChanged = added, removed, changed

  -- 1. Whatever buffs are already up answer their read verbs -- the reads themselves did not move
  --    (025-buffs-oop/039 already prove them; this re-asserts the premise this task's proof rests on).
  local list = hafen.buff():list()
  check(type(list) == "table", "hafen.buff():list() answers a table", type(list))
  if #list > 0 then
    local b = list[1]
    check(b:exists(), "a live buff reports :exists() true", b:exists())
    check(b:res() ~= nil, "a live buff's :res() answers", b:res())
  end

  -- 2. Idle: the feature's whole claim is that nothing fires on its own once nothing is changing.
  hafen.timer():after(3, function()
    local dAdded, dRemoved, dChanged = added - baseAdded, removed - baseRemoved, changed - baseChanged
    check((dAdded == 0) and (dRemoved == 0) and (dChanged == 0),
          "idle: no buff event fires over 3s with nothing changing",
          ("added=%d removed=%d changed=%d"):format(dAdded, dRemoved, dChanged))

    manualCheck("eat something that grants a buff, then let it expire, watching this log",
                "one 'BuffAdded fired' line the instant the buff appears (name may still be nil for a"
                .. " beat), then -- once it wears off -- one 'BuffRemoved fired ... exists=false' line"
                .. " printed the moment the icon STARTS fading, not after the ~0.35s fade finishes, and"
                .. " never a second BuffRemoved line for the same buff (each already checked above as it"
                .. " happens; this line asks you to read the ordering and timing off the raw log)")

    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t042-2", run)   -- the only way in: a suite does not start itself
