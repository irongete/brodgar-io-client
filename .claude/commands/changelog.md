# /changelog — write what a version changed

Usage: `/changelog <version>` → `/changelog v13`, `/changelog 13`, `/changelog v13.1-beta`.

Writes `docs/changelog/<version>.md`, the version's page on the site, and its row in
`docs/changelog/README.md`. **It commits nothing and pushes nothing.** The page is the longer
cousin of the notes posted on Discord: there, one line per system; here, the few lines under each
system a player or an addon author would want before updating.

**This command reads the history on purpose** — its subject is what changed, which no page under
`docs/` states. That is the only use it makes of it: nothing read here is a fact about what is in
force, and nothing here is written anywhere but `docs/changelog/`.

## 1. The range

The version is normalized to `vN` or `vN.X-beta` (`13` → `v13`); anything else stops the command.

- **The end**: the version's tag when it exists, else `HEAD` — the version not yet cut, whose page
  goes into the commit the release will tag. Untagged and not on `master`: stop and say so.
- **The start**, the rule `publish.ps1` writes its notes by: a release `vN` starts at the newest
  **release** tag below it (the betas between are its code); a beta starts at the newest tag of any
  kind below it. With `git tag -l 'v*' --merged <end> --sort=-v:refname`, the first that is lower
  than the version and of the right kind. No such tag: stop.
- **The date**: the tag's date when tagged, else today's.

State the range, the commit count and the date in one line before reading on.

## 2. What to read — and nothing else

1. **Every commit in the range**, subject and body: `git log --no-merges --format='--- %h %ad%n%s%n%n%b' --date=short <start>..<end>`.
   The body is written for the maintainer; what you take from it is *what the player or the
   addon author now sees*, never how it was built.
2. **The features closed in the range**: `git diff --name-only --diff-filter=A <start>..<end> -- 'specs/*/spec.md'`,
   and for each one only its spec's opening section (the problem and what the feature delivers) —
   that is the feature's own statement of what it is for.
3. **The API editions the range moved**: `git diff <start>..<end> -- docs/addons/manifest.md`, the
   *What needs `X.Y`* rows it added or changed. A row is the authoritative list of what an edition
   added, with its links.
4. **What the reference pages changed**: `git diff --stat <start>..<end> -- docs/addons`, and the
   table rows the range touched, `git diff <start>..<end> -- docs/addons/api | grep '^[+-]|'` — a
   verb's signature, range, default and rules live in those rows. A range with no feature still
   changes the API this way, through a client commit that corrected a page.
5. **Upstream**: `git log --merges --format='%h %s' <start>..<end>` — a `/merge` from upstream
   `haven` is one line, whatever it brought.
6. **The pages already written**: `docs/changelog/README.md` and the newest page there, for the
   wording the earlier versions used, so the same system keeps the same heading.
7. **A page you link to**, to check the link resolves (§4).

When a commit's effect on the player is not clear from its subject, body and spec, read the commit's
diff stat (`git show --stat <hash>`), not its code. When it is still not clear, leave it out and name
it at the close — a line that guesses is worse than no line.

## 3. The page

````markdown
# v13

**2026-10-05** · since [v12](v12.md) · [download](https://github.com/irongete/brodgar-io-client/releases/tag/v13)

One sentence: what this version is about, the way the Discord post opens.

## Client

### Combat

- **Added** the combat schools tab can be driven by addons ... one line.
- **Fixed** ...

### Terrain and view distance

- **Improved** ...

## Addon API

API edition **1.5** (was 1.4): an addon using what it added declares `"api_version": "1.5"`.

### Fight

- **Added** [`session:fight():school()`](../addons/api/fight.md#schools), the saved combat schools, with `:load()` and `:save()` under `fight.load` and `fight.save`.
- **Changed** ⚠️ `card:exists()` is now `card:empty()`; the old name raises naming it.

## Upstream

- Merged the upstream client of 2026-10-02.
````

The rules of the page:

- **Two parts, in this order**: `## Client` — what a player sees — and `## Addon API` — what an addon
  author sees. A part with nothing in it is left out. `## Upstream` only when the range merged
  upstream. Nothing else: no *Internal*, no *Docs*, no *Build* part.
- **A `###` per system**, named the way a player names it (*Combat*, *Sky and weather*, *Terrain and
  view distance*, *Interface*, *Windows*, *Addons*, *Login*, *Launcher*, *Performance*) — the same
  heading a system had on an earlier page. In `## Addon API`, a `###` per namespace or area (*Fight*,
  *UI*, *Client*, *Events*). Biggest change first.
- **A bullet is one line, one change**, opening with one verb in bold: **Added**, **Changed**,
  **Improved**, **Fixed**, **Removed**. It says what changed and the one detail a reader needs —
  where it is (*Options → Performance*), its default, its range, what to change in an addon — and
  stops. Roughly 25 words; never a second sentence of mechanism.
- **Size**: a system takes 1–6 bullets. A version of 10 commits is about 10–20 bullets; a big one
  stays under 50. Several commits that make one thing a player sees are **one** bullet; a fix to
  something this same range added is not a bullet at all — the reader never saw it broken.
- **Client bullets speak the player's words**: what is on screen, in the options, in the menus. Never
  a Java class, a file, a task or feature number, a suite, a commit hash, a measurement method; a
  measured result only as the player feels it (*~80% less memory*).
- **Addon API bullets speak the API's words**: the verb as written in Lua, linked to its page under
  `docs/addons/` with a relative link from `docs/changelog/` (`../addons/api/fight.md#schools`); the
  permission key when it needs one. A change a published addon has to react to — a verb that now
  raises, a reshaped answer, a changed meaning — is **Changed** or **Removed** with ⚠️, and says what
  to write instead. The edition line only when the range moved `ApiVersion`.
- **Not in the changelog**: commits that only touch `docs/`, `specs/`, `tools/`, `.claude/` or the
  release machinery; refactors with nothing to see; the work of a task that a later task in the range
  replaced.
- Present tense of the change (**Added** X, not *we added*); British English in prose, Lua spelled as
  it is; `docs/` rules on links apply (`DOCUMENTATION.md` §9). No hedging, no selling.

## 4. Writing it

1. Write `docs/changelog/<version>.md` (an existing page is rewritten whole: the command is the page's
   only author).
2. `docs/changelog/README.md`: the index, newest first — create it if missing:

   ```markdown
   # Changelog

   What each version of the client changed, newest first. A release lists everything since the
   previous release, its betas included.

   | Version | Date | |
   |---|---|---|
   | [v13](v13.md) | 2026-10-05 | The page's opening sentence. |
   ```

   One row per page, replaced if the version is already there.
3. Check every relative link in the page resolves: the file exists, and an `#anchor` matches a
   heading of that file (GitHub's slug: lower case, spaces to `-`, punctuation dropped).

## 5. The close

Report, in this order and briefly:

- The range, the commit count, and the path written.
- **Left out**: each commit or change you chose not to list, one line each with why — so the
  maintainer can put it back.
- **Unsure**: any bullet whose wording rests on a guess.

Then stop. The maintainer reads the page, asks for changes, and commits it. **The site shows
`docs/` as of the newest release's tag**, so a page reaches the site only from a commit that a release
tag includes: the page of a version not yet cut is committed before the release is published (a
docs-only commit carries `[skip ci]`); the page of a version already tagged appears with the next
release.
