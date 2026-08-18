-- 076.3 — the world and the character belong to a session. Self-checking suite.
--
-- What it proves: s:world() and s:player() are minted once per session, so a draw callback
-- allocates nothing; a Gob is interned on the (session, id) PAIR, so the same id read through two
-- sessions is two refs while :id() crosses them; gob:position() hands back a session-free Position
-- that names a grid; the character a login plays is s:character() and nowhere else; and
-- hafen.world / hafen.player refuse naming hafen.session().
--
-- HOW TO RUN IT. `:t076-3`, in the world. Every check is a read, so there is no walk and no timer.
-- With a SECOND session up it scores the cross-session claims too, and says so when there is only
-- one -- so run it once with one character and once with two.

local pass, fail, manual = 0, 0, 0
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

--- The grid a Position names, or nil plus why it named none. A grid id is 64-bit, so it crosses as
--- a decimal STRING, and the x,y beside it are the offset WITHIN that grid rather than :x()/:y().
local function gridOf(p)
  if p == nil then return nil, "no position" end
  local i = p:info()
  if type(i) ~= "table" then return nil, "info() is " .. type(i) end
  if type(i.gridId) ~= "string" then return nil, "gridId is " .. type(i.gridId) end
  if not i.gridId:match("^%-?%d+$") then return nil, "gridId is not decimal: " .. i.gridId end
  if (type(i.x) ~= "number") or (type(i.y) ~= "number") then return nil, "x,y are not numbers" end
  if (i.x < 0) or (i.x > 1100) or (i.y < 0) or (i.y > 1100) then
    return nil, ("offset outside its grid: %.1f, %.1f"):format(i.x, i.y)
  end
  return i, nil
end

--- Another session the client holds, or nil when this is the only one.
local function other(mine)
  for _, s in ipairs(hafen.session():list()) do
    if s:user() ~= mine then return s end
  end
  return nil
end

--- Everything two sessions let us assert, as ONE line: the pair the Gob is interned on, the id that
--- crosses it, the frames that do not, and the two verbs that belong to the screen.
local function crossSession(s, o, user)
  local why = {}
  local mine, theirs = s:player():gob(), o:player():gob()
  if (mine == nil) or (theirs == nil) then
    return nil, ((mine == nil) and user or o:user()) .. " is not in the world yet"
  end

  if o:world() == s:world() then why[#why + 1] = "the two sessions share one world object" end
  if o:player() == s:player() then why[#why + 1] = "the two sessions share one player object" end

  -- The pair: one id, read through two sessions, is two refs -- and :id() is what crosses them.
  local id = mine:id()
  local here, there = s:world():gob():get(id), o:world():gob():get(id)
  if here == theirs then why[#why + 1] = "one ref for two sessions" end
  if here ~= mine then why[#why + 1] = "get(id) is not s:player():gob()" end
  if there:id() ~= id then why[#why + 1] = ":id() disagrees across the two" end

  -- Two sessions' coordinates are relative to where each logged in, so there is no distance.
  local dok, derr = pcall(function() return theirs:distance(mine) end)
  if dok or (tostring(derr):find("different", 1, true) == nil) then
    why[#why + 1] = "distance across sessions was not refused: " .. tostring(derr)
  end

  -- The screen's two verbs: there is one screen, and it is not the background session's.
  if o:player():worldToScreen(theirs:position()) ~= nil then
    why[#why + 1] = "a background session projected a screen point"
  end
  local ook, oerr = pcall(function() return theirs:overlay() end)
  if ook or (tostring(oerr):find("not on screen", 1, true) == nil) then
    why[#why + 1] = "an overlay on a background gob was not refused: " .. tostring(oerr)
  end

  return table.concat(why, "; "), nil
end

local function run()
  pass, fail, manual = 0, 0, 0

  -- The address itself, checked first: everything below is a verb on it.
  local s = hafen.session():current()
  local user = s and s:user()
  check((s ~= nil) and (type(user) == "string") and (user ~= ""),
        "the session on screen is an object naming its account (" .. tostring(user) .. ")",
        tostring(user))
  if s == nil then
    log:write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual
              .. " manual -- log in and run :t076-3 again")
    return
  end

  -- Minted once per session: this is the allocation claim as much as the identity one, because a
  -- draw callback writing s:world():gob():nearest(...) runs 60x a second.
  check(s:world() == s:world(), "s:world() is one object across calls", tostring(s:world()))
  check(s:world():gob() == s:world():gob(), "s:world():gob() is one object across calls",
        tostring(s:world():gob()))

  -- Composition: the Player is minted once too, and the character's own Gob is the very ref its own
  -- world hands back for that id -- which is the (session, id) pair interning, read from both ends.
  local me = s:player():gob()
  local why = ""
  if s:player() ~= s:player() then why = "s:player() is a fresh object every call"
  elseif me == nil then why = "no gob (not in the world)"
  elseif s:world():gob():get(me:id()) ~= me then why = "get(id) is not s:player():gob()" end
  check(why == "", "s:player() is one object, and its gob is s:world():gob():get(its id)", why)

  -- The Position that comes out carries no session: a grid id the server published, and an offset
  -- inside that grid. That is the value an addon may keep, send and save.
  local info, badinfo = gridOf(me and me:position())
  check(info ~= nil, "the character's position names a grid ("
        .. ((info ~= nil) and info.gridId or "?") .. ")", badinfo)

  -- The character a login is playing is read on the Session, and there is no second spelling of it.
  local chr = s:character()
  check(type(chr) == "string", "s:character() names the character on screen (" .. tostring(chr)
        .. ")", chr)
  refuses("s:player():name is retired naming the Session",
          function() return s:player():name() end, "hafen.session()")

  -- The hard cut: neither namespace is on `hafen` any more, and reading either says where it went.
  refuses("hafen.world refuses naming hafen.session()",
          function() return hafen.world() end, "hafen.session()")
  refuses("hafen.player refuses naming hafen.session()",
          function() return hafen.player() end, "hafen.session()")
  refuses("hafen.player():name refuses naming hafen.session()",
          function() return hafen.player():name() end, "hafen.session()")

  -- The collection still addresses by id and only by id, and says so.
  refuses("s:world():gob():get refuses a name",
          function() return s:world():gob():get("me") end, "a gob id (a number)")

  local o = other(user)
  if o == nil then
    log:write("[note] one session up, so the cross-session claims were not reached --"
              .. " :session add USER CHAR and run :t076-3 again to score them")
  else
    local why, notyet = crossSession(s, o, user)
    if notyet ~= nil then
      log:write("[note] " .. notyet .. ", so the cross-session claims were not reached")
    else
      check(why == "", "with " .. o:user() .. " up: one id is two refs, :id() crosses, the frames"
            .. " and the screen do not", why)
    end
  end

  manual = manual + 1
  log:write("[manual] with two characters standing APART, read the OTHER one's"
            .. " hafen.session():get(user):player():gob():position():info() -- expect: a different"
            .. " gridId from the " .. ((info ~= nil) and info.gridId or "one") .. " above, being the"
            .. " grid THAT character stands on")

  log:write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t076-3", run)   -- the only way in: a suite does not start itself
