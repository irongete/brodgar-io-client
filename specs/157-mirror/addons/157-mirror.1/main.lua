-- 157.1 — hafen.ui():mirror(): a surface showing another widget's live picture. Self-checking suite.

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

local function box(widget)
  local size = widget:size()
  return size.w .. "x" .. size.h
end

local function run()
  -- the builder
  refuses("hafen.ui():mirror(1) is refused", function() hafen.ui():mirror(1) end, "takes no arguments")

  -- a source of our own: a painted surface, so the picture is something
  local source = hafen.ui():widget():size(64, 40):position(300, 300)
  source:on("Draw", function(draw_event)
    local graphics = draw_event:g()
    graphics:color(220, 120, 40)
    graphics:frect(0, 0, draw_event:w(), draw_event:h())
  end)
  local parent_before = source:parent()
  local position_before = source:position()

  local mirror = hafen.ui():mirror()
  eq("a mirror reads :type()", mirror:type(), "MirrorWidget")
  eq(":source() is nil before the first write", mirror:source(), nil)
  check(mirror:source(source) == mirror, ":source(w) chains")
  check(mirror:source() == source, ":source() reads the same widget back")
  eq("the box follows the source's box", box(mirror), "64x40")

  -- the pin
  mirror:size(32, 20)
  mirror:source(source)
  eq("a :size(w, h) survives a second :source(w)", box(mirror), "32x20")
  mirror:size(nil)
  eq(":size(nil) gives the box back to the source's", box(mirror), "64x40")
  refuses(":size(w) is refused naming the pair", function() mirror:size(10) end, "widget:size(w, h)")

  -- the source is untouched
  check(source:parent() == parent_before, "the source keeps its parent")
  local position_after = source:position()
  check(position_after.x == position_before.x and position_after.y == position_before.y, "the source keeps its place")
  eq("the source keeps its box", box(source), "64x40")
  eq("the source stays visible", source:visible(), true)
  eq("the source still exists", source:exists(), true)

  -- two mirrors of one source
  local second = hafen.ui():mirror():source(source)
  check(mirror:source() == source and second:source() == source, "two mirrors of one source both read it back")
  second:destroy()
  eq("destroying a mirror leaves the source standing", source:exists(), true)

  -- a source that goes
  local going = hafen.ui():widget():size(10, 10)
  local orphan = hafen.ui():mirror():source(going)
  going:destroy()
  eq(":source() reads nil once the source left its tree", orphan:source(), nil)
  eq("...and the mirror stands", orphan:exists(), true)
  orphan:destroy()

  -- refusals
  refuses(":source(\"x\") names what a mirror takes", function() mirror:source("gfx/hud/buttons/addu") end, "a Widget")
  refuses(":source(mirror) is a mirror inside its own picture", function() mirror:source(mirror) end, "its own picture")
  local holder = hafen.ui():widget():size(100, 100):position(300, 500)
  local inner = hafen.ui():mirror():parent(holder)
  refuses(":source(an ancestor) is a mirror inside its own picture", function() inner:source(holder) end, "its own picture")
  holder:destroy()
  local wide = hafen.ui():widget():size(3000, 10)
  refuses("a source over 2048 px on a side is refused naming the ceiling", function() mirror:source(wide) end, "2048")
  wide:destroy()

  -- the HUD portraits: one mirror per logged-in character, in a column the maintainer looks at
  local portraits = 0
  local rows = 0
  for _, session in ipairs(hafen.session():list()) do
    local portrait = session:ui():matchAll("@Avaview")[1]
    if portrait then
      local parent_was = portrait:parent()
      local row = hafen.ui():mirror():source(portrait):position(300, 400 + rows * 54):size(48, 48)
      rows = rows + 1
      if row:source() == portrait and portrait:parent() == parent_was then
        portraits = portraits + 1
      end
    end
  end
  eq("every logged-in character's HUD portrait mirrors and stays in its HUD", portraits, rows)

  -- the hit test wants the mirror armed, which is the tick after this statement
  mirror:position(300, 350)
  hafen.timer():after(0.5, function()
    check(hafen.ui():hit(310, 360) == mirror, "hafen.ui():hit() over the mirror answers the mirror")
    manualCheck("look at x=300: the orange mirror over its source, and one portrait per logged-in character below it (two characters logged in, one of them not on screen)",
                "both portraits drawn and moving, the one nobody is looking at like the other; the HUD's own portrait still in its corner; they stay until :reload")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

-- The only way in: a suite does not start itself. On the step, not inside the console command, which runs under
-- the drawn tree's monitor: a mirror of another session's widget takes that session's, and two nest nowhere.
hafen.console():on("t157", function() hafen.timer():after(0, run) end)
