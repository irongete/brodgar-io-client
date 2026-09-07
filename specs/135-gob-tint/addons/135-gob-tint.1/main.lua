-- 135.1 — gob:tint(c): a colour laid over a game object. Self-checking suite.
-- Run with :t135. Drives the player's own gob (the one always loaded), then tints three objects for 8 s.

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
  local world = s:world():gob()

  -- the read/write pair, on the one gob always loaded
  check(me:tint() == nil, "tint() reads nil bare", colour(me:tint()))

  local back = me:tint{255, 0, 0, 96}
  local c = me:tint()
  check(back == me and type(c) == "table" and c.r == 255 and c.g == 0 and c.b == 0 and c.a == 96,
        "tint{255, 0, 0, 96} hands the same Gob back and reads r=255, a=96 keyed",
        tostring(back) .. " " .. colour(c))

  me:tint{0, 255, 0}
  local c2, info = me:tint(), me:info()
  check(type(c2) == "table" and c2.a == 255 and type(info.tint) == "table" and info.tint.g == 255,
        "positional {0, 255, 0} reads a=255 and info().tint.g == 255",
        colour(c2) .. " / info " .. colour(info.tint))

  me:tint(nil)
  check(me:tint() == nil and me:info().tint == nil,
        "tint(nil) reads nil and info() carries no tint",
        colour(me:tint()) .. " / info " .. colour(me:info().tint))

  -- the refusal, one text for every non-colour
  refuses("tint(1) is refused naming the colour grammar", function() me:tint(1) end, "a colour is a table")
  refuses("tint(\"red\") is refused naming the colour grammar", function() me:tint("red") end, "a colour is a table")
  refuses("tint(true) is refused naming the colour grammar", function() me:tint(true) end, "a colour is a table")
  refuses("tint{\"x\"} is refused naming the colour grammar", function() me:tint{"x"} end, "a colour is a table")

  -- it composes with the other two writes
  me:tint{255, 0, 0, 96}:scale(2):visible(false):visible(true)
  local c3 = me:tint()
  check(type(c3) == "table" and c3.r == 255 and c3.a == 96 and me:scale() == 2 and me:visible() == true,
        "tint(c):scale(2):visible(false):visible(true) still reads the colour and scale() == 2",
        colour(c3) .. " scale=" .. tostring(me:scale()) .. " visible=" .. tostring(me:visible()))
  me:scale(1)
  me:tint(nil)

  -- a gob nobody holds: the read is nil and the write is taken and does nothing
  local gone = world:get(2 ^ 40)
  local wrote, err = pcall(function() gone:tint{1, 2, 3} end)
  check(gone:tint() == nil and wrote,
        "get(2^40) reads nil and takes a write without raising",
        colour(gone:tint()) .. " / write: " .. (wrote and "ok" or tostring(err)))

  -- what the eye checks: three objects for 8 seconds, then plain again
  me:tint{255, 0, 0, 96}
  local tinted = { me }

  local count = {}
  for _, g in ipairs(world:list()) do
    local n = g:name()
    if n and not g:player() then count[n] = (count[n] or 0) + 1 end
  end
  local twin = world:nearest(function(g)
    local n = g:name()
    return n ~= nil and not g:player() and (count[n] or 0) >= 2
  end)
  if twin then
    twin:tint{0, 255, 0, 96}
    tinted[#tinted + 1] = twin
  end

  local hurt = world:nearest(function(g)
    local h = g:health()
    return h ~= nil and h < 1
  end)
  if hurt then
    hurt:tint{0, 0, 255, 96}
    tinted[#tinted + 1] = hurt
  end

  hafen.timer():after(8, function()
    for _, g in ipairs(tinted) do g:tint(nil) end
  end)

  manualCheck("your character wears a red wash for 8 seconds and its shading stays",
              "lit and shaded sides still distinct, not a flat red silhouette")
  manualCheck("of two identical objects side by side, one is tinted green for 8 seconds ("
              .. (twin and (twin:name() .. " #" .. tostring(twin:id())) or "none with two copies in view") .. ")",
              "only that one")
  manualCheck("the damaged object, if one was in view, is tinted blue for 8 seconds ("
              .. (hurt and (hurt:name() .. " #" .. tostring(hurt:id())) or "none in view") .. ")",
              "its cracks and red damage wash still show under your colour")
  summary()
end

hafen.console():on("t135", run)   -- the only way in: a suite does not start itself
