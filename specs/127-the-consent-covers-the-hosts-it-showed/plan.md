# 127 — plan

## Approach

### The row grows a field, and the row learns to escape

A consent row is `"<id>=<key>,<key>"` inside `Utils.setprefsl`, whose own framing is NUL-separated UTF-8
through `setprefb`. It gains a third field behind a `;`:

```text
<id>=<key>,<key>;<host>,<host>
```

**One codec, applied to every field.** `enc`/`dec` percent-encode `%`, `=`, `,` and `;` and nothing else.
A domain name and a folder name contain none of them in practice, so an ordinary row is byte-identical to
what is written today; an id carrying `=` — which records nothing at all now, because `consentedMap` splits
on `indexOf('=')` and the tail parses to no key — round-trips. Because the codec escapes the delimiters,
the parse stays exactly as cheap as it is: the first literal `=` is still the id boundary, the first
literal `;` still the key/host boundary. Keys are escaped with the same pair even though `Permission.key`
is a closed `[a-z.]` vocabulary, so there is one encoder and no field is the exception that rots.

**No migration and no new pref name.** A row with no `;` reads as *no hosts recorded*, which is true: the
record predates the field. A network addon consented before this feature therefore fails the containment
test once and re-prompts once, which is the outcome this feature exists to produce — the user has never
approved a host. A non-network addon declares no hosts, so containment holds and it is never touched.

### The containment test grows the same field

`applyPermissionDefaults` is the pure policy — "declared ⊆ consented → the user's choice stands; otherwise
disable" — and it stays exactly that sentence with a second containment beside the first: the declared
hosts must be contained by the recorded hosts. `scanAddonDefaults` already loads each `Manifest` to read
`permissions.granted()`, so it reads `network` from the same object and hands both maps down; no extra disk
I/O. `consentPending` gains the matching clause and reports an unrecorded host the way it reports an
unrecorded key. `PermissionSet.isNew` decides the dialog's **NEW** marker, so the host list a network key's
line carries is marked against the recorded set the same way an entry is.

Hosts are recorded **additively**, as keys are: adding one re-prompts, dropping one does not. That is the
rule `permissions.md` already states for keys, and a second rule for the argument of a key would be a
distinction the user never drew.

### The record is the authority

`HttpApi.requireNetwork` stops asking `owner.manifest.hostAllowed(host)` and asks the hosts the user
granted, carried on the `Addon` and filled at load from the record. The manifest becomes the *request*; the
record is the *answer*.

The wildcard rule is not reimplemented. `Manifest.hostAllowed` is split so its matching half is a static
`hostMatches(List<String> allow, String host)` that both the manifest's own method and the granted set call
— one copy of `*.domain` matching the apex is excluded from, which is the rule most easily got subtly
wrong twice. The engine-internal / REPL owner keeps its any-host exemption where it already sits, tested
before the granted set is consulted.

### The write is bounded

`setprefb` Base64s into a `Preferences` value capped at `MAX_VALUE_LENGTH` (8192 chars), so the encoded
record has roughly 6 KB of room, and `docs/client/services.md` already records that `Utils.setpref*`
catches only `SecurityException` — passing the cap escapes as a raw `IllegalArgumentException` from the
writer. This feature is what puts addon-authored strings into that value, so it bounds it: `grantConsent`
encodes the candidate record, measures it, and where it does not fit **refuses the grant**, naming the
addon and the limit, leaving the addon disabled. Refusing to enable is the only honest branch — consent
that cannot be recorded is consent that will be asked for again next load, and silently dropping another
addon's row to make room would revoke a grant the user did give.

### The host the allowlist is asked about

`LuaHttp` parses through `URI`: `URI.create(url).toURL()` at the entry, and
`u.toURI().resolve(loc).toURL()` for a redirect's relative `Location`. Both throw
`IllegalArgumentException`/`URISyntaxException` where `new URL` was lax, so the two `catch` blocks widen and
still answer `Result.fail("malformed url: …")` — a stricter parse turns a tolerated URL into a failed
request, which is the intended direction. The scheme is checked to be `http` or `https` before
`openConnection`, because a `Location` naming another scheme currently reaches
`(HttpURLConnection)u.openConnection()` and a `ClassCastException` on the pool thread.

## Files to create or modify

| File | What |
|---|---|
| `src/io/brodgar/addon/AddonRegistry.java` | the `enc`/`dec` codec; the third field in `consentedMap`/`persistConsent`; hosts in `grantConsent`, `consentPending`, `applyPermissionDefaults`, `scanAddonDefaults`; the budget refusal |
| `src/io/brodgar/addon/PermissionSet.java` | `isNew` over a host |
| `src/io/brodgar/addon/Manifest.java` | `hostAllowed` split into the static `hostMatches` |
| `src/io/brodgar/addon/Addon.java` | the granted host set |
| `src/io/brodgar/addon/HttpApi.java` | `requireNetwork` asks the record |
| `src/io/brodgar/addon/ui/PermissionConsentWnd.java`, `ui/AddonPanel.java` | the NEW marker on a host |
| `src/io/brodgar/addon/LuaHttp.java` | `URI` parsing at both sites; the scheme check |
| `docs/addons/guides/permissions.md` | the re-ask rule covers a host |
| `docs/addons/api/http.md` | the gate consults what was approved |
| `docs/client/services.md` | `getprefsl`/`setprefsl` and the byte budget — **and the split the addition forces** |
| `addons/127-the-consent-covers-the-hosts-it-showed.1` … `.5` | one suite per task |

## Risks and gotchas

- **`Manifest.hostAllowed` returns true for the internal owner** (`REPL / internal owner: any host`). Moving
  the matching out must leave that exemption reachable, or `:lua` loses the network.
- **`consentedMap` drops a key this build no longer has**, on purpose. The host field has no such
  vocabulary, so an unknown host is kept, not dropped — dropping one would silently narrow a grant.
- **`applyPermissionDefaults` only ever adds to `disabled`**, and `scanAddonDefaults` writes only when the
  size changed. A host-driven disable has to go through the same door or it is not persisted.
- **`Preferences` caps the key at 80 chars too**, not only the value.
- **`URI.resolve` is not `new URL(base, spec)`.** A `Location` that is a bare query string or an empty
  string resolves differently; both are legal HTTP.
- **The consent record is client-wide, not per character**, and is written outside any session.

## Discarded alternatives

- **Storing the record as JSON in one preference** — it removes every delimiter question and it spends the
  6 KB budget the store actually has on punctuation, for a record whose whole content is short identifiers.
  The codec is smaller than the margin it buys back.
- **A second preference key for the hosts** — two keys that must be written together will one day be
  written apart, and the failure is a grant whose halves disagree about what the user approved.
- **A new pref name, migrating the old rows** — nothing is released, and the one-time re-prompt a missing
  field produces is not a migration cost but the correct security outcome: no user has ever approved a host.
- **Keeping the gate on `manifest.hostAllowed` and relying on the re-prompt** — it makes the refusal depend
  on the disable having fired, so any hole in the containment test becomes a hole in the gate. The record
  is the thing the user agreed to and is what the gate should read.
- **Re-implementing the wildcard match against the granted list** — two copies of "`*.example.com` matches
  a sub-domain and not the apex", which is the rule most easily got subtly different in the second copy.
- **Capping the host count in `Manifest` at load** — it bounds one input of a shared budget while leaving
  the budget itself unchecked, so a hundred small addons still overflow it and the error still escapes raw.
