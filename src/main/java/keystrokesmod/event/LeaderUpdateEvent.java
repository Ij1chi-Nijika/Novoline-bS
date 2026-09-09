package keystrokesmod.event;

import net.minecraftforge.fml.common.eventhandler.Event;

/** Leader UpdateEvent positions: EntityPlayerSP.onUpdate HEAD and RETURN, including timer subupdates. */
public final class LeaderUpdateEvent extends Event {
    public final boolean post;
    public LeaderUpdateEvent(boolean post) { this.post = post; }
}
