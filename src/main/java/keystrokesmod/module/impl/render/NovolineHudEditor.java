package keystrokesmod.module.impl.render;

import keystrokesmod.novoline.font.NovolineFonts;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import org.lwjgl.input.Keyboard;

import java.io.IOException;

/** Drag handles for the three independently positioned Novoline HUD panels. */
public final class NovolineHudEditor extends GuiScreen {
    private Element dragged;
    private int lastMouseX;
    private int lastMouseY;

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xA0000000);
        NovolineHudRenderer.drawEditorPreview();
        drawHandle("MODULE LIST", HUD.posX, HUD.posY, 120, 44, Element.MODULES);
        drawHandle("INVENTORY", (float) HUD.novolineInventoryX.getInput(), (float) HUD.novolineInventoryY.getInput(),
                NovolineHudRenderer.getInventoryWidth(), NovolineHudRenderer.getInventoryHeight(), Element.INVENTORY);
        drawHandle("TARGETS", (float) HUD.novolineTargetsX.getInput(), (float) HUD.novolineTargetsY.getInput() - 13,
                NovolineHudRenderer.getTargetsWidth(), NovolineHudRenderer.getTargetsHeight() + 13, Element.TARGETS);
        NovolineFonts.bold(18).drawString("NOVOLINE HUD EDITOR", 12, 12, 0xFFFFFFFF, true);
        NovolineFonts.thin(16).drawString("Drag a highlighted panel. ESC closes the editor.", 12, 31, 0xFFB9BBBE, false);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) throws IOException {
        super.mouseClicked(mouseX, mouseY, mouseButton);
        if (mouseButton != 0) return;
        dragged = at(mouseX, mouseY);
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    @Override
    protected void mouseClickMove(int mouseX, int mouseY, int button, long elapsed) {
        super.mouseClickMove(mouseX, mouseY, button, elapsed);
        if (button != 0 || dragged == null) return;
        int deltaX = mouseX - lastMouseX;
        int deltaY = mouseY - lastMouseY;
        switch (dragged) {
            case MODULES:
                HUD.setAbsolutePosition(HUD.posX + deltaX, HUD.posY + deltaY);
                break;
            case INVENTORY:
                HUD.novolineInventoryX.setValue(HUD.novolineInventoryX.getInput() + deltaX);
                HUD.novolineInventoryY.setValue(HUD.novolineInventoryY.getInput() + deltaY);
                break;
            case TARGETS:
                HUD.novolineTargetsX.setValue(HUD.novolineTargetsX.getInput() + deltaX);
                HUD.novolineTargetsY.setValue(HUD.novolineTargetsY.getInput() + deltaY);
                break;
            default:
                break;
        }
        lastMouseX = mouseX;
        lastMouseY = mouseY;
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        super.mouseReleased(mouseX, mouseY, state);
        if (state == 0) dragged = null;
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) throws IOException {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void drawHandle(String label, float x, float y, float elementWidth, float elementHeight, Element element) {
        int color = dragged == element ? 0xFFFFFFFF : 0xFF8A8AFF;
        drawRect((int) x - 2, (int) y - 2, (int) (x + elementWidth) + 2, (int) (y + elementHeight) + 2, color);
        NovolineFonts.thin(14).drawString(label, x + 3, y - 12, color, true);
    }

    private Element at(int mouseX, int mouseY) {
        if (contains(mouseX, mouseY, HUD.posX, HUD.posY, 120, 44)) return Element.MODULES;
        if (contains(mouseX, mouseY, HUD.novolineInventoryX.getInput(), HUD.novolineInventoryY.getInput(),
                NovolineHudRenderer.getInventoryWidth(), NovolineHudRenderer.getInventoryHeight())) return Element.INVENTORY;
        if (contains(mouseX, mouseY, HUD.novolineTargetsX.getInput(), HUD.novolineTargetsY.getInput() - 13,
                NovolineHudRenderer.getTargetsWidth(), NovolineHudRenderer.getTargetsHeight() + 13)) return Element.TARGETS;
        return null;
    }

    private static boolean contains(int mouseX, int mouseY, double x, double y, double width, double height) {
        return mouseX >= x - 2 && mouseX <= x + width + 2 && mouseY >= y - 2 && mouseY <= y + height + 2;
    }

    private enum Element { MODULES, INVENTORY, TARGETS }
}
