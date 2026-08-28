-- 118.4 -- the pages. Self-checking suite.
--
-- Every `lua` block docs/addons/api/vr/patches.md ships is pasted below VERBATIM, in the order the page has
-- them, with an assertion after each that it produced the patch the page claims. A page whose example does
-- not run is the defect this catches. The refusals the page's own table states are checked beside them,
-- because a documented refusal that does not happen is the same defect wearing a table row.
--
-- It declares NO permissions: every call below succeeding is what unprotected means here.

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

-- A refusal is a check: the call must fail, and fail SAYING why. The strip covers both shapes LuaJ writes --
-- "@chunk.lua:12: msg" for a Lua error and "@chunk.lua:12 msg" for one raised across the bridge.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- A ring of n points on a circle of radius r around p: convex by construction and n edges long, which is
-- how the edge limit the page states is put to the one test that can fail.
local function circle(p, r, n)
  local out = {}
  for i = 1, n do
    local a = (i - 1) * 2 * math.pi / n
    out[i] = p:offset(r * math.cos(a), r * math.sin(a))
  end
  return out
end

local function run()
  local me0 = hafen.session():current()
  me0 = me0 and me0:player():gob()
  if not (me0 and me0:position()) then
    check(false, "a character in the world to lay a patch under", "no session, no player gob, or no place")
    summary()
    return
  end
  local patches = hafen.vr():patch()
  -- The suite owns its own patches, so it starts from nothing however many times it is run.
  for _, old in ipairs(patches:list()) do patches:remove(old) end

  -- ---- patches.md, the opening block, verbatim -------------------------------------------------------
  local s = hafen.session():current()
  local here = s:player():gob():position()
  local ring = { here:offset(-6, -6), here:offset(6, -6),      -- a 12x12 square, in world units
                 here:offset(6, 6),   here:offset(-6, 6) }     -- a tile is 11 of them
  local patch = hafen.vr():patch():add(ring, here):tint{40, 200, 120}

  local first = patch                                          -- the click block below rebinds the name
  check((patch ~= nil) and patch:exists() and (patches:count() == 1) and (tostring(patch) == "Patch"),
        "the opening block lays a patch, the collection holds exactly it, and it prints as the bare kind",
        (patch == nil) and "nil" or (tostring(patch) .. ", count " .. patches:count()))
  refuses("a string filter is refused, a patch being a picture of nothing",
          function() return patches:list("square") end, "no name to match a string")

  -- ---- patches.md, "What a ring may be", verbatim ----------------------------------------------------
  local ok, err = pcall(function()
    hafen.vr():patch():add({ here, here:offset(6, 0) }, here)   -- two points
  end)
  hafen.log():write(tostring(err))                              -- ...says a line has no ground under it

  check((not ok) and (tostring(err):find("has no ground under it", 1, true) ~= nil),
        "the two-point block refuses, and the line it prints says why", ok and "<no error>" or err)

  -- The two rows of that same table no block on the page runs.
  refuses("a concave ring is refused naming convex", function()
    return patches:add({ here, here:offset(12, 0), here:offset(12, 6), here:offset(6, 6),
                         here:offset(6, 12), here:offset(0, 12) }, here)
  end, "a patch is convex")
  refuses("a ring over the edge limit is refused naming that number",
          function() return patches:add(circle(here, 6, 40), here) end, "at most 32")

  -- ---- patches.md, the snapshot block, verbatim ------------------------------------------------------
  local i = patch:info()
  hafen.log():write(i.kind .. ": " .. #i.ring .. " point(s), alpha " .. i.alpha)

  check((i.kind == "patch") and (#i.ring == 4) and (type(i.alpha) == "number"),
        "the snapshot block reads kind, ring and alpha off the patch the page laid",
        tostring(i.kind) .. "/" .. tostring(i.ring and #i.ring) .. "/" .. tostring(i.alpha))

  -- ---- patches.md, the filtering block, verbatim -----------------------------------------------------
  hafen.vr():patch():list()                                  -- all of them
  hafen.vr():patch():find(function(one) return one:drawn() end)  -- the first one on drawn ground

  check((#patches:list() == patches:count()) and (patches:find(function() return false end) == nil),
        "the filtering block reaches them, and :find takes a predicate that may match nothing",
        #patches:list() .. " of " .. patches:count())

  -- ---- patches.md, the clickability block, verbatim --------------------------------------------------
  local patch = hafen.vr():patch():add(ring, here)
    :clickable(true)
    :onClick(function(patch, button, x, y)  -- 1 = left, 3 = right; x, y = the clicked world point
      hafen.log():write("clicked my patch with button " .. button)
    end)
  hafen.event():on("PatchClicked", function(ev)
    hafen.vr():patch():remove(ev:patch())   -- ev:patch() ev:button() ev:x() ev:y()
  end)

  local sub = hafen.event():on("PatchClicked", function() end)
  check((patch ~= nil) and (patch:clickable() == true) and (type(patch:onClick()) == "function")
        and (sub ~= nil) and (sub:off() == sub),
        "the clickability block lays a clickable patch with its callback, and PatchClicked hands back a Sub",
        (patch == nil) and "nil" or (tostring(patch:clickable()) .. ", " .. type(patch:onClick())))

  -- ---- patches.md, the footprint block, and the page's first claim -----------------------------------
  -- The character's own hitbox is the server's to hand over, not this suite's to cause, and so is the
  -- ground resolving under a patch. Both are asked on a bounded window and the run is scored over what it
  -- reached. The `z` refusal rides here because the verb it names exists only on one that FOLLOWS a gob,
  -- which is exactly what this block lays.
  local tries, tm, before = 0, nil, patches:count()
  tm = hafen.timer():every(0.25, function()
    tries = tries + 1
    local rings = me0:hitbox()
    if ((rings ~= nil) and (#rings > 0)) or (tries >= 16) then
      tm:cancel()

      local me = hafen.session():current():player():gob()
      for _, ring in ipairs(me:hitbox() or {}) do            -- nil until the object's resource resolves
        hafen.vr():patch():add(ring, me):tint{255, 80, 80}   -- lit up on that object's own footprint
      end

      local laid = patches:count() - before
      check((rings ~= nil) and (laid == #rings),
            "the footprint block lays one patch per ring of the character's own hitbox",
            (rings == nil) and ("gob:hitbox() still nil after " .. tries .. " tries")
              or (laid .. " of " .. #rings))
      local followed = patches:add(ring, me)                 -- one that FOLLOWS, whatever the hitbox did
      refuses("a z on :offset is refused naming that a patch has no height",
              function() return followed:offset(0, 0, 1) end, "has no height")
      check(first:drawn(), "the patch the opening block laid is on the terrain being drawn",
            "not drawn after " .. tries .. " tries")
      manualCheck("zoom all the way in and back out over the green square under your character",
                  "the square lying flush on the ground at every zoom -- its edge the ring's own straight"
                  .. " sides and sharp corners, never a staircase of square tiles, with no gap, no shimmer"
                  .. " and no ground breaking through, and your character occluding the part it stands on")
      summary()
    end
  end)
end

hafen.console():on("t118", run)   -- the only way in: a suite does not start itself
