package io.brodgar.addon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The <b>permission catalogue</b> — one constant per protected verb the {@code hafen.*} API has (D-027;
 * D-028 for the model). Each entry carries the three things every consumer of the tier needs, so the three
 * lists that would otherwise drift are one that cannot:
 * <ul>
 *   <li>its <b>key</b> ({@code item.transfer}) — what an addon declares in {@code manifest.json}'s
 *       {@code "permissions"} array, named {@code <section>.<verb>} after the section the verb LIVES on
 *       (D-215: a verb lives with what it changes), so {@code pag:use()} is {@code menugrid.use} and
 *       {@code slot:use()} is {@code actionbar.use}; {@code player.hand.use} is the one nested case.
 *       {@code gob.click} is the one key whose section is not where its verb sits — the action is clicking
 *       an object, and it kept that name when the verb moved onto the world of the session that clicks;</li>
 *   <li>its <b>Lua spelling</b> ({@code item:transfer}) — the verb as an author writes it, which is what the
 *       gate's refusal opens with;</li>
 *   <li>its <b>plain-language line</b> (<i>"click objects in the world"</i>) — what the enable-time consent
 *       dialog says this key lets the addon do, in the user's words rather than the API's.</li>
 * </ul>
 *
 * <p>The catalogue is the contract: {@link PermissionSet} validates a manifest against it (an unknown key
 * fails to load), {@link AddonManager#requirePermission} builds its refusal from it, {@link Manifest#internal}
 * enumerates it for the trusted {@code :lua} REPL owner, and the consent dialog renders it. Adding a
 * protected verb is one entry here plus one gate call — nothing else has a list to update.
 *
 * <p><b>It is the WHOLE catalogue</b> (093, A-098): nothing is gated by a second mechanism beside it.
 * {@code http.get} and {@code http.post} are keys like any other, and an allowlist of hosts is the
 * <b>argument</b> of the key — the shape {@code player.hand.use} has for a nested one: the allowlist decides
 * <i>where</i>, the catalogue decides <i>whether</i>, and the consent dialog says both in one line.
 *
 * <p><b>A key gates what a verb DOES, not only what it tells the server</b> (093, A-096/A-097). Two entries
 * here reach nothing outside the client and are protected all the same: {@code map.marker} deletes a pin the
 * player made, which no server can restore, and {@code client.settings} rewrites the configuration and every
 * hotkey they have. The tier's own definition is <i>an action the player could have performed</i>, and both
 * are plainly that — every one of them is a control the user has in front of them.
 *
 * <p>The gate is always the FIRST statement of the verb it guards (D-213), so an addon that declared nothing
 * hears about its manifest even when its arguments were wrong too — which is also what lets a suite prove a
 * grant without acting on the world: reaching the argument refusal IS the grant.
 *
 * <p><b>The widest key here is {@code console.run}</b>, and it is wide for a reason no other entry is:
 * the console is the client's own command line, so one key covers every command the client dispatches
 * rather than one action. {@code :lua} is among them, and {@code :lua} evaluates against the full standard
 * library <i>outside</i> the addon sandbox — so an addon granted this can do anything the client's own
 * REPL can. Its line says so in the user's words, which is the whole of what a consent dialog can do about
 * a key whose reach is the vocabulary of another surface.
 *
 * <p><b>A key names the ACTION, not the target</b> (077.2). A verb addressed at a character the player is not
 * looking at needs no second key: the definition of protection is <i>an action the player could have
 * performed</i>, and the player could have tabbed to that character and performed it. A grant per session
 * would mean an addon the user allowed to add kin cannot add kin on an alt, a distinction the user never
 * drew — every one of those characters is theirs. So the {@code line} each entry carries is written to be
 * read across every login the client holds — 077.3 says it of the four that act on a character's bar, its
 * speed, its open recipe and its action menu, exactly as 077.2 said it of the kin roster, and 077.4 of the
 * radial menu a character has open, which is a widget in that character's tree rather than the screen's.
 * 081.2 says it of the login itself: {@code session.close} logs a character out, and the character it is
 * pointed at is the addon's to choose, exactly as the player could have typed {@code :session drop} at any
 * of them.
 */
public enum Permission {
    PLAYER_MOVE      ("player.move",        "session:player():move",          "walk your character to a place"),
    PLAYER_HAND_USE  ("player.hand.use",    "session:player():hand():use",    "use whatever it is holding on things"),
    GOB_CLICK        ("gob.click",          "session:world():click",          "click objects in the world"),
    ITEM_USE         ("item.use",           "item:use",                       "use items"),
    ITEM_TAKE        ("item.take",          "item:take",                      "pick items up onto the cursor"),
    ITEM_DROP        ("item.drop",          "item:drop",                      "drop items"),
    ITEM_TRANSFER    ("item.transfer",      "item:transfer",                  "move items between containers"),
    WORLD_PLACE      ("world.place",        "session:world():place",          "place buildings and objects"),
    WORLD_SELECT     ("world.select",       "session:world():select",         "select an area of the ground"),
    MAP_MARKER       ("map.marker",         "hafen.map():marker():add",       "add and delete pins on your map,"
                                                                              + " and recolour them"),
    MENUGRID_USE     ("menugrid.use",       "pag:use",                        "invoke entries of the action menu,"
                                                                              + " on any of your characters"),
    FLOWERMENU_SELECT("flowermenu.select",  "session:flowermenu():select",    "choose from the radial menu of any"
                                                                              + " of your characters"),
    FLOWERMENU_CANCEL("flowermenu.cancel",  "session:flowermenu():cancel",    "dismiss the radial menu of any of"
                                                                              + " your characters"),
    CRAFT_MAKE       ("craft.make",         "session:craft():make",           "press the Craft button, on any of"
                                                                              + " your characters"),
    ACTIONBAR_USE    ("actionbar.use",      "slot:use",                       "press the action-bar buttons of any"
                                                                              + " of your characters"),
    ACTIONBAR_RES    ("actionbar.res",      "slot:res",                       "assign one of the game's own actions"
                                                                              + " to any of your characters'"
                                                                              + " action-bar buttons"),
    ACTIONBAR_CLEAR  ("actionbar.clear",    "slot:clear",                     "empty any of your characters'"
                                                                              + " action-bar buttons"),
    KIN_ADD          ("kin.add",            "session:kin():add",              "add someone to any of your characters' kin lists"),
    KIN_RENAME       ("kin.rename",         "kin:rename",                     "rename someone on any of your characters' kin lists"),
    KIN_GROUP        ("kin.group",          "kin:group",                      "change someone's kin group, on any of your characters"),
    KIN_END          ("kin.end",            "kin:endKin",                     "end kinship with someone, on any of your characters"),
    KIN_FORGET       ("kin.forget",         "kin:forget",                     "forget someone from any of your characters' kin lists"),
    CHAT_SEND        ("chat.send",           "channel:send",                   "say a line in the chat, as any of your"
                                                                              + " characters"),
    SPEED_SET        ("speed.set",          "session:speed():set",            "change the movement speed of any of"
                                                                              + " your characters"),
    SESSION_CLOSE    ("session.close",      "session:close",                  "log out any of your characters"),
    WIDGET_SEND      ("widget.send",        "widget:send",                    "send any message the client itself could send"),
    WIDGET_VALUE     ("widget.value",       "widget:value",                   "flip the client's own controls — a box it ticks,"
                                                                              + " a field it types into — which the server sees"),
    UI_RESEND        ("ui.resend",          "ev:resend",                      "re-run a button you pressed, so the client"
                                                                              + " sends what that press sends"),
    UI_FOCUS         ("ui.focus",           "s:chat():selected",              "move the keyboard into a chat entry line,"
                                                                              + " so what you type next goes there"),
    VIRTUAL_CLICK    ("virtual.click",      "hafen.virtual():click",          "click the controls it has standing in the"
                                                                              + " world, which act as if you had clicked them"),
    CLIENT_SETTINGS  ("client.settings",    "hafen.client():options()",       "change your client settings and"
                                                                              + " hotkeys"),
    CONSOLE_RUN      ("console.run",        "s:console():run",                "run any of the client's console"
                                                                              + " commands, on any of your"
                                                                              + " characters, including ones that"
                                                                              + " run code outside the addon"
                                                                              + " sandbox"),
    // audit2 B14 (pm-06): BOTH NAME request:send, which is the one door either key gates. 095 reshaped
    // HTTP into a builder -- nothing leaves the client until :send() -- and the catalogue kept the old
    // spellings, so the permissions guide named a verb that is gated by nothing while the refusal an author
    // actually reads named the right one. The key is still per method: which of the two is asked for is
    // decided by the request's own method at the send.
    HTTP_GET         ("http.get",           "request:send",                   "fetch data from the servers it lists"),
    HTTP_POST        ("http.post",          "request:send",                   "send data to the servers it lists");

    /** The manifest key an addon declares to be granted this verb ({@code item.transfer}). */
    public final String key;
    /**
     * The verb as it is written in Lua ({@code item:transfer}) — the refusal opens with it, and the
     * permissions guide lists it. Where one key gates <b>several</b> doors onto one action, this names the
     * canonical one and each other site passes its own spelling to
     * {@link AddonManager#requirePermission}.
     */
    public final String lua;
    /** What this key lets the addon do, in the user's words — the consent dialog's line. */
    public final String line;

    Permission(String key, String lua, String line) {
        this.key = key;
        this.lua = lua;
        this.line = line;
    }

    private static final Map<String, Permission> BYKEY = new LinkedHashMap<String, Permission>();
    static {
        for(Permission p : values())
            BYKEY.put(p.key, p);
    }

    /** The catalogue entry with this exact key, or {@code null} if there is none. Case-sensitive. */
    public static Permission byKey(String key) {
        return (key == null) ? null : BYKEY.get(key);
    }

    /**
     * Every key, in catalogue order, comma-separated — what a refusal for an unknown declaration lists so
     * the author can see the whole vocabulary without opening the docs.
     */
    public static String keyList() {
        StringBuilder sb = new StringBuilder();
        for(Permission p : values()) {
            if(sb.length() > 0)
                sb.append(", ");
            sb.append(p.key);
        }
        return sb.toString();
    }

    /** The group prefix a {@code <prefix>.*} declaration would use to reach this key ({@code item}). */
    public String group() {
        int i = key.lastIndexOf('.');
        return (i < 0) ? key : key.substring(0, i);
    }
}
