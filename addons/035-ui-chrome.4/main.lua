-- 035.4 — cost, docs, the theme, close. Self-checking suite; see specs/addons/TESTING.md.
--
-- The task that closes C2 has two claims of its own, and both are numbers rather than looks:
--
--   * A THEME IS DATA, chrome included. The three tasks before this one were driven from Lua tables written by
--     hand; the deliverable is that a FILE can say the same thing. So this suite reads a theme.json shaped
--     exactly like the bundled `theme` example's, maps the only two values JSON cannot hold (a font's face and
--     an image path — both HANDLES), and then asserts the GEOMETRY the file's own numbers predict: a window
--     that measures content + the file's insets + twice the file's pad, to the pixel. Nothing between the file
--     and the client interprets a thing, and that arithmetic is what proves it.
--   * CHROME COSTS NO LUA. A frame is redrawn every frame with no raster cache to amortise it (unlike text,
--     which got 026's), which is why a per-frame Lua Deco callback was rejected at design time. The engine
--     paints from data parsed once, so with the client themed this addon's own draw and widget callbacks must
--     read ZERO and the widget tree must cost what it cost stock — measured over the same windows twice, since
--     the round stands up four of its own rather than measure whatever happens to be open. Both come from
--     hafen.client:profiling(), which answers only while the profiler is armed; when it is not, that half is
--     one [manual] line naming the switch to tick.
--
-- READ-ONLY: declares no permissions, mutates no persistent state (it READS the profiling switch and never
-- writes it), destroys its probe and drops its sheet before it finishes.

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

local function eq(what, got, want)
  check(got == want, what .. " (" .. tostring(want) .. ")", got)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function xy(p) return ("%d,%d"):format(p.x, p.y) end

-- The chrome is a CHILD of its window (030), and Window.resize2 sets deco.c = contarea().ul.inv(), so "where
-- does this window's content start" is a number Lua can read rather than a picture it has to describe.
local function decoOf(w)
  for _, kid in ipairs(w:children()) do
    local t = kid:type()
    if (t == "SkinDeco") or (t == "DefaultDeco") then return kid, t end
  end
end

local function contentStart(w)
  local d = decoOf(w)
  return d and { x = -d:pos().x, y = -d:pos().y }
end

-- ---- the file → a sheet ---------------------------------------------------------------------------
--
-- These are the `theme` example's own three lines, and they are the whole adapter a JSON theme needs. The
-- default is to COPY, because a colour array, a slice's four insets and a pad are already the sheet's own
-- shapes; what is mapped is only where a HANDLE has to stand.

local FILE = "theme.json"
local TREE = "window[title=035.4 probe]"

local function fontOf(f)
  local h = hafen.font(f.face)
  if f.size then h = h:derive{ size = f.size } end
  return h
end

local function ruleOf(props)
  local rule = {}
  for k, v in pairs(props) do rule[k] = v end
  if props.font then rule.font = fontOf(props.font) end
  if props.bg and props.bg.image then rule.bg = { image = hafen.asset(props.bg.image) } end
  if props.border then rule.border = { image = hafen.asset(props.border.image), slice = props.border.slice } end
  return rule
end

local function sheetOf(doc)
  local rules = {}
  for key, props in pairs(doc.rules or {}) do rules[key] = ruleOf(props) end
  return rules
end

-- ---- the run --------------------------------------------------------------------------------------

local W, H = 90, 40                       -- the probe window's CONTENT size
local doc, sheet, art                     -- `art` = the slice + pad the file asked for
local probe, base, basePos
local extra = {}                          -- the windows the cost round measures chrome ON
local armManual = false

local steps = {}

local function finish()
  if probe then probe:destroy(); probe = nil end
  for _, w in ipairs(extra) do w:destroy() end
  extra = {}
  hafen.ui.skin(nil)
  if armManual then
    manualCheck("tick Options ▸ Client ▸ \"Enable profiling\" and run ':t035-4' again",
                "two more [pass] lines: this addon runs 0 draw callbacks while a themed client paints, and the"
                .. " themed widget tree costs what the stock one costs — both medians printed in the line")
  end
  manualCheck("enable the bundled `theme` addon (Options ▸ AddOns, then ':reload'), then ':theme on',"
              .. " ':theme dump', ':theme off'",
              "on = the whole client themed live from theme.json — dark slate window frames with a title band,"
              .. " thinner frames on the portrait and the list boxes, serif body text, green mono chat; dump ="
              .. " one line per rule, chrome included; off (or disabling the addon) = the stock client back,"
              .. " frames, geometry and all. Its Lua names no surface, no colour and no pixel")
  manualCheck("with ':theme on', open the character sheet and a couple of windows, then drag one, resize it"
              .. " and close it",
              "every window wears the theme's frame with its caption still on the band; drag, resize and close"
              .. " behave exactly as stock, and the client reads as ONE theme rather than a mix")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

-- Every step waits first: the swap and the repack happen in Window.tick, so a step running inline with the
-- write it checks would read the previous frame (035.2's lesson).
local function step(i)
  hafen.timer():after(0.35, function()
    local f = steps[i]
    if not f then return finish() end
    f()
    if steps[i + 1] then step(i + 1) end     -- the last step chains its own continuation
  end)
end

-- ---- cost -----------------------------------------------------------------------------------------

local ID = "035-ui-chrome.4"
local SAMPLES = 15

local function median(t)
  table.sort(t)
  return t[math.ceil(#t / 2)] or 0
end

-- Milliseconds to three decimals. LuaJ 3.0.1's string.format IGNORES a float precision -- "%.3f" prints the
-- whole double (1.5739000000039027), which is unreadable in a verdict line meant to be pasted back. So the
-- rounding is done in integer arithmetic and printed with %d, which it does honour.
local function ms3(x)
  local t = math.floor((x * 1000) + 0.5)
  return ("%d.%03d"):format(math.floor(t / 1000), t % 1000)
end

-- Sample the widget tree's own per-frame cost (utick + draw) — where chrome is painted. Spaced, so every
-- sample is a different frame; the timer itself is charged to this addon's `timers` bracket and never to
-- `draw`, which is the very distinction the no-Lua check below rests on.
local function sample(n, acc, done)
  hafen.timer():after(0.06, function()
    local f = hafen.client:profiling():frame()
    if f and f.ui then acc[#acc + 1] = f.ui end
    if #acc >= n then done(acc) else sample(n, acc, done) end
  end)
end

local function ownRow()
  for _, r in ipairs(hafen.client:profiling():addons()) do
    if r.id == ID then return r end
  end
end

-- Measure with something to paint. The client's own open windows are whatever the maintainer happens to have
-- up, so the round stands up its own: the same windows are on screen for BOTH halves of the comparison, stock
-- and themed, which is what makes the difference between the two medians the chrome and nothing else.
local function costRound(after)
  local stock = {}
  hafen.ui.skin(nil)
  for i = 1, 4 do
    extra[i] = hafen.ui.window{ title = "035.4 cost " .. i, size = { 120, 60 }, pos = { 20 + (i * 24), 20 + (i * 24) } }
  end
  sample(SAMPLES, stock, function()
    hafen.ui.skin(sheet)
    hafen.timer():after(0.35, function()
      local dressed = #hafen.ui.all("@SkinDeco")
      local themed = {}
      sample(SAMPLES, themed, function()
        local row = ownRow()
        local paints = row and (row.calls.draw + row.calls.widgets)
        check(paints == 0,
              ("painting a themed client (%d window%s dressed) ran no Lua of ours: %s draw/widget callback%s"
               .. " over %d frames"):format(dressed, dressed == 1 and "" or "s", tostring(paints),
                                            (paints == 1) and "" or "s", SAMPLES),
              row and paints or "no addons() row for this suite")
        local a, b = median(stock), median(themed)
        check(b <= (a * 1.5) + 0.5,
              ("...and cost the widget tree what stock cost it: %s ms themed vs %s ms stock, median of %d"
               .. " frames"):format(ms3(b), ms3(a), SAMPLES), ms3(b) .. " vs " .. ms3(a) .. " ms")
        hafen.ui.skin(nil)
        after()
      end)
    end)
  end)
end

-- ---- the steps ------------------------------------------------------------------------------------

-- 1. the sheet the FILE describes is live, and the window's geometry is the file's own arithmetic.
steps[1] = function()
  local _, t = decoOf(probe)
  eq("a window.frame rule read out of a JSON file dresses a window", t, "SkinDeco")
  eq("...and the window measures content + the file's insets + twice its pad, exactly",
     xy(probe:size()), ("%d,%d"):format(W + art.slice[1] + art.slice[3] + 2 * art.pad,
                                        H + art.slice[2] + art.slice[4] + 2 * art.pad))
  eq("...with the content starting one of the file's pads inside its insets",
     xy(contentStart(probe)), ("%d,%d"):format(art.slice[1] + art.pad, art.slice[2] + art.pad))
  local st = probe:style()
  eq("a TREE key out of the same file resolves on the one window it names",
     st and st.bg and st.bg.color and st.bg.color.r, 40)
  hafen.ui.skin(nil)
end

-- 2. and the whole thing reverts.
steps[2] = function()
  eq("dropping a sheet that came from a file restores the exact numbers it found",
     xy(probe:size()) .. " @ " .. xy(probe:pos()), base .. " @ " .. basePos)
  eq("...and leaves the probe resolving nothing again", probe:style(), nil)
  eq("...and no sheet-fed chrome anywhere in the client", #hafen.ui.all("@SkinDeco"), 0)
  if hafen.client:options():client():profiling() then
    costRound(finish)
  else
    armManual = true
    finish()
  end
end

local function run()
  pass, fail, manual = 0, 0, 0    -- so a re-run through :t035-4 reports its own counts, not the last one's
  armManual = false
  hafen.ui.skin(nil)

  -- 1. the file, and the one thing a file cannot carry.
  local a = hafen.asset(FILE)
  eq("a theme is a file: it loads as a data asset", a:type(), "data")
  doc = hafen.json():parse(a:text())
  local f = doc.rules["window.frame"]
  check((f.bg.color[1] == 26) and (f.border.slice[2] == 40) and (f.pad == 4),
        "the chrome arrives as plain data — a 1-indexed colour array, four slice insets and a pad in pixels",
        hafen.json():encode(f))
  eq("an image is the one value in a rule JSON cannot hold: in the file it is a path",
     type(f.border.image), "string")
  art = { slice = f.border.slice, pad = f.pad }     -- the numbers the geometry below is predicted from

  -- 2. mapped, it is a sheet — text, chrome and a tree key in one table — and it applies.
  sheet = sheetOf(doc)
  eq("...and mapped through hafen.asset it is an image handle, exactly like a font's face before it",
     sheet["window.frame"].border.image:type(), "image")
  check(sheet[TREE] ~= nil and pcall(hafen.ui.skin, sheet),
        "a whole sheet built from one JSON file applies — site keys, chrome and a tree key together")
  hafen.ui.skin(nil)

  -- 3. the geometry the file predicts, read a frame after the write.
  probe = hafen.ui.window{ title = "035.4 probe", size = { W, H }, pos = { 8, 8 } }
  base, basePos = xy(probe:size()), xy(probe:pos())
  hafen.ui.skin(sheet)
  step(1)
end

-- ON DEMAND ONLY. A suite does not start itself: the maintainer runs it when they want it. This one themes
-- every window in the client twice — once for the round, once more for the cost samples — so it is the last
-- one that should share a login with anything else. Its round stages ~1.5 s, ~4 s with the cost half armed.
hafen.slash():register("t035-4", run)
