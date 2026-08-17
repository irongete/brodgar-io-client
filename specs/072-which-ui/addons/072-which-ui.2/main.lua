-- 072.2 — the tree says whose it is. Self-checking suite.
--
-- Every claim here held before this task and must still hold: the conversion moved the root reads off an
-- ambient field and onto hafen's host session, and the whole point is that nothing an addon can see moved
-- with them. So each check is one an earlier feature already made -- placing in the tree, searching it,
-- walking up it, and the refusal that names the selector grammar.

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

local TITLE = "t072-2 host tree"
local SEL = 'window[title=' .. TITLE .. ']'

local function run()
  pass, fail, manual = 0, 0, 0            -- the manual step asks for a second run: each one counts itself
  for _, w in ipairs(hafen.ui():all(SEL)) do
    w:destroy()                           -- ...and meets a clean tree, whatever an interrupted run left in it
  end

  -- The root itself: hafen.ui():root() is the top of the host session's tree, and the top has nothing above it.
  local root = hafen.ui():root()
  check(root ~= nil, "hafen.ui():root() answers the host session's root", root)
  check((root ~= nil) and (root:parent() == nil), "that root has no parent -- it IS the top",
        (root ~= nil) and root:parent() or "<no root>")

  -- Placed in that tree, and named back out of it.
  local win = hafen.ui():window():title(TITLE)
  local found = hafen.ui():find(SEL)
  check(found == win, "a window built here is found by selector in that same tree", found)
  local all = hafen.ui():all(SEL)
  check((all ~= nil) and (#all == 1), "...and :all() over the whole tree matches it exactly once",
        (all ~= nil) and #all or "<nil>")

  -- The upward walk ends at the very root hafen.ui():root() answered.
  local top, steps = found, 0
  while (top ~= nil) and (top:parent() ~= nil) and (steps < 64) do
    top = top:parent()
    steps = steps + 1
  end
  check((top == root) and (steps > 0), "widget:parent() walks from it up to that root in " .. steps .. " steps", top)

  -- Registering scans the LIVE tree, so `appear` fires for a window that is already open.
  local seen = nil
  local sub = hafen.ui():on(SEL, "appear", function(w) seen = w end)
  check(seen == win, "hafen.ui():on(sel, \"appear\") fires for the window already in the tree", seen)
  sub:remove()

  refuses("a malformed selector is refused, naming the grammar",
          function() hafen.ui():find('window[title=' .. TITLE) end, "a refiner is written [title=...]")

  -- One of the CLIENT's own windows, in the same tree: reported either way, since only the maintainer can open it.
  local inv = hafen.ui():find("window[title=Inventory]")
  manualCheck("open the Inventory, then run :t072-2 again; this run found "
              .. ((inv ~= nil) and ('window[title=Inventory], title "' .. tostring(inv:title()) .. '"')
                                or "no match for window[title=Inventory]"),
              'window[title=Inventory], title "Inventory"')

  win:destroy()                        -- so a second run does not meet two windows of one name
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t072-2", run)   -- the only way in: a suite does not start itself
