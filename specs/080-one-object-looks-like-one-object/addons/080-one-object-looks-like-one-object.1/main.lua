-- 080.1 — a gob is one object, and it looks like one. Self-checking suite.
--
-- The claim: a visual write on a gob applies to the OBJECT. A gob id is the server's and names one thing,
-- but each character holds its own copy of it -- so gob:scale(k) and gob:overlay():add(key) land on every
-- live session that holds the object, and the undo undoes the object. One thing is drawn one way, whichever
-- character is looking at it.
--   THE UNDO CANNOT BE ASSERTED BY THE ADDON THAT PERFORMED IT: teardown runs because this addon is gone,
-- so there is nothing left here to read the result with. It is two-phase, and the suite deliberately LEAVES
-- a scale and an overlay standing at the end for phase two to look at.

local pass, fail, manual = 0, 0, 0

local function log(s) hafen.log():write(s) end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    log("[pass] " .. what)
  else
    fail = fail + 1
    log("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- A refusal is a check: the call must fail, and fail SAYING why -- every `want` in the message.
local function refuses(what, fn, ...)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or tostring(err):gsub("^.-%.lua:%d+:%s*", "")
  local said = not ok
  for _, want in ipairs({...}) do
    said = said and (err:find(want, 1, true) ~= nil)
  end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  log("[manual] " .. step .. " -- expect: " .. expect)
end

local KEY = "080-1-label"

-- ---------------------------------------------------------------------------------------------------

local function run()
  pass, fail, manual = 0, 0, 0
  local s = hafen.session():current()
  if (s == nil) or (s:player():gob() == nil) then
    log("[fail] a character must be in world to run this -- got: no drawn character with a gob")
    log("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The subject: this character's own body. It is always loaded, it always has a position, and a second
  -- character standing beside it holds the very same object -- which is the whole point of the walk.
  local gob = s:player():gob()
  local id = gob:id()

  -- THE WRITE, read back. It hands the Gob back, so it chains.
  local chained = gob:scale(2.5)
  check((chained == gob) and (gob:scale() == 2.5),
        "gob:scale(k) writes, reads back, and hands the Gob back so it chains",
        tostring(chained) .. " / " .. tostring(gob:scale()))

  -- ...and 1 is the original size, which is the absence of the state rather than a value meaning nothing.
  gob:scale(1)
  check(gob:scale() == 1, "gob:scale(1) puts it back to the size the game draws it at", gob:scale())

  -- THE RANGE. 0 collapses the object to a point and a negative one turns it inside out, so both refuse
  -- naming the rule -- at the call site that wrote them, rather than somewhere in a later frame.
  refuses("gob:scale(0) refuses naming the range", function() return gob:scale(0) end,
          "greater than 0", "gob:scale(1) is the original size")
  refuses("gob:scale(-2) refuses naming the range", function() return gob:scale(-2) end,
          "greater than 0", "inside out")

  -- THE OTHER WRITE. Attached bare, then told what it draws; found by its key, and listed as one of ours.
  local ov = gob:overlay():add(KEY)
  ov:text("080.1"):color(255, 220, 120):offset(0, -20)
  local back = gob:overlay():get(KEY)
  local info = back and back:info()
  local listed = false
  for _, o in ipairs(gob:overlay():list()) do
    if (o == back) and (not o:native()) then listed = true end
  end
  check((back == ov) and listed and (info ~= nil) and (info.kind == "text") and (info.native == false)
        and (back:text() == "080.1"),
        "gob:overlay():add(key) attaches it, :get(key) is the same handle, and :list() carries it as ours",
        tostring(back) .. " kind=" .. tostring(info and info.kind) .. " listed=" .. tostring(listed))

  -- ...and the removal is the twin of that walk: off every copy, and reading it back is nil everywhere.
  gob:overlay():remove(KEY)
  check((gob:overlay():get(KEY) == nil) and (not ov:exists()),
        "gob:overlay():remove(key) ends it: :get reads nil and the handle stops existing",
        tostring(gob:overlay():get(KEY)) .. " exists=" .. tostring(ov:exists()))

  -- WHO WAS WALKED. gob:sessions() is the live read the write takes its walk from, so it is the list of
  -- characters this object was just written in -- and the session that ran the suite is one of them.
  local who, mine = gob:sessions(), false
  local names = {}
  for _, m in ipairs(who) do
    names[#names + 1] = m:user()
    if m:user() == s:user() then mine = true end
  end
  check((#who > 0) and mine,
        "gob:sessions() names the characters the write walked -- " .. #who .. ": " .. table.concat(names, ", "),
        tostring(#who) .. " sessions, mine=" .. tostring(mine))

  -- AN OBJECT NOBODY HOLDS. The walk is empty, and an empty walk IS "the write does nothing with it":
  -- no session to skip, no error, and the read is nil rather than a size.
  local ghost = s:world():gob():get(987654321987)
  local gok, gerr = pcall(function() return ghost:scale(3) end)
  check(gok and (ghost:scale() == nil) and (#ghost:sessions() == 0),
        "an id no character holds: the write is inert and throws nothing, and the read is nil",
        (not gok) and tostring(gerr) or tostring(ghost:scale()))

  -- LEFT STANDING, on purpose: this is what phase two looks at once this addon is gone.
  gob:scale(2.5)
  gob:overlay():add(KEY):text("080.1"):offset(0, -20)
  check((gob:scale() == 2.5) and (gob:overlay():get(KEY) ~= nil),
        "left standing for the undo: the body is scaled 2.5 and carries the overlay",
        tostring(gob:scale()) .. " / " .. tostring(gob:overlay():get(KEY)))

  manualCheck("PHASE ONE -- with two characters standing TOGETHER, look at the line above: gob " .. id
              .. " (" .. tostring(gob:name()) .. "), sessions " .. table.concat(names, ", "),
              ":sessions() names BOTH accounts, and the character on screen is visibly 2.5x with a"
              .. " \"080.1\" label over it")
  manualCheck("PHASE TWO -- :reload, then tab to the OTHER character and look at that same body",
              "it is back to its normal size with no label on it. The undo reached the session that was"
              .. " not on screen when the addon went away")
  manualCheck("paste the output of the guardrail grep from the task",
              "212")

  log(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t080-1", run)   -- the only way in: a suite does not start itself
