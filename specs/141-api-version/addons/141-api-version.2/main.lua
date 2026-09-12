-- 141.2 — Load out of date AddOns. Self-checking suite.
--
-- The box lives on the AddOns panel and nowhere else, so the panel is where this suite reads: `:t141`
-- arms a watch, the maintainer opens the panel, and once `window[title=AddOns]` is in the tree the
-- suite asserts the box and reads its own row off the panel's widgets. What a program cannot cause --
-- this suite's own manifest declaring "9.0" -- is the [manual] half: the maintainer edits it between
-- runs and reloads through the panel, and the automatic half re-proves the row on every run.

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

local WINDOW = "window[title=AddOns]"                     -- the Options window while the AddOns panel shows
local BOX = "@CheckBox[text=Load out of date AddOns]"     -- the one box that is not a row's
local ROW = "label[text^=" .. ADDON.id .. "]"             -- this suite's own row, by the name its manifest gives
local WATCH, STEP = 30, 0.5                               -- seconds the panel has to appear in, and the poll

-- The Options window of the character on screen, while it shows the AddOns panel; nil before that, and nil
-- on the login screen, where there is no tree to search.
local function panel()
  local s = hafen.session():current()
  if s == nil then return nil end
  return s:ui():match(WINDOW)
end

-- Everything the panel can be asked while it is open: the box, printing what it holds, and this suite's
-- row -- the parent of the label that carries the suite's name, read through its children, which is the
-- checkbox, that name and the status.
local function inspect(wnd)
  local box = wnd:match(BOX)
  check(box ~= nil, "the panel holds a 'Load out of date AddOns' box"
        .. (box and (" (value: " .. tostring(box:value()) .. ")") or ""), "no " .. BOX .. " inside " .. WINDOW)
  local name = wnd:match(ROW)
  if name == nil then
    check(false, "the suite's row reads 'loaded v0.1'", "no row whose label starts with " .. ADDON.id)
    return
  end
  local texts = {}
  for _, w in ipairs(name:parent():children():list()) do
    local t = w:text()
    if w ~= name and t ~= nil and t ~= "" then texts[#texts + 1] = t end
  end
  local status = table.concat(texts, " | ")
  check(status == "loaded v0.1", "the suite's row reads 'loaded v0.1'", status)
end

local function finish()
  manualCheck("write \"api_version\": \"9.0\" in this suite's manifest, tick Load out of date AddOns, Reload UI, :t141",
              "the row reads loaded v0.1, :t141 answers, and the log has loading out of date addon '"
              .. ADDON.id .. "'")
  manualCheck("tick it off, Reload UI", "the row reads outdated (API 9.0, client 1.0)")
  manualCheck("write \"1.0\" back, Reload UI", "the row reads loaded v0.1")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local watch   -- the armed poll, so a second :t141 replaces the first rather than doubling it

local function run()
  pass, fail, manual = 0, 0, 0
  if watch ~= nil then watch:cancel() end
  hafen.log():write("open the AddOns panel of the character on screen (Ctrl+O, AddOns) within "
                    .. WATCH .. " s -- the checks run when it appears")
  local polls = 0
  watch = hafen.timer():every(STEP, function()
    polls = polls + 1
    local ok, wnd = pcall(panel)
    if ok and wnd == nil and polls * STEP < WATCH then return end
    watch:cancel()
    if not ok then
      check(false, "the AddOns panel can be searched", wnd)
    elseif wnd == nil then
      check(false, "the AddOns panel was opened within " .. WATCH .. " s", "no " .. WINDOW .. " on screen")
    else
      local done, err = pcall(inspect, wnd)
      if not done then check(false, "the panel's widgets can be read", err) end
    end
    finish()
  end)
end

hafen.console():on("t141", run)   -- the only way in: a suite does not start itself
