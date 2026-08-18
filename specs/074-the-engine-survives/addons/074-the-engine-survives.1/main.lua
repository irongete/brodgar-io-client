-- 074.1 — the addon layer is a layer, not a tenant. Self-checking suite.
--
-- A surface you build is no longer a guest in the session's widget tree: it is put in the addon layer, one
-- tree for the client's life, drawn above whichever session holds the screen. Everything the builders
-- answer is unchanged -- that is half the claim, and the first six lines below. The other half is that the
-- two trees are two: what hafen.ui():find searches is still the client's own windows, and no walk upward
-- from one of those ever arrives at a window of ours.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local W, H = 220, 110                    -- the probe window's CONTENT box, in design pixels
local X, Y = 330, 250                    -- over the game world, which the third manual line needs

local probe, grip = nil, nil             -- the last run's surfaces, so a re-run leaves one set on screen

-- Walk upward from w, gathering what :parent() reaches. The last one is that tree's root, and the whole
-- chain is what "the client's windows cannot reach ours" is an assertion about.
local function ancestors(w)
  local up, at = {}, w:parent()
  while at ~= nil do
    up[#up + 1] = at
    at = at:parent()
  end
  return up
end

local function run()
  pass, fail, manual = 0, 0, 0
  if probe ~= nil then probe:destroy() end
  if grip ~= nil then grip:destroy() end

  -- THE BUILDER, and every read that answers on what it made. All of it now lands in the layer.
  local win = hafen.ui():window():title("074.1 layer"):size(W, H):position(X, Y)
  probe = win
  win:on("Draw", function(ev)
    ev:g():color(40, 60, 90)
    ev:g():frect(0, 0, ev:w(), ev:h())
    ev:g():color(230, 230, 210)
    ev:g():text("074.1 -- click me, drag me, log out", 8, 20)
  end)
  check((win:parent() ~= nil) and (win:visible() == true),
        "a window of ours is placed in a tree, and visible",
        tostring(win:parent()) .. "/" .. tostring(win:visible()))
  eq("...and round-trips its title", win:title(), "074.1 layer")
  local p = win:position()
  check((p ~= nil) and (p.x == X) and (p.y == Y),
        ("...and its position (%d,%d)"):format(X, Y), (p == nil) and "nil" or (p.x .. "," .. p.y))
  local s = win:size()
  check((s ~= nil) and (s.x >= W) and (s.y >= H),
        ("...and its outer box, the chrome fitted round the content it was given (%dx%d)"):format(W, H),
        (s == nil) and "nil" or (s.x .. "x" .. s.y))
  win:visible(false)
  local off = win:visible()
  win:visible(true)
  check((off == false) and (win:visible() == true),
        "...and its visible flag, off and on again", tostring(off) .. "/" .. tostring(win:visible()))

  -- THE TREE, one level down: a child re-homed under it is counted by :children().
  local before = #win:children()
  hafen.ui():label():parent(win):position(8, 80):text("child")
  eq("a child parented into it is counted by :children()", #win:children(), before + 1)

  -- THE SEARCH is the CLIENT's tree, and it did not move: host() still means the widgets an addon looks
  -- for, which belong to the session on screen.
  local hud = hafen.ui():find("@GameUI")
  check(hud ~= nil, "hafen.ui():find still locates one of the client's own windows", hud)

  -- ...AND THE TWO TREES ARE TWO. Walking up from that client widget reaches its own root and never ours,
  -- which is the whole claim of this task stated as an assertion.
  local reachedOurs, sameRoot = false, false
  if hud ~= nil then
    local up = ancestors(hud)
    for i = 1, #up do
      if up[i] == win then reachedOurs = true end
    end
    local theirs = up[#up]
    local ours = ancestors(win)
    ours = ours[#ours]
    sameRoot = (theirs ~= nil) and (theirs == ours)
  end
  check((hud ~= nil) and (not reachedOurs) and (not sameRoot),
        "...and no parent walk from it reaches our window, nor its root",
        tostring(reachedOurs) .. "/" .. tostring(sameRoot))

  refuses("widget:position(nil, 10) is refused, saying what it wanted",
          function() win:position(nil, 10) end, "number expected")

  -- The resize half of the first manual line. A window of the client's own is resized by a grip its
  -- decoration draws and a bare one has none, so the corner is one of ours, armed by the verb that arms it.
  grip = hafen.ui():widget():size(16, 16):position(X + W - 8, Y + H + 12)
  grip:on("Draw", function(ev)
    ev:g():color(200, 160, 90)
    ev:g():frect(0, 0, ev:w(), ev:h())
  end)
  win:resizable(grip)
  win:on("Close", function() if grip ~= nil then grip:destroy() end end)

  manualCheck("drag the '074.1 layer' window by its CAPTION, then drag the orange square under it",
              "the window follows the mouse, and the square resizes it, exactly as any client window does")
  manualCheck("log out to the login screen (:session drop all), and look at the screen",
              "the window is STILL THERE, drawn over the login screen")
  manualCheck("click the blue box where it overlaps the ground",
              "the window takes the click and your character does NOT walk there")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t074-1", run)   -- the only way in: a suite does not start itself
