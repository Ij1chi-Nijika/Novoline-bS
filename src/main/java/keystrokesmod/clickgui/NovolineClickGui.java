package keystrokesmod.clickgui;

import keystrokesmod.Raven;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.module.setting.Setting;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.KeySetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.module.setting.impl.TextSetting;
import keystrokesmod.novoline.font.NovolineFonts;
import keystrokesmod.novoline.font.api.FontRenderer;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Raven setting adapter rendered with Novoline's original Dropdown and Discord
 * ClickGUI interaction model.  The visual layer is independent from Raven's
 * module/config implementation, so existing profiles continue to work.
 */
public final class NovolineClickGui extends ClickGui {
    private static final int DARK = 0xFF1D1D1D;
    private static final int SURFACE = 0xFF282828;
    private static final int DISCORD_NAV = 0xFF202225;
    private static final int DISCORD_HEADER = 0xFF2F3136;
    private static final int DISCORD_CONTENT = 0xFF36393E;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFFB9BBBE;

    private final Map<Module.category, Panel> panels = new EnumMap<Module.category, Panel>(Module.category.class);
    private Module.category discordCategory = Module.category.combat;
    private Module draggedModule;
    private Setting draggedSlider;
    private Setting editingText;
    private Setting binding;
    private boolean draggingPanel;
    private float dragX;
    private float dragY;
    private int windowX;
    private int windowY;
    private int windowWidth;
    private int windowHeight;

    public NovolineClickGui() {
        super();
        float x = 20.0f;
        for (Module.category category : Module.category.values()) {
            panels.put(category, new Panel(category, x, 12.0f));
            x += 110.0f;
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        rebuildPanels();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution resolution = new ScaledResolution(mc);
        float scale = renderScale(resolution);
        int logicalMouseX = (int) (mouseX / scale);
        int logicalMouseY = (int) (mouseY / scale);
        drawRect(0, 0, resolution.getScaledWidth(), resolution.getScaledHeight(), 0x78000000);
        org.lwjgl.opengl.GL11.glPushMatrix();
        org.lwjgl.opengl.GL11.glScalef(scale, scale, 1.0f);
        if (isDiscord()) {
            drawDiscord(logicalMouseX, logicalMouseY);
        }
        else {
            drawDropdown(logicalMouseX, logicalMouseY);
        }
        if (draggedSlider instanceof SliderSetting) {
            updateSlider((SliderSetting) draggedSlider, logicalMouseX, findSettingBounds(draggedSlider));
        }
        org.lwjgl.opengl.GL11.glPopMatrix();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        float scale = renderScale(new ScaledResolution(mc));
        mouseX = (int) (mouseX / scale);
        mouseY = (int) (mouseY / scale);
        if (isDiscord()) {
            clickDiscord(mouseX, mouseY, mouseButton);
        }
        else {
            clickDropdown(mouseX, mouseY, mouseButton);
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0) {
            draggingPanel = false;
            draggedSlider = null;
            draggedModule = null;
            for (Panel panel : panels.values()) panel.dragging = false;
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        // Capture this event before ClickGui's legacy handler drains the wheel value,
        // while still letting GuiScreen dispatch mouse clicks and releases normally.
        int wheel = Mouse.getEventDWheel();
        super.handleMouseInput();
        if (wheel == 0) {
            return;
        }

        float scale = renderScale(new ScaledResolution(mc));
        int mouseX = (int) ((Mouse.getEventX() * width / mc.displayWidth) / scale);
        int mouseY = (int) ((height - Mouse.getEventY() * height / mc.displayHeight - 1) / scale);
        Panel panel = scrollTarget(mouseX, mouseY);
        if (panel != null && panel.open && panel.maxScroll > 0.0f) {
            float step = Math.max(12.0f, Gui.scrollSpeed == null ? 20.0f : (float) Gui.scrollSpeed.getInput());
            panel.scroll = clamp(panel.scroll - Math.signum(wheel) * step, 0.0f, panel.maxScroll);
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (binding != null) {
            if (binding instanceof KeySetting) {
                ((KeySetting) binding).setKey(keyCode == Keyboard.KEY_ESCAPE ? 0 : keyCode);
            }
            binding = null;
            return;
        }
        if (editingText instanceof TextSetting) {
            TextSetting text = (TextSetting) editingText;
            if (keyCode == Keyboard.KEY_RETURN) {
                text.submit();
                editingText = null;
                return;
            }
            if (keyCode == Keyboard.KEY_BACK && !text.getText().isEmpty()) {
                text.setText(text.getText().substring(0, text.getText().length() - 1));
                return;
            }
            if (keyCode == Keyboard.KEY_ESCAPE) {
                editingText = null;
                return;
            }
            if (Character.isDefined(typedChar) && !Character.isISOControl(typedChar)) {
                text.setText(text.getText() + typedChar);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawDropdown(int mouseX, int mouseY) {
        updateDraggedPanel(mouseX, mouseY);
        for (Panel panel : panels.values()) {
            drawPanel(panel, mouseX, mouseY, false);
        }
    }

    private void clickDropdown(int mouseX, int mouseY, int button) {
        for (Panel panel : panels.values()) {
            if (inside(mouseX, mouseY, panel.x, panel.y, 102, 15)) {
                if (button == 0) {
                    draggingPanel = true;
                    panel.dragging = true;
                    dragX = mouseX - panel.x;
                    dragY = mouseY - panel.y;
                }
                else if (button == 1) {
                    setPanelOpen(panel, !panel.open);
                }
                return;
            }
        }
        dispatchPanelClick(mouseX, mouseY, button, null);
    }

    private void drawDiscord(int mouseX, int mouseY) {
        windowWidth = Math.min(620, Math.max(380, width - 30));
        windowHeight = Math.min(390, Math.max(280, height - 30));
        windowX = (width - windowWidth) / 2;
        windowY = (height - windowHeight) / 2;
        int navWidth = 125;

        RenderUtils.drawRoundedRectangle(windowX, windowY, windowX + windowWidth, windowY + windowHeight, 6.0f, DISCORD_CONTENT);
        RenderUtils.drawRoundedRectangle(windowX, windowY, windowX + navWidth, windowY + windowHeight, 6.0f, DISCORD_NAV);
        drawRect(windowX + navWidth, windowY, windowX + windowWidth, windowY + 25, DISCORD_HEADER);

        FontRenderer title = NovolineFonts.bold(18);
        FontRenderer label = NovolineFonts.thin(16);
        title.drawString("NOVOLINE", windowX + 12, windowY + 8, TEXT, true);
        label.drawString(isMaterial() ? "MATERIAL" : "DISCORD", windowX + navWidth + 12, windowY + 8, MUTED, false);

        float rowY = windowY + 38;
        for (Module.category category : Module.category.values()) {
            boolean selected = category == discordCategory;
            boolean hover = inside(mouseX, mouseY, windowX + 7, rowY, navWidth - 14, 18);
            if (selected || hover) {
                RenderUtils.drawRoundedRectangle(windowX + 7, rowY, windowX + navWidth - 7, rowY + 18, 4.0f,
                        selected ? getAccent() : DISCORD_HEADER);
            }
            label.drawString(capitalize(category.name()), windowX + 14, rowY + 5, selected ? TEXT : MUTED, false);
            rowY += 21;
        }

        Panel panel = panels.get(discordCategory);
        if (panel == null) {
            return;
        }
        panel.x = windowX + navWidth + 8;
        panel.y = windowY + 30;
        panel.width = windowWidth - navWidth - 16;
        panel.open = true;
        panel.expand = 1.0f;
        drawPanel(panel, mouseX, mouseY, true);
    }

    private void clickDiscord(int mouseX, int mouseY, int button) {
        float rowY = windowY + 38;
        for (Module.category category : Module.category.values()) {
            if (inside(mouseX, mouseY, windowX + 7, rowY, 111, 18) && button == 0) {
                discordCategory = category;
                return;
            }
            rowY += 21;
        }
        dispatchPanelClick(mouseX, mouseY, button, panels.get(discordCategory));
    }

    private void updateDraggedPanel(int mouseX, int mouseY) {
        if (!draggingPanel && draggedSlider == null) {
            return;
        }
        for (Panel panel : panels.values()) {
            if (panel.dragging) {
                panel.x = mouseX - dragX;
                panel.y = mouseY - dragY;
            }
        }
        if (draggedSlider instanceof SliderSetting) {
            updateSlider((SliderSetting) draggedSlider, mouseX, findSettingBounds((SliderSetting) draggedSlider));
        }
    }

    private void drawPanel(Panel panel, int mouseX, int mouseY, boolean embedded) {
        FontRenderer header = NovolineFonts.sf(20);
        float panelWidth = embedded ? panel.width : 102.0f;
        panel.width = panelWidth;
        drawRect((int) panel.x - 1, (int) panel.y, (int) (panel.x + panelWidth + 1), (int) (panel.y + 15), DARK);
        header.drawString(capitalize(panel.category.name()), panel.x + 4, panel.y + 4, TEXT, true);
        NovolineFonts.icons(16).drawString(iconFor(panel.category), panel.x + panelWidth - 13, panel.y + 3, TEXT, false);
        float expand = embedded ? 1.0f : panelExpand(panel);
        if (expand <= 0.001f) {
            return;
        }

        List<Module> modules = modules(panel.category);
        float contentHeight = 0.0f;
        for (Module module : modules) {
            contentHeight += moduleHeight(module);
        }
        float fullViewportHeight = Math.min(contentHeight, contentViewportHeight(panel, embedded));
        panel.maxScroll = Math.max(0.0f, contentHeight - fullViewportHeight);
        panel.scroll = clamp(panel.scroll, 0.0f, panel.maxScroll);
        float viewportHeight = fullViewportHeight * expand;
        panel.viewportHeight = viewportHeight;

        float contentTop = panel.y + 15.0f;
        // Paint the entire viewport before individual rows so expanded settings never
        // reveal the screen behind the panel.
        drawRect((int) panel.x, (int) contentTop, (int) (panel.x + panelWidth),
                (int) (contentTop + viewportHeight), embedded ? DISCORD_CONTENT : SURFACE);
        if (viewportHeight <= 0.0f) {
            return;
        }

        org.lwjgl.opengl.GL11.glEnable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);
        RenderUtils.scissor(panel.x, contentTop, panelWidth, viewportHeight);
        float y = contentTop - panel.scroll;
        for (Module module : modules) {
            drawModule(panel, module, y, mouseX, mouseY, panelWidth);
            y += moduleHeight(module);
        }
        org.lwjgl.opengl.GL11.glDisable(org.lwjgl.opengl.GL11.GL_SCISSOR_TEST);

        if (panel.maxScroll > 0.0f) {
            float thumbHeight = Math.max(12.0f, viewportHeight * viewportHeight / contentHeight);
            float thumbY = contentTop + (viewportHeight - thumbHeight) * (panel.scroll / panel.maxScroll);
            drawRect((int) (panel.x + panelWidth - 2), (int) contentTop, (int) (panel.x + panelWidth),
                    (int) (contentTop + viewportHeight), 0x55000000);
            drawRect((int) (panel.x + panelWidth - 2), (int) thumbY, (int) (panel.x + panelWidth),
                    (int) (thumbY + thumbHeight), getAccent());
        }
    }

    private float drawModule(Panel panel, Module module, float y, int mouseX, int mouseY, float panelWidth) {
        boolean hover = inside(mouseX, mouseY, panel.x, y, panelWidth, 14);
        int active = getAccent();
        int rowColor = module.isEnabled() ? 0xFF35373C : (hover ? DARK : SURFACE);
        drawRect((int) panel.x, (int) y, (int) (panel.x + panelWidth), (int) (y + 14), rowColor);
        if (module.isEnabled()) {
            drawRect((int) panel.x, (int) y, (int) panel.x + 3, (int) (y + 14), active);
            NovolineFonts.thin(14).drawString("ON", panel.x + panelWidth - 27, y + 4, active, false);
        }
        NovolineFonts.sf(18).drawString(module.getName(), panel.x + (module.isEnabled() ? 5 : 3), y + 4,
                module.isEnabled() ? active : TEXT, true);
        if (!module.getSettings().isEmpty()) {
            NovolineFonts.icons(14).drawString(moduleOpen(module) ? "J" : "I", panel.x + panelWidth - 10, y + 4, TEXT, false);
        }
        if (moduleOpen(module)) {
            float settingY = y + 14;
            for (Setting setting : visibleSettings(module)) {
                drawSetting(panel, setting, settingY, panelWidth, mouseX, mouseY);
                settingY += settingHeight(setting);
            }
            return settingY - y;
        }
        return 14.0f;
    }

    private void drawSetting(Panel panel, Setting setting, float y, float panelWidth, int mouseX, int mouseY) {
        drawRect((int) panel.x, (int) y, (int) (panel.x + panelWidth), (int) (y + settingHeight(setting)), 0xFF282828);
        FontRenderer font = NovolineFonts.thin(16);
        if (setting instanceof DescriptionSetting) {
            font.drawString(((DescriptionSetting) setting).getDesc(), panel.x + 3, y + 4, MUTED, false);
            return;
        }
        if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            String value = slider.isString ? slider.getOptions()[(int) slider.getInput()] : Utils.asWholeNum(slider.getInput()) + slider.getSuffix();
            font.drawString(slider.getName() + ": " + value, panel.x + 3, y + 3, TEXT, false);
            float fraction = (float) ((slider.getInput() - slider.getMin()) / Math.max(0.0001, slider.getMax() - slider.getMin()));
            drawRect((int) panel.x + 3, (int) y + 12, (int) (panel.x + panelWidth - 3), (int) y + 14, 0xFF1D1D1D);
            drawRect((int) panel.x + 3, (int) y + 12, (int) (panel.x + 3 + (panelWidth - 6) * fraction), (int) y + 14, getAccent());
            return;
        }
        if (setting instanceof ButtonSetting) {
            ButtonSetting button = (ButtonSetting) setting;
            String suffix = button.isMethodButton ? "..." : (button.isToggled() ? "ON" : "OFF");
            font.drawString(button.getName() + ": " + suffix, panel.x + 3, y + 4, button.isToggled() ? getAccent() : TEXT, false);
            return;
        }
        if (setting instanceof KeySetting) {
            KeySetting key = (KeySetting) setting;
            String value = binding == setting ? "..." : getKeyName(key.getKey());
            font.drawString(key.getName() + ": " + value, panel.x + 3, y + 4, TEXT, false);
            return;
        }
        if (setting instanceof ColorSetting) {
            ColorSetting color = (ColorSetting) setting;
            font.drawString(color.getName(), panel.x + 3, y + 4, TEXT, false);
            drawRect((int) (panel.x + panelWidth - 18), (int) y + 3, (int) (panel.x + panelWidth - 4), (int) y + 12, color.getColor());
            return;
        }
        if (setting instanceof TextSetting) {
            TextSetting text = (TextSetting) setting;
            String value = text.getText().isEmpty() ? text.getPlaceholder() : text.getText();
            font.drawString(text.getName() + ": " + value + (editingText == setting ? "_" : ""), panel.x + 3, y + 4, TEXT, false);
            return;
        }
        if (setting instanceof GroupSetting) {
            GroupSetting group = (GroupSetting) setting;
            font.drawString(group.getName() + (group.isOpened() ? " -" : " +"), panel.x + 3, y + 4, TEXT, false);
            return;
        }
        font.drawString(setting.getName(), panel.x + 3, y + 4, MUTED, false);
    }

    private void dispatchPanelClick(int mouseX, int mouseY, int button, Panel only) {
        List<Panel> source = new ArrayList<Panel>();
        if (only != null) source.add(only); else source.addAll(panels.values());
        for (Panel panel : source) {
            if (!panel.open) continue;
            float contentTop = panel.y + 15.0f;
            if (!inside(mouseX, mouseY, panel.x, contentTop, panel.width, panel.viewportHeight)) {
                continue;
            }
            float y = panel.y + 15 - panel.scroll;
            for (Module module : modules(panel.category)) {
                float h = moduleHeight(module);
                if (inside(mouseX, mouseY, panel.x, y, panel.width, 14)) {
                    if (button == 0 && module.canBeEnabled()) module.toggle();
                    else if (button == 1 && !module.getSettings().isEmpty()) setModuleOpen(module, !moduleOpen(module));
                    return;
                }
                if (moduleOpen(module)) {
                    float settingY = y + 14;
                    for (Setting setting : visibleSettings(module)) {
                        float settingHeight = settingHeight(setting);
                        if (inside(mouseX, mouseY, panel.x, settingY, panel.width, settingHeight)) {
                            clickSetting(setting, mouseX, settingY, panel.width, button);
                            return;
                        }
                        settingY += settingHeight;
                    }
                }
                y += h;
            }
        }
    }

    private void clickSetting(Setting setting, int mouseX, float y, float panelWidth, int button) {
        if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            if (slider.isString) {
                int next = (int) slider.getInput() + (button == 1 ? -1 : 1);
                if (next < 0) next = slider.getOptions().length - 1;
                if (next >= slider.getOptions().length) next = 0;
                slider.setValueWithEvent(next);
            } else if (button == 0) {
                draggedSlider = slider;
                updateSlider(slider, mouseX, new float[]{0, y, panelWidth});
            }
        }
        else if (setting instanceof ButtonSetting && button == 0) {
            ButtonSetting value = (ButtonSetting) setting;
            if (value.isMethodButton) value.runMethod(); else value.toggle();
        }
        else if (setting instanceof KeySetting && button == 0) {
            binding = setting;
        }
        else if (setting instanceof TextSetting && button == 0) {
            editingText = setting;
        }
        else if (setting instanceof GroupSetting && button == 0) {
            GroupSetting group = (GroupSetting) setting;
            group.setOpened(!group.isOpened());
        }
        else if (setting instanceof ColorSetting && button == 0) {
            ColorSetting color = (ColorSetting) setting;
            color.setHue((mouseX % 100) * 3.6f);
        }
    }

    private void updateSlider(SliderSetting slider, int mouseX, float[] bounds) {
        float panelX = bounds[0];
        float panelWidth = bounds[2];
        if (panelWidth <= 0.0f) return;
        double fraction = Math.max(0.0, Math.min(1.0, (mouseX - panelX - 3.0) / (panelWidth - 6.0)));
        slider.setValueWithEvent(slider.getMin() + (slider.getMax() - slider.getMin()) * fraction);
    }

    private float[] findSettingBounds(Setting target) {
        for (Panel panel : panels.values()) {
            float y = panel.y + 15 - panel.scroll;
            for (Module module : modules(panel.category)) {
                if (moduleOpen(module)) {
                    float settingY = y + 14;
                    for (Setting setting : visibleSettings(module)) {
                        if (setting == target) return new float[]{panel.x, settingY, panel.width};
                        settingY += settingHeight(setting);
                    }
                }
                y += moduleHeight(module);
            }
        }
        return new float[]{0, 0, 0};
    }

    private void rebuildPanels() {
        for (Panel panel : panels.values()) {
            panel.scroll = 0.0f;
            panel.maxScroll = 0.0f;
            panel.expand = panel.open ? 1.0f : 0.0f;
            panel.animationStart = 0L;
        }
    }

    private List<Module> modules(Module.category category) {
        return new ArrayList<Module>(Raven.getModuleManager().inCategory(category));
    }

    private List<Setting> visibleSettings(Module module) {
        List<Setting> result = new ArrayList<Setting>();
        for (Setting setting : module.getSettings()) {
            if (!setting.visible) {
                continue;
            }
            GroupSetting group = settingGroup(setting);
            if (group == null || group.isOpened()) {
                result.add(setting);
            }
        }
        return result;
    }

    private GroupSetting settingGroup(Setting setting) {
        if (setting instanceof SliderSetting) return ((SliderSetting) setting).groupSetting;
        if (setting instanceof ButtonSetting) return ((ButtonSetting) setting).group;
        if (setting instanceof KeySetting) return ((KeySetting) setting).group;
        if (setting instanceof TextSetting) return ((TextSetting) setting).group;
        return null;
    }

    private String getKeyName(int key) {
        if (key == 1069) return "MScrollUp";
        if (key == 1070) return "MScrollDown";
        if (key >= 1000) return "M" + (key - 1000);
        if (key < 0 || key > 255) return "UNKNOWN";
        String name = Keyboard.getKeyName(key);
        return name == null ? "NONE" : name;
    }

    private float moduleHeight(Module module) {
        if (!moduleOpen(module)) return 14.0f;
        float value = 14.0f;
        for (Setting setting : visibleSettings(module)) value += settingHeight(setting);
        return value;
    }

    private float settingHeight(Setting setting) {
        return setting instanceof SliderSetting ? 16.0f : 15.0f;
    }

    private boolean moduleOpen(Module module) { return moduleState(module).open; }
    private void setModuleOpen(Module module, boolean value) { moduleState(module).open = value; }
    private ModuleState moduleState(Module module) { return states.computeIfAbsent(module, ignored -> new ModuleState()); }
    private final Map<Module, ModuleState> states = new java.util.IdentityHashMap<Module, ModuleState>();

    private int getAccent() {
        // HUD's public color API returns RGB by design; GUI rectangles require alpha.
        return 0xFF000000 | (keystrokesmod.module.impl.render.HUD.getHudColor(0.0) & 0xFFFFFF);
    }
    private void setPanelOpen(Panel panel, boolean open) {
        if (panel.open == open) {
            return;
        }
        panel.open = open;
        panel.animationFrom = panel.expand;
        panel.animationTo = open ? 1.0f : 0.0f;
        panel.animationStart = System.currentTimeMillis();
        if (!open) {
            panel.scroll = 0.0f;
        }
    }
    private float panelExpand(Panel panel) {
        if (panel.animationStart == 0L) {
            return panel.expand = panel.open ? 1.0f : 0.0f;
        }
        float progress = clamp((System.currentTimeMillis() - panel.animationStart) / 180.0f, 0.0f, 1.0f);
        // Ease-out keeps the first part responsive while avoiding a hard stop.
        float eased = 1.0f - (float) Math.pow(1.0f - progress, 3.0);
        panel.expand = panel.animationFrom + (panel.animationTo - panel.animationFrom) * eased;
        if (progress >= 1.0f) {
            panel.animationStart = 0L;
            panel.expand = panel.animationTo;
        }
        return panel.expand;
    }
    private Panel scrollTarget(int mouseX, int mouseY) {
        if (isDiscord()) {
            Panel selected = panels.get(discordCategory);
            return selected != null && inside(mouseX, mouseY, selected.x, selected.y + 15.0f,
                    selected.width, selected.viewportHeight) ? selected : null;
        }
        for (Panel panel : panels.values()) {
            if (panel.open && inside(mouseX, mouseY, panel.x, panel.y + 15.0f,
                    panel.width, panel.viewportHeight)) {
                return panel;
            }
        }
        return null;
    }
    private float contentViewportHeight(Panel panel, boolean embedded) {
        return Math.max(0.0f, embedded ? windowHeight - 45.0f : height - panel.y - 15.0f);
    }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private boolean isDiscord() { return Gui.novolineDesign != null && (int) Gui.novolineDesign.getInput() != 0; }
    private boolean isMaterial() { return Gui.novolineDesign != null && (int) Gui.novolineDesign.getInput() == 2; }
    private static boolean inside(float mouseX, float mouseY, float x, float y, float width, float height) { return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height; }
    private static String capitalize(String input) { return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1).toLowerCase(Locale.ROOT); }
    private static String iconFor(Module.category category) { return "" + (char) ('A' + category.ordinal() % 20); }
    private float renderScale(ScaledResolution resolution) { return width <= 0 ? 1.0f : (float) resolution.getScaledWidth() / width; }

    private static final class Panel {
        private final Module.category category;
        private float x, y, width = 100.0f, scroll, maxScroll, viewportHeight;
        private float expand, animationFrom, animationTo;
        private long animationStart;
        private boolean open, dragging;
        private Panel(Module.category category, float x, float y) { this.category = category; this.x = x; this.y = y; }
    }
    private static final class ModuleState { private boolean open; }
}
