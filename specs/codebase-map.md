# Codebase map — index

> **Shared by every area.** This file is an INDEX, not a map: one line per subsystem file
> under `specs/codebase/`. **Never read the whole tree** — open only the 1–2 subsystems your
> feature touches. Each subsystem file holds `file:line` anchors (line numbers are indicative;
> the **class + method/field name is the stable anchor**). Max 40 lines.
>
> **Coverage is paid for, not assumed:** if a task has to read `haven` source that no subsystem
> file covers, `/end` writes or extends the matching file (max 70 lines). The reading is paid
> for once.

| Subsystem | Covers |
|---|---|
| [boot-and-loop.md](codebase/boot-and-loop.md) | `main` → login → `RemoteUI.init` → `GameUI`; the per-frame tick/draw loop; the `UI` monitor, Loader/Connection threads and the `Loading` protocol |
| [widgets.md](codebase/widgets.md) | `Widget`/`UI` tree, `@RName` registry, server→create/place seams, tick/draw traversal seams, `GameUI.addchild`, `GOut`, introspection + hit-testing, drops and modifier flags |
| [network.md](codebase/network.md) | `Session`/`Connection`, `uimsg` in / `wdgmsg` out, and the full action channel (map clicks, menu acts, item verbs, flower petals) |
| [state.md](codebase/state.md) | Where game state lives: `Glob`, `OCache`/`Gob`, `MCache`, player, inventory/`GItem`/`ItemInfo`, `CharWnd` attrs, party, time/astronomy |
| [services.md](codebase/services.md) | Console, keybindings, `Resource` (+ code adoption), prefs + Options/`GSettings`, audio, chat, combat, buffs, kin, vitals, FEP/hunger, study, skills, crafting |
| [world-3d.md](codebase/world-3d.md) | `MapView` scene, client-only gobs, placement/snapping, pick pass + click intercept, `TexI`/`Material`/`TexRender`, billboards, world quads, Phong lighting, glTF geometry |
| [text-and-fonts.md](codebase/text-and-fonts.md) | `Text.Foundry` and every named surface that bakes one, `RichText` `$font`, DPI scaling, custom TTF loading |
| [addon-engine.md](codebase/addon-engine.md) | *(area `addons`)* `src/io/brodgar/addon/` file layout, the `haven` seams it owns, extension points, and the voice-feature integration template |

## Client-wide gotchas

- **One UI thread**: the frame loop is `tick → draw → swap` under `synchronized(ui)`; Loader
  threads apply server messages under the same monitor. Never call into a scripting layer from
  a Connection worker — queue and drain on the tick.
- **`Loading` is a control-flow exception**: any resource/gob/grid read can throw it — swallow
  to nil/partial, or defer to a loader task; never let it escape into user code.
- **Widget creation runs off the UI lock** (Loader) before attach/bind — do tree work in
  `added()`/`attached()`. `Widget.add` links directly (does NOT route through `addchild`).
- **`.res` lifecycle**: resources resolve async (`Resource.remote()`, `Indir.get()` throws
  `Loading` until cached); some `.res` files carry published Java code the fork does not ship —
  never assume a resource's name (servers ship `-alt` variants); read it off the running client.
- **Java changes need `ant` rebuild + full client restart** (no hot-reload). `ant hafen-client`
  is INCREMENTAL — a moved symbol can false-green; `rm -rf build/classes` for a true check.
- **No global world position**: `rc` is login-relative; the shareable anchor is grid id +
  within-grid offset. Grid/segment ids are 64-bit → expose as decimal strings.
- **Windows/Git-Bash**: `;`-separated `-cp` needs `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'`;
  write throwaway test files to the scratchpad, not `/tmp`.
