-- 066.1 — the RTS camera, and the keys it gives up. Self-checking suite.

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
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local FOCUS  = "rts-focus"
local NEXT   = "rts-next-anchor"
local NOSUCH = "no-such-binding-066-1"

-- A key the client has bound to nothing, so the write below takes one off nobody: the registry's
-- one-key-one-action rule unbinds whatever else matched, and a suite may not cost the maintainer a
-- binding they set by hand.
local function spare(all)
  local taken = {}
  for _, k in pairs(all) do taken[k] = true end
  for _, k in ipairs({ "F9", "F10", "F11", "F12" }) do
    if not taken[k] then return k end
  end
  return nil
end

-- Which other id holds this one's key, if any. The restore at the end of a round trip is a write like
-- any other, so it steals the key back from whoever else is on it -- and putting THAT one back would
-- only steal it again. An id sharing its key is one this suite leaves alone.
local function sharer(all, id)
  local k = all[id]
  if (k == nil) or (k == "None") then return nil end
  for other, ok in pairs(all) do
    if (other ~= id) and (ok == k) then return other end
  end
  return nil
end

-- Write a key, read it back, and put back exactly what was there. nil reads as unbound, and "None" is
-- how a write says that.
local function roundtrip(keys, id, key)
  local orig = keys:key(id)
  keys:key(id, key)
  eq(id .. " reads back the key just written", keys:key(id), key)
  keys:key(id, orig or "None")
  eq(id .. " is back to what it was", keys:key(id), orig)
end

local function run()
  local keys = hafen.client():options():keybindings()
  local all  = keys:list()

  -- The point of the task: the dispatch moved off the camera and onto the RTS layer, and the ids did
  -- not move with it, so a key the user assigned is still the same binding.
  check(all[FOCUS] ~= nil, "rts-focus is still an id in list()", "absent")
  check(all[NEXT] ~= nil, "rts-next-anchor is still an id in list()", "absent")

  local key, id = spare(all), nil
  for _, cand in ipairs({ FOCUS, NEXT }) do
    if (id == nil) and (all[cand] ~= nil) and (sharer(all, cand) == nil) then id = cand end
  end
  local writable = (key ~= nil) and (id ~= nil)
  check(writable,
        "a key can be written to one of the two without taking it off another binding"
        .. (writable and (" -- " .. id) or ""),
        (key == nil) and "F9..F12 are all bound"
          or ("both share their key: " .. tostring(sharer(all, FOCUS))
              .. ", " .. tostring(sharer(all, NEXT))))
  if writable then
    roundtrip(keys, id, key)
  end

  -- A read of a name that is no binding is a real answer, not a fault.
  local ok, got = pcall(function() return keys:key(NOSUCH) end)
  check(ok and (got == nil), "a read of an unknown id is plain nil",
        ok and tostring(got) or ("raised: " .. tostring(got)))

  -- A write to one is not: there is nothing to remap, and the refusal says which name.
  refuses("a write to an unknown id raises, naming it", function() keys:key(NOSUCH, "F9") end, NOSUCH)

  manualCheck("on a lone character with no fleet and the mode off, type :cam rts, then middle-drag,"
              .. " turn the wheel, and press Home. Then :cam ortho to put your own camera back",
              "the view pans with the drag and does not rotate; the wheel zooms further the further out"
              .. " you already are; Home follows your character again. :cam writes the defcam"
              .. " preference, which is why the last step is there")
  manualCheck("type :cam fleet",
              "a refusal naming rts: no camera named 'fleet' -- it is 'rts': :cam rts")
  manualCheck("note the camera you are on, then :fleet rts on, then :fleet rts off",
              "on swaps the panning camera in; off puts back exactly the camera you noted, and a"
              .. " restart comes up on that one -- the mode writes no preference")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t066-1", run)   -- the only way in: a suite does not start itself
