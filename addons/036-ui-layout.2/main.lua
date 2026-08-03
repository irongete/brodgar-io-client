-- 036.2 — pos and size as sheet properties. Self-checking suite; see specs/addons/TESTING.md.
--
-- 036.1 made the geometry verbs work on a native widget. This task puts a CASCADE above them: `pos` and `size`
-- join the sheet, folded per property by the same fold as font/color/bg/border/pad (D-076), and the verbs become
-- the hand-named TOP of that one fold (D-077) rather than a second mechanism beside it. So the two claims worth
-- asserting are (a) a rule lays a window out and dropping it gives the exact numbers back, and (b) the levels
-- compose in the right order -- the verb wins over a rule, and :pos(nil) drops back to THE RULE, not to stock.
--
-- Everything here is a number, read back through the very verbs the task ships. The one thing no program can do
-- is log out and back in, and that is where the feature's real risk lives: the client persists a few window
-- positions of its own, and a RULE is a new path into that write, so the [manual] line is this task's too.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, and drops its sheet before it prints.

local pass, fail, manual = 0, 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log("[manual] " .. step .. " -- expect: " .. expect)
end

local function xy(p) return p and ("%d,%d"):format(p.x, p.y) or "nil" end

-- A native window a [title=] selector names UNIQUELY, picked rather than named and SELF-VALIDATING on both
-- axes (030.2's inspector trick): a candidate counts only after hafen.ui.all() has actually resolved the
-- selector to exactly this widget, so the rules below run against a key the engine agrees with on whatever
-- HUD this happens to be. Captions carrying the grammar's own punctuation are skipped rather than escaped.
local function named(skip)
  for _, w in ipairs(hafen.ui.all("window")) do
    local cap = w:text()
    if w ~= skip and cap and cap ~= "" and not cap:find("[%[%]=]") then
      local sel = "window[title=" .. cap .. "]"
      local all = hafen.ui.all(sel)
      if (#all == 1) and (all[1] == w) then return w, sel end
    end
  end
end

-- ...and one the client lets an addon RESIZE. 036.1's finding is that a window packing around its content
-- (the inventory's Hidewnd, cresize(ch){pack()}) sizes itself straight back, so the size target is validated
-- with the verb first and put back at once -- otherwise a green suite would prove nothing about `size`.
local function resizable()
  for _, w in ipairs(hafen.ui.all("window")) do
    local cap, was = w:text(), w:size()
    if cap and cap ~= "" and not cap:find("[%[%]=]") and was then
      local sel = "window[title=" .. cap .. "]"
      local all = hafen.ui.all(sel)
      if (#all == 1) and (all[1] == w) then
        w:size(was.x + 40, was.y + 30)
        local took = xy(w:size()) ~= xy(was)
        w:size(nil)
        if took and (xy(w:size()) == xy(was)) then return w, sel end
      end
    end
  end
end

-- ---- the run ------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t036-2 reports its own counts
  hafen.ui.skin(nil)              -- ...and starts from a client this suite is holding nothing on

  -- 1. the premise, re-asserted here because everything below rests on it (D-085): a native window is
  --    reachable, is BORROWED, and a selector names it and nothing else.
  local w, sel = named()
  check(w ~= nil, "a native window a [title=] selector names uniquely, validated through hafen.ui.all()", sel)
  if w == nil then
    hafen.log("[fail] no HUD: run this in-world -- every check below needs one of the client's own windows")
    hafen.log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  eq("...and it is a widget this addon did NOT create", w:info().owned, false)
  local other = named(w)
  local stock, ostock = w:pos(), other and other:pos()

  -- 2. THE TASK: pos is a sheet property, applied when the sheet is -- not a frame later, and not at the draw.
  hafen.ui.skin{ [sel] = { pos = {stock.x + 45, stock.y + 35} } }
  eq("a sheet pos rule moves the window it names, read back through widget:pos()",
     xy(w:pos()), ("%d,%d"):format(stock.x + 45, stock.y + 35))
  if other ~= nil then
    eq("...and moves nothing it does not name", xy(other:pos()), xy(ostock))
  end
  eq("widget:style() reports the layout the sheet resolved", xy(w:style().pos),
     ("%d,%d"):format(stock.x + 45, stock.y + 35))

  -- 3. ONE FOLD, TWO LEVELS (D-077): the hand-named verb outranks the rule, and its undo drops back to the
  --    RULE rather than to stock -- widget:pos(nil) removes a level, it does not empty the cascade.
  w:pos(stock.x + 7, stock.y + 9)
  eq("the verb wins over a rule that also names the widget", xy(w:pos()),
     ("%d,%d"):format(stock.x + 7, stock.y + 9))
  w:pos(nil)
  eq("...and widget:pos(nil) falls back to THE RULE, not to the stock value", xy(w:pos()),
     ("%d,%d"):format(stock.x + 45, stock.y + 35))

  -- 4. per property, most specific wins (D-076) -- and a role rule reaches every window, which is what makes
  --    the [title=] one above it worth having.
  hafen.ui.skin{ ["window"] = { pos = {60, 70} }, [sel] = { pos = {stock.x + 45, stock.y + 35} } }
  eq("a [title=] rule outranks a role rule on the widget it names", xy(w:pos()),
     ("%d,%d"):format(stock.x + 45, stock.y + 35))
  if other ~= nil then
    eq("...while the role rule reaches the windows it does not", xy(other:pos()), "60,70")
  end

  -- 5. dropping the sheet restores the EXACT numbers -- the 035.2 method, and the whole point of a layer.
  hafen.ui.skin(nil)
  eq("dropping the sheet puts the window back exactly where the user had it", xy(w:pos()), xy(stock))
  if other ~= nil then
    eq("...and every other window the sheet reached with it", xy(other:pos()), xy(ostock))
  end

  -- 6. size is the same property one axis along, on a window the client actually lets us resize.
  local rw, rsel = resizable()
  check(rw ~= nil, "a native window the client lets an addon resize (036.1: one that packs around its content"
        .. " sizes itself straight back)", rsel)
  if rw ~= nil then
    local rstock = rw:size()
    hafen.ui.skin{ [rsel] = { size = {rstock.x + 40, rstock.y + 30} } }
    check(xy(rw:size()) ~= xy(rstock), "a sheet size rule resizes the window it names", xy(rw:size()))
    hafen.ui.skin(nil)
    eq("...and dropping it restores the outer box byte for byte", xy(rw:size()), xy(rstock))
  end

  -- 7. a rule naming nothing is inert, never an error.
  hafen.ui.skin{ ["@NoSuchWidgetClass"] = { pos = {11, 22} } }
  eq("a rule naming nothing lays nothing out", xy(w:pos()), xy(stock))
  hafen.ui.skin(nil)

  -- 8. the refusals, and both are about WHERE layout may be said: a site key names a render site, which has no
  --    position, and widget:skin says what a widget is drawn WITH -- the hand-named level is the verb.
  refuses("pos on a site key is refused, because \"*\" is the default SITE and not every widget",
          function() hafen.ui.skin{ ["*"] = { pos = {1, 1} } } end, "lays out a WIDGET")
  refuses("widget:skin{pos=} is refused: the hand-named level of the layout cascade is widget:pos(x, y)",
          function() w:skin{ pos = {1, 1} } end, "widget:pos(x, y)")
  refuses("an unknown property is still an error, layout or not (D-072)",
          function() hafen.ui.skin{ [sel] = { positoin = {1, 1} } } end, "not a style property")
  eq("...and a refused sheet leaves the client exactly as it was", xy(w:pos()), xy(stock))

  hafen.ui.skin(nil)
  manualCheck("log out to the character screen, disable \"036.2 — pos and size as sheet properties\" in the"
    .. " AddOns panel, log back in with the same character, and look at the inventory, equipment, character"
    .. " sheet, kin and map windows",
    "each one is where YOU last dragged it -- a sheet rule is a new path into the same write 036.1 answered,"
    .. " and the client must still persist the user's position and never the rule's")
  hafen.log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- ON DEMAND ONLY (D-085). A suite does not start itself, and running THIS command alone is the whole
-- verification of task 036.2: it installs its own sheets, asserts through the API it ships, and drops
-- everything before it prints -- so a client it has run on is a stock client.
hafen.slash.register("t036-2", run)
