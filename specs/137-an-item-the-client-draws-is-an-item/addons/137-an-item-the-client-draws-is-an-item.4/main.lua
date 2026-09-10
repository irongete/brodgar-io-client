-- 137.4 -- A depiction's Changed carries the depiction. Self-checking suite.
--
-- Runs from :t137.4 and nowhere else, with a barter stand open. It subscribes Changed on every item icon
-- the client is drawing and then KEEPS NOTHING: the handler writes counters into a plain table, and the
-- only Item the run ever holds is a local that dies with the call that made it. Between the subscribe and
-- the fire it asks the collector to take those handles -- which is the whole test, because a depiction is
-- not its own icon and the client's only record of which widget draws it is the very cache entry that
-- collected handle came from. If the record goes with the handle, a Changed handler is handed nil for the
-- thing it subscribed on. The run sends nothing and mutates nothing.
--
-- What it cannot cause it scores over a ten-second window, which is what the two instructions below are
-- for: a description happens once per depiction, when the client first works out what it is drawing, so
-- the run needs depictions that ARRIVE while it is watching. Both instructions make some.
--   * a barter stand mints its listings as the stand's contents are browsed;
--   * the character sheet's food list makes a fresh icon for every row scrolled into view.

local pass, fail, manual = 0, 0, 0

local function say(s) hafen.log():write(s) end

local function ok(name, cond, got)
  if cond then
    pass = pass + 1
    say("[pass] " .. name)
  else
    fail = fail + 1
    say("[fail] " .. name .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  say("[manual] " .. step .. " -- expect: " .. expect)
end

local function drawnKind(k)      -- a depiction is every icon that is not a held item's
  return (k ~= "WItem") and (k ~= "ItemDrag")
end

-- Up to three records by what their payload said about itself, so a line names WHICH rather than only how
-- many -- and it names them out of the STRINGS the handler kept, never out of an Item the run held on to.
local function named(list)
  local out, n = {}, #list
  for i = 1, math.min(n, 3) do
    local r = list[i]
    out[#out + 1] = (r.kind or "?") .. " " .. (r.pname or "<no name>") .. " " .. (r.pres or "<no res>")
  end
  local s = table.concat(out, ", ")
  if n > 3 then s = s .. ", +" .. (n - 3) .. " more" end
  return s
end

local function run()
  pass, fail, manual = 0, 0, 0        -- a second :t137.4 in one session scores itself, not the pair
  local s = hafen.session():current()
  if s == nil then
    say("[fail] a character is on screen -- got: nil")
    say("[summary] 0 pass, 1 fail, 0 manual")
    return
  end
  local ui = s:ui()

  local recs = {}        -- one per icon the run ever saw: the ICON, what it is, and what fired on it
  local subs = {}        -- everything to end at the close
  local minted = 0       -- handles made since the last collect, so a quiet sweep asks for no GC at all
  local sweeping = true

  local function keep(sub) subs[#subs + 1] = sub end

  local function collect()
    collectgarbage("collect")
    collectgarbage("collect")   -- twice: the first clears the references, the second gives the queue a turn
  end

  -- Subscribe holding NOTHING. The record carries the icon (a widget, not an item), three counters and the
  -- strings the handler reads off the payload -- so the only strong reference to the Item is the local
  -- below, and it goes when this call returns. The handler must not close over `it` either, which is why
  -- it reaches the icon through `rec` and never through the handle it was subscribed with.
  local function track(icon)
    for i = 1, #recs do
      if recs[i].icon == icon then return end
    end
    local it = icon:item()
    if it == nil then return end
    local kind = icon:type()
    local rec = {icon = icon, kind = kind, drawn = drawnKind(kind),
                 fires = 0, items = 0, cmp = 0, same = 0}
    rec.sub = it:on("Changed", function(payload)
      rec.fires = rec.fires + 1
      if type(payload) ~= "userdata" then
        rec.why = rec.why or ("payload is " .. type(payload))
        return
      end
      local read, res = pcall(function() return payload:res() end)
      if not read then
        rec.why = rec.why or ("res() raised: " .. tostring(res))
        return
      end
      if res == nil then
        rec.why = rec.why or "res() is nil"
        return
      end
      rec.items = rec.items + 1
      rec.pres = res
      rec.pname = payload:name()
      -- ...and it is the object the icon itself answers with. Read fresh, compared, and let go again --
      -- the sweep below is what takes it, so the NEXT fire is handed one that has been collected too. An
      -- icon that has left the tree answers nothing, and that is no comparison rather than a wrong one.
      local fresh = rec.icon:item()
      if fresh ~= nil then
        rec.cmp = rec.cmp + 1
        if fresh == payload then
          rec.same = rec.same + 1
        else
          rec.why = rec.why or "payload is not the object icon:item() answers with"
        end
      end
      fresh = nil
      minted = minted + 1
    end)
    keep(rec.sub)
    recs[#recs + 1] = rec
    it = nil
  end

  -- The drop, the collect, and then the question. In that order, because the question is only worth asking
  -- of an entry whose handle has already gone: icon:item() must still answer, and two reads must still be
  -- the same object, on a cache that has nothing left of the handle that made the entry.
  local function sweep()
    local waiting = 0
    for i = 1, #recs do
      if not recs[i].settled then waiting = waiting + 1 end
    end
    if (waiting > 0) or (minted > 0) then
      collect()
      for i = 1, #recs do
        local r = recs[i]
        if not r.settled then
          local a, b = r.icon:item(), r.icon:item()
          r.answers = (a ~= nil) and (a == b)
          r.settled = true
          a, b = nil, nil
        end
      end
      collect()
      minted = 0
    end
    if sweeping then hafen.timer():after(0.5, sweep) end
  end

  keep(ui:on("item", "Added", track))     -- seeded: every icon up now, and every one that arrives
  local seeded = #recs

  -- ---- the door itself, duplicated rather than assumed -------------------------------------------
  local held, drawn, subsOk = 0, 0, 0
  for i = 1, seeded do
    local r = recs[i]
    if r.drawn then drawn = drawn + 1 else held = held + 1 end
    if (r.sub ~= nil) and (r.sub:key() == "Changed") then subsOk = subsOk + 1 end
  end
  ok("every item icon hands back an Item that takes a Changed subscription"
       .. " (" .. seeded .. " icons: " .. held .. " held, " .. drawn .. " drawn)",
     (seeded > 0) and (subsOk == seeded),
     subsOk .. " of " .. seeded .. " took a subscription")

  -- Said HERE and not at the summary: the window they ask about opens now.
  manualCheck("browse or buy at a barter stand DURING the 10 s window that starts now, not before it",
              "the payload line below counts N depictions with N above zero")
  manualCheck("scroll the character sheet's food list up and down inside that same window",
              "the same line names an ItemIcon among the depictions it reached")

  sweep()

  hafen.timer():after(10.5, function()
    sweeping = false

    -- ---- A: every payload a depiction's Changed carried was that depiction ---------------------
    local dFires, dItems, hFires, hItems, cmp, same = 0, 0, 0, 0, 0, 0
    local shown, bad, badDrawn = {}, {}, {}
    for i = 1, #recs do
      local r = recs[i]
      if r.fires > 0 then
        if r.drawn then
          dFires, dItems = dFires + r.fires, dItems + r.items
          if r.items > 0 then shown[#shown + 1] = r end
          if r.why then badDrawn[#badDrawn + 1] = r.kind .. ": " .. r.why end
        else
          hFires, hItems = hFires + r.fires, hItems + r.items
        end
        cmp, same = cmp + r.cmp, same + r.same
        if r.why then bad[#bad + 1] = r.kind .. ": " .. r.why end
      end
    end
    ok("payload: " .. dItems .. " depictions, each an Item"
         .. ((#shown > 0) and (" -- " .. named(shown)) or ""),
       (dItems > 0) and (#badDrawn == 0) and (dItems == dFires),
       (#badDrawn > 0) and table.concat(badDrawn, "; ")
                        or ("nothing described a depiction -- " .. dFires .. " drawn fires reached, and "
                              .. drawn .. " drawn icons were up at the start"))

    -- ---- B: and so was every payload the run was handed, a held item's included ----------------
    local fires, items = dFires + hFires, dItems + hItems
    ok("a payload is the object its own icon answers with (" .. fires .. " fires: "
         .. dFires .. " drawn, " .. hFires .. " held; " .. cmp .. " icons still there to ask)",
       (fires > 0) and (items == fires) and (cmp > 0) and (same == cmp),
       (fires == 0) and "nothing fired at all -- no icon was described inside the window"
                     or (same .. " of " .. cmp .. " matched, " .. items .. " of " .. fires
                           .. " were an Item; " .. table.concat(bad, "; ")))

    -- ---- C: the entry outlived the handle, on every icon the run tracked -----------------------
    local asked, answered = 0, 0
    for i = 1, #recs do
      local r = recs[i]
      if r.settled then
        asked = asked + 1
        if r.answers then answered = answered + 1 end
      end
    end
    ok("the entry outlives the handle: after a collect icon:item() answers and two reads are =="
         .. " (" .. asked .. " icons)",
       (asked > 0) and (answered == asked),
       answered .. " of " .. asked .. " answered")

    for i = 1, #subs do
      if subs[i] ~= nil then pcall(function() subs[i]:off() end) end
    end
    say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
  end)
end

hafen.console():on("t137.4", function() hafen.timer():after(0, run) end)
