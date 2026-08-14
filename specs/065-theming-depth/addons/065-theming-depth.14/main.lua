-- 065.14 — emboss, and the colour it gives back. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why -- every fragment asked for.
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  local missing = nil
  for _, m in ipairs({...}) do
    if not err:find(m, 1, true) then missing = missing or m end
  end
  check((not ok) and (missing == nil), what,
        (not ok) and missing and ("no \"" .. missing .. "\" in: " .. err) or err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The client's own red leaf, which the character sheet already tiles through a FAILED heading. Naming it
-- costs the suite no art of its own and is unmistakable against the gold every carved surface wears.
local LEAF = "gfx/hud/fontred"

-- The three keys the client renders as a MASK. A theme names them one at a time; the suite names all three,
-- since "reaches the embossed sites" is the claim and one of them would not show it.
local CARVED = {"window.title", "heading", "button"}

-- The tables an emboss cannot mean, each with the words its refusal has to carry. One check for the lot:
-- what is being proved is that the value refuses rather than guesses, and the fragments say which is which.
local BAD = {
  {what = "{}",
   v = {},                                                     say = "says nothing"},
  {what = "{tex = <art>}",
   v = {tex = {res = LEAF}},                                   say = "is not an emboss property"},
  {what = "{texture = {color = ...}}",
   v = {texture = {color = {200, 40, 40}}},                    say = "has no pixels to tile"},
  {what = "{texture = {res = ..., at = ...}}",
   v = {texture = {res = LEAF, at = "topleft"}},               say = "takes no \"at\""},
}

local TITLE = {60, 220, 230}      -- window captions, flat
local CAPS  = {245, 90, 215}      -- button captions, flat

-- The whole look as DATA, which is the door a theme.json comes through: two keys flattened and coloured,
-- one re-textured, so both halves of the property are on screen at once.
local function theme()
  return {
    ["window.title"] = {emboss = false, color = TITLE},
    ["button"]       = {emboss = false, color = CAPS},
    ["heading"]      = {emboss = {texture = {res = LEAF}}},
  }
end

local function run()
  local s = hafen.ui():sheet()

  -- A colour alone says NOTHING about the relief, which is the whole reason `color` looks inert on a carved
  -- surface: with no emboss property the client tiles exactly the texture it always tiled.
  s:rule("window.title"):color(TITLE[1], TITLE[2], TITLE[3])
  check(s:rule("window.title"):emboss() == nil,
        "a rule carrying only a colour names no emboss, so the client's own relief is what stands",
        tostring(s:rule("window.title"):emboss()))

  -- ...and dropping it is what hands those letters back to the font. The two properties are independent and
  -- both read back: `false` is an answer, and it is not the same answer as nil.
  s:rule("window.title"):emboss(false)
  local e, c = s:rule("window.title"):emboss(), s:rule("window.title"):color()
  check((e == false) and c and (c.r == TITLE[1]) and (c.g == TITLE[2]) and (c.b == TITLE[3]),
        "emboss(false) and a colour sit in one rule and both read back",
        tostring(e) .. " / " .. (c and (c.r .. "," .. c.g .. "," .. c.b) or "<nil>"))

  -- The other shape, on every key the client carves. A texture is an ordinary picture, so it is named the
  -- way every art in this vocabulary is and reads back the spelling it was written with.
  local wrong = nil
  for _, k in ipairs(CARVED) do
    local ok, err = pcall(function() s:rule(k):emboss{texture = {res = LEAF}} end)
    local got = ok and s:rule(k):emboss() or nil
    if not (got and got.texture and (got.texture.res == LEAF)) then
      wrong = wrong or (k .. ": " .. (ok and tostring(got and got.texture and got.texture.res) or tostring(err)))
    end
  end
  check(wrong == nil, "all three carved keys take a texture, and each reads its own back by resource", wrong)

  -- A key the client draws no text through a mask for accepts it and does nothing -- the doctrine every
  -- unappliable property here follows, rather than a refusal that would make a whole-sheet theme unwritable.
  local iok, ierr = pcall(function() s:rule("inventory.slot"):emboss(false) end)
  check(iok and (s:rule("inventory.slot"):emboss() == false),
        "an emboss on a key that carves nothing is accepted and inert, not refused",
        iok and tostring(s:rule("inventory.slot"):emboss()) or tostring(ierr))

  -- `true` is the one spelling worth refusing loudly: it would mean the client's own relief, which is what
  -- silence already means, so the refusal names BOTH things an emboss may be and says to leave it out.
  refuses("emboss(true) is refused naming the two things an emboss may be, and what to write instead",
          function() s:rule("window.title"):emboss(true) end,
          "an emboss is false", "{ texture = ", "leave the property out")

  local badly = nil
  for _, b in ipairs(BAD) do
    local ok, err = pcall(function() s:rule("heading"):emboss(b.v) end)
    err = ok and "<no error>" or tostring(err)
    if ok or not err:find(b.say, 1, true) then
      badly = badly or (b.what .. " -> " .. err)
    end
  end
  check(badly == nil, "the four tables an emboss cannot mean are each refused saying which", badly)

  refuses("a texture naming a resource this client does not have is refused, at the rule and by name",
          function() s:rule("heading"):emboss{texture = {res = "gfx/nosuchtexture"}} end,
          "no such resource", "gfx/nosuchtexture")

  -- The DATA door on the same property, which is what makes a whole look a file: `false` survives being a
  -- table field, and the sheet installs with it.
  s:load(theme()):install()
  local i = s:info()
  local he = s:rule("heading"):emboss()
  local te = s:rule("window.title"):emboss()
  check(i.installed and (te == false) and he and he.texture and (he.texture.res == LEAF),
        "a sheet loaded from DATA carries emboss both ways, installs, and reads both back",
        tostring(i.installed) .. " / " .. tostring(te) .. " / "
        .. tostring(he and he.texture and he.texture.res))

  s:drop()
  check(s:info().installed == false, "dropping the sheet un-installs it, so every carved surface falls back",
        tostring(s:info().installed))

  s:load(theme()):install()
  manualCheck("open any window with a title bar, and the Options window beside it",
              "every window CAPTION and every button CAPTION drawn FLAT -- the captions cyan (60,220,230),"
              .. " the button labels magenta (245,90,215) -- with none of the gold carved relief they wear"
              .. " normally, their dark drop shadow still behind them, and the window frames, plates and"
              .. " button faces exactly as they were: this rule paints letters and nothing else")
  manualCheck("open the character sheet and look at its big fraktur section headings",
              "those headings filled with the client's RED leaf texture instead of the gold one, still"
              .. " carved rather than flat -- the other shape of the same property -- and the smaller group"
              .. " captions above an icon grid, in the crafting or build menu, in that same red")
  manualCheck("type :reload and look at all three again",
              "window captions, button labels and section headings back in the client's own gold relief,"
              .. " with nothing left over")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-14", run)   -- the only way in: a suite does not start itself
