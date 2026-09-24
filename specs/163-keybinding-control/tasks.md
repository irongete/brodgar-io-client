# 163 — tasks

> **Closing rule — the maintainer's, for this feature (2026-09-24).** It closes completely. Every boundary is drawn
> in `spec.md`'s *Out of scope* and every design choice in `plan.md`; a task's close report names **no** idea past the
> boundary and hands back **no** choice. Whatever a suite or a reading finds inside this feature's surface is fixed in
> the task that found it. The refusal texts are `plan.md`'s table, written exactly.
>
> **How a task reads.** Each checkbox below is one session; its section further down is the whole of it — the Java
> (code given in full where new), the docs (the exact before/after of every sentence), the suite (manifest and
> `main.lua` in full), the verification in order, and the log a passing run prints. Build what is written.

- [x] **163.1 — `hafen.ui():keybinding()`: the client's key button, built and bound to one of your hotkeys.**
      The panel's `SetButton` becomes a class anything can build; the bridge's `CKeybinding` wears it; `:bind(binding)`
      joins it to a live hotkey of the addon's and refuses everything else; the client implements API `1.1`.
      Claims criteria 1, 2, 3, 4 (the native tip; unprotected), 5 (`:value()`), 7, 10. Details: [163.1](#1631).
- [ ] **163.2 — A press on the key button assigns the key, and `Changed` says so.**
      The capture is wrapped: `Changed` once per press that moves the key, after the grab closes; a capture ends by
      itself when the button is disabled, unbound, hidden or its hotkey ends. Claims criteria 2 (shown at once, with a
      real key), 4, 5, 6, 8, 11. Details: [163.2](#1632).
- [ ] **163.3 — The client's own key button reads its key, and every page that sends a key to the Keybindings panel
      names the key button too.**
      `widget:value()` reads a borrowed `SetButton`; `:value(v)` and `:bind(binding)` on one are refused naming the
      write and the builder; the prose sweep of the impact set. Claims criterion 9. Details: [163.3](#1633).

---

## 163.1

### Core — `src/haven/OptWnd.java`

1. Delete the member `public class SetButton extends KeyMatch.Capture { … }` from `BindingPanel` — it is the last
   member of that class, from `public class SetButton` to its closing brace.
2. Insert the class below between the closing brace of `BindingPanel` and `public static class PointBind`, indented
   as `OptWnd`'s own members are (four spaces for the member, tabs inside, the upstream mix):

```java
    /* addon: (163.1) THE KEY BUTTON, a member of OptWnd rather than of BindingPanel. Upstream nests it in the panel,
     * an inner class, so it could only be built inside an Options window; as a static member it can stand anywhere --
     * hafen.ui():keybinding() builds this very class on an addon's own page. `cmd` is no longer final and may be null:
     * a button bound to nothing shows the key it last showed and writes nothing, and one an addon re-binds takes the
     * new binding. follow() is the display half of draw(), callable at once. BindingPanel.addbtn builds it as before. */
    public static class SetButton extends KeyMatch.Capture {
	public KeyBinding cmd;   // addon: (163.1) was final, and never null

	public SetButton(int w, KeyBinding cmd) {
	    super(w, (cmd == null) ? null : cmd.key());   // addon: (163.1) bound to nothing, it reads None
	    this.cmd = cmd;
	}

	public void set(KeyMatch key) {
	    super.set(key);
	    if(cmd != null)   // addon: (163.1)
		cmd.set(key);
	}

	/* addon: (163.1) show the binding's key WITHOUT writing it -- the test draw() made inline, by identity as
	 * before: KeyBinding.key() hands out one cached wrapper per key, so a new object means a new key. */
	public void follow() {
	    if((cmd != null) && (cmd.key() != key))
		super.set(cmd.key());
	}

	public void draw(GOut g) {
	    follow();   // addon: (163.1) was the same test, inline
	    super.draw(g);
	}

	protected KeyMatch mkmatch(KeyEvent ev) {
	    return(KeyMatch.forevent(ev, ~((cmd == null) ? 0 : cmd.modign)));   // addon: (163.1) null-safe
	}

	protected boolean handle(KeyEvent ev) {
	    if(ev.getKeyCode() == KeyEvent.VK_BACK_SPACE) {
		if(cmd != null) {   // addon: (163.1) bound to nothing, there is no default to go back to
		    cmd.set(null);
		    super.set(cmd.key());
		}
		return(true);
	    }
	    return(super.handle(ev));
	}

	public Object tooltip(Coord c, Widget prev) {
	    return(kbtt().tex());   // addon: (F3d)
	}
    }
```

3. `BindingPanel.addbtn` is not edited: its `new SetButton(UI.scale(175), cmd)` builds the moved class.
4. `src/haven/KeyBinding.java`, the comment above `private KeyMatch awarefor, aware;`: replace
   `OptWnd.SetButton.draw() compares key() by IDENTITY` with
   `OptWnd.SetButton.follow() (what its draw() runs every frame) compares key() by IDENTITY`.
   Nothing else in `haven` changes in this task.

### The bridge

**`src/io/brodgar/addon/CKeybinding.java`** — new, exactly:

```java
package io.brodgar.addon;

import haven.Coord;
import haven.GOut;
import haven.KeyBinding;
import haven.OptWnd;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;

/**
 * The adapter behind {@code hafen.ui():keybinding()} — the client's own key button, {@link OptWnd.SetButton}, the very
 * class each row of Options ▸ Game ▸ Keybindings ends in, joined by {@code widget:bind(binding)} to one of the hotkeys
 * its addon declared (spec {@code 163-keybinding-control}). The user presses it, then a key, and the key is assigned
 * exactly as on that row: the capture, Escape, Backspace, Delete and the exclusive, persisted edit are
 * {@code SetButton}'s and {@code KeyMatch.Capture}'s own, inherited unchanged.
 *
 * <p>{@link CtlButton}'s shape: the ownership contract over one {@link Owned.State}, the pending draw, the disabled
 * face, the {@code resize} redraw, the art's {@link #minsz}. What it adds is the binding: {@code cmd} is the
 * {@link KeyBinding} it is joined to, {@code null} while none, and a press starts a capture only while that binding's
 * hotkey is live in its addon ({@link #live}). {@code :type()} reads {@code "SetButton"}: {@link LuaWidget#typeName}
 * climbs past an {@link Owned.Control}.
 */
final class CKeybinding extends OptWnd.SetButton implements Owned.Control, Controls.Value {
    /** The Keybindings panel's own width for a key button ({@code BindingPanel.addbtn}'s {@code UI.scale(175)}), DESIGN px. */
    static final int DEF_W = 175;

    /** {@code widget:value(v)} on a key button — yours ({@link #value(LuaValue)}) and the client's ({@link LuaWidget}). */
    static final String VALUE_REFUSAL = "widget:value(v) on a key button is refused: the key it shows is its"
        + " binding's — binding:key(key) under client.settings writes it, and a press on the button is the user's"
        + " own edit. widget:value() reads the key it shows.";

    /** {@code widget:text(s)} on a key button ({@link Controls#text}). */
    static final String TEXT_REFUSAL = "widget:text(s) on a key button is refused: its caption is the key it shows,"
        + " and it follows the binding — a line beside it is a label: hafen.ui():label():text(s)";

    private final Owned.State own;

    /** What {@code Button.disable} was last told — {@code dis} is private there, and {@code disable} always redraws. */
    private boolean disabled;

    CKeybinding(Addon owner, int w) {
        super(w, null);
        this.own = new Owned.State(owner, this);
    }

    public Owned.State own() {
        return own;
    }

    /** The button's own art, as {@link CtlButton#minsz}: the two caps' widths, and {@code hs} (never the tall face). */
    public Coord minsz() {
        return Coord.of(bl.getWidth() + br.getWidth(), lg ? hl : hs);
    }

    // ------------------------------------------------------------------ the binding (widget:bind)

    /** Is the hotkey this button is joined to live in its addon right now? {@code false} while unbound. */
    boolean live() {
        KeyBinding kb = cmd;
        if(kb == null)
            return false;
        for(LuaKeyBind h : own.owner.keybinds) {
            if(h.alive && (h.binding == kb))
                return true;
        }
        return false;
    }

    /** {@code widget:bind()} — the Binding it is joined to, the object {@code keybindings:binding():get(name)} hands out. */
    LuaValue bound() {
        KeyBinding kb = cmd;
        return (kb == null) ? LuaValue.NIL : LuaBinding.of(own.owner, kb.id);
    }

    /**
     * The hotkey {@code v} names — a live one of {@code owner}'s — or the refusal naming what to do instead
     * (spec 163, criterion 3). Touches nothing, so a refused bind changes nothing. The order is the plan's: not a
     * Binding, then the addon's own prefix (live, else ended), then another addon's, then the client's, then a
     * handle taken before {@code keybindings:on} declared the name.
     */
    static KeyBinding hotkey(Addon owner, LuaValue v) {
        LuaBinding h = LuaBinding.resolve(v);
        if(h == null) {
            LuaOption o = LuaOption.resolve(v);
            if(o != null)
                throw new LuaError("widget:bind(binding): a key button joins one of your hotkeys, and '" + o.name
                    + "' is an option — hafen.ui():check(), :slider(), :dropdown(), :radio() or :entry() shows an"
                    + " option, and a key button takes the Binding keybindings:binding():get(name) hands you once"
                    + " keybindings:on(name, fn) has declared it");
            throw new LuaError("widget:bind(binding): a key button joins one of your hotkeys, the Binding"
                + " keybindings:binding():get(name) hands you once keybindings:on(name, fn) has declared it — got "
                + v.typename());
        }
        String id = h.id;
        String mine = HookApi.keyBindIdPrefix(owner.manifest.id);
        if(id.startsWith(mine)) {
            for(LuaKeyBind k : owner.keybinds) {
                if(k.alive && k.binding.id.equals(id))
                    return k.binding;
            }
            String name = id.substring(mine.length());
            throw new LuaError("widget:bind(binding): your hotkey '" + name + "' has ended — sub:off() ended it, or"
                + " your addon has not declared it since it reloaded. keybindings:on(\"" + name + "\", fn) declares it"
                + " again, and this Binding answers it at once.");
        }
        if(id.startsWith("addon/"))
            throw new LuaError("widget:bind(binding): '" + id + "' is another addon's hotkey — the user assigns its key"
                + " in Options ▸ Game ▸ Keybindings, or on that addon's own page. A key button joins a hotkey your own"
                + " addon declared with keybindings:on(name, fn).");
        if(KeyBinding.get(id) != null)
            throw new LuaError("widget:bind(binding): '" + id + "' is one of the client's own bindings — the user"
                + " assigns its key in Options ▸ Game ▸ Keybindings, and binding:key(key) under client.settings is the"
                + " write your code makes. A key button joins a hotkey your own addon declared with"
                + " keybindings:on(name, fn).");
        throw new LuaError("widget:bind(binding): the Binding '" + id + "' names no hotkey of yours — it was taken"
            + " before keybindings:on declared it, so it is the registry id as written. Take it after keybindings:on(\""
            + id + "\", fn): keybindings:binding():get(\"" + id + "\") then answers your own hotkey.");
    }

    /** {@code widget:bind(binding)}, {@code kb} from {@link #hotkey}: join it, and show its key at once. Caller holds the monitor. */
    void bind(KeyBinding kb) {
        this.cmd = kb;
        follow();
    }

    /** {@code widget:bind(nil)}: joined to nothing, the key it shows left shown. Caller holds the monitor. */
    void unbind() {
        this.cmd = null;
    }

    // ------------------------------------------------------------------ the value

    /** {@code widget:value()} — the key it shows, spelled as {@code binding:key()} spells it; {@code nil} for unbound. */
    public LuaValue value() {
        return LuaBinding.keyName(key);
    }

    /** {@code widget:value(v)} — refused: the key is its binding's, and {@code binding:key(key)} is the write. */
    public void value(LuaValue v) {
        throw new LuaError(VALUE_REFUSAL);
    }

    // ------------------------------------------------------------------ what the engine asks

    /** A press starts a capture only while the hotkey is live: built bare, unbound or ended, it does nothing. */
    public void click() {
        if(live())
            super.click();
    }

    /** The key the binding holds, shown every tick, drawn or hidden, so {@code :value()} is never a frame behind. */
    public void tick(double dt) {
        super.tick(dt);
        follow();
    }

    /** {@code widget:tooltip(s)} replaces the client's own tip ({@code kbtt}); {@code ""} nulls the field and brings it back. */
    public Object tooltip(Coord c, Widget prev) {
        return (tooltip != null) ? tooltip : super.tooltip(c, prev);
    }

    public void draw(GOut g) {
        if(own.pending())   // built this statement and not armed yet: a half-configured control paints NOTHING
            return;
        boolean dis = !Owned.effective(this);   // the client's disabled face follows the EFFECTIVE state (139.3)
        if(dis != disabled) {
            disabled = dis;
            disable(dis);
        }
        AddonText.enter();
        try {
            super.draw(Owned.dim(this, g));
        } finally {
            AddonText.exit();
        }
    }

    public void resize(Coord sz) {
        super.resize(sz);
        redraw();           // SIWidget caches the rasterised face; a resize without this keeps the old picture
    }
}
```

**`src/io/brodgar/addon/Controls.java`**
- Insert this builder directly after `static LuaValue entry(Addon owner, Varargs a) { … }`:

```java
    /**
     * {@code hafen.ui():keybinding()} — the client's own key button ({@link haven.OptWnd.SetButton}), bound to nothing
     * until {@code :bind(binding)} joins it to one of the addon's hotkeys (spec 163), at the Keybindings panel's width.
     */
    static LuaValue keybinding(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():keybinding() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():keybinding():bind(binding):size(w):parent(w)");
        UI u = UiApi.requireUi("keybinding");
        return UiApi.attach(u, owner, new CKeybinding(owner, Px.in(CKeybinding.DEF_W)));
    }
```

- In `static void text(Owned c, Widget w, String s)`, make this the first statement:
  `if(c instanceof CKeybinding) throw new LuaError(CKeybinding.TEXT_REFUSAL);   // 163.1: the caption is the key`
  (written as an `if` with the `throw` on its own line, as the file's other refusals are).

**`src/io/brodgar/addon/UiApi.java`** — directly after the `m.set("entry", …);` block:

```java
        // :keybinding() — 163.1, THE CLIENT'S KEY BUTTON: OptWnd.SetButton, the class each row of Options > Game >
        // Keybindings ends in, joined by :bind(binding) to one of this addon's hotkeys, so the user assigns the key
        // on the addon's own page. Built bare and configured by chained setters like every control here.
        m.set("keybinding", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Section.self(a.arg1(), "ui", "keybinding");
                return Controls.keybinding(owner, a);
            }
        });
```

**`src/io/brodgar/addon/LuaWidget.java`** — the `m.set("bind", …)` verb; add `import haven.KeyBinding;` among the
`haven` imports if absent.
- The read: replace `return Binding.read((w == null) ? null : ownedContent(owner, w));` with

```java
                if(!Args.passed(a, 2)) {
                    Owned rc = (w == null) ? null : ownedContent(owner, w);
                    return (rc instanceof CKeybinding) ? ((CKeybinding)rc).bound() : Binding.read(rc);   // 163.1
                }
```

- The write: between `LuaValue v = a.arg(2);` and `if(v.isnil())`, insert

```java
                if(c instanceof CKeybinding) {          // 163.1: a key button joins a hotkey of the addon's, not an option
                    CKeybinding keyButton = (CKeybinding)c;
                    KeyBinding hotkey = v.isnil() ? null : CKeybinding.hotkey(owner, v);   // every refusal first
                    synchronized(monitor(w)) {
                        if(hotkey == null)
                            keyButton.unbind();
                        else
                            keyButton.bind(hotkey);
                    }
                    return self;
                }
```

  The borrowed refusal above it (`c == null`) is not touched in this task.
- Update the verb's comment block: one line saying a key button takes a Binding of one of the addon's live hotkeys
  (163.1, `CKeybinding.hotkey`).

**`src/io/brodgar/addon/Binding.java`** — first statement of `static void bind(Addon owner, Owned c, Widget w, LuaValue v)`:

```java
        if(LuaBinding.resolve(v) != null)     // 163.1: a Binding is a hotkey's, and a key button is what joins one
            throw new LuaError(VERB + "(opt): a Binding joins a key button to one of your hotkeys —"
                + " hafen.ui():keybinding() — and a " + LuaWidget.typeName(w) + " binds to an option your addon"
                + " declared, what " + AddonOptions.HANDLE + ":boolean(name):default(v):add() and the three builders"
                + " beside it hand back.");
```

**`src/io/brodgar/addon/LuaBinding.java`** — `private static LuaValue keyName(KeyMatch km)` loses `private`.

**`src/io/brodgar/addon/Refusal.java`** — in the `for(String c : new String[] {…})` that feeds `uiKept`, add
`"keybinding"` directly after `"scrollbar"`.

**`src/io/brodgar/addon/ApiVersion.java`** — `CURRENT = new ApiVersion(1, 1)`. In `why`'s javadoc the three quoted
sentences end in `this client implements 1.1` (were `1.0`); in `label`'s javadoc the two labels read `client 1.1)`.

### The checker — `tools/refusalverbs.py`

- The tuple after `# every control hafen.ui() mints is a widget of yours`: add `"keybinding"` after `"scrollbar"`.
- `WIDGET_SETTERS`: add `"bind"` (it hands the widget back, so a chain through it walks on).

### The docs

**`docs/addons/api/ui/controls/interactive.md`**
- Line 3: `A button, a text entry, a checkbox, a radio, a slider, a scroll and a scrollbar: the [controls](README.md) the user drives.`
  → `A button, a text entry, a checkbox, a radio, a slider, a scroll, a scrollbar and a key button: the [controls](README.md) the user drives.`
- Insert after the Scrollbar section's rules table (its last row begins `| A list's own scrollbar |`) and before
  the `---` above *See Also*:

~~~markdown
## Key button

The client's own key button, the one each row of Options ▸ Game ▸ Keybindings ends in, joined to one of your hotkeys. The user presses it, then the key, and the key is assigned exactly as on that row. It needs `"api_version": "1.1"` in your [manifest](../../../manifest.md#the-api-version).

```lua
local keybindings = hafen.client():options():keybindings()
keybindings:on("toggle", function() hafen.log():write("toggled") end)
local toggle_binding = keybindings:binding():get("toggle")          -- after on(): your own hotkey
hafen.client():options():addon():panel(function(root)
  local toggle_row = hafen.ui():row():gap(8):parent(root)
  hafen.ui():label():parent(toggle_row):text("Toggle the window")
  hafen.ui():keybinding():parent(toggle_row):bind(toggle_binding)
end)
```

| Method | Returns | Permission | Description |
|---|---|---|---|
| `hafen.ui():keybinding()` | [`Widget`](../widget.md) | Unprotected | A key button bound to nothing. It reads `None`, and a press does nothing. |
| `key_button:bind(binding)` | `self` | Unprotected | Joins it to a hotkey your addon declared and has not ended: the [Binding](../../client/keybindings.md#the-binding-object) `keybindings:binding():get(name)` hands you. It shows that hotkey's key at once. A second call replaces the first. |
| `key_button:bind()` | `Binding \| nil` | Unprotected | The Binding it is joined to, the same object `keybindings:binding():get(name)` hands you. `nil` while none. |
| `key_button:bind(nil)` | `self` | Unprotected | Unbinds. The key stays shown, and a press does nothing. |
| `key_button:value()` | `string \| nil` | Unprotected | The key it shows, spelled as [`binding:key()`](../../client/keybindings.md#key-strings) spells it. `nil` for unbound. |

| Rule | Detail |
|---|---|
| The client's class | `:type()` reads `"SetButton"` and `:role()` `button`, so `@SetButton` names yours and the panel's alike, and a [`button`](../style/surfaces.md#button) rule dresses it. |
| Width | The panel's own, 175 design px, until `:size(w)`. The height is the button's art. |
| Your own hotkeys | A binding of the client's (`inv`) or of another addon's is refused, naming Options ▸ Game ▸ Keybindings, where the user assigns those. A Binding taken before `keybindings:on` declared the name is the registry id as written, not yours: refused, naming the order. A hotkey ended with `subscription:off()` is refused. A refused bind changes nothing. |
| A hotkey that ends while bound | The button keeps the Binding, and a press does nothing. Declaring the name again brings it back: the Binding is the same. |
| `:value(v)` | Refused, naming `binding:key(key)` under `client.settings`: the key is the binding's. |
| `:text(s)` | Refused: the caption is the key. A line beside it is a [label](display.md#label). |
| `:tooltip(s)` | Replaces the client's own tip, which names Escape, Backspace and Delete. `""` brings that tip back. |
| Unprotected | Building, binding and pressing it. A press is the user's own edit of their key, made by hand as on the panel's row. `binding:key(key)`, the write your code makes, stays under `client.settings`. |
~~~

- *See Also*, a new last line:

```text
- [Keybindings](../../client/keybindings.md) — the hotkey a key button assigns, and the Binding it takes.
```

**`docs/addons/api/ui/controls/README.md`** — every text below is the exact page text.
- Builders table, a new row directly after the `hafen.ui():scrollbar()` row:

```text
| `hafen.ui():keybinding()` | `Widget` | Unprotected | The client's key button, joined to one of your hotkeys. | [interactive](interactive.md#key-button) |
```

- Setters table, the row that begins `` | `:bind(opt)` / `:bind()` `` is replaced by:

```text
| `:bind(opt)`, `:bind(binding)` / `:bind()` | `self` / `Option \| Binding \| nil` | Unprotected | The [option of your addon's](../../client/addon.md#binding-a-control-shows-the-option) a checkbox, slider, dropdown, radio or entry shows and writes, or the [hotkey of yours](interactive.md#key-button) a key button assigns. `:bind(nil)` unbinds. |
```

- Rules table under it: a new row directly after the row that begins `` | `:bind(opt)` | ``:

```text
| `:bind(binding)` | A key button's alone: a [Binding](../../client/keybindings.md#the-binding-object) of a hotkey your addon declared and has not ended, its key shown at once. An Option given to a key button, and a Binding given to any other control, are refused naming the control that takes it. |
```

- The same table, the row that begins `` | `:value()` | `` is replaced by:

```text
| `:value()` | One verb for whatever a control holds: a [progress bar](display.md#progress-bar)'s fraction, a checkbox's boolean, an entry's string, a [key button](interactive.md#key-button)'s key. `nil` where it holds nothing. On a client control the write is [driving](../edit.md#driving-one-protected) and protected. A key button you built refuses the write: its key is its binding's. |
```

- Sizing, in the `:size(w)` row: `Answered by the button, text entry, checkbox, dropdown, slider and separator.` becomes
  `Answered by the button, key button, text entry, checkbox, dropdown, slider and separator.`
- *See Also*: the line `- [Interactive](interactive.md) — button, text entry, checkbox, radio, slider, scroll, scrollbar.`
  becomes `- [Interactive](interactive.md) — button, text entry, checkbox, radio, slider, scroll, scrollbar, key button.`

**`docs/addons/api/ui/writes.md`**
- The `:text(s)` row, owned column: `Writes a [control](controls/README.md)'s caption.` becomes
  `Writes a [control](controls/README.md)'s caption. A key button refuses: its caption is the key it shows.`
- The `:value(v)` row, owned column: `Writes what a [control](controls/README.md#setters) holds.` becomes
  `Writes what a [control](controls/README.md#setters) holds. A key button refuses: its key is its binding's.`
- The row that begins `` | `:bind(opt)` | `` is replaced by:

```text
| `:bind(opt)`, `:bind(binding)` | Joins a control to [an option your addon declared](../client/addon.md#binding-a-control-shows-the-option), or a [key button](controls/interactive.md#key-button) to a hotkey of yours. The control takes the option's value, or shows the hotkey's key. The user moving it writes the option, and the user's press on a key button assigns the key. A write to the option moves it. `:bind(nil)` unbinds. | Raises. `:bind()` reads `nil`. Driving a client control is `:value(v)`. |
```

**`docs/addons/api/README.md`** — the index row for *Interactive controls*: its description
`A button, a text entry, a checkbox, a radio, a slider, a scroll, a scrollbar.` becomes
`A button, a text entry, a checkbox, a radio, a slider, a scroll, a scrollbar, a key button.`

**`docs/addons/manifest.md`**
- The example at the top: `"api_version": "1.0",` becomes `"api_version": "1.1",`.
- The `api_version` field row: `This client implements API` followed by `1.0` in backticks becomes the same with `1.1`.
- The first rules table of *The API version*: a new row directly after the row that begins `| No read from Lua |`:

```text
| What needs `1.1` | [`hafen.ui():keybinding()`](api/ui/controls/interactive.md#key-button), the key button. Everything else these pages describe is in `1.0`. |
```

- The *You declare* table: the row that begins `` | `"1.0"` | `` is replaced by the first line below, and in each of
  the other three rows `this client implements 1.0` becomes `this client implements 1.1`:

```text
| `"1.0"`, `"1.1"` | Its generation, an edition it has. | Loads. |
```

- The *Out of date is not an error* row: `outdated (API 2.0, client 1.0)` becomes `outdated (API 2.0, client 1.1)`,
  and `outdated (no api_version, client 1.0)` becomes `outdated (no api_version, client 1.1)`.

**`"api_version": "1.0"` becomes `"api_version": "1.1"`**, the value only, in `docs/addons/getting-started.md` (both
manifests, lines 25 and 143), `docs/addons/api/http.md` (25), `docs/addons/api/websocket.md` (23),
`docs/addons/api/voice/README.md` (30) and `docs/addons/guides/libraries.md` (10 and 44).

**`docs/client/services.md`**
- In the keybinding-registry row (line 10), two replacements; each pair is *before*, then *after*:

```text
`BindingPanel.SetButton.draw` compares `key()` by **identity**
`OptWnd.SetButton.follow` (which its `draw` runs every frame) compares `key()` by **identity**

`KeyMatch.Capture` (which `BindingPanel.SetButton` extends)
`KeyMatch.Capture` (which `OptWnd.SetButton` extends)
```

- A new gotcha bullet directly after **The keybind panel lists bindings by hand**:

```text
- **The key button a row ends in is not the panel's own** (fork): `OptWnd.SetButton` is a static member of
  `OptWnd` (upstream nests it in `BindingPanel`), so it can stand in any tree. Its `cmd` is mutable and may be
  `null` — `set` then writes nothing and Backspace does nothing — and `follow()` shows `cmd.key()` without writing
  it; `draw` runs it every frame. ⚠️ `SetButton.set(KeyMatch)` is the **write** (`Capture.set`, then `cmd.set`):
  called to display a key, it makes a binding on its default an assignment. `KeyMatch.Capture.click` toggles the key
  grab — open, the caption reads `...`; a second click closes it and restores the caption — and `keydown` closes it
  only when `handle` answers `true`, which a bare modifier never does.
```

Discharges the impact-set rows `binds to…` (`controls/README.md`:48, 54; `writes.md`:37), `Answered by the button…`
(`controls/README.md`:62), `SetButton|BindingPanel|KeyMatch.Capture` (`services.md`:10, 102), `implements API|"1.0"`
(all), and `a scroll, a scrollbar…` (all).

### Its suite — `addons/163-keybinding-control.1/`

`manifest.json`:

```json
{
  "name": "163.1 — the key button, built and bound",
  "id": "163-keybinding-control.1",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 163.1: hafen.ui():keybinding() builds the client's key button, binds it to a hotkey of the suite's, reads it back and refuses what it does not take.",
  "api_version": "1.1",
  "files": ["main.lua"]
}
```

`main.lua`, exactly:

```lua
-- 163.1 — the key button, built and bound. Self-checking suite.

local pass, fail, manual = 0, 0, 0
local last                                   -- the previous run's window and timer, taken down by the next run

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

-- The message a refused call raised, its chunk prefix stripped; nil when the call did not raise.
local function refusal(fn, ...)
  local ok, err = pcall(fn, ...)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(message, text)
  return (message ~= nil) and (message:find(text, 1, true) ~= nil)
end

local function run()
  if last then
    last.poll:cancel()
    if last.window:exists() then last.window:destroy() end
  end
  pass, fail, manual = 0, 0, 0

  local keybindings = hafen.client():options():keybindings()
  local noop = function() end
  keybindings:on("first", noop)
  keybindings:on("second", noop)
  local dormant_hotkey = keybindings:on("dormant", noop)
  local first_binding = keybindings:binding():get("first")
  local second_binding = keybindings:binding():get("second")
  local dormant_binding = keybindings:binding():get("dormant")

  local window = hafen.ui():window():title("163.1 key button"):position(40, 80)
  local column = hafen.ui():column():gap(4):parent(window)
  local function key_row(caption)
    local row = hafen.ui():row():gap(8):parent(column)
    hafen.ui():label():parent(row):text(caption)
    return hafen.ui():keybinding():parent(row)
  end
  local first_button = key_row("first")
  local tipped_button = key_row("tipped")
  local bare_button = key_row("bare")
  local dormant_button = key_row("dormant")
  window:pack()
  tipped_button:bind(first_binding)
  dormant_button:bind(dormant_binding)
  dormant_hotkey:off()                       -- ended while bound: the button keeps its Binding

  -- 1. a key button built bare
  local bare_size = bare_button:size()
  check(bare_button:type() == "SetButton" and bare_button:role() == "button" and bare_size.w == 175
          and bare_button:text() == "None" and bare_button:value() == nil and bare_button:bind() == nil,
        "a bare key button is the client's: SetButton, button, 175 wide, reads None, holds nil, bound to nothing",
        ("type=%s role=%s w=%s text=%s value=%s bind=%s"):format(tostring(bare_button:type()),
          tostring(bare_button:role()), tostring(bare_size.w), tostring(bare_button:text()),
          tostring(bare_button:value()), tostring(bare_button:bind())))

  -- 2. :size(w)
  local sized_button, plain_button = hafen.ui():keybinding():size(120), hafen.ui():button()
  local sized, plain = sized_button:size(), plain_button:size()
  sized_button:destroy()
  plain_button:destroy()
  check(sized.w == 120 and sized.h == plain.h, "size(w) sets its width and keeps the height of the client's button art",
        ("w=%s h=%s button h=%s"):format(tostring(sized.w), tostring(sized.h), tostring(plain.h)))

  -- 3. an argument
  local argument_message = refusal(function() return hafen.ui():keybinding("first") end)
  check(says(argument_message, "takes no arguments"), "an argument to hafen.ui():keybinding() is refused",
        argument_message or "<no error>")

  -- 4. joined, replaced, unbound, and bound through an end
  first_button:bind(first_binding)
  local joined = first_button:bind() == first_binding
  local shown = first_button:text() == (first_binding:key() or "None") and first_button:value() == first_binding:key()
  first_button:bind(second_binding)
  local replaced = first_button:bind() == second_binding
  local caption = first_button:text()
  first_button:bind(nil)
  local unbound = first_button:bind() == nil and first_button:text() == caption
  first_button:bind(first_binding)
  local kept = dormant_button:bind() == dormant_binding
  check(joined and shown and replaced and unbound and kept,
        "bind(binding) joins it and reads back the same Binding, a second bind replaces it, bind(nil) keeps the key shown, and a hotkey ended while bound keeps its Binding",
        ("joined=%s shown=%s replaced=%s unbound=%s kept=%s"):format(tostring(joined), tostring(shown),
          tostring(replaced), tostring(unbound), tostring(kept)))

  -- 5. what is not a live hotkey of yours
  local late_name = "late" .. string.format("%d", os.time())
  local early_binding = keybindings:binding():get(late_name)     -- before on(): the registry id as written
  local late_hotkey = keybindings:on(late_name, noop)
  local cases = {
    { "client", keybindings:binding():get("inv"), "client's own" },
    { "other addon", keybindings:binding():get("addon/163-other-addon/toggle"), "another addon's" },
    { "taken before", early_binding, "taken before keybindings:on" },
    { "ended", dormant_binding, "has ended" },
  }
  local missed
  for _, case in ipairs(cases) do
    local message = refusal(first_button.bind, first_button, case[2])
    if (missed == nil) and not says(message, case[3]) then missed = case[1] .. ": " .. tostring(message) end
  end
  late_hotkey:off()
  local still = first_button:bind() == first_binding
  check(missed == nil and still,
        "a Binding that is not a live hotkey of yours is refused, naming what to do: the client's, another addon's, one taken before keybindings:on, one ended",
        missed or "a refused bind moved it")

  -- 6. an Option on the key button, a Binding on a check
  local options = hafen.client():options():addon()
  local flag_option = options:option():get("flag") or options:boolean("flag"):default(false):add()
  local option_message = refusal(first_button.bind, first_button, flag_option)
  local check_box = hafen.ui():check()
  local check_message = refusal(check_box.bind, check_box, first_binding)
  check_box:destroy()
  check(says(option_message, "keybindings:binding():get(name)") and says(check_message, "hafen.ui():keybinding()"),
        "an Option on the key button and a Binding on a check are refused, each naming the control that takes it",
        "option: " .. tostring(option_message) .. " / check: " .. tostring(check_message))

  -- 7. :value(v) and :text(s)
  local value_message = refusal(first_button.value, first_button, "F5")
  local text_message = refusal(first_button.text, first_button, "Go")
  check(says(value_message, "binding:key(key)") and says(value_message, "client.settings")
          and says(text_message, "hafen.ui():label()"),
        "value(v) and text(s) are refused, naming binding:key(key) under client.settings and a label",
        "value: " .. tostring(value_message) .. " / text: " .. tostring(text_message))

  -- 8. :tooltip(s)
  tipped_button:tooltip("Bound by the 163.1 suite")
  local cleared_button = hafen.ui():keybinding():tooltip("gone"):tooltip("")
  local tip, cleared = tipped_button:tooltip(), cleared_button:tooltip()
  cleared_button:destroy()
  check(tip == "Bound by the 163.1 suite" and cleared == nil, "tooltip(s) reads back, and an empty one clears it",
        "tip=" .. tostring(tip) .. " cleared=" .. tostring(cleared))

  -- the page holds the same control
  options:panel(function(root)
    local row = hafen.ui():row():gap(8):parent(root)
    hafen.ui():label():parent(row):text("first")
    hafen.ui():keybinding():parent(row):bind(first_binding)
  end)

  manualCheck("hover the key button 'first'", "the client's tip, three yellow lines: Escape, Backspace, Delete")
  manualCheck("hover the key button 'tipped'", "only the line: Bound by the 163.1 suite")
  manualCheck("open Options > AddOns > 163.1 — the key button, built and bound",
              "a row 'first' ending in a key button that reads what 'first' reads in the suite's window")
  manualCheck("click the key buttons 'bare' and 'dormant', once each", "both keep reading None")

  -- 9. a press that does nothing: the release seen, then half a second of captions
  local released = { bare = false, dormant = false }
  local captured = false
  bare_button:on("MouseUp", function(event) if event:button() == 1 then released.bare = true end end)
  dormant_button:on("MouseUp", function(event) if event:button() == 1 then released.dormant = true end end)
  local started, settle = os.time(), nil
  local poll
  poll = hafen.timer():every(0.05, function()
    if bare_button:text() == "..." or dormant_button:text() == "..." then captured = true end
    if released.bare and released.dormant and settle == nil then settle = 0 end
    if settle ~= nil then settle = settle + 1 end
    if (settle ~= nil and settle >= 10) or (os.time() - started >= 180) then
      poll:cancel()
      local both = released.bare and released.dormant
      check(both and not captured,
            "a press on a key button bound to nothing, or to a hotkey that has ended, began no capture",
            (not both) and ("not both clicked within 180 s (bare=" .. tostring(released.bare) .. " dormant="
              .. tostring(released.dormant) .. ")") or "one of them read ...")
      hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
    end
  end)
  last = { window = window, poll = poll }
end

hafen.console():on("t163-1", function() hafen.timer():after(0, run) end)
```

What proves what: check 1 is criterion 1 (the class, the role, the width, the bare reads); 2 the width `:size(w)`
sets and the art's height; 3 the builder's refusal; 4 criterion 2 (`==`, replace, unbind keeping the key shown, an
end keeping the Binding) and criterion 5's `:value()` read; 5–7 criterion 3, each refusal by the substring
`plan.md` fixes, and "a refused bind changes nothing" by `still`; 8 criterion 7's write and clear; the hovers are
criterion 4's native tip and criterion 7's replacement, which only an eye reads; the page line is the What & why's
"on its page"; 9 is "a press does nothing" for `cmd == null` (`bare`) and for a Binding whose hotkey ended
(`dormant`) — `live()`'s two branches. The suite declares no permission: every call it makes is unprotected, which
is criterion 4's last sentence. It runs at all only on a client implementing `1.1` (its manifest declares it), which
is criterion 10; `docverbs.py` holds the docs to the literal.

A passing run prints, in this order: eight `[pass]` lines (checks 1–8), four `[manual]` lines, one `[pass]` (check
9), then `[summary] 9 pass, 0 fail, 4 manual`.

### Verification, in this order

1. `rm -rf build/classes && ant hafen-client` → `BUILD SUCCESSFUL`.
2. `python tools/docverbs.py` and `python tools/refusalverbs.py`, each run bare, each exit `0`. docverbs must print
   `held the API version the docs state (1 sentence, 5 example(s)) to ApiVersion.CURRENT = 1.1`.
3. `grep -rn "BindingPanel.SetButton" docs/ src/` → no hit. `grep -rn '"api_version": "1.0"' docs/` → no hit.
4. DOCUMENTATION.md §11 over every page this task wrote: no single-letter local in a code block; every new table
   row has Returns and Permission; the links `interactive.md#key-button`, `../../client/keybindings.md#the-binding-object`,
   `#key-strings`, `../../../manifest.md#the-api-version`, `../style/surfaces.md#button` and `display.md#label`
   resolve. Report the counts.
5. `java -cp lib/brodgar/luaj-jse-3.0.1.jar luac -p addons/163-keybinding-control.1/main.lua`, then `rm -f luac.out`.
6. `rm -f build/hafen.jar && ant bin` (it copies `addons/` into `bin/addons/`); confirm
   `bin/addons/163-keybinding-control.1/main.lua` is the file just written. After any fix round, copy the folder
   into `bin/addons/` again.
7. Report the one command, `:t163-1`, after a full client restart.

---

## 163.2

### Core — `src/haven/KeyMatch.java`

In `Capture`, directly after `click()`:

```java
	/* addon: (163.2) whether the key grab is open -- the caption reads "..." while it is */
	public boolean capturing() {
	    return(grab != null);
	}

	/* addon: (163.2) close an open key grab and show the key again: what Escape does, without a key */
	public void cancel() {
	    if(grab != null) {
		grab.remove();
		grab = null;
		change(namefor(this.key));
	    }
	}
```

Nothing else in `haven` changes in this task.

### The bridge — `src/io/brodgar/addon/CKeybinding.java`

1. Add `Controls.Change` to the implemented interfaces:
   `final class CKeybinding extends OptWnd.SetButton implements Owned.Control, Controls.Value, Controls.Change`.
   `LuaWidget.widgetKeys` then lists `Changed` for it with no edit there.
2. Add `import java.awt.event.KeyEvent;`.
3. Add the field and the predicate:

```java
    /** A press that moved the key, fired as Changed once the grab has closed — Java null while none, NIL for unbound. */
    private LuaValue moved;

    /** Would a press here reach the key: joined to a live hotkey, enabled through every Owned above it, and shown? */
    boolean answers() {
        return live() && Owned.effective(this) && tvisible();
    }
```

4. `bind` and `unbind` cancel first:

```java
    void bind(KeyBinding kb) {
        cancel();           // 163.2: a capture belongs to the binding it began on
        this.cmd = kb;
        follow();
    }

    void unbind() {
        cancel();           // 163.2: unbound, the capture ends and nothing is assigned
        this.cmd = null;
    }
```

5. `click` lets a second press through while a capture is open:

```java
    /** A press starts a capture only while the button answers; while one is open, a press is what closes it. */
    public void click() {
        if(capturing() || answers())
            super.click();
    }
```

6. `tick` ends a capture that no longer answers, then follows:

```java
    /** A capture that no longer answers ends by itself; then the key the binding holds is shown, drawn or hidden. */
    public void tick(double dt) {
        super.tick(dt);
        if(capturing() && !answers())
            cancel();
        follow();
    }
```

7. The press:

```java
    /**
     * A key pressed while capturing. Where the button no longer answers, the capture ends and nothing is assigned —
     * answering false, because the grab is already closed. Otherwise the client's own handling runs, and a key that
     * moved is kept for {@link #keydown} to fire once the grab has closed.
     */
    protected boolean handle(KeyEvent ev) {
        if(!answers()) {
            cancel();
            return(false);
        }
        KeyBinding kb = cmd;
        LuaValue before = LuaBinding.keyName(kb.key());
        boolean done = super.handle(ev);
        LuaValue after = LuaBinding.keyName(kb.key());
        if(!after.eq_b(before))
            moved = after;
        return(done);
    }

    /** {@code Changed} fires here, after {@code Capture.keydown} has closed the grab, so a handler may unbind or destroy the button. */
    public boolean keydown(KeyDownEvent ev) {
        boolean took = super.keydown(ev);
        LuaValue key = moved;
        if(key != null) {
            moved = null;
            Controls.fire(this, LuaOption.CHANGED, key);
        }
        return(took);
    }
```

8. Update the class javadoc: one paragraph on the press — `Changed` once per press that moves the key, after the
   grab closes; a capture ends by itself when the button stops answering ({@link #answers}).

### The docs

**`docs/addons/api/ui/controls/interactive.md`** — directly after the *Key button* rules table 163.1 wrote, before
the `---`:

~~~markdown
### A press

The user presses the key button and it reads `...`. The next key they press, with its modifiers, is the hotkey's key: the same persisted, exclusive edit as the panel's row, so the key is taken off whichever binding was assigned it.

| Pressed after `...` | What happens |
|---|---|
| A key, with any modifiers | Assigned. |
| `Escape` | Cancels. The key stays. |
| `Backspace` | Back to the client's default, which for your hotkey is unbound. |
| `Delete` | Unbound. |
| A modifier alone | Nothing yet: the button waits for the key the modifier goes with. |

| Method | Returns | Permission | Description |
|---|---|---|---|
| `key_button:on("Changed", fn)` | `Sub` | Unprotected | `fn(key)` once per press that moves the key, after the capture has closed: the key it now shows, `nil` for unbound. |

```lua
local key_button = hafen.ui():keybinding():bind(toggle_binding)
key_button:on("Changed", function(key) hafen.log():write("toggle: " .. tostring(key)) end)
```

| Rule | Detail |
|---|---|
| A press that leaves the key | `Escape`, the key it already had, or `Backspace` on a hotkey already on its default: nothing fires. |
| A change made elsewhere | In Options ▸ Game ▸ Keybindings, on another key button, or with `binding:key(key)`: the button shows it on its next frame, and nothing fires. |
| The same key both places | A key assigned here shows in Options ▸ Game ▸ Keybindings, and one assigned there shows here. |
| Disabled | A disabled key button, `:enabled(false)` or inside a disabled [column](../column.md), takes no key. |
| A capture ends by itself | While it reads `...`, disabling it, unbinding it, hiding it or ending its hotkey ends the capture: nothing is assigned and nothing fires. |
~~~

**`docs/addons/api/ui/controls/README.md`** — *Subscribing*, a new row directly after the row that begins
`` | `:check()`, `:radio()` ``:

```text
| `:keybinding()` | `Changed` | The key it now shows, `nil` for unbound — [interactive](interactive.md#a-press). |
```

**`docs/client/services.md`** — append to the bullet 163.1 added (*The key button a row ends in is not the panel's
own*):

```text
  `Capture.capturing()` answers whether the grab is open, and `cancel()` closes it and restores the caption: Escape
  without a key (fork addition). ⚠️ The fork's disabled cut runs inside a key grab's dispatch too — `UI.dispatch`
  walks `grabs` first, but `UI.WidgetGrab.handle` is `Event.dispatch`, which is `Widget.handle`, whose first line is
  the cut — so a capture button the cut disables lets the key pass on to the tree with its caption still `...`
  until something calls `cancel()`.
```

`docs/addons/api/threading.md` is read, not written: its row *A control's `"Pressed"`, `"Changed"`, …* already covers
the key button's `Changed` (the press; its own tree only). Discharges the impact-set row `:check()`, `:radio()`
(`controls/README.md`:84).

### Its suite — `addons/163-keybinding-control.2/`

`manifest.json`:

```json
{
  "name": "163.2 — a press on the key button",
  "id": "163-keybinding-control.2",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 163.2: a press on the key button assigns the key as the Keybindings panel does, fires Changed once per moving press, and a capture ends by itself when the button stops answering.",
  "api_version": "1.1",
  "files": ["main.lua"]
}
```

`main.lua`, exactly:

```lua
-- 163.2 — a press on the key button. Self-checking suite.

local pass, fail, manual = 0, 0, 0
local last                                   -- the previous run's window and timer, taken down by the next run

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function run()
  if last then
    last.poll:cancel()
    if last.window:exists() then last.window:destroy() end
  end
  pass, fail, manual = 0, 0, 0

  local keybindings = hafen.client():options():keybindings()
  local noop = function() end
  keybindings:on("first", noop)
  local cancel_hotkey = keybindings:on("cancel", noop)
  keybindings:on("greyed", noop)
  local first_binding = keybindings:binding():get("first")
  local cancel_binding = keybindings:binding():get("cancel")
  local greyed_binding = keybindings:binding():get("greyed")

  local window = hafen.ui():window():title("163.2 key button"):position(40, 80)
  local column = hafen.ui():column():gap(4):parent(window)
  local function key_row(parent, caption, binding)
    local row = hafen.ui():row():gap(8):parent(parent)
    hafen.ui():label():parent(row):text(caption)
    return hafen.ui():keybinding():parent(row):bind(binding)
  end
  local first_button = key_row(column, "first", first_binding)
  local cancel_button = key_row(column, "cancel", cancel_binding)
  local greyed_column = hafen.ui():column():gap(4):parent(column)
  local greyed_button = key_row(greyed_column, "greyed", greyed_binding)
  greyed_column:enabled(false)
  window:pack()

  -- the keys: the first one differs from whatever an earlier run left on 'first'
  local step1_key = (first_binding:key() == "Shift+Ctrl+F12") and "Shift+Ctrl+F10" or "Shift+Ctrl+F12"
  local step2_key = "Shift+Ctrl+F11"
  local first_changed = {}
  first_button:on("Changed", function(key) first_changed[#first_changed + 1] = (key == nil) and "<nil>" or key end)
  local cancel_fired = 0
  cancel_button:on("Changed", function() cancel_fired = cancel_fired + 1 end)
  local cancel_start_key, cancel_start_assigned = cancel_binding:key(), cancel_binding:assigned()

  manualCheck("click the greyed key button 'greyed'", "nothing happens: it stays greyed and reads None")
  manualCheck("assign " .. step1_key .. " with the key button 'first': click it, then press the keys together",
              "it reads " .. step1_key)
  manualCheck("in Options > Game > Keybindings, section '163.2 — a press on the key button', give 'first' " .. step2_key,
              "before you do, that row reads " .. step1_key)
  manualCheck("click 'first' and press Escape", "it reads " .. step2_key .. " again")
  manualCheck("click 'first' and press Delete", "it reads None")
  manualCheck("click 'first' and press Backspace", "it still reads None")
  manualCheck("click the key button 'cancel' four times, a second apart", "each click shows ... for a moment, then None again")

  -- 'first': five steps, scored in the order the manual lines ask for them
  local function state()
    return ("changed={%s} key=%s assigned=%s value=%s"):format(table.concat(first_changed, ","),
      tostring(first_binding:key()), tostring(first_binding:assigned()), tostring(first_button:value()))
  end
  local steps = {
    { what = "the press assigned " .. step1_key .. ", fired Changed once with it, and a key button bound to 'first' afterwards shows it at once",
      score = function()
        local probe = hafen.ui():keybinding():bind(first_binding)
        local at_once = probe:text() == step1_key and probe:value() == step1_key
        probe:destroy()
        return #first_changed == 1 and first_changed[1] == step1_key and first_binding:key() == step1_key
          and first_binding:assigned() and first_button:value() == step1_key and at_once,
          state() .. " at once=" .. tostring(at_once)
      end },
    { what = "a key assigned in the panel showed on the button within a frame, and fired nothing", panel = true,
      score = function()
        return #first_changed == 0 and first_binding:key() == step2_key and first_button:value() == step2_key, state()
      end },
    { what = "Escape ended the capture, left the key and fired nothing",
      score = function()
        return #first_changed == 0 and first_binding:key() == step2_key and first_binding:assigned()
          and first_button:value() == step2_key, state()
      end },
    { what = "Delete unbound it and fired Changed once with nil",
      score = function()
        return #first_changed == 1 and first_changed[1] == "<nil>" and first_binding:key() == nil
          and first_binding:assigned() and first_button:value() == nil, state()
      end },
    { what = "Backspace put it back on its default and fired nothing",
      score = function()
        return #first_changed == 0 and first_binding:key() == nil and not first_binding:assigned()
          and first_button:value() == nil, state()
      end },
  }
  local step, was_capturing, settle, panel_polls = 1, false, nil, nil
  local function score_step()
    local ok, got = steps[step].score()
    check(ok, steps[step].what, got)
    first_changed = {}
    step, settle, panel_polls = step + 1, nil, nil
  end

  -- 'cancel': four captures, each ended by one cause; the first cause re-declares the hotkey, so the three
  -- captures after it prove the button answers again
  local causes = {
    { name = "hotkey ended", apply = function() cancel_hotkey:off() end,
      restore = function() cancel_hotkey = keybindings:on("cancel", noop) end },
    { name = "disabled", apply = function() cancel_button:enabled(false) end,
      restore = function() cancel_button:enabled(true) end },
    { name = "unbound", apply = function() cancel_button:bind(nil) end,
      restore = function() cancel_button:bind(cancel_binding) end },
    { name = "hidden", apply = function() cancel_button:visible(false) end,
      restore = function() cancel_button:visible(true) end },
  }
  local cause, acting, waited, ended = 1, false, 0, {}
  local cancel_what = "each capture ended by itself when its hotkey ended, when the button was disabled, unbound and hidden, the button answered again once its hotkey was declared again, and nothing was assigned"
  local function cancel_state()
    local parts = {}
    for index, entry in ipairs(causes) do parts[#parts + 1] = entry.name .. "=" .. tostring(ended[index]) end
    return table.concat(parts, " ") .. (" key=%s assigned=%s fired=%d"):format(tostring(cancel_binding:key()),
      tostring(cancel_binding:assigned()), cancel_fired)
  end
  local function score_cancel()
    check(ended[1] and ended[2] and ended[3] and ended[4] and cancel_binding:key() == cancel_start_key
            and cancel_binding:assigned() == cancel_start_assigned and cancel_fired == 0,
          cancel_what, cancel_state())
  end

  local greyed_captured, finished = false, false
  local started = os.time()
  local poll
  local function finish()
    finished = true
    poll:cancel()
    for index = step, #steps do check(false, steps[index].what, "not reached in 600 s") end
    if cause <= #causes then check(false, cancel_what, "not reached in 600 s: " .. cancel_state()) end
    check(not greyed_captured, "the greyed key button never began a capture", "it read ...")
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end
  poll = hafen.timer():every(0.05, function()
    if finished then return end
    if greyed_button:text() == "..." then greyed_captured = true end
    if step <= #steps then
      local capturing = first_button:text() == "..."
      if settle == nil and not capturing and (was_capturing or #first_changed > 0) then settle = 0 end
      was_capturing = capturing
      if settle ~= nil then
        settle = settle + 1
        if settle >= 2 then score_step() end        -- Changed lands just after the caption: score 0.1 s on
      elseif steps[step].panel and not capturing and first_binding:key() ~= step1_key then
        panel_polls = (panel_polls or 0) + 1
        if first_button:value() == first_binding:key() or panel_polls >= 2 then score_step() end
      end
    end
    if cause <= #causes then
      local capturing = cancel_button:text() == "..."
      if not acting then
        if capturing then
          causes[cause].apply()
          acting, waited = true, 0
        end
      else
        waited = waited + 1
        if (not capturing) or waited >= 10 then
          ended[cause] = not capturing
          causes[cause].restore()
          acting, cause = false, cause + 1
          if cause > #causes then score_cancel() end
        end
      end
    end
    if (step > #steps and cause > #causes) or (os.time() - started >= 600) then finish() end
  end)
  last = { window = window, poll = poll }
end

hafen.console():on("t163-2", function() hafen.timer():after(0, run) end)
```

What proves what: the five `first` steps are criterion 4 (the capture reads `...`, a key with its modifiers is
assigned — a chord whose Shift and Ctrl arrive first proves a bare modifier waits — Escape, Delete, Backspace, each
by the binding's own reads) and criterion 5 (`Changed` exactly once per moving press, with the key or `nil`; none on
Escape, on Backspace from `None`, or for the panel's change; the button showing the panel's key within a frame);
the probe in step 1 is criterion 2's "shows the key at once" with a real key; the panel line and step 2 are
criteria 8 and 11 (the panel's row shows what the page assigned; the panel assigns, and the page follows); `cancel`
is criterion 6's four causes, and — the first cause re-declaring the hotkey — criterion 2's "until the name is
declared again"; `greyed`, in a disabled column, is criterion 6's first sentence. `Changed` firing after the grab
closes (criterion 5) is verified by reading `CKeybinding.keydown`, which runs `super.keydown` first — no suite can
see a grab.

The keys are Shift+Ctrl+F12, F11 and F10: no binding the client ships defaults to any of them (the action bar's are
the number row), and the run leaves `first` back on its default. A passing run prints the seven `[manual]` lines,
then six `[pass]` lines as the steps are done (the `cancel` line whenever its fourth capture ends), then `[pass] the
greyed key button never began a capture` and `[summary] 7 pass, 0 fail, 7 manual`.

### Verification, in this order

1. `rm -rf build/classes && ant hafen-client` → `BUILD SUCCESSFUL`.
2. `python tools/docverbs.py`, `python tools/refusalverbs.py`: each exit `0`, run bare.
3. DOCUMENTATION.md §11 over the two pages written; the anchors `interactive.md#a-press` and `../column.md` resolve.
4. `java -cp lib/brodgar/luaj-jse-3.0.1.jar luac -p addons/163-keybinding-control.2/main.lua`, then `rm -f luac.out`.
5. `rm -f build/hafen.jar && ant bin`; confirm `bin/addons/163-keybinding-control.2/main.lua` is the file just
   written; after any fix round, copy the folder into `bin/addons/` again.
6. Report the one command, `:t163-2`, after a full client restart.

---

## 163.3

### The bridge — `src/io/brodgar/addon/LuaWidget.java`

Add `import haven.OptWnd;` among the `haven` imports.

1. `static LuaValue value(Widget w)` — the first arm inside its `try`:

```java
            if(w instanceof OptWnd.SetButton)      // 163.3: a key button holds its key, as binding:key() spells it
                return LuaBinding.keyName(((OptWnd.SetButton)w).key);
```

2. The `m.set("value", …)` write — replace

```java
                Owned c = ownedContent(owner, h.wdg);
                if(c == null)                             // BORROWED (or gone): the act, and its gate
                    AddonManager.requirePermission(AddonManager.current(), Permission.WIDGET_VALUE);
```

   with

```java
                Owned c = ownedContent(owner, h.wdg);
                if((c == null) && (h.wdg instanceof OptWnd.SetButton))   // 163.3: nothing drives a key button — its key
                    throw new LuaError(CKeybinding.VALUE_REFUSAL);        //   is its binding's; asked of the handle alone
                if(c == null)                             // BORROWED (or gone): the act, and its gate
                    AddonManager.requirePermission(AddonManager.current(), Permission.WIDGET_VALUE);
```

   and add a line to the verb's comment block: a key button, the client's or another addon's, is refused before the
   gate, naming `binding:key(key)` (163.3).

3. The `m.set("bind", …)` write — its `if(c == null)` refusal becomes a block whose first statement is the key
   button's own refusal (R11), the existing message unchanged after it:

```java
                if(c == null) {
                    if(w instanceof OptWnd.SetButton)   // 163.3: the client's key button, or another addon's
                        throw new LuaError(Binding.VERB + "(binding) joins a key button YOUR addon built, and this one"
                            + " is " + ((Owned.of(w) != null) ? "another addon's" : "one of the client's own")
                            + " — its key is its binding's: the user assigns it by hand, and binding:key(key) under"
                            + " client.settings is the write your code makes. To assign one of your hotkeys, build your"
                            + " own: hafen.ui():keybinding():bind(binding).");
                    throw new LuaError(/* the existing message, word for word */);
                }
```

### The docs

Every text below is the exact page text. "Append" means: after the sentence named, one space, then the text.

**`docs/addons/api/ui/edit.md`**
- *Reading what a borrowed control holds*: append after `A list's or dropdown's row.`:

```text
A [key button](controls/interactive.md#key-button)'s key, spelled as [`binding:key()`](../client/keybindings.md#the-binding-object) spells it, `nil` for unbound.
```

- *Driving one*, the `Refusals` row: append after its last sentence (`… the client re-reads every frame.`):

```text
A key button, before the permission is asked: its key is its binding's, written with [`binding:key(key)`](../client/keybindings.md#the-binding-object) under `client.settings`.
```

**`docs/addons/api/client/keybindings.md`**
- Line 3: its last sentence (the first line below) is replaced by the second line:

```text
A remap needs [`client.settings`](../../guides/permissions.md).
A remap from your code needs [`client.settings`](../../guides/permissions.md). The user assigns a key in Options ▸ Game ▸ Keybindings, or with a [key button](../ui/controls/interactive.md#key-button) on your own page.
```

- *Addon hotkeys start unbound*: append after `Every addon that declared a hotkey has its own section there, by addon name.`:

```text
[A key button on your own page](#a-key-button-on-your-own-page) assigns it too.
```

- A new section directly after *Addon hotkeys start unbound* and before *An assigned key answers to you and to
  nothing else*:

~~~markdown
## A key button on your own page

Each row Options ▸ Game ▸ Keybindings gives a hotkey ends in a key button, and your page can hold that button too: [`hafen.ui():keybinding()`](../ui/controls/interactive.md#key-button), joined to one of your hotkeys with `:bind(binding)`.

```lua
local keybindings = hafen.client():options():keybindings()
keybindings:on("toggle", function() hafen.log():write("toggled") end)
local toggle_binding = keybindings:binding():get("toggle")          -- after on(): your own hotkey
hafen.client():options():addon():panel(function(root)
  hafen.ui():keybinding():parent(root):bind(toggle_binding)
end)
```

| Rule | Detail |
|---|---|
| The same edit | A press on it assigns the key as the panel's row does: persisted, and taken off the binding that was assigned it. Each place shows what the other assigned. |
| Unprotected | The press is the user's own remap. `binding:key(key)`, the remap your code makes, stays under `client.settings`. |
| Your own hotkeys | It joins a hotkey your addon declared and has not ended. The client's bindings and other addons' are assigned in the panel. |
~~~

- *The binding object*, the `Writing persists` rule: its first sentence (first line below) is replaced by the second:

```text
As the same edit in Options ▸ Game ▸ Keybindings.
As the same edit a key button makes, in Options ▸ Game ▸ Keybindings or [on your page](#a-key-button-on-your-own-page).
```

**`docs/addons/guides/hotkeys-and-commands.md`**
- Line 3, the sentence (first line) is replaced by the second:

```text
Changing what the client has (remapping a key, writing one of its settings) is a [protected](permissions.md) write.
Changing what the client has from your code (remapping a key, writing one of its settings) is a [protected](permissions.md) write.
```

- Line 22, the sentence (first line) is replaced by the second:

```text
The user assigns the key in Options ▸ Game ▸ Keybindings, where every addon that declared one has a section.
The user assigns the key in Options ▸ Game ▸ Keybindings, where every addon that declared one has a section, or with a [key button](../api/ui/controls/interactive.md#key-button) on your own page.
```

- *A hotkey*'s rules table: a new row directly after the row that begins `| Reading is free, writing is not |`:

```text
| The user's press is not your write | A key button's press is the user remapping their own key, unprotected, as on the panel's row. `binding:key(key)` is your code writing it. |
```

**`docs/addons/guides/debugging.md`** — the `The hotkey is unbound` row's detail (first line) becomes the second:

```text
An addon hotkey starts with no key. Options ▸ Game ▸ Keybindings, your addon's section.
An addon hotkey starts with no key. Options ▸ Game ▸ Keybindings, your addon's section, or the addon's [key button](../api/ui/controls/interactive.md#key-button) on its page.
```

**`docs/addons/getting-started.md`** — step 7: append after `Assign a key, and it hides and shows your window.`:

```text
An addon can put that same key button on its own settings page too ([key button](api/ui/controls/interactive.md#key-button)).
```

**`docs/addons/api/client/addon.md`**
- Line 3: the clause (first line) is replaced by the second:

```text
and a control you build and [bind](#binding-a-control-shows-the-option) shows an option there. Nothing here is protected.
a control you build and [bind](#binding-a-control-shows-the-option) shows an option there, and a [key button](../ui/controls/interactive.md#key-button) assigns one of your hotkeys. Nothing here is protected.
```

- *The page*'s rules table: a new row directly after the row that begins `` | `root` | ``:

```text
| A hotkey on the page | [`hafen.ui():keybinding()`](../ui/controls/interactive.md#key-button) bound to one of your hotkeys is the key button Options ▸ Game ▸ Keybindings gives it. The user assigns the key there as on the panel's row. |
```

- *Binding*'s `Refused` row: append after its last sentence:

```text
A [Binding](keybindings.md#the-binding-object) names `hafen.ui():keybinding()`, the control that joins one of your hotkeys.
```

**`docs/addons/api/ui/controls/interactive.md`** — the *Key button* rules table (163.1's), a new last row:

```text
| The client's own | The key button a row of Options ▸ Game ▸ Keybindings ends in is this class, borrowed. `widget:value()` reads its key there too, and `:value(v)` and `:bind(binding)` are refused: [edit](../edit.md#reading-what-a-borrowed-control-holds). |
```

**`docs/addons/api/ui/controls/README.md`** — the `:value()` rule's last sentence (first line) becomes the second:

```text
A key button you built refuses the write: its key is its binding's.
A key button refuses the write, yours or the client's: its key is its binding's.
```

**`docs/addons/api/ui/writes.md`** — the `:value(v)` row, borrowed column: append after `The server sees it.`:

```text
A client key button refuses, before the permission is asked: its key is its binding's, written with `binding:key(key)`.
```

Discharges the impact-set rows `Game ▸ Keybindings…` (`keybindings.md`:31, 74; `getting-started.md`:112;
`debugging.md`:93; `hotkeys-and-commands.md`:22), `remap needs…` (`keybindings.md`:3; `hotkeys-and-commands.md`:3)
and `A checkbox's boolean…` (`edit.md`:119, 147). The *stays* of every row are read and left as they are.

### Its suite — `addons/163-keybinding-control.3/`

`manifest.json`:

```json
{
  "name": "163.3 — the client's key button",
  "id": "163-keybinding-control.3",
  "version": "1.0.0",
  "author": "brodgar",
  "description": "Self-checking suite for task 163.3: the key buttons Options > Game > Keybindings builds read their key with widget:value(), and refuse :value(v) and :bind(binding) naming the write and the builder.",
  "api_version": "1.1",
  "files": ["main.lua"]
}
```

`main.lua`, exactly:

```lua
-- 163.3 — the client's key button. Self-checking suite.

local pass, fail, manual = 0, 0, 0
local last                                   -- the previous run's timer, taken down by the next run

local function check(ok, what, got)
  if ok then
    pass = pass + 1
    hafen.log():write("[pass] " .. what)
  else
    fail = fail + 1
    hafen.log():write("[fail] " .. what .. " -- got: " .. tostring(got))
  end
end

local function manualCheck(step, expect)
  manual = manual + 1
  hafen.log():write("[manual] " .. step .. " -- expect: " .. expect)
end

local function refusal(fn, ...)
  local ok, err = pcall(fn, ...)
  if ok then return nil end
  return (tostring(err):gsub("^@?.-%.lua:%d+:?%s*", ""))
end

local function says(message, text)
  return (message ~= nil) and (message:find(text, 1, true) ~= nil)
end

local WHAT = {
  "the Inventory row's key button reads its key as binding:key() spells it",
  "every key button the panel built reads the key it shows",
  "value(v) on the client's key button is refused naming binding:key(key), before any permission is asked",
  "bind(binding) on the client's key button is refused naming hafen.ui():keybinding()",
}

local function run()
  if last then last.poll:cancel() end
  pass, fail, manual = 0, 0, 0
  local inventory_binding = hafen.client():options():keybindings():binding():get("inv")
  manualCheck("open Options > Game > Keybindings", "its rows, each ending in a key button; nothing else to do")

  local started = os.time()
  local poll
  poll = hafen.timer():every(0.25, function()
    local session = hafen.session():current()
    local options_window = session and session:ui():match("window[title=Options]")
    local row_button
    if options_window then
      for _, label in ipairs(options_window:matchAll("@Label[text=Inventory]")) do
        local siblings = label:parent():children():list()
        for index, sibling in ipairs(siblings) do
          local after = siblings[index + 1]
          if sibling == label and after and after:type() == "SetButton" then row_button = after end
        end
      end
    end
    if row_button == nil and os.time() - started < 180 then return end
    poll:cancel()
    if row_button == nil then
      for _, what in ipairs(WHAT) do check(false, what, "the Keybindings panel was not open within 180 s") end
    else
      check(row_button:value() == inventory_binding:key(), WHAT[1],
            "value=" .. tostring(row_button:value()) .. " binding=" .. tostring(inventory_binding:key()))
      local count, odd = 0, nil
      for _, key_button in ipairs(options_window:matchAll("@SetButton")) do
        local text, value = key_button:text(), key_button:value()
        if text ~= "..." then
          count = count + 1
          if not ((text == "None" and value == nil) or value == text) and odd == nil then
            odd = "text=" .. tostring(text) .. " value=" .. tostring(value)
          end
        end
      end
      check(count >= 2 and odd == nil, WHAT[2], odd or ("only " .. count .. " key buttons"))
      local value_message = refusal(row_button.value, row_button, "F5")
      check(says(value_message, "binding:key(key)"), WHAT[3], value_message or "<no error>")
      local bind_message = refusal(row_button.bind, row_button, inventory_binding)
      check(says(bind_message, "hafen.ui():keybinding()"), WHAT[4], bind_message or "<no error>")
    end
    hafen.log():write(("[summary] %d pass, %d fail, %d manual"):format(pass, fail, manual))
  end)
  last = { poll = poll }
end

hafen.console():on("t163-3", function() hafen.timer():after(0, run) end)
```

What proves what: the four checks are criterion 9 whole — the read on the Inventory row against the client's own
`inv` Binding and on every key button the panel built, and the two refusals by the substrings `plan.md` fixes. The
suite declares no permission, so the `:value(v)` refusal naming `binding:key(key)` is only reachable when it sits
before the `widget.value` gate: without it the gate's refusal would come back, naming the key instead.

A passing run prints `[manual] open Options > Game > Keybindings …`, then four `[pass]` lines once the panel is
open, then `[summary] 4 pass, 0 fail, 1 manual`.

### Verification, in this order

1. `rm -rf build/classes && ant hafen-client` → `BUILD SUCCESSFUL`.
2. `python tools/docverbs.py`, `python tools/refusalverbs.py`: each exit `0`, run bare.
3. The three impact-set commands this task discharges, re-run: every hit is a sentence this task wrote or a *stays*.
4. DOCUMENTATION.md §11 over every page written; the anchors `#a-key-button-on-your-own-page`,
   `../ui/controls/interactive.md#key-button`, `../edit.md#reading-what-a-borrowed-control-holds` and
   `keybindings.md#the-binding-object` resolve. Report the counts.
5. `java -cp lib/brodgar/luaj-jse-3.0.1.jar luac -p addons/163-keybinding-control.3/main.lua`, then `rm -f luac.out`.
6. `rm -f build/hafen.jar && ant bin`; confirm `bin/addons/163-keybinding-control.3/main.lua` is the file just
   written; after any fix round, copy the folder into `bin/addons/` again.
7. Report the one command, `:t163-3`, after a full client restart. This is the feature's last task: its `/end`
   checks every criterion claimed (1–11, above) and fast-forwards the branch into `master`.
