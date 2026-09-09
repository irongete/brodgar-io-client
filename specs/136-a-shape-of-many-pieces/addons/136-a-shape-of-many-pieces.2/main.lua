-- 136.2 — a piece is taken up, and the snapshot says pieces. Self-checking suite.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- A convex rectangle of Positions round `at`, in world units (a tile is 11 of them).
local function quad(at, x1, y1, x2, y2)
  return { at:offset(x1, y1), at:offset(x2, y1), at:offset(x2, y2), at:offset(x1, y2) }
end

local function run()
  local ok, at = pcall(function()
    return hafen.session():current():player():gob():position():offset(0, -14)
  end)
  if not ok or (at == nil) then
    hafen.log():write("[fail] the suite needs a character standing in the world -- got: " .. tostring(at))
    return
  end

  -- Three quads in a row, each overlapping its neighbour, laid as ONE patch.
  local main = hafen.virtual():patch():add(quad(at, -15, -4, -3, 4), at)
    :tint({40, 200, 120, 140}):border({255, 255, 255}, 0.3)
  local mid = main:piece():add(quad(at, -6, -4, 6, 4))
  local right = main:piece():add(quad(at, 3, -4, 15, 4))
  local left = main:piece():list()[1]
  eq("the shape holds the three pieces laid into it", main:piece():count(), 3)

  main:piece():remove(mid)
  eq("the middle piece is taken up: the count fell", main:piece():count(), 2)
  check((not mid:exists()) and left:exists() and right:exists(),
        "the piece taken up is gone, and the two it lay between are not",
        "mid=" .. tostring(mid:exists()) .. " left=" .. tostring(left:exists())
        .. " right=" .. tostring(right:exists()))
  local held = main:piece():list()
  check((#held == 2) and (held[1] == left) and (held[2] == right),
        "what is left is those two, in the order they were laid",
        "count=" .. #held .. " first==left " .. tostring(held[1] == left)
        .. " second==right " .. tostring(held[2] == right))

  local i = main:info()
  local ps = i.pieces
  check(ps and (#ps == 2) and (#ps[1] == 4) and (#ps[2] == 4)
        and (type(ps[1][1].gridId) == "string"),
        "info().pieces is the two rings, four durable points each",
        (ps == nil) and "nil" or (#ps .. " ring(s)"))
  eq("info() carries no ring beside them", i.ring, nil)

  local other = hafen.virtual():patch():add(quad(at, 20, -3, 26, 3), at):tint({255, 120, 40, 140})
  refuses("a piece of another patch is refused by name",
          function() main:piece():remove(other:piece():list()[1]) end, "belongs to another patch")
  refuses("a piece already taken up is refused by name",
          function() main:piece():remove(mid) end, "already been taken up")
  refuses("remove() with nothing raises naming the argument",
          function() main:piece():remove() end, "keyOrMember")

  local wasDrawn = other:drawn()
  other:piece():remove(other:piece():list()[1])
  check(other:exists(), "the patch whose last piece was taken up still exists", other:exists())
  check(wasDrawn and (other:drawn() == false),
        "...it was drawn while it held that piece, and draws nothing now",
        "before=" .. tostring(wasDrawn) .. " after=" .. tostring(other:drawn()))
  local op = other:info().pieces
  check((op ~= nil) and (#op == 0), "...and its info() names no pieces at all",
        (op == nil) and "nil" or #op)
  hafen.virtual():patch():remove(other)

  manualCheck("look at the shape on the ground beside your character",
              "two green quads with a clear gap where a third was, each outlined in white")
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.console():on("t136-2", run)   -- the only way in: a suite does not start itself
