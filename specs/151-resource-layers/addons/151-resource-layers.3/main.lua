-- 151.3 — picture, texture and shape specs. Self-checking suite.
--
-- The five spec types this task encodes: image (a picture from an image asset, the other fields kept
-- from the original), tex (a picture, required), neg (hotspot and box), obst (rings in world units) and
-- props (a string-keyed table). Each is written on agi and read back through the very layer snapshot.
-- The window is built after the image write and left standing: what is built keeps its layers, so the
-- release at the end takes nothing off the screen.

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
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function xy(point) return point.x .. "," .. point.y end

local function ringText(ring)
  local parts = {}
  for index, point in ipairs(ring) do parts[index] = xy(point) end
  return table.concat(parts, " ")
end

local standing   -- the window a run left, until the next run of this load

local function run()
  pass, fail, manual = 0, 0, 0
  if standing then
    standing:destroy()
    standing = nil
  end
  local agi = hafen.resource():get("gfx/hud/chr/agi")

  -- The fixture: poll for at most 5 s, then score what landed.
  local started = os.time()
  local poll
  poll = hafen.timer():every(0.2, function()
    if (not agi:loaded()) and (os.time() - started < 5) then return end
    poll:cancel()
    local layers = agi:layers()

    -- image: the picture from red.png, every other field kept from the original.
    local before = layers:get("image"):info()
    local written = layers:add({ type = "image", image = hafen.asset():get("red.png") })
    local info = written and written:info()
    check(info and (info.size.w == 8) and (info.size.h == 8), "agi's image written from red.png reads size 8x8",
          info and (info.size.w .. "x" .. info.size.h))
    check(info and (info.id == -1) and (written == layers:get("image:-1")), "the written image keeps id -1", info and info.id)
    eq("the written image keeps the offset read before the write", info and xy(info.offset), xy(before.offset))
    refuses("a data asset as image is refused naming an image asset",
            function() layers:add({ type = "image", image = hafen.asset():get("notogg.txt") }) end, "IMAGE asset")

    -- tex: the picture is required, and agi carries none to keep.
    refuses("a tex spec on a resource without one is refused naming image",
            function() layers:add({ type = "tex" }) end, "image is missing")

    -- neg: hotspot and box.
    local neg = layers:add({ type = "neg", hotspot = { x = 4, y = 5 }, box = { x = -2, y = -3, w = 10, h = 12 } })
    local negInfo = neg and neg:info()
    check(negInfo and (xy(negInfo.hotspot) == "4,5") and (negInfo.box.x == -2) and (negInfo.box.y == -3)
          and (negInfo.box.w == 10) and (negInfo.box.h == 12) and (#negInfo.ep == 8),
          "a neg with hotspot and box reads back equal",
          negInfo and (xy(negInfo.hotspot) .. " " .. negInfo.box.x .. "," .. negInfo.box.y .. " " .. negInfo.box.w .. "x" .. negInfo.box.h))

    -- obst: one square ring in world units (a tile is 11 units, so these are exact on the wire).
    local square = { { x = -11, y = -11 }, { x = 11, y = -11 }, { x = 11, y = 11 }, { x = -11, y = 11 } }
    local obst = layers:add({ type = "obst", id = "", rings = { square } })
    local obstInfo = obst and obst:info()
    check(obstInfo and (#obstInfo.rings == 1) and (ringText(obstInfo.rings[1]) == ringText(square))
          and (layers:get("obst:") == obst),
          "an obst with one square ring reads back equal", obstInfo and ringText(obstInfo.rings[1] or {}))

    -- props: a string, a number, a coordinate and a list.
    local props = layers:add({ type = "props", props = { name = "agility", weight = 1.5, at = { x = 3, y = 4 }, tags = { "a", 2 } } })
    local propsInfo = props and props:info()
    local got = propsInfo and propsInfo.props
    check(got and (got.name == "agility") and (got.weight == 1.5) and (xy(got.at) == "3,4")
          and (got.tags[1] == "a") and (got.tags[2] == 2) and (#got.tags == 2),
          "a props table reads back equal", got and (tostring(got.name) .. " " .. tostring(got.weight)))
    refuses("a props value that is a boolean is refused naming the kinds a value takes",
            function() layers:add({ type = "props", props = { flag = true } }) end, "a string, a number")

    -- The window: built under nobody's monitor, from the written image; left standing.
    hafen.timer():after(0, function()
      standing = hafen.ui():window():title("151.3"):size(96, 64)
      hafen.ui():image():parent(standing):position(16, 24):source("gfx/hud/chr/agi")
      manualCheck("look at the 151.3 window", "a solid red square where the Agility icon would be")

      agi:release()
      local restored = agi:layers():get("image"):info()
      check((restored.size.w == before.size.w) and (restored.size.h == before.size.h)
            and (agi:layers():count() == 2),
            "release() reads the original picture size and two layers again",
            restored.size.w .. "x" .. restored.size.h .. " / " .. agi:layers():count())
      summary()
    end)
  end)
end

hafen.console():on("t151", run)   -- the only way in: a suite does not start itself
