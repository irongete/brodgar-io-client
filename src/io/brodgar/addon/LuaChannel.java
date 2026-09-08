package io.brodgar.addon;

import haven.ChatUI;
import haven.GameUI;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.VarArgFunction;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A <b>Channel object</b> — one tab of the chat window ({@code s:chat()}). <b>The section object IS the
 * collection</b> (uniform grammar §2.1): {@code s:chat()} is the channels one character holds,
 * {@code s:chat():selected()} the distinguished member, and this is one of them. Built by {@link ChatApi},
 * which owns the collection and the section's own spelling.
 *
 * <p><b>The intern key is the {@link ChatUI.Channel} widget itself.</b> A channel publishes no id the client
 * addresses — the server places it as a widget and takes it away as one — so the widget's identity is the
 * whole of what a handle can be keyed by, and the map is the {@link LuaWidget} shape: weak on both axes, so
 * a channel the server destroyed is not pinned by a cache nobody is reading.
 *
 * <p><b>A channel that has left the tree answers nothing but its own identity.</b> {@link #live} drops the
 * reference the moment it can prove the widget is out of its tree (the no-pin rule a whole scrollback makes
 * expensive to break), so every read answers {@code nil} and {@code :exists()} answers {@code false} — the
 * shape {@code GobRemoved}'s payload has. What survives is the object: channels are interned, so a
 * {@code ChannelRemoved} handler compares the one it is handed against the table it filled on
 * {@code ChannelAdded} and needs no name to do it.
 *
 * <p><b>The kind is the client's own site key.</b> {@link ChatUI.Channel#chanscope()} is what a chat line's
 * face and colour resolve at, so {@code ch:kind()} and a stylesheet rule name one thing rather than two
 * vocabularies for one distinction. The set is closed by the channel classes the client ships:
 * {@code "chat"}, {@code "chat.system"}, {@code "chat.party"} and {@code "chat.private"}.
 *
 * <p><b>Threading.</b> Every read here runs on the UI thread. The channel list mutates on the thread that
 * applies the server's update, so {@link ChatApi} walks it under that tree's own monitor; nothing on this
 * class walks it.
 */
public final class LuaChannel {
    /**
     * The channel widget, or {@code null} once it has been proven out of its tree. Mutable for exactly that
     * reason — see {@link #live}.
     *
     * <p><b>Volatile</b> (audit2 B06): the transition is a one-way proof made by whichever thread read the
     * handle first, and every other reader has to see it. Without the barrier a second thread went on
     * answering with a channel this one had already proved dead.
     */
    private volatile ChatUI.Channel chan;

    private LuaChannel(ChatUI.Channel chan) {
        this.chan = chan;
    }

    /** {@code tostring(ch)}: {@code Channel(<name>)}, or {@code Channel(?)} once it is gone. */
    public String toString() {
        String nm = nameOf(live(this));
        return "Channel(" + ((nm == null) ? "?" : nm) + ")";
    }

    /** An interned Channel object for {@code chan} in {@code owner}'s env — the one way one reaches Lua. */
    static LuaValue of(Addon owner, ChatUI.Channel chan) {
        return owner.channels.of(chan);
    }

    /** The {@code LuaChannel} behind a Lua value, or {@code null} for anything that is not a Channel. */
    static LuaChannel resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaChannel) ? (LuaChannel)o : null;
    }

    /**
     * The channel widget behind a handle while it is still in its tree, else {@code null} — and the reference
     * is dropped on the way, so a destroyed channel's whole scrollback is not held by a handle Lua forgot to
     * let go of. The {@link LuaWidget#live} test, for the same reason.
     */
    static ChatUI.Channel live(LuaChannel h) {
        if(h == null)
            return null;
        ChatUI.Channel c = h.chan;
        if(c == null)
            return null;
        UI u = c.ui;
        if((u == null) || (u.root == null))
            return null;                       // no UI yet: unresolvable now, but not proven dead
        boolean gone;
        synchronized(LuaWidget.monitor(c)) {   // audit2 B06: the parent walk, under the tree that re-links it
            gone = u.destroyed || !c.hasparent(u.root);
        }
        if(gone) {
            h.chan = null;
            return null;
        }
        return c;
    }

    /**
     * The channel's name, or {@code null} where the client cannot state one. {@code PrivChat.name()} resolves
     * the other person through {@code GameUI.buddies}, so it walks up to the HUD — a channel that has left the
     * tree has no such walk, and asking anyway is a null dereference in upstream code.
     *
     * <p>It is also the string a filter matches, which is why it is package-visible.
     */
    static String nameOf(ChatUI.Channel c) {
        if(c == null)
            return null;
        // audit2 B06: under that tree's monitor. The walk up to the HUD follows parent links a Loader thread
        // re-points, and PrivChat.name() then reads GameUI.buddies, which the network thread rewrites.
        synchronized(LuaWidget.monitor(c)) {
            if(c.getparent(GameUI.class) == null)
                return null;
            return c.name();
        }
    }

    /**
     * How many lines this channel holds, or {@code 0} once it has gone. Under {@code rmsgs}' own monitor,
     * which is the list's rather than the tree's: the server's update thread appends under exactly that one.
     */
    private static int count(LuaChannel h) {
        ChatUI.Channel c = live(h);
        if(c == null)
            return 0;
        synchronized(c.rmsgs) {
            return c.rmsgs.size();
        }
    }

    /** The site key this channel's lines resolve at — the closed set {@code ch:kind()} answers from. */
    private static String kindOf(ChatUI.Channel c) {
        return (c == null) ? null : c.chanscope();
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Channel cache and metatable (its {@link Addon#channels}), keyed by the channel widget.
     * Weak on <b>both</b> axes, the {@link LuaWidget.Cache} shape: a channel the server destroys must not be
     * held by this map, and its scrollback is the reason the rule matters here rather than merely applying.
     */
    static final class Cache {
        private final Addon owner;
        // retained: weak on both axes -- the value is a WeakReference, so nothing reaches the channel.
        private final Map<ChatUI.Channel, WeakReference<LuaValue>> live =
            new WeakHashMap<ChatUI.Channel, WeakReference<LuaValue>>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code chan} — a cache hit, or a freshly minted (and inserted) one. */
        synchronized LuaValue of(ChatUI.Channel chan) {
            if(chan == null)
                return LuaValue.NIL;
            WeakReference<LuaValue> r = live.get(chan);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
            }
            LuaValue v = LuaValue.userdataOf(new LuaChannel(chan), meta());
            live.put(chan, new WeakReference<LuaValue>(v));
            return v;
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    // ---- the Channel metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("channel", methods(owner),
            "one tab of the chat window"));
        mt.set("__name", LuaValue.valueOf("Channel"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaChannel h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Channel(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // name() — the tab's own caption. A private conversation is named for the other person and reads
        // "???" until this character's kin roster carries them, which is the client's own answer.
        m.set("name", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String nm = nameOf(live(handle(self, "name")));
                return (nm == null) ? LuaValue.NIL : LuaValue.valueOf(nm);
            }
        });
        // kind() — the site key this channel's lines resolve at, which is the very word a stylesheet rule
        // names: "chat", "chat.system", "chat.party" or "chat.private". A closed set, decided by the channel
        // classes the client ships.
        m.set("kind", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                String k = kindOf(live(handle(self, "kind")));
                return (k == null) ? LuaValue.NIL : LuaValue.valueOf(k);
            }
        });
        // urgency() — the unread level the client keeps for this tab, 0 when nothing is unread. It is the
        // level "chat.urgent"'s palette is read one entry per, so the number and the theme agree.
        m.set("urgency", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                ChatUI.Channel c = live(handle(self, "urgency"));
                return (c == null) ? LuaValue.NIL : LuaValue.valueOf(c.urgency);
            }
        });
        // message() — this channel's LINES, oldest first and 1-based. A collection rather than a verb per
        // line: :get(i) is a real address, because the client never trims the scrollback -- what it drops
        // when a line scrolls away is the raster it drew, and the line keeps its place. :list() therefore
        // copies a whole login's chat, which is why the page reaches for :count() and :get(i).
        m.set("message", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                final LuaChannel h = handle(self, "message");
                return LuaCollection.create("channel:message()", new LuaCollection.Source() {
                    public List<LuaValue> members() {
                        List<LuaValue> out = new ArrayList<LuaValue>();
                        int n = count(h);
                        for(int i = 0; i < n; i++)
                            out.add(LuaMessage.of(owner, live(h), i));
                        return out;
                    }

                    /** A line is its text, so a string filter is a substring test over what was said. */
                    public boolean named() {
                        return true;
                    }

                    public String needle(LuaValue member) {
                        return LuaMessage.needle(member);
                    }

                    public boolean addressable() {
                        return true;
                    }

                    public LuaValue getMember(LuaValue key) {
                        int i = Args.integer(key, "channel:message():get", "i", "a line's position in"
                                             + " the scrollback, oldest first");
                        if(i < 1)
                            throw new LuaError("channel:message():get(i): a line's position is a whole"
                                + " number and the indices start at one — the oldest line this channel"
                                + " holds is :get(1) and the newest is :get(ch:message():count()), got "
                                + i);
                        ChatUI.Channel c = live(h);
                        return (i > count(h)) ? LuaValue.NIL : LuaMessage.of(owner, c, i - 1);
                    }

                    /** The key is a line's 1-based position in the scrollback. */
                    public String keyName() {
                        return "i";
                    }
                }, null);
            }
        });
        // send(text) — SAY a line in this channel, behind "chat.send" and gated as the first statement
        // (D-213). The System log has no entry line: the client writes it and nobody says anything in it.
        m.set("send", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                AddonManager.requirePermission(owner, Permission.CHAT_SEND);
                LuaValue self = a.arg1();
                LuaChannel h = handle(self, "send");
                String text = Args.str(a, 2, "channel:send", "text", null).tojstring();
                if(text.isEmpty())
                    throw new LuaError("channel:send(text): text is empty — there is no line to say, and"
                        + " the client's own quick line refuses one too");
                ChatUI.Channel c = live(h);
                if(c == null)
                    throw new LuaError("channel:send(text): this channel is gone — ch:exists() is the test,"
                        + " and " + ChatApi.CH + ":list() is what the character has now");
                if(!(c instanceof ChatUI.EntryChannel))
                    throw new LuaError("channel:send(text): a channel of kind '" + kindOf(c) + "' has no"
                        + " entry line — the kinds that take one are \"chat\", \"chat.party\" and"
                        + " \"chat.private\", and ch:kind() says which this is");
                // audit2 B06: send() appends to the channel's own history -- a plain list keydown walks --
                // and then walks the parent chain to reach the UI for its wdgmsg. Both under that tree's
                // monitor, which is what every other write in this section takes.
                synchronized(LuaWidget.monitor(c)) {
                    ((ChatUI.EntryChannel)c).send(text);
                }
                return self;
            }
        });
        // exists() — is this channel still one of the character's? A tab the server takes away goes false and
        // every read above answers nil, while the object stays the key your own table is under.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists")) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch; nil once the channel is gone, because there is nothing
        // left to copy.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(live(handle(self, "info")));
            }
        });
        return m;
    }

    /** The documented {@code Channel} snapshot, or {@code nil} once the channel has left the tree. */
    private static LuaValue snapshot(ChatUI.Channel c) {
        if(c == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String nm = nameOf(c);
        if(nm != null)
            t.set("name", LuaValue.valueOf(nm));
        t.set("kind", LuaValue.valueOf(c.chanscope()));
        t.set("urgency", LuaValue.valueOf(c.urgency));
        return t;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaChannel handle(LuaValue self, String method) {
        LuaChannel h = resolve(self);
        if(h == null)
            throw new LuaError("channel:" + method + "() — use a COLON call on a Channel object ("
                + ChatApi.CH + ":find(needle), :selected() or :list()[n])");
        return h;
    }
}
