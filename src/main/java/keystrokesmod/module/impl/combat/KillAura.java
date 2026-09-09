package keystrokesmod.module.impl.combat;

import keystrokesmod.Raven;
import keystrokesmod.event.AttackEvent;
import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.PrePlayerInteractEvent;
import keystrokesmod.event.LeaderUpdateEvent;
import keystrokesmod.utility.LeaderAutoBlockRuntime;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.gui.inventory.GuiContainer;
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
import net.minecraftforge.common.MinecraftForge;
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
    private String[] autoBlockModes = new String[]{"Vanilla", "Spoof", "Hypixel", "Blink", "Interact", "Swap", "Legit", "Fake", "WatchDog"};

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
    private final WatchDogAutoBlock watchDogAutoBlock;
    private final SliderSetting watchDogAps;
    private final ButtonSetting watchDogLowTimerCheck, watchDogAllowPlayerBlocking, watchDogAllowTools;
    private long watchDogAttackDelay;
    private boolean watchDogBufferPending;
    private final ButtonSetting watchDogPlayers, watchDogBosses, watchDogAnimals, watchDogGolems, watchDogSilverfish, watchDogBotCheck;



    public KillAura() {
        super("Kill Aura", category.combat);
        this.registerSetting(mode = new SliderSetting("Attack mode", 0, modes, "Mode"));
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
        watchDogAutoBlock = new WatchDogAutoBlock(this, autoBlockGroup);
        registerSetting(watchDogPlayers = new ButtonSetting(autoBlockGroup, "Players", true));
        registerSetting(watchDogBosses = new ButtonSetting(autoBlockGroup, "Bosses", false));
        registerSetting(watchDogAnimals = new ButtonSetting(autoBlockGroup, "Animals", false));
        registerSetting(watchDogGolems = new ButtonSetting(autoBlockGroup, "Golems", false));
        registerSetting(watchDogSilverfish = new ButtonSetting(autoBlockGroup, "Silverfish", false));
        registerSetting(watchDogBotCheck = new ButtonSetting(autoBlockGroup, "Bot Check", true));
        registerSetting(watchDogAps = new SliderSetting(autoBlockGroup, "AutoBlock Aps", 10, 1, 20, 1));
        registerSetting(watchDogLowTimerCheck = new ButtonSetting(autoBlockGroup, "Low Timer Check", true));
        registerSetting(watchDogAllowTools = new ButtonSetting(autoBlockGroup, "Allow Tools", false));
        registerSetting(watchDogAllowPlayerBlocking = new ButtonSetting(autoBlockGroup, "Allow Player Blocking", true));
        this.registerSetting(autoBlockRequirePress = new ButtonSetting(autoBlockGroup, "Require press", false));
        this.registerSetting(autoBlockMinAps = new SliderSetting(autoBlockGroup, "Minimum APS", 8.0, 1.0, 20.0, 1.0));
        this.registerSetting(autoBlockMaxAps = new SliderSetting(autoBlockGroup, "Maximum APS", 10.0, 1.0, 20.0, 1.0));
        this.registerSetting(autoBlockRange = new SliderSetting(autoBlockGroup, "Range", 6.0, 3.0, 8.0, 0.1));
        this.registerSetting(autoBlockIgnoreTeammates = new ButtonSetting(autoBlockGroup, "Ignore teammates for blocking", true));
    }

    @Override
    public void guiUpdate() {
        boolean watchdog = isAutoBlockEnabled() && getAutoBlockMode() == 8;
        watchDogAutoBlock.updateVisibility(watchdog);
        for (ButtonSetting setting : new ButtonSetting[]{watchDogPlayers, watchDogBosses, watchDogAnimals,
                watchDogGolems, watchDogSilverfish, watchDogBotCheck}) setting.setVisible(watchdog, this);
        watchDogAps.setVisible(watchdog, this);
        watchDogLowTimerCheck.setVisible(watchdog, this);
        watchDogAllowPlayerBlocking.setVisible(watchdog, this);
        watchDogAllowTools.setVisible(watchdog && weaponOnly.isToggled(), this);
        autoBlockMinAps.setVisible(!watchdog, this);
        autoBlockMaxAps.setVisible(!watchdog, this);
        autoBlockIgnoreTeammates.setVisible(!watchdog, this);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onWatchDogUpdate(LeaderUpdateEvent event) {
        if (event.post) {
            if (isAutoBlockEnabled() && getAutoBlockMode() == 8 && Utils.nullCheck()) watchDogAutoBlock.post();
            return;
        }
        if (!isAutoBlockEnabled() || getAutoBlockMode() != 8 || !Utils.nullCheck()) return;
        if (watchDogAttackDelay > 0L) watchDogAttackDelay -= 50L;
        boolean attack = target != null && watchDogCanAttack();
        boolean block = attack && Utils.holdingSword() && !Velocity.stoppedBlock
                && !(watchDogSmartCancelled() && SmartAttack.cancelAuraBlocking.isToggled())
                && (!autoBlockRequirePress.isToggled() || watchDogUsePressed());
        if (!block) watchDogAutoBlock.noBlock();
        Entity attacked = null;
        if (attack) {
            if (block) watchDogAutoBlock.pre();
            autoBlockVisualBlocking = watchDogAutoBlock.visualBlocking();
            if ((!block || !watchDogAutoBlock.skipAttack())
                    && watchDogDistance(target) <= swingRange.getInput()
                    && performWatchDogAttack()) attacked = target;
            watchDogAutoBlock.finish(attacked);
        }
        autoBlockVisualBlocking = watchDogAutoBlock.visualBlocking();
    }

    private boolean watchDogSmartCancelled() {
        return ModuleManager.smartAttack != null && ModuleManager.smartAttack.isEnabled()
                && SmartAttack.shouldCancel && SmartAttack.onKillAura.isToggled();
    }

    private boolean watchDogBufferEnabled() {
        return ModuleManager.keepSprint != null && ModuleManager.keepSprint.isEnabled()
                && ModuleManager.keepSprint.isBufferMode();
    }

    private boolean performWatchDogAttack() {
        if (watchDogPlayerBusy()) return false;
        if (watchDogBufferPending) {
            watchDogBufferPending = false;
            if (target != null && !mc.thePlayer.isUsingItem() && !autoBlockServerBlocking && watchDogRayOnTarget()) {
                sendWatchDogAttack();
                return true;
            }
            return false;
        }
        if (Velocity.stoppedBlock || mc.thePlayer.isUsingItem() || autoBlockServerBlocking || watchDogAttackDelay > 0L) return false;
        if (watchDogLowTimerCheck.isToggled()
                && ((keystrokesmod.mixin.impl.accessor.IAccessorMinecraft) mc).getTimer().timerSpeed < 1.0F) return false;
        if (watchDogSmartCancelled()) return false;
        if (Velocity.extraAttacked) {
            Velocity.extraAttacked = false;
            if (ModuleManager.velocity != null) watchDogAutoBlock.onExtraAttack((int) ModuleManager.velocity.reduceMode.getInput());
            return false;
        }
        int min = Math.max(1, (int) minCPS.getInput());
        int max = Math.max(min, (int) maxCPS.getInput());
        watchDogAttackDelay += watchDogAutoBlock.active() ? (long) (1000.0F / (float) watchDogAps.getInput())
                : 1000L / (min + rand.nextInt(max - min + 1));
        if (!watchDogBufferEnabled()) mc.thePlayer.swingItem();
        if (!watchDogRayOnTarget()) return false;
        if (watchDogBufferEnabled()) {
            mc.thePlayer.setSprinting(false);
            net.minecraft.client.settings.KeyBinding.setKeyBindState(mc.gameSettings.keyBindSprint.getKeyCode(), false);
            if (!(mc.thePlayer.hurtTime > 0 && !ModuleManager.keepSprint.bufferOnHurt.isToggled())) {
                watchDogBufferPending = true;
                return false;
            }
        }
        sendWatchDogAttack();
        return true;
    }

    private boolean watchDogRayOnTarget() {
        if (target == null) return false;
        if (rotationMode.getInput() == 2 && watchDogDistance(target) <= attackRange.getInput()) return true;
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = RotationUtils.getVectorForRotation(attackPitch, attackYaw);
        double range = attackRange.getInput();
        float border = target.getCollisionBorderSize();
        AxisAlignedBB box = target.getEntityBoundingBox().expand(border, border, border);
        return box.isVecInside(eyes) || box.calculateIntercept(eyes,
                eyes.addVector(look.xCoord * range, look.yCoord * range, look.zCoord * range)) != null;
    }

    private void sendWatchDogAttack() {
        if (watchDogBufferEnabled()) mc.thePlayer.swingItem();
        MinecraftForge.EVENT_BUS.post(new AttackEvent(target, mc.thePlayer, true));
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        mc.thePlayer.sendQueue.addToSendQueue(new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK));
        if (!mc.playerController.isSpectatorMode()) mc.thePlayer.attackTargetEntityWithCurrentItem(target);
        hitRegistered = true;
    }

    boolean watchDogPlayerBusy() { return LeaderAutoBlockRuntime.INSTANCE.digging || LeaderAutoBlockRuntime.INSTANCE.placing; }
    boolean watchDogNoSlowEnabled() { return ModuleManager.noSlow != null && ModuleManager.noSlow.isEnabled(); }
    private boolean watchDogUsePressed() { return mc.currentScreen == null && GameSettings.isKeyDown(mc.gameSettings.keyBindUseItem); }
    private boolean watchDogCanAttack() {
        if (disableInInventory.isToggled() && mc.currentScreen instanceof GuiContainer) return false;
        if (weaponOnly.isToggled() && !watchDogHasWeapon()) return false;
        ItemStack held = mc.thePlayer.getHeldItem();
        if (held != null && watchDogUsePressed() && (held.getItem() instanceof net.minecraft.item.ItemBow
                || held.getItemUseAction() == net.minecraft.item.EnumAction.EAT
                || held.getItemUseAction() == net.minecraft.item.EnumAction.DRINK)) return false;
        if (mc.playerController.getIsHittingBlock()) return false;
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) return false;
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) return false;
        if (requireMouseDown.isToggled() && !GameSettings.isKeyDown(mc.gameSettings.keyBindAttack)) return false;
        return !disableWhileMining.isToggled() || mc.objectMouseOver == null
                || mc.objectMouseOver.typeOfHit != MovingObjectPosition.MovingObjectType.BLOCK
                || !GameSettings.isKeyDown(mc.gameSettings.keyBindAttack);
    }

    private boolean watchDogHasWeapon() {
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (stack == null) return false;
        if (stack.hasTagCompound()) {
            net.minecraft.nbt.NBTTagCompound tag = stack.getTagCompound();
            long id = tag.getCompoundTag("ExtraAttributes").getLong("UHCid");
            if (id == 50006L || id == 50009L) return true;
            if (tag.hasKey("HideFlags") && stack.getItem() instanceof net.minecraft.item.ItemSpade
                    && ((net.minecraft.item.ItemSpade) stack.getItem()).getToolMaterial() == net.minecraft.item.Item.ToolMaterial.EMERALD) return true;
        }
        if (!(stack.getItem() instanceof net.minecraft.item.ItemEnchantedBook)
                && (stack.getItem() instanceof ItemSword || net.minecraft.enchantment.EnchantmentHelper.getEnchantments(stack).containsKey(19))) return true;
        return watchDogAllowTools.isToggled() && stack.getItem() instanceof net.minecraft.item.ItemTool;
    }

    @Override
    public String getInfo() {
        return modes[(int) mode.getInput()];
    }

    @Override
    public void onEnable() {
        rand = new Random();
        nextClickTime = 0L;
        lastTargetSwitch = 0L;
        switchIndex = 0;
        hitRegistered = false;
        resetAutoBlock();
        watchDogAutoBlock.enable();
        watchDogAttackDelay = 0L;
        watchDogBufferPending = false;
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
        boolean watchdog = isAutoBlockEnabled() && getAutoBlockMode() == 8;
        if (!basicCondition() || !watchdog && !settingCondition()) {
            setTarget(null);
            return;
        }
        if (!watchdog) updateTarget();
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
        if (isAutoBlockEnabled() && getAutoBlockMode() == 8) return;
        handleAutoBlockPrePlayerInteract();
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

        if (targetDistance > attackRange.getInput() || !isRotationOnTarget(target, attackYaw, attackPitch)) {
            mc.thePlayer.swingItem();
            return;
        }

        MinecraftForge.EVENT_BUS.post(new AttackEvent(target, mc.thePlayer, true));
        mc.playerController.attackEntity(mc.thePlayer, target);
        mc.thePlayer.swingItem();
        hitRegistered = true;
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
    public void onWatchDogTick(TickEvent.ClientTickEvent event) {
        if (isAutoBlockEnabled() && getAutoBlockMode() == 8 && Utils.nullCheck() && event.phase == TickEvent.Phase.START) updateWatchDogTarget();
        if (isAutoBlockEnabled() && getAutoBlockMode() == 8 && Utils.nullCheck()
                && event.phase == TickEvent.Phase.END && Utils.holdingSword()
                && (mc.thePlayer.isUsingItem() || autoBlockServerBlocking) && !mc.thePlayer.isBlocking()) {
            ItemStack stack = mc.thePlayer.getHeldItem();
            mc.thePlayer.setItemInUse(stack, stack.getMaxItemUseDuration());
        }
    }

    public boolean shouldCancelWatchDogInput() {
        return isEnabled() && isAutoBlockEnabled() && getAutoBlockMode() == 8 && Utils.nullCheck()
                && (watchDogAutoBlock.active() || target != null && watchDogCanAttack());
    }

    public boolean shouldKeepWatchDogUse() {
        return isEnabled() && isAutoBlockEnabled() && getAutoBlockMode() == 8 && watchDogAutoBlock.active();
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

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onAutoBlockSendPacket(SendPacketEvent event) {
        if (!isAutoBlockEnabled()) return;

        if (event.getPacket() instanceof C07PacketPlayerDigging) {
            C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
            if (packet.getStatus() == C07PacketPlayerDigging.Action.RELEASE_USE_ITEM) {
                autoBlockServerBlocking = false;
            }
        } else if (event.getPacket() instanceof C09PacketHeldItemChange) {
            autoBlockServerBlocking = false;
            if (getAutoBlockMode() == 8 && watchDogAutoBlock.active() && Utils.nullCheck()) mc.thePlayer.stopUsingItem();
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
        if (mode == 7 || isWatchDog(mode)) return;

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

    private boolean isWatchDog(int mode) {
        return mode == 8;
    }

    boolean watchDogServerBlocking() { return autoBlockServerBlocking; }

    boolean watchDogHasTarget() {
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityLivingBase && watchDogValidTarget((EntityLivingBase) entity)
                    && watchDogDistance(entity) <= autoBlockRange.getInput()) return true;
        }
        return false;
    }

    private double watchDogDistance(Entity entity) {
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        float border = entity.getCollisionBorderSize();
        AxisAlignedBB box = entity.getEntityBoundingBox().expand(border, border, border);
        double x = net.minecraft.util.MathHelper.clamp_double(eyes.xCoord, box.minX, box.maxX);
        double y = net.minecraft.util.MathHelper.clamp_double(eyes.yCoord, box.minY, box.maxY);
        double z = net.minecraft.util.MathHelper.clamp_double(eyes.zCoord, box.minZ, box.maxZ);
        return eyes.distanceTo(new Vec3(x, y, z));
    }

    private float watchDogAngle(Entity entity) {
        float border = entity.getCollisionBorderSize();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        if (entity.getEntityBoundingBox().expand(border, border, border).isVecInside(eyes)) return 0.0F;
        return Math.abs(net.minecraft.util.MathHelper.wrapAngleTo180_float((float) Math.toDegrees(
                Math.atan2(entity.posZ - eyes.zCoord, entity.posX - eyes.xCoord)) - 90.0F - mc.thePlayer.rotationYaw)) * 2.0F;
    }

    private boolean watchDogVisible(Entity entity) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        for (int i = 1; i <= 9; i++) {
            Vec3 point = new Vec3((box.minX + box.maxX) * 0.5D, box.minY + i * 0.1D * (box.maxY - box.minY),
                    (box.minZ + box.maxZ) * 0.5D);
            if (mc.theWorld.rayTraceBlocks(eyes, point) == null) return true;
        }
        return false;
    }

    private boolean watchDogTeamColor(EntityLivingBase entity) {
        net.minecraft.client.network.NetworkPlayerInfo self = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        if (self == null || self.getPlayerTeam() == null || self.getPlayerTeam().getColorPrefix().length() < 2) return false;
        EntityLivingBase stand = mc.theWorld.findNearestEntityWithinAABB(EntityArmorStand.class, entity.getEntityBoundingBox(), entity);
        return stand != null && stand.getName().contains(self.getPlayerTeam().getColorPrefix().substring(0, 2));
    }

    private boolean watchDogSameTeam(EntityPlayer entity) {
        net.minecraft.client.network.NetworkPlayerInfo self = mc.getNetHandler().getPlayerInfo(mc.thePlayer.getUniqueID());
        net.minecraft.client.network.NetworkPlayerInfo other = mc.getNetHandler().getPlayerInfo(entity.getUniqueID());
        return self != null && other != null && self.getPlayerTeam() != null && other.getPlayerTeam() != null
                && self.getPlayerTeam().getColorPrefix().equals(other.getPlayerTeam().getColorPrefix());
    }

    private boolean watchDogBot(EntityPlayer entity) {
        net.minecraft.client.network.NetworkPlayerInfo info = mc.getNetHandler().getPlayerInfo(entity.getName());
        if (info == null) return true;
        net.minecraft.scoreboard.Scoreboard scoreboard = mc.theWorld.getScoreboard();
        net.minecraft.scoreboard.ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
        String first = "";
        if (objective != null) {
            for (net.minecraft.scoreboard.Score score : scoreboard.getSortedScores(objective)) {
                first = net.minecraft.scoreboard.ScorePlayerTeam.formatPlayerName(scoreboard.getPlayersTeam(score.getPlayerName()), score.getPlayerName());
                break;
            }
        }
        if (!first.equals("§ewww.hypixel.ne🎂§et") && !first.equals("§ewww.hypixel.ne§g§et")) return false;
        if (entity.getName().startsWith("§k")) return entity.isInvisible();
        if (info.getResponseTime() < 1) return true;
        net.minecraft.scoreboard.ScorePlayerTeam team = info.getPlayerTeam();
        return team != null && team.getTeamName().isEmpty() && team.getColorPrefix().equals("§c");
    }

    private boolean watchDogValidTarget(EntityLivingBase entity) {
        Entity view = mc.getRenderViewEntity();
        if (!mc.theWorld.loadedEntityList.contains(entity) || entity == mc.thePlayer || entity == mc.thePlayer.ridingEntity
                || entity == view || view != null && entity == view.ridingEntity || entity.deathTime > 0
                || watchDogAngle(entity) > fov.getInput() || !aimThroughBlocks.isToggled() && !watchDogVisible(entity)) return false;
        if (entity instanceof net.minecraft.client.entity.EntityOtherPlayerMP) {
            return watchDogPlayers.isToggled() && !Utils.isFriended((EntityPlayer) entity)
                    && (!ignoreTeammates.isToggled() || !watchDogSameTeam((EntityPlayer) entity))
                    && (!watchDogBotCheck.isToggled() || !watchDogBot((EntityPlayer) entity));
        }
        if (entity instanceof net.minecraft.entity.boss.EntityDragon || entity instanceof net.minecraft.entity.boss.EntityWither) return watchDogBosses.isToggled();
        if (entity instanceof net.minecraft.entity.monster.EntityMob || entity instanceof net.minecraft.entity.monster.EntitySlime) {
            return entity instanceof EntitySilverfish ? watchDogSilverfish.isToggled()
                    && (!ignoreTeammates.isToggled() || !watchDogTeamColor(entity)) : attackMobs.isToggled();
        }
        if (entity instanceof net.minecraft.entity.passive.EntityAnimal || entity instanceof net.minecraft.entity.passive.EntityBat
                || entity instanceof net.minecraft.entity.passive.EntitySquid || entity instanceof net.minecraft.entity.passive.EntityVillager) return watchDogAnimals.isToggled();
        return entity instanceof EntityIronGolem && watchDogGolems.isToggled()
                && (!ignoreTeammates.isToggled() || !watchDogTeamColor(entity));
    }

    private void updateWatchDogTarget() {
        long now = System.currentTimeMillis();
        if (target != null && watchDogValidTarget(target) && watchDogDistance(target) <= attackRange.getInput()
                && watchDogDistance(target) <= swingRange.getInput() && now - lastTargetSwitch < switchDelay.getInput()) return;
        lastTargetSwitch = now;
        double range = Math.max(autoBlockRange.getInput(), Math.max(attackRange.getInput(), swingRange.getInput()));
        List<EntityLivingBase> candidates = new ArrayList<>();
        for (Entity entity : mc.theWorld.loadedEntityList) {
            if (entity instanceof EntityLivingBase && watchDogValidTarget((EntityLivingBase) entity)
                    && watchDogDistance(entity) <= range) candidates.add((EntityLivingBase) entity);
        }
        if (candidates.stream().anyMatch(entity -> watchDogDistance(entity) <= swingRange.getInput())) candidates.removeIf(entity -> watchDogDistance(entity) > swingRange.getInput());
        if (candidates.stream().anyMatch(entity -> watchDogDistance(entity) <= attackRange.getInput())) candidates.removeIf(entity -> watchDogDistance(entity) > attackRange.getInput());
        if (candidates.stream().anyMatch(entity -> entity instanceof EntityPlayer && Utils.isEnemy((EntityPlayer) entity))) candidates.removeIf(entity -> !(entity instanceof EntityPlayer) || !Utils.isEnemy((EntityPlayer) entity));
        candidates.sort((a, b) -> {
            int order = 0;
            switch ((int) sortMode.getInput()) {
                case 1: order = Float.compare(a.getHealth() * (20.0F / a.getTotalArmorValue()), b.getHealth() * (20.0F / b.getTotalArmorValue())); break;
                case 2: order = Integer.compare(a.hurtResistantTime, b.hurtResistantTime); break;
                case 3: order = Float.compare(watchDogAngle(a), watchDogAngle(b)); break;
            }
            return order != 0 ? order : Double.compare(watchDogDistance(a), watchDogDistance(b));
        });
        if (candidates.isEmpty()) { setTarget(null); return; }
        if (mode.getInput() == 1 && hitRegistered) { hitRegistered = false; switchIndex++; }
        if (mode.getInput() == 0 || switchIndex >= candidates.size()) switchIndex = 0;
        setTarget(candidates.get(switchIndex));
    }

    boolean watchDogAttackDueSoon() {
        return watchDogAttackDelay <= 50L;
    }

    /**
     * Leader-Lite's interactAttack sequence: ray trace the attacked entity using
     * the aura rotation, send INTERACT_AT and INTERACT, then start sword blocking.
     */
    void sendWatchDogInteraction(Entity entity) {
        if (entity == null || entity.isDead) return;

        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        Vec3 look = RotationUtils.getVectorForRotation(attackPitch, attackYaw);
        Vec3 rayEnd = eyes.addVector(look.xCoord * 8.0, look.yCoord * 8.0, look.zCoord * 8.0);
        float border = entity.getCollisionBorderSize();
        MovingObjectPosition intercept = entity.getEntityBoundingBox().expand(border, border, border).calculateIntercept(eyes, rayEnd);
        if (intercept == null) return;

        Vec3 relativeHit = new Vec3(
                intercept.hitVec.xCoord - entity.posX,
                intercept.hitVec.yCoord - entity.posY,
                intercept.hitVec.zCoord - entity.posZ
        );
        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
        mc.thePlayer.sendQueue.addToSendQueue(new C02PacketUseEntity(entity, relativeHit));
        mc.thePlayer.sendQueue.addToSendQueue(new C02PacketUseEntity(entity, C02PacketUseEntity.Action.INTERACT));
        startAutoBlock(mc.thePlayer.getHeldItem());
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
        if (getAutoBlockMode() == 8 && isAutoBlockEnabled()) {
            return watchDogAutoBlock.active() || target != null && Utils.nullCheck()
                    && watchDogCanAttack() && !watchDogAllowPlayerBlocking.isToggled();
        }
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

    void startAutoBlock(ItemStack stack) {
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

    void startAutoBlockBlink() {
        if (getAutoBlockMode() == 8) { LeaderAutoBlockRuntime.INSTANCE.setBlink(true); return; }
        if (autoBlockBlinkRequest != null) return;
        autoBlockBlinkRequest = new LagRequest(EnumLagDirection.ONLY_OUTBOUND, new ModuleBackedTimeout(this));
        Raven.lagHandler.requestLag(autoBlockBlinkRequest);
    }

    void releaseAutoBlockBlink() {
        if (getAutoBlockMode() == 8 || autoBlockLastMode == 8) LeaderAutoBlockRuntime.INSTANCE.setBlink(false);
        if (autoBlockBlinkRequest == null) return;
        autoBlockBlinkRequest.getTimeout().forceTimeOut();
        autoBlockBlinkRequest = null;
    }

    public int getWatchDogBlockTick() {
        return watchDogAutoBlock.blockTick();
    }

    public boolean isNoSlowAutoBlocking() {
        return (getAutoBlockMode() == 8 ? isEnabled() && isAutoBlockEnabled() && watchDogAutoBlock.active() : isAutoBlockActive())
                && Utils.nullCheck() && Utils.holdingSword()
                && (mc.thePlayer.isUsingItem() || autoBlockServerBlocking)
                && !mc.thePlayer.isInWater() && !mc.thePlayer.isInLava();
    }

    public boolean isAutoBlockActive() {
        return isEnabled() && isAutoBlockEnabled() && (autoBlockServerBlocking || autoBlockBlinkRequest != null
                || getAutoBlockMode() == 8 && watchDogAutoBlock.active());
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
        boolean watchdog = autoBlockLastMode == 8 || getAutoBlockMode() == 8;
        watchDogBufferPending = false;
        if (watchDogAutoBlock != null && watchdog) watchDogAutoBlock.reset();
        if (!watchdog && Utils.nullCheck()) stopAutoBlock(true);
        autoBlockServerBlocking = false;
        releaseAutoBlockBlink();
        autoBlockVisualBlocking = false;
        autoBlockPendingReblock = false;
        autoBlockPendingInteractTarget = null;
        autoBlockTarget = null;
        autoBlockLastMode = -1;
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
            resetAutoBlock();
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
                && (autoBlockServerBlocking || autoBlockPendingReblock || autoBlockVisualBlocking
                || getAutoBlockMode() == 8 && watchDogAutoBlock.active())) {
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
