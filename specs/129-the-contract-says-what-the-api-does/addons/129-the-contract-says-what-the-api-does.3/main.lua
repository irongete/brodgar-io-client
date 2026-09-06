-- 129.3 -- the census, and :info() stated by category. Self-checking suite: run with :t129.
--
-- The task counted every closed vocabulary and rewrote two statements of a rule that claimed no exception.
-- Prose is not what a suite can read, so this asserts what the census CLAIMS, on both sides of its line.
--
-- Three vocabularies it marks as carrying :info() -- a gob, an item, a patch -- must answer a table, and a
-- table whose keys are the ones their own page lists and no others: a key the page does not name is drift
-- the page cannot see, and an always-there key gone missing or changed type is the same drift from the
-- other end. One it marks EXEMPT -- a Sub, a carrier of an ending, whose whole state is that it has not
-- ended -- must refuse :info(), and refuse naming the two verbs it does answer, which is what its own page
-- says happens to a name a Sub does not carry.
--
-- The item is the one receiver here that only the server can produce. It is waited for over a bounded
-- window, and if the window closes empty the verdict says what to be carrying for the re-run.

local pass, fail = 0, 0

local function say(line) hafen.log():write(line) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    say("[pass] " .. what)
  else
    fail = fail + 1
    say("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg" -- a SPACE where a Lua error has a colon -- and the
-- sandbox loads a DebugLib for its instruction hard-stop, so a caught error carries a traceback after it.
-- A verdict is ONE line, so both go.
local function why(err)
  err = tostring(err):gsub("\nstack traceback:.*", "")
  return (err:gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail saying every one of `want`.
local function refuses(what, fn, want)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for i = 1, #want do
    if not err:find(want[i], 1, true) then said = false end
  end
  check(said, what, err)
end

-- The snapshot itself: it answers, it is a table, and every key in it is one the page names.
local function snapshot(what, fn, named)
  local ok, i = pcall(fn)
  if not ok then check(false, what, "raised: " .. why(i)); return nil end
  if type(i) ~= "table" then check(false, what, "a " .. type(i)); return nil end
  local stray = {}
  for k in pairs(i) do
    if not named[k] then stray[#stray + 1] = tostring(k) end
  end
  table.sort(stray)
  check(#stray == 0, what, "carries a key its page does not name: " .. table.concat(stray, " "))
  return i
end

-- The keys the page says are ALWAYS there, in one line: each present, each of the type the page gives it.
local function always(what, i, want)
  if not i then check(false, what, "no snapshot to read"); return end
  local bad = {}
  for k, t in pairs(want) do
    if type(i[k]) ~= t then bad[#bad + 1] = k .. "=" .. ((i[k] == nil) and "absent" or type(i[k])) end
  end
  table.sort(bad)
  check(#bad == 0, what, table.concat(bad, " "))
end

-- ------------------------------------------------------------------ what each page lists

local GOB = {id = true, x = true, y = true, angle = true, name = true, isplayer = true, hp = true,
             moving = true, speed = true, speech = true, icon = true, overlays = true, sdt = true,
             visible = true}
local GOB_ALWAYS = {id = "number", angle = "number", moving = "boolean", visible = "boolean"}

local ITEM = {res = true, name = true, quantity = true, progress = true, durability = true,
              quality = true, contents = true, handle = true, cell = true, slots = true}

local PATCH = {kind = true, rotate = true, scale = true, alpha = true, visible = true, clickable = true,
               exists = true, drawn = true, position = true, tint = true, anchor = true, offset = true,
               ring = true, border = true, occluded = true}
local PATCH_ALWAYS = {kind = "string", rotate = "number", scale = "number", alpha = "number",
                      visible = "boolean", clickable = "boolean", exists = "boolean", drawn = "boolean",
                      occluded = "boolean"}

-- ------------------------------------------------------------------ finding the one server-made receiver

local function firstItem(w)
  if not w then return nil end
  local ok, list = pcall(function() return w:items():list() end)
  if ok and list then return list[1] end
  return nil
end

-- The backpack, then what is worn, then the cursor: whichever holds one first. Every reach is guarded,
-- because this runs on a frame and the session it was handed can go away between two of them.
local function anItem(s)
  local ok, it = pcall(function()
    local ui = s:ui()
    local found = firstItem(ui and ui:inventory()) or firstItem(ui and ui:equipment())
    if found then return found end
    local p = s:player()
    local h = p and p:hand()
    return h and h:item()
  end)
  return ok and it or nil
end

-- fn(v) as soon as get() answers one, or fn(nil) once n drawn frames have gone by without one.
local function waitFor(n, get, fn)
  local v = get()
  if v then fn(v); return end
  local i, sub = 0, nil
  sub = hafen.event():on("Update", function()
    i = i + 1
    local got = get()
    if got then sub:off(); fn(got)
    elseif i >= n then sub:off(); fn(nil) end
  end)
end

local function square(centre, r)
  return {centre:offset(-r, -r), centre:offset(r, -r), centre:offset(r, r), centre:offset(-r, r)}
end

-- ------------------------------------------------------------------ the run

local function run()
  local s = hafen.session():current()
  local me = s and s:player() and s:player():gob()
  local here = me and me:position()
  if not here then
    say("[fail] this suite needs a character standing in the world -- got: no drawn session")
    return
  end

  -- ---- a gob: the census marks it as carrying one ---------------------------------------------------
  local g = snapshot("a gob answers :info(), and every key in it is one its page names",
                     function() return me:info() end, GOB)
  always("...with the keys that page says are always there", g, GOB_ALWAYS)

  -- ---- a patch: laid here, read back, taken up again ------------------------------------------------
  for _, old in ipairs(hafen.virtual():patch():list()) do hafen.virtual():patch():remove(old) end
  local p = hafen.virtual():patch():add(square(here, 4), here)
  local pi = snapshot("a patch answers :info(), and every key in it is one its page names",
                      function() return p:info() end, PATCH)
  always("...with the keys that page says are always there", pi, PATCH_ALWAYS)
  check(pi and (pi.kind == "patch") and (type(pi.ring) == "table") and (#pi.ring == 4),
        "...and the ring it was made from reads back off it, a point per corner",
        pi and (tostring(pi.kind) .. ", ring "
                .. ((type(pi.ring) == "table") and tostring(#pi.ring) or "absent")))
  hafen.virtual():patch():remove(p)

  -- ---- the one the census marks EXEMPT: a carrier of an ending --------------------------------------
  local sub = hafen.event():on("Update", function() end)
  refuses("a Sub carries no :info(), and the refusal names the two verbs it does answer",
          function() return sub:info() end,
          {"sub has no verb 'info'", "a subscription", "it answers :key() and :off()"})
  sub:off()

  -- ---- an item: the one receiver here only the server can make --------------------------------------
  waitFor(120, function() return anItem(s) end, function(it)
    if it then
      local ii = snapshot("an item answers :info(), and every key in it is one its page names",
                          function() return it:info() end, ITEM)
      check(ii and ((ii.res == nil) or (type(ii.res) == "string")),
            "...and every field on that page being optional is what the snapshot holds to",
            ii and ("res=" .. type(ii.res)) or "no snapshot to read")
    else
      check(false, "an item answers :info(), and every key in it is one its page names",
            "nothing in the backpack, worn or on the cursor -- carry one item and re-run")
      check(false, "...and every field on that page being optional is what the snapshot holds to",
            "no item to read")
    end
    say("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
  end)
end

hafen.console():on("t129", run)   -- the only way in: a suite does not start itself
