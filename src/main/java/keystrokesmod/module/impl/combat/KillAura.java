package keystrokesmod.module.impl.combat;

import keystrokesmod.Raven;
import keystrokesmod.event.AttackEvent;
import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PrePlayerInteractEvent;
import keystrokesmod.event.RightClickMouseEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.event.UseItemEvent;
import keystrokesmod.helper.RotationHelper;
import keystrokesmod.lag.api.EnumLagDirection;
import keystrokesmod.lag.api.LagRequest;
import keystrokesmod.lag.timeout.ModuleBackedTimeout;
import keystrokesmod.mixin.impl.accessor.IAccessorPlayerControllerMP;
import keystrokesmod.mixin.impl.accessor.IAccessorEntityRenderer;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.minigames.SkyWars;
import keystrokesmod.module.impl.world.AntiBot;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.CombatTargeting;
import keystrokesmod.utility.ReflectionUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.renderer.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityCreature;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.entity.monster.EntityGiantZombie;
import net.minecraft.entity.monster.EntityIronGolem;
import net.minecraft.entity.monster.EntityPigZombie;
import net.minecraft.entity.monster.EntitySilverfish;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;

import java.util.*;

public class KillAura extends Module {
    private SliderSetting mode;
    private SliderSetting minCPS;
    private SliderSetting maxCPS;
    private SliderSetting fov;
    private SliderSetting attackRange;
    private SliderSetting swingRange;
    private SliderSetting aimRange;
    public SliderSetting rotationMode;
    private SliderSetting speed;
    private SliderSetting sortMode;
    private SliderSetting switchDelay;
    private ButtonSetting attackMobs;
    private ButtonSetting targetInvis;
    private ButtonSetting disableInInventory;
    private ButtonSetting disableWhileMining;
    private ButtonSetting aimThroughBlocks;
    private ButtonSetting aimThroughEntities;
    private ButtonSetting ignoreTeammates;
    private ButtonSetting prioritizeEnemies;
    private ButtonSetting notUsingItem;
    private ButtonSetting requireMouseDown;
    private ButtonSetting weaponOnly;

    private GroupSetting autoBlockGroup;
    private ButtonSetting autoBlockEnabled;
    private SliderSetting autoBlockMode;
    private SliderSetting autoBlockRange;
    private SliderSetting autoBlockMinAps;
    private SliderSetting autoBlockMaxAps;
    private ButtonSetting autoBlockRequirePress;
    private ButtonSetting autoBlockIgnoreTeammates;

    private final String[] modes = new String[]{"Single", "Switch"};
    private final String[] rotationModes = new String[]{"Silent", "Lock view", "None"};
    private String[] sortModes = new String[]{"Distance", "Health", "Hurt time", "Yaw"};
    private String[] autoBlockModes = new String[]{"Vanilla", "Spoof", "Hypixel", "Blink", "Interact", "Swap", "Legit", "Fake", "Hypixel (Without NoSlow)"};

    public static EntityLivingBase target;
    public static EntityLivingBase attackingEntity;

    public boolean isRequireMouseDown() {
        return requireMouseDown.isToggled();
    }

    private List<Entity> hostileMobs = new ArrayList<>();
    private Map<Integer, Boolean> golems = new HashMap<>();

    private long nextClickTime;
    private long lastTargetSwitch;
    private int switchIndex;
    private boolean hitRegistered;
    private float attackYaw;
    private float attackPitch;
    private Random rand;
    private double targetDistance = Double.MAX_VALUE;

    private boolean autoBlockServerBlocking;
    private boolean autoBlockVisualBlocking;
    private boolean autoBlockPendingReblock;
    private EntityLivingBase autoBlockTarget;
    private Entity autoBlockPendingInteractTarget;
    private LagRequest autoBlockBlinkRequest;
    private int autoBlockLastMode = -1;
    private int autoBlockHypixelTick;
    private boolean autoBlockHypixelInitialized;
    private boolean autoBlockHypixelSkipAttack;

    public KillAura() {
        super("Kill Aura", category.combat);
        this.registerSetting(mode = new SliderSetting("Mode", 0, modes));
        this.registerSetting(minCPS = new SliderSetting("Minimum CPS", 14.0, 1.0, 20.0, 1.0));
        this.registerSetting(maxCPS = new SliderSetting("Maximum CPS", 14.0, 1.0, 20.0, 1.0));
        this.registerSetting(fov = new SliderSetting("FOV", "°", 360.0, 30.0, 360.0, 4.0));
        this.registerSetting(attackRange = new SliderSetting("Range (attack)", 3.0, 3.0, 6.0, 0.05));
        this.registerSetting(swingRange = new SliderSetting("Range (swing)", 4.5, 3.0, 8.0, 0.05));
        this.registerSetting(aimRange = new SliderSetting("Range (aim)", 4.5, 3.0, 8.0, 0.05));
        this.registerSetting(rotationMode = new SliderSetting("Rotation mode", 0, rotationModes));
        this.registerSetting(speed = new SliderSetting("Speed", 10, 1, 30, 1));
        this.registerSetting(sortMode = new SliderSetting("Sort mode", 0, sortModes));
        this.registerSetting(switchDelay = new SliderSetting("Switch delay", "ms", 150.0, 0.0, 1000.0, 25.0));
        this.registerSetting(targetInvis = new ButtonSetting("Target invis", true));
        this.registerSetting(attackMobs = new ButtonSetting("Attack mobs", false));
        this.registerSetting(aimThroughBlocks = new ButtonSetting("Hit through walls", false));
        this.registerSetting(aimThroughEntities = new ButtonSetting("Hit through entities", false));
        this.registerSetting(disableInInventory = new ButtonSetting("Disable in inventory", true));
        this.registerSetting(disableWhileMining = new ButtonSetting("Disable while mining", false));
        this.registerSetting(ignoreTeammates = new ButtonSetting("Ignore teammates", true));
        this.registerSetting(notUsingItem = new ButtonSetting("Not using item", false));
        this.registerSetting(prioritizeEnemies = new ButtonSetting("Prioritize enemies", false));
        this.registerSetting(requireMouseDown = new ButtonSetting("Require mouse down", false));
        this.registerSetting(weaponOnly = new ButtonSetting("Weapon only", false));

        this.registerSetting(autoBlockGroup = new GroupSetting("Auto Block"));
        this.registerSetting(autoBlockEnabled = new ButtonSetting(autoBlockGroup, "Enable", false));
        this.registerSetting(autoBlockMode = new SliderSetting(autoBlockGroup, "Mode", 1, autoBlockModes));
        this.registerSetting(autoBlockRequirePress = new ButtonSetting(autoBlockGroup, "Require press", false));
        this.registerSetting(autoBlockMinAps = new SliderSetting(autoBlockGroup, "Minimum APS", 8.0, 1.0, 20.0, 1.0));
        this.registerSetting(autoBlockMaxAps = new SliderSetting(autoBlockGroup, "Maximum APS", 10.0, 1.0, 20.0, 1.0));
        this.registerSetting(autoBlockRange = new SliderSetting(autoBlockGroup, "Range", 6.0, 3.0, 8.0, 0.1));
        this.registerSetting(autoBlockIgnoreTeammates = new ButtonSetting(autoBlockGroup, "Ignore teammates for blocking", true));
    }

    @Override
    public String getInfo() {
        if (rotationMode.getInput() == 2) {
            return (int) this.fov.getInput() + fov.getSuffix();
        }
        return rotationModes[(int) rotationMode.getInput()];
    }

    @Override
    public void onEnable() {
        rand = new Random();
        nextClickTime = 0L;
        lastTargetSwitch = 0L;
        switchIndex = 0;
        hitRegistered = false;
        resetAutoBlock();
    }

    @Override
    public void onDisable() {
        setTarget(null);
        nextClickTime = 0L;
        switchIndex = 0;
        hitRegistered = false;
        resetAutoBlock();
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onClientRotation(ClientRotationEvent e) {
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.shouldOverrideMouseOver()) {
            return;
        }
        if (!basicCondition() || !settingCondition()) {
            setTarget(null);
            return;
        }
        updateTarget();
        if (target == null) {
            return;
        }
        targetDistance = RotationUtils.distanceFromEyeToClosestOnAABB(target);
        attackYaw = e.yaw != null ? e.yaw : RotationUtils.serverRotations[0];
        attackPitch = e.pitch != null ? e.pitch : RotationUtils.serverRotations[1];
        if (rotationMode.getInput() != 2) {
            double aimRangeVal = aimRange.getInput();
            if (targetDistance <= aimRangeVal) {
                int speedVal = (int) speed.getInput();
                boolean useBackup = !aimThroughBlocks.isToggled() || !aimThroughEntities.isToggled();
                float[] rot = RotationHelper.get().getRotationsToTarget(target, e, speedVal, 100, 100, 0f, useBackup, aimRangeVal, aimThroughBlocks.isToggled(), aimThroughEntities.isToggled());
                if (rot != null) {
                    attackYaw = rot[0];
                    attackPitch = rot[1];
                    if (rotationMode.getInput() == 0) {
                        e.yaw = attackYaw;
                        e.pitch = attackPitch;
                    } else {
                        mc.thePlayer.rotationYaw = attackYaw;
                        mc.thePlayer.rotationPitch = attackPitch;
                        e.yaw = attackYaw;
                        e.pitch = attackPitch;
                    }
                }
            }
        } else {
            attackYaw = mc.thePlayer.rotationYaw;
            attackPitch = mc.thePlayer.rotationPitch;
        }
    }

    @Override
    public void onUpdate() {
        if (target != null && targetDistance <= attackRange.getInput()) {
            attackingEntity = target;
        } else {
            attackingEntity = null;
        }
    }

    @SubscribeEvent
    public void onPrePlayerInteract(PrePlayerInteractEvent e) {
        handleAutoBlockPrePlayerInteract();
        if (shouldSkipHypixelWithoutNoSlowAttack()) return;
        if (Velocity.stoppedBlock) return;
        if (Velocity.extraAttacked && isAutoBlockActive()) {
            Velocity.extraAttacked = false;
            return;
        }
        if (!basicCondition() || !settingCondition() || target == null || target.isDead || target.deathTime > 0) return;
        targetDistance = RotationUtils.distanceFromEyeToClosestOnAABB(target);
        if (targetDistance > swingRange.getInput()) return;
        if (notUsingItem.isToggled() && mc.thePlayer.isUsingItem()) return;

        long now = System.currentTimeMillis();
        if (nextClickTime == 0) {
            nextClickTime = now;
        }
        if (now < nextClickTime) return;
        nextClickTime = now + nextDelay();

        mc.thePlayer.swingItem();
        if (targetDistance > attackRange.getInput() || !isRotationOnTarget(target, attackYaw, attackPitch)) return;

        prepareAutoBlockAttack(target);
        mc.playerController.attackEntity(mc.thePlayer, target);
        hitRegistered = true;
        if (isHypixelWithoutNoSlow(getAutoBlockMode()) && isAutoBlockReady()) {
            startAutoBlock(mc.thePlayer.getHeldItem());
            autoBlockHypixelTick = 1;
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAutoBlockRightClick(RightClickMouseEvent event) {
        if (shouldCancelAutoBlockUse()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAutoBlockUseItem(UseItemEvent event) {
        if (shouldCancelAutoBlockUse()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public void onAutoBlockRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        if (!isAutoBlockEnabled()) {
            if (autoBlockServerBlocking || autoBlockVisualBlocking || autoBlockBlinkRequest != null) resetAutoBlock();
            return;
        }
        if (!Utils.nullCheck()) return;
        ReflectionUtils.setItemInUse(Utils.holdingSword() && autoBlockVisualBlocking);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onAutoBlockSendPacket(SendPacketEvent event) {
        if (!isAutoBlockEnabled()) return;

        if (event.getPacket() instanceof C07PacketPlayerDigging) {
            C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
            if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                autoBlockServerBlocking = false;
            }
        } else if (event.getPacket() instanceof C09PacketHeldItemChange) {
            autoBlockServerBlocking = false;
        } else if (isFakeAutoBlock() && event.getPacket() instanceof C08PacketPlayerBlockPlacement) {
            if (Utils.nullCheck() && Utils.holdingSword() && hasAutoBlockTarget()) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onAutoBlockAttack(AttackEvent event) {
        if (event.attacker == mc.thePlayer) {
            prepareAutoBlockAttack(event.target);
        }
    }

    private void prepareAutoBlockAttack(Entity attackTarget) {
        if (!isAutoBlockReady() || !autoBlockServerBlocking) return;

        int mode = getAutoBlockMode();
        if (mode == 0) { // Vanilla attacks while the server keeps its blocking state.
            mc.thePlayer.stopUsingItem();
            return;
        }
        if (mode == 7) return;

        if (mode == 1) { // Spoof
            stopAutoBlock(false);
            spoofHeldSlot(findEmptySlot(mc.thePlayer.inventory.currentItem));
        } else if (mode == 5) { // Swap
            stopAutoBlock(false);
            sendHeldItemChange(mc.thePlayer.inventory.currentItem);
        } else {
            stopAutoBlock(true);
        }

        if (mode == 3) { // Blink: flush block/release before the attack packet.
            releaseAutoBlockBlink();
        }
        if (mode == 4) {
            autoBlockPendingInteractTarget = attackTarget;
        }
        autoBlockPendingReblock = true;
    }

    private void handleAutoBlockPrePlayerInteract() {
        if (!isAutoBlockReady()) {
            resetAutoBlock();
            return;
        }

        int mode = getAutoBlockMode();
        if (mode != autoBlockLastMode) {
            resetAutoBlock();
            autoBlockLastMode = mode;
        }
        if (mode == 7) { // Fake
            if (autoBlockServerBlocking) stopAutoBlock(true);
            releaseAutoBlockBlink();
            autoBlockPendingReblock = false;
            autoBlockPendingInteractTarget = null;
            autoBlockVisualBlocking = true;
            return;
        }

        if (isHypixelWithoutNoSlow(mode)) {
            autoBlockVisualBlocking = true;
            autoBlockHypixelSkipAttack = false;
            if (!autoBlockHypixelInitialized) {
                autoBlockHypixelInitialized = true;
                autoBlockHypixelTick = 0;
                return;
            }
            if (autoBlockHypixelTick == 1) {
                autoBlockHypixelSkipAttack = true;
                autoBlockHypixelTick = 2;
                return;
            }
            if (autoBlockHypixelTick == 2) {
                stopAutoBlock(true);
                autoBlockHypixelTick = 0;
            }
            return;
        }

        autoBlockVisualBlocking = mode >= 2 && mode <= 5;
        if (autoBlockPendingInteractTarget != null) {
            sendAutoBlockInteraction(autoBlockPendingInteractTarget);
            autoBlockPendingInteractTarget = null;
        }

        if (!autoBlockServerBlocking || autoBlockPendingReblock) {
            autoBlockPendingReblock = false;
            startAutoBlockForMode(mode);
        } else if (!mc.thePlayer.isUsingItem()) {
            ItemStack heldItem = mc.thePlayer.getHeldItem();
            if (heldItem != null) {
                mc.thePlayer.setItemInUse(heldItem, heldItem.getMaxItemUseDuration());
            }
        }
    }

    private boolean isAutoBlockEnabled() {
        return autoBlockEnabled != null && autoBlockEnabled.isToggled();
    }

    private boolean isFakeAutoBlock() {
        return getAutoBlockMode() == 7;
    }

    private boolean isHypixelWithoutNoSlow(int mode) {
        return mode == 8;
    }

    private boolean shouldSkipHypixelWithoutNoSlowAttack() {
        return isAutoBlockEnabled() && isHypixelWithoutNoSlow(getAutoBlockMode())
                && autoBlockHypixelSkipAttack;
    }

    private int getAutoBlockMode() {
        return autoBlockMode == null ? 0 : (int) autoBlockMode.getInput();
    }

    private boolean hasAutoBlockTarget() {
        if (!Utils.nullCheck()) return false;
        if (target != null && !target.isDead && target.deathTime == 0
                && RotationUtils.distanceFromEyeToClosestOnAABB(target) <= autoBlockRange.getInput()) {
            autoBlockTarget = target;
            return true;
        }
        autoBlockTarget = CombatTargeting.findTarget(autoBlockRange.getInput() * autoBlockRange.getInput(),
                autoBlockIgnoreTeammates.isToggled());
        return autoBlockTarget != null;
    }

    private boolean isAutoBlockReady() {
        if (!isEnabled() || !isAutoBlockEnabled() || !Utils.nullCheck() || mc.thePlayer.isDead
                || mc.currentScreen != null || !Utils.holdingSword()) {
            return false;
        }
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) return false;
        if (autoBlockRequirePress.isToggled() && !Mouse.isButtonDown(1)) return false;
        return hasAutoBlockTarget();
    }

    private boolean shouldCancelAutoBlockUse() {
        return isAutoBlockReady() && (autoBlockServerBlocking || autoBlockVisualBlocking || isFakeAutoBlock());
    }

    private void startAutoBlockForMode(int mode) {
        int currentSlot = mc.thePlayer.inventory.currentItem;
        if (mode == 1) { // Spoof
            spoofHeldSlot(findEmptySlot(currentSlot));
            startAutoBlock(mc.thePlayer.getHeldItem());
        } else if (mode == 5) { // Swap
            int swordSlot = findSwordSlot(currentSlot);
            if (swordSlot >= 0) {
                sendHeldItemChange(swordSlot);
                startAutoBlock(mc.thePlayer.inventory.getStackInSlot(swordSlot));
            } else {
                startAutoBlock(mc.thePlayer.getHeldItem());
            }
        } else {
            startAutoBlock(mc.thePlayer.getHeldItem());
        }
        if (mode == 3) startAutoBlockBlink();
    }

    private void startAutoBlock(ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof ItemSword)) return;
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        mc.thePlayer.sendQueue.addToSendQueue(new C08PacketPlayerBlockPlacement(stack));
        mc.thePlayer.setItemInUse(stack, stack.getMaxItemUseDuration());
        autoBlockServerBlocking = true;
    }

    private void stopAutoBlock(boolean sendReleasePacket) {
        if (!autoBlockServerBlocking) return;
        if (sendReleasePacket) {
            mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(
                    C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        }
        mc.thePlayer.stopUsingItem();
        autoBlockServerBlocking = false;
    }

    private void startAutoBlockBlink() {
        if (autoBlockBlinkRequest != null) return;
        autoBlockBlinkRequest = new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));
        Raven.lagHandler.requestLag(autoBlockBlinkRequest);
    }

    private void releaseAutoBlockBlink() {
        if (autoBlockBlinkRequest == null) return;
        autoBlockBlinkRequest.getTimeout().forceTimeOut();
        autoBlockBlinkRequest = null;
    }

    public boolean isAutoBlockActive() {
        return isEnabled() && isAutoBlockEnabled() && (autoBlockServerBlocking || autoBlockBlinkRequest != null);
    }

    private void sendAutoBlockInteraction(Entity entity) {
        if (entity == null || entity.isDead) return;
        Vec3 relativeHit = new Vec3(0.0, Math.max(0.0, entity.height * 0.5), 0.0);
        mc.thePlayer.sendQueue.addToSendQueue(new C02PacketUseEntity(entity, relativeHit));
        mc.thePlayer.sendQueue.addToSendQueue(new C02PacketUseEntity(entity, C02PacketUseEntity.Action.INTERACT));
    }

    private void spoofHeldSlot(int spoofSlot) {
        int currentSlot = mc.thePlayer.inventory.currentItem;
        if (spoofSlot < 0 || spoofSlot == currentSlot) return;
        sendHeldItemChange(spoofSlot);
        sendHeldItemChange(currentSlot);
    }

    private void sendHeldItemChange(int slot) {
        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
    }

    private int findEmptySlot(int currentSlot) {
        for (int slot = 0; slot < 9; slot++) {
            if (slot != currentSlot && mc.thePlayer.inventory.getStackInSlot(slot) == null) return slot;
        }
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (slot != currentSlot && stack != null && !stack.hasDisplayName()) return slot;
        }
        return Math.floorMod(currentSlot - 1, 9);
    }

    private int findSwordSlot(int currentSlot) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (slot != currentSlot && stack != null && stack.getItem() instanceof ItemSword) return slot;
        }
        return -1;
    }

    private void resetAutoBlock() {
        if (Utils.nullCheck()) stopAutoBlock(true);
        releaseAutoBlockBlink();
        autoBlockVisualBlocking = false;
        autoBlockPendingReblock = false;
        autoBlockPendingInteractTarget = null;
        autoBlockTarget = null;
        autoBlockLastMode = -1;
        autoBlockHypixelTick = 0;
        autoBlockHypixelInitialized = false;
        autoBlockHypixelSkipAttack = false;
        ReflectionUtils.setItemInUse(false);
    }

    @SubscribeEvent
    public void onSetAttackTarget(LivingSetAttackTargetEvent e) {
        if (e.entity != null && !hostileMobs.contains(e.entity)) {
            if (!(e.target instanceof EntityPlayer) || !e.target.getName().equals(mc.thePlayer.getName())) {
                return;
            }
            if (Utils.getBedwarsStatus() == 2 && e.entity instanceof EntityPigZombie) {
                return;
            }
            hostileMobs.add(e.entity);
        }
        if (e.target == null && hostileMobs.contains(e.entity)) {
            hostileMobs.remove(e.entity);
        }
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent e) {
        if (e.entity == mc.thePlayer) {
            hostileMobs.clear();
            golems.clear();
            setTarget(null);
            switchIndex = 0;
            hitRegistered = false;
        }
    }

    private void setTarget(Entity entity) {
        if (!(entity instanceof EntityLivingBase)) {
            target = null;
            attackingEntity = null;
            targetDistance = Double.MAX_VALUE;
            nextClickTime = 0L;
        } else {
            target = (EntityLivingBase) entity;
        }
    }

    private void updateTarget() {
        long now = System.currentTimeMillis();
        double maxRange = Math.max(Math.max(attackRange.getInput(), swingRange.getInput()), aimRange.getInput());
        if (isAutoBlockEnabled()) {
            maxRange = Math.max(maxRange, autoBlockRange.getInput());
        }
        float fovValue = (float) fov.getInput();

        Candidate currentCandidate = target == null ? null : getCandidateTarget(target, maxRange, fovValue);
        boolean currentValid = currentCandidate != null
                && buildKillAuraTarget(currentCandidate.entity, currentCandidate.distance, maxRange) != null;
        if (currentValid && now - lastTargetSwitch < (long) switchDelay.getInput()) {
            return;
        }

        List<KillAuraTarget> candidates = new ArrayList<>();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            Candidate candidate = getCandidateTarget(entity, maxRange, fovValue);
            if (candidate == null) {
                continue;
            }

            KillAuraTarget auraTarget = buildKillAuraTarget(candidate.entity, candidate.distance, maxRange);
            if (auraTarget != null) {
                candidates.add(auraTarget);
            }
        }

        boolean hasAttackRangeTarget = candidates.stream().anyMatch(candidate -> candidate.distance <= attackRange.getInput());
        boolean hasSwingRangeTarget = candidates.stream().anyMatch(candidate -> candidate.distance <= swingRange.getInput());
        if (hasAttackRangeTarget) {
            candidates.removeIf(candidate -> candidate.distance > attackRange.getInput());
        } else if (hasSwingRangeTarget) {
            candidates.removeIf(candidate -> candidate.distance > swingRange.getInput());
        }

        boolean hasPlayerTarget = candidates.stream().anyMatch(candidate -> candidate.entity instanceof EntityPlayer);
        if (hasPlayerTarget) {
            candidates.removeIf(candidate -> !(candidate.entity instanceof EntityPlayer));
        }

        if (prioritizeEnemies.isToggled()) {
            List<KillAuraTarget> enemies = new ArrayList<>();
            for (KillAuraTarget candidate : candidates) {
                if (candidate.isEnemy) {
                    enemies.add(candidate);
                }
            }
            if (!enemies.isEmpty()) {
                candidates = enemies;
            }
        }

        candidates.sort(getTargetComparator().thenComparingDouble(c -> c.distance));
        lastTargetSwitch = now;
        if (candidates.isEmpty()) {
            setTarget(null);
            switchIndex = 0;
            hitRegistered = false;
            return;
        }

        if ((int) mode.getInput() == 1 && hitRegistered) {
            switchIndex++;
        }
        hitRegistered = false;
        if ((int) mode.getInput() == 0 || switchIndex >= candidates.size()) {
            switchIndex = 0;
        }
        setTarget(candidates.get(switchIndex).entity);
    }

    private Candidate getCandidateTarget(Entity entity, double maxRange, float fovValue) {
        if (!(entity instanceof EntityLivingBase) || entity == mc.thePlayer || entity.isDead) {
            return null;
        }

        if (entity instanceof EntityPlayer) {
            EntityPlayer player = (EntityPlayer) entity;
            if (Utils.isFriended(player) || player.deathTime != 0) {
                return null;
            }
            if (AntiBot.isBot(entity) || (ignoreTeammates.isToggled() && Utils.isTeammate(entity))) {
                return null;
            }
        } else if (entity instanceof EntityCreature && attackMobs.isToggled()) {
            EntityCreature creature = (EntityCreature) entity;
            if (creature.tasks == null || creature.isAIDisabled() || creature.deathTime != 0) {
                return null;
            }

            String canonicalName = entity.getClass().getCanonicalName();
            if (canonicalName == null || !canonicalName.startsWith("net.minecraft.entity.monster.")) {
                return null;
            }
        } else {
            return null;
        }

        if (entity.isInvisible() && !targetInvis.isToggled()) {
            return null;
        }

        if (fovValue != 360.0f && !Utils.inFov(fovValue, entity)) {
            return null;
        }

        double distance = RotationUtils.distanceFromEyeToClosestOnAABB(entity);
        if (distance > maxRange) {
            return null;
        }

        return new Candidate((EntityLivingBase) entity, distance);
    }

    private KillAuraTarget buildKillAuraTarget(EntityLivingBase entity, double distanceToBoundingBox, double maxRange) {
        if (entity instanceof EntityCreature && attackMobs.isToggled() && !isHostile((EntityCreature) entity)) {
            return null;
        }

        double multipointH = 100;
        double multipointV = 100;
        if (!RotationUtils.hasValidAimPoint(entity, multipointH, multipointV, maxRange, aimThroughBlocks.isToggled(), aimThroughEntities.isToggled())) {
            return null;
        }

        boolean isEnemyPlayer = entity instanceof EntityPlayer && Utils.isEnemy((EntityPlayer) entity);
        return new KillAuraTarget(
                entity,
                distanceToBoundingBox,
                entity.getHealth(),
                entity.hurtResistantTime,
                RotationUtils.distanceFromYaw(entity, false),
                entity.getEntityId(),
                isEnemyPlayer
        );
    }

    private Comparator<KillAuraTarget> getTargetComparator() {
        switch ((int) sortMode.getInput()) {
            case 1:
                return Comparator.comparingDouble(target -> target.health);
            case 2:
                return Comparator.comparingInt(target -> target.hurttime);
            case 3:
                return Comparator.comparingDouble(target -> target.yawDelta);
            case 0:
            default:
                return Comparator.comparingDouble(target -> target.distance);
        }
    }

    private boolean isRotationOnTarget(EntityLivingBase entity, float yaw, float pitch) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = RotationUtils.getVectorForRotation(pitch, yaw);
        double range = attackRange.getInput();
        Vec3 end = eyes.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range);
        float border = entity.getCollisionBorderSize();
        AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition intercept = box.calculateIntercept(eyes, end);
        if (!box.isVecInside(eyes) && intercept == null) return false;

        Vec3 hitVec = intercept == null ? eyes : intercept.hitVec;
        if (!aimThroughBlocks.isToggled()) {
            MovingObjectPosition blockHit = mc.theWorld.rayTraceBlocks(eyes, hitVec, false, false, true);
            if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) return false;
        }
        return aimThroughEntities.isToggled() || !RotationUtils.isPathBlockedByEntity(eyes, hitVec, entity);
    }

    private boolean isHostile(EntityCreature entityCreature) {
        if (SkyWars.onlyAuraHostiles()) {
            if (entityCreature instanceof EntityGiantZombie) {
                return false;
            }
            return !ModuleManager.skyWars.spawnedMobs.contains(entityCreature.getEntityId());
        } else if (entityCreature instanceof EntitySilverfish) {
            String teamColor = Utils.getFirstColorCode(entityCreature.getCustomNameTag());
            String teamColorSelf = Utils.getFirstColorCode(mc.thePlayer.getDisplayName().getFormattedText());
            return teamColor.isEmpty() || (!teamColorSelf.equals(teamColor) && !Utils.isTeammate(entityCreature));
        } else if (entityCreature instanceof EntityIronGolem) {
            if (Utils.getBedwarsStatus() != 2) {
                return true;
            }
            if (!golems.containsKey(entityCreature.getEntityId())) {
                double nearestDistance = -1;
                EntityArmorStand nearestArmorStand = null;
                for (Entity entity : mc.theWorld.loadedEntityList) {
                    if (!(entity instanceof EntityArmorStand)) {
                        continue;
                    }
                    String stripped = Utils.stripString(entity.getDisplayName().getFormattedText());
                    if (stripped.contains("[") && stripped.endsWith("]")) {
                        double distanceSq = entity.getDistanceSq(entityCreature.posX, entityCreature.posY, entityCreature.posZ);
                        if (distanceSq < nearestDistance || nearestDistance == -1) {
                            nearestDistance = distanceSq;
                            nearestArmorStand = (EntityArmorStand) entity;
                        }
                    }
                }
                if (nearestArmorStand != null) {
                    String teamColor = Utils.getFirstColorCode(nearestArmorStand.getDisplayName().getFormattedText());
                    String teamColorSelf = Utils.getFirstColorCode(mc.thePlayer.getDisplayName().getFormattedText());
                    boolean isTeam = !teamColor.isEmpty() && (teamColorSelf.equals(teamColor) || Utils.isTeammate(nearestArmorStand));
                    golems.put(entityCreature.getEntityId(), isTeam);
                    return !isTeam;
                }
                return !ModuleManager.bedwars.spawnedMobs.contains(entityCreature.getEntityId());
            } else {
                return !golems.getOrDefault(entityCreature.getEntityId(), false);
            }
        } else if (entityCreature instanceof EntityPigZombie && Utils.getBedwarsStatus() != 2) {
            return false;
        }
        return hostileMobs.contains(entityCreature);
    }

    private boolean basicCondition() {
        if (!Utils.nullCheck()) {
            return false;
        }
        return !mc.thePlayer.isDead;
    }

    private boolean settingCondition() {
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) {
            return false;
        } else if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) {
            return false;
        } else if (requireMouseDown.isToggled() && !Mouse.isButtonDown(0)) {
            return false;
        } else if (weaponOnly.isToggled() && !Utils.holdingWeapon()) {
            return false;
        } else if (disableWhileMining.isToggled() && Utils.isMining()) {
            return false;
        } else if (disableInInventory.isToggled() && mc.currentScreen != null) {
            return false;
        }
        return true;
    }

    private long nextDelay() {
        int cps;
        if (isAutoBlockEnabled() && getAutoBlockMode() != 7
                && (autoBlockServerBlocking || autoBlockPendingReblock || autoBlockVisualBlocking)) {
            int minAps = Math.max(1, (int) autoBlockMinAps.getInput());
            int maxAps = Math.max(1, (int) autoBlockMaxAps.getInput());
            int lower = Math.min(minAps, maxAps);
            int upper = Math.max(minAps, maxAps);
            cps = lower + rand.nextInt(upper - lower + 1);
        } else {
            int min = Math.max(1, (int) minCPS.getInput());
            int max = Math.max(1, (int) maxCPS.getInput());
            int lower = Math.min(min, max);
            int upper = Math.max(min, max);
            cps = lower + rand.nextInt(upper - lower + 1);
        }
        int baseDelay = 1000 / cps;
        int finalDelay = baseDelay + (rand.nextInt(21) - 10);
        return Math.max(33, Math.min(180, finalDelay));
    }

    public SliderSetting getAttackRangeSetting() {
        return attackRange;
    }

    public SliderSetting getSwingRangeSetting() {
        return swingRange;
    }

    public SliderSetting getAimRangeSetting() {
        return aimRange;
    }

    public boolean shouldOverrideMouseOver() {
        return this.isEnabled()
                && Utils.nullCheck()
                && attackingEntity != null
                && target == attackingEntity
                && basicCondition()
                && targetDistance <= swingRange.getInput();
    }

    public void modifyMouseOverFromGetMouseOver(float partialTicks) {
        if (!shouldOverrideMouseOver()) {
            return;
        }

        Entity viewEntity = mc.getRenderViewEntity();
        if (viewEntity == null) {
            return;
        }

        Vec3 eyes = viewEntity.getPositionEyes(partialTicks);
        Vec3 look = viewEntity.getLook(partialTicks);
        double reach = attackRange.getInput();
        Vec3 rayEnd = eyes.addVector(look.xCoord * reach, look.yCoord * reach, look.zCoord * reach);

        float border = attackingEntity.getCollisionBorderSize();
        AxisAlignedBB bb = attackingEntity.getEntityBoundingBox().expand(border, border, border);
        MovingObjectPosition intercept = bb.calculateIntercept(eyes, rayEnd);
        boolean inside = bb.isVecInside(eyes);
        if (!inside && intercept == null) {
            return;
        }

        Vec3 hitVec = inside ? (intercept == null ? eyes : intercept.hitVec) : intercept.hitVec;
        if (!aimThroughBlocks.isToggled()) {
            MovingObjectPosition blockHit = mc.theWorld.rayTraceBlocks(eyes, hitVec, false, false, true);
            if (blockHit != null && blockHit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK) {
                return;
            }
        }
        if (!aimThroughEntities.isToggled() && RotationUtils.isPathBlockedByEntity(eyes, hitVec, attackingEntity)) {
            return;
        }

        mc.objectMouseOver = new MovingObjectPosition(attackingEntity, hitVec);
        mc.pointedEntity = attackingEntity;

        EntityRenderer renderer = mc.entityRenderer;
        if (renderer instanceof IAccessorEntityRenderer) {
            ((IAccessorEntityRenderer) renderer).setPointedEntity(attackingEntity);
        }
    }

    private static final class Candidate {
        final EntityLivingBase entity;
        final double distance;

        Candidate(EntityLivingBase entity, double distance) {
            this.entity = entity;
            this.distance = distance;
        }
    }

    static class KillAuraTarget {
        final EntityLivingBase entity;
        final double distance;
        final float health;
        final int hurttime;
        final double yawDelta;
        final int entityId;
        final boolean isEnemy;

        public KillAuraTarget(EntityLivingBase entity, double distance, float health, int hurttime, double yawDelta, int entityId, boolean isEnemy) {
            this.entity = entity;
            this.distance = distance;
            this.health = health;
            this.hurttime = hurttime;
            this.yawDelta = yawDelta;
            this.entityId = entityId;
            this.isEnemy = isEnemy;
        }
    }
}
