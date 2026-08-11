# 004-saved-variables — Tasks

- [x] 004.1 — `hafen.store`: manifest `saved_variables` (per-char + account scopes), proxy
      tables + `flush()`, split restore lifecycle (account at load, per-char at
      `OnEnterWorld`), captured-scope flush on `OnDisable` + 30 s throttled auto-save,
      atomic non-fatal JSON writes.
