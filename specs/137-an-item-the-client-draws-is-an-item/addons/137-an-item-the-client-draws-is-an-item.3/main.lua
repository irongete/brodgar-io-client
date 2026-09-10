-- 137.3 -- Changed reaches every depiction, once per build. Self-checking suite.
--
-- Runs from :t137.3 and nowhere else, with a crafting recipe open. It subscribes Changed on every item
-- icon the client is drawing and then reads each one, which is what DESCRIBES an icon nothing has described
-- yet -- so the subscribe-then-read is itself the arrival under test, and each icon it describes must fire
-- exactly once. It then changes the client's font twice and scores what it cannot cause -- an icon that
-- arrives, and one that leaves the tree -- over a twelve-second window, which is what the two instructions
-- below are for. It sends nothing and mutates nothing.
--
-- Every window has its control, because a held item revises for reasons of its own -- the server resends a
-- tooltip, an item moves in or out of what a bag holds:
--   * a HALF SECOND per icon from the moment it is tracked: its first build lands here, once or not at all.
--   * TWO font changes, half a second apart, and a quiet second after them. Only an icon that fires in both
--     of them and not in the quiet second answers to the font: one that fires in a single window is a
--     revision of its own that the rebuild merely delayed into it.

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

-- Up to three icons by what they say about themselves, so a line names WHICH rather than only how many.
-- Both reads, because a depiction that answers one and not the other is worth seeing.
local function named(list)
  local out, n = {}, #list
  for i = 1, math.min(n, 3) do
    local r = list[i]
    out[#out + 1] = (r.kind or "?") .. " " .. (r.item:name() or "<no name>")
                      .. " " .. (r.item:res() or "<no res>")
  end
  local s = table.concat(out, ", ")
  if n > 3 then s = s .. ", +" .. (n - 3) .. " more" end
  return s
end

local function run()
  local s = hafen.session():current()
  if s == nil then
    say("[fail] a character is on screen -- got: nil")
    say("[summary] 0 pass, 1 fail, 0 manual")
    return
  end
  local ui = s:ui()

  local recs = {}          -- one per icon the run ever saw: the icon, its Item, and what fired on it
  local stales = {}        -- an Item whose icon HAS LEFT the tree, and a Changed subscribed after it did
  local subs = {}          -- everything to end at the close
  local removedSeen = 0    -- how many times the item role's Removed fired, whatever it was about

  local function keep(sub) subs[#subs + 1] = sub end

  -- Subscribe FIRST, read second. The read is what forces the build on an icon nothing has described yet,
  -- so whatever it describes fires here -- and `firstFired`, half a second on, is how many times. Zero for
  -- an icon the client could already describe (a recipe slot, described while its widget was being built);
  -- exactly one for an icon this read described; never two.
  local function track(icon)
    for i = 1, #recs do
      if recs[i].icon == icon then return end
    end
    local it = icon:item()
    if it == nil then return end
    local rec = { icon = icon, item = it, kind = icon:type(), fired = 0 }
    rec.sub = it:on("Changed", function() rec.fired = rec.fired + 1 end)
    rec.named = it:name()
    recs[#recs + 1] = rec
    keep(rec.sub)
    hafen.timer():after(0.5, function()
      rec.firstFired = rec.fired          -- the build window is closed; everything after this is a revision
    end)
  end

  keep(ui:on("item", "Added", track))       -- seeded: every icon up now, and every one that arrives
  keep(ui:on("item", "Removed", function() removedSeen = removedSeen + 1 end))

  -- A stale item is found by SWEEPING what the run is holding, not by matching a Removed payload back to
  -- the icon it was handed for: a widget handle is retired at the same disposal that ends the icon, so the
  -- two need not be the same object. The sweep also settles the ordering the check needs for free -- the
  -- subscription is made only once :exists() has already turned false, and a removal is a DETACH, so an
  -- icon is often still in the tree at the moment its Removed fires.
  local sweeping = true
  local function sweep()
    for i = 1, #recs do
      local r = recs[i]
      if (not r.staled) and (r.item:exists() == false) then
        r.staled = true
        local st = { item = r.item, kind = r.kind, fired = 0 }
        st.sub = r.item:on("Changed", function() st.fired = st.fired + 1 end)
        stales[#stales + 1] = st
        keep(st.sub)
      end
    end
    if sweeping then hafen.timer():after(0.5, sweep) end
  end
  hafen.timer():after(0.5, sweep)

  local seeded = #recs
  for i = 1, seeded do recs[i].seeded = true end

  -- ---- the door itself, duplicated rather than assumed -------------------------------------------
  local held, slots, subsOk = 0, 0, 0
  local function count()
    held, slots = 0, 0
    for i = 1, #recs do
      local k = recs[i].kind
      if not drawnKind(k) then held = held + 1 end
      if (k == "SpecWidget") or (k == "Input") then slots = slots + 1 end
    end
  end
  count()
  for i = 1, seeded do
    if (recs[i].sub ~= nil) and (recs[i].sub:key() == "Changed") then subsOk = subsOk + 1 end
  end
  ok("every icon up hands back an Item that takes a Changed subscription"
       .. " (" .. seeded .. " icons: " .. held .. " held, " .. slots .. " slots)",
     (seeded > 0) and (subsOk == seeded) and (slots > 0),
     (subsOk .. " of " .. seeded .. " took a subscription")
       .. ((slots == 0) and "; no recipe slot on screen -- open a crafting recipe and run :t137.3 again"
                         or ""))

  -- Said HERE and not at the summary: the window it asks about opens now.
  manualCheck("open a barter stand DURING the 12 s window that starts now, not before it",
              "\"described: N icons, each fired once\" with the drawn count above zero")
  manualCheck("close the crafting window before those 12 s are up",
              "\"a stale item ... (N reached)\" with N above zero")

  local face = hafen.font():get("serif"):derive():size(13):bold(true)

  local function flag(field)               -- who has fired since the last mark, recorded on the record
    for i = 1, #recs do
      local r = recs[i]
      r[field] = (r.fired ~= (r.atMark or 0))
    end
  end
  local function mark()
    for i = 1, #recs do recs[i].atMark = recs[i].fired end
  end
  local function readAll()
    for i = 1, #recs do pcall(function() recs[i].item:name() end) end
  end

  hafen.timer():after(1, function()
    -- Every seeded icon's build window has closed. This read is the one under test: the client can now
    -- describe all of them, so it must describe nothing again and fire nothing.
    mark()
    readAll()
  end)

  hafen.timer():after(2, function()
    -- ---- A: a read of what is already described fires nothing --------------------------------
    local seen, bad = 0, {}
    for i = 1, #recs do
      local r = recs[i]
      if r.seeded and (r.firstFired ~= nil) then
        seen = seen + 1
        if r.fired ~= (r.atMark or 0) then bad[#bad + 1] = r end
      end
    end
    ok("a read of an icon the client can already describe fires nothing (" .. seen .. " icons)",
       (seen > 0) and (#bad == 0), #bad .. " of " .. seen .. " fired: " .. named(bad))

    -- ---- B: a font change is not a revision, and it takes two to say so ----------------------
    mark()
    hafen.ui():sheet():rule("window.title"):font(face):sheet():install()
    readAll()                                     -- force the rebuild the new font asks for, now

    hafen.timer():after(0.5, function()
      flag("inFont1")
      mark()
      hafen.ui():sheet():release()                -- the second font change, the opposite way
      readAll()
      ok("the sheet is given back", hafen.ui():sheet():info().installed == false,
         hafen.ui():sheet():info().installed)

      hafen.timer():after(0.5, function()
        flag("inFont2")
        mark()

        hafen.timer():after(1, function()         -- a quiet second: no font change at all
          flag("inQuiet")
          count()
          local bad = {}
          for i = 1, #recs do
            local r = recs[i]
            if r.inFont1 and r.inFont2 and not r.inQuiet then bad[#bad + 1] = r end
          end
          ok("a font change fires nothing on any icon (" .. held .. " held, " .. slots .. " slots)",
             #bad == 0, #bad .. " fired for both font changes: " .. named(bad))
        end)
      end)
    end)
  end)

  hafen.timer():after(11, function() sweeping = false end)

  hafen.timer():after(12, function()
    -- ---- C: once per build, over every icon whose build window has closed ---------------------
    local described, twice, drawn, shown = 0, {}, 0, {}
    for i = 1, #recs do
      local r = recs[i]
      if r.firstFired ~= nil then
        if r.firstFired == 1 then
          described = described + 1
          if drawnKind(r.kind) then drawn = drawn + 1; shown[#shown + 1] = r end
        elseif r.firstFired > 1 then
          twice[#twice + 1] = r
        end
      end
    end
    ok("described: " .. described .. " icons, each fired once (" .. drawn .. " drawn, "
         .. (described - drawn) .. " held)"
         .. ((drawn > 0) and (" -- " .. named(shown)) or ""),
       (described > 0) and (#twice == 0) and (drawn > 0),
       (#twice > 0) and (#twice .. " fired more than once: " .. named(twice))
         or ((described == 0) and "no icon needed describing"
                               or (drawn .. " of them drawn -- open a barter stand inside the window")))

    -- ---- D: a stale item takes a Sub and is inert, scored over what the run reached -----------
    local inert, why = 0, {}
    for i = 1, #stales do
      local st = stales[i]
      if st.sub == nil then why[#why + 1] = "no Sub"
      elseif st.sub:key() ~= "Changed" then why[#why + 1] = "key " .. tostring(st.sub:key())
      elseif st.fired ~= 0 then why[#why + 1] = st.kind .. " fired " .. st.fired
      elseif st.item:res() == nil then why[#why + 1] = st.kind .. " has no res"
      else inert = inert + 1 end
    end
    ok("a stale item: on(\"Changed\") is a Sub and never fires (" .. #stales .. " reached)",
       (#stales > 0) and (inert == #stales),
       (#stales == 0) and ("no icon left the tree -- the item role's Removed fired " .. removedSeen
                             .. " times, and no Item the run holds turned stale")
                       or (inert .. " of " .. #stales .. " inert; " .. table.concat(why, ", ")))

    for i = 1, #subs do
      if subs[i] ~= nil then pcall(function() subs[i]:off() end) end
    end
    say("[summary] " .. pass .. " pass, " .. fail .. " fail, " .. manual .. " manual")
  end)
end

hafen.console():on("t137.3", function() hafen.timer():after(0, run) end)
