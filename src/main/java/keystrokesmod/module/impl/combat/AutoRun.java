package keystrokesmod.module.impl.combat;

import keystrokesmod.event.PostPlayerInputEvent;
import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PrePlayerMovementInputEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.Vec3;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Arrays;
import java.util.List;

public class AutoRun extends Module {
    private static final List<Block> INVALID_BLOCKS = Arrays.asList(
            Blocks.enchanting_table, Blocks.chest, Blocks.ender_chest, Blocks.trapped_chest, Blocks.anvil,
            Blocks.sand, Blocks.web, Blocks.torch, Blocks.crafting_table, Blocks.furnace, Blocks.waterlily,
            Blocks.dispenser, Blocks.stone_pressure_plate, Blocks.wooden_pressure_plate, Blocks.noteblock,
            Blocks.dropper, Blocks.tnt, Blocks.standing_banner, Blocks.wall_banner, Blocks.redstone_torch,
            Blocks.gravel, Blocks.cactus, Blocks.bed, Blocks.lever, Blocks.standing_sign, Blocks.wall_sign,
            Blocks.jukebox, Blocks.oak_fence, Blocks.spruce_fence, Blocks.birch_fence, Blocks.jungle_fence,
            Blocks.dark_oak_fence, Blocks.oak_fence_gate, Blocks.spruce_fence_gate, Blocks.birch_fence_gate,
            Blocks.jungle_fence_gate, Blocks.dark_oak_fence_gate, Blocks.nether_brick_fence, Blocks.trapdoor,
            Blocks.melon_block, Blocks.brewing_stand, Blocks.cauldron, Blocks.skull, Blocks.hopper,
            Blocks.carpet, Blocks.redstone_wire, Blocks.light_weighted_pressure_plate,
            Blocks.heavy_weighted_pressure_plate, Blocks.daylight_detector
    );

    private final ButtonSetting fastMode;
    private final SliderSetting placeDelay;
    private final SliderSetting breakDelay;
    private final SliderSetting lookUpDelay;
    private final SliderSetting lookDownDelay;

    private int stage;
    private int ticks;
    private boolean ready;
    private boolean bridgeReady;
    private boolean actionComplete;
    private int previousSlot = -1;
    private BlockPos breakingPos;
    private BlockPos placementSupport;
    private boolean breakingStarted;
    private boolean waitingForPlacement;
    private boolean sneakMode;
    private boolean breakingBelow;
    private Float targetPitch;

    public AutoRun() {
        super("AutoRun", category.combat);
        registerSetting(fastMode = new ButtonSetting("Fast mode", true));
        registerSetting(placeDelay = new SliderSetting("Place delay", " ticks", 0, 0, 6, 1));
        registerSetting(breakDelay = new SliderSetting("Break delay", " ticks", 1, 0, 5, 1));
        registerSetting(lookUpDelay = new SliderSetting("Look up delay", " ticks", 0, 0, 5, 1));
        registerSetting(lookDownDelay = new SliderSetting("Look down delay", " ticks", 0, 0, 5, 1));
    }

    @Override
    public void onEnable() {
        stage = 0;
        ticks = 0;
        ready = false;
        bridgeReady = false;
        actionComplete = false;
        waitingForPlacement = false;
        previousSlot = mc.thePlayer == null ? -1 : mc.thePlayer.inventory.currentItem;
        breakingPos = null;
        placementSupport = null;
        breakingStarted = false;
        sneakMode = false;
        breakingBelow = false;
        targetPitch = mc.thePlayer != null && hasBlockAbove() ? -73.0f : 73.0f;
    }

    @Override
    public void onDisable() {
        releaseControlledKeys();
        if (mc.thePlayer != null && previousSlot >= 0 && previousSlot < 9) {
            mc.thePlayer.inventory.currentItem = previousSlot;
        }
        previousSlot = -1;
        waitingForPlacement = false;
        breakingPos = null;
        placementSupport = null;
        breakingStarted = false;
        sneakMode = false;
        breakingBelow = false;
        targetPitch = null;
    }

    @Override
    public String getInfo() {
        return mc.gameSettings.keyBindSneak.isKeyDown() ? "Down" : "UP";
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPreMotion(PreMotionEvent event) {
        if (!Utils.nullCheck() || targetPitch == null) {
            return;
        }
        event.setRotations(mc.thePlayer.rotationYaw, targetPitch);
        event.setSprinting(false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPrePlayerMovement(PrePlayerMovementInputEvent event) {
        if (mc.thePlayer != null) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostPlayerInput(PostPlayerInputEvent event) {
        if (!Utils.nullCheck()) {
            return;
        }

        mc.thePlayer.setSprinting(false);
        int placeTicks = Math.max(0, (int) placeDelay.getInput());
        int downTicks = Math.max(0, (int) lookDownDelay.getInput());
        int upTicks = Math.max(0, (int) lookUpDelay.getInput());
        int breakTicks = Math.max(0, (int) breakDelay.getInput());
        targetPitch = null;

        if (mc.gameSettings.keyBindSneak.isKeyDown()) {
            runSneakMode(placeTicks, breakTicks);
            return;
        }
        if (sneakMode) {
            resetRunState();
        }

        switch (stage) {
            case 0:
                if (!hasBlockAbove()) {
                    beginJumpCycle();
                    break;
                }
                targetPitch = -73.0f;
                selectBestTool(getAbovePos());
                if (++ticks >= breakTicks) {
                    breakTopBlock();
                }
                break;

            case 1:
                targetPitch = 73.0f;
                setAttack(false);
                setJump(true);
                if (++ticks >= downTicks) {
                    stage = 2;
                    ticks = 0;
                    ready = true;
                    if (placeTicks == 0) {
                        placeBelow(upTicks);
                    }
                }
                break;

            case 2:
                targetPitch = 73.0f;
                setAttack(false);
                setJump(true);
                if (ready && !actionComplete && ++ticks >= placeTicks) {
                    placeBelow(upTicks);
                }
                break;

            case 3:
                releaseControlledKeys();
                targetPitch = hasBlockAbove() ? -73.0f : 73.0f;
                ++ticks;
                boolean finished = ticks >= (fastMode.isToggled() ? upTicks : Math.max(1, upTicks))
                        && (fastMode.isToggled() || mc.thePlayer.onGround);
                if (finished) {
                    stage = 0;
                    ticks = 0;
                    ready = false;
                    bridgeReady = false;
                    actionComplete = false;
                    placementSupport = null;
                    if (!hasBlockAbove()) {
                        beginJumpCycle();
                    }
                }
                break;

            default:
                resetRunState();
                break;
        }
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent event) {
        if (!(event.getPacket() instanceof C07PacketPlayerDigging)) {
            return;
        }
        C07PacketPlayerDigging packet = (C07PacketPlayerDigging) event.getPacket();
        if (packet.getStatus() != C07PacketPlayerDigging.Action.STOP_DESTROY_BLOCK
                || breakingPos == null || !breakingPos.equals(packet.getPosition())) {
            return;
        }

        if (breakingBelow) {
            finishBreaking();
            ticks = 0;
            ready = true;
            actionComplete = false;
            breakingBelow = false;
            breakingPos = null;
            placementSupport = null;
            targetPitch = hasUpperPlacementNeighbor() ? -73.0f : 73.0f;
            return;
        }
        if (stage == 0) {
            finishBreaking();
            ticks = 0;
            ready = false;
            actionComplete = false;
            breakingPos = null;
            if (!hasBlockAbove()) {
                beginJumpCycle();
            }
            else {
                returnToBreakStage();
            }
        }
    }

    private void beginJumpCycle() {
        setAttack(false);
        setJump(true);
        mc.playerController.resetBlockRemoving();
        breakingPos = null;
        placementSupport = getBelowPos();
        breakingStarted = false;
        stage = 1;
        ticks = 0;
        ready = false;
        bridgeReady = true;
        actionComplete = false;
        breakingBelow = false;
        targetPitch = 73.0f;
    }

    private void returnToBreakStage() {
        releaseControlledKeys();
        stage = 0;
        ticks = 0;
        ready = false;
        bridgeReady = false;
        actionComplete = false;
        breakingBelow = false;
        targetPitch = -73.0f;
    }

    private void resetForSneakMode() {
        releaseControlledKeys();
        mc.playerController.resetBlockRemoving();
        stage = 0;
        ticks = 0;
        ready = false;
        bridgeReady = false;
        actionComplete = false;
        waitingForPlacement = false;
        placementSupport = null;
        breakingPos = null;
        breakingStarted = false;
        breakingBelow = false;
        sneakMode = true;
    }

    private void resetRunState() {
        releaseControlledKeys();
        mc.playerController.resetBlockRemoving();
        stage = 0;
        ticks = 0;
        ready = false;
        bridgeReady = false;
        actionComplete = false;
        waitingForPlacement = false;
        placementSupport = null;
        breakingPos = null;
        breakingStarted = false;
        breakingBelow = false;
        sneakMode = false;
    }

    private void runSneakMode(int placeTicks, int breakTicks) {
        if (!sneakMode) {
            resetForSneakMode();
        }
        setAttack(false);
        setJump(false);

        if (waitingForPlacement) {
            targetPitch = 73.0f;
            if (isBelowReplaceable()) {
                return;
            }
            waitingForPlacement = false;
            actionComplete = false;
            ticks = 0;
        }
        if (!ready && !isBelowReplaceable()) {
            breakBlockBelow(breakTicks);
            return;
        }
        if (!hasUpperPlacementNeighbor()) {
            breakBlockBelow(breakTicks);
            return;
        }
        if (!ready) {
            ready = true;
            actionComplete = false;
            ticks = 0;
        }
        breakingStarted = false;
        breakingBelow = false;
        breakingPos = null;
        targetPitch = -73.0f;
        if (!actionComplete && ++ticks >= placeTicks) {
            placeAbove();
        }
    }

    private void breakBlockBelow(int delay) {
        targetPitch = 73.0f;
        ready = false;
        actionComplete = false;
        setUseItem(false);
        if (isBelowReplaceable()) {
            ticks = 0;
            breakingStarted = false;
            breakingBelow = false;
            breakingPos = null;
            return;
        }
        selectBestTool(getBelowPos());
        if (++ticks >= delay) {
            damageBlockBelow();
        }
    }

    private void placeBelow(int lookUpTicks) {
        if (!ready || actionComplete || selectBlockSlot()) {
            return;
        }
        if (!placeOnTopOfSupport()) {
            return;
        }
        actionComplete = true;
        bridgeReady = false;
        ready = false;
        setJump(false);
        setUseItem(false);
        ticks = 0;
        waitingForPlacement = true;
        if (fastMode.isToggled() && lookUpTicks <= 0) {
            waitingForPlacement = false;
            placementSupport = null;
            if (!hasBlockAbove()) {
                beginJumpCycle();
            }
            else {
                stage = 0;
                targetPitch = -73.0f;
            }
        }
        else {
            stage = 3;
        }
    }

    private void placeAbove() {
        if (!ready || actionComplete || !hasUpperPlacementNeighbor() || selectBlockSlot()) {
            return;
        }
        if (!placeAt(getAbovePos())) {
            return;
        }
        actionComplete = true;
        ready = false;
        bridgeReady = false;
        waitingForPlacement = true;
        setJump(false);
        setUseItem(false);
        ticks = 0;
        placementSupport = null;
    }

    private BlockPos getAbovePos() {
        return new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY + 2.0, mc.thePlayer.posZ);
    }

    private BlockPos getBelowPos() {
        return new BlockPos(mc.thePlayer.posX, mc.thePlayer.posY - 1.0, mc.thePlayer.posZ);
    }

    private boolean hasBlockAbove() {
        return !isReplaceable(getAbovePos());
    }

    private boolean hasUpperPlacementNeighbor() {
        BlockPos above = getAbovePos();
        EnumFacing facing = EnumFacing.fromAngle(mc.thePlayer.rotationYaw);
        return isSolid(above.offset(facing.rotateY())) || isSolid(above.offset(facing.rotateYCCW()));
    }

    private boolean isBelowReplaceable() {
        return isReplaceable(getBelowPos());
    }

    private void breakTopBlock() {
        BlockPos above = getAbovePos();
        if (!above.equals(breakingPos)) {
            breakingPos = above;
            breakingStarted = false;
        }
        breakingBelow = false;
        damageBlock(above, EnumFacing.DOWN);
    }

    private void damageBlockBelow() {
        BlockPos below = getBelowPos();
        if (!below.equals(breakingPos)) {
            breakingPos = below;
            breakingStarted = false;
        }
        breakingBelow = true;
        damageBlock(below, EnumFacing.UP);
    }

    private void damageBlock(BlockPos pos, EnumFacing face) {
        if (!breakingStarted) {
            mc.playerController.clickBlock(pos, face);
            breakingStarted = true;
        }
        else {
            mc.playerController.onPlayerDamageBlock(pos, face);
        }
        mc.thePlayer.swingItem();
    }

    private void finishBreaking() {
        setAttack(false);
        mc.playerController.resetBlockRemoving();
        breakingStarted = false;
    }

    private boolean placeOnTopOfSupport() {
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!(held != null && held.getItem() instanceof ItemBlock)) {
            return false;
        }
        if (!isSolid(placementSupport)) {
            placementSupport = getBelowPos();
        }
        if (!isSolid(placementSupport)) {
            return false;
        }
        ItemBlock block = (ItemBlock) held.getItem();
        if (!block.canPlaceBlockOnSide(mc.theWorld, placementSupport, EnumFacing.UP, mc.thePlayer, held)) {
            return false;
        }
        Vec3 hitVec = new Vec3(placementSupport.getX() + 0.5, placementSupport.getY() + 1.0,
                placementSupport.getZ() + 0.5);
        return rightClickBlock(held, placementSupport, EnumFacing.UP, hitVec);
    }

    private boolean placeAt(BlockPos target) {
        if (!isReplaceable(target)) {
            return true;
        }
        ItemStack held = mc.thePlayer.getHeldItem();
        if (!(held != null && held.getItem() instanceof ItemBlock)) {
            return false;
        }
        ItemBlock block = (ItemBlock) held.getItem();
        for (EnumFacing face : EnumFacing.values()) {
            BlockPos support = target.offset(face.getOpposite());
            if (!isSolid(support) || !block.canPlaceBlockOnSide(mc.theWorld, support, face, mc.thePlayer, held)) {
                continue;
            }
            Vec3 hitVec = new Vec3(
                    support.getX() + 0.5 + face.getFrontOffsetX() * 0.5,
                    support.getY() + 0.5 + face.getFrontOffsetY() * 0.5,
                    support.getZ() + 0.5 + face.getFrontOffsetZ() * 0.5
            );
            if (rightClickBlock(held, support, face, hitVec)) {
                return true;
            }
        }
        return false;
    }

    private boolean rightClickBlock(ItemStack held, BlockPos support, EnumFacing face, Vec3 hitVec) {
        int oldSize = held.stackSize;
        if (!mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, held, support, face, hitVec)) {
            return false;
        }
        mc.thePlayer.swingItem();
        if (held.stackSize == 0) {
            mc.thePlayer.inventory.mainInventory[mc.thePlayer.inventory.currentItem] = null;
        }
        else if (held.stackSize != oldSize || mc.playerController.isInCreativeMode()) {
            mc.entityRenderer.itemRenderer.resetEquippedProgress();
        }
        return true;
    }

    private boolean isSolid(BlockPos pos) {
        return pos != null && !isReplaceable(pos);
    }

    private boolean isReplaceable(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        return block == Blocks.air || block.isReplaceable(mc.theWorld, pos);
    }

    private void selectBestTool(BlockPos pos) {
        Block block = mc.theWorld.getBlockState(pos).getBlock();
        float bestStrength = 1.0f;
        int bestSlot = -1;
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            float strength = Utils.getEfficiency(stack, block);
            if (strength > bestStrength) {
                bestStrength = strength;
                bestSlot = slot;
            }
        }
        if (bestSlot != -1) {
            mc.thePlayer.inventory.currentItem = bestSlot;
        }
    }

    private boolean selectBlockSlot() {
        int slot = findBlockSlot();
        if (slot == -1) {
            return true;
        }
        mc.thePlayer.inventory.currentItem = slot;
        return false;
    }

    public static int findBlockSlot() {
        if (mc.thePlayer == null) {
            return -1;
        }
        for (int slot = 0; slot < 9; ++slot) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemBlock) || stack.stackSize <= 0) {
                continue;
            }
            Block block = ((ItemBlock) stack.getItem()).getBlock();
            if (block.isFullCube() && !INVALID_BLOCKS.contains(block)) {
                return slot;
            }
        }
        return -1;
    }

    public boolean isBridgeReady() {
        return bridgeReady;
    }

    private void releaseControlledKeys() {
        setAttack(false);
        setJump(false);
        setUseItem(false);
    }

    private void setAttack(boolean pressed) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.getKeyCode(), pressed);
    }

    private void setJump(boolean pressed) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.getKeyCode(), pressed);
    }

    private void setUseItem(boolean pressed) {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.getKeyCode(), pressed);
    }
}
