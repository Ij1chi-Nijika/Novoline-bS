package keystrokesmod.module.impl.other;

import keystrokesmod.event.SendPacketEvent;
import keystrokesmod.module.Module;
import keystrokesmod.module.ModuleManager;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.PacketUtils;
import keystrokesmod.utility.Utils;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemSword;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.C0DPacketCloseWindow;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C16PacketClientStatus;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class Disabler extends Module {
    private static final String[] MODES = new String[]{"WatchDog"};
    private static final Random RANDOM = new Random();

    private final SliderSetting mode;
    private final ButtonSetting inventory;
    private final ButtonSetting c09;
    private final SliderSetting secondSwordSlot;

    private final List<Packet<?>> inventoryPackets = new ArrayList<>();
    private static boolean c09Warned;

    public Disabler() {
        super("Disabler", category.exploits);
        this.registerSetting(mode = new SliderSetting("Mode", 0, MODES));
        this.registerSetting(inventory = new ButtonSetting("Inventory", false));
        this.registerSetting(c09 = new ButtonSetting("C09", false));
        this.registerSetting(secondSwordSlot = new SliderSetting("Second sword slot", 2.0, 1.0, 9.0, 1.0));
    }

    @Override
    public String getInfo() {
        return MODES[(int) mode.getInput()];
    }

    @Override
    public void onEnable() {
        if (inventory.isToggled()) {
            Utils.sendMessage("You can use Vanilla InvMove and Silent InvManager now.");
        }
        resetStates();
    }

    @Override
    public void onDisable() {
        if (inventory.isToggled()) {
            flushInventoryPackets();
        }
        resetStates();
    }

    @Override
    public void guiUpdate() {
        secondSwordSlot.setVisible(c09.isToggled(), this);
    }

    @SubscribeEvent
    public void onSendPacket(SendPacketEvent event) {
        if (!inventory.isToggled() || !Utils.nullCheck() || checkCompass()) return;

        Packet<?> packet = event.getPacket();
        if (packet instanceof C16PacketClientStatus || packet instanceof C0EPacketClickWindow) {
            event.setCanceled(true);
            inventoryPackets.add(packet);
        } else if (packet instanceof C0DPacketCloseWindow) {
            flushInventoryPackets();
        }
    }

    private boolean checkCompass() {
        if (!Utils.nullCheck()) return false;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack != null && stack.getUnlocalizedName().toLowerCase().contains("compass")) {
                return true;
            }
        }
        return false;
    }

    private void flushInventoryPackets() {
        if (Utils.nullCheck()) {
            for (Packet<?> packet : inventoryPackets) {
                PacketUtils.sendPacketNoEvent(packet);
            }
        }
        inventoryPackets.clear();
    }

    private void resetStates() {
        inventoryPackets.clear();
        c09Warned = false;
    }

    public static int getC09TargetSlot() {
        Disabler disabler = ModuleManager.disabler;
        if (disabler == null || !disabler.isEnabled() || !disabler.c09.isToggled() || !Utils.nullCheck()) {
            c09Warned = false;
            return -1;
        }

        int preferredSlot = (int) disabler.secondSwordSlot.getInput() - 1;
        if (preferredSlot < 0 || preferredSlot > 8 || preferredSlot == mc.thePlayer.inventory.currentItem) {
            return -1;
        }

        ItemStack stack = mc.thePlayer.inventory.getStackInSlot(preferredSlot);
        if (stack != null && stack.getItem() instanceof ItemSword) {
            c09Warned = false;
            return preferredSlot;
        }

        if (!c09Warned) {
            int mainSword = findMainSwordSlot();
            if (findSecondSwordSlot(mainSword) == -1) {
                c09Warned = true;
                Utils.sendMessage("&cNo second sword in inventory; C09 swaps will use a fallback slot.");
            }
        }
        return -1;
    }

    public static int getSwapSlot() {
        int target = getC09TargetSlot();
        if (target >= 0) return target;
        if (!Utils.nullCheck()) return -1;

        int currentSlot = mc.thePlayer.inventory.currentItem;
        int slot = RANDOM.nextInt(9);
        while (slot == currentSlot) {
            slot = RANDOM.nextInt(9);
        }
        return slot;
    }

    public static int getAltSlot(int currentSlot) {
        int target = getC09TargetSlot();
        if (target >= 0) return target;
        return Math.floorMod(currentSlot, 8) + 1;
    }

    public static int findSecondSwordSlot(int excludeSlot) {
        return findBestSwordSlot(excludeSlot);
    }

    private static int findBestSwordSlot(int excludeSlot) {
        if (!Utils.nullCheck()) return -1;

        double bestDamage = 0.0;
        List<Integer> bestSlots = new ArrayList<>();
        for (int slot = 0; slot < 36; slot++) {
            if (slot == excludeSlot) continue;
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemSword)) continue;

            double damage = getAttackBonus(stack);
            if (damage >= bestDamage && damage > 0.0) {
                if (damage > bestDamage) {
                    bestDamage = damage;
                    bestSlots.clear();
                }
                bestSlots.add(slot);
            }
        }

        if (bestSlots.isEmpty()) return -1;
        return bestSlots.get(RANDOM.nextInt(bestSlots.size()));
    }

    private static int findMainSwordSlot() {
        int bestSlot = -1;
        double bestDamage = 0.0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = mc.thePlayer.inventory.getStackInSlot(slot);
            if (stack == null || !(stack.getItem() instanceof ItemSword)) continue;
            if (stack.isItemDamaged() && stack.getMaxDamage() - stack.getItemDamage() < 30) continue;

            double damage = getAttackBonus(stack);
            if (damage > bestDamage) {
                bestDamage = damage;
                bestSlot = slot;
            }
        }
        return bestSlot;
    }

    private static double getAttackBonus(ItemStack stack) {
        if (stack == null) return 0.0;

        double damage = 0.0;
        String attackDamageName = SharedMonsterAttributes.attackDamage.getAttributeUnlocalizedName();
        for (Map.Entry<String, AttributeModifier> entry : stack.getAttributeModifiers().entries()) {
            if (attackDamageName.equals(entry.getKey())) {
                damage += entry.getValue().getAmount();
                break;
            }
        }
        if (stack.isItemEnchanted()) {
            damage += EnchantmentHelper.getEnchantmentLevel(Enchantment.fireAspect.effectId, stack);
            damage += EnchantmentHelper.getEnchantmentLevel(Enchantment.sharpness.effectId, stack) * 1.25;
        }
        return damage;
    }
}
