-- R14 -- low drift: foundation, infrastructure, media. Every check asserts through the API the block
-- changed. It runs only from :tr14 and leaves nothing behind: nothing here writes a setting, a file or
-- a saved variable, because every write it makes is one the client refuses.

local pass, fail = 0, 0

-- ...and the write's own return is checked as it goes: hafen.log():write chains, which is the one
-- thing about the log a program can read back at all, and it costs no line of its own to ask.
local chained = true
local function say(s) chained = chained and (hafen.log():write(s) == hafen.log()) end

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

-- Did fn refuse, and did it say why? Answers nil when it did, and what went wrong when it did not,
-- so a run of refusals can be scored on one line and still name the one that broke.
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

local function run(s)
  local face = hafen.font():get("serif")

  -- ---- the mechanism: Args.bool, at the doors a toboolean() used to sit at --------------------
  -- Each door is given "no" and 0, which are both TRUE in Lua and are the two values that used to
  -- turn a setting ON. Every one must refuse by naming the type.
  local doors = {
    {"font bold",   function(v) face:derive():bold(v) end},
    {"font italic", function(v) face:derive():italic(v) end},
    {"font aa",     function(v) face:derive():aa(v) end},
    {"virtual",     function(v) hafen.virtual():visible(v) end},
  }
  local made, row = pcall(function()
    return hafen.client():options():addon():boolean("r14probe"):label("R14 probe"):default(false):add()
  end)
  if made and row then doors[#doors + 1] = {"an option row", function(v) row:value(v) end} end
  if s then
    doors[#doors + 1] = {"snapAngle fine", function(v) s:world():snapAngle(0, v) end}
    local g = s:player():gob()
    if g and g:exists() then doors[#doors + 1] = {"gob visible", function(v) g:visible(v) end} end
  end
  local n, total, first = 0, 0, nil
  for _, d in ipairs(doors) do
    for _, bad in ipairs({"no", 0}) do
      total = total + 1
      local why_ = refused(d[1] .. "(" .. tostring(bad) .. ")", function() d[2](bad) end,
                           "must be true or false")
      if why_ then first = first or why_ else n = n + 1 end
    end
  end
  ok("every boolean door refuses a value that is not one (" .. n .. "/" .. total .. ")",
     (total >= 8) and (n == total), first or ("only " .. total .. " doors reachable"))

  -- ...the same doors take a real boolean, so the check is a check and not a wall; and the seven
  -- client-option doors are protected, so an addon that declared nothing meets the KEY refusal
  -- first, which is the gate order those doors promise.
  local took = pcall(function()
    face:derive():bold(true):italic(false):aa(true)
    hafen.virtual():visible(true)
  end)
  local gated = refused("video:shadows", function() hafen.client():options():video():shadows("no") end,
                        "client.settings")
  ok("a real boolean is taken, and a protected option refuses by key first", took and (gated == nil),
     took and gated or "the true/false write was refused")

  -- ...and a CALLBACK's answer still keeps Lua's own truth, which is the other half of the split:
  -- a filter ending in a string is an ordinary filter, and refusing it would be refusing Lua.
  ok("a filter keeps Lua's own truth",
     hafen.font():count(function(f) return f:family() end) == 4,
     tostring(hafen.font():count(function(f) return f:family() end)))

  -- ---- json: the keys the writer will name, the numbers it writes, the grammar it reads --------
  allRefuse("encode names a key JSON cannot hold, and two keys that spell one name", {
    {"a table key", function() return hafen.json():encode({[{}] = 1}) end, "cannot be a JSON key"},
    {"1 and \"1\"", function() return hafen.json():encode({[1] = "a", ["1"] = "b"}) end,
     "same JSON name"},
  })
  local n15 = hafen.json():encode({n = 1e15})
  allRefuse("parse refuses by JSON's own grammar, not by what a number scanner takes", {
    {"\\uZZZZ", function() return hafen.json():parse('"\\uZZZZ"') end, "four hex digits"},
    {"01", function() return hafen.json():parse("01") end, "JSON:"},
  })
  ok("an integral number is written as digits, however wide", n15 == '{"n":1000000000000000}', n15)

  -- ---- the asset loader: what it says about a URL, and the snapshots it now answers -------------
  local img, dat = hafen.asset():get("probe.png"), hafen.asset():get("probe.txt")
  local ii, di = img:info(), dat:info()
  local url = refused("a URL", function() return hafen.asset():get("https://example.com/x.png") end,
                      "is a URL")
  ok("a URL is named for what it is, and an image and a data file answer :info()",
     (url == nil) and (ii.width == 1) and (ii.height == 1) and (di.bytes == #dat:text()),
     url or (tostring(ii.width) .. "x" .. tostring(ii.height) .. " / " .. tostring(di.bytes)))

  -- ---- a font handle: its snapshot and its size cap ----------------------------------------------
  local fi = face:derive():size(12):bold(true):info()
  local big = refused("size 20000", function() face:derive():size(20000) end, "must be at most")
  ok("a font handle answers :info(), and is not derived at 20000 px",
     (big == nil) and (fi.type == "variant") and (fi.size == 12) and (fi.bold == true)
       and (fi.family ~= nil),
     big or (tostring(fi.type) .. "/" .. tostring(fi.size) .. "/" .. tostring(fi.family)))

  -- ---- the game clock as ONE reading --------------------------------------------------------------
  local ti = hafen.time():info()
  ok("hafen.time():info() agrees with the verbs it summarises",
     (ti ~= nil) and (ti.clock == hafen.time():clock()) and (ti.night == hafen.time():night())
       and (ti.season == hafen.time():season()), tostring(ti and ti.clock))

  -- ---- the sound collection is a noun now, and the old word names it -------------------------------
  local moved = refused("hafen.sound():playing", function() return hafen.sound():playing() end,
                        "hafen.sound():sounding(filter)")
  ok("hafen.sound():playing names its replacement, and :sounding() is the collection",
     (moved == nil) and (hafen.sound():sounding():count() >= 0), moved)

  -- ---- a hotkey's name is checked where it is written, not where the user assigns a key -------------
  local keys = hafen.client():options():keybindings()
  allRefuse("a hotkey name is refused empty and refused too long for the preference store", {
    {"empty", function() keys:on("", function() end) end, "name is empty"},
    {"80 chars", function() keys:on(string.rep("x", 80), function() end) end, "preference store takes"},
  })

  -- ---- the profiler's one argument -------------------------------------------------------------------
  allRefuse("p:history(n) refuses a negative", {
    {"-1", function() return hafen.client():profiling():history(-1) end, "cannot be negative"},
  })

  -- ---- the log and the console, which nothing named in the corpus had ever proved ------------------
  local logbad = refused("write(nil)", function() return hafen.log():write(nil) end, "must not be nil")
  local taken = refused("on(\"lua\")", function() hafen.console():on("lua", function() end) end,
                        "reserved engine command")
  ok("the log chains and refuses nil, and the console holds this suite's own command",
     chained and (logbad == nil) and (taken == nil) and (hafen.console():count() >= 1),
     logbad or taken or ("chained=" .. tostring(chained)
                         .. " commands=" .. tostring(hafen.console():count())))

  say("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("tr14", function()
  pass, fail = 0, 0
  -- The typed command runs under the console tree's monitor; an option row is a tree write, so the
  -- run is deferred to the step like every suite that touches one.
  hafen.timer():after(0, function() run(hafen.session():current()) end)
end)
