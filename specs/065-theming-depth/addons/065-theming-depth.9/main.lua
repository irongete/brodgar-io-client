-- 065.9 — text fields. Self-checking suite.

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

local KEY  = "textentry"
local ART  = "field.png"                                  -- the one file this suite ships: 48x40 design px
local FILL = { asset = ART }                              -- ...twice the stock field's own height
local LINE = { color = {255, 190, 60, 255}, width = 2 }   -- ...and a two-pixel amber frame around it
local PAD  = { 8, 3, 8, 3 }

local function hy(c) return c and c.y or -1 end          -- a height, and -1 for a size that never answered
local function tall(c) return tostring(hy(c)) end

local function run()
  local s    = hafen.ui():sheet()
  local h    = hafen.asset():get(ART)
  local art  = h:size()
  local body = hafen.font():get("mono"):derive():size(11)
  local big  = hafen.font():get("mono"):derive():size(24)

  local win = hafen.ui():window():title("065.9"):size(240, 170):position(120, 120)
  local stock = hafen.ui():entry():parent(win):position(10, 8):value("stock"):size()

  -- The font half moves no geometry: a field's height is its BACKGROUND's, never its font's.
  s:rule(KEY):font(big)
  s:install()
  local fonted = hafen.ui():entry():parent(win):position(10, 42):value("font only"):size()
  check((hy(stock) > 0) and (hy(fonted) == hy(stock)),
        "a field built under a font rule is the height it always was",
        tall(fonted) .. " vs " .. tall(stock))

  -- ...and the chrome half is what it does follow.
  s:rule(KEY):remove()
  s:rule(KEY):font(body):bg(FILL):border(LINE):padding(PAD[1], PAD[2], PAD[3], PAD[4])
  s:install()
  local r, b, l, p = s:rule(KEY), s:rule(KEY):bg(), s:rule(KEY):border(), s:rule(KEY):padding()
  check((r:font() == body) and b and (b.image == h) and l and (l.color.r == 255) and (l.width == 2)
        and p and (p.l == PAD[1]) and (p.t == PAD[2]),
        "one rule carries the face, the surface, the frame and the room, and all four read back",
        tostring(r:font() == body) .. " / " .. tostring(b and b.image) .. " / "
        .. tostring(l and l.width) .. " / " .. tostring(p and p.l))

  local dressed = hafen.ui():entry():parent(win):position(10, 76):value("dressed"):size()
  check(art.h ~= hy(stock), "the art this suite ships is not the stock height, so the check below can fail",
        art.h .. " vs " .. tall(stock))
  check(hy(dressed) == art.h, "a field built under the rule is as tall as the art it was given",
        tall(dressed) .. " vs " .. art.h)

  s:drop()
  local back = hafen.ui():entry():parent(win):position(10, 124):value("dropped"):size()
  check((s:info().installed == false) and (hy(back) == hy(stock)),
        "dropping the sheet un-installs it and gives the stock height back",
        tostring(s:info().installed) .. " " .. tall(back))

  refuses("a padding that is not a number is refused naming both spellings",
          function() s:rule(KEY):padding("6") end, "expected a number of pixels")
  refuses("a line with no width is refused", function() s:rule(KEY):border{ color = {1, 2, 3} } end,
          "a line needs a \"width\"")
  refuses("a surface that names nothing is refused", function() s:rule(KEY):bg{ at = "left" } end,
          "says nothing")

  s:rule(KEY):font(body):bg(FILL):border(LINE):padding(PAD[1], PAD[2], PAD[3], PAD[4])
  s:install()
  manualCheck("click into this suite's own four fields, then into the chat's input, then type : for the"
              .. " command line, and drag-select some of what you typed in each",
              "every field a dark teal panel with a pale vertical mark repeated along it and an amber 2 px"
              .. " frame, and no brown end caps left; the text in mono 11, sitting further in from that"
              .. " frame than the frame alone would put it; the caret between the two glyphs you clicked"
              .. " between, and the selection covering exactly the glyphs you dragged over")
  manualCheck("look at the four fields of this suite's own window together",
              "\"dressed\" -- the ONE built while the rule was installed -- about TWICE as tall as the three"
              .. " around it, which are the stock height still and wear the art inside the box they were"
              .. " built at: painting reaches every field, the art's height reaches only a field built under it")
  manualCheck("type :reload", "every field back to the stock brown caps, the stock face and nothing left over")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-9", run)   -- the only way in: a suite does not start itself
