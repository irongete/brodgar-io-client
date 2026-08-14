-- 065.17 — the client, read back as data. Self-checking suite.

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
  return said, what .. " -- " .. err
end

-- A rule reaches a window's chrome through Window.tick and is drawn a frame later, so every read waits a beat.
local function soon(fn) hafen.timer():after(0.4, fn) end

-- The five plates the client blits at fixed places, and the art each has always been drawn with.
local PLATES = {
  {key = "hud.belt",        res = "gfx/hud/hb-main"},
  {key = "hud.menu.left",   res = "gfx/hud/lbtn-bg"},
  {key = "hud.menu.right",  res = "gfx/hud/rbtn-bg"},
  {key = "hud.search",      res = "gfx/hud/csearch-bg"},
  {key = "minimap.frame",   res = "gfx/hud/blframe"},
}

-- The window's own background is three layers in paint order: a tiled field, then a shade down each side.
local LAYERS = {
  {res = "gfx/hud/wnd/lg/bg",  at = nil,     mode = "tile"},
  {res = "gfx/hud/wnd/lg/bgl", at = "left",  mode = "tile"},
  {res = "gfx/hud/wnd/lg/bgr", at = "right", mode = "tile"},
}

local function res(t) return t and t.res or "<none>" end

local function run()
  local s = hafen.ui():sheet()
  s:load{}                                   -- this sheet says nothing until the catalogue is loaded into it
  -- A window of its own, so the two window keys have been drawn by the time they are read: a site that has
  -- not drawn has declared nothing, which is the whole reason a reader opens what it means to read.
  local win = hafen.ui():window():title("065.17"):size(240, 90):position(90, 90)

  soon(function()
    local look = s:stock()

    -- ---- the art, named by the resource the client decoded it from ---------------------------------
    local bad = nil
    for _, p in ipairs(PLATES) do
      local r = look[p.key]
      if not (r and r.picture and (r.picture.res == p.res)) then
        bad = bad or (p.key .. " -> " .. res(r and r.picture))
      end
    end
    check(bad == nil, "the five plates the client blits come back named by their own resource", bad)

    local bg = (look["window.frame"] or {}).bg
    local wrong = nil
    for i, l in ipairs(LAYERS) do
      local g = bg and bg[i]
      if not (g and (g.res == l.res) and (g.at == l.at) and (g.mode == l.mode)) then
        wrong = wrong or (i .. " -> " .. res(g) .. " at " .. tostring(g and g.at)
                          .. " " .. tostring(g and g.mode))
      end
    end
    check((bg ~= nil) and (#bg == 3) and (wrong == nil),
          "...and a window's background as the three tiled layers it is, in paint order", wrong)

    local f, ttl = look["window.frame"] or {}, look["window.title"] or {}
    check(f.caption and (f.caption.at == "topleft") and f.caption.offset
          and (f.sizer or {}).res == "gfx/hud/wnd/sizer" and ((f.sizer or {}).at == "bottomright")
          and ((f.close or {}).res == "gfx/hud/wnd/lg/cbtnu") and (f.close.at == "topright")
          and (res(f.close.hover) == "gfx/hud/wnd/lg/cbtnh")
          and (res(f.close.pressed) == "gfx/hud/wnd/lg/cbtnd"),
          "the three ornaments come back as a place, a picture at a place, and a button with two faces",
          tostring(f.caption and f.caption.at) .. " / " .. res(f.sizer) .. " / " .. res(f.close))

    check((ttl.font or {}).builtin == "fraktur" and (ttl.font.size == 15) and (ttl.font.aa == true)
          and ((look["*"] or {}).font or {}).builtin == "sans" and (look["*"].font.size == 10)
          and ((ttl.emboss or {}).texture or {}).res == "gfx/hud/fonttex",
          "a face comes back named by its built-in at the size it is set in, with the relief it is cut from",
          tostring((ttl.font or {}).builtin) .. "/" .. tostring((ttl.font or {}).size)
          .. " " .. res((ttl.emboss or {}).texture))

    local sp = ((look["chat.speaker"] or {}).color or {}).generate
    local ur = ((look["chat.urgent"] or {}).color or {}).palette
    check(sp and (sp.step > 0) and (sp.saturation == 0.5) and (sp.brightness == 1.0)
          and ur and (#ur == 3) and (ur[1][3] == 255),
          "the two colours the client WALKS come back as the walk and the list they are, not as one colour",
          tostring(sp and sp.step) .. " / " .. tostring(ur and #ur))

    -- ---- one key, and a key with nothing to say ----------------------------------------------------
    check((s:stock("hud.belt") or {}).picture ~= nil
          and (s:stock("hud.belt").picture.res == look["hud.belt"].picture.res)
          and (s:stock("*") or {}).font ~= nil,
          "sheet:stock(key) answers exactly what the whole catalogue carries for that key",
          res((s:stock("hud.belt") or {}).picture))
    check((s:stock("panel") == nil) and (s:stock("scrollbar") == nil) and (look["panel"] == nil),
          "a site this client has offered no look for answers nothing rather than a guess",
          tostring(s:stock("panel")) .. " / " .. tostring(s:stock("scrollbar")))

    -- Each refusal names what is wrong with the key it was given, and they are three different things: a word
    -- that is no role at all is refused by the SELECTOR grammar, in the same words `sheet:rule` refuses it;
    -- a key that parses but names widgets is refused for naming no site; a number is refused for its type.
    local why = nil
    for _, t in ipairs({
      {refuses("a word that is no role at all", function() s:stock("nosuchkey") end,
               "is not a role", "window.frame")},
      {refuses("a TREE key, which names widgets", function() s:stock("@Img") end, "not a render site")},
      {refuses("a number, which is no selector", function() s:stock(3) end, "not a number")},
    }) do
      if not t[1] then why = why or t[2] end
    end
    check(why == nil, "sheet:stock refuses a key that names no site, a tree key and a number, saying why", why)

    -- ---- the round trip, which is the whole claim ---------------------------------------------------
    local ok, doc = pcall(function() return hafen.json():encode(look) end)
    local back = ok and hafen.json():parse(doc) or nil
    check(ok and back and (back["hud.belt"].picture.res == "gfx/hud/hb-main") and (#doc > 400),
          "the catalogue is plain data: it survives being written as JSON and read back",
          ok and (tostring(#doc) .. " chars") or tostring(doc))

    s:load(back):install()
    soon(function()
      local missing = nil
      for key, rule in pairs(back) do
        local got = s:rule(key):info()
        for prop, _ in pairs(rule) do
          if not (got and (got[prop] ~= nil)) then
            missing = missing or (key .. "." .. prop)
          end
        end
      end
      local n = 0
      for _ in pairs(back) do n = n + 1 end
      check(s:info().installed and (missing == nil) and (n >= 8)
            and (s:rule("hud.belt"):picture().res == "gfx/hud/hb-main")
            and (s:rule("window.frame"):bg()[3].res == "gfx/hud/wnd/lg/bgr"),
            "loaded back it installs, and every one of the " .. n
            .. " keys resolves to what stock() said", missing)
      win:destroy()

      manualCheck("run :t065-17 once, then open the Inventory and the Options window and run it again",
                  "the same verdicts both times -- and a client that has drawn an inventory square and a"
                  .. " checkbox has MORE keys to hand back the second time (the count above grows), because"
                  .. " a site that has not drawn yet has no look to read")
      manualCheck("with the catalogue installed (this suite leaves it so), look at any client window, the"
                  .. " belt, both bottom corners and the minimap, then type :reload and look again",
                  "no difference you can point at: the window frame's tiled runs, its caption plate, the"
                  .. " shading down each side and the foot piece at the bottom of its left edge all there"
                  .. " both times, the X still closing its window, and the belt and the two menu plates"
                  .. " unchanged -- the client's own look, expressed in the vocabulary")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end)
  end)
end

hafen.slash():register("t065-17", run)   -- the only way in: a suite does not start itself
