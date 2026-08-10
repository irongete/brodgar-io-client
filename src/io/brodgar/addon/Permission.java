package io.brodgar.addon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The <b>permission catalogue</b> — one constant per protected verb the {@code hafen.*} API has (D-027;
 * D-028 for the model). Each entry carries the three things every consumer of the tier needs, so the three
 * lists that would otherwise drift are one that cannot:
 * <ul>
 *   <li>its <b>key</b> ({@code gob.click}) — what an addon declares in {@code manifest.json}'s
 *       {@code "permissions"} array, named {@code <section>.<verb>} after the section the verb LIVES on
 *       (D-215: a verb lives with what it changes), so {@code pag:use()} is {@code menugrid.use} and
 *       {@code slot:use()} is {@code actionbar.use}; {@code player.hand.use} is the one nested case;</li>
 *   <li>its <b>Lua spelling</b> ({@code gob:click}) — the verb as an author writes it, which is what the
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
 * <p>The gate is always the FIRST statement of the verb it guards (D-213), so an addon that declared nothing
 * hears about its manifest even when its arguments were wrong too — which is also what lets a suite prove a
 * grant without acting on the world: reaching the argument refusal IS the grant.
 */
public enum Permission {
    PLAYER_MOVE      ("player.move",        "hafen.player():move",            "walk your character to a place"),
    PLAYER_HAND_USE  ("player.hand.use",    "hafen.player():hand():use",      "use whatever it is holding on things"),
    GOB_CLICK        ("gob.click",          "gob:click",                      "click objects in the world"),
    ITEM_USE         ("item.use",           "item:use",                       "use items"),
    ITEM_TAKE        ("item.take",          "item:take",                      "pick items up onto the cursor"),
    ITEM_DROP        ("item.drop",          "item:drop",                      "drop items"),
    ITEM_TRANSFER    ("item.transfer",      "item:transfer",                  "move items between containers"),
    WORLD_PLACE      ("world.place",        "hafen.world():place",            "place buildings and objects"),
    WORLD_SELECT     ("world.select",       "hafen.world():select",           "select an area of the ground"),
    MENUGRID_USE     ("menugrid.use",       "pag:use",                        "invoke entries of the action menu"),
    FLOWERMENU_SELECT("flowermenu.select",  "hafen.flowermenu():select",      "choose from the radial menu"),
    FLOWERMENU_CANCEL("flowermenu.cancel",  "hafen.flowermenu():cancel",      "dismiss the radial menu"),
    CRAFT_MAKE       ("craft.make",         "hafen.craft():current():make",   "press the Craft button"),
    ACTIONBAR_USE    ("actionbar.use",      "slot:use",                       "press your action-bar buttons"),
    ACTIONBAR_RES    ("actionbar.res",      "slot:res",                       "change what your action-bar buttons hold"),
    KIN_ADD          ("kin.add",            "hafen.kin():add",                "add someone to your kin list"),
    KIN_RENAME       ("kin.rename",         "kin:rename",                     "rename someone on your kin list"),
    KIN_GROUP        ("kin.group",          "kin:group",                      "change someone's kin group"),
    KIN_END          ("kin.endKin",         "kin:endKin",                     "end kinship with someone"),
    KIN_FORGET       ("kin.forget",         "kin:forget",                     "forget someone from your kin list"),
    SPEED_CURRENT    ("speed.current",      "hafen.speed():current",          "change your movement speed"),
    WIDGET_SEND      ("widget.send",        "widget:send",                    "send any message the client itself could send");

    /** The manifest key an addon declares to be granted this verb ({@code item.transfer}). */
    public final String key;
    /** The verb as it is written in Lua ({@code item:transfer}) — the refusal opens with it. */
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
