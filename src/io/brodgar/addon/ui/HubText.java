package io.brodgar.addon.ui;

import haven.Label;
import haven.Text;
import haven.Widget;

import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The words the Browse tab writes and the faces it writes them in — what the hub's own page says in the
 * same places: a size in {@code KB}, a date as {@code 14 Sep 2026}, a moment as {@code 3 days ago}, a
 * count with its plural — and the two secondary looks a card and a page share: the <b>muted</b> colour of
 * what stands beside the name, and the bolder face the name itself is set in. All of it draws through
 * {@code Label}, so a {@code label} rule of a sheet reaches every word here as it reaches the rest of the
 * client's body text.
 */
final class HubText {
    private HubText() {}

    /** What stands beside the name — the version, the author, a date — and the hub's {@code net} badge and the protected marker. */
    static final Color MUTED = new Color(176, 176, 176), NET = new Color(210, 168, 255), PROTECTED = new Color(255, 158, 100);
    /** A card's name, and a page's — sans, bold, at the two sizes the hub's page uses. */
    static final Text.Foundry NAME = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 11).aa(true);
    static final Text.Foundry TITLE = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 15).aa(true);

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    /** A muted label, unwrapped. */
    static Label muted(String text) {
        Label l = new Label(text);
        l.setcolor(MUTED);
        return l;
    }

    /**
     * A muted label wrapped at {@code w} device pixels. Built empty and written after the colour, because
     * a label renders in its constructor and {@code setcolor} re-renders on one line.
     */
    static Label muted(String text, int w) {
        Label l = new Label("", w);
        l.col = MUTED;
        l.settext(text, w);
        return l;
    }

    /** A label in {@code f}, in {@code col}. */
    static Label in(String text, Text.Foundry f, Color col) {
        Label l = new Label(text, f);
        if(col != null)
            l.setcolor(col);
        return l;
    }

    /**
     * Where a widget's baseline runs, from its top: a label's raster says where its own is, and anything
     * else — a button, a heading, a wrapped label, whose raster carries no baseline — sits on its bottom.
     * What a row aligns its members on, so a small face beside a large one stands on the same line.
     */
    static int baseline(Widget w) {
        if((w instanceof Label) && (((Label)w).text instanceof Text.Slug))
            return ((Text.Slug)((Label)w).text).baseline();
        return w.sz.y;
    }

    private static final Map<Text.Foundry, int[]> inks = new WeakHashMap<Text.Foundry, int[]>();

    /**
     * How far the letters of the face {@code l} is set in reach above and below the baseline — a capital's
     * height and a descender's depth, measured on an {@code H} and a {@code g} once per foundry. A raster
     * is taller than its letters: a face's leading and the room for its tallest ascender stand above the
     * caps, and its whole descent under the descenders, so a block centred by its boxes reads as sitting
     * off its middle. Centring by the caps and the descenders is centring by what shows, and the two are the
     * face's own, so every card centres alike whatever its words.
     */
    static int[] ink(Label l) {
        Text.Foundry f = l.f;
        int[] c;
        synchronized(inks) {
            c = inks.get(f);
        }
        if(c == null) {
            Text.Line h = f.render("H"), g = f.render("g");
            c = new int[] {Math.max(1, h.baseline() - ink(h.img, true)), Math.max(0, ink(g.img, false) - g.baseline() + 1)};
            h.dispose();
            g.dispose();
            synchronized(inks) {
                inks.put(f, c);
            }
        }
        return c;
    }

    /** How far a capital reaches above the baseline in the face {@code l} is set in. */
    static int capHeight(Label l) {
        return ink(l)[0];
    }

    /** How far a descender reaches below the baseline in the face {@code l} is set in. */
    static int descender(Label l) {
        return ink(l)[1];
    }

    /**
     * The first ({@code top}) or the last row of {@code img} that holds any ink: the raster's alpha, read a
     * row at a time. {@code 0}, or the last row, for a raster with no alpha or no ink at all.
     */
    static int ink(BufferedImage img, boolean top) {
        Raster r = img.getRaster();
        int w = img.getWidth(), h = img.getHeight(), band = r.getNumBands() - 1;
        if(img.getColorModel().hasAlpha()) {
            int[] row = new int[w];
            for(int i = 0; i < h; i++) {
                int y = top ? i : h - 1 - i;
                r.getSamples(0, y, w, 1, band, row);
                for(int x = 0; x < w; x++) {
                    if(row[x] > 16)
                        return y;
                }
            }
        }
        return top ? 0 : h - 1;
    }

    /** {@code 1 download}, {@code 12 downloads}. */
    static String count(long n, String word) {
        return n + " " + word + ((n == 1) ? "" : "s");
    }

    /** {@code 512 B}, {@code 18.4 KB}, {@code 1.2 MB} — as the hub's page writes a size. */
    static String bytes(long n) {
        if(n < 1024)
            return n + " B";
        if(n < 1024 * 1024) {
            double kb = n / 1024.0;
            return ((n < 10 * 1024) ? String.format(Locale.ROOT, "%.1f", kb) : Long.toString(Math.round(kb))) + " KB";
        }
        return String.format(Locale.ROOT, "%.1f", n / (1024.0 * 1024)) + " MB";
    }

    /** The moment an ISO-8601 stamp names, or {@code null} for anything that is not one. */
    static Instant instant(String iso) {
        if((iso == null) || iso.isEmpty())
            return null;
        try {
            return Instant.parse(iso);
        } catch(RuntimeException e) {
            return null;
        }
    }

    /** {@code 14 Sep 2026}, in this machine's zone; empty for a stamp that is not a date. */
    static String date(String iso) {
        Instant t = instant(iso);
        return (t == null) ? "" : DATE.format(t.atZone(ZoneId.systemDefault()));
    }

    /** {@code just now}, {@code 5 min ago}, {@code 3 h ago}, {@code 2 days ago}, then the date — the hub page's own scale. */
    static String ago(String iso) {
        Instant t = instant(iso);
        if(t == null)
            return "";
        long s = Math.max(0, (System.currentTimeMillis() - t.toEpochMilli()) / 1000);
        if(s < 60)
            return "just now";
        long m = s / 60;
        if(m < 60)
            return m + " min ago";
        long h = m / 60;
        if(h < 24)
            return h + " h ago";
        long d = h / 24;
        if(d < 30)
            return d + ((d == 1) ? " day ago" : " days ago");
        return date(iso);
    }

    private static final Pattern BREAK = Pattern.compile("(?i)<(?:br\\s*/?|/p|/div|/h[1-6]|/tr|/pre|/blockquote)\\s*>");
    private static final Pattern BULLET = Pattern.compile("(?i)<li(?:\\s[^>]*)?>");
    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ENTITY = Pattern.compile("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);");

    /**
     * The text of a description the hub rendered to HTML, for a label: a paragraph, a line break and a
     * heading each end a line, a list item opens one with a bullet, every other tag is dropped and
     * the common entities are read back. The client draws no HTML, and a page's long description is prose
     * whose paragraphs are the whole of its shape worth keeping.
     */
    static String plain(String html) {
        if(html == null)
            return null;
        String s = html.replace("\r\n", "\n");
        s = BULLET.matcher(s).replaceAll("\n• ");
        s = BREAK.matcher(s).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        Matcher m = ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while(m.find()) {
            String e = m.group(1), out;
            try {
                if(e.startsWith("#x") || e.startsWith("#X"))
                    out = new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                else if(e.startsWith("#"))
                    out = new String(Character.toChars(Integer.parseInt(e.substring(1))));
                else if(e.equals("amp")) out = "&";
                else if(e.equals("lt")) out = "<";
                else if(e.equals("gt")) out = ">";
                else if(e.equals("quot")) out = "\"";
                else if(e.equals("apos")) out = "'";
                else if(e.equals("nbsp")) out = " ";
                else out = m.group();
            } catch(RuntimeException x) {
                out = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(out));
        }
        m.appendTail(sb);
        s = sb.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").trim();
        return s.isEmpty() ? null : s;
    }
}
