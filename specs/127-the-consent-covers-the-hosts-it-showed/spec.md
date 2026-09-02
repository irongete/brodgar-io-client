# 127 — the consent covers the hosts it showed

## What and why

The consent dialog shows the hosts. `PermissionConsentWnd` takes them (`List<String> hosts`) and
`PermissionSet.describe(entry, hosts)` writes them into the network key's line, so the user reads
*"fetch data from the servers it lists: api.example.com"* — which `permissions.md` and `http.md` both
print as the point of the key.

**Nothing records them.** `AddonRegistry.grantConsent` persists `declared.granted()` alone, a
`Set<Permission>`. `consentPending` compares that enum alone. `applyPermissionDefaults` — the policy whose
own javadoc is "declared ⊆ consented → the user's choice stands; otherwise disable" — is a containment test
over keys and nothing else. And `HttpApi.requireNetwork` asks `owner.manifest.hostAllowed(host)`, the
**live** manifest.

So the half the user actually decided is the half nothing keeps. An addon consented for
`api.weather.com` rewrites its own `network.hosts` to `evil.example.com`; the declared key set is
unchanged, so `consentPending` returns `null`, no dialog is raised, the addon is not disabled, and
`requireNetwork` reads the new file and allows it. The user approved one host and the addon reaches
another, silently. `permissions.md` states the re-ask rule for keys — "a version of your addon that adds a
key is disabled again and prompts again" — and the hosts, which the same dialog showed, are outside it.

Two things fall out of fixing it, and both are this feature's own:

- **The record's encoding cannot carry a host as it stands.** A row is `"<id>=<key>,<key>"` inside
  `Utils.setprefsl`, whose own framing is NUL-separated UTF-8 through `setprefb`. `=` and `,` are
  unescaped, so an id containing `=` already records nothing and that addon re-prompts for ever. Hosts are
  addon-authored strings and would go in beside them.
- **The write is unbounded and the store is not.** `setprefb` Base64s into a
  `Preferences` value capped at 8192 characters, and `docs/client/services.md` already states that
  `Utils.setpref*` catches only `SecurityException`, so passing that cap escapes as a raw
  `IllegalArgumentException` from whichever writer minted the value — "a writer minting a key out of names
  it does not control has to bound the length itself". This feature is what puts addon-authored hosts into
  that value.

## Acceptance criteria

1. `grantConsent` records the hosts the dialog showed alongside the keys, and the record survives a client
   restart.
2. An addon whose manifest adds a host the record does not carry is disabled and re-prompted, exactly as
   one that adds a key is. The added host is marked **NEW** in the dialog, as an added key is.
3. An addon that *drops* a host is not re-prompted, matching the record's documented additive rule for keys.
4. **The record is the authority, not the manifest.** A request to a host the manifest lists and the record
   does not is refused at the call, naming the host — so the gate does not depend on the re-prompt having
   fired. The engine-internal owner reaching any host is unchanged.
5. An id or a host containing the row's own delimiters round-trips through the record, and an id containing
   `=` records and reads back correctly.
6. A declaration too large for the preference store is refused with an error naming the addon and the
   limit, instead of an `IllegalArgumentException` escaping from `setprefsl`.
7. The two remaining `new URL(String)` / `new URL(URL, String)` sites parse through `URI`, so the host the
   allowlist is asked about is the one a strict parse produced.

## Out of scope

- **The resolve-then-connect DNS-rebinding gap.** `LuaHttp.validateHop` names it in place as a known
  deferral awaiting a hardening that pins the checked address into the connection. It is a different
  mechanism — the address behind a host, not which host was approved — and this feature neither widens nor
  narrows it.
- **Re-checking a redirect hop against the allowlist.** Already done: `validateHop(r, u, hops > 0)` runs per
  hop and `http.md` states it. This feature changes *which* allowlist it consults, not whether it runs.
- **Every other permission key's argument.** `http.get`/`http.post` are the only keys that take one, so the
  record grows one field rather than a general mechanism. If a second key ever takes an argument, the field
  it needs is the one this feature builds.

## Docs impact

Pages this feature writes:

- `docs/addons/guides/permissions.md` — the re-ask rule covers a host as it covers a key.
- `docs/addons/api/http.md` — the allowlist that gates a request is the one the user approved.
- `docs/client/services.md` — `getprefsl`/`setprefsl`'s NUL framing and the real byte budget behind it,
  which line 15's `getpref/setpref` row does not cover. **The page is at its 150-line ceiling**, so the task
  that writes it splits it by subject — prefs and the Options window away from keybindings, resources,
  audio and the rest — and re-points the inbound links in that same task.

Derived impact set — the promise stated in prose, outside the two pages above:

```text
$ grep -rn "allowlist\|re-asks\|counts as added\|\[net\]\|servers it lists" docs/
docs/addons/api/asset.md:231          …over HTTP is hafen.http, which is protected by a manifest allowlist.
docs/addons/api/conventions.md:329    …the network host allowlist is the argument of the key…
docs/addons/api/README.md:162         fetch a URL, against the host allowlist your manifest declares
docs/addons/guides/saved-data.md:119  …allowlist in the manifest, and lands in a saved variable…
docs/addons/runtime.md:189            [net] — the tooltip names every host it may reach
```

Four of the five say the allowlist is *the manifest's*, which is exactly what criterion 4 changes; each is
revised or discharged with its reason by the task that owns the gate. `map/drawings.md:30`,
`map/README.md:57` and `boot-and-loop.md:113` match `re-asks` about redrawing and are discharged on sight.

## Context files

- `src/io/brodgar/addon/AddonRegistry.java` — 1, 2, 3, 4 (`grantConsent`, `consentPending`, `consentedMap`,
  `persistConsent`, `applyPermissionDefaults`, `scanAddonDefaults`, `PREF_CONSENTED`)
- `src/io/brodgar/addon/Manifest.java` — 1, 2 (`network`, `hostAllowed`, `usesNetwork`)
- `src/io/brodgar/addon/Addon.java` — 1 (where the granted hosts hang)
- `src/io/brodgar/addon/HttpApi.java` — 1 (`requireNetwork`)
- `src/io/brodgar/addon/PermissionSet.java` — 2 (`isNew`, `describe(entry, hosts)`, `entries`)
- `src/io/brodgar/addon/Permission.java` — 2 (`byKey`, `HTTP_GET`, `HTTP_POST`)
- `src/io/brodgar/addon/ui/PermissionConsentWnd.java` — 2
- `src/io/brodgar/addon/ui/AddonPanel.java` — 2 (the dialog's one door)
- `src/haven/Utils.java` — 3, 4 (read only: `getprefsl`, `setprefsl`, `setprefb`)
- `src/io/brodgar/addon/LuaHttp.java` — 5 (`validateHop`, the two `new URL` sites)
- `docs/addons/api/http.md` — 1
- `docs/addons/guides/permissions.md` — 2
- `docs/client/services.md` — 4
