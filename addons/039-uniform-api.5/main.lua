-- 039.5 — hafen.ui() the section, and the Widget entity. Self-checking suite; see specs/addons/TESTING.md.
--
-- Two cuts land here and they pull against each other, which is why the suite leads with them. hafen.ui() WAS
-- the root widget; it is now the section object, so the tree's top moved to hafen.ui():root() and every lookup
-- became a colon verb. And the Widget entity picks up the grammar's own spellings: :pos -> :position,
-- :rootpos -> :rootPos, :show/:hide -> :visible(b), and the read half of :replace -> :replacement().
--
-- THE POINT OF THE POSITION RENAME IS THAT IT IS *NOT* A POSITION. A widget's place is PIXELS on the screen,
-- and since 039.2 a place in the world is a TYPE -- so handing w:position() to a spatial verb now throws
-- instead of walking the character somewhere wrong. That is asserted through an UNGATED spatial verb, because
-- a read-only suite meets hafen.act's permission gate first and the gate is not the claim under test.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, restores every widget it touches, and drops
-- its own sheet at both ends of the run.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function xy(p) return p and ("%d,%d"):format(p.x, p.y) or "nil" end

-- A native window a [title=] selector names UNIQUELY, so a sheet rule can reach exactly it (036.2's helper,
-- duplicated here because check 5 rests on it -- D-085: a suite is read alone and must convince alone). It
-- must be BORROWED: on a window this addon created the layout verb writes `c` directly rather than naming a
-- level, so the undo would have nothing to drop and the check would be measuring the wrong branch.
local function named()
  for _, w in ipairs(hafen.ui():all("window")) do
    local cap = w:text()
    if cap and cap ~= "" and not cap:find("[%[%]=]") and not (w:info() or {}).owned then
      local sel = "window[title=" .. cap .. "]"
      local all = hafen.ui():all(sel)
      if (#all == 1) and (all[1] == w) and w:position() then return w, sel end
    end
  end
end

-- Is this hit inside our own window? at() answers the DEEPEST widget, which is the content child rather than
-- the chrome, so the question is asked by climbing rather than by comparing.
local function inside(hit, wnd)
  while hit do
    if hit == wnd then return true end
    hit = hit:parent()
  end
  return false
end

-- ---- the run ------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t039-5 reports its own counts
  hafen.ui.skin(nil)              -- ...and starts from a client this suite is holding nothing on

  -- 1. THE SECTION. It is a per-addon singleton handed back by identity, and the root it displaced is a verb
  --    on it: the top of the tree is the widget with no parent, and the first match of "*" in tree order.
  eq("hafen.ui() is one section object, handed back by identity", hafen.ui() == hafen.ui(), true)
  local root = hafen.ui():root()
  check((root ~= nil) and (root:parent() == nil), "hafen.ui():root() is the top of the client tree", root)
  eq("...and it is the very widget the bare hafen.ui() used to hand back: the first match in tree order",
     hafen.ui():find("*") == root, true)
  refuses("hafen.ui(selector) throws naming hafen.ui():find(selector)",
          function() return hafen.ui("window") end, "hafen.ui():find(selector)")
  refuses("hafen.ui.all(sel) throws naming hafen.ui():all(sel)",
          function() return hafen.ui.all end, "hafen.ui():all(selector)")
  refuses("hafen.ui.on(sel, ev, fn) throws naming hafen.ui():on(...)",
          function() return hafen.ui.on end, "hafen.ui():on(selector, event, fn)")

  -- 2. every door still hands back ONE interned entity -- the premise the renames sit on. node(id) is the
  --    round trip that proves it ACROSS doors: take the inventory's own id back through the id door.
  local inv = hafen.ui():inventory()
  check(inv ~= nil, "hafen.ui():inventory() reaches the client's own backpack grid", inv)
  if inv == nil then
    hafen.log():write("[fail] no HUD: run this in-world -- every check below needs the client's own widgets")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    return
  end
  eq("...and hafen.ui():node(id) hands back the SAME object, so the doors agree",
     (inv:id() ~= nil) and (hafen.ui():node(inv:id()) == inv), true)

  -- 3. THE WIDGET RENAMES, each old spelling throwing at the line that wrote it rather than reading nil.
  refuses("widget:pos() throws naming widget:position()", function() return inv:pos() end, "widget:position()")
  refuses("widget:rootpos() throws naming widget:rootPos()",
          function() return inv:rootpos() end, "widget:rootPos()")
  refuses("widget:show() throws naming widget:visible(true)",
          function() return inv:show() end, "widget:visible(true)")
  refuses("widget:hide() throws naming widget:visible(false)",
          function() return inv:hide() end, "widget:visible(false)")
  refuses("widget:replace() throws naming widget:replacement(), the read's own noun",
          function() return inv:replace() end, "widget:replacement()")
  eq("...and widget:replacement() answers nil where this addon stands in for nothing", inv:replacement(), nil)

  -- 4. A BOOLEAN PROPERTY IS A PROPERTY. Round-tripped on a widget of our own, so nothing of the client's is
  --    left toggled -- and an explicit nil is refused, because a write that silently became a READ is the bug
  --    the nil discipline exists for.
  -- A BARE widget, not a window: a window's transparent corner is not hit-testable at all (DefaultDeco.checkhit
  -- owns the caption strip and the content area, not the pixels between), and check 6 needs a top-left it can
  -- ask about. A bare one is its own rectangle all the way to the edge.
  local probe = hafen.ui():widget()
    :size(140, 60)
    :position(160, 160)
  eq("a widget is visible when it is built", probe:visible(), true)
  eq("widget:visible(false) hides it and chains on self", probe:visible(false) == probe, true)
  eq("...and the read says so", probe:visible(), false)
  probe:visible(true)
  refuses("widget:visible(nil) is refused: arity is the verb, so read it with no argument",
          function() probe:visible(nil) end, "must not be nil")
  check(probe:rootPos() ~= nil, "widget:rootPos() answers in root coords", probe:rootPos())

  -- 5. THE UNDO IS A LEVEL, NOT A RESET (D-086/D-089). On a NATIVE window the verb is a layer over the
  --    client's own: position(nil) drops OUR level and re-resolves, so a sheet rule that also names the
  --    window takes it back -- and only dropping the sheet too returns the numbers the user had.
  local wnd, sel = named()
  check(wnd ~= nil, "a native window a [title=] selector names uniquely", sel)
  if wnd ~= nil then
    local stock = wnd:position()
    wnd:position(stock.x + 31, stock.y + 19)
    eq("widget:position(x, y) lays out a window this addon did NOT create",
       xy(wnd:position()), ("%d,%d"):format(stock.x + 31, stock.y + 19))
    wnd:position(nil)
    eq("widget:position(nil) puts it back exactly where the user had it", xy(wnd:position()), xy(stock))
    hafen.ui.skin{ [sel] = { pos = {stock.x + 45, stock.y + 35} } }
    wnd:position(stock.x + 7, stock.y + 9)
    eq("the hand-named verb outranks a rule that also names the window", xy(wnd:position()),
       ("%d,%d"):format(stock.x + 7, stock.y + 9))
    wnd:position(nil)
    eq("...and widget:position(nil) falls back to THE RULE, not to the stock value", xy(wnd:position()),
       ("%d,%d"):format(stock.x + 45, stock.y + 35))
    hafen.ui.skin(nil)
    eq("...and only dropping the sheet as well returns the numbers the user had", xy(wnd:position()), xy(stock))
  end

  -- 6. THE MOVE IS c, NOT A DRAW OFFSET, so the engine's own pointer dispatch follows it. The negative probe
  --    is the old TOP-LEFT + 2, never the old centre: a widget wider than the move still covers its own old
  --    centre, and a check asking "it is no longer here" has to derive "here" from a point the move vacates.
  local was = probe:rootPos()
  check(inside(hafen.ui():at(was.x + 2, was.y + 2), probe),
        "hafen.ui():at() resolves to the probe widget where it stands", xy(was))
  probe:position(420, 380)
  local now = probe:rootPos()
  check(inside(hafen.ui():at(now.x + 2, now.y + 2), probe),
        "...and follows the move to its NEW place: the move is c, not a paint-time offset", xy(now))
  check(not inside(hafen.ui():at(was.x + 2, was.y + 2), probe),
        "...and no longer answers at the top-left it used to cover", xy(was))

  -- 7. A WIDGET'S POSITION IS PIXELS, AND THE TYPE NOW SAYS SO. hafen.world():tile(p) is the ungated spatial
  --    verb, so this refusal is the TYPE talking; hafen.act():moveTo meets the permission gate first, which is
  --    all a suite declaring nothing can see of it -- and is itself the check that the gate still stands.
  refuses("a spatial verb refuses widget:position(): screen pixels are not a place in the world",
          function() return hafen.world():tile(probe:position()) end, "must be a Position")
  refuses("...and hafen.act():moveTo refuses it too, at the permission gate a read-only addon meets first",
          function() return hafen.act():moveTo(probe:position()) end, "actions")

  probe:destroy()
  hafen.ui.skin(nil)
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t039-5", run)   -- the only way in: a suite does not start itself
