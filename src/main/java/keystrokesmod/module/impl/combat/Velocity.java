package keystrokesmod.module.impl.combat;

import keystrokesmod.Raven;
import keystrokesmod.event.AttackEvent;
import keystrokesmod.event.ClientRotationEvent;
import keystrokesmod.event.GameTickEvent;
import keystrokesmod.event.PostPlayerInputEvent;
import keystrokesmod.event.PreEntityVelocityEvent;
import keystrokesmod.event.PreExplosionPacketEvent;
import keystrokesmod.event.PreUpdateEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.lag.api.EnumLagDirection;
import keystrokesmod.lag.api.LagRequest;
import keystrokesmod.lag.timeout.ModuleBackedTimeout;
import keystrokesmod.mixin.impl.accessor.IAccessorEntity;
import keystrokesmod.mixin.impl.accessor.IAccessorS27PacketExplosion;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.movement.LongJump;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.RotationUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import net.minecraft.network.play.client.C0APacketAnimation;
import net.minecraft.network.play.server.S12PacketEntityVelocity;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.potion.Potion;
import net.minecraft.util.MathHelper;
import net.minecraft.util.MovingObjectPosition;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class Velocity extends Module {
    private static final String[] MODES = new String[]{"Vanilla", "Prediction"};
    private static final String[] REDUCE_MODES = new String[]{"Attack", "Release when can attack", "Release before can attack"};

    private final SliderSetting mode;
    private final ButtonSetting reduce;
    public final SliderSetting reduceMode;
    private final ButtonSetting extraAttack;
    private final ButtonSetting reduceWhenCanAttack;
    private final ButtonSetting onlySprinting;
    private final ButtonSetting smartTimes;
    private final SliderSetting attackTimes;
    private final ButtonSetting predictionKeepSprint;
    private final ButtonSetting testMode;
    private final SliderSetting stopBlockHurtTime;
    private final ButtonSetting jump;
    private final ButtonSetting delay;
    private final SliderSetting delayTicks;
    private final ButtonSetting airBuffer;
    private final ButtonSetting groundDelay;
    private final ButtonSetting rotate;
    private final SliderSetting rotateTicks;
    private final ButtonSetting autoMove;
    private final SliderSetting chance;
    public static SliderSetting horizontal;
    public static SliderSetting vertical;
    private final SliderSetting explosionHorizontal;
    private final SliderSetting explosionVertical;
    private final ButtonSetting fakeCheck;
    private final ButtonSetting debug;

    public boolean knockback;
    public boolean disable;
    public static boolean hasReceivedVelocity;
    public static boolean extraAttacked;
    public static boolean velocityAttacked;
    public static boolean stoppedBlock;

    private int chanceCounter;
    private int rotationTick;
    private int ticksSinceVelocity = -1;
    private int reduceTick;
    private int hitCount;
    private int delayedTicks;
    private boolean pendingExplosion;
    private boolean allowNext = true;
    private boolean delayFlag;
    private boolean releasingDelay;
    private boolean jumpFlag;
    private double knockbackX;
    private double knockbackZ;
    private float targetYaw;
    private LagRequest inboundDelay;

    public Velocity() {
        super("Velocity", category.combat, 0);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(reduce = new ButtonSetting("Reduce", true));
        this.registerSetting(reduceMode = new SliderSetting("Reduce mode", 0, REDUCE_MODES));
        this.registerSetting(extraAttack = new ButtonSetting("Extra attack", false));
        this.registerSetting(reduceWhenCanAttack = new ButtonSetting("Reduce when can attack", true));
        this.registerSetting(onlySprinting = new ButtonSetting("Only sprinting", true));
        this.registerSetting(smartTimes = new ButtonSetting("Smart times", true));
        this.registerSetting(attackTimes = new SliderSetting("Attack times", 1.0, 1.0, 5.0, 1.0));
        this.registerSetting(predictionKeepSprint = new ButtonSetting("Keep Sprint", false));
        this.registerSetting(testMode = new ButtonSetting("Test mode", false));
        this.registerSetting(stopBlockHurtTime = new SliderSetting("Stop block hurt time", 2.0, 0.0, 10.0, 1.0));
        this.registerSetting(jump = new ButtonSetting("Jump", true));
        this.registerSetting(delay = new ButtonSetting("Delay", false));
        this.registerSetting(delayTicks = new SliderSetting("Delay ticks", 1.0, 1.0, 5.0, 1.0));
        this.registerSetting(airBuffer = new ButtonSetting("Delay till on ground", true));
        this.registerSetting(groundDelay = new ButtonSetting("Ground delay", false));
        this.registerSetting(rotate = new ButtonSetting("Rotate", false));
        this.registerSetting(rotateTicks = new SliderSetting("Rotate ticks", 3.0, 1.0, 12.0, 1.0));
        this.registerSetting(autoMove = new ButtonSetting("Auto move", false));
        this.registerSetting(chance = new SliderSetting("Chance", "%", 100.0, 0.0, 100.0, 1.0));
        this.registerSetting(horizontal = new SliderSetting("Horizontal", "%", 100.0, 0.0, 100.0, 1.0));
        this.registerSetting(vertical = new SliderSetting("Vertical", "%", 100.0, 0.0, 100.0, 1.0));
        this.registerSetting(explosionHorizontal = new SliderSetting("Explosions horizontal", "%", 100.0, 0.0, 100.0, 1.0));
        this.registerSetting(explosionVertical = new SliderSetting("Explosions vertical", "%", 100.0, 0.0, 100.0, 1.0));
        this.registerSetting(fakeCheck = new ButtonSetting("Fake check", true));
        this.registerSetting(debug = new ButtonSetting("Debug", false));
        this.closetModule = true;
    }

    @Override
    public String getInfo() {
        if (isPrediction()) return "Prediction";
        return (int) horizontal.getInput() + "% " + (int) vertical.getInput() + "%";
    }

    @Override
    public void guiUpdate() {
        boolean prediction = isPrediction();
        boolean reducing = prediction && reduce.isToggled();
        int reduceModeValue = (int) reduceMode.getInput();
        horizontal.setVisible(!prediction, this);
        vertical.setVisible(!prediction, this);
        explosionHorizontal.setVisible(!prediction, this);
        explosionVertical.setVisible(!prediction, this);
        chance.setVisible(!prediction, this);
        reduce.setVisible(prediction, this);
        reduceMode.setVisible(reducing, this);
        extraAttack.setVisible(reducing && reduceModeValue != 0, this);
        reduceWhenCanAttack.setVisible(reducing && reduceModeValue == 0, this);
        onlySprinting.setVisible(reducing && reduceModeValue == 0, this);
        smartTimes.setVisible(reducing && reduceModeValue == 0, this);
        attackTimes.setVisible(reducing && reduceModeValue == 0 && !smartTimes.isToggled(), this);
        predictionKeepSprint.setVisible(reducing && reduceModeValue == 0, this);
        testMode.setVisible(reducing && reduceModeValue == 0, this);
        stopBlockHurtTime.setVisible(reducing && reduceModeValue == 0 && testMode.isToggled(), this);
        jump.setVisible(prediction, this);
        delay.setVisible(prediction, this);
        delayTicks.setVisible(prediction && delay.isToggled() && !airBuffer.isToggled(), this);
        airBuffer.setVisible(prediction && delay.isToggled(), this);
        groundDelay.setVisible(prediction && delay.isToggled() && !airBuffer.isToggled(), this);
        rotate.setVisible(prediction, this);
        rotateTicks.setVisible(prediction && rotate.isToggled(), this);
        autoMove.setVisible(prediction && rotate.isToggled(), this);
    }

    @Override
    public void onEnable() {
        resetState(true);
    }

    @Override
    public void onDisable() {
        resetState(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onReceivePacket(ReceivePacketEvent event) {
        if (!Utils.nullCheck() || event.isCanceled()) return;
        if (event.getPacket() instanceof S19PacketEntityStatus) {
            S19PacketEntityStatus packet = (S19PacketEntityStatus) event.getPacket();
            Entity entity = packet.getEntity(mc.theWorld);
            if (entity == mc.thePlayer && packet.getOpCode() == 2) allowNext = false;
            return;
        }
        if (event.getPacket() instanceof S27PacketExplosion) {
            S27PacketExplosion packet = (S27PacketExplosion) event.getPacket();
            if (!isPrediction() && hasExplosionMotion(packet)) pendingExplosion = true;
            return;
        }
        if (!(event.getPacket() instanceof S12PacketEntityVelocity)) return;
        S12PacketEntityVelocity packet = (S12PacketEntityVelocity) event.getPacket();
        if (packet.getEntityID() != mc.thePlayer.getEntityId()) return;
        knockback = true;
        if (!isPrediction() || releasingDelay || delayFlag || !predictionUsable()) return;
        if (!delay.isToggled()) return;

        boolean shouldBuffer = airBuffer.isToggled()
                ? !mc.thePlayer.onGround
                : !mc.thePlayer.onGround || groundDelay.isToggled();
        if (!shouldBuffer || isInLiquidOrWeb() || fakeCheck.isToggled() && allowNext) return;

        inboundDelay = new LagRequest(EnumLagDirection.ONLY_INBOUND, new ModuleBackedTimeout(this));
        Raven.lagHandler.requestLag(inboundDelay);
        delayFlag = true;
        delayedTicks = 0;
        debug("Velocity buffer active");
    }

    @SubscribeEvent
    public void onEntityVelocity(PreEntityVelocityEvent event) {
        if (!Utils.nullCheck() || event.isCanceled()) return;
        S12PacketEntityVelocity packet = event.packet;
        if (packet.getEntityID() != mc.thePlayer.getEntityId()) return;
        releasingDelay = false;

        if (fakeCheck.isToggled() && allowNext) return;
        allowNext = true;

        if (!isPrediction()) {
            applyVanillaVelocity(event, packet);
            return;
        }
        if (!predictionUsable()) return;

        knockbackX = packet.getMotionX() / 8000.0D;
        knockbackZ = packet.getMotionZ() / 8000.0D;
        if (rotate.isToggled() && packet.getMotionY() > 0 && (Math.abs(knockbackX) > 0.01 || Math.abs(knockbackZ) > 0.01)) {
            targetYaw = (float) (Math.atan2(-knockbackZ, -knockbackX) * 180.0D / Math.PI) - 90.0F;
            rotationTick = 1;
        }
        hitCount = computeReduceTicks(packet.getMotionX(), packet.getMotionZ());
        ticksSinceVelocity = 0;
        if (!testMode.isToggled()) hasReceivedVelocity = true;
    }

    @SubscribeEvent
    public void onExplosion(PreExplosionPacketEvent event) {
        if (isPrediction() || !pendingExplosion || event.isCanceled()) return;
        pendingExplosion = false;
        S27PacketExplosion packet = event.packet;
        if (!hasExplosionMotion(packet)) return;
        if (explosionHorizontal.getInput() == 0.0 || explosionVertical.getInput() == 0.0) {
            event.setCanceled(true);
            return;
        }
        IAccessorS27PacketExplosion accessor = (IAccessorS27PacketExplosion) packet;
        float horizontalMultiplier = (float) (explosionHorizontal.getInput() / 100.0D);
        float verticalMultiplier = (float) (explosionVertical.getInput() / 100.0D);
        accessor.setMotionX(packet.func_149149_c() * horizontalMultiplier);
        accessor.setMotionY(packet.func_149144_d() * verticalMultiplier);
        accessor.setMotionZ(packet.func_149147_e() * horizontalMultiplier);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public void onClientRotation(ClientRotationEvent event) {
        if (!isPrediction() || !rotate.isToggled() || rotationTick <= 0
                || rotationTick > (int) rotateTicks.getInput()) return;
        if (ModuleManager.scaffold != null && ModuleManager.scaffold.isEnabled()) return;
        float baseYaw = event.yaw == null ? RotationUtils.serverRotations[0] : event.yaw;
        event.yaw = baseYaw + MathHelper.wrapAngleTo180_float(targetYaw - baseYaw);
    }

    @SubscribeEvent
    public void onPreUpdate(PreUpdateEvent event) {
        if (!isPrediction() || !predictionUsable()) return;
        updateRotationState();
        updateDelayedVelocity();
        updateReduce();
        if (jumpFlag) {
            if (mc.thePlayer.onGround && mc.gameSettings.keyBindForward.isKeyDown()
                    && !mc.thePlayer.isPotionActive(Potion.jump) && !isInLiquidOrWeb()
                    && mc.thePlayer.isSprinting()) {
                mc.thePlayer.movementInput.jump = true;
            }
            jumpFlag = false;
        }
    }

    @SubscribeEvent
    public void onPostPlayerInput(PostPlayerInputEvent event) {
        if (isPrediction() && autoMove.isToggled() && rotationTick > 0
                && rotationTick <= (int) rotateTicks.getInput()) {
            mc.thePlayer.movementInput.moveForward = 1.0F;
        }
    }

    @SubscribeEvent
    public void onGameTick(GameTickEvent event) {
        if (ticksSinceVelocity >= 0 && ++ticksSinceVelocity >= 10) ticksSinceVelocity = -1;
        if (delayFlag) delayedTicks++;
        if (testMode.isToggled() && isPrediction() && reduce.isToggled()
                && (int) reduceMode.getInput() == 0 && ticksSinceVelocity >= (int) stopBlockHurtTime.getInput()) {
            hasReceivedVelocity = true;
            stoppedBlock = true;
        }
    }

    @SubscribeEvent
    public void onWorldJoin(EntityJoinWorldEvent event) {
        if (event.entity == mc.thePlayer) resetState(true);
    }

    private void applyVanillaVelocity(PreEntityVelocityEvent event, S12PacketEntityVelocity packet) {
        if (!passesChance()) return;
        if (horizontal.getInput() == 100.0 && vertical.getInput() == 100.0) return;
        event.setCanceled(true);
        if (horizontal.getInput() > 0.0) {
            mc.thePlayer.motionX = packet.getMotionX() / 8000.0D * horizontal.getInput() / 100.0D;
            mc.thePlayer.motionZ = packet.getMotionZ() / 8000.0D * horizontal.getInput() / 100.0D;
        }
        if (vertical.getInput() > 0.0) {
            mc.thePlayer.motionY = packet.getMotionY() / 8000.0D * vertical.getInput() / 100.0D;
        }
    }

    private boolean passesChance() {
        chanceCounter = chanceCounter % 100 + (int) chance.getInput();
        return chanceCounter >= 100;
    }

    private void updateRotationState() {
        if (rotationTick <= 0) return;
        rotationTick++;
        if (rotationTick > (int) rotateTicks.getInput()) {
            rotationTick = 0;
            knockbackX = 0.0;
            knockbackZ = 0.0;
        }
    }

    private void updateDelayedVelocity() {
        if (!delayFlag) return;
        if (!delay.isToggled()) {
            delayFlag = false;
            releasingDelay = true;
            flushInboundDelay();
            return;
        }
        boolean normalRelease = isInLiquidOrWeb()
                || airBuffer.isToggled() && mc.thePlayer.onGround
                || !airBuffer.isToggled() && delayedTicks >= (int) delayTicks.getInput();
        boolean combatRelease = shouldCombatRelease();
        if (!normalRelease && !combatRelease) return;

        debug("Velocity buffer released after " + delayedTicks + " ticks");
        delayFlag = false;
        releasingDelay = true;
        ticksSinceVelocity = 0;
        if (!testMode.isToggled()) hasReceivedVelocity = true;
        if (jump.isToggled()) jumpFlag = true;
        if (reduce.isToggled() && extraAttack.isToggled()
                && KillAura.target != null && (int) reduceMode.getInput() != 0) {
            extraAttacked = true;
            velocityAttacked = true;
        }
        flushInboundDelay();
    }

    private boolean shouldCombatRelease() {
        if (!reduce.isToggled() || (int) reduceMode.getInput() == 0
                || ModuleManager.killAura == null || !ModuleManager.killAura.isEnabled() || KillAura.target == null) {
            return false;
        }
        boolean blocking = ModuleManager.killAura.isAutoBlockActive();
        return (int) reduceMode.getInput() == 1 ? !blocking : blocking;
    }

    private void updateReduce() {
        if (velocityAttacked) {
            if (KillAura.target != null && ModuleManager.killAura != null && ModuleManager.killAura.isEnabled()
                    && mc.thePlayer.isSprinting()) {
                performReduceAttack(KillAura.target);
                if (!ModuleManager.killAura.isAutoBlockActive()) extraAttacked = false;
            } else {
                extraAttacked = false;
            }
            velocityAttacked = false;
        }
        if (!reduce.isToggled() || (int) reduceMode.getInput() != 0) return;
        if (!hasReceivedVelocity) return;

        int maximumAttacks = smartTimes.isToggled() ? hitCount : (int) attackTimes.getInput();
        if (reduceTick >= maximumAttacks) {
            reduceTick = 0;
            hasReceivedVelocity = false;
            stoppedBlock = false;
            return;
        }

        MovingObjectPosition ray = RotationUtils.rayTrace(3.0, 1.0F, RotationUtils.serverRotations, null);
        if (ray != null && ray.entityHit instanceof EntityPlayer && ray.entityHit != mc.thePlayer
                && (!onlySprinting.isToggled() || mc.thePlayer.isSprinting())) {
            Entity target = KillAura.target != null ? KillAura.target : ray.entityHit;
            if (!reduceWhenCanAttack.isToggled() || ModuleManager.killAura == null
                    || !ModuleManager.killAura.isAutoBlockActive()) {
                performReduceAttack(target);
            }
        }
        reduceTick++;
    }

    private boolean performReduceAttack(Entity target) {
        if (target == null || target == mc.thePlayer) return false;

        MinecraftForge.EVENT_BUS.post(new AttackEvent(target, mc.thePlayer, false));
        mc.thePlayer.sendQueue.addToSendQueue(new C0APacketAnimation());
        if (MinecraftForge.EVENT_BUS.post(new AttackEvent(target, mc.thePlayer, false))) return false;
        mc.thePlayer.sendQueue.addToSendQueue(
                new C02PacketUseEntity(target, C02PacketUseEntity.Action.ATTACK));

        mc.thePlayer.motionX *= 0.6D;
        mc.thePlayer.motionZ *= 0.6D;
        if (!predictionKeepSprint.isToggled()) {
            mc.thePlayer.setSprinting(false);
        }
        return true;
    }

    private int computeReduceTicks(int motionX, int motionZ) {
        int ticks = (int) Math.round(0.000643153527D * Math.hypot(motionX, motionZ) + 2.9419087136D);
        return Math.max(1, Math.min(10, ticks));
    }

    private boolean predictionUsable() {
        return Utils.nullCheck() && !LongJump.stopVelocity && !disable;
    }

    private boolean isPrediction() {
        return (int) mode.getInput() == 1;
    }

    private boolean isInLiquidOrWeb() {
        return mc.thePlayer.isInWater() || mc.thePlayer.isInLava()
                || ((IAccessorEntity) mc.thePlayer).getIsInWeb();
    }

    private boolean hasExplosionMotion(S27PacketExplosion packet) {
        return packet.func_149149_c() != 0.0F || packet.func_149144_d() != 0.0F || packet.func_149147_e() != 0.0F;
    }

    private void flushInboundDelay() {
        if (inboundDelay != null) {
            inboundDelay.getTimeout().forceTimeOut();
            inboundDelay = null;
        }
    }

    private void resetState(boolean flush) {
        if (flush) flushInboundDelay();
        knockback = false;
        hasReceivedVelocity = false;
        extraAttacked = false;
        velocityAttacked = false;
        stoppedBlock = false;
        pendingExplosion = false;
        allowNext = true;
        delayFlag = false;
        releasingDelay = false;
        jumpFlag = false;
        rotationTick = 0;
        ticksSinceVelocity = -1;
        reduceTick = 0;
        hitCount = 0;
        delayedTicks = 0;
        knockbackX = 0.0;
        knockbackZ = 0.0;
    }

    private void debug(String message) {
        if (debug.isToggled()) Utils.sendMessage("&7[Velocity] &f" + message);
    }
}
