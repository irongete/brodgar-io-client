-- R01 -- nothing process-wide that an addon owns.
--
-- Every surface this block moved is asserted through the API itself: the three visual writes on a game
-- object (whose intent record is now the writing addon's), the marker refs (now the asking addon's), the
-- mouse grab (now armed in the addon layer rather than in whichever session is drawn), the console
-- command (whose engine dispatcher now leaves with the last handler for its name) and the action-menu
-- entry (whose key binding now leaves the client's registry with the entry).
--
-- Run it with :tR01. It starts nothing by itself and leaves nothing behind.

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

-- LuaJ prefixes a bridge refusal with "@chunk.lua:<n>" and a SPACE, and a Lua error with "chunk.lua:<n>:".
local function why(err)
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function refuses(desc, fn, needle)
  local fine, err = pcall(fn)
  if fine then
    fail = fail + 1
    line("[fail] " .. desc .. " -- got: <no error>")
    return
  end
  local msg = why(err)
  ok(desc, msg:find(needle, 1, true) ~= nil, msg)
end

local function report()
  for _, s in ipairs(out) do hafen.log():write(s) end
  hafen.log():write("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
end

-- ---------------------------------------------------------------- the checks

local function run()
  out, pass, fail, manual = {}, 0, 0, 0
  local s = hafen.session():current()
  if not s then
    hafen.log():write("[fail] the suite needs a character in the world -- got: no current session")
    hafen.log():write("[summary] 0 pass, 1 fail, 0 manual")
    return
  end

  -- The three visual writes, each of which records its intent on THIS addon and reads back off the copy.
  local g = s:world():gob():nearest()
  if g then
    g:scale(2)
    local big = g:scale()
    g:scale(1)
    ok("gob:scale writes, reads back, and 1 forgets it", (big == 2) and (g:scale() == 1), big .. " then " .. tostring(g:scale()))

    g:visible(false)
    local hidden = g:visible()
    g:visible(true)
    ok("gob:visible writes, reads back, and true forgets it", (hidden == false) and (g:visible() == true), tostring(hidden) .. " then " .. tostring(g:visible()))

    g:tint{255, 0, 0, 96}
    local c = g:tint()
    g:tint(nil)
    ok("gob:tint writes, reads back keyed, and nil forgets it", (c ~= nil) and (c.r == 255) and (g:tint() == nil), tostring(c and c.r) .. " then " .. tostring(g:tint()))

    refuses("gob:visible refuses a number, naming the argument", function() g:visible(0) end, "b must be true or false")
  else
    fail = fail + 4
    line("[fail] the four gob checks need an object in view -- got: nothing near the character")
  end

  -- The marker refs: two look-ups of one pin are the same interned handle, through this addon's own map.
  local pins = hafen.map():marker():list()
  if #pins > 0 then
    ok("a marker handle is interned across two look-ups", pins[1] == hafen.map():marker():list()[1], "two different handles")
  else
    manual = manual + 1
    line("[manual] the map database holds no pin -- place one on the map and re-run to check marker interning")
  end

  -- The grab: minted in the addon layer, and every ending hands the receiver back.
  local grab = hafen.ui():mouse():grab()
  ok("a grab is minted, and release hands the grab back", (grab ~= nil) and (grab:release() == grab), tostring(grab))
  if grab then
    -- "Added" is a real key on the bus, so this asks the harder half: the grab's set is CLOSED, not merely
    -- checked against nonsense.
    refuses("grab:on refuses a key that is not its own, naming Move and Up", function() grab:on("Added", function() end) end, "it has: Move, Up")
  else
    fail = fail + 1
    line("[fail] grab:on refuses a key that is not its own -- got: no grab to ask")
  end

  -- The console command: its engine dispatcher leaves with the last handler, so the name registers again.
  local sub = hafen.console():on("r01tmp", function() end)
  sub:off()
  local again, err = pcall(function() return hafen.console():on("r01tmp", function() end) end)
  if again then err:off() end
  ok("a console command registers, ends, and registers again", again, again or why(err))
  refuses("hafen.console():on refuses a reserved name", function() hafen.console():on("reload", function() end) end, "reserved engine command")

  -- ev:gob() reads the clicking session's own state now, which only a real click can show.
  manual = manual + 1
  line("[manual] left-click the ground or an object once -- expect: one line 'ev:gob -> <name or ground>'")
  local watch
  watch = hafen.event():action():on("click", function(ev)
    watch:off()
    local clicked = ev:gob()
    hafen.log():write("ev:gob -> " .. (clicked and clicked:name() or "ground"))
  end)

  -- The action-menu entry's key binding leaves the client's registry with the entry. The button that mints
  -- it is built by the grid's own relayout, so the mint is waited for over a bounded window rather than
  -- assumed; the removal is asserted on the same id.
  local id = "scm/addon/" .. ADDON.id .. "/r01probe"
  local function bound() return #hafen.client():options():keybindings():binding():list(id) end
  local pag = s:menugrid():add("r01probe")
  pag:name("R01 probe")
  local tries = 0
  local poll
  poll = hafen.timer():every(0.2, function()
    tries = tries + 1
    local minted = bound() == 1
    if not minted and (tries < 10) then return end
    poll:cancel()
    s:menugrid():remove(pag)
    ok("a menu entry mints its key binding, and removing it takes the binding out",
       minted and (bound() == 0),
       minted and ("the binding stayed after :remove") or ("no binding after 2 s (is the action menu on a sub-screen?)"))
    report()
  end)
end

hafen.console():on("tR01", run)
