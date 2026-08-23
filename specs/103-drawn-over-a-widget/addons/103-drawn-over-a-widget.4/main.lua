-- 103.4 -- the label that costs one raster. Self-checking suite.
--
-- WHAT THIS SHIPS. The second kind of widget:overlay(): ov:text(s), a label the ENGINE draws, and the
-- five properties that dress it -- :anchor(ax, ay), :offset(x, y), :color(c), :font(h), :background(c),
-- each with a bare read of the same name. With it comes the one-kind rule: an overlay says exactly one
-- thing, so a second, different kind raises naming the first.
--
-- HOW IT IS PROVED. The claim no verb can state is the COST: a label never enters Lua at the draw and
-- never rasterises again, however many frames it is up for. The only thing that can say so is the text
-- cache's own pair of counters, so the suite reads hafen.client():profiling():textcache() a second
-- apart over a live label and asserts that `misses` did not move while `hits` did -- one rasterisation
-- for its lifetime, a lookup a frame. That measurement owns the first two seconds of the run alone:
-- every other string this suite draws is attached AFTER it, or it would be the misses being counted.
-- Everything else is read back straight, and every refusal is a check.

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

local function finish()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function xy(t)
  return (t == nil) and "nil" or ("(" .. tostring(t.x) .. ", " .. tostring(t.y) .. ")")
end

local own, label, face, inv
local before                                    -- the cache counters once the label has been drawn once

-- The last hop: a second of frames later, the cache must say the label was looked up and never rebuilt.
local function measured()
  local after = hafen.client():profiling():textcache()
  check((before ~= nil) and (after.misses == before.misses) and (after.hits > before.hits),
        "a live label is looked up every frame and rasterised none of them",
        "misses " .. tostring(before and before.misses) .. " -> " .. tostring(after.misses)
          .. ", hits " .. tostring(before and before.hits) .. " -> " .. tostring(after.hits))
  own:destroy()                                 -- the measuring rig goes; the icons below are the manual

  -----------------------------------------------------------------------------------------------
  -- What the [manual] line is read against: every item icon in the backpack wears its own quality,
  -- centred on a black strip along the bottom edge of its slot, in a face of the suite's own.
  -----------------------------------------------------------------------------------------------
  local icons = inv:matchAll("@WItem")
  for _, icon in ipairs(icons) do
    local it = icon:item()
    local q = it and it:quality()
    icon:overlay():add("q"):text(q and tostring(math.floor(q + 0.5)) or "-")
        :anchor(0.5, 1.0):color{255, 230, 140}:background{0, 0, 0, 200}:font(face)
  end

  if #icons == 0 then
    check(false, "the backpack holds at least one item to decorate",
          "no @WItem under the grid -- put something in your backpack and run :t103 again")
  end
  manualCheck("open your backpack and read the item icons, then leave them up for a few seconds",
              "each slot carrying a number on a black strip along its bottom edge, centred, in a"
                .. " serifed face plainly larger than the client's own text, and steady rather than"
                .. " flickering or shifting (they stay until :reload)")
  finish()
end

-- The first hop: one second in, the label has been drawn, so its one rasterisation is behind us.
local function armed()
  before = hafen.client():profiling():textcache()
  hafen.timer():after(1, function()
    local ok, err = pcall(measured)
    if not ok then
      check(false, "the measured half reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
      finish()
    end
  end)
end

local function body()
  local s = hafen.session():current()
  if s == nil then
    check(false, "a character is logged in", "no current session -- run this in the world")
    return finish()
  end

  inv = s:ui():inventory()
  if inv == nil then
    check(false, "the backpack grid is in the tree", "s:ui():inventory() is nil -- log in and retry")
    return finish()
  end

  -----------------------------------------------------------------------------------------------
  -- A bare overlay: no kind, no label, and the dressing at its own defaults.
  -----------------------------------------------------------------------------------------------
  own = hafen.ui():widget():size(90, 30):position(160, 160)
  label = own:overlay():add("cost")
  local a0, o0 = label:anchor(), label:offset()

  check((label:kind() == nil) and (label:text() == nil) and (label:color() == nil)
        and (label:font() == nil) and (label:background() == nil)
        and (a0.x == 0) and (a0.y == 0) and (o0.x == 0) and (o0.y == 0),
        "a bare overlay says no kind and no label, and the dressing reads its own defaults",
        "kind=" .. tostring(label:kind()) .. " text=" .. tostring(label:text())
          .. " anchor=" .. xy(a0) .. " offset=" .. xy(o0)
          .. " color=" .. tostring(label:color()) .. " font=" .. tostring(label:font())
          .. " background=" .. tostring(label:background()))

  -----------------------------------------------------------------------------------------------
  -- ...and every setter reads back what it wrote, on the very record the cache check then measures.
  -----------------------------------------------------------------------------------------------
  face = hafen.font():get("serif"):derive():size(14)
  label:text("qq 100"):anchor(0.5, 1.0):offset(0, -2):color{255, 230, 140}
       :background{0, 0, 0, 200}:font(face)

  local a, o, c, bg = label:anchor(), label:offset(), label:color(), label:background()
  local info = label:info()
  check((label:text() == "qq 100") and (a.x == 0.5) and (a.y == 1.0) and (o.x == 0) and (o.y == -2)
        and (c.r == 255) and (c.g == 230) and (c.b == 140) and (bg.a == 200) and (label:font() == face)
        and (label:kind() == "text") and (info.kind == "text") and (info.text == "qq 100"),
        "every setter reads back what it wrote, and the overlay says it is a label",
        "text=" .. tostring(label:text()) .. " anchor=" .. xy(a) .. " offset=" .. xy(o)
          .. " color=" .. tostring(c and c.r) .. "," .. tostring(c and c.g) .. "," .. tostring(c and c.b)
          .. " bg.a=" .. tostring(bg and bg.a) .. " font=" .. tostring(label:font() == face)
          .. " kind=" .. tostring(label:kind()) .. "/" .. tostring(info.kind))

  -----------------------------------------------------------------------------------------------
  -- An overlay says ONE thing, both ways round.
  -----------------------------------------------------------------------------------------------
  refuses("a painter is refused on a label, naming what it already draws",
          function() return label:draw(function(g, w, h) end) end, "already draws 'text'")

  local painter = own:overlay():add("painter"):draw(function(g, w, h) end)
  refuses("a label is refused on a painter, naming what it already draws",
          function() return painter:text("no") end, "already draws 'draw'")
  own:overlay():remove("painter")

  -----------------------------------------------------------------------------------------------
  -- The refusals of the dressing itself.
  -----------------------------------------------------------------------------------------------
  refuses("one number is not an anchor, and the refusal names both",
          function() return label:anchor(0.5) end, "takes BOTH fractions")

  refuses("a font that is not a handle is refused, naming where one comes from",
          function() return label:font(11) end, "expects a font handle")

  refuses("a label that is not a string is refused",
          function() return label:text(7) end, "must be a string")

  refuses("a colour that is not a table is refused, saying what one is",
          function() return label:background("black") end, "a colour is a table")

  -----------------------------------------------------------------------------------------------
  -- The boundary: the screen is not a box with corners, so the HUD painter has no label kind.
  -----------------------------------------------------------------------------------------------
  refuses("the HUD overlay has no label kind, and says what it does answer",
          function() return hafen.ui():overlay():add("t103-probe"):text("no") end, "has no verb 'text'")
  hafen.ui():overlay():remove("t103-probe")

  -- ...and now the frames run, with the label above the only string this addon draws.
  hafen.timer():after(1, function()
    local ok, err = pcall(armed)
    if not ok then
      check(false, "the armed half reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
      finish()
    end
  end)
end

-- The synchronous run is one pcall, so a read that throws becomes one [fail] line and a summary
-- rather than a bare stack trace with no verdict under it.
local function run()
  local ok, err = pcall(body)
  if not ok then
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    finish()
  end
end

hafen.slash():on("t103", run)   -- the only way in: a suite does not start itself
