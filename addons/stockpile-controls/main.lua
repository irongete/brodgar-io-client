-- Stockpile controls -- every window titled "Stockpile" gets a row below the pile: an amount, and a Take
-- button that draws that many items out of it.
--
-- The two controls are built with `:parent(window)` INTO the client's own frame and `window:pack()`
-- brings that frame down around them, so the window ends up one row taller and everything it already
-- showed stays put (docs/addons/api/ui/edit.md). Both come off again with the addon.
--
-- What is waited for is `window[title=Stockpile] @ISBox` -- the pile's own box, not the window -- because
-- a window is hung before its contents arrive and its caption can land later still, and a chain fires
-- once both are true (docs/addons/api/ui/replace.md).
--
-- Take sends `xfer`, which is the shift-click's message (src/haven/ISBox.java): it moves one item from
-- the pile into the backpack and carries no argument at all, so unlike `xfer2` -- the wheel's, with a
-- signed notch -- there is no direction to get wrong. N items is N of it, spaced on a timer.

local GAP            = 4     -- design px between the pile's box and the row, and between the two controls
local FIELD_WIDTH    = 48    -- the amount field; the button takes the rest of the box's width
local INTERVAL       = 0.05  -- seconds between two withdrawals
local MOST_PER_PRESS = 500   -- the most one press will ever ask for, whatever was typed

-- [account] = the subscription standing on that character's tree.
local watchByAccount = {}

-- One item, then the next, until the count runs out or the window closes under it.
local function withdrawNext(pileBox, remaining)
  if (remaining <= 0) or not pileBox:exists() then return end
  local sent, failure = pcall(function() pileBox:send("xfer") end)
  if not sent then
    hafen.log():write("stockpile-controls: " .. tostring(failure))
    return
  end
  hafen.timer():after(INTERVAL, function() withdrawNext(pileBox, remaining - 1) end)
end

local function addControls(pileBox)
  local window = pileBox:parent()
  if not (window and window:is("window")) then return end
  -- The button's own name is the record: a second subscription rescans the tree as it registers, and a
  -- window that already carries a row is one this has nothing to do to.
  if window:matchAll("[name=stockpile-controls/take]")[1] then return end

  local place, box = pileBox:position(), pileBox:size()
  local rowY = place.y + box.h + GAP

  local amountField = hafen.ui():entry():parent(window):size(FIELD_WIDTH):value("1")
    :tooltip("how many items to take out of this pile")
  local takeButton = hafen.ui():button():parent(window):size(box.w - FIELD_WIDTH - GAP)
    :name("take"):text("Take"):tooltip("take that many items out of this pile")

  -- The two controls are their own art's height, and the shorter one rides down to meet the taller.
  local lift = math.floor((takeButton:size().h - amountField:size().h) / 2)
  amountField:position(place.x, rowY + math.max(0, lift))
  takeButton:position(place.x + FIELD_WIDTH + GAP, rowY + math.max(0, -lift))
  window:pack()

  local function take()
    local amount = math.floor(tonumber(amountField:value() or "") or 0)
    if amount < 1 then
      hafen.log():write("stockpile-controls: type how many items to take out first")
      return
    end
    withdrawNext(pileBox, math.min(amount, MOST_PER_PRESS))
  end

  takeButton:on("Pressed", take)
  amountField:on("Submitted", take)
end

-- A widget subscription watches ONE character's tree, so every login needs one. SessionEnteredWorld
-- fires again for the same session when it picks another character, hence the replace rather than a
-- second one.
local function watchStockpiles(session)
  local previous = watchByAccount[session:user()]
  if previous then previous:off() end
  watchByAccount[session:user()] =
    session:ui():on("window[title=Stockpile] @ISBox", "Added", addControls)
end

hafen.event():on("SessionEnteredWorld", watchStockpiles)
hafen.event():on("SessionRemoved", function(session) watchByAccount[session:user()] = nil end)
