# Fix — AddOns-panel tooltip overflows `GL_MAX_TEXTURE_SIZE` (GL_INVALID_VALUE crash)

> **Status:** ✅ Implemented; compiles (`ant hafen-client` → BUILD SUCCESSFUL). **In-game DoD pending.**
> **Scope:** one-file bug fix in `io.brodgar.addon.ui` — **no API change.** Devlog-only (no `api/` edit).

## Symptom

Hovering over an addon row in **Options → AddOns** (even with every addon disabled) crashed the render
thread:

```
com.jogamp.opengl.GLException: Caught BGLException: ... GL Error: 1281 (GL_INVALID_VALUE) on thread AWT-EventQueue-0
    ... at haven.iosys.tk.JOGLToolkit$JOGLPanel.redraw
```

`GL_INVALID_VALUE (1281)` on a redraw is the classic signature of a **texture whose dimensions are
invalid** — here, wider than the GPU's `GL_MAX_TEXTURE_SIZE` (typically 16 384 px).

## Root cause

Each row set the addon's **description** as the label tooltip:

```java
nm.settip(tip.toString(), false);   // AddonPanel.Row ctor
```

`Widget.settip(text, rich)` builds a `KeyboundTip`. Its `get()` renders the tip with
`RichText.render(tip, w)` — and in the **non-rich** branch `w` stays **0**, which means **no wrap**: the
whole string is rasterized on **one unbroken line**. Our addon descriptions are pathologically long — the
`hello` manifest description is a **~7 000-character single paragraph**. One line of that is a
`BufferedImage` tens of thousands of pixels wide; the first `glTexImage2D` upload of it fails with
`GL_INVALID_VALUE`, and the exception kills the JOGL display callback. (The user correctly guessed it was
the "one-line tooltip wider than the whole screen".)

The tooltip-draw path itself (`UILoop.drawtooltip`) only clamps the *top-left* corner to the screen and
never clips width/height, so nothing bounded the texture size upstream — the width had to be bounded at
render time.

## The fix

Render the tooltip **wrapped** instead of on one line. `settip(text, true)` (rich mode) sets the wrap
width to `UI.scale(300)`, so the texture width is bounded and height grows in lines. But rich mode treats
the text as RichText **markup** (`$cmd{...}`), and our descriptions are full of `{ } [ ]` tokens — so we
first pass the text through `RichText.Parser.quote(...)`, which escapes `$ { }` so it renders **literally**:

```java
// AddonPanel.java — Row ctor
nm.settip(RichText.Parser.quote(tip.toString()), true);
```

`+ import haven.RichText;`

Both `RichText` and `RichText.Parser.quote` are public, so this stays a pure `io.brodgar.addon.ui` change
— **zero `haven` core edit**. The wrapped width (≈300 px scaled) is far under `GL_MAX_TEXTURE_SIZE`; even a
7 000-char description wraps to a tall-but-legal texture (~a few thousand px, still well under 16 384 at any
UI scale).

## Follow-up (not done here)

A 7 000-char description, even wrapped, is a very **tall** tooltip that can cover most of the screen
vertically — a UX wart, not a crash. If we want the tooltip to be genuinely readable we should **cap** the
description shown (e.g. first sentence / N chars + "…"), which is a content/product decision left for the
maintainer. The crash is fixed regardless.
