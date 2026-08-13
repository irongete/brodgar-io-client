-- 065.1 — a rule's chrome is a property bag, and `pad` becomes `padding`. Self-checking suite.

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

local TITLE = "065.1 padding"
local KEY = "window[title=" .. TITLE .. "]"

-- The four distances between a window's frame and its content, in design pixels. Both are CHILDREN of the
-- window: the chrome sits at the content area's top-left inverted, so it is the one with a negative place and
-- its box is the whole window, and what it frames is the other. The four gaps between those two boxes are the
-- distances -- measured between the two, so nothing here depends on which box a window reports as its size.
local function insets(win)
  local deco, content
  for _, c in ipairs(win:children()) do
    if c:exists() then
      local p = c:position()
      if p then
        if (p.x < 0) or (p.y < 0) then deco = c else content = c end
      end
    end
  end
  if not (deco and content) then return nil end
  local dr, ds, cr, cs = deco:rootPos(), deco:size(), content:rootPos(), content:size()
  if not (dr and ds and cr and cs) then return nil end
  return { l = cr.x - dr.x, t = cr.y - dr.y,
           r = (dr.x + ds.x) - (cr.x + cs.x), b = (dr.y + ds.y) - (cr.y + cs.y) }
end

local function shows(i)
  if not i then return "<no frame>" end
  return "{" .. i.l .. "," .. i.t .. "," .. i.r .. "," .. i.b .. "}"
end

-- Did each side move by exactly the padding asked for? Every read is a design pixel converted from a device
-- one on its own, so above interface scale 1.0 a difference of two of them can land a pixel or two out; at
-- 1.0 it is exact.
local function grew(now, base, l, t, r, b)
  if (not now) or (not base) then return false end
  local tol = (hafen.ui():scale() == 1.0) and 0 or 2
  local function near(got, want)
    local d = got - want
    if d < 0 then d = -d end
    return d <= tol
  end
  return near(now.l - base.l, l) and near(now.t - base.t, t)
     and near(now.r - base.r, r) and near(now.b - base.b, b)
end

-- A rule reaches a window's chrome through Window.tick, so every geometry read waits a beat for the swap.
local function soon(fn) hafen.timer():after(0.3, fn) end

local function run()
  local s = hafen.ui():sheet()
  local r = s:rule("window.frame")
  local face = hafen.font():get("serif"):derive():size(11)

  r:padding(8, 4, 8, 8)
  local p = r:padding()
  check(p and (p.l == 8) and (p.t == 4) and (p.r == 8) and (p.b == 8),
        "padding(8, 4, 8, 8) reads back as four numbers", shows(p))
  r:padding(6)
  local q = r:padding()
  check(q and (q.l == 6) and (q.t == 6) and (q.r == 6) and (q.b == 6),
        "padding(6) is every one of the four sides", shows(q))
  r:padding(p)
  local rt = r:padding()
  check(rt and (rt.l == 8) and (rt.t == 4) and (rt.r == 8) and (rt.b == 8),
        "the read round-trips into a write", shows(rt))

  refuses("rule:pad(4) is refused naming padding", function() r:pad(4) end, "padding")
  refuses("a loaded rule saying pad is refused naming padding",
          function() s:load{ ["window.frame"] = { pad = 4 } } end, "padding")
  refuses("padding(1, 2) is refused", function() r:padding(1, 2) end, "one number for all four sides")
  refuses("padding(-1) is refused", function() r:padding(-1) end, "negative")

  s:load{}                                            -- the sheet says nothing until a step installs one
  local win = hafen.ui():window():title(TITLE):size(160, 90):position(60, 60)

  soon(function()
    local base = insets(win)
    s:load{ ["window.frame"] = { padding = {8, 4, 8, 8} } }:install()
    soon(function()
      local a = insets(win)
      check(grew(a, base, 8, 4, 8, 8), "a window's content box sits at the four distances",
            shows(base) .. " -> " .. shows(a))
      s:load{ ["window.frame"] = { padding = 6 }, ["window"] = { font = face } }:install()
      soon(function()
        local b = insets(win)
        check(grew(b, base, 6, 6, 6, 6),
              "a broader rule naming only a font leaves the padding standing",
              shows(base) .. " -> " .. shows(b))
        s:load{ ["window"] = { font = face }, [KEY] = { padding = 5 } }:install()
        soon(function()
          local st = win:style()
          check(st and (st.font == face) and st.padding and (st.padding.l == 5),
                "one window's resolved style carries the font AND the padding",
                st and (tostring(st.font) .. " / " .. shows(st.padding)) or "<nil>")
          s:drop()
          soon(function()
            check(grew(insets(win), base, 0, 0, 0, 0), "dropping the sheet restores the four distances",
                  shows(base) .. " -> " .. shows(insets(win)))
            win:destroy()
            s:load{ ["window.frame"] = { padding = {8, 24, 8, 8} } }:install()
            manualCheck("open any client window and look at the top edge",
                        "its contents further from the frame at the TOP than at the bottom, and the frame"
                        .. " art itself unchanged -- :reload puts the stock client back")
            hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
          end)
        end)
      end)
    end)
  end)
end

hafen.slash():register("t065-1", run)   -- the only way in: a suite does not start itself
