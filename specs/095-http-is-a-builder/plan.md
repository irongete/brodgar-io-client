# 095 — http is a builder: plan

## Approach

Four rows, one unit, one suite — and the four are one change: separating construction from dispatch is what
makes the setters honest, what frees the callback to become a subscription, and what gives the collection a
membership to mean. Doing any one of them alone would have left the other three half-done.

**This suite declares its permission**, where 093's declared none. The two are opposite on purpose: 093
shipped the gates, so the proof was the refusals; 095 ships a builder, and the only honest proof of a builder
is to build one and send it. One GET of `example.com` — the IANA reserved documentation domain — is the whole
of the network it touches.

## Gotchas found while doing it

**The rewrite silently dropped the permission gate.** 093 had put `requireNetwork(owner, HTTP_GET, host, …)`
inside `get`/`post`; replacing those verbs with constructors deleted the call sites with them, and the build
stayed green. Nothing but re-reading 093's own diff would have caught it. The gate is in `:send()` now, which
is where it belongs — and it is the reason to re-read the *previous* feature's spec when rewriting a surface
it touched.

**A reshape has nothing for `Retired` to key on.** `hafen.http():get(url, cb)` still spells `get`; what
changed is an argument count. So the refusal is written inside the verb, which is exactly the rule
`CLAUDE.md` grew at 085.7 — *"a rename is free and a reshape is not"* — and this is its first real use.

**`extra` is merged last, so it overrides.** `LuaCollection.create(name, src, extra)` copies `extra` over the
built-in verbs, so a section verb named `get` shadows the collection's own. Worth knowing before naming any
`extra`.

**A Python heredoc ate one level of `\n` escaping** and wrote a real newline into a Java string literal. Four
"unclosed string literal" errors from one message. Write the script with the file tool, not the shell.

## Discarded alternatives

- **Keeping `res` as a table and saying the rule on `conventions.md`** — A-117's other branch: *a snapshot is
  a table and a live payload is an object*, then checking every payload against it. The finding picks the
  object, and the reason is that this is the **only** payload in the API that is not one, so the rule would
  have exactly one exception to describe.
- **`:get`/`:post` keeping their callback** as a convenience beside `:on`. That is two spellings for one
  thing, and the finding is explicit: they stay "**only if** they take no callback".
- **A `:url(u)` setter.** Nothing asks for one, and its absence is what lets the host be resolved once at
  construction and trusted at `:send()`.
- **Auto-sending from `:get(url)`**, so the convenience is a one-liner in the old sense. It would restore the
  very split the feature removes — one door that sends and one that does not — and the finding says `:get`
  "returns a bare request".
- **Gating at `:request(url)` as well as `:send()`.** Two gate sites for one action, where D-213 asks for the
  gate to be the first statement of the verb that *does* the thing. Nothing leaves the client until `:send()`.
- **Charging the queue cap at construction**, as the old code did. A request never sent reaches no wire, so
  holding a slot against the cap would make the cap count something that costs nothing.
- **Renaming the section's `:get` to free the collection's.** Not a row, and the collision is C26 — one of 35
  the audit catalogued and did not act on.
