# 095 — http is a builder

Discharges: A-115, A-116, A-117, A-118.

All four are rows in `audit/INVENTORY.md`'s own 095 block, and they are the **last four of the sweep**: with
them the open count is **0 of 122**.

## What and why

`CLAUDE.md` says a **builder is constructed bare and configured by chained setters**, and `HttpApi`'s own
javadoc claimed compliance — *"A request is constructed bare and configured by chained setters on the object
it hands back"*. It was not. `hafen.http():get(url, cb)` took the URL **and the completion handler**
positionally, and the request was already scheduled — it went out on the next tick — before the first setter
ran.

Compare the builders the API actually has: `hafen.ui():window()` takes nothing;
`hafen.vr():sprite():add(image)` then `:position(p)`; `gob:overlay():add(key)` then `:text(…)`;
`font:derive()` then `:size(11)`. Every one of them is bare.

The cost was a **lifetime rule no other builder carries**. `req:header(…)` had to be chained in the same
statement, because the tick was already coming; a user who stored the request and configured it from a later
callback was refused. That rule existed only because construction and dispatch were the same call.

And the payload was **the one callback result in the API that is not an object**. `ev:x()`, `ev:args()`,
`gob:name()`, `w:title()` are colon verbs; `res.status` was a bare table field, so it taught the dot habit —
which is a trap everywhere else, because a field read on a closed type hands back the **method**:
`if gob.name then` is always true, and `item.quantity > 5` fails as "attempt to compare function with number"
a line from the mistake.

Finally, the requests in flight are a **bounded set with the hardest cap in the API** — 6 running, 64 pending,
past which the call raises — and it was the one bounded set you could not look at. `hafen.timer()`,
`hafen.sound()`, `hafen.asset()` and `hafen.vr():ghost()` all answer *what of mine is live*.

## What shipped

```lua
hafen.http():request(url)
  :method("POST"):body(t)
  :header("Authorization", tok):timeout(5000)
  :on("done", function(res) end)
  :send()
```

**`:send()` is the dispatch, said out loud** (A-115). Every setter is legal until it and none after, which is
a rule with no timing in it. `:get(url)` and `:post(url, body)` stay as one-line conveniences and **take no
callback** — the handler has exactly one spelling.

**`req:on("done", fn)`** is the API's one notification verb and hands back a `Sub` (A-116). `"progress"`
costs nothing to add beside it later.

**`res` is an object** (A-117): `:ok()`, `:status()`, `:body()`, `:header(name)`, `:error()`. The verb earns
its keep beyond the grammar — `res:header(name)` does the same case-insensitive match `req:header(name)`
does, where the old `headers` table asked the reader to remember its keys were lower-cased.

**`hafen.http()` is the collection** of this addon's live requests (A-118), with `req:url()` and
`req:method()` for the filter to match on. So `hafen.http():count() > 4` sees the cap coming, and
`for _, r in ipairs(hafen.http():list("example.com")) do r:cancel() end` ends a backlog without having kept
every handle ever made.

## Two consequences worth writing down

**The permission gate moved to `:send()`.** 093 put it inside `get`/`post`; those are constructors now and
reach no network, so the key is checked where the dispatch is — and that is also the first moment the method
is settled, which is what decides between `http.get` and `http.post`. The URL's *syntax* is still checked
where it was written, so a typo raises at `:request(url)` rather than on a pool thread with nobody to tell.

**The queue cap is charged at `:send()`, not at construction.** A request that is never sent costs the wire
nothing, so it holds no slot.

## One collision, catalogued and inherited

**`:get` is shadowed on this collection.** The section's own `hafen.http():get(url)` convenience — which the
finding itself places in `extra` — overrides the collection's `:get(key)`, so `noGet()` never fires. That is
**C26** of `audit/02-naming.md`'s collision census (`:get` meaning an HTTP GET, a collection member and a
saved-variable table), which the audit recorded and deliberately never turned into a row: of 35 collisions,
C1–C35, exactly one became a row in the whole sweep. `:find(url)` is the search either way.

## Verified

`:t095` — **6 pass, 0 fail, 1 manual**, confirmed, the async round trip included. Clean `ant hafen-client`
from an empty `build/classes`. The open count went **4 → 0**.
