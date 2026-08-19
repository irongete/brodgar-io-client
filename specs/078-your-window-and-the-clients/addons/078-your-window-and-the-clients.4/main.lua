-- 078.4 — host() takes the session, and the sequence closes. Self-checking suite.
--
-- The closing proof. One window of YOURS, built into the addon layer and drawn above whichever character
-- is on screen -- and in it one row per live session, each read through that session's own address.
-- Your window and the client's window are not the same thing, and this is the picture of it: N
-- inventories that are not yours, in a window that is.

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
local MINE = "078.4-sessions"

-- THE WALK, and the whole claim: one row per session the client holds, each read through that session's
-- own address rather than through the screen -- so a character nobody is looking at answers exactly as
-- the one on screen does.
local function rows()
  local cur = hafen.session():current()
  local out = {}
  for _, s in ipairs(hafen.session():list()) do
    local w = s:ui():find(INV)
    out[#out + 1] = {
      user = s:user(),
      chr = s:character(),
      title = w and w:title() or nil,
      items = w and #w:items() or nil,
      current = (cur ~= nil) and (s:user() == cur:user()),
    }
  end
  return out
end

local function rowText(r)
  return (r.current and "> " or "  ") .. r.user .. " [" .. tostring(r.chr) .. "] "
    .. ((r.items == nil) and ("no " .. INV) or (r.items .. " item(s)"))
end

local function rowsText(rs)
  local out = {}
  for _, r in ipairs(rs) do out[#out + 1] = rowText(r) end
  return (#out == 0) and "<no session>" or table.concat(out, " | ")
end

-- The window is kept between runs, so :t078-4 REPLACES it rather than stacking a second one.
local window = nil

local function build()
  if window ~= nil then
    pcall(function() window:destroy() end)
    window = nil
  end
  local w = hafen.ui():window():title(MINE):position(120, 140):size(300, 90)
  -- The rows are re-read while the window stands, so tabbing between characters cannot change them: the
  -- address does not follow the screen. Refreshed a few times a second rather than every frame -- each
  -- walk is a whole tree per session, and this window outlives the command that built it.
  local frame, cached = 0, rows()
  w:on("Draw", function(ev)
    frame = frame + 1
    if (frame % 15) == 0 then cached = rows() end
    local g = ev:g()
    g:color(210, 215, 225)
    local y = 6
    for _, r in ipairs(cached) do
      g:text(rowText(r), 8, y)
      y = y + 15
    end
  end)
  w:on("Close", function()
    pcall(function() w:destroy() end)
    window = nil
  end)
  window = w
  return w
end

-- ---------------------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0
  local cur = hafen.session():current()
  if cur == nil then
    log("[fail] a session must be on screen -- got: the login screen")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- YOURS. The window is built into the addon layer, which is not any session's tree -- so nothing the
  -- suite builds is reachable from the address the rows are read through.
  local mine = build()
  check((top(mine) ~= cur:ui():root()) and (cur:ui():find("window[title=" .. MINE .. "]") == nil),
        "the suite's own window stands in the layer, and no session's selector reaches it",
        tostring(top(mine)))

  -- THE CLIENT'S. One row per session the client holds, each naming its own account, in the order
  -- hafen.session():list() gives.
  local list, rs = hafen.session():list(), rows()
  local named, seen = (#rs == #list) and (#rs > 0), {}
  for i, r in ipairs(rs) do
    named = named and (list[i] ~= nil) and (r.user == list[i]:user()) and (seen[r.user] == nil)
    seen[r.user] = true
  end
  check(#rs == #list and #rs > 0, "the window carries a row for every live session (" .. #rs .. ")", #rs)
  check(named, "each row names its own account, once", rowsText(rs))

  -- Every row is a real Inventory window, found in the tree of the character it belongs to.
  local allInv = #rs > 0
  for _, r in ipairs(rs) do allInv = allInv and (r.title == "Inventory") and (r.items ~= nil) end
  check(allInv, "every row found that character's own Inventory window through its address", rowsText(rs))

  -- THE ROW THAT IS NOT ON SCREEN. This is the sequence in one assertion: a window the game put up for a
  -- character nobody is looking at is open, findable and readable.
  local other = nil
  for _, r in ipairs(rs) do
    if not r.current then other = r end
  end
  check((other ~= nil) and (other.title == "Inventory") and (other.items ~= nil),
        "the row for a session that is NOT on screen carries its own Inventory and its items",
        (other == nil) and "only one session is up -- :session add a second and open its inventory"
          or rowText(other))

  -- THE TWO HALVES REFUSE EACH OTHER, which is what makes the picture above two things rather than one
  -- namespace: the lookup half is the session's, the builder half is the layer's.
  refuses("hafen.ui():find(selector) refuses, naming the session it is reached through",
          function() return hafen.ui():find(INV) end, "hafen.session():current():ui():find")
  refuses("s:ui():window() refuses, naming the layer the builders stay in",
          function() return cur:ui():window() end, "hafen.ui():window", "two trees")

  -- The two guarantees 070 and 074 left, re-read here because a change that touched this much would
  -- break them loudly or not at all.
  local p = hafen.client():profiling():session()
  check(p.engineReloads == 0, "engineReloads is still 0 (nothing rebuilt the layer unasked)",
        p.engineReloads)
  check(p.placedRebuiltOffTick == 0,
        "placedRebuiltOffTick is still 0 (no click resolved before the frame placed the sessions)",
        p.placedRebuiltOffTick)

  manualCheck("with two sessions in the world and an inventory open on each, TAB BETWEEN THEM without"
              .. " re-running -- the window reads: " .. rowsText(rs),
              "the window stays where it is, keeps both rows and neither row empties -- it is your"
              .. " window, in the layer, reading two characters at once")
  manualCheck("paste both closing numbers", "the survivor count and the guardrail this task recomputed")

  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t078-4", run)   -- the only way in: a suite does not start itself
