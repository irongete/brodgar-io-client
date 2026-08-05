package io.brodgar.addon;

import haven.Resource;
import haven.Tex;
import haven.UI;
import haven.Widget;
import haven.Window;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.awt.image.BufferedImage;

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
     * <b>A control verb dispatches on a CAPABILITY, not on a class</b> (040.2). {@code :onPress(fn)} belongs to
     * <i>a thing that fires and holds nothing</i>, and after this task two unrelated {@code haven} classes are
     * that thing — {@link haven.Button} and {@link haven.IButton}, which share no ancestor below {@code Widget}.
     * Naming them both at every call site is how a dispatch chain rots: the third one is added in four places and
     * forgotten in the fifth. So each verb gets one tiny interface, the adapters implement the ones they answer,
     * and the dispatch below is an {@code instanceof} against the <b>verb</b>.
     */
    interface Press {
        /** The installed {@code :onPress} handler, or {@code null}. */
        LuaValue onPress();

        void onPress(LuaValue fn);
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
     * <b>{@code :onChange(fn)} — the value CHANGED</b> (spec 040 §1, task 040.4): the notification half of the
     * {@code :value()} spine, dispatched on this capability exactly as {@code :onPress} dispatches on
     * {@link Press}. Fires from a real user interaction only — a programmatic {@code :value(v)} is a direct
     * field write on the implementation and never calls it, which is what keeps the write from re-entering its
     * own handler. {@link CCheck} is the first implementor; every later control with a value implements it the
     * same way.
     */
    interface Change {
        /** The installed {@code :onChange} handler, or {@code null}. */
        LuaValue onChange();

        void onChange(LuaValue fn);
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
     * <b>{@code :onSubmit(fn)} — the ENTRY's Enter</b> (task 040.7). {@link CEntry} is its one implementor, and
     * it is deliberately a different name from {@link Change}: {@code :onChange} fires on every keystroke,
     * {@code :onSubmit} once, when Enter is pressed — two gestures, not one gesture with a flag (unlike the
     * slider's {@code final}, which is the same drag reported twice).
     */
    interface Submit {
        /** The installed {@code :onSubmit} handler, or {@code null}. */
        LuaValue onSubmit();

        void onSubmit(LuaValue fn);
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
        return UiApi.attach(u, owner, new CtlButton(owner, CtlButton.DEF_W));
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
     * {@code :onSubmit(fn)} once, on Enter; {@code entry:text()} is retired, throwing and naming {@code :value()}.
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
     * (found the ordinary way, {@code hafen.ui():all("@Scrollbar")} or {@code sp:children()}) once content
     * taller than the box makes it live. The container itself has no verb of its own.
     */
    static LuaValue scroll(Addon owner, Varargs a) {
        if(Args.passed(a, 2))
            throw new LuaError("hafen.ui():scroll() takes no arguments — it is built bare and configured by"
                + " chained setters: hafen.ui():scroll():size(200, 160):position(x, y):parent(w)");
        UI u = UiApi.requireUi("scroll");
        return UiApi.attach(u, owner, new CScrollport(owner));
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
            UI u = AddonManager.ui;
            synchronized(u) { ((CtlButton)c).change(s); }
            return;
        }
        if(c instanceof CLabel) {
            UI u = AddonManager.ui;
            synchronized(u) { ((CLabel)c).settext(s); }   // resizes itself; see spec 040 risks/gotchas
            return;
        }
        if(c instanceof CCheck) {
            UI u = AddonManager.ui;
            synchronized(u) { ((CCheck)c).settext(s); }   // resizes itself, same as CLabel above
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

    /** {@code widget:onPress()} — the installed handler, or {@code nil} on anything that has nothing to press. */
    static LuaValue onPress(Owned c) {
        if(!(c instanceof Press))
            return LuaValue.NIL;
        LuaValue fn = ((Press)c).onPress();
        return (fn == null) ? LuaValue.NIL : fn;
    }

    /**
     * {@code widget:onPress(fn)} — <b>the button fired</b>, and it holds nothing. Distinct from
     * {@code :onClick(fn)} on purpose (spec 040 decision B): one is an intent, which the keyboard raises too,
     * the other is a mouse position. It is safe for the handler to destroy its own window — {@code Button}
     * releases its grab before it calls the activation, not after.
     */
    static void onPress(Owned c, Widget w, LuaValue fn) {
        if(c instanceof Press) {
            ((Press)c).onPress(fn);
            return;
        }
        throw new LuaError("widget:onPress(fn) is a BUTTON's activation, and hafen.ui():button() builds one — "
            + LuaWidget.typeName(w) + " has nothing to press. The raw mouse event on any widget you built is"
            + " widget:onClick(fn).");
    }

    // ------------------------------------------------------------------ the onChange verb (040.4)

    /** {@code widget:onChange()} — the installed handler, or {@code nil} on a control with no value at all. */
    static LuaValue onChange(Owned c) {
        if(!(c instanceof Change))
            return LuaValue.NIL;
        LuaValue fn = ((Change)c).onChange();
        return (fn == null) ? LuaValue.NIL : fn;
    }

    /**
     * {@code widget:onChange(fn)} — <b>the value CHANGED</b>, and only from a real interaction: a programmatic
     * {@code :value(v)} writes the implementation's field directly and never calls this (040.3/040.4's whole
     * value spine — see {@link Change}). Dispatches on the same capability {@code :value()} does, so a control
     * with no value refuses naming that fact rather than the verb.
     */
    static void onChange(Owned c, Widget w, LuaValue fn) {
        if(c instanceof Change) {
            ((Change)c).onChange(fn);
            return;
        }
        throw new LuaError("widget:onChange(fn) fires when a control's VALUE changes, and " + LuaWidget.typeName(w)
            + " holds nothing — widget:value() answers nil on it too. hafen.ui():check(), hafen.ui():radio(),"
            + " hafen.ui():slider(), hafen.ui():scrollbar() and hafen.ui():entry() are the builders that have"
            + " one, in this feature so far.");
    }

    // ------------------------------------------------------------------ the onSubmit verb (040.7)

    /** {@code widget:onSubmit()} — the installed handler, or {@code nil} on anything that has nothing to submit. */
    static LuaValue onSubmit(Owned c) {
        if(!(c instanceof Submit))
            return LuaValue.NIL;
        LuaValue fn = ((Submit)c).onSubmit();
        return (fn == null) ? LuaValue.NIL : fn;
    }

    /**
     * {@code widget:onSubmit(fn)} — the ENTRY's Enter, distinct from {@code :onChange(fn)} (every keystroke).
     * Dispatches on {@link Submit}, which only {@link CEntry} implements so far.
     */
    static void onSubmit(Owned c, Widget w, LuaValue fn) {
        if(c instanceof Submit) {
            ((Submit)c).onSubmit(fn);
            return;
        }
        throw new LuaError("widget:onSubmit(fn) fires when an ENTRY's Enter is pressed, and hafen.ui():entry()"
            + " is the builder that has one — " + LuaWidget.typeName(w) + " has nothing to submit.");
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
                + " widget:onDraw(fn).");
        if(!c.pending())
            throw new LuaError("widget:image(up, down[, hover]) chooses a button's FACE while the control is"
                + " being BUILT, and this one is already on screen — a face is not a property of a button, it IS"
                + " which button this is (the client draws a captioned one and a picture one with two different"
                + " widgets), so set it in the same statement that builds the control. A caption, unlike a face,"
                + " is live: widget:text(s) works at any time.");
        BufferedImage up = face(upv, "up"), down = face(downv, "down"), hover = face(hoverv, "hover");
        CtlIButton nu = new CtlIButton(owner, up, down, hover, upv, downv, hoverv);
        if(c instanceof Press)
            nu.onPress(((Press)c).onPress());     // a handler installed before the face outlives the rebuild
        UiApi.rebuild(owner, c, nu);
    }

    /**
     * {@code widget:image(up, down, hoverUp, hoverDown)} on a checkbox — the four-face completion to
     * {@link haven.ICheckBox} (task 040.4). Building-only like {@link #image}, and carries the checked state
     * and the {@code :onChange} handler across the rebuild exactly as {@link #image} carries a button's
     * {@code :onPress}.
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
            LuaValue v = ((Value)c).value();       // the checked state outlives the rebuild, like a button's onPress
            if((v != null) && !v.isnil())
                nu.value(v);
        }
        if(c instanceof Change)
            nu.onChange(((Change)c).onChange());   // a handler installed before the face outlives the rebuild too
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
                throw new LuaError("widget:image: the " + which + " face has been disposed — after a :dispose(),"
                    + " hafen.asset():get(path) loads the file again as a NEW asset");
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
                throw new LuaError("widget:image: the " + which + " face has been disposed — after a :dispose(),"
                    + " hafen.asset():get(path) loads the file again as a NEW asset");
            return li.tex;
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
    private static boolean fileish(String name) {
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
            return;
        }
        throw new LuaError("widget:value(v) writes what a control HOLDS, and " + LuaWidget.typeName(w)
            + " holds nothing — hafen.ui():progress(), hafen.ui():check(), hafen.ui():radio(),"
            + " hafen.ui():slider(), hafen.ui():scrollbar() and hafen.ui():entry() are the builders that do, in"
            + " this feature so far.");
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
    }

    /** {@code widget:source(h)}'s handle → a {@link Tex}. Same two doors {@link #face} resolves, minus the plural. */
    private static Tex sourceTex(LuaValue v) {
        LuaImage li = LuaImage.resolve(v);
        if(li != null) {
            if(li.dead)
                throw new LuaError("widget:source: this asset has been disposed — after a :dispose(),"
                    + " hafen.asset():get(path) loads the file again as a NEW asset");
            return li.tex;
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
            return;
        }
        throw new LuaError("widget:rows(t) sets a control's ROW SOURCE, and hafen.ui():radio() is the builder"
            + " that takes one, in this feature so far — " + LuaWidget.typeName(w) + " has no rows.");
    }

    // ------------------------------------------------------------------ the range verb (040.6)

    /** {@code widget:range()} — {@code {min=, max=}} as they stand, or {@code nil} on a control with no range. */
    static LuaValue range(Owned c) {
        if(!(c instanceof Range))
            return LuaValue.NIL;
        return ((Range)c).range();
    }

    /**
     * {@code widget:range(min, max)} — the value BOUNDS of a slider or scrollbar. Dispatches on {@link Range}
     * and hands both raw bounds to the implementation, which type-checks them and throws naming the rule
     * (040.6: {@link CSlider}/{@link CScrollbar} require {@code min <= max}, and re-clamp a value that no
     * longer fits WITHOUT firing {@code :onChange} — narrowing the range is not a user interaction).
     */
    static void range(Owned c, Widget w, Varargs a) {
        if(!(c instanceof Range))
            throw new LuaError("widget:range(min, max) sets a control's VALUE BOUNDS, and hafen.ui():slider()"
                + " and hafen.ui():scrollbar() are the builders that take one — " + LuaWidget.typeName(w)
                + " has no range.");
        LuaValue minv = Args.required(a, 2, "widget:range", "min");
        LuaValue maxv = Args.required(a, 3, "widget:range", "max");
        ((Range)c).range(minv, maxv);
    }
}
