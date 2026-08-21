-- 085.6 -- the last two sizes. Self-checking suite.
--
-- Two `size` keys still answered {x=, y=} after a size became {w=, h=} everywhere else: a grid's span in
-- tiles on grid:info(), and mapImg:info().size -- which contradicted mapImg:size(), on the SAME handle, so
-- one line of a minimap panel read .w and the next read .x and both looked right.
--
-- WHAT PROVES IT is the pair, on each table: the two keys a size carries ARE there, and the two it does not
-- RAISE naming their replacement rather than reading nil. Only the pair says the keys moved rather than
-- widened. The third claim is the contradiction itself: mapImg:size() and mapImg:info().size are now the
-- same two numbers under the same two keys.
--
-- Both halves need the map database, and grid:image(0) starts a render and answers nil until it lands, so
-- the run waits for a bounded window and scores what it reached.

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

-- Every key of `want` is present on `t` and holds a number. Answers the offender, or nil.
local function keys(t, want)
  if type(t) ~= "table" then return "not a table: " .. type(t) end
  for _, k in ipairs(want) do
    if type(t[k]) ~= "number" then return "." .. k .. " is " .. type(t[k]) end
  end
  return nil
end

-- A grid's span in tiles. The keys are the claim; the numbers are the client's own grid size and this
-- suite does not restate them.
local function gridHalf(grid)
  local sz = grid:info().size
  check(keys(sz, {"w", "h"}) == nil, "grid:info().size carries .w and .h as numbers",
        keys(sz, {"w", "h"}))
  refuses("grid:info().size.x raises naming .w",
          function() return grid:info().size.x end, "a size is {w=, h=}", ".w")
  -- The refusal: the shared metatable reached the map's own tables and not only the widget's.
  refuses("grid:info().size.y raises naming .h",
          function() return grid:info().size.y end, "a size is {w=, h=}", ".h")
end

-- The drawing, where the two verbs on one handle disagreed.
local function imageHalf(img)
  local sz = img:info().size
  check(keys(sz, {"w", "h"}) == nil, "mapImg:info().size carries .w and .h as numbers",
        keys(sz, {"w", "h"}))
  refuses("mapImg:info().size.x raises naming .w",
          function() return img:info().size.x end, "a size is {w=, h=}", ".w")

  local direct = img:size()
  check((keys(direct, {"w", "h"}) == nil) and (direct.w == sz.w) and (direct.h == sz.h),
        "mapImg:size() and mapImg:info().size answer the same two numbers under the same two keys",
        tostring(direct and direct.w) .. "x" .. tostring(direct and direct.h)
          .. " against " .. tostring(sz.w) .. "x" .. tostring(sz.h))
end

local function run()
  local tries, grid, img = 0, nil, nil
  local t
  t = hafen.timer():every(0.5, function()
    tries = tries + 1
    local s = hafen.session():current()
    local g = s and s:exists() and s:player() and s:player():gob()
    local p = g and g:position()
    local gp = p and p:info()
    grid = grid or (gp and hafen.map():grid():get(gp.gridId))
    -- The first ask starts the render off the frame and answers nil; a later one answers the handle.
    img = img or (grid and grid:image(0))
    if (tries < 30) and (img == nil) then return end
    t:cancel()

    if grid == nil then
      check(false, "a recorded grid under this character (both halves need the map database)",
            "none in 15s -- log in, walk a step, and re-run")
    else
      gridHalf(grid)
      if img == nil then
        check(false, "grid:image(0) answered a drawing inside the window",
              "still rendering after 15s -- re-run, the picture is kept once it lands")
      else
        imageHalf(img)
      end
    end
    hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
  end)
end

hafen.slash():register("t085-6", run)   -- the only way in: a suite does not start itself
