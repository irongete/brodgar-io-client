-- 148.1 — s:world():id(): which world that character is in. Self-checking suite.

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
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function run()
  local s = hafen.session():current()
  check(s ~= nil and s:character() ~= nil, "a session is on screen and in the world", s and s:character())
  local w = s and s:world()
  local id = w and w:id()
  check(type(id) == "string" and #id > 0, "s:world():id() is a non-empty string (" .. tostring(id) .. ")", id)
  check(w and w:id() == id, "a second read answers the same id", w and w:id())
  check(s and s:world() == w, "s:world() is the same object every call")
  check(id ~= s:character(), "the world id is not the character name", id)

  local ghost = hafen.session():get("nobody-148")
  check(ghost:exists() == false and ghost:world():id() == nil,
        "a session the client does not hold answers nil", ghost:world():id())

  refuses("a dot call is refused naming session:world():id",
          function() return w.id() end, "session:world():id()")

  manual = manual + 1
  hafen.log():write("[manual] compare " .. tostring(id) .. " with the folder names under bin/savedata/"
                    .. " -- expect: this character's folder is <that id>_" .. tostring(s and s:character()))

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  pass, fail, manual = 0, 0, 0
end

hafen.console():on("t148", run)   -- the only way in: a suite does not start itself
