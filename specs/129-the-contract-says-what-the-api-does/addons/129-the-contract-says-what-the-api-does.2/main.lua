-- 129.2 -- the edge rule names the vocabulary that ships. Self-checking suite: run with :t129.
--
-- The task changed PROSE. CLAUDE.md's rule and conventions.md's table now carry three rows beside
-- Added/Removed/Changed -- a threshold crossed, a selection made, a click on an entity of yours -- and
-- name Load and Disable as the moments whose word is the moment itself. Prose is not what a suite can
-- read, so this asserts what the prose CLAIMS: that every key those rows name is a key the client fires,
-- spelled exactly as written. Each goes onto the bus, the emitter its page names; sub:key() answers the
-- spelling the subscription went in under, so a key renamed while the rule was being written fails here
-- rather than in a reader's addon; and sub:off() ends it, so the run leaves nothing subscribed.
--
-- Then the two refusals. A near miss inside the session family must be refused NAMING the family, which
-- is the refusal saying what exists. And GhostAdded -- the discarded alternative in one string, a click
-- key bent to fit one of the three edges -- must not be a key at all.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg" -- a SPACE where a Lua error has a colon. And the
-- sandbox loads a DebugLib for its instruction hard-stop, so every caught error carries a traceback after
-- it; a verdict is ONE line, so that goes too.
local function why(err)
  err = tostring(err):gsub("\nstack traceback:.*", "")
  return (err:gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A key the rule names: the bus takes it, sub:key() answers the spelling it went in under, and sub:off()
-- ends it and hands the subscription back.
local function subscribes(key)
  local what = "the bus takes " .. key .. ", and sub:off() ends it"
  local ok, sub = pcall(function() return hafen.event():on(key, function() end) end)
  if not ok then
    check(false, what, "refused: " .. why(sub))
    return
  end
  local okk, k = pcall(function() return sub:key() end)
  if not okk then
    check(false, what, "sub:key() raised: " .. why(k))
    return
  end
  if k ~= key then
    check(false, what, "sub:key() = " .. tostring(k))
    return
  end
  local oko, back = pcall(function() return sub:off() end)
  check(oko and (back == sub), what,
        oko and ("sub:off() gave " .. tostring(back)) or ("sub:off() raised: " .. why(back)))
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

local function run()
  -- ---- a threshold crossed, and a selection made ----------------------------------------------------
  subscribes("SessionEnteredWorld")
  subscribes("SessionSelected")
  subscribes("ChannelSelected")

  -- ---- a click on an entity of your own -------------------------------------------------------------
  subscribes("GhostClicked")
  subscribes("SpriteClicked")
  subscribes("ObjectClicked")
  subscribes("PatchClicked")

  -- ---- the moments whose word is the moment ---------------------------------------------------------
  subscribes("Load")
  subscribes("Disable")

  -- ---- and the refusal says what exists -------------------------------------------------------------
  refuses("a near miss in the session family is refused naming the family (SessionEntered)",
          function() hafen.event():on("SessionEntered", function() end) end,
          {"unknown event 'SessionEntered'", "SessionAdded", "SessionEnteredWorld", "SessionSelected",
           "SessionRemoved"})
  refuses("a click key bent to one of the three edges is not a key (GhostAdded)",
          function() hafen.event():on("GhostAdded", function() end) end,
          {"unknown event 'GhostAdded'", "docs/addons/api/event/bus/"})

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("t129", run)   -- the only way in: a suite does not start itself
