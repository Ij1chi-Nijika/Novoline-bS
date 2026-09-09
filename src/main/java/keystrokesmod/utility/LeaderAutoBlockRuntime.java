package keystrokesmod.utility;

import keystrokesmod.module.ModuleManager;





import keystrokesmod.module.impl.player.BlinkSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.network.Packet;
import net.minecraft.network.handshake.client.C00Handshake;
import net.minecraft.network.login.client.C00PacketLoginStart;
import net.minecraft.network.login.client.C01PacketEncryptionResponse;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.status.client.C00PacketServerQuery;
import net.minecraft.network.status.client.C01PacketPing;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

public final class LeaderAutoBlockRuntime {
    public enum BlinkModules { NONE, AUTO_BLOCK }
    public static final LeaderAutoBlockRuntime INSTANCE = new LeaderAutoBlockRuntime();
    public boolean digging, placing;

    // Called after packet listeners and before Raven's general lag queue.
    public boolean handlePacket(Packet<?> packet) {
        if (packet instanceof net.minecraft.network.play.client.C07PacketPlayerDigging) digging = true;
        if (packet instanceof net.minecraft.network.play.client.C08PacketPlayerBlockPlacement) placing = true;
        if (packet instanceof C03PacketPlayer) digging = placing = false;
        if (packet instanceof C00Handshake || packet instanceof C00PacketLoginStart
                || packet instanceof C00PacketServerQuery || packet instanceof C01PacketPing
                || packet instanceof C01PacketEncryptionResponse) setBlink(false);
        return blinking && offerPacket(packet);
    }

    public void setBlink(boolean state) { setBlinkState(state, BlinkModules.AUTO_BLOCK); }

    public void disconnect() {
        blinkedPackets.clear();
        blinkModule = BlinkModules.NONE;
        blinking = slowReleasing = digging = placing = false;
        slowReleaseTicks = 0;
    }

    public static Minecraft mc = Minecraft.getMinecraft();
    public BlinkModules blinkModule = BlinkModules.NONE;
    public boolean blinking = false;
    public Deque<Packet<?>> blinkedPackets = new ConcurrentLinkedDeque<>();
    private boolean slowReleasing = false;
    private int slowReleaseTicks = 0;

    public boolean offerPacket(Packet<?> packet) {
        if (this.blinkModule == BlinkModules.NONE || packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) {
            return false;
        } else if (this.blinkedPackets.isEmpty() && packet instanceof C0FPacketConfirmTransaction) {
            return false;
        } else {
            this.blinkedPackets.offer(packet);
            return true;
        }
    }

    private BlinkSettings getBlinkSettings() {
        return ModuleManager.blinkSettings;
    }

    public boolean setBlinkState(boolean state, BlinkModules module) {
        if (module == BlinkModules.NONE) {
            return false;
        }
        if (state) {
            if (this.blinkModule != BlinkModules.NONE && this.blinkModule != module) {
                return false;
            }
            this.blinkModule = module;
            this.blinking = true;
            BlinkSettings settings = getBlinkSettings();
            if (settings != null && settings.slowRelease.isToggled() && (int) settings.slowReleaseTime.getInput() == 0) {
                this.slowReleasing = true;
                this.slowReleaseTicks = 0;
            }
        } else {
            if (blinkModule != module) {
                return false;
            }
            BlinkSettings settings = getBlinkSettings();
            if (settings != null && settings.slowRelease.isToggled() && (int) settings.slowReleaseTime.getInput() == 1) {
                this.blinking = false;
                this.slowReleasing = true;
                this.slowReleaseTicks = 0;
                return true;
            }
            this.blinking = false;
            this.slowReleasing = false;
            if (Minecraft.getMinecraft().getNetHandler() != null && this.blinkedPackets.isEmpty()) {
                this.blinkModule = BlinkModules.NONE;
                return true;
            }
            for (Packet<?> blinkedPacket : blinkedPackets) {
                PacketUtils.sendPacketNoEvent(blinkedPacket);
            }
            this.blinkedPackets.clear();
            this.blinkModule = BlinkModules.NONE;
        }
        return true;
    }

    public BlinkModules getBlinkingModule() {
        return this.blinkModule;
    }

    public long countMovement() {
        return this.blinkedPackets.stream().filter(packet -> packet instanceof C03PacketPlayer).count();
    }

    public boolean isBlinking() {
        return blinking;
    }

    public void tick() {
        if (mc.thePlayer == null) { disconnect(); return; }
        if (mc.thePlayer.isDead) {
            slowReleasing = false;
            setBlinkState(false, blinkModule);
        }
        if (slowReleasing) processSlowRelease();
    }

    private void processSlowRelease() {
        BlinkSettings settings = getBlinkSettings();
        if (settings == null || !settings.slowRelease.isToggled()) {
            slowReleasing = false;
            flushRemaining();
            return;
        }
        slowReleaseTicks++;
        if (slowReleaseTicks < (int) settings.slowReleaseDelay.getInput()) {
            return;
        }
        slowReleaseTicks = 0;
        int maxTotal = (int) settings.maxPacketsPerTick.getInput();
        int maxC03 = (int) settings.maxC03PacketsPerTick.getInput();
        int released = 0;
        int c03Released = 0;
        int size = blinkedPackets.size();
        for (int i = 0; i < size && released < maxTotal; i++) {
            Packet<?> pkt = blinkedPackets.poll();
            if (pkt == null) break;
            if (pkt instanceof C03PacketPlayer) {
                if (c03Released >= maxC03) {
                    blinkedPackets.offer(pkt);
                    continue;
                }
                c03Released++;
            }
            boolean wasBlinking = this.blinking;
            this.blinking = false;
            PacketUtils.sendPacketNoEvent(pkt);
            this.blinking = wasBlinking;
            released++;
        }
        if (blinkedPackets.isEmpty()) {
            if (!blinking) {
                slowReleasing = false;
                this.blinkModule = BlinkModules.NONE;
            }
        }
    }

    private void flushRemaining() {
        boolean wasBlinking = this.blinking;
        this.blinking = false;
        for (Packet<?> blinkedPacket : blinkedPackets) {
            PacketUtils.sendPacketNoEvent(blinkedPacket);
        }
        this.blinking = wasBlinking;
        blinkedPackets.clear();
        if (!wasBlinking) {
            this.blinkModule = BlinkModules.NONE;
        }
    }
}
