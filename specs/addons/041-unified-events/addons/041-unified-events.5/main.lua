-- 041.5 — The mouse entity, the grab, and the end of hafen.hook(). Self-checking suite; see specs/addons/TESTING.md.
--
-- WHAT THIS TASK CLAIMS. hafen.ui():mouse() stops being a {x=,y=} table read and becomes the pointer entity --
-- the same shape hafen.player() has, where the section's one thing IS the object: :x() :y() :over() :shift()
-- :ctrl() :alt() :grab(). mouse():grab() takes NO arguments (the old {move=,up=} config table is cut, R4) and
-- hands back an emitter over the SAME Subs mechanism as everything else: g:on("Move"/"Up", fn), g:release().
-- hafen.hook() is DELETED as a whole -- grab was its last verb, and slash/keybindings moved there long ago, so
-- there is nothing left to keep a section object around for.
--
-- WHAT A PROGRAM CANNOT DRIVE. Dragging the real mouse needs a human (D-017's sandbox has no synthetic input);
-- that is the one [manual] line. Everything else -- the entity's shape, the grab's construction/refusal/on/
-- off/release, and every retired spelling throwing -- is asserted directly. Teardown releasing a grab left
-- open cannot be proven from inside one run either (there is no Lua-callable :reload), so this run leaves ONE
-- grab open on purpose and the manual line's second half is running :reload and confirming it let go.
--
-- READ-ONLY: declares no permissions, mutates no persistent state. Every grab this suite creates for the
-- automated checks is released before the summary prints; the one exception is the deliberate leftover, which
-- is exactly what the manual step needs left behind.

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

local function nop() end
local function isSub(v) return (type(v) == "userdata") and (type(v.off) == "function") end

local function run()
  pass, fail, manual = 0, 0, 0

  -- 1. THE ENTITY. mouse() is never nil (a per-addon singleton, like hafen.player()), and the old {x=,y=}
  --    TABLE READ is gone: .x/.y now find the live VERB (a function), not a number or nil -- the same outcome
  --    ev.msg already established for LuaEvent (041.2): no metamethod can tell a dot read from a colon call,
  --    so a live member answers .name with the function it always was. What is gone is the shape, not the name.
  local m = hafen.ui():mouse()
  check(m ~= nil, "hafen.ui():mouse() is never nil -- the pointer, like the player, always exists", m)
  check(m == hafen.ui():mouse(), "hafen.ui():mouse() is a per-addon singleton, handed back by identity")
  check((type(m.x) == "function") and (type(m.y) == "function"),
        "the old {x=,y=} table read is gone: .x/.y find the VERB now, not a number",
        ("x=%s y=%s"):format(type(m.x), type(m.y)))
  check((type(m:x()) == "number") and (type(m:y()) == "number"), ":x()/:y() answer the cursor position",
        ("%s, %s"):format(tostring(m:x()), tostring(m:y())))

  -- 2. :over() AGREES WITH :at(), and :at(x,y) is NOT absorbed -- it still answers an ARBITRARY point.
  local overW, atW = m:over(), hafen.ui():at(m:x(), m:y())
  check(overW == atW, ":over() agrees with hafen.ui():at(m:x(), m:y())", tostring(overW) .. " vs " .. tostring(atW))
  local root = hafen.ui():root()
  check((root ~= nil) and (hafen.ui():at(2, 2) ~= nil),
        "hafen.ui():at(x, y) still answers for an arbitrary point, not just the cursor's", tostring(root))

  -- 3. MODIFIERS. Three flat booleans, readable ANY time -- not only from inside a grab handler (that reach
  --    is new: UI.modflags() was public and reached Lua nowhere before this task).
  check((type(m:shift()) == "boolean") and (type(m:ctrl()) == "boolean") and (type(m:alt()) == "boolean"),
        "the three modifier verbs answer booleans, any time -- not only inside a handler",
        ("%s/%s/%s"):format(tostring(m:shift()), tostring(m:ctrl()), tostring(m:alt())))

  -- 4. THE GRAB: bare construction (R4 cuts the {move=,up=} table), an emitter over the SAME Subs door as
  --    every other :on(key, fn) in the API -- a CLOSED key set, two independent Subs, idempotent :off()/:release().
  refuses("mouse():grab() takes no arguments -- the config table is cut (R4)",
          function() m:grab({ move = nop, up = nop }) end, "takes no arguments")
  local g = m:grab()
  check(g ~= nil, "mouse():grab() hands back a grab", g)
  local moveSub, upSub = g:on("Move", nop), g:on("Up", nop)
  check(isSub(moveSub) and isSub(upSub) and (moveSub ~= upSub),
        "a grab answers :on(\"Move\", fn) and :on(\"Up\", fn), each with its own Sub",
        tostring(moveSub) .. ", " .. tostring(upSub))
  refuses("a grab's key set is CLOSED -- an unknown one throws naming what it does answer",
          function() g:on("Down", nop) end, "Move, Up")
  moveSub:off()
  check(pcall(function() moveSub:off() end), "a second sub:off() on a grab's Sub is a no-op, like everywhere else")
  upSub:off()
  g:release()
  check(pcall(function() g:release() end), "a second :release() is a no-op")

  -- 5. THE RETIREMENT. hafen.hook is GONE AS A WHOLE (a section name, so this is one row on the hafen table's
  --    own __index -- reading hafen.hook fails before any .verb or :verb() is ever reached), and the old
  --    config-table spelling throws through that SAME refusal. slash/keybindings, which never lived under
  --    grab, are untouched.
  refuses("hafen.hook is gone as a whole", function() return hafen.hook end, "hafen.ui():mouse():grab()")
  refuses("hafen.hook() throws the same way (the read fails before the call is ever attempted)",
          function() return hafen.hook() end, "hafen.ui():mouse():grab()")
  refuses("hafen.hook():grab{move=,up=} throws naming its replacement",
          function() hafen.hook():grab{ move = nop, up = nop } end, "hafen.ui():mouse():grab()")
  check(type(hafen.slash) == "table", "hafen.slash() is untouched -- only grab moved, not the other two doors",
        type(hafen.slash))
  check(type(hafen.client) == "table", "hafen.client():options():keybindings() is untouched too",
        type(hafen.client))

  -- 6. TEARDOWN. A grab left OPEN (never released) is released on :reload/disable, not leaked -- the one claim
  --    this run cannot prove by itself (no Lua-callable :reload). So it leaves exactly one behind on purpose;
  --    the manual step's second half is the proof: :reload, then drag with nothing held, and the camera pans.
  local leftover = hafen.ui():mouse():grab()
  check(leftover ~= nil, "a grab left open (for the :reload half of the manual check below) constructs cleanly",
        leftover)

  manualCheck("hold a grab and drag across the map (e.g. `:lua local g = hafen.ui():mouse():grab();"
    .. " g:on(\"Move\", function(ev) end)`), then release it with `g:release()`; separately, run `:reload`"
    .. " (this suite just left ONE grab open) and drag again with nothing held",
    "while held: the camera does not pan and no move order is sent; after :release(): both work again;"
    .. " after :reload with nothing re-grabbed: the camera pans normally too -- the leftover grab was released"
    .. " by teardown, not left capturing forever")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t041-5", run)   -- the only way in: a suite does not start itself
