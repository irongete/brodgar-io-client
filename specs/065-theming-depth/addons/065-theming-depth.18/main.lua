-- 065.18 — the sweep, and the theme the guide ships. Self-checking suite.
--
-- theme.json beside this file is the guide's own block, verbatim, so the PAGE is what is under test: every
-- assertion below is a sentence that page makes about it. Change one and the other has to move with it.

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

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(fn, want)
  local ok, err = pcall(fn)
  if ok then return false, "<no error>" end
  err = tostring(err):gsub("^.-%.lua:%d+:%s*", "")
  return (err:find(want, 1, true) ~= nil), err
end

-- Every key this vocabulary adds to the ones that were already there. A SITE key is a place the CLIENT
-- draws, and it says so from both sides: sheet:stock() takes it, and the three properties that lay a
-- widget out refuse it for naming no widget. A key documented and never routed fails here.
local ADDED = {
  "inventory.slot", "checkbox", "checkbox.mark", "scrollbar", "scrollbar.knob", "slider", "slider.knob",
  "hud.belt", "hud.menu.left", "hud.menu.right", "hud.search", "minimap.frame",
  "chat.system", "chat.mine", "chat.private", "chat.party", "chat.urgent", "chat.speaker",
}

local function run()
  local s = hafen.ui():sheet()

  -- ---- every key this vocabulary adds is a site key -----------------------------------------------
  -- This mints a scratch rule per key on the sheet; the theme is loaded afterwards, and sheet:load
  -- replaces the whole document, so what the manual checks below look at is the file's four rules alone.
  local bad = nil
  for _, k in ipairs(ADDED) do
    local ok, err = pcall(function() return s:stock(k) end)
    if not ok then
      bad = bad or (k .. " -> sheet:stock refused it: " .. tostring(err))
    else
      local said, why = refuses(function() s:rule(k):position(0, 0) end, "render site")
      if not said then bad = bad or (k .. " -> :position(0, 0) " .. why) end
    end
  end
  check(bad == nil, "each of the " .. #ADDED .. " keys this vocabulary adds resolves as a SITE key:"
        .. " stock() takes it and position() refuses it for naming no widget", bad)

  local said, why = refuses(function() s:stock("@Img") end, "not a render site")
  local movable = pcall(function() s:rule("@Img"):position(0, 0) end)
  s:rule("@Img"):remove()   -- a control, not a level this suite means to leave standing
  check(said and movable, "...and the test tells the two apart: a TREE key answers the other way round",
        (said and "" or ("stock: " .. why)) .. (movable and "" or " / position was refused too"))

  -- ---- the guide's own theme file, verbatim -------------------------------------------------------
  local doc = hafen.json():parse(hafen.asset():get("theme.json"):text())
  s:load(doc.rules):install()

  local named, info = {}, s:info()
  for _, k in ipairs(info.rules) do named[k] = true end
  check(info.installed and (#info.rules == 4) and named["*"] and named["window.frame"] and named["chat"]
        and named["chat.speaker"],
        "the guide's own theme file parses, loads and installs, saying four rules and no more",
        table.concat(info.rules, " "))

  local face = s:rule("*"):font()
  check(face and (face:size() == 11) and (face:family() == hafen.font():get("serif"):family()),
        "the file's face reads back as the built-in it names, at the size it names, under everything",
        face and (tostring(face:family()) .. " " .. tostring(face:size())) or "<none>")

  local b = s:rule("window.frame"):border()
  check(b and (b.box == "gfx/hud/wnd") and (b.mode == "tile") and (b.slice == nil),
        "the client's OWN frame, named by the folder its eight pieces sit in, its edges repeated",
        b and (tostring(b.box) .. " / " .. tostring(b.mode)) or "<none>")

  local bg = s:rule("window.frame"):bg()
  check(bg and (#bg == 2) and (bg[1].res == "gfx/hud/wnd/lg/bg") and (bg[1].mode == "tile")
        and (bg[1].at == nil) and (bg[2].res == "gfx/hud/wnd/lg/bgl") and (bg[2].at == "left")
        and (bg[2].mode == "tile"),
        "...over the two background layers the file lists, in the order it lists them",
        bg and (tostring(#bg) .. ": " .. tostring(bg[1] and bg[1].res) .. " then "
                .. tostring(bg[2] and bg[2].res) .. " at " .. tostring(bg[2] and bg[2].at)) or "<none>")

  local p = s:rule("window.frame"):padding()
  check(p and (p.l == 8) and (p.t == 24) and (p.r == 8) and (p.b == 8),
        "...and eight pixels of room between that frame and the contents, twenty-four of it at the top",
        p and (p.l .. "," .. p.t .. "," .. p.r .. "," .. p.b) or "<none>")

  local c = s:rule("chat"):color()
  check(c and (c.r == 190) and (c.g == 210) and (c.b == 190) and (c.a == 255),
        "the chat reads back the one colour the file writes as three numbers",
        c and (c.r .. "," .. c.g .. "," .. c.b .. "," .. c.a) or "<none>")

  local seq = s:rule("chat.speaker"):color()
  local pal = seq and seq.palette
  check(pal and (#pal == 2) and (pal[1].r == 220) and (pal[1].g == 190) and (pal[1].b == 140)
        and (pal[2].r == 150) and (pal[2].g == 200) and (pal[2].b == 220) and (seq.generate == nil),
        "...and the speakers as the two colours the file lists them cycling, never flattened to one",
        pal and (#pal .. " colours, first " .. tostring(pal[1] and pal[1].r)) or "<none>")

  -- A flat colour on that key, and a sequence anywhere else, are each refused: which shape a key takes
  -- is the key's, and the file above is the only spelling either of the two accepts.
  local flat = refuses(function() s:rule("chat.speaker"):color(200, 200, 200) end, "palette")
  local wrong = refuses(function() s:rule("chat"):color{ palette = { {1, 2, 3} } } end, "chat.speaker")
  check(flat and wrong, "one flat colour is refused where the client walks one, and a sequence where it"
        .. " does not, each naming the keys that take which", tostring(flat) .. "/" .. tostring(wrong))

  manualCheck("with this theme installed (the suite leaves it so), open a client window and the chat, then"
              .. " read guides/theming.md top to bottom",
              "every block describing what is on screen: the client's own plain golden box around each"
              .. " window, a shade down the left of its dark green background, its contents clear of its"
              .. " caption rather than under it, the chat log green, and every text surface in serif at 11"
              .. " -- captions and section headings included, which is what the page says a size on \"*\""
              .. " does")
  manualCheck("read a channel two people are talking in, or say something in area chat from two characters",
              "the two speakers in the file's own two colours -- warm sand and pale blue -- rather than in"
              .. " the hue the client walks, and each keeping its colour line after line")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-18", run)   -- the only way in: a suite does not start itself
