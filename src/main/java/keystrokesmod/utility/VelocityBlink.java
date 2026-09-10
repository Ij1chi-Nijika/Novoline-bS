package keystrokesmod.utility;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/** Flux's independent outbound Velocity queue; releases bypass packet listeners. */
public final class VelocityBlink {
    private final Queue<Packet<?>> packets = new ArrayDeque<>();
    private boolean active;

    public synchronized void start() { active = true; }
    public synchronized boolean isActive() { return active; }

    public synchronized boolean offer(Packet<?> packet) {
        if (!active || packet instanceof C00PacketKeepAlive
                || packet instanceof C0FPacketConfirmTransaction
                || packet instanceof C19PacketResourcePackStatus
                || packet instanceof C01PacketChatMessage
                || packet instanceof C14PacketTabComplete) return false;
        packets.offer(packet);
        return true;
    }

    public synchronized void release(DoubleSupplier slowFactor, Consumer<Double> slow,
                                     Consumer<Packet<?>> send) {
        // Query KeepSprint without the active-blink override, exactly as Flux does.
        active = false;
        double factor = slowFactor.getAsDouble();
        for (Packet<?> packet : packets) {
            if (packet instanceof C02PacketUseEntity) slow.accept(factor);
        }
        Packet<?> packet;
        while ((packet = packets.poll()) != null) send.accept(packet);
    }

    public synchronized void dispatch(Consumer<Packet<?>> send) {
        active = false;
        Packet<?> packet;
        while ((packet = packets.poll()) != null) send.accept(packet);
    }

    public synchronized void clear() {
        active = false;
        packets.clear();
    }
}
