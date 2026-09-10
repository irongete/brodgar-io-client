package io.brodgar.addon;

import haven.Coord;
import haven.Resource;
import haven.Tex;
import haven.UI;
import haven.Widget;
import haven.Window;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;

/**
 * The <b>controls</b> half of {@code hafen.ui} (spec {@code 040-ui-controls}): the builders that put one of the
 * client's own controls on screen, and the dispatch behind the control verbs on the Widget entity.
 *
 * <p><b>A control is a Widget, not a nineteenth entity.</b> {@code docs/addons/api/ui/} opens with <i>"there is
 * one type"</i>, and a control joins it: {@code :position}, {@code :size}, {@code :visible}, {@code :parent},
 * {@code :destroy}, {@code :style}, {@code :type}, {@code :role} and every selector answer on a control on day
 * one, with nothing written for them. What lives here is only what a control <b>adds</b> — and each such verb
 * is a name on the one Widget entity that <b>answers where it applies</b>, exactly as {@code :title()} already
 * reads {@code nil} on a bare widget and {@code :items()} answers on a container.
 *
 * <p><b>A builder is constructed bare and configured by chained setters</b> (R4): {@code hafen.ui():button()}
 * takes no argument, and {@code :text}, {@code :size}, {@code :position}, {@code :onPress} follow. It is the
 * same shape {@code :window()} has, arming rule included — a control is attached inert and completes on the
 * tick after the statement that built it (D-112/D-119), so it is findable from the first instant and never
 * drawn half-built.
 *
 * <p>Task 040.1 ships the first consumer, {@code :button()}; the later tasks of the feature add their builders
 * beside it and their verbs to the dispatch below, copying {@link CtlButton}'s shape.
 */
final class Controls {
    private Controls() {
    }

    // ------------------------------------------------------------------ what a verb dispatches ON

    /**
     * <b>A control key answers on a CAPABILITY, not on a class</b> (040.2). {@code "Pressed"} belongs to
     * <i>a thing that fires and holds nothing</i>, and two unrelated {@code haven} classes are that thing —
     * {@link haven.Button} and {@link haven.IButton}, which share no ancestor below {@code Widget}. Naming them
     * both at every call site is how a dispatch chain rots: the third one is added in four places and forgotten
     * in the fifth. So each key gets one tiny marker interface, the adapters implement the ones they answer, and
     * {@code widget:on(key, fn)}'s vocabulary ({@link LuaWidget#widgetKeys}) is an {@code instanceof} against it.
     *
     * <p><b>A pure marker since 041.4.</b> Before the unified {@code :on(key, fn)} verb this interface also
     * carried the single installed-handler slot ({@code onPress()}/{@code onPress(fn)}); N subscribers live on
     * the widget's own {@link WidgetSubs} now ({@link Controls#fire}), so the capability is nothing but the
     * ANSWER to "does this control fire {@code Pressed}?" — the same shape {@link Value}/{@link Rows} always had.
     */
    interface Press {
    }

    /**
     * <b>{@code :value()} — the one verb for what a control HOLDS</b> (spec 040 §1, task 040.3). {@link CProgress}
     * is the first implementor; every later control with a value (checkbox, radio, slider, entry, list, dropdown)
     * answers this the same way, each doing its own range/type check on the write and throwing naming it. A
     * control with no value is simply not an instance of this — the dispatch below reads {@code nil} on one
     * rather than asking it a question it has no answer to.
     */
    interface Value {
        /** The current value, as whatever Lua shape this control's value is. */
        LuaValue value();

        /** Validated by the implementation, which throws naming the rule a bad {@code v} broke. */
        void value(LuaValue v);
    }

    /**
     * <b>{@code :source(h)} — a picture widget's own content setter</b> (spec 040 decision E, task 040.3):
     * {@link CImg} is its one implementor. Named apart from {@code :image()} (a button/checkbox FACE) so that
     * {@code hafen.ui():image()} — the builder for a picture — never reads as {@code image():image(h)}.
     */
    interface Source {
        /** The handle or resource name last given, or {@code nil} before the first one. */
        LuaValue source();

        /** Bridge-only; {@link Controls#source} resolves {@code h} and installs the texture before calling this. */
        void source(LuaValue h);
    }

    /**
     * <b>{@code "Changed"} — the value CHANGED</b> (spec 040 §1, task 040.4): the notification half of the
     * {@code :value()} spine, answered on this capability exactly as {@code "Pressed"} is on {@link Press}.
     * Fires from a real user interaction only — a programmatic {@code :value(v)} is a direct field write on the
     * implementation and never fires it, which is what keeps the write from re-entering its own handler.
     * {@link CCheck} is the first implementor; every later control with a value implements it the same way.
     * A pure marker since 041.4 — see {@link Press}.
     */
    interface Change {
    }

    /**
     * <b>{@code :rows(t)} — the ROW SOURCE of a model-backed control</b> (spec 040 §1, task 040.5). {@link CRadio}
     * is the first implementor: an array of row labels that becomes the buttons stacked under it. The later
     * model-backed five (list, dropdown, menu, grid, table — 040.9-040.12) answer this the same way, each over
     * its own row shape; a control with no row source is simply not an instance of this, exactly as {@link Value}
     * is absent from one with no value.
     */
    interface Rows {
        /** Exactly the table {@code :rows(t)} was last given, or {@code null} before the first one. */
        LuaValue rows();

        /** Validated by the implementation, which throws naming the rule a bad {@code t} broke. */
        void rows(LuaValue t);
    }

    /**
     * <b>{@code :range(min, max)} — the value BOUNDS of a slider or scrollbar</b> (task 040.6). {@link CSlider}
     * is the first implementor, {@link CScrollbar} the second; a control with no bounds is simply not an
     * instance of this, exactly as {@link Value} is absent from one with no value. Unlike {@link Rows} or
     * {@link Value}, the write takes the two raw arguments rather than one Lua value: the bridge splits
     * {@code narg} into read/write, but the two numbers themselves are each the implementation's own to
     * type-check and name in its refusal.
     */
    interface Range {
        /** {@code {min =, max =}} as they stand right now. */
        LuaValue range();

        /** Validated by the implementation, which throws naming the rule a bad bound broke. */
        void range(LuaValue minv, LuaValue maxv);
    }

    /**
     * <b>{@code "Submitted"} — the ENTRY's Enter</b> (task 040.7). {@link CEntry} is its one implementor, and
     * it is deliberately a different key from {@link Change}: {@code "Changed"} fires on every keystroke,
     * {@code "Submitted"} once, when Enter is pressed — two gestures, not one gesture with a flag (unlike the
     * slider's {@code final}, which is the same drag reported twice). A pure marker since 041.4 — see {@link Press}.
     */
    interface Submit {
    }

    /**
     * <b>{@code :rowHeight(n)} — the ROW HEIGHT of a model-backed list, in pixels</b> (task 040.9). {@link CList}
     * is its one implementor so far. Read-only as a capability — the WRITE is not a plain field assignment
     * (unlike {@link Value}/{@link Rows}): {@code SListBox.itemh} is {@code final}, so choosing a different one
     * is building-only and goes through a rebuild in {@link Controls#rowHeight}, the same shape {@link #image}
     * already has for a button's face.
     */
    interface RowHeight {
        /** The row height as the client's own widget holds it — DEVICE px; {@link Controls#rowHeight(Owned)} converts. */
        int rowHeight();
    }

    /**
     * <b>{@code "Selected"} — a MENU ROW WAS CHOSEN, and it holds nothing</b> (task 040.10). {@link CMenu} is
     * its one implementor: distinct from {@link Change} (a menu answers no {@link Value} to change) and closer
     * in shape to {@link Press} — a fire-and-forget notification — except it carries the picked row as its
     * argument, which {@code "Pressed"} does not. A pure marker since 041.4 — see {@link Press}.
     */
    interface Select {
    }

    /**
     * <b>{@code :cellSize(w, h)} — a GRID's cell box, in pixels</b> (task 040.11). {@link CGrid} is its one
     * implementor. Read-only as a capability, exactly like {@link RowHeight}: the WRITE is not a plain field
     * assignment — {@code GridList.Group.itemsz} is {@code final}, so choosing a different one is building-only
     * and goes through a rebuild in {@link Controls#cellSize}, the same shape {@link #rowHeight} already has.
     */
    interface CellSize {
        /** The cell box as {@code GridList} holds it — DEVICE px; {@link Controls#cellSize(Owned)} converts. */
        Coord cellSize();
    }

    /**
     * <b>{@code "Cell"} — a GRID's cell painter</b> (task 040.11). {@link CGrid} is its one implementor:
     * {@code GridList} draws rather than builds row widgets, so this is the one model-backed control whose row
     * source is painted through the same {@code g} wrapper {@code "Draw"} uses, rather than turned into a widget
     * by {@link LuaRows}. A pure marker since 041.4 — see {@link Press}.
     */
    interface OnCell {
    }

    /**
     * <b>{@code :columns(t)} — a TABLE's column descriptors</b> (task 040.12). {@link CTable} is its one
     * implementor. Read-only as a capability, exactly like {@link RowHeight}/{@link CellSize}: the WRITE is not
     * a plain field assignment — {@code TableBox.cols}/{@code main} are {@code public final}, fixed at
     * construction from {@code spec()}, so a different column set is building-only and goes through a rebuild
     * in {@link Controls#columns}, the same shape {@link #rowHeight}/{@link #cellSize} already have.
     */
    interface Columns {
        /** Exactly the table {@code :columns(t)} was last given, or {@code null} before the first one. */
        LuaValue columns();
    }

    // ------------------------------------------------------------------ the builders

    /**
     * {@code hafen.ui():button()} — a {@link haven.Button}, the client's own, at its own height and a default
     * width {@code :size(w, h)} overrides. Its caption is {@code :text(s)} and its activation
     * {@code :onPress(fn)}; {@code :onClick(fn)} is not it — that is the raw mouse event every widget has.
     */
    static LuaValue button(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():button() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():button():text(\"Go\"):position(x, y):parent(w):onPress(fn)");
        UI u = UiApi.requireUi("button");
        return UiApi.attach(u, owner, new CtlButton(owner, Px.in(CtlButton.DEF_W)));
    }

    /**
     * {@code hafen.ui():label()} — a {@link haven.Label}, the client's own live-restyling text widget (task
     * 040.3). Its caption is {@code :text(s)}; {@link haven.ILabel}, the plan's expected second class, turned
     * out to carry no picture at all (a fixed, non-restyling font furnace instead) and is not shipped — see
     * {@link CLabel}.
     */
    static LuaValue label(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():label() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():label():text(\"Stamina\"):position(x, y):parent(w)");
        UI u = UiApi.requireUi("label");
        return UiApi.attach(u, owner, new CLabel(owner));
    }

    /**
     * {@code hafen.ui():image()} — an {@link haven.Img}, the client's own static picture widget (task 040.3).
     * Its content is {@code :source(h)}, not {@code :image()} — a button's face and a picture's own content are
     * different verbs on purpose (decision E), so this builder never completes as {@code image():image(h)}.
     */
    static LuaValue image(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():image() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():image():source(h):position(x, y):parent(w)");
        UI u = UiApi.requireUi("image");
        return UiApi.attach(u, owner, new CImg(owner));
    }

    /**
     * {@code hafen.ui():separator()} — an {@link haven.HRuler}, the client's own horizontal rule (task 040.3,
     * decision F — the plain word over {@code :ruler()}). It has no verb of its own: {@code :size(w, h)}
     * overrides its width, and every control property it does not answer reads {@code nil} or refuses naming
     * what does.
     */
    static LuaValue separator(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():separator() takes no arguments — it is built bare and placed by"
                + " chained setters: hafen.ui():separator():size(w, 1):position(x, y):parent(w)");
        UI u = UiApi.requireUi("separator");
        return UiApi.attach(u, owner, new CSeparator(owner));
    }

    /**
     * {@code hafen.ui():progress()} — a {@link haven.Progress} bar, the client's own (task 040.3). Its fill
     * fraction is {@code :value()}, {@code 0..1} — the first control in this feature to answer the six-name
     * {@code :value()} verb rather than merely reading {@code nil} on it.
     */
    static LuaValue progress(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():progress() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():progress():size(w, h):value(0.0):position(x, y):parent(w)");
        UI u = UiApi.requireUi("progress");
        return UiApi.attach(u, owner, new CProgress(owner));
    }

    /**
     * {@code hafen.ui():check()} — a {@link haven.CheckBox}, the client's own (task 040.4), where
     * {@code :image(up, down, hoverUp, hoverDown)} completes it as an {@link haven.ICheckBox} exactly as
     * {@code :image(up, down[, hover])} completes {@code :button()} as an {@link haven.IButton}. Its caption is
     * {@code :text(s)}, its state {@code :value(v)}, and it is the first control this feature ships that answers
     * {@code :onChange(fn)} — the sixth and last of the six names spec 040 §1 gives the whole roster.
     */
    static LuaValue check(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():check() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():check():text(\"Show grid\"):value(true):onChange(fn)");
        UI u = UiApi.requireUi("check");
        return UiApi.attach(u, owner, new CCheck(owner));
    }

    /**
     * {@code hafen.ui():radio()} — ONE control, not a group object plus N buttons (task 040.5): a real
     * {@link haven.RadioGroup} of the client's own {@code RadioButton}s, stacked downward from this control's
     * own {@code :position}, one row height apart. {@code :rows{…}} is the row source, {@code :value(label)}
     * checks one and {@code :onChange(fn)} fires on a real pick only — {@code RadioGroup}/{@code RadioButton}
     * never appear in Lua.
     */
    static LuaValue radio(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():radio() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():radio():rows{\"A\", \"B\"}:value(\"A\"):onChange(fn)");
        UI u = UiApi.requireUi("radio");
        return UiApi.attach(u, owner, new CRadio(owner));
    }

    /**
     * {@code hafen.ui():slider()} — a real {@link haven.HSlider}, the client's own (task 040.6). Its bounds are
     * {@code :range(min, max)}, its position within them {@code :value(n)} — CLAMPED on a write outside the
     * range rather than refused — and {@code :onChange(v, final)} is one callback over the engine's
     * {@code changed()}/{@code fchanged()} pair, {@code final} false while dragging and true once on release.
     */
    static LuaValue slider(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():slider() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():slider():range(0, 100):value(50):onChange(fn)");
        UI u = UiApi.requireUi("slider");
        return UiApi.attach(u, owner, new CSlider(owner));
    }

    /**
     * {@code hafen.ui():scrollbar()} — a bare {@link haven.Scrollbar}, the client's own (task 040.6), for
     * driving something yourself: the same {@code :range}/{@code :value} as {@code :slider()}, minus the
     * {@code final} flag on {@code :onChange(fn)} — the engine gives this control no separate "drag ended" hook.
     */
    static LuaValue scrollbar(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():scrollbar() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():scrollbar():range(0, 100):value(0):onChange(fn)");
        UI u = UiApi.requireUi("scrollbar");
        return UiApi.attach(u, owner, new CScrollbar(owner));
    }

    /**
     * {@code hafen.ui():entry()} — a real {@link haven.TextEntry}, the client's own (task 040.7). Its content is
     * {@code :value(s)} — the ONE door (decision A) — with {@code :onChange(fn)} firing per keystroke and
     * {@code :onSubmit(fn)} once, on Enter.
     */
    static LuaValue entry(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():entry() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():entry():value(\"\"):onChange(fn):onSubmit(fn)");
        UI u = UiApi.requireUi("entry");
        return UiApi.attach(u, owner, new CEntry(owner));
    }

    /**
     * {@code hafen.ui():scroll()} — a scrolling container over {@link haven.Scrollport}'s own two pieces (task
     * 040.8): {@code :parent(sp)} on any control puts it INSIDE the scrolling area, never beside the bar, and
     * the bar answers the same {@code :range}/{@code :value}/{@code :onChange} as a bare {@code :scrollbar()}
     * (found the ordinary way, {@code s:ui():matchAll("@Scrollbar")} or {@code sp:children()}) once content
     * taller than the box makes it live. The container itself has no verb of its own.
     */
    static LuaValue scroll(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():scroll() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():scroll():size(200, 160):position(x, y):parent(w)");
        UI u = UiApi.requireUi("scroll");
        return UiApi.attach(u, owner, new CScrollport(owner));
    }

    /**
     * {@code hafen.ui():listbox()} — a real {@link haven.SListBox}, the client's own scrolling row list (task
     * 040.9), the first of the model-backed five. Its row source is {@code :rows(t)} — the Lua-array bridge
     * {@link LuaRows} every later model-backed control reuses (D-108) — its selection
     * {@code :value()}/{@code :value(v)}, and {@code :onChange(fn)} fires on a real pick only, exactly the same
     * spine every other value-bearing control in this feature already answers.
     */
    static LuaValue listbox(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():listbox() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():listbox():rowHeight(20):rows{\"A\", \"B\"}:onChange(fn)");
        UI u = UiApi.requireUi("listbox");
        return UiApi.attach(u, owner, new CList(owner, Px.in(CList.DEF_SZ), CList.defaultItemHeight()));
    }

    /**
     * {@code hafen.ui():dropdown()} — a real {@link haven.SDropBox}, the client's own closed-until-clicked row
     * list (task 040.10), the second of the model-backed five: the same {@link LuaRows} bridge
     * {@code :listbox()} uses (D-108). Its row source is {@code :rows(t)}, its pick
     * {@code :value()}/{@code :value(v)}, and {@code :onChange(fn)} fires on a real pick only — the same spine
     * {@code :listbox()} answers.
     */
    static LuaValue dropdown(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():dropdown() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():dropdown():rowHeight(18):rows{\"A\", \"B\"}:onChange(fn)");
        UI u = UiApi.requireUi("dropdown");
        return UiApi.attach(u, owner,
            new CDropdown(owner, Px.in(CDropdown.DEF_W), Px.in(CDropdown.DEF_LISTH), CDropdown.defaultItemHeight()));
    }

    /**
     * {@code hafen.ui():menu()} — a real {@link haven.SListMenu}, the client's own row-of-actions widget (task
     * 040.10), the third of the model-backed five over the same {@link LuaRows} bridge. It FIRES and holds
     * nothing: {@code :value()} reads {@code nil} on it, and {@code :onSelect(fn)} — not {@code :onChange} — is
     * what carries the picked row.
     */
    static LuaValue menu(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():menu() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():menu():rows{\"A\", \"B\"}:onSelect(fn)");
        UI u = UiApi.requireUi("menu");
        return UiApi.attach(u, owner, new CMenu(owner, Px.in(CMenu.DEF_SZ), CMenu.defaultItemHeight()));
    }

    /**
     * {@code hafen.ui():grid()} — a real {@link haven.GridList}, the client's own laid-out icon grid (task
     * 040.11), the fourth of the model-backed five and the odd one out: it DRAWS cells rather than building row
     * widgets, so its row source ({@code :rows(t)}, a plain array of arbitrary Lua values) is painted through
     * {@code :onCell(g, item, w, h)} — the same {@code g} wrapper {@code widget:onDraw(fn)} hands a surface —
     * rather than turned into rows by {@link LuaRows}. {@code :cellSize(w, h)} is the cell box and, like
     * {@code :rowHeight(n)}, building-only.
     */
    static LuaValue grid(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():grid() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():grid():cellSize(48, 48):rows(items):onCell(fn)");
        UI u = UiApi.requireUi("grid");
        return UiApi.attach(u, owner, new CGrid(owner, Px.in(CGrid.DEF_SZ), Px.in(CGrid.DEF_CELL)));
    }

    /**
     * {@code hafen.ui():table()} — a real {@link haven.TableBox}, the client's own columned row list (task
     * 040.12), the fifth and last of the model-backed five. Built with NO columns and the client's own default
     * row height until {@code :columns(t)} names them — {@code title}, {@code width} and an {@code of(row)}
     * accessor per column, over {@code ColSpec.of} — and {@code :rows(t)} feeds it rows, a plain array of
     * arbitrary Lua values (whatever shape the addon's own {@code of(row)} accessors read).
     */
    static LuaValue table(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():table() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():table():columns{...}:rows(items)");
        UI u = UiApi.requireUi("table");
        return UiApi.attach(u, owner,
            CTable.create(owner, Px.in(CTable.DEF_SZ), CTable.defaultItemHeight(), Collections.<CTable.ColDef>emptyList(),
                null));
    }

    // ------------------------------------------------------------------ the control verbs

    /**
     * {@code widget:text(s)} — the write half of a verb that has read on every text-bearing widget since spec
     * 20. R2 in the ordinary way: the bare noun reads whatever the widget displays, the same name with a value
     * writes it where the addon owns the control. A control that displays nothing refuses <b>naming what
     * does</b> rather than failing as a nil call.
     */
    static void text(Owned c, Widget w, String s) {
        if(c instanceof CtlButton) {
            synchronized(LuaWidget.monitor(w)) { ((CtlButton)c).change(s); }
            WidgetSurface.touch(w);   // 044.1: if it is standing in the world, its picture is out of date
            return;
        }
        if(c instanceof CLabel) {
            synchronized(LuaWidget.monitor(w)) { ((CLabel)c).settext(s); }   // resizes itself; see spec 040
            WidgetSurface.touch(w);   // 044.1: if it is standing in the world, its picture is out of date
            return;
        }
        if(c instanceof CCheck) {
            synchronized(LuaWidget.monitor(w)) { ((CCheck)c).settext(s); }   // resizes itself, same as CLabel above
            WidgetSurface.touch(w);   // 044.1: if it is standing in the world, its picture is out of date
            return;
        }
        if(c instanceof CtlIButton)
            throw new LuaError("widget:text(s) writes a control's CAPTION, and this button's face is a PICTURE —"
                + " an image button shows the faces widget:image(up, down[, hover]) gave it and has no caption."
                + " A captioned button is the same builder completed the other way: hafen.ui():button():text(\""
                + s + "\").");
        if(c instanceof CICheck)
            throw new LuaError("widget:text(s) writes a control's CAPTION, and this checkbox's face is a"
                + " PICTURE — an image checkbox shows the faces widget:image(up, down, hoverUp, hoverDown) gave"
                + " it and has no caption. A captioned checkbox is the same builder completed the other way:"
                + " hafen.ui():check():text(\"" + s + "\").");
        throw new LuaError("widget:text(s) writes the caption of a CONTROL you built, and hafen.ui():button(),"
            + " hafen.ui():label() or hafen.ui():check() is the builder that takes one — " + LuaWidget.typeName(w)
            + " has no caption to write" + ((w instanceof Window) ? "; a window's caption is widget:title(s)." : "."));
    }

    /**
     * {@code widget:on(key, fn)}'s fire side for a control notification ({@code "Pressed"}/{@code "Changed"}/
     * {@code "Submitted"}/{@code "Selected"}, 041.4) — the one place every control's activation, value-change,
     * submit and select method now ends, in place of the single stored slot + direct {@code callLua} each used
     * to have. Looks up the widget's {@link WidgetSubs} WITHOUT minting one ({@link Addon#widgetSubsOrNull}), so
     * a control nobody subscribed to costs one map lookup and nothing else — the {@code hasSub} gate one level
     * up from {@link Subs#has}.
     */
    static void fire(Owned c, String key, LuaValue... args) {
        if(c.dead())
            return;
        WidgetSubs s = c.profOwner().widgetSubsOrNull(c.widget());
        if(s != null)
            s.subs.fire(key, args);
    }

    // ------------------------------------------------------------------ the borrowed capability keys (061.1)

    /**
     * <b>The keys a control the addon did NOT build answers</b> (061.1) — the borrowed half of the capability
     * roster above, and the one place it is written down. The owned half dispatches on the marker interfaces
     * ({@link Press}/{@link Change}/…) an adapter implements; a native widget implements none of them, so the
     * question is asked of the {@code haven} class instead, in one {@code instanceof} chain — the same
     * discipline {@link LuaWidget#typeName}/{@link LuaWidget#role} use for fragile upstream knowledge, so
     * upstream churn breaks this method and nothing else.
     *
     * <p>It is what keeps a refusal honest: {@code widget:on(key, fn)} lists what the widget DOES answer, so a
     * native button has to say {@code Pressed} there or the roster and the seam disagree about the same widget.
     * Empty for anything that fires nothing — a native {@link haven.Label}, a window, a container.
     */
    static List<String> borrowedKeys(Widget w) {
        if((w instanceof haven.Button) || (w instanceof haven.IButton))
            return Collections.singletonList("Pressed");
        if(w instanceof haven.ACheckBox)   // 061.2: a CheckBox, an ICheckBox and a RadioGroup.RadioButton alike
            return Collections.singletonList("Changed");
        if(w instanceof haven.SListMenu)   // 061.3: a menu holds nothing, so a pick is Selected, not Changed
            return Collections.singletonList("Selected");
        if(w instanceof haven.SListWidget) {
            // ...and a list one level INSIDE a control answers nothing: the seam addresses slistowner(), so a
            // dropdown's popup and a menu's inner list would otherwise be listed for a key that never fires
            // there. See keyElsewhere, which is where the author of that mistake is told where the key went.
            if(((haven.SListWidget<?, ?>)w).slistowner() != w)
                return Collections.<String>emptyList();
            return Collections.singletonList("Changed");
        }
        if(w instanceof haven.GridList)    // 061.3: a grid paints its cells, and a pick is one of them
            return Collections.singletonList("Cell");
        if(w instanceof haven.TextEntry)   // 061.4: Enter, and only Enter — a keystroke is not a submission
            return Collections.singletonList("Submitted");
        if((w instanceof haven.HSlider) || (w instanceof haven.Scrollbar))   // 061.4: ...and uncancelable
            return Collections.singletonList("Changed");
        return Collections.<String>emptyList();
    }

    /**
     * <b>The one case where the key is real and the ADDRESS is wrong</b> (061.3) — appended to
     * {@code widget:on(key, fn)}'s refusal, and empty for every other widget. A dropdown's popup list and a
     * menu's inner list are the inside of a control rather than the control, so {@link #borrowedKeys} does not
     * list their rows' key on them; without this the refusal would read <i>a list has no 'Changed'</i>, which
     * is the one thing an author would not believe, and they would go looking for a bug rather than for the
     * widget the key belongs to.
     */
    static String keyElsewhere(Widget w, String key) {
        if(!(w instanceof haven.SListWidget))
            return "";
        Widget o = ((haven.SListWidget<?, ?>)w).slistowner();
        if((o == w) || !borrowedKeys(o).contains(key))
            return "";
        String noun = (o instanceof haven.SListMenu) ? "menu" : "dropdown";
        return ". A " + noun + "'s rows live in a list of their own, and '" + key + "' fires on the " + noun
            + " itself — hold that widget and subscribe there.";
    }

    /**
     * <b>The interception seam</b> (061.1) — asked by the client's own activation site, through
     * {@code haven.AddonWidgets.activate}, immediately before it runs its own method: <i>does any addon hold
     * {@code key} on this widget, and may I proceed?</i> One helper carries every family, so each
     * {@code // addon:} line in {@code haven} is the same one-liner and the whole decision lives here.
     *
     * <p><b>Every holder fires, and cancelling is OR.</b> The {@link Subs.Cancel} is minted once and shared
     * across every addon of this one activation ({@link Subs#fire}'s existing rule), so any handler cancels,
     * every handler still runs, and the outcome never depends on the order the addons happen to be loaded in.
     *
     * <p><b>An addon that OWNS the control is skipped.</b> Its own adapter already dispatches the key from the
     * method the client calls ({@link #fire}), so without this it would receive the same press twice — a native
     * control lives inside an addon's own controls (a {@code :dropdown()}'s arrow, a window's close button), so
     * this is the common case rather than the odd one.
     *
     * <p><b>And a replay is not an activation.</b> {@code ev:resend()} runs the control's own method, which may
     * route back through the very site that fired: the re-entrancy flag ({@link #replaying}) makes the seam
     * skip while it does, so re-issuing cannot loop.
     *
     * <p><b>And the widget an addon holds is not always the one the client acts on</b> (061.3). A dropdown's
     * rows live in a popup that is not even its child and a menu's in its own inner list, so {@code w} is the
     * address — where the handlers are and what the roster answers for — while {@code actor} is what
     * {@code ev:resend()} runs the held-back method on. Everywhere else they are one widget.
     *
     * @return whether the client should go on and run its own action.
     */
    static boolean activate(Widget w, Widget actor, String key, Object value) {
        return dispatch(w, actor, key, value, false);
    }

    /**
     * <b>The half of the seam that reports rather than asks</b> (061.4) — the two controls that write their
     * value before they say anything ({@code HSlider}'s drag, {@code Scrollbar}'s drag, wheel and step). The
     * client is past the point of being stopped, so this answers nothing and the {@code ev} it hands the
     * handlers raises on both {@code preventDefault} and {@code resend}, naming that the value has moved.
     *
     * <p>Everything else is {@link #activate}'s: the same holders, the same owned-control skip, the same
     * one-lookup cost for a widget nobody listens to.
     */
    static void report(Widget w, String key, Object value) {
        dispatch(w, w, key, value, true);
    }

    /** Both halves of the seam, in one walk — {@code moved} is what tells the {@code ev} which one it is. */
    private static boolean dispatch(Widget w, Widget actor, String key, Object value, boolean moved) {
        if((w == null) || replaying(w, key))
            return true;
        Subs.Cancel c = null;
        for(Addon a : AddonManager.addons)   // a snapshot walk: a handler may disable its own addon mid-fire
            c = offer(a, w, actor, key, value, c, moved);
        c = offer(AddonManager.consoleOwner, w, actor, key, value, c, moved);
        return (c == null) || !c.prevented();
    }

    /** One owner's part of {@link #dispatch}: fire its handlers, minting the shared {@link Subs.Cancel} lazily. */
    private static Subs.Cancel offer(Addon a, Widget w, Widget actor, String key, Object value, Subs.Cancel c,
                                     boolean moved) {
        if(a == null)
            return c;
        WidgetSubs s = a.widgetSubsOrNull(w);        // the hasSub gate: an unlistened widget costs one lookup
        if((s == null) || !s.subs.has(key))
            return c;
        if(LuaWidget.ownedContent(a, w) != null)     // it built this control: Controls.fire is its door, not this
            return c;
        if(c == null)
            c = new Subs.Cancel();
        s.fireBorrowed(key, actor, value, c, moved);
        return c;
    }

    /**
     * Is this activation the one {@code ev:resend()} is replaying <b>in this widget's own tree</b>? Then the
     * seam is not asked again.
     *
     * <p>The flag is the tree's ({@link AddonManager.SessionState#replayWdg}, audit2 B01). It was one pair for
     * the client, confined to neither thread nor session, so a second UI dispatching an activation while one
     * was replaying read the other's {@code (widget, key)}. A widget names its tree, so the question is asked
     * of the tree the widget is in, and a widget with no state behind it — a tree already destroyed — is
     * simply not replaying.
     */
    private static boolean replaying(Widget w, String key) {
        AddonManager.SessionState st = AddonManager.state(w.ui);
        return (st != null) && (st.replayWdg == w) && key.equals(st.replayKey);
    }

    /**
     * <b>{@code ev:resend()}'s other half</b> (061.1) — run the control's own action, the one the seam held
     * back. Which method that is belongs to the family, so this is the mirror of {@link #borrowedKeys}: the
     * roster says a widget answers the key, this says what the key DOES on it.
     *
     * <p>The flag is the ADDRESS ({@code w}), which is what the seam asks with, while the method runs on
     * {@code actor} — the two differ only for a list one level inside a control (061.3). The previous
     * (widget, key) is saved and restored rather than cleared, so a replay that reaches another control's seam
     * is still seen as an activation there — only the one being re-issued is skipped.
     */
    static void replay(final Widget w, final Widget actor, final String key, final Object value) {
        unseamed(w, key, new Runnable() {
            public void run() {
                synchronized(LuaWidget.monitor(actor)) { Controls.run(actor, key, value); }
            }
        });
    }

    /**
     * Run {@code body} with the seam for one (widget, key) <b>suppressed</b> — the flag {@link #replaying}
     * reads, saved and restored rather than cleared, so a call that reaches another control's seam is still
     * seen as an activation there.
     *
     * <p>Two callers, and they are the same claim from two sides: {@code ev:resend()} re-issues an action the
     * seam held back, and {@link #drive} writes a value nobody gestured for. Neither is a user interaction,
     * so neither may be reported as one.
     */
    private static void unseamed(Widget w, String key, Runnable body) {
        AddonManager.SessionState st = (w == null) ? null : AddonManager.state(w.ui);
        if(st == null) {
            body.run();       // no tree to suppress the seam in: the run itself is still owed
            return;
        }
        Widget pw = st.replayWdg;
        String pk = st.replayKey;
        st.replayWdg = w;
        st.replayKey = key;
        try {
            body.run();
        } finally {
            st.replayWdg = pw;
            st.replayKey = pk;
        }
    }

    /** The client's own method behind one key on one widget — {@link #replay}'s dispatch, and nothing else. */
    private static void run(Widget w, String key, Object value) {
        if(w instanceof haven.Button) {
            ((haven.Button)w).click();
            return;
        }
        if(w instanceof haven.IButton) {
            ((haven.IButton)w).click();
            return;
        }
        if(w instanceof haven.RadioGroup.RadioButton) {   // 061.2: BEFORE the checkbox arm — a radio is one
            haven.RadioGroup.RadioButton rb = (haven.RadioGroup.RadioButton)w;
            rb.group().check(rb);   // ...and its own activation moves the GROUP's selection, not just its tick
            return;
        }
        if(w instanceof haven.ACheckBox) {
            ((haven.ACheckBox)w).click();
            return;
        }
        if(w instanceof haven.SListWidget) {   // 061.3: virtually, so a popup closes and a menu fires its choice
            haven.AddonWidgets.listChange((haven.SListWidget<?, ?>)w, value);
            return;
        }
        if(w instanceof haven.GridList) {      // ...and a grid's is the selecting button's own click
            haven.AddonWidgets.gridClick((haven.GridList<?>)w, value);
            return;
        }
        if(w instanceof haven.TextEntry) {     // 061.4: VIRTUALLY, so the chat's own override does the sending
            ((haven.TextEntry)w).activate((value == null) ? "" : String.valueOf(value));
            return;
        }
        throw new LuaError("ev:resend() — a " + LuaWidget.typeName(w) + " has no '" + key + "' action of its"
            + " own to run again.");
    }

    // ------------------------------------------------------------------ the face setter (040.2)

    /** {@code widget:image()} — the faces this control was given, or {@code nil} where a control has none. */
    static LuaValue faces(Owned c) {
        if(c instanceof CtlIButton)
            return ((CtlIButton)c).faces();
        if(c instanceof CICheck)
            return ((CICheck)c).faces();
        return LuaValue.NIL;
    }

    /**
     * {@code widget:image(up, down [, hover])} — <b>the face setter</b>, and the second class behind one builder:
     * it completes {@code hafen.ui():button()} as an {@link haven.IButton} where {@code :text(s)} completes it as
     * a {@link haven.Button} (spec 040 decision E/F).
     *
     * <p><b>Two or three faces</b>, and {@code hover} defaults to {@code up} — the engine's own two-argument
     * {@code IButton} constructor does exactly that, so the default is the client's rather than one invented here.
     *
     * <p><b>Legal while the control is being BUILT, refused once it is on screen.</b> An {@code IButton}'s faces
     * are {@code final} and its box is the picture's size, so choosing one is not a property write but the choice
     * of <i>which widget this is</i> — D-113 (a setter that changes how the visual is BUILT rebuilds it) inside
     * D-119's arming window, which is the same shape {@code :parent(w)} already has (D-121). {@code :text(s)}
     * after arming is a different thing and keeps working: {@code Button.change(String)} is a live setter.
     *
     * <p><b>Every face is resolved before anything is replaced</b>, so a bad handle or an unknown resource name
     * leaves the control exactly as it was — the same guarantee D-113 states for the overlay rebuild.
     *
     * <p><b>A checkbox is the other completion this verb drives</b> (task 040.4): {@code widget:image(up, down,
     * hoverUp, hoverDown)} on a {@code hafen.ui():check()} completes it as an {@link haven.ICheckBox} the same
     * building-only way, but with FOUR faces rather than two or three — a checkbox carries two persistent states
     * (checked/unchecked), each with its own hover, where a button has one gesture. That shape lives in
     * {@link #checkImage}; this method only tells the two apart before either runs.
     */
    static void image(Addon owner, Widget w, Owned c, Varargs a) {
        if((c instanceof CCheck) || (c instanceof CICheck)) {
            checkImage(owner, w, c, a);
            return;
        }
        LuaValue upv = Args.required(a, 2, "widget:image", "up");
        LuaValue downv = Args.required(a, 3, "widget:image", "down");
        LuaValue hoverv = Args.passed(a, 4) ? Args.required(a, 4, "widget:image", "hover") : upv;
        if(Args.passed(a, 5))
            throw new LuaError("widget:image(up, down[, hover]) takes TWO or THREE faces — the released one, the"
                + " pressed one, and (optionally) the one under the cursor, which defaults to the released face.");
        if(!(c instanceof CtlButton) && !(c instanceof CtlIButton))
            throw new LuaError("widget:image(...) sets the FACE of a control you built, and hafen.ui():button()"
                + " or hafen.ui():check() are the builders that take one — " + LuaWidget.typeName(w) + " has no"
                + " face to set. A surface you paint yourself draws its own pictures with g:image inside"
                + " widget:on(\"Draw\", fn).");
        if(!c.pending())
            throw new LuaError("widget:image(up, down[, hover]) chooses a button's FACE while the control is"
                + " being BUILT, and this one is already on screen — a face is not a property of a button, it IS"
                + " which button this is (the client draws a captioned one and a picture one with two different"
                + " widgets), so set it in the same statement that builds the control. A caption, unlike a face,"
                + " is live: widget:text(s) works at any time.");
        BufferedImage up = face(upv, "up"), down = face(downv, "down"), hover = face(hoverv, "hover");
        CtlIButton nu = new CtlIButton(owner, up, down, hover, upv, downv, hoverv);
        UiApi.rebuild(owner, c, nu);
    }

    /**
     * {@code widget:image(up, down, hoverUp, hoverDown)} on a checkbox — the four-face completion to
     * {@link haven.ICheckBox} (task 040.4). Building-only like {@link #image}, and carries the checked state
     * across the rebuild exactly as {@link #image} carries a button's picked face. (A {@code widget:on(key, fn)}
     * subscription cannot yet exist to carry: 041.3 found it must be its own statement, after the chain that
     * builds and faces the control has already finished.)
     */
    private static void checkImage(Addon owner, Widget w, Owned c, Varargs a) {
        LuaValue upv = Args.required(a, 2, "widget:image", "up");
        LuaValue downv = Args.required(a, 3, "widget:image", "down");
        LuaValue hoverUpv = Args.required(a, 4, "widget:image", "hoverUp");
        LuaValue hoverDownv = Args.required(a, 5, "widget:image", "hoverDown");
        if(Args.passed(a, 6))
            throw new LuaError("widget:image(up, down, hoverUp, hoverDown) on a checkbox takes exactly FOUR"
                + " faces — the unchecked look, the checked look, and each one again under the cursor.");
        if(!c.pending())
            throw new LuaError("widget:image(up, down, hoverUp, hoverDown) chooses a checkbox's FACES while the"
                + " control is being BUILT, and this one is already on screen — a face is not a property of a"
                + " checkbox, it IS which checkbox this is (the client draws a captioned one and a picture one"
                + " with two different widgets), so set it in the same statement that builds the control. A"
                + " caption, unlike a face, is live: widget:text(s) works at any time.");
        Tex up = faceTex(upv, "up"), down = faceTex(downv, "down");
        Tex hoverUp = faceTex(hoverUpv, "hoverUp"), hoverDown = faceTex(hoverDownv, "hoverDown");
        CICheck nu = new CICheck(owner, up, down, hoverUp, hoverDown, upv, downv, hoverUpv, hoverDownv);
        if(c instanceof Value) {
            LuaValue v = ((Value)c).value();       // the checked state outlives the rebuild, like a button's face
            if((v != null) && !v.isnil())
                nu.value(v);
        }
        UiApi.rebuild(owner, c, nu);
    }

    /**
     * One face &rarr; the image behind it. <b>Two sources, and they are genuinely different things</b>: a
     * {@code hafen.asset} handle is a file the addon ships, drawn at its own pixels (D-081 — only a type size is
     * scaled); a <b>string</b> is one of the CLIENT's own resources ({@code "gfx/hud/buttons/addu"}), taken
     * {@code scaled()} exactly as every {@code IButton} the client builds takes it, so an addon's button matches
     * the art beside it at any UI scale.
     *
     * <p>That is why a string here is not the "path string" other verbs refuse: it names the game's art, not the
     * addon's folder. A string that looks like a file says so and names {@code hafen.asset}, because the two are
     * one keystroke apart and the wrong one would otherwise fail as "no such resource".
     */
    private static BufferedImage face(LuaValue v, String which) {
        LuaImage li = LuaImage.resolve(v);
        if(li != null) {
            if(li.dead)
                throw new LuaError("widget:image: the " + which + " face has been freed — after"
                    + " hafen.asset():remove(a), hafen.asset():get(path) loads the file again as a NEW asset");
            return li.tex.back;
        }
        if(v.isstring() && !v.isnumber()) {       // in LuaJ a number IS a string — that one is just a wrong type
            String name = v.tojstring();
            if(fileish(name))
                throw new LuaError("widget:image: \"" + name + "\" looks like a file in your own addon folder,"
                    + " and a STRING here names one of the client's own resources"
                    + " (\"gfx/hud/buttons/addu\") — load your own image with hafen.asset():get(\"" + name
                    + "\") and pass the handle");
            Resource.Image ri;
            try {
                ri = Resource.loadrimg(name);
            } catch(RuntimeException e) {
                throw new LuaError("widget:image: the client has no resource named \"" + name + "\" (the "
                    + which + " face) — a face is either a name from the game's own art"
                    + " (\"gfx/hud/buttons/addu\") or a hafen.asset():get(\"up.png\") handle");
            }
            if(ri == null)
                throw new LuaError("widget:image: the client resource \"" + name + "\" (the " + which + " face)"
                    + " carries no image layer — name the image resource itself"
                    + " (\"gfx/hud/buttons/addu\", not its folder)");
            return ri.scaled();
        }
        throw new LuaError("widget:image: the " + which + " face is a hafen.asset image handle"
            + " (hafen.asset():get(\"up.png\")) or a client resource name (\"gfx/hud/buttons/addu\"), got "
            + v.typename());
    }

    /**
     * One checkbox face &rarr; the {@link Tex} behind it (task 040.4) — the same two doors {@link #face}
     * resolves, but returning a {@link Tex} rather than a {@link BufferedImage}: {@link haven.ICheckBox} blits a
     * {@code Tex} each frame (a plain {@code Widget}, not an {@link haven.SIWidget} that rasterises once), where
     * {@link haven.IButton} wants the raster. Errors read exactly as {@link #face}'s, {@code widget:image}
     * included, since both are the same verb's argument.
     */
    private static Tex faceTex(LuaValue v, String which) {
        LuaImage li = LuaImage.resolve(v);
        if(li != null) {
            if(li.dead)
                throw new LuaError("widget:image: the " + which + " face has been freed — after"
                    + " hafen.asset():remove(a), hafen.asset():get(path) loads the file again as a NEW asset");
            // audit2 B15: as a LIVE view, not the raw TexI. The widget keeps this for as long as it lives and
            // blits it every frame; the dead test above is one-shot, and nothing else re-asks it.
            return LuaImage.live(li, li.tex);
        }
        if(v.isstring() && !v.isnumber()) {       // in LuaJ a number IS a string — that one is just a wrong type
            String name = v.tojstring();
            if(fileish(name))
                throw new LuaError("widget:image: \"" + name + "\" looks like a file in your own addon folder,"
                    + " and a STRING here names one of the client's own resources"
                    + " (\"gfx/hud/buttons/addu\") — load your own image with hafen.asset():get(\"" + name
                    + "\") and pass the handle");
            Resource.Image ri;
            try {
                ri = Resource.loadrimg(name);
            } catch(RuntimeException e) {
                throw new LuaError("widget:image: the client has no resource named \"" + name + "\" (the "
                    + which + " face) — a face is either a name from the game's own art"
                    + " (\"gfx/hud/buttons/addu\") or a hafen.asset():get(\"up.png\") handle");
            }
            if(ri == null)
                throw new LuaError("widget:image: the client resource \"" + name + "\" (the " + which + " face)"
                    + " carries no image layer — name the image resource itself"
                    + " (\"gfx/hud/buttons/addu\", not its folder)");
            return ri.tex();
        }
        throw new LuaError("widget:image: the " + which + " face is a hafen.asset image handle"
            + " (hafen.asset():get(\"up.png\")) or a client resource name (\"gfx/hud/buttons/addu\"), got "
            + v.typename());
    }

    /** Does this string name a FILE rather than a client resource? (Resource names carry no extension.) */
    static boolean fileish(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")
            || n.endsWith(".bmp");
    }

    // ------------------------------------------------------------------ the value verb (040.3)

    /** {@code widget:value()} — what the control holds, or {@code nil} on one that holds nothing. */
    static LuaValue value(Owned c) {
        if(!(c instanceof Value))
            return LuaValue.NIL;
        LuaValue v = ((Value)c).value();
        return (v == null) ? LuaValue.NIL : v;
    }

    /**
     * {@code widget:value(v)} — writes what the control holds. Dispatches on {@link Value} and hands {@code v}
     * straight to the implementation, which does its own type/range check and throws naming it (040.3:
     * {@link CProgress} requires a number in {@code 0..1}; a later control's own rule is its own to state).
     */
    static void value(Owned c, Widget w, LuaValue v) {
        if(c instanceof Value) {
            ((Value)c).value(v);
            WidgetSurface.touch(w);   // 044.1: a value is what a signature over the tree cannot see
            return;
        }
        throw noValue(w);
    }

    /**
     * <b>Nothing here holds a value</b> — one refusal for both arms of {@code widget:value(v)} (061.8). It
     * names <i>what holds one</i> rather than the builders that make one: the write answers on a control the
     * client built exactly as on one the addon did, so a message listing only the builders would send the
     * author of {@code label:value(true)} looking for the wrong mistake.
     */
    static LuaError noValue(Widget w) {
        return new LuaError("widget:value(v) writes what a control HOLDS, and " + LuaWidget.typeName(w)
            + " holds nothing — a checkbox, a radio button, a slider, a scrollbar, a text entry, a list, a"
            + " dropdown and a kin-colour row are what hold one, whether the client built it or you did"
            + " (hafen.ui():check(),"
            + " :radio(), :slider(), :scrollbar(), :entry(), :listbox(), :dropdown() — and :progress(), which"
            + " holds one on a bar you built).");
    }

    // ------------------------------------------------------------------ driving a borrowed control (061.8)

    /**
     * <b>{@code widget:value(v)} on a control the addon did NOT build</b> (061.8) — the one <b>act</b> of the
     * editing surface, and the mirror of {@link LuaWidget#value(Widget)}, which reads the same families back.
     *
     * <p><b>It drives the funnel, not the input.</b> Each arm calls the very method the client's own gesture
     * ends in — {@code ACheckBox.set}, {@code RadioGroup.check}, a slider's value write and its
     * {@code changed}/{@code fchanged} hooks, {@code Scrollbar.ch}, {@code TextEntry.settext},
     * {@code SListWidget.change} — so {@code canactivate} and the outgoing {@code wdgmsg} behave exactly as
     * they do when the user does it, and a subclass's own override is what runs.
     *
     * <p><b>A write is not an interaction, so it fires nothing.</b> The capability seams sit where the client
     * <i>receives</i> input, which no arm below goes through — except a scrollbar, whose {@code ch(int)} is
     * both its value write and one of its two seams, so that one arm marks itself ({@link #unseamed}). One
     * rule for all seven families: driving a control never dispatches its own {@code Changed}.
     *
     * <p>Gated by {@code widget.value} at the call site, before this is reached (D-213).
     */
    static void drive(Widget w, LuaValue v) {
        Object mon = LuaWidget.monitor(w);   // 072.1: the monitor of the tree THIS control is in
        if(w instanceof haven.Progress)
            throw new LuaError("widget:value(v) on a progress bar of the client's own — what it draws is a"
                + " Supplier the client re-reads every frame, so a value written here would be gone before"
                + " it was seen. widget:value() reads the fraction it is showing.");
        if(w instanceof haven.BuddyWnd.GroupSelector) {
            // select(group) is the very method GroupRect's own mousedown ends in, and the polity windows
            // override it to send their message (ui/vlg's Village sends "gsel", and the member panel its
            // own) -- so this drive IS the click, and the group need not be one the eight squares can show.
            final haven.BuddyWnd.GroupSelector gs = (haven.BuddyWnd.GroupSelector)w;
            final int to = num(v, "a colour row");
            if((to < 0) || (to > 254))
                throw new LuaError("widget:value(v) on a colour row is a GROUP, 0..254 — the range the"
                    + " server accepts; the eight colours only reach 0..7, got " + to);
            synchronized(mon) { gs.select(to); }
            return;
        }
        if(w instanceof haven.RadioGroup.RadioButton) {   // BEFORE the checkbox arm — a radio button is one
            haven.RadioGroup.RadioButton rb = (haven.RadioGroup.RadioButton)w;
            haven.RadioGroup.RadioButton tgt = row(rb, str(v, "a radio button", "the LABEL of one of its"
                + " group's rows"));
            synchronized(mon) { rb.group().check(tgt); }
            return;
        }
        if(w instanceof haven.ACheckBox) {                // a CheckBox and an ICheckBox alike
            boolean b = Args.bool(v, "widget:value", "v", "a checkbox holds one or the other");
            synchronized(mon) { ((haven.ACheckBox)w).set(b); }
            return;
        }
        if(w instanceof haven.HSlider) {
            final haven.HSlider s = (haven.HSlider)w;
            int to = clamp(num(v, "a slider"), s.min, s.max);
            synchronized(mon) {
                if(to != s.val) {     // ...and driving one to what it already holds does nothing at all
                    s.val = to;
                    s.changed();      // the drag's own hook, then the release's: a drive is the whole gesture
                    s.fchanged();
                }
            }
            return;
        }
        if(w instanceof haven.Scrollbar) {
            final haven.Scrollbar s = (haven.Scrollbar)w;
            // audit2 B15: a scrollbar built as Scrollbar(int, Scrollable) -- the constructor a listbox and a
            // dropdown use (docs/client/ui-lists.md) -- re-reads min/max/val off its Scrollable in every
            // draw, so a value written here is gone by the next drawn frame. A write that is silently
            // reverted is the one outcome this API refuses to hand back, and the thing that CAN be driven is
            // the list itself: widget:value(row) scrolls it there by moving what it is showing.
            if(s.ctl != null)
                throw new LuaError("widget:value(v) on a scrollbar that belongs to a list is refused: that"
                    + " bar reads its position back off the list on every frame, so the write would be undone"
                    + " before you saw it. Drive the LIST -- widget:value(row) on the list itself, with a row"
                    + " out of widget:rows() -- and the bar follows");
            int to = clamp(num(v, "a scrollbar"), s.min, s.max);
            synchronized(mon) {
                final int step = to - s.val;              // ch(int) is RELATIVE: there is no absolute setter
                if(step != 0) {
                    unseamed(s, "Changed", new Runnable() {
                        public void run() { s.ch(step); }
                    });
                }
            }
            return;
        }
        if(w instanceof haven.TextEntry) {
            String s = str(v, "a text entry", "the text it holds");
            // audit2 B15: rsettext, never settext. docs/client/ui-lists.md records the pair: settext notifies
            // `changed` and marks the field dirty -- it is the USER typing -- where rsettext is the silent
            // one a value write has to go through. Driving a field with settext made the client believe the
            // player had edited it.
            synchronized(mon) { ((haven.TextEntry)w).rsettext(s); }
            return;
        }
        if(w instanceof haven.SListWidget) {              // a list, and a dropdown, which is one
            haven.SListWidget<?, ?> l = (haven.SListWidget<?, ?>)w;
            if(!v.isuserdata())
                throw new LuaError("widget:value(v) on one of the client's own lists takes a ROW OF THAT"
                    + " LIST — the opaque value widget:value() and ev:value() hand you. Its rows are the"
                    + " client's own objects, so there is nothing here to build one out of: hold the row you"
                    + " were given, and hand it back.");
            Object row = v.touserdata();
            if(!haven.AddonWidgets.listHas(l, row))
                throw new LuaError("widget:value(v) — that row is not in this list. A row is only ever the"
                    + " one this list handed you, and the client rebuilds its own rows on its own schedule,"
                    + " so one kept across a refill is a row this list no longer has.");
            synchronized(mon) { haven.AddonWidgets.listChange(l, row); }
            return;
        }
        throw noValue(w);
    }

    /**
     * {@code v} as the number a slider or a scrollbar holds, through {@link Args#integer} — a position
     * within a range is a whole number, and 2.7 truncated to 2 was a value the control never showed.
     * Package-private
     * <b>because the adapters call it too</b>: {@link CSlider}, {@link CScrollbar} and {@link CScrollport}'s
     * bar hold the same kind of value as the client's own controls of those kinds, and a control family with
     * two type languages refuses {@code "50"} on one of a pair and takes it on the other.
     */
    static int num(LuaValue v, String what) {
        return Args.integer(v, "widget:value", "v", "on " + what + " it is a position within its range");
    }

    /** {@code v} as a string, through {@link Args#str} — which is where the LuaJ coercion rule is stated. */
    static String str(LuaValue v, String what, String is) {
        return Args.str(v, "widget:value", "v", "on " + what + " it is " + is).tojstring();
    }

    /** A value into the bounds the control itself carries — clamped, exactly as a control you built is. */
    private static int clamp(int v, int min, int max) {
        return (v < min) ? min : ((v > max) ? max : v);
    }

    /**
     * The button of {@code rb}'s own group carrying {@code row}, or the refusal listing the rows it has. A
     * {@link haven.RadioGroup} is not a widget, so the set is read off the buttons themselves: every one of
     * them is added into the one {@code parent} the group was built on, and each answers whose group it is.
     */
    private static haven.RadioGroup.RadioButton row(haven.RadioGroup.RadioButton rb, String row) {
        StringBuilder rows = new StringBuilder();
        for(haven.RadioGroup.RadioButton b : siblings(rb)) {
            String r = b.row();
            if(row.equals(r))
                return b;
            if(rows.length() > 0)
                rows.append(", ");
            rows.append('"').append(r).append('"');
        }
        throw new LuaError("widget:value(v) — this radio has no row named \"" + row + "\"; its rows are "
            + ((rows.length() == 0) ? "not readable from here" : rows.toString()) + ".");
    }

    /** Every button of {@code rb}'s group, in tree order, taken under the {@code ui} monitor. */
    private static List<haven.RadioGroup.RadioButton> siblings(haven.RadioGroup.RadioButton rb) {
        List<haven.RadioGroup.RadioButton> out = new java.util.ArrayList<haven.RadioGroup.RadioButton>();
        Widget p = rb.parent;
        if(p == null)
            return out;
        synchronized(LuaWidget.monitor(p)) {
            for(Widget c : p.children()) {
                if((c instanceof haven.RadioGroup.RadioButton)
                   && (((haven.RadioGroup.RadioButton)c).group() == rb.group()))
                    out.add((haven.RadioGroup.RadioButton)c);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the source setter (040.3)

    /** {@code widget:source()} — the handle or resource name a picture was given, or {@code nil} before one is. */
    static LuaValue source(Owned c) {
        if(!(c instanceof Source))
            return LuaValue.NIL;
        LuaValue v = ((Source)c).source();
        return (v == null) ? LuaValue.NIL : v;
    }

    /**
     * {@code widget:source(h)} — a picture's own content setter (decision E). Unlike a button's face this is
     * NOT building-only: {@link haven.Img#setimg} is a live, public, post-construction setter, so the picture
     * may be replaced at any time, armed or not. Resolved through the same two doors as a button face
     * ({@code hafen.asset} handle, or a client resource name) but installed as a {@link haven.Tex} directly —
     * a client resource's own {@code Resource.Image.tex()} is already the cached, UI-scaled texture, so there
     * is no BufferedImage round trip to pay for what {@link CtlIButton} needs and this does not.
     */
    static void source(Addon owner, Widget w, Owned c, LuaValue v) {
        if(!(c instanceof CImg))
            throw new LuaError("widget:source(h) sets the PICTURE of a control you built, and hafen.ui():image()"
                + " is the builder that takes one — " + LuaWidget.typeName(w) + " has no picture to set.");
        Tex tex = sourceTex(v);
        CImg img = (CImg)c;
        img.setimg(tex);
        img.source(v);
        WidgetSurface.touch(w);       // 044.1: ...nor the picture a control shows
    }

    /** {@code widget:source(h)}'s handle → a {@link Tex}. Same two doors {@link #face} resolves, minus the plural. */
    private static Tex sourceTex(LuaValue v) {
        LuaImage li = LuaImage.resolve(v);
        if(li != null) {
            if(li.dead)
                throw new LuaError("widget:source: this asset has been freed — after"
                    + " hafen.asset():remove(a), hafen.asset():get(path) loads the file again as a NEW asset");
            return LuaImage.live(li, li.tex);   // audit2 B15: a live view — see faceTex
        }
        if(v.isstring() && !v.isnumber()) {       // in LuaJ a number IS a string — that one is just a wrong type
            String name = v.tojstring();
            if(fileish(name))
                throw new LuaError("widget:source: \"" + name + "\" looks like a file in your own addon folder,"
                    + " and a STRING here names one of the client's own resources"
                    + " (\"gfx/hud/buttons/addu\") — load your own image with hafen.asset():get(\"" + name
                    + "\") and pass the handle");
            Resource.Image ri;
            try {
                ri = Resource.loadrimg(name);
            } catch(RuntimeException e) {
                throw new LuaError("widget:source: the client has no resource named \"" + name + "\" — a"
                    + " picture's source is either a name from the game's own art (\"gfx/hud/buttons/addu\")"
                    + " or a hafen.asset():get(\"logo.png\") handle");
            }
            if(ri == null)
                throw new LuaError("widget:source: the client resource \"" + name + "\" carries no image layer"
                    + " — name the image resource itself (\"gfx/hud/buttons/addu\", not its folder)");
            return ri.tex();
        }
        throw new LuaError("widget:source: the picture is a hafen.asset image handle"
            + " (hafen.asset():get(\"logo.png\")) or a client resource name (\"gfx/hud/buttons/addu\"), got "
            + v.typename());
    }

    // ------------------------------------------------------------------ the rows verb (040.5)

    /** {@code widget:rows()} — the table last given, or {@code nil} on a control with no row source. */
    static LuaValue rows(Owned c) {
        if(!(c instanceof Rows))
            return LuaValue.NIL;
        LuaValue t = ((Rows)c).rows();
        return (t == null) ? LuaValue.NIL : t;
    }

    /**
     * {@code widget:rows(t)} — the ROW SOURCE, an array {@code t}. Dispatches on {@link Rows} and hands
     * {@code t} straight to the implementation, which does its own validation and throws naming the rule
     * (040.5: {@link CRadio} requires an array of unique string labels).
     */
    static void rows(Owned c, Widget w, LuaValue t) {
        if(c instanceof Rows) {
            ((Rows)c).rows(t);
            WidgetSurface.touch(w);   // 044.1: ...and neither is a row list
            return;
        }
        throw new LuaError("widget:rows(t) sets a control's ROW SOURCE, and hafen.ui():radio(),"
            + " hafen.ui():listbox(), hafen.ui():dropdown(), hafen.ui():menu(), hafen.ui():grid() and"
            + " hafen.ui():table() are the builders that take one — " + LuaWidget.typeName(w) + " has no rows.");
    }

    // ------------------------------------------------------------------ the range verb (040.6)

    /** {@code widget:range()} — {@code {min=, max=}} as they stand, or {@code nil} on a control with no range. */
    static LuaValue range(Owned c) {
        if(!(c instanceof Range))
            return LuaValue.NIL;
        return ((Range)c).range();
    }

    /** One {@code :range} bound, through {@link Args#integer} — the same door {@link #num} is for a value, and
     *  the reason the adapters call it rather than testing {@code isnumber()} each for themselves. */
    static int bound(LuaValue v, String param) {
        return Args.integer(v, "widget:range", param, "a bound of the control's own range");
    }

    /**
     * {@code widget:range(min, max)} — the value BOUNDS of a slider or scrollbar. Dispatches on {@link Range}
     * and hands both raw bounds to the implementation, which type-checks them through {@link #bound} and
     * throws naming the rule (040.6: {@link CSlider}/{@link CScrollbar} require {@code min <= max}, and
     * re-clamp a value that no longer fits WITHOUT firing {@code :onChange} — narrowing the range is not a
     * user interaction).
     */
    static void range(Owned c, Widget w, Varargs a) {
        if(!(c instanceof Range))
            throw new LuaError("widget:range(min, max) sets a control's VALUE BOUNDS, and hafen.ui():slider()"
                + " and hafen.ui():scrollbar() are the builders that take one — " + LuaWidget.typeName(w)
                + " has no range.");
        LuaValue minv = Args.required(a, 2, "widget:range", "min");
        LuaValue maxv = Args.required(a, 3, "widget:range", "max");
        ((Range)c).range(minv, maxv);
        WidgetSurface.touch(w);       // 044.1: ...nor a slider's bounds
    }

    // ------------------------------------------------------------------ the rowHeight verb (040.9)

    /**
     * {@code widget:rowHeight()} — the row height in design pixels, or {@code nil} on a control with no rows.
     * Converted out of the client's own space (058.3), so a height this API was given reads back unchanged and a
     * stock one — the client's label height, which grows with the UI scale — reads as the same design number on
     * every client.
     */
    static LuaValue rowHeight(Owned c) {
        if(!(c instanceof RowHeight))
            return LuaValue.NIL;
        return LuaValue.valueOf(Px.out(((RowHeight)c).rowHeight()));
    }

    /**
     * {@code widget:rowHeight(n)} — building-only, like {@link #image} (spec 040 decision G): the client's own
     * row-list widgets fix their row height at construction ({@code SListBox.itemh}, {@code SDropBox.itemh},
     * {@code SListMenu}'s inner {@code box.itemh} — all {@code final}), so a different one is a different widget
     * under the same Lua handle. Carries the current rows and, where the control has one, the selection, across
     * the rebuild, exactly as {@link #image} carries a button's picked face. {@link CList} (040.9) is the first
     * implementor; {@link CDropdown}/{@link CMenu} (040.10) answer it the same way, each rebuilding its own
     * class; {@link CTable} (040.12) too, carrying its current columns across the rebuild instead of a selection.
     * (A {@code widget:on(key, fn)} subscription cannot yet exist to carry — see {@link #image}.)
     */
    static void rowHeight(Addon owner, Widget w, Owned c, LuaValue v) {
        if(!(c instanceof RowHeight))
            throw new LuaError("widget:rowHeight(n) sets a list's ROW HEIGHT, and hafen.ui():listbox(),"
                + " hafen.ui():dropdown(), hafen.ui():menu() and hafen.ui():table() are the builders that take"
                + " one — " + LuaWidget.typeName(w) + " has none.");
        // DESIGN px, as written: checked, reported and only then converted
        int des = Args.integer(v, "widget:rowHeight", "n", "a NUMBER of design pixels");
        if(des <= 0)
            throw new LuaError("widget:rowHeight(n) — n must be a POSITIVE number of pixels, got " + des);
        int n = Px.in(des);                   // 058.3: the client's own row widgets measure in device px
        if(!c.pending())
            throw new LuaError("widget:rowHeight(n) chooses a list's ROW HEIGHT while the control is being"
                + " BUILT, and this one is already on screen — the client's own row-list widget fixes its row"
                + " height at construction, so set it in the same statement that builds the control.");
        if(c instanceof CList) {
            CList old = (CList)c;
            CList nu = new CList(owner, old.sz, n);
            if(old.rows() != null)
                nu.rows(old.rows());
            if(old.value() != null)
                nu.value(old.value());
            UiApi.rebuild(owner, old, nu);
            return;
        }
        if(c instanceof CDropdown) {
            CDropdown old = (CDropdown)c;
            CDropdown nu = new CDropdown(owner, old.sz.x, old.listh, n);
            if(old.rows() != null)
                nu.rows(old.rows());
            if(old.value() != null)
                nu.value(old.value());
            UiApi.rebuild(owner, old, nu);
            return;
        }
        if(c instanceof CMenu) {
            CMenu old = (CMenu)c;
            CMenu nu = new CMenu(owner, old.boxSz(), n);
            if(old.rows() != null)
                nu.rows(old.rows());
            UiApi.rebuild(owner, old, nu);
            return;
        }
        CTable old = (CTable)c;
        CTable nu = CTable.create(owner, old.sz, n, old.cols(), old.columns());
        if(old.rows() != null)
            nu.rows(old.rows());
        UiApi.rebuild(owner, old, nu);
    }

    // ------------------------------------------------------------------ the cellSize verb (040.11)

    /**
     * {@code widget:cellSize()} — the current cell box {@code {w=, h=}} in design pixels, or {@code nil} on a
     * control with no cells. Converted out like {@link #rowHeight}: a bare grid's stock box reads {@code 32x32}
     * — the client's own inventory slot, in the pixels its art was drawn at — on every client.
     */
    static LuaValue cellSize(Owned c) {
        if(!(c instanceof CellSize))
            return LuaValue.NIL;
        Coord sz = Px.out(((CellSize)c).cellSize());
        LuaTable t = new LuaTable();
        t.set("w", LuaValue.valueOf(sz.x));
        t.set("h", LuaValue.valueOf(sz.y));
        return t;
    }

    /**
     * {@code widget:cellSize(w, h)} — building-only, exactly like {@link #rowHeight}: {@code
     * GridList.Group.itemsz} is {@code final}, so choosing a different cell box is a different widget under the
     * same Lua handle (D-164). Carries the current rows across the rebuild. {@link CGrid} (040.11) is its one
     * implementor.
     */
    static void cellSize(Addon owner, Widget w, Owned c, Varargs a) {
        if(!(c instanceof CellSize))
            throw new LuaError("widget:cellSize(w, h) sets a GRID's CELL SIZE, and hafen.ui():grid() is the"
                + " builder that takes one — " + LuaWidget.typeName(w) + " has none.");
        // DESIGN px, as written: checked and reported in that space
        int cw = Args.integer(a, 2, "widget:cellSize", "w", "a NUMBER of design pixels");
        int ch = Args.integer(a, 3, "widget:cellSize", "h", "a NUMBER of design pixels");
        if((cw <= 0) || (ch <= 0))
            throw new LuaError("widget:cellSize(w, h) — both must be POSITIVE numbers of pixels, got " + cw + "x"
                + ch);
        if(!c.pending())
            throw new LuaError("widget:cellSize(w, h) chooses a grid's CELL SIZE while the control is being"
                + " BUILT, and this one is already on screen — the client's own grid widget fixes its cell box"
                + " at construction, so set it in the same statement that builds the control.");
        CGrid old = (CGrid)c;
        CGrid nu = new CGrid(owner, old.sz, Px.in(new Coord(cw, ch)));   // 058.3: GridList measures in device px
        if(old.rows() != null)
            nu.rows(old.rows());
        UiApi.rebuild(owner, old, nu);
    }

    // ------------------------------------------------------------------ the columns verb (040.12)

    /** {@code widget:columns()} — exactly the table last given, or {@code nil} on a control with no columns. */
    static LuaValue columns(Owned c) {
        if(!(c instanceof Columns))
            return LuaValue.NIL;
        LuaValue t = ((Columns)c).columns();
        return (t == null) ? LuaValue.NIL : t;
    }

    /**
     * {@code widget:columns(t)} — building-only, exactly like {@link #cellSize}/{@link #rowHeight}:
     * {@code TableBox.cols}/{@code main} are {@code public final}, so choosing a different column set is a
     * different widget under the same Lua handle. Carries the current rows across the rebuild — re-resolved
     * against the NEW columns, since each row's cell text comes from ITS column's {@code of(row)} — the same
     * shape {@link #cellSize} carries a grid's rows across a cell-size rebuild. {@link CTable} (040.12) is
     * its one implementor.
     */
    static void columns(Addon owner, Widget w, Owned c, LuaValue t) {
        if(!(c instanceof Columns))
            throw new LuaError("widget:columns(t) sets a TABLE's COLUMNS, and hafen.ui():table() is the builder"
                + " that takes one — " + LuaWidget.typeName(w) + " has none.");
        if(!c.pending())
            throw new LuaError("widget:columns(t) chooses a table's COLUMNS while the control is being BUILT,"
                + " and this one is already on screen — the client's own table widget fixes its columns at"
                + " construction, so set it in the same statement that builds the control.");
        List<CTable.ColDef> parsed = CTable.parseColumns(t);
        CTable old = (CTable)c;
        CTable nu = CTable.create(owner, old.sz, old.rowHeight(), parsed, t);
        if(old.rows() != null)
            nu.rows(old.rows());
        UiApi.rebuild(owner, old, nu);
    }
}
