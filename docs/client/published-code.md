# Published code: the Java a `.res` carries

Not all of the client's code is in this tree (`doc/resource-code`): a `.res` can carry **its own Java**
— a `code` layer of classes plus a `codeentry` layer naming the entry point and, under type `2` or a
`use` datum, a **classpath** of other resources to load it against (`$use: lib/obst` in the preprocessed
source). `CodeEntry.loader()` chains a `Resource.ResClassLoader` per entry, whose parent is the client's
own loader. Every tooltip (`ui/tt/*`), the gilding window (`ui/slot-info`), the village and realm
panels, most effect sprites and every instrument are code of this kind, and it is what a server widget
gets whenever its type name contains `/` (`Widget.gettype3`).

Where a resource comes from, in the order `Resource.remote()` asks: `Resource.local()` first — the
`res/` tree of `builtin-res.jar` (`Resource.JarSource`) plus a development `resdir` — then the
`res-preload/` tree of `hafen-res.jar`, then the `HashDirCache` under `Config.localdir()/data`, then the
resource server (`Resource.addurl`, tee'd into that cache). A source holding an older version than the
one demanded fails with `LoadException` and the next is asked, which is how the server's newer copy wins.

**A local copy under `src/haven/res/`** wins over the served class only when its
`@haven.FromResource(name, version)` matches the resource actually served; otherwise the loader warns and
uses the fetched code. So adopting a class is version-pinned by construction and a server-side bump
degrades to upstream behaviour rather than breaking. `Resource`'s own `main` has `get-code` (fetch the
source and write it annotated) and `find-updates` (report copies whose version has moved on).

## ⚠️ Every `public` member of `haven` is an ABI the server's code links against

Served classes are compiled against upstream's `haven` and resolved **by name and descriptor, at first
use, against this client's classes**: `ui/slot-info`'s `Probability` does `new ILabel(text, Window.cf)`,
and its `getstatic haven/Window.cf : Lhaven/Text$Furnace;` is answered by whatever `haven.Window` this
jar holds — or by `NoSuchFieldError`, thrown out of the widget's constructor the first time the player
opens that window: not at compile time, not at start-up. So for a `public` or `protected` member of `haven`:

- **"Nothing in the tree reads it" is not a deletion test.** The tree is not the corpus, and `grep`
  cannot see the server. A member upstream marks `@Deprecated` is the opposite of dead: it is kept
  because something outside the tree links to it (`Window.cf`/`ncf`, upstream's aliases of
  `Window.DefaultDeco.cf`/`ncf`, are exactly that).
- **The descriptor is the contract, not the name.** A field's type and a method's parameter *and
  return* types are what the JVM resolves: narrowing `Text.Furnace` to `Text.Forge`, or `void` to
  `boolean`, breaks the link as surely as a deletion. Modifiers do not: dropping `final`, adding
  `volatile` or `synchronized`, or moving a member **up** into a superclass (resolution walks the
  ancestors) all keep it.
- **It must keep working, not only existing.** A `public static final` whose value is captured at
  class init from something now built lazily is a `null` the compiler cannot see and the tree never
  reads; the served widget finds it, as an NPE, when the player does. `Window.cf`/`ncf` resolve their
  forge at the call for this reason.

What the descriptor rule does not cover, no tool covers: a member kept intact whose *behaviour* changed.

The reshapes of upstream's surface this client carries that no served code in the bundled jars or the
cache reaches — debt to know about, not bugs: `GameUI.FKeyBelt.beltkeys`; the seven `UI` input entry
points (`keydown`, `keyup`, `mousedown`, `mouseup`, `mousemove`, `mousehover`, `mousewheel`) returning
`boolean`; `UILoop.dispatch(UI)` → `dispatch(UI, UI)` and its four subclasses; the `OptWnd` panel
constructors without their `Panel` argument.

## See also

- [resources](resources.md) — what a `.res` carries: layers, `obst`/`neg`, and the `OD_RES` delta
- [services](services.md) — the adoption row: `get-code` + `@FromResource`, and `Resource.classres`
