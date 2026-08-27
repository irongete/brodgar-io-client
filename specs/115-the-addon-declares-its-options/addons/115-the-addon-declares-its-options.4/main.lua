-- 115.4 — an addon's options page scrolls. Self-checking suite.
--
-- Type :t115 in the world. It needs no window open and no row clicked: declaring is what puts this addon
-- in the AddOns list, and the list picks its first row on its own, so the page stands in the tree from the
-- moment this file ran. Forty rows is the point of it -- the page has to stop growing somewhere, and the
-- checks below say where without measuring anything: the page is shorter than its own column.

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

local function report()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- ---------------------------------------------------------------- the declaration

-- The AddOns list draws this addon's manifest name and no verb hands that back, so the row is matched on
-- the one piece of it that is this addon and nothing else.
local TAG = "115.4"

local function isMine(s)
  return (s ~= nil) and (s:find(TAG, 1, true) ~= nil)
end

local opts = hafen.client():options():addon()

-- Thirty-nine rows of five kinds, and then the fortieth. Enough that the column is several times the box
-- the client gives it, which is the whole condition being tested.
local KINDS = {"boolean", "number", "choice", "button", "label"}
local booleans = 0

for i = 1, 39 do
  local kind = KINDS[((i - 1) % #KINDS) + 1]
  local name = kind:sub(1, 1) .. i
  local cap = "Row " .. i
  if kind == "boolean" then
    booleans = booleans + 1
    opts:boolean(name):label(cap):default(true):add()
  elseif kind == "number" then
    opts:number(name):label(cap):range(1, 10):default(5):add()
  elseif kind == "choice" then
    opts:choice(name):label(cap):choices{"a", "b"}:default("a"):add()
  elseif kind == "button" then
    opts:button(name):label(cap):press(function() end):add()
  else
    opts:label(name):label(cap):text("row " .. i):add()
  end
end

-- The fortieth, and the only text row on the page: one `@Entry` to find, at the bottom of a column the
-- port cannot show the end of.
local last = opts:text("last"):label("Row 40"):default("alpha"):add()

-- ---------------------------------------------------------------- reading the window

-- This addon's own options page, identified by the heading the panel opens with -- its first child, a
-- Label at the origin carrying the addon's display name.
local function myPage(ui)
  for _, p in ipairs(ui:matchAll("@AddonOptionsPanel")) do
    for _, ch in ipairs(p:children():list()) do
      local at = ch:position()
      if (at.x == 0) and (at.y == 0) and isMine(ch:text()) then return p end
    end
  end
  return nil
end

-- The one widget of type `ty` inside w, or nil where it holds none or more than one.
local function one(w, ty)
  local found = w:matchAll("@" .. ty)
  return (#found == 1) and found[1] or nil
end

-- ---------------------------------------------------------------- the run

local wrote

local function phase2()
  local ui = hafen.session():current()
  ui = ui and ui:ui()
  local page = ui and myPage(ui)
  check(page ~= nil, "this addon's options page stands in the tree", page)
  if not page then return report() end

  check(opts:option():count() == 40 and #page:matchAll("@Check") == booleans,
        "forty rows declared, and the page drew a control for every one of them",
        opts:option():count() .. " declared, " .. #page:matchAll("@Check") .. " checkboxes of "
        .. booleans)

  local port = one(page, "Scrollport")
  local rows = one(page, "Rows")
  check((port ~= nil) and (rows ~= nil), "the page holds one scroll port, with the row column inside it",
        tostring(port) .. " / " .. tostring(rows))
  if (port == nil) or (rows == nil) then return report() end

  -- The column is longer than the box it is shown through, and the page is the box rather than the
  -- column. Two reads, no measurement: whatever the row heights come out at on this client, a page that
  -- grew with its rows would fail the second.
  check(rows:size().h > port:size().h, "the row column is taller than the port showing it",
        rows:size().h .. " column vs " .. port:size().h .. " port")
  check(page:size().h < rows:size().h, "and the page is shorter than the column, so the window is too",
        page:size().h .. " page vs " .. rows:size().h .. " column")

  -- The bar itself: it stands, and it spans the port, which is what says the port was refitted rather
  -- than left at the box it was built with. How far it REACHES is bar.max, and nothing unprotected reads
  -- that back -- widget:range() answers nil on a control the client built, not this addon -- so the drag
  -- below is what checks it.
  local bar = one(port, "Scrollbar")
  check((bar ~= nil) and (bar:size().h == port:size().h), "the port's scrollbar stands and spans it",
        bar and (bar:size().h .. "px bar vs " .. port:size().h .. "px port"))

  -- The fortieth row: below the fold, drawn by nothing, and answering exactly as the first one does.
  local field = one(page, "Entry")
  check((field ~= nil) and (field:position().y > port:size().h) and (field:value() == wrote),
        "the last row sits past the fold and still reads back what Lua wrote",
        field and (field:position().y .. "px down, reads " .. tostring(field:value()) .. " want "
                   .. tostring(wrote)))

  manualCheck("open Options, pick the AddOns tab and pick this suite",
              "a scrollbar down the right of the rows")
  manualCheck("drag that scrollbar to the bottom",
              "it moves, and the last row is whole and usable, captioned Row 40 with a text field")
  report()
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t115 scores its own run, not both
  wrote = (last:value() == "alpha") and "beta" or "alpha"
  last:value(wrote)
  -- The page mirrors its options once a frame, so the read of the control waits for one.
  hafen.timer():after(0.2, phase2)
end

hafen.console():on("t115", run)   -- the only way in: a suite does not start itself
