# 124 — A reload announces every login

## What & why

`:reload` tears down every addon subscription there is, then re-announces `SessionEnteredWorld` for the
session **on screen** and for no other. Every other login that is in the world stands there with nothing
subscribed to it and nothing to tell an addon it is there, until that character is switched — which may
be hours, or never.

So an addon that holds anything per login has to hand-write a catch-up loop over
`hafen.session():list()` in its file body or its `Load`. Eight of the addons under `addons/` carry one.
An addon that omits it **passes single-login testing** — with one login, that session *is* the screen's
— and then silently does nothing for the background logins of anyone running two.

The boundary is recorded in `AddonRegistry.reload()`'s own javadoc as deliberate: announcing another
session "would be announcing one that did not enter anything". That argument does not distinguish the
two cases. The screen's session had not entered anything either — the gate the code uses is that its
HUD already exists — and the same javadoc calls the fire "the WoW `PLAYER_LOGIN` analog … so addons
re-initialize as if freshly logged in". The event is already being used to mean *re-initialize for this
session*, and that meaning is being withheld from sessions on the strength of which one the player
happened to be looking at, which is not a property of a session at all.

The reload becomes uniform: every session that is in the world is announced, the screen's first. The
catch-up loop stops being something an author must know about, and comes out of the eight addons that
carry one.

Verified against `src/` while planning, and not to be re-derived:

- **Nothing needs preparing first.** `StoreApi.detach()` clears `SessionState.charStores` but not
  `charScope`, and a `SessionState` outlives the addon layer; `StoreApi.charStore` mints on first ask
  and calls `loadChar` whenever `charScope` is set, so a background character's saved variables are read
  in when an addon asks. `StoreApi.enterWorld` is per session — only the `rescope()` inside it is
  screen-scoped, and that is idempotent.
- **`BeltHold.restore` is correctly absent from the reload path** — `beltPlaced` lives on `SessionState`
  and survives, and the bar restore is driven by `AddonPagina` being re-added. Do not add it.
- **The ordinary path already fires for background sessions** (per-session tick, per-session
  `enterWorldPending`), so no new kind of handler exists after this.

## Acceptance criteria

1. After a `:reload` with two or more characters in the world, an addon whose only door is
   `hafen.event():on("SessionEnteredWorld", fn)` receives one announcement for **every** one of them.
2. The screen's session is announced **first**, so a single login is announced exactly as it is today.
3. No session is announced twice by one reload, and a session that is not in the world is not announced.
4. None of the eight swept addons contains a catch-up loop, and each still initializes for a background
   login after a reload.
5. Every page that states the old boundary states the new one, in present tense, with nothing anywhere
   under `docs/` addressed to a reader who knew the old one.

## Out of scope

- **The four session keys' subscribe-time behaviour.** A subscription made mid-life still hears only
  what happens after it; `hafen.session():list()` remains the read for what is already there, and stays
  documented as such. This feature changes what a **reload** announces and nothing else.
- **`:reload <id>` and enable-without-reload** (`specs/ROADMAP.md`, *Lifecycle conveniences*). A
  per-addon reload would have to answer the same question for one addon; it is a separate surface.

## Docs impact

Derived by grepping the three claims rather than the event's name — 28 pages mention
`SessionEnteredWorld` and almost all merely subscribe to it.

```
grep -rn "re-announce\|again on a \`:reload\`\|for the character on screen" docs/
grep -rn "Once per session\|once per session" docs/
grep -rn "report changes\|already there\|hears about none" docs/
```

**Revised** — six passages, five files: `api/event/bus/lifecycle.md:78-80` (the boundary, and *These
report changes, not the state*) · `runtime.md:68` (the moments table calls the event **Once per
session**, which contradicts `lifecycle.md:74` — it fires again on a character switch — and the reload
itself) · `runtime.md:229-232` (*What a reload keeps, and what it drops*) · `guides/events-and-timers.md:49`
· `getting-started.md:74` · `api/session.md:248-255` (*Sessions that come and go*, and its example).

**Discharged** — matched a grep, different subject: `api/chat.md:240` (the same *report changes rather
than state* sentence, about channels) · `api/console.md:95`, `api/player.md:67`, `api/threading.md:15`,
`guides/hotkeys-and-commands.md:114`, `api/session.md:151` and `:222`, `runtime.md:206` (all iterate the
logins for their own reason, none is catch-up) · `api/position.md:47`, `api/ui/widget.md:29`,
`api/event/bus/lifecycle.md:20` (*on screen* / *once per* about other subjects).

**No `docs/client/` page is owed.** Everything read for this feature is `src/io/brodgar/addon/**`, which
that subtree does not document.

## Context files

- `src/io/brodgar/addon/AddonRegistry.java` — 1
- `src/io/brodgar/addon/AddonManager.java` — 1
- `src/io/brodgar/addon/StoreApi.java` — 1
- `addons/actionbars/main.lua`, `addons/autodrop/main.lua`, `addons/clickpath/main.lua`,
  `addons/item-indicators/main.lua`, `addons/simple-chat/main.lua`, `addons/simple-minimap/main.lua`,
  `addons/water-meter/main.lua`, `addons/stockpile-controls/main.lua` — 1
- `docs/addons/api/event/bus/lifecycle.md`, `docs/addons/runtime.md`,
  `docs/addons/guides/events-and-timers.md`, `docs/addons/getting-started.md`,
  `docs/addons/api/session.md` — 2
- `specs/124-a-reload-announces-every-login/addons/124-a-reload-announces-every-login.1/main.lua` — 2
  (this feature's own, archived by 124.1: the load-time record and the four checks 124.2's suite duplicates)
- `DOCUMENTATION.md` — 2
