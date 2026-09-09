package keystrokesmod.module.impl.movement;

import keystrokesmod.utility.LeaderAutoBlockRuntime;
import keystrokesmod.event.LeaderUpdateEvent;
import keystrokesmod.event.RightClickMouseEvent;
import keystrokesmod.mixin.impl.accessor.IAccessorPlayerControllerMP;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.other.Disabler;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.item.ItemPotion;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.item.EntityArmorStand;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.item.EnumAction;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C08PacketPlayerBlockPlacement;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** Leader-Lite NoSlow, using Raven's movement hooks and owned lag requests. */
public class NoSlow extends Module {
    private final SliderSetting swordMode, foodMode, bowMode;
    private final SliderSetting swordMotion, foodMotion, bowMotion, swapDelay;
    private final ButtonSetting swordSprint, foodSprint, bowSprint, onlyKillAuraAutoBlock, slowOnRelease;
    private final ButtonSetting[] ticks = new ButtonSetting[5];
    private static final String[] SWORD_MODES = {"None", "Vanilla", "BlinkSemi", "Prediction", "WatchDog", "Test"};
    private static final String[] USE_MODES = {"None", "Vanilla", "Float"};
    private int delay, blinkDelay, lastSlot = -1;
    private boolean swapped, floatActive, floating;
    public boolean noSlowing;

    public NoSlow() {
        super("NoSlow", category.movement, 0);
        registerSetting(swordMode = new SliderSetting("Sword Mode", 1, SWORD_MODES));
        for (int i = 0; i < ticks.length; i++) registerSetting(ticks[i] = new ButtonSetting("Tick " + i, i < 2));
        registerSetting(slowOnRelease = new ButtonSetting("SlowOnRelease", true));
        registerSetting(swapDelay = new SliderSetting("Slow Delay", 0, 0, 3, 1));
        registerSetting(swordMotion = new SliderSetting("Sword Motion", "%", 100, 0, 100, 1));
        registerSetting(swordSprint = new ButtonSetting("Sword Sprint", true));
        registerSetting(onlyKillAuraAutoBlock = new ButtonSetting("Only Kill Aura Auto Block", false));
        registerSetting(foodMode = new SliderSetting("Food Mode", 0, USE_MODES));
        registerSetting(foodMotion = new SliderSetting("Food Motion", "%", 100, 0, 100, 1));
        registerSetting(foodSprint = new ButtonSetting("Food Sprint", true));
        registerSetting(bowMode = new SliderSetting("Bow Mode", 0, USE_MODES));
        registerSetting(bowMotion = new SliderSetting("Bow Motion", "%", 100, 0, 100, 1));
        registerSetting(bowSprint = new ButtonSetting("Bow Sprint", true));
    }

    @Override
    public void guiUpdate() {
        int mode = (int) swordMode.getInput();
        for (ButtonSetting tick : ticks) tick.setVisible(mode == 4, this);
        slowOnRelease.setVisible(mode == 3, this);
        swapDelay.setVisible(mode == 3, this);
        swordMotion.setVisible(mode != 0, this);
        swordSprint.setVisible(mode != 0, this);
        onlyKillAuraAutoBlock.setVisible(mode != 0, this);
        foodMotion.setVisible(foodMode.getInput() != 0, this);
        foodSprint.setVisible(foodMode.getInput() != 0, this);
        bowMotion.setVisible(bowMode.getInput() != 0, this);
        bowSprint.setVisible(bowMode.getInput() != 0, this);
    }

    private boolean holdingSword() {
        return Utils.nullCheck() && mc.thePlayer.getHeldItem() != null
                && mc.thePlayer.getHeldItem().getItem() instanceof ItemSword;
    }

    private boolean holdingFood() {
        if (!Utils.nullCheck()) return false;
        ItemStack stack = mc.thePlayer.getHeldItem();
        return stack != null && !ItemPotion.isSplash(stack.getItem().getMetadata(stack)) && (stack.getItemUseAction() == EnumAction.EAT || stack.getItemUseAction() == EnumAction.DRINK);
    }

    private boolean holdingBow() {
        return Utils.nullCheck() && mc.thePlayer.getHeldItem() != null
                && mc.thePlayer.getHeldItem().getItem() instanceof ItemBow;
    }

    private boolean auraBlocking() {
        return ModuleManager.killAura != null && ModuleManager.killAura.isNoSlowAutoBlocking();
    }

    private int blockTick() {
        return ModuleManager.killAura == null ? 0 : ModuleManager.killAura.getWatchDogBlockTick();
    }

    public boolean isSwordActive() {
        return swordMode.getInput() != 0 && holdingSword()
                && (!onlyKillAuraAutoBlock.isToggled() || auraBlocking());
    }

    public boolean isAnyActive() {
        if (!isEnabled() || !Utils.nullCheck()) return false;
        int mode = (int) swordMode.getInput();
        // Preserve Leader's sword-mode-first dispatch, including its food/bow behavior.
        if (mode != 2 && mode != 3 && mode != 4 && mode != 5) {
            return mc.thePlayer.isUsingItem() && (isSwordActive()
                    || holdingFood() && foodMode.getInput() != 0 || holdingBow() && bowMode.getInput() != 0);
        } else if (mode == 2 && isSwordActive()) return blinkDelay == 2;
        else if (mode == 3 && isSwordActive()) {
            if (!slowOnRelease.isToggled() || blockTick() != 0) return delay == 0;
        } else if (mode == 4 && isSwordActive()) {
            int tick = blockTick();
            return auraBlocking() && tick >= 0 && tick < ticks.length && ticks[tick].isToggled();
        } else if (mode == 5 && isSwordActive()) return !swapped;
        return false;
    }

    public boolean canSprint() {
        return holdingSword() ? swordSprint.isToggled()
                : holdingFood() ? foodSprint.isToggled() : holdingBow() && bowSprint.isToggled();
    }

    public static boolean isActive() {
        return ModuleManager.noSlow != null && ModuleManager.noSlow.isAnyActive();
    }

    public static float getSlowed() {
        NoSlow module = ModuleManager.noSlow;
        if (module == null || !module.isAnyActive()) return 0.2F;
        double motion = module.holdingSword() ? module.swordMotion.getInput()
                : module.holdingFood() ? module.foodMotion.getInput() : module.bowMotion.getInput();
        return (float) motion / 100.0F;
    }

    public static boolean blocksSprint() {
        NoSlow module = ModuleManager.noSlow;
        return module == null || !module.isAnyActive() || !module.canSprint();
    }

    // Leader's PlayerUtil.isUsingItem checks the physical use key, not itemInUse.
    private boolean usePressed() {
        return mc.currentScreen == null && GameSettings.isKeyDown(mc.gameSettings.keyBindUseItem);
    }

    @SubscribeEvent
    public void onLeaderUpdate(LeaderUpdateEvent event) { updatePackets(!event.post); }

    private void updatePackets(boolean pre) {
        if (!isEnabled() || !Utils.nullCheck()) return;
        int mode = (int) swordMode.getInput();
        if (isSwordActive() && usePressed()) {
            if (mode == 3 && pre) {
                if (--delay < 0) {
                    if (!slowOnRelease.isToggled() || blockTick() != 0) {
                        int slot = mc.thePlayer.inventory.currentItem;
                        sendSlot(Disabler.getAltSlot(slot));
                        sendSlot(slot % 7 + 2);
                        sendSlot(slot);
                    }
                    delay = (int) swapDelay.getInput();
                }
            } else if (mode == 2 && pre) {
                if (blinkDelay == 2) {
                    sendSlot(Disabler.getSwapSlot());
                    sendSlot(mc.thePlayer.inventory.currentItem);
                    mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(
                            C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
                    mc.thePlayer.stopUsingItem();
                    blinkDelay = 0;
                } else {
                    if (!auraBlocking() && blinkDelay == 0) {
                        releaseBlink();
                        ((IAccessorPlayerControllerMP) mc.playerController).callSyncCurrentPlayItem();
                        ItemStack stack = mc.thePlayer.getHeldItem();
                        mc.thePlayer.sendQueue.addToSendQueue(new C08PacketPlayerBlockPlacement(stack));
                        mc.thePlayer.setItemInUse(stack, stack.getMaxItemUseDuration());
                        LeaderAutoBlockRuntime.INSTANCE.setBlink(true);
                    }
                    blinkDelay++;
                }
            } else if (mode == 5) {
                if (swapped) { restoreSlot(); return; }
                else {
                    sendSlot(Disabler.getAltSlot(mc.thePlayer.inventory.currentItem));
                    swapped = true;
                }
            }
        } else {
            if (mode == 5 && swapped) { restoreSlot(); return; }
            if (mode == 2 && blinkDelay >= 0) {
                releaseBlink();
                blinkDelay = -1;
            }
        }
        // Retained only for legacy Raven callers; Leader has no separate bow movement flag.
        noSlowing = false;
    }

    public static void updateFloat() {
        if (ModuleManager.noSlow != null) ModuleManager.noSlow.updateFloatState();
    }

    private void updateFloatState() {
        if (!Utils.nullCheck()) return;
        // Leader FloatManager runs before NoSlow's LOW-priority PlayerUpdate listener.
        if ((floatActive || floating) && mc.thePlayer.onGround
                && mc.thePlayer.posY < mc.thePlayer.lastTickPosY && mc.thePlayer.motionY < 0.0D) {
            mc.thePlayer.setPosition(mc.thePlayer.posX, mc.thePlayer.posY + 0.001D, mc.thePlayer.posZ);
            floating = true;
        } else floating = false;
        if (isEnabled() && isFloatMode()) {
            int slot = mc.thePlayer.inventory.currentItem;
            if (lastSlot != slot && usePressed()) {
                lastSlot = slot;
                floatActive = true;
            }
        } else {
            lastSlot = -1;
            floatActive = false;
        }
    }

    /** Called at Leader's LivingUpdate position, immediately before the superclass update. */
    public static void applyMotion() {
        NoSlow module = ModuleManager.noSlow;
        if (module == null || !module.isAnyActive()) return;
        float multiplier = getSlowed();
        mc.thePlayer.movementInput.moveForward *= multiplier;
        mc.thePlayer.movementInput.moveStrafe *= multiplier;
        if (!module.canSprint()) mc.thePlayer.setSprinting(false);
    }

    private boolean isFloatMode() {
        return holdingFood() && foodMode.getInput() == 2 || holdingBow() && bowMode.getInput() == 2;
    }

    @SubscribeEvent
    public void onRightClick(RightClickMouseEvent event) {
        if (!isEnabled() || !Utils.nullCheck() || !isFloatMode()) return;
        MovingObjectPosition hit = mc.objectMouseOver;
        if (hit != null) {
            // Preserve normal block/entity interactions before trying to consume the held item.
            if (hit.typeOfHit == MovingObjectPosition.MovingObjectType.BLOCK
                    && isInteractable(mc.theWorld.getBlockState(hit.getBlockPos()).getBlock()) && !mc.thePlayer.isSneaking()) return;
            if (hit.entityHit instanceof EntityVillager || isShop(hit.entityHit)) return;
        }
        if (!floating && mc.thePlayer.onGround) {
            event.setCanceled(true);
            mc.thePlayer.motionY = 0.42D;
        }
    }

    private boolean isInteractable(Block block) {
        return block instanceof BlockContainer || block instanceof BlockWorkbench || block instanceof BlockAnvil
                || block instanceof BlockBed || block instanceof BlockDoor && block.getMaterial() != Material.iron
                || block instanceof BlockTrapDoor || block instanceof BlockFenceGate || block instanceof BlockFence
                || block instanceof BlockButton || block instanceof BlockLever || block instanceof BlockJukebox;
    }

    private boolean isShop(net.minecraft.entity.Entity entity) {
        if (!(entity instanceof EntityLivingBase) || entity == mc.thePlayer) return false;
        EntityLivingBase stand = mc.theWorld.findNearestEntityWithinAABB(
                EntityArmorStand.class, entity.getEntityBoundingBox(), (EntityLivingBase) entity);
        if (stand == null) return false;
        String name = stand.getName();
        return name.contains("RIGHT CLICK") || name.contains("ITEM SHOP") || name.contains("UPGRADES")
                || name.contains("BANKER") || name.contains("STREAK POWERS");
    }

    private void sendSlot(int slot) {
        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(slot));
    }

    private void restoreSlot() {
        if (swapped && Utils.nullCheck()) sendSlot(mc.thePlayer.inventory.currentItem);
        swapped = false;
    }

    private void releaseBlink() {
        LeaderAutoBlockRuntime.INSTANCE.setBlink(false);
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent event) {
        if (event.entity == mc.thePlayer) {
            // Connection-local state cannot be carried into a different world.
            swapped = floatActive = floating = noSlowing = false;
            delay = blinkDelay = 0;
            lastSlot = -1;
        }
    }

    @Override
    public String getInfo() { return SWORD_MODES[(int) swordMode.getInput()]; }
}
