-- stockfilter (040-ui-controls) -- a whole panel built entirely from hafen.ui()'s own controls, the
-- worked example spec 040's api-sketch.md carries end to end: a label, a text entry, a separator, a
-- radio group, a checkbox, a slider driving a live label, a dropdown, a scrolling list and two buttons,
-- laid out in one window and dressed by the stylesheet like any window the client builds itself.
--
-- Unlike the sketch's placeholder `query{}`, every filter here reads the REAL items in your backpack or
-- your equipment (widget:items(), docs/addons/api/ui/items.md) -- there is nothing to fake: :name(),
-- :quality() and :wear() are exactly the fields a real Item answers. It is read-only end to end: nothing
-- here writes to the server, so there is no permission to declare and nothing to undo.
--
--   :stockfilter   opens the panel; running it again while open closes it
--
-- Reset puts every filter back to its opening value and re-reads the container, same as a fresh open.

local win                                             -- the panel window, or nil while closed
local search, sort, onlyQuality, qLabel, minq, kind, results

local function container()
  if kind and (kind:value() == "Equipment") then
    return hafen.ui():equipment()
  end
  return hafen.ui():inventory()
end

local function label(it)
  local name = it:name() or it:res() or "?"
  local q = it:quality()
  return q and ("%s (q%d)"):format(name, q) or name
end

local function refresh()
  if not win then return end                          -- the window closed mid-callback; nothing to do
  local needle = (search:value() or ""):lower()
  local minQ = minq:value() or 0
  local by = sort:value() or "Name"
  local requireQuality = onlyQuality:value()

  local c = container()
  local rows = {}
  for _, it in ipairs(c and c:items() or {}) do
    local name = (it:name() or it:res() or ""):lower()
    local q = it:quality()
    if ((needle == "") or (name:find(needle, 1, true) ~= nil))
        and (not requireQuality or (q ~= nil))
        and ((q == nil) or (q >= minQ)) then
      table.insert(rows, it)
    end
  end

  table.sort(rows, function(a, b)
    if by == "Quality" then return (a:quality() or -1) > (b:quality() or -1)
    elseif by == "Wear" then return (a:wear() or -1) > (b:wear() or -1)
    else return (a:name() or a:res() or "") < (b:name() or b:res() or "") end
  end)

  local names = {}
  for i, it in ipairs(rows) do names[i] = label(it) end
  results:rows(names)
end

local function build()
  win = hafen.ui():window():title("Stock filter"):size(260, 330):position(80, 120)
  -- widget:on(key, fn) hands back a SUB, not the widget (041.3), so it can never sit mid-chain -- every one
  -- below is its own statement, after the builder chain that made the control it belongs to.
  win:on("Close", function() win = nil end)

  hafen.ui():label():parent(win):position(10, 10):text("Search")

  search = hafen.ui():entry()
    :parent(win):position(10, 28):size(240)
    :value("")
  search:on("Changed", function() refresh() end)

  hafen.ui():separator():parent(win):position(10, 58):size(240)

  sort = hafen.ui():radio()
    :parent(win):position(10, 70)                     -- the stack starts HERE; rows go downward
    :rows{"Name", "Quality", "Wear"}
    :value("Name")
  sort:on("Changed", function() refresh() end)

  onlyQuality = hafen.ui():check()
    :parent(win):position(10, 132)
    :text("Only items with quality")
    :value(false)
  onlyQuality:on("Changed", function() refresh() end)

  qLabel = hafen.ui():label():parent(win):position(10, 158):text("Min quality: 0")

  minq = hafen.ui():slider()
    :parent(win):position(10, 176):size(240)
    :range(0, 100)
    :value(0)
  -- a slider's Changed hands an ev now (R4: two things to say) -- ev:value()/:final(), not two loose args.
  minq:on("Changed", function(ev)
    qLabel:text(("Min quality: %d"):format(ev:value()))     -- live while dragging
    if ev:final() then refresh() end                        -- once, on release
  end)

  kind = hafen.ui():dropdown()
    :parent(win):position(10, 204):size(120)
    :rows{"Backpack", "Equipment"}
    :value("Backpack")
  kind:on("Changed", function() refresh() end)

  results = hafen.ui():list()
    :parent(win):position(10, 232):size(240, 60)
    :rows{}
  results:on("Changed", function(row) hafen.log():write("stockfilter: picked " .. tostring(row)) end)

  local refreshBtn = hafen.ui():button()
    :parent(win):position(10, 300):size(80)
    :text("Refresh")
  refreshBtn:on("Pressed", refresh)

  local resetBtn = hafen.ui():button()
    :parent(win):position(96, 300):size(80)
    :text("Reset")
  resetBtn:on("Pressed", function()
    search:value("")
    sort:value("Name")
    onlyQuality:value(false)
    minq:value(0)
    qLabel:text("Min quality: 0")
    kind:value("Backpack")
    refresh()
  end)

  refresh()
end

hafen.slash():register("stockfilter", function()
  if win then
    win:destroy()
    win = nil
  else
    build()
  end
end)

hafen.log():write("stockfilter loaded -- :stockfilter opens a panel filtering your backpack (or equipment)"
  .. " by name, quality and wear, built entirely from hafen.ui()'s own controls")
