-- 136.3 — a click lands on whichever piece is under it. Self-checking suite.

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

local WINDOW = 60          -- seconds the three presses are scored over: one press serves both halves
local TOL = 2              -- world units of slack round a quad, for the click's own plane arithmetic

-- Two quads in a line away from the character, with ten clear world units of ground between them.
local NEAR = {-9, -18, 9, -6}
local FAR = {-9, -40, 9, -28}

-- A convex rectangle of Positions round `at`, in world units (a tile is 11 of them).
local function quad(at, r)
  return { at:offset(r[1], r[2]), at:offset(r[3], r[2]), at:offset(r[3], r[4]), at:offset(r[1], r[4]) }
end

-- Is the world point (x, y) in this quad, measured from the anchor's own world numbers?
local function within(x, y, ax, ay, r)
  return (x >= (ax + r[1]) - TOL) and (x <= (ax + r[3]) + TOL)
     and (y >= (ay + r[2]) - TOL) and (y <= (ay + r[4]) + TOL)
end

local function run()
  local ok, at = pcall(function()
    return hafen.session():current():player():gob():position()
  end)
  if not ok or (at == nil) or (at:x() == nil) then
    hafen.log():write("[fail] the suite needs a character standing in the world -- got: " .. tostring(at))
    return
  end
  local ax, ay = at:x(), at:y()

  local patch = hafen.virtual():patch():add(quad(at, NEAR), at)
    :tint({40, 200, 120, 140}):border({255, 255, 255}, 0.3):clickable(true)
  patch:piece():add(quad(at, FAR))
  eq("the patch takes the pointer", patch:clickable(), true)

  local none = hafen.event():count("PatchClicked")
  local probe = hafen.event():on("PatchClicked", function() end)
  local armed = hafen.event():count("PatchClicked")
  probe:off()
  local off = hafen.event():count("PatchClicked")
  check((probe:key() == "PatchClicked") and (armed == none + 1) and (off == none),
        "a PatchClicked subscription arms, and sub:off() ends it",
        "key=" .. tostring(probe:key()) .. " count " .. none .. "->" .. armed .. "->" .. off)

  local seenNear, seenFar, outside = false, false, 0
  local sub = hafen.event():on("PatchClicked", function(ev)
    local x, y = ev:x(), ev:y()
    if within(x, y, ax, ay, NEAR) then
      if not seenNear then
        seenNear = true
        check(true, "the press inside the near quad was answered, and by the near quad")
      end
    elseif within(x, y, ax, ay, FAR) then
      if not seenFar then
        seenFar = true
        check(true, "the press inside the far quad was answered, and by the far quad")
      end
    else
      outside = outside + 1
    end
  end)

  manualCheck("left-click inside the quad beside your character, within " .. WINDOW .. "s",
              "one [pass] line naming the near quad")
  manualCheck("left-click inside the second quad, a tile of clear ground beyond the first, within "
              .. WINDOW .. "s", "one [pass] line naming the far quad")
  manualCheck("left-click the clear ground between the two quads, within " .. WINDOW .. "s",
              "no line at all, and your character walks there")

  hafen.timer():after(WINDOW, function()
    sub:off()
    if not seenNear then
      check(false, "the press inside the near quad was answered, and by the near quad", "no press arrived")
    end
    if not seenFar then
      check(false, "the press inside the far quad was answered, and by the far quad", "no press arrived")
    end
    check(outside == 0, "no press outside the two quads reached the patch", outside .. " did")
    hafen.virtual():patch():remove(patch)
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
end

hafen.console():on("t136-3", run)   -- the only way in: a suite does not start itself
