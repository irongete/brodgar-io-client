/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.awt.event.KeyEvent;
import java.util.*;

public class KeyBinding {
    private static final Map<String, KeyBinding> bindings = new HashMap<>();
    // addon: which binding EXPLICITLY holds a given key+modifiers, by keyid(). Guarded by `bindings`, and the
    // whole of what makes the exclusivity in set() reversible: a claim is dropped the instant its holder is
    // re-keyed or unbound, and key() reads it live rather than anything having been written into a loser.
    private static final Map<Long, KeyBinding> claimed = new HashMap<>();
    public final String id;
    public final KeyMatch defkey;
    public final int modign;
    public KeyMatch key;

    static {
	repair();
    }

    private KeyBinding(String id, KeyMatch defkey, int modign) {
	this.id = id;
	this.defkey = defkey;
	this.modign = modign;
    }

    public void set(KeyMatch key) {
	// addon: keybinding exclusivity -- a physical key+modifier combo answers to ONE action (WoW-style).
	// Only an EXPLICIT assignment is ever cleared here. A binding still on its default is never written
	// to at all: it YIELDS the key for as long as the claim stands (key()) and takes it straight back
	// when the claim is dropped. Reverting-to-default (null) and disabling (nil) claim nothing.
	synchronized(bindings) {
	    unclaim();
	    if(claimable(key)) {
		KeyBinding held = claimed.get(keyid(key));
		if((held != null) && (held != this))
		    held.clear();
		claimed.put(keyid(key), this);
	    }
	    Utils.setpref("keybind/" + id, KeyMatch.reduce(key));
	    this.key = key;
	}
    }

    // addon: force this binding to "unbound" (nil), distinct from null which means "use the default".
    private void clear() {
	synchronized(bindings) {
	    unclaim();
	    Utils.setpref("keybind/" + id, KeyMatch.reduce(KeyMatch.nil));
	    this.key = KeyMatch.nil;
	}
    }

    // addon: drop this binding's claim on the key it holds, if it is in fact the holder.
    private void unclaim() {
	if(claimable(this.key) && (claimed.get(keyid(this.key)) == this))
	    claimed.remove(keyid(this.key));
    }

    // addon: is this a real key, one that can be claimed at all? Null ("use the default") is not, nil
    // ("unbound") is not, and neither is a match that normalizes to no keycode.
    private static boolean claimable(KeyMatch k) {
	return((k != null) && (k != KeyMatch.nil) && (keycode(k) != KeyEvent.VK_UNDEFINED));
    }

    // addon: the identity of a key+modifiers as one number. Normalizes a char-based match (forchar) and a
    // code-based one (forcode/forevent) to one keycode, so "Ctrl+G" as a letter and as a VK code are one key.
    private static long keyid(KeyMatch k) {
	return(((long)keycode(k) << 8) | (k.modmatch & 0xff));
    }

    private static int keycode(KeyMatch k) {
	if(k.code != KeyEvent.VK_UNDEFINED)
	    return(k.code);
	if(k.chr != 0)
	    return(KeyEvent.getExtendedKeyCodeForChar(k.chr));
	return(KeyEvent.VK_UNDEFINED);
    }

    public boolean set() {
	return(key != null);
    }

    public KeyMatch key() {
	if(key != null)
	    return(key);
	// addon: a binding still on its DEFAULT yields that key while another binding explicitly holds it.
	// Read live and never persisted, so clearing the holder hands this one its default straight back --
	// which is the point: the menu hotkeys (`scm/<res>`) no panel lists are all defaults, and a loss
	// written into their own pref was a loss with no way back.
	if(claimable(defkey)) {
	    synchronized(bindings) {
		KeyBinding held = claimed.get(keyid(defkey));
		if((held != null) && (held != this))
		    return(KeyMatch.nil);
	    }
	}
	return(defkey);
    }

    public static KeyBinding get(String id, KeyMatch defkey, int modign) {
	if(defkey == null)
	    throw(new NullPointerException());
	synchronized(bindings) {
	    KeyBinding ret = bindings.get(id);
	    if(ret == null) {
		KeyMatch set = KeyMatch.restore(Utils.getpref("keybind/" + id, ""));
		bindings.put(id, ret = new KeyBinding(id, defkey, modign));
		ret.key = set;
		if(claimable(set))   // addon: a stored assignment claims its key as the binding loads
		    claimed.put(keyid(set), ret);
	    }
	    return(ret);
	}
    }

    public static KeyBinding get(String id, KeyMatch defkey) {
	return(get(id, defkey,0));
    }

    public static KeyBinding get(String id) {
	synchronized(bindings) {
	    return(bindings.get(id));
	}
    }

    // addon: a snapshot of the whole registry, for hafen.client:options():keybindings():list(). The map is
    // private and lazily populated (a binding exists only once its owning class has been loaded), so a copy
    // taken under the monitor is the only safe way to enumerate what is currently bound.
    public static Collection<KeyBinding> all() {
	synchronized(bindings) {
	    return(new ArrayList<>(bindings.values()));
	}
    }

    // addon: one-time repair of what an EARLIER, destructive form of the rule in set() left behind. It wrote
    // "unbound" into the loser's own pref, so a menu hotkey -- `scm/<res>`, which no panel lists, and which
    // is a default rather than an assignment -- lost its letter to an Options binding for good, and clearing
    // that binding again did not bring it back. Those prefs are dropped once: every menu action nothing has
    // deliberately re-keyed goes back to the hotkey its own resource names. Assignments are left alone.
    private static void repair() {
	if(Utils.getprefb("keybind-repair/menu-hotkeys", false))
	    return;
	try {
	    java.util.prefs.Preferences prefs = Utils.prefs();
	    for(String nm : prefs.keys()) {
		if(nm.startsWith("keybind/scm/") && "n".equals(prefs.get(nm, null)))
		    prefs.remove(nm);
	    }
	} catch(Exception e) {
	    /* A prefs backend that cannot enumerate keeps what it has; nothing here is worth failing over. */
	}
	Utils.setprefb("keybind-repair/menu-hotkeys", true);
    }

    public static interface Bindable {
	public KeyBinding getbinding(Coord cc);

	public static class BindingQuery extends Widget.QueryEvent<KeyBinding> {
	    public BindingQuery(Coord c) {super(c);}
	    public BindingQuery(BindingQuery from, Coord c) {super(from, c);}
	    public BindingQuery derive(Coord c) {return(new BindingQuery(this, c));}

	    protected boolean shandle(Widget w) {
		if(w instanceof Bindable) {
		    KeyBinding ret = ((Bindable)w).getbinding(c);
		    if(ret != null)
			return(set(ret));
		}
		if(w.kb_gkey != null)
		    return(set(w.kb_gkey));
		return(super.shandle(w));
	    }
	}

	public static KeyBinding getbinding(Widget wdg, Coord c) {
	    return(wdg.ui.dispatchq(wdg, new BindingQuery(c)).ret);
	}
    }
}
