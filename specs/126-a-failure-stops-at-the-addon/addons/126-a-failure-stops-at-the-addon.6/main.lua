-- 126.6 -- an accessor's byte range is checked against its buffer. Suite: run with :t126.
--
-- Three model files ship beside this file. Two of them declare an accessor whose stride walk leaves the
-- bytes it was given; the third is the control that says the new bound did not cost a legal document:
--
--   pos-overruns-buffer.glb    a POSITION accessor of three VEC3 floats -- 36 bytes -- over a 12-byte buffer
--   index-overruns-buffer.glb  a legal POSITION, and an index accessor claiming twelve ushorts over the
--                              six-byte view that ends the buffer, so its walk leaves view and buffer together
--   good.glb                   the same three vertices, indexed legally
--
-- Both malformed files failed before this task too, so merely failing proves nothing: they failed by
-- reading past the byte array, which is an anonymous Java array-index fault naming neither the model nor
-- what is wrong with it. So both halves of every message are asserted -- the phrase for the overrun, and
-- the model's own name -- and the index file is asserted to name the accessor that overran, since a
-- message that named only the model would not say which of its two accessors is the bad one.
--
-- good.glb must LOAD, with its vertex and triangle counts read back. The new bound sits on the path every
-- accessor takes, so a check set one byte too tight would refuse every model in the client, and this is
-- the assertion that catches that.
--
-- That the summary below is reached at all is the last assertion: each refusal is a Lua error this addon
-- caught, not a fault that took the addon down on its way out.

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

local function strip(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- A refusal is a check: the call must fail, and fail saying both what is wrong and which model.
local function refuses(file, what, wantMsg)
  local ok, err = pcall(function() return hafen.asset():get(file) end)
  local msg = ok and "<no error>" or strip(err)
  check((not ok) and (msg:find(wantMsg, 1, true) ~= nil), what, msg)
  check((not ok) and (msg:find(file, 1, true) ~= nil), "...and it names " .. file, msg)
  return msg
end

local function run()
  refuses("pos-overruns-buffer.glb",
          "a POSITION accessor whose range leaves its buffer is refused", "runs past its buffer")

  local msg = refuses("index-overruns-buffer.glb",
                      "an index accessor whose range leaves its buffer is refused", "runs past its buffer")
  check(msg:find("index accessor 1", 1, true) ~= nil,
        "...and it names the accessor that overran, not just the model", msg)

  -- The control: the bound is on the path every accessor takes, so an ordinary model must still load.
  local ok, mdl = pcall(function() return hafen.asset():get("good.glb") end)
  check(ok, "a legal indexed model still loads", ok and "<loaded>" or strip(mdl))
  local info = ok and mdl:info() or {}
  check((info.verts == 3) and (info.tris == 1),
        "...with all of its geometry (3 vertices, 1 triangle)",
        tostring(info.verts) .. " vertices, " .. tostring(info.tris) .. " triangles")
  if ok then
    pcall(function() return hafen.asset():remove(mdl) end)   -- leave nothing interned behind
  end

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("t126", run)   -- the only way in: a suite does not start itself
