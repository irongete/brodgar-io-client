-- 065.3 — a face is named too, so a whole theme is a file. Self-checking suite.
-- fonts/inconsolata.ttf is Inconsolata by Raph Levien, SIL Open Font License 1.1.

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

local FILE = "theme.json"                    -- the whole look, and the only place it is written
local FONT = "fonts/inconsolata.ttf"         -- the one file this suite ships beside it
local BOX  = "gfx/hud/wnd"

local function face(h)
  if h == nil then return "<nil>" end
  return tostring(h:family()) .. " " .. tostring(h:size()) .. " bold=" .. tostring(h:bold())
end

local function run()
  local s = hafen.ui():sheet()

  -- The whole claim: a file, parsed, loaded, installed -- and not one handle named on the way.
  local doc = hafen.json():parse(hafen.asset():get(FILE):text())
  s:load(doc.rules):install()

  local info = s:info()
  check(info and (info.installed == true) and (#info.rules == 3),
        "the theme in the file installed, with the three rules it declares",
        info and (tostring(info.installed) .. ", " .. tostring(#info.rules)))

  local star = s:rule("*"):font()
  check(star and (star:family() == "Monospaced") and (star:size() == 11),
        "the file's builtin face reads back as the client's own mono, at the size the file named",
        face(star))

  local cap = s:rule("window.title"):font()
  check(cap and (cap:family() == "Inconsolata") and (cap:size() == 15) and (cap:bold() == true),
        "the file's asset face reads back as the shipped file, sized and bold",
        face(cap))

  local bg = s:rule("window.frame"):bg()
  check(bg and bg.color and (bg.color.r == 26) and (bg.color.g == 26) and (bg.color.b == 28)
        and (bg.color.a == 240),
        "the file's bg colour reads back",
        bg and bg.color and (bg.color.r .. "," .. bg.color.g .. "," .. bg.color.b .. "," .. bg.color.a))

  local bd = s:rule("window.frame"):border()
  check(bd and (bd.box == BOX) and (bd.mode == "tile"),
        "the file's border box reads back, in the mode it named",
        bd and (tostring(bd.box) .. " " .. tostring(bd.mode)))

  local pd = s:rule("window.frame"):padding()
  check(pd and (pd.l == 6) and (pd.t == 24) and (pd.r == 6) and (pd.b == 6),
        "the file's padding reads back as the four numbers it wrote",
        pd and (pd.l .. "," .. pd.t .. "," .. pd.r .. "," .. pd.b))

  -- Naming a face and loading one are the same door: with no variant, the name IS the interned handle.
  local named = s:rule("label"):font{ builtin = "serif" }:font()
  local shipped = s:rule("menu"):font{ asset = FONT }:font()
  check((named == hafen.font():get("serif")) and (shipped == hafen.asset():get(FONT)),
        "a face named with no variant is the very handle the loader hands back, either way",
        face(named) .. " / " .. face(shipped))

  refuses("a face naming no face of the client's is refused naming the built-ins",
          function() s:rule("label"):font{ builtin = "nosuchface" } end, '"mono"')
  refuses("a face that says neither builtin nor asset is refused for saying nothing",
          function() s:rule("label"):font{} end, "says nothing")
  refuses("a face whose asset is not a font file is refused",
          function() s:rule("label"):font{ asset = FILE } end, "is not a font")

  s:load(doc.rules):install()   -- the last word is the FILE's, whole, for the reading below
  manualCheck("read this log, then open any client window",
              "every line of client text in a monospaced face at a small size, window captions in a"
              .. " DIFFERENT face -- narrow, monospaced, bold -- rather than the client's own blackletter,"
              .. " and each window framed in the client's own golden frame over a near-black fill,"
              .. " its contents pushed a title bar's worth of room down from the top edge and only a"
              .. " little in from the sides. Nothing but a JSON file says any of it, and the Lua that"
              .. " read it names no font, colour, size or pixel -- :reload puts the stock client back")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-3", run)   -- the only way in: a suite does not start itself
