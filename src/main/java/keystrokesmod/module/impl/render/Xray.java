package keystrokesmod.module.impl.render;

import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.BlockUtils;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.block.Block;
import net.minecraft.init.Blocks;
import net.minecraft.util.BlockPos;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Novoline-style XRay. Blocks are discovered while Minecraft rebuilds chunks;
 * the companion renderer mixins make ordinary blocks translucent and retain
 * the selected blocks as solid geometry.
 */
public final class Xray extends Module {
    public static final List<Integer> blockIdList = Collections.unmodifiableList(Arrays.asList(
            10, 11, 8, 9, 14, 15, 16, 21, 41, 42, 46, 48, 52, 56, 57, 61,
            62, 73, 74, 84, 89, 103, 116, 117, 118, 120, 129, 133, 137, 145,
            152, 153, 154
    ));
    public static final List<BlockPos> blockPosList = new CopyOnWriteArrayList<>();

    public static volatile int alpha = 160;
    public static volatile boolean isEnabled;

    private final SliderSetting opacity;
    private final ButtonSetting esp;
    private final ButtonSetting tracers;
    private final ButtonSetting redstone;
    private final ButtonSetting diamond;
    private final ButtonSetting emerald;
    private final ButtonSetting lapis;
    private final ButtonSetting iron;
    private final ButtonSetting coal;
    private final ButtonSetting gold;
    private final SliderSetting distance;
    private final ButtonSetting chunkUpdate;
    private final SliderSetting delay;

    private long lastChunkUpdate;

    public Xray() {
        super("XRay", category.visuals);
        this.registerSetting(diamond = new ButtonSetting("Diamond", true));
        this.registerSetting(redstone = new ButtonSetting("Redstone", false));
        this.registerSetting(emerald = new ButtonSetting("Emerald", false));
        this.registerSetting(lapis = new ButtonSetting("Lapis", false));
        this.registerSetting(iron = new ButtonSetting("Iron", false));
        this.registerSetting(coal = new ButtonSetting("Coal", false));
        this.registerSetting(gold = new ButtonSetting("Gold", false));
        this.registerSetting(opacity = new SliderSetting("Opacity", 160, 0, 255, 5));
        this.registerSetting(distance = new SliderSetting("Distance", 42, 16, 64, 4));
        this.registerSetting(tracers = new ButtonSetting("Tracers", true));
        this.registerSetting(esp = new ButtonSetting("ESP", true));
        this.registerSetting(chunkUpdate = new ButtonSetting("Chunks Update", false));
        this.registerSetting(delay = new SliderSetting("Update Delay", " seconds", 10.0, 1.0, 30.0, 0.5));
    }

    @Override
    public void onEnable() {
        toggleRenderer(true);
    }

    @Override
    public void onDisable() {
        toggleRenderer(false);
        lastChunkUpdate = 0L;
    }

    private void toggleRenderer(boolean enabled) {
        blockPosList.clear();
        isEnabled = enabled;
        alpha = (int) opacity.getInput();
        reloadRenderers();
    }

    @Override
    public void onUpdate() {
        int configuredAlpha = (int) opacity.getInput();
        if (alpha != configuredAlpha) {
            alpha = configuredAlpha;
            reloadRenderers();
            return;
        }

        if (chunkUpdate.isToggled()) {
            long now = System.currentTimeMillis();
            if (now - lastChunkUpdate >= (long) (delay.getInput() * 1000.0D)) {
                reloadRenderers();
                lastChunkUpdate = now;
            }
        }
    }

    @Override
    public void guiButtonToggled(ButtonSetting setting) {
        if (setting == esp || setting == tracers || isOreSetting(setting)) {
            blockPosList.clear();
            reloadRenderers();
        }
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.entity == mc.thePlayer) {
            blockPosList.clear();
            reloadRenderers();
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderWorldLastEvent event) {
        if (!Utils.nullCheck() || !esp.isToggled()) {
            return;
        }

        double maximumDistanceSq = distance.getInput() * distance.getInput();
        for (BlockPos pos : blockPosList) {
            double dx = mc.thePlayer.posX - pos.getX();
            double dz = mc.thePlayer.posZ - pos.getZ();
            if (dx * dx + dz * dz > maximumDistanceSq) {
                continue;
            }

            Block block = BlockUtils.getBlock(pos);
            int color = getOreColor(block);
            if (color == 0) {
                continue;
            }

            RenderUtils.renderBlock(pos, color, true, true);
            if (tracers.isToggled()) {
                drawTracer(pos, color);
            }
        }
    }

    private int getOreColor(Block block) {
        if (block == Blocks.diamond_ore && diamond.isToggled()) return rgb(0, 255, 255);
        if (block == Blocks.iron_ore && iron.isToggled()) return rgb(225, 225, 225);
        if (block == Blocks.lapis_ore && lapis.isToggled()) return rgb(0, 0, 255);
        if (block == Blocks.redstone_ore && redstone.isToggled()) return rgb(255, 0, 0);
        if (block == Blocks.coal_ore && coal.isToggled()) return rgb(0, 30, 30);
        if (block == Blocks.emerald_ore && emerald.isToggled()) return rgb(0, 255, 0);
        if (block == Blocks.gold_ore && gold.isToggled()) return rgb(255, 255, 0);
        return 0;
    }

    private static int rgb(int red, int green, int blue) {
        return new Color(red, green, blue).getRGB();
    }

    private static void drawTracer(BlockPos pos, int color) {
        double x = pos.getX() - mc.getRenderManager().viewerPosX + 0.5D;
        double y = pos.getY() - mc.getRenderManager().viewerPosY + 0.5D;
        double z = pos.getZ() - mc.getRenderManager().viewerPosZ + 0.5D;
        float red = (color >> 16 & 255) / 255.0F;
        float green = (color >> 8 & 255) / 255.0F;
        float blue = (color & 255) / 255.0F;

        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.0F);
        GL11.glColor4f(red, green, blue, 1.0F);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex3d(0.0D, mc.thePlayer.getEyeHeight(), 0.0D);
        GL11.glVertex3d(x, y, z);
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glPopMatrix();
    }

    private boolean isOreSetting(ButtonSetting setting) {
        return setting == diamond || setting == redstone || setting == emerald || setting == lapis
                || setting == iron || setting == coal || setting == gold;
    }

    private static void reloadRenderers() {
        if (mc.renderGlobal != null) {
            mc.renderGlobal.loadRenderers();
        }
    }

    public static boolean showESP() {
        Xray module = (Xray) Module.getModule(Xray.class);
        return module != null && module.esp.isToggled();
    }

    public static int getDistance() {
        Xray module = (Xray) Module.getModule(Xray.class);
        return module == null ? 42 : (int) module.distance.getInput();
    }

    public static void discover(Block block, BlockPos pos) {
        if (!isEnabled || !showESP() || mc.thePlayer == null || block == null || pos == null) {
            return;
        }
        if (block != Blocks.diamond_ore && block != Blocks.iron_ore && block != Blocks.lapis_ore
                && block != Blocks.redstone_ore && block != Blocks.coal_ore
                && block != Blocks.emerald_ore && block != Blocks.gold_ore) {
            return;
        }

        double dx = mc.thePlayer.posX - pos.getX();
        double dz = mc.thePlayer.posZ - pos.getZ();
        int range = getDistance();
        if (dx * dx + dz * dz <= range * range && !blockPosList.contains(pos)) {
            blockPosList.add(new BlockPos(pos.getX(), pos.getY(), pos.getZ()));
        }
    }
}
