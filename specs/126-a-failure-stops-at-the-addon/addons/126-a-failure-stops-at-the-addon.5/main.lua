-- 126.5 -- a node reached twice is refused as the invalid document it is. Suite: run with :t126.
--
-- Three model files ship beside this file. The first is the abusive one; the other two are the controls
-- that say the bound did not cost a legal document:
--
--   diamond.glb     node 0 -> {1, 2}, and both 1 and 2 -> 3. Node 3 has two parents.
--   deep-chain.glb  500 mesh-less nodes, each the single parent of the next, the last carrying the mesh.
--   no-scene.glb    a parent and its child, with no `scenes` array at all -- the loader's fallback branch.
--
-- diamond.glb must FAIL, and fail naming the node that is reached twice: before this task the walk simply
-- re-entered it, so a DAG was re-walked once per path and a wide one expanded far past its node count. It
-- is refused rather than skipped because a node under two parents is the same mesh with a different baked
-- transform -- skipping the second visit would drop that geometry with no error at all.
--
-- deep-chain.glb must LOAD. That is the assertion that the walk was bounded by glTF's own one-parent
-- invariant and not by a depth budget: a legal chain far deeper than any hand-made model still parses, and
-- its one triangle arrives. no-scene.glb must load too, and with exactly one triangle: a document with no
-- scene has every unparented node for a root, and taking *every* node for a root would enter the child a
-- second time and turn this ordinary model into a refusal.
--
-- That the summary below is reached at all is the last assertion: the refusal is a Lua error this addon
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

-- The control: a model that must load, with all of its geometry.
local function loads(file, what)
  local ok, mdl = pcall(function() return hafen.asset():get(file) end)
  check(ok, what, ok and "<loaded>" or strip(mdl))
  local info = ok and mdl:info() or {}
  check((info.verts == 3) and (info.tris == 1),
        "...with all of its geometry (3 vertices, 1 triangle)",
        tostring(info.verts) .. " vertices, " .. tostring(info.tris) .. " triangles")
  if ok then
    pcall(function() return hafen.asset():remove(mdl) end)   -- leave nothing interned behind
  end
end

local function run()
  -- A refusal is a check: it must fail, and fail naming the node -- not merely fail, and not hang.
  local ok, err = pcall(function() return hafen.asset():get("diamond.glb") end)
  local msg = ok and "<no error>" or strip(err)
  check((not ok) and (msg:find("reached twice", 1, true) ~= nil),
        "a node graph that reaches a node twice is refused", msg)
  check((not ok) and (msg:find("node 3", 1, true) ~= nil),
        "...naming the node that has two parents (node 3)", msg)
  check((not ok) and (msg:find("diamond.glb", 1, true) ~= nil),
        "...and naming the model", msg)

  loads("deep-chain.glb", "a legal 500-node single-parent chain still loads")
  loads("no-scene.glb", "a parented document with no scene still loads")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("t126", run)   -- the only way in: a suite does not start itself
