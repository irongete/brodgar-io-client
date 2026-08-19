-- 079.4 — the world fires once, and the character says which. Self-checking suite.
--
-- Two claims, and they are opposites.
--   THE WORLD FIRES ONCE. A gob id is the server's and names one object, so an object arriving is one
-- GobAdded however many of your characters can see it, and it is reported gone when it leaves the LAST
-- of them. So over any window, no id may receive two GobAdded without a GobRemoved between: that is the
-- deduplication, stated as an assertion rather than as a claim.
--   THE CHARACTER SAYS WHICH. The sixteen events about one character hand their Session as the handler's
-- LAST argument, so an addon watching two logins can tell whose meter moved -- and a handler that does
-- not care declares one parameter and is unchanged, because Lua drops the argument it did not name.
--   And the seven that need none do not grow one: Update carries dt and nothing after it.
--
-- Objects arriving and bars moving are the SERVER's to produce, so the run opens a bounded window and
-- scores what reaches it. Walk during that window (the [manual] lines say so) or it has nothing to score.

local WINDOW = 15          -- seconds of collection; the maintainer walks during it

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

local function keys(t)
  local out = {}
  for k in pairs(t) do out[#out + 1] = tostring(k) end
  table.sort(out)
  return out
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

  -- THE CLOSED KEY SET, unchanged: a near miss is refused at the line that wrote it and pointed at the
  -- catalogue, rather than reading as a subscription that silently never fires.
  refuses("a key that is not one of them is refused, pointing at the catalogue",
          function() return hafen.event():on("GobAdded ", function() end) end,
          "unknown event", "bus.md")

  -- THE SEVEN THAT NEED NONE DO NOT GROW ONE. Update is the one of them that fires by itself, so it is
  -- the one a suite can hold to the rule: dt, and nothing after it.
  local updArgs, updDt = nil, nil
  local upd = hafen.event():on("Update", function(...)
    if updArgs == nil then
      updArgs = select("#", ...)
      if updArgs > 0 then updDt = (select(1, ...)) end
    end
  end)

  -- THE SIXTEEN CARRY THEIRS. Two subscriptions on one key, deliberately: one that TAKES the session and
  -- one that ignores it, because "a handler that does not care is not asked to change" is half the claim.
  local meters, mBad, mAccounts, mIgnored = 0, 0, {}, 0
  local mFull = hafen.event():on("MeterChanged", function(m, who)
    meters = meters + 1
    -- pcall'd whole: an argument that is not a Session at all would raise here, and a handler that
    -- threw would be one firing nothing scored -- which reads back as the wrong failure.
    local safe, ok = pcall(function()
      return (who ~= nil) and (who:user() ~= "")
             and (hafen.session():get(who:user()) == who)    -- the interned Session for that account
             and (type(m:segments()) == "table")
    end)
    if safe and (ok == true) then
      mAccounts[who:user()] = (mAccounts[who:user()] or 0) + 1
    else
      mBad = mBad + 1
    end
  end)
  local mBare = hafen.event():on("MeterChanged", function(m)
    if (m ~= nil) and (type(m:segments()) == "table") then mIgnored = mIgnored + 1 end
  end)

  -- THE WORLD FIRES ONCE. Count GobAdded per id, and let a GobRemoved clear that id -- an object you
  -- walk away from and come back to is entitled to arrive again, and only an id that arrives TWICE with
  -- no departure between is the defect this task closes.
  local open, twice, adds, removes, payloadBad = {}, {}, 0, 0, 0
  local gAdd = hafen.event():on("GobAdded", function(g)
    adds = adds + 1
    local id = g:id()
    if open[id] then twice[id] = true else open[id] = true end
    -- ...and the payload is the OBJECT: the same interned handle every door to that id hands back.
    if s:world():gob():get(id) ~= g then payloadBad = payloadBad + 1 end
  end)
  local gDel = hafen.event():on("GobRemoved", function(g)
    removes = removes + 1
    open[g:id()] = nil
  end)

  -- The window needs something to score, and only a player can produce it: objects come into view
  -- because somebody walked. So the instruction and the observation are one line.
  manualCheck("with two characters standing TOGETHER, walk one of them a few steps for the next "
              .. WINDOW .. " seconds, then read the GobAdded line below",
              "one firing per object and not two -- the added count is the number of objects that came"
              .. " into view, whatever number of your characters saw each of them")

  hafen.timer():after(WINDOW, function()
    upd:off(); mFull:off(); mBare:off(); gAdd:off(); gDel:off()

    check(updArgs == 1, "Update carries dt and nothing after it -- one argument, no session",
          tostring(updArgs) .. " arguments, first = " .. tostring(updDt))

    local accounts = keys(mAccounts)
    check((meters > 0) and (mBad == 0),
          "every MeterChanged carried a live Session whose :user() reads -- " .. meters
          .. " firings from " .. #accounts .. ": " .. table.concat(accounts, ", "),
          (meters == 0) and ("no MeterChanged in " .. WINDOW .. "s -- walk during the window")
            or (mBad .. " of " .. meters .. " carried no usable session"))

    check((meters > 0) and (mIgnored == meters),
          "a handler that declares one parameter still receives the meter, all " .. meters .. " times",
          tostring(mIgnored) .. " of " .. tostring(meters))

    local dup = keys(twice)
    check((adds > 0) and (#dup == 0),
          "no gob id received two GobAdded without a GobRemoved between -- " .. adds .. " added, "
          .. removes .. " removed",
          (adds == 0) and ("no GobAdded in " .. WINDOW .. "s -- walk during the window")
            or (#dup .. " ids fired twice: " .. table.concat(dup, ", ")))

    check((adds > 0) and (payloadBad == 0),
          "every GobAdded payload is the interned Gob its id answers with",
          (adds == 0) and "no GobAdded to check" or (payloadBad .. " of " .. adds .. " differed"))

    manualCheck("with the two characters APART, run this again and read the MeterChanged line",
                "it names BOTH accounts -- each character's own bars are labelled with that character,"
                .. " not with whichever one is on screen")

    log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t079-4", run)   -- the only way in: a suite does not start itself
