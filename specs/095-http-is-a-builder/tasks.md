# 095 — http is a builder: tasks

Shipped as **one task with one suite**, in the maintainer's own session: four rows that are one change —
separating construction from dispatch is what makes the setters honest, what frees the callback to become a
subscription, and what gives the collection a membership to mean.

- [x] **095 — http is a builder.** `CLAUDE.md` says a builder is constructed bare and configured by chained
      setters, and `HttpApi`'s own javadoc claimed compliance while `hafen.http():get(url, cb)` took the URL
      **and the handler** positionally and scheduled the request before the first setter ran — so every
      setter carried a lifetime rule no other builder in the API has. **`hafen.http():request(url)` is bare
      and `:send()` is the dispatch**: every setter is legal until it and none after, which is a rule with no
      timing in it. **`req:on("done", fn)`** is the one notification verb, handing back a `Sub`, where a
      positional callback was; `:get(url)` / `:post(url, body)` stay as conveniences and **take no callback**.
      **`res` is an object** — `:ok()` `:status()` `:body()` `:header(name)` `:error()` — the last payload in
      the API that was a bare table, and `res:header(name)` does the case-insensitive match the old `headers`
      table asked the reader to remember. **`hafen.http()` is the collection** of this addon's live requests,
      with `req:url()` and `:method()`, so the hardest cap in the API can be seen coming. The permission gate
      moved to `:send()`, which is the call that reaches the network and the first moment the method is
      settled.
      *Its suite* **declares `http.get` and one host**, the opposite of 093's on purpose: the only honest
      proof of a builder is to build one and send it. It asserts the bare state, that four setters chain and
      read back, that an unsent request is in no collection, five refusals (a setter after `:send()`, a second
      `:send()`, a missing url, a non-http url, a bad method), the `Sub`, the **positional callback refused
      naming the whole new shape**, the collection quartet, and last — asynchronously — one GET of
      `example.com` with all five result verbs, plus the line that says why the object was needed:
      `type(res.ok) == "function"`, because a field read on a closed type hands back the method.
      `[manual]`: one — that nothing of the maintainer's went anywhere.
      *Audit*: **A-115** (`audit/ns-http.md` F1) · **A-116** (same) · **A-117** (`ns-http.md` F2) ·
      **A-118** (`ns-http.md` F5).

## Result

`:t095` — **6 pass, 0 fail, 1 manual**, confirmed. Clean build from an empty `build/classes`.

Four rows ticked and struck. **The open count went 4 → 0: the sweep is complete, 122 of 122.** One row
carries a note:

- **A-118**: `:get` is **shadowed** on this collection by the section's own `:get(url)` convenience, which
  the finding itself places in `extra`. That is **C26** of the naming census, catalogued and never made a
  row; `:find(url)` is the search, and `noGet()` stands as the fallback.

**No row of another feature's block was implemented here.**

## Reported at the close, not changed

**The rewrite silently dropped 093's permission gate**, and the build stayed green — `requireNetwork` lived
inside `get`/`post`, and replacing those verbs with constructors deleted the call sites with them. It is back,
in `:send()`. Worth a habit rather than a fix: when a feature rewrites a surface the previous one gated,
re-read that feature's spec before building.

**`ns-http.md` F3 and F6 are findings with no row.** F3 (the section door uses `a.checkjstring` rather than
the house `Args` helpers) is now moot — `urlArg` goes through `Args.str`. F6 (`req:cancel()` returning
nothing) was already true before this feature: it returns the request. Neither is in
`audit/INVENTORY.md`; both are recorded here so a later reader does not go looking.
