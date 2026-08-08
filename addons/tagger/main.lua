-- Tagger: the EXAMPLE for gob:overlay -- the one way to attach anything to a game object, and the one way to
-- read what is already attached (spec 038-gob-overlays).
--
-- gob:overlay() IS A COLLECTION, and the key is your own name for the thing you hung there:
--   gob:overlay():list()     -- every overlay on that gob: yours, then the GAME's own
--   gob:overlay():get(key)   -- that one, or nil
--   gob:overlay():add(key)   -- attach a BARE one, or REPLACE whatever that key already named
--   gob:overlay():remove(key)-- remove it
--
-- AN OVERLAY IS WHAT IS *DRAWN* AT THE GOB, in screen space at its projected point: :text(s) and :draw(fn),
-- with :color(r, g, b) and :offset(x, y) in PIXELS. Every setter answers the overlay, so one statement
-- configures the whole thing -- and until it names a kind it draws nothing, so a half-configured overlay never
-- flickers. You never place it and you never poll: the record lives ON the gob, so it follows the gob for free
-- and it DIES WITH IT -- a felled tree takes your label with it and a gob that comes back is bare.
--
-- A THING STANDING IN THE WORLD IS NOT AN OVERLAY (043.3). :image/:model/:ghost are gone: what they built was a
-- client gob of its own in the 3D scene, so it belongs to hafen.vr(), and the gob is the second argument of the
-- placement -- hafen.vr():sprite():add(icon, gob). It still shows up in that gob's overlay list, READ-ONLY, so
-- "what is at this gob?" has one complete answer; you address it through the collection that owns it.
--
-- THERE IS NO FILTER FORM, and this addon is what that trade looks like: "label every player" is a GobAdded
-- handler plus one loop over the players already here (see tagAll/onAdded below). You name the gob, so nothing
-- is searched per frame -- which is the whole reason the old sweep is gone.
--
-- COMMANDS (:tagger <sub>):
--   (bare)   -- labels ON/OFF: a green name over every player body, kept up to date from GobAdded
--   pin      -- stand icon.png in the WORLD over the nearest object, anchored to it
--   unpin    -- take it off again
--   read     -- print everything at the nearest object: yours, what you stood there, and the game's own
--   watch    -- toggle the two events, GobOverlayAdded / GobOverlayRemoved
--
-- SAFE tier: it declares NO permissions, sends nothing to the server and writes nothing persistent. Everything
-- it attaches is removed on ':reload' or on disable, and the game's own overlays are never touched -- they are
-- READ-ONLY, and both an attach onto a native key and a remove of one raise, naming the key.

local LABEL = "name"                  -- our key for a player's label       (an overlay: screen space)
local RING  = "ring"                  -- our key for the ring under it      (an overlay: a draw callback)

local labelling = false
local watching  = false
local tagged    = {}                  -- gob id -> true, the players we have labelled
local pin_                            -- the hafen.vr() Sprite standing over a gob, if any
local pinGob                          -- the id of the gob it is anchored to
local icon

hafen.event():on("Load", function() icon = hafen.asset():get("icon.png") end)

-- ---- the labels ---------------------------------------------------------------------------------------

-- One player, two overlays under two keys of our own. A key is per addon, so "name" here can never collide
-- with another addon's "name" -- and the read below will list ours and the game's, never a third party's.
local function tag(g)
  if not (g and g:exists() and g:isPlayer()) then return end
  local kin = g:kin()
  local ov = g:overlay():add(LABEL):text(kin and kin:name() or "player"):offset(0, -4)
  if kin then ov:color(120, 220, 120) else ov:color(220, 220, 120) end
  -- A draw callback gets the SAME projected point as sx, sy and paints whatever it likes there. It runs inside
  -- the client's draw pass, which is outside the per-tick CPU budget -- so keep it to a few shapes.
  g:overlay():add(RING):draw(function(gout, gob, sx, sy)
                          gout:color(120, 220, 120, 160)
                          gout:rect(sx - 5, sy - 5, 10, 10)
                        end)
  tagged[g:id()] = true
end

local function untag(g)
  if not (g and g:exists()) then return end
  g:overlay():remove(LABEL):remove(RING)   -- :remove answers the collection, so removals chain
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
-- A pin dies with the gob it was anchored to, so a pin that no longer exists means nothing is pinned.
local function target()
  if pin_ and pin_:exists() then
    local g = hafen.world():gob():get(pinGob)
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
  if pin_ and pin_:exists() then hafen.vr():sprite():remove(pin_) end
  -- THE GOB IS THE ANCHOR, the second argument of the placement. :offset takes three numbers in WORLD units
  -- with z up, so 18 floats it overhead; :position(p) is refused here, because a follower's point is its gob's.
  -- Every setter answers the sprite, so the whole thing is one statement -- and the visual is built once.
  pin_ = hafen.vr():sprite():add(icon, g):scale(2):offset(0, 0, 18):tint(255, 200, 90):alpha(0.85)
  pinGob = g:id()
  local p = pin_:position()
  hafen.log():write(string.format("tagger: pinned %s%s -- ':tagger read' now reads THAT object", label(g),
                          p and string.format(", at %.1f %.1f", p:x(), p:y()) or ""))
end

local function unpin()
  if not pin_ then return hafen.log():write("tagger: nothing is pinned") end
  -- If the gob went, the pin went with it (it was anchored to it) -- so :exists() is the whole test.
  if pin_:exists() then hafen.vr():sprite():remove(pin_) end
  pin_, pinGob = nil, nil
  hafen.log():write("tagger: pin removed")
end

-- ---- the read -----------------------------------------------------------------------------------------

-- gob:overlay():list() is the one read, and it answers ALL THREE groups: your own overlays first, then whatever
-- you stood at that gob with hafen.vr(), then the game's own. ov:native() is true for the game's alone -- a vr
-- entity is yours, it is simply addressed elsewhere -- and ov:kind() tells the two of yours apart: "draw"/"text"
-- is an overlay, "sprite"/"object"/"ghost" names the hafen.vr() collection that owns it. A native one is a union
-- over its RESOURCE NAME -- a gob may carry several of one resource -- so ov:count() says how many it stands for.
local WORLD = { sprite = true, object = true, ghost = true }

local function read()
  local g, isPinned = target()
  if not g then return hafen.log():write("tagger: nothing near you to read") end
  local all = g:overlay():list()
  hafen.log():write(string.format("tagger: %s (%s) carries %d thing(s)", label(g),
                          isPinned and "the pinned one" or "the nearest object", #all))
  for _, ov in ipairs(all) do
    local kind = ov:kind()
    local whose = ov:native() and "the game's"
               or (WORLD[kind] and ("yours in the WORLD, hafen.vr():" .. kind .. "()")
                                or ("yours, " .. tostring(kind)))
    hafen.log():write(string.format("  %-22s %s%s x%d", ov:key(), whose,
                            ov:res() and (" [" .. ov:res() .. "]") or "", ov:count() or 0))
  end
  if #all == 0 then hafen.log():write("  (nothing -- try ':tagger pin' first, or stand next to a fire)") end
end

-- ---- the events ---------------------------------------------------------------------------------------

-- The same read, pushed. Yours arrive owner-scoped (nobody else is told about a key only you can read); the
-- game's broadcast, keyed by resource name. Both arrive on the NEXT frame, not inside the :add itself -- which
-- is also why a handler reading the overlay back sees it fully configured.
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
