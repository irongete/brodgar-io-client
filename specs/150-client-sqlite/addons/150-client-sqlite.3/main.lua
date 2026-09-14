-- 150.3 — the placements live in the client's file. Self-checking suite.
--
-- Two shapes of run, told apart by what the name "p" holds. A PLACING run remembers a window under "p" at a
-- mark of its own, destroys it, and proves a second window remembered under "p" answers that mark; it leaves
-- that window standing, so a title-bar drag and a :reload can be observed to bring it back where it was
-- dropped. A RELEASING run is any run that finds "p" holding a place that is neither the stock one nor the
-- mark — the dropped one, read out of the client's file by the fresh addon — and it reports that, forgets
-- the name and leaves the window standing to be looked at (it is remembered under nothing, so closing it
-- writes nothing).

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
local function refuses(what, fn, wantMsg, notMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
  local said = (not ok) and (err:find(wantMsg, 1, true) ~= nil)
  if said and notMsg then said = err:find(notMsg, 1, true) == nil end
  check(said, what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local MARK_X, MARK_Y = 248, 172        -- where the placing run puts the window: not the stock place

local function xy(p)
  return p and (p.x .. "," .. p.y) or "nil"
end

local function same(p, x, y)
  return p and (p.x == x) and (p.y == y)
end

-- The name of a table in the addon's OWN file, read through a statement with the name bound as ?, so the
-- statement's text carries no hafen_ word for the scan to refuse.
local function tableCount(name)
  local rows = hafen.store():query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", name)
  return #rows
end

local standing                          -- the window a placing run left, until the next run of this load

local function window(title)
  return hafen.ui():window():title(title):size(160, 48)
end

local function run()
  pass, fail, manual = 0, 0, 0          -- one verdict per run: the maintainer runs it more than once
  if standing then                      -- a second run of the same load: start over
    standing:destroy()
    standing = nil
  end

  -- What "p" holds, read by remembering a fresh window under it: the stock place when it holds nothing,
  -- the mark after a placing run, and the dropped place after a drag and a :reload.
  local probe = window("150.3")
  local stock = probe:position()
  probe:remember("p")
  local held = probe:position()

  if held and not same(held, stock.x, stock.y) and not same(held, MARK_X, MARK_Y) then
    -- A releasing run.
    check(true, "the window came back from the client's file where it was dropped (" .. xy(held) .. ")")
    probe:remember(nil)                 -- the record goes; the window stays, to be looked at
    probe:title("150.3 — back where you dropped it; close me by the X")
    standing = probe
    manualCheck("look at the window (close it by its X when done)",
                "it stands where you dropped it before the :reload")
    summary()
    return
  end

  -- A placing run.
  probe:position(MARK_X, MARK_Y)
  eq("the window is at the mark", xy(probe:position()), MARK_X .. "," .. MARK_Y)
  probe:destroy()                       -- where it stood goes into the client's file
  local w = window("150.3 — drag me by the title bar")
  w:remember("p")
  eq("a second window remembered under the name answers the record", xy(w:position()),
     MARK_X .. "," .. MARK_Y)
  eq("w:remember() reads the name", w:remember(), "p")

  eq("the addon's own file lists no hafen_placements", tableCount("hafen_placements"), 0)
  eq("...and no hafen_holds", tableCount("hafen_holds"), 0)
  eq("...and one hafen_documents", tableCount("hafen_documents"), 1)
  refuses("a statement naming a hafen_ table is refused naming hafen_documents alone",
          function() hafen.store():exec("DELETE FROM hafen_x") end, "hafen_documents", "hafen_placements")
  refuses("a declaration under the hafen_ prefix is refused naming hafen_documents alone",
          function() hafen.store():table("hafen_x") end, "hafen_documents", "hafen_placements")

  standing = w
  manualCheck("drag the window by its title bar, :reload, :t150",
              "[pass] the window came back from the client's file where it was dropped (x,y)"
              .. " (that run forgets the name and leaves the window to be looked at)")
  manualCheck("quit the client, ls savedata/",
              "<id>/ folders and client.sqlite (its -wal/-shm while the client runs), nothing else")
  summary()
end

hafen.console():on("t150", function() hafen.timer():after(0, run) end)   -- the only way in: a suite does
                                                                          -- not start itself; deferred off
                                                                          -- the typed tree's monitor
