-- 145.3 — Installed: Update, Remove and Check for updates. Self-checking suite.
--
-- Runs against the local hub: start the client with `ant -Dregistry=http://localhost:3730/addons/api run`,
-- with fixtures/1.0.0/145-plain.zip published there (on the hub's website, signed in, pick the zip, Publish)
-- and fixtures/1.1.0/145-plain.zip published ONLY when run 2 says so. It waits for the AddOns manager (open
-- it: Ctrl+O, AddOns), drives the Browse field through w:value(s) from whichever tab is showing, and tells
-- its four runs apart by what the 145-plain rows say:
--   run 1  no addons/145-plain folder and the hub at 1.0.0: as 145.2 -- Install is the maintainer's press,
--          watched until the stage lands, then Reload UI
--   run 2  the Installed row reads `loaded v1.0.0`: Remove and no Update; 1.1.0 is published and the manager
--          reopened, Update is watched in and pressed, the stage watched, then Reload UI
--   run 3  the Installed row reads `loaded v1.1.0`: Remove is pressed, the mark watched on both tabs, Reload UI
--   run 4  no folder and the hub at 1.1.0: no Installed row, the Browse row empty with Install back

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

-- A tab is the widget holding its own caption; the Browse tab's field is the one TextEntry inside it.
local function tab(w, caption)
  local cap = w:match("@Label[text^=" .. caption .. "]")
  return cap and cap:parent() or nil
end

local function browseTab(w) return tab(w, "Search the addons published at") end
local function installedTab(w) return tab(w, "Enable or disable addons") end

local function field(w)
  local t = browseTab(w)
  return t and t:match("@TextEntry") or nil
end

-- A row on either tab is a widget of two labels, the metadata and the status. Given a tab and the
-- metadata's prefix, answer the row widget, its status text and its metadata text, or nil when no such row.
local function row(t, prefix)
  local port = t and t:match("@Scrollport")
  if not port then return nil end
  for _, lbl in ipairs(port:matchAll("@Label")) do
    local text = lbl:text()
    if text and text:sub(1, #prefix) == prefix then
      local r = lbl:parent()
      for _, other in ipairs(r:matchAll("@Label")) do
        if other ~= lbl then return r, other:text(), text end
      end
    end
  end
  return nil
end

local function hasButton(r, text)
  return r:match("@Button[text=" .. text .. "]") ~= nil
end

-- Which of the row's buttons stand, as one word for a verdict line.
local function buttonsOf(r)
  local names = {}
  for _, b in ipairs(r:matchAll("@Button")) do names[#names + 1] = b:text() end
  return (#names == 0) and "no button" or table.concat(names, "+")
end

-- The Installed tab's own line for the update check: `checking`, a count, or why the hub did not answer.
local function checkedLine(w)
  local t = installedTab(w)
  if not t then return nil end
  for _, lbl in ipairs(t:matchAll("@Label")) do
    local s = lbl:text() or ""
    if s == "checking" or s:find("update available", 1, true) or s:find("no addon installed", 1, true) then
      return s
    end
  end
  return nil
end

-- The System channel's newest lines, searched for a plain text.
local function systemSays(text, back)
  local s = hafen.session():current()
  local ch = s and s:chat():find("System")
  if not ch then return false end
  local n = ch:message():count()
  for i = n, math.max(1, n - (back or 40)), -1 do
    local m = ch:message():get(i)
    local line = m and m:text()
    if line and line:find(text, 1, true) then return true end
  end
  return false
end

-- ---- the steps: each may write the field, then waits (bounded) for what the manager should show.
-- A test answers (reached, ok, got): not reached is "keep waiting", up to the step's limit in seconds. A step
-- that reports on its own (a manual line, or a branch that checks and decides) is `silent`. The window may go
-- away mid-step -- the manager closed and reopened is a step of run 2 -- and a step simply waits it out.

local steps, i, ticks = {}, 0, 0

local function step(name, query, limit, test, silent)
  steps[#steps + 1] = { name = name, query = query, limit = limit, test = test, silent = silent }
end

local function manualStep(action, expect)
  step(action, nil, 0, function() manualCheck(action, expect); return true, true end, true)
end

local function reloadStep(expect)
  manualStep("press Reload UI (Installed tab), then run :t145 again", expect)
end

local function essentialsStep()
  step("the by-hand Installed row (Essentials) carries neither Update nor Remove", nil, 5, function(w)
    local r = row(installedTab(w), "Essentials")
    if r then return true, not hasButton(r, "Update") and not hasButton(r, "Remove"), buttonsOf(r) end
    return false
  end)
end

local STAGED_1 = "staged 1.0.0 - Reload UI to apply"
local STAGED_2 = "staged 1.1.0 - Reload UI to apply"
local REMOVED = "removed on Reload UI"

-- Run 1: the folder is absent and the hub is at 1.0.0 -- the row offers Install, and the press is the maintainer's.
local function firstRun()
  out("[run] 1 of 4: no addons/145-plain folder, the hub at 1.0.0")
  essentialsStep()
  manualStep("press Install on the 145 Plain row (Browse tab)",
    "the status runs `downloading n%` then `" .. STAGED_1 .. "`, and the button goes")
  step("after the press, the row reads `" .. STAGED_1 .. "` without a button", nil, 60, function(w)
    local r, status = row(browseTab(w), "145 Plain")
    if r and status == STAGED_1 then return true, not hasButton(r, "Install"), "Install still offered" end
    return false
  end)
  step("the System channel carries the log line `staged 145-plain v1.0.0`", nil, 5, function(w)
    if systemSays("staged 145-plain v1.0.0") then return true, true end
    return false
  end)
  reloadStep("run 2: the Installed row `loaded v1.0.0` with Remove and no Update")
end

-- Run 2: 1.0.0 is installed; 1.1.0 is published, the reopened manager checks, Update is pressed.
local function secondRun(r)
  out("[run] 2 of 4: 145-plain is installed at 1.0.0")
  check(hasButton(r, "Remove") and not hasButton(r, "Update"),
    "the hub-installed Installed row carries Remove and, the hub at 1.0.0, no Update", buttonsOf(r))
  -- The publish is watched on Browse: the field is re-driven every five seconds so the hub is asked again.
  local flip = false
  step("publish 1.1.0", "145-plain", 180, function(w)
    if ticks == 1 then
      manualCheck("publish fixtures/1.1.0/145-plain.zip at http://localhost:3730/addons/me (within three minutes)",
        "the suite sees the hub's 145 Plain at v1.1.0")
    elseif ticks % 10 == 0 then
      flip = not flip
      pcall(function() field(w):value(flip and "145-plain " or "145-plain") end)
    end
    local r2, _, meta = row(browseTab(w), "145 Plain")
    if r2 and meta:find("v1.1.0", 1, true) then return true, true end
    return false
  end, true)
  step("reopen the manager", nil, 0, function()
    manualCheck("close the manager, then open it again on the Installed tab (Ctrl+O, AddOns)",
      "the 145 Plain row reads `update 1.1.0` with Update and Remove")
    return true, true
  end, true)
  step("on the reopened tab, the 145 Plain row reads `update 1.1.0` with Update and Remove", nil, 60, function(w)
    local ir, istatus = row(installedTab(w), "145 Plain")
    if ir and istatus == "update 1.1.0" then
      return true, hasButton(ir, "Update") and hasButton(ir, "Remove"), buttonsOf(ir)
    end
    return false
  end)
  step("the check's line counts the update (`1 update available`)", nil, 5, function(w)
    local s = checkedLine(w)
    if s and s ~= "checking" then return true, s:match("^%d+ updates? available$") ~= nil, s end
    return false
  end)
  essentialsStep()
  manualStep("press Update on the 145 Plain row (Installed tab)",
    "the status runs `downloading n%` then `" .. STAGED_2 .. "`, and both buttons go")
  step("after the press, the row reads `" .. STAGED_2 .. "` without a button", nil, 60, function(w)
    local ir, istatus = row(installedTab(w), "145 Plain")
    if ir and istatus == STAGED_2 then
      return true, not hasButton(ir, "Update") and not hasButton(ir, "Remove"), buttonsOf(ir)
    end
    return false
  end)
  step("the System channel carries the log line `staged 145-plain v1.1.0`", nil, 5, function(w)
    if systemSays("staged 145-plain v1.1.0") then return true, true end
    return false
  end)
  step("the Installed tab's hint reads `Changes pending`", nil, 5, function(w)
    local t = installedTab(w)
    if t and t:match("@Label[text^=Changes pending]") then return true, true end
    return false
  end)
  reloadStep("run 3: the Installed row `loaded v1.1.0` with Remove and no Update")
end

-- Run 3: the update is applied; Remove is pressed and the mark read on both tabs.
local function thirdRun(r)
  out("[run] 3 of 4: 145-plain is installed at 1.1.0")
  check(hasButton(r, "Remove") and not hasButton(r, "Update"),
    "the updated Installed row carries Remove and, the record at the hub's latest, no Update", buttonsOf(r))
  step("the check's line reads `no update available`", nil, 5, function(w)
    local s = checkedLine(w)
    if s and s ~= "checking" then return true, s == "no update available", s end
    return false
  end)
  manualStep("press Remove on the 145 Plain row (Installed tab)",
    "the status reads `" .. REMOVED .. "` and the button goes")
  step("after the press, the row reads `" .. REMOVED .. "` without a button", nil, 60, function(w)
    local ir, istatus = row(installedTab(w), "145 Plain")
    if ir and istatus == REMOVED then
      return true, not hasButton(ir, "Update") and not hasButton(ir, "Remove"), buttonsOf(ir)
    end
    return false
  end)
  step("the System channel carries the log line `marked 145-plain for removal`", nil, 5, function(w)
    if systemSays("marked 145-plain for removal") then return true, true end
    return false
  end)
  step("the Installed tab's hint reads `Changes pending`", nil, 5, function(w)
    local t = installedTab(w)
    if t and t:match("@Label[text^=Changes pending]") then return true, true end
    return false
  end)
  step("the Browse row reads `" .. REMOVED .. "` too, without Install", "145-plain", 15, function(w)
    local r2, status = row(browseTab(w), "145 Plain")
    if r2 and status == REMOVED then return true, not hasButton(r2, "Install"), buttonsOf(r2) end
    if r2 and status ~= "" then return true, false, status end
    return false
  end)
  reloadStep("run 4: no 145 Plain row on Installed, the Browse row empty with Install back")
end

-- Run 4: the removal is applied -- no row on Installed, and the Browse row is back where run 1 found it.
local function fourthRun()
  out("[run] 4 of 4: the folder is gone, the hub at 1.1.0")
  step("no 145 Plain row on the Installed tab", nil, 5, function(w)
    local t = installedTab(w)
    if t and t:match("@Scrollport") then return true, row(t, "145 Plain") == nil, "a row is still there" end
    return false
  end)
  step("the System channel carries the reload's log line `removed 145-plain`", nil, 5, function(w)
    if systemSays("removed 145-plain", 300) then return true, true end
    return false
  end)
  essentialsStep()
end

local function run()
  out("[setup] client on -Dregistry=http://localhost:3730/addons/api; fixtures/1.0.0/145-plain.zip published at"
    .. " http://localhost:3730/addons/me, fixtures/1.1.0/145-plain.zip NOT until run 2 says; run 1 with no"
    .. " addons/145-plain folder, each later run after Reload UI")
  pass, fail, manual = 0, 0, 0
  steps, i, ticks = {}, 0, 0
  local timer
  local function stop()
    summary()
    timer:cancel()
  end
  -- The first step decides the run from the Installed tab: a 145 Plain row is a folder, and its version says
  -- which run; no row sends the second step to Browse, where the hub's version tells run 1 from run 4.
  step("the run is decided", nil, 5, function(w)
    local t = installedTab(w)
    if not (t and t:match("@Scrollport")) then return false end
    local ir, istatus = row(t, "145 Plain")
    if ir then
      if istatus == "loaded v1.0.0" then secondRun(ir)
      elseif istatus == "loaded v1.1.0" then thirdRun(ir)
      else check(false, "the 145 Plain Installed row is in a state a run starts from (loaded v1.0.0 or v1.1.0)", istatus) end
      return true, true
    end
    step("with the folder absent, the 145 Plain Browse row reads an empty status and offers Install", "145-plain", 15,
      function(w2)
        local r, status, meta = row(browseTab(w2), "145 Plain")
        if not r then return false end
        check((status == "") and hasButton(r, "Install"),
          "with the folder absent, the 145 Plain Browse row reads an empty status and offers Install",
          "'" .. tostring(status) .. "' with " .. buttonsOf(r))
        if meta:find("v1.1.0", 1, true) then fourthRun() else firstRun() end
        return true, true
      end, true)
    return true, true
  end, true)
  local function fire()
    ticks = ticks + 1
    local w = win()
    if i == 0 then
      if not w then
        if ticks >= 60 then
          check(false, "the AddOns manager is open (window[title=AddOns])", "not open within 30 s")
          stop()
        end
        return
      end
      local f = field(w)
      check(f ~= nil, "the Browse tab holds a text entry", "no @TextEntry under the Browse caption")
      if not f then return stop() end
    end
    local s = steps[i]
    if s then
      -- A step's query is written once the window is there to take it; the wait starts with the write.
      if s.query and not s.written then
        if not w then return end
        local ok, err = pcall(function() field(w):value(s.query) end)
        if not ok then
          local why = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""):match("^[^\n]*")
          check(false, "the field takes w:value (is widget.value granted to this suite?)", why)
          return stop()
        end
        s.written = true
        ticks = 0
        return
      end
      local reached, ok, got = false, nil, nil
      if w then reached, ok, got = s.test(w) end
      if reached then
        if not s.silent then check(ok, s.name, got) end
      elseif ticks >= s.limit * 2 then
        check(false, s.name, "nothing to read within " .. s.limit .. " s")
      else
        return   -- still waiting
      end
    end
    i = i + 1
    ticks = 0
    if not steps[i] then return stop() end
  end
  timer = hafen.timer():every(0.5, fire)
end

hafen.console():on("t145", run)   -- the only way in: a suite does not start itself
