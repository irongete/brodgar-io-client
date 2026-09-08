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
    // addon: the exclusivity-aware form of whichever of `key`/`defkey` key() is handing out, and the one it
    // was built from. Cached because OptWnd.SetButton.draw() compares key() by IDENTITY: a fresh wrapper on
    // every call would re-label the button every frame. Guarded by `bindings`, like every other claim state.
    private KeyMatch awarefor, aware;

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

    // addon: TAKE the claim on this binding's own assignment, for a binding whose holder has just come (back)
    // to life -- an addon declaring its hotkey. Every other claim is taken by set() (the user assigning a
    // key) or by get() (restoring one as the binding loads); this one is for a binding that already exists,
    // still carries the user's assignment, and let the claim go when its handler went away (release()).
    //
    // It never steals. The key may have been assigned elsewhere while this one had no handler, and that
    // later choice is the user's. Arriving late and losing IS losing, so the assignment is cleared -- the
    // same thing set() does to the binding it takes a key off -- and the panel goes on telling the truth:
    // an assignment it shows is an assignment that fires.
    public void hold() {
	synchronized(bindings) {
	    if(!claimable(this.key))
		return;
	    KeyBinding held = claimed.get(keyid(this.key));
	    if(held == this)
		return;
	    if(held != null)
		clear();
	    else
		claimed.put(keyid(this.key), this);
	}
    }

    // addon: DROP this binding's claim while KEEPING the user's assignment -- for a holder that is going
    // away: an addon disabled, its hotkey ended, the watchdog killing it. The key answers to whoever else
    // wants it (a menu hotkey takes its own default straight back), the assignment stays in the prefs and in
    // the panel, and hold() takes the key back the moment a handler for it exists again. A reload is a
    // release and a hold with no input in between, so it is invisible; a disable is a release with no hold.
    public void release() {
	synchronized(bindings) {
	    unclaim();
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

    // addon: the same identity for a key as it was actually PRESSED, which is what exclusivity has to be
    // measured against: a match may IGNORE a modifier the event carries, and so answer to a combo it never
    // claimed. `modign` is the CALLER's declaration that some modifiers are no part of this key's identity
    // (Fightsess passes MODS to ask "was this very key released?"), so those bits are dropped here too --
    // a binding's own modmask is not, since ignoring Shift is exactly the case this exists for. Answers -1
    // where the event names no key at all, which nothing can claim and nothing should yield to.
    private static long keyid(KeyEvent ev, int modign) {
	int code = ev.getExtendedKeyCode();
	if(code == KeyEvent.VK_UNDEFINED) {
	    char c = ev.getKeyChar();
	    if((c == 0) || (c == KeyEvent.CHAR_UNDEFINED))
		return(-1);
	    code = KeyEvent.getExtendedKeyCodeForChar(Character.toUpperCase(c));
	}
	if(code == KeyEvent.VK_UNDEFINED)
	    return(-1);
	return(((long)code << 8) | (UI.modflags(ev) & KeyMatch.MODS & ~modign));
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

    // addon: a binding's key, carrying the exclusivity into the MATCH. key() below yields a default whose
    // EXACT key+modifiers another binding explicitly holds; this is the other half of the same rule -- a
    // match that IGNORES a modifier the event carries, and so answers to a combo it never claimed and could
    // not have been made to yield. The action menu's hotkeys are precisely that: PagButton.hotkey() is
    // forchar(hk, MODS & ~S, 0), reading Shift as "keep the menu open", so plain "B" also matched Shift+B
    // and ate it -- MenuGrid sits above the addon root in the globtype walk -- before whoever had been
    // assigned Shift+B was ever offered it.
    //
    // The test is on the EVENT's own key+modifiers, so nothing coarse happens: plain B still opens Build,
    // Shift+B goes to its holder, and dropping the claim -- unbinding or re-keying the holder -- hands
    // Shift+B back to the menu on the very next press. Only ANOTHER binding's claim yields; the holder
    // matches its own key as it always did.
    private static class Yielding extends KeyMatch {
	private final KeyBinding owner;

	Yielding(KeyBinding owner, KeyMatch from) {
	    super(from.chr, from.casematch, from.code, from.extmatch, from.keyname, from.modmask, from.modmatch);
	    this.owner = owner;
	}

	public boolean match(KeyEvent ev, int modign) {
	    if(!super.match(ev, modign))
		return(false);
	    long id = keyid(ev, modign);
	    if(id < 0)
		return(true);
	    synchronized(bindings) {
		KeyBinding held = claimed.get(id);
		return((held == null) || (held == owner));
	    }
	}
    }

    // addon: `k` as a Yielding, one instance per (binding, k). Never wraps null or nil: LuaBinding and
    // KeyHeld read "no key" by identity against KeyMatch.nil, and neither can match anything anyway.
    private KeyMatch aware(KeyMatch k) {
	if(!claimable(k))
	    return(k);
	synchronized(bindings) {
	    if(awarefor != k) {
		aware = new Yielding(this, k);
		awarefor = k;
	    }
	    return(aware);
	}
    }

    public KeyMatch key() {
	if(key != null)
	    return(aware(key));
	// addon: a binding still on its DEFAULT yields that key while another binding explicitly holds it.
	// Read live and never persisted, so clearing the holder hands this one its default straight back --
	// which is the point: the menu hotkeys (`scm/<res>`) no panel lists are all defaults, and a loss
	// written into their own pref was a loss with no way back. This is the EXACT overlap; Yielding above
	// is the partial one, where the default goes on matching every combo nothing else has claimed.
	if(claimable(defkey)) {
	    synchronized(bindings) {
		KeyBinding held = claimed.get(keyid(defkey));
		if((held != null) && (held != this))
		    return(KeyMatch.nil);
	    }
	}
	return(aware(defkey));
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

    // addon: DROP a binding from the registry entirely -- the counterpart get() never had, for a binding
    // whose holder is not merely going quiet but ceasing to exist. `bindings` is a process-wide map with no
    // removal, so every id ever minted stayed in it: a custom action-menu entry mints "scm/addon/<addon>/<id>"
    // the first time its button is drawn, and disabling the addon, removing the entry or reloading the layer
    // left that id -- and the key the player had assigned to it -- answering a button that is in no grid.
    //
    // It drops the CLAIM as release() does, so the key goes back to whoever else wants it, and it drops the
    // stored assignment with the entry: the id is gone, so an assignment against it is a preference for
    // something that does not exist, and leaving it would hand the key straight back to a re-minted binding
    // the player never assigned it to. Unknown ids are inert. Nothing here touches a binding the client
    // itself declares -- those are static fields of live classes and are never unregistered.
    public static void unregister(String id) {
	if(id == null)
	    return;
	synchronized(bindings) {
	    KeyBinding kb = bindings.remove(id);
	    if(kb == null)
		return;
	    kb.unclaim();
	    Utils.setpref("keybind/" + id, "");
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
