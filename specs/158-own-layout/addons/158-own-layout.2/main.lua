-- 158.2 — a rule lands on a surface of yours when it is armed, named or resized. Self-checking suite.

local ID = "158-own-layout.2"

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

local function at(widget)
  local position = widget:position()
  return position.x .. "," .. position.y
end

local function box(widget)
  local size = widget:size()
  return size.w .. "x" .. size.h
end

local function name(word)
  return "[name=" .. ID .. "/" .. word .. "]"
end

local built = {}
local function keep(surface)
  built[#built + 1] = surface
  return surface
end

local function finish(sheet)
  sheet:release()
  for _, surface in ipairs(built) do
    surface:destroy()
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  -- Every rule is installed BEFORE anything is built, and no sheet:install() follows: what lands, lands
  -- on the arming tick (or on the name, or on the resize) and on nothing else.
  local sheet = hafen.ui():sheet()
  sheet:load{
    [name("a")]    = { anchor = { to = "screen", at = "topleft", offset = {12, 34} } },
    [name("b")]    = { position = {70, 80} },
    [name("b2")]   = { position = {60, 90} },
    [name("m")]    = { position = {50, 120} },
    [name("outer") .. " " .. name("inner")] = { position = {5, 6} },
    [name("late")] = { position = {70, 80} },
  }:install()

  -- armed: a bare widget under a screen anchor, a window, a control, a mirror, a chain
  local anchored = keep(hafen.ui():widget():name("a"):size(50, 40))
  local window = keep(hafen.ui():window():name("b"))
  local button = keep(hafen.ui():button():name("b2"):text("Go"))
  local mirror = keep(hafen.ui():mirror():name("m"))
  local outer = keep(hafen.ui():widget():name("outer"))
  local inner = hafen.ui():widget():name("inner"):parent(outer)
  -- :name lands its rule at once, pending or not, and :size is a verb that folds it: every surface but
  -- inner stands on its rule before the tick. inner's chain step, :parent(outer), was configured AFTER
  -- its name, so it stands at the default place until the tick -- which is what says the tick landed it.
  local beforeArming = at(window) .. " " .. at(button) .. " " .. at(mirror) .. " " .. at(inner)

  -- resizing: f hangs off t's bottom-right corner, its own corner on it
  local target = keep(hafen.ui():widget():name("t"):position(300, 300):size(100, 100))
  sheet:rule(name("f")):anchor{ to = target, at = "bottomright" }
  local follower = keep(hafen.ui():widget():name("f"):size(20, 20))

  -- a name written late: built and armed first, named on the next step
  local late = keep(hafen.ui():widget())
  local outer2 = keep(hafen.ui():widget():name("outer"))
  local inner2 = hafen.ui():widget():parent(outer2)

  -- a Draw handler holds the character's tree: :name on a layer widget from there must refuse
  local layerWidget = keep(hafen.ui():widget())
  local session = hafen.session():current()
  local hud = session and session:ui():match("@GameUI")
  local drawOutcome = nil
  if hud then
    local probe = keep(hafen.ui():widget():parent(hud):position(0, 0):size(8, 8))
    probe:on("Draw", function()
      if drawOutcome ~= nil then return end
      local ok, err = pcall(function() layerWidget:name("x") end)
      drawOutcome = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
    end)
  end

  hafen.timer():after(0.1, function()
    check(beforeArming == "70,80 60,90 50,120 100,100",
      "before the tick :name landed the rule on the window, button and mirror; inner, parented after its name, waits", beforeArming)
    check(at(anchored) == "12,34", "a screen anchor {12, 34} lands on a widget one tick after it was built", at(anchored))
    check(at(window) == "70,80", "a position rule lands on a window one tick after it was built", at(window))
    check(at(button) == "60,90", "a position rule lands on a button one tick after it was built", at(button))
    check(at(mirror) == "50,120", "a position rule lands on a mirror one tick after it was built", at(mirror))
    check(at(inner) == "5,6", "a chain rule [outer] [inner] lands on the inner widget at arming", at(inner))
    check(at(follower) == "380,380",
      "f anchored bottomright to t (300,300 100x100) sized 20x20 reads {380, 380} at arming", at(follower))

    follower:size(40, 40)
    hafen.timer():after(0.1, function()
      check(at(follower) == "360,360", "f:size(40, 40): one tick later f's corner is still on t's, {360, 360}", at(follower))
      hafen.ui():label():parent(follower):position(0, 0):text("row")
      follower:pack()
      hafen.timer():after(0.1, function()
        local packed = follower:size()
        local want = (400 - packed.w) .. "," .. (400 - packed.h)
        check(packed.w ~= 40 and at(follower) == want,
          "f:pack(): one tick later f's corner is still on t's for the packed box " .. box(follower), at(follower))

        -- the late name lands at once, its subtree included
        local wasLate = at(late)
        late:name("late")
        check(wasLate == "100,100" and at(late) == "70,80",
          ":name(\"late\") on an armed widget reads the rule's {70, 80} right after the write",
          wasLate .. " | " .. at(late))
        local wasInner = at(inner2)
        inner2:name("inner")
        check(wasInner == "100,100" and at(inner2) == "5,6",
          "inner named after outer armed reads the chain rule's {5, 6} right after the write",
          wasInner .. " | " .. at(inner2))
        refuses(":name a second time still refuses naming the first name",
          function() late:name("again") end, "already called \"" .. ID .. "/late\"")

        -- the Draw handler's refusal, recorded on the first frame the probe drew
        if hud then
          check(drawOutcome ~= nil and drawOutcome:find("one tree monitor at a time", 1, true) ~= nil,
            ":name(\"x\") on a layer widget from a HUD surface's Draw handler refuses naming one tree monitor at a time",
            drawOutcome)
          check(layerWidget:name() == nil, "the refused name was not written", layerWidget:name())
        else
          check(false, ":name(\"x\") on a layer widget from a HUD surface's Draw handler refuses naming one tree monitor at a time",
            "no character in world -- run :t158 logged in")
        end
        finish(sheet)
      end)
    end)
  end)
end

-- The only way in: a suite does not start itself. A console line runs under the character's tree monitor
-- and the suite writes layer widgets, so the run is deferred to the step, which holds none.
hafen.console():on("t158", function() hafen.timer():after(0, run) end)
