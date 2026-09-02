# 127 — tasks

- [x] **127.1 — the record carries the hosts, and the gate asks the record.**
      A consent row gains a third field behind a `;`, so `consentedMap` and `persistConsent` carry
      `<id>=<keys>;<hosts>`; a row with no `;` reads as no hosts recorded, which is what every row written
      before this says. `grantConsent` records the hosts the dialog showed, additively, as it records keys.
      `Manifest.hostAllowed` is split so its wildcard matching is a static `hostMatches(allow, host)`, and
      `Addon` carries the granted hosts, filled at load from the record; `HttpApi.requireNetwork` asks that
      instead of the live manifest, with the engine-internal owner's any-host exemption tested first.
      Revises `http.md` — the gate consults what the user approved — and **discharges the feature's impact
      set**: the four pages calling the allowlist the manifest's, each revised or discharged with its reason.
      *Its suite* declares one host in its own manifest and asserts a `:send()` to a **second** host raises
      naming that host, then asserts `hafen.http()` refuses nothing about the declared one — the pair that
      distinguishes a gate that reads the grant from one that reads nothing at all.
      `[manual]`: quit the client, start it again and run `:t127` — expect: no consent dialog, this addon
      still enabled, and the same verdict line, which is the record surviving a restart.

- [x] **127.2 — an added host re-prompts, and reads as NEW.**
      `applyPermissionDefaults` keeps its one sentence and gains a second containment beside the first: the
      declared hosts must be contained by the recorded hosts. `scanAddonDefaults` reads `network` off the
      `Manifest` it already loads, so no extra disk I/O. `consentPending` reports an unrecorded host the way
      it reports an unrecorded key, and `PermissionSet.isNew` marks it, so the dialog's network line shows
      the added host as **NEW** rather than restating the approved ones. Dropping a host re-prompts nothing.
      `permissions.md`'s "Asking for more re-asks" covers a host as it covers a key.
      *Its suite* asserts the pure policy's two directions through the one door Lua has — that this addon,
      consented and unchanged, is running and its declared host is reachable — which is the check that fails
      if the new containment is inverted and disables everything.
      `[manual]`: two runs of one action, and they are the containment's two directions. Add a host to this
      suite's manifest and **Reload UI** — expect: a consent dialog, the added host marked **NEW**, and the
      addon disabled until it is answered. Then remove a host and **Reload UI** — expect: no dialog, the
      addon still enabled.

- [x] **127.3 — a row escapes its own delimiters.**
      One `enc`/`dec` pair percent-encodes `%`, `=`, `,` and `;`, applied to all three fields — the id, each
      key and each host — so there is one encoder and no field is the exception. Because the delimiters are
      escaped, `consentedMap`'s parse is unchanged: the first literal `=` is still the id boundary and the
      first literal `;` still the key/host boundary. An ordinary row is byte-identical to what is written
      today, and an id containing `=`, which records nothing at all today and re-prompts for ever, records
      and reads back.
      *Its suite* asserts nothing the codec does — it is not reachable from Lua — and instead re-asserts
      127.1's pair, that a declared host is reachable and an undeclared one is refused, which is what breaks
      if the codec corrupts a row it round-trips through.
      `[manual]`: rename a copy of this suite's folder to one containing `=`, matching its manifest `id`,
      then **Reload UI** and consent — expect: it stays enabled across a second **Reload UI**.
      <!-- extra context: jshell is the pre-check for enc/dec round-tripping, per CLAUDE.md -->

- [ ] **127.4 — a declaration too large is refused, not thrown.**
      `setprefb` Base64s into a `Preferences` value capped at 8192 characters, and `Utils.setpref*` catches
      only `SecurityException`, so passing the cap escapes raw from whoever wrote it. `grantConsent` encodes
      the candidate record, measures it, and where it does not fit refuses the grant naming the addon and
      the limit, leaving the addon disabled — the only branch that neither loses the write nor revokes
      another addon's grant to make room. Writes the `getprefsl`/`setprefsl` row and its real byte budget
      into `docs/client/services.md`, and **splits that page** — it is at its 150-line ceiling — by subject,
      prefs and the Options window away from the rest, re-pointing every inbound link in this same task.
      *Its suite* asserts its own declared host is still reachable, which is the check that the bounded
      write did not start refusing ordinary grants.
      `[manual]`: add ~400 hosts to this suite's manifest, **Reload UI** and consent — expect: a refusal
      naming this addon and the limit, and no Java error in the console.

- [ ] **127.5 — the host the allowlist is asked about is a strictly parsed one.**
      **Three** `new URL` sites parse through `URI`, and `HttpApi.httpHost` is the one this task is named
      after: it runs at `hafen.http():request(url)` and what it returns **is** `req.host`, the host
      `requireNetwork` measures against the record — so a lenient parse there is a lenient parse of the whole
      gate, whatever the sites behind it do. It takes `URI.create(url).toURL()`, its `catch` widened past
      `MalformedURLException`, still raising the `LuaError` that names the url. `LuaHttp` takes the other
      two: `URI.create(url).toURL()` at the entry and `u.toURI().resolve(loc).toURL()` for a redirect's
      relative `Location`, with those two `catch` blocks widened to what `URI` throws and still answering
      `Result.fail("malformed url: …")`. The redirect is also checked to be `http` or `https` before
      `openConnection` — the check `httpHost` already makes at the entry, and without it a `Location` naming
      another scheme reaches `(HttpURLConnection)u.openConnection()` and a `ClassCastException` on the pool
      thread. The three together are the last of the build's deprecation warnings.
      *Its suite* `pcall`s `hafen.http():request(url)` over a handful of URLs `new URL` accepts and `URI`
      refuses, asserting each raises **and** that the message names the URL rather than reading `null`, then
      asserts an ordinary `https://` URL still builds and that a `file:` one is refused at the call — the
      pair that separates a stricter parse from a broken one. That pair lands on `httpHost`, because it is
      first and a URL it refuses never reaches `:send()`; the two `LuaHttp` sites are verified by reading
      them, a relative `Location` being something no offline suite can cause a server to send.
