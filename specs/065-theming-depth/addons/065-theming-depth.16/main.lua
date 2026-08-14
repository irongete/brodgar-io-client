-- 065.16 — the chat's colours, the walked one included. Self-checking suite.

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
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or tostring(err)
  local said = not ok
  for _, want in ipairs({...}) do
    said = said and (err:find(want, 1, true) ~= nil)
  end
  check(said, what, err)
end

-- The four kinds a chat line comes in, each with a colour no other kind wears, so "each key resolved its
-- own" is a claim about four distinguishable answers rather than about one repeated.
local KINDS = {
  {key = "chat.system",  c = {255, 200,   0}},
  {key = "chat.mine",    c = {  0, 255, 160}},
  {key = "chat.private", c = {255,  60, 200}},
  {key = "chat.party",   c = { 60, 200, 255}},
}
-- ...and the two whose colour the client WALKS rather than holds, which take a sequence and nothing else.
local WALKED = {"chat.speaker", "chat.urgent"}

local PALETTE = {{255, 0, 0}, {0, 0, 255}}   -- two colours, in this order, and the order is the assertion
local GEN = {step = 0.41, saturation = 0.5, brightness = 1.0}   -- roughly the client's own walk

local function rgb(c)
  if not c then return "<nil>" end
  return tostring(c.r) .. "," .. tostring(c.g) .. "," .. tostring(c.b)
end

local function is(c, want)
  return c and (c.r == want[1]) and (c.g == want[2]) and (c.b == want[3])
end

local function run()
  local s = hafen.ui():sheet()

  -- ---- the keys exist, and each is a SITE key ------------------------------------------------------
  -- A site key is refused a `position`: a site is where the client draws, and a place belongs to a widget.
  -- That refusal is the one thing that tells a real site key from a name that merely PARSED and would have
  -- become a tree key matching nothing -- which is the way this feature's keys can silently fail.
  local notsite = nil
  for _, k in ipairs({"chat.system", "chat.mine", "chat.private", "chat.party", "chat.urgent",
                      "chat.speaker"}) do
    local ok, err = pcall(function() s:rule(k):position(4, 4) end)
    if ok or not tostring(err):find("position", 1, true) then
      notsite = notsite or (k .. " -> " .. (ok and "<no error>" or tostring(err)))
    end
  end
  check(notsite == nil, "all six new keys are SITE keys: each refuses a position, naming the fix", notsite)

  -- ---- four kinds, four colours, read back apart ---------------------------------------------------
  for _, k in ipairs(KINDS) do
    s:rule(k.key):color(k.c[1], k.c[2], k.c[3])
  end
  local wrong = nil
  for _, k in ipairs(KINDS) do
    if not is(s:rule(k.key):color(), k.c) then
      wrong = wrong or (k.key .. " -> " .. rgb(s:rule(k.key):color()))
    end
  end
  check(wrong == nil, "each of the four kinds took its own colour and read exactly that one back", wrong)

  -- `chat` still covers the whole window, and naming a kind said nothing about it.
  check(s:rule("chat"):color() == nil,
        "naming the kinds leaves `chat` itself saying nothing, so it still covers the whole window",
        rgb(s:rule("chat"):color()))
  s:rule("chat"):color(200, 210, 220)
  check(is(s:rule("chat"):color(), {200, 210, 220}) and is(s:rule("chat.mine"):color(), KINDS[2].c),
        "...and writing `chat` afterwards does not overwrite a kind that named its own",
        rgb(s:rule("chat"):color()) .. " / " .. rgb(s:rule("chat.mine"):color()))

  -- ---- the two walked colours ----------------------------------------------------------------------
  s:rule("chat.speaker"):color{palette = PALETTE}
  local p = (s:rule("chat.speaker"):color() or {}).palette
  check(p and (#p == 2) and is(p[1], PALETTE[1]) and is(p[2], PALETTE[2]),
        "a palette reads back as the colours it listed, in the order it listed them",
        p and (rgb(p[1]) .. " then " .. rgb(p[2])) or "<no palette>")

  s:rule("chat.speaker"):color{generate = GEN}
  local g = (s:rule("chat.speaker"):color() or {}).generate
  check(g and (g.step == GEN.step) and (g.saturation == GEN.saturation)
          and (g.brightness == GEN.brightness) and ((s:rule("chat.speaker"):color() or {}).palette == nil),
        "a generator reads back its three numbers, and replaces the palette rather than joining it",
        g and (tostring(g.step) .. "/" .. tostring(g.saturation) .. "/" .. tostring(g.brightness))
          or "<no generator>")

  s:rule("chat.speaker"):color{generate = {step = GEN.step, saturation = 0.9, brightness = GEN.brightness}}
  check(((s:rule("chat.speaker"):color() or {}).generate or {}).saturation == 0.9,
        "a second generator with a different saturation is what the sequence then resolves to",
        tostring(((s:rule("chat.speaker"):color() or {}).generate or {}).saturation))

  s:rule("chat.urgent"):color{palette = PALETTE}
  check(#(((s:rule("chat.urgent"):color() or {}).palette) or {}) == 2,
        "the urgency colour is a sequence too, one entry per level rather than one colour for the three",
        rgb(((s:rule("chat.urgent"):color() or {}).palette or {})[1]))

  -- ---- what each shape is refused on ---------------------------------------------------------------
  -- A sequence is taken by the two keys the client WALKS and by nothing else...
  for _, k in ipairs({"chat", "chat.mine", "window.title", "*"}) do
    refuses("a sequence on \"" .. k .. "\" is refused, naming the two keys that take one",
            function() s:rule(k):color{palette = PALETTE} end, "chat.speaker", "chat.urgent")
  end
  refuses("...and on a TREE key too, which resolves per widget and walks nothing",
          function() s:rule("window[title=nope]"):color{palette = PALETTE} end,
          "chat.speaker", "chat.urgent")

  -- ...and neither of those two takes a colour, which is the point of naming the sequence at all.
  for _, k in ipairs(WALKED) do
    refuses("a flat colour on \"" .. k .. "\" is refused, saying it walks rather than holds",
            function() s:rule(k):color(1, 2, 3) end, k, "palette", "generate")
  end

  refuses("an empty palette is refused: cycling no colours leaves nothing to draw with",
          function() s:rule("chat.speaker"):color{palette = {}} end, "empty")
  refuses("a palette entry that is not a colour is refused, naming the entry",
          function() s:rule("chat.speaker"):color{palette = {{255, 0, 0}, "blue"}} end, "palette[2]")
  refuses("a generator naming only a step is refused, listing all three fields",
          function() s:rule("chat.speaker"):color{generate = {step = 0.4}} end,
          "saturation", "brightness")
  refuses("a step of 0 is refused: it never moves off one hue, so every speaker is one colour",
          function() s:rule("chat.speaker"):color{generate = {step = 0, saturation = 0.5, brightness = 1}} end,
          "step")
  refuses("a field outside 0..1 is refused, saying each is a fraction",
          function() s:rule("chat.speaker"):color{generate = {step = 2, saturation = 0.5, brightness = 1}} end,
          "0..1")
  refuses("naming both a palette and a generator is refused rather than one silently winning",
          function() s:rule("chat.speaker"):color{palette = PALETTE, generate = GEN} end, "both")
  refuses("an unknown sequence property is refused rather than ignored, naming the misspelling",
          function() s:rule("chat.speaker"):color{pallette = PALETTE} end, "pallette",
          "is not a sequence property")
  refuses("a value naming neither spelling is refused for saying nothing, not for being the wrong shape",
          function() s:rule("chat.speaker"):color{} end, "neither a palette nor a generator")

  -- ---- the DATA door: the same sheet, written as a file would write it -----------------------------
  local theme = {
    ["chat"]          = {color = {180, 190, 200}},
    ["chat.system"]   = {color = KINDS[1].c},
    ["chat.mine"]     = {color = KINDS[2].c},
    ["chat.private"]  = {color = KINDS[3].c},
    ["chat.party"]    = {color = KINDS[4].c},
    ["chat.speaker"]  = {color = {palette = PALETTE}},
    ["chat.urgent"]   = {color = {palette = {{0, 255, 0}, {255, 255, 0}, {255, 0, 0}}}},
    -- Not a chat key: the surface a program can actually make the client draw `$col[...]` markup on, so the
    -- claim that markup beats a rule is duplicated here rather than assumed from an older suite.
    ["tooltip"]       = {color = {150, 150, 150}},
  }
  local d = hafen.ui():sheet()
  d:load(theme):install()
  check(d:info().installed and is(d:rule("chat.private"):color(), KINDS[3].c)
          and (#(((d:rule("chat.speaker"):color() or {}).palette) or {}) == 2),
        "the whole set loads from DATA, installs, and every key reads back what the table said",
        tostring(d:info().installed) .. " / " .. rgb(d:rule("chat.private"):color()))

  refuses("...and the data door refuses a sequence on the wrong key with the same words the setter does",
          function() hafen.ui():sheet():load{["chat"] = {color = {palette = PALETTE}}} end,
          "chat.speaker", "chat.urgent")

  -- ---- and it all goes away -------------------------------------------------------------------------
  d:drop()
  check(d:info().installed == false,
        "dropping the sheet un-installs every one of the new keys at once",
        tostring(d:info().installed))

  d:load(theme):install()
  manualCheck("hover an inventory item whose tooltip carries a coloured number -- a piece of food, or any"
              .. " item showing a green or red attribute delta",
              "the tip's ordinary text in flat grey (150,150,150), which is the rule -- and the coloured"
              .. " numbers STILL green and red, because $col[...] markup is part of the string the server"
              .. " sent rather than the site's choice of colour, and beats every rule on the surface")
  manualCheck("say something in area chat, then take a private message, then trigger a System line"
              .. " (an unknown :command will do)",
              "your OWN line in green (0,255,160), the private one in pink (255,60,200), the System line in"
              .. " amber (255,200,0) -- three visibly different colours rather than one; any OTHER kind of"
              .. " line, and the channel tabs, in the pale grey-blue (180,190,200) `chat` sets, which is the"
              .. " cascade: a kind with no rule of its own still reads as `chat`")
  manualCheck("watch two DIFFERENT people speak in area chat while this theme is installed",
              "their two names in pure RED and pure BLUE, the palette's two colours in the order written,"
              .. " rather than the client's own pastel hues; and with an unread channel, its tab and the"
              .. " chat button's glow in the urgency palette's green/yellow/red rather than blue/orange/red")
  manualCheck("type :reload and look at the chat again",
              "every kind back in the client's own colour, the speakers back on their pastel walk, and the"
              .. " urgency indicator back to blue/orange/red")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-16", run)   -- the only way in: a suite does not start itself
