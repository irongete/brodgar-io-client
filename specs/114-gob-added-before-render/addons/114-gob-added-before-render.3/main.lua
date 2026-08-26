-- 114.3 — gob:visible(b): an object the client draws, or does not. Self-checking suite.
--
-- Type :t114 while standing in the world with something in front of you (a tree is ideal). The run
-- takes about a minute and it wants you WALKING for the second half of it, into ground you have not
-- seen this session, so trees keep streaming in ahead of you.
--
-- The automated half finishes in the first instant; the three [manual] lines below say what to watch
-- for and when, and the run reports once it has put everything back.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- LuaJ writes a bridge refusal as "@chunk.lua:189 msg", with a SPACE and no second colon, so the
-- strip has to allow both shapes or the message is scored with its own location glued to the front.
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or why(err)
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local SHOW_AT, UNSCALE_AT, WALK_AT, DONE_AT = 15, 25, 25, 55

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t114 scores its own run, not both
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t114 in the world")
    return report()
  end

  -- One object to round-trip on: a tree if there is one, otherwise the nearest thing that has a name.
  local g = s:world():gob():nearest("tree")
          or s:world():gob():nearest(function(o) return o:name() ~= nil end)
  if not g then
    check(false, "something of the game's is standing near you", "nothing in view -- move and re-run")
    return report()
  end
  local what = g:name() or "object"

  manualCheck("the " .. what .. " nearest you is now twice its size and hidden, for " .. SHOW_AT .. "s"
              .. " -- look at where it stands, and click there",
              "it is gone from the scene, and the click reaches the ground behind it rather than the"
              .. " object")
  manualCheck("at " .. SHOW_AT .. "s the suite shows it again, and takes the size off " .. UNSCALE_AT
              .. "s in", "it comes back AT TWICE ITS SIZE -- the hide did not lose the size -- and"
              .. " then shrinks back to normal")
  manualCheck("from " .. WALK_AT .. "s to " .. DONE_AT .. "s every arriving tree is hidden: WALK into"
              .. " ground you have not seen this session", "no tree appears at all, not even for an"
              .. " instant before it goes")

  -- ---- the pair, round-tripped on a live object -----------------------------------------------
  check(g:visible() == true, "gob:visible() is true for an object nobody hid", g:visible())
  local chained = g:visible(false)
  check(chained == g, "gob:visible(b) hands the Gob back, so the call chains", tostring(chained))
  local off = g:visible()
  g:visible(true)
  check((off == false) and (g:visible() == true),
        "gob:visible() reads false after the write and true again after the next",
        tostring(off) .. " then " .. tostring(g:visible()))

  -- The record's lifetime is not the object's: hiding it does not make it gone.
  g:visible(false)
  check(g:exists() == true, "a hidden object is still there: gob:exists() is true while it is hidden",
        g:exists())
  local info = g:info()
  check((info ~= nil) and (info.visible == false),
        "gob:info().visible follows the live read", info and tostring(info.visible) or "no snapshot")
  g:visible(true)

  -- It composes with the size: the two are different state and neither forgets the other.
  g:scale(2)
  g:visible(false):visible(true)
  check(g:scale() == 2, "hiding and showing leaves the size alone: scale() is still 2", g:scale())

  -- ---- and what it will not take ---------------------------------------------------------------
  refuses("gob:visible(nil) is refused, naming the argument", function() g:visible(nil) end, "b must")
  refuses("gob:visible(0) is refused, naming the argument -- 0 is TRUE in Lua",
          function() g:visible(0) end, "b must")

  -- ---- the manual schedule ---------------------------------------------------------------------
  g:scale(2):visible(false)                       -- doubled AND hidden, for the eye
  hafen.timer():after(SHOW_AT, function() pcall(function() g:visible(true) end) end)
  hafen.timer():after(UNSCALE_AT, function() pcall(function() g:scale(1) end) end)

  local hidden, sub = {}, nil
  hafen.timer():after(WALK_AT, function()
    sub = hafen.event():on("GobAdded", function(gob)
      local name = gob:name()
      if name and name:find("tree", 1, true) then
        gob:visible(false)
        hidden[#hidden + 1] = gob
      end
    end)
  end)

  hafen.timer():after(DONE_AT, function()
    if sub then sub:off() end
    for i = 1, #hidden do pcall(function() hidden[i]:visible(true) end) end
    pcall(function() g:visible(true):scale(1) end)   -- nothing this suite touched stays touched

    check(#hidden > 0, "every tree arriving over that window was hidden from its GobAdded handler ("
          .. #hidden .. ")", "no tree arrived -- walk into unseen ground and re-run")
    report()
  end)
end

hafen.console():on("t114", run)   -- the only way in: a suite does not start itself
