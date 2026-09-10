package keystrokesmod.utility;

/** Flux Hypixel's block/attack stages, independent of Minecraft packet transport. */
public final class FluxHypixelCycle {
    private int stage = 1;
    private boolean initialized;
    private boolean needsReblock;

    public boolean advance(int aps, boolean blocking, Runnable releaseBlock) {
        if (!initialized) {
            stage = blocking ? 0 : 1;
            initialized = true;
        }
        switch (stage) {
            case 0:
                if (aps == 10 || aps == 14) releaseBlock.run();
                stage = aps == 10 ? 1 : 3;
                break;
            case 1:
                stage = 2;
                break;
            case 3:
                if (aps == 3 || aps == 5) stage = 5;
                else if (aps == 7 || aps == 14) {
                    releaseBlock.run();
                    stage = 1;
                }
                break;
            case 5:
                if (aps == 3 || aps == 5) {
                    releaseBlock.run();
                    stage = 1;
                }
                break;
            default:
                break;
        }
        needsReblock = stage == 2;
        return stage == 2 || stage == 5 || stage == 6;
    }

    public boolean onAttack() {
        boolean reblock = needsReblock;
        if (reblock) stage = 0;
        needsReblock = false;
        return reblock;
    }

    public void reset() {
        stage = 1;
        initialized = false;
        needsReblock = false;
    }
}
