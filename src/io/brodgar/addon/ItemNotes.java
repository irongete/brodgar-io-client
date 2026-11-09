package io.brodgar.addon;

import haven.Fonts;
import haven.ItemInfo;
import haven.RichText;
import haven.Tex;
import haven.UI;
import haven.WItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/** Per-addon extra lines drawn inside the native long item tooltip. */
public final class ItemNotes {
    private static final Map<ItemInfo.Owner, Entry> entries =
        new WeakHashMap<ItemInfo.Owner, Entry>();
    private static final List<Tex> retired = new ArrayList<Tex>();

    private static final class Note {
        final String value;
        final String markup;

        Note(String value, String markup) {
            this.value = value;
            this.markup = markup;
        }
    }

    private static final class Entry {
        final LinkedHashMap<Addon, Note> notes = new LinkedHashMap<Addon, Note>();
        int fontgen = -1;
        Tex tex;

        void invalidate() {
            fontgen = -1;
            if(tex != null) {
                retired.add(tex);
                tex = null;
            }
        }
    }

    private ItemNotes() {}

    /** Called only at the start of the UI tooltip draw, before any note texture is borrowed this frame. */
    public static synchronized void frame() {
        for(int i = 0; i < retired.size(); i++)
            retired.get(i).dispose();
        retired.clear();
    }

    public static synchronized String get(Addon addon, ItemInfo.Owner item) {
        Entry e = entries.get(item);
        Note n = (e == null) ? null : e.notes.get(addon);
        return (n == null) ? null : n.value;
    }

    public static synchronized void set(Addon addon, ItemInfo.Owner item, String value) {
        Entry e = entries.get(item);
        Note old = (e == null) ? null : e.notes.get(addon);
        if(value == null || value.isEmpty()) {
            if(old == null)
                return;
            e.notes.remove(addon);
            e.invalidate();
            if(e.notes.isEmpty())
                entries.remove(item);
            return;
        }
        if(old != null && old.value.equals(value))
            return;
        String markup = LuaWidget.richTip(value) ? value : RichText.Parser.quote(value);
        if(e == null) {
            e = new Entry();
            entries.put(item, e);
        }
        e.notes.put(addon, new Note(value, markup));
        e.invalidate();
    }

    public static synchronized void clear(Addon addon) {
        for(java.util.Iterator<Map.Entry<ItemInfo.Owner, Entry>> it = entries.entrySet().iterator(); it.hasNext();) {
            Entry e = it.next().getValue();
            if(e.notes.remove(addon) != null) {
                e.invalidate();
                if(e.notes.isEmpty())
                    it.remove();
            }
        }
    }

    public static synchronized void drop(Addon addon, ItemInfo.SpriteOwner item) {
        if(item == null)
            return;
        Entry e = entries.get(item);
        if(e == null || e.notes.remove(addon) == null)
            return;
        e.invalidate();
        if(e.notes.isEmpty())
            entries.remove(item);
    }

    /** Extra tooltip texture, or null when this is not a native long item tip with notes. */
    public static synchronized Tex extra(Object tooltip) {
        if(entries.isEmpty() || !(tooltip instanceof ItemInfo.InfoTip) || (tooltip instanceof WItem.ShortTip))
            return null;
        List<ItemInfo> info = ((ItemInfo.InfoTip)tooltip).info();
        if(info.isEmpty())
            return null;
        ItemInfo.Owner owner = info.get(0).owner;
        if(owner == null)
            return null;
        Entry e = entries.get(owner);
        if(e == null || e.notes.isEmpty())
            return null;
        int gen = Fonts.gen();
        if(e.tex != null && e.fontgen == gen)
            return e.tex;
        if(e.tex != null) {
            e.invalidate();
        }
        StringBuilder text = new StringBuilder();
        for(Note n : e.notes.values()) {
            if(text.length() > 0)
                text.append('\n');
            text.append(n.markup);
        }
        String joined = text.toString();
        Fonts.enter("tooltip");
        AddonText.enter();
        try {
            e.tex = RichText.render(joined, UI.scale(300)).tex();
        } finally {
            AddonText.exit();
            Fonts.exit();
        }
        e.fontgen = gen;
        return e.tex;
    }
}
