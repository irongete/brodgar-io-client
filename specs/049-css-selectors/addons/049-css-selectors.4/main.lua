-- 049.4 -- the inspector teaches the new grammar. Self-checking suite; see specs/testing/addon-suite.md.
--
-- widgetstack's selector panel lives in ANOTHER addon's sandbox, so no suite can call its builder. What a suite
-- CAN assert is the set of RULES that builder now follows -- and they are exactly the rules whose breaking makes
-- it silently useless:
--   * the candidate the OLD builder wrote for anything inside a titled window (`button[title=Cupboard]`) is a
--     PARSE ERROR since 049.1. It was pcall-dropped, so the panel lost its single most useful line and never
--     said why. The CHAIN is what replaces it.
--   * a chain must NAME ONE widget where the flat step names several -- that is what the extra walk buys, and it
--     is the only thing that lets a `find(...)` line be offered at all.
--   * every candidate built out of a widget's own parts must PARSE and RESOLVE BACK TO IT, or the inspector's
--     self-validation throws it away. That is asserted here over the CLIENT'S OWN live tree, widget by widget.
-- Restating those rules here is this suite's premise, not a transcription of the inspector: a suite is read alone
-- and must convince alone. What a program genuinely cannot do is read the panel -- that is the one [manual] line.
--
-- The scene is BUILT, because the claim needs two windows with DIFFERENT captions holding the SAME button text:
-- that is the shape where the flat candidate is ambiguous and only the chain names one. Two shapes are inherited
-- from 049.1/049.2's suites: a built window is not in the tree until the next tick (the builder arms the add),
-- and the probe text is unique because a window's own chrome carries a close IButton the `button` role matches.

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

local CAPA = "049 Panel Alpha"       -- two DIFFERENT captions: the chain is what tells the twins apart
local CAPB = "049 Panel Beta"
local BTXT = "ProbeD Close"          -- the SAME words in both windows: the flat candidate is ambiguous
local LTXT = "ProbeD 12 left"        -- a moving tail: stem "ProbeD" before the first digit
local STEM = "ProbeD"

local WA, WB = ("window[title=%s]"):format(CAPA), ("window[title=%s]"):format(CAPB)

-- ---- the inspector's candidate rules, restated ------------------------------------------------------------

local function trim(s) return (s:gsub("^%s+", ""):gsub("%s+$", "")) end

-- A value goes into [key=value] exactly as read: the parser trims what it finds between "=" and "]", so a padded
-- value could never match itself; one carrying "]" cannot be written at all; an empty one is a parse error.
local function writable(s)
  if not s or s:find("]", 1, true) then return nil end
  if (s == "") or (s ~= trim(s)) then return nil end
  return s
end

-- The anchor step: the nearest STRICTLY enclosing window that has a caption to be named by. Strict, because a
-- window's own caption is its own step's [title=] and not a chain in front of itself; and an uncaptioned window
-- is skipped rather than ending the walk, since the space is descendant at ANY depth.
local function anchorOf(w)
  local n = w:parent()
  while n do
    if n:role() == "window" then
      local cap = writable(n:text())
      if cap then return ("window[title=%s]"):format(cap) end
    end
    n = n:parent()
  end
  return nil
end

-- The most specific candidate the rules build for `w`: the anchor step, then the target's role, @Class, its OWN
-- [title=]/[text=] and its [res=]. Every part is read off THIS widget, so it must parse and it must match it.
local function fullCandidate(w)
  local role, cls, s = w:role(), w:type(), ""
  if role then s = role end
  if cls and (cls ~= "?") then s = s .. "@" .. cls end
  local own = writable(w:text())
  if own then s = s .. ("[%s=%s]"):format((role == "window") and "title" or "text", own) end
  local res = writable(w:res())
  if res then s = s .. ("[res=%s]"):format(res) end
  if s == "" then return nil end                -- nothing to say about it: the panel offers nothing either
  local anc = anchorOf(w)
  return (anc and (anc .. " ") or "") .. s
end

-- ---- the checks -------------------------------------------------------------------------------------------

local function checks(winA, winB, btnA, btnB, lblA)
  -- THE PREMISE. The candidate the inspector used to build for every widget inside a titled window does not
  -- parse any more, so a builder that still wrote it would offer nothing at all where it matters most.
  local retired = raised(function() return hafen.ui():find(("button[title=%s]"):format(CAPA)) end)
  check(retired and (retired:find("bad selector", 1, true) ~= nil)
        and (retired:find(("window[title=%s] button"):format(CAPA), 1, true) ~= nil),
        "the candidate the old inspector built inside a titled window is a PARSE ERROR naming the chain",
        retired or "<no error>")

  -- ...and the mirror, which is why the panel labels the key by ROLE rather than always saying [title=].
  local wrongKey = raised(function() return hafen.ui():find(("window[text=%s]"):format(CAPA)) end)
  check(wrongKey and (wrongKey:find(("[title=%s]"):format(CAPA), 1, true) ~= nil),
        "[text=] on a window step is refused, naming [title=] -- the two keys are disjoint at both ends",
        wrongKey or "<no error>")

  -- THE REASON FOR THE CHAIN: the flat candidate is ambiguous across the two windows, the chain names one.
  local flat = ("button[text=%s]"):format(BTXT)
  check(#hafen.ui():all(flat) == 2, "the flat candidate matches BOTH windows' buttons", #hafen.ui():all(flat))
  check((hafen.ui():find(WA .. " " .. flat) == btnA) and (hafen.ui():find(WB .. " " .. flat) == btnB),
        "the chain candidate names exactly one of them, and the right one, from each window")

  -- Which is precisely the rule the offered line follows: find() only where the candidate is unique.
  check((#hafen.ui():all(WA .. " " .. flat) == 1) and (raised(function() return hafen.ui():find(flat) end) ~= nil),
        "a chain is offerable as find(); the flat twin is not, because find refuses two matches")

  -- THE ANCHOR IS STRICTLY ENCLOSING -- a window is not a chain in front of itself.
  check((anchorOf(winA) == nil) and (anchorOf(btnA) == WA),
        "a top-level window has no anchor, while a widget inside it anchors on that window",
        tostring(anchorOf(winA)) .. " / " .. tostring(anchorOf(btnA)))

  -- THE OPERATORS. The ^= form is offered beside the exact one because it says something exact cannot: it still
  -- matches when the tail moves. The same value under = matches nothing at all, which is the whole point.
  check((hafen.ui():find(("label[text^=%s]"):format(STEM)) == lblA)
        and (#hafen.ui():all(("label[text=%s]"):format(STEM)) == 0),
        "[text^=stem] names the label with a moving tail, where [text=stem] names nothing")

  -- [res=] is EXACT since 049.1, so the panel offers the *= form beside it -- the shape the old behaviour had.
  -- Asserted against a real client widget, since nothing an addon builds carries a resource.
  local rw, rname
  for _, w in ipairs(hafen.ui():all("*")) do
    local r = writable(w:res())
    if r and r:find("/", 1, true) and (r:match("([^/]+)$") ~= r) then rw, rname = w, r; break end
  end
  if rw then
    local tail = rname:match("([^/]+)$")
    check((#hafen.ui():all(("[res=%s]"):format(rname)) > 0)
          and (#hafen.ui():all(("[res*=%s]"):format(tail)) > 0)
          and (#hafen.ui():all(("[res=%s]"):format(tail)) == 0),
          ("[res=] is exact and [res*=] is the substring form, on the client's own %q"):format(rname))
  else
    manualCheck("no widget on the HUD carries a slash-bearing resource name right now -- open a container or a"
                .. " crafting window (their windows are resource-built) and run ':t049-4' again",
                'one more [pass] line reading "[res=] is exact and [res*=] is the substring form, on the'
                .. " client's own ...\": the full name matches, its last path segment matches under *=, and the"
                .. " same segment under = matches NOTHING")
  end

  -- THE SWEEP. Every candidate the rules build for a live widget must parse and must resolve back to it -- the
  -- inspector's own self-validation drops anything that does not, silently, and then offers a weaker line.
  -- The WHOLE tree, uncapped: one full walk per widget is affordable once, and a cap here would quietly turn
  -- "every widget" into "the first few hundred".
  local seen, built, resolves, unique, findable = hafen.ui():all("*"), 0, 0, 0, 0
  local firstBad
  for i = 1, #seen do
    local w = seen[i]
    local sel = fullCandidate(w)
    if sel then
      built = built + 1
      local ok, hits = pcall(function() return hafen.ui():all(sel) end)
      local found = false
      if ok and hits then
        for k = 1, #hits do
          if hits[k] == w then found = true; break end
        end
        if found then
          resolves = resolves + 1
          if #hits == 1 then
            unique = unique + 1
            if hafen.ui():find(sel) == w then findable = findable + 1 end
          end
        end
      end
      if not found then firstBad = firstBad or (sel .. " -> " .. (ok and (#hits .. " hits") or tostring(hits))) end
    end
  end
  check((built > 0) and (resolves == built),
        ("every candidate built from a live widget's own parts parses and resolves back to it (%d/%d over %d widgets)")
          :format(resolves, built, #seen), firstBad)
  check((unique > 0) and (findable == unique),
        ("every candidate that names ONE widget is find()-able and hands back that widget (%d of %d)")
          :format(findable, unique), ("%d unique, %d findable"):format(unique, findable))

  -- HOW the old panel lost it. Candidates are resolved through :all() and a pcall drops whatever fails, so the
  -- retired spelling never announced itself -- the line simply stopped appearing. Same parse error, that door.
  check(raised(function() return hafen.ui():all(("button[title=%s]"):format(CAPA)) end) ~= nil,
        "the retired spelling fails at :all() too -- the door candidates are resolved through, where a pcall"
        .. " drops it in silence")

  -- ...and a chain still reaches across an intervening wrapper, so the anchor never has to be the direct parent.
  check((lblA:parent() ~= winA) and (hafen.ui():find(WA .. " " .. ("*[text=%s]"):format(LTXT)) == lblA),
        "the anchor reaches a descendant at ANY depth -- the label sits under a wrapper, not in the window itself",
        tostring(lblA:parent() == winA))

  winA:destroy(); winB:destroy()
  check((not winA:exists()) and (not winB:exists()) and (#hafen.ui():all(WA) == 0) and (#hafen.ui():all(WB) == 0),
        "both probe windows are gone -- the suite leaves no widget behind",
        ("%d / %d"):format(#hafen.ui():all(WA), #hafen.ui():all(WB)))

  manualCheck("bring up the Widget Stack window (':widgetstack' if it is hidden), hover a button or a label"
              .. " INSIDE a captioned window (your Inventory, or Character Sheet), press the widgetstack freeze"
              .. " hotkey to hold the stack, then run ':selector' and paste the whole logged block back",
              'the starred line is a CHAIN -- "window[title=<that window>] <role>..." -- reading "(1 match, this'
              .. ' one is #1)", the panel names the widget\'s OWN [text=]/[title=] (not the window\'s) with the'
              .. ' enclosing window on the "anchor:" line, and the last logged line is'
              .. ' hafen.ui():find("<that same chain>")')

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  local winA = hafen.ui():window():title(CAPA):size(240, 110):position(120, 140)
  local btnA = hafen.ui():button():parent(winA):position(8, 8):text(BTXT)
  local wrap = hafen.ui():widget():parent(winA):position(8, 36):size(200, 40)   -- an intervening wrapper
  local lblA = hafen.ui():label():parent(wrap):position(0, 0):text(LTXT)
  local winB = hafen.ui():window():title(CAPB):size(240, 110):position(400, 140)
  local btnB = hafen.ui():button():parent(winB):position(8, 8):text(BTXT)
  -- The builder arms the add for the NEXT tick, so nothing above is findable yet.
  hafen.timer():after(0.2, function()
    local ok, err = pcall(checks, winA, winB, btnA, btnB, lblA)
    if not ok then
      hafen.log():write("[fail] the suite itself raised -- got: " .. tostring(err))
      if winA:exists() then winA:destroy() end
      if winB:exists() then winB:destroy() end
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail + 1, manual))
    end
  end)
end

hafen.slash():register("t049-4", run)   -- the only way in: a suite does not start itself
