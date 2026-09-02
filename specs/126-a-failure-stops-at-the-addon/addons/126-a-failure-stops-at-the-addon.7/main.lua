-- 126.7 -- the node walk is bounded by the parser, not by the Java stack. Suite: run with :t126.
--
-- Three model files ship beside this file:
--
--   deep-chain.glb  20,000 mesh-less nodes, each the single parent of the next, then one triangle.
--   cycle.glb       node 0 -> 1 -> 2 -> 0, with the mesh on node 1.
--   diamond.glb     node 0 -> {1, 2}, and both 1 and 2 -> 3. Node 3 has two parents.
--
-- deep-chain.glb must LOAD, and arrive with its triangle. It is the assertion that fails before this task:
-- the walk spent one Java frame per node, so a *legal* single-parent chain -- which glTF lets run as deep
-- as it likes -- ended the load with a StackOverflowError somewhere between 2,000 and 3,000 nodes. That
-- ceiling was the thread's, not the parser's, and it cost this addon a quarantine rather than the failed
-- load and nothing else that the models page promises.
--
-- cycle.glb must be REFUSED, naming the node it comes back to. The old recursion carried a separate
-- cycle flag and returned quietly when it tripped, so a cycle was cut in silence and the model loaded
-- truncated -- node 1's triangle arrived and nothing said the document was invalid. A cycle reaches a
-- node twice by definition, so the one-parent rule is the whole answer and the flag is gone with the
-- recursion.
--
-- diamond.glb must be REFUSED too. That rule moved house into the iterative walk, and this is the check
-- that it survived the move rather than the check of a new claim.
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

-- A refusal is a check: the load must fail, and fail naming the node and the model -- not merely fail,
-- since a StackOverflowError fails too and is the thing this task removes.
local function refused(file, node)
  local ok, err = pcall(function() return hafen.asset():get(file) end)
  local msg = ok and "<no error>" or strip(err)
  check((not ok) and (msg:find("reached twice", 1, true) ~= nil),
        file .. " is refused as a document that reaches a node twice", msg)
  check((not ok) and (msg:find(node, 1, true) ~= nil),
        "...naming the node it comes back to (" .. node .. ")", msg)
  check((not ok) and (msg:find(file, 1, true) ~= nil),
        "...and naming the model", msg)
end

local function run()
  local ok, mdl = pcall(function() return hafen.asset():get("deep-chain.glb") end)
  check(ok, "a legal 20,000-node single-parent chain loads", ok and "<loaded>" or strip(mdl))
  local info = ok and mdl:info() or {}
  check((info.verts == 3) and (info.tris == 1),
        "...with its geometry (3 vertices, 1 triangle)",
        tostring(info.verts) .. " vertices, " .. tostring(info.tris) .. " triangles")
  if ok then
    pcall(function() return hafen.asset():remove(mdl) end)   -- leave nothing interned behind
  end

  refused("cycle.glb", "node 0")
  refused("diamond.glb", "node 3")

  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, 0 manual")
end

hafen.console():on("t126", run)   -- the only way in: a suite does not start itself
