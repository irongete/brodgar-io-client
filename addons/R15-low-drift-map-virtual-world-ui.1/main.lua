-- R15 -- low drift: map, virtual, world and ui. Every check asserts through the API the block changed.
-- It runs only from :tr15, mutates no persistent state, and takes back everything it stands or attaches.

local pass, fail = 0, 0

local function say(s) hafen.log():write(s) end

local function ok(name, cond, got)
  if cond then
    pass = pass + 1
    say("[pass] " .. name)
  else
    fail = fail + 1
    say("[fail] " .. name .. " -- got: " .. tostring(got))
  end
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a space and no colon, and a Lua-level
-- error adds a "chunk.lua:12: " of its own in front of it. Strip both, and no more than both.
local function why(e)
  local s = tostring(e)
  for _ = 1, 2 do
    local cut, n = s:gsub("^@?.-%.lua:%d+:?%s*", "")
    if n == 0 then break end
    s = cut
  end
  return s
end

-- Did fn refuse, and did it say why? Answers nil when it did, and what went wrong when it did not.
local function refused(label, fn, needle)
  local good, err = pcall(fn)
  if good then return label .. ": <no error>" end
  local msg = why(err)
  -- ...spelled with an `if`, never `cond and nil or x`: in Lua the `and` branch being nil takes the
  -- `or` branch, so that idiom answers "it did not refuse" for every refusal that did.
  if msg:find(needle, 1, true) ~= nil then return nil end
  return label .. ": " .. msg
end

-- Every one of these must refuse, and say why. One line for the set; the first that does not is what
-- the line reports.
local function allRefuse(name, cases)
  local bad
  for _, c in ipairs(cases) do
    bad = bad or refused(c[1], c[2], c[3])
  end
  ok(name, bad == nil, bad)
end

-- ---- the world half, scored over a bounded window ------------------------------------------------
-- Half of what this block changed lives on a gob, a place or a recorded grid, and only the server
-- makes those. The run waits on a timer until the character is in the world and then scores what it
-- reached, rather than asking the maintainer to stand somewhere before typing the command.

local TRIES = 20            -- ~10 s at 500 ms, which is a login and a step outdoors

local function world()
  local s = hafen.session():current()
  if not s then return nil end
  local got, g = pcall(function() return s:player():gob() end)
  if not got or not g or not g:exists() then return nil end
  local at, p = pcall(function() return g:position() end)
  if not at or not p then return nil end
  return g, p
end

local function flat()
  -- ---- the ceilings the drawn word had none of (uc-06, uc-05, uc-23) ----------------------------
  local tc = hafen.client():profiling():textcache()
  -- Deliberately NOT measured from just below the ceiling: 4096 characters is a raster some thirty
  -- thousand pixels wide, and asking for one to prove that it is allowed is the very cost the cap
  -- exists to refuse. An ordinary line is what proves the door still opens.
  local measured = hafen.ui():measure("the quick brown fox")
  allRefuse("a drawn string is bounded at 4096 characters, and the refusal names the ceiling", {
    {"measure", function() return hafen.ui():measure(string.rep("x", 4097)) end, "past the 4096"},
  })
  ok("the text cache reports all three of its ceilings, and an ordinary line still measures",
     (tc.maxEntries == 512) and (tc.maxBytes > 0) and (tc.maxEntryBytes > 0) and (measured.w > 0),
     tostring(tc.maxEntries) .. "/" .. tostring(tc.maxBytes) .. "/" .. tostring(tc.maxEntryBytes))

  -- ---- a row list is bounded, and has no holes in it (uc-17, uc-18) ------------------------------
  local box = hafen.ui():listbox():size(120, 80)
  local many = {}
  for i = 1, 4097 do many[i] = "row " .. i end
  local holed = {}
  holed[1] = "a"
  holed[3] = "c"
  allRefuse("a row list is bounded at 4096, and a hole in the array is named by its index", {
    {"4097 rows", function() box:rows(many) end, "past the 4096"},
    {"a hole",    function() box:rows(holed) end, "row 2 is nil"},
  })

  -- ---- a HUD painter is a live object, so it answers :info() (un-18) -----------------------------
  local ov = hafen.ui():overlay():add("r15probe")
  local bare = ov:info()
  ov:draw(function() end)
  local drawn = ov:info()
  hafen.ui():overlay():remove("r15probe")
  ok("a HUD painter answers :info(), and `drawn` is false until it has been given a painter",
     (bare.key == "r15probe") and (bare.exists == true) and (bare.drawn == false)
       and (drawn.drawn == true) and (ov:info().exists == false),
     tostring(bare.drawn) .. "/" .. tostring(drawn.drawn) .. "/" .. tostring(ov:info().exists))

  box:destroy()
end

local function inworld(g, p)
  -- ---- a ring that crosses itself is refused as a concave one is (vm-04) -------------------------
  -- The five points of a pentagon, in two orders. Round the rim they are a convex ring and stand; every
  -- second one they are a PENTAGRAM, which turns the same way at every vertex -- so the turn-sign test
  -- passed it and the carve drew the small pentagon in the middle instead. It goes round TWICE, and
  -- that is what is now asked. The bow-tie beside it is the other shape, caught by the turn signs.
  local v = {}
  for i = 0, 4 do
    local a = math.rad(90 + (72 * i))
    v[i + 1] = p:offset(6 * math.cos(a), 6 * math.sin(a))
  end
  local pent = {v[1], v[2], v[3], v[4], v[5]}
  local star = {v[1], v[3], v[5], v[2], v[4]}
  local bow  = {p, p:offset(6, 6), p:offset(6, 0), p:offset(0, 6)}
  local laid = hafen.virtual():patch():add(pent, p)
  allRefuse("a star and a bow-tie are refused, where the convex ring of the same points is laid", {
    {"a pentagram", function() hafen.virtual():patch():add(star, p) end, "convex"},
    {"a bow-tie",   function() hafen.virtual():patch():add(bow, p) end, "convex"},
  })
  ok("...and the pentagon it was refused beside really did stand",
     (laid ~= nil) and (laid:exists() == true), tostring(laid))

  -- ---- a ghost carries the fifth answer to "why can I not see it" (vg-05) ------------------------
  -- Only the loader can answer this one, so the ghost is stood here and scored at the end of the run
  -- over a bounded window -- a name that will never resolve has to be tried before it can be refused.
  local gh = hafen.virtual():ghost():add("gfx/terobjs/r15-no-such-resource", p)

  -- ---- a dot call on a world entity raises rather than reading (vg-09) ---------------------------
  -- Both shapes the row names. `e.visible(false)` puts the argument in slot 1, where the receiver
  -- belongs, so the write used to read; `a.exists(b)` is a's verb called on b, so it used to answer
  -- about a. Two entities are needed to write the second one at all, which is why the ghost is here.
  allRefuse("a dot call on a world entity raises rather than silently reading", {
    {"visible(false)", function() laid.visible(false) end, "COLON call"},
    {"a.exists(b)",    function() laid.exists(gh) end, "COLON call"},
  })
  hafen.virtual():patch():remove(laid)

  -- ---- what one gob holds (po-08) ---------------------------------------------------------------
  local keys = {}
  for i = 1, 32 do
    keys[i] = "r15-" .. i
    g:overlay():add(keys[i]):text("x")
  end
  allRefuse("a gob takes 32 overlays of yours, and the key and the label are bounded too", {
    {"a 33rd",       function() g:overlay():add("r15-33"):text("x") end, "already has 32"},
    {"a long key",   function() g:overlay():add(string.rep("k", 129)) end, "past the 128"},
    {"a long label", function() g:overlay():get(keys[1]):text(string.rep("t", 257)) end, "past the 256"},
  })
  for i = 1, 32 do g:overlay():remove(keys[i]) end
  ok("...and taking them off again leaves the gob with nothing of ours on it",
     g:overlay():count("r15-") == 0, tostring(g:overlay():count("r15-")))

  -- ---- a recorded grid says whether its picture was given up on (mp-10, mp-25) -------------------
  local pi = p:info()
  local grid = pi.gridId and hafen.map():grid():get(pi.gridId)
  local gi = grid and grid:info()
  ok("a recorded grid's :info() carries `failed`, so nil-while-rendering is not nil-for-ever",
     (gi ~= nil) and (gi.failed == false), tostring(gi and gi.failed))

  -- ---- a panel's side is bounded, and the ceiling is named (vm-06) -------------------------------
  local wide = hafen.ui():window():title("R15"):size(4000, 60)
  allRefuse("a widget too wide to stand is refused, naming the 2048 a panel is bounded by", {
    {"4000 px", function() hafen.virtual():widget():add(wide, p) end, "at most 2048"},
  })
  wide:destroy()
  return gh
end

-- The ghost's own bounded window: a resource name that resolves to nothing has to be TRIED before the
-- client can give up on it, so `failed` is polled rather than read once, and the run scores what it
-- reached. `drawn` is false the whole way through, which is exactly why the flag had to exist.
local function ghostVerdict(gh, n, done)
  local i = gh and gh:info()
  if (i == nil) or (i.failed == true) or (n >= TRIES) then
    ok("a ghost whose resource cannot load latches :info().failed, where :drawn() only says false",
       (i ~= nil) and (i.failed == true) and (i.drawn == false),
       i and (tostring(i.failed) .. " after " .. n .. " tries"))
    if gh then hafen.virtual():ghost():remove(gh) end
    done()
  else
    hafen.timer():after(0.5, function() ghostVerdict(gh, n + 1, done) end)
  end
end

local function finish()
  say("[manual] label 200 crops, open the profiler and watch for a minute -- expect: this addon's"
      .. " per-frame allocation does not grow with the labels")
  say("[manual] leave a widget:on(\"Draw\") handler that throws running for a minute -- expect: ONE"
      .. " \"surface draw error\" line in the log, not one a frame")
  say("[summary] " .. pass .. " pass, " .. fail .. " fail, 2 manual")
end

local function report()
  flat()
  local g, p = world()
  if not g then
    fail = fail + 1
    say("[fail] the world half could not run -- no character in the world after " .. TRIES .. " tries")
    finish()
    return
  end
  ghostVerdict(inworld(g, p), 1, finish)
end

local function attempt(n)
  if world() or (n >= TRIES) then
    report()
  else
    hafen.timer():after(0.5, function() attempt(n + 1) end)
  end
end

hafen.console():on("tr15", function()
  pass, fail = 0, 0
  -- The typed command runs under the console tree's monitor, and this suite builds widgets, so the
  -- run is deferred to the step exactly as every suite that touches a tree is.
  hafen.timer():after(0, function() attempt(1) end)
end)
