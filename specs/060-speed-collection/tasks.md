# 060 — tasks

- [x] **060.1 — the speeds become a collection, and picking one is `:set`.** Adds
      `src/io/brodgar/addon/LuaSpeed.java`: a Speed member (`:index()`, `:name()`, `:available()`,
      `:exists()`, `:info()`) interned per addon in `Addon.speeds`, and `LuaSpeed.collection(owner)` —
      `:list`/`:count`/`:find` over the speeds selectable right now, `:get(key)` over all four by index
      or whole case-insensitive name, plus the `extra` verbs `:current()` and the protected
      `:set(speed|index|name)`. Mounted with `Section.mount` where `AddonManager` called
      `ActApi.installSpeed`, which goes, with the rest of that file's speed half. `Permission`'s
      `SPEED_CURRENT` becomes `SPEED_SET` (`speed.set`); `Retired` gains `hafen.speed():max` and
      `():name`, re-points the `hafen.speed.*` dot rows and drops `section("speed", …)`; and
      `LuaCollection.meta`'s `__index` consults `Retired` so a collection-section's retired verb names
      its replacement instead of reading *has no verb*. `hello` and `walker` move to the new spellings,
      and `walker`'s manifest to `speed.set` — its old key now fails to load.
      *Its suite* declares `speed.set` and drives the whole surface: that every member of `:list()` is
      `:available()` and its indices ascend from `0`, that `:count()` matches, that `:get(3)` answers a
      Speed whether or not it is in `:list()`, that `:get("run") == :get(2)` and `:current()` is `==`
      the member it names, and that `:info()` carries the four documented fields. It then `:set`s a
      speed other than the one you are on and polls `:current()` for up to two seconds — the server
      owns `cur`, so the read-back is a round trip — and restores the speed it found. Refusals, each
      `pcall`ed and each asserted on its text: `:max()` names `:list()`, `:name(2)` names `:get`,
      `:current(2)` names `:set`, `:set(9)` and `:set("nope")` and `:set(nil)` and `:set({})` each
      raise, and `#coll` is refused. A speed that is **not** selectable is refused naming the ones that
      are — scored when the run finds a locked speed, and printed as the `[manual]` line below when it
      does not.
      `[manual]`: no speed was locked this run — re-run while sprint is locked and expect: refused,
      listing the selectable speeds.

- [ ] **060.2 — the pages say what the API now is.** Rewrites `docs/addons/api/speed.md` whole around
      the collection: the six verbs, the Speed object, the one rule that reconciles them (*the
      collection enumerates what you can pick; `:get` addresses a speed by its key*), the write's
      `speed.set` key and every error it raises, and the note that `Speedget.max` can leave nothing
      selectable at all. Adds the `Speed` snapshot row to `docs/addons/api/types.md`, renames the key
      row in `docs/addons/guides/permissions.md`, re-reads the four link labels the spec's impact set
      lists, and maps the HUD speed selector in `docs/client/services.md` — `Speedget`, its `cur`/`max`
      fields, `tips`, `set` → `wdgmsg("set", n)`, and that it is found by a `children(Speedget.class)`
      walk because `GameUI` gives it no field.
      *Its suite* declares **no** permission at all, which is the claim: `hafen.speed():set(1)` raises
      naming `speed.set` — and the text must not contain `speed.current`, which is what proves the
      rename reached the gate and not only the docs. Around it, it walks every spelling the rewritten
      page shows — `:list`, `:count`, `:find`, `:get` by index and by name, `:current`, `:info`,
      `sp:available` — asserting the page and the client agree, and re-asserts the three retired
      spellings raise, so this task's verification needs nothing else run.
      `[manual]`: run `:hello` and read its speed line — expect: the selected speed with its name, and
      the selectable ones listed. Then `:walker speed 1` — expect: the character drops to Walk and the
      HUD selector moves.
