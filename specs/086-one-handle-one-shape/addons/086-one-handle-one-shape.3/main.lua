-- 086.3 -- a binding is an object. Self-checking suite.
--
-- keybindings:list() handed back { [id] = key }, so ipairs over it walked NOTHING and raised nothing:
-- the one :list() in the API that was not an array, and a loop that looks exactly like "no bindings".
-- And keybindings:key(name, k) wrote two of the three states the client's registry actually has, so an
-- addon that politely saved a key and wrote it back turned the client's own DEFAULT into an assignment
-- for good -- with no verb anywhere able to tell the two apart.
--
-- THE CLAIM IS THAT A BINDING IS AN OBJECT. The collection lists them as an array; the member carries
-- all three states -- :key() reads the effective key, :key(k) assigns, :key("None") unbinds, and
-- :key(nil) puts it back on the client's own default -- and :default() / :assigned() are what make
-- that last one usable. Every write below is on a binding this suite declared itself.

local pass, fail, manual = 0, 0, 0

-- A handler that has to exist and has to do nothing: this suite proves the SHAPE of a binding, and
-- nothing here needs the key to be pressed.
local function noop() end

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

-- The message a call raised, with the "main.lua:12:" prefix Lua puts on it stripped off; nil if it did
-- not raise at all.
local function said(fn)
  local ok, err = pcall(fn)
  if ok then return nil end
  return (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local kb = hafen.client():options():keybindings()

-- The suite's own hotkey, declared once however often the suite is run: a second kb:on(name, fn) makes
-- a SECOND subscription on that name, which would put a second row in the client's keybind panel and
-- make the manual step ambiguous. It is left declared so that panel row exists to be looked at.
local HOTNAME = "t086key3"
local hot
local function mine()
  if not hot then hot = kb:on(HOTNAME, noop) end
  return kb:binding():get(HOTNAME)
end

-- Four deliberately obscure chords, and the first of them nothing else is using. Assigning a real key
-- UNBINDS every other binding on it, and that theft has no undo -- so the one key this suite writes is
-- one the client and every other addon has left alone. Our own binding does not count as taken: a
-- previous run leaves it assigned for the manual step, and overwriting it is the point.
local CANDIDATES = {"Ctrl+Alt+F9", "Ctrl+Alt+F10", "Ctrl+Alt+F11", "Ctrl+Alt+F12"}

local function freeKey(b)
  local taken = {}
  for _, other in ipairs(kb:binding():list()) do
    local k = other:key()
    if k and (other:id() ~= b:id()) then taken[k] = true end
  end
  for _, k in ipairs(CANDIDATES) do
    if not taken[k] then return k end
  end
  return nil
end

-- ---- the collection is an array ---------------------------------------------------------------------
--
-- The claim the row exists for: :list() is a plain 1-based array, so a bare ipairs walks every member
-- and # agrees with :count(). This is the loop that ran zero times over a map.
local function shapeSection()
  local coll = kb:binding()
  local list = coll:list()
  local walked = 0
  for _, b in ipairs(list) do walked = walked + 1 end
  local counted = coll:count()
  check((walked == counted) and (#list == walked) and (walked > 0),
        ("ipairs over kb:binding():list() walks every binding :count() reported (%d/%d)")
          :format(walked, counted),
        "#list=" .. tostring(#list) .. " walked=" .. tostring(walked))

  -- :get(id) addresses one, and it tries this addon's own scope first -- so a hotkey is reached by the
  -- name it was declared under while b:id() is the full registry id. Interned, so == finds it again.
  local b = mine()
  local again = kb:binding():get(HOTNAME)
  check((b ~= nil) and (again == b) and (b:id() == "addon/086-one-handle-one-shape.3/" .. HOTNAME),
        "kb:binding():get(name) finds this addon's own binding by its short name, and b:id() is the"
          .. " full registry id",
        tostring(b and b:id()))

  -- A binding's id IS its name, so a string filter is a substring test on it.
  check(kb:binding():find(HOTNAME) == b,
        "kb:binding():find(\"" .. HOTNAME .. "\") finds it by the string filter",
        tostring(kb:binding():find(HOTNAME)))

  -- The registry fills in as classes load and as addons declare hotkeys, so an id nothing has declared
  -- is a state of a binding rather than a miss: :get always hands back an object and :exists() asks.
  local ghost = kb:binding():get("t086-no-such-binding")
  check((ghost ~= nil) and (ghost:exists() == false) and (ghost:key() == nil)
          and (ghost:default() == nil) and (ghost:info() == nil),
        "an id nothing has declared still hands back a Binding, and it answers :exists() false",
        tostring(ghost) .. " exists=" .. tostring(ghost and ghost:exists()))
  return b
end

-- ---- the three states -------------------------------------------------------------------------------
--
-- Assign, read back, revert. The revert is the half nothing could do: putting a binding back on the
-- client's own default, which is what a save-and-restore has to be able to write.
local function stateSection(b)
  local k = freeKey(b)
  if k == nil then
    check(false, "an unused test key is available to write", "all of " .. table.concat(CANDIDATES, ", ")
          .. " are bound to something")
    return nil
  end
  local before = b:default()
  b:key(k)
  local assignedKey, wasAssigned = b:key(), b:assigned()
  b:key(nil)
  check((assignedKey == k) and (wasAssigned == true)
          and (b:assigned() == false) and (b:key() == b:default()) and (b:default() == before),
        "b:key(k) assigns and b:key(nil) puts it back on the client's own default (" .. k .. ")",
        "assigned=" .. tostring(assignedKey) .. "/" .. tostring(wasAssigned)
          .. " after=" .. tostring(b:key()) .. "/" .. tostring(b:assigned()))

  -- The pair only a three-valued object can separate: "None" is the USER choosing unbound, and nil is
  -- the client's default -- both read as no key, and :assigned() is what tells them apart.
  b:key("None")
  local noneAssigned, noneKey = b:assigned(), b:key()
  b:key(nil)
  check((noneAssigned == true) and (noneKey == nil) and (b:assigned() == false),
        "b:key(\"None\") is an assignment and b:key(nil) is not, though both read as no key",
        "None: assigned=" .. tostring(noneAssigned) .. " key=" .. tostring(noneKey))

  -- :info() is the one snapshot, and it carries what the four reads answer.
  b:key(k)
  local i = b:info()
  check((i ~= nil) and (i.id == b:id()) and (i.key == k) and (i.assigned == true) and (i.default == nil),
        "b:info() carries the id, the key, the default and whether it is assigned",
        (i == nil) and "nil" or (tostring(i.id) .. " / " .. tostring(i.key) .. " / "
          .. tostring(i.default) .. " / " .. tostring(i.assigned)))
  return k
end

-- ---- the closed vocabulary --------------------------------------------------------------------------
--
-- The two spellings that addressed a binding by name are retired, and a typo on the object itself is
-- refused. All three are reads of a key the value has not got, so without a closed vocabulary each is a
-- plain nil that fails one line later as "attempt to call a nil value", naming nothing.
local function refusalSection(b)
  local reached = 0
  local misses = {}
  local cases = {
    {"kb:list()", function() return kb:list() end, "binding()"},
    {"kb:key(\"x\")", function() return kb:key("x") end, "binding()"},
    {"b:keys()", function() return b:keys() end, ":default()"},
  }
  for _, c in ipairs(cases) do
    local msg = said(c[2])
    if (msg ~= nil) and (msg:find(c[3], 1, true) ~= nil) then
      reached = reached + 1
    else
      misses[#misses + 1] = c[1] .. " -> " .. tostring(msg)
    end
  end
  check(reached == #cases,
        ("the retired spellings and an unknown verb each raise naming the fix (%d/%d)")
          :format(reached, #cases),
        table.concat(misses, " | "))

  -- Changing what writes a key did not lose the refusal that was already there.
  local msg = said(function() b:key("nonsense++") end)
  check((msg ~= nil) and (msg:find("\"F5\"", 1, true) ~= nil) and (msg:find("\"Ctrl+M\"", 1, true) ~= nil)
          and (msg:find("\"None\"", 1, true) ~= nil),
        "b:key(\"nonsense++\") raises naming the examples \"F5\", \"Ctrl+M\" and \"None\"",
        msg or "<no error>")
end

-- Each section is its own pcall: a call that raises where nothing expected it to loses that section's
-- remaining checks and nothing else, so the run still prints every other verdict and a summary.
local function section(name, fn, arg)
  local ok, r = pcall(fn, arg)
  if not ok then
    fail = fail + 1
    hafen.log():write("[fail] the " .. name .. " section ran to the end -- got: " .. tostring(r))
    return nil
  end
  return r
end

local function run()
  pass, fail, manual = 0, 0, 0

  local b = section("collection", shapeSection)
  local k = b and section("state", stateSection, b)
  if b then section("refusal", refusalSection, b) end

  -- The run ends with the binding ASSIGNED, because the eye needs both readings and only one of them
  -- can be the state a run leaves behind. :t086-3revert is the other half.
  manualCheck("open Options > Keybindings, find this addon's own section (its name starts 086.3) and"
                .. " read its " .. HOTNAME .. " row; then run :t086-3revert and read that row again",
              (k or "the test key") .. ", then None")

  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local function revert()
  local b = mine()
  b:key(nil)
  hafen.log():write("[manual-step] " .. HOTNAME .. " is back on the client's own default: key="
                    .. tostring(b:key()) .. ", assigned=" .. tostring(b:assigned()))
end

hafen.slash():on("t086-3", run)              -- the only way in: a suite does not start itself
hafen.slash():on("t086-3revert", revert)     -- the second half of the one manual step
