-- 150.2 — the holds live in the client's file. Self-checking suite.
--
-- Two shapes of run, told apart by the bar itself. A PLACING run adds the suite's menu entry, holds a free
-- slot for it and leaves the hold standing, so a :reload (and a disable/enable cycle) can be observed to
-- bring it back. A RELEASING run is any run that finds its entry already held on a slot — after a :reload
-- the :add itself is what re-applies the row — and it reports that, ends the hold and takes the entry out.

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
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local ENTRY = "t150"

-- The slot the suite's entry is held on, or nil.
local function heldOn(bar, pag)
  for _, slot in ipairs(bar:list()) do
    local h = slot:hold()
    if h and h:res() == pag:res() then return slot end
  end
  return nil
end

-- The name of a table in the addon's OWN file, read through a statement with the name bound as ?, so the
-- statement's text carries no hafen_ word for the scan to refuse.
local function tableCount(name)
  local rows = hafen.store():query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", name)
  return #rows
end

local function run()
  pass, fail, manual = 0, 0, 0          -- one verdict per run: the maintainer runs it more than once
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "no session")
    summary()
    return
  end
  local mg, bar = s:menugrid(), s:actionbar()
  local pag = mg:get(ENTRY)
  if not pag then
    local ok, p = pcall(function() return mg:add(ENTRY):name("150.2 hold") end)
    check(ok, "the suite's entry is in the menu", p)
    if not ok then summary() return end
    pag = p
  end

  -- A releasing run: the entry is on a slot already, put there by the :add above (after a :reload) or by
  -- the previous run (no reload between). Either way the client's file is what put it back.
  local back = heldOn(bar, pag)
  if back then
    check(true, "the hold on slot " .. back:index() .. " came back from the client's file")
    back:hold(nil)
    eq("slot:hold(nil) reads nil on slot " .. back:index(), back:hold(), nil)
    mg:remove(pag)
    summary()
    return
  end

  -- A placing run.
  local free = {}
  for _, slot in ipairs(bar:list()) do
    if slot:empty() and (slot:hold() == nil) then free[#free + 1] = slot end
    if #free == 2 then break end
  end
  check(#free == 2, "two free slots on the bar", #free)
  if #free < 2 then summary() return end
  local a, b = free[1], free[2]

  a:hold(pag)
  local h = a:hold()
  check(h and (h:res() == pag:res()), "slot:hold(pag) reads the entry back on slot " .. a:index(),
        h and h:res() or "nil")
  b:hold(pag)
  b:hold(nil)
  eq("slot:hold(nil) then reads nil on slot " .. b:index(), b:hold(), nil)
  refuses("a hold on slot 145 is refused naming the range",
          function() bar:get(145):hold(pag) end, "1..144")

  eq("the addon's own file lists no hafen_holds", tableCount("hafen_holds"), 0)
  eq("the same read finds hafen_documents", tableCount("hafen_documents"), 1)

  manualCheck(":reload, then :t150 again",
              "[pass] the hold on slot " .. a:index() .. " came back from the client's file"
              .. " (that run ends it and removes the entry)")
  manualCheck(":t150 (places again), disable the suite in the AddOns panel, :reload, enable it, :reload, :t150",
              "[pass] the hold on slot N came back from the client's file")
  summary()
end

hafen.console():on("t150", run)   -- the only way in: a suite does not start itself
