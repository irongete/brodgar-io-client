/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.util.*;
import java.text.*;
import java.util.regex.*;
import haven.iosys.tk.*;
import java.io.IOException;
import java.net.URI;
import java.awt.Color;
import java.awt.Font;
import java.awt.font.TextAttribute;
import java.awt.font.TextHitInfo;
import java.awt.image.BufferedImage;
import java.text.AttributedCharacterIterator.Attribute;

public class ChatUI extends Widget {
    public static final RichText.Foundry fnd = new RichText.Foundry(new ChatParser(TextAttribute.FONT, Text.dfont.deriveFont(UI.scale(12f)), TextAttribute.FOREGROUND, Color.BLACK)).aa(true);
    public static final Text.Foundry qfnd = new Text.Foundry(Text.dfont, 12, new java.awt.Color(192, 255, 192));
    /* addon: "chat" font scope (F3d, D-043). `fnd`/`qfnd` above stay STOCK; the chat window renders through the
     * provider-resolved twins below, rebuilt lazily whenever Fonts.gen() moves (and each cached line/message is
     * dropped on the same check, so a restyle is live).
     *
     * The message foundry is a RichText.Foundry built over a ChatParser (it turns URLs into clickable parts), and
     * RichText.Foundry.derive() would drop that subclass -- so instead of deriving we ask the provider for the
     * resolved SCALARS (Fonts.style) and rebuild a fresh ChatParser foundry with the same recipe stock uses,
     * keeping TextAttribute.FONT (what stock itself passes) so nothing about the chat markup changes. `fndstock` is
     * the font stock bakes in, and exists so the provider can inherit the stock SIZE when the override carries
     * none; `fndcol` likewise for the default (local-message) colour. */
    private static final Font fndstock = Text.dfont.deriveFont(UI.scale(12f));
    private static final Color fndcol = Color.BLACK;
    private static Text.Foundry bqfnd;
    private static int fontgen = -1;
    /* addon: (065.16) one message foundry per RESOLVED style rather than one for the whole window. A chat line
     * now resolves at one of six keys -- "chat" itself, or one of the five that REFINE it ("chat.system",
     * "chat.mine", "chat.private", "chat.party", "chat.urgent") -- and each of those cascades into "chat", so a
     * client with a rule on "chat" alone resolves them all to the very SAME interned Spec and they share one
     * foundry between them. That is why this is keyed on the style's identity and not on the scope's name: the
     * common case costs exactly what it cost before, and a theme that splits the kinds pays one foundry per kind
     * it actually split. Dropped whole on a gen move, like every other cached raster here. */
    private static final java.util.Map<Fonts.Style, RichText.Foundry> byst =
	new java.util.IdentityHashMap<Fonts.Style, RichText.Foundry>();
    private static void checkfont() {
	int g = Fonts.gen();
	if(fontgen != g) {
	    synchronized(byst) {byst.clear();}
	    bqfnd = Fonts.foundry("chat", qfnd);
	    fontgen = g;
	}
    }
    /**
     * addon: the message foundry for {@code scope} — an override, else stock {@link #fnd}. Every caller passes
     * the key its own line resolves at ({@link Channel.Message#scope}), which on any client not themed per kind
     * is {@code "chat"} or something that cascades straight into it.
     */
    public static RichText.Foundry fnd(String scope) {
	checkfont();
	Fonts.Style st = Fonts.style(scope);
	if(st == null)
	    return(fnd);
	synchronized(byst) {
	    RichText.Foundry f = byst.get(st);
	    if(f == null) {
		byst.put(st, f = new RichText.Foundry(new ChatParser(TextAttribute.FONT, st.font(fndstock),
								     TextAttribute.FOREGROUND, st.color(fndcol)))
			 .aa(st.aa(fnd.aa)));
	    }
	    return(f);
	}
    }
    /**
     * addon: (033.2) the colour a chat line should be rendered in, given the colour the SITE picked. Almost every
     * line passes its own {@code FOREGROUND} — a speaker's kin colour, a system message's — which as a per-render
     * attribute outranks the foundry's default, so without this a {@code ["chat"] = {color=…}} rule would only
     * ever reach the handful of lines that pass none. A sheet rule is what the surface looks like, so it wins;
     * with no rule this hands the site's own colour straight back and nothing changes.
     *
     * <p>addon: (065.16) asked at the line's OWN key, so a rule on one kind leaves the other kinds reading as
     * they did. With no rule on that kind the cascade hands back {@code "chat"}'s, which is what a client themed
     * before the kinds existed still resolves.
     */
    private static Color fndcol(String scope, Color site) {
	/* addon: (065.17) no colour is declared here, and the reason is what these keys are: a kind's colour
	 * arrives per LINE -- a notice's white or an error's dark red at the same key, a private message's two
	 * colours for received and sent, a party line's the member's own -- so not one of them is a constant
	 * the catalogue could hand back and load again. The two keys whose colour IS a value declare it as
	 * the sequence it is, in checkfont() above. */
	Fonts.Style st = Fonts.style(scope);
	Color c = (st == null) ? null : st.color(null);
	return((c != null) ? c : site);
    }
    /**
     * addon: (065.16) the colour urgency level {@code urg} is drawn in — the glow around the chat toggle button
     * and the blur behind an unread channel's tab — or {@code stock}'s own where no rule names it.
     *
     * <p>A {@code chat.urgent} rule names the whole SEQUENCE rather than a colour, one entry per level, for the
     * reason the per-speaker hue does: three levels flattened to one colour stop saying <i>how</i> urgent, which
     * is the only thing the indicator is for. Level 0 is "nothing unread" rather than a level, so it keeps its
     * site's own value — a null glow here, the resting tab colour there — and a rule never reaches it.
     */
    public static Color urgcol(int urg, Color[] stock) {
	if(urg >= 1) {
	    Fonts.Sequence sq = Fonts.sequence("chat.urgent");
	    if(sq != null)
		return(sq.color(urg - 1));
	}
	return(stock[urg]);
    }
    /** addon: the current quick-line foundry for the {@code "chat"} scope (an override, else stock {@link #qfnd}). */
    public static Text.Foundry qfnd() {checkfont(); return(bqfnd);}
    public static final int selw = UI.scale(130);
    public static final Coord marg = UI.scale(new Coord(9, 9));
    public static final Color[] urgcols = new Color[] {
	null,
	new Color(0, 128, 255),
	new Color(255, 128, 0),
	new Color(255, 0, 0),
    };
    /* addon: (065.17) what the chat is made of: the face its lines are set in, and the two colours it WALKS
     * rather than holds -- the hue a channel mints per speaker, said as the walk it is, and the urgency
     * triple, said as the colours it cycles. Both are the sequence itself, because that is the only shape
     * either has: flattening one to a colour is what neither can survive.
     *   Declared beside the three constants rather than where a line is rendered, because that is what they
     * are: none of them is drawn FROM, so a client whose chat has had nothing to say yet would otherwise
     * carry no answer for colours it is already prepared to hand out. */
    static {
	Fonts.stock("chat", "font", qfnd);
	Fonts.stock("chat.speaker", "color",
		    Double.valueOf(Math.sqrt(2) % 1.0), Double.valueOf(0.5), Double.valueOf(1.0));
	Fonts.stock("chat.urgent", "color", urgcols[1], urgcols[2], urgcols[3]);
    }
    public Channel sel = null;
    public int urgency = 0;
    private final Selector chansel;
    private Coord base = Coord.z;
    private QuickLine qline = null;
    private final LinkedList<Notification> notifs = new LinkedList<Notification>();
    private UI.Grab qgrab;

    public ChatUI() {
	super(Coord.of(0, UI.scale(Math.max(minh, Utils.getprefi("chatsize", minh)))));
	chansel = add(new Selector(new Coord(selw, sz.y - marg.y)), marg);
	setfocusctl(true);
	setcanfocus(true);
    }

    protected void added() {
	base = this.c;
	resize(this.sz);
    }

    public static class ChatAttribute extends Attribute {
	private ChatAttribute(String name) {
	    super(name);
	}

	public static final Attribute HYPERLINK = new ChatAttribute("hyperlink");
    }

    public static class ChatParser extends RichText.Parser {
	public static final Pattern urlpat = Pattern.compile("\\b((https?://)|(www\\.[a-z0-9_.-]+\\.[a-z0-9_.-]+))[a-z0-9/_.~#%+?&:*=-]*", Pattern.CASE_INSENSITIVE);
	public static final Map<? extends Attribute, ?> urlstyle = RichText.fillattrs(TextAttribute.FOREGROUND, new Color(64, 64, 255),
										      TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);

	public ChatParser(Object... args) {
	    super(args);
	}

	protected RichText.Part text(PState s, String text, Map<? extends Attribute, ?> attrs) throws IOException {
	    RichText.Part ret = null;
	    int p = 0;
	    while(true) {
		Matcher m = urlpat.matcher(text);
		if(!m.find(p))
		    break;
		URI uri;
		try {
		    String su = text.substring(m.start(), m.end());
		    if(su.indexOf(':') < 0)
			su = "http://" + su;
		    uri = Utils.uri(su);
		} catch(IllegalArgumentException e) {
		    p = m.end();
		    continue;
		}
		RichText.Part lead = new RichText.TextPart(text.substring(p, m.start()), attrs);
		if(ret == null) ret = lead; else ret.append(lead);
		Map<Attribute, Object> na = new HashMap<Attribute, Object>(attrs);
		na.putAll(urlstyle);
		na.put(ChatAttribute.HYPERLINK, uri);
		ret.append(new RichText.TextPart(text.substring(m.start(), m.end()), na));
		p = m.end();
	    }
	    if(ret == null)
		ret = new RichText.TextPart(text, attrs);
	    else
		ret.append(new RichText.TextPart(text.substring(p), attrs));
	    return(ret);
	}
    }

    public static abstract class Channel extends Widget {
	public final List<RenderedMessage> rmsgs = new ArrayList<>();
	public int urgency = 0;
	private final Scrollbar sb;
	private final IButton cb;
	private double dy;

	public static abstract class Message {
	    public final double time = Utils.ntime();
	    /* addon: (065.16) the site key this line's face and colour resolve at. Stamped by append() below,
	     * which is the one funnel every message goes through and the only place that knows both the message
	     * and the channel it landed in -- a System line and a private one are the same SimpleMessage class. */
	    private String scope = null;

	    /** addon: (065.16) the key this KIND of line names, or null to take the channel's. */
	    protected String kind() {return(null);}
	    /** addon: (065.16) the site key this line resolves at — never null once it has been appended. */
	    public String scope() {return((scope == null) ? "chat" : scope);}

	    /* addon: (110.3) THE LINE'S OWN DATA, for a reader outside this window. Three accessors here
	     * rather than a subclass ladder in the reader: the subclasses that carry a line are not
	     * enumerable from outside, and a new one added upstream would read as a line with no text,
	     * silently, wherever such a ladder ran out. Null is "this kind of line carries none", which is a
	     * fact about the kind rather than a gap -- an unadorned SimpleMessage has no sender at all. */
	    /** addon: (110.3) the line as it was written, markup and all, or null where the kind holds none. */
	    public String text() {return(null);}
	    /** addon: (110.3) the colour this line carries of itself, before any override, or null for none. */
	    public Color color() {return(null);}
	    /** addon: (110.3) the buddy id of whoever said it, or null where the line names no sender. */
	    public Integer speaker() {return(null);}

	    public abstract Indir<Text> render(int w);
	    public boolean valid(Indir<Text> prev) {
		return(true);
	    }
	    public boolean mousedown(Channel chan, CharPos pos, Coord c, int btn) {return(false);}
	    public boolean mouseup(Channel chan, CharPos pos, Coord c, int btn) {return(false);}
	    public boolean clicked(Channel chan, CharPos pos, Coord c, int btn) {return(false);}
	}

	private RenderedMessage soldest = null, snewest = null;
	public class RenderedMessage {
	    public final Message msg;
	    public final int idx;
	    private Indir<Text> data;
	    private Text text;
	    RenderedMessage snext = null, sprev = null;
	    double lseen = 0;
	    int w, y;

	    public RenderedMessage(Message msg, int idx, int iw) {
		this.msg = msg;
		this.idx = idx;
		this.w = iw;
	    }

	    public Indir<Text> data() {
		if(data == null)
		    data = msg.render(w);
		return(data);
	    }

	    private void slink() {
		synchronized(Channel.this) {
		    sprev = null;
		    snext = snewest;
		    if(soldest == null)
			soldest = this;
		    if(snewest != null)
			snewest.sprev = this;
		    snewest = this;
		}
	    }

	    private void sunlink() {
		synchronized(Channel.this) {
		    if(sprev != null)
			sprev.snext = snext;
		    if(snext != null)
			snext.sprev = sprev;
		    if(snewest == this)
			snewest = snext;
		    if(soldest == this)
			soldest = sprev;
		}
	    }

	    public Text text() {
		lseen = ui.lasttick;
		if(text == null) {
		    /* addon: (102.2) the ONE place a line becomes a raster, whichever Message subclass built it and
		     * whenever the Indir is finally pulled -- so this is where the line's own key is declared and an
		     * addon's catalogue reaches a System notice, a private message or a party line by name. The key
		     * is the line's, not the channel's: `scope()` is the kind's when the kind names one (065.16).
		     * The Message and its text are untouched, so nothing that reads the chat back changes. */
		    Fonts.enter(msg.scope());
		    try {
			text = data().get();
		    } finally {
			Fonts.exit();
		    }
		} else {
		    sunlink();
		}
		slink();
		return(text);
	    }

	    private Coord sz = null;
	    public int h() {
		if(sz == null)
		    sz = text().sz();
		return(sz.y);
	    }

	    public void clear() {
		if(text != null) {
		    text.dispose();
		    text = null;
		    sunlink();
		}
	    }

	    public void invalidate() {
		clear();
		if(data != null) {
		    if(data instanceof Disposable)
			((Disposable)data).dispose();
		    data = null;
		}
		sz = null;
	    }

	    public void resize(int w) {
		if(this.w != w) {
		    this.w = w;
		    invalidate();
		}
	    }

	    private int fontgen = Fonts.gen();   // addon: Fonts.gen() at the last render (F3d)
	    public boolean update() {
		/* addon: a "chat" font override moved -> drop this message's rendered line so the next draw
		 * re-renders it through the provider. Only VISIBLE messages are update()d, so a restyle costs
		 * nothing for the scrollback until it is scrolled into view. The `true` return makes the channel
		 * re-run updyseq(), since the new font may change the message's height. */
		if(fontgen != Fonts.gen()) {
		    fontgen = Fonts.gen();
		    invalidate();
		    return(true);
		}
		if((data == null) || msg.valid(data))
		    return(false);
		invalidate();
		return(true);
	    }
	}

	private void trimunseen() {
	    double now = ui.lasttick;
	    while(true) {
		RenderedMessage rm = soldest;
		if((rm == null) || (now - rm.lseen < 10))
		    break;
		rm.clear();
	    }
	}

	public static class SimpleMessage extends Message {
	    public final String text;
	    public final Color col;

	    public SimpleMessage(String text, Color col) {
		this.text = text;
		this.col = col;
	    }

	    public String text() {return(text);}   // addon: (110.3)
	    public Color color() {return(col);}    // addon: (110.3)

	    public Indir<Text> render(int w) {
		if(col == null)
		    return(() -> fnd(scope()).render(RichText.Parser.quote(text), w));   // addon: this line's own scope (F3d, 065.16)
		else
		    return(() -> fnd(scope()).render(RichText.Parser.quote(text), w, TextAttribute.FOREGROUND, fndcol(scope(), col)));   // addon: (F3d) + the sheet's colour (033.2, 065.16)
	    }
	}

	public Channel(boolean closable) {
	    sb = add(new Scrollbar(0, 0, 0));
	    if(closable)
		cb = add(new IButton("gfx/hud/chat-close", "", "-d", "-h"));
	    else
		cb = null;
	}

	/** addon: (065.16) the key the lines of this channel resolve at, unless a line names its own kind. */
	public String chanscope() {return("chat");}

	public void append(Message msg, int urgency) {
	    if(msg.scope == null)   // addon: (065.16) BEFORE the first render below, which reads it
		msg.scope = (msg.kind() != null) ? msg.kind() : chanscope();
	    int idx;   // addon: (110.3) the line's own place, read out under the lock that assigned it
	    synchronized(rmsgs) {
		RenderedMessage rm = new RenderedMessage(msg, rmsgs.size(), iw());
		if(rmsgs.isEmpty()) {
		    rm.y = 0;
		} else {
		    RenderedMessage lm = rmsgs.get(rmsgs.size() - 1);
		    rm.y = lm.y + lm.h();
		}
		rmsgs.add(rm);
		idx = rm.idx;   // addon: (110.3)
		boolean b = sb.val >= sb.max;
		sb.max = rm.y + rm.h() - ih();
		if(b)
		    sb.val = sb.max;
	    }
	    /* addon: (110.3) a line landed in this channel. THE one funnel -- every uimsg shape, the client's
	     * own notices and the console's output all delegate here -- and it runs on the thread that applies
	     * the server's update, so this only enqueues and the tick fires MessageAdded. Before the notify
	     * below, which is the popup: the fact is the line, not what the window did about it. */
	    io.brodgar.addon.AddonManager.chatMessageAdded(this, idx);
	    getparent(ChatUI.class).notify(this, msg, urgency);
	    updurgency(Math.max(this.urgency, urgency));
	}

	public void append(Message msg) {
	    append(msg, 0);
	}

	public void append(String line, Color col) {
	    append(new SimpleMessage(line, col));
	}

	public int iw() {
	    return(sz.x - sb.sz.x);
	}

	public int ih() {
	    return(sz.y);
	}

	public void updurgency(int urg) {
	    if(urgency != urg) {
		urgency = urg;
		int mu = 0;
		ChatUI p = getparent(ChatUI.class);
		for(Selector.DarkChannel ch : p.chansel.chls)
		    mu = Math.max(mu, ch.chan.urgency);
		p.urgency = mu;
	    }
	}

	public int messageat(int y, boolean nearest) {
	    synchronized(rmsgs) {
		int t = 0, b = rmsgs.size();
		while(b > t) {
		    int c = (t + b) / 2;
		    RenderedMessage rm = rmsgs.get(c);
		    if(rm.y > y) {
			b = c;
		    } else if(rm.y + rm.h() < y) {
			t = c + 1;
		    } else {
			return(c);
		    }
		}
		return(nearest ? t : -1);
	    }
	}

	public RenderedMessage messageat(Coord c, Coord hc) {
	    synchronized(rmsgs) {
		int mi = messageat(sb.val + c.y, false);
		if(mi < 0)
		    return(null);
		RenderedMessage rm = rmsgs.get(mi);
		if(hc != null) {
		    hc.x = c.x;
		    hc.y = sb.val + c.y - rm.y;
		}
		return(rm);
	    }
	}

	private void updyseq(int mi) {
	    synchronized(rmsgs) {
		mi = Math.min(mi, rmsgs.size() - 1);
		RenderedMessage lm = rmsgs.get(mi++);
		int y = lm.y + lm.h();
		while(mi < rmsgs.size()) {
		    RenderedMessage rm = rmsgs.get(mi++);
		    rm.y = y;
		    y += rm.h();
		}
		boolean b = sb.val >= sb.max;
		sb.max = y - ih();
		if(b)
		    sb.val = sb.max;
		if(sb.val > sb.max)
		    sb.val = sb.max;
	    }
	}

	/* addon: (106) what a channel's wash is made of: one flat tint over the chat's own field, and nothing
	 * else. No `border` is declared because a channel draws none -- a theme writing one ADDS a frame.
	 *   It is "chat.log" rather than "chat.frame": the FRAME is the decoration ChatUI itself paints
	 * round the lot -- corners, runs and two pinned ornaments over a tiled field -- which is the analogue
	 * of window.frame. This is the dimming laid inside it, behind one channel's lines. */
	private static final Color washc = new Color(0, 0, 0, 128);
	private static void stockwash() {
	    Fonts.stock("chat.log", "bg", Fonts.piece(washc));
	}

	public void draw(GOut g) {
	    /* addon: (106) the wash this channel lays behind its lines is the "chat.log" rule's -- the
	     * dimming inside the decoration "chat.frame" dresses, and a PART of "chat" rather than a kind of
	     * it, so it takes nothing from it (Fonts.SCOPE_PARENT is declared, never derived from the dot).
	     * Null on a stock client, and then the wash below is the rectangle it always was. */
	    stockwash();
	    Fonts.Chrome fld = Fonts.chrome("chat.log", this);
	    if(fld == null) {
		g.chcolor(0, 0, 0, 128);
		g.frect(Coord.z, sz);
		g.chcolor();
	    } else {
		fld.draw(g, Coord.z, sz);
	    }
	    int sy = (int)Math.round(dy), h = ih(), w = iw();
	    boolean sel = false;
	    synchronized(rmsgs) {
		int smi = messageat(sy, true);
		if(smi < rmsgs.size()) {
		    int y = rmsgs.get(smi).y;
		    boolean upd = false;
		    for(int mi = smi; mi < rmsgs.size(); mi++) {
			RenderedMessage rm = rmsgs.get(mi);
			if(y > sy + h)
			    break;
			if(rm.w != w) {
			    rm.resize(w);
			    upd = true;
			}
			if(rm.update())
			    upd = true;
			if((selstart != null) && (selend != null)) {
			    if((rm.idx >= selstart.rm.idx) && (rm.idx <= selend.rm.idx))
				drawsel(g, rm, rm.y - sy);
			}
			g.image(rm.text().tex(), new Coord(0, y - sy));
			y += rm.h();
		    }
		    if(upd)
			updyseq(smi);
		}
	    }
	    super.draw(g);
	    updurgency(0);
	    trimunseen();
	}

	public void tick(double dt) {
	    super.tick(dt);
	    double ty = sb.val;
	    dy = ty + (Math.pow(2, -dt * 40) * (dy - ty));
	}

	public boolean mousewheel(MouseWheelEvent ev) {
	    sb.ch(ev.s * 45);
	    return(true);
	}

	public void resize(Coord sz) {
	    super.resize(sz);
	    if(sb != null) {
		sb.move(new Coord(sz.x - (UI.scale(12) - marg.x), UI.scale(34) - marg.y));
		sb.resize(ih() - sb.c.y);
		if(!rmsgs.isEmpty()) {
		    RenderedMessage lm = rmsgs.get(rmsgs.size() - 1);
		    boolean b = sb.val >= sb.max;
		    sb.max = lm.y + lm.h() - ih();
		    if(b)
			sb.val = sb.max;
		}
	    }
	    dy = sb.val;
	    if(cb != null) {
		cb.c = new Coord(sz.x + marg.x - cb.sz.x, -marg.y);
	    }
	}

	public static class CharPos {
	    public final RenderedMessage rm;
	    public final int pn;
	    public final RichText.TextPart part;
	    public final TextHitInfo ch;

	    public CharPos(RenderedMessage rm, RichText.TextPart part, TextHitInfo ch) {
		this.rm = rm;
		this.pn = partnum((RichText)rm.text(), part);
		this.part = part;
		this.ch = ch;
	    }

	    private static int partnum(RichText text, RichText.TextPart part) {
		int i = 0;
		for(RichText.Part p = text.parts; p != null; p = p.next) {
		    if(p == part)
			return(i);
		    i++;
		}
		throw(new IllegalArgumentException("Text part not part of text"));
	    }

	    public boolean equals(Object oo) {
		if(!(oo instanceof CharPos)) return(false);
		CharPos o = (CharPos)oo;
		return((o.rm == this.rm) && (o.part == this.part) && o.ch.equals(this.ch));
	    }

	    public String toString() {
		return(String.format("#<charpos %s(%s) %s %s>", rm.msg, rm.idx, part, ch));
	    }
	}

	public final Comparator<CharPos> poscmp = new Comparator<CharPos>() {
	    public int compare(CharPos a, CharPos b) {
		if(a.rm != b.rm) {
		    return(a.rm.idx - b.rm.idx);
		} else if(a.pn != b.pn) {
		    return(a.pn - b.pn);
		} else {
		    return(a.ch.getInsertionIndex() - b.ch.getInsertionIndex());
		}
	    }
	};

	public CharPos charat(Coord c) {
	    if(c.y < -sb.val) {
		synchronized(rmsgs) {
		    if(rmsgs.isEmpty())
			return(null);
		    RenderedMessage rm = rmsgs.get(0);
		    if(!(rm.text() instanceof RichText))
			return(null);
		    RichText.TextPart fp = null;
		    for(RichText.Part part = ((RichText)rm.text()).parts; part != null; part = part.next) {
			if(part instanceof RichText.TextPart) {
			    fp = (RichText.TextPart)part;
			    break;
			}
		    }
		    if(fp == null)
			return(null);
		    return(new CharPos(rm, fp, TextHitInfo.leading(0)));
		}
	    }

	    Coord hc = new Coord();
	    RenderedMessage rm = messageat(c, hc);
	    if((rm == null) || !(rm.text() instanceof RichText))
		return(null);
	    RichText rt = (RichText)rm.text();
	    RichText.Part p = rt.partat(hc);
	    if(p == null) {
		RichText.TextPart lp = null;
		for(RichText.Part part = rt.parts; part != null; part = part.next) {
		    if(part instanceof RichText.TextPart)
			lp = (RichText.TextPart)part;
		}
		if(lp == null) return(null);
		return(new CharPos(rm, lp, TextHitInfo.trailing(lp.end - lp.start - 1)));
	    }
	    if(!(p instanceof RichText.TextPart))
		return(null);
	    RichText.TextPart tp = (RichText.TextPart)p;
	    return(new CharPos(rm, tp, tp.charat(hc)));
	}

	private CharPos selorig, lasthit, selstart, selend;
	private UI.Grab grab;
	private boolean dragging;
	public boolean mousedown(MouseDownEvent ev) {
	    if(ev.propagate(this) || super.mousedown(ev))
		return(true);
	    if(grab != null)
		return(true);
	    CharPos ch = charat(ev.c);
	    selorig = ch;
	    if(ch != null) {
		if(ch.rm.msg.mousedown(this, ch, ev.c, ev.b))
		    return(true);
	    }
	    if(ev.b == 1) {
		selstart = selend = null;
		if(ch != null) {
		    lasthit = ch;
		    dragging = false;
		    grab = ui.grabmouse(this);
		}
		return(true);
	    }
	    return(true);
	}

	public void mousemove(MouseMoveEvent ev) {
	    super.mousemove(ev);
	    if(grab != null) {
		CharPos ch = charat(ev.c);
		if((ch != null) && !ch.equals(lasthit)) {
		    lasthit = ch;
		    if(!dragging && !ch.equals(selorig))
			dragging = true;
		    int o = poscmp.compare(selorig, ch);
		    if(o < 0) {
			selstart = selorig; selend = ch;
		    } else if(o > 0) {
			selstart = ch; selend = selorig;
		    } else {
			selstart = selend = null;
		    }
		}
	    }
	}

	public boolean mouseup(MouseUpEvent ev) {
	    if(ev.propagate(this) || super.mouseup(ev))
		return(true);
	    if((ev.b == 1) && (grab != null)) {
		grab.remove();
		grab = null;
		dragging = false;
		if(selstart != null) {
		    selected(selstart, selend);
		    return(true);
		}
	    }
	    CharPos ch = charat(ev.c);
	    if(ch != null) {
		if(ch.rm.msg.mouseup(this, ch, ev.c, ev.b))
		    return(true);
		if(!dragging && (selorig != null) && ch.equals(selorig)) {
		    if(ch.rm.msg.clicked(this, ch, ev.c, ev.b))
			return(true);
		    if(clicked(selorig, ev.b))
			return(true);
		}
	    }
	    return(true);
	}

	protected void selected(CharPos start, CharPos end) {
	    StringBuilder buf = new StringBuilder();
	    synchronized(rmsgs) {
		for(int mi = start.rm.idx; mi <= end.rm.idx; mi++) {
		    RenderedMessage rm = rmsgs.get(mi);
		    if(!(rm.text() instanceof RichText))
			continue;
		    RichText rt = (RichText)rm.text();
		    RichText.Part part = null;
		    if(rm == start.rm) {
			for(part = rt.parts; part != null; part = part.next) {
			    if(part == start.part)
				break;
			}
		    } else {
			part = rt.parts;
		    }
		    for(; part != null; part = part.next) {
			if(!(part instanceof RichText.TextPart))
			    continue;
			RichText.TextPart tp = (RichText.TextPart)part;
			CharacterIterator iter = tp.ti();
			int sch;
			if(tp == start.part)
			    sch = tp.start + start.ch.getInsertionIndex();
			else
			    sch = tp.start;
			int ech;
			if(tp == end.part)
			    ech = tp.start + end.ch.getInsertionIndex();
			else
			    ech = tp.end;
			for(int i = sch; i < ech; i++)
			    buf.append(iter.setIndex(i));
			if(part == end.part)
			    break;
			buf.append(' ');
		    }
		    if(rm != end.rm)
			buf.append('\n');
		}
	    }
	    CharPos ownsel = selstart;
	    Clipboard.Item<CharSequence> item = new Clipboard.Item<>(Clipboard.Format.TEXT, buf);
	    Runnable lost = ()-> {
		if(selstart == ownsel)
		    selstart = selend = null;
	    };
	    /* XXX: Putting on CLIPBOARD should be from eg. C-c, but
	     * that requires Channel as a whole to accept keyobard
	     * focus... */
	    for(Object id : new Object[] {Clipboard.Std.PRIMARY, Clipboard.Std.CLIPBOARD}) {
		ui.wnd.clipboard(id).put(new Clipboard.Contents(item), lost);
	    }
	}

	protected boolean clicked(CharPos pos, int btn) {
	    if(btn == 1) {
		AttributedCharacterIterator inf = pos.part.ti();
		inf.setIndex(pos.ch.getCharIndex() + pos.part.start);
		URI uri = (URI)inf.getAttribute(ChatAttribute.HYPERLINK);
		if(uri != null) {
		    try {
			ui.wnd.toolkit().browse(uri);
		    } catch(java.net.MalformedURLException e) {
			getparent(GameUI.class).error("Could not follow link.");
		    } catch(IOException e) {
			getparent(GameUI.class).error("Could not launch web browser: " + e.getMessage());
		    }
		}
		return(true);
	    }
	    return(false);
	}

	public void select() {
	    getparent(ChatUI.class).select(this);
	}

	public void display() {
	    select();
	    ChatUI chat = getparent(ChatUI.class);
	    chat.expand();
	    chat.parent.setfocus(chat);
	}

	private void drawsel(GOut g, RenderedMessage rm, int y) {
	    RichText rt = (RichText)rm.text();
	    boolean sel = rm != selstart.rm;
	    for(RichText.Part part = rt.parts; part != null; part = part.next) {
		if(!(part instanceof RichText.TextPart))
		    continue;
		RichText.TextPart tp = (RichText.TextPart)part;
		if(tp.start == tp.end)
		    continue;
		TextHitInfo a, b;
		if(sel) {
		    a = TextHitInfo.leading(0);
		} else if(tp == selstart.part) {
		    a = selstart.ch;
		    sel = true;
		} else {
		    continue;
		}
		if(tp == selend.part) {
		    sel = false;
		    b = selend.ch;
		} else {
		    b = TextHitInfo.trailing(tp.end - tp.start - 1);
		}
		Coord ul = new Coord(tp.x + (int)tp.advance(0, a.getInsertionIndex()), tp.y + y);
		Coord sz = new Coord((int)tp.advance(a.getInsertionIndex(), b.getInsertionIndex()), tp.height());
		g.chcolor(0, 0, 255, 255);
		g.frect(ul, sz);
		g.chcolor();
		if(!sel)
		    break;
	    }
	}

	public void uimsg(String name, Object... args) {
	    if(name == "sel") {
		select();
	    } else if(name == "dsp") {
		display();
	    } else {
		super.uimsg(name, args);
	    }
	}

	public void wdgmsg(Widget sender, String msg, Object... args) {
	    if(sender == cb) {
		wdgmsg("close");
	    } else {
		super.wdgmsg(sender, msg, args);
	    }
	}

	public abstract String name();

	public boolean selmousedown(Coord c, int btn) {return(false);}
	public boolean selmouseup(Coord c, int btn) {return(false);}
	public boolean selclicked(Coord c, int btn) {return(false);}

	private Indir<Resource> iconres = null;
	public Channel icon(Indir<Resource> res) {iconres = res; return(this);}
	public Resource.Image icon() {
	    return((iconres == null) ? null : iconres.get().layer(Resource.imgc));
	}
    }

    public static class Log extends Channel {
	private final String name;
	
	public Log(String name) {
	    super(false);
	    this.name = name;
	}

	// addon: (065.16) the System log: the lines the CLIENT writes rather than a player says. Their stock is
	// UI.Notice's own white and UI.ErrorMessage's dark red, neither of which lives in this class.
	public String chanscope() {return("chat.system");}
	
	public String name() {return(name);}
    }

    public static abstract class EntryChannel extends Channel {
	private final TextEntry in;
	private List<String> history = new ArrayList<String>();
	private int hpos = 0;
	private String hcurrent;

	public EntryChannel(boolean closable) {
	    super(closable);
	    setfocusctl(true);
	    this.in = new TextEntry(0, "") {
		    public void activate(String text) {
			if(text.length() > 0)
			    send(text);
			settext("");
			hpos = history.size();
		    }

		    public boolean keydown(KeyDownEvent ev) {
			if(ConsoleHost.kb_histprev.key().match(ev)) {
			    if(hpos > 0) {
				if(hpos == history.size())
				    hcurrent = text();
				rsettext(history.get(--hpos));
			    }
			    return(true);
			} else if(ConsoleHost.kb_histnext.key().match(ev)) {
			    if(hpos < history.size()) {
				if(++hpos == history.size())
				    rsettext(hcurrent);
				else
				    rsettext(history.get(hpos));
			    }
			    return(true);
			} else {
			    return(super.keydown(ev));
			}
		    }
		};
	    add(this.in);
	}

	public int ih() {
	    return(sz.y - in.sz.y);
	}

	public void resize(Coord sz) {
	    super.resize(sz);
	    if(in != null) {
		in.c = new Coord(0, this.sz.y - in.sz.y);
		in.resize(this.sz.x);
	    }
	}

	/* addon: (audit2 B07) the entry history is BOUNDED. It is the list the Up/Down keys walk, and
	 * nothing ever trimmed it -- so every line said, from the entry line or from an addon's
	 * channel:send(text), was kept for the life of the login. HISTORY is as far back as the arrow
	 * keys reach; what was said before that is the scrollback's, and the scrollback is not this. */
	private static final int HISTORY = 100;

	public void send(String text) {
	    history.add(text);
	    while(history.size() > HISTORY)
		history.remove(0);
	    wdgmsg("msg", text);
	}
    }

    public static class SimpleChat extends EntryChannel {
	public final String name;

	public SimpleChat(boolean closable, String name) {
	    super(closable);
	    this.name = name;
	}

	public void uimsg(String msg, Object... args) {
	    if((msg == "msg") || (msg == "log")) {
		String line = (String)args[0];
		Color col = null;
		if(args.length > 1) col = (Color)args[1];
		if(col == null) col = Color.WHITE;
		int urgency = (args.length > 2) ? Utils.iv(args[2]) : 0;
		Message cmsg = new SimpleMessage(line, col);
		append(cmsg, urgency);
	    } else {
		super.uimsg(msg, args);
	    }
	}

	public String name() {
	    return(name);
	}
    }

    public static class MultiChat extends EntryChannel {
	public final int urgency;
	private final String name;
	private final Map<Integer, Color> pc = new HashMap<Integer, Color>();
	private Map<Integer, Boolean> muted = null;
	private Integer mutewait = null;

	public class NamedMessage extends Message {
	    public final int from;
	    public final String text;
	    public final Color col;

	    public NamedMessage(int from, String text, Color col) {
		this.from = from;
		this.text = text;
		this.col = col;
	    }

	    public String text() {return(text);}        // addon: (110.3)
	    public Color color() {return(col);}         // addon: (110.3)
	    public Integer speaker() {return(from);}    // addon: (110.3) the one kind that names one

	    public class Rendered implements Indir<Text> {
		public final int w;
		public final String nm;

		public Rendered(int w, String nm) {
		    this.w = w;
		    this.nm = nm;
		}

		public Text get() {
		    return(fnd(scope()).render(RichText.Parser.quote(String.format("%s: %s", nm, text)), w, TextAttribute.FOREGROUND, fndcol(scope(), col)));   // addon: (F3d) + the sheet's colour (033.2, 065.16)
		}
	    }

	    private String nm() {
		BuddyWnd.Buddy b = getparent(GameUI.class).buddies.find(from);
		return((b == null) ? "???" : b.name);
	    }

	    public Indir<Text> render(int w) {
		return(new Rendered(w, nm()));
	    }

	    public boolean valid(Indir<Text> data) {
		return(((Rendered)data).nm.equals(nm()));
	    }

	    public boolean clicked(Channel chan, CharPos pos, Coord c, int btn) {
		if((btn == 3) && (muted != null)) {
		    Boolean muted = MultiChat.this.muted.get(from);
		    if(muted == null) {
			mutewait = from;
			wdgmsg("muted", from);
		    } else {
			mutemenu(from, muted);
		    }
		    return(true);
		}
		return(super.clicked(chan, pos, c, btn));
	    }
	}

	private void mutemenu(int pl, boolean cur) {
	    SListMenu.Action ma = SListMenu.Action.of(cur ? "Unmute" : "Mute", () -> wdgmsg("mute", pl, cur ? 0 : 1));
	    SListMenu.of(UI.scale(250, 120), null, Arrays.asList(ma)).addat(ui.root, ui.mc);
	}

	public class MyMessage extends SimpleMessage {
	    public MyMessage(String text) {
		super(text, new Color(192, 192, 255));
	    }

	    // addon: (065.16) your OWN line, in whichever multi-chat it was said in -- so a theme paints it apart
	    // from everyone else's, which is what this colour is for and what the party channel inherits too.
	    protected String kind() {return("chat.mine");}
	}

	public MultiChat(boolean closable, String name, int urgency) {
	    super(closable);
	    this.name = name;
	    this.urgency = urgency;
	}

	private float colseq = 0;
	private int seqn = 0;       // addon: (065.16) how many speakers this channel has minted a colour for
	/* addon: (065.16, audit2 B15) the SEQUENCE the mints below were made from -- the resolved
	 * Fonts.sequence("chat.speaker") itself, compared by value, and null for "the client's own walk".
	 * It was Fonts.gen(), which is the GLOBAL style/font generation: any addon's unrelated font or
	 * colour edit anywhere bumped it, dropped every minted speaker colour and re-shuffled who was what
	 * colour -- against chat.md's "A speaker keeps the colour they were given". The drop itself is right
	 * and is why a chat rule takes effect on the next line rather than the next login; what was wrong is
	 * what it keyed on, which must be the one thing that decides these colours and nothing else. */
	private Object seqkey = SEQ_UNSET;   // addon:
	/* addon: (audit2 B15) "no sequence has been asked for yet", told apart from the null that means
	 * "asked, and the client's own walk is the answer" -- so the FIRST fromcolor of a channel does not
	 * clear a map that is already empty and, more to the point, does not read as a change. */
	private static final Object SEQ_UNSET = new Object();   // addon:
	private Color nextcol() {
	    /* addon: (065.16) a theme may name the SEQUENCE this walks rather than any of the colours it emits:
	     * a palette it lists and this cycles, or this very walk with its own step, saturation and brightness.
	     * Naming the sequence is what makes it a value at all -- flattening it to one colour would stop
	     * telling speakers apart, which is the whole of what it is for. With no rule the client's own walk is
	     * exactly what it always was, accumulation and all. */
	    Fonts.Sequence sq = Fonts.sequence("chat.speaker");
	    if(sq != null)
		return(sq.color(seqn++));
	    seqn++;
	    return(new Color(Color.HSBtoRGB(colseq = ((colseq + (float)Math.sqrt(2)) % 1.0f), 0.5f, 1.0f)));
	}

	public Color fromcolor(int from) {
	    synchronized(pc) {
		/* addon: (065.16) a speaker's colour is minted once and kept, so a rule installed later would
		 * never reach anyone already speaking. Dropping the mints on a gen move is what makes a theme
		 * take effect on the next line rather than on the next login. Lines ALREADY in the scrollback
		 * keep theirs: a message's colour is decided when it arrives, not when it is drawn. */
		Object sq = Fonts.sequence("chat.speaker");   // addon: (audit2 B15) the sequence itself, by value
		if(!Utils.eq(seqkey, sq)) {
		    seqkey = sq;
		    pc.clear();
		    seqn = 0;
		    colseq = 0;
		}
		Color c = pc.get(from);
		if(c == null)
		    pc.put(from, c = nextcol());
		return(c);
	    }
	}

	public void uimsg(String msg, Object... args) {
	    if(msg == "msg") {
		Number from = (Number)args[0];
		String line = (String)args[1];
		if(from == null) {
		    append(new MyMessage(line), -1);
		} else {
		    Message cmsg = new NamedMessage(from.intValue(), line, fromcolor(from.intValue()));
		    append(cmsg, urgency);
		}
	    } else if(msg == "mutable") {
		this.muted = Utils.bv(args[0]) ? new HashMap<>() : null;
	    } else if(msg == "muted") {
		int pl = Utils.iv(args[0]);
		boolean muted = Utils.bv(args[1]);
		this.muted.put(pl, muted);
		if((mutewait != null) && (mutewait == pl)) {
		    mutewait = null;
		    mutemenu(pl, muted);
		}
	    } else {
		super.uimsg(msg, args);
	    }
	}

	public String name() {
	    return(name);
	}
    }
    
    public static class PartyChat extends MultiChat {
	public PartyChat() {
	    super(false, "Party", 2);
	}

	// addon: (065.16) a party line's stock colour is the member's own, which the SERVER assigns; a rule on
	// this key is what a theme says when it wants the party channel to read as one thing instead.
	public String chanscope() {return("chat.party");}

	public void uimsg(String msg, Object... args) {
	    if(msg == "msg") {
		Number from = (Number)args[0];
		long gobid = Utils.uiv(args[1]);
		String line = (String)args[2];
		Color col = Color.WHITE;
		synchronized(ui.sess.glob.party.memb) {
		    Party.Member pm = ui.sess.glob.party.memb.get(gobid);
		    if(pm != null)
			col = pm.col;
		}
		if(from == null) {
		    append(new MyMessage(line), -1);
		} else {
		    Message cmsg = new NamedMessage(from.intValue(), line, Utils.blendcol(col, Color.WHITE, 0.5));
		    append(cmsg, urgency);
		}
	    } else {
		super.uimsg(msg, args);
	    }
	}
    }
    
    public static class PrivChat extends EntryChannel {
	// addon: (065.16) both halves of a private conversation -- the incoming line and the outgoing one, whose
	// two stock colours are the only pair the client holds for one kind.
	public String chanscope() {return("chat.private");}

	private final int other;
	private boolean muted;
	
	public PrivChat(boolean closable, int other) {
	    super(closable);
	    this.other = other;
	}

	private void menu() {
	    SListMenu.Action ma = SListMenu.Action.of(muted ? "Unmute" : "Mute", () -> wdgmsg("mute", muted ? 0 : 1));
	    SListMenu.of(UI.scale(250, 120), null, Arrays.asList(ma)).addat(ui.root, ui.mc);
	}

	public boolean selclicked(Coord c, int btn) {
	    if(btn == 3) {
		menu();
		return(true);
	    }
	    return(super.selclicked(c, btn));
	}

	public class InMessage extends SimpleMessage {
	    public InMessage(String text) {
		super(text, new Color(255, 128, 128, 255));
	    }

	    public boolean clicked(Channel chan, CharPos pos, Coord c, int btn) {
		if(btn == 3) {
		    menu();
		    return(true);
		}
		return(super.clicked(chan, pos, c, btn));
	    }
	}

	public class OutMessage extends SimpleMessage {
	    public OutMessage(String text) {
		super(text, new Color(128, 128, 255, 255));
	    }
	}

	public void uimsg(String msg, Object... args) {
	    if(msg == "msg") {
		String t = (String)args[0];
		String line = (String)args[1];
		if(t.equals("in")) {
		    Message cmsg = new InMessage(line);
		    append(cmsg, 3);
		} else if(t.equals("out")) {
		    append(new OutMessage(line), -1);
		}
	    } else if(msg == "err") {
		String err = (String)args[0];
		Message cmsg = new SimpleMessage(err, Color.RED);
		append(cmsg, 3);
	    } else if(msg == "muted") {
		this.muted = Utils.bv(args[0]);
	    } else {
		super.uimsg(msg, args);
	    }
	}
	
	public String name() {
	    BuddyWnd.Buddy b = getparent(GameUI.class).buddies.find(other);
	    if(b == null)
		return("???");
	    else
		return(b.name);
	}
    }
    
    @RName("schan")
    public static class $SChan implements Factory {
	public Widget create(UI ui, Object[] args) {
	    String name = (String)args[0];
	    Channel ret = new SimpleChat(false, name);
	    if(args.length > 1)
		ret.icon(ui.sess.getresv(args[1]));
	    return(ret);
	}
    }
    @RName("mchat")
    public static class $MChat implements Factory {
	public Widget create(UI ui, Object[] args) {
	    String name = (String)args[0];
	    int urgency = Utils.iv(args[1]);
	    Channel ret = new MultiChat(false, name, urgency);
	    if(args.length > 2)
		ret.icon(ui.sess.getresv(args[2]));
	    return(ret);
	}
    }
    @RName("pchat")
    public static class $PChat implements Factory {
	public Widget create(UI ui, Object[] args) {
	    Channel ret = new PartyChat();
	    if(args.length > 0)
		ret.icon(ui.sess.getresv(args[0]));
	    return(ret);
	}
    }
    @RName("pmchat")
    public static class $PMChat implements Factory {
	public Widget create(UI ui, Object[] args) {
	    int other = Utils.iv(args[0]);
	    Channel ret = new PrivChat(true, other);
	    if(args.length > 1)
		ret.icon(ui.sess.getresv(args[1]));
	    return(ret);
	}
    }

    public void addchild(Widget child, Object... args) {
	add(child);
    }

    public <T extends Widget> T add(T w) {
	if(w instanceof Channel) {
	    Channel chan = (Channel)w;
	    chan.c = chansel.c.add(chansel.sz.x, 0);
	    chan.resize(sz.x - marg.x - chan.c.x, sz.y - chan.c.y);
	    super.add(w);
	    chansel.add(chan);
	    /* addon: (110.2) a channel joined this character's chat. BEFORE the select() below, which is this
	     * method's own: a brand-new tab is picked as it arrives, and an addon hears it added before it hears
	     * it picked. Queued, never fired here -- the server places a channel on the thread that applies its
	     * update, and Lua runs on the UI thread. */
	    io.brodgar.addon.AddonManager.chatChannelAdded(chan);
	    select(chan, false);
	    return(w);
	} else {
	    return(super.add(w));
	}
    }

    public void cdestroy(Widget w) {
	if(w instanceof Channel) {
	    Channel chan = (Channel)w;
	    if(chan == sel)
		sel = null;
	    chansel.rm(chan);
	    /* addon: (110.2) a channel left this character's chat. Widget.remove() unlinks before it calls
	     * this, so the channel is already out of the tree by the time the queue is drained -- which is what
	     * the payload's ch:exists() reports. Losing the SELECTED channel picks nothing, so no
	     * ChannelSelected follows: there is no tab to name. */
	    io.brodgar.addon.AddonManager.chatChannelRemoved(chan);
	}
    }
    
    private static final Tex chandiv = Resource.loadtex("gfx/hud/chat-cdiv");
    private static final Tex chanseld = Resource.loadtex("gfx/hud/chat-csel");
    private class Selector extends Widget {
	public final BufferedImage ctex = Resource.loadimg("gfx/hud/chantex");
	public final Text.Foundry tf = new Text.Foundry(Text.serif.deriveFont(Font.BOLD, UI.scale(12))).aa(true);
	// addon: the channel tabs down the side of the chat window follow the "chat" scope too (F3d) -- `tf` stays
	// stock (the ellw/maxnmw layout constants below are measured from it), each tab re-renders on a gen move.
	private Text.Foundry btf;
	private int tfgen = -1;
	private Text.Foundry tfont() {
	    int g = Fonts.gen();
	    if((btf == null) || (tfgen != g)) {
		btf = Fonts.foundry("chat", tf);
		tfgen = g;
	    }
	    return(btf);
	}
	public final Color[] uc = {
	    new Color(80, 40, 0),
	    new Color(0, 128, 255),
	    new Color(255, 128, 0),
	    new Color(255, 0, 0),
	};
	private final List<DarkChannel> chls = new ArrayList<DarkChannel>();
	private final int iconsz = UI.scale(16), ellw = tf.strsize("...").x, maxnmw = selw - iconsz;
	private final int offset = chandiv.sz().y + chanseld.sz().y;
	private int ts = 0;
	private double ds = 0;
	private Channel cstart;

	private Text namedeco(String name, BufferedImage img, Color col) {
	    PUtils.tilemod(img.getRaster(), ctex.getRaster(), Coord.z);
	    img = PUtils.rasterimg(PUtils.blurmask2(img.getRaster(), 1, 1, col));
	    return(new Text(name, img));
	}

	public Text nmrender(String name, Color col) {
	    // addon: (102.2) ...and so does the same name rendered anywhere else in the selector.
	    return(namedeco(name, Fonts.render("chat", tfont(), name).img, col));   // addon: the "chat" scope (F3d)
	}

	public int chidx(Channel chan) {
	    for(int i = 0; i < chls.size(); i++) {
		if(chls.get(i).chan == chan)
		    return(i);
	    }
	    return(-1);
	}

	public void resize(Coord sz) {
	    int si = chidx(sel);
	    boolean fit = (((si * offset) - ts) >= 0) && (((si * offset) - ts + chanseld.sz().y) <= this.sz.y);
	    super.resize(sz);
	    ds = clips((int)Math.round(ds));
	    ts = clips(ts);
	    if(fit)
		show(si);
	}

	private class DarkChannel {
	    public final Channel chan;
	    public Text rname;
	    public Tex ricon;
	    private int urgency = 0;
	    private Resource.Image icon;

	    private DarkChannel(Channel chan) {
		this.chan = chan;
	    }

	    private int fontgen = -1;   // addon: Fonts.gen() at the last rname render (F3d)
	    public Text rname() {
		String name = chan.name();
		int urg = chan.urgency;
		int fgen = Fonts.gen();
		if((rname != null) && (fontgen != fgen)) {   // addon: re-render on a "chat" font change (F3d)
		    rname.dispose();
		    rname = null;
		}
		fontgen = fgen;   // addon:
		// addon: (102.2) `rname.text` is the channel's own name here -- namedeco rebuilds the Text
		// around it -- so this guard is against the source already and a catalogue cannot break it.
		if((rname == null) || !rname.text.equals(name) || (urgency != urg)) {
		    /* addon: (102.2) a channel tab is chat text and declares "chat", so a catalogue reaches the
		     * names the CLIENT chose (a private tab is named after a player, which nothing names). The
		     * ellipsis is then cut out of `raw.text`, the string the raster is OF: measuring an offset in
		     * one string and taking it out of another indexes past the end of the shorter. */
		    Fonts.enter("chat");
		    Text.Line raw;
		    try {
			raw = tfont().render(name);   // addon: the "chat" scope (F3d)
			if(raw.sz().x > maxnmw) {
			    int len = raw.charat(maxnmw - ellw);
			    raw = tfont().render(raw.text.substring(0, len) + "...");   // addon:
			}
		    } finally {
			Fonts.exit();
		    }
		    BufferedImage img = raw.img;
		    rname = namedeco(name, img, urgcol(urgency = urg, uc));   // addon: the "chat.urgent" sequence (065.16)
		}
		return(rname);
	    }

	    public Tex ricon() {
		if((ricon == null) || (icon != chan.icon())) {
		    Resource.Image img = chan.icon();
		    if(img == null)
			return(null);
		    Coord sz;
		    if(img.sz.x > img.sz.y)
			sz = Coord.of(iconsz, (iconsz * img.sz.y) / img.sz.x);
		    else
			sz = Coord.of((iconsz * img.sz.x) / img.sz.y, iconsz);
		    ricon = new TexI(PUtils.uiscale(img.img, sz));
		    icon = img;
		}
		return(ricon);
	    }
	}

	public Selector(Coord sz) {
	    super(sz);
	}

	private void add(Channel chan) {
	    synchronized(chls) {
		chls.add(new DarkChannel(chan));
	    }
	}

	private void rm(Channel chan) {
	    synchronized(chls) {
		for(Iterator<DarkChannel> i = chls.iterator(); i.hasNext();) {
		    DarkChannel c = i.next();
		    if(c.chan == chan)
			i.remove();
		}
	    }
	}

	public void draw(GOut g) {
	    int ds = (int)Math.round(this.ds);
	    synchronized(chls) {
		for(int i = ds / offset; i < chls.size(); i++) {
		    int y = i * offset - ds;
		    if(y >= sz.y)
			break;
		    DarkChannel ch = chls.get(i);
		    if(ch.chan == sel)
			g.image(chanseld, Coord.of(0, y));
		    Tex name = ch.rname().tex(), icon = null;
		    try {
			icon = ch.ricon();
		    } catch(Loading l) {}
		    int my = y + (chanseld.sz().y / 2) - UI.scale(1);
		    if(icon == null) {
			g.aimage(name, Coord.of(sz.x / 2, my), 0.5, 0.5);
		    } else {
			int w = name.sz().x + icon.sz().x;
			int x = (sz.x - w) / 2;
			g.aimage(icon, Coord.of(x, my), 0.0, 0.5); x += icon.sz().x;
			g.aimage(name, Coord.of(x, my), 0.0, 0.5);
		    }
		    g.image(chandiv, Coord.of(0, y + chanseld.sz().y));
		}
	    }
	}

	public void tick(double dt) {
	    super.tick(dt);
	    ds = ts + (Math.pow(2, -dt * 20) * (ds - ts));
	}

	public void show(int si) {
	    int ty = si * offset;
	    if(ty - ts + chanseld.sz().y > sz.y)
		ts = ty + chanseld.sz().y - sz.y;
	    else if(ty - ts < 0)
		ts = ty;
	}

	public void show(Channel chan) {
	    int si = chidx(chan);
	    if(si >= 0)
		show(si);
	}

	public boolean up() {
	    Channel prev = null;
	    for(DarkChannel ch : chls) {
		if(ch.chan == sel) {
		    if(prev != null) {
			select(prev);
			return(true);
		    } else {
			return(false);
		    }
		}
		prev = ch.chan;
	    }
	    return(false);
	}

	public boolean down() {
	    for(Iterator<DarkChannel> i = chls.iterator(); i.hasNext();) {
		DarkChannel ch = i.next();
		if(ch.chan == sel) {
		    if(i.hasNext()) {
			select(i.next().chan);
			return(true);
		    } else {
			return(false);
		    }
		}
	    }
	    return(false);
	}

	private Channel bypos(Coord c) {
	    int ds = (int)Math.round(this.ds);
	    int i = (c.y + ds) / offset;
	    if((i >= 0) && (i < chls.size()))
		return(chls.get(i).chan);
	    return(null);
	}

	public boolean mousedown(MouseDownEvent ev) {
	    Channel chan = bypos(ev.c);
	    cstart = chan;
	    if(chan != null) {
		if(ev.b == 1) {
		    select(chan);
		} else {
		    chan.selmousedown(ev.c, ev.b);
		}
	    }
	    return(true);
	}

	public boolean mouseup(MouseUpEvent ev) {
	    Channel chan = bypos(ev.c);
	    if(chan != null) {
		if(ev.b != 1) {
		    chan.selmouseup(ev.c, ev.b);
		    if(cstart == chan)
			chan.selclicked(ev.c, ev.b);
		}
	    }
	    cstart = null;
	    return(true);
	}

	private int clips(int s) {
	    int maxh = (chls.size() * offset) - sz.y - chandiv.sz().y;
	    return(Math.max(Math.min(s, maxh), 0));
	}

	public boolean mousewheel(MouseWheelEvent ev) {
	    if(!ui.modshift) {
		ts = clips(ts + (ev.a * UI.scale(40)));
	    } else {
		if(ev.a < 0)
		    up();
		else if(ev.a > 0)
		    down();
	    }
	    return(true);
	}
    }

    public void select(Channel chan, boolean focus) {
	Channel prev = sel;
	sel = chan;
	if(prev != null)
	    prev.hide();
	sel.show();
	chansel.show(chan);
	resize(sz);
	if(focus || hasfocus)
	    setfocus(chan);
	/* addon: (110.2) the chat changed tab. THE one funnel -- the selector's click, the hotkeys, the server's
	 * own "sel" uimsg and add() above all come through here. Only a real change is reported: naming the tab
	 * that is already up is not a moment, exactly as writing the screen to the session already drawn is not.
	 * Queued: the uimsg path runs on the thread that applies the server's update. */
	if(prev != chan)
	    io.brodgar.addon.AddonManager.chatChannelSelected(chan);
    }

    public void select(Channel chan) {
	select(chan, true);
    }

    private class Notification {
	public final Channel chan;
	public final Channel.Message msg;
	public final Text chnm, rmsg;
	public final double time = Utils.ntime();

	private Notification(Channel chan, Channel.Message msg) {
	    this.chan = chan;
	    this.msg = msg;
	    this.chnm = fnd("chat").render(chan.name(), 0, TextAttribute.FOREGROUND, fndcol("chat", Color.WHITE));   // addon: the "chat" scope (F3d) + the sheet's colour (033.2)
	    this.rmsg = msg.render(sz.x - selw).get();
	}
    }

    private Text.Line rqline = null;
    private int rqpre;
    private int rqgen = -1;   // addon: Fonts.gen() at the last rqline render (F3d)
    /* addon: (102.2) the composed line `rqline` was rendered FROM. The guard beneath used to compare the
     * BUFFER against `rqline.text`, which also carries the channel prompt -- so it never matched and the
     * quick line was re-rendered, and re-textured, on every single frame it was open. Under a catalogue
     * that also meant a miss recorded per frame, spelling out every prefix of what was being typed. */
    private String rqsrc = null;
    public void drawsmall(GOut g, Coord br, int h) {
	Coord c;
	if(qline != null) {
	    // addon: re-render the typed quick line when a "chat" font override moves (F3d)
	    String pre = String.format("%s> ", qline.chan.name());
	    String qsrc = pre + qline.buf.line();   // addon: (102.2) the line as composed -- see rqsrc
	    if((rqline == null) || !qsrc.equals(rqsrc) || (rqgen != Fonts.gen())) {
		rqgen = Fonts.gen();   // addon:
		/* addon: (102.2) the quick line declares "chat", which is the surface it IS -- it lives in the chat
		 * window and is built from the chat's own recipe -- and declares in the same breath that what it
		 * DRAWS is the line the player is typing. So a theme goes on styling it as chat while no catalogue
		 * can rewrite a word as it is typed, nor report one as a string to translate. */
		Fonts.enterTyped("chat");
		try {
		    rqline = qfnd().render(qsrc);   // addon: the "chat" scope (F3d)
		} finally {
		    Fonts.exit();
		}
		rqsrc = qsrc;          // addon: (102.2)
		rqpre = pre.length();
	    }
	    int point = qline.buf.point(), mark = qline.buf.mark();
	    int px = rqline.advance(point + rqpre) + UI.scale(1);
	    if(mark >= 0) {
		int mx = rqline.advance(mark + rqpre) + UI.scale(1);
		g.chcolor(TextEntry.selcol);
		g.frect2(Coord.of(br.x + Math.min(px, mx), br.y - UI.scale(18)),
			 Coord.of(br.x + Math.max(px, mx), br.y - UI.scale(6)));
		g.chcolor();
	    }
	    c = br.sub(UI.scale(0, 20));
	    g.image(rqline.tex(), c);
	    g.line(Coord.of(br.x + px, br.y - UI.scale(18)), Coord.of(br.x + px, br.y - UI.scale(6)), 1);
	} else {
	    c = br.sub(UI.scale(0, 5));
	}
	double now = Utils.ntime();
	synchronized(notifs) {
	    for(Iterator<Notification> i = notifs.iterator(); i.hasNext();) {
		Notification n = i.next();
		if(now - n.time > 5.0) {
		    i.remove();
		    continue;
		}
		int mh = Math.max(n.chnm.sz().y, n.rmsg.sz().y);
		if((c.y -= mh) < br.y - h)
		    break;
		g.chcolor(0, 0, 0, 192);
		g.frect2(c.add(selw - UI.scale(12) - n.chnm.sz().x, 0),
			 c.add(selw + n.rmsg.sz().x + UI.scale(2), mh));
		g.chcolor(192, 192, 192, 255);
		g.line(c.add(selw - UI.scale(5), 1), c.add(selw - UI.scale(5), mh - 1), 1);
		g.chcolor();
		g.image(n.chnm.tex(),
			c.add(selw - UI.scale(10) - n.chnm.sz().x, (mh - n.chnm.sz().y) / 2),
			br.sub(0, h), br.add(selw - UI.scale(10), 0));
		g.image(n.rmsg.tex(), c.add(selw, (mh - n.rmsg.sz().y) / 2));
	    }
	}
    }

    private static final Tex bulc = Resource.loadtex("gfx/hud/chat-lc");
    private static final Tex burc = Resource.loadtex("gfx/hud/chat-rc");
    private static final Tex bhb = Resource.loadtex("gfx/hud/chat-hori");
    private static final Tex bvlb = Resource.loadtex("gfx/hud/chat-verti");
    private static final Tex bvrb = bvlb;
    private static final Tex bmf = Resource.loadtex("gfx/hud/chat-mid");
    private static final Tex bcbd = Resource.loadtex("gfx/hud/chat-close-g");
    /* addon: (106) what the chat's own DECORATION is made of. Only the FIELD is declared: the frame below
     * is two corners, three repeated runs and two pinned ornaments with no bottom edge at all, a shape no
     * single `border` value restates -- so the catalogue says the field and stays quiet about the rest,
     * rather than handing back a frame that reads right and installs wrong. */
    private static void stockdeco() {
	Fonts.stock("chat.frame", "bg", Fonts.piece(Window.bg).tile());
    }

    public void draw(GOut g) {
	/* addon: (106) the chat's own decoration is the "chat.frame" rule's -- its FIELD as a `bg`, tiled
	 * inside the margin the chat keeps, and the corners/runs/ornaments below as a `border`. It is the
	 * analogue of window.frame, and the chat being no Window is exactly why it needs a key of its own.
	 * Each half is skipped only where the rule answers for it, so a bg-only rule keeps the stock frame
	 * and a border-only rule the stock field. */
	stockdeco();
	Fonts.Chrome deco = Fonts.chrome("chat.frame", this);
	if((deco == null) || !deco.bg())
	    g.rimage(Window.bg, marg, sz.sub(marg.x * 2, marg.y));
	else
	    deco.drawbg(g, marg, sz.sub(marg.x * 2, marg.y));
	super.draw(g);
	if((deco == null) || !deco.border()) {
	    g.image(bulc, new Coord(0, 0));
	    g.image(burc, new Coord(sz.x - burc.sz().x, 0));
	    g.rimagev(bvlb, new Coord(0, bulc.sz().y), sz.y - bulc.sz().y);
	    g.rimagev(bvrb, new Coord(sz.x - bvrb.sz().x, burc.sz().y), sz.y - burc.sz().y);
	    g.rimageh(bhb, new Coord(bulc.sz().x, 0), sz.x - bulc.sz().x - burc.sz().x);
	    g.aimage(bmf, new Coord(sz.x / 2, 0), 0.5, 0);
	    if((sel == null) || (sel.cb == null))
		g.aimage(bcbd, new Coord(sz.x, 0), 1, 0);
	} else {
	    deco.drawborder(g, Coord.z, sz);
	}
    }

    private static final Resource notifsfx = Resource.local().loadwait("sfx/hud/chat");
    public void notify(Channel chan, Channel.Message msg, int urgency) {
	if(urgency > 0) {
	    synchronized(notifs) {
		notifs.addFirst(new Notification(chan, msg));
	    }
	    ui.sfx(notifsfx);
	}
    }

    private class Spring extends NormAnim {
	final int oy = base.y - c.y, ny;
	Spring(int ny) {
	    super(0.15);
	    this.ny = ny;
	    show();
	}

	public void ntick(double a) {
	    double b = Math.cos(Math.PI * 2.5 * a) * Math.exp(-5 * a);
	    c = Coord.of(c.x, base.y + ny + (int)((ny - oy) * b));
	    if((a == 1.0) && (ny >= 0)) {
		hide();
	    }
	}
    }

    public void resize(Coord sz) {
	super.resize(sz);
	if(visible)
	    this.c = base.add(0, -this.sz.y);
	chansel.resize(new Coord(selw, this.sz.y - marg.y));
	if(sel != null)
	    sel.resize(new Coord(this.sz.x - marg.x - sel.c.x, this.sz.y - sel.c.y));
    }

    public void presize() {
	if(sz.y > parent.sz.y - UI.scale(100))
	    hresize(Math.max(UI.scale(minh), parent.sz.y - UI.scale(100)));
    }

    public boolean targetshow = false;
    public void sshow(boolean show) {
	clearanims(Spring.class);
	new Spring(show ? -sz.y : 0);
	targetshow = show;
    }

    public void hresize(int h) {
	clearanims(Spring.class);
	resize(sz.x, h);
    }

    public void resize(int w) {
	resize(new Coord(Math.max(w, selw + marg.x + UI.scale(10) + marg.x), sz.y));
    }

    public void move(Coord base) {
	this.c = (this.base = base).add(0, visible ? -sz.y : 0);
    }

    public void expand() {
	if(!visible)
	    sshow(true);
    }

    public void show() {
	super.show();
	targetshow = true;
    }

    public void hide() {
	super.hide();
	targetshow = false;
    }

    private class QuickLine implements ReadLine.Owner {
	public final ReadLine buf;
	public final EntryChannel chan;
	
	private QuickLine(EntryChannel chan) {
	    this.buf = ReadLine.make(this, "");
	    this.chan = chan;
	}
	
	private void cancel() {
	    qline = null;
	    qgrab.remove();
	}
	
	public UI ui() {return(ui);}

	public void done(ReadLine buf) {
	    if(!buf.empty())
		chan.send(buf.line());
	    cancel();
	}

	public boolean key(KbdEvent ev) {
	    if(key_esc.match(ev)) {
		cancel();
		return(true);
	    } else {
		return(buf.key(ev.awt));
	    }
	}
    }

    private UI.Grab dm = null;
    private Coord doff;
    private static final int minh = 111;
    public boolean mousedown(MouseDownEvent ev) {
	int bmfx = (sz.x - bmf.sz().x) / 2;
	Coord c= ev.c;
	if((ev.b == 1) && (c.y < bmf.sz().y) && (c.x >= bmfx) && (c.x <= (bmfx + bmf.sz().x))) {
	    dm = ui.grabmouse(this);
	    doff = c;
	    return(true);
	} else {
	    return(super.mousedown(ev));
	}
    }

    public void mousemove(MouseMoveEvent ev) {
	super.mousemove(ev);
	if(dm != null)
	    resize(sz.x, Math.max(UI.scale(minh), Math.min(parent.sz.y - UI.scale(100), sz.y + doff.y - ev.c.y)));
    }

    public boolean mouseup(MouseUpEvent ev) {
	if(dm != null) {
	    dm.remove();
	    dm = null;
	    Utils.setprefi("chatsize", UI.unscale(sz.y));
	    return(true);
	} else {
	    return(super.mouseup(ev));
	}
    }

    public boolean keydown(KeyDownEvent ev) {
	boolean M = (ev.mods & KeyMatch.M) != 0;
	if(qline != null) {
	    if(M && (ev.code == ev.awt.VK_UP)) {
		Channel prev = this.sel;
		while(chansel.up()) {
		    if(this.sel instanceof EntryChannel)
			break;
		}
		if(!(this.sel instanceof EntryChannel)) {
		    select(prev);
		    return(true);
		}
		qline = new QuickLine((EntryChannel)sel);
		return(true);
	    } else if(M && (ev.code == ev.awt.VK_DOWN)) {
		Channel prev = this.sel;
		while(chansel.down()) {
		    if(this.sel instanceof EntryChannel)
			break;
		}
		if(!(this.sel instanceof EntryChannel)) {
		    select(prev);
		    return(true);
		}
		qline = new QuickLine((EntryChannel)sel);
		return(true);
	    }
	    qline.key(ev);
	    return(true);
	} else {
	    if(M && (ev.code == ev.awt.VK_UP)) {
		chansel.up();
		return(true);
	    } else if(M && (ev.code == ev.awt.VK_DOWN)) {
		chansel.down();
		return(true);
	    }
	    return(super.keydown(ev));
	}
    }

    public static final KeyBinding kb_quick = KeyBinding.get("chat-quick", KeyMatch.forcode(java.awt.event.KeyEvent.VK_ENTER, 0));
    public boolean globtype(GlobKeyEvent ev) {
	if(kb_quick.key().match(ev)) {
	    if(!visible && (sel instanceof EntryChannel)) {
		qgrab = ui.grabkeys(this);
		qline = new QuickLine((EntryChannel)sel);
		return(true);
	    }
	}
	return(super.globtype(ev));
    }
}
