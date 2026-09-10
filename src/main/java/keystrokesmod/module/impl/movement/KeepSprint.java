package keystrokesmod.module.impl.movement;

import keystrokesmod.event.AttackEvent;
import keystrokesmod.event.DispatchPacketEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.utility.KeepSprintTrace;
import keystrokesmod.module.setting.Setting;
import net.minecraft.network.play.client.C03PacketPlayer;
import net.minecraft.network.play.client.C0BPacketEntityAction;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import java.util.Locale;
import keystrokesmod.event.GameTickEvent;
import keystrokesmod.event.LeaderUpdateEvent;
import keystrokesmod.event.LivingUpdateEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.PacketUtils;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Flux KeepSprint, adapted to Raven's Minecraft 1.8.9 event and packet APIs. */
public class KeepSprint extends Module {
    private static final String[] MODES = {"Vanilla", "Prediction", "Legit", "Grim", "Buffer", "Packet"};
    private final SliderSetting mode = new SliderSetting("Mode", 4, MODES);
    private final ButtonSetting onHurt = new ButtonSetting("On Hurt", false);
    private final SliderSetting slowdown = new SliderSetting("Slowdown", 0, 0, 100, 1);
    private final ButtonSetting groundOnly = new ButtonSetting("Ground Only", false);
    private final ButtonSetting reachOnly = new ButtonSetting("Reach Only", false);
    private final ButtonSetting autoFactor = new ButtonSetting("Auto Factor", true);
    private final SliderSetting offsetBudget = new SliderSetting("Offset Budget", 50, 0, 100, 1);
    private final SliderSetting factor = new SliderSetting("Factor", 65, 0, 100, 1);
    private final ButtonSetting grimGroundOnly = new ButtonSetting("Grim Ground Only", true);
    private final SliderSetting bufferSlowdown = new SliderSetting("Buffer Slowdown", 100, 0, 100, 1);
    private final SliderSetting bufferMaxTicks = new SliderSetting("Buffer Max Ticks", 4, 1, 10, 1);
    private final ButtonSetting bufferGroundOnly = new ButtonSetting("Buffer Ground Only", true);

    private final ButtonSetting debugTrace = new ButtonSetting("Debug Trace", false);
    private final KeepSprintTrace trace = new KeepSprintTrace();
    private boolean tracing;

    private int disSprintTicks = 0;
    private final Deque<Packet<?>> pendingSwing = new ConcurrentLinkedDeque<>();
    private final Deque<BufferedAttack> bufferedAttacks = new ConcurrentLinkedDeque<>();
    private int swingTicks = 0;
    private Entity lastAttackTarget = null;

    private PredictionState predictionState = PredictionState.IDLE;
    private int predictionTicks = 0;
    private boolean predictionAttackHandled = false;

    public KeepSprint() {
        super("Keep Sprint", Module.category.movement, 0);
        registerSetting(mode);
        registerSetting(onHurt);
        registerSetting(slowdown);
        registerSetting(groundOnly);
        registerSetting(reachOnly);
        registerSetting(autoFactor);
        registerSetting(offsetBudget);
        registerSetting(factor);
        registerSetting(grimGroundOnly);
        registerSetting(bufferSlowdown);
        registerSetting(bufferMaxTicks);
        registerSetting(bufferGroundOnly);
        registerSetting(debugTrace);
    }

    private enum PredictionState {
        IDLE,
        WAITING,
        RESTORE
    }

    private static class BufferedAttack {
        final Packet<?> swing;
        final Packet<?> attack;
        final Entity target;
        int ticks;

        BufferedAttack(Packet<?> swing, Packet<?> attack, Entity target) {
            this.swing = swing;
            this.attack = attack;
            this.target = target;
        }
    }

    @Override
    public void onEnable() {
        trace.clear();
        tracing = false;
        disSprintTicks = 0;
        resetPrediction();
        pendingSwing.clear();
        bufferedAttacks.clear();
        swingTicks = 0;
        lastAttackTarget = null;
    }

    @Override
    public void onDisable() {
        trace.clear();
        tracing = false;
        resetPrediction();
        if (mc.thePlayer == null || mc.theWorld == null) {
            clearBuffer();
            return;
        }
        while (!pendingSwing.isEmpty()) {
            PacketUtils.sendPacketNoEvent(pendingSwing.poll());
        }
        while (!bufferedAttacks.isEmpty()) {
            BufferedAttack attack = bufferedAttacks.poll();
            PacketUtils.sendPacketNoEvent(attack.swing);
            PacketUtils.sendPacketNoEvent(attack.attack);
        }
        swingTicks = 0;
        lastAttackTarget = null;

        if (isMode("Legit") && mc.thePlayer != null) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), isPhysicallyDown(mc.gameSettings.keyBindSprint));
        }
    }

    public boolean shouldKeepSprint() {
        if (mc.thePlayer == null) return false;

        switch (getInfo()) {
            case "Prediction": return predictionState == PredictionState.RESTORE && (int) slowdown.getInput() != 60;
            case "Legit": return false;
            case "Grim": return !grimGroundOnly.isToggled() || mc.thePlayer.onGround;
            case "Buffer": return !bufferGroundOnly.isToggled() || mc.thePlayer.onGround;
            case "Packet": return true;
            default:
                if (groundOnly.isToggled() && !mc.thePlayer.onGround) return false;
                if (!reachOnly.isToggled()) return true;
                MovingObjectPosition hit = mc.objectMouseOver;
                return hit != null && hit.hitVec != null
                        && hit.hitVec.distanceTo(mc.thePlayer.getPositionEyes(1.0F)) > 3.0;
        }
    }

    public boolean isAttackNoSlow() {
        return isEnabled() && (shouldKeepSprint() || isMode("Buffer"));
    }

    public boolean isPacketMode() {
        return isMode("Packet");
    }

    public double getSlowFactor() {
        if (ModuleManager.velocity != null && ModuleManager.velocity.isKeepSprintBlinkActive()) return 1.0;
        switch (getInfo()) {
            case "Prediction": return predictionState == PredictionState.RESTORE ? getConfiguredSlowFactor() : 0.6;
            case "Legit": return 0.6;
            case "Grim": return getGrimFactor();
            case "Buffer": return bufferGroundOnly.isToggled() && mc.thePlayer != null && !mc.thePlayer.onGround ? 0.6 : 1.0;
            case "Packet": return 1.0;
            default: return getConfiguredSlowFactor();
        }
    }

    private double getConfiguredSlowFactor() {
        return 1.0 - 0.4 * slowdown.getInput() / 100.0;
    }

    private double getGrimFactor() {
        if (mc.thePlayer == null) return 1.0;
        if (!autoFactor.isToggled()) return factor.getInput() / 100.0;

        double speed = Math.hypot(mc.thePlayer.motionX, mc.thePlayer.motionZ);
        if (speed <= 0.0) return 1.0;

        double budget = 0.001 * offsetBudget.getInput() / 100.0;
        double maxFactor = speed * 0.6 < 0.005 ? budget / speed : 0.6 + budget / speed;
        return Math.min(1.0, maxFactor);
    }

    @SubscribeEvent
    public void onAttack(AttackEvent event) {
        lastAttackTarget = event.target;
        if (debugTrace.isToggled()) trace.record("AttackEvent target="
                + (event.target == null ? -1 : event.target.getEntityId()) + " " + tracePlayerState());

        if (isMode("Prediction")) {
            handlePredictionAttack(event);
            return;
        }

        if (isMode("Legit")) {
            disSprintTicks = 3;
        }

        if (isMode("Packet")) {
            mc.thePlayer.swingItem();
        }
    }

    private void handlePredictionAttack(AttackEvent event) {
        if (mc.thePlayer == null || predictionAttackHandled) return;

        if (event.target instanceof EntityPlayer) {
            switch (predictionState) {
                case IDLE: {
                    predictionState = mc.thePlayer.isSprinting()
                            ? PredictionState.WAITING
                            : PredictionState.RESTORE;
                    predictionTicks = 0;
                    break;
                }
                case WAITING: {
                    mc.thePlayer.setSprinting(false);
                    predictionTicks = 0;
                    predictionState = PredictionState.RESTORE;
                    break;
                }
                case RESTORE: {
                }
            }
        }

        predictionAttackHandled = true;
    }

    @SubscribeEvent
    public void onPlayerUpdate(LeaderUpdateEvent event) {
        if (event.post) return;

        if (mc.thePlayer == null) {
            resetPrediction();
            return;
        }

        if (isMode("Prediction")) {
            updatePrediction();
            return;
        }

        resetPrediction();
        if (!isMode("Legit") || disSprintTicks < 0) return;

        if (onHurt.isToggled() || mc.thePlayer.hurtTime == 0) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
            mc.thePlayer.setSprinting(false);
        }
        disSprintTicks--;
    }

    private void updatePrediction() {
        if (predictionTicks > 5) {
            resetPrediction();
            return;
        }

        switch (predictionState) {
            case WAITING: {
                mc.thePlayer.setSprinting(false);
                predictionTicks++;
                break;
            }
            case RESTORE: {
                resumePredictionSprint();
                resetPrediction();
                break;
            }
            case IDLE: {
            }
        }
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingUpdateEvent event) {
        if (mc.thePlayer == null || !isMode("Prediction")) return;

        switch (predictionState) {
            case WAITING: mc.thePlayer.setSprinting(false); break;
            case RESTORE: resumePredictionSprint(); break;
            case IDLE: {
            }
        }
    }

    private void resumePredictionSprint() {
        if (!mc.thePlayer.isUsingItem()) {
            mc.thePlayer.setSprinting(true);
            return;
        }

        Sprint sprint = ModuleManager.sprint;
        if (sprint != null && sprint.isEnabled()) {
            mc.thePlayer.setSprinting(true);
        }
    }

    public void preparePredictionAutoBlock() {
        if (!isEnabled() || !isMode("Prediction") || mc.thePlayer == null) return;

        predictionState = PredictionState.WAITING;
        predictionTicks = 0;
        predictionAttackHandled = false;
    }

    private void resetPrediction() {
        predictionState = PredictionState.IDLE;
        predictionTicks = 0;
        predictionAttackHandled = false;
    }

    @SubscribeEvent
    public void onWorld(WorldEvent.Unload event) {
        if (event.world != mc.theWorld) return;
        clearBuffer();
        resetPrediction();
        trace.clear();
    }

    @SubscribeEvent
    public void onPacket(SendPacketEvent event) {
        if (!isEnabled() || !isMode("Buffer")) return;
        if (mc.thePlayer == null || (bufferGroundOnly.isToggled() && !mc.thePlayer.onGround)) return;

        if (event.getPacket() instanceof C0APacketAnimation) {
            event.setCanceled(true);
            pendingSwing.offer(event.getPacket());
        } else if (event.getPacket() instanceof C02PacketUseEntity) {
            if (lastAttackTarget == null || pendingSwing.isEmpty()) {
                return;
            }
            event.setCanceled(true);
            bufferedAttacks.offer(new BufferedAttack(pendingSwing.poll(), event.getPacket(), lastAttackTarget));
            lastAttackTarget = null;
        }
    }

    @SubscribeEvent
    public void onTick(GameTickEvent event) {
        if (debugTrace.isToggled() && mc.thePlayer != null) {
            if (!tracing) trace.clear();
            tracing = true;
            trace.configuration(traceConfiguration());
            trace.record("tick " + tracePlayerState());
        } else if (tracing) {
            trace.clear();
            tracing = false;
        }
        predictionAttackHandled = false;

        if (!isEnabled() || !isMode("Buffer") || mc.thePlayer == null) return;

        if (bufferedAttacks.isEmpty()) {
            if (!pendingSwing.isEmpty()) {
                if (++swingTicks > 2) {
                    swingTicks = 0;
                    while (!pendingSwing.isEmpty()) {
                        PacketUtils.sendPacketNoEvent(pendingSwing.poll());
                    }
                }
            } else {
                swingTicks = 0;
            }
            return;
        }

        BufferedAttack attack = bufferedAttacks.peek();
        attack.ticks++;
        if (attack.ticks > (int) bufferMaxTicks.getInput()) {
            bufferedAttacks.poll();
            return;
        }
        if (attack.target == null || !attack.target.isEntityAlive()) {
            bufferedAttacks.poll();
            return;
        }

        KillAura aura = ModuleManager.killAura;
        boolean auraAiming = aura != null && aura.isEnabled()
                && KillAura.target == attack.target;
        if (!auraAiming) {
            if (mc.objectMouseOver == null || mc.objectMouseOver.entityHit != attack.target) {
                return;
            }
        }

        if (aura != null && aura.isEnabled() && aura.isKeepSprintBlocking()) {
            return;
        }

        double factor = 0.6 + 0.4 * (1.0 - bufferSlowdown.getInput() / 100.0);
        mc.thePlayer.motionX *= factor;
        mc.thePlayer.motionZ *= factor;

        PacketUtils.sendPacketNoEvent(attack.swing);
        PacketUtils.sendPacketNoEvent(attack.attack);
        bufferedAttacks.poll();
    }

    /** Called at the actual slowdown branch, not merely on an attack click. */
    public void traceAttackSlowdown(String phase) {
        if (debugTrace.isToggled()) trace.record("slowdown " + phase + " " + tracePlayerState());
    }

    @SubscribeEvent
    public void onTraceDispatch(DispatchPacketEvent event) {
        if (!debugTrace.isToggled() || mc.thePlayer == null) return;
        Packet<?> packet = event.getPacket();
        String detail;
        if (packet instanceof C02PacketUseEntity) detail = "attack/interact " + ((C02PacketUseEntity) packet).getAction();
        else if (packet instanceof C0APacketAnimation) detail = "swing";
        else if (packet instanceof C0BPacketEntityAction) detail = "sprint/sneak " + ((C0BPacketEntityAction) packet).getAction();
        else if (packet instanceof C03PacketPlayer) {
            C03PacketPlayer movement = (C03PacketPlayer) packet;
            detail = String.format(Locale.ROOT, "move position=%s x=%.5f y=%.5f z=%.5f ground=%s",
                    movement.isMoving(), movement.getPositionX(), movement.getPositionY(), movement.getPositionZ(), movement.isOnGround());
        } else if (packet instanceof C07PacketPlayerDigging) detail = "dig/release " + ((C07PacketPlayerDigging) packet).getStatus();
        else if (packet instanceof C08PacketPlayerBlockPlacement) detail = "use/block";
        else if (packet instanceof C09PacketHeldItemChange) detail = "slot " + ((C09PacketHeldItemChange) packet).getSlotId();
        else return;
        trace.record("SEND " + detail + " " + tracePlayerState());
    }

    @SubscribeEvent
    public void onTraceReceive(ReceivePacketEvent event) {
        if (!debugTrace.isToggled() || mc.thePlayer == null) return;
        if (event.getPacket() instanceof S08PacketPlayerPosLook) {
            S08PacketPlayerPosLook correction = (S08PacketPlayerPosLook) event.getPacket();
            trace.correction(String.format(Locale.ROOT, "S08 x=%.5f y=%.5f z=%.5f flags=%s %s",
                    correction.getX(), correction.getY(), correction.getZ(), correction.func_179834_f(), tracePlayerState()));
        } else if (event.getPacket() instanceof S12PacketEntityVelocity) {
            S12PacketEntityVelocity velocity = (S12PacketEntityVelocity) event.getPacket();
            if (velocity.getEntityID() == mc.thePlayer.getEntityId()) trace.record("RECV velocity "
                    + velocity.getMotionX() + "," + velocity.getMotionY() + "," + velocity.getMotionZ());
        }
    }

    private String tracePlayerState() {
        if (mc.thePlayer == null) return "no-player";
        return String.format(Locale.ROOT, "tick=%d sprint=%s ground=%s hurt=%d using=%s motion=%.6f,%.6f,%.6f",
                mc.thePlayer.ticksExisted, mc.thePlayer.isSprinting(), mc.thePlayer.onGround, mc.thePlayer.hurtTime,
                mc.thePlayer.isUsingItem(), mc.thePlayer.motionX, mc.thePlayer.motionY, mc.thePlayer.motionZ);
    }

    private String traceConfiguration() {
        StringBuilder out = new StringBuilder("FluxChain=2 KeepSprint=").append(getInfo());
        for (Module module : new Module[]{ModuleManager.killAura, ModuleManager.velocity, ModuleManager.sprint,
                ModuleManager.noSlow, ModuleManager.wTap}) {
            if (module == null) continue;
            out.append(" | ").append(module.getName()).append(" enabled=").append(module.isEnabled());
            if (!module.isEnabled()) continue;
            for (Setting setting : module.getSettings()) {
                if (setting instanceof SliderSetting) out.append(" ").append(setting.getProfileKey()).append("=").append(((SliderSetting) setting).getInput());
                else if (setting instanceof ButtonSetting) out.append(" ").append(setting.getProfileKey()).append("=").append(((ButtonSetting) setting).isToggled());
            }
        }
        return out.toString();
    }

    private void clearBuffer() {
        pendingSwing.clear();
        bufferedAttacks.clear();
        swingTicks = 0;
        lastAttackTarget = null;
    }

    private boolean isPhysicallyDown(KeyBinding key) {
        int code = key.getKeyCode();
        return code < 0 ? code + 100 >= 0 && Mouse.isButtonDown(code + 100)
                : code > 0 && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }

    private boolean isMode(String name) {
        return name.equals(getInfo());
    }

    @Override
    public String getInfo() {
        return MODES[Math.max(0, Math.min(MODES.length - 1, (int) mode.getInput()))];
    }

    @Override
    public void guiUpdate() {
        onHurt.setVisible(isMode("Legit"), this);
        slowdown.setVisible(isMode("Vanilla") || isMode("Prediction"), this);
        groundOnly.setVisible(isMode("Vanilla"), this);
        reachOnly.setVisible(isMode("Vanilla"), this);
        autoFactor.setVisible(isMode("Grim"), this);
        offsetBudget.setVisible(isMode("Grim") && autoFactor.isToggled(), this);
        factor.setVisible(isMode("Grim") && !autoFactor.isToggled(), this);
        grimGroundOnly.setVisible(isMode("Grim"), this);
        bufferSlowdown.setVisible(isMode("Buffer"), this);
        bufferMaxTicks.setVisible(isMode("Buffer"), this);
        bufferGroundOnly.setVisible(isMode("Buffer"), this);
    }
}
