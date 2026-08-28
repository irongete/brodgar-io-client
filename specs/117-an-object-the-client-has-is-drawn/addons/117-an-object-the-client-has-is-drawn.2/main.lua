-- 117.2 — a dropped render add is not for ever. Self-checking suite.
-- Type :t117 in the world and then STAND STILL for about 12 seconds. The suite puts a counting overlay on
-- every object the world lists and closes a "pass" at every frame: an overlay of yours is drawn once per
-- slot its gob stands in, so the count IS the answer to "is this object in the scene?" -- zero is an object
-- the OCache holds and the scene does not, two is the double-add the reconcile's `adding` guard prevents.
--
-- With one correction, and it is the whole reason for the second half of this file: an overlay is also not
-- painted for a gob the camera cannot see. There is no screen point to paint it at, so the painter returns
-- and the count reads zero for a reason that has nothing to do with the scene. `worldToScreen` is the
-- client's own answer to that same question -- the same projection, the same frame, the same camera -- so
-- every silence is put to it before it is scored. Only the SILENT need asking: an object that painted was
-- projectable by definition, which is what makes asking at all affordable over a whole view.

local pass, fail = 0, 0

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function report()
  hafen.log():write(("[summary] %d pass, %d fail, 0 manual"):format(pass, fail))
end

local COUNT = "t117c"
local SETTLE, SAMPLE = 1.5, 9   -- seconds: let the view settle, then count
local GRACE = 45                -- passes with a screen point before "never drawn" is a verdict
local GATE = 96                 -- silences a single pass will ask the projection about; the rest go unscored

local function head(t, n)
  local out = {}
  for i = 1, math.min(#t, n) do out[i] = t[i] end
  if #t > n then out[n + 1] = "(+" .. (#t - n) .. " more)" end
  return table.concat(out, "; ")
end

local function start()
  local s = hafen.session():current()
  if not s then
    check(false, "a character is on screen", "none -- run :t117 in the world")
    return report()
  end
  local first = s:world():gob():list()
  if #first == 0 then
    check(false, "the character can see at least one object", "none in view")
    return report()
  end

  local logins = #hafen.session():list()
  hafen.log():write("[t117.2] stand still for about " .. (SETTLE + SAMPLE + 1) .. "s -- "
                    .. #first .. " objects in view"
                    .. ((logins > 1) and (", " .. logins .. " characters logged in") or ""))

  -- ---- the counting overlay: one closure per object, so a frame costs one table increment each --------
  local world = s:world()          -- the same object every call, so the draw pass below allocates nothing
  local watched, byid = {}, {}
  local perPass, silent = {}, {}
  local frames, passes, dark, ungated, gateCursor = 0, 0, 0, 0, 0
  local sampling = false

  local function attach(g)
    local id = g:id()
    if byid[id] then return end
    -- An object whose own drawing is still resolving takes no overlay in that instant; the sweep below is
    -- the retry the overlay page names, so a refusal here is not final.
    local ok = pcall(function()
      g:overlay():add(COUNT):draw(function() perPass[id] = (perPass[id] or 0) + 1 end)
    end)
    if not ok then return end
    local w = {id = id, gob = g, from = passes, lit = 0, seen = 0, zero = 0, blind = 0,
               dbl = 0, worst = 0, k = 0, kk = 0, shown = false, live = false, dead = false, born = false}
    watched[#watched + 1] = w
    byid[id] = w
  end

  for i = 1, #first do attach(first[i]) end
  check(#watched > 0, "a counting overlay attached to the objects in view (" .. #watched
        .. " of " .. #first .. ")", "none attached")
  if #watched == 0 then return report() end

  -- ---- the pass boundary: our own window draws after the map does, in the same frame -----------------
  local win = hafen.ui():window():title("117.2"):size(140, 18):position(12, 12)

  win:on("Draw", function()
    frames = frames + 1
    if not sampling then return end
    local n, nsilent = 0, 0
    for i = 1, #watched do
      local w = watched[i]
      local k = perPass[w.id] or 0
      perPass[w.id] = nil
      w.kk = k            -- not w.k yet: a pass that turns out dark must leave the last real one standing
      n = n + k
      if (k == 0) and (not w.dead) then
        nsilent = nsilent + 1
        silent[nsilent] = w
      end
    end
    -- Nothing at all painted: MapView.draw threw Loading and never reached the 2D pass, so this frame says
    -- nothing about any object. Counting it would score every one of them a miss.
    if n == 0 then
      dark = dark + 1
      for i = 1, nsilent do silent[i] = nil end
      return
    end
    passes = passes + 1
    for i = 1, #watched do
      local w = watched[i]
      w.k = w.kk
      if (not w.dead) and (w.k > 0) then
        w.lit, w.shown = w.lit + 1, true
        -- The first pass it painted in is the first one it can be held to: added(Gob) defers the slot add
        -- to a Loader thread, so an object is announced some frames before it is drawn.
        w.live, w.seen = true, w.seen + 1
        if w.k > 1 then
          w.dbl = w.dbl + 1
          if w.k > w.worst then w.worst = w.k end
        end
      end
    end
    -- Put every silence to the client before scoring it. A gob with no screen point had nowhere for its
    -- painter to run -- Eye answers nil and the overlay returns without drawing -- and that is the camera,
    -- not the scene having lost the object.
    -- Capped, and the cap rotates: pulled all the way in on the `bad` camera most of a view can be behind
    -- the eye at once, and asking about the same first GATE of them every pass would leave the rest never
    -- asked at all rather than asked less often.
    local asked, base = 0, gateCursor
    for i = 1, nsilent do
      local w = silent[((base + i - 1) % nsilent) + 1]
      local p = (asked < GATE) and w.gob:position() or nil
      if p and world:worldToScreen(p) then
        asked = asked + 1
        w.lit, w.shown = w.lit + 1, true
        if w.live then                          -- in the cache, on screen, and it did not paint
          w.seen, w.zero = w.seen + 1, w.zero + 1
        end
      elseif p then
        asked = asked + 1
        w.blind, w.shown = w.blind + 1, false
      else
        if asked >= GATE then ungated = ungated + 1 end
        w.shown = false                         -- no claim: unasked, or the object has no place at all
      end
    end
    gateCursor = base + asked
    for i = 1, nsilent do silent[i] = nil end
  end)

  -- ---- the arrival path the reconcile races, and the departure that is not a miss --------------------
  local subAdd = hafen.event():on("GobAdded", function(g)
    attach(g)
    local w = byid[g:id()]
    if w and sampling then w.born = true end
  end)
  local subRem = hafen.event():on("GobRemoved", function(g)
    local w = byid[g:id()]
    if w then w.dead = true end
  end)
  -- Objects whose attach was refused, and anything the events did not reach us about.
  local sweep = hafen.timer():every(1, function()
    local cur = hafen.session():current()
    if not cur then return end
    local now = cur:world():gob():list()
    for i = 1, #now do attach(now[i]) end
  end)

  local function score(listNow, listN, covered, cutoff)
    sampling = false
    sweep:cancel()
    subAdd:off()
    subRem:off()

    check(passes > 0, "the client drew the scene through the window (" .. passes .. " of "
          .. frames .. " frames; " .. dark .. " drew no scene at all)",
          "no frame drew the scene -- nothing was sampled")

    -- ---- exactly once, and none missing --------------------------------------------------------------
    local short, doubled, judged, blind = {}, {}, 0, 0
    for i = 1, #watched do
      local w = watched[i]
      -- Judged on what is still there: an object that left the view stopped painting because it left, and
      -- its zeros are its departure rather than a stranding.
      if w.live and (not w.dead) and listNow[w.id] and (w.seen > 0) then
        judged = judged + 1
        blind = blind + w.blind
        if w.zero > 0 then
          short[#short + 1] = w.id .. ": on screen and drew nothing in " .. w.zero .. " of " .. w.seen
        end
        if w.dbl > 0 then
          doubled[#doubled + 1] = w.id .. ": drew " .. w.worst .. "x in " .. w.dbl .. " of " .. w.seen
        end
      end
    end
    check((judged > 0) and (#short == 0),
          "no object missed a frame it had a screen point for (" .. judged .. " objects, "
          .. passes .. " frames, " .. blind .. " gob-frames off camera, " .. ungated .. " unasked)",
          (judged == 0) and "no object stayed in view" or (#short .. " stranded -- " .. head(short, 4)))
    check((judged > 0) and (#doubled == 0),
          "no object's overlay ever painted twice in a frame (" .. judged .. " objects over "
          .. passes .. ")",
          (judged == 0) and "no object stayed in view"
          or (#doubled .. " double-added -- " .. head(doubled, 4)))

    -- ---- the same count for what arrived mid-run -----------------------------------------------------
    local nborn, nbad = 0, {}
    for i = 1, #watched do
      local w = watched[i]
      if w.born and w.live and (not w.dead) and listNow[w.id] then
        nborn = nborn + 1
        if (w.zero > 0) or (w.dbl > 0) then
          nbad[#nbad + 1] = w.id .. ": " .. w.zero .. " missed, " .. w.dbl .. " doubled, of " .. w.seen
        end
      end
    end
    check(#nbad == 0, (nborn == 0)
          and "objects arriving mid-run are held to the same count (none arrived)"
          or ("every object that arrived mid-run painted once a frame too (" .. nborn .. ")"),
          head(nbad, 4))

    -- ---- an object the cache holds is an object that reached the scene at all -------------------------
    local stranded, aged = {}, 0
    for i = 1, #watched do
      local w = watched[i]
      -- Judged on frames it HAD a screen point for: an object the camera never saw was never asked to
      -- paint, and reading its silence as a stranding is reading the camera.
      if (not w.dead) and listNow[w.id] and (w.lit >= GRACE) then
        aged = aged + 1
        if not w.live then stranded[#stranded + 1] = tostring(w.id) end
      end
    end
    check((aged > 0) and (#stranded == 0),
          "every object the world lists reached the scene (" .. aged .. " judged)",
          (aged == 0) and "no object was on screen long enough to judge"
          or (#stranded .. " never drew -- " .. head(stranded, 5)))

    -- ---- and the two sets agree, both ways -----------------------------------------------------------
    -- `shown` is that object's own answer from the last counted pass: it painted, or the client said it
    -- had a screen point and it did not. Either way the camera is out of the comparison.
    local quiet, fwd = {}, 0
    for id in pairs(listNow) do
      local w = byid[id]
      if w and w.live and (not w.dead) and w.shown then
        fwd = fwd + 1
        if w.k < 1 then quiet[#quiet + 1] = tostring(id) end
      end
    end
    check((fwd > 0) and (#quiet == 0),
          "every object the world lists and the camera can see is painting (" .. fwd .. " on screen of "
          .. listN .. " listed, " .. covered .. " carry the overlay)",
          (fwd == 0) and "no listed object was on screen and being counted" or head(quiet, 5))

    local stale, painting = {}, 0
    for i = 1, cutoff do
      local w = watched[i]
      if w.k >= 1 then
        painting = painting + 1
        if not listNow[w.id] then
          stale[#stale + 1] = w.id .. (w.gob:exists() and " (loaded elsewhere)" or " (gone)")
        end
      end
    end
    check(#stale == 0, "nothing painting has left that list (" .. painting .. " painting)",
          head(stale, 5))

    for i = 1, #watched do
      local w = watched[i]
      if w.gob:exists() then pcall(function() w.gob:overlay():remove(COUNT) end) end
    end
    pcall(function() win:destroy() end)
    report()
  end

  -- Snapshot the world, then go on counting for a moment before scoring: an object that left the view in
  -- the frame before this one is still holding the last pass's count, and that reads exactly like a slot
  -- the scene kept. Six more frames put every count strictly after the snapshot, and `cutoff` keeps what
  -- arrives inside them out of a comparison neither set was taken for.
  local function finish()
    local cur = hafen.session():current()
    local listNow, listN = {}, 0
    if cur then
      local now = cur:world():gob():list()
      listN = #now
      for i = 1, #now do listNow[now[i]:id()] = true end
    end
    local cutoff, covered = #watched, 0
    for id in pairs(listNow) do
      if byid[id] then covered = covered + 1 end
    end
    hafen.timer():after(0.1, function() score(listNow, listN, covered, cutoff) end)
  end

  hafen.timer():after(SETTLE, function()
    perPass = {}      -- the settling frames painted too, and nothing drained them
    sampling = true
    hafen.timer():after(SAMPLE, finish)
  end)
end

-- The console line runs under the character's own tree monitor, and a window of our own lives in the addon
-- layer, which is a second one. Getting onto the step first is the door threading names for that.
local function run()
  pass, fail = 0, 0                       -- a second :t117 scores its own run, not both
  hafen.timer():after(0, start)
end

hafen.console():on("t117", run)   -- the only way in: a suite does not start itself
