package keystrokesmod.module.impl.render;

import keystrokesmod.module.Module;
import keystrokesmod.module.impl.combat.KillAura;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.novoline.font.NovolineFonts;
import keystrokesmod.novoline.font.api.FontRenderer;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Theme;
import keystrokesmod.utility.Timer;
import keystrokesmod.utility.Utils;
import keystrokesmod.utility.shader.BlurUtils;
import keystrokesmod.utility.shader.RoundedUtils;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.client.event.RenderWorldLastEvent;
import net.minecraftforge.fml.client.config.GuiButtonExt;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

public class TargetHUD extends Module {
    private SliderSetting mode;
    private SliderSetting targetHudFont;
    private SliderSetting theme;
    private ButtonSetting renderEsp;
    private ButtonSetting showStatus;
    private ButtonSetting healthColor;
    private Timer fadeTimer;
    private Timer healthBarTimer = null;
    private EntityLivingBase target;
    private long lastAliveMS;
    private double lastHealth;
    private float lastHealthBar;
    private EntityLivingBase myauLastTarget;
    private float myauHealthFrom;
    private float myauHealthTo;
    private long myauHealthAnimationStart;
    private EntityLivingBase foodByteLastTarget;
    private float foodByteTrailingHealth;
    private float foodByteLastHealth;
    private float foodByteHeadScale = 1.0f;
    private long foodByteLastFrame;
    private long foodByteDamageStart;
    private String foodByteDamageText;
    private int lastRenderedMode = -1;
    public EntityLivingBase renderEntity;
    public int posX = 70;
    public int posY = 30;
    private String[] modes = new String[]{ "Modern", "Legacy", "Astolfo", "BingUS", "Myau", "FoodByte" };
    private static final String[] FONT_MODES = new String[]{ "Minecraft", "SF", "SF Bold", "SF Thin" };
    private static final DecimalFormat MYAU_HEALTH_FORMAT = new DecimalFormat("0.0", new DecimalFormatSymbols(Locale.US));
    private static final DecimalFormat MYAU_DIFF_FORMAT = new DecimalFormat("+0.0;-0.0", new DecimalFormatSymbols(Locale.US));

    public TargetHUD() {
        super("TargetHUD", category.visuals);
        this.registerSetting(new DescriptionSetting("Only works with KillAura."));
        this.registerSetting(mode = new SliderSetting("Mode", 1, modes));
        this.registerSetting(targetHudFont = new SliderSetting("Font", 0, FONT_MODES));
        this.registerSetting(theme = new SliderSetting("Theme", 0, Theme.themes));
        this.registerSetting(new ButtonSetting("Edit position", () -> {
            mc.displayGuiScreen(new EditScreen());
        }));
        this.registerSetting(renderEsp = new ButtonSetting("Render ESP", true));
        this.registerSetting(showStatus = new ButtonSetting("Show win or loss", true));
        this.registerSetting(healthColor = new ButtonSetting("Traditional health color", false));
    }

    public void onDisable() {
        reset();
    }

    @SubscribeEvent
    public void onRenderTick(TickEvent.RenderTickEvent ev) {
        if (!Utils.nullCheck()) {
            reset();
            return;
        }
        if (ev.phase == TickEvent.Phase.END) {
            if (mc.currentScreen != null) {
                reset();
                return;
            }
            if (KillAura.attackingEntity != null) {
                target = KillAura.attackingEntity;
                lastAliveMS = System.currentTimeMillis();
                fadeTimer = null;
            } else if (target != null) {
                if (System.currentTimeMillis() - lastAliveMS >= 400 && fadeTimer == null) {
                    (fadeTimer = new Timer(400)).start();
                }
            }
            else {
                return;
            }
            String playerInfo = target.getDisplayName().getFormattedText();
            double health = target.getHealth() / target.getMaxHealth();
            if (target.isDead) {
                health = 0;
            }
            int selectedMode = (int) mode.getInput();
            if (selectedMode != lastRenderedMode) {
                healthBarTimer = null;
                lastHealthBar = 0.0f;
                myauLastTarget = null;
                foodByteLastTarget = null;
                foodByteLastFrame = 0L;
                foodByteDamageText = null;
                lastRenderedMode = selectedMode;
            }
            if (health != lastHealth) {
                (healthBarTimer = new Timer(mode.getInput() == 0 ? 500 : 350)).start();
            }
            lastHealth = health;
            playerInfo += " " + Utils.getHealthStr(target, true);
            drawTargetHUD(fadeTimer, playerInfo, health, target);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRenderWorld(RenderWorldLastEvent renderWorldLastEvent) {
        if (!renderEsp.isToggled() || !Utils.nullCheck()) {
            return;
        }
        if (KillAura.target != null) {
            RenderUtils.renderEntity(KillAura.target, 2, 0.0, 0.0, Theme.getGradient((int) theme.getInput(), 0), false);
        }
        else if (renderEntity != null) {
            RenderUtils.renderEntity(renderEntity, 2, 0.0, 0.0, Theme.getGradient((int) theme.getInput(), 0), false);
        }
    }

    private void drawTargetHUD(Timer fadeTimer, String string, double health, EntityLivingBase entity) {
        if (showStatus.isToggled()) {
            string = string + " " + ((health <= Utils.getTotalHealth(mc.thePlayer) / mc.thePlayer.getMaxHealth()) ? "§aW" : "§cL");
        }
        final int alpha = (fadeTimer == null) ? 255 : (255 - fadeTimer.getValueInt(0, 255, 1));
        if (alpha <= 0) {
            target = null;
            healthBarTimer = null;
            return;
        }
        if ((int) mode.getInput() == 2) {
            drawAstolfoTargetHUD(entity, health, alpha);
            return;
        }
        if ((int) mode.getInput() == 3) {
            drawBingUsTargetHUD(entity, health, alpha);
            return;
        }
        if ((int) mode.getInput() == 4) {
            drawMyauTargetHUD(entity, alpha);
            return;
        }
        if ((int) mode.getInput() == 5) {
            drawFoodByteTargetHUD(entity, alpha);
            return;
        }
        final ScaledResolution scaledResolution = new ScaledResolution(mc);
        final int padding = 8;
        final int targetStrWithPadding = getTargetStringWidth(string) + padding;
        final int x = (scaledResolution.getScaledWidth() / 2 - targetStrWithPadding / 2) + posX;
        final int y = (scaledResolution.getScaledHeight() / 2 + 15) + posY;
        final int n6 = x - padding;
        final int n7 = y - padding;
        final int n8 = x + targetStrWithPadding;
        final int n9 = y + (getTargetFontHeight() + 5) - 6 + padding;
        if (alpha > 0) {
            final int maxAlphaOutline = (alpha > 110) ? 110 : alpha;
            final int maxAlphaBackground = (alpha > 210) ? 210 : alpha;
            final int[] gradientColors = Theme.getGradients((int) theme.getInput());
            switch ((int) mode.getInput()) {
                case 0:
                    float bloomRadius = (fadeTimer == null) ? 2f : (2f * alpha / 255f);
                    float blurRadius = (fadeTimer == null) ? 3 : (3f * alpha / 255f);
                    BlurUtils.prepareBloom();
                    RoundedUtils.drawRound((float) n6, (float) n7, Math.abs((float) n6 - n8), Math.abs((float) n7 - (n9 + 13)), 8.0f, true, new Color(0, 0, 0, maxAlphaBackground));
                    BlurUtils.bloomEnd(3, bloomRadius);
                    BlurUtils.prepareBlur();
                    RoundedUtils.drawRound((float) n6, (float) n7, Math.abs((float) n6 - n8), Math.abs((float) n7 - (n9 + 13)), 8.0f, true, new Color(Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline)));
                    BlurUtils.blurEnd(2, blurRadius);
                    break;
                case 1:
                    RenderUtils.drawRoundedGradientOutlinedRectangle((float) n6, (float) n7, (float) n8, (float) (n9 + 13), 10.0f, Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline), Utils.mergeAlpha(gradientColors[0], alpha), Utils.mergeAlpha(gradientColors[1], alpha));
                    break;
            }
            final int n13 = n6 + 6;
            final int n14 = n8 - 6;
            final int n15 = n9;

            // Bar background
            RenderUtils.drawRoundedRectangle((float) n13, (float) n15, (float) n14, (float) (n15 + 5), 4.0f, Utils.mergeAlpha(Color.black.getRGB(), maxAlphaOutline));
            int mergedGradientLeft = Utils.mergeAlpha(gradientColors[0], maxAlphaBackground);
            int mergedGradientRight = Utils.mergeAlpha(gradientColors[1], maxAlphaBackground);
            float healthBar = (float) (int) (n14 + (n13 - n14) * (1 - health));
            boolean smoothBack = false;
            if (healthBar != lastHealthBar && lastHealthBar - n13 >= 3 && healthBarTimer != null ) {
                int type = mode.getInput() == 0 ? 4 : 1;
                float diff = lastHealthBar - healthBar;
                if (diff > 0) {
                    lastHealthBar = lastHealthBar - healthBarTimer.getValueFloat(0, diff, type);
                }
                else {
                    smoothBack = true;
                    lastHealthBar = healthBarTimer.getValueFloat(lastHealthBar, healthBar, type);
                }
            }
            else {
                lastHealthBar = healthBar;
            }
            if (healthColor.isToggled()) {
                mergedGradientLeft = mergedGradientRight = Utils.mergeAlpha(Utils.getColorForHealth(health), maxAlphaBackground);
            }
            if (lastHealthBar > n14) { // exceeds total width then clamp
                lastHealthBar = n14;
            }

            switch ((int) mode.getInput()) { // health bar
                case 0:
                    RenderUtils.drawRoundedRectangle((float) n13, (float) n15, lastHealthBar, (float) (n15 + 5), 4.0f, Utils.darkenColor(mergedGradientRight, 25));
                    RenderUtils.drawRoundedGradientRect((float) n13, (float) n15, smoothBack ? lastHealthBar : healthBar, (float) (n15 + 5), 4.0f, mergedGradientLeft, mergedGradientLeft, mergedGradientRight, mergedGradientRight);
                    break;
                case 1:
                    RenderUtils.drawRoundedGradientRect((float) n13, (float) n15, lastHealthBar, (float) (n15 + 5), 4.0f, mergedGradientLeft, mergedGradientLeft, mergedGradientRight, mergedGradientRight);
                    break;
            }
            drawTargetString(string, x, y,
                    (new Color(220, 220, 220, 255).getRGB() & 0xFFFFFF) | Utils.clamp(alpha + 15) << 24, true);
        }
        else {
            target = null;
            healthBarTimer = null;
        }
    }

    private void drawAstolfoTargetHUD(EntityLivingBase entity, double health, int alpha) {
        if (entity == null) {
            return;
        }

        final float[] bounds = getHudBounds(entity.getDisplayName().getFormattedText());
        final float left = bounds[0];
        final float top = bounds[1];
        final float right = bounds[2];
        final float bottom = bounds[3];
        final int backgroundAlpha = Math.min(140, alpha * 140 / 255);

        RenderUtils.drawRect(left, top, right, bottom, Utils.mergeAlpha(Color.BLACK.getRGB(), alpha));
        RenderUtils.drawRect(left + 0.5f, top + 0.5f, right - 0.5f, bottom - 0.5f,
                Utils.mergeAlpha(Color.BLACK.getRGB(), backgroundAlpha));

        final int textAlpha = Utils.clamp(alpha + 15);
        drawTargetString(entity.getDisplayName().getFormattedText(), left + 35, top + 3,
                Utils.mergeAlpha(Color.WHITE.getRGB(), textAlpha), true);

        final double healthPercent = Math.max(0.0, Math.min(1.0, health));
        final Color healthColor = Color.getHSBColor((float) (healthPercent * 120.0 / 360.0), 0.7f, 1.0f);
        final String healthValue = String.valueOf(Math.round(Math.max(0.0f, entity.getHealth()) * 10.0f) / 10.0d);
        final String healthText = usesMinecraftFont() ? healthValue + " \u2764" : healthValue;
        GL11.glPushMatrix();
        GL11.glTranslatef(left + 36, top + 15, 0);
        GL11.glScalef(2.0f, 2.0f, 2.0f);
        drawTargetString(healthText, 0, 0, Utils.mergeAlpha(healthColor.getRGB(), textAlpha), true);
        if (!usesMinecraftFont()) {
            mc.fontRendererObj.drawStringWithShadow("\u2764", getTargetStringWidth(healthValue) + 2, 0,
                    Utils.mergeAlpha(healthColor.getRGB(), textAlpha));
        }
        GL11.glPopMatrix();

        final float barLeft = left + 36;
        final float barRight = right - 10;
        final float barTop = top + 36.5f;
        final float healthBar = barLeft + (barRight - barLeft) * (float) healthPercent;
        if (lastHealthBar < barLeft || lastHealthBar > barRight) {
            lastHealthBar = healthBar;
        }
        if (healthBar != lastHealthBar && healthBarTimer != null) {
            lastHealthBar = healthBarTimer.getValueFloat(lastHealthBar, healthBar, 1);
        }
        else if (healthBarTimer == null) {
            lastHealthBar = healthBar;
        }

        final int darkHealth = Utils.mergeAlpha(healthColor.darker().darker().getRGB(), alpha);
        RenderUtils.drawRect(barLeft, barTop, barRight, barTop + 8,
                Utils.mergeAlpha(healthColor.darker().darker().getRGB(), Math.min(90, alpha)));
        RenderUtils.drawRect(barLeft, barTop, Math.max(barLeft, lastHealthBar), barTop + 8, darkHealth);
        RenderUtils.drawRect(barLeft, barTop, Math.max(barLeft, healthBar), barTop + 8,
                Utils.mergeAlpha(healthColor.getRGB(), alpha));

        GL11.glColor4f(1, 1, 1, 1);
        GuiInventory.drawEntityOnScreen((int) (left + 17), (int) (top + 46),
                Math.max(1, (int) (42.0f / entity.height)), 0, 0, entity);
        GL11.glColor4f(1, 1, 1, 1);
    }

    private void drawBingUsTargetHUD(EntityLivingBase entity, double health, int alpha) {
        if (entity == null) {
            return;
        }

        final String displayName = entity.getDisplayName().getFormattedText();
        final float[] bounds = getHudBounds(displayName);
        final float left = bounds[0];
        final float top = bounds[1];
        final float right = bounds[2];
        final float bottom = bounds[3];
        final double healthPercent = Math.max(0.0, Math.min(1.0, health));
        final int accentColor = Utils.mergeAlpha(Theme.getGradient((int) theme.getInput(), 0), alpha);
        final int backgroundAlpha = Math.min(100, alpha * 100 / 255);

        RenderUtils.drawRect(left, top, right, bottom,
                Utils.mergeAlpha(new Color(5, 5, 5).getRGB(), backgroundAlpha));
        drawTargetString(displayName, left + 26, top + 2, accentColor, true);

        final float healthBar = left + (right - left) * (float) healthPercent;
        if (lastHealthBar < left || lastHealthBar > right) {
            lastHealthBar = healthBar;
        }
        if (healthBar != lastHealthBar && healthBarTimer != null) {
            lastHealthBar = healthBarTimer.getValueFloat(lastHealthBar, healthBar, 1);
        }
        else if (healthBarTimer == null) {
            lastHealthBar = healthBar;
        }
        RenderUtils.drawRect(left, bottom - 2, Math.max(left, lastHealthBar), bottom, accentColor);

        GL11.glColor4f(1, 1, 1, 1);
        GuiInventory.drawEntityOnScreen((int) (left + 13), (int) (top + 40), 20,
                entity.rotationYaw, -entity.rotationPitch, entity);
        GL11.glColor4f(1, 1, 1, 1);

        final String healthValue = String.valueOf(Math.round(Math.max(0.0f, entity.getHealth()) * 10.0f) / 10.0d);
        final String healthText = usesMinecraftFont() ? healthValue + " \u2764" : healthValue;
        GL11.glPushMatrix();
        GL11.glTranslatef(left + 25, top + 14, 0);
        GL11.glScalef(1.5f, 1.5f, 1.5f);
        drawTargetString(healthText, 0, 0, accentColor, true);
        if (!usesMinecraftFont()) {
            mc.fontRendererObj.drawStringWithShadow("\u2764", getTargetStringWidth(healthValue) + 1, 0, accentColor);
        }
        GL11.glPopMatrix();
    }

    private void drawMyauTargetHUD(EntityLivingBase entity, int alpha) {
        if (entity == null) {
            return;
        }

        final float targetHealth = Math.max(0.0f, entity.getHealth() + entity.getAbsorptionAmount());
        final float maxHealth = Math.max(1.0f, entity.getMaxHealth());
        if (entity != myauLastTarget) {
            myauLastTarget = entity;
            myauHealthFrom = targetHealth;
            myauHealthTo = targetHealth;
            myauHealthAnimationStart = System.currentTimeMillis();
        }
        else if (targetHealth != myauHealthTo) {
            myauHealthFrom = getMyauAnimatedHealth();
            myauHealthTo = targetHealth;
            myauHealthAnimationStart = System.currentTimeMillis();
        }

        final float healthRatio = Math.max(0.0f, Math.min(1.0f, getMyauAnimatedHealth() / maxHealth));
        final float targetHearts = targetHealth / 2.0f;
        final float absorptionHearts = entity.getAbsorptionAmount() / 2.0f;
        final float playerHearts = (mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount()) / 2.0f;
        final String nameText = Utils.stripString(entity.getDisplayName().getFormattedText());
        final String healthText = "§f" + MYAU_HEALTH_FORMAT.format(targetHearts)
                + (absorptionHearts > 0.0f ? "§6" : "§c") + "\u2764§r";
        final String statusText = "§l" + (targetHearts == playerHearts ? "D" : targetHearts < playerHearts ? "W" : "L") + "§r";
        final String differenceText = targetHearts == playerHearts
                ? "0.0"
                : MYAU_DIFF_FORMAT.format(playerHearts - targetHearts);
        final float[] bounds = getMyauHudBounds(entity);
        final float width = bounds[2] - bounds[0];
        final float headOffset = getMyauSkin(entity) == null ? 0.0f : 25.0f;
        final int healthColor = getMyauHealthColor(healthRatio, alpha);
        final float healthDeltaRatio = Math.max(0.0f, Math.min(1.0f, (playerHearts - targetHearts + 1.0f) / 2.0f));
        final int deltaColor = getMyauHealthColor(healthDeltaRatio, alpha);

        GlStateManager.pushMatrix();
        GlStateManager.translate(bounds[0], bounds[1], 0.0f);
        RenderUtils.drawRect(0.0f, 0.0f, width, 27.0f,
                Utils.mergeAlpha(Color.BLACK.getRGB(), alpha * 64 / 255));
        RenderUtils.drawRect(headOffset + 2.0f, 22.0f, width - 2.0f, 25.0f,
                Utils.mergeAlpha(darkenColor(healthColor, 0.2f), alpha));
        RenderUtils.drawRect(headOffset + 2.0f, 22.0f,
                headOffset + 2.0f + healthRatio * (width - headOffset - 4.0f), 25.0f, healthColor);

        GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        mc.fontRendererObj.drawString(nameText, headOffset + 2.0f, 2.0f,
                Utils.mergeAlpha(Color.WHITE.getRGB(), alpha), true);
        mc.fontRendererObj.drawString(healthText, headOffset + 2.0f, 12.0f,
                Utils.mergeAlpha(Color.WHITE.getRGB(), alpha), true);
        if (showStatus.isToggled()) {
            mc.fontRendererObj.drawString(statusText,
                    width - 2.0f - mc.fontRendererObj.getStringWidth(statusText), 2.0f, deltaColor, true);
            mc.fontRendererObj.drawString(differenceText,
                    width - 2.0f - mc.fontRendererObj.getStringWidth(differenceText), 12.0f,
                    Utils.mergeAlpha(darkenColor(deltaColor, 0.8f), alpha), true);
        }

        final ResourceLocation skin = getMyauSkin(entity);
        if (skin != null) {
            GlStateManager.color(1.0f, 1.0f, 1.0f, alpha / 255.0f);
            mc.getTextureManager().bindTexture(skin);
            Gui.drawScaledCustomSizeModalRect(2, 2, 8.0f, 8.0f, 8, 8, 23, 23, 64.0f, 64.0f);
            Gui.drawScaledCustomSizeModalRect(2, 2, 40.0f, 8.0f, 8, 8, 23, 23, 64.0f, 64.0f);
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.disableBlend();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private float getMyauAnimatedHealth() {
        final float progress = Math.max(0.0f, Math.min(1.0f,
                (System.currentTimeMillis() - myauHealthAnimationStart) / 150.0f));
        return myauHealthFrom + (myauHealthTo - myauHealthFrom) * progress;
    }

    private int getMyauHealthColor(float healthRatio, int alpha) {
        final Color color = Color.getHSBColor(healthRatio * 120.0f / 360.0f, 0.7f, 1.0f);
        return Utils.mergeAlpha(color.getRGB(), alpha);
    }

    private int darkenColor(int color, float factor) {
        final Color source = new Color(color, true);
        return new Color((int) (source.getRed() * factor), (int) (source.getGreen() * factor),
                (int) (source.getBlue() * factor), source.getAlpha()).getRGB();
    }

    private ResourceLocation getMyauSkin(EntityLivingBase entity) {
        if (!(entity instanceof EntityPlayer) || mc.getNetHandler() == null) {
            return null;
        }
        final NetworkPlayerInfo playerInfo = mc.getNetHandler().getPlayerInfo(entity.getUniqueID());
        return playerInfo == null ? null : playerInfo.getLocationSkin();
    }

    private float[] getMyauHudBounds(EntityLivingBase entity) {
        final ScaledResolution resolution = new ScaledResolution(mc);
        final float targetHearts = Math.max(0.0f, entity.getHealth() + entity.getAbsorptionAmount()) / 2.0f;
        final float playerHearts = (mc.thePlayer.getHealth() + mc.thePlayer.getAbsorptionAmount()) / 2.0f;
        final String nameText = Utils.stripString(entity.getDisplayName().getFormattedText());
        final String healthText = MYAU_HEALTH_FORMAT.format(targetHearts) + "\u2764";
        final String statusText = targetHearts == playerHearts ? "D" : targetHearts < playerHearts ? "W" : "L";
        final String differenceText = targetHearts == playerHearts ? "0.0" : MYAU_DIFF_FORMAT.format(playerHearts - targetHearts);
        final float indicatorWidth = showStatus.isToggled()
                ? 4.0f + Math.max(mc.fontRendererObj.getStringWidth(statusText), mc.fontRendererObj.getStringWidth(differenceText))
                : 0.0f;
        final float contentWidth = Math.max(mc.fontRendererObj.getStringWidth(nameText),
                mc.fontRendererObj.getStringWidth(healthText)) + indicatorWidth;
        final float headOffset = getMyauSkin(entity) == null ? 0.0f : 25.0f;
        final float width = Math.max(headOffset + 70.0f, headOffset + contentWidth + 4.0f);
        final float left = resolution.getScaledWidth() / 2.0f - width / 2.0f + posX;
        final float top = resolution.getScaledHeight() / 2.0f + 7.0f + posY;
        return new float[]{left, top, left + width, top + 27.0f};
    }

    private void drawFoodByteTargetHUD(EntityLivingBase entity, int alpha) {
        if (entity == null) {
            return;
        }

        final long now = System.currentTimeMillis();
        final float health = Math.max(0.0f, entity.getHealth());
        final float maxHealth = Math.max(1.0f, entity.getMaxHealth());
        final float healthRatio = Math.max(0.0f, Math.min(1.0f, health / maxHealth));
        final float frameTime = foodByteLastFrame == 0L ? 16.0f : Math.min(50.0f, now - foodByteLastFrame);
        foodByteLastFrame = now;

        if (entity != foodByteLastTarget) {
            foodByteLastTarget = entity;
            foodByteTrailingHealth = healthRatio;
            foodByteLastHealth = health;
            foodByteHeadScale = 1.0f;
            foodByteDamageText = null;
        }
        else {
            if (health != foodByteLastHealth) {
                float difference = health - foodByteLastHealth;
                foodByteDamageText = (difference > 0.0f ? "§a+ " : "§c- ")
                        + MYAU_HEALTH_FORMAT.format(Math.abs(difference));
                foodByteDamageStart = now;
                foodByteLastHealth = health;
            }
            if (entity.hurtTime <= 6) {
                float catchUp = 1.0f - (float) Math.exp(-frameTime / 110.0f);
                foodByteTrailingHealth += (healthRatio - foodByteTrailingHealth) * catchUp;
            }
        }

        float targetHeadScale = entity.hurtTime > 5 ? 0.9f : 1.0f;
        float headCatchUp = Math.min(1.0f, frameTime / 70.0f);
        foodByteHeadScale += (targetHeadScale - foodByteHeadScale) * headCatchUp;

        final String name = Utils.stripString(entity.getName());
        final float naturalNameWidth = getTargetStringWidth(name);
        final float contentWidth = Math.max(65.0f, naturalNameWidth);
        final float barWidth = contentWidth - 9.0f;
        final float[] bounds = getFoodByteHudBounds(entity);
        final float width = bounds[2] - bounds[0];
        final float armorRatio = Math.max(0.0f, Math.min(1.0f, entity.getTotalArmorValue() / 20.0f));
        final int backgroundAlpha = alpha * 150 / 255;
        final int outlineAlpha = alpha * 150 / 255;
        final int healthGold = Utils.mergeAlpha(new Color(219, 190, 1).getRGB(), alpha);
        final int trailingGold = Utils.mergeAlpha(new Color(153, 133, 1).getRGB(), alpha);
        final int armorBlue = Utils.mergeAlpha(new Color(0, 180, 255).getRGB(), alpha);

        GlStateManager.pushMatrix();
        GlStateManager.translate(bounds[0], bounds[1], 0.0f);
        RenderUtils.drawRoundedRectangle(0.0f, 0.0f, width, 30.0f, 1.0f,
                Utils.mergeAlpha(Color.BLACK.getRGB(), backgroundAlpha));

        float nameX = naturalNameWidth > 65.0f ? 32.0f : 64.0f - naturalNameWidth / 2.0f;
        drawTargetString(name, nameX, 3.0f, Utils.mergeAlpha(Color.WHITE.getRGB(), alpha), false);

        drawFoodByteBar(40.5f, 16.0f, barWidth + 1.0f, outlineAlpha);
        RenderUtils.drawRect(41.0f, 16.5f,
                41.0f + barWidth * Math.max(0.0f, Math.min(1.0f, foodByteTrailingHealth)), 18.5f, trailingGold);
        RenderUtils.drawRect(41.0f, 16.5f, 41.0f + barWidth * healthRatio, 18.5f, healthGold);

        drawFoodByteBar(40.5f, 23.0f, barWidth + 1.0f, outlineAlpha);
        RenderUtils.drawRect(41.0f, 23.5f, 41.0f + barWidth * armorRatio / 2.0f, 25.5f, armorBlue);

        mc.fontRendererObj.drawString("\u2764", 31.0f, 13.0f, healthGold, false);
        mc.fontRendererObj.drawString("\u25c6", 31.0f, 20.0f,
                Utils.mergeAlpha(new Color(153, 153, 153).getRGB(), alpha), false);
        mc.fontRendererObj.drawString("\u2764", contentWidth + 13.0f, 19.5f,
                Utils.mergeAlpha(new Color(0, 153, 255).getRGB(), alpha), false);

        String healthValue = formatFoodByteHealth(health + entity.getAbsorptionAmount());
        FontRenderer smallFont = NovolineFonts.sf(14);
        smallFont.drawString(healthValue, contentWidth + 25.0f - smallFont.stringWidth(healthValue) / 2.0f,
                20.0f, Utils.mergeAlpha(Color.WHITE.getRGB(), alpha), false);

        ResourceLocation skin = getMyauSkin(entity);
        if (skin != null) {
            drawFoodByteHead(entity, skin, alpha);
        }

        if (foodByteDamageText != null) {
            float progress = Math.min(1.0f, (now - foodByteDamageStart) / 800.0f);
            if (progress < 1.0f) {
                int damageAlpha = (int) (alpha * (1.0f - progress));
                FontRenderer damageFont = NovolineFonts.bold(18);
                damageFont.drawString(foodByteDamageText,
                        13.0f - damageFont.stringWidth(foodByteDamageText) / 2.0f,
                        -11.0f - progress * 5.0f, Utils.mergeAlpha(Color.WHITE.getRGB(), damageAlpha), true);
            }
            else {
                foodByteDamageText = null;
            }
        }

        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.popMatrix();
    }

    private void drawFoodByteBar(float x, float y, float width, int alpha) {
        RenderUtils.drawRect(x, y, x + width, y + 3.0f,
                Utils.mergeAlpha(new Color(153, 153, 153).getRGB(), alpha));
        RenderUtils.drawRect(x + 0.5f, y + 0.5f, x + width - 0.5f, y + 2.5f,
                Utils.mergeAlpha(Color.BLACK.getRGB(), alpha * 80 / 150));
    }

    private void drawFoodByteHead(EntityLivingBase entity, ResourceLocation skin, int alpha) {
        GlStateManager.pushMatrix();
        GlStateManager.translate(14.0f, 14.0f, 0.0f);
        GlStateManager.scale(foodByteHeadScale, foodByteHeadScale, 1.0f);
        GlStateManager.translate(-14.0f, -14.0f, 0.0f);
        GlStateManager.disableDepth();
        GlStateManager.disableCull();
        GlStateManager.enableBlend();
        GlStateManager.blendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        float hurtTint = 1.0f - Math.min(1.0f, entity.hurtTime / 10.0f);
        GlStateManager.color(1.0f, hurtTint, hurtTint, alpha / 255.0f);
        mc.getTextureManager().bindTexture(skin);
        drawCircularSkinLayer(14.0f, 14.0f, 13.0f, 12.0f, 12.0f);
        if (entity instanceof EntityPlayer && ((EntityPlayer) entity).isWearing(EnumPlayerModelParts.HAT)) {
            drawCircularSkinLayer(14.0f, 14.0f, 13.0f, 44.0f, 12.0f);
        }
        GlStateManager.color(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        GlStateManager.enableDepth();
        GlStateManager.popMatrix();
    }

    private void drawCircularSkinLayer(float centerX, float centerY, float radius, float textureCenterX,
                                       float textureCenterY) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glTexCoord2f(textureCenterX / 64.0f, textureCenterY / 64.0f);
        GL11.glVertex2f(centerX, centerY);
        for (int point = 0; point <= 40; ++point) {
            double angle = Math.PI * 2.0 * point / 40.0;
            float cosine = (float) Math.cos(angle);
            float sine = (float) Math.sin(angle);
            GL11.glTexCoord2f((textureCenterX + cosine * 4.0f) / 64.0f,
                    (textureCenterY + sine * 4.0f) / 64.0f);
            GL11.glVertex2f(centerX + cosine * radius, centerY + sine * radius);
        }
        GL11.glEnd();
    }

    private String formatFoodByteHealth(float health) {
        return Math.abs(health - Math.round(health)) < 0.001f
                ? String.valueOf(Math.round(health))
                : MYAU_HEALTH_FORMAT.format(health);
    }

    private float[] getFoodByteHudBounds(EntityLivingBase entity) {
        ScaledResolution resolution = new ScaledResolution(mc);
        String name = Utils.stripString(entity.getName());
        float contentWidth = Math.max(65.0f, getTargetStringWidth(name));
        float width = 37.0f + contentWidth;
        float left = resolution.getScaledWidth() / 2.0f - width / 2.0f + posX;
        float top = resolution.getScaledHeight() / 2.0f + 7.0f + posY;
        return new float[]{left, top, left + width, top + 30.0f};
    }

    private float[] getHudBounds(String string) {
        final ScaledResolution resolution = new ScaledResolution(mc);
        if ((int) mode.getInput() == 2) {
            final int contentWidth = Math.max(75, getTargetStringWidth(string) + 20);
            final float width = 55 + contentWidth;
            final float left = resolution.getScaledWidth() / 2.0f - width / 2.0f + posX;
            final float top = resolution.getScaledHeight() / 2.0f + 7 + posY;
            return new float[]{left, top, left + width, top + 47};
        }
        if ((int) mode.getInput() == 3) {
            final float width = 28 + Math.max(80, getTargetStringWidth(string));
            final float left = resolution.getScaledWidth() / 2.0f - width / 2.0f + posX;
            final float top = resolution.getScaledHeight() / 2.0f + 7 + posY;
            return new float[]{left, top, left + width, top + 45};
        }
        if ((int) mode.getInput() == 4 && mc.thePlayer != null) {
            return getMyauHudBounds(mc.thePlayer);
        }
        if ((int) mode.getInput() == 5 && mc.thePlayer != null) {
            return getFoodByteHudBounds(mc.thePlayer);
        }

        final int padding = 8;
        final int targetStrWithPadding = getTargetStringWidth(string) + padding;
        final int x = (resolution.getScaledWidth() / 2 - targetStrWithPadding / 2) + posX;
        final int y = (resolution.getScaledHeight() / 2 + 15) + posY;
        final int left = x - padding;
        final int top = y - padding;
        final int right = x + targetStrWithPadding;
        final int textBottom = y + (getTargetFontHeight() + 5) - 6 + padding;
        return new float[]{left, top, right, textBottom + 13};
    }

    private boolean usesMinecraftFont() {
        return targetHudFont == null || (int) targetHudFont.getInput() == 0;
    }

    private FontRenderer getTargetFont() {
        int selectedFont = targetHudFont == null ? 0 : (int) targetHudFont.getInput();
        switch (selectedFont) {
            case 2:
                return NovolineFonts.bold(18);
            case 3:
                return NovolineFonts.thin(18);
            default:
                return NovolineFonts.sf(18);
        }
    }

    private int getTargetStringWidth(String text) {
        return usesMinecraftFont() ? mc.fontRendererObj.getStringWidth(text) : getTargetFont().stringWidth(text);
    }

    private int getTargetFontHeight() {
        return usesMinecraftFont() ? mc.fontRendererObj.FONT_HEIGHT : getTargetFont().getHeight();
    }

    private void drawTargetString(String text, float x, float y, int color, boolean shadow) {
        if (usesMinecraftFont()) {
            mc.fontRendererObj.drawString(text, x, y, color, shadow);
        }
        else {
            getTargetFont().drawString(text, x, y, color, shadow);
        }
    }

    private void reset() {
        fadeTimer = null;
        target = null;
        healthBarTimer = null;
        myauLastTarget = null;
        foodByteLastTarget = null;
        foodByteLastFrame = 0L;
        foodByteDamageText = null;
        lastRenderedMode = -1;
        renderEntity = null;
    }

    class EditScreen extends GuiScreen {
        GuiButtonExt resetPosition;
        boolean d = false;
        int miX = 0;
        int miY = 0;
        int maX = 0;
        int maY = 0;
        int aX = 70;
        int aY = 30;
        int laX = 0;
        int laY = 0;
        int lmX = 0;
        int lmY = 0;
        int clickMinX = 0;

        public void initGui() {
            super.initGui();
            this.buttonList.add(this.resetPosition = new GuiButtonExt(1, this.width - 90, this.height - 25, 85, 20, "Reset position"));
            this.aX = posX;
            this.aY = posY;
        }

        public void drawScreen(int mX, int mY, float pt) {
            ScaledResolution res = new ScaledResolution(this.mc);
            drawRect(0, 0, this.width, this.height, -1308622848);
            posX = this.aX;
            posY = this.aY;
            String playerInfo = mc.thePlayer.getDisplayName().getFormattedText();
            double health = mc.thePlayer.getHealth() / mc.thePlayer.getMaxHealth();
            if (mc.thePlayer.isDead) {
                health = 0;
            }
            lastHealth = health;
            playerInfo += " " + Utils.getHealthStr(mc.thePlayer, true);
            drawTargetHUD(null, playerInfo, health, mc.thePlayer);
            if (showStatus.isToggled()) {
                playerInfo = playerInfo + " " + ((health <= Utils.getTotalHealth(mc.thePlayer) / mc.thePlayer.getMaxHealth()) ? "§aW" : "§cL");
            }
            float[] bounds = getHudBounds(mode.getInput() >= 2
                    ? mc.thePlayer.getDisplayName().getFormattedText()
                    : playerInfo);
            this.miX = (int) bounds[0];
            this.miY = (int) bounds[1];
            this.maX = (int) Math.ceil(bounds[2]);
            this.maY = (int) Math.ceil(bounds[3]);
            this.clickMinX = this.miX;
            String edit = "Edit the HUD position by dragging.";
            int x = res.getScaledWidth() / 2 - fontRendererObj.getStringWidth(edit) / 2;
            int y = res.getScaledHeight() / 2 - 20;
            RenderUtils.drawColoredString(edit, '-', x, y, 2L, 0L, true, this.mc.fontRendererObj);

            try {
                this.handleInput();
            }
            catch (IOException var12) {
            }

            super.drawScreen(mX, mY, pt);
        }

        protected void mouseClickMove(int mX, int mY, int b, long t) {
            super.mouseClickMove(mX, mY, b, t);
            if (b == 0) {
                if (this.d) {
                    this.aX = this.laX + (mX - this.lmX);
                    this.aY = this.laY + (mY - this.lmY);
                }
                else if (mX > this.clickMinX && mX < this.maX && mY > this.miY && mY < this.maY) {
                    this.d = true;
                    this.lmX = mX;
                    this.lmY = mY;
                    this.laX = this.aX;
                    this.laY = this.aY;
                }

            }
        }

        protected void mouseReleased(int mX, int mY, int s) {
            super.mouseReleased(mX, mY, s);
            if (s == 0) {
                this.d = false;
            }

        }

        public void actionPerformed(GuiButton b) {
            if (b == this.resetPosition) {
                this.aX = posX = 70;
                this.aY = posY = 30;
            }

        }

        public boolean doesGuiPauseGame() {
            return false;
        }
    }
}
