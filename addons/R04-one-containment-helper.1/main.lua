-- R04 -- one containment helper.
--
-- One mechanism: every name that comes from data -- an asset path, a .gltf's external buffer or texture
-- URI, a manifest `files` entry, the character folder the server names -- is resolved to the file it REALLY
-- means inside the folder that owns it, and refused when that file is not inside. The check is made on the
-- real path rather than on the spelling, so a link inside a folder pointing out of it is refused like any
-- other path outside; a name that does not exist yet still resolves, so a missing file is refused by the
-- loader in the loader's own words.
--
-- What this asserts is that one door seen from the two sides Lua can reach: hafen.asset()'s own resolve,
-- and the .gltf loader that used to carry a second, hand-rolled copy of the check with its own message and
-- its own hole. The two sides that no addon can reach -- a manifest `files` entry, a character folder off
-- the wire, and the link that walked through both asset checks -- are the two [manual] lines: the first
-- runs the block's headless proof, which probes every one of them against the compiled classes.
--
-- Run it with :tR04. It starts nothing by itself, and every call but one is a REFUSAL, so nothing it
-- touches is left changed -- which the last check reads back rather than assumes.

local out, pass, fail, manual = {}, 0, 0, 0

local function line(s) out[#out + 1] = s end

local function ok(desc, cond, got)
  if cond then
    pass = pass + 1
    line("[pass] " .. desc)
  else
    fail = fail + 1
    line("[fail] " .. desc .. " -- got: " .. tostring(got))
  end
end

local function todo(action, expect)
  manual = manual + 1
  line("[manual] " .. action .. " -- expect: " .. expect)
end

-- LuaJ prefixes a bridge refusal with "@chunk.lua:<n>" and a SPACE, and a Lua error with "chunk.lua:<n>:".
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

-- Every call in `fns` must raise, and every message must carry `needle`. Answers true, or false and the
-- first thing that went wrong -- so a check that has something to READ BACK as well is still one line.
local function refused(needle, fns)
  for i = 1, #fns do
    local fine, err = pcall(fns[i])
    if fine then return false, "call " .. i .. " raised nothing" end
    local msg = why(err)
    if not msg:find(needle, 1, true) then return false, "call " .. i .. " said " .. msg end
  end
  return true
end

local function report()
  for _, l in ipairs(out) do hafen.log():write(l) end
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- ---------------------------------------------------------------- the checks

local function run()
  out, pass, fail, manual = {}, 0, 0, 0
  local a = hafen.asset()
  local r, got

  -- 1. The one name that loads, and the interning that proves the RESOLVED path is the key: an internal
  --    "a/../b" is normalised and still lands inside the folder, so both spellings are one handle.
  local plain = a:get("data/ok.txt")
  local round = a:get("data/../data/ok.txt")
  ok("an internal a/../b resolves inside the folder, and interns to the same handle",
     (plain == round) and (plain:type() == "data") and (plain:text():find("R04-INSIDE-THE-FOLDER", 1, true) ~= nil),
     tostring(plain:type()) .. " / same=" .. tostring(plain == round))
  local held = #a:list()

  -- 2. An absolute name is refused before the disk is touched at all, and the message says which shape.
  r, got = refused("is absolute", {function() return a:get("/etc/passwd") end})
  ok("an absolute path is refused, and the refusal says it is absolute", r, got)

  -- 3. The other two rooted spellings a Windows path has. Neither is a file this addon can name, whatever
  --    the host filesystem makes of the string, so the assertion is that neither one ever loads.
  r, got = refused("hafen.asset", {function() return a:get("C:/Windows/win.ini") end,
                                   function() return a:get("\\\\srv\\share\\icon.png") end})
  ok("a drive-lettered and a UNC spelling never load a file", r, got)

  -- 4. The climb out, in the two shapes an addon writes it.
  r, got = refused("is not inside", {function() return a:get("../outside.txt") end,
                                     function() return a:get("data/../../outside.txt") end})
  ok("a path that climbs out of the addon folder is not inside it", r, got)

  -- 5. A name that IS inside and simply is not there: the containment check hands it on, and the loader
  --    refuses it in the loader's own words. That split is what lets a missing file keep its own message.
  r, got = refused("no such file", {function() return a:get("nope.png") end})
  ok("a legal name that is not there is refused by the loader, not by the containment check", r, got)

  -- 6. The empty name, which is neither inside nor outside.
  r, got = refused("non-empty", {function() return a:get("") end})
  ok("an empty path is refused", r, got)

  -- 7. The second site: a .gltf's external buffer URI resolves beside the model file and then goes through
  --    the SAME door, so it is refused in the same words rather than in a copy of them.
  r, got = refused("is not inside", {function() return a:get("escape.gltf") end})
  ok("a .gltf external URI goes through the same door, in the same words", r, got)

  -- 8. And none of those refusals left anything behind: a refused load interns nothing and frees nothing.
  ok("a refused load interns nothing: the collection still holds what it held",
     (#a:list() == held) and (a:get("data/ok.txt") == plain), #a:list() .. " vs " .. held)

  todo('run: jshell --class-path "build/classes;lib/brodgar/luaj-jse-3.0.1.jar" audit2/refactor/B04.jsh',
       "every probe line reads refused, and the control line reads resolved")
  todo('copy any addon folder, set its manifest "files" to ["../x.lua"], then :reload',
       'its AddOns row reads error: and names ../x.lua')
  report()
end

hafen.console():on("tR04", run)
