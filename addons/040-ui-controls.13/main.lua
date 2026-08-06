-- 040.13 -- the close: a completeness sweep over the whole 040-ui-controls roster. Self-checking suite;
-- see specs/addons/TESTING.md.
--
-- WHAT THIS TASK CLAIMS. Every earlier 040.* suite proves ONE builder. This one proves the two things
-- none of them can, because both are properties of the WHOLE roster: that all 16 builders in
-- docs/addons/api/ui/controls.md and lists.md exist, are built bare (R4), and come back as the client
-- class the roster promises -- and that the SIX shared names (:text :value :rows :onChange :onPress
-- :onSelect, plus :onSubmit :onCell :range :rowHeight :cell :columns :image :source) answer non-nil on
-- EXACTLY the controls the page says they answer on, and read nil everywhere else. A verb that quietly
-- stopped answering somewhere, or started answering where the page says it should not, is a documentation
-- bug nothing else in this feature would catch -- each earlier suite only ever looked at its own control.
--
-- WHAT IT DELIBERATELY DOES NOT RE-PROVE. Per TESTING.md's duplication rule, a claim belongs to the suite
-- that first makes it: the WRITE-side rules (clamping, rebuild-while-pending, refusing a bad value, the
-- :onChange feedback-loop guarantee) are each task 040.1-040.12's own premise, re-asserted there. This
-- sweep is reads only -- it configures one instance of every control with its own documented verbs so the
-- read half of the matrix sees a real answer, not an unset default, and leaves the write contracts alone.
-- The one write it does re-assert is entry:text(s), because that is the feature's only RETIRED spelling
-- and a sweep claiming completeness that skipped its one exception would not be complete.
--
-- READ-ONLY. It declares no permissions and builds everything inside one scratch window it destroys
-- before it prints its summary, so the run leaves nothing behind to inspect afterwards -- the roster
-- itself is proven by assertion, not by eye. The end-to-end "does it look and feel right" claim belongs
-- to the stockfilter example addon instead, which is what the [manual] line below points at.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- How many widgets are in the whole client tree right now.
local function treeCount()
  local n = 0
  hafen.ui():root():walk(function() n = n + 1 end)
  return n
end

local function has(list, v)
  for _, x in ipairs(list) do if x == v then return true end end
  return false
end

-- ---- the roster, as data -----------------------------------------------------------------------------
-- The 16 builders (docs/addons/api/ui/controls.md + lists.md), the client class a BARE one builds, and
-- the documented verbs it answers -- reads only (see the header). Radio deliberately carries no "text"
-- despite spec.md's own summary table: 040.5 shipped it without one, on the record (STATE.md), and this
-- roster follows the shipped page, not the aspirational table.
local ROSTER = {
  {name = "button",    type = "Button",    verbs = {"text", "onPress"}},
  {name = "entry",     type = "TextEntry", verbs = {"text", "value", "onChange", "onSubmit"}},
  {name = "label",     type = "Label",     verbs = {"text"}},
  {name = "image",     type = "Img",       verbs = {"source"}},
  {name = "progress",  type = "Progress",  verbs = {"value"}},
  {name = "separator", type = "HRuler",    verbs = {}},
  {name = "check",     type = "CheckBox",  verbs = {"text", "value", "onChange"}},
  {name = "radio",     type = "Widget",    verbs = {"rows", "value", "onChange"}},
  {name = "slider",    type = "HSlider",   verbs = {"value", "range", "onChange"}},
  {name = "scroll",    type = "Widget",    verbs = {}},
  {name = "scrollbar", type = "Scrollbar", verbs = {"value", "range", "onChange"}},
  {name = "list",      type = "SListBox",  verbs = {"rows", "value", "onChange", "rowHeight"}},
  {name = "dropdown",  type = "SDropBox",  verbs = {"rows", "value", "onChange", "rowHeight"}},
  {name = "menu",      type = "SListMenu", verbs = {"rows", "onSelect", "rowHeight"}},
  {name = "grid",      type = "GridList",  verbs = {"rows", "onCell", "cell"}},
  {name = "table",     type = "TableBox",  verbs = {"rows", "rowHeight", "columns"}},
}

-- Every verb the roster shares, minus :image()/:source() -- those two are decision E's picture verbs and
-- get their own dedicated check below, because exercising them on :button()/:check() would rebuild those
-- two into IButton/ICheckBox and break the type check above for the very entries this table proves.
local ALL_VERBS = {
  "text", "value", "rows", "onChange", "onPress", "onSelect", "onSubmit", "onCell",
  "range", "rowHeight", "cell", "columns",
}

local function fn() end
local ADD_U, ADD_D, ADD_H, SUB_H =
  "gfx/hud/buttons/addu", "gfx/hud/buttons/addd", "gfx/hud/buttons/addh", "gfx/hud/buttons/subh"

-- Configure one instance of `name` with EVERY read-verb it owns, so the matrix below sees a real answer
-- rather than an unset default. Separator and scroll take none of their own.
local function configure(w, name)
  if     name == "button"    then w:text("Go"):onPress(fn)
  elseif name == "entry"     then w:value("hi"):onChange(fn):onSubmit(fn)
  elseif name == "label"     then w:text("hi")
  elseif name == "image"     then w:source(ADD_U)
  elseif name == "progress"  then w:value(0.5)
  elseif name == "check"     then w:text("hi"):value(true):onChange(fn)
  elseif name == "radio"     then w:rows{"A", "B"}:value("A"):onChange(fn)
  elseif name == "slider"    then w:range(0, 10):value(5):onChange(fn)
  elseif name == "scrollbar" then w:range(0, 10):value(5):onChange(fn)
  elseif name == "list"      then w:rows{"A", "B"}:value("A"):onChange(fn)
  elseif name == "dropdown"  then w:rows{"A", "B"}:value("A"):onChange(fn)
  elseif name == "menu"      then w:rows{"A", "B"}:onSelect(fn)
  elseif name == "grid"      then w:rows{"A"}:onCell(fn)
  elseif name == "table"     then w:columns{{title = "X", width = 20, of = function(r) return "x" end}}:rows{{}}
  end
end

local function run()
  pass, fail, manual = 0, 0, 0
  local ui = hafen.ui()

  -- 1. EVERY BUILDER EXISTS, AND IS BUILT BARE (R4) -- constructed with an argument, each refuses BEFORE
  --    anything is built, naming that it is bare and chained instead.
  local existsBad, r4Bad = nil, nil
  for _, r in ipairs(ROSTER) do
    if type(ui[r.name]) ~= "function" then existsBad = existsBad or (r.name .. " is not callable") end
    local ok, err = pcall(function() ui[r.name](ui, "x") end)
    err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    if ok or (err:find("takes no arguments", 1, true) == nil) then
      r4Bad = r4Bad or (r.name .. "() -> " .. err)
    end
  end
  check(existsBad == nil, "all 16 control builders in the roster exist as callable verbs on hafen.ui()", existsBad)
  check(r4Bad == nil, "every one of the 16 refuses a constructor argument, naming that it is built bare (R4)",
        r4Bad)

  -- 2. BUILD THE WHOLE ROSTER, EACH CONFIGURED WITH ITS OWN VERBS, IN ONE SCRATCH WINDOW.
  local base = treeCount()
  local hold = ui:window():title("040.13 -- scratch"):size(240, 280):position(440, 40)
  local built, y = {}, 8
  for _, r in ipairs(ROSTER) do
    local w = ui[r.name](ui):parent(hold):position(4, y)
    configure(w, r.name)
    built[r.name] = w
    y = y + 14
  end

  -- 3. EACH BUILDS AS THE CLIENT CLASS THE ROSTER PROMISES -- a bare button/check stays Button/CheckBox,
  --    since only :image() (tested separately, step 5) switches either to its picture class.
  local badType = nil
  for _, r in ipairs(ROSTER) do
    local t = built[r.name]:type()
    if t ~= r.type then badType = badType or (r.name .. "() -> " .. t .. " (wanted " .. r.type .. ")") end
  end
  check(badType == nil, "every one of the 16, built bare and configured, is the client class the roster promises",
        badType)

  -- 4. THE VERB MATRIX -- every documented verb answers non-nil EXACTLY on the controls the page says it
  --    answers on, and reads nil everywhere else. One combination per (verb, builder) pair.
  local total, matched, bad = 0, 0, nil
  for _, verb in ipairs(ALL_VERBS) do
    for _, r in ipairs(ROSTER) do
      total = total + 1
      local w = built[r.name]
      local got = w[verb](w)
      local want = has(r.verbs, verb)
      if want == (got ~= nil) then
        matched = matched + 1
      else
        bad = bad or (r.name .. ":" .. verb .. "() -> " .. tostring(got)
                        .. " (wanted " .. (want and "non-nil" or "nil") .. ")")
      end
    end
  end
  check((matched == total) and (bad == nil),
        ("every documented verb answers non-nil exactly on the controls the page says it answers on, and"
          .. " reads nil elsewhere (%d/%d combinations)"):format(matched, total), bad)

  -- 5. THE TWO PICTURE VERBS (decision E) -- :image() on a picture button/checkbox and :source() on a
  --    picture, each answering ONLY there. Built separately: exercising :image() on the roster's own
  --    button/check would rebuild them into IButton/ICheckBox and break step 3's type check.
  local ibtn = ui:button():parent(hold):position(4, y):image(ADD_U, ADD_D)
  local ichk = ui:check():parent(hold):position(4, y + 14):image(ADD_U, ADD_D, ADD_H, SUB_H)
  local pic = ui:image():parent(hold):position(4, y + 28):source(ADD_U)
  check((ibtn:type() == "IButton") and (ibtn:image() ~= nil)
          and (ichk:type() == "ICheckBox") and (ichk:image() ~= nil)
          and (pic:source() ~= nil) and (built.label:image() == nil) and (built.label:source() == nil),
        "decision E's two picture verbs -- :image() on a picture button/checkbox, :source() on a picture --"
          .. " each answer only there",
        ("button=%s/%s check=%s/%s image source=%s"):format(ibtn:type(), tostring(ibtn:image()),
          ichk:type(), tostring(ichk:image()), tostring(pic:source())))

  -- 6. THE ONE RETIRED SPELLING THIS FEATURE SHIPS -- re-asserted here per D-085, since a roster claiming
  --    completeness that skipped its one exception would not be complete.
  refuses("entry:text(s) still throws, naming :value(s), the one retired spelling this feature ships",
          function() built.entry:text("nope") end, "value")

  -- 7. TEARDOWN -- one destroy on the scratch window gives back a tree with no control left in it. The
  --    literal :reload is the [manual] line below; this is the same before/after count 040.9-040.12 each
  --    already assert, re-run here over the WHOLE roster at once.
  hold:destroy()
  local after = treeCount()
  check(after == base,
        "destroying the scratch window gives back a tree with no control left in it -- the whole roster,"
          .. " gone in one destroy", ("base=%d after=%d"):format(base, after))

  manualCheck("run :reload, then :t040-13 again",
              "an identical [summary] line -- the reload rebuilds the addon layer with nothing left over"
                .. " from the run before it")
  manualCheck("run :stockfilter and use the panel end to end",
              "a window with a search field, a sort/hide/quality filter, a backpack-or-equipment dropdown"
                .. " and a results list, all built from these same 16 builders, filtering your real items")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t040-13", run)   -- the only way in: a suite does not start itself
