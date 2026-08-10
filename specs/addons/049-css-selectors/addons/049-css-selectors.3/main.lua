-- 049.3 — the chain's OTHER two consumers: the appear/disappear subscriptions and the stylesheet's tree keys.
-- Self-checking suite; see specs/addons/TESTING.md.
--
-- What is new is that a chain's [title=] sits on an ANCESTOR step, so the caption that decides a match lands on a
-- widget the answer is not about. Both consumers cache: a subscription remembers what it matched, the sheet keeps
-- one folded style per widget and lets a NEGATIVE answer settle after a bounded number of re-folds. So the suite
-- settles that negative first (25 reads, past the countdown) and only then renames the window -- a re-fold that
-- happened by countdown would prove nothing about the seam this task ships.
--   Three shapes are inherited from 049.1/049.2's suites: a built window IS NOT IN THE TREE until the next tick,
-- the captions carry SPACES on purpose (the combinator splits on whitespace OUTSIDE brackets), and a window an
-- addon builds never enters the client's placement seam -- which is why every appear below comes from the
-- registration scan or from the caption seam, and why a window the SERVER built is asserted against separately.

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

local CAP_A    = "049 Chain Alpha"      -- the window whose caption is there from the start
local CAP_B    = "049 Chain Beta"       -- a second window, same shape, different caption: the negative control
local CAP_C    = "049 Chain Gamma"      -- the third window's caption before it is renamed
local CAP_LATE = "049 Chain Late"       -- ...and after: the caption that lands LATE, on an ancestor step
local CAP_GONE = "049 Chain Gone"       -- ...and after that: the rename that ENDS a match

local function chain(cap) return ("window[title=%s] label"):format(cap) end
local function done() hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual)) end

local sheet
local winA, winB, winC, labA, labB, labC
local subA, subLate, subGone
local seenA, seenLate, seenGone = {}, {}, {}

-- ---------------------------------------------------------------- the last round: dedup, the reverse, cleanup

local function phase3()
  check(#seenA == 1, "re-announcing the SAME caption fires no second appear -- the tracked set is the dedup",
        #seenA)
  check(labC:style() == nil, "a rename that ENDS the chain takes the descendant's style away too",
        tostring(labC:style()))
  check((#seenGone == 1) and (seenGone[1] == labA),
        "disappear fires once, for the descendant the chain had matched, when it leaves the tree", #seenGone)

  subA:remove(); subLate:remove(); subGone:remove()
  sheet:drop()
  winA:destroy(); winB:destroy(); winC:destroy()
  check((#hafen.ui():all(("window[title=%s]"):format(CAP_A)) == 0)
        and (#hafen.ui():all(("window[title=%s]"):format(CAP_B)) == 0)
        and (#hafen.ui():all(("window[title=%s]"):format(CAP_GONE)) == 0),
        "the suite leaves no widget, no subscription and no installed sheet behind")

  -- The same claim against a window the SERVER built, whose tree carries the Frames and content areas a
  -- hand-built one does not -- the whole reason the combinator is descendant-at-any-depth rather than child.
  local count, real, cap = {}, nil, nil
  for _, w in ipairs(hafen.ui():all("window")) do
    local t = w:title()
    if t and (t ~= "") then count[t] = (count[t] or 0) + 1 end
  end
  for _, w in ipairs(hafen.ui():all("window")) do
    local t = w:title()
    if t and (t ~= "") and (count[t] == 1) and (#w:all("label") > 0) then real, cap = w, t; break end
  end
  if real then
    local hits, own, set = {}, real:all("label"), {}
    local s = hafen.ui():on(chain(cap), "appear", function(w) hits[#hits + 1] = w end)
    s:remove()
    for _, w in ipairs(own) do set[w] = true end
    local ok = (#hits == #own) and (#own > 0)
    for i = 1, #hits do ok = ok and (set[hits[i]] == true) end
    check(ok, ("a chain over a window the SERVER built resolves to exactly its own labels (%q, %d)")
              :format(cap, #own), ("%d fired vs %d in the subtree"):format(#hits, #own))
  else
    manualCheck("no client window with a unique caption and a label inside it is open right now -- open one"
                .. " (a chest, a cupboard, the character sheet) and run ':t049-3' again",
                'one more [pass] line reading "a chain over a window the SERVER built resolves to exactly its'
                .. ' own labels": subscribing to "window[title=<that caption>] label" fires once per label'
                .. " inside that window and for nothing else")
  end
  done()
end

-- ------------------------------------------------------- the middle round: what the late caption re-opened

local function phase2()
  local sC = labC:style()
  check(sC and sC.color and (sC.color.b == 220),
        "a SETTLED negative style is re-opened when the ANCESTOR's caption lands",
        sC and sC.color and sC.color.b)
  check((#seenLate == 1) and (seenLate[1] == labC),
        "...and the appear chain fires exactly once for that same descendant, on that same caption", #seenLate)
  check(#seenA == 1, "the first chain fired nothing extra while the third window was renamed", #seenA)

  winA:title(CAP_A)        -- re-announced with the caption it already had: the subtree is re-offered, and nothing
  winC:title(CAP_GONE)     --   may fire twice. And the reverse: a rename that ends a match.
  labA:destroy()           -- ...and the matched descendant leaves the tree -> disappear
  hafen.timer():after(0.3, function()
    local ok, err = pcall(phase3)
    if not ok then
      hafen.log():write("[fail] the suite itself raised in its third round -- got: " .. tostring(err))
      fail = fail + 1
      done()
    end
  end)
end

-- ------------------------------------------------------------- the first round: the tree key and the scan

local function phase1()
  sheet = hafen.ui():sheet()

  -- A chain is ALWAYS a tree key, however site-shaped its first word: a site is ONE bare word. Layout is where
  -- the two kinds differ observably -- a site is where the client draws, so it has no position to be given.
  local siteErr = raised(function() sheet:load{ ["chat"] = { position = { 10, 10 } } } end)
  local chainErr = raised(function() sheet:load{ ["chat label"] = { position = { 10, 10 } } } end)
  check(siteErr and (siteErr:find("render site", 1, true) ~= nil) and (chainErr == nil),
        'a chain is a TREE key: ["chat"] refuses position as a render site, ["chat label"] takes it',
        ("site -> %s | chain -> %s"):format(tostring(siteErr), tostring(chainErr)))

  sheet:load{
    [chain(CAP_A)]    = { color = { 200, 180, 140 } },
    [chain(CAP_LATE)] = { color = { 120, 160, 220 } },
  }:install()

  local sA = labA:style()
  check(sA and sA.color and (sA.color.r == 200) and (sA.color.g == 180),
        "the chain key styles the label INSIDE the window it names",
        sA and sA.color and sA.color.r)
  check((labB:style() == nil) and (winA:style() == nil),
        "...and neither the same-shaped label under another caption nor the window the chain walks THROUGH",
        ("sibling %s | window %s"):format(tostring(labB:style()), tostring(winA:style())))

  subA = hafen.ui():on(chain(CAP_A), "appear", function(w) seenA[#seenA + 1] = w end)
  check((#seenA == 1) and (seenA[1] == labA),
        "an appear chain's registration scan fires once, for the descendant of the window it names", #seenA)
  subGone = hafen.ui():on(chain(CAP_A), "disappear", function(w) seenGone[#seenGone + 1] = w end)

  subLate = hafen.ui():on(chain(CAP_LATE), "appear", function(w) seenLate[#seenLate + 1] = w end)
  check(#seenLate == 0, "...and a chain whose caption no window carries yet fires nothing at all", #seenLate)

  local settled
  for _ = 1, 25 do settled = labC:style() end     -- past the negative re-check countdown: SETTLED, not merely nil
  check(settled == nil, "the third window's label resolves to no style, and that answer has settled",
        tostring(settled))

  winC:title(CAP_LATE)     -- THE caption seam: the attribute that decides labC lands on labC's ANCESTOR
  hafen.timer():after(0.3, function()
    local ok, err = pcall(phase2)
    if not ok then
      hafen.log():write("[fail] the suite itself raised in its second round -- got: " .. tostring(err))
      fail = fail + 1
      done()
    end
  end)
end

local function run()
  winA = hafen.ui():window():title(CAP_A):size(220, 80):position(120, 120)
  labA = hafen.ui():label():parent(winA):position(8, 8):text("Chain049-A")
  winB = hafen.ui():window():title(CAP_B):size(220, 80):position(360, 120)
  labB = hafen.ui():label():parent(winB):position(8, 8):text("Chain049-B")
  winC = hafen.ui():window():title(CAP_C):size(220, 80):position(600, 120)
  labC = hafen.ui():label():parent(winC):position(8, 8):text("Chain049-C")
  -- The builder arms the add for the NEXT tick, so nothing above is findable, styleable or matchable yet.
  hafen.timer():after(0.3, function()
    local ok, err = pcall(phase1)
    if not ok then
      hafen.log():write("[fail] the suite itself raised in its first round -- got: " .. tostring(err))
      fail = fail + 1
      if winA:exists() then winA:destroy() end
      if winB:exists() then winB:destroy() end
      if winC:exists() then winC:destroy() end
      done()
    end
  end)
end

hafen.slash():register("t049-3", run)   -- the only way in: a suite does not start itself
