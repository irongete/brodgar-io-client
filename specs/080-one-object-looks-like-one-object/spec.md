# 080 — one object looks like one object

## What and why

`079.3` decided that a gob is **one object**: `LuaGob` is keyed on the id alone, because gob ids are
the server's and `docs/client/multi-session.md` records one as *"one object observed by two
sessions"*. Every read on it answers the same whichever session computes it.

Its two **visual writes** did not follow, in both directions.

### The teardown reverts one session's worth

`UiApi.teardownGobOverlays` and `UiApi.teardownGobScales` each take `screen()` — the drawn session —
and walk `AddonManager.allGobs()`, the argumentless form that reads the drawn session's `OCache`.
So when an addon is unloaded by `:reload`, by a disable, or by the CPU watchdog, what it left on an
object **only a background character has loaded stays there**.

That matters more than a large tree, because of what the guarantee is holding up.
`teardownGobScales`'s own javadoc says it plainly:

> *"Nothing an addon that is no longer running left distorted stays distorted, **which is what makes a
> purely visual write on the game's own objects safe to leave unprotected**."*

The undo is the reason `gob:scale()` needs no permission. And the promise is written where an author
reads it: `api/gob.md` says *"a `:reload` or a disable puts back **everything** you resized, so
**nothing** is left distorted behind you"*, and `api/overlay.md` says the same for overlays. **The page
says everything and the engine means whatever the drawn character can see.**

### And the write itself is one-sided

Fixing only the sweep would leave the other half crooked. `gob:scale(k)` resolves one `Gob` and calls
`GobScale.apply(g, owner, k)`; `gob:overlay()` reaches `LuaGobOverlay.on(g)` the same way. A `Gob` is
per-`OCache`, so both land on **one session's copy** — and after `079.3` that copy is whichever
session happened to answer.

So today a gob is one object that **looks different depending on which character is looking at it**,
which is the incoherence `079.3` removed from every read and left in these two writes. This feature
finishes it: **a visual write on a gob applies to the object, and the undo undoes the object.**

### What the damage is, honestly

Bounded, and saying so is part of the decision. It is **cosmetic** — nothing is written to disk,
nothing reaches the server. It **self-heals**: the code's own comment records that *"a leftover scale
is visual only, and dies with the gob anyway"*, so the object comes back clean once it leaves view and
returns. And it **only appears with two or more sessions**.

What does not self-heal is a written sentence that is false.

## Acceptance criteria

1. `gob:scale(k)` and `gob:overlay()` apply to **every live session that holds that gob**, so one
   object is drawn the same whichever character is looking at it.
2. Unloading an addon — `:reload`, a disable, or the CPU watchdog's auto-disable — reverts every scale
   and removes every overlay it left, **in every live session**, not only the drawn one.
3. Each session's walk takes **that session's own monitor**, and **never two at once**:
   `docs/client/multi-session.md` records that the tick holds one UI monitor at a time and that the
   direction is the only one anything may take.
4. `AddonManager.allGobs()`'s **argumentless form does not exist**. Its two callers are the two
   methods this feature fixes, so once they take a session it has none — and deleting it is what stops
   the defect being reintroduced, as `072.3` and `078.4` deleted theirs.
5. `api/gob.md` and `api/overlay.md` say what is now true, and the sentence that made an unprotected
   visual write safe is true as written.
6. `ROADMAP.md` loses the one line this feature closes, and no other.
7. The guardrail holds: `grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/`
   is **212 before and 212 after**.

## Out of scope

- The **`Fonts`** subsystem.
- **The other 20 `ROADMAP.md` lines.** This feature takes one and leaves the rest untouched.

## Docs impact

**Written**: `api/gob.md` (the scale verb, and that the undo covers every session) · `api/overlay.md`
(the same for overlays) — the two pages carrying the promise this feature makes true.

**Derived set:**

```
grep -rniE "reload or a disable|left distorted|puts back|removes every overlay" docs/
```

Run and pasted by `080.1` before it revises anything. What it must catch, known today:
`api/gob.md`'s *"puts back everything you resized, so nothing is left distorted behind you"* and
`api/overlay.md`'s *"a `:reload` or a disable likewise removes every overlay you…"*. Both are true
after this feature and are not true today, so both are read rather than assumed.

**Guardrail:**

```
grep -rn "hafen\.\(timer\|event\|slash\|asset\|json\|http\|font\|client\)()" docs/
```

**212, and it must still be 212.** No namespace moves; this feature changes what two verbs reach.

## Context files

- `src/io/brodgar/addon/UiApi.java` — 1 (`teardownGobOverlays` ~2249, `teardownGobScales` ~2277)
- `src/io/brodgar/addon/AddonManager.java` — 1 (`allGobs()` ~4065 and `allGobs(String)` ~3938)
- `src/io/brodgar/addon/LuaGob.java` — 1 (`scale` ~350; `gob(self, …)` resolves one copy)
- `src/io/brodgar/addon/LuaOverlay.java` — 1 (`collection()`'s `addMember`/`removeMember`: where the
  overlay attach and removal actually are — `LuaGobOverlay` is the store they write into)
- `src/io/brodgar/addon/GobScale.java` — 1 (`apply`, `revert`, `value`)
- `src/io/brodgar/addon/LuaGobOverlay.java` — 1 (`on`, `removeOwner`, `prune`)
- `src/io/brodgar/session/Sessions.java` — 1 (the live sessions to walk)
- `docs/client/multi-session.md` — 1 (read: one monitor at a time, and the direction)
- `docs/addons/api/gob.md` — 1
- `docs/addons/api/overlay.md` — 1
- `docs/addons/api/event/bus.md` — 1 (when `GobOverlayRemoved` fires: a record on every copy makes that
  the LAST session's edge, as it already was for the game's own)
- `specs/ROADMAP.md` — 1
- `DOCUMENTATION.md` — 1
