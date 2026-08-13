-- 065.5 — the close button is the theme's. Self-checking suite.

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

local UP, HOVER, PRESSED = "img/close.png", "img/closehover.png", "img/closepressed.png"
local W, H = 26, 14                   -- the art this suite ships, in design pixels: neither square nor the stock box
local TITLE = "065.5"

-- A rule reaches a window's chrome through Window.tick and is drawn a frame later, so every read waits a beat.
local function soon(fn) hafen.timer():after(0.3, fn) end

local function box(b)
  if not b then return "<nil>" end
  return "(" .. b.x .. "," .. b.y .. ") " .. b.w .. "x" .. b.h
end

-- Every read here is a device pixel converted back on its own, so above interface scale 1.0 a rounded
-- coordinate can land a pixel out; at 1.0 it is exact.
local function near(got, want)
  local tol = (hafen.ui():scale() == 1.0) and 0 or 2
  local d = (got or -9999) - want
  if d < 0 then d = -d end
  return d <= tol
end

local function run()
  local s = hafen.ui():sheet()
  local r = s:rule("window.frame")
  local up, hover, pressed = hafen.asset():get(UP), hafen.asset():get(HOVER), hafen.asset():get(PRESSED)
  local ART = { asset = UP, hover = { asset = HOVER }, pressed = { asset = PRESSED },
                at = "topleft", offset = {4, 4} }

  r:close(ART)
  local cl = r:close()
  check(cl and (cl.image == up) and (cl.hover.image == hover) and (cl.pressed.image == pressed)
        and (cl.at == "topleft") and cl.offset and (cl.offset.x == 4) and (cl.offset.y == 4),
        "a close art, its two state faces and its spot all read back as they were written",
        cl and (tostring(cl.at) .. " " .. tostring(cl.hover and cl.hover.image)))

  refuses("a close that says nothing at all is refused, naming both halves",
          function() r:close{} end, "says nothing")
  refuses("a state face with no face to vary is refused",
          function() r:close{ hover = { asset = HOVER } } end, "no face for it to vary")
  refuses("a close spelled with a field of its own is refused, naming what one carries",
          function() r:close{ asset = UP, corner = "topleft" } end, "is not a close property")

  s:load{}                                          -- the sheet says nothing until a step installs one
  local win = hafen.ui():window():title(TITLE):size(220, 100):position(80, 80)

  soon(function()
    local stock = win:chrome() and win:chrome().close
    check(stock and (stock.w > 0) and (stock.h > 0),
          "with no rule the window wears the client's own close button", box(stock))

    s:load{ ["window.frame"] = { close = ART } }:install()
    soon(function()
      local c = win:chrome() and win:chrome().close
      check(c and (c.w == W) and (c.h == H),
            "the button's box is the art's own size (" .. W .. "x" .. H .. ")", box(c))
      check(c and (c.x == 4) and (c.y == 4),
            "...and it sits at the corner plus the offset the rule named, (4,4)", box(c))

      local b = win:find("@IButton")
      check(b and b:exists() and (b:role() == "button") and (b:type() == "IButton")
            and (b:position().x == 4) and (b:size().x == W),
            "the rebuilt button is one of the client's own buttons, at the spot",
            b and (b:type() .. " " .. b:role()))
      -- It carries the client's own close action: the X runs the window's reqclose, which on an addon's own
      -- window IS the destroy below. Nothing here clicks it -- the manual line does.
      local sub = b:on("Pressed", function() end)
      check(sub ~= nil, "...and it is wired into the client's own press path", sub)
      sub:off()

      win:size(300, 160)
      soon(function()
        local moved = win:chrome() and win:chrome().close
        check(moved and (moved.x == 4) and (moved.y == 4) and (moved.w == W),
              "resizing the window leaves the button at its corner, at its own size", box(moved))

        s:load{}:install()
        soon(function()
          local back = win:chrome() and win:chrome().close
          -- The client's own place is its own RULE -- flush to the top right -- so on a window that has since
          -- been widened it is a different number than it was, and the rule is what the restore has to land on.
          local out = win:size()
          check(back and stock and (back.w == stock.w) and (back.h == stock.h) and (back.y == stock.y)
                and near(back.x + back.w, out.x),
                "dropping the sheet puts the client's own button back, at its size and flush to its own corner",
                box(stock) .. " -> " .. box(back) .. " in a " .. tostring(out and out.x) .. " wide window")
          win:destroy()

          s:load{ ["window.frame"] = { close = ART } }:install()
          manualCheck("open the Inventory, hover its X, hold the button down, then release it over the X",
                      "a dark red bar with a pale cross tight under the TOP-LEFT corner of the frame rather"
                      .. " than at the top right; brighter red under the pointer and near black while held;"
                      .. " and the window CLOSING when you release -- :reload puts the stock client back")
          manualCheck("open the Inventory again and drag its window by the caption, then open the Map and drag"
                      .. " the Map's bottom-right corner out",
                      "the X staying in the theme's top-left corner on both, before and after the resize, with"
                      .. " nothing drawn at the window's top right at all")
          hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
        end)
      end)
    end)
  end)
end

hafen.slash():register("t065-5", run)   -- the only way in: a suite does not start itself
