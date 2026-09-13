-- 145.2 — Install: the package, the staging and the reload that applies it. Self-checking suite.
--
-- Runs against the local hub: start the client with `ant -Dregistry=http://localhost:3730/addons/api run`,
-- with fixtures/145-plain.zip published there (on the hub's website, signed in, pick the zip, Publish) and
-- NO addons/145-plain folder beside the client for the first run. It waits for the AddOns manager (open it:
-- Ctrl+O, AddOns) and drives the Browse field through w:value(s). It is run twice, and tells the two runs
-- apart by what the 145-plain row says: with the folder absent the row offers Install, and the maintainer's
-- press is watched until the stage lands; after Reload UI the second run reads the install back on both tabs.

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

local function hasInstall(r)
  return r:match("@Button[text=Install]") ~= nil
end

-- The System channel's newest lines, searched for a plain text.
local function systemSays(text)
  local s = hafen.session():current()
  local ch = s and s:chat():find("System")
  if not ch then return false end
  local n = ch:message():count()
  for i = n, math.max(1, n - 40), -1 do
    local m = ch:message():get(i)
    local line = m and m:text()
    if line and line:find(text, 1, true) then return true end
  end
  return false
end

local STAGED = "staged 1.0.0 - Reload UI to apply"

-- ---- the steps: each may write the field, then waits (bounded) for what the manager should show.
-- A test answers (reached, ok, got): not reached is "keep waiting", up to the step's limit in seconds.

local steps, i, ticks = {}, 0, 0

-- A step that reports on its own (a manual line, or a branch that checks and decides) is `silent`.
local function step(name, query, limit, test, silent)
  steps[#steps + 1] = { name = name, query = query, limit = limit, test = test, silent = silent }
end

local function essentialsStep()
  step("a by-hand row (Essentials, in addons/ by hand) offers no Install button", "essentials", 15, function(w)
    local r, status = row(browseTab(w), "Essentials")
    if r then return true, (status == "in addons/ by hand") and not hasInstall(r), status .. (hasInstall(r) and " with Install" or " without Install") end
    return false
  end)
end

-- Run 1: the folder is absent, the row offers Install, and the press is the maintainer's.
local function firstRun()
  essentialsStep()
  step("the 145-plain row is back for the press", "145-plain", 15, function(w)
    local r = row(browseTab(w), "145 Plain")
    if r then return true, hasInstall(r), "no Install button" end
    return false
  end)
  step("press Install", nil, 0, function(w)
    manualCheck("press Install on the 145 Plain row",
      "the status runs `downloading n%` then `" .. STAGED .. "`, and the button goes")
    return true, true
  end, true)
  step("after the press, the row reads `" .. STAGED .. "` without a button", nil, 60, function(w)
    local r, status = row(browseTab(w), "145 Plain")
    if r and status == STAGED then return true, not hasInstall(r), "Install still offered" end
    return false
  end)
  step("the System channel carries the log line `staged 145-plain v1.0.0`", nil, 5, function(w)
    if systemSays("staged 145-plain v1.0.0") then return true, true end
    return false
  end)
  step("the Installed tab's hint reads `Changes pending`", nil, 5, function(w)
    local t = installedTab(w)
    if t and t:match("@Label[text^=Changes pending]") then return true, true end
    return false
  end)
  step("Reload UI, then run again", nil, 0, function(w)
    manualCheck("press Reload UI (Installed tab), then run :t145 again",
      "the second run: the Browse row `installed v1.0.0` with no button, the Installed row `loaded v1.0.0`")
    return true, true
  end, true)
end

-- Run 2: the reload applied the stage, and both tabs read it back.
local function secondRun(r)
  check(not hasInstall(r), "the installed row offers no Install button", "Install still offered")
  step("the Installed tab lists 145 Plain as `loaded v1.0.0`", nil, 5, function(w)
    local ir, istatus = row(installedTab(w), "145 Plain")
    if ir then return true, istatus == "loaded v1.0.0", istatus end
    return false
  end)
  essentialsStep()
end

local function run()
  out("[setup] client on -Dregistry=http://localhost:3730/addons/api; fixtures/145-plain.zip published at"
    .. " http://localhost:3730/addons/me; run 1 with no addons/145-plain folder, run 2 after Reload UI")
  pass, fail, manual = 0, 0, 0
  steps, i, ticks = {}, 0, 0
  local timer
  local function stop()
    summary()
    timer:cancel()
  end
  -- The first step decides the run: the row with the folder absent offers Install and reads nothing;
  -- with the install applied it reads `installed v1.0.0`.
  step("the 145-plain row is found", "145-plain", 15, function(w)
    local r, status = row(browseTab(w), "145 Plain")
    if not r then return false end
    if status == "installed v1.0.0" then
      check(true, "the 145-plain Browse row reads `installed v1.0.0` (a record present)")
      secondRun(r)
    else
      check((status == "") and hasInstall(r), "with the folder absent, the 145-plain row reads an empty status and offers Install",
        "'" .. tostring(status) .. "'" .. (hasInstall(r) and " with Install" or " without Install"))
      firstRun()
    end
    return true, true
  end, true)
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
      local f = field(w)
      check(f ~= nil, "the Browse tab holds a text entry", "no @TextEntry under the Browse caption")
      if not f then return stop() end
    end
    local s = steps[i]
    if s then
      local reached, ok, got = s.test(w)
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
    s = steps[i]
    if not s then return stop() end
    if s.query then
      local ok, err = pcall(function() field(w):value(s.query) end)
      if not ok then
        local why = tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""):match("^[^\n]*")
        check(false, "the field takes w:value (is widget.value granted to this suite?)", why)
        return stop()
      end
    end
  end
  timer = hafen.timer():every(0.5, fire)
end

hafen.console():on("t145", run)   -- the only way in: a suite does not start itself
