package keystrokesmod.utility.profile;

import com.google.gson.JsonObject;

/** Converts pre-Flux settings before the profile loader applies mode/enabled state. */
public final class KeepSprintProfileMigration {
    private KeepSprintProfileMigration() {}

    public static boolean migrate(JsonObject data) {
        if (data.has("Slowdown") || !(data.has("Slow %") || data.has("Stop Sprint")
                || data.has("WatchDog slowdown") || data.has("In combat cancel rate"))) return false;

        int oldMode = data.has("Mode") ? data.get("Mode").getAsInt() : 0;
        // Normal -> Vanilla, WatchDog -> Packet, Buffer -> Buffer.
        int mode = oldMode == 2 ? 5 : oldMode == 3 ? 4 : 0;
        data.addProperty("Mode", mode);
        double oldSlow = data.has("Slow %") ? data.get("Slow %").getAsDouble() : 40.0;
        data.addProperty("Slowdown", Math.max(0.0, Math.min(40.0, oldSlow)) * 2.5);
        data.addProperty("Ground Only", data.has("Disable while jumping")
                && data.get("Disable while jumping").getAsBoolean());
        data.addProperty("Reach Only", data.has("Only reduce reach hits")
                && data.get("Only reduce reach hits").getAsBoolean());
        // Smart's click filtering and the old Aura-only Buffer have no equivalent
        // in Flux. Do not silently enable a different attack strategy on load.
        boolean needsSelection = oldMode != 0 && oldMode != 2;
        if (needsSelection) data.addProperty("enabled", false);
        return needsSelection;
    }
}
