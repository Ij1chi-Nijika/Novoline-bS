package keystrokesmod.helper;

import keystrokesmod.utility.Utils;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

public class PingHelper {
    private static boolean waitingForResponse = false;
    private static long sendTime = 0L;

    @SubscribeEvent
    public void onChatMessageRecieved(ClientChatReceivedEvent event) {
        if (waitingForResponse && Utils.nullCheck()) {
            if (Utils.stripColor(event.message.getUnformattedText()).startsWith("Unknown")) {
                event.setCanceled(true);
                this.getPing();
            }
        }
    }

    public static void checkPing() {
        Utils.sendMessage("&7Checking ping...");

        if (waitingForResponse) {
            Utils.sendMessage("&7Please wait before checking again.");
            return;
        }

        Utils.mc.thePlayer.sendChatMessage("/...");
        waitingForResponse = true;
        sendTime = System.currentTimeMillis();
    }

    private void getPing() {
        int ping = (int) (System.currentTimeMillis() - sendTime) - 20;
        if (ping < 0) {
            ping = 0;
        }

        Utils.sendMessage("&7Your ping: &b" + ping + "&7ms.");
        reset();
    }

    public static void reset() {
        waitingForResponse = false;
        sendTime = 0L;
    }
}
