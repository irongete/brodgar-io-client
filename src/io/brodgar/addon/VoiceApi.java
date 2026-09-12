package io.brodgar.addon;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import haven.Coord2d;
import haven.MapView;

import io.brodgar.session.Sessions;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

/**
 * The {@code hafen.voice} section (143.1) — {@link WebSocketApi}'s shape a second time, with the voice engine
 * where the JDK socket was: the collection of an addon's live voice links, {@code :connection(url)} as its
 * one extra verb, a {@code Voice} handle built bare and dispatched by {@code :connect()}, and
 * {@code :on(key, fn)} as the one door its edges come through. The record and the host are
 * {@link LuaVoice}; the payloads are {@link LuaVoiceEvent}; the microphone is {@link SharedMic}; this class
 * owns the vocabulary, the gate, the drain and the teardown. The mic, the mix and the peers (143.2) are
 * verbs here over fields on the record, and {@link LuaPeer} is the one entity under a link.
 *
 * <p><b>The gate is {@link HttpApi#requireNetwork}</b>, under {@link Permission#VOICE_CONNECT} with the
 * {@code https} origin the address names ({@link WebSocketApi#wssOrigin}): the allowlist names servers, so a
 * voice server is declared as a request's host is, and the consent line carries the list. It runs in
 * {@code :connect()} — the one call that reaches the network and the microphone — and first (D-213). Two
 * more refusals follow it, both about the protocol rather than the user: a <b>second live link to a server
 * any addon already speaks to</b>, naming that addon, because two sessions from one client silence each
 * other on the server; and a <b>fifth live link in the client</b>, since every one is an audio pipeline of
 * its own.
 *
 * <p><b>Delivery is the layer's step</b> ({@link #drain}, from {@code AddonManager.layerStep} beside the
 * websocket drain): every addon's links, every queued edge, fired through the link's own {@link Subs} under
 * {@code enterLua} exactly as a timer is — and, in the same walk, each open link's spatial vectors for the
 * frame ({@link LuaVoice#spatialize}), which is what the voice feature's per-frame tick in the map view used
 * to be. Not a session's tick, on purpose: a link's subject is a server, and it follows whichever character
 * is drawn rather than dying with one.
 *
 * <p>All members static; not instantiable.
 */
final class VoiceApi {
    private VoiceApi() {}

    /**
     * Build the {@code hafen.voice} section for {@code owner} — <b>the collection of its live links</b>, with
     * {@code :connection(url)} as its {@code extra} verb. Called from {@code installHafen}, beside
     * {@link WebSocketApi#install}.
     *
     * <p>A link is in the set from {@code :connect()} until its {@code Close} or {@code Error} has been
     * delivered — the set the client-wide cap counts, summed over every addon, and the one a second link to
     * the same server is refused against. One built and never connected, or closed before it was, is in no
     * collection and holds no slot.
     */
    static void install(LuaTable hafen, final Addon owner) {
        LuaTable extra = new LuaTable();
        // connection(url) -- a BARE link. Nothing is opened, so every setter is legal until :connect(), and
        // the URL is checked here, where it was written: a typo is a refusal at this call rather than an
        // Error a frame later. The permission is not asked here -- nothing has left.
        extra.set("connection", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaCollection.receiver(a.arg1(), "hafen.voice()", "connection");
                Args.only(a, 1, "hafen.voice():connection");
                String url = Args.str(a, 2, "hafen.voice():connection", "url", "a wss:// address").tojstring();
                return newLink(owner, url, "hafen.voice():connection");
            }
        });
        LuaValue coll = LuaCollection.create("hafen.voice()", new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(LuaVoice c : owner.voices) {
                    if(!c.done && (c.handle != null))
                        out.add(c.handle);
                }
                return out;
            }

            /** A link has an address, so a string filter is a substring test over it. */
            public String needle(LuaValue member) {
                LuaVoice c = resolve(member);
                return (c == null) ? "" : c.url;
            }

            public boolean named() {
                return true;
            }

            /** A link has no key: it is the handle :connection(url) handed you. */
            public String noGet() {
                return "a voice link has no key of its own -- it is the handle :connection(url) handed you."
                    + " hafen.voice():find(\"<url substring>\") searches, and :list() is all of them";
            }
        }, extra);
        Section.mount(hafen, "voice", coll, null);
    }

    /** The link behind a handle, or {@code null} — the userdata is the record itself. */
    static LuaVoice resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaVoice) ? (LuaVoice)o : null;
    }

    /** The receiver of a colon call on a link, or the error that says a dot call passed the wrong self. */
    private static LuaVoice self(LuaValue v, String method) {
        LuaVoice c = resolve(v);
        if(c == null)
            throw new LuaError("voice:" + method + "() — use a COLON call on the link"
                + " hafen.voice():connection(url) handed you (voice:" + method + "(…))");
        return c;
    }

    /**
     * Build a link — <b>bare</b>. Returns the Lua handle: {@code :url()} {@code :state()} {@code :id()}
     * {@code :session([s])} {@code :timeout([ms])} {@code :spatial([b])} {@code :bitrate([n])}
     * {@code :on(key, fn)} {@code :connect()} and {@code :close()}. Nothing is registered or scheduled here:
     * the caps are charged by {@code :connect()}, because a link that never opens reaches no server and
     * should hold no slot against a cap that counts what is live.
     */
    private static LuaValue newLink(Addon owner, String url, String verb) {
        String origin = WebSocketApi.wssOrigin(url, verb);
        LuaVoice c = new LuaVoice(owner, url, URI.create(url), origin);
        LuaValue h = LuaValue.userdataOf(c, meta(owner));
        c.handle = h;
        return h;
    }

    /** A connect-time setter refuses once the link has been DISPATCHED: the engine has it, so writing the field would lie. */
    private static void requireNew(LuaVoice c, String verb) {
        if(c.dispatched)
            throw new LuaError("voice:" + verb + ": this link has already been dispatched by :connect() --"
                + " configure it before :connect(), which is the whole of the rule");
        if(c.state == LuaVoice.State.CLOSED)
            throw new LuaError("voice:" + verb + ": this link was closed by :close() before it was opened --"
                + " build another with hafen.voice():connection(url)");
    }

    /** The per-addon {@code Voice} metatable, built once (D-017) — the vocabulary, closed. */
    private static LuaValue meta(final Addon owner) {
        if(owner.voiceMeta != null)
            return owner.voiceMeta;
        LuaTable m = new LuaTable();
        // url() / state() -- what the link IS and where it is: the filter matches on the first, and the
        // second is the one word every other verb's legality follows from.
        m.set("url", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "voice:url");
                return LuaValue.valueOf(self(a.arg1(), "url").url);
            }
        });
        m.set("state", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "voice:state");
                return LuaValue.valueOf(self(a.arg1(), "state").state.word);
            }
        });
        // id() -- the session id the server gave this link: a number from Open on, nil before and after.
        m.set("id", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "voice:id");
                long id = self(a.arg1(), "id").id();
                return (id < 0) ? LuaValue.NIL : LuaValue.valueOf(id);
            }
        });
        // session() reads the Session this link speaks for -- the one on screen unless one is pinned, so it
        // answers nil on the login screen; session(s) pins one, and session(nil) is the write that answers
        // the read's own default: the explicit nil has a meaning here, as hafen.session():current(nil) has.
        // Legal in every state, because whose world the link reports can change while it is open.
        m.set("session", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "session");
                Args.only(a, 1, "voice:session");
                if(!Args.passed(a, 2)) {
                    String user = c.session;
                    if(user == null) {
                        Sessions.Member mem = Sessions.anchormember();
                        return (mem == null) ? LuaValue.NIL : LuaSession.of(owner, mem.user);
                    }
                    return LuaSession.of(owner, user);
                }
                LuaValue want = a.arg(2);
                if(want.isnil()) {
                    c.session = null;
                    return a.arg1();
                }
                LuaSession h = LuaSession.resolve(want);
                if(h == null)
                    throw new LuaError("voice:session(s): s must be a Session object -- what"
                        + " hafen.session():current(), :get(user), :find(filter) and :list() hand back -- or"
                        + " nil to follow the screen again, got " + want.typename());
                c.session = h.user;
                return a.arg1();
            }
        });
        // timeout() reads the milliseconds the handshake may take, timeout(ms) writes it and returns SELF.
        // The range is REFUSED, never clamped: a deadline silently rewritten is a wait nobody wrote.
        m.set("timeout", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "timeout");
                Args.only(a, 1, "voice:timeout");
                LuaValue ms = Args.written(a, 2, "voice:timeout", "ms");
                if(ms == null)
                    return LuaValue.valueOf(c.timeout);
                requireNew(c, "timeout");
                c.timeout = (int)Args.integer(ms, "voice:timeout", "ms", "milliseconds", 1, LuaHttp.MAX_TIMEOUT);
                return a.arg1();
            }
        });
        // spatial() reads whether the mix pans by position, spatial(b) writes it -- true unless told
        // otherwise, and connect-time: the engine's mixer is built with it.
        m.set("spatial", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "spatial");
                Args.only(a, 1, "voice:spatial");
                LuaValue v = Args.written(a, 2, "voice:spatial", "on");
                if(v == null)
                    return LuaValue.valueOf(c.spatial);
                requireNew(c, "spatial");
                c.spatial = Args.bool(v, "voice:spatial", "on", "whether voices pan and fade by position");
                return a.arg1();
            }
        });
        // bitrate() reads the Opus bitrate in bits per second, bitrate(n) writes it -- a whole number in the
        // codec's own range, refused outside it, and connect-time: the encoder is built with it.
        m.set("bitrate", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "bitrate");
                Args.only(a, 1, "voice:bitrate");
                LuaValue v = Args.written(a, 2, "voice:bitrate", "n");
                if(v == null)
                    return LuaValue.valueOf(c.bitrate);
                requireNew(c, "bitrate");
                c.bitrate = (int)Args.integer(v, "voice:bitrate", "n", "bits per second", LuaVoice.MIN_BITRATE,
                                              LuaVoice.MAX_BITRATE);
                return a.arg1();
            }
        });
        // The mic and the mix (143.2): seven DESIRED settings, each read from the record and written to it in
        // EVERY state -- a link is configured before it opens and adjusted while it is -- and written
        // through to the engine whenever there is one (LuaVoice.applied). Arity is the verb: the bare name
        // reads, one argument writes and hands the link back.
        m.set("transmitting", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "transmitting");
                Args.only(a, 1, "voice:transmitting");
                LuaValue v = Args.written(a, 2, "voice:transmitting", "on");
                if(v == null)
                    return LuaValue.valueOf(c.transmitting);
                c.transmitting = Args.bool(v, "voice:transmitting", "on", "whether the microphone goes out");
                c.applied();
                return a.arg1();
            }
        });
        m.set("vad", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "vad");
                Args.only(a, 1, "voice:vad");
                LuaValue v = Args.written(a, 2, "voice:vad", "on");
                if(v == null)
                    return LuaValue.valueOf(c.vad);
                c.vad = Args.bool(v, "voice:vad", "on", "whether silence is held back while transmitting");
                c.applied();
                return a.arg1();
            }
        });
        m.set("threshold", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "threshold");
                Args.only(a, 1, "voice:threshold");
                LuaValue v = Args.written(a, 2, "voice:threshold", "rms");
                if(v == null)
                    return LuaValue.valueOf(c.threshold);
                c.threshold = ranged(v, "voice:threshold", "rms", "the loudness that counts as speech, on the"
                                     + " 16-bit sample scale", LuaVoice.MIN_THRESHOLD, LuaVoice.MAX_THRESHOLD);
                c.applied();
                return a.arg1();
            }
        });
        m.set("agc", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "agc");
                Args.only(a, 1, "voice:agc");
                LuaValue v = Args.written(a, 2, "voice:agc", "on");
                if(v == null)
                    return LuaValue.valueOf(c.agc);
                c.agc = Args.bool(v, "voice:agc", "on", "whether your loudness is evened out before it goes");
                c.applied();
                return a.arg1();
            }
        });
        m.set("muted", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "muted");
                Args.only(a, 1, "voice:muted");
                LuaValue v = Args.written(a, 2, "voice:muted", "on");
                if(v == null)
                    return LuaValue.valueOf(c.muted);
                c.muted = Args.bool(v, "voice:muted", "on", "whether the microphone is held shut whatever"
                                    + " transmitting says");
                c.applied();
                return a.arg1();
            }
        });
        m.set("deafened", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "deafened");
                Args.only(a, 1, "voice:deafened");
                LuaValue v = Args.written(a, 2, "voice:deafened", "on");
                if(v == null)
                    return LuaValue.valueOf(c.deafened);
                c.deafened = Args.bool(v, "voice:deafened", "on", "whether every voice is silenced");
                c.applied();
                return a.arg1();
            }
        });
        m.set("volume", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "volume");
                Args.only(a, 1, "voice:volume");
                LuaValue v = Args.written(a, 2, "voice:volume", "g");
                if(v == null)
                    return LuaValue.valueOf(c.volume);
                c.volume = gain(v, "voice:volume", "g");
                c.applied();
                return a.arg1();
            }
        });
        // speaking() -- is the character's voice going out right now: past the gate, the mute and the
        // detector. false with no engine, which is every state but open.
        m.set("speaking", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "voice:speaking");
                return LuaValue.valueOf(self(a.arg1(), "speaking").speaking());
            }
        });
        // info() -- the one SNAPSHOT: the seven settings and, from the engine, the id, the relay round trip
        // and the four counters.
        m.set("info", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "voice:info");
                return snapshot(self(a.arg1(), "info"));
            }
        });
        // peer() -- the players this link relates you to, as a collection addressed by Gob (LuaPeer).
        m.set("peer", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                Args.only(a, 0, "voice:peer");
                return LuaPeer.collection(self(a.arg1(), "peer"));
            }
        });
        // on(key, fn) -- THE HANDLER, the API's one notification verb, over a CLOSED set of keys: an unknown
        // one raises naming them rather than being accepted and never firing (D-129). Legal after :connect()
        // too -- a link in flight has not ended yet -- and it hands back a Sub, ended with sub:off().
        m.set("on", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "on");
                Args.only(a, 2, "voice:on");
                String key = Args.str(a, 2, "voice:on", "key", "one of " + keys()).tojstring();
                LuaValue fnArg = Args.required(a, 3, "voice:on", "fn");
                if(!fnArg.isfunction())
                    throw new LuaError("voice:on(key, fn): fn must be a function, got " + fnArg.typename());
                String moved = Refusal.eventKey("voice", key);
                if(moved != null)
                    throw new LuaError(moved);
                if(!isKey(key))
                    throw new LuaError("voice:on(key, fn): a voice link has no event '" + key + "' -- it has: "
                        + keys() + ". Open hands the link; Close and Error hand an ev answering"
                        + " ev:connection(); the Peer keys hand the Peer");
                return c.subs.on(key, fnArg);
            }
        });
        // connect() -- DISPATCH, said out loud, and the GATE, which is the call it belongs to (D-213 for a
        // builder): nothing has left the client until here, and the microphone is not open until here. The
        // key before the allowlist, as 093 wrote it; then the builder's own rule; then the two protocol
        // refusals, charged here and not at construction.
        m.set("connect", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "connect");
                Args.only(a, 0, "voice:connect");
                HttpApi.requireNetwork(c.owner, Permission.VOICE_CONNECT, c.origin, "voice:connect");
                if(c.dispatched)
                    throw new LuaError("voice:connect(): this link has already been dispatched by :connect()"
                        + " -- build another with hafen.voice():connection(url)");
                if(c.state == LuaVoice.State.CLOSED)
                    throw new LuaError("voice:connect(): this link was closed by :close() before it was"
                        + " opened -- build another with hafen.voice():connection(url)");
                Addon holder = holderOf(c.origin);
                if(holder != null)
                    throw new LuaError("voice:connect(): " + AddonManager.ownerName(holder)
                        + " already holds a live link to " + c.origin + ((holder == c.owner)
                            ? " -- this addon's own: one link per server, because a second session from the"
                              + " same client would silence both. hafen.voice():find(\"" + c.uri.getHost()
                              + "\") is the one that is live; close it, or wait for its Close"
                            : " -- one link per server for the whole client, because a second session from"
                              + " the same client would silence both"));
                int live = liveCount();
                if(live >= LuaVoice.MAX_LIVE)
                    throw new LuaError("voice:connect(): the client already holds " + live + " live voice"
                        + " links, which is the cap for every addon together; hafen.voice():count() is how"
                        + " many are this addon's -- close one, or wait for one to end.");
                c.owner.voices.add(c);
                c.dispatch();
                return a.arg1();
            }
        });
        // close() -- the ending, and the only verb legal in every state. Idempotent: a second close answers
        // the link and changes nothing.
        m.set("close", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaVoice c = self(a.arg1(), "close");
                Args.only(a, 0, "voice:close");
                close(c);
                return a.arg1();
            }
        });
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("voice", m,
            "a voice link",
            "it is dispatched by :connect(): timeout, spatial and bitrate are legal until then and none"
            + " after; session, the mic and the mix are legal in every state"));
        mt.set("__name", LuaValue.valueOf("Voice"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaVoice c = resolve(self);
                return LuaValue.valueOf((c == null) ? "Voice(?)" : c.toString());
            }
        });
        owner.voiceMeta = mt;
        return mt;
    }

    /** A gain argument — the link's {@code volume} and a peer's: a number in {@code 0..4}, refused outside it. */
    static double gain(LuaValue v, String verb, String param) {
        return ranged(v, verb, param, "a gain, 1 being as sent", LuaVoice.MIN_VOLUME, LuaVoice.MAX_VOLUME);
    }

    /** A finite number in {@code lo..hi}, <b>refused</b> outside it naming the range — never clamped. */
    private static double ranged(LuaValue v, String verb, String param, String hint, double lo, double hi) {
        double d = Args.num(v, verb, param, hint).todouble();
        if((d < lo) || (d > hi))
            throw new LuaError(verb + ": " + param + " must be a number from " + show(lo) + " to " + show(hi)
                + " (" + hint + "), got " + show(d));
        return d;
    }

    /** A number as a refusal prints it: whole without a decimal point. */
    private static String show(double d) {
        return (d == Math.rint(d)) ? Long.toString((long)d) : Double.toString(d);
    }

    /**
     * {@code voice:info()} — the settings as set, then what the engine knows: {@code id} once there is one,
     * {@code rtt} in milliseconds once the relay has echoed a ping, and the four counters, {@code 0} with no
     * engine. Optional fields are absent rather than a sentinel, the snapshot rule.
     */
    private static LuaValue snapshot(LuaVoice c) {
        LuaTable t = new LuaTable();
        t.set("url", LuaValue.valueOf(c.url));
        t.set("state", LuaValue.valueOf(c.state.word));
        t.set("transmitting", LuaValue.valueOf(c.transmitting));
        t.set("vad", LuaValue.valueOf(c.vad));
        t.set("threshold", LuaValue.valueOf(c.threshold));
        t.set("agc", LuaValue.valueOf(c.agc));
        t.set("muted", LuaValue.valueOf(c.muted));
        t.set("deafened", LuaValue.valueOf(c.deafened));
        t.set("volume", LuaValue.valueOf(c.volume));
        t.set("speaking", LuaValue.valueOf(c.speaking()));
        io.brodgar.voice.BrodgarVoice v = c.engine();
        long id = (v == null) ? -1 : v.sessionId();
        if(id >= 0)
            t.set("id", LuaValue.valueOf((double)id));
        long rtt = (v == null) ? -1 : v.udpRttNanos();
        if(rtt >= 0)
            t.set("rtt", LuaValue.valueOf(rtt / 1e6));
        t.set("sent", LuaValue.valueOf((double)((v == null) ? 0 : v.framesSent())));
        t.set("received", LuaValue.valueOf((double)((v == null) ? 0 : v.udpAudioPacketsReceived())));
        t.set("mixed", LuaValue.valueOf((double)((v == null) ? 0 : v.framesMixed())));
        t.set("streams", LuaValue.valueOf((v == null) ? 0 : v.activeIncomingStreams()));
        return t;
    }

    /** The keys a link fires, as the refusal lists them: {@code Open, Close, Error} and the Peer keys. */
    private static String keys() {
        StringBuilder sb = new StringBuilder();
        for(String k : LuaVoice.KEYS) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(k);
        }
        return sb.toString();
    }

    /** Is {@code key} one a link fires? */
    private static boolean isKey(String key) {
        for(String k : LuaVoice.KEYS) {
            if(k.equals(key))
                return true;
        }
        return false;
    }

    /** Every addon that may hold a link — the loaded ones and the {@code :lua} REPL owner. */
    private static List<Addon> owners() {
        List<Addon> out = new ArrayList<Addon>(AddonManager.addons);
        Addon c = AddonManager.consoleOwner;
        if(c != null)
            out.add(c);
        return out;
    }

    /** The addon holding a live link to {@code origin}, or {@code null}. Case-insensitive: an origin is one. */
    private static Addon holderOf(String origin) {
        String want = origin.toLowerCase(Locale.ROOT);
        for(Addon a : owners()) {
            for(LuaVoice c : a.voices) {
                if(!c.done && c.origin.toLowerCase(Locale.ROOT).equals(want))
                    return a;
            }
        }
        return null;
    }

    /** Live links across the client — what {@link LuaVoice#MAX_LIVE} bounds. */
    private static int liveCount() {
        int n = 0;
        for(Addon a : owners()) {
            for(LuaVoice c : a.voices) {
                if(!c.done)
                    n++;
            }
        }
        return n;
    }

    /**
     * <b>End a link from Lua</b> — {@code :close()}'s body, under the owner's lock, by state:
     * <ul>
     *   <li>{@code new}: nothing was opened, so it is {@code closed} here and silently — no {@code Close}
     *       fires for a link that never went anywhere, and it is in no collection to leave.</li>
     *   <li>{@code connecting}: {@code closing}, and a {@code Close} carrying an empty reason is queued for
     *       the drain. The engine, if the connect has produced one, is closed by the connect's own
     *       completion, which finds the state moved ({@link LuaVoice#open}).</li>
     *   <li>{@code open}: {@code closing}; the engine is taken and closed on the pool, and the {@code Close}
     *       is queued once it has — so when the handler runs the microphone is given back and the server
     *       slot is free, and a reconnect from inside it is not refused for a link still winding down.</li>
     *   <li>{@code closing} and {@code closed}: nothing; a second ending answers the receiver again.</li>
     * </ul>
     */
    private static void close(final LuaVoice c) {
        switch(c.state) {
        case NEW:
            c.state = LuaVoice.State.CLOSED;
            break;
        case CONNECTING:
            c.state = LuaVoice.State.CLOSING;
            c.events.add(new LuaVoice.Pending("Close", ""));
            break;
        case OPEN:
            c.state = LuaVoice.State.CLOSING;
            c.endEngine(new Runnable() {
                public void run() {
                    c.events.add(new LuaVoice.Pending("Close", ""));
                }
            });
            break;
        default:
            break;
        }
    }

    // ------------------------------------------------------------- the drain

    /**
     * <b>Every addon's queued edges, fired on the step</b> — called from {@code AddonManager.layerStep} right
     * after the websocket drain, holding no tree monitor — and every open link's spatial vectors for the
     * frame. The {@code :lua} REPL owner is walked too, as its timers are.
     */
    static void drain() {
        for(Addon a : AddonManager.addons)
            drain(a);
        Addon c = AddonManager.consoleOwner;
        if(c != null)
            drain(c);
    }

    /**
     * One addon's links — <b>the whole pass as one entry into its Lua</b>, for {@link Subs#fire}'s reason:
     * several edges landing on one frame are one addon's own beat. Ended links are collected and dropped in
     * one call, since the list is copy-on-write.
     */
    private static void drain(Addon a) {
        if(a.voices.isEmpty() || !AddonManager.enterLua(a))
            return;
        try {
            List<LuaVoice> ended = null;
            for(LuaVoice c : a.voices) {
                if(step(c)) {
                    if(ended == null)
                        ended = new ArrayList<LuaVoice>();
                    ended.add(c);
                } else {
                    c.spatialize();
                }
            }
            if(ended != null)
                a.voices.removeAll(ended);
        } finally {
            AddonManager.leaveLua(a);
        }
    }

    /**
     * Fire what one link queued, in order, and stop at its ending. Answers whether the link is done and
     * leaves the collection.
     *
     * <p>Each edge is read against the state: an {@code Open} queued for a link the addon has since closed is
     * not fired, a Peer edge fires on an open link only, and a {@code Close} or {@code Error} behind the one
     * that ended it is not fired either — exactly one of the two ends a link, whatever the engine reported
     * after.
     */
    private static boolean step(LuaVoice c) {
        LuaVoice.Pending p;
        while(!c.done && ((p = c.events.poll()) != null)) {
            if("Open".equals(p.key)) {
                if(c.state == LuaVoice.State.CONNECTING) {
                    c.state = LuaVoice.State.OPEN;
                    c.opened = true;
                    c.subs.fire("Open", c.handle);
                }
            } else if("Close".equals(p.key)) {
                end(c, "Close", LuaVoiceEvent.close(c.owner, c.handle, p.text));
            } else if("Error".equals(p.key)) {
                end(c, "Error", LuaVoiceEvent.error(c.owner, c.handle, p.text));
            } else if(p.gob >= 0) {
                // A Peer edge (143.2): the Peer is minted here, under the addon's lock, and fired only while
                // the link is open -- one queued behind the Open of a link the addon has since closed says
                // nothing a closed link can act on.
                if(c.state == LuaVoice.State.OPEN)
                    c.subs.fire(p.key, LuaPeer.of(c, p.gob));
            }
        }
        return c.done;
    }

    /**
     * Deliver the ending: the state is {@code closed} before the handler runs, so {@code voice:state()} read
     * inside it says what the event says; the engine, where the addon's own {@code :close()} has not already
     * taken it, is closed off the step; and the subscriptions are dropped after the fire, so a handler that
     * closed over its own link no longer holds the record alive through it.
     */
    private static void end(LuaVoice c, String key, LuaValue ev) {
        c.state = LuaVoice.State.CLOSED;
        c.done = true;
        c.endEngine(null);
        c.subs.fire(key, ev);
        c.subs.clear();
    }

    // ------------------------------------------------------------- the taps

    /**
     * A move order issued for {@code mv}'s character — from {@code MapView.clickhit} on the hit-test's own
     * thread, and from {@code Sessions.send} on the UI thread — offered to every live link that reports that
     * view. No Lua is entered and nothing is allocated for a client with no link.
     */
    static void move(MapView mv, Coord2d mc) {
        for(Addon a : AddonManager.addons)
            move(a, mv, mc);
        Addon c = AddonManager.consoleOwner;
        if(c != null)
            move(c, mv, mc);
    }

    private static void move(Addon a, MapView mv, Coord2d mc) {
        for(LuaVoice c : a.voices) {
            if(!c.done)
                c.move(mv, mc);
        }
    }

    // ------------------------------------------------------------- teardown

    /**
     * Teardown (the {@code Step} after the websocket connections, and the exit): every link's engine is
     * closed off the step, its record dropped, and <b>no handler runs</b> — the addon these belonged to is
     * going away, and there is nothing left to tell it. The last engine to close gives the microphone back.
     */
    static void teardown(Addon a) {
        if(a.voices.isEmpty())
            return;
        for(LuaVoice c : a.voices) {
            c.done = true;
            c.state = LuaVoice.State.CLOSED;
            c.endEngine(null);
            c.subs.clear();
        }
        a.voices.clear();
    }
}
