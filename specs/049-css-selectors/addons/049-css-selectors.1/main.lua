-- 049.1 — the selector grammar becomes real CSS: the descendant combinator, the four attribute operators,
-- [text=], and the disjointness errors that keep [title=] and [text=] from meaning each other.
-- Self-checking suite; see specs/testing/addon-suite.md.
--
-- It builds its OWN window+button rather than leaning on whatever the player has open, so every structural
-- claim is deterministic. And it proves the spellings the shipped `bags`/`hello` addons were migrated to by
-- ASSERTING THEM HERE -- D-085: a suite stands alone, so a changed spelling is duplicated into it and never
-- delegated to "also run :hello selector". Three things shape the code:
--   * a built window IS NOT IN THE TREE until the next tick (UiApi's armPending), so the checks run from a
--     timer rather than straight after the builder;
--   * the caption carries SPACES on purpose — the combinator splits on whitespace, and it must not cut inside
--     a [ ] value, or the shipped theme's "window[title=Character Sheet]" key would break;
--   * the probe button's text is unique because a Window's own chrome carries a close IButton, which the
--     `button` role matches too — "the button inside my window" is not one widget.

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

-- A refusal is a check: the selector must fail to PARSE, and fail saying what to write instead.
local function refuses(what, sel, wantMsg)
  local ok, err = pcall(function() return hafen.ui():find(sel) end)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local CAP  = "049 Chain Probe"      -- spaces on purpose (see above)
local TEXT = "Probe049"             -- unique: the window's own close IButton is a `button` too

local function checks(win, btn)
  local W = ("window[title=%s]"):format(CAP)
  local function hits(s) return #hafen.ui():all(W .. " " .. s) end

  -- The anchor step itself: a caption with spaces survives the combinator split.
  check(hafen.ui():find(W) == win, "window[title=<caption with spaces>] is the window, split intact")

  -- The combinator reaches inside it, and [text=] names one exact widget within that scope.
  local inButtons, sawBtn = hafen.ui():all(W .. " button"), false
  for i = 1, #inButtons do if inButtons[i] == btn then sawBtn = true end end
  check(sawBtn, "a two-step chain reaches the button inside the window", #inButtons)
  check(hafen.ui():find(W .. " button[text=" .. TEXT .. "]") == btn,
        "chain + [text=] names ONE exact nested widget -- the whole point of the feature")

  -- A widget is not its own descendant: the anchor never comes back from its own chain.
  local inside, sawWin = hafen.ui():all(W .. " *"), false
  for i = 1, #inside do if inside[i] == win then sawWin = true end end
  check((#inside > 0) and not sawWin, "the chain reaches DESCENDANTS only -- the anchor is not one of them",
        ("%d inside, anchor among them=%s"):format(#inside, tostring(sawWin)))

  -- The space is DESCENDANT, not child: somewhere in the live HUD a match sits more than one hop down.
  local deep = 0
  for _, w in ipairs(hafen.ui():all("window *")) do
    local p, hops = w:parent(), 1
    while p and (p:role() ~= "window") do p = p:parent(); hops = hops + 1 end
    if p and (hops > 1) then deep = deep + 1 end
  end
  check(deep > 0, "the space is DESCENDANT, not child: matches sit more than one hop below the anchor", deep)

  -- The four CSS operators, all against the one button whose text we control.
  check((hits("button[text=" .. TEXT .. "]") == 1) and (hits("button[text*=obe0]") == 1)
        and (hits("button[text^=Prob]") == 1) and (hits("button[text$=e049]") == 1)
        and (hits("button[text=Prob]") == 0),
        "= is EXACT (a prefix matches nothing) and *= ^= $= each match")

  -- [res=] used to be an implicit substring; it is exact now, and *= is how you say substring.
  local anyRes
  for _, w in ipairs(hafen.ui():all("*")) do
    local r = w:res()
    if r and (#r > 6) then anyRes = r; break end
  end
  if anyRes then
    local exact  = #hafen.ui():all(("*[res=%s]"):format(anyRes))
    local prefix = #hafen.ui():all(("*[res=%s]"):format(anyRes:sub(1, #anyRes - 1)))
    local sub    = #hafen.ui():all(("*[res*=%s]"):format(anyRes:sub(2, #anyRes - 1)))
    check((exact > 0) and (prefix == 0) and (sub > 0),
          "[res=] is EXACT now and [res*=] is the substring form",
          ("res=%s -> exact %d, prefix %d (want 0), substring %d"):format(anyRes, exact, prefix, sub))
  else
    manualCheck("no widget on screen carries a resource, so [res=] could not be exercised",
                "go in-world with the HUD meters up and re-run ':t049-1'")
  end

  -- The retired spelling is REFUSED at parse time, naming its replacement. Under CSS semantics it would
  -- still parse and merely never match, which is the silent failure the old ancestor rule existed to avoid.
  refuses("inventory[title=X] refuses, naming the chain", "inventory[title=Cupboard]",
          "window[title=Cupboard] inventory")
  refuses("*[title=X] and a bare [title=X] refuse too", "[title=Cupboard]", "window[title=Cupboard]")
  refuses("window[text=X] refuses, naming [title=]", "window[text=Cupboard]", "[title=Cupboard]")

  -- And the old catalogue still errors, so the new grammar took nothing away.
  refuses("an unclosed [ still errors", "window[title=X", "unclosed")
  refuses("a repeated refiner still errors", "window[title=A][title=B]", "more than once")
  refuses("an unknown role still lists them all", "notarole", "is not a role")

  -- THE MIGRATED SPELLINGS THE SHIPPED ADDONS NOW CARRY, proven HERE (D-085: a suite proves its own task
  -- alone, so a changed spelling is duplicated into it rather than delegated to the addon that carries it).
  refuses("bags' OLD spelling is refused, naming the new one", "inventory[title=Inventory]",
          "window[title=Inventory] inventory")
  check(pcall(function() return hafen.ui():find("window[title=Inventory] inventory") end)
        and (type(hafen.ui():all("[res*=gfx/hud/meter]")) == "table"),
        "bags' and hello's migrated spellings both resolve (empty-not-nil, never a raise)")

  -- ...and resolved for real when the window they name is open, which is the claim bags actually rests on.
  local invWin = hafen.ui():find("window[title=Inventory]")
  if invWin then
    local grid, up, reached = hafen.ui():find("window[title=Inventory] inventory"), nil, false
    up = grid and grid:parent()
    while up do
      if up == invWin then reached = true; break end
      up = up:parent()
    end
    check(grid and (grid:role() == "inventory") and reached,
          "bags' migrated selector names the GRID inside the Inventory window",
          grid and (grid:role() .. ", reaches that window=" .. tostring(reached)) or "nil")
  else
    manualCheck("the Inventory window is closed, so bags' selector could not be resolved against a real one",
                "open your inventory (Tab) and re-run ':t049-1' -- it must name the grid inside it")
  end

  win:destroy()
  check(not win:exists(), "the probe window is gone -- the suite leaves no widget behind")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function run()
  local win = hafen.ui():window():title(CAP):size(220, 90)
  local btn = hafen.ui():button():parent(win):position(8, 8):text(TEXT)
  -- The builder arms the add for the NEXT tick, so nothing above is findable yet.
  hafen.timer():after(0.2, function()
    local ok, err = pcall(checks, win, btn)
    if not ok then
      hafen.log():write("[fail] the suite itself raised -- got: " .. tostring(err))
      if win:exists() then win:destroy() end
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail + 1, manual))
    end
  end)
end

hafen.slash():register("t049-1", run)   -- the only way in: a suite does not start itself
