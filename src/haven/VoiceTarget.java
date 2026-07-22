package haven;

/* Brodgar voice: remembers the player a click most recently resolved to, so the
 * flower menu can offer a client-side "Mute voice" option. This is client-side
 * integration glue — the voice library only exposes the mute controls
 * (io.brodgar.voice.Voice.togglePlayerMuted, etc.). */
final class VoiceTarget {
    private static volatile long gob = -1;
    private static volatile long nanos = 0;

    private VoiceTarget() {}

    /** Record a clicked gob (or null). A non-player, the ground, or the local
     *  player clears any pending target. */
    static void note(Gob g, long selfGob) {
        if(g != null && g.id != selfGob && isPlayer(g)) {
            gob = g.id;
            nanos = System.nanoTime();
        } else if(g != null) {
            gob = -1;
        }
        // g == null (ground) leaves the last target; the time window expires it
    }

    /** The player clicked within the last few seconds, or -1. */
    static long recent() {
        long g = gob;
        if(g < 0 || System.nanoTime() - nanos > 3_000_000_000L)
            return(-1);
        return(g);
    }

    private static boolean isPlayer(Gob g) {
        Drawable d = g.getattr(Drawable.class);
        if(d == null)
            return(false);
        Resource res;
        try {
            res = d.getres();
        } catch(Loading l) {
            return(false);
        }
        return(res != null && "gfx/borka/body".equals(res.name));
    }
}
