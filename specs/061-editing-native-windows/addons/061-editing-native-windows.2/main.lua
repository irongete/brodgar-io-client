-- 061.2 — Changed on a native checkbox, and widget:value() reading one. Self-checking suite.
--
-- Two native targets, neither of which needs a game window open:
--   * the "Vertical sync" checkbox on Options -> Video settings, an anonymous CheckBox subclass that
--     overrides set() — exactly the case a base-class hook would miss;
--   * the drop arrow of the suite's OWN dropdown, a real ICheckBox the client's SDropBox builds, so it
--     reads BORROWED inside a control this addon owns.
--
-- Three clicks are the whole manual part.
--
--   :t061-2

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
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The Options window's "Vertical sync" box, re-found each time: ticking it changes a graphics preference,
-- and the Video panel rebuilds its whole column of checkboxes the next frame after one moves.
local function vsyncBox(win)
  for _, c in ipairs(win:all("@CheckBox")) do
    if c:text() == "Vertical sync" then
      return c
    end
  end
  return nil
end

-- Phase 3: the same box, cancelled once and resent once — the same option off and then on, one click each.
local function nativeCheckbox(win, box)
  local v0 = box:value()
  local scored, evVal = false, nil
  local sub
  sub = box:on("Changed", function(ev)
    ev:preventDefault()
    if scored then
      return
    end
    scored = true
    evVal = ev:value()
    -- The client's own click would run after this fire returns, so what the cancel did is read a beat later.
    hafen.timer():after(0.4, function()
      check(evVal == (not v0), "the cancelled tick carried the value the box was ABOUT to take", evVal)
      check(box:value() == v0, "...and left widget:value() reading exactly what it read before", box:value())
      sub:off()
      local sub2, done = nil, false
      sub2 = box:on("Changed", function(ev2)
        if done then                 -- the run ends on the FIRST resent tick: the next one is the
          return                     -- maintainer's own, putting vsync back, and none of our business
        end
        done = true
        sub2:off()
        ev2:resend()
        check(box:value() == (not v0), "ev:resend() ran the client's own click: the box holds the other boolean",
              box:value())
        hafen.timer():after(0.6, function()
          local again = vsyncBox(win)
          check((again ~= nil) and (again:value() == (not v0)),
                "the client really took it: the rebuilt checkbox reads the new value",
                again and again:value())
          summary()
        end)
      end)
      manualCheck("tick \"Vertical sync\" again",
                  "it changes this time; tick it once more afterwards to leave vsync as you found it")
    end)
  end)
  manualCheck("tick \"Vertical sync\" on Options -> Video settings", "the box does NOT change")
end

-- Phase 2: a control the addon owns, with one of the client's own controls living inside it.
local function ownDropdown(win, box)
  local dwin = hafen.ui():window():title("061.2"):size(200, 60):position(300, 300)
  local dd = hafen.ui():dropdown():parent(dwin):position(12, 12):size(150):rows{"A", "B"}
  local arrow = dd:find("@ICheckBox")
  check((arrow ~= nil) and dd:info().owned and not arrow:info().owned,
        "the dropdown reads owned while the drop arrow the client built inside it reads borrowed",
        arrow and arrow:info().owned)
  if not arrow then
    summary()
    return
  end

  local perClick, arrowVal, ddFires, scored = 0, nil, 0, false
  dd:on("Changed", function()
    ddFires = ddFires + 1
  end)
  arrow:on("Changed", function(ev)
    perClick = perClick + 1
    arrowVal = ev:value()
    ev:preventDefault()
    if scored then
      return
    end
    scored = true
    -- ONE CLICK, one dispatch. A double dispatch lands in the same frame as the click that caused it, and
    -- no hand clicks twice in 100 ms — so this scores that click and never a second, impatient one (a
    -- cancelled click looks exactly like nothing happening, so it does get clicked again).
    hafen.timer():after(0.1, function()
      check((perClick == 1) and (arrowVal == true),
            "one click dispatched Changed once on the borrowed arrow, carrying the value it was about to take",
            perClick .. " fire(s), value " .. tostring(arrowVal))
      check(ddFires == 0,
            "the addon that OWNS the dropdown never received the arrow's key as well", ddFires)
      dwin:destroy()
      nativeCheckbox(win, box)
    end)
  end)
  manualCheck("click the dropdown on the 061.2 window (anywhere on it, arrow or box)",
              "no list drops down: the handler cancels it")
end

local function run()
  pass, fail, manual = 0, 0, 0

  local win = hafen.ui():find("window[title=Options]")
  local box = win and vsyncBox(win)
  check(box ~= nil, "Options is open and Video settings has been visited: its \"Vertical sync\" box is here")
  if not box then
    summary()
    return
  end
  check(box:info().owned == false, "a checkbox on that panel is BORROWED", box:info().owned)
  check(type(box:value()) == "boolean", "widget:value() reads a native checkbox's boolean", box:value())

  local label = win:all("@Label")[1]
  check((label ~= nil) and (label:value() == nil),
        "widget:value() on a native Label reads nil rather than raising", label and label:value())
  refuses("Changed on a native Label is refused, naming the keys that Label does have",
          function() label:on("Changed", function() end) end,
          "MouseDown, MouseUp, MouseMove, Wheel, Destroy")

  ownDropdown(win, box)
end

hafen.slash():register("t061-2", run)   -- the only way in: a suite does not start itself
