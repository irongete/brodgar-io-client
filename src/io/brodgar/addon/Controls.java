package io.brodgar.addon;

import haven.Resource;
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
        if(c instanceof CtlIButton)
            throw new LuaError("widget:text(s) writes a control's CAPTION, and this button's face is a PICTURE —"
                + " an image button shows the faces widget:image(up, down[, hover]) gave it and has no caption."
                + " A captioned button is the same builder completed the other way: hafen.ui():button():text(\""
                + s + "\").");
        throw new LuaError("widget:text(s) writes the caption of a CONTROL you built, and hafen.ui():button()"
            + " is the builder that takes one — " + LuaWidget.typeName(w) + " has no caption to write"
            + ((w instanceof Window) ? "; a window's caption is widget:title(s)." : "."));
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

    // ------------------------------------------------------------------ the face setter (040.2)

    /** {@code widget:image()} — the faces this control was given, or {@code nil} where a control has none. */
    static LuaValue faces(Owned c) {
        return (c instanceof CtlIButton) ? ((CtlIButton)c).faces() : LuaValue.NIL;
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
     */
    static void image(Addon owner, Widget w, Owned c, Varargs a) {
        LuaValue upv = Args.required(a, 2, "widget:image", "up");
        LuaValue downv = Args.required(a, 3, "widget:image", "down");
        LuaValue hoverv = Args.passed(a, 4) ? Args.required(a, 4, "widget:image", "hover") : upv;
        if(Args.passed(a, 5))
            throw new LuaError("widget:image(up, down[, hover]) takes TWO or THREE faces — the released one, the"
                + " pressed one, and (optionally) the one under the cursor, which defaults to the released face.");
        if(!(c instanceof CtlButton) && !(c instanceof CtlIButton))
            throw new LuaError("widget:image(up, down[, hover]) sets the FACE of a control you built, and"
                + " hafen.ui():button() is the builder that takes one — " + LuaWidget.typeName(w) + " has no face"
                + " to set. A surface you paint yourself draws its own pictures with g:image inside"
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

    /** Does this string name a FILE rather than a client resource? (Resource names carry no extension.) */
    private static boolean fileish(String name) {
        String n = name.toLowerCase();
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".gif")
            || n.endsWith(".bmp");
    }
}
