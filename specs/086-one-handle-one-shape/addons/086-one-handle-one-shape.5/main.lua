-- 086.5 -- a file you loaded is an object. Self-checking suite.
--
-- Five handle kinds -- an image, a mesh, a data file, a font and a rendered map drawing -- were tables
-- of per-instance closures with no metatable at all. So tostring(icon) said "table: 0x...", a typo read
-- nil and failed one call later naming nothing, `icon.dispose = nil` disabled the addon's own cleanup,
-- and a hand-built look-alike was accepted by every verb that draws an image.
--
-- THE CLAIM IS THAT EACH OF THEM IS NOW ONE SHAPE: userdata over the record, with a shared per-addon
-- metatable, a closed vocabulary and a __tostring that names the file. The reads answer exactly as they
-- did, identity survives -- w:style().font is the very handle you wrote -- and the forgery is gone.
--
-- It ships its own dot.png, tri.gltf, data.json and fonts/inconsolata.ttf, so everything but the map
-- drawing is proved without the world.

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

-- One verdict line per claim, scored -- the shape is five handles' worth of the same sentence.
local function scored(hits, of, what, got)
  check(hits == of, ("%s (%d/%d)"):format(what, hits, of), got)
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn, ...)
  local ok, r = pcall(fn, ...)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

local function has(s, needle)
  return (s ~= nil) and (tostring(s):find(needle, 1, true) ~= nil)
end

-- ---- the shape ---------------------------------------------------------------------------------------
--
-- Five handles, one sentence: each is userdata, and each prints as something a log line can use. A face
-- that was loaded from a file names the file, like every other asset; a built-in was never a file, so it
-- names the only thing it has, its family.
local function shapeSection()
  local a = hafen.asset()
  local held = {
    img  = a:get("dot.png"),
    mdl  = a:get("tri.gltf"),
    data = a:get("data.json"),
    face = a:get("fonts/inconsolata.ttf"),
  }
  held.builtin = hafen.font():get("serif")

  local want = {
    {held.img,  "Asset(image, dot.png)"},
    {held.mdl,  "Asset(mesh, tri.gltf)"},
    {held.data, "Asset(data, data.json)"},
    {held.face, "Asset(font, fonts/inconsolata.ttf)"},
    {held.builtin, "Font(" .. held.builtin:family() .. ")"},
  }
  local hits, saw = 0, {}
  for _, w in ipairs(want) do
    local h, printed = w[1], tostring(w[1])
    if (type(h) == "userdata") and (printed == w[2]) then hits = hits + 1 end
    saw[#saw + 1] = printed
  end
  scored(hits, #want, "every handle is userdata printing what it is", table.concat(saw, " "))
  return held
end

-- ---- the reads ---------------------------------------------------------------------------------------
--
-- Nothing about the vocabulary moved: what a table of closures answered, a shared metatable answers.
local function readsSection(h)
  local sz, b, info = h.img:size(), h.mdl:bounds(), h.mdl:info()
  local hits, saw = 0, {}
  local function one(ok, got)
    if ok then hits = hits + 1 end
    saw[#saw + 1] = tostring(got)
  end
  one((sz.w == 8) and (sz.h == 6), sz.w .. "x" .. sz.h)
  one(h.img:type() == "image", h.img:type())
  one(h.img:path() == "dot.png", h.img:path())
  one((b.extent.z > 0) and (b.extent.z == b.max.z - b.min.z), b.extent.z)
  one(info.tris == 1, info.tris)
  one(h.data:text():find("a data asset", 1, true) ~= nil, h.data:text())
  one(h.face:family() == "Inconsolata", h.face:family())
  one((h.face:type() == "font") and (h.face:path() == "fonts/inconsolata.ttf"), h.face:type())
  -- and the collection still answers over them: a string filter matches the path a member was loaded
  -- from, and the member it hands back is the very handle :get did.
  one(hafen.asset():find("dot") == h.img, hafen.asset():count())
  scored(hits, 9, "the reads answer exactly as they did", table.concat(saw, " "))
end

-- ---- what being userdata buys ------------------------------------------------------------------------
--
-- The two mistakes that used to be silent, and the marker field the forged look-alike was built out of:
-- reading it is a typo like any other now, so there is nothing left to copy into a table.
local function closedSection(h)
  local wrote = said(function() h.img.dispose = nil end)
  check(has(wrote, "userdata") and (h.img:type() == "image"),
        "img.dispose = nil is refused: an addon cannot break its own teardown",
        wrote or "<no error>")

  local typo = said(function() return h.img:sizes() end)
  check(has(typo, ":size()") and has(typo, ":dispose()"),
        "img:sizes() raises naming the vocabulary instead of reading nil",
        typo or "<no error>")

  local marker = said(function() return h.img.__image end)
  check(marker ~= nil, "the marker field a look-alike was copied from is a typo like any other",
        marker or "<no error>")
end

-- ---- a face, and a face that is also a file ----------------------------------------------------------
--
-- Freeing a file is the job of the addon that loaded it, and a variant is a font rather than a file --
-- so the asset verbs are on the loaded face and on neither of the other two, and reaching for one says
-- which of the two kinds you are holding.
local function facesSection(h)
  local builtin = said(function() return h.builtin:dispose() end)
  local variant = said(function() return h.face:derive():path() end)
  check(has(builtin, ":derive()") and has(builtin, "loaded from")
          and has(variant, ":derive()") and has(variant, "loaded from"),
        "a built-in and a variant refuse the asset verbs, naming the file a face was loaded from",
        tostring(builtin) .. " / " .. tostring(variant))

  local d = h.face:derive():size(11):bold(true)
  check((type(d) == "userdata") and (d:size() == 11) and (d:bold() == true)
          and (h.face:size() == nil),
        "a variant is still a chain of setters over the face it came from, and leaves that face alone",
        tostring(d) .. " size=" .. tostring(d:size()))
  return d
end

-- ---- identity ----------------------------------------------------------------------------------------
--
-- Four pages promise that a style read hands back the very handle you wrote. The handles moved from a
-- table to userdata; the interning behind them did not.
local function identitySection(h, variant)
  local w = hafen.ui():window():title("086.5"):size(120, 40)
  local ok, style = pcall(function()
    w:rule():font(variant):bg{image = h.img}
    return w:style()
  end)
  w:destroy()
  if not ok then error(style) end
  check((style ~= nil) and (style.font == variant) and (style.bg ~= nil) and (style.bg.image == h.img),
        "w:style() hands back the very font and image handles the rule was written with",
        (style == nil) and "nil" or (tostring(style.font) .. " / "
          .. tostring(style.bg and style.bg.image)))
end

-- ---- the forgery -------------------------------------------------------------------------------------
--
-- A table shaped like an image handle used to be taken by everything that draws one, and then lied about
-- its size wherever it was passed. Nothing resolves a table any more, so the verb refuses it and points
-- at the one door a picture comes through.
local function forgerySection(h)
  local s = hafen.session():current()
  if s == nil then
    manual = manual + 1
    hafen.log():write("[manual] no character is logged in -- run :t086-5 in the world"
      .. " -- expect: the sprite and map drawing checks scored")
    return nil
  end
  local p = s:player():gob():position()
  local msg = said(function() return hafen.vr():sprite():add({}, p) end)
  check(has(msg, "hafen.asset"),
        "a hand-built look-alike is refused naming hafen.asset()", msg or "<no error>")
  return s
end

-- ---- the map drawing ---------------------------------------------------------------------------------
--
-- The fifth kind is a picture of the map database rather than a file, so only the client can produce it:
-- the first call kicks the render and answers nil. Scored over a bounded window, and over whatever the
-- run reached.
local function mapSection(s, done)
  if s == nil then return done() end
  local grid = hafen.map():grid():get(s:player():gob():position():info().gridId)
  local tries = 0
  local function poll()
    tries = tries + 1
    local img = grid and grid:image(0)
    if img then
      local i = img:info()
      -- ...and it is closed like the other four: a typo raises naming the one verb an asset has not got.
      local typo = said(function() return img:sizes() end)
      check((type(img) == "userdata") and (tostring(img):find("Asset(image, map:", 1, true) == 1)
              and (img:size().w > 0) and (i.source == "map") and (img:type() == "image")
              and has(typo, ":info()"),
            "a map drawing is an image handle that says what ground it is of",
            tostring(img) .. " " .. tostring(i and i.source) .. " " .. tostring(typo))
      return done()
    end
    if tries >= 20 then
      check(false, "a map drawing is an image handle that says what ground it is of",
            "still rendering after " .. tries .. " tries")
      return done()
    end
    hafen.timer():after(0.25, poll)
  end
  poll()
end

-- ---- the refusal that was already there ---------------------------------------------------------------
--
-- The sandbox check and the RAISE promise sit beside every line this task rewrote: a file this addon does
-- not ship is an authoring mistake, and it is named.
local function refusalSection()
  local msg = said(function() return hafen.asset():get("no.png") end)
  check(has(msg, "no such file") and has(msg, "no.png"),
        "hafen.asset():get(\"no.png\") still raises naming the file", msg or "<no error>")
end

local function run()
  pass, fail, manual = 0, 0, 0
  local h = section("shape", shapeSection)
  if h then
    section("reads", readsSection, h)
    section("closed", closedSection, h)
    local variant = section("faces", facesSection, h)
    if variant then section("identity", identitySection, h, variant) end
  end
  section("refusal", refusalSection)
  local s = h and section("forgery", forgerySection, h)

  mapSection(s, function()
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():on("t086-5", run)               -- the only way in: a suite does not start itself
