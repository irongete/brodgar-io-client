-- 084.2 -- the twelve that never asked. Self-checking suite.
--
-- These twelve handles had the bare methods table as their __index, so an unknown key read nil and
-- the retirement table was never consulted. The proof is the SHAPE of the message: a receiver, the
-- key that missed, a separator and a hint naming the type's own verbs. A bare table produces none of
-- it -- it produces nothing at all -- so the shape is what says the mechanism is now in the door.
--
-- A buff and a recorded overlay mask exist only when the character has one, so the sweep RETRIES over
-- a bounded window and scores what it reached, printing the names it did not.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, e = pcall(fn)
  local msg = ok and "<no error>" or (tostring(e):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (msg:find(wantMsg, 1, true) ~= nil), what, msg)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function err(ok, e)
  if ok then return "<no error>" end
  return (tostring(e):gsub("^.-%.lua:%d+:%s*", ""))
end

-- ---------------------------------------------------------------- the roster

local function S() return hafen.session():current() end

-- A recorded overlay mask needs ground someone claimed. Two nets, widest first: the grids streamed in
-- around the character, then a square of the current segment's recorded ground, which is every place
-- this character has walked rather than only where it stands.
local function anyMask()
  local s = S()
  if not s then return nil end
  for _, g in ipairs(s:world():grid():list()) do
    local m = g:overlay():list()[1]
    if m then return m end
  end
  local seg = hafen.map():segment():current()
  local me = s:player():gob()
  local here = me and s:world():grid():at(me:position())
  local sc = here and here:segmentCoord()
  if seg and sc then
    for _, g in ipairs(seg:grid():list{ x = sc.x - 4, y = sc.y - 4, w = 9, h = 9 }) do
      local m = g:overlay():list()[1]
      if m then return m end
    end
  end
end

-- entity   the receiver spelling the refusal must carry (how Retired keys a row on this type)
-- verbs    real verbs of that type, which its hint must name (three, or all it has)
-- known    a READ that must still answer on the handle
-- reach    a live one, or nil when the game has not got one right now
local TYPES = {
  { entity = "buff", verbs = { ":res()", ":name()", ":amount()" }, known = "exists",
    reach = function() local s = S() return s and s:buff():list()[1] end },
  { entity = "meter", verbs = { ":res()", ":index()", ":value()" }, known = "exists",
    reach = function() local s = S() return s and s:meter():list()[1] end },
  { entity = "sound", verbs = { ":res()", ":play()", ":stop()" }, known = "res",
    reach = function() return hafen.sound():get("sfx/msg") end },
  { entity = "cat", verbs = { ":res()", ":name()", ":show()" }, known = "res",
    reach = function() return hafen.map():icon():list()[1] end },
  { entity = "mask", verbs = { ":tag()", ":grid()", ":covers()" }, known = "tag",
    reach = anyMask },
  { entity = "profiling", verbs = { ":frame()", ":history()", ":memory()" }, known = "memory",
    reach = function() return hafen.client():profiling() end },
  { entity = "scope", verbs = { ":begin()", ":finish()", ":name()" }, known = "name",
    reach = function() return hafen.client():profiling():scope("t084-2") end },
  { entity = "audio", verbs = { ":masterVolume()", ":uiVolume()", ":latency()" }, known = "masterVolume",
    reach = function() return hafen.client():options():audio() end },
  { entity = "camera", verbs = { ":mode()", ":invertHorizontal()", ":invertVertical()" },
    known = "invertHorizontal",
    reach = function() return hafen.client():options():camera() end },
  { entity = "client", verbs = { ":profiling()" }, known = "profiling",
    reach = function() return hafen.client():options():client() end },
  { entity = "interface", verbs = { ":scale()", ":posGran()", ":angGran()" }, known = "scale",
    reach = function() return hafen.client():options():interface() end },
  { entity = "video", verbs = { ":shadows()", ":renderScale()", ":vsync()" }, known = "shadows",
    reach = function() return hafen.client():options():video() end },
}

-- ------------------------------------------------------------------ the test

-- One handle, three questions, each scored on its own line so a failure says which half broke.
-- Returns three reasons, nil where that half held.
local function probe(t, x)
  local head = t.entity .. " has no verb 'nosuchverb'"

  local ok, e = pcall(function() return x:nosuchverb() end)
  local msg = err(ok, e)
  local ok2, e2 = pcall(function() local _ = x.nosuchverb end)
  local fmsg = err(ok2, e2)

  -- 1. both doors raise. A field read and a colon call are one lookup, so a type that answers one
  --    and not the other has its refusal hung somewhere it does not belong.
  local doors
  if ok then doors = "the call read nil"
  elseif ok2 then doors = "the field read nil"
  elseif not msg:find(head, 1, true) then doors = "call: " .. msg
  elseif not fmsg:find(head, 1, true) then doors = "field: " .. fmsg
  end

  -- 2. the message is the closedIndex SHAPE: the receiver, the key that missed, the separator, and a
  --    hint naming this type's own verbs. This is what a bare methods table cannot produce.
  local shape
  if ok then shape = "no message at all"
  elseif not msg:find(head .. " — ", 1, true) then shape = "head/separator: " .. msg
  else
    for i = 1, #t.verbs do
      if not msg:find(t.verbs[i], 1, true) then shape = "hint has no " .. t.verbs[i] .. ": " .. msg break end
    end
  end

  -- 3. the vocabulary the hint names still answers -- a closed door that shut on its own verbs too
  --    would pass both checks above and have broken everything.
  local kok, ke = pcall(function() return x[t.known](x) end)
  local known = (not kok) and (":" .. t.known .. "() broke: " .. err(kok, ke)) or nil

  return doors, shape, known
end

local reached, broke = {}, { doors = {}, shape = {}, known = {} }

local function sweep()
  for _, t in ipairs(TYPES) do
    if not reached[t.entity] then
      local ok, x = pcall(t.reach)
      if ok and x then
        reached[t.entity] = true
        local doors, shape, known = probe(t, x)
        if doors then broke.doors[#broke.doors + 1] = t.entity .. " (" .. doors .. ")" end
        if shape then broke.shape[#broke.shape + 1] = t.entity .. " (" .. shape .. ")" end
        if known then broke.known[#broke.known + 1] = t.entity .. " (" .. known .. ")" end
      end
    end
  end
end

local function verdict()
  local n, missing = 0, {}
  for _, t in ipairs(TYPES) do
    if reached[t.entity] then n = n + 1 else missing[#missing + 1] = t.entity end
  end
  local where = (#missing == 0) and "all reached"
    or ("not reached: " .. table.concat(missing, ", "))
  local scored = " (" .. n .. "/" .. #TYPES .. " reached; " .. where .. ")"

  check(#broke.doors == 0,
        "every handle reached raises on both doors, x:nosuchverb() and x.nosuchverb" .. scored,
        table.concat(broke.doors, "; "))
  check(#broke.shape == 0,
        "and raises in the closedIndex shape -- the receiver, the key, and a hint naming its own"
        .. " verbs, which a bare methods table produces none of" .. scored,
        table.concat(broke.shape, "; "))
  check(#broke.known == 0,
        "and still answers the verbs that hint names" .. scored,
        table.concat(broke.known, "; "))

  -- The section's own refusal is answered before any vocabulary is consulted: a dot call on a section
  -- is a different mistake with a different fix, and the new sentence must not swallow it.
  refuses("hafen.client().options() still says to use a colon call",
          function() return hafen.client().options() end, "use a COLON call")

  manualCheck("with every addon you normally run enabled, watch the console through a login and a few"
              .. " minutes of play",
              "no new error out of an addon -- a typo one of them carries on a Buff, Meter, Sound,"
              .. " IconCat, Mask, the profiling handle, a scope or an options handle now raises"
              .. " where it read nil, so this is where an old one surfaces")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- A buff and a recorded mask are not up the instant the command runs, and the map streams in after
-- the HUD does. Eight seconds, then whatever it reached.
local TICKS, EVERY = 8, 1.0

local function run()
  pass, fail, manual = 0, 0, 0
  reached, broke = {}, { doors = {}, shape = {}, known = {} }

  local left, finished = TICKS, false
  sweep()
  local timer
  timer = hafen.timer():every(EVERY, function()
    if finished then return end             -- the verdict is written once, whatever the timer does
    sweep()
    left = left - 1
    local done = true
    for _, t in ipairs(TYPES) do
      if not reached[t.entity] then done = false break end
    end
    if done or (left <= 0) then
      finished = true
      timer:cancel()
      verdict()
    end
  end)
end

hafen.slash():register("t084-2", run)       -- the only way in: a suite does not start itself
