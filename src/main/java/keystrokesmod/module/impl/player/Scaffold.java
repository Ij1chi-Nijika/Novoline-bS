package keystrokesmod.module.impl.player;

import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.JumpEvent;
import keystrokesmod.event.PostPlayerInputEvent;
import keystrokesmod.event.PreAttackEvent;
import keystrokesmod.event.PreMotionEvent;
import keystrokesmod.event.PrePlayerInputEvent;
import keystrokesmod.event.PrePlayerMovementInputEvent;
import keystrokesmod.event.RightClickMouseEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.event.SlotUpdateEvent;
import keystrokesmod.event.StrafeEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.movement.LongJump;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.*;
import net.minecraft.block.material.Material;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C00PacketKeepAlive;
import net.minecraft.network.play.client.C01PacketChatMessage;
import net.minecraft.network.play.client.C0FPacketConfirmTransaction;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraft.util.Vec3;
import net.minecraftforge.event.entity.living.LivingEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ThreadLocalRandom;

public class Scaffold extends Module {
    private static final double[] PLACE_OFFSETS = {
            0.03125, 0.09375, 0.15625, 0.21875, 0.28125, 0.34375,
            0.40625, 0.46875, 0.53125, 0.59375, 0.65625, 0.71875,
            0.78125, 0.84375, 0.90625, 0.96875
    };
    private static final double[] PREDICTION_OFFSETS = {0.1, 0.3, 0.5, 0.7, 0.9};
    private static final Random LEADER_RANDOM = new Random();
    private static final int MODE_NORMAL = 0;
    private static final int MODE_KEEP_Y = 1;
    private static final int MODE_SNAP = 2;
    private static final int MODE_LEGIT = 3;
    private static final int MODE_TELLY = 4;
    private static final String[] MODES = {"Normal", "KeepY", "Snap", "Legit", "Telly"};
    private static final String[] ROTATION_MODES = {"None", "Vanilla", "Backwards", "Prediction"};
    private static final String[] MOVE_FIX_MODES = {"None", "Silent"};

    private final SliderSetting mode;
    private final SliderSetting rotationMode;
    private final SliderSetting moveFix;
    private final SliderSetting jumpDelay;
    private final SliderSetting placeDelay;
    private final SliderSetting startRotateSpeed;
    private final SliderSetting normalRotateSpeed;
    private final ButtonSetting swing;
    public final ButtonSetting itemSpoof;
    private final ButtonSetting clutch;
    private final ButtonSetting onlyInVoid;
    private final ButtonSetting bpsRender;
    private final SliderSetting edgeThreshold;
    private final ButtonSetting ticksLimit;
    private final SliderSetting limitTicks;
    private final SliderSetting forwardSpeed;
    private final SliderSetting backSpeed;
    private final ButtonSetting snapRotation;
    private final ButtonSetting speedLimit;
    private final SliderSetting speedLimitTicks;
    private final SliderSetting forwardRotationTicks;
    private final SliderSetting legitSneakDelay;
    private final SliderSetting legitPlaceDuration;

    public static int count;

    private int rotationTick;
    private int lastSlot = -1;
    private int blockCount = -1;
    private float yaw = -180.0F;
    private float pitch;
    private float movementFixYaw;
    private boolean canRotate;
    private int tellyJumpDelayTimer;
    private int jumpDelayOverride = -1;
    private boolean wasInAir;
    private int stage;
    private int startY = 256;
    private boolean shouldKeepY;
    private boolean towering;
    private boolean clutchActive;
    private volatile int clutchTickCounter;
    private volatile boolean clutchBlinking;
    private double clutchSavedMotionX;
    private double clutchSavedMotionY;
    private double clutchSavedMotionZ;
    private final Deque<Packet<?>> clutchPackets = new ConcurrentLinkedDeque<>();
    private final Deque<Packet<?>> clutchVelocityPackets = new ConcurrentLinkedDeque<>();
    private boolean tellyRotationWritten;
    private boolean tellyRotationActive;
    private float tellyWrittenYaw;
    private float tellyWrittenPitch;
    private int placeDelayCounter;
    private double previousBpsX;
    private double previousBpsZ;
    private float currentBps;
    private boolean snapForward = true;
    private int snapForwardTimer;
    private boolean snapLocked;
    private int airTicks;
    private boolean pendingSpeedLimitRotation;
    private int forwardRotateTicksLeft;
    private boolean tellyFacingPlayer;
    private boolean tellyLandedThisTick;
    private boolean restoreGroundSprintAfterMoveFix;
    private int legitEdgeState;
    private int legitEdgeTimer;
    private boolean legitWasOnEdge;

    public Scaffold() {
        super("Scaffold", category.player);
        registerSetting(mode = new SliderSetting("Mode", 1, MODES));
        registerSetting(rotationMode = new SliderSetting("Rotate mode", 3, ROTATION_MODES));
        registerSetting(moveFix = new SliderSetting("Move fix", 1, MOVE_FIX_MODES));
        registerSetting(jumpDelay = new SliderSetting("Jump delay", 2.0, 0.0, 5.0, 1.0));
        registerSetting(placeDelay = new SliderSetting("Place delay", 1.0, 0.0, 5.0, 1.0));
        registerSetting(startRotateSpeed = new SliderSetting("Start rotate speed", 180.0, 1.0, 180.0, 1.0));
        registerSetting(normalRotateSpeed = new SliderSetting("Normal rotate speed", 180.0, 1.0, 180.0, 1.0));
        registerSetting(swing = new ButtonSetting("Swing", true));
        registerSetting(itemSpoof = new ButtonSetting("Item spoof", false));
        registerSetting(clutch = new ButtonSetting("Clutch", true));
        registerSetting(onlyInVoid = new ButtonSetting("Only void", false));
        registerSetting(bpsRender = new ButtonSetting("Render BPS", true));
        registerSetting(edgeThreshold = new SliderSetting("Edge threshold", 0.15, 0.01, 0.5, 0.01));
        registerSetting(ticksLimit = new ButtonSetting("Ticks limit", false));
        registerSetting(limitTicks = new SliderSetting("Limit ticks", 10.0, 1.0, 40.0, 1.0));
        registerSetting(forwardSpeed = new SliderSetting("Forward speed", 180.0, 1.0, 180.0, 1.0));
        registerSetting(backSpeed = new SliderSetting("Back speed", 180.0, 1.0, 180.0, 1.0));
        registerSetting(snapRotation = new ButtonSetting("Snap rotation", false));
        registerSetting(speedLimit = new ButtonSetting("Speed limit", false));
        registerSetting(speedLimitTicks = new SliderSetting("Speed limit ticks", 3.0, 0.0, 5.0, 1.0));
        registerSetting(forwardRotationTicks = new SliderSetting("Forward rotation ticks", 1.0, 1.0, 5.0, 1.0));
        registerSetting(legitSneakDelay = new SliderSetting("Legit sneak delay", 4.0, 1.0, 5.0, 1.0));
        registerSetting(legitPlaceDuration = new SliderSetting("Legit place time", 4.0, 2.0, 5.0, 1.0));
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    @Override
    public void guiUpdate() {
        int modeValue = (int) mode.getInput();
        rotationMode.setVisible(modeValue != MODE_LEGIT, this);
        jumpDelay.setVisible(isTellyFamily(modeValue), this);
        startRotateSpeed.setVisible(isTellyFamily(modeValue), this);
        normalRotateSpeed.setVisible(isTellyFamily(modeValue), this);
        edgeThreshold.setVisible(modeValue == MODE_SNAP, this);
        ticksLimit.setVisible(modeValue == MODE_SNAP, this);
        limitTicks.setVisible(modeValue == MODE_SNAP && ticksLimit.isToggled(), this);
        forwardSpeed.setVisible(modeValue == MODE_SNAP, this);
        backSpeed.setVisible(modeValue == MODE_SNAP, this);
        snapRotation.setVisible(modeValue == MODE_SNAP, this);
        speedLimit.setVisible(isTellyFamily(modeValue), this);
        speedLimitTicks.setVisible(isTellyFamily(modeValue) && speedLimit.isToggled(), this);
        forwardRotationTicks.setVisible(isTellyFamily(modeValue) && speedLimit.isToggled(), this);
        legitSneakDelay.setVisible(modeValue == MODE_LEGIT, this);
        legitPlaceDuration.setVisible(modeValue == MODE_LEGIT, this);
        onlyInVoid.setVisible(clutch.isToggled(), this);
    }

    @Override
    public void onEnable() {
        if (!Utils.nullCheck()) return;
        lastSlot = mc.thePlayer.inventory.currentItem;
        blockCount = -1;
        rotationTick = 3;
        yaw = -180.0F;
        pitch = 0.0F;
        canRotate = false;
        towering = false;
        placeDelayCounter = 0;
        previousBpsX = mc.thePlayer.posX;
        previousBpsZ = mc.thePlayer.posZ;
        currentBps = 0.0F;
        snapForward = true;
        snapForwardTimer = 0;
        snapLocked = false;
        airTicks = 0;
        pendingSpeedLimitRotation = false;
        forwardRotateTicksLeft = 0;
        tellyFacingPlayer = false;
        tellyLandedThisTick = false;
        restoreGroundSprintAfterMoveFix = false;
        legitEdgeState = 0;
        legitEdgeTimer = 0;
        legitWasOnEdge = false;
        // Leader's Telly keeps these fields across enable cycles. Its disable path
        // already resets Clutch, but stage/startY are intentionally not reinitialised.
        if (!isTellyFamily()) {
            stage = 0;
            startY = MathHelper.floor_double(mc.thePlayer.posY);
            clutchActive = false;
            clutchTickCounter = 0;
        }
    }

    @Override
    public void onDisable() {
        clutchReset();
        if (mc.thePlayer != null && lastSlot >= 0 && lastSlot < 9) {
            mc.thePlayer.inventory.currentItem = lastSlot;
        }
    }

    public boolean shouldSafeWalk() {
        return false;
    }

    public int getSlot() {
        return lastSlot;
    }

    /**
     * Scaffold owns movement correction while its local Move fix is active.
     * RotationHelper uses this to avoid applying a second correction pass.
     */
    public boolean isUsingOwnMovementFix() {
        return isEnabled() && (int) moveFix.getInput() == 1
                && (isTellyFamily() ? tellyRotationActive : canRotate)
                && (int) rotationMode.getInput() != 0;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onClientRotation(ClientRotationEvent event) {
        tellyRotationWritten = false;
        tellyRotationActive = false;
        if (!Utils.nullCheck() || mc.currentScreen != null && !isTellyFamily()) return;
        float eventYaw = event.yaw == null ? RotationUtils.serverRotations[0] : event.yaw;
        float eventPitch = event.pitch == null ? RotationUtils.serverRotations[1] : event.pitch;
        updateState();
        if (!canPlace()) return;
        selectBlock();
        updateModeState(eventYaw, eventPitch);
        initializeTellyRotation(eventYaw, eventPitch);

        BlockData blockData = getBlockData();
        Vec3 hitVec = null;
        if ((int) mode.getInput() == MODE_SNAP && snapForward) blockData = null;
        if (blockData != null) {
            Aim aim = isTellyFamily()
                    ? findTellyAim(blockData, eventYaw, eventPitch)
                    : findAim(blockData, eventYaw, eventPitch, (int) rotationMode.getInput() == 3);
            if (aim != null) {
                yaw = aim.yaw;
                pitch = aim.pitch;
                hitVec = aim.hitVec;
                canRotate = true;
            }
        }

        if (isTellyFamily() && canRotate && isMoving()) {
            float movementYaw = getMovementYaw();
            float backwardsYaw = eventYaw
                    + MathHelper.wrapAngleTo180_float(movementYaw - 180.0F - eventYaw);
            if (Math.abs(MathHelper.wrapAngleTo180_float(backwardsYaw - yaw)) < 90.0F
                    && (int) rotationMode.getInput() == 2) {
                yaw = tellyQuantize(backwardsYaw);
            }
        }

        float outputYaw = yaw;
        float outputPitch = pitch;
        int modeValue = (int) mode.getInput();
        if (isTellyFamily(modeValue) && (int) rotationMode.getInput() != 0) {
            float[] tellyRotation = getTellyRotation(eventYaw, eventPitch);
            outputYaw = tellyRotation[0];
            outputPitch = tellyRotation[1];
            event.yaw = outputYaw;
            event.pitch = outputPitch;
            movementFixYaw = outputYaw;
            tellyWrittenYaw = outputYaw;
            tellyWrittenPitch = outputPitch;
            tellyRotationWritten = true;
        } else if (canRotate && modeValue != MODE_LEGIT && (int) rotationMode.getInput() != 0) {
            float speed = 180.0F;
            if (modeValue == MODE_SNAP) speed = snapForward ? (float) forwardSpeed.getInput() : (float) backSpeed.getInput();
            if (Math.abs(MathHelper.wrapAngleTo180_float(outputYaw - eventYaw)) > speed) {
                rotationTick = Math.max(rotationTick, 1);
            }
            outputYaw = clampRotation(eventYaw, outputYaw, speed);
            outputPitch = quantizePitch(outputPitch, eventPitch);
            event.yaw = outputYaw;
            event.pitch = outputPitch;
            movementFixYaw = outputYaw;
        } else if (canRotate && modeValue == MODE_LEGIT) {
            event.yaw = outputYaw;
            event.pitch = outputPitch;
            movementFixYaw = outputYaw;
        }

        if (blockData != null && hitVec != null && rotationTick <= 0 && legitCanPlace()) {
            if (placeDelayCounter > 0) {
                placeDelayCounter--;
            } else {
                MovingObjectPosition ray = RotationUtils.rayCastBlock(mc.playerController.getBlockReachDistance(), yaw, pitch);
                if (ray != null && blockData.pos.equals(ray.getBlockPos()) && blockData.facing == ray.sideHit) {
                    place(blockData, ray.hitVec);
                } else if (canRotate) {
                    place(blockData, hitVec);
                }
                placeDelayCounter = (int) placeDelay.getInput();
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onFinalizeTellyRotation(ClientRotationEvent event) {
        if (!tellyRotationWritten || event.yaw == null || event.pitch == null) return;
        if (Float.compare(event.yaw, tellyWrittenYaw) != 0
                || Float.compare(event.pitch, tellyWrittenPitch) != 0) return;
        event.preQuantized = true;
        tellyRotationActive = true;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPreMotion(PreMotionEvent event) {
        if (!isTellyFamily() && shouldStopSprint()) event.setSprinting(false);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onMoveInput(PrePlayerInputEvent event) {
        if (clutchFrozen()) {
            event.setForward(0.0F);
            event.setStrafe(0.0F);
            event.setJump(false);
            event.setSneak(false);
            return;
        }
        if (isKeepYMode() && mc.thePlayer.onGround && tellyJumpDelayTimer > 0) {
            event.setJump(false);
        } else if (isTellyFamily() && mc.thePlayer.onGround && stage > 0
                && isMoving() && tellyJumpDelayTimer <= 0) {
            event.setJump(true);
        }
        if ((int) mode.getInput() == MODE_LEGIT && mc.currentScreen == null && mc.thePlayer.onGround
                && (legitEdgeState == 1 || legitEdgeState == 2)) {
            event.setSneak(true);
            event.setSneakSlowDownMultiplier(0.3D);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onPostInput(PostPlayerInputEvent event) {
        restoreGroundSprintAfterMoveFix = false;
        if ((int) moveFix.getInput() != 1
                || (isTellyFamily() ? !tellyRotationActive : !canRotate)
                || (int) rotationMode.getInput() == 0 || !isMoving()) return;

        // Silent correction can map W+A/W+D to a server-relative strafe or
        // backwards input. Vanilla then sees moveForward < 0.8 and drops sprint
        // for the landing tick, producing a visible sideways jerk/slowdown.
        // Remember the pre-correction sprint state and restore it immediately
        // before movement is applied; do not grant sprint if it was not active.
        boolean forwardHeld = mc.gameSettings.keyBindForward.isKeyDown()
                && !mc.gameSettings.keyBindBack.isKeyDown();
        boolean wasSprinting = mc.thePlayer.isSprinting()
                || mc.gameSettings.keyBindSprint.isKeyDown();
        restoreGroundSprintAfterMoveFix = isTellyFamily() && mc.thePlayer.onGround
                && forwardHeld && wasSprinting;
        fixMovement(movementFixYaw);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onStrafe(StrafeEvent event) {
        if (clutchFrozen()) {
            event.setForward(0.0F);
            event.setStrafe(0.0F);
        } else if ((int) moveFix.getInput() == 1
                && (isTellyFamily() ? tellyRotationActive : canRotate)
                && (int) rotationMode.getInput() != 0) {
            event.setYaw(movementFixYaw);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onJump(JumpEvent event) {
        if ((int) moveFix.getInput() == 1
                && (isTellyFamily() ? tellyRotationActive : canRotate)
                && (int) rotationMode.getInput() != 0) {
            event.setYaw(movementFixYaw);
        }
        if ((isTellyMode() && isMoving())
                || (isKeepYMode() && restoreGroundSprintAfterMoveFix)) {
            event.setSprint(true);
            mc.thePlayer.setSprinting(true);
        }
        if (!isTellyFamily() && shouldStopSprint()) event.setSprint(false);
    }

    @SubscribeEvent
    public void onPlayerMovement(PrePlayerMovementInputEvent event) {
        if (isTellyFamily()) {
            if (restoreGroundSprintAfterMoveFix
                    && mc.gameSettings.keyBindForward.isKeyDown()
                    && !mc.gameSettings.keyBindBack.isKeyDown()) {
                mc.thePlayer.setSprinting(true);
            }
            restoreGroundSprintAfterMoveFix = false;
        } else if (shouldStopSprint()) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClutchSendPacket(SendPacketEvent event) {
        if (!clutchBlinking || event.isCanceled()) return;
        Packet<?> packet = event.getPacket();
        if (packet instanceof C00PacketKeepAlive || packet instanceof C01PacketChatMessage) return;
        if (clutchPackets.isEmpty() && packet instanceof C0FPacketConfirmTransaction) return;
        clutchPackets.offer(packet);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onClutchReceivePacket(ReceivePacketEvent event) {
        if (!clutchBlinking || mc.thePlayer == null || event.isCanceled()
                || !(event.getPacket() instanceof S12PacketEntityVelocity)) return;
        S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
        if (packet.getEntityID() != mc.thePlayer.getEntityId()) return;
        clutchVelocityPackets.offer(packet);
        event.setCanceled(true);
        int remainder = clutchTickCounter % 10;
        if (remainder != 0) clutchTickCounter += 9 - remainder;
    }

    @SubscribeEvent
    public void onLivingUpdate(LivingEvent.LivingUpdateEvent event) {
        if (event.entity != mc.thePlayer) return;
        double dx = mc.thePlayer.posX - previousBpsX;
        double dz = mc.thePlayer.posZ - previousBpsZ;
        currentBps = (float) (Math.sqrt(dx * dx + dz * dz) * 20.0D);
        previousBpsX = mc.thePlayer.posX;
        previousBpsZ = mc.thePlayer.posZ;
        if (clutchFrozen()) {
            mc.thePlayer.motionX = 0.0D;
            mc.thePlayer.motionY = 0.0D;
            mc.thePlayer.motionZ = 0.0D;
        } else if (shouldStopSprint()) {
            mc.thePlayer.setSprinting(false);
        }
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !bpsRender.isToggled() || !Utils.nullCheck()
                || mc.currentScreen != null || mc.gameSettings.showDebugInfo) return;
        count = countBlocks();
        ScaledResolution resolution = new ScaledResolution(mc);
        int width = 100;
        int x = resolution.getScaledWidth() / 2 - width / 2;
        int y = resolution.getScaledHeight() / 2;
        int fill = (int) Math.min(width, currentBps / 10.0F * width);
        Gui.drawRect(x, y, x + width, y + 4, new Color(0, 0, 0, 120).getRGB());
        Gui.drawRect(x, y, x + fill, y + 4,
                currentBps > 5.92F ? new Color(255, 50, 50, 200).getRGB() : new Color(0, 200, 255, 200).getRGB());
        int marker = x + (int) (5.92F / 10.0F * width);
        Gui.drawRect(marker, y - 2, marker + 1, y + 6, Color.WHITE.getRGB());
        mc.fontRendererObj.drawStringWithShadow("5.92", marker - mc.fontRendererObj.getStringWidth("5.92") / 2, y - 12, -1);
        mc.fontRendererObj.drawStringWithShadow(String.format("%.2f BPS", currentBps), x + width + 2, y - 2, -1);
    }

    @SubscribeEvent
    public void onLeftClick(PreAttackEvent event) {
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onRightClick(RightClickMouseEvent event) {
        event.setCanceled(true);
    }

    @SubscribeEvent
    public void onSlotUpdate(SlotUpdateEvent event) {
        lastSlot = event.slot;
        event.setCanceled(true);
    }

    private void updateState() {
        tellyLandedThisTick = false;
        if (rotationTick > 0) rotationTick--;
        if (forwardRotateTicksLeft > 0) forwardRotateTicksLeft--;
        if (mc.thePlayer.onGround) {
            if (stage > 0) stage--;
            if (stage < 0) stage++;
            if (!shouldKeepY) startY = MathHelper.floor_double(mc.thePlayer.posY);
            shouldKeepY = false;
            towering = false;
            if (wasInAir) {
                tellyJumpDelayTimer = isTellyFamily()
                        ? (jumpDelayOverride >= 0 ? jumpDelayOverride : (int) jumpDelay.getInput()) : 0;
                tellyFacingPlayer = isTellyMode();
                tellyLandedThisTick = tellyFacingPlayer;
                wasInAir = false;
            }
            if (tellyJumpDelayTimer > 0) tellyJumpDelayTimer--;
            if (speedLimit.isToggled()) {
                pendingSpeedLimitRotation = false;
                airTicks = 0;
            }
        } else {
            if (speedLimit.isToggled()) airTicks++;
            tellyFacingPlayer = false;
            wasInAir = true;
        }
        if (isTellyFamily() && mc.thePlayer.onGround && isMoving()
                && !mc.gameSettings.keyBindJump.isKeyDown() && stage == 0) stage = 1;
        jumpDelayOverride = isTellyFamily() && mc.gameSettings.keyBindJump.isKeyDown() ? 2 : -1;
        updateClutch();
    }

    private void updateModeState(float eventYaw, float eventPitch) {
        int modeValue = (int) mode.getInput();
        if (modeValue == MODE_SNAP) updateSnap();
        if (modeValue == MODE_LEGIT) updateLegit(eventYaw);
        if (modeValue == MODE_SNAP) {
            if (snapForward) {
                yaw = quantizeAngle(getMovementYaw(), eventYaw);
                pitch = quantizePitch(80.0F, eventPitch);
                canRotate = true;
            } else if (!snapRotation.isToggled()) {
                yaw = quantizeAngle(getMovementYaw() + 180.0F, eventYaw);
                pitch = quantizePitch(85.0F, eventPitch);
                canRotate = true;
            }
        }
    }

    private float[] getTellyRotation(float eventYaw, float eventPitch) {
        float targetYaw = yaw;
        float targetPitch = pitch;
        boolean normalRotationWhileJumpHeld = isKeepYMode()
                && mc.gameSettings.keyBindJump.isKeyDown();
        boolean startingJump = isTellyMode() && !tellyLandedThisTick && isTowering()
                && tellyJumpDelayTimer <= 0 && forwardRotateTicksLeft <= 0;

        if (isTellyMode() && tellyFacingPlayer && !startingJump) {
            targetYaw = tellyQuantize(eventYaw
                    + MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - eventYaw));
            targetPitch = tellyQuantize(mc.thePlayer.rotationPitch);
            return new float[]{targetYaw, targetPitch};
        }

        if (speedLimit.isToggled() && forwardRotateTicksLeft > 0) {
            float yawDelta = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - eventYaw);
            yaw = tellyQuantize(eventYaw + yawDelta * random(0.98F, 0.99F));
            pitch = tellyQuantize(random(30.0F, 80.0F));
            rotationTick = 0;
        } else if (towering && (mc.thePlayer.motionY > 0.0D || mc.thePlayer.posY > startY + 1.0D)) {
            float yawDifference = MathHelper.wrapAngleTo180_float(yaw - eventYaw);
            float tolerance = rotationTick >= 2
                    ? (float) startRotateSpeed.getInput() : (float) normalRotateSpeed.getInput();
            if (Math.abs(yawDifference) > tolerance) {
                targetYaw = tellyQuantize(eventYaw + MathHelper.clamp_float(yawDifference, -tolerance, tolerance));
                rotationTick = Math.max(rotationTick, 1);
            }
        }

        boolean beginTowerRotation = isTellyMode() ? startingJump
                : !normalRotationWhileJumpHeld && isTowering()
                && tellyJumpDelayTimer <= 0 && forwardRotateTicksLeft <= 0;
        if (beginTowerRotation) {
            if (isTellyMode()) tellyFacingPlayer = false;
            if (!speedLimit.isToggled()) {
                float yawDelta = MathHelper.wrapAngleTo180_float(yaw - eventYaw);
                targetYaw = tellyQuantize(eventYaw + MathHelper.clamp_float(yawDelta, -45.0F, 45.0F));
                float pitchDelta = pitch - eventPitch;
                targetPitch = tellyQuantize(eventPitch
                        + MathHelper.clamp_float(pitchDelta, -45.0F, 45.0F));
                rotationTick = 3;
                towering = true;
            } else {
                pendingSpeedLimitRotation = true;
                airTicks = 0;
            }
        } else if (tellyJumpDelayTimer > 0) {
            targetYaw = yaw != -180.0F
                    ? yaw : tellyQuantize(MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - eventYaw) + eventYaw);
            targetPitch = Math.abs(pitch) > 10.0F ? pitch : 60.0F;
        }

        if (speedLimit.isToggled() && pendingSpeedLimitRotation && !mc.thePlayer.onGround
                && airTicks >= (int) speedLimitTicks.getInput()) {
            float yawDelta = MathHelper.wrapAngleTo180_float(mc.thePlayer.rotationYaw - eventYaw);
            yaw = tellyQuantize(eventYaw + yawDelta * random(0.98F, 0.99F));
            pitch = tellyQuantize(random(30.0F, 80.0F));
            forwardRotateTicksLeft = (int) forwardRotationTicks.getInput();
            rotationTick = 0;
            towering = true;
            pendingSpeedLimitRotation = false;
            airTicks = 0;
        }
        return new float[]{targetYaw, targetPitch};
    }

    private void initializeTellyRotation(float eventYaw, float eventPitch) {
        if (!isTellyFamily() || canRotate) return;
        boolean initialRotation = yaw == -180.0F && pitch == 0.0F;
        float movementYaw = getMovementYaw();
        float backwardsYaw = eventYaw + MathHelper.wrapAngleTo180_float(movementYaw - 180.0F - eventYaw);
        float diagonalTarget = isDiagonal(movementYaw)
                ? movementYaw - 180.0F
                : movementYaw - 135.0F * ((movementYaw + 180.0F) % 90.0F < 45.0F ? 1.0F : -1.0F);
        float diagonalYaw = eventYaw + MathHelper.wrapAngleTo180_float(diagonalTarget - eventYaw);

        switch ((int) rotationMode.getInput()) {
            case 1:
                yaw = tellyQuantize(diagonalYaw);
                break;
            case 2:
                yaw = tellyQuantize(backwardsYaw);
                if (initialRotation) pitch = tellyQuantize(85.0F);
                break;
            case 3:
                if (initialRotation) {
                    yaw = tellyQuantize(diagonalYaw);
                    pitch = tellyQuantize(85.0F);
                }
                break;
        }
    }

    private void updateSnap() {
        if (!ticksLimit.isToggled()) {
            snapForward = mc.thePlayer.onGround && !isOnEdge();
            return;
        }
        boolean canForward = mc.thePlayer.onGround && !isOnEdge();
        if (!canForward) {
            snapForward = false;
            snapForwardTimer = 0;
            snapLocked = false;
        } else if (snapLocked) {
            snapForward = false;
        } else if (!snapForward) {
            snapForward = true;
            snapForwardTimer = 1;
        } else if (++snapForwardTimer >= (int) limitTicks.getInput()) {
            snapForward = false;
            snapLocked = true;
            snapForwardTimer = 0;
        }
    }

    private void updateLegit(float eventYaw) {
        boolean edge = mc.thePlayer.onGround && isOnEdge();
        boolean holding = isUsableBlock(mc.thePlayer.getHeldItem());
        boolean justReached = edge && !legitWasOnEdge;
        if (!mc.thePlayer.onGround) {
            legitEdgeState = legitEdgeTimer = 0;
        } else if (edge && holding) {
            if (legitEdgeState == 0 && (justReached || legitEdgeTimer == 0)) {
                legitEdgeState = 1;
                legitEdgeTimer = (int) legitSneakDelay.getInput();
            } else if (legitEdgeState == 1 && --legitEdgeTimer <= 0) {
                legitEdgeState = 2;
                legitEdgeTimer = (int) legitPlaceDuration.getInput();
            } else if (legitEdgeState == 2 && --legitEdgeTimer <= 0) {
                legitEdgeState = 3;
                legitEdgeTimer = 3 + ThreadLocalRandom.current().nextInt(4);
            } else if (legitEdgeState == 3 && --legitEdgeTimer <= 0) {
                legitEdgeState = legitEdgeTimer = 0;
            }
        } else {
            legitEdgeState = legitEdgeTimer = 0;
        }
        legitWasOnEdge = edge;
        yaw = quantizeAngle(getMovementYaw() - 180.0F, eventYaw);
        pitch = 85.0F;
        canRotate = true;
    }

    private boolean legitCanPlace() {
        return (int) mode.getInput() != MODE_LEGIT || !mc.thePlayer.onGround || legitEdgeState == 0 || legitEdgeState == 2;
    }

    private BlockData getBlockData() {
        int playerY = MathHelper.floor_double(mc.thePlayer.posY);
        BlockPos target = new BlockPos(MathHelper.floor_double(mc.thePlayer.posX),
                (stage != 0 && !shouldKeepY ? Math.min(playerY, startY) : playerY) - 1,
                MathHelper.floor_double(mc.thePlayer.posZ));
        if (!isReplaceableForScaffold(target)) return null;

        List<BlockPos> supports = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            for (int y = -4; y <= 0; y++) {
                for (int z = -4; z <= 4; z++) {
                    BlockPos pos = target.add(x, y, z);
                    Block block = BlockUtils.getBlock(pos);
                    if (!isReplaceableForScaffold(pos) && !isInteractableForScaffold(block)
                            && mc.thePlayer.getDistance(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D)
                            <= mc.playerController.getBlockReachDistance()) {
                        for (EnumFacing facing : EnumFacing.VALUES) {
                            if (facing != EnumFacing.DOWN && isReplaceableForScaffold(pos.offset(facing))) {
                                supports.add(pos);
                            }
                        }
                    }
                }
            }
        }
        if (supports.isEmpty()) return null;
        if (isTellyFamily()) {
            supports.sort(Comparator.comparingDouble(pos -> pos.distanceSqToCenter(
                    target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D)));
        } else {
            supports.sort(Comparator.comparingDouble(pos -> pos.distanceSq(
                    target.getX(), target.getY(), target.getZ())));
        }
        BlockPos support = supports.get(0);
        EnumFacing facing = getBestFacing(support, target);
        return facing == null ? null : new BlockData(support, facing);
    }

    private EnumFacing getBestFacing(BlockPos support, BlockPos target) {
        EnumFacing best = null;
        double bestDistance = Double.MAX_VALUE;
        for (EnumFacing facing : EnumFacing.VALUES) {
            if (facing == EnumFacing.DOWN
                    || !isTellyFamily() && !isReplaceableForScaffold(support.offset(facing))) continue;
            BlockPos placed = support.offset(facing);
            if (placed.getY() > target.getY()) continue;
            double distance = isTellyFamily()
                    ? placed.distanceSqToCenter(target.getX() + 0.5D, target.getY() + 0.5D, target.getZ() + 0.5D)
                    : placed.distanceSq(target.getX(), target.getY(), target.getZ());
            if (best == null || distance < bestDistance || distance == bestDistance && facing == EnumFacing.UP) {
                best = facing;
                bestDistance = distance;
            }
        }
        return best;
    }

    private Aim findAim(BlockData data, float baseYaw, float basePitch, boolean prediction) {
        double[] xOffsets = prediction ? PREDICTION_OFFSETS : PLACE_OFFSETS;
        double[] yOffsets = prediction ? PREDICTION_OFFSETS : PLACE_OFFSETS;
        double[] zOffsets = prediction ? PREDICTION_OFFSETS : PLACE_OFFSETS;
        switch (data.facing) {
            case NORTH: zOffsets = new double[]{prediction ? 0.02 : 0.0}; break;
            case EAST: xOffsets = new double[]{prediction ? 0.98 : 1.0}; break;
            case SOUTH: zOffsets = new double[]{prediction ? 0.98 : 1.0}; break;
            case WEST: xOffsets = new double[]{prediction ? 0.02 : 0.0}; break;
            case DOWN: yOffsets = new double[]{prediction ? 0.02 : 0.0}; break;
            case UP: yOffsets = new double[]{prediction ? 0.98 : 1.0}; break;
        }
        Aim best = null;
        double bestCost = Double.MAX_VALUE;
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        for (double x : xOffsets) for (double y : yOffsets) for (double z : zOffsets) {
            Vec3 point = new Vec3(data.pos.getX() + x, data.pos.getY() + y, data.pos.getZ() + z);
            float[] rotations = rotationsTo(eyes, point, baseYaw, basePitch);
            MovingObjectPosition ray = rayCast(eyes, rotations[0], rotations[1]);
            if (ray == null || !data.pos.equals(ray.getBlockPos()) || data.facing != ray.sideHit) continue;
            double cost = Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - yaw)) + Math.abs(rotations[1] - pitch);
            if (best == null || cost < bestCost) {
                bestCost = cost;
                best = new Aim(rotations[0], rotations[1], ray.hitVec);
            }
        }
        if (best != null && prediction) {
            best.yaw += random(-0.5F, 0.5F);
            best.pitch += random(-0.3F, 0.3F);
        }
        return best;
    }

    private Aim findTellyAim(BlockData data, float eventYaw, float eventPitch) {
        boolean prediction = (int) rotationMode.getInput() == 3;
        double[] xOffsets = prediction ? PREDICTION_OFFSETS : PLACE_OFFSETS;
        double[] yOffsets = prediction ? PREDICTION_OFFSETS : PLACE_OFFSETS;
        double[] zOffsets = prediction ? PREDICTION_OFFSETS : PLACE_OFFSETS;
        switch (data.facing) {
            case NORTH: zOffsets = new double[]{prediction ? 0.02D : 0.0D}; break;
            case EAST: xOffsets = new double[]{prediction ? 0.98D : 1.0D}; break;
            case SOUTH: zOffsets = new double[]{prediction ? 0.98D : 1.0D}; break;
            case WEST: xOffsets = new double[]{prediction ? 0.02D : 0.0D}; break;
            case DOWN: yOffsets = new double[]{prediction ? 0.02D : 0.0D}; break;
            case UP: yOffsets = new double[]{prediction ? 0.98D : 1.0D}; break;
        }

        Aim best = null;
        double bestDifference = Double.MAX_VALUE;
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        float baseYaw = eventYaw + MathHelper.wrapAngleTo180_float(yaw - eventYaw);
        for (double x : xOffsets) for (double y : yOffsets) for (double z : zOffsets) {
            Vec3 point = new Vec3(data.pos.getX() + x, data.pos.getY() + y, data.pos.getZ() + z);
            float[] rotations = prediction
                    ? rawRotationsTo(eyes, point)
                    : leaderRotationsTo(point.xCoord - eyes.xCoord, point.yCoord - eyes.yCoord,
                    point.zCoord - eyes.zCoord, baseYaw, pitch);
            MovingObjectPosition ray = rayCast(eyes, rotations[0], rotations[1]);
            if (ray == null || !data.pos.equals(ray.getBlockPos()) || data.facing != ray.sideHit) continue;

            double difference;
            if (prediction) {
                float yawDifference = Math.abs(MathHelper.wrapAngleTo180_float(rotations[0] - yaw));
                float pitchDifference = Math.abs(rotations[1] - pitch);
                difference = Math.sqrt(yawDifference * yawDifference + pitchDifference * pitchDifference);
            } else {
                difference = Math.abs(rotations[0] - baseYaw) + Math.abs(rotations[1] - pitch);
            }
            if (best == null || difference < bestDifference) {
                bestDifference = difference;
                best = new Aim(rotations[0], rotations[1], ray.hitVec);
            }
        }
        if (best != null && prediction) {
            best.yaw += random(-0.5F, 0.5F);
            best.pitch += random(-0.3F, 0.3F);
        }
        return best;
    }

    private float[] rawRotationsTo(Vec3 eyes, Vec3 point) {
        double x = point.xCoord - eyes.xCoord;
        double y = point.yCoord - eyes.yCoord;
        double z = point.zCoord - eyes.zCoord;
        double horizontal = MathHelper.sqrt_double(x * x + z * z);
        return new float[]{(float) Math.toDegrees(Math.atan2(z, x)) - 90.0F,
                (float) -Math.toDegrees(Math.atan2(y, horizontal))};
    }

    private float[] leaderRotationsTo(double x, double y, double z, float currentYaw, float currentPitch) {
        double horizontal = Math.sqrt(x * x + z * z);
        float yawDelta = MathHelper.wrapAngleTo180_float((float) Math.toDegrees(Math.atan2(z, x)) - 90.0F - currentYaw);
        float pitchDelta = MathHelper.wrapAngleTo180_float((float) -Math.toDegrees(Math.atan2(y, horizontal)) - currentPitch);
        if (Math.abs(yawDelta) <= 1.0F) yawDelta = 0.0F;
        else yawDelta *= 1.0F - Math.max(0.0F, random(-0.1F, 0.1F)) * 0.5F;
        if (Math.abs(pitchDelta) <= 1.0F) pitchDelta = 0.0F;
        else pitchDelta *= 1.0F - Math.max(0.0F, random(-0.1F, 0.1F)) * 0.5F;
        return new float[]{tellyQuantize(currentYaw + yawDelta), tellyQuantize(currentPitch + pitchDelta)};
    }

    private float tellyQuantize(float angle) {
        return (float) ((double) angle - (double) angle % (double) 0.0096F);
    }

    private void place(BlockData data, Vec3 hitVec) {
        ItemStack stack = mc.thePlayer.getHeldItem();
        if (!isUsableBlock(stack) || blockCount <= 0
                || !isTellyFamily() && !BlockUtils.replaceable(data.pos.offset(data.facing))) return;
        if (mc.playerController.onPlayerRightClick(mc.thePlayer, mc.theWorld, stack, data.pos, data.facing, hitVec)) {
            if (!mc.thePlayer.capabilities.isCreativeMode) blockCount--;
            if (swing.isToggled()) mc.thePlayer.swingItem();
            else mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
        }
    }

    private void selectBlock() {
        ItemStack current = mc.thePlayer.getHeldItem();
        if (isTellyFamily()) {
            int heldCount = isUsableBlock(current) ? current.stackSize : 0;
            blockCount = Math.min(blockCount, heldCount);
        } else {
            blockCount = isUsableBlock(current)
                    ? Math.min(blockCount < 0 ? current.stackSize : blockCount, current.stackSize) : 0;
        }
        if (blockCount > 0) return;
        int currentSlot = mc.thePlayer.inventory.currentItem;
        if (isTellyFamily() && blockCount == 0) currentSlot--;
        for (int i = currentSlot; i > currentSlot - 9; i--) {
            int slot = (i % 9 + 9) % 9;
            ItemStack candidate = mc.thePlayer.inventory.getStackInSlot(slot);
            if (isUsableBlock(candidate)) {
                mc.thePlayer.inventory.currentItem = slot;
                blockCount = candidate.stackSize;
                return;
            }
        }
    }

    private void updateClutch() {
        if (!clutch.isToggled() || mc.thePlayer.onGround || hasBlockUnder(2)) {
            clutchReset();
            return;
        }
        boolean shouldClutch = mc.thePlayer.fallDistance > 2.0F && !hasCollisionAbove()
                && !mc.thePlayer.isCollidedHorizontally
                && (!onlyInVoid.isToggled() || isFallingIntoVoid());
        if (shouldClutch && !clutchActive) {
            clutchActive = true;
            clutchTickCounter = 0;
            beginClutchBlink();
        }
        if (clutchActive) {
            clutchTickCounter++;
            if (clutchTickCounter % 10 == 0) {
                releaseClutchBlink();
            } else if (!clutchBlinking) {
                beginClutchBlink();
            }
        }
    }

    private boolean clutchFrozen() {
        return clutchActive && clutchTickCounter % 10 != 0;
    }

    private void clutchReset() {
        releaseClutchBlink();
        clutchActive = false;
        clutchTickCounter = 0;
    }

    private void beginClutchBlink() {
        if (clutchBlinking || mc.thePlayer == null) return;
        clutchSavedMotionX = mc.thePlayer.motionX;
        clutchSavedMotionY = mc.thePlayer.motionY;
        clutchSavedMotionZ = mc.thePlayer.motionZ;
        clutchBlinking = true;
    }

    private void releaseClutchBlink() {
        if (clutchBlinking && mc.thePlayer != null) {
            clutchBlinking = false;
            mc.thePlayer.motionX = clutchSavedMotionX;
            mc.thePlayer.motionY = clutchSavedMotionY;
            mc.thePlayer.motionZ = clutchSavedMotionZ;
        } else {
            clutchBlinking = false;
        }
        if (mc.getNetHandler() == null) {
            clutchPackets.clear();
            clutchVelocityPackets.clear();
            return;
        }
        Packet<?> packet;
        while ((packet = clutchPackets.poll()) != null) PacketUtils.sendPacketNoEvent(packet);
        while ((packet = clutchVelocityPackets.poll()) != null) PacketUtils.receivePacketNoEvent(packet);
    }

    private boolean isFallingIntoVoid() {
        int x = MathHelper.floor_double(mc.thePlayer.posX);
        int z = MathHelper.floor_double(mc.thePlayer.posZ);
        if (isTellyFamily()) {
            int playerY = MathHelper.floor_double(mc.thePlayer.posY);
            for (int i = 0; i <= 128; i++) {
                if (BlockUtils.getBlock(new BlockPos(x, playerY - i, z)).getMaterial().isSolid()) return false;
            }
            return true;
        }
        for (int y = MathHelper.floor_double(mc.thePlayer.posY); y >= 0; y--) {
            if (BlockUtils.getBlock(new BlockPos(x, y, z)).getMaterial().isSolid()) return false;
        }
        return true;
    }

    private boolean hasBlockUnder(int depth) {
        int y = MathHelper.floor_double(mc.thePlayer.posY);
        for (int i = 1; i <= depth; i++) {
            if (BlockUtils.getBlock(new BlockPos(mc.thePlayer.posX, y - i, mc.thePlayer.posZ)).getMaterial().isSolid()) return true;
        }
        return false;
    }

    private boolean isOnEdge() {
        if (!mc.thePlayer.onGround) return true;
        int x = MathHelper.floor_double(mc.thePlayer.posX);
        int y = MathHelper.floor_double(mc.thePlayer.posY) - 1;
        int z = MathHelper.floor_double(mc.thePlayer.posZ);
        if (BlockUtils.replaceable(new BlockPos(x, y, z))) return true;
        double threshold = edgeThreshold.getInput();
        double xOffset = mc.thePlayer.posX - x;
        double zOffset = mc.thePlayer.posZ - z;
        int checkX = xOffset < threshold ? x - 1 : xOffset > 1.0D - threshold ? x + 1 : x;
        int checkZ = zOffset < threshold ? z - 1 : zOffset > 1.0D - threshold ? z + 1 : z;
        return (checkX != x || checkZ != z) && BlockUtils.replaceable(new BlockPos(checkX, y, checkZ));
    }

    private void fixMovement(float rotationYaw) {
        float forward = mc.thePlayer.movementInput.moveForward;
        float strafe = mc.thePlayer.movementInput.moveStrafe;
        if (forward == 0.0F && strafe == 0.0F) return;
        double intended = direction(mc.thePlayer.rotationYaw, forward, strafe);
        float bestForward = 0.0F;
        float bestStrafe = 0.0F;
        double bestDifference = Double.MAX_VALUE;
        for (int f = -1; f <= 1; f++) for (int s = -1; s <= 1; s++) {
            if (f == 0 && s == 0) continue;
            double difference = Math.abs(MathHelper.wrapAngleTo180_double(intended - direction(rotationYaw, f, s)));
            if (difference < bestDifference) {
                bestDifference = difference;
                bestForward = f;
                bestStrafe = s;
            }
        }
        mc.thePlayer.movementInput.moveForward = bestForward;
        mc.thePlayer.movementInput.moveStrafe = bestStrafe;
    }

    private float getMovementYaw() {
        float forward = mc.gameSettings.keyBindForward.isKeyDown() ? 1.0F : mc.gameSettings.keyBindBack.isKeyDown() ? -1.0F : 0.0F;
        float strafe = mc.gameSettings.keyBindLeft.isKeyDown() ? 1.0F : mc.gameSettings.keyBindRight.isKeyDown() ? -1.0F : 0.0F;
        return (float) direction(mc.thePlayer.rotationYaw, forward, strafe);
    }

    private double direction(float rotationYaw, float forward, float strafe) {
        if (forward < 0.0F) rotationYaw += 180.0F;
        float factor = forward < 0.0F ? -0.5F : forward > 0.0F ? 0.5F : 1.0F;
        if (strafe > 0.0F) rotationYaw -= 90.0F * factor;
        if (strafe < 0.0F) rotationYaw += 90.0F * factor;
        return rotationYaw;
    }

    private float[] rotationsTo(Vec3 eyes, Vec3 point, float baseYaw, float basePitch) {
        double dx = point.xCoord - eyes.xCoord;
        double dy = point.yCoord - eyes.yCoord;
        double dz = point.zCoord - eyes.zCoord;
        double horizontal = MathHelper.sqrt_double(dx * dx + dz * dz);
        float targetYaw = horizontal < 1.0E-6D ? baseYaw : (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new float[]{quantizeAngle(targetYaw, baseYaw), quantizePitch(targetPitch, basePitch)};
    }

    private MovingObjectPosition rayCast(Vec3 eyes, float rotationYaw, float rotationPitch) {
        Vec3 direction = RotationUtils.getVectorForRotation(rotationPitch, rotationYaw);
        double reach = mc.playerController.getBlockReachDistance();
        return mc.theWorld.rayTraceBlocks(eyes, eyes.addVector(direction.xCoord * reach,
                direction.yCoord * reach, direction.zCoord * reach), false, false, false);
    }

    private float clampRotation(float base, float target, float maximum) {
        float difference = MathHelper.wrapAngleTo180_float(target - base);
        return quantizeAngle(base + MathHelper.clamp_float(difference, -maximum, maximum), base);
    }

    private float quantizeAngle(float target, float base) {
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sensitivity * sensitivity * sensitivity * 1.2F;
        float unwrapped = base + MathHelper.wrapAngleTo180_float(target - base);
        return base + Math.round((unwrapped - base) / gcd) * gcd;
    }

    private float quantizePitch(float target, float base) {
        float sensitivity = mc.gameSettings.mouseSensitivity * 0.6F + 0.2F;
        float gcd = sensitivity * sensitivity * sensitivity * 1.2F;
        return MathHelper.clamp_float(base + Math.round((target - base) / gcd) * gcd, -90.0F, 90.0F);
    }

    private boolean isKeepYMode() {
        return (int) mode.getInput() == MODE_KEEP_Y;
    }

    private boolean isTellyMode() {
        return (int) mode.getInput() == MODE_TELLY;
    }

    private boolean isTellyFamily() {
        return isTellyFamily((int) mode.getInput());
    }

    private boolean isTellyFamily(int modeValue) {
        return modeValue == MODE_TELLY || modeValue == MODE_KEEP_Y;
    }

    private boolean shouldStopSprint() {
        return !isTowering() && stage <= 0 && (int) mode.getInput() != MODE_SNAP;
    }

    private boolean isTowering() {
        if (!isMoving() || hasCollisionAbove()) return false;
        if (mc.thePlayer.onGround && (stage > 0 || mc.gameSettings.keyBindJump.isKeyDown())) return true;
        return tellyJumpDelayTimer > 0;
    }

    private boolean canPlace() {
        if (ModuleManager.bedAura != null && ModuleManager.bedAura.isActivelyMining()) return false;
        return ModuleManager.longJump == null || !ModuleManager.longJump.isEnabled() || !LongJump.stopModules;
    }

    private boolean isMoving() {
        return mc.gameSettings.keyBindForward.isKeyDown() != mc.gameSettings.keyBindBack.isKeyDown()
                || mc.gameSettings.keyBindLeft.isKeyDown() != mc.gameSettings.keyBindRight.isKeyDown();
    }

    private boolean hasCollisionAbove() {
        return !mc.theWorld.getCollidingBoundingBoxes(mc.thePlayer,
                mc.thePlayer.getEntityBoundingBox().offset(0.0D, 1.0D, 0.0D)).isEmpty();
    }

    private boolean isDiagonal(float rotationYaw) {
        float absoluteYaw = Math.abs(rotationYaw % 90.0F);
        return absoluteYaw > 20.0F && absoluteYaw < 70.0F;
    }

    private boolean isUsableBlock(ItemStack stack) {
        if (stack == null || stack.stackSize < 1 || !(stack.getItem() instanceof ItemBlock)) return false;
        ItemBlock itemBlock = (ItemBlock) stack.getItem();
        return isTellyFamily()
                ? isLeaderSolid(itemBlock.getBlock()) && !isLeaderInteractable(itemBlock.getBlock())
                : Utils.canBePlaced(itemBlock);
    }

    private boolean isReplaceableForScaffold(BlockPos pos) {
        if (!isTellyFamily()) return BlockUtils.replaceable(pos);
        Block block = BlockUtils.getBlock(pos);
        if (!block.getMaterial().isReplaceable()) return false;
        return !(block instanceof BlockSnow) || block.getBlockBoundsMaxY() <= 0.125D;
    }

    private boolean isInteractableForScaffold(Block block) {
        return isTellyFamily() ? isLeaderInteractable(block) : BlockUtils.isInteractable(block);
    }

    private boolean isLeaderInteractable(Block block) {
        return block instanceof BlockContainer
                || block instanceof BlockWorkbench
                || block instanceof BlockAnvil
                || block instanceof BlockBed
                || block instanceof BlockDoor && block.getMaterial() != Material.iron
                || block instanceof BlockTrapDoor
                || block instanceof BlockFenceGate
                || block instanceof BlockFence
                || block instanceof BlockButton
                || block instanceof BlockLever
                || block instanceof BlockJukebox;
    }

    private boolean isLeaderSolid(Block block) {
        return !(block instanceof BlockStairs
                || block instanceof BlockSlab
                || block instanceof BlockEndPortalFrame
                || block instanceof BlockEndPortal
                || block instanceof BlockVine
                || block instanceof BlockPumpkin
                || block instanceof BlockCactus
                || block instanceof BlockBush
                || block instanceof BlockFalling
                || block instanceof BlockWeb
                || block instanceof BlockPane
                || block instanceof BlockCarpet
                || block instanceof BlockSnow
                || block instanceof BlockFence
                || block instanceof BlockFenceGate
                || block instanceof BlockWall
                || block instanceof BlockLadder
                || block instanceof BlockTorch
                || block instanceof BlockRedstoneWire
                || block instanceof BlockRedstoneDiode
                || block instanceof BlockBasePressurePlate
                || block instanceof BlockTripWire
                || block instanceof BlockTripWireHook
                || block instanceof BlockRailBase
                || block instanceof BlockSlime
                || block instanceof BlockTNT);
    }

    private int countBlocks() {
        int total = 0;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (isUsableBlock(stack)) total += stack.stackSize;
        }
        return total;
    }

    private float random(float minimum, float maximum) {
        return LEADER_RANDOM.nextFloat() * (maximum - minimum) + minimum;
    }

    private static final class BlockData {
        private final BlockPos pos;
        private final EnumFacing facing;

        private BlockData(BlockPos pos, EnumFacing facing) {
            this.pos = pos;
            this.facing = facing;
        }
    }

    private static final class Aim {
        private float yaw;
        private float pitch;
        private final Vec3 hitVec;

        private Aim(float yaw, float pitch, Vec3 hitVec) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.hitVec = hitVec;
        }
    }
}
