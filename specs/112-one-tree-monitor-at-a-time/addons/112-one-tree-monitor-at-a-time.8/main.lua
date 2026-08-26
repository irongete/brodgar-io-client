-- 112.8 — threading becomes a page, and it says which seam runs where.
-- Self-checking suite. Type :t112 AT THE GAME'S OWN CHAT LINE, in the world. No manual step.
-- (A line typed at the terminal instead reaches the console on a reader thread holding no tree at
-- all, and the first check below has nothing to be refused -- it says so when that happens.)
--
-- The page under test is docs/addons/api/threading.md. PAGE below is the block it prints, copied
-- character for character, and every refusal this run raises is compared against it -- so the page
-- cannot drift from the client that raises it without this failing.

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

-- The message a pcall came back with, with the chunk:line prefix taken off. Two shapes reach here:
-- "@<chunk>.lua:189 msg" for an error the bridge raised in Java, and "<chunk>.lua:12: msg" for one
-- raised in Lua. The colon after the number is optional, and so is the "@".
local function why(ok, err)
  if ok then
    return "<no error>"
  end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", "", 1))
end

-- Is a {x=, y=} read the place we wrote? Within a pixel: a design pixel goes to the screen and back
-- through an interface scale that need not be a whole number.
local function near(got, x, y)
  return (got ~= nil) and (math.abs(got.x - x) <= 1) and (math.abs(got.y - y) <= 1)
end

local function place(p)
  return (p == nil) and "<nowhere>" or (p.x .. "," .. p.y)
end

-- The refusal exactly as docs/addons/api/threading.md prints it, wrapping and all.
local PAGE = [[
one tree monitor at a time: this handler already holds the widget tree of the addon layer, and
writing a widget of the character "yourname" would take a second one — two trees held at once is
the shape this client deadlocks in. A Draw handler, a control's own notification, a gesture, a
drop and a console line each run under one tree's monitor and may reach only that tree. Do the
work that crosses trees where no monitor is held: widget:on("Update", fn),
hafen.event():on("Update", fn) or hafen.timer():after(0, fn), all of which run on the engine
step, holding none.]]

-- One line, so the page's own line breaks are the only difference allowed between the two strings.
local function flat(s)
  return (tostring(s):gsub("%s+", " "):gsub("^ ", ""):gsub(" $", ""))
end

-- The page's block with its two example trees swapped for the pair this refusal actually names.
-- Two sentinels, so substituting the first cannot be re-substituted by the second.
local function expected(held, want)
  local s = PAGE:gsub("the addon layer", "\1", 1)
  s = s:gsub('the character "yourname"', "\2", 1)
  s = s:gsub("\1", (held:gsub("%%", "%%%%")))
  s = s:gsub("\2", (want:gsub("%%", "%%%%")))
  return flat(s)
end

-- Where two strings first differ, with a window of each side. nil when they are equal.
local function diff(got, want)
  if got == want then
    return nil
  end
  local n, i = math.min(#got, #want), 1
  while (i <= n) and (got:sub(i, i) == want:sub(i, i)) do
    i = i + 1
  end
  return "differ at " .. i .. ": got [" .. got:sub(i, i + 39) .. "] page [" .. want:sub(i, i + 39) .. "]"
end

local LAYER = "the addon layer"

local A_X, A_Y = 21, 22           -- where each door writes its own probe in the character's tree
local B_X, B_Y = 31, 32
local C_X, C_Y = 41, 42
local WIN_X, WIN_Y = 40, 40       -- where the layer window the doors also write stands
local WATCH = 12.0                -- the bounded window the item revision is waited for

local st = {}

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Every key the Draw handler writes is created here with a value that is never nil: a Draw runs under
-- its tree's monitor and not on the step, so it must never grow this table while the step reads it.
local function blank()
  return {
    done = false, drew = false,
    drawOk = "<the Draw handler never ran>", drawErr = "<the Draw handler never ran>",
    consoleErr = "<the console line was not reached>", consoleRefused = 0,
    doorA = "<never ran>", doorB = "<never ran>", doorC = "<never ran>",
    building = false, entrySeen = false, entryInline = false, entryStep = false,
    reading = false, changed = false, changedInline = false, changedStep = false, items = 0,
  }
end

-- ---------------------------------------------------------------- the console line

-- This body runs where the console is, which is inside ONE widget tree -- the character's when the
-- line was typed at the client's own chat, the layer's when it was typed at an addon's. So exactly
-- one of these two builds is the second tree, and which one says where we are.
local function fromConsoleLine()
  st = blank()
  local s = hafen.session():current()
  if not s then
    return nil
  end
  local layerOk, layerErr = pcall(function()
    st.stray = hafen.ui():widget():size(2, 2):position(1, 1)
  end)
  local charOk, charErr = pcall(function()
    st.stray2 = hafen.ui():widget():parent(s:ui():root()):size(2, 2):position(1, 1)
  end)
  local n = (layerOk and 0 or 1) + (charOk and 0 or 1)
  st.consoleRefused = n
  if not layerOk then
    st.consoleErr, st.consoleWant = why(layerOk, layerErr), LAYER
  elseif not charOk then
    st.consoleErr, st.consoleWant = why(charOk, charErr), nil    -- the character's is the second tree
  else
    st.consoleErr = "<neither build was refused>"
  end
  return s
end

-- ---------------------------------------------------------------- the scoring

local function finish()
  if st.done then
    return
  end
  st.done = true
  for _, sub in ipairs(st.subs) do
    sub:off()
  end

  local s = hafen.session():current()
  local CHAR = s and ('the character "' .. s:user() .. '"') or "<no character>"
  -- The console held one tree and the other was refused; which way round says which console it was.
  local consoleWant = (st.consoleWant == LAYER) and expected(CHAR, LAYER) or expected(LAYER, CHAR)

  check(st.consoleRefused == 1, "a console line holds one tree, and the other is refused",
        (st.consoleRefused == 0)
          and "neither build was refused -- was :t112 typed at the terminal rather than the game's chat?"
          or (st.consoleRefused .. " of the two builds refused"))
  check(st.drawOk == false, "a Draw handler holds its own tree, and the other is refused", st.drawErr)
  local dDraw = diff(flat(st.drawErr), expected(LAYER, CHAR))
  local dCons = diff(flat(st.consoleErr), consoleWant)
  check((dDraw == nil) and (dCons == nil),
        "both refusals are the message the page prints, character for character",
        dDraw or dCons)

  check(st.doorA == true and near(st.readA, A_X, A_Y),
        "hafen.timer():after(0, fn) writes the layer and a character in one handler",
        (st.doorA == true) and place(st.readA) or st.doorA)
  check(st.doorB == true and near(st.readB, B_X, B_Y),
        'hafen.event():on("Update", fn) writes the layer and a character in one handler',
        (st.doorB == true) and place(st.readB) or st.doorB)
  check(st.doorC == true and near(st.readC, C_X, C_Y),
        'widget:on("Update", fn) writes the layer and a character in one handler',
        (st.doorC == true) and place(st.readC) or st.doorC)

  check(st.entrySeen and st.entryStep and not st.entryInline,
        'an "Added" lands on the step AFTER the widget entered, not inside the entering',
        st.entrySeen and ("step=" .. tostring(st.entryStep) .. " inline=" .. tostring(st.entryInline))
          or "the entry was never reported")
  check(st.changed and st.changedStep and not st.changedInline,
        'an item "Changed" lands on the step AFTER the description resolved, not inside the read',
        st.changed and ("step=" .. tostring(st.changedStep) .. " inline=" .. tostring(st.changedInline))
          or (st.items .. " items watched, none revised in " .. WATCH .. "s"))

  local built = { st.win, st.pA, st.pB, st.pC, st.pD, st.entry, st.stray, st.stray2 }
  for i = 1, 8 do                     -- a numeric loop: two of these are legitimately nil
    if built[i] then built[i]:destroy() end
  end
  report()
end

-- ---------------------------------------------------------------- the step

local function start(s)
  st.subs = {}
  st.win = hafen.ui():window():title("112.8"):size(150, 34):position(WIN_X, WIN_Y)
  local root = s:ui():root()
  st.pA = hafen.ui():widget():parent(root):size(4, 4):position(2, 2)
  st.pB = hafen.ui():widget():parent(root):size(4, 4):position(2, 2)
  st.pC = hafen.ui():widget():parent(root):size(4, 4):position(2, 2)
  st.pD = hafen.ui():widget():parent(root):size(4, 4):position(2, 2)   -- the Draw handler's own target

  -- The refusal the page quotes, raised where the page says it is raised.
  st.subs[#st.subs + 1] = st.win:on("Draw", function()
    if st.drew then
      return
    end
    st.drew = true
    local ok, err = pcall(function() st.pD:position(99, 99) end)
    st.drawOk, st.drawErr = ok, why(ok, err)
  end)

  -- Door A: a timer body. Each door writes BOTH trees, which is what "may reach any" means.
  -- Not in st.subs: a timer is ended with :cancel(), and this one has fired by the time we score.
  hafen.timer():after(0, function()
    local ok, err = pcall(function()
      st.win:position(WIN_X, WIN_Y)
      st.pA:position(A_X, A_Y)
    end)
    st.doorA = ok or why(ok, err)
  end)

  -- Door B: the bus's own Update.
  st.subs[#st.subs + 1] = hafen.event():on("Update", function()
    if st.doorB ~= "<never ran>" then
      return
    end
    local ok, err = pcall(function()
      st.win:position(WIN_X, WIN_Y)
      st.pB:position(B_X, B_Y)
    end)
    st.doorB = ok or why(ok, err)
  end)

  -- Door C: the same door addressed at one of our own surfaces -- the step, not the drawing pass.
  st.subs[#st.subs + 1] = st.win:on("Update", function()
    if st.doorC ~= "<never ran>" then
      return
    end
    local ok, err = pcall(function()
      st.win:position(WIN_X, WIN_Y)
      st.pC:position(C_X, C_Y)
    end)
    st.doorC = ok or why(ok, err)
  end)

  -- The entry seam's own timing, on a widget we place ourselves so the moment is ours to bracket.
  st.subs[#st.subs + 1] = s:ui():on("[name=112-one-tree-monitor-at-a-time.8/entry]", "Added", function(w)
    if st.entrySeen then
      return
    end
    st.entrySeen = true
    st.entryInline = st.building          -- true would mean it fired inside Widget.add, the old defect
    st.entryStep = hafen.client():stepping()
  end)

  -- The item seam's, over what the run reaches: subscribe first, then ask, so an item whose tooltip
  -- has not landed fires when it does.
  st.subs[#st.subs + 1] = s:ui():on("item", "Added", function(icon)
    local item = icon:item()
    if (item == nil) or st.changed then
      return
    end
    st.items = st.items + 1
    item:on("Changed", function()
      if st.changed then
        return
      end
      st.changed = true
      st.changedInline = st.reading
      st.changedStep = hafen.client():stepping()
    end)
    st.reading = true
    pcall(function() return item:quality() end)   -- asking is what makes the client build it
    st.reading = false
  end)

  hafen.timer():after(0.5, function()
    st.building = true
    st.entry = hafen.ui():widget():parent(s:ui():root()):name("entry"):size(4, 4):position(3, 3)
    st.building = false
  end)

  hafen.timer():after(WATCH, function()
    st.readA, st.readB, st.readC = st.pA:position(), st.pB:position(), st.pC:position()
    finish()
  end)
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t112 scores its own run, not both
  local s = fromConsoleLine()         -- this body is NOT the step: it is the console's own tree
  if not s then
    st = blank()
    st.subs = {}
    check(false, "a character is on screen", "none -- run :t112 in the world")
    return report()
  end
  hafen.timer():after(0, function() start(s) end)
end

hafen.console():on("t112", run)   -- the only way in: a suite does not start itself
