# 076 — tasks

Five tasks: the address, the events, the two namespaces onto it, the sweep the cut charges, and
`move`'s reach.

Read `spec.md`, `plan.md` and `specs/075-globals-without-a-session/namespaces.md` first. Every file a
task may open is in `spec.md` under **Context files**, tagged with the task that needs it. The derived
set goes to **0** in `076.4`; the never-move set stays at **211** throughout.

---

- [x] **076.1 — The session is a thing you can name.**
      Adds `LuaSession` — a ref wrapping the **account name** and nothing else, re-resolved through
      `Sessions.members()`, interned per addon — and `hafen.session()`, the collection over them:
      `:list(filter)`, `:count(filter)`, `:find(filter)`, `:get(user)` and `:current()`, which is
      `Sessions.anchormember()` and `nil` on the login screen. The object answers `:user()`,
      `:character()` (that session's own `GameUI.chrid`, not the name `:session add` asked for),
      `:exists()` and `:info()`. Nothing moves onto it here. Writes `api/session.md`, its
      `api/README.md` line and the door in `conventions.md`.
      *Its suite* asserts the collection is one object across calls, `:count()` equals `#:list()`, and
      `:get(u) == :get(u)` is the same ref. That `:current():character()` equals `hafen.player():name()`
      is the check that matters: two doors onto the drawn character agreeing is what says the address
      resolves. It `pcall`s `#hafen.session()` and asserts `:list()` is named, and `:get()` with no key
      and asserts the account is.
      `[manual]`: with two sessions up, tab and re-run. Expect: `:count()` is 2 both times, and
      `:current():user()` is the account tabbed to.
      `[manual]`: `:session drop` one and re-run. Expect: its ref still names the account, dead.

- [x] **076.2 — The four session events carry the session.**
      The four hand a Session instead of an account string — minted per addon at fire time, the way
      `AddonManager.fireGob` mints a Gob. A hard cut: the string payload is gone, and the one `SessionDestroyed` carries
      reports `:exists() == false` while still naming its account. Revises `api/event/bus.md`: the
      payload, and keying your own tables by `s:user()`, which survives the session.
      *Its suite* subscribes to all four and scores over a bounded window: on whatever arrives, the
      payload is **not** a string and `:user()` answers — and for a `SessionDestroyed`, `:exists()`
      is false while `:user()` still answers. A misspelt session key is refused naming the four.
      `[manual]`: tab to another character while it is armed. Expect: one `SessionSelected` line
      naming that account, and nothing else.
      `[manual]`: `:session drop` one. Expect: a `SessionDestroyed` line naming it, dead.

- [ ] **076.3 — The world and the character belong to a session.**
      `session:world()` and `session:player()` — the same two surfaces, answering for **that**
      session: `WorldApi.installWorld` and `CharApi.installPlayer` stop reading `screen()` and read
      the session they hang on, minted once per *(addon, session)* on the interned `LuaSession` so
      that `s:world() == s:world()` and a draw callback allocates nothing. A `LuaGob`
      carries its session beside its id and `Addon.gobs` keys on the pair, so a read taken on the
      session you named is about that character; `gob:position()` derives its durable anchor through
      its own session's `MCache`, handing back a session-free Position. `hafen.world` and
      `hafen.player` are **hard-cut** into `Retired` naming their replacement, and the closed
      `__index` moves with the Player object. Publishes `api/world.md`, `api/player.md`,
      `api/gob.md`'s identity section and the rest of `api/session.md`.
      *Its suite* asserts `s:world() == s:world()` and `s:world():gob() == s:world():gob()`, which is
      the allocation claim as much as the identity one; that
      `s:player():gob() == s:world():gob():get(that id)`; and that `:position():info()` names a grid.
      It `pcall`s `hafen.world()`, `hafen.player()` and `hafen.player():name()`, asserting each
      refuses **naming `hafen.session()`**.
      `[manual]`: with the two apart, read the other one's `:player():gob():position():info()`.
      Expect: the grid **that** character stands on, not yours.

- [ ] **076.4 — No page teaches an address that is gone.**
      The sweep the cut in `076.3` charges: every page in `spec.md`'s derived set re-spelled through
      `hafen.session():current()` or `:get(user)`, whichever the passage means — and a passage that
      meant *the character on screen* says so rather than implying it. Re-points every link the
      re-spelling moves and runs `DOCUMENTATION.md` §11 over them. The proof is the two counts: the
      derived set to **0**, the never-move set still **211**.
      *Its suite* runs the canonical chain each swept page now teaches — section, collection, object,
      verb — and asserts none throws, which is the docs and the engine agreeing about the spelling.
      It `pcall`s the two retired sections and asserts they still refuse, so the sweep cannot have
      quietly put one back.
      `[manual]`: none — every claim here is a grep or a call.

- [ ] **076.5 — Walking a character you are not looking at.**
      `session:player():move(p)` reaches a session that is not drawn: the `player.move` permission
      unchanged, the Position resolved against **that** session's map, the click sent through **that**
      session's `MapView`. A background session sends through `UI.rawWdgmsg` and the drawn one through
      `UI.wdgmsg`, the line `Sessions.send` already draws — so action hooks see an order to the
      character on screen and not one behind it. Revises `api/player.md`.
      *Its suite* declares `player.move` and asserts the consent gate held. It `pcall`s `move` with a
      Position the addressed character cannot locate and asserts the refusal names it unlocatable
      **for that character**, and with a plain `{x, y}` table and asserts the Position type is named.
      It arms an action hook and asserts an order to `:current()` reaches it.
      `[manual]`: with the two together, order the **other** one to your own Position, then tab to
      it. Expect: it walked there.
      `[manual]`: order the other one with the hook armed. Expect: it did **not** fire.
