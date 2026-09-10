package keystrokesmod.utility;

import net.minecraft.network.Packet;
import net.minecraft.network.play.client.*;
import com.google.gson.JsonObject;
import keystrokesmod.utility.profile.KeepSprintProfileMigration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Deterministic transport/stage regression checks without starting Minecraft. */
public final class KeepSprintLinkageTest {
    private static int checks;

    public static void main(String[] args) {
        testBlinkRelease();
        testBlinkLifecycle();
        testHypixelStages();
        testLegacyProfiles();
        testFluxCombatRules();
        System.out.println("KeepSprint linkage: " + checks + " checks passed");
    }

    private static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }

    private static void testBlinkRelease() {
        VelocityBlink blink = new VelocityBlink();
        Packet<?> swing = new C0APacketAnimation();
        Packet<?> attack = new C02PacketUseEntity();
        Packet<?> movement = new C03PacketPlayer();
        check(!blink.offer(attack), "inactive queue must pass packets");
        blink.start();
        for (Packet<?> ignored : new Packet<?>[]{new C00PacketKeepAlive(), new C0FPacketConfirmTransaction(),
                new C19PacketResourcePackStatus(), new C01PacketChatMessage(), new C14PacketTabComplete()}) {
            check(!blink.offer(ignored), "protocol/chat packet must bypass blink");
        }
        check(blink.offer(swing) && blink.offer(attack) && blink.offer(movement) && blink.offer(attack), "queue combat and movement");
        List<Packet<?>> sent = new ArrayList<>();
        double[] motion = {1, 2};
        blink.release(() -> {
            check(!blink.isActive(), "factor must be read without active override");
            return 0.8;
        }, factor -> {
            check(sent.isEmpty(), "all slowdown precedes packet release");
            motion[0] *= factor;
            motion[1] *= factor;
        }, sent::add);
        check(sent.equals(Arrays.asList(swing, attack, movement, attack)), "FIFO release exactly once");
        check(Math.abs(motion[0] - .64) < 1e-10 && Math.abs(motion[1] - 1.28) < 1e-10, "two interactions apply two factors");
        blink.dispatch(sent::add);
        check(sent.size() == 4 && !blink.isActive(), "release drains queue");
    }

    private static void testBlinkLifecycle() {
        VelocityBlink blink = new VelocityBlink();
        List<Packet<?>> sent = new ArrayList<>();
        blink.start();
        blink.offer(new C02PacketUseEntity());
        blink.clear();
        blink.dispatch(sent::add);
        check(sent.isEmpty() && !blink.isActive(), "world unload discards stale packets");
        blink.start();
        blink.offer(new C03PacketPlayer());
        blink.dispatch(sent::add);
        check(sent.size() == 1 && !blink.isActive(), "disable flushes queue");
        blink.start();
        blink.offer(new C02PacketUseEntity());
        double[] speed = {1};
        blink.release(() -> 1, factor -> speed[0] *= factor, sent::add);
        check(speed[0] == 1, "zero-slowdown KeepSprint survives release");
    }

    private static void testHypixelStages() {
        for (int aps : new int[]{3, 5, 7, 10, 14}) {
            FluxHypixelCycle cycle = new FluxHypixelCycle();
            boolean[] blocking = {false};
            int[] releases = {0};
            Runnable release = () -> { blocking[0] = false; releases[0]++; };
            check(cycle.advance(aps, false, release), "initial unblocked stage allows attack");
            check(cycle.onAttack(), "first successful attack requests reblock");
            blocking[0] = true;
            check(!cycle.advance(aps, true, release), "first blocked stage delays attack");
            if (aps == 10) {
                check(!blocking[0], "10 APS releases on stage zero");
                check(cycle.advance(aps, false, release), "10 APS next tick attacks");
            } else if (aps == 3 || aps == 5) {
                check(cycle.advance(aps, true, release), "3/5 APS intermediate attack stage");
                check(!cycle.onAttack(), "intermediate stage must not request reblock");
                check(!cycle.advance(aps, true, release) && !blocking[0], "next stage releases before attack");
                check(cycle.advance(aps, false, release), "release followed by attack stage");
            } else {
                check(!cycle.advance(aps, blocking[0], release) && !blocking[0], "7/14 APS release stage");
                check(cycle.advance(aps, false, release), "7/14 APS attack after release");
            }
            check(cycle.onAttack(), "normal attack restarts block cycle");
            check(releases[0] > 0, "cycle releases block");
            cycle.reset();
            check(cycle.advance(aps, false, release), "reset restarts idle cycle");
            check(cycle.advance(aps, false, release), "failed attack leaves attack stage available");
        }
    }

    private static void testLegacyProfiles() {
        JsonObject normal = legacyProfile(0, 40);
        check(!KeepSprintProfileMigration.migrate(normal), "Normal has a compatible mode");
        check(normal.get("Mode").getAsInt() == 0, "Normal remains Vanilla");
        check(normal.get("Slowdown").getAsDouble() == 100, "40 percent reduction must not become zero reduction");
        check(normal.get("enabled").getAsBoolean(), "compatible mode preserves enabled state");
        JsonObject partial = legacyProfile(0, 20);
        KeepSprintProfileMigration.migrate(partial);
        check(partial.get("Slowdown").getAsDouble() == 50, "scale partial slowdown");
        JsonObject watchdog = legacyProfile(2, 40);
        KeepSprintProfileMigration.migrate(watchdog);
        check(watchdog.get("Mode").getAsInt() == 5, "WatchDog must not become Legit");
        for (int mode : new int[]{1, 3}) {
            JsonObject incompatible = legacyProfile(mode, 40);
            check(KeepSprintProfileMigration.migrate(incompatible), "incompatible strategy needs selection");
            check(!incompatible.get("enabled").getAsBoolean(), "do not silently enable incompatible strategy");
        }
        normal.addProperty("Mode", 1);
        normal.addProperty("Slowdown", 25);
        check(!KeepSprintProfileMigration.migrate(normal), "new profile must not migrate twice");
        check(normal.get("Mode").getAsInt() == 1 && normal.get("Slowdown").getAsDouble() == 25,
                "preserve explicitly saved Flux settings");
    }

    private static JsonObject legacyProfile(int mode, double slow) {
        JsonObject data = new JsonObject();
        data.addProperty("Mode", mode);
        data.addProperty("Slow %", slow);
        data.addProperty("enabled", true);
        return data;
    }

    private static void testFluxCombatRules() {
        check(FluxCombatRules.attackDelay(14) == 50, "Flux's 14 CPS setting uses a 50ms interval");
        check(FluxCombatRules.attackDelay(10) == 70, "Flux does not use Raven's 1000/CPS jitter");
        check(FluxCombatRules.attackDelay(0) > 0, "invalid profile cannot divide by zero");
        int[][][] allowed = {
                {{0}, {0}, {1}}, {{0, 2}, {0}, {2}}, {{0, 3}, {0}, {3}},
                {{0, 2, 4}, {0}, {4}}, {{0, 4}, {0}, {4}}
        };
        for (int mode = 0; mode < allowed.length; mode++) {
            for (int phase = 0; phase < 3; phase++) {
                for (int tick = 0; tick < 6; tick++) {
                    boolean expected = false;
                    for (int value : allowed[mode][phase]) expected |= value == tick;
                    check(FluxCombatRules.lagReduce(mode, phase, tick) == expected,
                            "AutoBlock phase mismatch: " + mode + "/" + phase + "/" + tick);
                }
            }
        }
        check(!FluxCombatRules.lagReduce(8, 0, 0), "Flux has no Swap release-phase rule");
        check(FluxCombatRules.customReduce(0, 2, 5, 2), "custom attack stage");
        check(FluxCombatRules.customReduce(2, 0, 5, 2), "custom release-before stage wraps");
        check(!FluxCombatRules.velocityReleaseTiming(true, .1, false, false, false, false, 10, 3), "rising gate beats timeout");
        check(!FluxCombatRules.velocityReleaseTiming(false, -.1, true, false, false, true, 10, 3), "ground wait beats liquid/timeout");
        check(FluxCombatRules.velocityReleaseTiming(false, 0, false, true, true, false, 0, 3), "ground delay releases on landing");
        check(!FluxCombatRules.velocityReleaseTiming(false, -.1, false, false, false, false, 2, 3), "delay not elapsed");
        check(FluxCombatRules.velocityReleaseTiming(false, -.1, false, false, false, false, 3, 3), "delay releases at boundary");
    }
}
