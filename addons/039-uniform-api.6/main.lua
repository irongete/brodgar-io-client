-- 039.6 — the UI builders: window, widget, overlay. Self-checking suite; see specs/addons/TESTING.md.
--
-- The thirteen keys of hafen.ui.window{…} are thirteen setters on the widget the builder hands back, each
-- with a matching bare read. Lua has no keyword arguments -- f{…} is only sugar for f({…}) -- so the config
-- table was never a style choice, and chaining is the one other spelling the language has for named ones.
--
-- THE CLAIM THAT NEEDED A MECHANISM is that a half-configured surface never paints. It is built with the
-- client's own defaults and is in the tree at once (so every lookup still finds it), but it draws NOTHING
-- until the first tick after the statement that built it. The last round measures exactly that, and it is
-- the only check here that could redden if the arming were removed: within one Lua statement no frame can
-- fall, so a surface built and configured in one breath is complete before any draw whether the engine
-- promises it or not. Building one from INSIDE another surface's tick is the case where the promise is the
-- difference, so that is where the round builds it.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, and destroys every surface it builds.

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

local function nop() end

-- Every builder setter, with the write that must return self and the read that must give it back. One table
-- so the thirteen are one loop and two verdict lines rather than twenty-six: a failure names the verb it was.
local function setters()
  local font = hafen.font("mono")
  local root = hafen.ui():root()
  local t = {
    { "title",    function(w) return w:title("probe") end,  function(w) return w:title() == "probe" end },
    { "parent",   function(w) return w:parent(root) end,    function(w) return w:parent() == root end },
    { "position", function(w) return w:position(12, 14) end,
                  function(w) local p = w:position() return (p ~= nil) and (p.x == 12) and (p.y == 14) end },
    -- a WINDOW's :size(w, h) sizes the CONTENT and refits the chrome around it, so the outer box it reads
    -- back is the pair plus the frame -- which is 029.2's contract, not this task's, and is asserted as such.
    { "size",     function(w) return w:size(120, 40) end,
                  function(w) local s = w:size() return (s ~= nil) and (s.x >= 120) and (s.y >= 40) end },
    { "font",     function(w) return w:font(font) end,      function(w) return w:font() == font end },
  }
  for _, nm in ipairs({ "onDraw", "onTick", "onClick", "onMouseUp", "onMouseMove", "onWheel", "onDrop", "onClose" }) do
    t[#t + 1] = { nm, function(w) return w[nm](w, nop) end, function(w) return w[nm](w) == nop end }
  end
  return t
end

-- ---- the last round: a surface does not paint in the frame it was built in --------------------------------
-- Built from inside a widget's own tick, which is AFTER the engine armed everything built this frame -- so
-- without the arming the new surface would be reached by this very frame's draw pass, and with it the first
-- paint lands one frame later. The clock widget is the frame counter and the thing that does the building.
local function paintRound(finish)
  local frames, builtAt, paintedAt, done = 0, nil, nil, false
  local clock = hafen.ui():widget():size(6, 6):position(2, 2)
  local probe
  local function report()
    if done then return end                          -- the deadline and the round both call this
    done = true
    check((builtAt ~= nil) and (paintedAt ~= nil) and (paintedAt > builtAt),
          "a surface built mid-frame paints only after its arming tick, never in the frame that built it",
          ("built in frame %s, first painted in frame %s"):format(tostring(builtAt), tostring(paintedAt)))
    if probe then probe:destroy() end
    clock:destroy()
    finish()
  end
  clock:onTick(function()
    frames = frames + 1
    if builtAt or (frames < 3) then return end       -- let the clock itself be on screen first
    builtAt = frames
    probe = hafen.ui():widget():size(6, 6):position(12, 2)
    probe:onDraw(function() if not paintedAt then paintedAt = frames end end)
    hafen.timer():after(0.5, report)
  end)
  hafen.timer():after(3.0, report)                   -- a round that never got its frames still closes the block
end

local function run()
  -- 1. THE PREMISE THIS TASK STANDS ON (D-085: a suite convinces alone). hafen.ui() is the section, handed
  --    back by identity, and the builders are verbs on it rather than keys beside it.
  eq("hafen.ui() is the section object, the same one on every call", hafen.ui() == hafen.ui(), true)
  refuses("hafen.ui.window{...} throws naming the chained form",
          function() return hafen.ui.window end, "hafen.ui():window()")
  refuses("hafen.ui.widget{...} throws naming the chained form",
          function() return hafen.ui.widget end, "hafen.ui():widget()")
  refuses("hafen.ui.overlay(fn) throws naming the painter's own setter",
          function() return hafen.ui.overlay end, "hafen.ui():overlay():onDraw(fn)")

  -- 2. BUILT BARE, WITH THE CLIENT'S OWN DEFAULTS. A window exists for the length of the statement with no
  --    caption and a place and a size it did not choose -- which is the state every setter below starts from.
  local bare = hafen.ui():window()
  local bp, bs = bare:position(), bare:size()
  check((bare:title() == "") and (bp.x == 100) and (bp.y == 100) and (bs.x > 0) and (bs.y > 0)
        and (bare:parent() == hafen.ui():root()),
        "a bare window exists at once: no caption, the client's default place and size, under the root",
        ("title=%q %d,%d %dx%d"):format(bare:title(), bp.x, bp.y, bs.x, bs.y))
  refuses("...and the constructor itself takes nothing: an opts table names the setters that replaced it",
          function() return hafen.ui():window({ title = "x" }) end, "takes no arguments")

  -- 3. EVERY SETTER RETURNS SELF, AND EVERY SETTER READS BACK. Thirteen of each, two lines: the `got` names
  --    the verb that broke, which is the whole reason the table exists.
  local list = setters()
  local badSelf, badRead
  for _, s in ipairs(list) do
    if s[2](bare) ~= bare then badSelf = s[1] break end
  end
  check(badSelf == nil, "all thirteen builder setters return the widget itself, so a chain is one expression",
        badSelf)
  for _, s in ipairs(list) do
    if not s[3](bare) then badRead = (badRead or s[1]) end
  end
  check(badRead == nil, "...and every one reads back bare: arity is the verb on the builders too", badRead)

  -- 4. THE REFUSALS THE SETTERS OWN. A nil write is an accident with nothing to undo (it would silently
  --    become a READ); a caption belongs to chrome a bare widget does not have; and a native widget has
  --    nowhere to put a callback of ours.
  refuses("widget:onDraw(nil) is refused: a nil that silently became a read is the bug the rule exists for",
          function() bare:onDraw(nil) end, "must not be nil")
  local plain = hafen.ui():widget()
  refuses("widget:title(s) on a bare widget names the builder that has chrome to write it on",
          function() plain:title("x") end, "hafen.ui():window()")
  refuses("...and a callback setter refuses a widget this addon did not create",
          function() hafen.ui():root():onDraw(nop) end, "NATIVE widget")

  -- 5. THE PARENT IS A WIDGET, NOT A WORD. `parent = "gameui"` was the last string in this section that named
  --    a widget; the HUD is now the same selector every lookup takes. Re-homing is a thing you do while the
  --    surface is being BUILT, so the refusal after that names the verb that moves one on screen.
  local hud = hafen.ui():find("@GameUI")
  local homed = hafen.ui():widget()
  check((hud ~= nil) and (homed:parent(hud) == homed) and (homed:parent() == hud),
        "widget:parent(w) re-homes a surface while it is being built, and the HUD is just another widget",
        tostring(hud))

  -- 6. THE HUD OVERLAY IS A BUILDER TOO, and the one thing this section builds that is not a widget. It mints
  --    rather than collecting because a HUD painter has no key: there is nothing to :get(). It ends with
  --    :destroy(), like the other two, and the handle's old :remove() says so.
  local ov = hafen.ui():overlay()
  eq("a HUD overlay is built bare, with no painter and nothing to paint", ov:onDraw(), nil)
  eq("overlay:onDraw(fn) chains on self", ov:onDraw(nop) == ov, true)
  eq("...and reads back", ov:onDraw(), nop)
  eq("a live overlay exists", ov:exists(), true)
  ov:destroy()
  eq("...and overlay:destroy() ends it", ov:exists(), false)
  refuses("the old handle's :remove() throws naming :destroy()", function() return ov.remove end, ":destroy()")
  refuses("...and hafen.ui():overlay(fn) refuses the painter as an argument",
          function() return hafen.ui():overlay(nop) end, "onDraw(fn)")

  -- 7. WHAT YOU BUILD IS WHAT YOU FIND (029.2), and it is findable from the first instant: the surface is in
  --    the tree while it is still being configured, which is why the arming skips the DRAW and not the attach.
  --    A BARE widget, not a window: a window's transparent corner is not hit-testable at all, and this needs a
  --    top-left it can ask about (036.3). 420,380 is the point 039.5's own hit-test round proved is clear.
  plain:size(40, 20):position(420, 380)
  local rp = plain:rootPos()
  check(hafen.ui():at(rp.x + 2, rp.y + 2) == plain,
        "hafen.ui():at() resolves to a surface built in this very statement", tostring(rp and rp.x))

  bare:destroy()
  plain:destroy()
  homed:destroy()

  -- 8. The measured claim, one round later (it needs frames to happen in).
  paintRound(function()
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.slash():register("t039-6", run)   -- the only way in: a suite does not start itself
