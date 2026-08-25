package io.brodgar.addon;

import haven.ChatUI;
import haven.GameUI;
import haven.Widget;

import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import java.util.ArrayList;
import java.util.List;

/**
 * The <b>chat</b> subsystem: {@code s:chat()}, the channels one character holds, and the one it has on
 * screen. <b>The section object IS the collection</b> (uniform grammar §2.1) — a subsystem that holds
 * exactly one kind of thing <i>is</i> the collection of that kind, the shape {@code s:party()} and
 * {@code s:kin()} already have — so there is no inner {@code :channel()} noun with nothing else beside it.
 * One channel is a {@link LuaChannel}.
 *
 * <p><b>It is one character's, and so is every read through it.</b> The chat is {@link GameUI#chat}, which
 * is one login's HUD: two characters have two chats, two Party channels and two private conversations with
 * the same person. The address goes in the call — there is no {@code hafen.chat()} that would have to answer
 * for whichever session happens to be drawn.
 *
 * <p><b>No {@code :get}.</b> A channel publishes no key the client addresses: the server places a tab and
 * takes it away, and two channels may carry one name. So the doors are the two every collection has —
 * {@link LuaCollection.Source#noGet} says which, and the refusal quotes it.
 *
 * <p><b>{@code :selected()} is the distinguished member</b> (§2.2's R8), and its write arity hands the
 * character's chat to another tab — the same gesture the player makes by clicking it, focus included.
 * Client-local: nothing is sent, so it is unprotected, exactly as {@code hafen.session():current(s)} is.
 * {@code ch:send(text)} is the one write here that leaves the client, and it carries {@code chat.send}.
 *
 * <p><b>Threading.</b> Every read runs on the UI thread. The channel list is mutated by the thread that
 * applies the server's update ({@code ChatUI.add} / {@code cdestroy}), so {@link #channels} walks it under
 * that tree's own monitor and copies what it needs.
 */
final class ChatApi {
    private ChatApi() {}

    /** {@code s:chat()} — how the section is reached, and so how every one of its messages spells itself. */
    static final String CH = "session:chat()";

    /**
     * Build the chat section object for {@code (owner, user)} — <b>the channels THAT character holds</b>,
     * reached as {@code s:chat()}. Minted once per {@code (addon, session)} and hung on the interned Session
     * handle, the shape every other session-addressed section has, so {@code s:chat() == s:chat()}.
     *
     * <p>A session with no HUD yet — connecting, on the character list, gone — answers an empty collection
     * and a {@code nil} selection rather than throwing, exactly as it does before entering the world.
     */
    static LuaValue chat(final Addon owner, final String user) {
        LuaTable extra = new LuaTable();
        // selected() — the tab this character has on screen, nil before the HUD is up. selected(ch) hands it
        // to another one: the player's own click, focus and all, and nothing the server is told about.
        extra.set("selected", new VarArgFunction() {
            public Varargs invoke(Varargs a) {
                LuaValue self = a.arg1();
                LuaCollection.receiver(self, "selected");
                LuaValue want = Args.written(a, 2, CH + ":selected", "channel");
                ChatUI chat = chatui(user);
                if(want == null) {                                    // the read
                    ChatUI.Channel sel = (chat == null) ? null : chat.sel;
                    return (sel == null) ? LuaValue.NIL : LuaChannel.of(owner, sel);
                }
                LuaChannel h = LuaChannel.resolve(want);
                if(h == null)
                    throw new LuaError(CH + ":selected(channel): expected a Channel object, got "
                        + want.typename() + " — " + CH + ":find(needle) and " + CH + ":list()[n] are what"
                        + " hand you one");
                ChatUI.Channel c = LuaChannel.live(h);
                if(c == null)
                    throw new LuaError(CH + ":selected(channel): that channel is gone — ch:exists() is the"
                        + " test, and " + CH + ":list() is what this character has now");
                if(chat == null)
                    throw new LuaError(CH + ":selected(channel): this character has no chat yet — its HUD"
                        + " is not up, and " + CH + ":list() is empty until it is");
                if(c.parent != chat)
                    throw new LuaError(CH + ":selected(channel): that channel is another character's — a"
                        + " chat belongs to the login it was opened on, and " + CH + ":list() is this"
                        + " one's");
                chat.select(c);
                return self;
            }
        });
        return LuaCollection.create(CH, new LuaCollection.Source() {
            public List<LuaValue> members() {
                List<LuaValue> out = new ArrayList<LuaValue>();
                for(ChatUI.Channel c : channels(user))
                    out.add(LuaChannel.of(owner, c));
                return out;
            }

            public String needle(LuaValue member) {
                return LuaChannel.nameOf(LuaChannel.live(LuaChannel.resolve(member)));
            }

            /** A channel has a name, so a string filter is a substring test over it. */
            public boolean named() {
                return true;
            }

            public String noGet() {
                return "a channel carries no key the client addresses: " + CH + ":find(needle) is the search"
                    + " by name and " + CH + ":list()[n] takes a position";
            }
        }, extra);
    }

    /** That character's chat window, or {@code null} before its HUD exists. */
    static ChatUI chatui(String user) {
        GameUI g = AddonManager.gameui(user);
        return (g == null) ? null : g.chat;
    }

    /**
     * That character's channels, in the order the chat itself holds them — which is the order of its tabs,
     * since {@code ChatUI.add} appends to the widget list and to the selector's own list together.
     *
     * <p>Copied under that tree's monitor: the list is mutated by the thread that applies the server's
     * update, and a walk of a linked list being relinked under it is the one read that cannot be retried.
     */
    static List<ChatUI.Channel> channels(String user) {
        List<ChatUI.Channel> out = new ArrayList<ChatUI.Channel>();
        ChatUI chat = chatui(user);
        if(chat == null)
            return out;
        synchronized(LuaWidget.monitor(chat)) {
            for(Widget w = chat.child; w != null; w = w.next) {
                if(w instanceof ChatUI.Channel)
                    out.add((ChatUI.Channel)w);
            }
        }
        return out;
    }
}
