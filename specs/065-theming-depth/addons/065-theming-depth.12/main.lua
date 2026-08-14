-- 065.12 — picture, the whole plate. Self-checking suite.

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

-- The plate the manual line reads: a flat fill has no size of its own, so it covers whatever rectangle the
-- client was going to blit its own art into -- which is the claim about where a picture lands.
local PLATE = {40, 20, 90, 255}

-- A client picture, and the resource it is showing. The bottom panels each blit one; the walk takes the
-- first that can NAME its art, since the second half of the seam is that the name does not change.
local function plate()
  for _, w in ipairs(hafen.ui():all("@Img")) do
    if w:picture() then return w, w:picture() end
  end
  return nil, nil
end

local function rgb(c)
  if not c then return "<nil>" end
  return c.r .. "," .. c.g .. "," .. c.b
end

local function run()
  local s = hafen.ui():sheet()
  local w, art = plate()
  if not w then
    hafen.log():write("[fail] a client picture to dress -- got: no @Img in the tree (log in first)")
    hafen.log():write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The setter door. A picture is ONE surface with the fields every picture in this vocabulary carries.
  local r = s:rule("@Img"):picture{color = PLATE}
  local p = r:picture() or {}
  check(p.color and (p.color.r == 40) and (p.color.g == 20) and (p.color.b == 90) and (p[1] == nil),
        "a tree rule takes a picture and reads back the one surface it was given (40,20,90)", rgb(p.color))

  -- State rides INSIDE the value, exactly as a bg's face does: the same shape, keyed by the state it varies.
  s:rule("@Img"):picture{res = art, hover = {color = {10, 200, 10}}}
  local v = s:rule("@Img"):picture() or {}
  check((v.res == art) and (v.mode == "tile") and v.hover and v.hover.color and (v.hover.color.g == 200),
        "the client's own art is nameable, and a state face rides inside the value it varies",
        tostring(v.res) .. " / hover " .. rgb(v.hover and v.hover.color))

  -- A site is where the client draws a KIND of thing; nothing there blits a plate yet, so the property is
  -- accepted and inert rather than refused. That is the doctrine every unappliable property here follows.
  local sok, serr = pcall(function() s:rule("tooltip"):picture{color = {1, 2, 3}} end)
  check(sok, "a picture on a site key is accepted and inert, never refused", serr)

  refuses("a picture naming two spellings at once is refused",
          function() s:rule("@Img"):picture{color = {1, 2, 3}, res = art} end, "not several")
  refuses("a state face with no face for it to vary is refused",
          function() s:rule("@Img"):picture{hover = {color = {1, 2, 3}}} end,
          "names a state face and no face for it to vary")
  refuses("an ARRAY of surfaces is refused: layers are what a bg is painted in, a plate is the whole picture",
          function() s:rule("@Img"):picture{{color = {1, 2, 3}}, {color = {4, 5, 6}}} end, "ONE surface")
  refuses("an unknown field inside a picture is refused, naming what a surface and a state are",
          function() s:rule("@Img"):picture{colour = {1, 2, 3}} end,
          "is neither a surface property nor a state")

  -- The DATA door on the same property, so a theme.json carries a plate: the whole sheet at once, replacing
  -- what the setters above wrote, and the read below proves which of the two the client is wearing.
  s:load{["@Img"] = {picture = {color = PLATE}}}
  s:install()
  check(s:info().installed, "a picture loaded from DATA installs", tostring(s:info().installed))

  -- THE SEAM, and it is two questions rather than one. The widget resolves the rule's art -- that is what
  -- the draw paints from -- while the widget's own read still answers the picture the CLIENT put there,
  -- because a picture rule is never written into the widget: the server re-points this one at will.
  local st = w:style() or {}
  local sp = st.picture or {}
  check(sp.color and (sp.color.r == 40) and (sp.color.g == 20) and (sp.color.b == 90),
        "the widget's resolved style carries the rule's plate", rgb(sp.color))
  check(w:picture() == art, "...while the widget's own picture is untouched: it still answers " .. art,
        tostring(w:picture()))

  s:drop()
  local dst = w:style()
  check((dst == nil) or (dst.picture == nil), "dropping the sheet leaves the widget resolving no picture",
        tostring(dst and dst.picture))

  s:load{["@Img"] = {picture = {color = PLATE}}}
  s:install()
  manualCheck("look at the bottom-left corner, then CLICK the middle of the purple where the minimap was",
              "the plate around the minimap a flat DEEP PURPLE (40,20,90) rectangle instead of the client's"
              .. " own carved frame, with the minimap HIDDEN BEHIND it: this client draws that plate OVER the"
              .. " map it frames, and a flat colour has no transparent centre to see the map through -- and"
              .. " yet the click still walks your character to that spot, which is the map unmoved and still"
              .. " live underneath, a rule having changed the paint and nothing else; the same purple on the"
              .. " plates in the bottom-RIGHT corner, with every button on them still where it was and still"
              .. " clickable")
  manualCheck("type :reload and look again",
              "every plate back to the client's own art, with nothing left over")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t065-12", run)   -- the only way in: a suite does not start itself
