-- 140.1 — the page is a column the addon fills. Self-checking suite.

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

local opts = hafen.client():options():addon()

-- What the fill was handed, and where it ran. Written by fn, read by the watch.
local handed = nil      -- the root the client handed over
local stepping = nil    -- hafen.client():stepping() as fn saw it
local button = nil      -- a control built INTO root from fn

local function fill(root)
  handed = root
  stepping = hafen.client():stepping()
  hafen.ui():label():parent(root):text("140.1: built by the addon")
  button = hafen.ui():button():parent(root):size(160):text("a button of yours")
end

opts:panel(fill)   -- registered from the file body: the row is in Options ▸ AddOns from load

local WINDOW = 'Options ▸ AddOns ▸ "140.1 — the page is a column"'
local WATCH = 30

-- Walk :parent() up from root until a window titled Options; the chain walked is the second answer.
local function optionsWindow(root)
  local w, chain = root, {}
  for _ = 1, 32 do
    if w == nil then return nil, table.concat(chain, " > ") end
    chain[#chain + 1] = w:type() .. (w:title() and ("[" .. w:title() .. "]") or "")
    if w:title() == "Options" then return w, table.concat(chain, " > ") end
    w = w:parent()
  end
  return nil, table.concat(chain, " > ")
end

local function score()
  local root = handed
  eq("root:role() is a column", root:role(), "column")
  eq("root:info().owned reads true", root:info().owned, true)
  eq("root:gap() answers on it: an owned column", root:gap(), 0)
  local port = root:parent()
  eq("root:size().w is the port's width", root:size().w, port and port:size().w)
  local win, chain = optionsWindow(root)
  if win == nil then   -- what the selector path says of the same window, beside the read the walk made
    local s = hafen.session():current()
    local sel = s and s:ui():match("window[title=Options]")
    chain = chain .. " | selector: " .. tostring(sel and sel:type()) .. " | visible: "
      .. tostring(s and s:ui():match("@OptWnd") and s:ui():match("@OptWnd"):visible())
  end
  check(win ~= nil, "root:parent() reaches a window titled Options", chain)
  eq("fn ran on the step, holding no tree", stepping, true)
  check(button ~= nil and button:exists(), "the button fn built into root exists",
        button and button:exists())
  local want = "column"
  eq("the button stands in root", button and button:parent() and button:parent():role(), want)
end

local function run()
  pass, fail, manual = 0, 0, 0
  handed, stepping, button = nil, nil, nil
  check(opts:panel() == fill, "opts:panel() reads the function registered", opts:panel())
  opts:panel(nil)
  eq("opts:panel(nil) withdraws it: :panel() reads nil", opts:panel(), nil)
  local function g(root) return fill(root) end
  opts:panel(g)
  check(opts:panel() == g, "a second :panel(g) replaces it: :panel() reads g", opts:panel())
  hafen.log():write("[watch] open " .. WINDOW .. " within " .. WATCH .. " s")
  local waited = 0
  local tick
  tick = hafen.timer():every(0.5, function()
    waited = waited + 0.5
    if handed ~= nil then
      tick:cancel()
      score()
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    elseif waited >= WATCH then
      tick:cancel()
      check(false, "the page filled", WINDOW .. " was not opened within " .. WATCH .. " s")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end
  end)
end

hafen.console():on("t140", run)   -- the only way in: a suite does not start itself
