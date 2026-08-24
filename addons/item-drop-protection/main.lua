-- With an item on the cursor the map's two gestures swap:
--     left click          walks     (the client would have dropped it)
--     Ctrl + left click   drops it  (the client would have walked)

hafen.event():action():on("drop", function(event)
  local map = event:widget()
  if map:type() ~= "MapView" then return end
  event:preventDefault()
  map:session():player():move(event:position(2))
end)

hafen.event():action():on("click", function(ev)
  local map = ev:widget()
  if map:type() ~= "MapView" then return end
  local press, where, button, mods = table.unpack(ev:args())
  if button ~= 1 then return end
  if not map:session():player():hand() then return end
  ev:preventDefault()
  map:send("drop", press, where, mods)
end)
