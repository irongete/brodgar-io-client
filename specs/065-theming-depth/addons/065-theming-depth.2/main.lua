-- 065.2 — art is named, not only handed over. Self-checking suite.

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

local ART   = "art.png"                 -- the one file this suite ships
local BOX   = "gfx/hud/wnd"             -- the client's own eight-part window frame
local FIELD = "gfx/hud/wnd/lg/bg"       -- ...its tiled background field
local SHL   = "gfx/hud/wnd/lg/bgl"      -- ...and the shade down each side
local SHR   = "gfx/hud/wnd/lg/bgr"

local KB = 'window[title=065.2 box]'
local KS = 'window[title=065.2 stretch]'
local KT = 'window[title=065.2 tile]'

-- The suite's own 32x32 frame art: flat corners, and ONE bright mark at the start of each run. Which is the
-- whole point of it -- the client's own edges are uniform bars, so stretching one and repeating one land on
-- the same pixels and a manual line asking to tell them apart could not be answered.
local SLICE = { 8, 8, 8, 8 }

-- The client's own window background, said in the vocabulary: a tiled field, then a shade down each side.
local LAYERS = { { res = FIELD, mode = "tile" },
                 { res = SHL, at = "left",  mode = "tile" },
                 { res = SHR, at = "right", mode = "tile" } }

-- A rule reaches a window's chrome through Window.tick, so a read of a just-opened window waits a beat.
local function soon(fn) hafen.timer():after(0.3, fn) end

local function shows(v)
  if v == nil then return "<nil>" end
  return tostring(v.box or v.res or v.image) .. " " .. tostring(v.mode)
end

local function run()
  local s = hafen.ui():sheet()
  local h = hafen.asset():get(ART)

  -- Named, not shipped: two keys dressed from the client's own frame with no file of our own.
  s:load{ ["window.frame"] = { border = { box = BOX } },
          ["panel"]        = { border = { box = BOX } } }:install()
  local wb, pb = s:rule("window.frame"):border(), s:rule("panel"):border()
  check(wb and (wb.box == BOX) and pb and (pb.box == BOX),
        "a window frame and a panel both dress from the client's own box, shipping no art",
        shows(wb) .. " / " .. shows(pb))

  -- One file, two spellings: the handle and the path intern to the same art.
  s:load{ ["window.frame"] = { bg = { image = h } }, ["panel"] = { bg = { asset = ART } } }
  local ba, bb = s:rule("window.frame"):bg(), s:rule("panel"):bg()
  check(ba and bb and (ba.image == h) and (bb.image == h),
        "the same file by handle and by asset path is one art",
        tostring(ba and ba.image) .. " / " .. tostring(bb and bb.image))

  -- A bg of several layers, read back at the arity it was written.
  s:load{ ["window.frame"] = { bg = LAYERS } }
  local ls = s:rule("window.frame"):bg()
  check(ls and (#ls == 3) and (ls[1].res == FIELD) and (ls[2].res == SHL) and (ls[2].at == "left")
        and (ls[3].res == SHR) and (ls[3].at == "right"),
        "a three-layer bg comes back in the order written",
        ls and (tostring(#ls) .. ": " .. tostring(ls[1].res) .. ", " .. tostring(ls[2].res)
                .. ", " .. tostring(ls[3].res)) or "<nil>")

  refuses("a box that resolves to nothing is refused naming it",
          function() s:rule("window.frame"):border{ box = "gfx/hud/nosuchbox" } end, "gfx/hud/nosuchbox")
  refuses("a resource that resolves to nothing is refused naming it",
          function() s:rule("window.frame"):bg{ res = "gfx/nosuchimage" } end, "gfx/nosuchimage")
  refuses("art AND a box in one border is refused",
          function() s:rule("window.frame"):border{ image = h, box = BOX } end, "not both")
  refuses("a colour AND a resource in one surface is refused",
          function() s:rule("window.frame"):bg{ color = {26, 26, 28}, res = FIELD } end, "not several")
  refuses("an unknown mode is refused naming the two that exist",
          function() s:rule("window.frame"):border{ box = BOX, mode = "repeat-x" } end, "stretch")
  refuses("a layer array with nothing in it is refused",
          function() s:rule("window.frame"):bg{} end, "says nothing")

  s:load{}                                 -- the sheet says nothing until the last step installs one
  local wb = hafen.ui():window():title("065.2 box"):size(320, 60):position(60, 60)
  local ws = hafen.ui():window():title("065.2 stretch"):size(320, 60):position(60, 190)
  local wt = hafen.ui():window():title("065.2 tile"):size(320, 60):position(60, 320)

  soon(function()
    s:load{ [KB] = { border = { box = BOX, mode = "tile" }, bg = LAYERS },
            [KS] = { border = { asset = ART, slice = SLICE } },
            [KT] = { border = { asset = ART, slice = SLICE, mode = "tile" } } }:install()
    soon(function()
      local sb, ss, st = wb:style(), ws:style(), wt:style()
      check(sb and sb.border and (sb.border.box == BOX) and sb.bg and (#sb.bg == 3),
            "a window resolves the client's own box, and three layers under it, through the cascade",
            shows(sb and sb.border))
      check(ss and st and ss.border and st.border and (ss.border.image == h)
            and (st.border.image == h) and (ss.border.mode == "stretch") and (st.border.mode == "tile"),
            "one art, two modes, two borders",
            shows(ss and ss.border) .. " / " .. shows(st and st.border))
      manualCheck("look at the \"065.2 box\" window, and at the panels beside it",
                  "framed in the client's own plain golden frame, sharp and at the weight those panels wear"
                  .. " -- not blurred, not four times too large; and inside it the client's own tiled field"
                  .. " with a shade down the left side and one down the right, painted over it")
      manualCheck("look at \"065.2 stretch\" and \"065.2 tile\", which wear the same art of the suite's own",
                  "the same pale corners on both; on \"tile\" the orange mark REPEATED along every run at the"
                  .. " size it was drawn, the last repeat clipped rather than squeezed and never spilling over"
                  .. " a corner; on \"stretch\" one mark smeared across a quarter of each run"
                  .. " -- :reload puts the stock client back")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end)
  end)
end

hafen.slash():register("t065-2", run)   -- the only way in: a suite does not start itself
