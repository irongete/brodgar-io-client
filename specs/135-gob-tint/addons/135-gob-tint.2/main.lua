-- 135.2 — the page a gob's look is on. Self-checking suite: what look.md promises, read back.
-- Run with :t135. Tints the player's own gob and LEAVES it tinted, for the two manual checks.

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

local function colour(c)
  if type(c) ~= "table" then return tostring(c) end
  return ("r=%s g=%s b=%s a=%s [1]=%s"):format(tostring(c.r), tostring(c.g), tostring(c.b), tostring(c.a), tostring(c[1]))
end

local function run()
  local s = hafen.session():current()
  local me = s and s:player():gob()
  if not me then
    check(false, "the player's own gob is loaded", "no session in the world")
    summary()
    return
  end

  -- the keyed read shape the page promises: .r answers, [1] is nil
  me:tint{255, 0, 0, 96}
  local c = me:tint()
  check(type(c) == "table" and c.r == 255 and c.g == 0 and c.b == 0 and c.a == 96 and c[1] == nil,
        "the read is keyed: .r answers and [1] is nil", colour(c))

  -- tint(nil) is legal, and reads nil
  local ok, err = pcall(function() me:tint(nil) end)
  check(ok and me:tint() == nil, "tint(nil) is legal and reads nil back",
        ok and colour(me:tint()) or tostring(err))

  -- the refusal names both spellings of a colour
  refuses("a non-colour is refused naming the positional spelling", function() me:tint("red") end, "{200, 210, 220}")
  refuses("a non-colour is refused naming the keyed spelling", function() me:tint("red") end, "r = 200, g = 210, b = 220")

  -- the write reaches every character: read it back through each session's own world
  me:tint{255, 0, 0, 96}
  local id = me:id()
  local sessions = me:sessions():list()
  local seen, agree = 0, 0
  for _, sess in ipairs(sessions) do
    seen = seen + 1
    local t = sess:world():gob():get(id):tint()
    if type(t) == "table" and t.r == 255 and t.a == 96 then agree = agree + 1 end
  end
  check(seen >= 1 and agree == seen,
        "the tint reads back r=255, a=96 through every session that can see the object (" .. tostring(seen) .. " logged in)",
        tostring(agree) .. " of " .. tostring(seen))

  -- the gob is left red-washed on purpose: the two manual checks below read that
  manualCheck("log a second character in near your first one, after this run tinted it",
              "it arrives already red-washed in the second character's view")
  manualCheck(":reload the addon layer",
              "every object this run tinted is drawn plain again, in every character's view")
  summary()
end

hafen.console():on("t135", run)   -- the only way in: a suite does not start itself
