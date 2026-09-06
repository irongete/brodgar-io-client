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
import haven.render.*;
import haven.Audio.CS;

public class AudioSprite {
    public static List<Audio.Clip> clips(Resource res, String id) {
	return(new ArrayList<>(res.layers(Audio.clip, clip -> clip.layerid().equals(id))));
    }

    public static Audio.Clip randoom(Resource res, String id) {
	List<Audio.Clip> cl = clips(res, id);
	if(!cl.isEmpty())
	    return(cl.get((int)(Math.random() * cl.size())));
	return(null);
    }

    public static final Sprite.Factory fact = new Sprite.Factory() {
	    public Sprite create(Sprite.Owner owner, Resource res, Message sdt) {
		{
		    Audio.Clip clip = randoom(res, "cl");
		    if(clip != null)
			return(new ClipSprite(owner, res, clip));
		}
		{
		    List<Audio.Clip> clips = clips(res, "rep");
		    if(!clips.isEmpty())
			return(new RepeatSprite(owner, res, randoom(res, "beg"), clips, randoom(res, "end")));
		}
		{
		    if((res.layer(Audio.clip, "amb") != null) || (res.layer(ClipAmbiance.Desc.class) != null))
			return(new Ambience(owner, res));
		}
		return(null);
	    }
	};

    public static class ClipSprite extends Sprite {
	public final ActAudio.PosClip clip;
	private boolean done = false;

	public ClipSprite(Owner owner, Resource res, Audio.Clip clip) {
	    super(owner, res);
	    this.clip = new ActAudio.PosClip(new Audio.Monitor(clip.stream()) {
		    protected void eof() {
			super.eof();
			done = true;
		    }
		});
	}

	public void added(RenderTree.Slot slot) {
	    ActAudio list = slot.state().get(ActAudio.audio);
	    /* There is a strong case to be made that audio-thread
	     * overloading should be less heuristically prevented than
	     * having a fixed per-mixer limit like this which may or
	     * may not fit certain system requirements, but it isn't
	     * immediately obvious what the correct solution would
	     * be. Combined with the problem below, there may be a
	     * case to be made that there should be a way to get rid
	     * of audio clips apart from having to completely play
	     * them through. */
	    if((list == null) || (list.pos.size() > 64)) {
		done = true;
	    } else {
		slot.add(clip);
	    }
	}

	/* rts: nobody is looking at this session, so this clip will never reach an ActAudio and can only
	 * end here. It is the same answer `added` gives when the mixer is full: the sound does not
	 * happen. Deferring it instead is what left every sfx of a background session alive, stream and
	 * all, until the session was promoted and the whole backlog played at once. */
	public void unheard() {
	    done = true;
	}

	public boolean tick(double dt) {
	    /* XXX: This is slightly bad, because virtual sprites that
	     * are stuck as loading (by getting outside the map, for
	     * instance), never play and therefore never get done,
	     * effectively leaking. For now, this is seldom a problem
	     * because in practice most (all?) virtual audio-sprites
	     * come from Skeleton.FxTrack which memoizes its origin
	     * instead of asking the map for it, but also see comment
	     * in glsl.MiscLib.maploc. Solve pl0x. */
	    return(done);
	}
    }

    public static class RepeatSprite extends Sprite implements Sprite.CDel {
	private ActAudio.PosClip clip;
	private final Audio.Clip end;
	private final Collection<RenderTree.Slot> slots = new ArrayList<>(1);
	/* rts: told that nothing it plays will be heard (Gob.ctick, a session nobody is looking at), and
	 * whether the server has already ended it. Its only ending is the end clip reaching eof in a
	 * mixer, and a sprite in no tree hands its clip to no mixer: delete() re-added the end clip over an
	 * empty slot list, so the overlay of a background session never ended and never left `ols`. */
	private boolean unheard = false, deleted = false;

	public RepeatSprite(Owner owner, Resource res, Audio.Clip beg, List<Audio.Clip> clips, Audio.Clip end) {
	    super(owner, res);
	    this.end = end;
	    CS rep = new Audio.Repeater() {
		    private boolean f = true;

		    public CS cons() {
			if(f && (beg != null)) {
			    f = false;
			    return(beg.stream());
			}
			return(clips.get((int)(Math.random() * clips.size())).stream());
		    }
		};
	    this.clip = new ActAudio.PosClip(rep);
	}

	private void parts(RenderTree.Slot slot) {
	    if(clip != null)
		slot.add(clip);
	}

	public void added(RenderTree.Slot slot) {
	    parts(slot);
	    slots.add(slot);
	    unheard = false;   // rts: it is in a tree now, so it is heard
	}

	public void removed(RenderTree.Slot slot) {
	    slots.remove(slot);
	}

	public boolean tick(double dt) {
	    return(clip == null);
	}

	/* rts: the same answer ClipSprite gives -- the sound does not happen. For a loop that means: if the
	 * server has already ended it, the end clip was waiting for a mixer nobody will give it, so end now;
	 * otherwise remember, so that delete() ends it outright rather than re-adding an end clip to nothing. */
	public void unheard() {
	    unheard = true;
	    if(deleted)
		clip = null;
	}

	public void delete() {
	    deleted = true;
	    if(unheard) {
		clip = null;
		return;
	    }
	    if(end != null) {
		clip = new ActAudio.PosClip(new Audio.Monitor(end.stream()) {
			protected void eof() {
			    super.eof();
			    RepeatSprite.this.clip = null;
			}
		    });
		RUtils.readd(slots, this::parts, () -> {});
	    } else {
		clip = null;
	    }
	}
    }

    public static class Ambience extends Sprite {
	public final RenderTree.Node amb;

	public Ambience(Owner owner, Resource res) {
	    super(owner, res);
	    ClipAmbiance.Desc clamb = res.layer(ClipAmbiance.Desc.class);
	    if(clamb != null)
		this.amb = clamb.spr;
	    else
		this.amb = new ActAudio.Ambience(res);
	}

	public void added(RenderTree.Slot slot) {
	    if(amb != null)
		slot.add(amb);
	}
    }
}
