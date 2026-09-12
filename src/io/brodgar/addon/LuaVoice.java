package io.brodgar.addon;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import haven.Coord2d;
import haven.Drawable;
import haven.GameUI;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MapView;
import haven.OCache;
import haven.Resource;

import io.brodgar.session.Sessions;
import io.brodgar.voice.BrodgarVoice;
import io.brodgar.voice.BrodgarVoiceHost;
import io.brodgar.voice.MovementIntent;
import io.brodgar.voice.Vec;
import io.brodgar.voice.VisibleGob;
import io.brodgar.voice.VoiceConfig;
import io.brodgar.voice.VoiceException;
import io.brodgar.voice.VoiceListener;

import org.luaj.vm2.LuaValue;

/**
 * One {@code hafen.voice()} link (143.1) — the record behind the {@code Voice} handle, the voice engine
 * ({@link BrodgarVoice}) under it, and the queue between the two. The sibling of {@link LuaWebSocket} one
 * door along, and the same shape: bridge-owned, registered in {@link Addon#voices} from {@code :connect()}
 * until its ending is heard, torn down with the addon.
 *
 * <p><b>Built bare, opened on purpose.</b> Every connect-time setter ({@code timeout}, {@code spatial},
 * {@code bitrate}) writes a field here until {@code :connect()} and none after: {@link #dispatched} is
 * written by that verb alone, so there is no timing in the rule. {@link #session} is the one setter legal
 * in every state, because it names whose world the link reports and that can change while it is open.
 *
 * <p><b>The engine is built on the pool</b> ({@link #open}): {@code BrodgarVoice.connect} blocks on the
 * handshake and the audio open, so the call path turns the state {@code connecting} and hands the rest to
 * {@link HttpApi#pool()}, where the address is resolved and checked first, exactly as a websocket's is.
 * Success queues {@code Open}; a {@link VoiceException} — a host that will not resolve, a refused hello, a
 * protocol version the server does not speak, no microphone — queues {@code Error} naming it. The engine
 * never reconnects by itself ({@code autoReconnect(false)}): a link resurrected behind its {@code Close}
 * would break the one-ending rule, and a {@code Close} handler and a timer are the whole of a reconnect.
 *
 * <p><b>The listener only enqueues.</b> The engine fires {@link VoiceListener} on threads of its own, which
 * hold no tree and may not enter Lua, so every edge it reports is a {@link Pending} on {@link #events}, and
 * {@link VoiceApi#drain} fires them from the layer's step under the addon's own lock, in the order they
 * happened. A fatal {@code disconnected} is the server ending the link and fires as {@code Close} with the
 * server's reason; any other fatal error fires as {@code Error}; a non-fatal one is logged and the link
 * goes on.
 *
 * <p><b>This record is the engine's host</b> ({@link BrodgarVoiceHost}), and <b>everything it answers is
 * derived on the call</b>: the view is the one drawn ({@link Sessions#anchormember}) or the pinned session's
 * own ({@link Sessions#byuser}), read every time, so a link follows the screen and a pinned one follows
 * its character through a relog. The engine polls {@link #localGobId} and {@link #visiblePlayers} every
 * report interval from its own thread; both snapshot under the {@link OCache} lock and classify outside
 * it. Move intents arrive from the two taps ({@code MapView.clickhit}, {@code Sessions.send}) through
 * {@link #move}; the per-frame spatial vectors are pushed by the drain ({@link #spatialize}).
 *
 * <p><b>One ending, said once.</b> {@link #done} is set by the drain when it delivers {@code Close} or
 * {@code Error}, and nothing here fires after it. Whichever side ends the link, the engine under it is
 * closed <b>off the step</b> ({@link #closeEngine}) — {@code BrodgarVoice.close} sends the Bye and joins
 * its threads — and the shared microphone is released after it, so the last link to end is the one that
 * closes the device.
 */
final class LuaVoice implements BrodgarVoiceHost, VoiceListener {
    /** The keys a link answers, in the order a refusal lists them — a closed set (D-129). */
    static final String[] KEYS = {"Open", "Close", "Error"};

    /** Live links the whole client may hold at once; the next {@code :connect()} is refused naming the count. */
    static final int MAX_LIVE = 4;
    /** The Opus bitrate a link is built with unless {@code :bitrate(n)} says otherwise, and the range it takes. */
    static final int DEFAULT_BITRATE = 24_000, MIN_BITRATE = 8_000, MAX_BITRATE = 64_000;
    /** What the server is told the client is, in the hello. */
    static final String CLIENT_INFO = "hafen+brodgar";

    /** Where a link is in its life — the word {@code voice:state()} reads. */
    enum State {
        NEW("new"), CONNECTING("connecting"), OPEN("open"), CLOSING("closing"), CLOSED("closed");

        final String word;

        State(String word) {
            this.word = word;
        }
    }

    /**
     * One edge the listener saw, waiting for the drain: the key it fires as, and what the payload carries —
     * {@code Close} its reason, {@code Error} its sentence, {@code Open} nothing.
     */
    static final class Pending {
        final String key;
        final String text;

        Pending(String key, String text) {
            this.key = key;
            this.text = text;
        }
    }

    final Addon owner;
    /** The address as written, what {@code voice:url()} reads and the collection's string filter matches. */
    final String url;
    final URI uri;
    /** The <b>origin</b> the allowlist is asked about, and the one a second live link is refused against. */
    final String origin;
    /** The handshake deadline in milliseconds; {@link LuaHttp#DEFAULT_TIMEOUT} unless set. */
    int timeout = LuaHttp.DEFAULT_TIMEOUT;
    /** Whether the mix pans and attenuates by position; {@code true} unless {@code :spatial(false)}. */
    boolean spatial = true;
    /** The Opus bitrate in bits per second. */
    int bitrate = DEFAULT_BITRATE;
    /** The account this link speaks for, or {@code null} to follow the screen. Written any time, read off-thread. */
    volatile String session;
    /** {@code voice:on(key, fn)} — the one notification verb, over the three {@link #KEYS}. */
    final Subs subs;
    /** The Lua handle over this record — what {@code Open} hands over and {@code ev:connection()} answers. */
    LuaValue handle;

    volatile State state = State.NEW;
    /** Has {@code :connect()} run? Written under the owner's lock; until it has, every setter is legal. */
    boolean dispatched;
    /** Has the drain delivered {@code Open}? */
    volatile boolean opened;
    /** Has the drain delivered {@code Close} or {@code Error}? After it nothing here fires again. */
    volatile boolean done;
    /** The engine, from the connect's success until whoever ends the link takes it to close. Guarded by this record's monitor. */
    private BrodgarVoice engine;
    /** What the listener queued and the drain has not yet fired, in the order it happened. */
    final Queue<Pending> events = new ConcurrentLinkedQueue<Pending>();
    /** The engine's sink for move intents, registered at connect; {@code null} before. */
    private volatile Consumer<MovementIntent> intentSink;
    /** Did the last spatial push carry vectors? An empty one after an empty one is skipped. */
    private boolean spatialized;

    LuaVoice(Addon owner, String url, URI uri, String origin) {
        this.owner = owner;
        this.url = url;
        this.uri = uri;
        this.origin = origin;
        this.subs = new Subs(owner, Addon.C_EVENT);
    }

    /** {@code tostring(voice)} — the address and where the link is in its life. */
    public String toString() {
        return "Voice(" + url + ", " + state.word + ")";
    }

    /** The engine, or {@code null} before {@code Open} and from the moment the link began to end. */
    synchronized BrodgarVoice engine() {
        return engine;
    }

    /** The session id the server gave this link, or {@code -1} while there is none. */
    long id() {
        BrodgarVoice v = engine();
        return (v == null) ? -1 : v.sessionId();
    }

    // ------------------------------------------------------------- opening

    /**
     * <b>Dispatch</b> — {@code :connect()}'s other half, on the call path under the owner's lock: the state
     * turns {@code connecting} here and now, and the connect goes to the pool, because everything it does
     * blocks and nothing on the call path may.
     */
    void dispatch() {
        dispatched = true;
        state = State.CONNECTING;
        HttpApi.pool().execute(new Runnable() {
            public void run() {
                open();
            }
        });
    }

    /**
     * On a pool thread: the address check, the microphone, then the engine. <b>Every way this fails is an
     * {@code Error}</b> queued for the drain — a host that does not resolve, one that resolves into private
     * space, a microphone that will not open, a handshake the server refuses or one that runs past the
     * deadline — because a link that never opens still ends, and the addon hears its ending from the same
     * door as any other. The microphone is taken before the engine and given back on every failure, so a
     * link that never opened holds nothing.
     */
    private void open() {
        boolean held = false;
        try {
            String host = uri.getHost();
            InetAddress[] addrs = InetAddress.getAllByName(host);
            for(InetAddress a : addrs) {
                if(LuaHttp.isBlockedAddress(a)) {
                    fail("host " + host + " resolves to a blocked address (" + a.getHostAddress()
                         + "; private/loopback ranges are refused)");
                    return;
                }
            }
            if(state != State.CONNECTING)
                return;                                // closed while the name resolved: nothing is opened
            SharedMic.acquire();
            held = true;
            BrodgarVoice v = BrodgarVoice.connect(config(), this);
            boolean wanted;
            synchronized(this) {
                wanted = (state == State.CONNECTING);
                if(wanted)
                    engine = v;
            }
            if(!wanted) {
                // Closed or torn down while the engine was being built: the Close the addon asked for is
                // already queued (or the record already dropped), so the fresh engine is closed here, where
                // it is first known -- on this thread, which may block, and outside the monitor the step's
                // engine() read takes.
                closeEngine(v);
                return;
            }
            v.addListener(this);
            events.add(new Pending("Open", null));
        } catch(UnknownHostException e) {
            fail("DNS resolution failed for " + uri.getHost());
        } catch(VoiceException e) {
            if(held)
                SharedMic.release();
            fail(e.getMessage());
        } catch(RuntimeException e) {
            if(held)
                SharedMic.release();
            fail(e.getClass().getSimpleName() + ": " + Refusal.reason(e));
        }
    }

    /** The engine's configuration from this record's fields — read once, here, after the submit that published them. */
    private VoiceConfig config() {
        return VoiceConfig.builder()
            .serverUri(url)
            .clientInfo(CLIENT_INFO)
            .connectTimeoutMs(timeout)
            .spatialAudio(spatial)
            .bitrate(bitrate)
            .autoReconnect(false)
            .audioSource(SharedMic.feed())
            .build();
    }

    /** Queue an {@code Error} with {@code why} — from the pool thread, never entering Lua. */
    void fail(String why) {
        events.add(new Pending("Error", why));
    }

    // ------------------------------------------------------------- ending

    /**
     * <b>Take the engine to close it</b>: whoever ends the link — {@code :close()} on an open one, the drain
     * delivering the engine's own ending, the teardown — takes the engine out of the record here and closes
     * it on the pool ({@link #closeEngine}), so {@code engine()} answers {@code null} from the first moment
     * the link is ending and the close blocks no step. {@code after} runs once the engine has closed, or at
     * once when there was none.
     */
    void endEngine(final Runnable after) {
        final BrodgarVoice v;
        synchronized(this) {
            v = engine;
            engine = null;
        }
        intentSink = null;
        if(v == null) {
            if(after != null)
                after.run();
            return;
        }
        HttpApi.pool().execute(new Runnable() {
            public void run() {
                closeEngine(v);
                if(after != null)
                    after.run();
            }
        });
    }

    /** Close one engine and give its share of the microphone back. Pool thread: {@code close} blocks. */
    private static void closeEngine(BrodgarVoice v) {
        try {
            v.close();
        } catch(RuntimeException e) {
            AddonManager.log("voice: an engine did not close cleanly: " + Refusal.reason(e));
        } finally {
            SharedMic.release();
        }
    }

    // ------------------------------------------------------------- the listener (engine threads)

    /**
     * The engine's own {@code disconnected} is the server ending the link, and fires as {@code Close} with
     * the server's reason; any other fatal error ends it with {@code Error}. A non-fatal one — a capture
     * hiccup, a report the presence socket refused — is logged and the link goes on.
     */
    public void onError(String code, String message, boolean fatal) {
        if(done)
            return;
        String msg = ((message == null) || message.isEmpty()) ? code : message;
        if("disconnected".equals(code)) {
            events.add(new Pending("Close", msg));
        } else if(fatal) {
            events.add(new Pending("Error", code + ": " + msg));
        } else {
            AddonManager.logAbout(owner, "voice " + url + ": " + code + ": " + msg);
        }
    }

    /** The presence connection went down under an open link: the server's ending, as {@code Close}. */
    public void onConnectionState(boolean connected) {
        if(!connected && opened && !done)
            events.add(new Pending("Close", "connection lost"));
    }

    // ------------------------------------------------------------- the host (engine and client threads)

    /**
     * The view this link reports — the drawn one, or the pinned session's own — <b>derived every call</b>,
     * so a link never names a world its character has left. {@code null} before that session's HUD is up.
     */
    MapView mapview() {
        String user = session;
        Sessions.Member m = (user == null) ? Sessions.anchormember() : Sessions.byuser(user);
        GameUI g = (m == null) ? null : m.gameui();
        return (g == null) ? null : g.map;
    }

    public long localGobId() {
        MapView mv = mapview();
        return (mv == null) ? -1 : mv.plgob;             // -1 already when there is no character
    }

    /**
     * Every player gob in the view, as a vector from the character in tiles — <b>world-aligned</b>, the
     * frame every client shares, never rotated. Snapshotted under the {@link OCache} lock (its iterator is
     * not synchronized) and classified outside it, which the render thread also takes.
     */
    public List<VisibleGob> visiblePlayers() {
        MapView mv = mapview();
        Gob me = (mv == null) ? null : mv.player();
        if((me == null) || (me.rc == null))
            return Collections.emptyList();
        Coord2d origin = me.rc;
        long meId = me.id;
        OCache oc = me.glob.oc;
        List<Gob> gobs = new ArrayList<Gob>();
        List<Coord2d> rcs = new ArrayList<Coord2d>();
        synchronized(oc) {
            for(Gob g : oc) {
                if((g.id == meId) || (g.rc == null))
                    continue;
                gobs.add(g);
                rcs.add(g.rc);
            }
        }
        List<VisibleGob> out = new ArrayList<VisibleGob>();
        for(int i = 0; i < gobs.size(); i++) {
            Gob g = gobs.get(i);
            if(isPlayer(g)) {
                Coord2d v = rcs.get(i).sub(origin).div(MCache.tilesz);
                out.add(new VisibleGob(g.id, v.x, v.y));
            }
        }
        return out;
    }

    public void setMovementIntentSink(Consumer<MovementIntent> sink) {
        intentSink = sink;
    }

    /** A gob is a human player when its base sprite is the borka body. */
    private static boolean isPlayer(Gob g) {
        Drawable d = g.getattr(Drawable.class);
        if(d == null)
            return false;
        Resource res;
        try {
            res = d.getres();
        } catch(Loading stillStreaming) {
            return false;
        }
        return (res != null) && "gfx/borka/body".equals(res.name);
    }

    /**
     * A move order for {@code mv}'s character, in map units — from the two taps, on whichever thread issued
     * it. Reported to the engine as a vector from where the character stands, in tiles, when {@code mv} is
     * the view this link reports; ignored otherwise, and before the engine has asked for intents.
     */
    void move(MapView mv, Coord2d dest) {
        Consumer<MovementIntent> sink = intentSink;
        if((sink == null) || (dest == null) || (mv == null) || (mapview() != mv))
            return;
        Gob me = mv.player();
        if((me == null) || (me.rc == null))
            return;
        Coord2d v = dest.sub(me.rc).div(MCache.tilesz);
        sink.accept(new MovementIntent(System.currentTimeMillis(), v.x, v.y));
    }

    /**
     * The frame's spatial vectors — for each player heard, the camera-relative {@code (right, forward)}
     * vector from {@link MapView#spatialAzimuth}, the same eye-space balance the game's own positional
     * audio uses, so a voice pans with the camera. From the drain, once a frame, on the step. Purely local:
     * nothing here reaches the server. Skipped for a link built with {@code :spatial(false)}.
     */
    void spatialize() {
        if(!spatial)
            return;
        BrodgarVoice v = engine();
        if(v == null)
            return;
        Map<Long, Vec> vectors = new HashMap<Long, Vec>();
        MapView mv = mapview();
        Gob me = (mv == null) ? null : mv.player();
        if((me != null) && (me.rc != null)) {
            Coord2d origin = me.rc;
            OCache oc = me.glob.oc;
            for(long id : v.audibleGobs()) {
                Gob g = oc.getgob(id);
                if((g == null) || (g.rc == null))
                    continue;
                double az = mv.spatialAzimuth(g.rc);
                if(Double.isNaN(az))
                    continue;
                double dist = g.rc.sub(origin).div(MCache.tilesz).abs();
                vectors.put(id, new Vec(Math.sin(az) * dist, Math.cos(az) * dist));
            }
        }
        if(vectors.isEmpty() && !spatialized)
            return;
        spatialized = !vectors.isEmpty();
        v.setSpatialVectors(vectors);
    }
}
