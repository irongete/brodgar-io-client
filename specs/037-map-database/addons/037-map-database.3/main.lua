-- 037.3 — overlays: the recorded masks, and the client's display toggles as an owned HOLD.
-- Self-checking suite; see specs/testing/addon-suite.md.
--
-- The task has two halves that share only a word, and the suite's job is to prove they are different things:
--
--   * THE RECORDED MASKS. A grid on disk carries, beside its tiles and heights, a boolean mask per overlay
--     that covered that ground when the client wrote it down. grid:overlay():list() is the census of the
--     TAGS it carries and grid:overlay():get(tag) is the Mask. The tag space belongs to the server's overlay
--     RESOURCES, so an unknown tag here is plain nil — never an error.
--   * THE DISPLAY TOGGLES. hafen.map():overlay() is the four switches the client's own map menu owns, and
--     that set IS closed: a typo is refused, because a switch that silently did nothing forever is the one
--     failure nothing else would report. The two vocabularies are the trap the docs exist for — "prov" is
--     provinces in the WORLD, "realm" is provinces on the MAP, same feature, two engine tags.
--
--   * AND A HOLD IS NOT A SWITCH (D-097), which is why the toggle's verbs are t:hold()/t:release()/t:shown()
--     rather than a boolean write. MapView.oltags is a ref-counted multiset shared with the user's checkbox
--     and with the server's own flash; an addon can only add its +1 and take it away. So the take is
--     IDEMPOTENT (a second one would leave the count standing after a single release) and the release is
--     exactly one -1 — which is what the teardown has to do too, and what the reload check below proves.
--
-- IT PUTS EVERYTHING BACK: every hold it takes is released in the same run, against the value it read first.

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

-- A refusal is a check: the call must fail, and fail SAYING why.
local function refuses(what, fn, wantMsg)
  local ok, err = pcall(fn)
  err = ok and "<no error>" or (tostring(err):gsub("^.-%.lua:%d+:%s*", ""))
  check((not ok) and (err:find(wantMsg, 1, true) ~= nil), what, err)
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function summary()
  hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
end

local SWEEP = 2          -- half-width, in grids, of the neighbourhood searched for a recorded overlay
local CMAPS = 100        -- MCache.cmaps

-- The display toggle for one tag, and the two questions it answers separately: is it drawn at all, and do
-- WE hold it. Both go through the interned object, so a stashed one keeps working across a reload.
local function toggle(tag)
  return hafen.map():overlay():get(tag)
end

local function shown(tag)
  return toggle(tag):shown()
end

-- ---- the parked rounds: the visual check, and the proof that the TEARDOWN releases ------------------
local function holdRound()
  local c, r = shown("cplot"), shown("realm")
  if (c == nil) or (r == nil) then
    check(false, "the world view and the map window are both up (the two sides this task drives)",
          ("cplot=%s realm=%s"):format(tostring(c), tostring(r)))
    return summary()
  end
  local stock = hafen.store():get("stock")   -- the LIVE persisted table: write THROUGH it, never replace it
  stock.cplot, stock.realm = c, r
  hafen.store():flush()
  toggle("cplot"):hold()
  toggle("realm"):hold()
  manualCheck("open the map window and look at the world, then run ':reload' and ':t037-3 after'"
              .. " (or ':t037-3 drop' to put them back without reloading)",
              "personal claims are drawn on the ground in the world AND provinces are drawn on the map;"
              .. " after the reload ':t037-3 after' prints 2 pass -- the teardown released both holds")
  summary()
end

-- Assert both toggles are back where holdRound found them. Runs after a :reload (the teardown released) or
-- after ':t037-3 drop' (the API released) -- the same assertion either way, which is the point.
local function backRound(how)
  local s = hafen.store():get("stock")
  if not (s and (type(s.cplot) == "boolean")) then
    check(false, "':t037-3 hold' ran first and stored the stock toggle values",
          "nothing in the store -- run ':t037-3 hold' first")
    return summary()
  end
  check(shown("cplot") == s.cplot,
        "the personal-claim overlay is back where it was before the hold (" .. how .. ")",
        tostring(shown("cplot")) .. " vs stock " .. tostring(s.cplot))
  check(shown("realm") == s.realm,
        "...and so is the province overlay on the map, released on the other side entirely (" .. how .. ")",
        tostring(shown("realm")) .. " vs stock " .. tostring(s.realm))
  summary()
end

local function dropRound()
  toggle("cplot"):release()
  toggle("realm"):release()
  backRound("released by the API")
end

-- ---- the main run -----------------------------------------------------------------------------------
local function run(args)
  local arg = args and args[1]
  if arg == "hold" then return holdRound() end
  if arg == "drop" then return dropRound() end
  if arg == "after" then return backRound("released by the teardown") end
  pass, fail, manual = 0, 0, 0

  local me = hafen.player() and hafen.player():gob()
  local p = me and me:position()
  local gp = p and p:info()
  local seg = hafen.map():segment():current()
  if (not p) or (not seg) or (not gp) then
    check(false, "the player and the map database are both up (every check below stands on them)",
          (not p) and "no player gob" or "no segment/anchor yet -- the map DB streams in a beat after login")
    return summary()
  end

  -- 1. the census: the client owns exactly these switches, and the two vocabularies sit on two sides
  local ovs = hafen.map():overlay():list()
  local cplot, vlg, prov, realm = toggle("cplot"), toggle("vlg"), toggle("prov"), toggle("realm")
  check((#ovs == 4) and (toggle("cplot") == cplot)
          and (cplot:where() == "world") and (vlg:where() == "world") and (prov:where() == "world")
          and (realm:where() == "map")
          and (type(cplot:shown()) == "boolean") and (type(realm:shown()) == "boolean"),
        "the client's four overlay toggles enumerate as interned objects -- three in the WORLD, 'realm' on"
        .. " the MAP",
        ("%d rows; prov=%s realm=%s"):format(#ovs, prov:where(), realm:where()))

  -- 2. the round trip: a hold is taken, the overlay is displayed, the hold is released, stock is back
  local stock = cplot:shown()
  cplot:hold()
  local on, heldWhile = cplot:shown(), cplot:held()
  cplot:release()
  check((on == true) and (heldWhile == true) and (cplot:shown() == stock) and (cplot:held() == false),
        "t:hold() takes a hold, the overlay is displayed, t:release() puts it back to stock",
        ("stock=%s held=%s after=%s"):format(tostring(stock), tostring(heldWhile), tostring(cplot:shown())))

  -- 3. the hold is IDEMPOTENT, which is what makes one release -- the teardown's -- exactly enough. If a
  --    second take added a second count, the release below would leave the overlay standing.
  cplot:hold():hold():release()
  check((cplot:shown() == stock) and (cplot:held() == false),
        "a second take adds no second count, so ONE release is the whole undo (the ref count's own rule)",
        ("after two takes and one release: %s vs stock %s"):format(tostring(cplot:shown()), tostring(stock)))

  -- 4. ...and a release with nothing held never decrements somebody else's count
  cplot:release()
  check((cplot:shown() == stock) and (cplot:held() == false),
        "a release with no hold is a no-op, not a -1 on the client's own count",
        tostring(cplot:shown()))

  -- 5. THE TWO VOCABULARIES: 'prov' is the world's provinces and 'realm' is the map's, and neither write
  --    can be seen from the other side
  local pStock, rStock = prov:shown(), realm:shown()
  prov:hold()
  local rWhileProv = realm:shown()
  prov:release()
  realm:hold()
  local pWhileRealm = prov:shown()
  realm:release()
  check((rWhileProv == rStock) and (pWhileRealm == pStock)
          and (prov:shown() == pStock) and (realm:shown() == rStock),
        "'prov' reaches the world alone and 'realm' the map alone -- one feature, two engine tags",
        ("realm while prov held=%s, prov while realm held=%s"):format(tostring(rWhileProv),
                                                                      tostring(pWhileRealm)))

  -- 6./7. the two refusals that guard the closed set
  refuses("a toggle the client does not own is refused, naming the ones it does",
          function() return hafen.map():overlay():get("claims") end, "the toggles it owns")
  refuses("...and so is a display tag passed as a number, which no switch is named by",
          function() return hafen.map():overlay():get(3) end, "tag is one of")

  -- 8+. the RECORDED masks, staged: an overlay's tags live in its own resource, so the first sweep may be
  --     kicking those loads. (035.2: wait FIRST, then judge.)
  local sc = hafen.map():grid():get(gp.gridId)
  sc = sc and sc:segmentCoord()
  hafen.timer():after(1.5, function()
    local here = hafen.map():grid():get(gp.gridId)
    local tags = here and here:overlay():list()
    check((tags ~= nil) and (type(tags) == "table"),
          "grid:overlay():list() answers the mask census of the ground under the player",
          (tags == nil) and "nil -- the grid or an overlay resource is still loading" or ("%d tag(s)"):format(#tags))

    -- find a grid nearby that actually recorded an overlay; the player may simply be in the wilderness
    local found, ftag
    for _, cand in ipairs(seg:grid():list{ x = sc.x - SWEEP, y = sc.y - SWEEP,
                                           w = (SWEEP * 2) + 1, h = (SWEEP * 2) + 1 }) do
      local ts = cand:overlay():list()
      if #ts > 0 then found, ftag = cand, ts[1]:tag() break end
    end

    if found then
      local mask = found:overlay():get(ftag)
      check((mask ~= nil) and (mask == found:overlay():get(ftag)) and (mask:tag() == ftag)
              and (mask:grid() == found),
            "grid:overlay():get(\"" .. tostring(ftag) .. "\") is an interned Mask naming its own tag and grid",
            (mask == nil) and "nil for a tag the census just listed" or tostring(mask))
      local n, area = mask:count(), mask:area()
      local inBox, edges = 0, 0
      if area then
        for y = area.y, (area.y + area.h) - 1 do
          for x = area.x, (area.x + area.w) - 1 do
            if mask:covers{ x = x, y = y } then inBox = inBox + 1 end
          end
        end
        -- a bounding box is only a bounding box if it is TIGHT: each of its four edges carries a tile
        for x = area.x, (area.x + area.w) - 1 do
          if mask:covers{ x = x, y = area.y } then edges = edges + 1 break end
        end
        for x = area.x, (area.x + area.w) - 1 do
          if mask:covers{ x = x, y = (area.y + area.h) - 1 } then edges = edges + 1 break end
        end
        for y = area.y, (area.y + area.h) - 1 do
          if mask:covers{ x = area.x, y = y } then edges = edges + 1 break end
        end
        for y = area.y, (area.y + area.h) - 1 do
          if mask:covers{ x = (area.x + area.w) - 1, y = y } then edges = edges + 1 break end
        end
      end
      check((n ~= nil) and (n > 0) and (area ~= nil) and (inBox == n) and (edges == 4),
            "every tile the mask covers is inside its own bounding box, and the box is tight on all four sides",
            ("count=%s inBox=%d edges=%d area=%s"):format(tostring(n), inBox, edges,
              area and ("%d,%d %dx%d"):format(area.x, area.y, area.w, area.h) or "nil"))
      check(found:overlay():get("no-such-overlay-tag") == nil,
            "a tag the grid does not carry is plain nil -- the tag space belongs to the server's resources,"
            .. " deliberately unlike the display toggles' closed set",
            tostring(found:overlay():get("no-such-overlay-tag")))
      refuses("a Mask reads WITHIN-grid tile coords, like the grid it came from",
              function() return mask:covers{ x = CMAPS, y = 0 } end, "WITHIN-grid tile coord")
    else
      manualCheck("stand next to a personal claim or inside a village and run ':t037-3' again",
                  "no grid within " .. SWEEP .. " of the player recorded any overlay, so the four mask"
                  .. " checks were skipped; beside a claim they run and pass")
    end

    manualCheck("run ':t037-3 hold', look at the world and the map, then ':reload' and ':t037-3 after'",
                "claims drawn on the ground and provinces drawn on the map while held; then ':t037-3 after'"
                .. " prints 2 pass -- the teardown released both, on both sides")
    summary()
  end)
end

hafen.slash():register("t037-3", run)   -- the only way in: a suite does not start itself
