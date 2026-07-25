# hafen.kin — kin / buddy roster

Read and manage the Kin window (your buddy list). The write verbs are **gated** — they require the
[`actions` permission](actions.md).

## Read

| Function | Returns | Description |
|---|---|---|
| `hafen.kin.list([filter])` | [`KinEntry`](types.md#kinentry)`[]` | the roster, in window sort order, matching the [filter](conventions.md#the-filter-argument) |
| `hafen.kin.find(nameOrId)` | [`KinEntry`](types.md#kinentry) \| nil | one kin — a number matches by id, a string by exact (case-insensitive) name |

Subscribe to [`KinChanged`](events.md#roster-quests-markers) (payload = the new list) to react to a
kin being added, removed, renamed, regrouped, or flipping online/offline.

```lua
for _, k in ipairs(hafen.kin.list()) do
  hafen.log(k.name .. (k.online and " (online)" or ""))
end
```

## Write *(gated — requires the `actions` permission)*

A `kin` argument is a [`KinEntry`](types.md#kinentry) snapshot, its `id`, or an exact name.

| Function | Description |
|---|---|
| `hafen.kin.add(secret)` | add a kin by the other player's **hearth secret** (the "Add kin" field) |
| `hafen.kin.remove(kin)` | **End kinship** — ends the kinship; the kin stays *memorized* in the list |
| `hafen.kin.forget(kin)` | **Forget** — drops a memorized (un-kinned) kin from the list entirely |
| `hafen.kin.rename(kin, name)` | set a kin's nickname |
| `hafen.kin.setGroup(kin, group)` | move a kin to colour group 0..7 |

> **Removing is two steps.** The game drops a kin in two stages: `remove(kin)` ends the kinship (the
> kin becomes memorized but stays listed), then `forget(kin)` drops the memorized entry. To fully
> remove an active kin, call `remove` then `forget`. There is no add-by-name — kinning needs a shared
> hearth secret (or the right-click "Add as kin" petal via
> [`hafen.act.clickGob`](actions.md#hafenactclickgob) + [`flower`](actions.md#hafenactflower)).
