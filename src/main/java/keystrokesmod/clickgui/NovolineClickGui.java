package keystrokesmod.clickgui;

import keystrokesmod.Raven;
import keystrokesmod.module.Module;
import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.module.setting.Setting;
import keystrokesmod.module.setting.impl.BlockListSetting;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.ColorSetting;
import keystrokesmod.module.setting.impl.DescriptionSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.InventoryItemListSetting;
import keystrokesmod.module.setting.impl.KeySetting;
import keystrokesmod.module.setting.impl.PlayerListSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.module.setting.impl.StringListSetting;
import keystrokesmod.module.setting.impl.TextSetting;
import keystrokesmod.novoline.font.NovolineFonts;
import keystrokesmod.novoline.font.api.FontRenderer;
import keystrokesmod.utility.PlayerRelationsManager;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * NightHeron's Dropdown and Discord/Material ClickGUIs adapted to Raven's module
 * and setting model. Dropdown is the default and keeps the original inline,
 * second-level module expansion used by NightHeron.
 */
public final class NovolineClickGui extends ClickGui {
    private static final int NAV = 45;
    private static final int MODULES = 105;
    private static final int MIN_SETTINGS = 190;
    private static final int MIN_HEIGHT = 300;
    private static final int HEADER = 21;

    private static final int DISCORD_NAV = 0xFF202225;
    private static final int DISCORD_CHANNELS = 0xFF2F3136;
    private static final int DISCORD_CONTENT = 0xFF36393E;
    private static final int MATERIAL_NAV = 0xFF1D1E22;
    private static final int MATERIAL_CHANNELS = 0xFF16171A;
    private static final int MATERIAL_CONTENT = 0xFF202225;
    private static final int DIVIDER = 0xFF202225;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF868386;

    private final Map<Module.category, Float> moduleScroll = new EnumMap<Module.category, Float>(Module.category.class);
    private final Map<Module, Float> settingScroll = new IdentityHashMap<Module, Float>();
    private final Map<ColorSetting, ColorMode> colorModes = new IdentityHashMap<ColorSetting, ColorMode>();
    private final Map<Module.category, DropdownPanel> dropdownPanels = new EnumMap<Module.category, DropdownPanel>(Module.category.class);
    private final Map<Module, DropdownModuleState> dropdownStates = new IdentityHashMap<Module, DropdownModuleState>();

    private Module.category selectedCategory = Module.category.combat;
    private Module selectedModule;
    private Module bindingModule;
    private Setting activeSetting;
    private SliderSetting openCombo;
    private boolean dragging;
    private boolean resizing;
    private int dragOffsetX;
    private int dragOffsetY;
    private int resizeOffsetX;
    private int resizeOffsetY;
    private int x = 100;
    private int y = 100;
    private int settingsWidth = MIN_SETTINGS;
    private int windowHeight = MIN_HEIGHT;
    private boolean positioned;
    private DropdownPanel draggedDropdownPanel;
    private Module activeDropdownModule;

    public NovolineClickGui() {
        super();
        for (Module.category category : Module.category.values()) {
            moduleScroll.put(category, 0.0F);
            dropdownPanels.put(category, new DropdownPanel(category, 20 + category.ordinal() * 110.0F, 10.0F));
        }
    }

    @Override
    public void initGui() {
        super.initGui();
        if (!positioned) {
            x = Math.max(8, (width - totalWidth()) / 2);
            y = Math.max(14, (height - windowHeight) / 2);
            positioned = true;
        }
        constrainWindow();
        if (!modules(selectedCategory).contains(selectedModule)) {
            selectedModule = null;
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        ScaledResolution resolution = new ScaledResolution(mc);
        double scale = renderScale(resolution);
        int logicalMouseX = (int) Math.floor(mouseX / scale);
        int logicalMouseY = (int) Math.floor(mouseY / scale);

        if (dropdown()) {
            drawDropdown(logicalMouseX, logicalMouseY);
            return;
        }
        updateWindowDrag(logicalMouseX, logicalMouseY);

        drawRect(0, 0, resolution.getScaledWidth(), resolution.getScaledHeight(), 0x78000000);
        GlStateManager.pushMatrix();
        GlStateManager.scale(scale, scale, 1.0D);

        drawWindow(logicalMouseX, logicalMouseY);
        drawTabs(logicalMouseX, logicalMouseY);
        drawModules(logicalMouseX, logicalMouseY);
        drawSettings(logicalMouseX, logicalMouseY);

        GlStateManager.popMatrix();
    }

    private void drawDropdown(int mouseX, int mouseY) {
        ScaledResolution resolution = new ScaledResolution(mc);
        drawRect(0, 0, resolution.getScaledWidth(), resolution.getScaledHeight(), 0x78000000);
        GlStateManager.pushMatrix();
        double scale = renderScale(resolution);
        GlStateManager.scale(scale, scale, 1.0D);

        if (draggedDropdownPanel != null && draggedDropdownPanel.dragging) {
            draggedDropdownPanel.x = mouseX + draggedDropdownPanel.dragOffsetX;
            draggedDropdownPanel.y = mouseY + draggedDropdownPanel.dragOffsetY;
            draggedDropdownPanel.x = clamp(draggedDropdownPanel.x, 0, Math.max(0, width - 101));
            draggedDropdownPanel.y = clamp(draggedDropdownPanel.y, 0, Math.max(0, height - 15));
        }

        for (DropdownPanel panel : dropdownPanels.values()) drawDropdownPanel(panel, mouseX, mouseY);
        GlStateManager.popMatrix();
    }

    private void drawDropdownPanel(DropdownPanel panel, int mouseX, int mouseY) {
        drawRect((int) panel.x - 1, (int) panel.y, (int) panel.x + 101, (int) panel.y + 15, 0xFF1D1D1D);
        FontRenderer header = NovolineFonts.sf(20);
        header.drawString(title(panel.category.name()), panel.x + 4, panel.y + 4, TEXT, true);
        NovolineFonts.icons(24).drawString(tabIcon(panel.category), panel.x + 88, panel.y + 5, TEXT, false);

        long now = System.currentTimeMillis();
        float elapsed = panel.lastAnimationFrame == 0L ? 0.0F : Math.min(0.05F, (now - panel.lastAnimationFrame) / 1000.0F);
        panel.lastAnimationFrame = now;
        float targetExpand = panel.open ? 1.0F : 0.0F;
        if (elapsed > 0.0F) {
            float interpolation = 1.0F - (float) Math.exp(-14.0F * elapsed);
            panel.expand += (targetExpand - panel.expand) * interpolation;
        }
        if (Math.abs(targetExpand - panel.expand) < 0.002F) panel.expand = targetExpand;
        if (panel.expand <= 0.001F) return;

        float contentHeight = 0.0F;
        for (Module module : modules(panel.category)) {
            DropdownModuleState state = dropdownState(module);
            float target = dropdownModuleHeight(module, state.open);
            state.height += (target - state.height) * 0.28F;
            if (Math.abs(target - state.height) < 0.2F) state.height = target;
            contentHeight += state.height;
        }

        float viewportHeight = Math.min(contentHeight, Math.max(0.0F, height - panel.y - 15.0F));
        panel.maxScroll = Math.max(0.0F, contentHeight - viewportHeight);
        panel.scroll = clamp(panel.scroll, 0.0F, panel.maxScroll);
        float visibleHeight = viewportHeight * easeOutCubic(panel.expand);
        panel.visibleHeight = visibleHeight;
        drawRect((int) panel.x - 1, (int) panel.y + 15, (int) panel.x + 101,
                (int) (panel.y + 16 + visibleHeight), 0xFF1D1D1D);
        RenderUtils.scissorPushGui(panel.x - 1, panel.y + 15, 102, visibleHeight + 1);

        float moduleY = panel.y + 15 - panel.scroll;
        for (Module module : modules(panel.category)) {
            DropdownModuleState state = dropdownState(module);
            drawRect((int) panel.x - 1, (int) moduleY, (int) panel.x + 101, (int) (moduleY + state.height), 0xFF1D1D1D);
            drawDropdownModule(panel, module, state, moduleY, mouseX, mouseY);
            moduleY += state.height;
        }
        drawRect((int) panel.x - 1, (int) moduleY, (int) panel.x + 101, (int) moduleY + 1, 0xFF1D1D1D);
        RenderUtils.scissorPop();

    }

    private void drawDropdownModule(DropdownPanel panel, Module module, DropdownModuleState state,
                                    float moduleY, int mouseX, int mouseY) {
        boolean hovered = inside(mouseX, mouseY, panel.x, moduleY, 101, 14);
        int row = module.isEnabled() ? accent() : hovered ? 0xFF1D1D1D : 0xFF282828;
        drawRect((int) panel.x, (int) moduleY, (int) panel.x + 100, (int) moduleY + 14, row);
        FontRenderer font = NovolineFonts.sf(18);
        String name = bindingModule == module ? "Press a key..." : module.getName();
        font.drawString(font.trimStringToWidth(name, module.getSettings().isEmpty() ? 96 : 86), panel.x + 2, moduleY + 4, TEXT, true);
        if (!module.getSettings().isEmpty()) {
            float arrowTarget = state.open ? 1.0F : 0.0F;
            state.arrow += (arrowTarget - state.arrow) * 0.25F;
            if (Math.abs(arrowTarget - state.arrow) < 0.002F) state.arrow = arrowTarget;
            drawDropdownArrow(panel.x + 93.5F, moduleY + 7.5F, state.arrow);
        }

        if (state.height <= 14.2F) return;
        RenderUtils.scissorPushGui(panel.x, moduleY + 14, 100, state.height - 14);
        float settingY = moduleY + 14;
        for (Setting setting : visibleSettings(module)) {
            drawDropdownSetting(panel, module, setting, settingY, mouseX, mouseY);
            settingY += dropdownSettingHeight(setting);
        }
        RenderUtils.scissorPop();
    }

    private void drawDropdownSetting(DropdownPanel panel, Module module, Setting setting,
                                     float sy, int mouseX, int mouseY) {
        float left = panel.x + 1;
        float right = panel.x + 99;
        FontRenderer font = NovolineFonts.sf(18);
        drawRect((int) panel.x, (int) sy, (int) panel.x + 100, (int) (sy + dropdownSettingHeight(setting)), 0xFF282828);

        if (setting instanceof DescriptionSetting) {
            font.drawString(font.trimStringToWidth(((DescriptionSetting) setting).getDesc(), 94), panel.x + 3, sy + 5, 0xFFB9BBBE, false);
            return;
        }
        if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            if (slider.isString) {
                String[] options = slider.getOptions();
                int index = (int) clamp((float) slider.getInput(), 0, options.length - 1);
                font.drawString(font.trimStringToWidth(slider.getName(), 55), panel.x + 3, sy + 5, TEXT, true);
                String value = options[index].toUpperCase(Locale.ROOT);
                font.drawString(font.trimStringToWidth(value, 40, true), panel.x + 97 - font.stringWidth(font.trimStringToWidth(value, 40, true)), sy + 5, TEXT, true);
            } else {
                double range = Math.max(0.00001D, slider.getMax() - slider.getMin());
                float percent = (float) ((slider.getInput() - slider.getMin()) / range);
                drawRect((int) left, (int) sy + 3, (int) right, (int) sy + 14, 0x32000000);
                drawRect((int) left, (int) sy + 3, (int) (left + 98 * percent), (int) sy + 14, accent());
                String value = slider.getName() + " " + Utils.asWholeNum(slider.getInput()) + slider.getSuffix();
                font.drawString(font.trimStringToWidth(value, 94), panel.x + 4, sy + 5, TEXT, true);
                if (activeSetting == setting && activeDropdownModule == module && Mouse.isButtonDown(0)) {
                    updateDropdownSlider(slider, mouseX, left, module);
                }
            }
            return;
        }
        if (setting instanceof ButtonSetting) {
            ButtonSetting button = (ButtonSetting) setting;
            font.drawString(font.trimStringToWidth(setting.getName(), 82), panel.x + 4, sy + 5, 0xFFE3E3E3, true);
            drawRect((int) panel.x + 89, (int) sy + 4, (int) panel.x + 99, (int) sy + 14, 0x32000000);
            if (button.isMethodButton) font.drawCenteredString("+", panel.x + 94, sy + 5, accent());
            else if (button.isToggled()) drawCheck(panel.x + 91, sy + 6, accent());
            return;
        }
        if (setting instanceof TextSetting) {
            TextSetting text = (TextSetting) setting;
            font.drawString(setting.getName(), panel.x + 4, sy + 2, 0xFFE3E3E3, false);
            String value = text.getText().isEmpty() ? text.getPlaceholder() : text.getText();
            value = NovolineFonts.sf(16).trimStringToWidth(value, 88, true);
            NovolineFonts.sf(16).drawString(value + (activeSetting == setting && caretVisible() ? "|" : ""), panel.x + 6, sy + 11,
                    text.getText().isEmpty() ? 0x64FFFFFF : TEXT, false);
            drawRect((int) panel.x + 6, (int) sy + 18, (int) panel.x + 94, (int) sy + 19,
                    activeSetting == setting ? accent() : 0xFFC3C3C3);
            return;
        }
        if (setting instanceof KeySetting) {
            font.drawString(setting.getName(), panel.x + 3, sy + 5, TEXT, false);
            String key = "[" + (activeSetting == setting ? ".." : keyName(((KeySetting) setting).getKey())) + "]";
            font.drawString(key, panel.x + 97 - font.stringWidth(key), sy + 5, MUTED, false);
            return;
        }
        if (setting instanceof ColorSetting) {
            ColorSetting color = (ColorSetting) setting;
            ColorMode mode = colorModes.containsKey(color) ? colorModes.get(color) : ColorMode.HUE;
            float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
            for (int i = 0; i < 98; i++) {
                float value = i / 97.0F;
                int rgb = mode == ColorMode.HUE ? Color.HSBtoRGB(value, hsb[1], hsb[2])
                        : mode == ColorMode.SATURATION ? Color.HSBtoRGB(hsb[0], value, hsb[2])
                        : Color.HSBtoRGB(hsb[0], hsb[1], value);
                drawRect((int) left + i, (int) sy + 3, (int) left + i + 1, (int) sy + 14, rgb);
            }
            font.drawString(setting.getName(), panel.x + 4, sy + 5, TEXT, true);
            float marker = mode == ColorMode.HUE ? hsb[0] : mode == ColorMode.SATURATION ? hsb[1] : hsb[2];
            drawRect((int) (left + marker * 97), (int) sy + 3, (int) (left + marker * 97) + 1, (int) sy + 14, TEXT);
            if (activeSetting == setting && activeDropdownModule == module && Mouse.isButtonDown(0)) {
                updateDropdownColor(color, mode, mouseX, left);
            }
            return;
        }
        if (setting instanceof GroupSetting) {
            GroupSetting group = (GroupSetting) setting;
            font.drawString(setting.getName(), panel.x + 4, sy + 5, TEXT, true);
            font.drawString(group.isOpened() ? "-" : "+", panel.x + 91, sy + 5, group.isOpened() ? accent() : TEXT, false);
            return;
        }
        String summary = listSummary(setting);
        font.drawString(font.trimStringToWidth(setting.getName(), 62), panel.x + 4, sy + 5, TEXT, false);
        if (summary != null) font.drawString(summary, panel.x + 97 - font.stringWidth(summary), sy + 5, MUTED, false);
    }

    private void clickDropdown(int mouseX, int mouseY, int button) {
        List<DropdownPanel> panels = new ArrayList<DropdownPanel>(dropdownPanels.values());
        for (int i = panels.size() - 1; i >= 0; i--) {
            DropdownPanel panel = panels.get(i);
            if (inside(mouseX, mouseY, panel.x, panel.y, 101, 15)) {
                if (button == 0 && draggedDropdownPanel == null) {
                    panel.dragging = true;
                    panel.dragOffsetX = panel.x - mouseX;
                    panel.dragOffsetY = panel.y - mouseY;
                    draggedDropdownPanel = panel;
                } else if (button == 1) {
                    panel.open = !panel.open;
                    if (!panel.open) panel.scroll = 0.0F;
                }
                return;
            }
        }

        for (DropdownPanel panel : panels) {
            if (!panel.open || !inside(mouseX, mouseY, panel.x, panel.y + 15, 101, panel.visibleHeight)) continue;
            float moduleY = panel.y + 15 - panel.scroll;
            for (Module module : modules(panel.category)) {
                DropdownModuleState state = dropdownState(module);
                if (inside(mouseX, mouseY, panel.x, moduleY, 101, 14)) {
                    if (button == 0 && module.canBeEnabled()) module.toggle();
                    else if (button == 1 && !module.getSettings().isEmpty()) state.open = !state.open;
                    else if (button == 2) bindingModule = module;
                    activeSetting = null;
                    activeDropdownModule = null;
                    return;
                }
                if (state.open) {
                    float sy = moduleY + 14;
                    for (Setting setting : visibleSettings(module)) {
                        float sh = dropdownSettingHeight(setting);
                        if (inside(mouseX, mouseY, panel.x, sy, 100, sh)) {
                            handleDropdownSettingClick(panel, module, setting, mouseX, button);
                            return;
                        }
                        sy += sh;
                    }
                }
                moduleY += state.height;
            }
        }
        if (button == 0) {
            activeSetting = null;
            activeDropdownModule = null;
        }
    }

    private void handleDropdownSettingClick(DropdownPanel panel, Module module, Setting setting, int mouseX, int button) {
        if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            if (slider.isString) cycleCombo(slider, button == 1 ? -1 : 1);
            else if (button == 0) {
                activeSetting = setting;
                activeDropdownModule = module;
                selectedModule = module;
                updateDropdownSlider(slider, mouseX, panel.x + 1, module);
            }
        } else if (setting instanceof ButtonSetting && button == 0) {
            ButtonSetting value = (ButtonSetting) setting;
            if (value.isMethodButton) value.runMethod(); else value.toggle();
            module.guiButtonToggled(value);
        } else if ((setting instanceof TextSetting || setting instanceof KeySetting) && button == 0) {
            activeSetting = setting;
            activeDropdownModule = module;
        } else if (setting instanceof ColorSetting) {
            ColorSetting color = (ColorSetting) setting;
            ColorMode mode = colorModes.containsKey(color) ? colorModes.get(color) : ColorMode.HUE;
            if (button == 1) colorModes.put(color, ColorMode.values()[(mode.ordinal() + 1) % ColorMode.values().length]);
            else if (button == 0) {
                activeSetting = setting;
                activeDropdownModule = module;
                updateDropdownColor(color, mode, mouseX, panel.x + 1);
            }
        } else if (setting instanceof GroupSetting && button == 0) {
            GroupSetting group = (GroupSetting) setting;
            group.setOpened(!group.isOpened());
        }
    }

    private float dropdownModuleHeight(Module module, boolean open) {
        if (!open) return 14.0F;
        float result = 17.0F;
        for (Setting setting : visibleSettings(module)) result += dropdownSettingHeight(setting);
        return result;
    }

    private float dropdownSettingHeight(Setting setting) {
        return setting instanceof TextSetting ? 22.0F : 15.0F;
    }

    private void updateDropdownSlider(SliderSetting slider, int mouseX, float left, Module module) {
        double fraction = clamp((mouseX - left) / 98.0F, 0, 1);
        slider.setValueWithEvent(slider.getMin() + (slider.getMax() - slider.getMin()) * fraction);
        module.onSlide(slider);
    }

    private void updateDropdownColor(ColorSetting color, ColorMode mode, int mouseX, float left) {
        float value = clamp((mouseX - left) / 98.0F, 0, 1);
        if (mode == ColorMode.HUE) color.setHue(value * 360.0F);
        else if (mode == ColorMode.SATURATION) color.setSaturation(value);
        else color.setBrightness(value);
    }

    private DropdownModuleState dropdownState(Module module) {
        DropdownModuleState state = dropdownStates.get(module);
        if (state == null) {
            state = new DropdownModuleState();
            dropdownStates.put(module, state);
        }
        return state;
    }

    private void drawWindow(int mouseX, int mouseY) {
        int right = x + totalWidth();
        int channels = channelColor();
        int content = contentColor();

        RenderUtils.drawRoundedRectangle(x, y - 10, right, y + 5, 8.0F, navColor());
        RenderUtils.drawRoundedRectangle(x, y, x + NAV, y + windowHeight, material() ? 9.0F : 6.0F, navColor());
        RenderUtils.drawRoundedRectangle(x + NAV + MODULES, y, right, y + windowHeight, 4.0F, content);
        drawRect(x + NAV, y, x + NAV + MODULES, y + windowHeight, channels);
        drawRect(x + NAV, y + 20, right, y + HEADER, material() ? 0xFF111214 : DIVIDER);

        RenderUtils.drawRoundedRectangle(x + 7, y + 5, x + 37, y + 35, 15.0F, DISCORD_CONTENT);
        NovolineFonts.icons(35).drawCenteredString("?", x + 22, y + 14, TEXT);

        if (inside(mouseX, mouseY, x + 7, y + 5, 30, 30)) {
            String label = material() ? "Material GUI" : "NightHeron";
            FontRenderer thin = NovolineFonts.thin(16);
            float tooltipX = x - thin.stringWidth(label) - 12;
            RenderUtils.drawRoundedRectangle(tooltipX, y + 14, x - 5, y + 24, 5.0F, 0xFF2F2F2F);
            thin.drawString(label, tooltipX + 3, y + 16, TEXT, false);
        }

        if (selectedModule == null) {
            String line1 = "<------------";
            String line2 = "Select a module";
            mc.fontRendererObj.drawStringWithShadow(line1, x + 59, y + 65, 0xFF6A7179);
            mc.fontRendererObj.drawStringWithShadow(line2, x + NAV + MODULES + 18, y + 72, 0xFF6A7179);
            NovolineFonts.thin(16).drawCenteredString(material() ? "M A T E R I A L I N E" : "N I G H T H E R O N",
                    x + NAV + MODULES + settingsWidth / 2.0F, y + 7, 0xFFB9BBBE);
        }

        if (inside(mouseX, mouseY, right - 7, y + windowHeight - 7, 7, 7)) {
            drawRect(right - 5, y + windowHeight - 1, right, y + windowHeight, accent());
            drawRect(right - 1, y + windowHeight - 5, right, y + windowHeight, accent());
        }
    }

    private void drawTabs(int mouseX, int mouseY) {
        Module.category[] categories = Module.category.values();
        float spacing = categories.length <= 1 ? 35.0F : Math.min(35.0F, (windowHeight - 82.0F) / (categories.length - 1));
        FontRenderer iconFont = NovolineFonts.icons(35);
        FontRenderer tooltipFont = NovolineFonts.thin(16);

        for (int i = 0; i < categories.length; i++) {
            Module.category category = categories[i];
            float centerY = y + 63.0F + i * spacing;
            boolean selected = category == selectedCategory;
            boolean hovered = inside(mouseX, mouseY, x + 7, centerY - 15, 30, 30);
            if (selected || hovered) {
                drawRect(x, (int) centerY - (selected ? 10 : 5), x + 2, (int) centerY + (selected ? 10 : 5), TEXT);
            }
            drawCircle(x + 22, centerY, 15.0F, DISCORD_CONTENT);
            iconFont.drawCenteredString(tabIcon(category), x + 22, centerY - 6, hovered ? accent() : TEXT);

            if (hovered) {
                String name = title(category.name());
                float tx = x - tooltipFont.stringWidth(name) - 12;
                RenderUtils.drawRoundedRectangle(tx, centerY - 6, x - 5, centerY + 5, 5.0F, 0xFF2F2F2F);
                tooltipFont.drawString(name, tx + 3, centerY - 3, TEXT, false);
            }
        }
    }

    private void drawModules(int mouseX, int mouseY) {
        FontRenderer titleFont = NovolineFonts.thin(20);
        FontRenderer moduleFont = NovolineFonts.thin(20);
        FontRenderer hashFont = NovolineFonts.bold(26);
        titleFont.drawString(tabIcon(selectedCategory), x + 50, y + 7, TEXT, false);
        titleFont.drawString(title(selectedCategory.name()), x + 63, y + 7, TEXT, false);

        List<Module> modules = modules(selectedCategory);
        float scroll = clampModuleScroll(modules, moduleScroll.get(selectedCategory));
        moduleScroll.put(selectedCategory, scroll);
        float rowY = y + 30.0F + scroll;

        scissor(x + NAV, y + HEADER, MODULES, windowHeight - HEADER);
        for (Module module : modules) {
            boolean hovered = inside(mouseX, mouseY, x + NAV, rowY - 4, MODULES, 18);
            if (hovered || module == selectedModule) {
                drawRect(x + NAV, (int) rowY - 4, x + NAV + MODULES, (int) rowY + 14, contentColor());
            }
            hashFont.drawString("#", x + 50, rowY - 2, 0xFF605D60, false);
            String name = bindingModule == module ? "Press a key..." : module.getName();
            moduleFont.drawString(moduleFont.trimStringToWidth(name, 87), x + 63, rowY,
                    module.isEnabled() ? 0xFFE3DFE3 : MUTED, false);
            rowY += 18.0F;
        }
        endScissor();

        if (selectedModule != null) {
            titleFont.drawString(selectedModule.getName() + " Settings", x + NAV + MODULES + 15, y + 7, TEXT, false);
        }
    }

    private void drawSettings(int mouseX, int mouseY) {
        if (selectedModule == null) return;
        List<Setting> settings = visibleSettings(selectedModule);
        if (settings.isEmpty()) {
            String text = "NO SETTINGS ;(";
            mc.fontRendererObj.drawStringWithShadow(text,
                    x + NAV + MODULES + (settingsWidth - mc.fontRendererObj.getStringWidth(text)) / 2.0F,
                    y + (windowHeight - mc.fontRendererObj.FONT_HEIGHT) / 2.0F, 0xFF6A7179);
            return;
        }

        float scroll = clampSettingScroll(settings, settingScroll.containsKey(selectedModule) ? settingScroll.get(selectedModule) : 0.0F);
        settingScroll.put(selectedModule, scroll);
        float settingY = y + 32.0F + scroll;
        scissor(x + NAV + MODULES, y + HEADER, settingsWidth, windowHeight - HEADER);
        for (Setting setting : settings) {
            drawSetting(setting, settingY, mouseX, mouseY);
            settingY += settingHeight(setting);
        }
        endScissor();

        // Open combo boxes are drawn last, just like NightHeron's setting sort.
        if (openCombo != null && settings.contains(openCombo)) {
            float comboY = settingScreenY(settings, openCombo, scroll);
            drawComboOptions(openCombo, comboY, mouseX, mouseY);
        }
    }

    private void drawSetting(Setting setting, float sy, int mouseX, int mouseY) {
        FontRenderer label = NovolineFonts.thin(17);
        FontRenderer valueFont = NovolineFonts.thin(16);
        float left = x + NAV + MODULES + 10.0F;
        float right = x + NAV + MODULES + settingsWidth - 10.0F;
        float controlLeft = right - 70.0F;
        label.drawString(setting instanceof DescriptionSetting ? ((DescriptionSetting) setting).getDesc() : setting.getName(),
                left, sy, setting instanceof DescriptionSetting ? 0xFFB9BBBE : TEXT, false);

        if (setting instanceof DescriptionSetting) return;

        if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            if (slider.isString) {
                bordered(controlLeft, sy - 2, right, sy + 8, inside(mouseX, mouseY, controlLeft, sy - 2, 70, 10) ? accent() : 0x64000000);
                String[] options = slider.getOptions();
                int index = (int) clamp((float) slider.getInput(), 0, options.length - 1);
                valueFont.drawCenteredString(options[index], controlLeft + 35, sy, TEXT);
            } else {
                double range = Math.max(0.00001D, slider.getMax() - slider.getMin());
                float percent = (float) ((slider.getInput() - slider.getMin()) / range);
                bordered(controlLeft, sy + 2, right, sy + 4, 0x64000000);
                drawRect((int) controlLeft, (int) sy + 2, (int) (controlLeft + 70 * percent), (int) sy + 4, accent());
                drawCircle(controlLeft + 70 * percent, sy + 3, 2, TEXT);
                String value = Utils.asWholeNum(slider.getInput()) + slider.getSuffix();
                NovolineFonts.thin(12).drawCenteredString(value, controlLeft + 70 * percent, sy - 5, TEXT);
                if (activeSetting == slider && Mouse.isButtonDown(0)) updateSlider(slider, mouseX, controlLeft);
            }
            return;
        }

        if (setting instanceof ButtonSetting) {
            ButtonSetting button = (ButtonSetting) setting;
            bordered(right - 10, sy - 2, right, sy + 8, 0x64000000);
            if (button.isMethodButton) {
                valueFont.drawCenteredString("+", right - 5, sy, accent());
            } else if (button.isToggled()) {
                drawCheck(right - 8, sy + 2, accent());
            }
            return;
        }

        if (setting instanceof TextSetting) {
            TextSetting text = (TextSetting) setting;
            bordered(controlLeft, sy - 2, right, sy + 8, activeSetting == setting ? accent() : 0x64000000);
            String value = text.getText().isEmpty() ? text.getPlaceholder() : text.getText();
            value = valueFont.trimStringToWidth(value, 64, true);
            valueFont.drawString(value + (activeSetting == setting && caretVisible() ? "|" : ""), controlLeft + 2, sy,
                    text.getText().isEmpty() ? 0x64FFFFFF : TEXT, false);
            return;
        }

        if (setting instanceof KeySetting) {
            bordered(controlLeft, sy - 2, right, sy + 8, activeSetting == setting ? accent() : 0x64000000);
            valueFont.drawCenteredString(activeSetting == setting ? "Press a key" : keyName(((KeySetting) setting).getKey()), controlLeft + 35, sy, TEXT);
            return;
        }

        if (setting instanceof ColorSetting) {
            ColorSetting color = (ColorSetting) setting;
            ColorMode mode = colorModes.containsKey(color) ? colorModes.get(color) : ColorMode.HUE;
            float[] hsb = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null);
            for (int i = 0; i < 70; i++) {
                float value = i / 69.0F;
                int rgb = mode == ColorMode.HUE ? Color.HSBtoRGB(value, hsb[1], hsb[2])
                        : mode == ColorMode.SATURATION ? Color.HSBtoRGB(hsb[0], value, hsb[2])
                        : Color.HSBtoRGB(hsb[0], hsb[1], value);
                drawRect((int) controlLeft + i, (int) sy - 2, (int) controlLeft + i + 1, (int) sy + 8, rgb);
            }
            float marker = mode == ColorMode.HUE ? hsb[0] : mode == ColorMode.SATURATION ? hsb[1] : hsb[2];
            drawRect((int) (controlLeft + marker * 69), (int) sy - 2, (int) (controlLeft + marker * 69) + 1, (int) sy + 8, 0xFF000000);
            if (activeSetting == setting && Mouse.isButtonDown(0)) updateColor(color, mode, mouseX, controlLeft);
            return;
        }

        if (setting instanceof GroupSetting) {
            GroupSetting group = (GroupSetting) setting;
            valueFont.drawString(group.isOpened() ? "-" : "+", right - 8, sy, group.isOpened() ? accent() : TEXT, false);
            return;
        }

        String summary = listSummary(setting);
        if (summary != null) {
            bordered(controlLeft, sy - 2, right, sy + 8, 0x64000000);
            valueFont.drawCenteredString(summary, controlLeft + 35, sy, TEXT);
        }
    }

    private void drawComboOptions(SliderSetting setting, float sy, int mouseX, int mouseY) {
        String[] options = setting.getOptions();
        float right = x + NAV + MODULES + settingsWidth - 10.0F;
        float left = right - 70.0F;
        float bottom = sy + 10 + options.length * 11;
        scissor(x + NAV + MODULES, y + HEADER, settingsWidth, windowHeight - HEADER);
        bordered(left, sy + 10, right, bottom, 0x64000000);
        for (int i = 0; i < options.length; i++) {
            boolean hovered = inside(mouseX, mouseY, left, sy + 10 + i * 11, 70, 11);
            NovolineFonts.thin(16).drawCenteredString(options[i], left + 35, sy + 13 + i * 11,
                    (int) setting.getInput() == i || hovered ? accent() : TEXT);
        }
        endScissor();
    }

    @Override
    public void mouseClicked(int mouseX, int mouseY, int button) throws IOException {
        if (dropdown()) {
            clickDropdown(mouseX, mouseY, button);
            return;
        }
        int right = x + totalWidth();
        if (button == 0 && inside(mouseX, mouseY, x, y - 10, totalWidth(), 30)) {
            dragging = true;
            dragOffsetX = x - mouseX;
            dragOffsetY = y - mouseY;
            return;
        }
        if (button == 0 && inside(mouseX, mouseY, right - 7, y + windowHeight - 7, 7, 7)) {
            resizing = true;
            resizeOffsetX = settingsWidth - mouseX;
            resizeOffsetY = windowHeight - mouseY;
            return;
        }

        Module.category tab = tabAt(mouseX, mouseY);
        if (tab != null && button == 0) {
            selectedCategory = tab;
            selectedModule = null;
            activeSetting = null;
            openCombo = null;
            return;
        }

        Module module = moduleAt(mouseX, mouseY);
        if (module != null) {
            if (button == 0 && module.canBeEnabled()) module.toggle();
            else if (button == 1) {
                selectedModule = selectedModule == module ? null : module;
                activeSetting = null;
                openCombo = null;
            } else if (button == 2) {
                bindingModule = module;
            }
            return;
        }

        if (selectedModule != null) clickSettingAt(mouseX, mouseY, button);
    }

    private void clickSettingAt(int mouseX, int mouseY, int button) {
        List<Setting> settings = visibleSettings(selectedModule);
        float scroll = settingScroll.containsKey(selectedModule) ? settingScroll.get(selectedModule) : 0.0F;

        if (openCombo != null) {
            float comboY = settingScreenY(settings, openCombo, scroll);
            String[] options = openCombo.getOptions();
            float right = x + totalWidth() - 10.0F;
            float left = right - 70.0F;
            for (int i = 0; i < options.length; i++) {
                if (inside(mouseX, mouseY, left, comboY + 10 + i * 11, 70, 11)) {
                    openCombo.setValueWithEvent(i);
                    openCombo = null;
                    return;
                }
            }
        }

        float sy = y + 32 + scroll;
        for (Setting setting : settings) {
            float height = settingHeight(setting);
            if (inside(mouseX, mouseY, x + NAV + MODULES, sy - 3, settingsWidth, height)) {
                handleSettingClick(setting, mouseX, sy, button);
                return;
            }
            sy += height;
        }
        if (button == 0) activeSetting = null;
    }

    private void handleSettingClick(Setting setting, int mouseX, float sy, int button) {
        float controlLeft = x + totalWidth() - 80.0F;
        if (setting instanceof SliderSetting) {
            SliderSetting slider = (SliderSetting) setting;
            if (slider.isString) {
                if (button == 0) openCombo = openCombo == slider ? null : slider;
                else if (button == 1) cycleCombo(slider, -1);
            } else if (button == 0) {
                activeSetting = slider;
                updateSlider(slider, mouseX, controlLeft);
            }
        } else if (setting instanceof ButtonSetting && button == 0) {
            ButtonSetting value = (ButtonSetting) setting;
            if (value.isMethodButton) value.runMethod(); else value.toggle();
            selectedModule.guiButtonToggled(value);
        } else if (setting instanceof TextSetting && button == 0) {
            activeSetting = setting;
        } else if (setting instanceof KeySetting && button == 0) {
            activeSetting = setting;
        } else if (setting instanceof ColorSetting) {
            ColorSetting color = (ColorSetting) setting;
            if (button == 1) {
                ColorMode mode = colorModes.containsKey(color) ? colorModes.get(color) : ColorMode.HUE;
                colorModes.put(color, ColorMode.values()[(mode.ordinal() + 1) % ColorMode.values().length]);
            } else if (button == 0) {
                activeSetting = setting;
                updateColor(color, colorModes.containsKey(color) ? colorModes.get(color) : ColorMode.HUE, mouseX, controlLeft);
            }
        } else if (setting instanceof GroupSetting && button == 0) {
            GroupSetting group = (GroupSetting) setting;
            group.setOpened(!group.isOpened());
        }
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int state) {
        if (state == 0) {
            dragging = false;
            resizing = false;
            draggedDropdownPanel = null;
            for (DropdownPanel panel : dropdownPanels.values()) panel.dragging = false;
            if (activeSetting instanceof SliderSetting || activeSetting instanceof ColorSetting) activeSetting = null;
            activeDropdownModule = null;
        }
    }

    @Override
    public void handleMouseInput() throws IOException {
        int wheel = Mouse.getEventDWheel();
        super.handleMouseInput();
        if (wheel == 0) return;
        int mouseX = Mouse.getEventX() * width / mc.displayWidth;
        int mouseY = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        if (dropdown()) {
            for (DropdownPanel panel : dropdownPanels.values()) {
                if (panel.open && panel.maxScroll > 0.0F
                        && inside(mouseX, mouseY, panel.x, panel.y + 15, 101, panel.visibleHeight)) {
                    float step = Math.max(2.0F, Gui.scrollSpeed == null ? 20.0F : (float) Gui.scrollSpeed.getInput());
                    panel.scroll = clamp(panel.scroll - Math.signum(wheel) * step, 0.0F, panel.maxScroll);
                    return;
                }
            }
            return;
        }
        float direction = wheel > 0 ? 12.0F : -12.0F;
        if (inside(mouseX, mouseY, x + NAV, y + HEADER, MODULES, windowHeight - HEADER)) {
            moduleScroll.put(selectedCategory, moduleScroll.get(selectedCategory) + direction);
        } else if (selectedModule != null && inside(mouseX, mouseY, x + NAV + MODULES, y + HEADER, settingsWidth, windowHeight - HEADER)) {
            settingScroll.put(selectedModule, (settingScroll.containsKey(selectedModule) ? settingScroll.get(selectedModule) : 0.0F) + direction);
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (bindingModule != null) {
            bindingModule.setBind(keyCode == Keyboard.KEY_ESCAPE ? 0 : keyCode);
            bindingModule = null;
            return;
        }
        if (activeSetting instanceof KeySetting) {
            ((KeySetting) activeSetting).setKey(keyCode == Keyboard.KEY_ESCAPE ? 0 : keyCode);
            activeSetting = null;
            return;
        }
        if (activeSetting instanceof TextSetting) {
            TextSetting text = (TextSetting) activeSetting;
            if (keyCode == Keyboard.KEY_ESCAPE) {
                activeSetting = null;
            } else if (keyCode == Keyboard.KEY_RETURN) {
                text.submit();
                activeSetting = null;
            } else if (keyCode == Keyboard.KEY_BACK && !text.getText().isEmpty()) {
                text.setText(text.getText().substring(0, text.getText().length() - 1));
            } else if (Character.isDefined(typedChar) && !Character.isISOControl(typedChar)) {
                text.setText(text.getText() + typedChar);
            }
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE && openCombo != null) {
            openCombo = null;
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) mc.displayGuiScreen(null);
    }

    @Override
    public void onGuiClosed() {
        dragging = false;
        resizing = false;
        bindingModule = null;
        activeSetting = null;
        openCombo = null;
        draggedDropdownPanel = null;
        activeDropdownModule = null;
        for (DropdownPanel panel : dropdownPanels.values()) panel.dragging = false;
        super.onGuiClosed();
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void updateWindowDrag(int mouseX, int mouseY) {
        if (dragging) {
            x = dragOffsetX + mouseX;
            y = dragOffsetY + mouseY;
            constrainWindow();
        }
        if (resizing) {
            settingsWidth = Math.max(MIN_SETTINGS, resizeOffsetX + mouseX);
            windowHeight = Math.max(MIN_HEIGHT, resizeOffsetY + mouseY);
            settingsWidth = Math.min(settingsWidth, Math.max(MIN_SETTINGS, width - x - NAV - MODULES));
            windowHeight = Math.min(windowHeight, Math.max(MIN_HEIGHT, height - y));
        }
    }

    private void constrainWindow() {
        x = Math.max(0, Math.min(x, Math.max(0, width - totalWidth())));
        y = Math.max(10, Math.min(y, Math.max(10, height - windowHeight)));
    }

    private Module.category tabAt(int mouseX, int mouseY) {
        Module.category[] categories = Module.category.values();
        float spacing = categories.length <= 1 ? 35.0F : Math.min(35.0F, (windowHeight - 82.0F) / (categories.length - 1));
        for (int i = 0; i < categories.length; i++) {
            if (inside(mouseX, mouseY, x + 7, y + 63 + i * spacing - 15, 30, 30)) return categories[i];
        }
        return null;
    }

    private Module moduleAt(int mouseX, int mouseY) {
        if (!inside(mouseX, mouseY, x + NAV, y + HEADER, MODULES, windowHeight - HEADER)) return null;
        float rowY = y + 30 + moduleScroll.get(selectedCategory);
        for (Module module : modules(selectedCategory)) {
            if (inside(mouseX, mouseY, x + NAV, rowY - 4, MODULES, 18)) return module;
            rowY += 18;
        }
        return null;
    }

    private List<Module> modules(Module.category category) {
        return new ArrayList<Module>(Raven.getModuleManager().inCategory(category));
    }

    private List<Setting> visibleSettings(Module module) {
        List<Setting> result = new ArrayList<Setting>();
        for (Setting setting : module.getSettings()) {
            if (!setting.visible) continue;
            GroupSetting group = groupOf(setting);
            if (group == null || group.isOpened()) result.add(setting);
        }
        return result;
    }

    private GroupSetting groupOf(Setting setting) {
        if (setting instanceof SliderSetting) return ((SliderSetting) setting).groupSetting;
        if (setting instanceof ButtonSetting) return ((ButtonSetting) setting).group;
        if (setting instanceof KeySetting) return ((KeySetting) setting).group;
        if (setting instanceof TextSetting) return ((TextSetting) setting).group;
        if (setting instanceof ColorSetting) return ((ColorSetting) setting).groupSetting;
        if (setting instanceof BlockListSetting) return ((BlockListSetting) setting).group;
        if (setting instanceof StringListSetting) return ((StringListSetting) setting).group;
        if (setting instanceof PlayerListSetting) return ((PlayerListSetting) setting).group;
        return null;
    }

    private float clampModuleScroll(List<Module> modules, float scroll) {
        float content = modules.size() * 18.0F;
        float viewport = windowHeight - 30.0F;
        return clamp(scroll, Math.min(0, viewport - content), 0);
    }

    private float clampSettingScroll(List<Setting> settings, float scroll) {
        float content = 0;
        for (Setting setting : settings) content += settingHeight(setting);
        float viewport = windowHeight - 32.0F;
        return clamp(scroll, Math.min(0, viewport - content), 0);
    }

    private float settingScreenY(List<Setting> settings, Setting target, float scroll) {
        float sy = y + 32 + scroll;
        for (Setting setting : settings) {
            if (setting == target) return sy;
            sy += settingHeight(setting);
        }
        return sy;
    }

    private float settingHeight(Setting setting) {
        return setting instanceof DescriptionSetting ? 14.0F : 18.0F;
    }

    private void updateSlider(SliderSetting slider, int mouseX, float left) {
        double fraction = clamp((mouseX - left) / 70.0F, 0, 1);
        slider.setValueWithEvent(slider.getMin() + (slider.getMax() - slider.getMin()) * fraction);
        if (selectedModule != null) selectedModule.onSlide(slider);
    }

    private void cycleCombo(SliderSetting setting, int amount) {
        int length = setting.getOptions().length;
        int next = ((int) setting.getInput() + amount) % length;
        if (next < 0) next += length;
        setting.setValueWithEvent(next);
    }

    private void updateColor(ColorSetting color, ColorMode mode, int mouseX, float left) {
        float value = clamp((mouseX - left) / 70.0F, 0, 1);
        if (mode == ColorMode.HUE) color.setHue(value * 360.0F);
        else if (mode == ColorMode.SATURATION) color.setSaturation(value);
        else color.setBrightness(value);
    }

    private String listSummary(Setting setting) {
        if (setting instanceof InventoryItemListSetting) return ((InventoryItemListSetting) setting).getItems().size() + " items";
        if (setting instanceof BlockListSetting) return ((BlockListSetting) setting).getBlocks().size() + " entries";
        if (setting instanceof StringListSetting) return ((StringListSetting) setting).getEntries().size() + " entries";
        if (setting instanceof PlayerListSetting) return ((PlayerListSetting) setting).getEntries().size() + " players";
        return null;
    }

    private void bordered(float left, float top, float right, float bottom, int border) {
        drawRect((int) left - 1, (int) top - 1, (int) right + 1, (int) bottom + 1, border);
        drawRect((int) left, (int) top, (int) right, (int) bottom, channelColor());
    }

    private void drawCheck(float cx, float cy, int color) {
        Color checkColor = new Color(color, true);
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glColor4f(checkColor.getRed() / 255.0F, checkColor.getGreen() / 255.0F,
                checkColor.getBlue() / 255.0F, checkColor.getAlpha() / 255.0F);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINE_STRIP);
        GL11.glVertex2f(cx, cy + 2.0F);
        GL11.glVertex2f(cx + 2.0F, cy + 4.0F);
        GL11.glVertex2f(cx + 5.5F, cy);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    private void drawCircle(float cx, float cy, float radius, int color) {
        GL11.glPushMatrix();
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        Color c = new Color(color, true);
        GL11.glColor4f(c.getRed() / 255.0F, c.getGreen() / 255.0F, c.getBlue() / 255.0F, c.getAlpha() / 255.0F);
        GL11.glBegin(GL11.GL_TRIANGLE_FAN);
        GL11.glVertex2f(cx, cy);
        for (int i = 0; i <= 36; i++) {
            double angle = Math.PI * 2.0D * i / 36.0D;
            GL11.glVertex2d(cx + Math.sin(angle) * radius, cy + Math.cos(angle) * radius);
        }
        GL11.glEnd();
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    private void drawDropdownArrow(float cx, float cy, float expansion) {
        GL11.glPushMatrix();
        GL11.glTranslatef(cx, cy, 0.0F);
        GL11.glRotatef(90.0F * clamp(expansion, 0.0F, 1.0F), 0.0F, 0.0F, 1.0F);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glHint(GL11.GL_LINE_SMOOTH_HINT, GL11.GL_NICEST);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        GL11.glLineWidth(1.0F);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(-2.0F, -2.5F);
        GL11.glVertex2f(1.0F, 0.0F);
        GL11.glVertex2f(1.0F, 0.0F);
        GL11.glVertex2f(-2.0F, 2.5F);
        GL11.glEnd();
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glPopMatrix();
    }

    private void scissor(float sx, float sy, float sw, float sh) {
        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        RenderUtils.scissor(sx, sy, sw, sh);
    }

    private void endScissor() {
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
    }

    private int totalWidth() { return NAV + MODULES + settingsWidth; }
    private int navColor() { return material() ? MATERIAL_NAV : DISCORD_NAV; }
    private int channelColor() { return material() ? MATERIAL_CHANNELS : DISCORD_CHANNELS; }
    private int contentColor() { return material() ? MATERIAL_CONTENT : DISCORD_CONTENT; }
    private boolean dropdown() { return Gui.novolineDesign == null || (int) Gui.novolineDesign.getInput() == 0; }
    private boolean material() { return Gui.novolineDesign != null && (int) Gui.novolineDesign.getInput() == 2; }
    private int accent() { return 0xFF000000 | (keystrokesmod.module.impl.render.HUD.getHudColor(0.0D) & 0xFFFFFF); }
    private boolean caretVisible() { return System.currentTimeMillis() / 500L % 2L == 0L; }
    private double renderScale(ScaledResolution resolution) { return width <= 0 ? 1.0D : (double) resolution.getScaledWidth() / width; }
    private static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float easeOutCubic(float value) {
        float inverse = 1.0F - clamp(value, 0.0F, 1.0F);
        return 1.0F - inverse * inverse * inverse;
    }
    private static boolean inside(float mx, float my, float x, float y, float w, float h) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }
    private static String title(String name) { return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1).toLowerCase(Locale.ROOT); }

    private static String tabIcon(Module.category category) {
        switch (category) {
            case combat: return "D";
            case movement: return "A";
            case player: return "B";
            case visuals: return "C";
            case misc: return "F";
            case exploits: return "G";
            case configs: return "H";
            case scripts: return "E";
            default: return "?";
        }
    }

    private String keyName(int key) {
        if (key == 0) return "NONE";
        if (key == 1069) return "MScrollUp";
        if (key == 1070) return "MScrollDown";
        if (key >= 1000) return "M" + (key - 1000);
        String name = Keyboard.getKeyName(key);
        return name == null ? "NONE" : name;
    }

    private static final class DropdownPanel {
        private final Module.category category;
        private float x;
        private float y;
        private float dragOffsetX;
        private float dragOffsetY;
        private boolean open;
        private boolean dragging;
        private float expand;
        private float visibleHeight;
        private float scroll;
        private float maxScroll;
        private long lastAnimationFrame;

        private DropdownPanel(Module.category category, float x, float y) {
            this.category = category;
            this.x = x;
            this.y = y;
        }
    }

    private static final class DropdownModuleState {
        private boolean open;
        private float height = 14.0F;
        private float arrow;
    }

    private enum ColorMode { HUE, SATURATION, BRIGHTNESS }
}
