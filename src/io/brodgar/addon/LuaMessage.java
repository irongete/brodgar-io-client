package io.brodgar.addon;

import haven.ChatUI;
import haven.UI;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.lib.OneArgFunction;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A <b>Message object</b> — one line of one channel's scrollback ({@code ch:message()}). Built by
 * {@link LuaChannel}, which owns the channel these hang off and the collection they are read through.
 *
 * <p><b>The intern key is {@code (Channel, index)}</b>, and it is a real address rather than a convenience:
 * {@code Channel.rmsgs} is only ever appended to, so a line's place in it is fixed for the life of the
 * channel and two reads of line 40 are the same line. That is what lets a {@code MessageAdded} handler
 * compare its payload against {@code ch:message():get(n)}, and what makes a table keyed by a line work.
 *
 * <p><b>The line's data is read off {@link ChatUI.Channel.Message} itself</b> — {@code text()},
 * {@code color()} and {@code speaker()}, which the client answers for whichever subclass built the line.
 * Reading the subclasses from here instead would need a ladder that a subclass added upstream falls off,
 * silently, as a line with no text.
 *
 * <p><b>A line whose channel has gone answers nothing but its own identity.</b> The channel holds the
 * scrollback, so a handle that kept one alive would pin every line of a conversation the server closed:
 * {@link #live} drops the reference the moment it can prove the channel is out of its tree, and after that
 * every read answers {@code nil} and {@code :exists()} answers {@code false} — the shape a
 * {@link LuaChannel} has, for the same reason and one level up.
 *
 * <p><b>Threading.</b> Every read here runs on the UI thread, but {@code rmsgs} is appended to by the thread
 * that applies the server's update and under the list's <i>own</i> monitor rather than the tree's, so every
 * read of it here takes that one.
 */
public final class LuaMessage {
    /** The channel this line is in, or {@code null} once it has been proven out of its tree ({@link #live}). */
    private ChatUI.Channel chan;
    /** Its 0-based place in {@code rmsgs}; {@code ch:message():get(i)} takes the 1-based one. */
    private final int idx;

    private LuaMessage(ChatUI.Channel chan, int idx) {
        this.chan = chan;
        this.idx = idx;
    }

    /** {@code tostring(msg)}: {@code Message(12)}, its 1-based place, or {@code Message(?)} once it is gone. */
    public String toString() {
        return "Message(" + ((chan == null) ? "?" : Integer.toString(idx + 1)) + ")";
    }

    /** An interned Message object for the line at {@code idx} of {@code chan}, in {@code owner}'s env. */
    static LuaValue of(Addon owner, ChatUI.Channel chan, int idx) {
        return owner.messages.of(chan, idx);
    }

    /** The {@code LuaMessage} behind a Lua value, or {@code null} for anything that is not a Message. */
    static LuaMessage resolve(LuaValue v) {
        if((v == null) || !v.isuserdata())
            return null;
        Object o = v.touserdata();
        return (o instanceof LuaMessage) ? (LuaMessage)o : null;
    }

    /** What a <b>string</b> filter matches: the line as it was written. Null for a line that has gone. */
    static String needle(LuaValue member) {
        ChatUI.Channel.Message msg = live(resolve(member));
        return (msg == null) ? null : msg.text();
    }

    /**
     * The channel behind a handle while it is still in its tree, else {@code null} — and the reference is
     * dropped on the way, which is what keeps a held line from pinning a closed conversation's whole
     * scrollback. The {@link LuaChannel#live} test, applied one level down.
     */
    private static ChatUI.Channel channel(LuaMessage h) {
        if(h == null)
            return null;
        ChatUI.Channel c = h.chan;
        if(c == null)
            return null;
        UI u = c.ui;
        if((u == null) || (u.root == null))
            return null;                       // no UI yet: unresolvable now, but not proven dead
        if(u.destroyed || !c.hasparent(u.root)) {
            h.chan = null;
            return null;
        }
        return c;
    }

    /** The line itself, or {@code null} once its channel has gone. Under {@code rmsgs}' own monitor. */
    private static ChatUI.Channel.Message live(LuaMessage h) {
        ChatUI.Channel c = channel(h);
        if(c == null)
            return null;
        synchronized(c.rmsgs) {
            if((h.idx < 0) || (h.idx >= c.rmsgs.size()))
                return null;
            return c.rmsgs.get(h.idx).msg;
        }
    }

    /**
     * Is this line <b>the player's own</b>? A membership test over the two classes the client says it with —
     * your line in a multi-chat, and the outgoing half of a private conversation — rather than a read of
     * anything the line carries, because that is what the distinction <i>is</i>: the client mints a
     * different class for it. A subclass nobody here knows about is somebody else's line, which is the safe
     * answer for a test whose whole job is to tell your own apart.
     */
    private static boolean mine(ChatUI.Channel.Message m) {
        return (m instanceof ChatUI.MultiChat.MyMessage) || (m instanceof ChatUI.PrivChat.OutMessage);
    }

    // ---- the per-addon intern cache + metatable ----------------------------------------------------

    /**
     * One addon's Message cache and metatable (its {@link Addon#messages}), keyed by the channel widget and
     * then by the line's index. Weak on <b>both</b> axes, the {@link LuaChannel.Cache} shape: a channel that
     * goes must not be held here, and the index map of a long scrollback must not be held by handles Lua has
     * released, which is why the inner map is drained through a {@link ReferenceQueue} the way
     * {@link LuaKin.Cache} is.
     */
    static final class Cache {
        private final Addon owner;
        private final Map<ChatUI.Channel, Map<Integer, Ref>> live =
            new WeakHashMap<ChatUI.Channel, Map<Integer, Ref>>();
        private final ReferenceQueue<LuaValue> dead = new ReferenceQueue<LuaValue>();
        private LuaValue mt;

        Cache(Addon owner) {
            this.owner = owner;
        }

        /** The interned handle for {@code (chan, idx)} — a cache hit, or a freshly minted (inserted) one. */
        synchronized LuaValue of(ChatUI.Channel chan, int idx) {
            drain();
            if(chan == null)
                return LuaValue.NIL;
            Map<Integer, Ref> byidx = live.get(chan);
            if(byidx == null)
                live.put(chan, byidx = new HashMap<Integer, Ref>());
            Integer key = Integer.valueOf(idx);
            Ref r = byidx.get(key);
            if(r != null) {
                LuaValue v = r.get();
                if(v != null)
                    return v;
                byidx.remove(key);
            }
            LuaValue v = LuaValue.userdataOf(new LuaMessage(chan, idx), meta());
            byidx.put(key, new Ref(v, byidx, key, dead));
            return v;
        }

        /** Drop the entries whose handle Lua has released — a scrollback walked once would leak them all. */
        private void drain() {
            Reference<? extends LuaValue> r;
            while((r = dead.poll()) != null) {
                Ref mr = (Ref)r;
                if(mr.map.get(mr.key) == mr)    // not already replaced by a fresh handle for the same line
                    mr.map.remove(mr.key);
            }
        }

        private LuaValue meta() {
            if(mt == null)
                mt = buildMeta(owner);
            return mt;
        }
    }

    /**
     * A weak handle reference that remembers where it is filed, so the {@link ReferenceQueue} drain can unmap
     * it. It holds the <b>index map</b> and not the channel: the map is the {@link WeakHashMap}'s value, and a
     * value that pointed back at its own key would keep the channel — and its whole scrollback — alive for
     * exactly as long as one released handle sat unreaped.
     */
    private static final class Ref extends WeakReference<LuaValue> {
        final Map<Integer, Ref> map;
        final Integer key;

        Ref(LuaValue v, Map<Integer, Ref> map, Integer key, ReferenceQueue<LuaValue> q) {
            super(v, q);
            this.map = map;
            this.key = key;
        }
    }

    // ---- the Message metatable ---------------------------------------------------------------------

    private static LuaValue buildMeta(final Addon owner) {
        LuaTable mt = new LuaTable();
        mt.set(LuaValue.INDEX, Refusal.closedIndex("message", methods(owner),
            "one line of a chat channel"));
        mt.set("__name", LuaValue.valueOf("Message"));
        mt.set("__tostring", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMessage h = resolve(self);
                return LuaValue.valueOf((h == null) ? "Message(?)" : h.toString());
            }
        });
        return mt;
    }

    private static LuaTable methods(final Addon owner) {
        LuaTable m = new LuaTable();
        // text() — the line as it was written, markup and all. The client renders that markup; nothing here
        // strips it, so what an addon reads is what the server said.
        m.set("text", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                ChatUI.Channel.Message msg = live(handle(self, "text"));
                String s = (msg == null) ? null : msg.text();
                return (s == null) ? LuaValue.NIL : LuaValue.valueOf(s);
            }
        });
        // kind() — the site key THIS line resolves at, which is the channel's own unless the line names its
        // own: your line in a multi-chat is "chat.mine" in a "chat" channel. The same closed set of words a
        // stylesheet rule is written at.
        m.set("kind", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                ChatUI.Channel.Message msg = live(handle(self, "kind"));
                return (msg == null) ? LuaValue.NIL : LuaValue.valueOf(msg.scope());
            }
        });
        // color() — the colour the line carries of itself: the server's, for a party line the speaker's, and
        // for your own the client's. A theme paints over it, which is the theme's business and not the line's.
        m.set("color", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                ChatUI.Channel.Message msg = live(handle(self, "color"));
                return (msg == null) ? LuaValue.NIL : AddonManager.color(msg.color());
            }
        });
        // time() — when the client took the line, in epoch SECONDS. A double, so it is formatted with "%d"
        // rather than printed: Lua's default number formatting turns it into scientific notation.
        m.set("time", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                ChatUI.Channel.Message msg = live(handle(self, "time"));
                return (msg == null) ? LuaValue.NIL : LuaValue.valueOf(msg.time);
            }
        });
        // speaker() — who said it, as a Kin of THAT character's roster, or nil where the line names nobody:
        // the System log, an ordinary channel's plain lines, and both halves of a private conversation, which
        // carry a direction rather than a sender.
        m.set("speaker", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                LuaMessage h = handle(self, "speaker");
                ChatUI.Channel.Message msg = live(h);
                Integer from = (msg == null) ? null : msg.speaker();
                if(from == null)
                    return LuaValue.NIL;
                String user = AddonManager.userOf(h.chan);
                return (user == null) ? LuaValue.NIL : LuaKin.of(owner, user, from.intValue());
            }
        });
        // mine() — did this character say it? A bare adjective, true for your own line in a channel and for
        // the outgoing half of a private conversation.
        m.set("mine", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                ChatUI.Channel.Message msg = live(handle(self, "mine"));
                return (msg == null) ? LuaValue.NIL : LuaValue.valueOf(mine(msg));
            }
        });
        // channel() — the Channel this line is in, interned, so it IS the one :list() handed you.
        m.set("channel", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                ChatUI.Channel c = channel(handle(self, "channel"));
                return (c == null) ? LuaValue.NIL : LuaChannel.of(owner, c);
            }
        });
        // exists() — is this line still readable? False once its channel has gone, taking the scrollback with
        // it. A line itself is never dropped: the client trims the raster it drew, never the line.
        m.set("exists", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return LuaValue.valueOf(live(handle(self, "exists")) != null);
            }
        });
        // info() — the one SNAPSHOT escape hatch; nil once the line is gone, because there is nothing left
        // to copy.
        m.set("info", new OneArgFunction() {
            public LuaValue call(LuaValue self) {
                return snapshot(live(handle(self, "info")));
            }
        });
        return m;
    }

    /** The documented {@code Message} snapshot, or {@code nil} once the line's channel has gone. */
    private static LuaValue snapshot(ChatUI.Channel.Message msg) {
        if(msg == null)
            return LuaValue.NIL;
        LuaTable t = new LuaTable();
        String s = msg.text();
        if(s != null)
            t.set("text", LuaValue.valueOf(s));
        t.set("kind", LuaValue.valueOf(msg.scope()));
        LuaValue col = AddonManager.color(msg.color());
        if(!col.isnil())
            t.set("color", col);
        t.set("time", LuaValue.valueOf(msg.time));
        t.set("mine", LuaValue.valueOf(mine(msg)));
        Integer from = msg.speaker();
        if(from != null)
            t.set("speaker", LuaValue.valueOf(from.intValue()));
        return t;
    }

    /** The handle behind a method's {@code self}, or a guiding error (a dot-call passes the wrong self). */
    private static LuaMessage handle(LuaValue self, String method) {
        LuaMessage h = resolve(self);
        if(h == null)
            throw new LuaError("message:" + method + "() — use a COLON call on a Message object"
                + " (channel:message():get(i), :find(needle) or :list()[n])");
        return h;
    }
}
