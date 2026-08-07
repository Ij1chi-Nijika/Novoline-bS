package keystrokesmod.module.impl.render;

import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.font.MinecraftFontAdapter;
import keystrokesmod.utility.font.RavenFontRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Compact MoonLight-style toast renderer. Text measurement and drawing deliberately
 * use the global HUD renderer so changing HUD font or scale updates active toasts too.
 */
public final class NotificationManager {
    private static final Minecraft MC = Minecraft.getMinecraft();
    private static final RavenFontRenderer NOTIFICATION_FONT = new MinecraftFontAdapter(MC.fontRendererObj);
    private static final Object LOCK = new Object();
    private static final List<Notification> NOTIFICATIONS = new ArrayList<Notification>();
    private static final int MAX_VISIBLE = 5;
    private static final float MIN_WIDTH = 150.0f;
    private static final float CARD_GAP = 4.0f;
    private static final long ENTER_DURATION = 250L;
    private static final long EXIT_DURATION = 220L;
    private static long lastRenderTime;

    private NotificationManager() {
    }

    public static void moduleState(Module module, boolean enabled) {
        if (module == null || module == ModuleManager.hud || module.alwaysOn || module.isHidden()) {
            return;
        }
        if (!isEnabled() || MC.thePlayer == null || MC.theWorld == null) {
            return;
        }

        show(module.getName(), enabled ? "Enabled" : "Disabled", enabled ? Type.SUCCESS : Type.DISABLED,
                getConfiguredDuration());
    }

    public static void info(String title, String description) {
        show(title, description, Type.INFO, getConfiguredDuration());
    }

    public static void success(String title, String description) {
        show(title, description, Type.SUCCESS, getConfiguredDuration());
    }

    public static void warning(String title, String description) {
        show(title, description, Type.WARNING, getConfiguredDuration());
    }

    public static void error(String title, String description) {
        show(title, description, Type.ERROR, getConfiguredDuration());
    }

    public static void show(String title, String description, Type type, long duration) {
        if (!isEnabled()) {
            return;
        }

        String safeTitle = title == null || title.trim().isEmpty() ? "Notification" : title;
        String safeDescription = description == null ? "" : description;
        long safeDuration = Math.max(250L, duration);
        long now = System.currentTimeMillis();

        synchronized (LOCK) {
            NOTIFICATIONS.add(new Notification(safeTitle, safeDescription, type == null ? Type.INFO : type,
                    safeDuration, now));
            while (NOTIFICATIONS.size() > MAX_VISIBLE) {
                NOTIFICATIONS.remove(0);
            }
        }
    }

    public static void render() {
        if (!isEnabled()) {
            clear();
            return;
        }

        long now = System.currentTimeMillis();
        float deltaSeconds = lastRenderTime == 0L ? 0.0f : Math.min(0.1f, (now - lastRenderTime) / 1000.0f);
        lastRenderTime = now;
        RavenFontRenderer font = NOTIFICATION_FONT;
        ScaledResolution resolution = new ScaledResolution(MC);

        synchronized (LOCK) {
            Iterator<Notification> iterator = NOTIFICATIONS.iterator();
            while (iterator.hasNext()) {
                Notification notification = iterator.next();
                if (notification.isFinished(now)) {
                    iterator.remove();
                }
            }

            float targetBottom = resolution.getScaledHeight() - getBottomOffset(resolution);
            for (int index = NOTIFICATIONS.size() - 1; index >= 0; index--) {
                Notification notification = NOTIFICATIONS.get(index);
                float height = getCardHeight(font);
                float width = getCardWidth(font, notification.title, notification.description);
                float targetY = targetBottom - height;

                if (Float.isNaN(notification.y)) {
                    notification.y = targetY;
                } else if (deltaSeconds > 0.0f) {
                    float stackEase = 1.0f - (float) Math.exp(-14.0f * deltaSeconds);
                    notification.y += (targetY - notification.y) * stackEase;
                }

                drawNotification(notification, font, resolution.getScaledWidth(), width, height, now);
                targetBottom = targetY - CARD_GAP;
            }
        }
    }

    static void drawEditorPreview() {
        RavenFontRenderer font = NOTIFICATION_FONT;
        ScaledResolution resolution = new ScaledResolution(MC);
        long now = System.currentTimeMillis();
        Notification preview = new Notification("Notification", "Module enabled", Type.SUCCESS, 2000L,
                now - ENTER_DURATION);
        float width = getCardWidth(font, preview.title, preview.description);
        float height = getCardHeight(font);
        preview.y = resolution.getScaledHeight() - getBottomOffset(resolution) - height;
        drawNotification(preview, font, resolution.getScaledWidth(), width, height, now);
    }

    static float getEditorX() {
        RavenFontRenderer font = NOTIFICATION_FONT;
        ScaledResolution resolution = new ScaledResolution(MC);
        return resolution.getScaledWidth() - getRightOffset(resolution)
                - getCardWidth(font, "Notification", "Module enabled");
    }

    static float getEditorY() {
        RavenFontRenderer font = NOTIFICATION_FONT;
        ScaledResolution resolution = new ScaledResolution(MC);
        return resolution.getScaledHeight() - getBottomOffset(resolution) - getCardHeight(font);
    }

    static float getEditorWidth() {
        return getCardWidth(NOTIFICATION_FONT, "Notification", "Module enabled");
    }

    static float getEditorHeight() {
        return getCardHeight(NOTIFICATION_FONT);
    }

    public static void clear() {
        synchronized (LOCK) {
            NOTIFICATIONS.clear();
        }
        lastRenderTime = 0L;
    }

    private static void drawNotification(Notification notification, RavenFontRenderer font, int screenWidth,
                                         float width, float height, long now) {
        long age = Math.max(0L, now - notification.createdAt);
        float offset;
        float alpha = 1.0f;

        if (age < ENTER_DURATION) {
            float progress = age / (float) ENTER_DURATION;
            offset = (1.0f - easeOutCubic(progress)) * (width + 8.0f);
        } else if (age > ENTER_DURATION + notification.duration) {
            float progress = Math.min(1.0f,
                    (age - ENTER_DURATION - notification.duration) / (float) EXIT_DURATION);
            offset = easeInCubic(progress) * (width + 8.0f);
            alpha = 1.0f - progress;
        } else {
            offset = 0.0f;
        }

        ScaledResolution resolution = new ScaledResolution(MC);
        float x = screenWidth - getRightOffset(resolution) - width + offset;
        float y = notification.y;
        int background = withAlpha(0x000000, Math.round(150.0f * alpha));
        int accent = withAlpha(notification.type.color, Math.round(255.0f * alpha));
        int primary = withAlpha(0xFFFFFF, Math.round(255.0f * alpha));
        int secondary = withAlpha(0xAAAAAA, Math.round(255.0f * alpha));
        float iconSize = Math.min(14.0f, height - 4.0f);
        float iconX = x + 3.0f;
        float iconY = y + (height - iconSize) * 0.5f;

        RenderUtils.drawRoundedRectangle(x, y, x + width, y + height, 3.0f, background);
        RenderUtils.drawRoundedRectangle(iconX, iconY, iconX + iconSize, iconY + iconSize, 3.0f,
                withAlpha(notification.type.color, Math.round(190.0f * alpha)));

        String symbol = notification.type.symbol;
        float symbolX = iconX + (iconSize - font.getStringWidth(symbol)) * 0.5f;
        float symbolY = iconY + (iconSize - font.getLineHeight()) * 0.5f - font.getTextTopOffset();
        font.drawString(symbol, symbolX, symbolY, primary, false);

        float textX = x + iconSize + 8.0f;
        float contentHeight = font.getLineHeight() * 2.0f + 1.0f;
        float firstLineY = y + (height - contentHeight) * 0.5f - font.getTextTopOffset();
        font.drawString(notification.title, textX, firstLineY, primary, false);
        font.drawString(notification.description, textX, firstLineY + font.getLineHeight() + 1.0f, secondary, false);

        float stayProgress = Math.max(0.0f, Math.min(1.0f,
                (age - ENTER_DURATION) / (float) notification.duration));
        float remainingWidth = width * (1.0f - stayProgress);
        if (remainingWidth > 0.0f) {
            RenderUtils.drawRect(x, y + height - 1.0f, x + remainingWidth, y + height, accent);
        }
    }

    private static boolean isEnabled() {
        return ModuleManager.hud != null && ModuleManager.hud.isEnabled()
                && HUD.novolineNotifications != null && HUD.novolineNotifications.isToggled();
    }

    private static long getConfiguredDuration() {
        double seconds = HUD.novolineNotificationDuration == null
                ? 2.0 : HUD.novolineNotificationDuration.getInput();
        return (long) (Math.max(0.5, Math.min(5.0, seconds)) * 1000.0);
    }

    private static float getCardWidth(RavenFontRenderer font, String title, String description) {
        float textWidth = Math.max(font.getStringWidth(title), font.getStringWidth(description));
        return Math.max(MIN_WIDTH, textWidth + 30.0f);
    }

    private static float getCardHeight(RavenFontRenderer font) {
        return Math.max(22.0f, font.getLineHeight() * 2.0f + 3.0f);
    }

    private static float getRightOffset(ScaledResolution resolution) {
        double configured = HUD.novolineNotificationsX == null ? 8.0 : HUD.novolineNotificationsX.getInput();
        return (float) Math.max(0.0, Math.min(resolution.getScaledWidth() - MIN_WIDTH, configured));
    }

    private static float getBottomOffset(ScaledResolution resolution) {
        double configured = HUD.novolineNotificationsY == null ? 8.0 : HUD.novolineNotificationsY.getInput();
        return (float) Math.max(0.0, Math.min(resolution.getScaledHeight() - getCardHeight(NOTIFICATION_FONT),
                configured));
    }

    private static float easeOutCubic(float value) {
        float inverse = 1.0f - value;
        return 1.0f - inverse * inverse * inverse;
    }

    private static float easeInCubic(float value) {
        return value * value * value;
    }

    private static int withAlpha(int rgb, int alpha) {
        int safeAlpha = Math.max(0, Math.min(255, alpha));
        return safeAlpha << 24 | rgb & 0xFFFFFF;
    }

    public enum Type {
        SUCCESS(0x41FC41, "+"),
        DISABLED(0xE2574C, "-"),
        INFO(0x7FAED2, "i"),
        WARNING(0xFFFF5E, "!"),
        ERROR(0xE2574C, "x");

        private final int color;
        private final String symbol;

        Type(int color, String symbol) {
            this.color = color;
            this.symbol = symbol;
        }
    }

    private static final class Notification {
        private String title;
        private String description;
        private Type type;
        private long duration;
        private long createdAt;
        private float y = Float.NaN;

        private Notification(String title, String description, Type type, long duration, long createdAt) {
            reset(title, description, type, duration, createdAt);
        }

        private void reset(String title, String description, Type type, long duration, long createdAt) {
            this.title = title;
            this.description = description;
            this.type = type;
            this.duration = duration;
            this.createdAt = createdAt;
        }

        private boolean isFinished(long now) {
            return now - createdAt >= ENTER_DURATION + duration + EXIT_DURATION;
        }
    }
}
