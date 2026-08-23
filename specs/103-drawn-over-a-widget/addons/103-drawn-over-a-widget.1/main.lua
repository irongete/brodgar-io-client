-- 103.1 -- the item an icon draws. Self-checking suite.
--
-- WHAT THIS SHIPS. widget:item() is the read an item icon could not answer. widget:items() walks a
-- CONTAINER's icons and the traversal behind it excludes the receiver, so an icon's own :items() is
-- empty by construction -- an addon handed an icon by a selector match or by a MouseDown subscription
-- could read where it is and never what it is. The verb answers the interned Item on an icon, and nil
-- on every other widget and on a stale one.
--
-- HOW IT IS PROVED. Interning is the claim, so the assertion is `==` against the entry the container
-- around the icon lists -- identity, not a field-by-field likeness, which two distinct handles to one
-- item would also satisfy. The nil half is asserted on three shapes at once: the grid the icons sit in,
-- the window around that grid, and a bare widget this suite builds and then destroys.

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

local function finish()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function body()
  local s = hafen.session():current()
  if s == nil then
    check(false, "a character is logged in", "no current session -- run this in the world")
    return finish()
  end

  local inv = s:ui():inventory()
  if inv == nil then
    check(false, "the backpack grid is in the tree", "s:ui():inventory() is nil -- log in and retry")
    return finish()
  end

  -- The precondition is scored, never skipped: an empty backpack is one [fail] naming what to do.
  local icons = inv:matchAll("@WItem")
  local icon = icons[1]
  if icon == nil then
    check(false, "the backpack holds at least one item", "no @WItem under the grid -- put something in"
          .. " your backpack and run :t103 again")
    return finish()
  end

  -----------------------------------------------------------------------------------------------
  -- The read itself, and the identity that is the whole claim.
  -----------------------------------------------------------------------------------------------
  local it = icon:item()
  local entries = inv:items():list()

  local byres = nil                                  -- the entry the container lists for this res
  for _, e in ipairs(entries) do
    if (e:res() ~= nil) and (e:res() == icon:res()) then byres = e; break end
  end

  check((it ~= nil) and (byres ~= nil) and (it == byres),
        "the icon's item IS the entry its container lists, by identity",
        (it == nil) and "icon:item() is nil"
          or ((byres == nil) and ("no entry carrying res " .. tostring(icon:res())
                                  .. " among " .. #entries)
              or ("two distinct objects: " .. tostring(it) .. " / " .. tostring(byres))))

  -- Interning again, through a second read and a freshly taken list: a handle minted per call would
  -- pass the check above and fail this one.
  check((it ~= nil) and (icon:item() == it) and (entries[1] ~= nil)
        and (inv:items():list()[1] == entries[1]),
        "two reads of one icon, and two takings of the list, are the same objects",
        tostring(icon:item()) .. " / " .. tostring(inv:items():list()[1]))

  check((it ~= nil) and (it:res() ~= nil), "the item it hands back answers for itself",
        (it ~= nil) and it:res() or "no item")

  -- Why the verb exists at all: the icon's own :items() has nothing in it.
  check(icon:items():count() == 0, "widget:items() on the icon itself is still empty",
        icon:items():count())

  -----------------------------------------------------------------------------------------------
  -- nil on everything that is not an icon.
  -----------------------------------------------------------------------------------------------
  check(inv:item() == nil, "the grid the icons sit in answers nil", inv:item())

  local win = inv:parent()
  check((win ~= nil) and (win:role() == "window") and (win:item() == nil),
        "the window around the grid answers nil",
        (win == nil) and "the grid has no parent"
          or (tostring(win:role()) .. " -> " .. tostring(win:item())))

  -- :exists() is asserted beside it, so a nil that merely meant "never placed" cannot pass for the claim.
  local bare = hafen.ui():widget():size(40, 40):position(10, 10)
  check((bare:exists() == true) and (bare:item() == nil), "a bare widget of your own answers nil",
        tostring(bare:exists()) .. " -> " .. tostring(bare:item()))

  bare:destroy()
  check((bare:exists() == false) and (bare:item() == nil), "a widget that left the tree answers nil",
        tostring(bare:exists()) .. " -> " .. tostring(bare:item()))

  -----------------------------------------------------------------------------------------------
  -- Arity is the verb: there is nothing to look an item up BY, so an argument is a mistake.
  -----------------------------------------------------------------------------------------------
  refuses("an argument to it is refused, naming the verb",
          function() return icon:item(1) end, "widget:item() takes no arguments")

  finish()
end

-- The synchronous run is one pcall, so a read that throws becomes one [fail] line and a summary
-- rather than a bare stack trace with no verdict under it.
local function run()
  local ok, err = pcall(body)
  if not ok then
    check(false, "the run reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    finish()
  end
end

hafen.slash():on("t103", run)   -- the only way in: a suite does not start itself
