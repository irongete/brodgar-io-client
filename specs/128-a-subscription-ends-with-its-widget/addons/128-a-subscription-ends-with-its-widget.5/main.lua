-- 128.5 — the disposal seam retires an item's subscriptions and its handles. Self-checking suite.
--
-- No verb reports Addon.itemSubs or a per-item intern cache, and a subscription on a destroyed item fired
-- nothing before this task and fires nothing after — so the retirement itself is proved headlessly, against
-- a real off-screen UI, and this suite guards the surface that retirement could break. Two things:
--
--   * a LIVE item must go on interning to the one object it always was, across the drains that now reach
--     every disposed widget in the client — the failure the retirement would cause if it were hung on the
--     removal seam, or if it swept more than the one widget it is handed;
--   * an item that dies WITH the container holding it — a destroy and never a removal, which is exactly
--     the case no removal seam ever saw — must leave every verb on the handle ANSWERING rather than
--     raising: the subscription it carried can be ended, a new one can be taken out on it, and it goes on
--     saying what it was.
--
-- The container half needs a container: a chest, a cupboard, a basket. The main inventory window HIDES
-- rather than closing, so it destroys nothing, which is why the [manual] lines below ask for one.

local pass, fail, manual = 0, 0, 0
local S = {}
local STEP   = 0.25       -- seconds per tick; a drain runs every FRAME, so one tick is dozens of them
local SETTLE = 2          -- ticks between a read and the re-read that proves the drains left it alone
local WAIT   = 480        -- ticks (~2 min) a [manual] step is given before the run scores without it

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A verb on an item that has gone is answered, never raised, so the check is that it did NOT raise.
local function raisesNothing(what, fn)
  local ok, err = pcall(fn)
  check(ok, what, ok and "<no error>" or tostring(err))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function items(w)
  if w == nil then return {} end
  local ok, list = pcall(function() return w:items():list() end)
  return ok and list or {}
end

local function mainItem()
  local inv = S.s and S.s:ui():inventory()
  return items(inv)[1]
end

-- The container the maintainer opens is the one that was NOT there when we asked, and that test is the
-- whole filter: a tree with nothing open already holds containers of its own — the backpack and the
-- worn-equipment grid (the `inventory` role covers Equipory too), both wrapped in a Hidewnd whose close
-- HIDES and so destroys nothing, and a ContentsWindow per stack, which the client mints the moment the
-- server sends that stack's contents and which no one can close on cue. Naming those three would still be
-- guessing at the fourth; what the proof needs is a container whose window the maintainer just put up.
local function promptOpen()
  S.known = {}
  for _, w in ipairs(S.s:ui():matchAll("inventory")) do S.known[w] = true end
  manualCheck("open a container holding at least one item (a chest, a cupboard, a basket)",
              "the suite's next line names an item of its own")
end

local function containerItem()
  for _, w in ipairs(S.s:ui():matchAll("inventory")) do
    if not S.known[w] then
      local it = items(w)[1]
      if it ~= nil then return w, it end
    end
  end
  return nil, nil
end

local function finish()
  if S.timer then S.timer:cancel() end
  if S.sub then pcall(function() S.sub:off() end) end
  S = {}
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- 1 — the character's own backpack answers, and the item it answers with interns.
local function stage1()
  local it = mainItem()
  if it == nil then
    if S.n < SETTLE * 4 then return end
    check(false, "the character's inventory answers with an item", "<no item in the backpack>")
    promptOpen()
    S.n, S.stage = 0, 3
    return
  end
  check(true, "the character's inventory answers with an item")
  S.a = it
  check(mainItem() == it, "an item re-read from its container is the same object", mainItem())
  S.n, S.stage = 0, 2
end

-- 2 — ...and it is STILL that object once the drains that now reach every death have run over it.
local function stage2()
  if S.n < SETTLE then return end
  check(mainItem() == S.a, "the same object again after the drains have run", mainItem())
  check(type(S.a:info()) == "table", "...and its snapshot still answers", type(S.a:info()))
  promptOpen()
  S.n, S.stage = 0, 3
end

-- 3 — the container the maintainer opened, and a subscription on an item inside it.
local function stage3()
  local w, it = containerItem()
  if it == nil then
    if S.n < WAIT then return end
    check(false, "a container answered with an item of its own", "<no container opened>")
    finish()
    return
  end
  S.b = it
  S.sub = it:on("Changed", function() end)
  check(S.sub ~= nil and it:exists(),
        "a container answered with an item of its own, and it is subscribed -- "
          .. tostring(w:type()) .. " holding " .. tostring(it:res()), S.sub)
  manualCheck("close that container's window",
              "the suite's next line reads [pass] an item's subscription ends with the container that held it")
  S.n, S.stage = 0, 4
end

-- 4 — it was DESTROYED with the window, never removed from it: the case the removal seam never saw.
local function stage4()
  if S.b:exists() then
    if S.n < WAIT then return end
    check(false, "an item's subscription ends with the container that held it",
          "<the container is still open>")
    finish()
    return
  end
  check(true, "an item's subscription ends with the container that held it")
  raisesNothing("ending the gone item's subscription raises nothing", function() S.sub:off() end)
  S.sub = nil
  raisesNothing("subscribing on the gone item raises nothing", function() S.b:on("Changed", function() end) end)
  local live = mainItem()
  if live == nil then
    check(false, "a fresh subscription on a live item still works", "<no item in the backpack>")
  else
    local sub = live:on("Changed", function() end)
    check(sub ~= nil, "a fresh subscription on a live item still works", sub)
    pcall(function() sub:off() end)
  end
  finish()
end

local stages = {stage1, stage2, stage3, stage4}

local function tick()
  S.n = S.n + 1
  local fn = stages[S.stage]
  if fn == nil then finish() else fn() end
end

local function start()
  S.s = hafen.session():current()
  if S.s == nil then
    check(false, "a character is logged in", "<no session>")
    finish()
    return
  end
  S.n, S.stage = 0, 1
  S.timer = hafen.timer():every(STEP, tick)
end

local function run()
  if S.timer then S.timer:cancel() end
  if S.sub then pcall(function() S.sub:off() end) end
  pass, fail, manual = 0, 0, 0        -- a second :t128 scores its own run, not both
  S = {}
  hafen.timer():after(0, start)       -- the step, where the tree is the tree the frame is looking at
end

hafen.console():on("t128", run)   -- the only way in: a suite does not start itself
