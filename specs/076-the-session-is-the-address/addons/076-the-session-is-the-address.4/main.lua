-- 076.4 - no page teaches an address that is gone. Self-checking suite.
--
-- What it proves: every canonical chain the swept pages now spell is a call the engine answers, so
-- the docs and the engine agree about the spelling. Each check RUNS one page's chain end to end --
-- section, collection, object, verb -- and asserts nothing throws; and the two sections the sweep
-- took out of the docs are called inside pcall, so the sweep cannot have quietly put one back.
--
-- HOW TO RUN IT. `:t076-4`, in the world. Every claim is a call: there is no manual step.

local pass, fail = 0, 0
local log = hafen.log()

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log:write("[pass] " .. what)
  else
    fail = fail + 1
    log:write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

--- Run a page's chain. `fn` answers a why-string ("" when the chain held), or throws -- either way
--- one verdict line names the spelling that was run.
local function chain(what, fn)
  local ok, why = pcall(fn)
  check(ok and (why == ""), what, ok and why or ("threw: " .. tostring(why)))
end

--- The payload the tutorial's `function(s)` handler is handed, captured at load time: the pages
--- teach SessionEnteredWorld handing a Session, and a reload in-world is what fires it.
local entered, enteredWhy = nil, "SessionEnteredWorld has not fired since this addon loaded"
hafen.event():on("SessionEnteredWorld", function(s)
  local ok, why = pcall(function()
    if type(s:user()) ~= "string" then return "s:user() is not a string" end
    if type(s:character()) ~= "string" then return "s:character() is not a string" end
    if s:world() ~= s:world() then return "s:world() is a fresh object every call" end
    return ""
  end)
  entered, enteredWhy = (ok and (why == "")), (ok and why or ("threw: " .. tostring(why)))
end)

local function run()
  pass, fail = 0, 0

  -- The door every swept page now goes through: the section IS the collection, and it is one
  -- object across calls, exactly as conventions.md says a section is.
  local coll = hafen.session()
  local s = coll:current()
  local user = s and s:user()
  check((coll == hafen.session()) and (s ~= nil) and (type(user) == "string") and (user ~= ""),
        "hafen.session() is one object, and :current() names an account (" .. tostring(user) .. ")",
        tostring(user))
  if s == nil then
    log:write("[summary] " .. pass .. " pass, " .. fail
              .. " fail, 0 manual -- log in and run :t076-4 again")
    return
  end

  -- The two spellings the sweep chose between, and the read the tutorial, README and http.md teach.
  chain("hafen.session():get(user) is :current(), and s:character() answers", function()
    if coll:get(user) ~= s then return ":get(user) is not the same ref as :current()" end
    if type(s:character()) ~= "string" then return "s:character() is " .. type(s:character()) end
    return ""
  end)

  -- reading-the-world.md, custom-ui.md, getting-started.md, kin.md, log.md, references.md,
  -- attribution.md, vr/widgets.md: the whole gob collection, reached through the session.
  chain("s:world():gob() -- :count :list :nearest :within :get all answer", function()
    local g = s:world():gob()
    if type(g:count("terobjs/tree")) ~= "number" then return ":count() is not a number" end
    if type(g:list()) ~= "table" then return ":list() is not an array" end
    g:nearest(function(x) return x:isPlayer() end)
    if type(g:within(15)) ~= "table" then return ":within() is not an array" end
    local me = s:player():gob()
    if me == nil then return "s:player():gob() is nil (not in the world)" end
    if g:get(me:id()) ~= me then return ":get(its id) is not s:player():gob()" end
    return ""
  end)

  -- map/README.md, markers.md, drawings.md, overlays.md, grids.md: where a character stands, and
  -- the recorded grid that opens on the other side of the bridge. Nothing here writes a pin.
  local me = s:player():gob()
  local here = me and me:position()
  chain("s:player():gob():position():info() names a grid the map database opens", function()
    if here == nil then return "no position (not in the world)" end
    local i = here:info()
    if (type(i) ~= "table") or (type(i.gridId) ~= "string") then return "info() names no gridId" end
    hafen.map():grid():get(i.gridId)                  -- nil until the file lands; must not throw
    return ""
  end)

  -- reading-the-world.md, grids.md, types.md: terrain and the coordinate spaces, addressed.
  chain("s:world():tile(p), :height(p) and :grid():at(p) answer for that character", function()
    if here == nil then return "no position (not in the world)" end
    local t = s:world():tile(here)
    if (t ~= nil) and (type(t.id) ~= "number") then return "tile() names no id" end
    local h = s:world():height(here)
    if (h ~= nil) and (type(h) ~= "number") then return "height() is not a number" end
    s:world():grid():at(here)
    return ""
  end)

  -- saved-data.md, grids.md, types.md: the durable round trip a stored place is rebuilt through.
  chain("s:world():position(saved) rebuilds the place p:info() wrote down", function()
    if here == nil then return "no position (not in the world)" end
    local back = s:world():position(here:info())
    if back == nil then return ":position(saved) answered nil" end
    local a, b = here:info(), back:info()
    if b == nil then return "the rebuilt place is not durable" end
    if (a.gridId ~= b.gridId) or (math.abs(a.x - b.x) > 1) or (math.abs(a.y - b.y) > 1) then
      return "the round trip moved: " .. a.gridId .. " vs " .. tostring(b.gridId)
    end
    return ""
  end)

  -- ui/mouse.md, ui/pixels.md, vr/ghosts.md, event/streams.md, vr/sprites.md: the three that are
  -- the SCREEN's, which the pages now spell on hafen.session():current() and say why.
  chain("snapPlace, worldToScreen and screenToWorld answer on the drawn session", function()
    if here == nil then return "no position (not in the world)" end
    if s:world():snapPlace(here) == nil then return "snapPlace() answered nil" end
    local pt = s:player():worldToScreen(here)
    if (pt == nil) or (type(pt.x) ~= "number") then return "worldToScreen() named no point" end
    s:world():screenToWorld(pt.x, pt.y, function(p) end)
    return ""
  end)

  -- ui/items.md, ui/widget.md: the cursor is read on the session, and an empty one is nil.
  chain("s:player():hand() reads the cursor, empty or not", function()
    local h = s:player():hand()
    if h == nil then return "" end                    -- nil IS the empty cursor: items.md says so
    if h ~= s:player():hand() then return "hand() is a fresh object every call" end
    h:item()
    return ""
  end)

  -- getting-started.md, README.md, custom-ui.md, saved-data.md: `function(s)` is what they teach.
  check(entered == true, "SessionEnteredWorld hands a Session, as the pages now spell it", enteredWhy)

  -- The hard cut the sweep exists to finish: no page teaches either, and neither answers.
  refuses("hafen.world still refuses naming hafen.session()",
          function() return hafen.world() end, "hafen.session()")
  refuses("hafen.player still refuses naming hafen.session()",
          function() return hafen.player() end, "hafen.session()")
  refuses("hafen.player():name still refuses naming hafen.session()",
          function() return hafen.player():name() end, "hafen.session()")

  log:write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

hafen.slash():register("t076-4", run)   -- the only way in: a suite does not start itself
