-- 038.3 — the two events, and the only core edits. Self-checking suite; see specs/addons/TESTING.md.
--
-- 038.1 put the verb on the gob and 038.2 brought the other space home; this task makes the attachment
-- OBSERVABLE. `GobOverlayAdded` / `GobOverlayRemoved` carry { gob, key, native } and fire for both halves of
-- the read: an addon's own attach/remove, and the GAME putting one of its overlays on a gob (a lit fire, a
-- growing crop, a curiosity's sparkle) -- which is this feature's only core edit, three `// addon:` lines in
-- Gob.java (addol's body, Overlay.remove, and ctick's expiry of a sprite that ended by itself).
--
-- Two rules the checks below are shaped by:
--   * The addon half is OWNER-SCOPED, the game's broadcasts. An overlay key is per addon, so a native = false
--     event handed to a bystander would name a key that addon cannot read; a native key is a resource name,
--     which everyone can.
--   * A native overlay is a UNION over its resource name (038.1 measured 13 of 33 decorated gobs carrying two
--     of one resource), so the event follows the KEY: the second `foo` arriving is not an add.
--
-- Both events are QUEUED onto the tick -- addol runs on the loader threads, and nothing may call into Lua from
-- there -- so every check below is staged behind a timer, and one of them asserts that queueing directly.
--
-- READ-ONLY: it declares no permissions, attaches only to the player's own gob, and never writes persistent
-- state. The two subscriptions are made at load (that is how the game's own overlays are ever seen at all --
-- they happen when they happen), but nothing RUNS until ':t038-3'.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local KEY  = "evt"                    -- this suite's own key on the player's gob
local MINE = { [KEY] = true }         -- the keys this suite ever attaches, for the foreign-event test

local log      = {}                   -- OUR events since the last stage, as "A:<key>:<native>"
local reads    = {}                   -- what a hafen.* read inside the handler answered, per event
local natives  = {}                   -- the game's own events seen since login (last few, plus a count)
local nativeN  = 0
local foreign  = 0                    -- non-native events under a key this suite never attached: must stay 0
local icon

hafen.event():on("Load", function() icon = hafen.asset():get("icon.png") end)

local function record(tag)
  return function(e)
    if e.native then
      nativeN = nativeN + 1
      natives[#natives + 1] = tag .. " " .. tostring(e.key)
      if #natives > 4 then table.remove(natives, 1) end
      return
    end
    if not MINE[e.key] then                 -- another addon's overlay: we must never be told about it
      foreign = foreign + 1
      return
    end
    log[#log + 1] = tag .. ":" .. tostring(e.key) .. ":" .. tostring(e.native)
    -- The handler runs on the UI thread, so a hafen.* read inside it WORKS -- and reads the truth: on an add
    -- the overlay is already there, on a removal it is already gone.
    local ok, v = pcall(function() return e.gob:overlay():get(e.key) end)
    reads[#reads + 1] = (not ok) and ("error " .. tostring(v))
                        or (tostring(e.gob:id()) .. "/" .. ((v ~= nil) and "there" or "gone"))
  end
end

hafen.event():on("GobOverlayAdded", record("A"))
hafen.event():on("GobOverlayRemoved", record("R"))

-- What arrived since the last stage: the event line, and what the handler's own read answered on the FIRST
-- of them. Both are cleared, so each stage reads only its own events.
local function seen()
  local s, r = table.concat(log, "|"), reads[1]
  log, reads = {}, {}
  return s, r
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a re-run reports its own counts, not the last one's
  local me = hafen.player() and hafen.player():gob()
  if (me == nil) or (not me:exists()) then
    check(false, "the player's own gob is the thing every check below attaches to", "no player gob yet")
    return summary()
  end
  local myId = me:id()
  me:overlay():remove(KEY)            -- a re-run starts from a bare gob
  log, reads = {}, {}

  -- 1. THE EVENT IS QUEUED ONTO THE TICK, not fired inside the verb. It has to be: the game's own half comes
  --    off the loader threads, and one mechanism means one moment for both halves.
  me:overlay():add(KEY):text("ev")
  eq("an attach does not fire inside gob:overlay -- the event is queued onto the tick", #log, 0)

  hafen.timer():after(0.4, function()
    -- 2. ONE ADD, and the payload says which gob, which key, and whose.
    local got, read = seen()
    eq("gob:overlay():add(key) fires GobOverlayAdded exactly once, native = false", got, "A:" .. KEY .. ":false")
    eq("...and a hafen.* read INSIDE the handler works -- it runs on the UI thread, on the payload's own gob",
       read, tostring(myId) .. "/there")
    me:overlay():remove(KEY)

    hafen.timer():after(0.4, function()
      local gotR, readR = seen()
      eq("gob:overlay():remove(key) fires GobOverlayRemoved exactly once", gotR, "R:" .. KEY .. ":false")
      eq("...and the handler reads the TRUTH on that side too: the overlay is already gone",
         readR, tostring(myId) .. "/gone")
      me:overlay():add(KEY):text("ev")

      hafen.timer():after(0.4, function()
        seen()
        -- 3. A REPLACE reports BOTH, so a handler keeping its own set stays balanced: the key survives, but
        --    the thing under it is a different one -- and here it is even in the other SPACE.
        if icon then me:overlay():add(KEY):image(icon):offset(0, 0, 18) end

        hafen.timer():after(0.4, function()
          if icon then
            eq("a REPLACE fires the removal AND the add -- and a WORLD-space kind fires the same pair",
               seen(), "R:" .. KEY .. ":false|A:" .. KEY .. ":false")
          else
            check(false, "the suite's own asset (icon.png) loaded, for the world-space half", "no icon")
          end
          me:overlay():remove(KEY)

          hafen.timer():after(0.4, function()
            eq("...and the last removal is reported once, whichever space it was in", seen(), "R:" .. KEY .. ":false")
            eq("the suite leaves nothing of its own on the gob", me:overlay():get(KEY), nil)

            -- 4. OWNER-SCOPED: an overlay key is per addon, so an event naming one is too.
            eq("no event ever arrived under a key this addon did not attach (the addon half is owner-scoped)",
               foreign, 0)

            -- 5. THE GAME'S OWN HALF. Nothing here can make the server decorate a gob, so this is a
            --    measurement: what has the world done since login?
            if nativeN > 0 then
              check(true, ("the game's own overlays fire the pair too, native = true, keyed by resource name"
                           .. " -- %d since login, latest: %s"):format(nativeN, table.concat(natives, ", ")))
            else
              manualCheck("walk past a lit fire, a growing crop or a curiosity, then run ':t038-3' again",
                          "the last line reads 'the game's own overlays fire the pair too' with a count and"
                          .. " resource names (gfx/...), instead of this [manual]")
            end
            manualCheck("run ':lua hafen.player():gob():overlay():add(\"cross-lua\"):text(\"x\")', then ':t038-3'",
                        "the owner-scoped line above still passes (foreign = 0) -- the console is a DIFFERENT"
                        .. " addon, so its overlay is none of our business; then clear it with"
                        .. " ':lua hafen.player():gob():overlay():remove(\"cross-lua\")'")
            summary()
          end)
        end)
      end)
    end)
  end)
end

hafen.slash():register("t038-3", run)   -- the only way in: a suite does not start itself
