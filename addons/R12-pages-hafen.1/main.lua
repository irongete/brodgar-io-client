-- R12 -- pages: hafen. Every check below asserts, through the API itself, a sentence the block
-- wrote onto a page. It runs only from :tr12 and leaves nothing behind.

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

local function refuses(name, fn, needle)
  local good, err = pcall(fn)
  if good then
    ok(name, false, "<no error>")
  else
    local msg = why(err)
    ok(name, msg:find(needle, 1, true) ~= nil, msg)
  end
end

local function run()
  local json = hafen.json()

  -- json.md: an empty table has no keys, so it satisfies the array rule and is written as [].
  ok("an empty table encodes as []", json:encode({}) == "[]", json:encode({}))

  -- json.md: a key written twice is the last one, silently.
  local dup = json:parse('{"a":1,"a":2}').a
  ok("a duplicate object key is last-wins", dup == 2, dup)

  -- json.md: an integral value wider than 32 bits stays a float, and survives the round trip.
  local wide = json:encode(json:parse('{"n":2147483648}'))
  ok("a wide integral number survives parse and encode", wide == '{"n":2147483648}', wide)

  -- json.md: encode is strict where the store's own flush is forgiving.
  refuses("encode refuses a table cycle", function()
    local t = {}
    t.self = t
    return json:encode(t)
  end, "cycle")

  -- sound.md: the key is read with its surrounding whitespace trimmed off.
  local snd = hafen.sound()
  ok("a sound name is trimmed to one interned Sound",
     snd:get(" sfx/msg ") == snd:get("sfx/msg"), tostring(snd:get(" sfx/msg ")))
  refuses("a sound name of nothing but spaces is refused",
          function() return snd:get("   ") end, "resource name is empty")

  -- timer.md: a period of 0 or less reads as 0, and :repeats() is what tells the two apart.
  local t = hafen.timer():every(-1, function() end)
  local iv, rp = t:interval(), t:repeats()
  ok("a negative period clamps to 0 and still repeats", (iv == 0) and (rp == true),
     tostring(iv) .. "/" .. tostring(rp))

  -- timer.md: the snapshot carries due only while the timer is alive.
  t:cancel()
  local snap = t:info()
  ok("a dead timer's snapshot drops due and keeps the rest",
     (snap.due == nil) and (snap.alive == false) and (snap.repeats == true) and (snap.interval == 0),
     tostring(snap.due) .. "/" .. tostring(snap.alive))

  -- runtime.md: the string metatable is shared and the sandbox does not wall it off.
  local smt = getmetatable("")
  ok("the string metatable is reachable from an addon",
     (smt ~= nil) and (smt.__index ~= nil), tostring(smt))

  -- locale.md: nothing records until :install(), and :install() with no catalogue is refused.
  refuses("installing with no catalogue names :load(doc)",
          function() return hafen.locale():install() end, "load(doc)")

  -- client/keybindings.md: :info() is nil for an id nothing has declared.
  local b = hafen.client():options():keybindings():binding():get("r12-nothing-declares-this")
  ok("an undeclared binding has no snapshot", b:info() == nil, tostring(b:info()))

  -- time.md: every verb is a read, so an argument raises.
  refuses("a time verb refuses an argument",
          function() return hafen.time():clock(1) end, "takes no arguments")

  -- selectors.md: a site role is a valid name that matches no widget, and says so with a nil selector.
  local role = hafen.ui():role()
  local site, wdg = role:get("checkbox"):selector(), role:get("button"):selector()
  ok("a site role has no selector and a widget role is its own",
     (site == nil) and (wdg == "button"), tostring(site) .. "/" .. tostring(wdg))

  say("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("tr12", function()
  pass, fail = 0, 0
  run()
end)
