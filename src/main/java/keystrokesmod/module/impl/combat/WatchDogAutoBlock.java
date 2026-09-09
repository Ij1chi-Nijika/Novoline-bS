package keystrokesmod.module.impl.combat;

import io.netty.buffer.Unpooled;
import keystrokesmod.module.impl.other.Disabler;
import keystrokesmod.module.setting.impl.ButtonSetting;
import keystrokesmod.module.setting.impl.GroupSetting;
import keystrokesmod.module.setting.impl.SliderSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.Entity;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.client.C07PacketPlayerDigging;
import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C17PacketCustomPayload;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;

/** Leader-Lite Hypixel AutoBlock state machine adapted to Raven's packet and event APIs. */
final class WatchDogAutoBlock {
    private final Minecraft mc = Minecraft.getMinecraft();
    private final KillAura owner;
    private final SliderSetting watchDogMode, lagMode;
    private final ButtonSetting noStop, test, postStartBlock, alwaysRenderBlocking, c09Instead, fullC09;
    private final SliderSetting moreAttackDelay, maxTick, startBlinkTick, stopBlinkTick, swapTick, switchBackTick, stopBlockTick, attackTick, startBlockTick, startHurtTime, holdBlockTick;
    private int blockTick, testAttackTick, holdTicks;
    private boolean swapped, postBlock, postBlink, postBlinkReset, postSwap;
    private boolean predictBlocking, isBlocking, fakeBlockState;
    private boolean swap, blocked, skipAttack;

    WatchDogAutoBlock(KillAura owner, GroupSetting group) {
        this.owner = owner;
        owner.registerSetting(watchDogMode = new SliderSetting(group, "WatchDogMode", 0,
                new String[]{"OldHypixel", "Without NoSlow", "Custom", "Lag", "Predict"}));
        owner.registerSetting(lagMode = new SliderSetting(group, "LagMode", 1,
                new String[]{"2Tick", "3Tick", "4Tick", "3Tick + 2Tick", "5Tick", "6Tick", "3TickFull", "4TickFull", "Swap", "TestPostSwap"}));
        owner.registerSetting(noStop = new ButtonSetting(group, "NoSwap", true));
        owner.registerSetting(test = new ButtonSetting(group, "MoreAttack", false));
        owner.registerSetting(postStartBlock = new ButtonSetting(group, "PostBlock", false));
        owner.registerSetting(alwaysRenderBlocking = new ButtonSetting(group, "AlwaysRenderBlocking", true));
        owner.registerSetting(c09Instead = new ButtonSetting(group, "C09Instead", true));
        owner.registerSetting(fullC09 = new ButtonSetting(group, "FullC09(Will Cause Damage Less)", false));
        owner.registerSetting(moreAttackDelay = new SliderSetting(group, "MoreAttackDelay", 1, 0, 3, 1));
        owner.registerSetting(maxTick = new SliderSetting(group, "MaxTick", 3, 1, 5, 1));
        // Leader initializes some ticks to 0 even though their slider minimum is 1.
        owner.registerSetting(startBlinkTick = new SliderSetting(group, "StartBlinkTick", 0, 1, 5, 1));
        owner.registerSetting(stopBlinkTick = new SliderSetting(group, "StopBlinkTick", 2, 1, 5, 1));
        owner.registerSetting(swapTick = new SliderSetting(group, "SwapTick", 2, 1, 5, 1));
        owner.registerSetting(switchBackTick = new SliderSetting(group, "SwitchBackTick", 2, 1, 5, 1));
        owner.registerSetting(stopBlockTick = new SliderSetting(group, "StopBlockTick", 2, 1, 5, 1));
        owner.registerSetting(attackTick = new SliderSetting(group, "AttackTick", 0, 1, 5, 1));
        owner.registerSetting(startBlockTick = new SliderSetting(group, "StartBlockTick", 0, 1, 5, 1));
        owner.registerSetting(startHurtTime = new SliderSetting(group, "StartHurtTime", 6, 1, 10, 1));
        owner.registerSetting(holdBlockTick = new SliderSetting(group, "HoldBlockTick", 2, 0, 6, 1));
    }

    void updateVisibility(boolean visible) {
        int mode = (int) watchDogMode.getInput();
        int lag = (int) lagMode.getInput();
        watchDogMode.setVisible(visible, owner);
        lagMode.setVisible(visible && mode == 3, owner);
        noStop.setVisible(visible && mode == 0, owner);
        test.setVisible(visible && mode == 0, owner);
        moreAttackDelay.setVisible(visible && mode == 0 && test.isToggled(), owner);
        for (SliderSetting setting : new SliderSetting[]{maxTick, startBlinkTick, stopBlinkTick,
                swapTick, switchBackTick, stopBlockTick, attackTick, startBlockTick}) {
            setting.setVisible(visible && mode == 2, owner);
        }
        postStartBlock.setVisible(visible && mode == 2, owner);
        startHurtTime.setVisible(visible && mode == 4, owner);
        holdBlockTick.setVisible(visible && mode == 4, owner);
        alwaysRenderBlocking.setVisible(visible && mode == 3, owner);
        c09Instead.setVisible(visible && mode == 3 && lag == 1, owner);
        fullC09.setVisible(visible && mode == 3 && lag >= 2 && lag <= 8, owner);
    }

    void pre() {
        boolean attack = true;
        swap = blocked = skipAttack = false;
        if (predictBlocking) holdTicks++;
        switch ((int) watchDogMode.getInput()) {
            case 0: // OldHypixel
                if (owner.watchDogHasTarget()) {
                    if (!owner.watchDogPlayerBusy()) {
                        switch (this.blockTick) {
                            case 0:
                                if (!this.isPlayerBlocking()) {
                                    swap = true;
                                }
                                blocked = true;
                                this.blockTick = 1;
                                break;
                            case 1:
                                attack = false;
                                this.blockTick = 2;
                                break;
                            case 2:
                                if (this.isPlayerBlocking()) {
                                    if (!noStop.isToggled()) {
                                        int handle = mc.thePlayer.inventory.currentItem;
                                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                    }
                                    this.stopBlock();
                                }
                                if (test.isToggled()){
                                    if (testAttackTick >= moreAttackDelay.getInput()){
                                        testAttackTick = 0;
                                    }
                                    else {
                                        testAttackTick++;
                                        attack = false;
                                    }
                                }
                                else {
                                    attack = false;
                                }
                                this.blockTick = 0;
                                break;
                            default:
                                this.blockTick = 0;
                                break;
                        }
                    }
                    this.isBlocking = true;
                    this.fakeBlockState = true;
                } else {
                    owner.releaseAutoBlockBlink();
                    this.isBlocking = false;
                    this.fakeBlockState = false;
                    mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getSwapSlot()));
                    mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                    Velocity.extraAttacked = false;
                }
                break;
            case 1: // Without NoSlow
                if (owner.watchDogHasTarget()) {
                    if (!owner.watchDogPlayerBusy()) {
                        switch (this.blockTick) {
                            case 0:
                                owner.releaseAutoBlockBlink();
                                if (!this.isPlayerBlocking()) {
                                    swap = true;
                                }
                                this.blockTick = 1;
                                break;
                            case 1:
                                attack = false;
                                blockTick = 2;
                                break;
                            case 2:
                                owner.startAutoBlockBlink();
                                if (this.isPlayerBlocking()) {
                                    this.stopBlock();
                                }
                                if (test.isToggled()){
                                    if (testAttackTick >= moreAttackDelay.getInput()){
                                        testAttackTick = 0;
                                    }
                                    else {
                                        testAttackTick++;
                                        attack = false;
                                    }
                                }
                                this.blockTick = 0;
                                break;
                            default:
                                this.blockTick = 0;
                        }
                    }
                    this.isBlocking = true;
                    this.fakeBlockState = true;
                } else {
                    owner.releaseAutoBlockBlink();
                    this.isBlocking = false;
                    this.fakeBlockState = false;
                    Velocity.extraAttacked = false;
                }
                break;
            case 2: // Custom
                if (owner.watchDogHasTarget()) {
                    if (!owner.watchDogPlayerBusy()) {
                        if (blockTick + 1 == startBlinkTick.getInput()){
                            blocked = true;
                        }
                        if (blockTick + 1 != attackTick.getInput()){
                            attack = false;
                        }
                        if (blockTick + 1 == startBlockTick.getInput()){
                            if (!this.isPlayerBlocking()) {
                                swap = true;
                                if (postStartBlock.isToggled())postBlock = true;
                            }
                        }
                        if (blockTick + 1 == stopBlinkTick.getInput()){
                            owner.releaseAutoBlockBlink();
                        }
                        if (blockTick + 1 == swapTick.getInput()){
                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getSwapSlot()));
                            swapped = true;
                        }
                        if (blockTick + 1 == switchBackTick.getInput()){
                            if (swapped){
                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                                swapped = false;
                            }
                        }
                       if (blockTick + 1 == stopBlockTick.getInput()){
                           if (this.isPlayerBlocking()) {
                               this.stopBlock();
                           }
                       }
                        blockTick++;
                        // Preserve Leader's MaxTick - 1 cycle boundary.
                        if (blockTick >= maxTick.getInput() - 1){
                            blockTick = 0;
                        }
                    }
                    this.isBlocking = true;
                    this.fakeBlockState = true;
                } else {
                    if (swapped){
                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
                        swapped = false;
                    }
                    owner.releaseAutoBlockBlink();
                    this.isBlocking = false;
                    this.fakeBlockState = false;
                    Velocity.extraAttacked = false;
                }
                break;
            case 3: // Lag
                switch ((int) lagMode.getInput()) {
                    case 0: // 2Tick
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        owner.releaseAutoBlockBlink();
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        owner.startAutoBlockBlink();
                                        if (this.isPlayerBlocking()) {
                                           this.stopBlock();
                                        }
                                        attack = false;
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 1: // 3Tick
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        blocked = true;
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        if (this.isPlayerBlocking()) {
                                            if (c09Instead.isToggled()){
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            }
                                            else this.stopBlock();
                                        }
                                        attack = false;
                                        blockTick = 2;
                                        break;
                                    case 2:
                                        owner.releaseAutoBlockBlink();
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 2: // 4Tick
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        blocked = true;
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        if (this.isPlayerBlocking()) {
                                            if (fullC09.isToggled()) {
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                                this.stopBlock();
                                            }
                                        }
                                        attack = false;
                                        blockTick = 2;
                                        break;
                                    case 2:
                                        int handle = mc.thePlayer.inventory.currentItem;
                                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                        this.stopBlock();
                                        blockTick = 3;
                                        break;
                                    case 3:
                                        owner.releaseAutoBlockBlink();
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 3: // 3Tick + 2Tick
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        blocked = true;
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        if (this.isPlayerBlocking()) {
                                            if (fullC09.isToggled()) {
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            }
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        blockTick = 2;
                                        break;
                                    case 2:
                                        blocked = true;
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        blockTick = 3;
                                        break;
                                    case 3:
                                        if (this.isPlayerBlocking()) {
                                            if (fullC09.isToggled()) {
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            }
                                            this.stopBlock();
                                        }
                                        blockTick = 4;
                                        break;
                                    case 4:
                                        owner.releaseAutoBlockBlink();
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 4: // 5Tick
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        owner.startAutoBlockBlink();
                                        if (this.isPlayerBlocking()) {
                                            if (fullC09.isToggled()) {
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            }
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        blockTick = 2;
                                        break;
                                    case 2:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        blockTick = 3;
                                        break;
                                    case 3:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        blockTick = 4;
                                        break;
                                    case 4:
                                        owner.releaseAutoBlockBlink();
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 5: // 6Tick
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        owner.startAutoBlockBlink();
                                        if (this.isPlayerBlocking()) {
                                         if (fullC09.isToggled()) {
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                               mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                           }
                                         this.stopBlock();
                                        }
                                       attack = false;
                                       this.blockTick = 2;
                                       break;
                                    case 2:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        blockTick = 3;
                                        break;
                                    case 3:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        blockTick = 4;
                                        break;
                                    case 4:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        blockTick = 5;
                                        break;
                                    case 5:
                                        owner.releaseAutoBlockBlink();
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 6: // 3TickFull
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        postBlink = true;
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        owner.startAutoBlockBlink();
                                        if (this.isPlayerBlocking()) {
                                            if (fullC09.isToggled()) {
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            }
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        this.blockTick = 2;
                                        break;
                                    case 2:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 7: // 4TickFull
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        postBlink = true;
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        owner.startAutoBlockBlink();
                                        if (this.isPlayerBlocking()) {
                                            if (fullC09.isToggled()) {
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            }
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        this.blockTick = 2;
                                        break;
                                    case 2:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        this.blockTick = 3;
                                        break;
                                    case 3:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 8: // Swap
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        owner.startAutoBlockBlink();
                                        if (this.isPlayerBlocking()) {
                                            if (fullC09.isToggled()) {
                                                int handle = mc.thePlayer.inventory.currentItem;
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                                mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            }
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        this.blockTick = 2;
                                        break;
                                    case 2:
                                        if (fullC09.isToggled()) {
                                            int handle = mc.thePlayer.inventory.currentItem;
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                            this.stopBlock();
                                        }
                                        attack = false;
                                        this.blockTick = 3;
                                        break;
                                    case 3:
                                        postBlink = true;
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    case 9: // TestPostSwap
                        if (owner.watchDogHasTarget()) {
                            if (!owner.watchDogPlayerBusy()) {
                                switch (this.blockTick) {
                                    case 0:
                                        if (!this.isPlayerBlocking()) {
                                            swap = true;
                                        }
                                        postBlinkReset = true;
                                        this.blockTick = 1;
                                        break;
                                    case 1:
                                        owner.startAutoBlockBlink();
                                        if (this.isPlayerBlocking()) {
                                            postSwap = true;
                                        }
                                        attack = false;
                                        this.blockTick = 2;
                                        break;
                                    case 2:
                                        int handle = mc.thePlayer.inventory.currentItem;
                                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getAltSlot(handle)));
                                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle % 7 + 2));
                                        mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(handle));
                                        attack = false;
                                        if (owner.watchDogAttackDueSoon()) {
                                            this.blockTick = 0;
                                        }
                                        break;
                                    default:
                                        this.blockTick = 0;
                                }
                            }
                            this.isBlocking = true;
                            this.fakeBlockState = alwaysRenderBlocking.isToggled();
                        } else {
                            owner.releaseAutoBlockBlink();
                            this.isBlocking = false;
                            this.fakeBlockState = false;
                            Velocity.extraAttacked = false;
                        }
                        break;
                    default:
                        break;
                }
                break;
            case 4: // Predict
                if (owner.watchDogHasTarget()) {
                    if (!owner.watchDogPlayerBusy()) {
                        int hurt = mc.thePlayer.hurtTime;
                        if (hurt != 0 && hurt <= startHurtTime.getInput()) {
                            if (!predictBlocking) {
                                if (!this.isPlayerBlocking()) {
                                    swap = true;
                                }
                                this.predictBlocking = true;
                                holdTicks = 0;
                            }
                        }
                        if(holdTicks >= holdBlockTick.getInput() && this.predictBlocking) {
                            if (this.isPlayerBlocking()) {
                                this.stopBlock();
                            }
                            predictBlocking = false;
                            holdTicks = 0;
                        }
                    }
                    this.isBlocking = this.predictBlocking;
                    this.fakeBlockState = this.predictBlocking;
                } else {
                    if (this.predictBlocking) {
                        if (this.isPlayerBlocking()) {
                            this.stopBlock();
                        }
                        holdTicks = 0;
                        this.predictBlocking = false;
                    }
                    owner.releaseAutoBlockBlink();
                    this.isBlocking = false;
                    this.fakeBlockState = false;
                    Velocity.extraAttacked = false;
                }
                break;
            default:
                break;
        }
        skipAttack = !attack;
    }

    boolean skipAttack() {
        return skipAttack || isPlayerBlocking() || owner.watchDogPlayerBusy();
    }

    void onExtraAttack(int reduceMode) {
        int mode = (int) watchDogMode.getInput();
        if (reduceMode == 2) {
            if (mode == 2) blockTick = (int) attackTick.getInput();
            else if (mode <= 1 || mode == 3) blockTick = 0;
        } else if (reduceMode == 1) {
            if (mode <= 1) blockTick = 2;
            else if (mode == 2) blockTick = (int) attackTick.getInput();
            else if (mode == 3) {
                int[] ticks = {1, 2, 3, 4, 4, 5, 4, 4, 3, 2};
                blockTick = ticks[(int) lagMode.getInput()];
            }
        }
    }
    boolean visualBlocking() { return fakeBlockState; }
    boolean active() { return isBlocking; }
    int blockTick() { return blockTick; }

    void finish(Entity attackedTarget) {
        if (swap) {
            if (attackedTarget != null) owner.sendWatchDogInteraction(attackedTarget);
            else if (!postBlock) owner.startAutoBlock(mc.thePlayer.getHeldItem());
        }
        swap = false;
        if (blocked) {
            owner.releaseAutoBlockBlink();
            owner.startAutoBlockBlink();
            blocked = false;
        }
    }

    void post() {
        if (postBlinkReset) {
            owner.releaseAutoBlockBlink();
            owner.startAutoBlockBlink();
            postBlinkReset = false;
        }
        if (postBlink) {
            owner.releaseAutoBlockBlink();
            postBlink = false;
        }
        if (postSwap) {
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(Disabler.getSwapSlot()));
            mc.thePlayer.sendQueue.addToSendQueue(new C17PacketCustomPayload("send", new PacketBuffer(Unpooled.buffer())));
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
            stopBlock();
            postSwap = false;
        }
        if (postBlock) {
            owner.startAutoBlock(mc.thePlayer.getHeldItem());
            postBlock = false;
        }
    }

    private boolean isPlayerBlocking() {
        return mc.thePlayer.isUsingItem() || owner.watchDogServerBlocking();
    }

    private void stopBlock() {
        mc.thePlayer.sendQueue.addToSendQueue(new C07PacketPlayerDigging(
                C07PacketPlayerDigging.Action.RELEASE_USE_ITEM, BlockPos.ORIGIN, EnumFacing.DOWN));
        mc.thePlayer.stopUsingItem();
    }

    int mode() { return (int) watchDogMode.getInput(); }

    void noBlock() {
        owner.releaseAutoBlockBlink();
        if (mode() == 0 && isBlocking && owner.watchDogNoSlowEnabled()) {
            isBlocking = false;
            stopBlock();
        }
        if (predictBlocking) {
            if (isPlayerBlocking()) stopBlock();
            predictBlocking = false;
            holdTicks = 0;
        }
        if (swapped) {
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
            swapped = false;
        } else isBlocking = false;
        fakeBlockState = false;
        blockTick = 0;
        swap = blocked = skipAttack = false;
    }

    void enable() {
        blockTick = 0;
        predictBlocking = false;
    }

    void reset() {
        owner.releaseAutoBlockBlink();
        Velocity.extraAttacked = false;
        fakeBlockState = false;
        predictBlocking = false;
        if (swapped && Utils.nullCheck()) {
            mc.thePlayer.sendQueue.addToSendQueue(new C09PacketHeldItemChange(mc.thePlayer.inventory.currentItem));
            swapped = false;
        }
        if (mode() == 0 && isBlocking && owner.watchDogNoSlowEnabled() && Utils.nullCheck()) {
            isBlocking = false;
            stopBlock();
        } else isBlocking = false;
    }
}
