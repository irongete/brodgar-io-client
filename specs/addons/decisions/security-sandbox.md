# Decisions — Security & sandbox

> Part of the decision log (ADR-lite). Index: [../DECISIONS.md](../DECISIONS.md). Entries are
> verbatim; each `### D-xxx` header line doubles as the decision's one-liner (grep them).
> Append new decisions of this kind here.
> Legend: ✅ Accepted - 🔄 Revisit later - ❌ Rejected - 💤 Superseded.

### D-017 — Strict default sandbox ✅ (closes Q-007)
Per-addon `_ENV`. Expose `string,table,math,os.time/clock/date,select,pairs,ipairs,next,type,
tostring,tonumber,pcall,xpcall,error,assert,unpack`. Withhold `io,os.execute/exit/getenv/remove/
rename,dofile,loadfile,load(path),unrestricted require,debug`. `require` resolves only within the
addon's own folder. **No Java reflection from Lua** (facade only). See [12-security-and-permissions.md](../design/12-security-and-permissions.md).

### D-018 — Watchdog: instruction hard-stop + soft per-tick budget ✅ (closes Q-009)
LuaJ instruction-count hook aborts a single callback at a hard cap; plus a soft per-addon per-tick
time budget (default ~4 ms) — repeated violations auto-disable the addon. Both limits configurable
in `haven-config.properties`; defaults ship. See [04-engine.md](../design/04-engine.md).
