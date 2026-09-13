-- 146.1 — the file, and the documents in it. Self-checking suite.
--
-- Two documents, one on each door: `seen` is the addon's own ("scope": "client"), `settings` is the
-- character's (a bare name). Both are written, both doors are flushed, and the file is asked about itself.
-- The proof that a document is READ BACK from the file is a run counter: the first run of a load finds
-- none and asks for a :reload; the first run of the next load finds the count the previous load wrote.

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

local win          -- the window this suite remembers, built once per load
local sawEarlier   -- decided by this load's first run: did the file hold a count from an earlier load?

-- The window is built off the console handler's step (:t146 runs under the typed tree's monitor), and
-- once per load: a :reload tears it down, and remember("win") on the new one puts it back where it was.
local function window()
  if win and win:exists() then return end
  win = hafen.ui():window():title("146.1"):size(160, 40):position(50, 50)
  win:on("Draw", function(ev)
    local g = ev:g()
    g:color(220, 220, 220)
    g:text("drag me, then :reload", 6, 12)
  end)
  win:remember("win")
end

local function run()
  local store = hafen.store()
  local names = table.concat(store:list(), ",")
  check(names == "seen", "hafen.store():list() names the client document (seen)", names)

  local s = hafen.session():current()
  local ss = s and s:store()
  local cnames = ss and table.concat(ss:list(), ",")
  check(cnames == "settings", "s:store():list() names the character's document (settings)", cnames)

  local seen = store:get("seen")
  local settings = ss and ss:get("settings")
  local seenBefore = seen.runs
  local settingsBefore = settings and settings.runs
  local stamp = os.time()
  seen.runs = (seen.runs or 0) + 1
  seen.stamp = stamp
  if settings then
    settings.runs = (settings.runs or 0) + 1
    settings.stamp = stamp
  end

  local back = store:flush()
  check(back == store, "hafen.store():flush() answers the store", back)
  local cback = ss and ss:flush()
  check(ss and (cback == ss), "s:store():flush() answers that character's store", cback)
  check((store:get("seen").stamp == stamp) and settings and (ss:get("settings").stamp == stamp),
        "both documents hold the write after the flush", tostring(seen.stamp) .. "/" .. tostring(settings and settings.stamp))

  local info = store:info()
  local file = tostring(info.file)
  check(file:sub(-19) == "146-sqlite.1.sqlite", ":info().file ends in 146-sqlite.1.sqlite", file)
  check(not file:find("account", 1, true), ":info().file holds no \"account\"", file)
  check((type(info.bytes) == "number") and (info.bytes > 0), ":info().bytes is over 0 after the flush", info.bytes)

  if sawEarlier == nil then
    sawEarlier = (seenBefore ~= nil) and (settingsBefore ~= nil)
  end
  if sawEarlier then
    check(true, "an earlier run is counted (client " .. tostring(seen.runs) .. ", character "
          .. tostring(settings and settings.runs) .. ")")
  else
    manualCheck(":reload and run :t146 again", "[pass] an earlier run is counted")
  end

  hafen.timer():after(0, window)
  manualCheck("drag the suite's window, :reload, run :t146 again", "the window opens where you left it")
  manualCheck("run :t146 on the launcher's rebuilt runtime", "the same summary line")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  pass, fail, manual = 0, 0, 0
end

hafen.console():on("t146", run)   -- the only way in: a suite does not start itself
