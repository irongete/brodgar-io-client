-- 078.2 — the client's widgets are reached through their session. Self-checking suite.
--
-- The claim: a widget the game put up stands in the tree of the character it was put up for, so the
-- lookups are addressed at that character -- and a window this suite BUILDS stands in the addon layer,
-- which is a different tree that no selector reaches. Both halves are proved here, and so is the
-- refusal each side gets when it is called on the other.

local pass, fail, manual = 0, 0, 0

local function log(s) hafen.log():write(s) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every `want` in the message.
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or tostring(err):gsub("^.-%.lua:%d+:%s*", "")
  local said = not ok
  for _, want in ipairs({...}) do
    said = said and (err:find(want, 1, true) ~= nil)
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

-- Walk :parent() to the top of whatever tree a widget stands in, bounded so a cycle cannot hang the tick.
local function top(w)
  for _ = 1, 200 do
    local p = w:parent()
    if p == nil then return w end
    w = p
  end
  return w
end

local INV = "window[title=Inventory]"

-- ---------------------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if s == nil then
    log("[fail] a session must be on screen -- got: the login screen")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The handle is one per (addon, session), like every other section on a Session.
  check(s:ui() == s:ui(), "s:ui() is minted once and handed back by identity", tostring(s:ui()))

  -- THE CLIENT'S. The Inventory window is put up hidden at login, so it is in the tree from the moment
  -- the HUD is, and it is what this suite names throughout.
  local inv = s:ui():find(INV)
  if inv == nil then
    log("[fail] " .. s:user() .. " must be in the world with a HUD -- got: no " .. INV)
    log("[summary] " .. pass .. " pass, " .. (fail + 1) .. " fail, 0 manual")
    return
  end
  check(inv:title() == "Inventory",
        "s:ui():find(\"" .. INV .. "\") answers that character's own window, titled "
        .. tostring(inv:title()), inv:title())

  -- ...and a widget THE SERVER placed resolves back to the very same object through its id, on that same
  -- address. A widget id counts inside one tree, which is the whole reason :node is a session's verb.
  -- The backpack GRID is the server's widget; the Inventory window around it is a frame the client builds
  -- for itself, so it carries no id at all -- which is also why :inventory() is a verb and not a selector.
  local bag = s:ui():inventory()
  check((bag ~= nil) and (bag:id() ~= nil) and (s:ui():node(bag:id()) == bag),
        "s:ui():node(w:id()) is the same widget, interned",
        (bag == nil) and "no backpack" or tostring(bag:id()))
  check(#s:ui():all("window") > 0 and (top(s:ui():root()) == s:ui():root()),
        "s:ui():all(selector) collects from that tree, and :root() is its top",
        #s:ui():all("window"))

  -- TWO TREES. A window built here goes into the addon layer, and walking :parent() up from the
  -- client's window never arrives at it -- which is what makes "your window" and "the client's window"
  -- two things rather than one namespace.
  local mine = hafen.ui():window():title("078.2"):position(72, 104)
  local clientTop, myTop = top(inv), top(mine)
  check((clientTop ~= myTop) and (clientTop == s:ui():root()) and (myTop ~= mine),
        "the client's window and the suite's own stand in two different trees",
        tostring(clientTop) .. " vs " .. tostring(myTop))
  check(s:ui():find("window[title=078.2]") == nil,
        "no selector on the session reaches a window the suite built in the layer",
        s:ui():find("window[title=078.2]"))

  -- :on(sel, "appear", fn) SCANS the live tree at registration, so a window that is already open fires
  -- at once -- inside the registration itself, which is why a plain flag reads it back on the next line.
  local seen = nil
  local sub = s:ui():on(INV, "appear", function(w) seen = w end)
  check(seen == inv, "s:ui():on(sel, \"appear\", fn) fires for a window already open, with that widget",
        tostring(seen))
  sub:remove()

  -- ...and the container reads answer on the same address.
  local items = bag and bag:items()
  check((bag ~= nil) and (type(items) == "table"),
        "s:ui():inventory() is that character's backpack, holding " .. #(items or {}) .. " item(s)",
        tostring(bag))

  -- THE TWO REFUSALS, one per direction. The moved half is gone from the global section...
  refuses("hafen.ui():find(selector) refuses, naming the session it is reached through",
          function() return hafen.ui():find(INV) end, "hafen.session():current():ui():find")
  -- ...and the half that KEPT its spelling is absent from the session's, which is the mistake a sweep
  -- makes: addressing a builder would compile, run, and be wrong.
  refuses("s:ui():window() refuses, naming the layer the builders stay in",
          function() return s:ui():window() end, "hafen.ui():window", "two trees")

  mine:destroy()

  -- Every OTHER session the client holds, read through its own address -- a tree that is not on screen.
  local others = {}
  for _, o in ipairs(hafen.session():list()) do
    if o:user() ~= s:user() then
      local w = o:ui():find(INV)
      others[#others + 1] = "get(\"" .. o:user() .. "\") [" .. tostring(o:character()) .. "] -> "
        .. ((w == nil) and "nil" or ("\"" .. tostring(w:title()) .. "\", " .. #w:items() .. " item(s)"))
    end
  end
  manualCheck("with a second session in the world, read this line: "
              .. ((#others == 0) and "<no other session>" or table.concat(others, " | ")),
              "each other account names ITS own Inventory window and its own item count, found in a tree"
              .. " that is not on screen")

  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t078-2", run)   -- the only way in: a suite does not start itself
