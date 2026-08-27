-- 116.3 — the widget door names the ring's own verb. Self-checking suite.
--
-- Raises a ring of its own, matches it as a widget, and asks the generic visibility write for it.
-- Both directions must refuse, and each refusal must name the spelling that replaces it — not merely
-- say no. The read is unchanged, so the two doors agree on whether the ring is painted and disagree
-- only on who may write it.

local pass, fail = 0, 0

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

-- A refusal is a check: the call must fail, and fail SAYING why. LuaJ prefixes a bridge error with
-- "@chunk.lua:NN " — a space, not a colon — so the strip has to allow both.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

-- The ring has to be caused: nothing in the API delivers a right-click but a right-click. Try the
-- objects around the character one at a time over a bounded window, and score what the run reached.
-- A click, then a whole beat before the next one: a ring that arrives after its candidate was given
-- up on would stand beside the one we then match, and two are not one.
local function raise(s, done)
  local cands = s:world():gob():within(100)
  local i, ticks, t = 0, 0, nil
  t = hafen.timer():every(0.5, function()
    if s:flowermenu():count() > 0 then
      t:cancel()
      done(true)
      return
    end
    ticks = ticks + 1
    if (ticks % 2) == 1 then
      i = i + 1
      if (i > #cands) or (i > 8) then
        t:cancel()
        done(false)
      else
        pcall(function() s:world():click(cands[i], 3) end)
      end
    end
  end)
end

local function checks(s)
  local w = s:ui():match("@FlowerMenu")
  check(w ~= nil, "the ring is a widget of that character's tree: s:ui():match(\"@FlowerMenu\")", w)
  if w == nil then return end

  refuses("w:visible(false) is refused, naming the ring's own verb",
          function() w:visible(false) end, "flowermenu():visible")
  refuses("w:visible(true) is refused, naming the ring's own verb",
          function() w:visible(true) end, "flowermenu():visible")

  eq("w:visible() reads the painted ring", w:visible(), true)
  s:flowermenu():visible(false)
  eq("w:visible() reads it hidden, once its own verb hid it", w:visible(), false)
  s:flowermenu():visible(true)
  eq("w:visible() reads it painted again", w:visible(), true)

  -- The control: the refusal is that one receiver's, not the verb's. A widget of your own still hides
  -- and shows through this very door.
  local mine = hafen.ui():widget():size(10, 10):position(0, 0)
  mine:visible(false)
  eq("the same write still answers on a widget of your own", mine:visible(), false)
  mine:visible(true)
  eq("...and shows it again", mine:visible(), true)
  mine:destroy()
end

local function run()
  local s = hafen.session():current()
  if s == nil then
    hafen.log():write("[fail] a character is logged in -- got: no current session")
    summary()
    return
  end
  raise(s, function(up)
    check(up, "a ring went up on a nearby object", "none of the objects nearby opened one")
    if up then
      local ok, err = pcall(checks, s)
      if not ok then
        check(false, "the checks ran to the end", err)
      end
      pcall(function() s:flowermenu():cancel() end)   -- the ring holds the keyboard: give it back
    end
    summary()
  end)
end

hafen.console():on("t116", run)   -- the only way in: a suite does not start itself
