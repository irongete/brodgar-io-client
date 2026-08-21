-- 086.6 -- a setting is an object. Self-checking suite.
--
-- The last of the plain-table handles. hafen.client():options() had no metatable AT ALL, so
-- opts:vidoe() read nil and failed one call later as "attempt to call a nil value" naming nothing;
-- the six panels, the profiling handle, a scope and a standing entity refused a typo but were still
-- writable from Lua and printed as table: 0x...; an HTTP request was open on both counts.
--
-- THE CLAIM IS THAT EVERY ONE OF THEM IS NOW ONE SHAPE: userdata with a closed vocabulary and a
-- __tostring that names the thing. A typo raises, a write is refused, tostring reads, and everything
-- that already held -- 085's identity, every read, every refusal beside the rewritten lines -- holds.

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

-- One verdict line per claim, scored -- the shape is eleven handles' worth of the same sentence.
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

local GHOST = "gfx/terobjs/arch/logcabin"
local URL   = "http://127.0.0.1:9/t086"

-- ---- the gap nobody has seen ---------------------------------------------------------------------------
--
-- 084 gave the six panels a closed vocabulary and left the handle that HANDS THEM OUT bare: it was a
-- plain table with no metatable, so a misspelt panel read nil in silence.
local function gapSection()
  local msg = said(function() return hafen.client():options():vidoe() end)
  check(has(msg, ":video()") and has(msg, ":keybindings()"),
        "opts:vidoe() raises naming the panels instead of reading nil", msg or "<no error>")
end

-- ---- everything this task holds --------------------------------------------------------------------------
--
-- Built once and handed to every section, so the ghost and the request are made and ended exactly once.
local function handles()
  local opts = hafen.client():options()
  local h = {
    opts = opts,
    iface = opts:interface(), video = opts:video(), audio = opts:audio(),
    camera = opts:camera(), client = opts:client(), keys = opts:keybindings(),
    prof = hafen.client():profiling(),
  }
  h.scope = h.prof:scope("t086")
  -- Against a port nothing listens on, and cancelled below before the tick that would send it.
  local ok, req = pcall(function() return hafen.http():get(URL) end)
  h.req = ok and req or nil
  -- The one handle of the eleven that needs the world. Its absence costs the run two scored points and
  -- a [manual] line saying so, rather than the whole verdict.
  local s = hafen.session():current()
  if s ~= nil then
    local ok, g = pcall(function() return hafen.vr():ghost():add(GHOST, s:player():gob():position()) end)
    h.ghost = ok and g or nil
  end
  return h
end

-- ---- the shape -------------------------------------------------------------------------------------------
--
-- Eleven handles, one sentence: each is userdata, and each prints as something a log line can use.
local function shapeSection(h)
  local want = {
    {h.opts,   "Options"},
    {h.iface,  "Options(interface)"},
    {h.video,  "Options(video)"},
    {h.audio,  "Options(audio)"},
    {h.camera, "Options(camera)"},
    {h.client, "Options(client)"},
    {h.keys,   "Options(keybindings)"},
    {h.prof,   "Profiling"},
    {h.scope,  "ProfScope(t086)"},
    {h.req,    "Request(GET " .. URL .. ")"},
  }
  if h.ghost then want[#want + 1] = {h.ghost, "Ghost(" .. GHOST .. ")"} end
  local hits, saw = 0, {}
  for _, w in ipairs(want) do
    local printed = tostring(w[1])
    if (type(w[1]) == "userdata") and (printed == w[2]) then
      hits = hits + 1
    else
      saw[#saw + 1] = type(w[1]) .. " " .. printed
    end
  end
  scored(hits, #want, "every handle is userdata printing what it is",
         (#saw > 0) and table.concat(saw, " | ") or "-")
end

-- ---- identity, and the reads -------------------------------------------------------------------------------
--
-- 085 minted these once per addon and handed them back by identity; the conversion holds what it held.
local function identitySection(h)
  local hits, saw = 0, {}
  local function one(ok, got)
    if ok then hits = hits + 1 end
    saw[#saw + 1] = tostring(got)
  end
  one(hafen.client():options() == h.opts, hafen.client():options())
  one(h.opts:video() == h.video, h.opts:video())
  one(hafen.client():profiling() == h.prof, hafen.client():profiling())
  scored(hits, 3, "the same handle every time, as 085 made it", table.concat(saw, " "))

  hits, saw = 0, {}
  one(type(h.video:fpsLimit()) == "number", h.video:fpsLimit())
  one(type(h.iface:scale()) == "number", h.iface:scale())
  one(type(h.camera:invertHorizontal()) == "boolean", h.camera:invertHorizontal())
  one(type(h.client:profiling()) == "boolean", h.client:profiling())
  one(h.scope:name() == "t086", h.scope:name())
  scored(hits, 5, "the reads answer exactly as they did", table.concat(saw, " "))
end

-- ---- what being userdata buys --------------------------------------------------------------------------------
--
-- The write that used to be legal on every one of these, and is what "an addon can break its own
-- teardown" meant: with a table, opts.video = nil deleted the addon's own way to the panel.
local function closedSection(h)
  local hits, saw = 0, {}
  local function refuses(fn, still)
    local msg = said(fn)
    if has(msg, "userdata") and still() then hits = hits + 1 end
    saw[#saw + 1] = tostring(msg)
  end
  refuses(function() h.opts.video = nil end, function() return h.opts:video() == h.video end)
  refuses(function() h.req.cancel = nil end, function() return h.req:timeout() ~= nil end)
  local of = 2
  if h.ghost then
    of = 3
    refuses(function() h.ghost.tint = nil end, function() return h.ghost:exists() end)
  end
  scored(hits, of, "a handle is not writable from Lua", table.concat(saw, " | "))
end

-- ---- the request ---------------------------------------------------------------------------------------------
--
-- Made against a port nothing listens on and cancelled before the tick that would send it, so the shape
-- is asserted without a network. It gained a vocabulary here: it had no metatable at all either.
local function requestSection(h)
  local hits, saw = 0, {}
  local function one(ok, got)
    if ok then hits = hits + 1 end
    saw[#saw + 1] = tostring(got)
  end
  one(h.req:timeout() == 10000, h.req:timeout())
  one(h.req:header("X-Test", "1") == h.req, "chained")
  one(h.req:header("x-test") == "1", h.req:header("x-test"))
  one(h.req:timeout(1000):timeout() == 1000, h.req:timeout())
  local typo = said(function() return h.req:cancle() end)
  one(has(typo, ":cancel()"), typo)
  h.req:cancel()
  one(tostring(h.req) == "Request(GET " .. URL .. ", cancelled)", tostring(h.req))
  scored(hits, 6, "the request answers for itself, and a typo on it raises", table.concat(saw, " "))
end

-- ---- a saved variable ------------------------------------------------------------------------------------------
--
-- A handle never round-tripped: an options handle was an empty table, so it was carried and written as {}
-- and read back a week later as nothing at all. It is refused now, and the refusal names the path.
local function storeSection(h)
  local st = hafen.store():get("handles")
  st.h = h.opts
  local msg = said(function() hafen.store():flush() end)
  st.h = nil
  -- The refusal itself goes on the verdict line, so the run shows what an addon that stored one is told.
  check(has(msg, "userdata") and has(msg, "\"handles\".h"),
        "a handle in a saved variable is refused: " .. tostring(msg and msg:match("^[^,]+")),
        msg or "<no error>")
end

-- ---- the refusal that was already there ---------------------------------------------------------------------
--
-- A range refusal sits inside an option's own write, one line from everything this task rewrote.
local function refusalSection(h)
  local msg = said(function() return h.audio:masterVolume(2) end)
  check(has(msg, "0.0 and 1.0"), "opts:audio():masterVolume(2) still names the range", msg or "<no error>")
end

local function run()
  pass, fail, manual = 0, 0, 0
  section("gap", gapSection)
  local h = section("handles", handles)
  if h then
    if h.ghost == nil then
      manual = manual + 1
      hafen.log():write("[manual] no character is logged in -- run :t086-6 in the world"
        .. " -- expect: a Ghost in the shape and the write checks, each scored one higher")
    end
    section("shape", shapeSection, h)
    section("identity", identitySection, h)
    section("closed", closedSection, h)
    section("request", requestSection, h)
    section("store", storeSection, h)
    section("refusal", refusalSection, h)
    if h.ghost then hafen.vr():ghost():remove(h.ghost) end
    if h.req then pcall(function() h.req:cancel() end) end
  end
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():on("t086-6", run)               -- the only way in: a suite does not start itself
