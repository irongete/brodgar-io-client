-- Brodgar.io Profiler -- the standing in-game harness for hafen.client:profiling() (spec 019).
--
-- It lives on its own instead of inside 'hello': 'hello' is the whole-API regression addon and is already
-- too large to absorb a whole feature demo (the same reason 'optionstest' is its own addon).
--
-- DORMANT by default (the hogtest/bags rule): nothing is created, nothing is drawn and nothing is read
-- until you open the window -- so a routine regression login is completely undisturbed, with profiling
-- off AND on.
--
--   'toggle' hotkey   open/close the window   |   'pause' hotkey   freeze/unfreeze what it shows
--                     Both start UNBOUND (D-047); assign them under Options > Keybindings > Brodgar.io
--                     Profiler (suggested: Ctrl+Shift+P and Ctrl+Shift+O).
--   :profiler         the same window toggle from the console
--   :profiler on|off  arm/disarm profiling itself -- the same switch as the Options > Client checkbox
--                     and the client's own ':profile on'. The status line is clickable for it too.
--   :profiler pause | live | clear | <tab>
--
-- PAUSE + TIMELINE. The engine keeps a ring of ~600 frames; that ring IS the recording, and PAUSE is how
-- you read it. Pausing freezes every table the window shows (they are snapshots, so holding them is free)
-- and turns the frame graph into a scrubbable timeline: click any bar -- or step with [<] [>] -- and the
-- detail block below reports THAT frame's time, GPU time, addon cost and full phase breakdown. Clicking
-- the graph while live pauses automatically, because a live graph shifts one bar per frame and there
-- would be nothing to point at. CLEAR (p:reset()) empties the ring, which is how you record a specific
-- moment: clear, do the thing, pause, scrub.
--
-- The six tabs are the six halves of the surface:
--   FRAME     fps, frame time (this frame + avg/min/max/p95 over the ring), idle, latency, GPU time, the
--             frame graph / timeline, and both phase breakdowns (UI thread + render thread).
--   PASSES    p:passes() -- the curated render passes, CPU and GPU side by side -- plus p:gl(), the
--             armed-only submission counters. Turn Video > Shadows off and watch the 'shadow' row.
--   WIDGETS   p:widgets() -- per widget type and per widget, inclusive vs self time.
--   ADDONS    p:addons() -- per addon Lua cost, sorted, with each addon's named scopes.
--   COUNTERS  p:memory()/:net()/:loader()/:render() -- the PULL-ONLY counters. This tab is the one that
--             works with profiling OFF, and every number in it should match ':stats on' field by field.
--   OVERHEAD  p:overhead() -- what profiling costs, per tier, against its 5% budget.
--
-- It profiles ITSELF: the whole draw runs inside p:measure("draw", ...) and the frame graph inside a
-- nested p:measure("graph", ...), so this addon's own cost -- and those two scopes -- show up under its
-- row in the ADDONS tab. That is the scope API demonstrating itself on the one addon guaranteed to be
-- running while you are looking at the table. (It is not cheap: every g:text re-rasterises a texture each
-- frame, so an open window costs a few ms. Close it before you measure fps -- and note that PAUSE does
-- NOT make it cheaper, since the pixels are still drawn every frame; it only stops the numbers moving.)

local p = hafen.client:profiling()

-- A monospace handle so the columns line up: with a mono font, padding by character count IS alignment.
-- The window's `font =` makes it the default for every g:text this addon draws (fonts F2), and it touches
-- nobody else's pixels.
local FONT = hafen.font.load("mono", { size = 11 })

local function client() return hafen.client:options():client() end

-- ------------------------------------------------------------------------------------ layout constants

local WIN_W, WIN_H = 700, 470
local TAB_H, TAB_W = 18, 100          -- the tab bar, and one tab cell (6 * 100 fits in 700)
local Y_STATUS     = TAB_H + 3        -- the status/controls line
local Y0           = TAB_H + 24       -- first content line
local LINE         = 13

local TABS = { "frame", "passes", "widgets", "addons", "counters", "overhead" }
local tab  = 1
local win

-- The status line's clickable controls, as {x, width} in window coords.
local BTN_PAUSE = { WIN_W - 168, 74 }
local BTN_CLEAR = { WIN_W - 88, 74 }

-- The frame graph / timeline. G_N samples of G_BAR px each -- a bounded number of draw calls per frame
-- rather than one per ring slot: this window is itself Lua work on the frame it is measuring.
local G_X, G_Y, G_H, G_BAR, G_N = 6, 84, 88, 3, 180

-- The phase names the client itself uses, in the client's own order (pairs() order would shuffle the
-- table between frames, which is unreadable in a live window), and one colour each for the stacked bar.
local PHASES  = { "dwait", "stick", "utick", "draw", "aux", "wait" }
local PCOLOR  = { { 90, 90, 130 }, { 120, 170, 120 }, { 220, 190, 90 }, { 220, 120, 90 },
                  { 170, 120, 200 }, { 80, 80, 80 } }
local RPHASES = { "tick", "draw", "swap", "finish" }

-- ------------------------------------------------------------------------------------ small formatters
--
-- LuaJ 3.0.1's string.format is NOT C's: it ignores the PRECISION of %f/%g/%e (so "%.2f" prints
-- 10.852199999987988, the raw double) and the WIDTH of %s (so "%-16s" pads nothing). Only %d honours a
-- width. A live table of numbers therefore has to round and pad by hand -- that is what these do, and why
-- every row below is built from a column spec rather than from one format string.

-- Fixed-point: round to d decimals and render the digits, keeping the trailing zeros tostring() drops.
local function fx(v, d)
  if v == nil then return "--" end
  d = d or 2
  local neg = v < 0
  if neg then v = -v end
  local m = 10 ^ d
  local n = math.floor(v * m + 0.5)
  local i = math.floor(n / m)
  local f = tostring(math.floor(n - i * m))
  while #f < d do f = "0" .. f end
  return (neg and "-" or "") .. tostring(i) .. ((d > 0) and ("." .. f) or "")
end

local function f2(v)  return fx(v, 2) end
local function f3(v)  return fx(v, 3) end
local function pct(v) return fx((v or 0) * 100, 1) .. "%" end
local function mb(v)  return (v and (fx(v / 1048576, 1) .. " MB")) or "--" end
local function num(v) return (v and tostring(math.floor(v + 0.5))) or "--" end

-- Column padding. `#s` is bytes, which is exactly right for the mono font this window draws in (every
-- glyph one cell, every byte one glyph for the ASCII these tables contain).
local function L(s, n)
  s = tostring(s)
  return (#s >= n) and s:sub(1, n) or (s .. string.rep(" ", n - #s))
end
local function R(s, n)
  s = tostring(s)
  return (#s >= n) and s:sub(1, n) or (string.rep(" ", n - #s) .. s)
end

-- ------------------------------------------------------------------------------------------- tables
--
-- A table is a COLUMN SPEC -- { {title, width, "l"/"r"}, ... } -- shared by the header and every row, so a
-- value can never drift out of its column: both are laid out by the same widths, in the same order. Rows
-- get alternating stripes and the header a rule under it, which is what makes a wall of numbers readable.

local GAP = "  "                                      -- two spaces between columns

local function cells(spec, vals)
  local out = {}
  for i = 1, #spec do
    local c = spec[i]
    out[i] = (c[3] == "r") and R(vals[i], c[2]) or L(vals[i], c[2])
  end
  return table.concat(out, GAP)
end

local function header(g, spec, x, y, w)
  local titles = {}
  for i = 1, #spec do titles[i] = spec[i][1] end
  g:color(160, 190, 240); g:text(cells(spec, titles), x, y); g:color()
  g:color(70, 80, 100); g:line(x, y + LINE - 1, x + w, y + LINE - 1); g:color()
  return y + LINE + 2
end

-- One striped row. `i` is the row index (1-based) -- odd rows get the stripe.
local function rowbg(g, i, x, y, w)
  if (i % 2) == 1 then
    g:color(255, 255, 255, 14); g:frect(x - 2, y - 1, w + 2, LINE); g:color()
  end
end

-- ------------------------------------------------------------------ pause: the ring is the recording
--
-- Pausing captures every snapshot ONCE and keeps drawing those. They are plain tables with no identity
-- and no live link into the engine (that is the whole point of the snapshot rule), so holding them costs
-- nothing and they cannot go stale under us -- a frozen table is exactly the frame it was taken from.

local paused = false
local snap                                            -- the frozen tables, or nil while live
local sel                                             -- the scrubbed sample index into snap.history

local function capture()
  return {
    frame = p:frame(), history = p:history(G_N), passes = p:passes(), gl = p:gl(),
    widgets = p:widgets(), addons = p:addons(), overhead = p:overhead(),
    memory = p:memory(), net = p:net(), loader = p:loader(), render = p:render(),
  }
end

-- Live: read just the one snapshot this tab needs. Paused: hand back the frozen one.
local function get(name)
  if snap then return snap[name] end
  if name == "history" then return p:history(G_N) end
  return p[name](p)
end

local function pause(on)
  paused = on and true or false
  snap = paused and capture() or nil
  if not paused then sel = nil end
end

-- ------------------------------------------------------------------------------------------- the tabs

-- FRAME ---------------------------------------------------------------------------------------------

-- The graph, and while paused the timeline: one bar per sample, tallest = the worst frame in view (never
-- below 20 ms, so a smooth run is not amplified into a mountain range). Colour is bucketed by the 60 fps
-- budget, and the colour call only happens when the bucket CHANGES -- 180 bars, rarely 180 switches.
local function drawGraph(g, h)
  local n = #h
  g:color(0, 0, 0, 120); g:frect(G_X, G_Y, G_N * G_BAR, G_H); g:color()
  if n == 0 then
    g:color(150, 150, 150); g:text("no samples yet", G_X + 6, G_Y + 6); g:color()
    return
  end
  local peak = 20
  for i = 1, n do if h[i].ms > peak then peak = h[i].ms end end
  local scale = G_H / peak

  local y60 = G_Y + G_H - 16.7 * scale                -- the 60 fps reference
  if y60 > G_Y then
    g:color(90, 90, 60); g:line(G_X, y60, G_X + G_N * G_BAR, y60); g:color()
  end

  local bucket
  for i = 1, n do
    local f = h[i]
    local b = (f.ms <= 16.7) and 1 or ((f.ms <= 33.3) and 2 or 3)
    if b ~= bucket then
      bucket = b
      if b == 1 then g:color(90, 200, 110) elseif b == 2 then g:color(220, 200, 90) else g:color(220, 100, 90) end
    end
    local bh = math.max(1, f.ms * scale)
    g:frect(G_X + (i - 1) * G_BAR, G_Y + G_H - bh, G_BAR - 1, bh)
  end
  -- The GPU column, where it has come back: a marker on top of the bar rather than a second bar, so the
  -- two are read against each other. Frames whose timestamps are still in flight simply have no marker
  -- (an absent key means "not measured" -- drawing it as 0 would be a lie).
  g:color(110, 170, 255)
  for i = 1, n do
    local gm = h[i].gpuMs
    if gm then
      local y = G_Y + G_H - math.max(1, gm * scale)
      g:line(G_X + (i - 1) * G_BAR, y, G_X + (i - 1) * G_BAR + G_BAR - 1, y)
    end
  end
  g:color()
  if sel and h[sel] then                              -- the timeline cursor
    local x = G_X + (sel - 1) * G_BAR + 1
    g:color(255, 255, 255); g:line(x, G_Y - 3, x, G_Y + G_H + 3); g:color()
  end
  g:color(120, 120, 120); g:rect(G_X, G_Y, G_N * G_BAR, G_H); g:color()
  g:color(150, 150, 150)
  g:text(("%d samples   peak %s ms   line = 60 fps   blue = GPU   %s"):format(
    n, f2(peak), paused and "click a bar to inspect" or "click to pause + scrub"),
    G_X, G_Y + G_H + 2)
  g:color()
end

-- The selected (or current) frame, broken down: the numbers, a stacked phase bar, and the phase list.
-- While scrubbing this is a HISTORY entry -- a frame that finished seconds ago -- which is the whole
-- reason to pause: the frame you want to explain is never the frame you are looking at.
local function drawDetail(g, w, y, f, scrubbed)
  local total = 0
  for i = 1, #PHASES do total = total + ((f.phases and f.phases[PHASES[i]]) or 0) end

  g:color(230, 230, 160)
  g:text(("frame #%d   %s ms%s   addons %s ms%s"):format(
    f.frameno or 0, f2(f.ms), f.gpuMs and ("   gpu " .. f2(f.gpuMs) .. " ms") or "",
    f3(f.addons), scrubbed and "      [<]  [>]" or ""), 6, y)
  g:color()
  y = y + LINE + 2

  -- the stacked phase bar: where those milliseconds went, to scale
  local x, bw = 6, w - 12
  g:color(0, 0, 0, 120); g:frect(x, y, bw, 12); g:color()
  if total > 0 then
    for i = 1, #PHASES do
      local v = (f.phases and f.phases[PHASES[i]]) or 0
      local seg = (v / total) * bw
      if seg >= 1 then
        local c = PCOLOR[i]
        g:color(c[1], c[2], c[3]); g:frect(x, y, seg, 12); g:color()
        x = x + seg
      end
    end
  end
  y = y + 16

  local parts = {}
  for i = 1, #PHASES do
    parts[i] = PHASES[i] .. " " .. f3(f.phases and f.phases[PHASES[i]])
  end
  g:text(table.concat(parts, "   "), 6, y)
  return y + LINE + 4
end

local function drawFrameTab(g, w, h)
  local f = get("frame")
  local hist = get("history")
  if not f.ms and (#hist == 0) then
    g:color(200, 180, 120)
    if client():profiling() then
      g:text("armed -- waiting for the first sampled frame (arming takes effect on the NEXT frame)", 6, Y0)
    else
      g:text("profiling is off. Click the status line above, or tick Options > Client > Enable profiling.", 6, Y0)
      g:text("The COUNTERS tab works either way -- those numbers are pull-only.", 6, Y0 + LINE)
    end
    g:color()
    return
  end

  g:color(230, 230, 160)
  g:text(R(fx(f.fps, 1), 6) .. " fps" .. GAP .. R(f2(f.ms), 7) .. " ms" .. GAP ..
         "avg " .. R(f2(f.msAvg), 7) .. GAP .. "min " .. R(f2(f.msMin), 7) .. GAP ..
         "max " .. R(f2(f.msMax), 7) .. GAP .. "p95 " .. R(f2(f.msP95), 7), 6, Y0)
  g:color()
  g:text("idle " .. R(pct(f.idle), 6) .. GAP .. "latency " .. R(f2(f.latency), 6) .. " ms" .. GAP ..
         "ui " .. R(f2(f.ui), 6) .. " ms" .. GAP ..
         (f.gpuMs and ("gpu " .. R(f2(f.gpuMs), 6) .. " ms (frame #" .. f.gpuFrameno .. ")")
                  or "gpu -- (no GL timestamps back yet)"), 6, Y0 + LINE)

  p:measure("graph", drawGraph, g, hist)

  -- the detail block: the scrubbed sample while paused on one, else the frame that just finished
  local y = G_Y + G_H + 18
  local scrubbed = (sel ~= nil) and hist[sel] or nil
  y = drawDetail(g, w, y, scrubbed or f, scrubbed ~= nil)

  -- The render thread only ever describes the LIVE frame: p:history() carries the UI-thread phases, so
  -- there is nothing to scrub here. Absent beats a wrong number.
  g:color(160, 190, 240); g:text("render thread", 6, y); g:color()
  y = y + LINE
  if scrubbed then
    g:color(150, 150, 150)
    g:text("  (not recorded per frame -- the history ring keeps the UI-thread phases only)", 6, y)
    g:color()
  else
    local parts = {}
    for i = 1, #RPHASES do
      parts[i] = RPHASES[i] .. " " .. f3(f.render and f.render[RPHASES[i]])
    end
    g:text("  " .. table.concat(parts, "   "), 6, y)
    g:color(150, 150, 150)
    g:text("  (lags about one frame: it closes on the next frame's fence)", 6, y + LINE)
    g:color()
  end
end

-- PASSES --------------------------------------------------------------------------------------------

local P_SPEC = { { "pass", 8, "l" }, { "cpu ms", 9, "r" }, { "gpu ms", 9, "r" }, { "% of gpu frame", 15, "r" } }

local function drawPassesTab(g, w, h)
  local ps = get("passes")
  local y = Y0
  if not ps.frameno then
    g:color(200, 180, 120)
    g:text(client():profiling() and "armed -- waiting for the first frame whose GL timestamps come back"
                                 or "profiling is off -- the passes are armed-only", 6, y)
    g:color()
  else
    g:color(150, 150, 150)
    g:text(("frame #%d -- whole frame: cpu %s ms, gpu %s ms"):format(ps.frameno, f2(ps.ms), f2(ps.gpuMs)), 6, y)
    g:color()
    y = y + LINE + 2
    y = header(g, P_SPEC, 6, y, 420)
    local peak = 0.01
    for _, r in ipairs(ps) do if r.gpuMs > peak then peak = r.gpuMs end end
    for i, r in ipairs(ps) do
      rowbg(g, i, 6, y, 420)
      g:text(cells(P_SPEC, { r.name, f3(r.cpuMs), f3(r.gpuMs),
                             (ps.gpuMs > 0) and pct(r.gpuMs / ps.gpuMs) or "--" }), 6, y)
      g:color(110, 170, 255); g:frect(440, y + 3, math.max(1, (r.gpuMs / peak) * 240), 7); g:color()
      y = y + LINE
    end
    g:color(150, 150, 150)
    y = y + 6
    g:text("the rows are SELF time and disjoint: shadow and scene run inside the widget", 6, y)
    g:text("draw, so ui2d is the 2D UI and the three sum to less than the frame. Turn", 6, y + LINE)
    g:text("Video > Shadows off: shadow falls to zero AND scene drops too (the world's", 6, y + 2 * LINE)
    g:text("shaders stop sampling the shadow map).", 6, y + 3 * LINE)
    g:color()
    y = y + 4 * LINE + 8
  end

  local gl = get("gl")
  g:color(160, 190, 240); g:text("GL submission (armed-only -- nothing counts these otherwise)", 6, y); g:color()
  y = y + LINE + 2
  if not gl.drawCalls then
    g:color(150, 150, 150); g:text("  --", 6, y); g:color()
  else
    g:text("  " .. L("draw calls", 14) .. R(num(gl.drawCalls), 12) .. GAP ..
           L("program binds", 14) .. R(num(gl.programBinds), 12), 6, y)
    g:text("  " .. L("vertices", 14) .. R(num(gl.vertices), 12) .. GAP ..
           L("triangles", 14) .. R(num(gl.triangles), 12), 6, y + LINE)
    g:color(150, 150, 150)
    g:text("  binds far below calls = the draw list's sort by program is doing its job", 6, y + 2 * LINE)
    g:color()
  end
end

-- WIDGETS -------------------------------------------------------------------------------------------

local W_SPEC = { { "type", 24, "l" }, { "n", 4, "r" }, { "tick ms", 9, "r" }, { "draw ms", 9, "r" },
                 { "self ms", 9, "r" } }
local T_SPEC = { { "type", 24, "l" }, { "self ms", 9, "r" }, { "tick ms", 9, "r" }, { "draw ms", 9, "r" },
                 { "id", 6, "r" }, { "owner", 14, "l" } }

local function drawWidgetsTab(g, w, h)
  local ws = get("widgets")
  if not ws.byType then
    g:color(200, 180, 120)
    g:text(client():profiling() and "armed -- waiting for the first sampled frame"
                                 or "profiling is off -- the widget breakdown is armed-only", 6, Y0)
    g:color()
    return
  end
  local t = ws.total
  g:color(230, 230, 160)
  g:text(("whole tree: tick %s ms + draw %s ms = %s ms over %d measured widgets"):format(
    f2(t.tickMs), f2(t.drawMs), f2(t.ms), t.count or 0), 6, Y0)
  g:color()
  local y = header(g, W_SPEC, 6, Y0 + LINE + 4, 480)
  local rows = math.min(#ws.byType, 12)
  for i = 1, rows do
    local r = ws.byType[i]
    rowbg(g, i, 6, y, 480)
    g:text(cells(W_SPEC, { r.type, num(r.count), f3(r.tickMs), f3(r.drawMs), f3(r.selfMs) }), 6, y)
    y = y + LINE
  end
  if #ws.byType > rows then
    g:color(120, 120, 120); g:text(("... (+%d more types)"):format(#ws.byType - rows), 6, y); g:color()
  end
  y = y + LINE + 6
  g:color(160, 190, 240); g:text("heaviest individual widgets", 6, y); g:color()
  y = header(g, T_SPEC, 6, y + LINE, 560)
  for i = 1, math.min(#ws.top, 6) do
    local r = ws.top[i]
    rowbg(g, i, 6, y, 560)
    g:text(cells(T_SPEC, { r.type, f3(r.selfMs), f3(r.tickMs), f3(r.drawMs),
                           r.id and ("#" .. r.id) or "--", r.owner or "" }), 6, y)
    y = y + LINE
  end
  g:color(150, 150, 150)
  g:text("self = inclusive minus children: a container with one expensive child blames the child.", 6, y + 4)
  g:text("owner = an addon put it there -- it is in the ADDONS tab too, one measurement.", 6, y + 4 + LINE)
  g:color()
end

-- ADDONS --------------------------------------------------------------------------------------------

-- The five call columns are the callLua categories, abbreviated so the row fits: evt = events,
-- tmr = timers, drw = draw callbacks, hk = hooks/hotkeys/slash, wdg = the rest of a custom widget's life.
local A_SPEC = { { "addon", 16, "l" }, { "ms", 8, "r" }, { "avg", 8, "r" }, { "peak", 8, "r" },
                 { "share", 7, "r" }, { "evt", 5, "r" }, { "tmr", 5, "r" }, { "drw", 5, "r" },
                 { "hk", 5, "r" }, { "wdg", 5, "r" } }
-- A scope row shares the addon row's first four columns exactly (so ms/avg/peak stay in their columns)
-- and then skips `share`, which a scope does not have; its call count lands last as "xN".
local S_SPEC = { { "  scope", 16, "l" }, { "ms", 8, "r" }, { "avg", 8, "r" }, { "peak", 8, "r" },
                 { "", 7, "r" }, { "calls", 5, "r" } }

local function drawAddonsTab(g, w, h)
  local rows = get("addons")
  if not rows.total then
    g:color(200, 180, 120)
    g:text(client():profiling() and "armed -- waiting for the first sampled frame"
                                 or "profiling is off -- per-addon cost is armed-only", 6, Y0)
    g:color()
    return
  end
  g:color(230, 230, 160)
  g:text(("all Lua this frame: %s ms (%s of the frame) -- the same number as frame().addons"):format(
    f3(rows.total.ms), pct(rows.total.share)), 6, Y0)
  g:color()
  local TW = 640
  local y = header(g, A_SPEC, 6, Y0 + LINE + 4, TW)
  local i = 0
  for _, r in ipairs(rows) do
    if y > h - LINE * 2 then break end
    i = i + 1
    rowbg(g, i, 6, y, TW)
    g:text(cells(A_SPEC, { r.id, f3(r.ms), f3(r.msAvg), f3(r.msPeak), pct(r.share),
                           num(r.calls.events), num(r.calls.timers), num(r.calls.draw),
                           num(r.calls.hooks), num(r.calls.widgets) }), 6, y)
    y = y + LINE
    -- Each addon's named scopes, indented under it. This addon's own "draw"/"graph" scopes are here,
    -- which is the point: p:measure() attributing to the CALLING addon, demonstrated live.
    for name, s in pairs(r.scopes) do
      if y > h - LINE * 2 then break end
      i = i + 1
      rowbg(g, i, 6, y, TW)
      g:color(160, 200, 160)
      g:text(cells(S_SPEC, { "  " .. name, f3(s.ms), f3(s.msAvg), f3(s.msPeak), "", "x" .. num(s.calls) }), 6, y)
      g:color()
      y = y + LINE
    end
  end
  g:color(150, 150, 150)
  g:text("calls: evt=events tmr=timers drw=draw hk=hooks wdg=widgets. '(console)' is the :lua REPL.",
         6, h - LINE - 4)
  g:color()
end

-- COUNTERS ------------------------------------------------------------------------------------------

-- A label/value pair block: the label column is fixed, so every value in the tab starts at one x.
local function kv(g, y, label, value)
  g:text("  " .. L(label, 14) .. value, 6, y)
  return y + LINE
end

local function drawCountersTab(g, w, h)
  g:color(150, 150, 150)
  g:text("pull-only: these answer with profiling OFF and match ':stats on' field by field", 6, Y0)
  g:color()
  local y = Y0 + LINE + 4

  local m = get("memory")
  g:color(160, 190, 240); g:text("memory", 6, y); g:color(); y = y + LINE
  y = kv(g, y, "heap", "used " .. R(mb(m.heapUsed), 11) .. GAP .. "free " .. R(mb(m.heapFree), 11) ..
                       GAP .. "total " .. R(mb(m.heapTotal), 11) .. GAP .. "max " .. R(mb(m.heapMax), 11))
  -- allocPerFrame and the gc totals are ABSENT rather than 0 when the client has not computed them (the
  -- HUD has never been drawn / no management beans), so they show as "--", not as a zero.
  y = kv(g, y, "alloc/frame", R(mb(m.allocPerFrame), 11) .. GAP ..
                              "gc " .. num(m.gcCount) .. " collections, " .. num(m.gcMs) .. " ms cumulative")
  y = y + 6

  local n = get("net")
  g:color(160, 190, 240); g:text("net", 6, y); g:color(); y = y + LINE
  if not n.rtt then
    g:color(150, 150, 150); g:text("  no connection (the login screen)", 6, y); g:color(); y = y + LINE
  else
    y = kv(g, y, "tx", R(num(n.packetsTx), 9) .. " pkt" .. GAP .. R(mb(n.bytesTx), 11) ..
                       GAP .. "resent " .. num(n.resentTx))
    y = kv(g, y, "rx", R(num(n.packetsRx), 9) .. " pkt" .. GAP .. R(mb(n.bytesRx), 11) ..
                       GAP .. "resent " .. num(n.resentRx) .. ", reordered " .. num(n.reorderedRx))
    y = kv(g, y, "rtt", R(f2(n.rtt), 9) .. " ms" .. GAP .. "variance " .. f2(n.rttVar) .. " ms")
  end
  y = y + 6

  local l = get("loader")
  local d = l.defer or {}
  g:color(160, 190, 240); g:text("loader", 6, y); g:color(); y = y + LINE
  y = kv(g, y, "ui", "queued " .. R(num(l.queued), 5) .. GAP .. "loading " .. R(num(l.loading), 5) ..
                     GAP .. "busy " .. R(num(l.busy), 5) .. GAP .. "pool " .. R(num(l.poolSize), 5))
  y = kv(g, y, "defer", "queued " .. R(num(d.queued), 5) .. GAP .. "busy    " .. R(num(d.busy), 5) ..
                        GAP .. "pool " .. R(num(d.poolSize), 5))
  y = kv(g, y, "resources", "queue " .. R(num(l.resQueue), 6) .. GAP .. "loaded " .. num(l.resLoaded))
  y = y + 6

  local r = get("render")
  g:color(160, 190, 240); g:text("render", 6, y); g:color(); y = y + LINE
  if not r.drawSlots then
    g:color(150, 150, 150)
    y = kv(g, y, "state slots", num(r.stateSlots) .. "   (the scene counters need a world + a GL environment)")
    g:color()
    return
  end
  y = kv(g, y, "draw slots", R(num(r.drawSlots), 8) .. GAP .. "batches " .. R(num(r.batches), 8) ..
                             GAP .. "instances " .. R(num(r.instances), 8) ..
                             GAP .. "unique " .. R(num(r.uniqueInstances), 8))
  y = kv(g, y, "invalid", R(num(r.invalid), 8) .. GAP .. "bypass  " .. R(num(r.bypass), 8) ..
                          GAP .. "tree " .. num(r.treeLeaves) .. " leaves / " .. num(r.treeNodes) .. " nodes")
  y = kv(g, y, "programs", R(num(r.programs), 8) .. GAP .. "state slots " .. num(r.stateSlots))
  if r.vram then
    local out = {}
    for _, k in ipairs({ "vertices", "indices", "textures", "vaos", "fbos" }) do
      local v = r.vram[k]
      if v then out[#out + 1] = k .. " " .. mb(v.bytes) end
    end
    kv(g, y, "vram", table.concat(out, GAP))
  end
end

-- OVERHEAD ------------------------------------------------------------------------------------------

local O_SPEC = { { "tier", 9, "l" }, { "ms", 9, "r" }, { "share", 8, "r" }, { "modelled ms", 12, "r" },
                 { "method", 8, "r" }, { "hits/frame", 11, "r" } }

local function drawOverheadTab(g, w, h)
  local o = get("overhead")
  if not o.totalMs then
    g:color(200, 180, 120)
    g:text("profiling is off -- there is nothing to account for", 6, Y0)
    g:color()
    return
  end
  if o.withinBudget == false then g:color(230, 120, 100) else g:color(140, 220, 140) end
  g:text(("profiling costs %s ms of a %s ms frame = %s   (budget %s -- %s)"):format(
    fx(o.totalMs, 4), f2(o.frameMs), pct(o.shareOfFrame), pct(o.budget),
    (o.withinBudget == false) and "OVER" or "within"), 6, Y0)
  g:color()
  local y = Y0 + LINE + 2
  y = kv(g, y, "method", o.method)
  y = kv(g, y, "aggregator", R(fx(o.aggregatorMs, 4), 8) .. " ms   (timed directly)")
  y = kv(g, y, "gpu queries", R(fx(o.gpuQueryMs, 4), 8) .. " ms   (timed directly)")
  y = kv(g, y, "probes", R(fx(o.probeMs, 4), 8) .. " ms   (modelled from the arm-time calibration)")
  if o.measuredMs then
    y = kv(g, y, "control", R(fx(o.measuredMs, 4), 8) .. " ms   +/- " .. fx(o.measuredErrorMs, 4) ..
                            "   (spread " .. fx(o.measuredSpreadMs, 4) .. ", " .. o.periods .. " periods)")
  else
    g:color(150, 150, 150)
    y = kv(g, y, "control", ("%d of %d periods collected"):format(o.periods, o.periodsNeeded))
    g:color()
  end
  y = kv(g, y, "frames", ("%d armed, %d control"):format(o.armedFrames, o.controlFrames))
  y = y + 6

  local TW = 620
  y = header(g, O_SPEC, 6, y, TW)
  for i, r in ipairs(o.tiers) do
    rowbg(g, i, 6, y, TW)
    g:text(cells(O_SPEC, { r.name, fx(r.ms, 4), pct(r.share), fx(r.modelledMs, 4), r.method,
                           num(r.hits) }), 6, y)
    y = y + LINE
  end
  g:color(150, 150, 150)
  y = y + 6
  g:text("'model' errs HIGH: the calibration loops run cold while the real probes run", 6, y)
  g:text("JIT-compiled. A measured cost at or below zero is the EXPECTED outcome -- the", 6, y + LINE)
  g:text("cost is under the comparison's own noise floor, not profiling making the client", 6, y + 2 * LINE)
  g:text("faster. Then the model has the say.", 6, y + 3 * LINE)
  g:color()
end

local DRAW = {
  frame    = drawFrameTab,
  passes   = drawPassesTab,
  widgets  = drawWidgetsTab,
  addons   = drawAddonsTab,
  counters = drawCountersTab,
  overhead = drawOverheadTab,
}

-- ------------------------------------------------------------------------------------- the window

local function button(g, b, y, label, lit)
  g:color(lit and 70 or 45, lit and 90 or 45, lit and 70 or 45)
  g:frect(b[1], y - 1, b[2], LINE + 1)
  g:color(lit and 190 or 165, lit and 240 or 165, lit and 190 or 165)
  g:atext(label, b[1] + b[2] / 2, y, 0.5, 0)
  g:color(100, 100, 100); g:rect(b[1], y - 1, b[2], LINE + 1); g:color()
end

local function drawChrome(g, w, h)
  g:color(0, 0, 0, 190); g:frect(0, 0, w, h); g:color()
  for i = 1, #TABS do
    local x = (i - 1) * TAB_W
    if i == tab then
      g:color(60, 80, 60); g:frect(x, 0, TAB_W - 2, TAB_H); g:color(180, 240, 180)
    else
      g:color(40, 40, 40); g:frect(x, 0, TAB_W - 2, TAB_H); g:color(170, 170, 170)
    end
    g:atext(TABS[i]:upper(), x + TAB_W / 2, 3, 0.5, 0)
    g:color()
  end
  -- the clickable status line: the one switch, from here
  local on = client():profiling()
  g:color(on and 140 or 200, on and 220 or 180, on and 140 or 120)
  g:text("profiling: " .. L(on and "ON" or "OFF", 4) ..
         ("(click to turn it %s)"):format(on and "off" or "on"), 6, Y_STATUS)
  g:color()
  button(g, BTN_PAUSE, Y_STATUS, paused and "PAUSED" or "LIVE", paused)
  button(g, BTN_CLEAR, Y_STATUS, "CLEAR", false)
end

local function draw(g, w, h)
  drawChrome(g, w, h)
  DRAW[TABS[tab]](g, w, h)
  g:color(120, 120, 120); g:rect(0, 0, w, h); g:color()
end

-- Is (x, y) inside the button {x, width} on the status line?
local function hit(b, x, y)
  return (y >= Y_STATUS - 1) and (y <= Y_STATUS + LINE) and (x >= b[1]) and (x <= b[1] + b[2])
end

local function click(x, y, button)
  if y < TAB_H then                                   -- the tab bar
    local i = math.floor(x / TAB_W) + 1
    if i >= 1 and i <= #TABS then tab = i end
  elseif hit(BTN_PAUSE, x, y) then
    pause(not paused)
  elseif hit(BTN_CLEAR, x, y) then
    p:reset()                                         -- empty the ring: clear, act, pause, scrub
    pause(false)
  elseif y < Y_STATUS + LINE then
    local c = client()
    c:profiling(not c:profiling())                    -- arity is the verb: one argument writes, and it chains
  elseif (TABS[tab] == "frame") and (y >= G_Y) and (y <= G_Y + G_H) and
         (x >= G_X) and (x < G_X + G_N * G_BAR) then
    -- The timeline. A live graph shifts one bar per frame, so pointing at a bar only means something
    -- once it holds still: clicking while live pauses first, then selects.
    if not paused then pause(true) end
    local i = math.floor((x - G_X) / G_BAR) + 1
    local n = #(snap and snap.history or {})
    sel = (i >= 1) and (i <= n) and i or nil
  elseif paused and sel and (TABS[tab] == "frame") and
         (y >= G_Y + G_H + 18) and (y < G_Y + G_H + 18 + LINE) then
    local n = #(snap and snap.history or {})
    if x >= 300 and x < 330 then sel = math.max(1, sel - 1)          -- [<]
    elseif x >= 330 and x < 366 then sel = math.min(n, sel + 1) end  -- [>]
  end
  return true                                         -- consume: never fall through to the world
end

local function open()
  if win then return end
  win = hafen.ui.window{
    title  = "Brodgar.io Profiler",
    size   = { WIN_W, WIN_H },
    pos    = { 80, 60 },
    font   = FONT,
    -- The whole draw is one scope, with the graph nested inside its own: this addon's cost and its two
    -- scopes appear in the ADDONS tab while you are reading it.
    onDraw = function(g, w, h) p:measure("draw", draw, g, w, h) end,
    onClick = click,
    onClose = function()
      win = nil
      hafen.log("profiler: closed -- ':profiler' or the 'toggle' hotkey brings it back")
    end,
  }
end

local function toggle()
  if win then win:destroy(); win = nil else open() end
  hafen.log(("profiler: window %s"):format(win and "open" or "closed"))
end

-- Both hotkeys start UNBOUND (D-047): assign them under Options > Keybindings > Brodgar.io Profiler.
-- Suggested keys: Ctrl+Shift+P (toggle) and Ctrl+Shift+O (pause).
local keys = hafen.client:options():keybindings()
keys:register("toggle", toggle)
keys:register("pause", function()
  pause(not paused)
  hafen.log(("profiler: %s"):format(paused and "PAUSED -- click a bar in the FRAME graph to inspect it" or "live"))
end)

-- :profiler [on|off|pause|live|clear|<tab>]
hafen.slash.register("profiler", function(args)
  local a = (args and args[1] or ""):lower()
  if a == "" then
    toggle()
  elseif a == "on" or a == "off" then
    client():profiling(a == "on")
    hafen.log(("profiler: profiling %s"):format(a:upper()))
  elseif a == "pause" or a == "live" then
    pause(a == "pause")
    open()
    hafen.log(("profiler: %s"):format(paused and "PAUSED" or "live"))
  elseif a == "clear" then
    p:reset()
    pause(false)
    hafen.log("profiler: ring cleared -- it refills as the client draws")
  else
    for i = 1, #TABS do
      if TABS[i] == a then
        tab = i
        open()
        hafen.log("profiler: tab " .. a)
        return
      end
    end
    hafen.log("profiler: usage -- :profiler [on|off|pause|live|clear|" .. table.concat(TABS, "|") .. "]")
  end
end)

hafen.log("profiler loaded (v0.2.0) -- dormant; ':profiler' or the 'toggle' hotkey opens the window")
