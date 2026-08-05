# Subsystem: the client's own CONTROLS

> The catalogue of interactive widgets `haven` already ships — the classes an addon builds through
> `hafen.ui()`'s control builders. Lines are indicative; the **class + method/field name is the stable
> anchor**. The tree itself is [widgets.md](widgets.md); what draws a window *frame* is
> [ui-chrome.md](ui-chrome.md). Max 70 lines.

## The image-backed base: `SIWidget`

Every control whose face is a picture (`Button`, `IButton`, `CheckBox`, `ICheckBox`, …) extends
[`SIWidget`](src/haven/SIWidget.java:31), which is one idea: **rasterise once, blit thereafter**.

| What | Where |
|---|---|
| The cache | [`SIWidget.surf`](src/haven/SIWidget.java:32) — a `Tex`, built lazily in [`draw(GOut)`](src/haven/SIWidget.java:47) from the subclass's [`draw(BufferedImage)`](src/haven/SIWidget.java:39) |
| The only invalidation | [`redraw()`](src/haven/SIWidget.java:54) — disposes `surf` and nulls it; [`dispose()`](src/haven/SIWidget.java:59) is teardown |

> **`Widget.resize(Coord)` does NOT call `redraw()`.** [`Widget.resize`](src/haven/Widget.java:1534) sets
> `sz`, presizes the children and tells the parent — nothing more. So resizing an `SIWidget` moves its box
> and keeps its old picture, at the old dimensions, until something else happens to invalidate it. Anything
> that sizes one of these controls has to `redraw()` itself; the symptom reads as a layout bug.

## `Button`

[`Button`](src/haven/Button.java:34) — the text push button. Height is **fixed by its images**:
`hs`/`hl` ([:42](src/haven/Button.java:42)), short or "large", never the caller's.

| What | Where |
|---|---|
| The activation | [`click()`](src/haven/Button.java:213) runs [`action`](src/haven/Button.java:50) (public field, or the [`action(Runnable)`](src/haven/Button.java:158) chainer). [`gkeytype`](src/haven/Button.java:218) calls it too — **it is not a mouse event** |
| ...and the order that matters | [`mouseup`](src/haven/Button.java:265) does `d.remove(); redraw();` and calls `click()` **last**, so a handler may destroy the window it is sitting in |
| The caption, post-construction | [`change(String)`](src/haven/Button.java:193) / [`change(String, Color)`](src/haven/Button.java:187) — re-render + `redraw()` |
| Short vs large | [`largep(w)`](src/haven/Button.java:106) — `w >= bl+bm+br` **on the UI-scaled images**, so the same width is not the same button on every client. The `lg` constructors ([:115](src/haven/Button.java:115), [:134](src/haven/Button.java:134)) say it outright |
| The server-sending default | [`Button(int, String)`](src/haven/Button.java:143) → [:134](src/haven/Button.java:134) sets `action = () -> wdgmsg("activate")`. The `Runnable` overloads ([:115](src/haven/Button.java:115), [:139](src/haven/Button.java:139)) do not |
| The font seam | [`checkfont`](src/haven/Button.java:61)/[`render`](src/haven/Button.java:123) — the caption goes through the `"button"` scope provider and re-renders in [`draw(GOut)`](src/haven/Button.java:200) when `Fonts.gen()` moves |

An **empty caption is safe**: [`Text.Foundry.render`](src/haven/Text.java:226) widens a zero-width string to
1 px before allocating the buffer, so a button built with `""` does not blow up on `new BufferedImage(0, …)`.

## A native control that is always in the tree

[`Window.DefaultDeco.cbtn`](src/haven/Window.java:195) — the close box, a real `IButton` added by the deco
([:205](src/haven/Window.java:205)) and owned by nobody. Every window carries one, which makes it the
reliable answer to *"find a control this addon did not build"* without depending on which client windows
happen to be open. The rest of the deco is [ui-chrome.md](ui-chrome.md).

## Tree operations a control adapter uses

| What | Where |
|---|---|
| Resize / move / pack | [`Widget.resize`](src/haven/Widget.java:1534) (no-op when equal) · [`move`](src/haven/Widget.java:1530) · [`pack`](src/haven/Widget.java:1526) = `resize(contentsz())` |
| Unlink + destroy | [`remove()`](src/haven/Widget.java:570) is **null-parent safe**, so a double destroy is harmless; [`destroy()`](src/haven/Widget.java:586) is `remove()` + `rdispose()` and cascades to children by unlinking the subtree's root |
| Visibility | [`hide`](src/haven/Widget.java:2048)/[`show`](src/haven/Widget.java:2054) also touch the parent's focus list; [`visible()`](src/haven/Widget.java:2068) is the widget's **own** flag, [`tvisible()`](src/haven/Widget.java:2072) walks up |

**Every control needs a thin subclass anyway**, which is why the ownership contract costs nothing extra: the
hooks the engine offers are `protected`/overridable methods (`Button.click`, `SIWidget.draw`) or public
fields taking a lambda, not a settable callback slot. The split between the two decides how much an adapter
does — a lambda-taking control needs the subclass only to carry ownership.
