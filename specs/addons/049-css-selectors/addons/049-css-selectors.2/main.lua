-- 049.2 — the lookup doors: a STRICT find (never a wrong widget in place of no answer) and the SCOPED
-- widget:find(sel) / widget:all(sel). Self-checking suite; see specs/addons/TESTING.md.
--
-- It builds TWO windows carrying THE SAME caption, because that is the whole situation the task exists for: with
-- two Cupboards open, "the Cupboard's grid" has no single answer, and a lookup that handed back whichever the walk
-- met first was quietly wrong half the time. Building the pair rather than asking for two real containers makes
-- every claim deterministic -- and where the client HAS two windows sharing a caption right now, the same claim is
-- asserted against those too, so the [manual] line only appears when it genuinely cannot be automated.
--   Three shapes are inherited from 049.1's suite: a built window IS NOT IN THE TREE until the next tick (the
-- builder arms the add), the caption carries SPACES on purpose (the combinator splits on whitespace OUTSIDE
-- brackets), and the probe buttons carry unique text because a window's own chrome carries a close IButton that
-- the `button` role matches too.

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

-- The refusal message, or nil when the call answered. A refusal is a check: it must fail, and fail SAYING why.
local function raised(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local CAP  = "049 Twin Probe"        -- BOTH windows carry it: the ambiguity is the point (spaces on purpose)
local PRE  = "Probe049b"             -- unique: a window's own close IButton is a `button` too

local function checks(win1, win2, btnA, btnB)
  local W = ("window[title=%s]"):format(CAP)

  -- The premise, and the half that did NOT change: :all still answers where find now refuses.
  check(#hafen.ui():all(W) == 2, "the collection form still answers for an ambiguous selector (2 twin windows)",
        #hafen.ui():all(W))

  -- find("*") -- the most ambiguous selector there is. The message must carry BOTH the count and the door that
  -- does have an answer, since that is all the addon author gets back.
  local everyN = #hafen.ui():all("*")
  local star = raised(function() return hafen.ui():find("*") end)
  check(star and (tonumber(star:match("matches (%d+) widget")) == everyN)
        and (star:find('hafen.ui():all("*")[i]', 1, true) ~= nil),
        ('find("*") refuses, saying how many matched (%d) and naming hafen.ui():all(sel)[i]'):format(everyN),
        star or "<no error>")

  -- ...and a selector that names exactly ONE widget still answers, unchanged.
  check(hafen.ui():find(W .. (" button[text=%s-A]"):format(PRE)) == btnA,
        "a selector matching exactly one widget still hands it back")

  -- THE PAIR, root-anchored: one chain, three matching buttons across the two twin windows, no answer.
  local chain = W .. (" button[text^=%s]"):format(PRE)
  local amb = raised(function() return hafen.ui():find(chain) end)
  check(amb and (tonumber(amb:match("matches (%d+) widget")) == 3) and (#hafen.ui():all(chain) == 3),
        "the root-anchored chain refuses across the two twin windows (3 probe buttons)", amb or "<no error>")

  -- THE PAIR, scoped: the SAME string, asked of one window, answers -- that window's own child and no other's.
  check(win1:find(("button[text^=%s]"):format(PRE)) == btnA,
        "widget:find scoped inside one twin window returns THAT window's own button")

  -- The scope is what disambiguates, not the selector: strictness holds at the scoped door too, and its message
  -- names the door the caller actually used.
  local inner = raised(function() return win2:find(("button[text^=%s]"):format(PRE)) end)
  check(inner and (tonumber(inner:match("matches (%d+) widget")) == 2)
        and (inner:find("widget:all(", 1, true) ~= nil),
        "widget:find refuses two matches inside one subtree, naming widget:all(sel)[i]", inner or "<no error>")

  -- The scope is INCLUSIVE, and an ancestor step may name a widget above it (CSS querySelector semantics): the
  -- very selector that has no root-anchored answer is exact when asked of one of the two.
  check((win1:find(W) == win1) and (win2:find(W) == win2),
        "the scope includes the widget itself, so each twin answers the ambiguous selector with itself")

  -- The collection forms answer empty, never nil -- scoped and root alike.
  local none, rootNone = win1:all("inventory"), hafen.ui():all("textentry@Label")
  check((type(none) == "table") and (#none == 0) and (type(rootNone) == "table") and (#rootNone == 0),
        "widget:all is an EMPTY TABLE for no match, never nil (and so is hafen.ui():all)",
        ("scoped %s/%d, root %s/%d"):format(type(none), #none, type(rootNone), #rootNone))

  -- The same claim against the CLIENT'S OWN windows, whenever two of them share a caption right now.
  local seen, twin = {}, nil
  for _, w in ipairs(hafen.ui():all("window")) do
    local t = w:text()
    if t and (t ~= "") and (t ~= CAP) then
      if seen[t] then twin = t; break end
      seen[t] = true
    end
  end
  if twin then
    local sel = ("window[title=%s]"):format(twin)
    local both = hafen.ui():all(sel)
    check((raised(function() return hafen.ui():find(sel) end) ~= nil)
          and (both[1]:find(sel) == both[1]) and (both[2]:find(sel) == both[2]),
          ("two of the client's OWN windows are captioned %q: the root form refuses, each scoped one answers itself")
            :format(twin))
  else
    manualCheck("no two of the client's own windows share a caption right now -- open TWO containers with the same"
                .. " one (two chests, or two cupboards) and run ':t049-2' again",
                'one more [pass] line reading "two of the client\'s OWN windows are captioned ...": the'
                .. ' root-anchored hafen.ui():find("window[title=<that caption>]") RAISES, while w:find of the same'
                .. ' string inside each of the two answers with that window itself')
  end

  -- A STALE handle REFUSES at both doors -- the one read in this section that does not answer nil/empty, because
  -- "nothing matched" and "the thing you searched is gone" are different facts.
  win1:destroy()
  local sf = raised(function() return win1:find("button") end)
  local sa = raised(function() return win1:all("button") end)
  check((not win1:exists()) and sf and sa
        and (sf:find("widget:exists() is false", 1, true) ~= nil)
        and (sa:find("widget:exists() is false", 1, true) ~= nil),
        "a stale widget REFUSES at both doors, naming widget:exists()",
        ("exists=%s | find -> %s | all -> %s"):format(tostring(win1:exists()), tostring(sf), tostring(sa)))

  win2:destroy()
  check((not win1:exists()) and (not win2:exists()) and (#hafen.ui():all(W) == 0),
        "both probe windows are gone -- the suite leaves no widget behind", #hafen.ui():all(W))
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  local win1 = hafen.ui():window():title(CAP):size(220, 90):position(120, 120)
  local btnA = hafen.ui():button():parent(win1):position(8, 8):text(PRE .. "-A")
  local win2 = hafen.ui():window():title(CAP):size(220, 90):position(360, 120)
  local btnB = hafen.ui():button():parent(win2):position(8, 8):text(PRE .. "-B")
  hafen.ui():button():parent(win2):position(8, 36):text(PRE .. "-C")   -- two inside win2: the scoped refusal
  -- The builder arms the add for the NEXT tick, so nothing above is findable yet.
  hafen.timer():after(0.2, function()
    local ok, err = pcall(checks, win1, win2, btnA, btnB)
    if not ok then
      hafen.log():write("[fail] the suite itself raised -- got: " .. tostring(err))
      if win1:exists() then win1:destroy() end
      if win2:exists() then win2:destroy() end
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail + 1, manual))
    end
  end)
end

hafen.slash():register("t049-2", run)   -- the only way in: a suite does not start itself
