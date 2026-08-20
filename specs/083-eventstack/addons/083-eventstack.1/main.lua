-- 083.1 -- the log. Self-checking suite.
--
-- The addon layer has no search verbs, so no suite can find EventStack's own window. This drives the
-- very API the addon drives -- a table's of(row) accounting, a ring of the same shape, and each of the
-- four doors -- and asserts the invariants there.
--
-- It sends exactly one message, and cancels it inside its own wildcard so it never reaches the wire.

local pass, fail, manual = 0, 0, 0
local WINDOW = 6                      -- seconds the run waits on the inbound stream and the bus
local PROBE  = "brodgar-probe"        -- the outbound message, cancelled before it leaves
local CAP    = 8                      -- the ring's cap, for a ring driven twelve records past it
local gen    = 0                      -- which run owns the pending window (a re-run before it closes wins)

-- Every key on the bus but Update, which fires once a frame and says only that a frame happened.
local BUS_KEYS = {
  "Load", "Disable",
  "SessionAdded", "SessionEnteredWorld", "SessionSelected", "SessionDestroyed",
  "GobAdded", "GobRemoved", "GobOverlayAdded", "GobOverlayRemoved",
  "MeterAdded", "MeterRemoved", "MeterChanged",
  "BuffAdded", "BuffRemoved", "BuffChanged",
  "FepChanged", "StudyChanged", "EquipChanged", "ActionbarChanged", "WoundChanged",
  "KinChanged", "QuestAdded", "QuestDone", "MarkersChanged",
  "FlowerMenuOpened", "FlowerMenuClosed",
  "GhostClicked", "SpriteClicked", "ObjectClicked",
}

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every `want` in the message.
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

local function summarise()
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ---------------------------------------------------------------------------------------------------
-- One record, four producers: src, name and about, and every one of the three a string.

local function why(r)
  if r == nil then return "no record" end
  for _, f in ipairs({"src", "name", "about"}) do
    if type(r[f]) ~= "string" then return f .. " = " .. tostring(r[f]) end
  end
  return nil
end

local function door(what, r)
  local bad = why(r)
  local said = (bad == nil) and (" -- " .. r.src .. " | " .. r.name .. " | " .. r.about) or ""
  check(bad == nil, what .. said, bad)
end

-- What a bus payload says about itself, from whichever of these it answers. A payload that answers none
-- costs the record its description and not its record: "" is a string too, which is the point.
local function describe(a)
  if a == nil then return "" end
  local t = type(a)
  if t == "string" then return a end
  if t == "number" then return string.format("%d", a) end
  if t == "table"  then return string.format("%d", #a) .. " entries" end
  for _, verb in ipairs({"name", "user", "res", "key", "title", "id"}) do
    local ok, v = pcall(function() return a[verb](a) end)
    if ok and (type(v) == "string") then return v end
    if ok and (type(v) == "number") then return string.format("%d", v) end
  end
  return ""
end

-- ---------------------------------------------------------------------------------------------------
-- The cost one :rows(t) has, counted on a table of this suite's own

local tbl, calls = nil, 0

local function countRows()
  local rows = {}
  for i = 1, 100 do rows[i] = {a = "a" .. i, b = "b" .. i} end

  calls = 0
  tbl = hafen.ui():table():size(200, 100):visible(false)
    :columns{
      {title = "a", width = 100, of = function(r) calls = calls + 1 return r.a end},
      {title = "b", width = 100, of = function(r) calls = calls + 1 return r.b end},
    }

  tbl:rows(rows)
  eq("a hundred rows over two columns is two hundred of(row) calls", calls, 200)
  tbl:rows(rows)
  eq("an identical second write is two hundred more", calls, 400)
end

-- ---------------------------------------------------------------------------------------------------
-- The ring: an array with a head index, never a table that shifts

local function ringCheck()
  local ring, count, head = {}, 0, 0

  local function push(v)
    head = (head % CAP) + 1
    ring[head] = v
    if count < CAP then count = count + 1 end
  end

  local function records()
    local out, first = {}, (count < CAP) and 1 or ((head % CAP) + 1)
    for i = 1, count do out[i] = ring[((first + i - 2) % CAP) + 1] end
    return out
  end

  for i = 1, CAP + 4 do push(i) end
  local out = records()

  local want = {}
  for i = 5, CAP + 4 do want[#want + 1] = i end
  eq("past its cap the ring keeps the newest, in arrival order",
     table.concat(out, ","), table.concat(want, ","))

  local oldest = false
  for _, v in ipairs(out) do
    if v < 5 then oldest = true end
  end
  check((not oldest) and (#out == CAP), "the oldest are gone and the ring stayed at its cap", #out)
end

-- ---------------------------------------------------------------------------------------------------
-- The doors

local function run()
  gen = gen + 1
  local mine = gen
  pass, fail, manual = 0, 0, 0

  countRows()
  ringCheck()

  -- tostring(os.time()) is 1.7871145E9 and "%.0f" does not convert at all: a log's first column would
  -- read the same for a hundred seconds either side of now.
  local clock = string.format("%d", os.time())
  check((clock:find("E", 1, true) == nil) and (#clock >= 10),
        "the clock column carries no E (" .. clock .. ")", clock)

  local s = hafen.session():current()
  local gu = s and s:ui():find("@GameUI")

  -- 1. Out: a message of this suite's own, seen and CANCELLED inside the wildcard that saw it.
  local outRec
  if gu == nil then
    check(false, "the outbound door yields a record", "no GameUI -- run this in the world")
  else
    local osub = hafen.event():action():on("*", function(ev)
      if ev:msg() == PROBE then
        local w = ev:sender()
        outRec = {src = "out", name = ev:msg(), about = (w and w:type()) or ""}
        ev:preventDefault()                       -- it never reaches the wire
      end
    end)
    gu:send(PROBE)
    osub:off()
    door("the outbound door yields a record, and the send was cancelled", outRec)
  end

  -- 2. Widget: appear fires AT ONCE for what that character already has open, inside registration.
  if s == nil then
    check(false, "the widget door yields a record", "no session -- run this in the world")
  else
    local wRec
    local h = s:ui():on("*", "appear", function(w)
      if wRec == nil then wRec = {src = "widget", name = w:type(), about = "appear"} end
    end)
    h:remove()
    door("the widget door yields a record for what is already open", wRec)
  end

  -- 3 and 4. In and bus: a receiver only the server produces, over a bounded window, scored on what the
  -- run reached.
  local inRec, busRec
  local isub = hafen.event():message():on("*", function(ev)
    if inRec == nil then
      local w = ev:target()
      inRec = {src = "in", name = ev:msg(), about = (w and w:type()) or ""}
    end
  end)
  local bsubs = {}
  for _, key in ipairs(BUS_KEYS) do
    bsubs[#bsubs + 1] = hafen.event():on(key, function(a, b)
      if busRec == nil then busRec = {src = "bus", name = key, about = describe(a)} end
    end)
  end

  log("[wait] " .. WINDOW .. "s for the inbound stream and the bus")
  hafen.timer():after(WINDOW, function()
    if gen ~= mine then return end                -- a re-run took the window over; that run reports

    isub:off()
    for _, b in ipairs(bsubs) do b:off() end

    door("the inbound door yields a record", inRec)
    door("the bus door yields a record", busRec)

    -- The columns are chosen at construction, so a table that has been on screen for a tick refuses.
    refuses("a table already on screen refuses :columns(t)",
            function() tbl:columns{{title = "c", width = 100, of = function(r) return r.a end}} end,
            "while the control is being BUILT")
    tbl:destroy()

    manualCheck("type :eventstack, then move, open a window and click something",
                "the newest line at the BOTTOM, and the client as smooth as it was before")
    manualCheck("drag the window somewhere, quit the client and start it again, then :eventstack",
                "it comes back where you left it")
    summarise()
  end)
end

hafen.slash():register("t083-1", run)
