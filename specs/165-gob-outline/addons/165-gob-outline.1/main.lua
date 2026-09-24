-- 165.1 — gob:outline(color [, width]): a ring round what the client draws of a gob. Self-checking suite.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The refusal text with the chunk prefix LuaJ puts on a Java error stripped, or nil when the call succeeded.
local function refusal(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Every call in `calls` must raise, and its text must contain `want`. Answers nil, or what went wrong.
local function allRefuse(calls, want)
  for i, fn in ipairs(calls) do
    local err = refusal(fn)
    if err == nil then return "#" .. i .. " <no error>" end
    if not err:find(want, 1, true) then return "#" .. i .. " " .. err end
  end
  return nil
end

local function isColor(c, r, g, b, a)
  return type(c) == "table" and c.r == r and c.g == g and c.b == b and c.a == a
end

local function show(c, w)
  if type(c) ~= "table" then return tostring(c) .. ", " .. tostring(w) end
  return ("{%s,%s,%s,%s}, %s"):format(tostring(c.r), tostring(c.g), tostring(c.b), tostring(c.a), tostring(w))
end

local hover = nil   -- the PickChanged subscription of the last run, so a second run does not stack one

local function run()
  pass, fail, manual = 0, 0, 0
  local session = hafen.session():current()
  local me = session and session:player():gob()
  if not me then
    check(false, "the player's gob is there to outline", "no session or no player gob")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end

  -- 1. a write hands the Gob back; the read is the colour keyed (a = 255), then the default width
  local back = me:outline{0, 255, 0}
  local c, w = me:outline()
  check(back == me and isColor(c, 0, 255, 0, 255) and w == 2,
        "outline{0,255,0} hands the Gob back and reads {0,255,0,255}, 2", show(c, w))

  -- 2. alpha and width are kept
  me:outline({255, 0, 0, 128}, 5)
  c, w = me:outline()
  check(isColor(c, 255, 0, 0, 128) and w == 5, "outline({255,0,0,128}, 5) reads a = 128, width 5", show(c, w))

  -- 3. the snapshot key, and the tint untouched by writing and clearing the ring
  me:tint{0, 0, 255, 96}
  local ring = me:info().outline
  local snapOk = type(ring) == "table" and isColor(ring.color, 255, 0, 0, 128) and ring.width == 5
  me:outline(nil)
  me:outline{0, 255, 0}
  me:outline(nil)
  local tint = me:tint()
  check(snapOk and type(tint) == "table" and tint.a == 96,
        "info().outline is {color, width = 5}; writing and clearing it leaves tint().a = 96",
        (type(ring) == "table" and show(ring.color, ring.width) or tostring(ring)) .. " / tint a "
          .. tostring(type(tint) == "table" and tint.a or tint))
  me:tint(nil)

  -- 4. nil takes it off
  back = me:outline(nil)
  local after = me:outline()
  local info = me:info()
  check(back == me and after == nil and info.outline == nil,
        "outline(nil) hands the Gob back, reads nil and drops the snapshot key",
        tostring(after) .. " / " .. tostring(info.outline))

  -- 5. what is not a colour
  local why = allRefuse({
    function() me:outline(1) end,
    function() me:outline("red") end,
    function() me:outline(true) end,
    function() me:outline{"x"} end,
  }, "a colour is a table")
  check(why == nil, "1, \"red\", true and {\"x\"} are refused naming a colour", why)

  -- 6. widths
  why = allRefuse({function() me:outline({0, 255, 0}, 0) end,
                   function() me:outline({0, 255, 0}, 9) end}, "from 1 to 8")
            or allRefuse({function() me:outline({0, 255, 0}, 1.5) end}, "whole number")
            or allRefuse({function() me:outline({0, 255, 0}, "2") end}, "must be a number")
  check(why == nil, "widths 0, 9, 1.5 and \"2\" are refused naming the rule", why)

  -- 7. a surplus argument, and a width beside nil
  why = allRefuse({function() me:outline({0, 255, 0}, 2, 3) end}, "takes at most 2 arguments")
            or allRefuse({function() me:outline(nil, 2) end}, "takes no width")
  check(why == nil, "a third argument and outline(nil, 2) are refused", why)

  -- 8. a gob that is not there: the read is nil, a write does nothing and raises nothing
  local gone = session:world():gob():get(2 ^ 40)
  local before = gone:outline()
  local okWrite, errWrite = pcall(function() return gone:outline{1, 2, 3} end)
  local later = gone:outline()
  check(before == nil and okWrite and later == nil,
        "a gob that is gone reads nil, takes outline{1,2,3} silently, still reads nil",
        tostring(before) .. " / " .. tostring(errWrite) .. " / " .. tostring(later))

  -- 9. composition with scale and visible
  me:outline{0, 255, 0}:scale(2):visible(false):visible(true)
  c, w = me:outline()
  local scale, shown = me:scale(), me:visible()
  check(isColor(c, 0, 255, 0, 255) and w == 2 and scale == 2 and shown == true,
        "outline, scale(2), visible(false), visible(true) compose",
        show(c, w) .. " / scale " .. tostring(scale) .. " / visible " .. tostring(shown))
  me:scale(1)

  -- the hover highlight, for 20 s: the object under the pointer (never me) in blue, 4 px
  if hover then hover:off() end
  local lit = nil
  local myId = me:id()
  local sub
  sub = hafen.ui():mouse():on("PickChanged", function(gob)
    if lit then lit:outline(nil) end
    lit = nil
    if gob and gob:id() ~= myId then
      gob:outline({0, 120, 255}, 4)
      lit = gob
    end
  end)
  hover = sub
  hafen.timer():after(20, function()
    sub:off()
    if hover == sub then hover = nil end
    if lit then lit:outline(nil) end
    lit = nil
  end)

  manualCheck("look at your character",
              "a green 2 px ring round body and gear, nothing painted inside")
  manualCheck("for 20 s, sweep the pointer across objects",
              "the one under it wears a blue 4 px ring, the one it left loses it")
  manualCheck("put a tree or a wall between the camera and your character",
              "the ring follows the obstacle's edge, never crossing it")
  manualCheck("type :reload", "the green ring round your character is gone")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t165-1", run)   -- the only way in: a suite does not start itself
