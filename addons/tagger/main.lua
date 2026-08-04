-- Tagger: the EXAMPLE for gob:overlay -- the one way to attach anything to a game object, and the one way to
-- read what is already attached (spec 038-gob-overlays).
--
-- ARITY IS THE VERB, and the key is your own name for the thing you hung there:
--   gob:overlay()            -- every overlay on that gob: yours, then the GAME's own
--   gob:overlay(key)         -- that one, or nil
--   gob:overlay(key, spec)   -- attach it, or REPLACE whatever that key already named
--   gob:overlay(key, nil)    -- remove it
--
-- ONE SPEC TABLE, TWO SPACES. {text=} and {draw=} paint on the SCREEN at the gob's projected point;
-- {image=}, {model=} and {ghost=} stand in the 3D WORLD, anchored to the gob, with offset in world units.
-- You never place either one and you never poll: the record lives ON the gob, so it follows the gob for free
-- and it DIES WITH IT -- a felled tree takes your label with it and a gob that comes back is bare.
--
-- THERE IS NO FILTER FORM, and this addon is what that trade looks like: "label every player" is a GobAdded
-- handler plus one loop over the players already here (see tagAll/onAdded below). You name the gob, so nothing
-- is searched per frame -- which is the whole reason the old sweep is gone.
--
-- COMMANDS (:tagger <sub>):
--   (bare)   -- labels ON/OFF: a green name over every player body, kept up to date from GobAdded
--   pin      -- hang icon.png in the WORLD over the nearest object, and chain the composed verbs on it
--   unpin    -- take it off again
--   read     -- print every overlay on the nearest object: yours AND the game's own (native, keyed by resource)
--   watch    -- toggle the two events, GobOverlayAdded / GobOverlayRemoved
--
-- SAFE tier: it declares NO permissions, sends nothing to the server and writes nothing persistent. Everything
-- it attaches is removed on ':reload' or on disable, and the game's own overlays are never touched -- they are
-- READ-ONLY, and both an attach onto a native key and a remove of one raise, naming the key.

local LABEL = "name"                  -- our key for a player's label       (screen space)
local RING  = "ring"                  -- our key for the ring under it      (screen space, a draw callback)
local PIN   = "pin"                   -- our key for the world pin          (world space)

local labelling = false
local watching  = false
local tagged    = {}                  -- gob id -> true, the players we have labelled
local pinned                          -- the gob id the pin is on, if any
local icon

hafen.event():on("OnLoad", function() icon = hafen.asset("icon.png") end)

-- ---- the labels ---------------------------------------------------------------------------------------

-- One player, two overlays under two keys of our own. A key is per addon, so "name" here can never collide
-- with another addon's "name" -- and gob:overlay() below will list ours and the game's, never a third party's.
local function tag(g)
  if not (g and g:exists() and g:isPlayer()) then return end
  local kin = g:kin()
  g:overlay(LABEL, { text = kin and kin:name() or "player",
                     color = kin and {120, 220, 120} or {220, 220, 120},
                     offset = { x = 0, y = -4 } })
  -- A draw callback gets the SAME projected point as sx, sy and paints whatever it likes there. It runs inside
  -- the client's draw pass, which is outside the per-tick CPU budget -- so keep it to a few shapes.
  g:overlay(RING, { draw = function(gout, gob, sx, sy)
                      gout:color(120, 220, 120, 160)
                      gout:rect(sx - 5, sy - 5, 10, 10)
                    end })
  tagged[g:id()] = true
end

local function untag(g)
  if not (g and g:exists()) then return end
  g:overlay(LABEL, nil)
  g:overlay(RING, nil)
end

local function tagAll()
  for _, g in ipairs(hafen.world():gob():list()) do tag(g) end
end

local function untagAll()
  for id in pairs(tagged) do untag(hafen.world():gob():get(id)) end
  tagged = {}
end

-- The filter form's replacement, in two lines: everyone who walks in, and everyone already here.
hafen.event():on("GobAdded", function(g) if labelling then tag(g) end end)

-- Nothing to prune here, and that is the point: an overlay dies with its gob, so this handler exists only to
-- keep OUR bookkeeping table from growing. The overlay itself is already gone.
hafen.event():on("GobRemoved", function(g) tagged[g:id()] = nil end)

-- ---- the world pin ------------------------------------------------------------------------------------

-- hafen.world():gob():nearest already measures from the player and skips the player's own gob, so a bare call is
-- nearest OTHER thing in the world.
local function nearestObject()
  return hafen.world():gob():nearest()
end

-- What ':tagger read' and ':tagger pin' talk about. The PINNED gob wins when there is one -- otherwise the two
-- commands would drift apart the moment you take a step, and 'read' would report an object 'pin' never touched.
local function target()
  if pinned then
    local g = hafen.world():gob():get(pinned)
    if g:exists() then return g, true end
  end
  return nearestObject(), false
end

-- gob:name() is the resource identity, and it is nil until that resource resolves -- so a gob is named by what
-- it is when that is known, and by its id when it is not.
local function label(g)
  return g:name() or ("gob " .. g:id())
end

local function pin()
  if not icon then return hafen.log():write("tagger: icon.png did not load") end
  local g = nearestObject()
  if not g then return hafen.log():write("tagger: nothing near you to pin") end
  if pinned then local old = hafen.world():gob():get(pinned) if old:exists() then old:overlay(PIN, nil) end end
  -- A WORLD-space overlay: offset is in world units with z up, so {z = 18} floats it overhead. It carries the
  -- look verbs its entity already had, and they CHAIN.
  local ov = g:overlay(PIN, { image = icon, scale = 2, offset = { z = 18 } })
  ov:tint{ 255, 200, 90 }:alpha(0.85)
  pinned = g:id()
  local p = ov:pos()
  hafen.log():write(string.format("tagger: pinned %s%s -- ':tagger read' now reads THAT object", label(g),
                          p and string.format(", at %.1f %.1f", p.x, p.y) or ""))
end

local function unpin()
  if not pinned then return hafen.log():write("tagger: nothing is pinned") end
  local g = hafen.world():gob():get(pinned)
  if g:exists() then g:overlay(PIN, nil) end   -- and if it does NOT exist, the overlay went with it
  pinned = nil
  hafen.log():write("tagger: pin removed")
end

-- ---- the read -----------------------------------------------------------------------------------------

-- gob:overlay() is the one read, and it answers BOTH halves. ov:native() says which: ours are false and carry
-- a kind, the game's are true and are keyed by their RESOURCE NAME. A native one is a union over that name --
-- a gob may carry several overlays of one resource -- so ov:count() publishes how many it stands for.
local function read()
  local g, isPinned = target()
  if not g then return hafen.log():write("tagger: nothing near you to read") end
  local all = g:overlay()
  hafen.log():write(string.format("tagger: %s (%s) carries %d overlay(s)", label(g),
                          isPinned and "the pinned one" or "the nearest object", #all))
  for _, ov in ipairs(all) do
    hafen.log():write(string.format("  %-22s %s%s x%d", ov:key(),
                            ov:native() and "the game's" or ("yours, " .. tostring(ov:kind())),
                            ov:res() and (" [" .. ov:res() .. "]") or "", ov:count() or 0))
  end
  if #all == 0 then hafen.log():write("  (nothing -- try ':tagger pin' first, or stand next to a fire)") end
end

-- ---- the events ---------------------------------------------------------------------------------------

-- The same read, pushed. Yours arrive owner-scoped (nobody else is told about a key only you can read); the
-- game's broadcast, keyed by resource name. Both arrive on the NEXT frame, not inside gob:overlay.
local function onEvent(what)
  return function(e)
    if watching then
      hafen.log():write(string.format("tagger: %s %s on gob %d (%s)", what, e.key, e.gob:id(),
                              e.native and "the game's" or "ours"))
    end
  end
end
hafen.event():on("GobOverlayAdded", onEvent("+"))
hafen.event():on("GobOverlayRemoved", onEvent("-"))

-- ---- the command --------------------------------------------------------------------------------------

hafen.slash():register("tagger", function(args)
  local sub = args and args[1]
  if sub == "pin" then return pin() end
  if sub == "unpin" then return unpin() end
  if sub == "read" then return read() end
  if sub == "watch" then
    watching = not watching
    return hafen.log():write("tagger: overlay events " .. (watching and "ON" or "off"))
  end
  labelling = not labelling
  if labelling then tagAll() else untagAll() end
  hafen.log():write("tagger: player labels " .. (labelling and "ON" or "off")
            .. " -- ':tagger pin' hangs one in the world, ':tagger read' lists what is on a gob")
end)
