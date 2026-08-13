-- 065.4 — a theme says where the caption goes, what it sits on, and where the sizer is. Self-checking suite.

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

local TITLE = "065.4"
local LONG  = "065.4 a window caption very much longer than the one before it"
local FRAME = "gfx/hud/wnd"           -- the client's own window frame...
local PLATE = "gfx/hud/bosq"          -- ...one of its other frames, plainly not a title bar's
local FOOT  = "gfx/hud/wnd/lg/lb"     -- ...the piece it drops at the foot of its left run...
local GRIP  = "gfx/hud/wnd/sizer"     -- ...and its corner grip

-- The client's own caption origin, in design pixels: Window.cpo. Every read here is a device pixel converted
-- back on its own, so above interface scale 1.0 a rounded coordinate can land a pixel out; at 1.0 it is exact.
local STOCK_X, STOCK_Y = 36, 16
local function near(got, want)
  local tol = (hafen.ui():scale() == 1.0) and 0 or 2
  local d = (got or -999) - want
  if d < 0 then d = -d end
  return d <= tol
end

local function xy(c) if not c then return "<nil>" end return "(" .. c.x .. "," .. c.y .. ")" end

-- A rule reaches a window's chrome through Window.tick and is PAINTED a frame later, so every read waits a beat.
local function soon(fn) hafen.timer():after(0.3, fn) end

local function run()
  local s = hafen.ui():sheet()
  local r = s:rule("window.frame")

  r:caption{ at = "topleft", offset = {12, 4} }
  local cp = r:caption()
  check(cp and (cp.at == "topleft") and cp.offset and (cp.offset.x == 12) and (cp.offset.y == 4),
        "caption{at, offset} reads back as the corner and the offset",
        cp and (tostring(cp.at) .. " " .. xy(cp.offset)))

  r:sizer{ res = GRIP, at = "topright" }
  local sr = r:sizer()
  check(sr and (sr.res == GRIP) and (sr.at == "topright"),
        "sizer{art, at} reads back as the art it named, at the corner it named",
        sr and (tostring(sr.res) .. " " .. tostring(sr.at)))

  r:border{ box = FRAME, mode = "tile",
            parts = { { res = FOOT, at = "bottomleft" }, { res = GRIP, at = "topright" } } }
  local ps = r:border() and r:border().parts
  check(ps and ps[1] and ps[2] and (ps[1].res == FOOT) and (ps[1].at == "bottomleft")
        and (ps[2].res == GRIP) and (ps[2].at == "topright"),
        "two parts read back at two corners, each its own art",
        ps and ps[1] and ps[2] and (ps[1].at .. "/" .. ps[1].res .. " " .. ps[2].at .. "/" .. ps[2].res))

  refuses("a spot naming no corner of the nine is refused naming all nine",
          function() r:caption{ at = "middle" } end, '"bottomright"')
  refuses("a spot spelled with a field of its own is refused naming the two a spot carries",
          function() r:caption{ corner = "topleft" } end, "is not a spot property")
  refuses("a part with no art is refused",
          function() r:border{ box = FRAME, parts = {{ at = "topleft" }} } end, "says nothing")
  refuses("a part that is pinned nowhere is refused",
          function() r:border{ box = FRAME, parts = {{ res = FOOT }} } end, "PINNED")

  s:load{}                                            -- the sheet says nothing until a step installs one
  local win = hafen.ui():window():title(TITLE):size(200, 90):position(60, 60)

  soon(function()
    local stock = win:chrome() and win:chrome().caption
    check(stock and near(stock.x, STOCK_X) and near(stock.y, STOCK_Y),
          "with no rule the caption is drawn where the client itself draws it, ("
          .. STOCK_X .. "," .. STOCK_Y .. ")", xy(stock))

    s:load{ ["window.frame"] = { caption = { at = "topleft", offset = {12, 4} } } }:install()
    soon(function()
      local c = win:chrome() and win:chrome().caption
      check(c and (c.x == 12) and (c.y == 4),
            "the caption is drawn at the corner plus the offset the rule named", xy(c))

      s:load{}:install()
      soon(function()
        local back = win:chrome() and win:chrome().caption
        check(back and stock and (back.x == stock.x) and (back.y == stock.y),
              "removing the property puts it back at the client's own origin, to the pixel",
              xy(stock) .. " -> " .. xy(back))

        s:load{ ["window.title"] = { bg = { color = {150, 40, 40, 230} },
                                     border = { box = PLATE } } }:install()
        soon(function()
          local pl = win:chrome() and win:chrome().plate
          -- ...and it covers the title bar from the window's OWN corner: the plate stands in for the frame's
          -- caption art, so a box starting anywhere else leaves a bare notch at that end of the run.
          check(pl and (pl.styled == true) and (pl.x == 0) and (pl.y == 0),
                "the window.title key resolved the plate, painted it in place of the frame's own caption art",
                pl and (tostring(pl.styled) .. " at " .. xy(pl)))
          local was = pl and pl.w
          win:title(LONG)
          soon(function()
            local now = win:chrome() and win:chrome().plate
            check(was and now and (now.w > was),
                  "a much longer caption widens the plate: the client still owns its geometry",
                  tostring(was) .. " -> " .. tostring(now and now.w))
            win:destroy()

            s:load{ ["window.frame"] = { caption = { at = "topleft", offset = {6, 3} },
                                         sizer = { res = GRIP, at = "topright" } },
                    ["window.title"] = { bg = { color = {150, 40, 40, 230} },
                                         border = { box = PLATE } } }:install()
            manualCheck("open the Inventory and read its title bar end to end",
                        "its caption tight under the TOP-LEFT corner of the frame rather than floating in"
                        .. " from it, sitting on a dark red plate framed in the client's OTHER box art; the"
                        .. " plate running from the window's own left corner to where the golden top edge"
                        .. " takes over, with NO bare notch at either end of it; and the window's contents"
                        .. " exactly where they were -- :reload puts the stock client back")
            manualCheck("open the Map and look at its two right-hand corners",
                        "the corner grip at the TOP right instead of the bottom right, in the client's own"
                        .. " grip art, and nothing drawn at the bottom right at all")
            hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
          end)
        end)
      end)
    end)
  end)
end

hafen.slash():register("t065-4", run)   -- the only way in: a suite does not start itself
