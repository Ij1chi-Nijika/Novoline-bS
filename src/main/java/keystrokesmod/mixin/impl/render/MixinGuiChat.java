package keystrokesmod.mixin.impl.render;

import keystrokesmod.Raven;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@SideOnly(Side.CLIENT)
@Mixin(GuiChat.class)
public abstract class MixinGuiChat extends MixinGuiScreen {
    @Shadow
    protected GuiTextField inputField;

    @Shadow
    private boolean waitingOnAutocomplete;

    @Shadow
    public abstract void onAutocompleteResponse(String[] suggestions);

    @Inject(method = "keyTyped", at = @At("RETURN"))
    private void updateCommandCompletion(char typedChar, int keyCode, CallbackInfo callbackInfo) {
        if (Raven.commandManager == null) {
            return;
        }

        String input = inputField.getText();
        if (Raven.commandManager.isCommand(input)) {
            inputField.setMaxStringLength(256);
            Raven.commandManager.autoComplete(input);
        } else {
            inputField.setMaxStringLength(100);
            Raven.commandManager.clearAutoComplete();
        }
    }

    @Inject(method = "sendAutocompleteRequest", at = @At("HEAD"), cancellable = true)
    private void handleClientCommandCompletion(String full, String ignored, CallbackInfo callbackInfo) {
        if (Raven.commandManager == null || !Raven.commandManager.autoComplete(full)) {
            return;
        }

        waitingOnAutocomplete = true;
        String[] completions = Raven.commandManager.getLatestAutoComplete();
        if (!full.toLowerCase().endsWith(completions[completions.length - 1].toLowerCase())) {
            onAutocompleteResponse(completions);
            callbackInfo.cancel();
        }
    }

    @Inject(
        method = "onAutocompleteResponse",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiChat;autocompletePlayerNames()V",
            shift = At.Shift.BEFORE
        ),
        cancellable = true
    )
    private void stopServerCompletionLoop(String[] response, CallbackInfo callbackInfo) {
        if (Raven.commandManager != null && Raven.commandManager.getLatestAutoComplete().length != 0) {
            callbackInfo.cancel();
        }
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    private void drawCommandCompletion(int mouseX, int mouseY, float partialTicks, CallbackInfo callbackInfo) {
        if (Raven.commandManager == null || !Raven.commandManager.isCommand(inputField.getText())) {
            return;
        }

        String[] completions = Raven.commandManager.getLatestAutoComplete();
        if (completions.length == 0) {
            return;
        }

        String input = inputField.getText();
        int lastSpace = input.lastIndexOf(' ');
        String currentToken = input.substring(lastSpace + 1);
        String completion = completions[0];
        if (currentToken.length() > completion.length()
            || !completion.regionMatches(true, 0, currentToken, 0, currentToken.length())) {
            return;
        }

        String suffix = completion.substring(currentToken.length());
        if (!suffix.isEmpty()) {
            int x = inputField.xPosition + mc.fontRendererObj.getStringWidth(input);
            mc.fontRendererObj.drawStringWithShadow(suffix, x, inputField.yPosition, 0xFFA5A5A5);
        }
    }
}
