-- 065.11 — the speech bubble. Self-checking suite.

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

-- The bubble wears both halves of its key at once: a surface and a frame, and the face the key already
-- carried. The fill is LIGHT because the bubble's text is black and stays black -- this client throws the
-- glyph colour away at the blit -- so the FACE is the text half the manual line can actually read back.
local FILL = {250, 230, 120, 255}
local LINE = {color = {255, 60, 60, 255}, width = 2}

local function dress(s)
  s:rule("world.speech"):bg{color = FILL}:border(LINE)
  return s
end

local function rgb(c)
  if not c then return "<nil>" end
  return c.r .. "," .. c.g .. "," .. c.b
end

local function has(list, want)
  for _, v in ipairs(list or {}) do
    if v == want then return true end
  end
  return false
end

local function run()
  local s = hafen.ui():sheet()
  local face = hafen.font():get("mono"):derive():size(13)

  local ok, err = pcall(function()
    dress(s):rule("world.speech"):font(face):color(90, 40, 40)
  end)
  check(ok, "world.speech takes a bg and a border beside a font and a colour", err)

  -- The line border's own claims, duplicated rather than assumed: the manual line below rests on them, and
  -- this suite is the whole verification of this task.
  local b = s:rule("world.speech"):border() or {}
  check(b.color and (b.color.r == 255) and (b.color.g == 60) and (b.color.b == 60) and (b.width == 2)
        and (b.slice == nil) and (b.box == nil) and (b.image == nil) and (b.res == nil) and (b.mode == nil),
        "the bubble's line reads back its colour and its width (255,60,60 / 2) and carries no art",
        rgb(b.color) .. " / " .. tostring(b.width))

  -- Both halves of ONE key: the chrome half displaces neither of the two the key already carried. A colour
  -- reads back here even though this surface throws it away at the blit, which is what every key whose
  -- colour is inert does -- the rule carries what it was told, and the surface decides what it can wear.
  local r = s:rule("world.speech")
  local fill = r:bg() or {}
  check(fill.color and (fill.color.r == 250) and (fill.color.g == 230) and (fill.color.b == 120)
        and (r:font() == face) and r:color() and (r:color().r == 90),
        "the same rule reads back its surface beside the face and colour it already carried",
        rgb(fill.color) .. " / " .. tostring(r:font()))

  -- The client's own bubble frame, named rather than shipped -- gfx/hud/emote is the eight-part box
  -- Speaking itself draws with, so a theme can rebuild the stock bubble out of the vocabulary.
  s:rule("world.speech"):border{box = "gfx/hud/emote"}
  local bx = s:rule("world.speech"):border() or {}
  check((bx.box == "gfx/hud/emote") and (bx.mode == "stretch") and (bx.color == nil) and (bx.width == nil),
        "the client's own bubble box is nameable: {box = \"gfx/hud/emote\"} reads back with its own mode",
        tostring(bx.box) .. " / " .. tostring(bx.mode))

  refuses("a line with a slice is refused, saying a line has no slice",
          function() s:rule("world.speech"):border{color = {1, 2, 3}, width = 1, slice = {1, 1, 1, 1}} end,
          "a line has no slice")
  refuses("a border that names no art and no colour is refused",
          function() s:rule("world.speech"):border{mode = "tile"} end,
          "says nothing about what the frame is made of")
  refuses("a surface that names nothing is refused",
          function() s:rule("world.speech"):bg{at = "left"} end, "says nothing")

  -- A site is where the client draws, and this one is not even a widget: nothing here has a position.
  local put, perr = pcall(function() s:rule("world.speech"):position(4, 4) end)
  perr = put and "<no error>" or tostring(perr)
  check((not put) and perr:find("render site (\"world.speech\")", 1, true)
        and perr:find("Name the widget instead", 1, true),
        "world.speech is a site key: a position on it is refused naming the site and the fix", perr)

  dress(s)
  s:install()
  local i = s:info()
  check(i.installed and has(i.rules, "world.speech"), "the sheet installs and names the key",
        tostring(i.installed))

  -- THE SEAM. A speech bubble is no widget -- it is an attribute of a character out in the world -- so its
  -- box cannot arrive by the per-widget path. This says so from both ends: the sheet carries exactly ONE
  -- rule and it is the site key, and the widget tree resolves no border of OURS (asked of the root, which
  -- every widget is inside). Another addon's own rule on the root is therefore not mistaken for this one.
  local root = hafen.ui():root()
  local rst = (root and root:style()) or {}
  local ours = rst.border and rst.border.color and (rst.border.color.r == 255) and (rst.border.width == 2)
  check((root ~= nil) and (#i.rules == 1) and (not ours),
        "the rule is in force yet no widget resolves it: the bubble's box comes from the widget-less path",
        #i.rules .. " rule(s), root border " .. tostring(rst.border and rst.border.width))

  s:drop()
  check(s:info().installed == false, "dropping the sheet un-installs it, and every bubble falls back",
        tostring(s:info().installed))

  dress(s)
  s:install()
  manualCheck("say something in area chat and look above your own character",
              "the bubble filled PALE YELLOW (250,230,120) edge to edge inside a 2 px RED (255,60,60)"
              .. " outline, with the client's own golden emote frame GONE -- the fill reaching the outline is"
              .. " the rule's own border being the whole frame; the sentence itself in MONOSPACE rather than"
              .. " the stock serif -- and still BLACK, since this client throws the glyph colour away at the"
              .. " blit -- which is the TEXT half of the same key still reaching the same bubble; the bubble"
              .. " still growing with a longer sentence; and the little TAIL beneath it the client's own,"
              .. " unmoved, still pointing at your character's head")
  manualCheck("type :reload, then say something again",
              "the bubble back to the client's own white fill in its own golden frame, with nothing left over")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-11", run)   -- the only way in: a suite does not start itself
