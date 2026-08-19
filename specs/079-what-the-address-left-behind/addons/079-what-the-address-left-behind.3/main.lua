-- 079.3 — a gob is one object, and the click belongs to a character. Self-checking suite.
--
-- The claim: a Gob names the SERVER's object and nothing else. So two lookups of one id are the same
-- handle however they were reached -- through the character on screen, through a character named by its
-- account, through any character that can see it -- and every read on it answers the same, because it
-- reads the object rather than one character's copy of it. Which characters hold it is gob:sessions(),
-- a live read of the object caches and an empty array for an object nobody has.
--   And the one verb that ACTED does not survive that: a click is something a character does, so it is
-- session:world():click(gob, button, mods). This addon declares NO permission, so both refusals are
-- reachable -- the retired spelling naming its replacement, and the replacement naming the key.

local pass, fail, manual = 0, 0, 0

local function log(s) hafen.log():write(s) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every `want` in the message.
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or tostring(err):gsub("^.-%.lua:%d+:%s*", "")
  local said = not ok
  for _, want in ipairs({...}) do
    said = said and (err:find(want, 1, true) ~= nil)
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

-- nil == nil counts as equal: a read that is absent for both doors agrees just as much as one that is not.
local function same(a, b)
  return a == b
end

-- The durable form of a Position, as a comparable string; "nil" for no position at all.
local function place(p)
  if p == nil then return "nil" end
  local i = p:info()
  if i == nil then return "no-durable-form" end
  return tostring(i.gridId) .. "@" .. string.format("%.3f,%.3f", i.x, i.y)
end

-- ---------------------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if (s == nil) or (s:player():gob() == nil) then
    log("[fail] a character must be in world to run this -- got: no drawn character with a gob")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The subject: this character's own body, which is always loaded and always has a position.
  local gob = s:player():gob()
  local id = gob:id()

  -- INTERNING, under the new key. Two lookups through one door, the player's own door, and a door
  -- addressed by ACCOUNT rather than by ":current()" -- all one value.
  local byId = s:world():gob():get(id)
  local named = hafen.session():get(s:user()):world():gob():get(id)
  check((byId == gob) and (named == gob) and (byId == named),
        "every door to one id hands back the SAME Gob (:get, :player():gob(), a session named by account)",
        tostring(byId) .. " / " .. tostring(gob) .. " / " .. tostring(named))

  -- SAMENESS. Each read answers, and answers the same thing through a session named by account as it does
  -- through the drawn one -- and the values are the object's own rather than a shape that merely exists.
  local reads = { id = same(named:id(), id), exists = named:exists() and gob:exists(),
                  name = same(named:name(), gob:name()), health = same(named:health(), gob:health()),
                  facing = same(named:facing(), gob:facing()),
                  position = (place(named:position()) == place(gob:position())) }
  local bad = {}
  for k, v in pairs(reads) do
    if not v then bad[#bad + 1] = k end
  end
  check((#bad == 0) and (type(gob:name()) == "string") and (place(gob:position()) ~= "nil"),
        "id, exists, name, health, facing and position all answer, and answer the same either way ("
        .. tostring(gob:name()) .. " at " .. place(gob:position()) .. ")",
        (#bad > 0) and table.concat(bad, ",") or "name/position not readable")

  -- ...and the SNAPSHOT agrees with the readers it freezes, which is what says both are reading one object.
  local info = gob:info()
  check((info ~= nil) and (info.id == id) and same(info.name, gob:name()) and same(info.hp, gob:health()),
        "gob:info() agrees with :id(), :name() and :health()",
        (info == nil) and "nil" or (tostring(info.id) .. "/" .. tostring(info.name)))

  -- OVERLAY still answers off the handle, and it is a collection: a key nobody attached is nil, and
  -- listing it never throws. (Reading it is all this does -- the suite attaches nothing.)
  local ovOk, ovAns = pcall(function()
    local col = gob:overlay()
    return (col:get("079-3-nothing-attached") == nil) and (type(col:list()) == "table")
  end)
  check(ovOk and (ovAns == true),
        "gob:overlay() answers a collection, and a key nobody attached reads nil",
        tostring(ovAns))

  -- gob:sessions() -- WHO holds it, live, and the session it was found through is one of them. Every
  -- session that lists it must also hand back the very same handle: one object, one value, every door.
  local who, mine, agree = gob:sessions(), false, true
  local names = {}
  for _, m in ipairs(who) do
    names[#names + 1] = m:user()
    if m:user() == s:user() then mine = true end
    if m:world():gob():get(id) ~= gob then agree = false end
  end
  check((#who > 0) and mine and agree,
        "gob:sessions() lists the session it was found through, and each lists the same handle -- "
        .. #who .. ": " .. table.concat(names, ", "),
        tostring(#who) .. " sessions, mine=" .. tostring(mine) .. " agree=" .. tostring(agree))

  -- An object NOBODY holds: never nil from :get, empty from :sessions(), nil from every read, and no throw.
  local ghost = s:world():gob():get(987654321987)
  local sess = ghost:sessions()
  check((ghost ~= nil) and (not ghost:exists()) and (type(sess) == "table") and (#sess == 0)
        and (ghost:name() == nil) and (ghost:position() == nil) and (ghost:distance() == nil),
        "an id no character holds: :get is still a Gob, :sessions() is EMPTY (not nil), the reads are nil",
        tostring(ghost) .. " exists=" .. tostring(ghost:exists()) .. " #sessions=" .. tostring(#sess))

  -- THE RETIRED SPELLING. A Gob has nobody to send a click as, so reading `click` off one throws naming
  -- where the verb went -- at the line that wrote it, rather than "attempt to call a nil value" later.
  refuses("gob:click is retired, naming s:world():click(gob, ...) as its replacement",
          function() return gob:click(1) end, "world():click(gob")

  -- THE REPLACEMENT, ungranted. The gate is the verb's first statement, so this refuses on the KEY rather
  -- than on the gob, and names both the verb and the key -- which is unchanged, because a key names the
  -- action and the action is still clicking an object.
  refuses("s:world():click(gob) refuses without the key, naming the verb and \"gob.click\"",
          function() return s:world():click(gob) end, "session:world():click", "gob.click")

  -- ...and the argument refusal is behind the gate, so a bad target cannot be reached without the key
  -- either: the same message, which is what proves the gate runs FIRST.
  refuses("the gate runs before the argument: a non-Gob target still refuses on the key",
          function() return s:world():click(42) end, "gob.click")

  manualCheck("with two characters standing TOGETHER, run this, then tab to the other and run it again",
              "the gob:sessions() line names BOTH accounts on both runs -- one body, seen by two"
              .. " characters, is one object with two holders, not two objects")

  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t079-3", run)   -- the only way in: a suite does not start itself
