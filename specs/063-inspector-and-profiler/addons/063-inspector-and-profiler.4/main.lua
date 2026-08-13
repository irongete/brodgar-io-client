-- 063.4 — the inspector says everything the widget will answer. Self-checking suite.
--
-- The inspector's describe() driver prints a line for a read ONLY where that read answered. So the thing to
-- prove is the gate itself: build the zoo the driver has to describe -- a picture control, a picture button,
-- a slider with a range, a dropdown with rows, a grid with a cell box, a table with columns, a text entry
-- holding a value, a tooltipped button, and a bare label that has none of it -- and assert every read
-- answers on the widget that HAS it and nil on the one that has not. Then take the selector the panel would
-- offer for one of them and assert it hands that very widget back.

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

local PIC = "gfx/hud/wnd/lg/cbtnu"     -- a picture out of the client's own art: every window's close box
local zoo                              -- the window the zoo hangs in, kept so a re-run replaces it

-- The reads a bare label must answer nil to -- which is exactly the set the driver gates a line on. :items()
-- and :focused() are not among them: they answer an empty array and false rather than nil, and they are
-- checked on their own below.
local NILABLE = { "picture", "tooltip", "value", "range", "rows",
                  "rowHeight", "cell", "columns", "source", "image", "style" }

local function silent(w)               -- every nil-able read that ANSWERED on w, as text (nothing = "")
  local said = {}
  for i = 1, #NILABLE do
    local k = NILABLE[i]
    local ok, v = pcall(function() return w[k](w) end)
    if ok and (v ~= nil) then said[#said + 1] = k .. "=" .. tostring(v) end
  end
  return table.concat(said, ", ")
end

local function run()
  if zoo then zoo:destroy() end        -- a second run must not leave two "Go" buttons for the selector check
  zoo = hafen.ui():window():title("T0634"):size(380, 190):position(140, 140)

  local pic  = hafen.ui():image():parent(zoo):position(8, 8):source(PIC)
  local ibtn = hafen.ui():button():parent(zoo):position(48, 8):image(PIC, PIC)
  local sld  = hafen.ui():slider():parent(zoo):position(8, 40):size(140, 20):range(0, 100):value(50)
  local dd   = hafen.ui():dropdown():parent(zoo):position(8, 68):size(120, 20)
                 :rows{"All", "Seeds", "Tools"}:value("All")
  local ent  = hafen.ui():entry():parent(zoo):position(8, 96):size(160, 20):value("zoo")
  local btn  = hafen.ui():button():parent(zoo):position(8, 124):text("Go"):tooltip("what it does")
  local lbl  = hafen.ui():label():parent(zoo):position(8, 156):text("plain")
  local grd  = hafen.ui():grid():parent(zoo):position(200, 8):size(120, 60):cell(24, 24):rows{{}, {}}
  local tbl  = hafen.ui():table():parent(zoo):position(200, 76):size(160, 70)
                 :columns{ { title = "Name", width = 110, of = function(r) return r.name end },
                           { title = "Q",    width = 40,  of = function(r) return tostring(r.q) end } }
                 :rows{ { name = "Bucket", q = 10 } }

  -- ---- every read answers on the widget that has it ------------------------------------------------
  check(pic:picture() == PIC and pic:source() == PIC,
        "the picture control answers :picture() and :source() (" .. PIC .. ")",
        tostring(pic:picture()) .. " / " .. tostring(pic:source()))

  local faces = ibtn:image()
  check(faces ~= nil and faces.up == PIC and ibtn:picture() == PIC,
        "the picture button answers :image() and :picture()",
        tostring(faces and faces.up) .. " / " .. tostring(ibtn:picture()))

  local rng = sld:range()
  check(rng ~= nil and rng.min == 0 and rng.max == 100 and sld:value() == 50,
        "the slider answers :range() 0..100 and :value() 50",
        tostring(rng and rng.min) .. ".." .. tostring(rng and rng.max) .. " / " .. tostring(sld:value()))

  local rws, rh = dd:rows(), dd:rowHeight()
  check(rws ~= nil and #rws == 3 and rws[1] == "All" and type(rh) == "number" and rh > 0,
        "the dropdown answers :rows() (3) and :rowHeight()",
        tostring(rws and #rws) .. " rows / rowHeight " .. tostring(rh))

  local cel, cols = grd:cell(), tbl:columns()
  check(cel ~= nil and cel.w == 24 and cel.h == 24 and cols ~= nil and #cols == 2
        and cols[1].title == "Name",
        "the grid answers :cell() 24x24 and the table answers :columns() (2)",
        tostring(cel and cel.w) .. "x" .. tostring(cel and cel.h) .. " / "
          .. tostring(cols and #cols) .. " columns")

  check(ent:value() == "zoo" and btn:tooltip() == "what it does",
        "the text entry answers :value() and the button answers :tooltip()",
        tostring(ent:value()) .. " / " .. tostring(btn:tooltip()))

  -- ---- and nil on the widget that has none: the gate deciding whether a line is printed -------------
  local said = silent(lbl)
  check(said == "", "a bare label answers nil to every one of those reads",
        (said == "") and "(silent)" or said)

  local its, foc = lbl:items(), lbl:focused()
  check(type(its) == "table" and #its == 0 and foc == false,
        "the two that never answer nil: the label's :items() is empty and :focused() is false",
        tostring(#its) .. " items / focused " .. tostring(foc))

  check(btn:info().owned == true and hafen.ui():root():info().owned == false,
        "provenance is :info().owned -- true on the zoo, false on the client's own root",
        tostring(btn:info().owned) .. " / " .. tostring(hafen.ui():root():info().owned))

  -- ---- the selector the panel would offer, built from those same reads, resolves back to the widget --
  local sel = ("window[title=%s] %s[text=%s]"):format(zoo:text(), btn:role(), btn:text())
  local found = hafen.ui():find(sel)
  check(found == btn, 'the offered selector finds that widget back (' .. sel .. ')',
        (found == nil) and "nil" or ("a " .. tostring(found:type())))

  -- An empty attribute value never resolves, which is why the panel refuses to offer one.
  refuses("an empty attribute value is a parse error",
          function() hafen.ui():find("window[title=]") end, "has an empty value")

  manualCheck("with :widgetstack up, hover any window's close button, then click that stack row",
              "'picture: " .. PIC .. "' under 'what it answers', in the panel AND in the Inspector")
  manualCheck("hover the 'plain' label in the T0634 window this run left on screen",
              "'it answers none of the widget reads', and not one empty line under it")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t063-4", function()
  pass, fail, manual = 0, 0, 0
  run()
end)
