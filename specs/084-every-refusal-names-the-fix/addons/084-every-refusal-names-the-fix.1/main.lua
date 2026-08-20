-- 084.1 -- the entity types answer for themselves. Self-checking suite.
--
-- The 37 types that used to read an unknown key as nil now throw naming themselves and their verbs.
-- A type is only reachable when the game has the data for it, so the sweep RETRIES over a bounded
-- window and scores what it reached; the missing names are printed, because which ones they are is
-- the answer the [manual] line asks for.

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
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
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
local function me() local s = S() return s and s:player():gob() end

-- The four VR kinds are four collections of one section, and the kind is data here -- so the door is
-- a table of the four spellings rather than a dynamic index, which is what the grammar asks for.
local VR = {
  ghost  = function(v) return v:ghost()  end,
  sprite = function(v) return v:sprite() end,
  object = function(v) return v:object() end,
  widget = function(v) return v:widget() end,
}

local held = {}                             -- what the sweep stood up and must take down again

local function stand(kind, what, flat)
  local a = me()                            -- the player's own gob: the one anchor always durable
  if not a then return nil end
  local e = VR[kind](hafen.vr()):add(what, a)
  if e then
    e:visible(false)                        -- reached, never seen: it is gone again a line later
    held[#held + 1] = { kind = kind, e = e, flat = flat }
  end
  return e
end

-- entity   the spelling the refusal must carry (the receiver, as Retired keys it)
-- verbs    real verbs of that type, which its hint must name (at least three, or all it has)
-- known    a READ that must still answer on the handle
-- reach    a live one, or nil when the game has not got one right now
-- release  undo what reach stood up (optional)
local TYPES = {
  { entity = "session", verbs = { ":user()", ":world()", ":ui()" }, known = "exists",
    reach = function() return S() end },
  { entity = "widget", verbs = { ":type()", ":children()", ":destroy()" }, known = "exists",
    reach = function() local s = S() return s and s:ui():root() end },
  { entity = "gob", verbs = { ":id()", ":position()", ":name()" }, known = "exists",
    reach = function() local s = S() return s and s:world():gob():nearest() end },
  { entity = "position", verbs = { ":x()", ":y()", ":info()" }, known = "info",
    reach = function() local s = S() return s and s:world():position(0, 0) end },
  { entity = "item", verbs = { ":res()", ":quantity()", ":drop()" }, known = "exists",
    reach = function()
      local s = S()
      local inv = s and s:ui():inventory()
      local its = inv and inv:items()
      return its and its[1]
    end },
  { entity = "contents", verbs = { ":items()", ":quality()", ":level()" }, known = "info",
    reach = function()
      local s = S()
      local inv = s and s:ui():inventory()
      for _, it in ipairs((inv and inv:items()) or {}) do
        local c = it:contents()
        if c then return c end
      end
    end },
  { entity = "hand", verbs = { ":item()", ":use(" }, known = "item",
    reach = function() local s = S() return s and s:player():hand() end },
  { entity = "kin", verbs = { ":name()", ":group()", ":online()" }, known = "exists",
    reach = function() local s = S() return s and s:kin():list()[1] end },
  { entity = "partymember", verbs = { ":id()", ":gob()", ":leader()" }, known = "exists",
    reach = function() local s = S() return s and s:party():list()[1] end },
  { entity = "marker", verbs = { ":name()", ":position()", ":icon()" }, known = "exists",
    reach = function() return hafen.map():marker():list()[1] end },
  { entity = "segment", verbs = { ":id()", ":grid()", ":markers()" }, known = "exists",
    reach = function() return hafen.map():segment():current() end },
  { entity = "grid", verbs = { ":id()", ":tile()", ":height()" }, known = "exists",
    reach = function() local s = S() return s and s:world():grid():list()[1] end },
  { entity = "toggle", verbs = { ":tag()", ":shown()", ":held()" }, known = "shown",
    reach = function() return hafen.map():overlay():list()[1] end },
  { entity = "overlay", verbs = { ":key()", ":gob()", ":native()" }, known = "exists",
    reach = function()
      local g = me()
      return g and g:overlay():add("t084")
    end,
    release = function() local g = me() if g then pcall(function() g:overlay():remove("t084") end) end end },
  { entity = "quest", verbs = { ":id()", ":title()", ":conditions()" }, known = "exists",
    reach = function() local s = S() return s and s:quest():list()[1] end },
  { entity = "condition", verbs = { ":description()", ":status()", ":quest()" }, known = "exists",
    reach = function()
      local s = S()
      for _, q in ipairs((s and s:quest():list()) or {}) do
        local c = q:conditions()
        if c and c[1] then return c[1] end
      end
    end },
  { entity = "wound", verbs = { ":id()", ":name()", ":severity()" }, known = "exists",
    reach = function() local s = S() return s and s:wound():list()[1] end },
  { entity = "slot", verbs = { ":index()", ":empty()", ":pagina()" }, known = "index",
    reach = function() local s = S() return s and s:actionbar():get(0) end },
  { entity = "pagina", verbs = { ":res()", ":name()", ":path()" }, known = "exists",
    reach = function() local s = S() return s and s:menugrid():list()[1] end },
  { entity = "speed", verbs = { ":index()", ":name()", ":available()" }, known = "exists",
    reach = function() local s = S() return s and s:speed():get(0) end },
  { entity = "attr", verbs = { ":name()", ":base()", ":composite()" }, known = "name",
    reach = function() local s = S() return s and s:char():attr():get("str") end },
  { entity = "skill", verbs = { ":name()", ":cost()", ":known()" }, known = "exists",
    reach = function() local s = S() return s and s:char():skill():list()[1] end },
  { entity = "credo", verbs = { ":name()", ":acquired()", ":pursuing()" }, known = "exists",
    reach = function() local s = S() return s and s:char():credo():list()[1] end },
  { entity = "experience", verbs = { ":name()", ":score()", ":modified()" }, known = "exists",
    reach = function() local s = S() return s and s:char():experience():list()[1] end },
  { entity = "food", verbs = { ":cap()", ":feps()", ":hunger()" }, known = "exists",
    reach = function() local s = S() return s and s:char():food() end },
  { entity = "studyslot", verbs = { ":res()", ":lp()", ":attention()" }, known = "exists",
    reach = function() local s = S() return s and s:study():slot():list()[1] end },
  { entity = "craft", verbs = { ":name()", ":inputs()", ":tools()" }, known = "exists",
    reach = function() local s = S() return s and s:craft():current() end },
  { entity = "maneuver", verbs = { ":res()", ":available()", ":used()" }, known = "exists",
    reach = function() local s = S() return s and s:fight():maneuver():list()[1] end },
  { entity = "deckcard", verbs = { ":slot()", ":key()", ":maneuver()" }, known = "exists",
    reach = function() local s = S() return s and s:fight():deck()[1] end },
  { entity = "fightsummary", verbs = { ":maxActions()", ":deckSize()", ":activeSave()" }, known = "exists",
    reach = function() local s = S() return s and s:fight():summary() end },
  { entity = "opponent", verbs = { ":id()", ":gob()", ":info()" }, known = "exists",
    reach = function() local s = S() return s and s:fight():target() end },
  { entity = "uioverlay", verbs = { ":onDraw(", ":exists()", ":destroy()" }, known = "exists",
    reach = function() return hafen.ui():overlay() end,
    release = function(h) pcall(function() h:destroy() end) end },
  { entity = "keybindings", verbs = { ":register()", ":key()", ":list()" }, known = "list",
    reach = function() return hafen.client():options():keybindings() end },
  { entity = "ghost", verbs = { ":position()", ":scale()", ":res()" }, known = "exists",
    reach = function() return stand("ghost", "gfx/terobjs/arch/logcabin") end },
  { entity = "sprite", verbs = { ":position()", ":scale()", ":image()" }, known = "exists",
    reach = function() return stand("sprite", hafen.asset():get("dot.png")) end },
  { entity = "object", verbs = { ":position()", ":scale()", ":mesh()" }, known = "exists",
    reach = function() return stand("object", hafen.asset():get("tri.gltf")) end },
  { entity = "widget (vr)", key = "widget", verbs = { ":position()", ":scale()", ":screen()" },
    known = "exists",
    reach = function()
      local w = hafen.ui():widget():size(8, 8)
      local e = stand("widget", w, w)
      if not e then pcall(function() w:destroy() end) end
      return e
    end },
}

-- ------------------------------------------------------------------ the test

-- One type, one verdict: both doors raise, the message names the receiver and three of its verbs,
-- and a verb it really has still answers. Returns nil when it held, or why it did not.
local function probe(t, x)
  local receiver = t.key or t.entity

  local ok, e = pcall(function() return x:nosuchverb() end)
  if ok then return "the call read nil" end
  local msg = err(ok, e)

  local ok2, e2 = pcall(function() local _ = x.nosuchverb end)
  if ok2 then return "the field read nil" end
  local fmsg = err(ok2, e2)

  if not msg:find(receiver .. " has no verb", 1, true) then return "not named: " .. msg end
  if not fmsg:find(receiver .. " has no verb", 1, true) then return "field: " .. fmsg end

  local want = #t.verbs
  if want > 3 then want = 3 end
  for i = 1, want do
    if not msg:find(t.verbs[i], 1, true) then return "no " .. t.verbs[i] .. ": " .. msg end
  end

  local kok, ke = pcall(function() return x[t.known](x) end)
  if not kok then return ":" .. t.known .. "() broke: " .. err(kok, ke) end
  return nil
end

local reached, broke = {}, {}
local samples = {}                          -- a gob and an item, for the retired-row checks

local function sweep()
  for _, t in ipairs(TYPES) do
    if not reached[t.entity] then
      local ok, x = pcall(t.reach)
      if ok and x then
        reached[t.entity] = true
        if (t.entity == "gob") or (t.entity == "item") then samples[t.entity] = x end
        local why = probe(t, x)
        if why then broke[#broke + 1] = t.entity .. " (" .. why .. ")" end
        if t.release then t.release(x) end
      end
    end
  end
end

local function takeDown()
  for _, h in ipairs(held) do
    pcall(function() VR[h.kind](hafen.vr()):remove(h.e) end)
    if h.flat then pcall(function() h.flat:destroy() end) end   -- a stood widget comes back to the flat UI
  end
  held = {}
end

local function verdict()
  takeDown()

  local n, missing = 0, {}
  for _, t in ipairs(TYPES) do
    if reached[t.entity] then n = n + 1 else missing[#missing + 1] = t.entity end
  end
  local where = (#missing == 0) and "all reached"
    or ("not reached: " .. table.concat(missing, ", "))
  check(#broke == 0,
        "every type reached refuses :nosuchverb() and .nosuchverb, names itself and three of its verbs,"
        .. " and still answers a known verb (" .. n .. "/" .. #TYPES .. " reached; " .. where .. ")",
        table.concat(broke, "; "))

  -- The rows that were already there must go on firing: closedIndex looks a retirement up before it
  -- writes its own sentence, so a regression here is the whole table going quiet.
  local g = samples["gob"]
  if g then
    refuses("a retired row still fires: gob:isplayer", function() return g:isplayer() end, "gob:isPlayer()")
  else
    check(false, "a retired row still fires: gob:isplayer", "no gob reached -- run it in the world")
  end
  local it = samples["item"]
  if it then
    refuses("a retired row still fires: item:pos", function() return it:pos() end, "an item's place is two verbs now")
  else
    check(false, "a retired row still fires: item:pos", "no item reached -- open an inventory")
  end

  -- The section's own refusal must not be swallowed by the new one: a dot call on a section is a
  -- different mistake with a different fix, and it is answered before any vocabulary is consulted.
  refuses("hafen.time().clock() still says to use a colon call",
          function() return hafen.time().clock() end, "use a COLON call")

  manualCheck("with a character in the world and an inventory open, read the count on the verdict line",
              "the count out of " .. #TYPES .. ", and the names it lists as not reached")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The character sheet, the quest log and the study window build a beat after login, so one pass
-- would score a type absent that is merely late. Six seconds, then whatever it reached.
local TICKS, EVERY = 6, 1.0

local function run()
  pass, fail, manual = 0, 0, 0
  reached, broke, samples, held = {}, {}, {}, {}

  local left, finished = TICKS, false
  sweep()
  local timer
  timer = hafen.timer():every(EVERY, function()
    if finished then return end             -- the verdict is written once, whatever the timer does
    sweep()
    left = left - 1
    local done = true
    for _, t in ipairs(TYPES) do
      if not reached[t.entity] then done = false; break end
    end
    if done or (left <= 0) then
      finished = true
      timer:cancel()
      verdict()
    end
  end)
end

hafen.slash():register("t084-1", run)       -- the only way in: a suite does not start itself
