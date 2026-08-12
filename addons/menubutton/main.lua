-- menubutton — an entry of your own in the action menu.
--
-- Everything this addon knows about is the MENU. Drag the button onto the action bar and it stays
-- there across restarts: the slot is the client's own record, not this addon's, so there is nothing
-- here to save it with and nothing here to put it back.

local count = 0

hafen.event():on("EnterWorld", function()
  local bell = hafen.menugrid():add("bell")
                               :name("Ring the bell")
                               :tooltip("Counts how many times you have pressed it")
                               :icon(hafen.asset():get("bell.png"))

  bell:on("use", function(pag)
    count = count + 1
    hafen.log():write(pag:name() .. ": " .. count)
  end)
end)
