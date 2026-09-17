# 155 — Tasks

- [x] **155.1 — The receiver map follows the pages.** `tools/docverbs.py`: `RECEIVERS` gains the
      descriptive spellings the pages use, an explicit `None` with its reason for each spelling the tool
      cannot check, a suffix rule for local names built on an entity word, `PER_FILE` entries where one
      spelling means two types, and `cat` pointed at the entity that exists. Every finding the widened
      gate raises is fixed as a spelling on its page. Criteria 1, 2, 3.
      *Its verification*: `python tools/docverbs.py` exits 0 and reports more checked calls than 154.6's
      1497; `--verbose` lists no spelling the pages write more than a handful of times;
      `tools/refusalverbs.py` still exits 0; the link check over `docs/addons/**` still resolves.
- [x] **155.2 — The bridge's comments and refusals name live verbs.** Every file under
      `src/io/brodgar/addon/`: each `:onChange`, `:onPress`, `:onSubmit`, `:onSelect`, `:onCell`, `onDraw`,
      `onDrop`, `onClose`, `onTick`/`Tick`, `find(selector)`/`all(selector)`, `s:ui():find(` in a comment or
      a refusal message is replaced by the key or verb the API answers, in a sentence that stays true where
      it stands. `tools/refusalverbs.py` walks a builder chain through the widget's chained setters, so the
      builders' messages are read. No code path changes. Criterion 4.
      *Its verification*: `grep -n` for each retired spelling over `src/io/brodgar/addon/` finds only
      `Refusal.java`'s `MOVED` key for `session:ui():find`; `python tools/refusalverbs.py` exits 0 and
      reports more checked mentions than before (3485), and reports the old builder literals when they are
      put back; `ant hafen-client` builds.
