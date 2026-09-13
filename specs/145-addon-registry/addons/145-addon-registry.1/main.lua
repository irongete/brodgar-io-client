-- 145.1 — Browse: the hub client and the tab that searches it. Self-checking suite.
--
-- Runs against the local hub: start the client with `ant -Dregistry=http://localhost:3730/addons/api run`
-- and publish this suite's two fixture zips first, the way anyone publishes -- on the hub's website, signed
-- in, pick the zip, Publish (the first output line says which). It waits for the AddOns manager (open it:
-- Ctrl+O, AddOns) and drives the Browse field through w:value(s) -- the write is silent, so a search that
-- follows it proves the tab polls the field rather than listening to keystrokes.

local pass, fail, manual = 0, 0, 0

local function out(s) hafen.log():write(s) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    out("[pass] " .. what)
  else
    fail = fail + 1
    out("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  out("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  out("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- ---- the manager's widgets, read fresh each time (the tree is the client's, never cached)

local function win()
  local s = hafen.session():current()
  return s and s:ui():match("window[title=AddOns]") or nil
end

-- The Browse tab is the one holding the field's caption; the field is the one TextEntry inside it.
local function browseTab(w)
  local cap = w:match("@Label[text^=Search the addons published at]")
  return cap and cap:parent() or nil
end

local function field(w)
  local tab = browseTab(w)
  return tab and tab:match("@TextEntry") or nil
end

-- A Browse row is a widget of two labels: the metadata, and the status. Given the metadata's prefix,
-- answer the row's status text and the metadata text, or nil when no such row is in the list.
local function rowStatus(w, prefix)
  local tab = browseTab(w)
  local port = tab and tab:match("@Scrollport")
  if not port then return nil end
  for _, lbl in ipairs(port:matchAll("@Label")) do
    local t = lbl:text()
    if t and t:sub(1, #prefix) == prefix then
      for _, other in ipairs(lbl:parent():matchAll("@Label")) do
        if other ~= lbl then return other:text(), t end
      end
    end
  end
  return nil
end

-- How many rows the Browse list holds: the labels under its port, two per row.
local function rowCount(w)
  local tab = browseTab(w)
  local port = tab and tab:match("@Scrollport")
  return port and (#port:matchAll("@Label") / 2) or -1
end

local function lineSays(w, text)
  local tab = browseTab(w)
  return tab and (tab:match("@Label[text=" .. text .. "]") ~= nil) or false
end

-- ---- the steps: each writes the field, then waits (bounded) for what the tab should show

local steps = {}

local function step(name, query, limit, test)
  steps[#steps + 1] = { name = name, query = query, limit = limit, test = test }
end

step("Simple Chat 1.0.0 is a row with the status `in addons/ by hand`", "simple", 15, function(w)
  local status, meta = rowStatus(w, "Simple Chat")
  if meta and meta:find("1.0.0", 1, true) then return true, status == "in addons/ by hand", status end
  return false
end)

step("145-old reads `outdated (API 9.0, client 1.0)`", "145-old", 15, function(w)
  local status = rowStatus(w, "145 Old")
  if status then return true, status == "outdated (API 9.0, client 1.0)", status end
  return false
end)

step("145-plain reads an empty status", "145-plain", 15, function(w)
  local status = rowStatus(w, "145 Plain")
  if status then return true, status == "", "'" .. status .. "'" end
  return false
end)

-- Ordered after a search that showed rows, so that zero rows is the empty text's doing.
step("an empty field shows no rows", "", 5, function(w)
  if rowCount(w) == 0 then return true, true end
  return false
end)

step("zzzz-nothing: the tab's line says `no addon matches`", "zzzz-nothing", 15, function(w)
  if lineSays(w, "no addon matches") then return true, true end
  return false
end)

-- Leaves the tab showing both fixtures, which is what the manual check reads.
step("145 lists both fixtures", "145", 15, function(w)
  if rowStatus(w, "145 Old") and rowStatus(w, "145 Plain") then return true, true end
  return false
end)

local function run()
  out("[setup] client on -Dregistry=http://localhost:3730/addons/api; fixtures/145-old.zip and"
    .. " fixtures/145-plain.zip published at http://localhost:3730/addons/me (sign in, pick the zip, Publish)")
  pass, fail, manual = 0, 0, 0
  local ticks, i, timer = 0, 0, nil        -- ticks: half-seconds since the manager was looked for, or since a step began
  local function stop()
    summary()
    timer:cancel()
  end
  local function fire()
    ticks = ticks + 1
    local w = win()
    if not w then
      if ticks >= 60 then
        check(false, "the AddOns manager is open (window[title=AddOns])", "not open within 30 s")
        stop()
      end
      return
    end
    if i == 0 then
      -- the manager is up: the tabs, and the field
      check(w:match("@Button[text=Installed]") ~= nil, "the manager has an Installed tab button")
      check(w:match("@Button[text=Browse]") ~= nil, "the manager has a Browse tab button")
      local f = field(w)
      check(f ~= nil, "the Browse tab holds a text entry", "no @TextEntry under the Browse caption")
      if not f then return stop() end
    end
    local s = steps[i]
    if s then
      local reached, ok, got = s.test(w)
      if reached then
        check(ok, s.name, got)
      elseif ticks >= s.limit * 2 then
        check(false, s.name, "nothing to read within " .. s.limit .. " s")
      else
        return   -- still waiting
      end
    end
    i = i + 1
    ticks = 0
    s = steps[i]
    if s then
      local f = field(w)
      local ok, err = pcall(function() f:value(s.query) end)
      if not ok then
        local why = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""):match("^[^\n]*")
        check(false, "the field takes w:value (is widget.value granted to this suite?)", why)
        return stop()
      end
    else
      manualCheck("press Browse", "two tabs, the field reading `145`, and two rows: 145 Old"
        .. " (outdated (API 9.0, client 1.0)) and 145 Plain (no status)")
      stop()
    end
  end
  timer = hafen.timer():every(0.5, fire)
end

hafen.console():on("t145", run)   -- the only way in: a suite does not start itself
