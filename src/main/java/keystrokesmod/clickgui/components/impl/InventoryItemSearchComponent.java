package keystrokesmod.clickgui.components.impl;

import keystrokesmod.module.impl.client.Gui;
import keystrokesmod.module.setting.impl.InventoryItemListSetting;
import keystrokesmod.utility.ItemSearchIndex;
import keystrokesmod.utility.RenderUtils;
import keystrokesmod.utility.font.RavenFontRenderer;
import net.minecraft.client.Minecraft;
import org.lwjgl.input.Keyboard;

import java.util.ArrayList;
import java.util.List;

public class InventoryItemSearchComponent extends AbstractItemSearchComponent<InventoryItemListSetting> {
    private static final float SLOT_BUTTON_WIDTH = 9f;
    private static final float SLOT_VALUE_WIDTH = 11f;
    private static final float SLOT_STEPPER_WIDTH = SLOT_BUTTON_WIDTH * 2f + SLOT_VALUE_WIDTH;
    private static final float SLOT_TEXT_Y_OFFSET = 0.5f;
    private static final float SLOT_BOX_GAP = 3f;
    private static final float DRAG_SCROLL_EDGE = 10f;
    private static final float DRAG_SCROLL_SPEED = 3f;

    private static final class InventorySelectedRowData extends SelectedRowData {
        final Integer assignedSlot;

        private InventorySelectedRowData(String storageId, String displayName, net.minecraft.item.ItemStack stack, List<net.minecraft.item.ItemStack> cyclingStacks, Integer assignedSlot) {
            super(storageId, displayName, stack, cyclingStacks);
            this.assignedSlot = assignedSlot;
        }
    }

    private List<InventorySelectedRowData> selectedRowsCache;
    private String listeningStorageId;
    private String draggingStorageId;
    private float dragGrabOffsetY;

    public InventoryItemSearchComponent(InventoryItemListSetting setting, ModuleComponent moduleComponent, float o) {
        super(setting, moduleComponent, o);
    }

    @Override
    protected int getSelectedEntryCount() {
        return setting.getItems().size();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY) {
        super.drawScreen(mouseX, mouseY);
        updateDragState();
    }

    @Override
    public void mouseReleased(int mouseX, int mouseY, int button) {
        if (button == 0) {
            draggingStorageId = null;
        }
    }

    @Override
    public void keyTyped(char typedChar, int keyCode) {
        if (!moduleComponent.isOpened) {
            return;
        }

        if (listeningStorageId != null) {
            if (keyCode == Keyboard.KEY_ESCAPE) {
                listeningStorageId = null;
                return;
            }

            int slot = getHotbarSlotForKey(keyCode);
            if (slot != -1) {
                setting.setAssignedSlot(listeningStorageId, slot);
                listeningStorageId = null;
                invalidateSelectedRows();
                markUnsaved();
            }
            return;
        }

        super.keyTyped(typedChar, keyCode);
    }

    @Override
    protected boolean hasAdditionalTextInputFocus() {
        return listeningStorageId != null;
    }

    @Override
    protected void clearAdditionalTextInputFocus() {
        listeningStorageId = null;
    }

    @Override
    protected void onDropdownClickHandled(int mouseX, int mouseY) {
        listeningStorageId = null;
    }

    @Override
    protected void onSearchFocusHandled(int mouseX, int mouseY) {
        listeningStorageId = null;
    }

    @Override
    protected void onOutsideClick(int mouseX, int mouseY, int button) {
        listeningStorageId = null;
    }

    @Override
    protected void renderSelectedRows(Layout layout, float offsetPx, int firstRow, int end) {
        List<String> items = setting.getItems();
        if (selectedRowsCache == null || selectedRowsCache.size() != items.size()) {
            selectedRowsCache = new ArrayList<InventorySelectedRowData>();
            for (String storageId : items) {
                List<ItemSearchIndex.ItemEntry> variants = ItemSearchIndex.isGroupedSelection(storageId)
                    ? ItemSearchIndex.getSelectionVariants(storageId)
                    : null;
                List<net.minecraft.item.ItemStack> cyclingStacks = null;
                if (variants != null && !variants.isEmpty()) {
                    cyclingStacks = new ArrayList<net.minecraft.item.ItemStack>();
                    for (ItemSearchIndex.ItemEntry variant : variants) {
                        cyclingStacks.add(variant.toItemStack());
                    }
                }
                selectedRowsCache.add(new InventorySelectedRowData(
                    storageId,
                    ItemSearchIndex.getDisplayName(storageId),
                    ItemSearchIndex.getItemStack(storageId),
                    cyclingStacks,
                    setting.getAssignedSlot(storageId)
                ));
            }
        }

        for (int i = firstRow; i < end; i++) {
            InventorySelectedRowData row = selectedRowsCache.get(i);
            float rowTop = getSelectedTop(layout) - offsetPx + i * ROW_HEIGHT;
            int bg = row.storageId.equals(draggingStorageId) ? 0xFF2A2A3C : ((i % 2 == 0) ? 0xFF1A1A2A : 0xFF1E1E2E);
            renderSelectedRow(row, layout.left, layout.right, rowTop, bg);
        }
    }

    @Override
    protected boolean handleSelectedEntryClick(int mouseX, int mouseY, Layout layout) {
        int rowIndex = getSelectedRowIndex(mouseX, mouseY, layout);
        if (rowIndex < 0) {
            draggingStorageId = null;
            return false;
        }

        String storageId = setting.getItems().get(rowIndex);
        float rowTop = getSelectedTop(layout) - selectedScrollAnim.getValue() + rowIndex * ROW_HEIGHT;
        if (isOverClose(mouseX, mouseY, rowTop, layout.right)) {
            setting.removeItem(storageId);
            invalidateSelectedRows();
            listeningStorageId = null;
            draggingStorageId = null;
            markUnsaved();
            clampSelectedScroll();
            updateDropdownAnimation();
            moduleComponent.updateSettingPositions();
            return true;
        }

        int slotControl = getSlotControl(mouseX, mouseY, rowTop, layout.right);
        if (slotControl != Integer.MIN_VALUE) {
            int currentSlot = setting.getAssignedSlot(storageId);
            if (slotControl < 0) {
                setting.setAssignedSlot(storageId, currentSlot <= 1 ? 9 : currentSlot - 1);
                listeningStorageId = null;
                invalidateSelectedRows();
                markUnsaved();
            }
            else if (slotControl > 0) {
                setting.setAssignedSlot(storageId, currentSlot >= 9 ? 1 : currentSlot + 1);
                listeningStorageId = null;
                invalidateSelectedRows();
                markUnsaved();
            }
            else {
                listeningStorageId = storageId;
            }
            draggingStorageId = null;
            return true;
        }

        draggingStorageId = storageId;
        dragGrabOffsetY = mouseY - rowTop;
        if (!isPointInSlotControl(mouseX, mouseY, layout)) {
            listeningStorageId = null;
        }
        return true;
    }

    @Override
    protected void invalidateSelectedRows() {
        selectedRowsCache = null;
    }

    @Override
    protected void onSearchStateReset() {
        listeningStorageId = null;
        draggingStorageId = null;
    }

    private void renderSelectedRow(InventorySelectedRowData row, float left, float right, float rowTop, int bgColor) {
        RenderUtils.drawRect(left, rowTop, right, rowTop + ROW_HEIGHT - 1f, bgColor);
        renderItemInRow(getPreviewStack(row), left + 2f, rowTop);

        float closeX = right - CLOSE_SIZE - CLOSE_PAD;
        float slotRight = closeX - SLOT_BOX_GAP;
        float slotLeft = slotRight - SLOT_STEPPER_WIDTH;
        String displayName = fitDisplayName(row.displayName, Math.max(0f, slotLeft - (left + 15f)));
        drawListRowText(displayName, left + 13f, rowTop, 0xFFCCCCCC);
        renderSlotStepper(row, slotLeft, slotRight, rowTop);
        renderCloseIcon(right, rowTop);
    }

    private void renderSlotStepper(InventorySelectedRowData row, float left, float right, float rowTop) {
        boolean listening = row.storageId.equals(listeningStorageId);
        float top = rowTop + 2f;
        float bottom = rowTop + ROW_HEIGHT - 3f;
        float valueLeft = left + SLOT_BUTTON_WIDTH;
        float plusLeft = valueLeft + SLOT_VALUE_WIDTH;
        renderSlotButton("-", left, valueLeft, top, bottom, 0xFF30303A);
        renderSlotButton(getSlotLabel(row.storageId), valueLeft, plusLeft, top, bottom, listening ? 0xFF35557A : 0xFF244966);
        renderSlotButton("+", plusLeft, right, top, bottom, 0xFF30303A);
    }

    private void renderSlotButton(String label, float left, float right, float top, float bottom, int fill) {
        RenderUtils.drawRect(left, top, right, bottom, 0xFF11141C);
        RenderUtils.drawRect(left + 1f, top + 1f, right - 1f, bottom - 1f, fill);
        RavenFontRenderer renderer = Gui.getClickGuiSettingFontRenderer();
        float textWidth = renderer.getStringWidth(label) * TEXT_SCALE;
        float textX = left + ((right - left) - textWidth) / 2f;
        float textY = centeredScaledTextY(top, bottom - top) + SLOT_TEXT_Y_OFFSET;
        drawScaledText(label, textX, textY, 0xFFE8EEF5);
    }

    private void updateDragState() {
        if (draggingStorageId == null || setting.getItems().isEmpty()) {
            return;
        }

        Layout layout = layout(true);
        float selectedTop = getSelectedTop(layout);
        float selectedHeight = getSelectedVisibleHeight();
        if (selectedHeight <= 0f) {
            draggingStorageId = null;
            return;
        }

        if (setting.getItems().size() > MAX_VISIBLE_SELECTED && lastMouseX >= layout.left && lastMouseX <= layout.right) {
            if (lastMouseY < selectedTop + DRAG_SCROLL_EDGE) {
                selectedScrollAnim.extend(DRAG_SCROLL_SPEED);
                clampSelectedScroll();
            }
            else if (lastMouseY > selectedTop + selectedHeight - DRAG_SCROLL_EDGE) {
                selectedScrollAnim.extend(-DRAG_SCROLL_SPEED);
                clampSelectedScroll();
            }
        }

        List<String> orderedItems = setting.getItems();
        int currentIndex = orderedItems.indexOf(draggingStorageId);
        if (currentIndex < 0) {
            draggingStorageId = null;
            return;
        }

        float draggedRowTop = lastMouseY - dragGrabOffsetY;
        float draggedRowCenter = draggedRowTop + ROW_HEIGHT / 2f;
        int desiredIndex = (int) Math.floor((draggedRowCenter - selectedTop + selectedScrollAnim.getValue()) / ROW_HEIGHT);
        desiredIndex = Math.max(0, Math.min(desiredIndex, orderedItems.size() - 1));
        if (desiredIndex != currentIndex) {
            setting.moveItem(draggingStorageId, desiredIndex);
            invalidateSelectedRows();
            markUnsaved();
        }
    }

    private int getSelectedRowIndex(int mouseX, int mouseY, Layout layout) {
        if (!isMouseOverSelectedList(mouseX, mouseY)) {
            return -1;
        }

        float selectedTop = getSelectedTop(layout);
        float offsetPx = selectedScrollAnim.getValue();
        int rowIndex = (int) ((mouseY - selectedTop + offsetPx) / ROW_HEIGHT);
        if (rowIndex < 0 || rowIndex >= setting.getItems().size()) {
            return -1;
        }

        float rowTop = selectedTop - offsetPx + rowIndex * ROW_HEIGHT;
        return mouseY >= rowTop && mouseY < rowTop + ROW_HEIGHT ? rowIndex : -1;
    }

    private boolean isPointInSlotControl(int mouseX, int mouseY, Layout layout) {
        int rowIndex = getSelectedRowIndex(mouseX, mouseY, layout);
        if (rowIndex < 0) {
            return false;
        }
        float rowTop = getSelectedTop(layout) - selectedScrollAnim.getValue() + rowIndex * ROW_HEIGHT;
        return getSlotControl(mouseX, mouseY, rowTop, layout.right) != Integer.MIN_VALUE;
    }

    private int getSlotControl(int mouseX, int mouseY, float rowTop, float right) {
        float slotRight = right - CLOSE_SIZE - CLOSE_PAD - SLOT_BOX_GAP;
        float slotLeft = slotRight - SLOT_STEPPER_WIDTH;
        if (mouseX < slotLeft || mouseX > slotRight || mouseY < rowTop + 1f || mouseY > rowTop + ROW_HEIGHT - 1f) {
            return Integer.MIN_VALUE;
        }
        if (mouseX < slotLeft + SLOT_BUTTON_WIDTH) {
            return -1;
        }
        return mouseX < slotLeft + SLOT_BUTTON_WIDTH + SLOT_VALUE_WIDTH ? 0 : 1;
    }

    private String getSlotLabel(String storageId) {
        if (storageId != null && storageId.equals(listeningStorageId)) {
            return "*";
        }

        Integer assignedSlot = setting.getAssignedSlot(storageId);
        return Integer.toString(assignedSlot != null ? assignedSlot : 1);
    }

    private String fitDisplayName(String displayName, float maxWidth) {
        String text = displayName != null ? displayName : "";
        RavenFontRenderer renderer = Gui.getClickGuiSettingFontRenderer();
        if (renderer.getStringWidth(text) * LIST_ROW_TEXT_SCALE <= maxWidth) {
            return text;
        }

        String suffix = "...";
        while (!text.isEmpty() && renderer.getStringWidth(text + suffix) * LIST_ROW_TEXT_SCALE > maxWidth) {
            text = text.substring(0, text.length() - 1);
        }
        return text.isEmpty() ? "" : text + suffix;
    }

    private int getHotbarSlotForKey(int keyCode) {
        Minecraft minecraft = Minecraft.getMinecraft();
        for (int i = 0; i < minecraft.gameSettings.keyBindsHotbar.length; i++) {
            if (keyCode == minecraft.gameSettings.keyBindsHotbar[i].getKeyCode()) {
                return i + 1;
            }
        }
        return -1;
    }
}
