# hafen.event: your addon and the sessions

Your addon being loaded, ticked and disabled, and a character connecting, reaching the world, taking the
screen and ending. These are the keys you subscribe to before there is anything to read — the ones that
tell you when the rest of the API has something in it. Everything here is part of
[the catalogue](README.md), so `hafen.event():on(key, fn)` is the door.

## Lifecycle

Your addon's own three, and each is about the addon rather than about a character.

| Event | Payload | Fires |
|---|---|---|
| `Load` | — | once for the client, when the addon is loaded, before any character exists |
| `Update` | `dt` (number) | every frame; `dt` is seconds since the last frame |
| `Disable` | — | once for the client, when the addon is disabled or reloaded, or the client closes |

`Load` and `Disable` fire **once each for the client**, whatever happens to the characters underneath —
one addon, one Lua state, however many sessions are logged in. Keep `Update` handlers cheap: they run on
the [step](../../threading.md) on every frame, once per frame, and not once per session.

## Sessions

A character logging in, reaching the world, taking the screen and ending are four different moments, and
each is a session's rather than your addon's. The payload is that [`Session`](../../session.md) — the
address every read your handler goes on to make is named by, and the one thing that says which character
the moment was about.

| Event | Payload | Fires |
|---|---|---|
| `SessionAdded` | [`Session`](../../session.md) | a session connects, before it has a character or a world |
| `SessionEnteredWorld` | [`Session`](../../session.md) | ...and its HUD is up, so that character can be read |
| `SessionSelected` | [`Session`](../../session.md) | the screen changed to this session |
| `SessionRemoved` | [`Session`](../../session.md) | this session ended, however it ended |

```lua
hafen.event():on("SessionEnteredWorld", function(s)
  hafen.log():write(s:user() .. " is playing " .. (s:character() or "nobody yet"))
end)
```

**A `SessionRemoved` names a session that is already gone.** `s:user()` answers there — the account name
is the whole of a `Session`, so there is nothing left to resolve — while `s:exists()` is `false` and
everything else about that login reads `nil`. That is what makes the payload usable as the key you drop
your own tables by, on the one event where the login it names has already gone.

`SessionEnteredWorld` fires once the HUD exists — the [action menu](../../menugrid.md) included, so the
entries your addon adds go in from there — but much character-sheet data streams in for a few seconds
afterwards, see [missing data returns nil](../../conventions.md#missing-data-returns-nil). When that
session is the one on screen it is also the point from which its
[per-character saved variables](../../store.md) read back; a character reaching the world behind another
brings theirs to the tables when you tab to them, which is a `SessionSelected`.

**Taking the screen is not entering the world.** Going between two characters already in the world
fires `SessionSelected` and nothing else, once per change — whether the player tabbed or an addon
wrote the screen with [`hafen.session():current(s)`](../../session.md#write-unprotected) — and only on a
change, so naming the session already drawn fires nothing at all. Ending the session **on screen** hands
the screen to another one, so that session's `SessionRemoved` comes first and a `SessionSelected` for
the one taking over follows it. Going to the login screen selects nothing, so it fires nothing — whether it
is where dropping your last session left you, where
[`hafen.session():current(nil)`](../../session.md#hafensessioncurrentnil) put you with every login still
running, or a login the player performed there. This family's payload **is** a session, and none was
picked; [`hafen.session():current()`](../../session.md), which reads `nil` there, is what answers instead.

**Nor is entering the world being looked at.** A session that reaches the world while another holds the
screen fires `SessionEnteredWorld` there and then, without ever having been drawn — these four are about
sessions, and only `SessionSelected` is about the screen. So a handler runs for a character you are not
looking at, and it reads that character through the `Session` it was handed rather than through
[`hafen.session():current()`](../../session.md), which is whoever holds the screen at that moment and need
not be the one the event was about.

**A session is the account, and one account plays one character at a time.** Picking another character on
the same account keeps that session alive — the server hands it a new world rather than ending it — so
`SessionEnteredWorld` fires a second time for the same `Session`, with no `SessionRemoved` between. Key
your own tables by `s:user()` if what you are tracking is the account, and rebuild whatever was that
character's on every `SessionEnteredWorld` for it.

**These report changes, not the state.** They fire for what happens after you subscribe, so an addon
loaded while three characters are up hears about none of the three; a `:reload` in the world re-announces
the session **on screen** with `SessionEnteredWorld`, because that is the one whose per-character saved
variables were just put back, and says nothing about the others.

> **Your state survives a character switch.** Nothing of yours is torn down or rebuilt when the screen
> moves, so a widget handle, a Gob or an [item](../../ui/items.md) you kept from one character is still in
> your table under the next one — and still belongs to the character it came from. `SessionRemoved` is
> where you drop what belonged to that session, and its payload is the key to drop it by.

## See also

- [the catalogue](README.md) — the other families, and whose character an event was
- [`hafen.session`](../../session.md) — the payload these four hand you, and the collection of the rest
- [when your code runs](../../../runtime.md) — the whole life of an addon, of which these are the moments
- [saved data](../../store.md) — what is read back at `SessionEnteredWorld`, and when it is written
- [events and timers](../../../guides/events-and-timers.md) — the guide that puts a handler to work
