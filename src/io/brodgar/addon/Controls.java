package io.brodgar.addon;

import haven.UI;
import haven.Widget;
import haven.Window;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

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
        throw new LuaError("widget:text(s) writes the caption of a CONTROL you built, and hafen.ui():button()"
            + " is the builder that takes one — " + LuaWidget.typeName(w) + " has no caption to write"
            + ((w instanceof Window) ? "; a window's caption is widget:title(s)." : "."));
    }

    /** {@code widget:onPress()} — the installed handler, or {@code nil} on anything that has nothing to press. */
    static LuaValue onPress(Owned c) {
        if(!(c instanceof CtlButton))
            return LuaValue.NIL;
        LuaValue fn = ((CtlButton)c).onPress();
        return (fn == null) ? LuaValue.NIL : fn;
    }

    /**
     * {@code widget:onPress(fn)} — <b>the button fired</b>, and it holds nothing. Distinct from
     * {@code :onClick(fn)} on purpose (spec 040 decision B): one is an intent, which the keyboard raises too,
     * the other is a mouse position. It is safe for the handler to destroy its own window — {@code Button}
     * releases its grab before it calls the activation, not after.
     */
    static void onPress(Owned c, Widget w, LuaValue fn) {
        if(c instanceof CtlButton) {
            ((CtlButton)c).onPress(fn);
            return;
        }
        throw new LuaError("widget:onPress(fn) is a BUTTON's activation, and hafen.ui():button() builds one — "
            + LuaWidget.typeName(w) + " has nothing to press. The raw mouse event on any widget you built is"
            + " widget:onClick(fn).");
    }
}
