package keystrokesmod.utility;

/** Version-independent timing decisions taken from Flux's Aura and AutoBlock. */
public final class FluxCombatRules {
    private FluxCombatRules() {}

    public static long attackDelay(int cps) {
        return 700L / Math.max(1, cps);
    }

    public static boolean velocityReleaseTiming(boolean risingGate, double motionY, boolean airBuffer,
                                                boolean groundDelay, boolean onGround, boolean liquid,
                                                int elapsed, int delay) {
        if (risingGate && motionY > 0) return false;
        return airBuffer || groundDelay ? onGround : liquid || elapsed >= delay;
    }

    public static boolean customReduce(int phase, int tick, int maximum, int attackTick) {
        int period = Math.max(1, maximum - 1);
        if (phase == 0) return tick == attackTick;
        if (phase == 1) return tick == attackTick % period;
        return tick == (attackTick - 2 + period) % period;
    }

    public static boolean lagReduce(int mode, int phase, int tick) {
        switch (mode) {
            case 0: return phase == 2 ? tick == 1 : tick == 0;
            case 1: return phase == 2 ? tick == 2 : phase == 1 ? tick == 0 : tick == 0 || tick == 2;
            case 2: return phase == 2 ? tick == 3 : phase == 1 ? tick == 0 : tick == 0 || tick == 3;
            case 3: return phase == 2 ? tick == 4 : phase == 1 ? tick == 0 : tick == 0 || tick == 2 || tick == 4;
            case 4: return phase == 2 ? tick == 4 : phase == 1 ? tick == 0 : tick == 0 || tick == 4;
            default: return false;
        }
    }
}
