-- 110.4 -- text that wraps, and the box it takes. Self-checking suite.
--
-- WHAT THIS SHIPS. g:text and g:atext take a `width` in the opts table they already carry and WRAP at it,
-- and hafen.ui():measure(s, opts) answers {w =, h =} for the box that identical call would occupy --
-- asked from anywhere, not only from inside a draw callback.
--
-- HOW IT IS PROVED, WITHOUT READING A PIXEL. A hafen.ui():label() is exactly as wide as the string it
-- rendered: writing a caption resizes it to the raster. So label:text(s):size() IS a drawn box, read back
-- through an unrelated surface, and measuring the same string against it is the whole claim that a
-- measured line and a drawn one are one thing. Everything after that is arithmetic on boxes: a width
-- narrower than the drawn box has to answer a taller box that fits inside the width, two different widths
-- have to answer two different boxes, and a string carrying $col{...} has to measure as the words it
-- draws rather than as the characters it is spelled with.
--
-- THE CACHE KEY, WHICH IS THE BUG THIS COULD HAVE. The measure rasterises through the very cache g:text
-- draws through, so hafen.client():profiling():textcache() can be read across it. A width missing from
-- the key shows up as one entry serving two widths -- the second measure would be a HIT and would answer
-- the first one's box. The counters are therefore asserted directly: a new width takes a new entry, and
-- only a repeat of a width already measured is a hit. That is also what ties this to the draw half, since
-- the entry either call makes is the same entry under the same key.

local pass, fail, manual = 0, 0, 0
local runs = 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING every one of `wants`.
local function refuses(what, fn, wants)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local missing = nil
  if not ok then
    for _, w in ipairs(wants) do
      if err:find(w, 1, true) == nil then missing = w end
    end
  end
  check((not ok) and (missing == nil), what, ok and err or ("no mention of " .. tostring(missing)
    .. " in: " .. err))
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function box(b)
  return (b == nil) and "nil" or ("" .. tostring(b.w) .. "x" .. tostring(b.h))
end

local function same(a, b)
  return (a ~= nil) and (b ~= nil) and (a.w == b.w) and (a.h == b.h)
end

local function body()
  local ui = hafen.ui()

  -- (1) THE DRAWN BOX. A label renders its caption and resizes to the raster, so its :size() is a box the
  -- client actually put on screen -- measured by a surface that knows nothing about this verb.
  local s = "the quick brown fox jumps over the lazy dog"
  local l = ui:label():text(s)
  local drawn = l:size()
  l:destroy()                       -- built, read and gone inside one statement run: it never paints
  check(same(ui:measure(s, {}), drawn), "measure answers the box a label of the same string drew ("
        .. box(drawn) .. ")", box(ui:measure(s, {})))

  -- (2) THE WRAP. Half the width the line wanted: the box has to grow downwards and stay inside the width.
  local half = math.floor(drawn.w / 2)
  local wide = ui:measure(s, {width = half})
  check(wide.h > drawn.h, "a width narrower than the line answers a taller box (" .. box(wide) .. ")",
        box(wide))
  check(wide.w <= half, "...and one no wider than the width it was given (" .. half .. ")", wide.w)

  -- (3) TWO WIDTHS, TWO RASTERS. If the width were missing from the cache key the second measure would
  -- be served the first one's raster, and these two boxes would be one box.
  local quarter = math.floor(drawn.w / 4)
  local narrow = ui:measure(s, {width = quarter})
  check(not same(wide, narrow), "the same string at two widths answers two different boxes",
        box(wide) .. " vs " .. box(narrow))
  check(same(ui:measure(s, {width = half}), wide),
        "...and the first width still answers what it answered", box(ui:measure(s, {width = half})))

  -- (4) MARKUP IS READ, NOT COUNTED. The point of measuring through the client's own rich text: a $col
  -- run is a colour, not twelve more characters to lay out.
  local plain = "hello there brown fox"
  local marked = "$col[255,0,0]{hello} there $b{brown} fox"
  local w = math.max(1, math.floor(ui:measure(plain, {}).w / 2))
  check(same(ui:measure(marked, {width = w}), ui:measure(plain, {width = w})),
        "a line carrying $col{} and $b{} measures as the words it draws",
        box(ui:measure(marked, {width = w})) .. " vs " .. box(ui:measure(plain, {width = w})))

  -- (5) THE CACHE, READ ACROSS THE CALLS. A string this run has never measured, so the entries it takes
  -- are its own. `total` is left alone: this addon's own cache is what the verb rasterises into.
  local u = "110.4 run " .. runs .. " alpha beta gamma delta epsilon"
  local wu = math.max(1, math.floor(ui:measure(u, {}).w / 3))
  local a = hafen.client():profiling():textcache()
  ui:measure(u, {width = wu})
  local b = hafen.client():profiling():textcache()
  ui:measure(u, {width = wu * 2})
  local c = hafen.client():profiling():textcache()
  ui:measure(u, {width = wu})
  local d = hafen.client():profiling():textcache()
  check((b.entries == a.entries + 1) and (b.misses == a.misses + 1)
          and (c.entries == b.entries + 1) and (c.misses == b.misses + 1),
        "each new width takes a cache entry of its own",
        tostring(a.entries) .. " -> " .. tostring(b.entries) .. " -> " .. tostring(c.entries))
  check((d.entries == c.entries) and (d.hits == c.hits + 1),
        "...and only a width already measured is served from it",
        tostring(d.entries) .. " entries, " .. tostring(d.hits - c.hits) .. " hit")

  -- (6) THE REFUSALS. Zero is the engine's own word for "do not wrap" and is exactly what an addon's own
  -- arithmetic produces by accident, so it is refused rather than read as one line.
  refuses("a width of 0 is refused, saying how to ask for one line",
          function() return ui:measure(s, {width = 0}) end, {"positive", "omit width"})
  refuses("opts that is not a table is refused, naming the table g:text takes",
          function() return ui:measure(s, "60") end, {"must be a table", "g:text"})

  summary()
end

-- The run is one pcall, so a read that throws becomes one [fail] line and a summary rather than a bare
-- stack trace with no verdict under it.
local function run()
  pass, fail, manual, runs = 0, 0, 0, runs + 1
  local ok, err = pcall(body)
  if not ok then
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    summary()
  end
end

hafen.console():on("t110", run)   -- the only way in: a suite does not start itself
