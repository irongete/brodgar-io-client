-- 063.2 -- the client keeps two addons. Self-checking suite.
--
-- Run :t063-2. It answers in about a fifth of a second and prints the whole verdict at once.
--
-- What it proves: the client loads THREE addons and no others -- the inspector, the profiler, and this
-- suite. The fourteen demos are gone from the tree, so nothing of theirs can be loaded.
--
-- How, and why this way rather than through the console. `hafen.client():profiling():addons()` is one row
-- per LUA OWNER: every loaded addon, plus `(console)` for the :lua prompt. So the loaded set is something
-- the program can READ, name by name, and the same read that shows the fourteen are absent shows the two
-- tools are present -- which is what makes the absence mean "not installed" rather than "not looked for".
-- The slash namespace cannot say this: hafen.slash():register is last-registration-wins across addons
-- (HookApi.newSlashCommand), so registering a name a loaded addon owns SUCCEEDS and quietly takes the
-- command over. Registering the fourteen retired names would prove nothing and would hijack :widgetstack.
--
-- The one price is the switch: those rows exist only while the client's profiler is armed, and that switch
-- is persisted. So this run reads it, arms it if it was off, and puts it back exactly as it found it --
-- and then checks that it did, which is the last line of the block.

local pass, fail, manual = 0, 0, 0

local function log(s)
  hafen.log():write(s)
end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function why(err)
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every one of the words asked for.
local function refuses(what, fn, ...)
  local want = { ... }
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  local said = not ok
  for i = 1, #want do
    if not err:find(want[i], 1, true) then said = false end
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

-- ============================================================================================= the run

local SETTLE = 10          -- frames waited after arming: the switch takes effect on the NEXT frame

local ME      = "063-inspector-and-profiler.2"
local KEEP    = { profiler = true, widgetstack = true, [ME] = true, ["(console)"] = true }
local RETIRED = { "atlas", "bags", "cupboard", "hello", "hogtest", "menubutton", "netdemo",
                  "optionstest", "planner", "stockfilter", "tagger", "theme", "timers", "walker" }

local st                   -- the run in flight, nil between runs

local function list(t)
  table.sort(t)
  return (#t > 0) and table.concat(t, ", ") or "none"
end

local function finish()
  if st.upd then st.upd:off() end

  -- ---- who is loaded, read by name --------------------------------------------------------------------
  local rows = hafen.client():profiling():addons()
  local loaded, ids = {}, {}
  for _, r in ipairs(rows) do
    loaded[r.id] = true
    ids[#ids + 1] = r.id
  end
  local read = (#ids > 0)
  local unread = "no rows -- the switch did not take in " .. SETTLE .. " frames, so NOTHING was read"
  check(read, "the profiler arms and answers a row per loaded addon", unread)
  check(loaded.widgetstack == true, "the inspector is loaded (widgetstack)", list(ids))
  check(loaded.profiler == true, "the profiler is loaded (profiler)", list(ids))
  check(loaded[ME] == true, "...and so is this suite, which is what makes an ABSENCE below readable",
        list(ids))

  local found = {}
  for _, name in ipairs(RETIRED) do
    if loaded[name] then found[#found + 1] = name end
  end
  -- An absence read off an empty list is not an absence, so both of these fail rather than pass vacuously.
  check(read and (#found == 0), "not one of the fourteen retired demos is loaded",
        read and list(found) or unread)

  local extra = {}
  for _, id in ipairs(ids) do
    if not KEEP[id] then extra[#extra + 1] = id end
  end
  check(read and (#extra == 0), "...and nothing else is either: the two tools and this suite are the whole list",
        read and list(extra) or unread)

  -- ---- the name rule that DOES refuse ------------------------------------------------------------------
  refuses("a slash name the engine owns is refused, naming the command",
          function() hafen.slash():register("reload", function() end) end, "reload", "reserved")

  -- ---- the switch goes back exactly as it was ----------------------------------------------------------
  local opts = hafen.client():options():client()
  opts:profiling(st.was)
  check(opts:profiling() == st.was,
        "the profiling switch is back where the run found it (" .. tostring(st.was) .. ")",
        opts:profiling())

  manualCheck("open the AddOns panel", "exactly three rows -- profiler, widgetstack and "
              .. ME .. " -- and no other addon listed, enabled or disabled")
  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  st = nil
end

local function update()
  if not st then return end
  st.frames = st.frames + 1
  if st.frames >= SETTLE then
    local ok, err = pcall(finish)
    if not ok then                       -- never leave the client's switch where this run put it
      if st then
        if st.upd then st.upd:off() end
        pcall(function() hafen.client():options():client():profiling(st.was) end)
        st = nil
      end
      log("[fail] the run itself -- got: " .. why(err))
    end
  end
end

local function run()
  if st then
    log("063.2: already running")
    return
  end
  pass, fail, manual = 0, 0, 0

  local opts = hafen.client():options():client()
  st = { frames = 0, was = opts:profiling() }
  if not st.was then opts:profiling(true) end

  st.upd = hafen.event():on("Update", update)
  log("063.2: reading who is loaded -- the verdict prints here in a moment")
end

hafen.slash():register("t063-2", run)      -- the only way in: a suite does not start itself
