-- 140.4 — the six addons draw their pages. Self-checking suite.
--
-- :t140 arms a bounded watch and asks the maintainer to open Options > AddOns and pick each of the six rows.
-- Everything it scores is read through the Options window's own widgets: the AddOns tab's list is the labels
-- of its PanelList, and a page is an AddonOptionsPanel whose heading is the addon's display name and whose
-- column is the root the addon filled. The suite holds a page of its own, so its row is in the list too.

local SUITE  = "140.4 — the six addons draw their pages"
local SIX    = {"Actionbars", "Builder helper", "Essentials", "Hitboxes", "Simple Animal Radius", "Themes"}
-- Load order is the addons folder's own order, which on this client's filesystem is by id: the suite's
-- folder ("140-…") sorts before the six.
local ORDER  = {SUITE, "Actionbars", "Builder helper", "Essentials", "Hitboxes", "Simple Animal Radius",
                "Themes"}
local TICK   = 0.25     -- seconds between reads of the window
local LIMIT  = 180      -- seconds the watch stays armed for the six pages to be opened

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

local opts = hafen.client():options():addon()
opts:panel(function(root)
  hafen.ui():label():parent(root):text("the suite's own page: one label, so the row above has a page")
end)

-- ---------------------------------------------------------------- reading the window

-- The AddOns tab's list: the PanelList whose rows name this suite, its labels in row order (top to bottom).
local function listNames(win)
  for _, list in ipairs(win:matchAll("@PanelList")) do
    local rows = {}
    for _, lbl in ipairs(list:matchAll("label")) do
      local row = lbl:parent()
      rows[#rows + 1] = {y = row and row:position().y or 0, name = lbl:text() or ""}
    end
    table.sort(rows, function(a, b) return a.y < b.y end)
    local names, mine = {}, false
    for i, r in ipairs(rows) do
      names[i] = r.name
      if r.name == SUITE then mine = true end
    end
    if mine then return names end
  end
  return nil
end

-- Every addon page standing in the window, filled or not: [heading] = how many children its column holds.
local function pages(win)
  local out = {}
  for _, p in ipairs(win:matchAll("@AddonOptionsPanel")) do
    local first = p:children():list()[1]
    local heading = first and first:text()
    local root = p:matchAll("column")[1]
    if heading and root then out[heading] = root:children():count() end
  end
  return out
end

-- ---------------------------------------------------------------- the watch

local watch                -- the timer while armed
local names                -- the AddOns list as last read with the suite's row in it
local filled = {}          -- [display name] = the most children its page was seen holding

local function has(list, name)
  for _, n in ipairs(list) do
    if n == name then return true end
  end
  return false
end

local function finish()
  if watch then watch:cancel() end
  watch = nil

  local seen = names or {}
  local missing = {}
  for _, n in ipairs(SIX) do
    if not has(seen, n) then missing[#missing + 1] = n end
  end
  check(#missing == 0, "the six are listed on Options > AddOns",
        (#missing == 0) and "" or ("missing " .. table.concat(missing, ", ") .. " in {"
          .. table.concat(seen, ", ") .. "}"))

  local ours = {}
  for _, n in ipairs(seen) do
    if has(ORDER, n) then ours[#ours + 1] = n end
  end
  check(table.concat(ours, "|") == table.concat(ORDER, "|"),
        "the list is in load order, the suite's own row among them", table.concat(ours, ", "))

  for _, n in ipairs(SIX) do
    local count = filled[n]
    check((count or 0) >= 1, n .. ": its page fills with " .. tostring(count or 0) .. " controls",
          count and "an empty column" or "the page was not opened, or its column never came")
  end

  manualCheck("on Essentials, flip Swimming and press Reload UI, then open the page again",
              "the page comes back with it flipped (flip it back afterwards)")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function poll()
  local s = hafen.session():current()
  if not s then return false end
  local win = s:ui():matchAll("window[title=Options]")[1]
  if not win then return false end
  local seen = listNames(win)
  if seen then names = seen end
  for heading, count in pairs(pages(win)) do
    if count > (filled[heading] or -1) then filled[heading] = count end
  end
  for _, n in ipairs(SIX) do
    if (filled[n] or 0) < 1 then return false end
  end
  return names ~= nil
end

local function arm()
  if watch then watch:cancel() end
  pass, fail, manual, names, filled = 0, 0, 0, nil, {}
  hafen.log():write("[t140] open Options > AddOns and pick each of the six rows; scored as they fill, within "
    .. LIMIT .. " s")
  local elapsed = 0
  watch = hafen.timer():every(TICK, function()
    elapsed = elapsed + TICK
    if poll() or (elapsed >= LIMIT) then finish() end
  end)
end

hafen.console():on("t140", arm)   -- the only way in: a suite does not start itself
