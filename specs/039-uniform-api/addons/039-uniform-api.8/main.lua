-- 039.8 — hafen.render(), hafen.ghost(), hafen.asset(), hafen.font(). Self-checking suite; see
-- specs/testing/addon-suite.md.
--
-- Four sections that all hand out THINGS, and four different ways of asking for one. A sprite came from
-- hafen.render.sprite{image=, x=, y=, scale=}, a ghost from hafen.ghost.new{res=, x=, y=}, a file from
-- hafen.asset(path) and a font from hafen.font(name) — three config tables and two arities-as-verbs. They
-- are now three collections and one more: hafen.render():sprite() / :object(), hafen.ghost() (the section
-- object IS the collection), hafen.asset() and hafen.font(). :add(asset, p) places one, :list(filter) reads
-- them, :remove(x) ends one -- and the PLACE is positional beside the thing, because the scene resolves the
-- tile under a gob as it enters it, so a world entity with no place cannot be built at all.
--
-- THE HEADLINE IS THAT THE POSITION REACHES THE WORLD ENTITY. A place in the world has been one TYPE since
-- 039.2, and the two things this section places took a pair of loose numbers. So the run puts one sprite at
-- p and another at p:offset(11, 0) and measures them ONE TILE apart through the API's own reads — which is
-- also the check that a Position built by the engine, offset by the engine and handed to the engine never
-- becomes two numbers on the way.
--
-- The other three claims: every ended thing ends through its collection (:destroy() throws naming
-- :remove(), while an asset's :dispose() SURVIVES, because freeing a resource now is not leaving a set);
-- h:derive() plus setters builds the same handle the options table did, and refuses a write once the font
-- is shared or already in use; and the old table constructors all throw naming what replaced them.
--
-- READ-ONLY: declares no permissions, mutates no persistent state, and removes every entity it places.

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

local ICON, MESH = "icon.png", "tri.glb"
local GHOST = "gfx/terobjs/arch/logcabin"   -- a .res every client has (the docs' own example)
local TILE = 11                             -- world units per tile

local function run()
  local render, ghosts = hafen.render(), hafen.ghost()
  local sprites, objects = render:sprite(), render:object()

  -- 1. THE SHAPE. A section is called and everything after it is a colon verb; a section-level collection is
  -- a SINGLETON, handed back by identity, because one called inside a draw callback runs 60x a second.
  check(hafen.render() == render, "hafen.render() is one section object, handed back by identity")
  check(render:sprite() == sprites, "hafen.render():sprite() is the same collection every call")
  check(hafen.ghost() == ghosts, "hafen.ghost() IS the collection -- one thing, so the section is it")
  refuses("a collection is not an array: #hafen.ghost() says so",
          function() return #hafen.ghost() end, "hafen.ghost():count()")

  -- 2. THE FILES. hafen.asset() is the collection of what this addon shipped: :get(path) loads and interns,
  -- :list() is what it holds, and a string filter matches the path.
  local assets = hafen.asset()
  local icon = assets:get(ICON)
  check(assets:get(ICON) == icon, "hafen.asset():get(path) is interned -- the same handle for the same file")
  check(assets:find(ICON) == icon, "...and a string filter over hafen.asset():list() finds it by path")
  check(assets:count() >= 1, "hafen.asset():list() is the files this addon holds")
  refuses("hafen.asset(path) throws naming the collection",
          function() return hafen.asset(ICON) end, "hafen.asset():get(path)")

  -- 3. THE FONTS. hafen.font() is the built-ins this addon has named; :derive() plus setters is the variant
  -- the options table used to build, and it must build the SAME handle.
  local fonts = hafen.font()
  local mono = fonts:get("mono")
  check(fonts:get("mono") == mono, "hafen.font():get(name) is interned per addon")
  check(fonts:find("mono") == mono, "...and it is a member of hafen.font():list()")
  refuses("hafen.font(name) throws naming the collection",
          function() return hafen.font("mono") end, "hafen.font():get(name)")

  local d = mono:derive():size(13):bold(true)
  eq("a derived handle keeps the family it came from", d:family(), mono:family())
  eq("...takes the size the setter wrote", d:size(), 13)
  eq("...and the style, baked into the face as the options table baked it", d:bold(), true)
  eq("...while the font it derived FROM is untouched", mono:size(), nil)
  refuses("font:derive{...} throws: the options table is the thing that went",
          function() return mono:derive{ size = 13 } end, "takes no arguments")
  refuses("a SHARED font refuses a setter, naming :derive()",
          function() return mono:size(13) end, "h:derive()")

  -- 4. THE WORLD ENTITIES. :add() takes the two things a thing standing in the world is meaningless
  -- without -- what it draws and WHERE -- and everything else is a setter on what comes back.
  local me = hafen.player() and hafen.player():gob()
  if not me then
    hafen.log():write("[fail] the run needs your character in the world -- got: no player gob")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail + 1, manual))
    return
  end
  local p = me:position()
  local s = sprites:add(icon, p):scale(2)
  local s2 = sprites:add(icon, p:offset(TILE, 0)):scale(2)

  -- THE HEADLINE. Built from a Position, offset by the engine, handed back as a Position: one tile apart.
  local d1 = s:position():distance(s2:position())
  check((d1 ~= nil) and (math.abs(d1 - TILE) < 0.001),
        "a sprite at p and one at p:offset(11, 0) stand ONE TILE apart", d1)
  check(s:position():durable(), "...and what a placed sprite reports is a Position, so it is durable")
  refuses("a plain {x, y} table is not a place in the world",
          function() return sprites:add(icon, { x = 1, y = 2 }) end, "must be a Position")
  refuses("...and a thing that stands in the world is not built without one",
          function() return sprites:add(icon) end, "p is required")

  -- Arity is the verb on every property, and a write chains.
  s:position(p:offset(0, TILE))
  check(math.abs(s:position():distance(p) - TILE) < 0.001,
        "sprite:position(p) is the write half of the read -- one name, moved one tile south", s:position())
  s:position(p)
  eq("sprite:scale() reads what sprite:scale(2) wrote", s:scale(), 2)
  eq("sprite:image() is the file it draws", s:image(), ICON)
  eq("sprite:visible() is true for one that was just placed", s:visible(), true)
  eq("...and sprite:visible(false) writes it", s:visible(false):visible(), false)
  s:visible(true)
  eq("sprite:billboard() is false by default -- an upright quad in the world", s:billboard(), false)
  eq("...and writing it re-mills the visual in place, keeping the same sprite",
     s:billboard(true):billboard(), true)
  s:billboard(false)

  -- 5. THE COLLECTIONS ANSWER FOR THEM. Membership, the string filter, and the removal.
  eq("hafen.render():sprite():count() sees both of them", sprites:count(), 2)
  check(sprites:find(ICON) ~= nil, "...and a string filter matches a sprite by its image path")
  eq("hafen.render():object():count() is separate -- a sprite is not an object", objects:count(), 0)

  local g = ghosts:add(GHOST, p)
  eq("hafen.ghost():add(res, p) hands back a ghost that already knows its resource", g:res(), GHOST)
  eq("...and it is in the collection", ghosts:count(GHOST), 1)
  g:res(GHOST)   -- ghost:res(name) is the write half of the same name (was :setRes)
  eq("ghost:res(name) is the write half of the read -- one name, not two", g:res(), GHOST)

  -- 6. ENDING A THING GOES THROUGH WHAT OWNS IT (R7).
  refuses("sprite:destroy() throws naming the collection's :remove()",
          function() return s:destroy() end, "hafen.render():sprite():remove(s)")
  refuses("ghost:destroy() throws naming hafen.ghost():remove()",
          function() return g:destroy() end, "hafen.ghost():remove(g)")
  sprites:remove(s2)
  eq("...and hafen.render():sprite():remove(s) drops it from the collection", sprites:count(), 1)
  eq("...and the entity itself says it is gone", s2:exists(), false)
  refuses("removing something this addon did not place is a refusal, not a silent miss",
          function() return sprites:remove(g) end, "expects a sprite this addon placed")

  -- ...but an ASSET keeps :dispose(): it frees a resource NOW, which is not leaving a collection.
  local tmp = assets:get(MESH)
  local held = assets:count()
  tmp:dispose()
  check(assets:count() == held - 1, "an asset's :dispose() still frees it now -- and drops it from the list")
  check(assets:get(MESH) ~= tmp, "...and the next :get(path) loads the file again as a NEW asset")
  assets:get(MESH):dispose()

  -- 7. THE OLD SPELLINGS. Every one throws naming its replacement, at the line that wrote it.
  refuses("hafen.render.sprite{...} throws", function() return hafen.render.sprite end,
          "hafen.render():sprite():add(imageAsset, p)")
  refuses("hafen.render.object{...} throws", function() return hafen.render.object end,
          "hafen.render():object():add(meshAsset, p)")
  refuses("hafen.ghost.new{...} throws", function() return hafen.ghost.new end, "hafen.ghost():add(res, p)")
  refuses("hafen.ghost.list() throws", function() return hafen.ghost.list end, "hafen.ghost():list(filter)")
  refuses("sprite:pos() throws, and says what a Position is", function() return s.pos end,
          "sprite:position()")
  refuses("sprite:move(x, y) throws", function() return s.move end, "sprite:position(p [, a])")
  refuses("ghost:setRes(res, sdt) throws", function() return g.setRes end, "ghost:res(res, spawnData)")
  refuses("sprite:show() throws", function() return s.show end, "sprite:visible(true)")
  eq("the 028.1 hard cuts still read as plain nil, because THIS feature did not move them",
     tostring(hafen.render.image) .. "/" .. tostring(hafen.font.setFont), "nil/nil")

  -- Clean up: this suite leaves nothing standing.
  sprites:remove(s)
  ghosts:remove(g)
  eq("the suite leaves no sprite behind", sprites:count(), 0)
  eq("the suite leaves no ghost behind", ghosts:count(), 0)

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

hafen.slash():register("t039-8", run)   -- the only way in: a suite does not start itself
