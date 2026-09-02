-- 126.4 -- a model's accessors are bounded before anything is allocated. Suite: run with :t126.
--
-- Three model files ship beside this file, each one line of malformed glTF, each hitting a bound the
-- parser read off the document and used before it read the data:
--
--   count-past-cap.glb    an accessor declaring 5,000,000 vertices over a 36-byte view
--   no-buffer.glb         a bufferView with no `buffer` property at all
--   index-past-verts.glb  three vertices, indexed [0, 1, 7]
--
-- Each must fail, and fail SAYING what is wrong and in which model. Merely failing proves nothing: all
-- three failed before this task too -- by reading past an array, or by subscripting the -1 an absent
-- property reads as, which is an anonymous Java fault naming neither the file nor the fault. So both
-- halves of every message are asserted: the phrase for the fault, and the model's own name.
--
-- good.glb is the control. It is the same three vertices, indexed legally, and it must LOAD -- which is
-- the check that the three bounds refuse a hostile document without refusing an ordinary one. Its
-- vertex and triangle counts are read back, so a bound that quietly dropped geometry fails here.
--
-- That the summary below is reached at all is the fourth assertion: every refusal is a Lua error this
-- addon caught, not a fault that took the addon down on its way out.

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
end

local function run()
  refuses("count-past-cap.glb",
          "an accessor count past the vertex cap is refused before the allocation", "past the vertex cap")
  refuses("no-buffer.glb",
          "a bufferView with no buffer is refused instead of being indexed with -1", "bufferView has no buffer")
  refuses("index-past-verts.glb",
          "an index outside the vertex count is refused", "outside the primitive's 3 vertices")

  -- The control: the bounds must refuse the hostile document and pass the ordinary one.
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
