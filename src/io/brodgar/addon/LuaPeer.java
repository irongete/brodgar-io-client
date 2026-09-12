package io.brodgar.addon;

import java.util.ArrayList;
import java.util.List;

import io.brodgar.voice.BrodgarVoice;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * A <b>Peer object</b> (143.2) — one player a voice link relates you to: someone you hear, someone who hears
 * you, or both. {@code voice:peer()} is the collection of them and {@code voice:peer():get(gob)} addresses
 * one <b>by Gob</b>, the only key a player has on this side of the wire; the three keys {@code PeerAdded},
 * {@code PeerRemoved} and {@code PeerChanged} hand one over.
 *
 * <p><b>It wraps the link and the gob id and nothing else.</b> Every read re-asks the engine under the link
 * ({@link LuaVoice#engine}), which holds the two sets and the speaking flag as lock-free volatiles: so
 * {@code exists()} is membership of the union, {@code audible()} and {@code hears()} are the two halves, and
 * {@code speaking()} is the mixer's own hangover flag. A link with no engine — before {@code Open}, from the
 * moment it began to end — answers {@code false} to all four, which is the same nothing a peer who left
 * answers. The two writes, {@code muted} and {@code volume}, are <b>this addon's own state</b>
 * ({@link LuaVoice#peerPrefs}): they read back from the record in every state of the link and every state
 * of the peer, and reach the mixer whenever there is one.
 *
 * <p><b>Interned per link and gob id</b> ({@link LuaVoice#peers}, weak values): a peer read through
 * {@code :get}, listed, or handed by a key is {@code ==} the same object as long as the addon holds it, so
 * {@code seen[peer] = true} works. Two links relating you to one player are two peers, because what is
 * asked — do I hear them <i>on this server</i> — is per link. The metatable is per addon
 * ({@link Addon#peerMeta}), built once (D-017).
 *
 * <p><b>{@code gob()} is the link's session's copy.</b> A gob id is the server's and names one object in
 * every session that has loaded it, and a link speaks for one character ({@link LuaVoice#sessionUser}), so
 * the Gob a peer hands back is minted through that login — the same handle {@code s:world():gob():get(id)}
 * answers there. On the login screen there is no session to read through, and it answers {@code nil}.
 */
final class LuaPeer {
    /** The link this peer is a peer on. */
    final LuaVoice link;
    /** The player's gob id — the key, and the whole of the identity. */
    final long id;

    private LuaPeer(LuaVoice link, long id) {
        this.link = link;
        this.id = id;
    }

    /** {@code tostring(peer)}: {@code Peer(<gob id>)}. */
    public String toString() {
        return "Peer(" + id + ")";
    }

    /** The interned Peer for {@code id} on {@code link} — the one way a Peer reaches Lua. */
    static LuaValue of(final LuaVoice link, final long id) {
        return link.peers.of(Long.valueOf(id), () -> LuaValue.userdataOf(new LuaPeer(link, id), meta(link.owner)));
    }

    /** The {@code LuaPeer} behind a Lua value, or {@code null} for anything else. */
    static LuaPeer resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaPeer) ? (LuaPeer)o : null;
    }

    /** The receiver of a colon call, or the error that says a dot call passed the wrong self. */
    private static LuaPeer self(LuaValue v, String method) {
        LuaPeer p = resolve(v);
        if(p == null)
            throw new LuaError("peer:" + method + "() — use a COLON call on a Peer object, what"
                + " voice:peer():get(gob), voice:peer():list()[i] and the Peer keys hand you (peer:" + method
                + "(…))");
        return p;
    }

    // ------------------------------------------------------------- the reads

    /** Do you hear this peer right now? */
    boolean audible() {
        BrodgarVoice v = link.engine();
        return (v != null) && v.audibleGobs().contains(Long.valueOf(id));
    }

    /** Does this peer hear you right now? */
    boolean hears() {
        BrodgarVoice v = link.engine();
        return (v != null) && v.heardByGobs().contains(Long.valueOf(id));
    }

    /** Is this peer's voice arriving right now? */
    boolean speaking() {
        BrodgarVoice v = link.engine();
        return (v != null) && v.isSpeaking(id);
    }

    /** What this addon set on the peer, or {@code false}. */
    boolean muted() {
        LuaVoice.PeerPrefs p = link.peerPrefs.get(Long.valueOf(id));
        return (p != null) && p.muted;
    }

    /** What this addon set on the peer, or {@code 1}. */
    double volume() {
        LuaVoice.PeerPrefs p = link.peerPrefs.get(Long.valueOf(id));
        return (p == null) ? 1 : p.volume;
    }

    /** The documented {@code Peer} snapshot: the id, the four live reads and the two settings. */
    LuaValue snapshot() {
        LuaTable t = new LuaTable();
        t.set("id", LuaValue.valueOf((double)id));
        boolean a = audible(), h = hears();
        t.set("exists", LuaValue.valueOf(a || h));
        t.set("audible", LuaValue.valueOf(a));
        t.set("hears", LuaValue.valueOf(h));
        t.set("speaking", LuaValue.valueOf(speaking()));
        t.set("muted", LuaValue.valueOf(muted()));
        t.set("volume", LuaValue.valueOf(volume()));
        return t;
    }

    // ------------------------------------------------------------- the collection

    /**
     * {@code voice:peer()} — the players this link relates you to, as a view read on every call:
     * {@code :get(gob)} <b>mints</b> a Peer for any Gob, so {@code :exists()} is the question rather than
     * {@code nil}; a key that is not a Gob is refused naming one. Nameless, so a string filter is refused.
     */
    static LuaValue collection(final LuaVoice link) {
        return LuaCollection.create("voice:peer()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(Long id : link.peerIds())
                    out.add(of(link, id));
                return out;
            }

            public int size() {
                return link.peerIds().size();
            }

            public boolean addressable() {
                return true;
            }

            public String keyName() {
                return "gob";
            }

            public LuaCollection.Missing missing() {
                return LuaCollection.Missing.MINT;
            }

            public LuaValue getMember(LuaValue key) {
                LuaGob g = LuaGob.resolve(key);
                if(g == null)
                    throw new LuaError("voice:peer():get(gob): gob must be a Gob object — what"
                        + " s:world():gob():get(id), :nearest(filter) and a GobAdded payload hand back, got "
                        + key.typename());
                return of(link, g.id);
            }
        }, null);
    }

    // ------------------------------------------------------------- the metatable

    /** The per-addon {@code Peer} metatable, built once — the vocabulary, closed. */
    private static LuaValue meta(final Addon owner) {
        if(owner.peerMeta != null)
            return owner.peerMeta;
        LuaTable m = new LuaTable();
        // gob() -- the player's object, read through the link's session; nil on the login screen.
        m.set("gob", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "peer:gob");
                LuaPeer p = self(a.arg1(), "gob");
                String user = p.link.sessionUser();
                return (user == null) ? LuaValue.NIL : LuaGob.of(owner, user, p.id);
            }
        });
        // id() -- the gob id, from the handle alone: it answers inside a PeerRemoved handler too.
        m.set("id", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "peer:id");
                return LuaValue.valueOf((double)self(a.arg1(), "id").id);
            }
        });
        // exists() -- is the server relating you to them right now, either way round?
        m.set("exists", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "peer:exists");
                LuaPeer p = self(a.arg1(), "exists");
                return LuaValue.valueOf(p.audible() || p.hears());
            }
        });
        m.set("audible", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "peer:audible");
                return LuaValue.valueOf(self(a.arg1(), "audible").audible());
            }
        });
        m.set("hears", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "peer:hears");
                return LuaValue.valueOf(self(a.arg1(), "hears").hears());
            }
        });
        m.set("speaking", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "peer:speaking");
                return LuaValue.valueOf(self(a.arg1(), "speaking").speaking());
            }
        });
        // muted() reads whether YOU have silenced them on this link, muted(b) writes it -- yours, kept
        // across their comings and goings, and legal in every state of the link.
        m.set("muted", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaPeer p = self(a.arg1(), "muted");
                Args.only(a, 1, "peer:muted");
                LuaValue v = Args.written(a, 2, "peer:muted", "on");
                if(v == null)
                    return LuaValue.valueOf(p.muted());
                p.link.prefs(p.id).muted = Args.bool(v, "peer:muted", "on", "whether their voice is discarded");
                p.link.applied(p.id);
                return a.arg1();
            }
        });
        // volume() reads the gain their voice is played at, volume(g) writes it: 0..4, 1 being as sent.
        m.set("volume", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaPeer p = self(a.arg1(), "volume");
                Args.only(a, 1, "peer:volume");
                LuaValue v = Args.written(a, 2, "peer:volume", "g");
                if(v == null)
                    return LuaValue.valueOf(p.volume());
                p.link.prefs(p.id).volume = VoiceApi.gain(v, "peer:volume", "g");
                p.link.applied(p.id);
                return a.arg1();
            }
        });
        // info() -- the one snapshot escape hatch.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "peer:info");
                return self(a.arg1(), "info").snapshot();
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("peer", m, "a player a voice link relates you to"));
        mt.set("__name", LuaValue.valueOf("Peer"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaPeer p = resolve(self);
                return LuaValue.valueOf((p == null) ? "Peer(?)" : p.toString());
            }
        });
        owner.peerMeta = mt;
        return mt;
    }
}
