-- 078.1 — which half each verb is in, and the number that proves it. Self-checking suite.
--
-- The three verdicts this task records, proved through the API rather than read off a page: the SCREEN's
-- verbs answer about a point the caller picks and about the client's own scale, and the LAYER's verbs
-- build something of yours. Both halves keep their spelling, so nothing here is a retirement -- the
-- checks are that the verbs mean what verbs.md says they mean.

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

-- Walk :parent() to the top of whatever tree a widget stands in, bounded so a cycle cannot hang the tick.
local function top(w)
  for _ = 1, 200 do
    local p = w:parent()
    if p == nil then return w end
    w = p
  end
  return w
end

-- ---------------------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0
  local root = hafen.ui():root()
  if root == nil then
    log("[fail] a UI must be up -- got: no tree at all")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end
  local sz = root:size()
  check((type(sz.x) == "number") and (type(sz.y) == "number") and (sz.x > 0) and (sz.y > 0),
        "hafen.ui():root() answers, and the client measures " .. tostring(sz.x) .. "x" .. tostring(sz.y)
        .. " design pixels", tostring(sz.x) .. "x" .. tostring(sz.y))

  -- THE SCREEN, verb by verb. The pointer first: one cursor, inside the one box the client draws.
  local m = hafen.ui():mouse()
  local mx, my = m:x(), m:y()
  check((type(mx) == "number") and (type(my) == "number")
        and (mx >= 0) and (mx <= sz.x) and (my >= 0) and (my <= sz.y),
        "hafen.ui():mouse() reads the pointer inside the client's own box",
        tostring(mx) .. "," .. tostring(my))

  -- :at(x, y) handed the pointer IS m:over() -- one pair, asked two ways, and the reason both are the
  -- screen's rather than a character's.
  check(hafen.ui():at(mx, my) == m:over(),
        "hafen.ui():at(m:x(), m:y()) is m:over() -- one pointer, one coordinate space",
        tostring(hafen.ui():at(mx, my)) .. " vs " .. tostring(m:over()))

  -- ...and handed a point the SUITE picks it answers for THAT point. A verb that quietly read the pointer
  -- would hand back the widget under the cursor here; off the screen there is nothing to hand back.
  check(hafen.ui():at(-4000, -4000) == nil,
        "hafen.ui():at(x, y) answers for the point it is given, not for the pointer",
        hafen.ui():at(-4000, -4000))
  local cx, cy = math.floor(sz.x / 2), math.floor(sz.y / 2)
  local hit = hafen.ui():at(cx, cy)
  check((hit ~= nil) and (top(hit) == root),
        "a point in the middle of the client resolves into the client's own tree",
        (hit == nil) and "nil" or tostring(top(hit) == root))
  refuses("hafen.ui():at() with no coordinate refuses, naming the argument",
          function() return hafen.ui():at() end, "hafen.ui():at", "x is required")

  -- The scale is the client's: one number for the whole client, and a read only.
  local f = hafen.ui():scale()
  check((type(f) == "number") and (f >= 1.0), "hafen.ui():scale() reads the factor in force (" .. tostring(f)
        .. ")", f)
  refuses("hafen.ui():scale(v) refuses, naming the setting that does write it",
          function() return hafen.ui():scale(2) end, "hafen.client():options():interface():scale(v)")

  -- YOURS, the layer. :window() builds and places one -- in the tree at once, at the place you gave it.
  local win = hafen.ui():window():title("078.1"):position(64, 96)
  local at = win:position()
  check(win:exists() and (win:parent() ~= nil) and (at.x == 64) and (at.y == 96),
        "hafen.ui():window() builds a window and places it where it was put",
        tostring(win:exists()) .. " parent=" .. tostring(win:parent()) .. " at "
        .. tostring(at.x) .. "," .. tostring(at.y))
  check(top(win) ~= root,
        "the window stands in the addon layer, not in the tree hafen.ui():root() answers for",
        tostring(top(win)) .. " vs " .. tostring(root))

  -- :widget() is the same builder without the chrome -- a CONSTRUCTOR of yours, not a lookup. It takes no
  -- argument at all, which is what tells it apart from :find(selector) and :node(id).
  local bare = hafen.ui():widget():size(40, 20)
  check(bare:exists() and (bare:parent() ~= nil) and (bare:type() ~= nil),
        "hafen.ui():widget() builds a bare surface of yours (" .. tostring(bare:type()) .. ")",
        tostring(bare:exists()))
  refuses("hafen.ui():widget(selector) refuses -- it builds, it does not look up",
          function() return hafen.ui():widget("window[title=Inventory]") end,
          "hafen.ui():widget() takes no arguments", "built bare")

  win:destroy()
  bare:destroy()

  manualCheck("open specs/078-your-window-and-the-clients/verbs.md at \"The targets\" and paste the row"
              .. " figures, then run the checklist and guardrail greps it names",
              "verbs.md states 298 occurrences now and 196 after (288 lines now, 190 after), and the"
              .. " guardrail grep reads 212")

  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t078-1", run)   -- the only way in: a suite does not start itself
