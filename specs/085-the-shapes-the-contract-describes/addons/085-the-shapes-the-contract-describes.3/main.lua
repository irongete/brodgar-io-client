-- 085.3 -- a size is a size. Self-checking suite.
--
-- One word wore two shapes: widget:size() answered {x=, y=} while widget:cell(), img:size() and
-- mapImg:size() answered {w=, h=}, so the wrong guess read nil rather than raising. Every size now
-- answers .w and .h, and the keys that moved RAISE -- which is the half a rename alone would not give,
-- because code written against the old spelling goes on running and reads nothing.
--
-- BOTH DIRECTIONS ARE ASSERTED. The read has to carry .w AND refuse .x: only the pair proves the shape
-- moved rather than widened. The same goes for the write, where an {x=, y=} under `size` must be refused
-- instead of taken -- the positional fallback would otherwise swallow it and lay the widget out anyway.
--
-- IT ASKS NOTHING OF THE WORLD. Every surface here is one this addon builds in the layer, and the image
-- and the model are its own files, so the whole run is the same on the login screen as in the world.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING every word the reader needs.
local function refuses(what, fn, ...)
  local msg = said(fn)
  local ok = (msg ~= nil)
  for _, want in ipairs({...}) do
    ok = ok and (msg:find(want, 1, true) ~= nil)
  end
  check(ok, what, msg or "<no error>")
end

-- One key off a table that may REFUSE it: every "got" line below reads through this, so a shape that
-- came back wrong is reported rather than killing the run from inside the report.
local function peek(t, k)
  if type(t) ~= "table" then return nil end
  local ok, v = pcall(function() return t[k] end)
  return ok and v or nil
end

-- A shape, described: nil when the table carries exactly the numbers named, otherwise what is wrong.
local function shaped(t, ...)
  if type(t) ~= "table" then return "a " .. type(t) end
  for _, k in ipairs({...}) do
    local v = peek(t, k)
    if type(v) ~= "number" then return "." .. k .. " is " .. ((v == nil) and "absent" or type(v)) end
  end
  return nil
end

-- ---- the size a widget answers ---------------------------------------------------------------------

local function sizes()
  local w = hafen.ui():widget():size(120, 40):position(30, 20)

  local sz = w:size()
  check((type(sz) == "table") and (sz.w == 120) and (sz.h == 40),
        "w:size(120, 40) reads back {w = 120, h = 40}",
        shaped(sz, "w", "h") or (tostring(peek(sz, "w")) .. "x" .. tostring(peek(sz, "h"))))

  -- The claim the whole task rests on: the key that moved says where it went, rather than reading nil.
  refuses("w:size().x raises naming .w and .h", function() return w:size().x end,
          "a size is {w=, h=}", ".w and .h")
  refuses("w:size().y raises the same way", function() return w:size().y end, "a size is {w=, h=}")

  -- A place and a pixel did NOT move: three reads that must answer exactly what they always did.
  local unmoved, wrong = 0, {}
  local ok, grid = pcall(function() return hafen.ui():grid():size(100, 100):cell(24, 18) end)
  grid = ok and grid or nil
  for _, e in ipairs({{"grid:cell()", grid and grid:cell(), "w", "h"},
                      {"w:position()", w:position(), "x", "y"},
                      {"w:rootPos()", w:rootPos(), "x", "y"}}) do
    local bad = shaped(e[2], e[3], e[4])
    if bad then wrong[#wrong + 1] = e[1] .. " " .. bad else unmoved = unmoved + 1 end
  end
  check(unmoved == 3, "the shapes that did not move -- grid:cell(), w:position(), w:rootPos() (3/3)",
        (#wrong > 0) and table.concat(wrong, ", ") or ("only " .. unmoved .. " reached"))

  local i = w:info()
  check((i ~= nil) and (peek(i.size, "w") == 120) and (peek(i.size, "h") == 40)
          and (peek(i.pos, "x") == 30) and (peek(i.pos, "y") == 20),
        "w:info().size is {w=, h=} and w:info().pos is still {x=, y=}",
        (i == nil) and "no snapshot"
          or (tostring(peek(i.size, "w")) .. "/" .. tostring(peek(i.pos, "x"))))

  -- The metatable must not reach anything that WALKS the table: pairs and the serialiser both read the
  -- raw fields, so a size goes into a saved variable and out over hafen.json exactly as it always did.
  local keys, seen = 0, {}
  for k in pairs(w:size()) do keys = keys + 1; seen[k] = true end
  check((keys == 2) and seen.w and seen.h, "pairs(w:size()) walks exactly two keys, w and h", keys)

  local enc = hafen.json():encode(w:size())
  check((enc == '{"w":120,"h":40}') or (enc == '{"h":40,"w":120}'),
        "hafen.json():encode(w:size()) is {\"w\":120,\"h\":40}", enc)

  -- The refusal that sits beside this code and must still fire: one number is the arity a control's own
  -- ART earns, and a surface with no art of its own has nothing to ask.
  refuses("w:size(400) on a widget with no art of its own still refuses, naming both ways out",
          function() hafen.ui():widget():size(400) end, "widget:size(w, h)", "widget:pack()")

  if grid then grid:destroy() end
  w:destroy()
end

-- ---- the size a stylesheet rule says, which is the same parser a theme.json goes through -------------

local function rules()
  local r = hafen.ui():sheet():rule("window[title=085-3-probe]")
  r:size(300, 220)

  local err = said(function() r:size(r:size()) end)
  local back = r:size()
  check((err == nil) and (type(back) == "table") and (back.w == 300) and (back.h == 220),
        "rule:size(rule:size()) is one expression, and reads back {w = 300, h = 220}",
        err or (shaped(back, "w", "h")
                  or (tostring(peek(back, "w")) .. "x" .. tostring(peek(back, "h")))))

  refuses("rule:size{x = 300, y = 200} is refused, naming w and h",
          function() r:size({x = 300, y = 200}) end, "a size is spelled w and h", "w = 300, h = 200")

  r:remove()
end

-- ---- the two assets this suite ships, so the shapes below need nothing of the world ------------------

local function assets()
  local img = hafen.asset():get("probe.png")
  local s = img:size()
  check((type(s) == "table") and (s.w == 4) and (s.h == 2), "img:size() is unchanged: {w = 4, h = 2}",
        shaped(s, "w", "h") or (tostring(peek(s, "w")) .. "x" .. tostring(peek(s, "h"))))

  local mdl = hafen.asset():get("probe.gltf")
  local b = mdl:bounds()
  check((shaped(peek(b, "extent"), "x", "y", "z") == nil) and (shaped(peek(b, "min"), "x", "y", "z") == nil)
          and (shaped(peek(b, "max"), "x", "y", "z") == nil),
        "mdl:bounds() carries .min, .max and a numeric .extent.z",
        shaped(peek(b, "extent"), "x", "y", "z") or tostring(b))

  refuses("mdl:bounds().size raises naming extent", function() return mdl:bounds().size end, "extent")
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn)
  local ok, err = pcall(fn)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(err))
  end
end

local function run()
  pass, fail = 0, 0
  section("widget", sizes)
  section("rule", rules)
  section("asset", assets)
  hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

hafen.slash():register("t085-3", run)   -- the only way in: a suite does not start itself
