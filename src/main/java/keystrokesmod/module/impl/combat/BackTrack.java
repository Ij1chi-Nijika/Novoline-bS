package keystrokesmod.module.impl.combat;

import keystrokesmod.Raven;
import keystrokesmod.event.AttackEvent;
import keystrokesmod.event.GameTickEvent;
import keystrokesmod.event.ReceivePacketEvent;
import keystrokesmod.lag.api.EnumLagDirection;
import keystrokesmod.lag.api.LagRequest;
import keystrokesmod.lag.timeout.ModuleBackedTimeout;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.CombatTargeting;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.network.play.server.S06PacketUpdateHealth;
import net.minecraft.network.play.server.S08PacketPlayerPosLook;
import net.minecraft.network.play.server.S14PacketEntity;
import net.minecraft.network.play.server.S18PacketEntityTeleport;
import net.minecraft.network.play.server.S29PacketSoundEffect;
import net.minecraft.network.play.server.S40PacketDisconnect;
import net.minecraft.util.AxisAlignedBB;
import net.minecraft.util.MathHelper;
import net.minecraft.util.Vec3;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Holds inbound movement packets so the client keeps a target at an older,
 * hittable position. This is a Java/1.8.9 port of LiquidBounce's Backtrack.
 */
public class BackTrack extends Module {
    private static final String[] TARGET_MODES = {"Attack", "Range"};
    private static final String[] ESP_MODES = {"Box", "None"};

    private final SliderSetting minimumRange;
    private final SliderSetting maximumRange;
    private final SliderSetting minimumDelay;
    private final SliderSetting maximumDelay;
    private final SliderSetting minimumNextDelay;
    private final SliderSetting maximumNextDelay;
    private final SliderSetting trackingBuffer;
    private final SliderSetting chance;
    private final ButtonSetting pauseOnHurtTime;
    private final SliderSetting hurtTime;
    private final SliderSetting targetMode;
    private final SliderSetting lastAttackTimeToWork;
    private final SliderSetting espMode;
    private final ColorSetting espColor;

    private EntityPlayer target;
    private Vec3 trackedPosition;
    private LagRequest inboundLag;
    private long lastAttackAt;
    private long lastInRangeAt;
    private long nextBacktrackAt;
    private int currentDelay;
    private boolean chancePassed;

    public BackTrack() {
        super("BackTrack", category.combat);
        registerSetting(new DescriptionSetting("Activation range"));
        registerSetting(minimumRange = new SliderSetting("Minimum range", 1.0, 0.0, 10.0, 0.1));
        registerSetting(maximumRange = new SliderSetting("Maximum range", 3.0, 0.0, 10.0, 0.1));
        registerSetting(new DescriptionSetting("Packet delay"));
        registerSetting(minimumDelay = new SliderSetting("Minimum delay", "ms", 100, 0, 1000, 10));
        registerSetting(maximumDelay = new SliderSetting("Maximum delay", "ms", 150, 0, 1000, 10));
        registerSetting(minimumNextDelay = new SliderSetting("Minimum next delay", "ms", 0, 0, 2000, 10));
        registerSetting(maximumNextDelay = new SliderSetting("Maximum next delay", "ms", 10, 0, 2000, 10));
        registerSetting(trackingBuffer = new SliderSetting("Tracking buffer", "ms", 500, 0, 2000, 10));
        registerSetting(chance = new SliderSetting("Chance", "%", 50, 0, 100, 1));
        registerSetting(new DescriptionSetting("Conditions"));
        registerSetting(pauseOnHurtTime = new ButtonSetting("Pause on hurt time", false));
        registerSetting(hurtTime = new SliderSetting("Hurt time", 3, 0, 10, 1));
        registerSetting(targetMode = new SliderSetting("Target mode", 0, TARGET_MODES));
        registerSetting(lastAttackTimeToWork = new SliderSetting("Last attack time", "ms", 1000, 0, 5000, 50));
        registerSetting(new DescriptionSetting("ESP"));
        registerSetting(espMode = new SliderSetting("ESP mode", 0, ESP_MODES));
        registerSetting(espColor = new ColorSetting("ESP color", 255, 80, 80, 120));
        closetModule = true;
    }

    @Override
    public void guiUpdate() {
        hurtTime.setVisible(pauseOnHurtTime.isToggled(), this);
        espColor.setVisible((int) espMode.getInput() == 0, this);
    }

    @Override
    public void onEnable() {
        if (hasInboundBlinkConflict()) {
            Utils.sendMessage("&cBackTrack conflicts with Blink inbound / both.");
            disable();
            return;
        }
        resetState(false);
    }

    @Override
    public void onDisable() {
        flushLag();
        resetState(false);
    }

    @Override
    public String getInfo() {
        return currentDelay + "ms";
    }

    @SubscribeEvent
    public void onAttack(AttackEvent event) {
        if (event.attacker != mc.thePlayer) return;

        lastAttackAt = System.currentTimeMillis();
        chancePassed = chance.getInput() >= 100.0 || Math.random() * 100.0 < chance.getInput();
        if ((int) targetMode.getInput() != 0) return;

        EntityPlayer enemy = CombatTargeting.asValidPlayer(event.target, maximumRangeSq());
        if (enemy != null) processTarget(enemy);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGameTick(GameTickEvent event) {
        if (!Utils.nullCheck() || mc.thePlayer.isDead || mc.theWorld == null) {
            flushLag();
            resetState(false);
            return;
        }

        EntityPlayer enemy = target;
        if ((int) targetMode.getInput() == 1) {
            enemy = CombatTargeting.findTarget(maximumRangeSq());
            if (enemy != target) {
                chancePassed = chance.getInput() >= 100.0 || Math.random() * 100.0 < chance.getInput();
            }
        }

        if (enemy == null || !CombatTargeting.isTrackablePlayer(enemy)) {
            clearTarget(true);
            return;
        }

        processTarget(enemy);
        if (!shouldBacktrack(enemy)) {
            clearTarget(true);
            return;
        }

        if (!isLagging()) startLag();
        Raven.lagHandler.releaseExpiredPackets(EnumLagDirection.INBOUND, currentDelay);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onReceivePacket(ReceivePacketEvent event) {
        if (event.isCanceled()) return;
        Packet<?> packet = event.getPacket();

        if (packet instanceof S08PacketPlayerPosLook || packet instanceof S40PacketDisconnect) {
            clearTarget(true);
            return;
        }
        if (packet instanceof S06PacketUpdateHealth && ((S06PacketUpdateHealth) packet).getHealth() <= 0.0F) {
            clearTarget(true);
            return;
        }

        if (!isLagging()) return;

        // Chat and the local hurt sound should never be made sluggish by BackTrack.
        if (packet instanceof S02PacketChat || isOwnHurtSound(packet)) {
            Raven.lagHandler.forPacket(packet);
            return;
        }

        if (target == null || trackedPosition == null) return;
        Vec3 nextPosition = positionFromPacket(packet, target, trackedPosition);
        if (nextPosition == null) return;

        trackedPosition = nextPosition;
        if (boxedDistanceSq(target, nextPosition) < boxedDistanceSq(target, null)) {
            // The live position is easier to hit; release everything immediately.
            stopLagWithCooldown();
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!Utils.nullCheck() || !isLagging() || target == null || trackedPosition == null
                || (int) espMode.getInput() != 0) return;

        AxisAlignedBB entityBox = target.getEntityBoundingBox();
        AxisAlignedBB box = entityBox.offset(
                trackedPosition.xCoord - target.posX - mc.getRenderManager().viewerPosX,
                trackedPosition.yCoord - target.posY - mc.getRenderManager().viewerPosY,
                trackedPosition.zCoord - target.posZ - mc.getRenderManager().viewerPosZ
        );
        float r = espColor.getRed() / 255.0F;
        float g = espColor.getGreen() / 255.0F;
        float b = espColor.getBlue() / 255.0F;
        float a = espColor.getAlpha() / 255.0F;

        GL11.glPushMatrix();
        GlStateManager.enableBlend();
        GlStateManager.disableTexture2D();
        GlStateManager.disableDepth();
        GlStateManager.depthMask(false);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        RenderUtils.drawBoundingBox(box, r, g, b, a * 0.35F);
        GL11.glLineWidth(2.0F);
        GL11.glColor4f(r, g, b, a);
        RenderGlobal.drawSelectionBoundingBox(box);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableTexture2D();
        GlStateManager.disableBlend();
        GL11.glPopMatrix();
    }

    public boolean isLagging() {
        return inboundLag != null && !inboundLag.getTimeout().isTimedOut();
    }

    private void processTarget(EntityPlayer enemy) {
        if (target != enemy) {
            flushLag();
            target = enemy;
            trackedPosition = new Vec3(enemy.posX, enemy.posY, enemy.posZ);
        }
    }

    private boolean shouldBacktrack(EntityPlayer enemy) {
        long now = System.currentTimeMillis();
        double distance = Math.sqrt(boxedDistanceSq(enemy, null));
        double min = Math.min(minimumRange.getInput(), maximumRange.getInput());
        double max = Math.max(minimumRange.getInput(), maximumRange.getInput());
        boolean inRange = distance >= min && distance <= max;
        if (inRange) lastInRangeAt = now;

        return (inRange || now - lastInRangeAt <= (long) trackingBuffer.getInput())
                && CombatTargeting.isTrackablePlayer(enemy)
                && mc.thePlayer.ticksExisted > 10
                && chancePassed
                && now >= nextBacktrackAt
                && (!pauseOnHurtTime.isToggled() || enemy.hurtTime < (int) hurtTime.getInput())
                && now - lastAttackAt <= (long) lastAttackTimeToWork.getInput()
                && !hasInboundBlinkConflict();
    }

    private void startLag() {
        currentDelay = randomBetween(minimumDelay, maximumDelay);
        inboundLag = new LagRequest(EnumLagDirection.ONLY_INBOUND, new ModuleBackedTimeout(this));
        Raven.lagHandler.requestLag(inboundLag);
    }

    private void flushLag() {
        if (inboundLag != null) {
            inboundLag.getTimeout().forceTimeOut();
            inboundLag = null;
        }
    }

    private void stopLagWithCooldown() {
        flushLag();
        nextBacktrackAt = System.currentTimeMillis() + randomBetween(minimumNextDelay, maximumNextDelay);
    }

    private void clearTarget(boolean useCooldown) {
        boolean hadTarget = target != null;
        flushLag();
        if (hadTarget && useCooldown) {
            nextBacktrackAt = System.currentTimeMillis() + randomBetween(minimumNextDelay, maximumNextDelay);
        }
        target = null;
        trackedPosition = null;
    }

    private void resetState(boolean useCooldown) {
        clearTarget(useCooldown);
        lastAttackAt = 0L;
        lastInRangeAt = 0L;
        chancePassed = chance.getInput() >= 100.0 || Math.random() * 100.0 < chance.getInput();
        currentDelay = randomBetween(minimumDelay, maximumDelay);
    }

    private boolean hasInboundBlinkConflict() {
        return ModuleManager.blink != null && ModuleManager.blink.isEnabled()
                && ModuleManager.blink.delaysInboundPackets();
    }

    private double maximumRangeSq() {
        double max = Math.max(minimumRange.getInput(), maximumRange.getInput());
        return max * max;
    }

    private static int randomBetween(SliderSetting first, SliderSetting second) {
        int min = (int) Math.min(first.getInput(), second.getInput());
        int max = (int) Math.max(first.getInput(), second.getInput());
        return min == max ? min : ThreadLocalRandom.current().nextInt(min, max + 1);
    }

    private static Vec3 positionFromPacket(Packet<?> packet, EntityPlayer entity, Vec3 base) {
        if (packet instanceof S18PacketEntityTeleport) {
            S18PacketEntityTeleport teleport = (S18PacketEntityTeleport) packet;
            if (teleport.getEntityId() == entity.getEntityId()) {
                return new Vec3(teleport.getX() / 32.0D, teleport.getY() / 32.0D, teleport.getZ() / 32.0D);
            }
        } else if (packet instanceof S14PacketEntity) {
            S14PacketEntity movement = (S14PacketEntity) packet;
            if (movement.getEntity(mc.theWorld) == entity) {
                return base.addVector(
                        movement.func_149062_c() / 32.0D,
                        movement.func_149061_d() / 32.0D,
                        movement.func_149064_e() / 32.0D
                );
            }
        }
        return null;
    }

    /** Distance from the player's eyes to the closest point of an entity box. */
    private static double boxedDistanceSq(EntityPlayer entity, Vec3 at) {
        AxisAlignedBB box = entity.getEntityBoundingBox();
        if (at != null) {
            box = box.offset(at.xCoord - entity.posX, at.yCoord - entity.posY, at.zCoord - entity.posZ);
        }
        Vec3 eyes = mc.thePlayer.getPositionEyes(1.0F);
        double x = MathHelper.clamp_double(eyes.xCoord, box.minX, box.maxX);
        double y = MathHelper.clamp_double(eyes.yCoord, box.minY, box.maxY);
        double z = MathHelper.clamp_double(eyes.zCoord, box.minZ, box.maxZ);
        return eyes.squareDistanceTo(new Vec3(x, y, z));
    }

    private static boolean isOwnHurtSound(Packet<?> packet) {
        if (!(packet instanceof S29PacketSoundEffect) || mc.thePlayer == null) return false;
        S29PacketSoundEffect sound = (S29PacketSoundEffect) packet;
        if (!"game.player.hurt".equals(sound.getSoundName())) return false;
        double dx = sound.getX() - mc.thePlayer.posX;
        double dy = sound.getY() - mc.thePlayer.posY;
        double dz = sound.getZ() - mc.thePlayer.posZ;
        return dx * dx + dy * dy + dz * dz <= 4.0D;
    }
}
