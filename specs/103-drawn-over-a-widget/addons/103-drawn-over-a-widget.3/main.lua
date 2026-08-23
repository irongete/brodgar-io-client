-- 103.3 -- what is drawn over one widget. Self-checking suite.
--
-- WHAT THIS SHIPS. widget:overlay() is the third receiver of a word that means one thing in this API:
-- keyed decorations bound to a thing. The screen has hafen.ui():overlay() and a game object has
-- gob:overlay(); one widget had nothing, so a number on an item icon or a mark on a button had to be
-- re-derived every frame by a screen-wide painter searching the tree for a rectangle. The collection
-- ships whole -- :add :get :remove :list :count :find, members interned, the collection a view -- with
-- the :draw(fn) kind, called with (g, w, h) in the widget's own box.
--
-- HOW IT IS PROVED. The claim that cannot be read back from any verb is THE SEAM: that a painter hung
-- on a widget is actually called by the client's draw traversal. So the painter counts its own frames
-- into an upvalue and records the (w, h) it was handed, and a timer reads both a second later -- a
-- check that can only pass if the client ran it. Everything else is read back straight: the ordering,
-- the replace, the inert removal, the refusals, and the death of an overlay with its widget.

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

local function finish()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- The keys of :list(), in the order it hands them back -- which the page calls the draw order.
local function keys(coll)
  local out = {}
  for _, ov in ipairs(coll:list()) do out[#out + 1] = ov:key() end
  return table.concat(out, ",")
end

-- What the painter writes down about being called at all. Read from the timer below.
local ran, gotw, goth = 0, nil, nil
local own, mine

-- The deferred half: everything that needs the client to have DRAWN a frame first.
local function deferred()
  local size = own:size()
  check((ran > 0) and (gotw == size.w) and (goth == size.h),
        "the painter runs from the draw seam, and is handed the widget's own box",
        ran .. " call(s), (w, h) = " .. tostring(gotw) .. "x" .. tostring(goth)
          .. " against a widget of " .. size.w .. "x" .. size.h)

  own:destroy()
  check((own:exists() == false) and (mine:exists() == false) and (own:overlay():count() == 0)
        and (own:overlay():get("ran") == nil) and (mine:key() == "ran"),
        "an overlay dies with the widget it hangs on, and still answers :key()",
        "widget=" .. tostring(own:exists()) .. " overlay=" .. tostring(mine:exists())
          .. " left=" .. own:overlay():count())

  refuses("adding to a widget that has left the tree is refused, naming the tree",
          function() return own:overlay():add("late") end, "has left the tree")

  manualCheck("look at the bottom-right corner of your backpack grid, then hide or close the backpack",
              "a red square tucked INTO the corner, cut off at the grid's edge rather than overhanging"
                .. " it, and gone with the window while it is hidden (it stays until :reload)")
  finish()
end

-- ...under the same one pcall the synchronous half runs under, for the same reason.
local function later()
  local ok, err = pcall(deferred)
  if not ok then
    check(false, "the deferred half reached its end", tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
    finish()
  end
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

  -----------------------------------------------------------------------------------------------
  -- The widget the suite builds, and the painter whose running IS the seam.
  -----------------------------------------------------------------------------------------------
  own = hafen.ui():widget():size(60, 40):position(140, 140)
  mine = own:overlay():add("ran"):draw(function(g, w, h)
    ran = ran + 1
    gotw, goth = w, h
    g:color(0, 200, 255)
    g:rect(0, 0, w, h)
  end)

  check((mine ~= nil) and (mine:key() == "ran") and (mine:kind() == "draw")
        and (mine:exists() == true) and (own:overlay():get("ran") == mine),
        "an overlay added on a widget is live, keyed, and the one :get(key) hands back",
        tostring(mine) .. " kind=" .. tostring(mine and mine:kind()))

  local ovs = own:overlay()
  check((own:overlay() ~= own:overlay()) and (ovs:get("ran") == own:overlay():get("ran")),
        "two :overlay() calls are two views, while two :get(key) are one object",
        tostring(own:overlay() == own:overlay()) .. " / "
          .. tostring(ovs:get("ran") == own:overlay():get("ran")))

  -----------------------------------------------------------------------------------------------
  -- A bare one, the ordering, and the replace.
  -----------------------------------------------------------------------------------------------
  local bare = ovs:add("bare")
  check((bare:exists() == true) and (bare:kind() == nil) and (bare:draw() == nil)
        and (bare:info().key == "bare") and (bare:info().kind == nil),
        "a bare overlay is live, says no kind and carries no painter -- so it paints nothing",
        "kind=" .. tostring(bare:kind()) .. " draw=" .. tostring(bare:draw()))

  ovs:add("last"):draw(function(g, w, h) end)
  local before = keys(ovs)
  local again = ovs:add("bare")                 -- the same key again: still one member, now at the end
  local after = keys(ovs)

  check((before == "ran,bare,last") and (after == "ran,last,bare") and (ovs:count("bare") == 1)
        and (again ~= bare) and (bare:exists() == false),
        ":list() is the draw order, and the same key again replaces rather than adds",
        before .. "  ->  " .. after .. " (" .. ovs:count("bare") .. " under 'bare')")

  check((ovs:remove("last") == ovs) and (ovs:get("last") == nil) and (ovs:find("last") == nil)
        and (ovs:remove("nothing-under-this-key") == ovs) and (ovs:count() == 2),
        ":remove empties the key and chains, and a key naming nothing is inert",
        keys(ovs))

  -----------------------------------------------------------------------------------------------
  -- The refusals. Each must fail, and say why.
  -----------------------------------------------------------------------------------------------
  refuses("a key that is not a string is refused, saying what a key is for",
          function() return ovs:add(7) end, "the key must be a string")

  refuses("an argument to widget:overlay() is refused, naming the collection",
          function() return own:overlay("ran") end, "takes no arguments")

  refuses("a painter that is not a function is refused, naming the signature",
          function() return mine:draw("hello") end, "expects a function")

  refuses("a name an overlay does not answer is refused, listing the ones it does",
          function() return mine:paint() end, "has no verb 'paint'")

  -----------------------------------------------------------------------------------------------
  -- The mark the [manual] line is read against: it overhangs the grid's bottom-right corner, so
  -- what is drawn shows both that an overlay is clipped to its widget and that it goes when the
  -- widget does. Left up on purpose -- :reload takes it off.
  -----------------------------------------------------------------------------------------------
  inv:overlay():add("mark"):draw(function(g, w, h)
    g:color(255, 90, 90)
    g:frect(w - 12, h - 12, 24, 24)
  end)

  hafen.timer():after(1, later)
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
