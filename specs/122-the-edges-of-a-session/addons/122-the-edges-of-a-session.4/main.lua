-- 122.4 — the layout is written when the screen leaves a character. Self-checking suite.
--
-- What the task changed is WHEN the client writes its window positions down. The write used to sit behind
-- a guard that asks whether this character is the one on screen, and on the one path where the screen is
-- actually leaving it that guard is false by construction -- so a switch away persisted nothing, and the
-- layout the player had just arranged survived only if they stayed put long enough for the sixty-second
-- clock. It is now written where the screen leaves the character, on the frame that moves the views.
--
-- No addon can watch that. The client's position store is not a surface this API opens, and it must not
-- become one: what it holds is the coordinate the USER last placed, and an addon's own move is deliberately
-- substituted away before the write so that uninstalling an addon never leaves the HUD displaced. So a
-- suite that moved the inventory to a known place and switched characters would have the client write the
-- pre-suite value, prove nothing, and pass. The drag has to be the player's.
--
-- What is automated is therefore the HANDLE the two manual steps are read with, whole: the drawn character
-- answers, its own tree carries exactly one window captioned Inventory -- the wrapper the client persists,
-- not the grid inside it -- and that window's position reads, writes and comes back exactly as it was. If
-- every line below passes, "it is where you dragged it" is a statement about the client's position store
-- and about nothing else; if one fails, the manual steps are measuring the wrong widget.
--
-- This suite leaves the screen exactly as it found it: the one move it makes is given straight back, and
-- the client never learns about it either way.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function xy(p)
  return (p == nil) and "nil" or ("(" .. tostring(p.x) .. ", " .. tostring(p.y) .. ")")
end

local function run()
  pass, fail, manual = 0, 0, 0

  local s = hafen.session():current()
  check(s ~= nil, "the client draws a character, and it is the one the steps below are performed on ("
        .. (s and s:user() or "none") .. ")", "the login screen")
  if s == nil then
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
    return
  end

  -- The window the client persists is the WRAPPER the client builds around the server's grid, and it is
  -- named by its caption: its class is anonymous, so @Class has nothing to say about it.
  local all = s:ui():matchAll("window[title=Inventory]")
  check(#all == 1, "that character's own tree carries exactly one window captioned Inventory",
        #all .. " of them")
  if #all ~= 1 then
    hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
    return
  end

  local w = s:ui():match("window[title=Inventory]")
  check((w ~= nil) and w:exists() and (w:role() == "window"),
        "and the lookup answers it as a window that is still in the tree",
        (w == nil) and "nil" or (tostring(w:role()) .. ", exists = " .. tostring(w:exists())))

  -- The wrapper, never the grid: the client writes the frame's coordinate, and the grid inside it sits at
  -- the frame's own origin whatever the player has done with the window.
  local grid = w:match("inventory")
  check((grid ~= nil) and (grid ~= w), "it is the frame the player drags, not the grid inside it",
        (grid == nil) and "no grid under it" or "the same widget")

  check((w:session() ~= nil) and (w:session():user() == s:user()),
        "and it stands in the drawn character's own tree, not another character's",
        w:session() and w:session():user() or "no session")

  local was = w:position()
  check((was ~= nil) and (type(was.x) == "number") and (type(was.y) == "number"),
        "its position reads as the pair the client persists " .. xy(was), xy(was))

  w:position(was.x + 7, was.y + 5)
  local moved = w:position()
  check((moved ~= nil) and (moved.x == was.x + 7) and (moved.y == was.y + 5),
        "and a move reads straight back off it " .. xy({x = was.x + 7, y = was.y + 5}), xy(moved))

  w:position(nil)
  local back = w:position()
  check((back ~= nil) and (back.x == was.x) and (back.y == was.y),
        "and dropping that move leaves it at the very pair it started at " .. xy(was), xy(back))

  refuses("one coordinate is refused, naming both", function() w:position(was.x) end,
          "takes BOTH coordinates")
  refuses("a caption asked outside a window step is refused, naming the role it needs",
          function() s:ui():match("[title=Inventory]") end, "step whose role is window")

  manualCheck("open the inventory, drag it well away from " .. xy(was)
              .. ", then switch to another character and back",
              "the inventory is exactly where you dragged it -- the write the switch makes disturbs nothing")
  manualCheck("now restart the client, within a minute of that drag and without dragging again",
              "it comes up where you dragged it: inside that minute the switch is the only thing that can"
              .. " have written it down")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

hafen.console():on("t122", run)   -- the only way in: a suite does not start itself
