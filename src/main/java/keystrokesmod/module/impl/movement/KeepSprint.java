package keystrokesmod.module.impl.movement;

import keystrokesmod.event.PreAttackEvent;
import keystrokesmod.event.PrePlayerInteractEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.setting.Setting;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.CombatTargeting;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class KeepSprint extends Module {
    private static final String[] MODES = {"Normal", "Smart"};
    private static final double HIT_RANGE_SQ = 9.0D;
    private static final int HURT_WINDOW_TICKS = 10;
    private static final int SERVER_CONFIRM_COOLDOWN_TICKS = HURT_WINDOW_TICKS;
    private static final int SERVER_CONFIRM_TIMEOUT_TICKS = 30;
    private static final int BLOCK_WAIT_FIRST = 1;
    private static final int BLOCK_SERVER_COOLDOWN = 1 << 3;
    private static final int BLOCK_PREDICTED_BURST = 1 << 4;

    public static SliderSetting slow;
    public static ButtonSetting stopSprint;
    public static ButtonSetting disableWhileJump;
    public static ButtonSetting reduceReachHits;

    private final SliderSetting mode;
    private final DescriptionSetting smartDescription;
    private final SliderSetting pauseDuration;
    private final SliderSetting waitForFirstHit;
    private final ButtonSetting disableDuringKnockback;
    private final ButtonSetting useServerAttackTime;
    private final ButtonSetting fakeSwing;
    private final SliderSetting inCombatCancelRate;
    private final SliderSetting missedSwingsCancelRate;
    private final Setting[] smartSettings;

    private EntityPlayer currentTarget;
    private final Map<Integer, TargetState> targetStates = new HashMap<>();
    private int lastSelfHurtTime;
    private boolean takingKnockback;
    private boolean waitFirstTracking;
    private int waitFirstStartTick = -1;
    private boolean waitFirstUnlocked;
    private boolean smartStateActive;
    private int tickCounter;

    public KeepSprint() {
        super("Keep Sprint", Module.category.movement, 0);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(new DescriptionSetting("Default is 40% motion reduction."));
        this.registerSetting(slow = new SliderSetting("Slow %", 40.0D, 0.0D, 40.0D, 1.0D));
        this.registerSetting(stopSprint = new ButtonSetting("Stop Sprint", true));
        this.registerSetting(disableWhileJump = new ButtonSetting("Disable while jumping", false));
        this.registerSetting(reduceReachHits = new ButtonSetting("Only reduce reach hits", false));

        this.registerSetting(smartDescription = new DescriptionSetting("Smart hit selection"));
        this.registerSetting(pauseDuration = new SliderSetting("Pause duration", "ms", 500.0D, 0.0D, 500.0D, 50.0D));
        this.registerSetting(waitForFirstHit = new SliderSetting("Wait for first hit", "ms", 0.0D, 0.0D, 500.0D, 50.0D));
        this.registerSetting(disableDuringKnockback = new ButtonSetting("Disable during knockback", false));
        this.registerSetting(useServerAttackTime = new ButtonSetting("Use server attack time", false));
        this.registerSetting(fakeSwing = new ButtonSetting("Fake swing", false));
        this.registerSetting(inCombatCancelRate = new SliderSetting("In combat cancel rate", "%", 100.0D, 0.0D, 100.0D, 1.0D));
        this.registerSetting(missedSwingsCancelRate = new SliderSetting("Missed swings cancel rate", "%", 0.0D, 0.0D, 100.0D, 1.0D));
        smartSettings = new Setting[]{smartDescription, pauseDuration, waitForFirstHit, disableDuringKnockback,
                useServerAttackTime, fakeSwing, inCombatCancelRate, missedSwingsCancelRate};
    }

    @Override
    public void guiUpdate() {
        boolean visible = isSmart();
        for (Setting setting : smartSettings) {
            setting.setVisible(visible, this);
        }
    }

    @Override
    public void onEnable() {
        tickCounter = 0;
        smartStateActive = isSmart();
        resetSmartState();
    }

    @Override
    public void onDisable() {
        smartStateActive = false;
        resetSmartState();
    }

    private boolean isSmart() {
        return (int) mode.getInput() == 1;
    }

    public static void keepSprint(Entity en) {
        boolean vanilla = false;
        if (disableWhileJump.isToggled() && !mc.thePlayer.onGround) {
            vanilla = true;
        }
        else if (reduceReachHits.isToggled() && !mc.thePlayer.capabilities.isCreativeMode) {
            double distance = -1.0;
            final Vec3 getPositionEyes = mc.thePlayer.getPositionEyes(1.0f);
            if (ModuleManager.killAura != null && ModuleManager.killAura.isEnabled() && KillAura.target != null) {
                distance = getPositionEyes.distanceTo(KillAura.target.getPositionEyes(1.0f));
            }
            else if (ModuleManager.reach != null && ModuleManager.reach.isEnabled() && mc.objectMouseOver != null) {
                distance = getPositionEyes.distanceTo(mc.objectMouseOver.hitVec);
            }
            if (distance != -1.0 && distance <= 3.0) {
                vanilla = true;
            }
        }
        if (vanilla) {
            mc.thePlayer.motionX *= 0.6;
            mc.thePlayer.motionZ *= 0.6;
        }
        else {
            float mult = (100.0f - (float) slow.getInput()) / 100.0f;
            mc.thePlayer.motionX *= mult;
            mc.thePlayer.motionZ *= mult;
        }

        if (stopSprint.isToggled()) {
            mc.thePlayer.motionX *= 0.5;
        }
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent event) {
        if (!isSmart()) {
            if (smartStateActive) {
                smartStateActive = false;
                resetSmartState();
            }
            return;
        }
        smartStateActive = true;

        if (!Utils.nullCheck() || mc.thePlayer.isDead || mc.theWorld == null) {
            resetSmartState();
            return;
        }

        tickCounter++;
        int currentTick = tickCounter;
        pruneTargetStates();
        EntityPlayer nextTarget = CombatTargeting.findTarget(HIT_RANGE_SQ);
        updateCurrentTarget(nextTarget, currentTick);
        updateSelfDamage(currentTick);
        updateTargetDamage(currentTick);
    }

    @SubscribeEvent
    public void onPreAttack(PreAttackEvent event) {
        if (!isSmart() || !Utils.nullCheck() || mc.theWorld == null || mc.thePlayer.isDead) {
            return;
        }

        int currentTick = tickCounter;
        ClickType clickType = classifyClick(event.objectMouseOver);
        if (clickType == ClickType.BLOCK_INTERACTION) {
            return;
        }
        if (clickType == ClickType.MISSED_SWING) {
            if (shouldCancel(missedSwingsCancelRate.getInput())) cancelClick(event);
            return;
        }

        EntityPlayer clickedTarget = CombatTargeting.asValidPlayer(
                event.objectMouseOver == null ? null : event.objectMouseOver.entityHit, HIT_RANGE_SQ);
        if (clickedTarget == null) return;

        updateCurrentTarget(clickedTarget, currentTick);
        TargetState state = getTargetState(clickedTarget);
        int blockMask = 0;
        if (!disableDuringKnockback.isToggled() || !isTakingKnockback()) {
            blockMask = getBurstBlockMask(state, currentTick);
            if (isWaitingForFirstHit(currentTick)) blockMask |= BLOCK_WAIT_FIRST;
        }

        boolean shouldBlock = (blockMask & BLOCK_WAIT_FIRST) != 0
                || (blockMask & BLOCK_PREDICTED_BURST) != 0
                || applyPauseDuration(state, blockMask & ~BLOCK_PREDICTED_BURST, currentTick);
        if (shouldBlock && shouldCancel(inCombatCancelRate.getInput())) {
            cancelClick(event);
            return;
        }
        recordPassedValidHit(clickedTarget, currentTick);
    }

    private ClickType classifyClick(MovingObjectPosition objectMouseOver) {
        if (objectMouseOver == null) return ClickType.MISSED_SWING;
        if (objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) return ClickType.BLOCK_INTERACTION;
        if (objectMouseOver.typeOfHit == MovingObjectPosition.MovingObjectType.ENTITY) {
            return CombatTargeting.asValidPlayer(objectMouseOver.entityHit, HIT_RANGE_SQ) != null
                    ? ClickType.VALID_HIT : ClickType.MISSED_SWING;
        }
        return ClickType.MISSED_SWING;
    }

    private void cancelClick(PreAttackEvent event) {
        if (fakeSwing.isToggled()) Utils.setSwinging();
        event.setCanceled(true);
    }

    private void updateCurrentTarget(EntityPlayer nextTarget, int currentTick) {
        if (sameTarget(nextTarget)) {
            if (nextTarget != null) {
                currentTarget = nextTarget;
                getTargetState(nextTarget);
            }
            return;
        }
        currentTarget = nextTarget;
        if (nextTarget == null) {
            resetWaitFirstState();
        } else if (!waitFirstTracking) {
            waitFirstTracking = true;
            waitFirstStartTick = currentTick;
            waitFirstUnlocked = false;
        }
        if (nextTarget != null) getTargetState(nextTarget);
    }

    private void updateSelfDamage(int currentTick) {
        int hurtTime = mc.thePlayer.hurtTime;
        boolean hurtAgain = hurtTime > lastSelfHurtTime;
        if (hurtAgain) {
            if (waitFirstTracking && !waitFirstUnlocked) waitFirstUnlocked = true;
            takingKnockback = true;
        }
        if (takingKnockback && mc.thePlayer.onGround && !hurtAgain) takingKnockback = false;
        lastSelfHurtTime = hurtTime;
    }

    private void updateTargetDamage(int currentTick) {
        if (currentTarget == null || !useServerAttackTime.isToggled()) return;
        TargetState state = getTargetState(currentTarget);
        int targetHurtTime = currentTarget.hurtTime;
        if (state.pendingServerConfirmationTick >= 0
                && currentTick - state.pendingServerConfirmationTick > SERVER_CONFIRM_TIMEOUT_TICKS) {
            state.pendingServerConfirmationTick = -1;
        }
        if (state.pendingServerConfirmationTick >= 0 && targetHurtTime > state.lastObservedTargetHurtTime) {
            state.pendingServerConfirmationTick = -1;
            state.lastConfirmedTargetDamageTick = currentTick;
            state.rawBlockMask = BLOCK_SERVER_COOLDOWN;
            state.rawBlockStartTick = currentTick;
        }
        state.lastObservedTargetHurtTime = targetHurtTime;
    }

    private int getBurstBlockMask(TargetState state, int currentTick) {
        if (useServerAttackTime.isToggled()) {
            return state.lastConfirmedTargetDamageTick >= 0
                    && currentTick - state.lastConfirmedTargetDamageTick < SERVER_CONFIRM_COOLDOWN_TICKS
                    ? BLOCK_SERVER_COOLDOWN : 0;
        }
        if (!isPredictedBurstWindowActive(state, currentTick)) return 0;
        int pauseTicks = msToTicks(pauseDuration.getInput());
        return pauseTicks > 0 && currentTick - state.predictedBurstWindowStartTick < pauseTicks
                ? BLOCK_PREDICTED_BURST : 0;
    }

    private boolean isWaitingForFirstHit(int currentTick) {
        if (waitForFirstHit.getInput() <= 0.0D || currentTarget == null || !waitFirstTracking
                || waitFirstUnlocked || waitFirstStartTick < 0) return false;
        int requiredTicks = msToTicks(waitForFirstHit.getInput());
        return requiredTicks > 0 && currentTick - waitFirstStartTick < requiredTicks;
    }

    private boolean applyPauseDuration(TargetState state, int blockMask, int currentTick) {
        if (blockMask == 0) {
            state.rawBlockMask = 0;
            state.rawBlockStartTick = -1;
            return false;
        }
        if (pauseDuration.getInput() <= 0.0D) {
            state.rawBlockMask = blockMask;
            state.rawBlockStartTick = currentTick;
            return false;
        }
        if (blockMask != state.rawBlockMask) {
            state.rawBlockMask = blockMask;
            state.rawBlockStartTick = currentTick;
        } else if (state.rawBlockStartTick < 0) {
            state.rawBlockStartTick = currentTick;
        }
        int requiredTicks = msToTicks(pauseDuration.getInput());
        return requiredTicks > 0 && currentTick - state.rawBlockStartTick < requiredTicks;
    }

    private void recordPassedValidHit(EntityPlayer target, int currentTick) {
        updateCurrentTarget(target, currentTick);
        TargetState state = getTargetState(target);
        if (useServerAttackTime.isToggled()) {
            state.pendingServerConfirmationTick = currentTick;
            state.lastConfirmedTargetDamageTick = -1;
        } else if (!isPredictedBurstWindowActive(state, currentTick)) {
            startPredictedBurstWindow(state, currentTick, HURT_WINDOW_TICKS);
        }
    }

    private boolean sameTarget(EntityPlayer target) {
        if (currentTarget == null || target == null) return currentTarget == target;
        return currentTarget.getEntityId() == target.getEntityId();
    }

    private TargetState getTargetState(EntityPlayer target) {
        TargetState state = targetStates.get(target.getEntityId());
        if (state == null) {
            state = new TargetState();
            if (useServerAttackTime.isToggled()) state.lastObservedTargetHurtTime = target.hurtTime;
            targetStates.put(target.getEntityId(), state);
        }
        return state;
    }

    private void startPredictedBurstWindow(TargetState state, int startTick, int windowTicks) {
        state.predictedBurstWindowStartTick = startTick;
        state.predictedBurstWindowEndTick = startTick + Math.max(1, windowTicks);
    }

    private boolean isPredictedBurstWindowActive(TargetState state, int currentTick) {
        return state.predictedBurstWindowEndTick >= 0 && currentTick < state.predictedBurstWindowEndTick;
    }

    private boolean isTakingKnockback() {
        return takingKnockback || mc.thePlayer.hurtTime > 0;
    }

    private void pruneTargetStates() {
        if (mc.theWorld == null) {
            targetStates.clear();
            return;
        }
        Iterator<Map.Entry<Integer, TargetState>> iterator = targetStates.entrySet().iterator();
        while (iterator.hasNext()) {
            Entity entity = mc.theWorld.getEntityByID(iterator.next().getKey());
            if (!(entity instanceof EntityPlayer) || entity.isDead || ((EntityPlayer) entity).deathTime != 0) {
                iterator.remove();
            }
        }
    }

    private void resetSmartState() {
        currentTarget = null;
        targetStates.clear();
        lastSelfHurtTime = 0;
        takingKnockback = false;
        resetWaitFirstState();
    }

    private void resetWaitFirstState() {
        waitFirstTracking = false;
        waitFirstStartTick = -1;
        waitFirstUnlocked = false;
    }

    private static int msToTicks(double ms) {
        return ms <= 0.0D ? 0 : (int) Math.ceil(ms / 50.0D);
    }

    private boolean shouldCancel(double chance) {
        return chance >= 100.0D || chance > 0.0D && Math.random() * 100.0D < chance;
    }

    private enum ClickType {
        VALID_HIT,
        BLOCK_INTERACTION,
        MISSED_SWING
    }

    private static final class TargetState {
        private int lastConfirmedTargetDamageTick = -1;
        private int pendingServerConfirmationTick = -1;
        private int predictedBurstWindowStartTick = -1;
        private int predictedBurstWindowEndTick = -1;
        private int lastObservedTargetHurtTime;
        private int rawBlockStartTick = -1;
        private int rawBlockMask;
    }
}
