-- 046.1 -- a native gob answers :scale. Self-checking suite; see specs/addons/TESTING.md and
-- specs/addons/046-gob-scale/{spec,plan,tasks}.md.
--
-- WHAT THIS TASK CLAIMS. Every hafen.vr() entity answers :scale -- a ghost, a sprite, an object, a standing
-- widget -- and now the things the GAME puts in the world answer the same verb with the same meaning:
-- gob:scale(k). It is the first WRITE on a handle that had been read-only, and it is client-local and purely
-- visual, on the footing gob:overlay() already stands on: nothing goes on the wire, and nothing about what
-- the gob IS changes. Bare reads (1 for a gob nobody scaled), one number writes and hands the GOB back.
--
-- AND IT ENDS WITH THE LOADED OBJECT, BY DIRECTIVE. The size lives on the engine's Gob, so walking far enough
-- to unload the object and coming back gives you the original size. That is the contract, not a defect -- an
-- addon that wants it back re-applies on GobAdded. What must NOT survive is an addon that stopped running:
-- :reload or disable puts every gob it resized back.
--
-- THREE COMMANDS. ':t046-1' is the whole assertion run and ends leaving NOTHING scaled -- it grows you to
-- 1.5x for three seconds on the way out, which is the one thing a program cannot see for itself.
-- ':t046-1 big' scales the nearest object 2.5x and LEAVES it standing, which is what the click / walk /
-- :reload lines below need. ':t046-1 off' puts everything back.
--
-- READ-ONLY: no permissions, no persistent state. A scale is client-local and dies with the loaded object.

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

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- An id no server ever minted. hafen.world():gob():get(id) never answers nil, so this is the page's own door
-- onto a Gob that is GONE -- the handle every method must answer nil from without ever throwing.
local NEVER = 999999999999

local icon                                  -- this suite's own image (hafen.asset), for the vr sibling
hafen.event():on("Load", function() icon = hafen.asset():get("icon.png") end)

-- Is this listed gob drawn at anything other than its own size? A nil read is NOT one: the world list is a
-- snapshot and it carries the client's own transient effect gobs (the ones the skeleton stands up to hang a
-- one-shot overlay on, id -1, dropped again the tick the overlay ends), whose handle re-resolves to nothing.
-- "The gob is gone" is exactly the answer this task's sixth check celebrates -- it is not a size.
local function scaleOf(g)
  local k = g:scale()
  return ((k ~= nil) and (k ~= 1)) and k or nil
end

-- Put every game object back to its original size and say how many had to be put back. Used at both ends of
-- the run: a run starts from a clean world and must leave one.
local function unscaleAll()
  local n, who = 0, nil
  for _, g in ipairs(hafen.world():gob():list()) do
    if scaleOf(g) ~= nil then
      n = n + 1
      who = who or (g:name() or ("#" .. tostring(g:id())))
      g:scale(1)
    end
  end
  return n, who
end

-- How many gobs are drawn at anything other than their own size right now, and the first of them by name.
local function scaledNow()
  local n, who = 0, nil
  for _, g in ipairs(hafen.world():gob():list()) do
    local k = scaleOf(g)
    if k ~= nil then
      n = n + 1
      who = who or ((g:name() or ("#" .. tostring(g:id()))) .. "=" .. tostring(k))
    end
  end
  return n, who
end

-- Every one of `cases` must be REFUSED, and refused saying why: the message has to carry `wantMsg`. Answers
-- false plus the case that broke the rule, so a [fail] names the input rather than just the count.
local function allRefused(fn, cases, wantMsg)
  for _, c in ipairs(cases) do
    local ok, err = pcall(fn, c.v)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if ok or (err:find(wantMsg, 1, true) == nil) then
      return false, c.name .. " -> " .. err
    end
  end
  return true, "-"
end

local finish

local function run()
  pass, fail, manual = 0, 0, 0             -- a re-run reports its own counts, not the last one's

  local okp, me = pcall(function() return hafen.player():gob() end)
  if not (okp and me and me:exists()) then
    check(false, "the suite is in the world (it resizes game objects)", tostring(okp and me))
    return summary()
  end
  unscaleAll()                              -- start clean, whatever a previous ':t046-1 big' left standing

  -- The premise, stated where it can fail: the original size is what a gob nobody touched reads back, so
  -- every number below is measured against something rather than against nothing.
  check(me:scale() == 1, "a game object nobody scaled reads back 1 -- the original size is a value the verb"
        .. " answers, not the absence of an answer", tostring(me:scale()))

  local back = me:scale(1.5)
  check(me:scale() == 1.5, "gob:scale(k) writes the size of an object the SERVER owns, and it reads back"
        .. " through the same verb -- the handle's first write, on the footing gob:overlay() already stands"
        .. " on: client-local, purely visual, nothing on the wire", tostring(me:scale()))
  check((back ~= nil) and (back:id() == me:id()), "...and the write hands the GOB back, so gob:scale(2):name()"
        .. " is one chain -- the read/write pair every hafen.vr() entity answers, spelled the same way here",
        tostring(back and back:id()))

  me:scale(1)
  local after1, after2 = me:scale(), me:scale()
  check((after1 == 1) and (after2 == 1), "gob:scale(1) puts it back exactly as it was and leaves NOTHING"
        .. " behind -- the original size is the absence of this state, so a second read answers 1 from a gob"
        .. " that carries nothing again", ("%s then %s"):format(tostring(after1), tostring(after2)))

  -- Three rules, seven inputs, and each refusal names the rule it broke. A size is one of the few numbers
  -- where the wrong value does not look wrong -- 0 collapses the object to a point and a negative turns it
  -- inside out -- so it is refused at the call site rather than quietly clamped.
  local function w(v) return me:scale(v) end
  local okA, whyA = allRefused(w, { { name = '"big"', v = "big" }, { name = "a table", v = {} } },
                               "must be a number")
  check(okA, 'a factor that is not a number is refused naming the type: "big" and a table', whyA)
  local okN, whyN = pcall(function() me:scale(nil) end)
  whyN = okN and "<no error>" or tostring(whyN)
  check((not okN) and (whyN:find("must not be nil", 1, true) ~= nil),
        "...and an explicit nil is refused rather than silently becoming a READ -- arity is the verb here,"
        .. " so the write nobody made would have had no symptom at all", whyN)
  local okF, whyF = allRefused(w, { { name = "1/0", v = 1 / 0 }, { name = "0/0", v = 0 / 0 } },
                               "must be a finite number")
  check(okF, "a non-finite factor is refused: 1/0 and 0/0 -- neither of them is a matrix", whyF)
  local okZ, whyZ = allRefused(w, { { name = "0", v = 0 }, { name = "-1", v = -1 } },
                               "must be greater than 0")
  check(okZ, "and a factor that is not greater than zero is refused naming why: 0 collapses the object to a"
        .. " point and -1 turns it inside out", whyZ)
  check(me:scale() == 1, "...and not one of those seven left a mark: after every refusal the object is still"
        .. " its own size", tostring(me:scale()))

  -- A gob that is GONE. hafen.world():gob():get(id) never answers nil, so this handle is real and its object
  -- is not -- the Gob page's standing rule is that every method answers nil there and none of them throws,
  -- and the first WRITE is no exception to it.
  local ghost = hafen.world():gob():get(NEVER)
  local readGone = ghost:scale()
  local okGone, errGone = pcall(function() return ghost:scale(2) end)
  check((not ghost:exists()) and (readGone == nil) and okGone and (ghost:scale() == nil),
        "a gob that is gone answers nil to :scale() and takes :scale(2) without throwing -- it simply does"
        .. " nothing with it. Every method on a departed gob goes quiet, and writing is not the exception",
        ("exists=%s read=%s wrote ok=%s (%s)"):format(tostring(ghost:exists()), tostring(readGone),
          tostring(okGone), okGone and "-" or tostring(errGone)))

  -- The premise this task rests on, stated here because this suite is read alone: the verb it just gave a
  -- native gob is the one its virtual siblings already answer, and giving it away did not take it from them.
  if icon == nil then
    check(false, "the suite's own image loaded (hafen.asset) -- the vr sibling below needs it", "no icon")
  else
    local sp = hafen.vr():sprite():add(icon, me:position())
    local sread = sp:scale(3):scale()
    hafen.vr():sprite():remove(sp)
    check(sread == 3, "one verb, two kinds: a hafen.vr() sprite still answers its own :scale, written and"
          .. " read back through the same pair -- a native gob gained the verb, it did not take it",
          tostring(sread))
  end

  -- ...and now the one thing a program cannot see. The growth is LAST so that the run still ends clean.
  manualCheck("watch your own character for the next three seconds -- this run is about to grow you to 1.5x"
              .. " and shrink you back on its own (run ':t046-1' again if you missed it)",
              "you grow to 1.5x and shrink back IN PLACE: your feet do not move, the camera does not jump,"
              .. " and you still turn and walk normally at either size")
  me:scale(1.5)
  hafen.timer():after(3.0, function() finish(me) end)
end

finish = function(me)
  me:scale(1)
  local n, who = scaledNow()
  check((me:scale() == 1) and (n == 0), "and the run ends leaving NOTHING scaled: the object it grew a moment"
        .. " ago is back at 1, and so is every other game object in the world",
        ("you=%s; %d others still scaled (%s)"):format(tostring(me:scale()), n, tostring(who)))

  manualCheck("run ':t046-1 big' (it scales the nearest object 2.5x and leaves it standing), left-click that"
              .. " object, then run ':reload'",
              "the click still selects it and the pick follows the DRAWN size -- clicking the enlarged"
              .. " outline works; and after ':reload' it is back to its original size, with nothing else"
              .. " changed. An addon that stopped running leaves nothing distorted")
  manualCheck("run ':t046-1 big' again, then walk far enough away for that object to unload and come back to"
              .. " it (finish with ':t046-1 off' if anything is left big)",
              "it is its ORIGINAL size when you return -- the scale ends with the loaded object, which is the"
              .. " contract rather than a defect: re-applying on GobAdded is the addon's job")
  summary()
end

-- ':t046-1 big' -- scale the nearest object and LEAVE it, for the three manual lines above.
local function bigRound()
  pass, fail, manual = 0, 0, 0
  local g = hafen.world():gob():nearest()
  if (g == nil) or (not g:exists()) then
    check(false, "there is an object near you to scale", "nothing found")
    return summary()
  end
  local who = g:name() or "an unnamed object"
  g:scale(2.5)
  check(g:scale() == 2.5, "the nearest object (" .. who .. ") is left standing at 2.5x -- click it, or walk"
        .. " away from it, or ':reload' with it in view. ':t046-1 off' puts it back", tostring(g:scale()))
  summary()
end

-- ':t046-1 off' -- put everything back, whatever a 'big' round left standing.
local function offRound()
  pass, fail, manual = 0, 0, 0
  local n, who = unscaleAll()
  local left = scaledNow()
  check(left == 0, ("everything is back at its own size (%d put back%s)"):format(n, (who == nil) and "" or (", first: " .. who)),
        ("%d still scaled"):format(left))
  summary()
end

hafen.slash():register("t046-1", function(args)   -- the only way in: a suite does not start itself
  if args and (args[1] == "big") then return bigRound() end
  if args and (args[1] == "off") then return offRound() end
  return run()
end)
