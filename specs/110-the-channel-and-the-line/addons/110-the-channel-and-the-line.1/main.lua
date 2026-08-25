-- 110.1 -- the snapshot catalogue, split by subject. Self-checking suite.
--
-- WHAT THIS SHIPS. Nothing in Lua. The catalogue of snapshot shapes became a folder of six pages under
-- one hub, and every inbound link in the tree was re-pointed at it. No name moved and no shape changed.
--
-- HOW IT IS PROVED. A split catalogue can be wrong in exactly one way: a shape, or a field of one, that
-- fell out of the pages in the move, or one that stayed on a page while the client stopped handing it
-- back. Neither is visible in a diff of 350 moved lines. So the field list of every shape a client with
-- one character up can reach without asking the server for anything is TRANSCRIBED here off the new
-- page -- name, type, and whether the page calls it always-present -- and each is walked in BOTH
-- directions against the table the client actually hands over: no field the page does not list, and
-- every field the page calls always-present, at the type the page gives it. The run prints how many
-- shapes it reached and how many fields it read, so a shape that quietly went missing is a number that
-- does not add up rather than a green line.
--
-- Two claims the hub page itself makes are checked beside them: that a snapshot is a COPY, so a second
-- one is a fresh table nothing you did to the first can reach, and that a Position snapshot is the
-- durable form s:world():position(saved) rebuilds a place from.

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

-- ----------------------------------------------------------------------------------------------------
-- The pages, transcribed. {field, type, always-present}
-- ----------------------------------------------------------------------------------------------------

-- types/world.md#session
local SESSION = {
  {"user", "string", true}, {"character", "string", false},
  {"exists", "boolean", true}, {"current", "boolean", true},
}

-- types/world.md#gobinfo
local GOBINFO = {
  {"id", "number", true}, {"x", "number", false}, {"y", "number", false},
  {"angle", "number", true}, {"name", "string", false}, {"isplayer", "boolean", false},
  {"hp", "number", false}, {"moving", "boolean", true}, {"speed", "number", false},
  {"speech", "string", false}, {"icon", "string", false}, {"overlays", "table", false},
}

-- types/world.md#position
local POSITION = {
  {"gridId", "string", true}, {"x", "number", true}, {"y", "number", true},
}

-- types/character.md#wound
local WOUND = {
  {"id", "number", true}, {"name", "string", false}, {"res", "string", false},
  {"severity", "string", false}, {"parentid", "number", true}, {"level", "number", true},
}

-- types/ui.md#pagina
local PAGINA = {
  {"res", "string", true}, {"exists", "boolean", true}, {"addon", "string", false},
  {"name", "string", false}, {"tooltip", "string", false}, {"hotkey", "string", false},
  {"path", "table", false}, {"parent", "string", false}, {"isnew", "boolean", false},
}

-- ----------------------------------------------------------------------------------------------------
-- The walk, both directions.
-- ----------------------------------------------------------------------------------------------------

local reached, unreached, fields = 0, {}, 0

-- Every field of `t` is on the page, at the type the page gives it, and every field the page calls
-- always-present is there. Returns the first disagreement, or nil.
local function walk(t, spec)
  local listed = {}
  for _, f in ipairs(spec) do
    listed[f[1]] = f[2]
    local v = t[f[1]]
    if v == nil then
      if f[3] then
        return "the page calls '" .. f[1] .. "' always-present, and it is absent"
      end
    else
      fields = fields + 1
      if type(v) ~= f[2] then
        return "'" .. f[1] .. "' is a " .. type(v) .. ", the page says " .. f[2]
      end
    end
  end
  for k in pairs(t) do
    if listed[k] == nil then
      return "the table carries '" .. tostring(k) .. "', which no page lists"
    end
  end
  return nil
end

local function shape(name, t, spec)
  if t == nil then
    unreached[#unreached + 1] = name
    return
  end
  reached = reached + 1
  local bad = walk(t, spec)
  check(bad == nil, name .. " is the shape its page lists, field for field", bad)
end

-- ----------------------------------------------------------------------------------------------------

local function body()
  local s = hafen.session():current()
  if s == nil then
    check(false, "a character is up", "no session on screen -- log in and run :t110 again")
    return
  end

  shape("Session", s:info(), SESSION)

  local me = s:player():gob()
  shape("GobInfo", me and me:info(), GOBINFO)

  local p = me and me:position()
  local pinfo = p and p:info()
  shape("Position", pinfo, POSITION)

  local wounds = s:wound():list()
  shape("Wound", wounds[1] and wounds[1]:info(), WOUND)

  local paginae = s:menugrid():list()
  shape("Pagina", paginae[1] and paginae[1]:info(), PAGINA)

  -- The hub page's own claim: a snapshot is a point-in-time COPY, so a second one is a fresh table and
  -- what you did to the first is not in it.
  local a = s:info()
  a.user = "scribbled"
  local b = s:info()
  check((a ~= b) and (b.user ~= "scribbled") and (b.user == s:user()),
        "a snapshot is a copy: the next one is a fresh table",
        tostring(b.user))

  -- ...and the Position page's: the {gridId, x, y} form is what a place is rebuilt from.
  if pinfo ~= nil then
    local back = s:world():position(pinfo)
    local again = back:info()
    check((again ~= nil) and (again.gridId == pinfo.gridId)
            and (math.abs(again.x - pinfo.x) < 0.01) and (math.abs(again.y - pinfo.y) < 0.01),
          "the Position snapshot is the durable form a place is rebuilt from",
          again and (tostring(again.gridId) .. " " .. tostring(again.x) .. "," .. tostring(again.y)))
  end

  local missed = (#unreached == 0) and "" or (" -- not reached: " .. table.concat(unreached, ", "))
  hafen.log():write(("[summary] %d pass, %d fail, %d manual -- %d of 5 shapes, %d fields read%s")
    :format(pass, fail, manual, reached, fields, missed))
end

-- The run is one pcall, so a read that throws becomes one [fail] line and a summary rather than a bare
-- stack trace with no verdict under it.
local function run()
  local ok, err = pcall(body)
  if not ok then
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end
end

hafen.console():on("t110", run)   -- the only way in: a suite does not start itself
