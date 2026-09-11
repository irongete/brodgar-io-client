-- 138.1 -- a numeric string is a string, and a number is not one: the type rule at the doors.
--
-- conventions.md ("A number is not a string, and a numeric string is not a number") states that what is
-- checked is an argument's TYPE, never what Lua would convert it to. In LuaJ "42" answers isnumber() and
-- 42 answers isstring(), so a door written with either predicate broke the rule in one direction or the
-- other. This suite drives both directions through the API at the doors an addon reaches without a
-- permission: a numeric STRING must travel as the string it is, all the way to the refusal that names it
-- (or be accepted, where a name is all a key has to be), and a NUMBER must be refused as the wrong kind.
--
-- Two doors are left to reading, not to this suite: ov:text(42) on a gob's overlay needs an object in
-- view, and slot:res(42) is protected -- a suite proves a protected verb's effect or its gate, never both.
--
-- :t138 runs it. Nothing here starts on its own, and nothing it touches survives the run: the one painter
-- it adds is removed, the one selector watch it opens is ended, and a catalogue that fails to parse is
-- never loaded.

local pass, fail = 0, 0

local function out(line) hafen.log():write(line) end

-- LuaJ prefixes an error raised from Java as "@chunk.lua:189 msg" (a SPACE, no second colon), and one
-- raised by Lua as "chunk.lua:189: msg"; strip either so a needle is matched against the message alone.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function verdict(what, ok, got)
  if ok then pass = pass + 1 else fail = fail + 1 end
  out((ok and "[pass] " or "[fail] ") .. what .. (ok and "" or (" -- got: " .. tostring(got))))
end

-- The call must FAIL, its message must carry `needle`, and must not carry `absent` (the wrong refusal).
local function refuses(what, fn, needle, absent)
  local ok, err = pcall(fn)
  if ok then return verdict(what, false, "<no error>") end
  local msg = why(err)
  local right = msg:find(needle, 1, true) ~= nil and (absent == nil or msg:find(absent, 1, true) == nil)
  verdict(what, right, msg)
end

-- The call must SUCCEED, and `test(result)` must hold.
local function accepts(what, fn, test)
  local ok, res = pcall(fn)
  if not ok then return verdict(what, false, why(res)) end
  verdict(what, test == nil or test(res), tostring(res))
end

local function run()
  pass, fail = 0, 0
  local s = hafen.session():current()
  local noop = function() end

  -- A numeric string is a string: it reaches the refusal that names it, or is taken as the name it is.
  refuses("hafen.font():get(\"42\") is refused as a font NAME, not as a number",
    function() return hafen.font():get("42") end, 'get("42")', "not a number")
  refuses("hafen.asset():get(\"42\") is refused as a missing FILE, not as a number",
    function() return hafen.asset():get("42") end, "no such file '42'", "not a number")
  accepts("hafen.ui():overlay():add(\"42\") takes the key, and :get(\"42\") answers it",
    function()
      local ov = hafen.ui():overlay():add("42")
      local same = hafen.ui():overlay():get("42") == ov
      hafen.ui():overlay():remove("42")
      return same and hafen.ui():overlay():get("42") == nil
    end, function(r) return r == true end)
  refuses("hafen.event():on(\"42\", fn) is refused as an unknown KEY naming it",
    function() return hafen.event():on("42", noop) end, "unknown event '42'", "expects (string, function)")
  accepts("hafen.console():get(\"42\") is nil -- a name never registered, not a wrong type",
    function() return hafen.console():get("42") end, function(r) return r == nil end)
  if s then
    refuses("s:ui():on(\"42\", \"Added\", fn) is refused as a bad SELECTOR naming it",
      function() return s:ui():on("42", "Added", noop) end, 'bad selector "42"', "must be a string")
  else
    verdict("s:ui():on(\"42\", \"Added\", fn) is refused as a bad SELECTOR naming it", false,
      "no character in world -- run :t138 logged in")
  end
  refuses("locale:load{text = {[\"42\"] = {}}} is refused as an unknown SURFACE, not as a list",
    function() return hafen.locale():load({ text = { ["42"] = {} } }) end,
    '"42" is not a surface', "keyed, not a list")

  -- A number is not a string: refused as the wrong kind, before anything is looked up or sent.
  refuses("hafen.font():get(42) is refused as a number",
    function() return hafen.font():get(42) end, "not a number")
  refuses("hafen.asset():get(42) is refused as a number",
    function() return hafen.asset():get(42) end, "not a number")
  refuses("hafen.ui():overlay():add(42) is refused: the key must be a string",
    function() return hafen.ui():overlay():add(42) end, "must be a string")
  refuses("hafen.event():on(42, fn) is refused as (string, function)",
    function() return hafen.event():on(42, noop) end, "expects (string, function)")
  refuses("hafen.console():get(42) is refused naming the conversion, not answered nil",
    function() return hafen.console():get(42) end, "tostring(n)")
  if s then
    refuses("s:ui():on(\"*\", 42, fn) is refused: the event must be a string",
      function() return s:ui():on("*", 42, noop) end, "event must be a string")
  else
    verdict("s:ui():on(\"*\", 42, fn) is refused: the event must be a string", false,
      "no character in world -- run :t138 logged in")
  end
  refuses("locale:load{text = {[42] = {}}} is refused: keyed, not a list",
    function() return hafen.locale():load({ text = { [42] = {} } }) end, "keyed, not a list")

  out(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

-- Deferred a tick: a :t<NNN> handler runs under the typed tree's monitor, and the run is kept off it.
hafen.console():on("t138", function() hafen.timer():after(0, run) end)
