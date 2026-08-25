package keystrokesmod.module.impl.other;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import keystrokesmod.module.Module;
import keystrokesmod.module.setting.impl.TextSetting;
import keystrokesmod.utility.Utils;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ChatComponentText;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares the current Minecraft account name with the IRC service and marks
 * other connected IRC users in the in-game player list.
 */
public class IRC extends Module {
    private static final String DEFAULT_SERVER = "ws://irc.fedal.icu:7886";
    private static final int CLIENT_ID = 1;
    private static final int MAX_MESSAGE_LENGTH = 256;
    private static final Map<String, String> CLIENT_STRINGS = new ConcurrentHashMap<String, String>();

    private final TextSetting server;
    private volatile WebSocketClient socket;

    public IRC() {
        super("IRC", category.misc);
        this.registerSetting(server = new TextSetting(
                "WebSocket server",
                DEFAULT_SERVER,
                "ws://host:port",
                255,
                this::reconnect
        ) {
            @Override
            public void loadProfile(JsonObject data) {
                if (data != null && data.has(getProfileKey()) && data.get(getProfileKey()).isJsonPrimitive()) {
                    setText(data.get(getProfileKey()).getAsString());
                }
            }
        });
    }

    @Override
    public void onEnable() {
        connect();
    }

    @Override
    public void onDisable() {
        disconnect();
    }

    private void reconnect() {
        if (!isEnabled()) {
            return;
        }
        disconnect();
        connect();
    }

    private void connect() {
        final URI uri;
        try {
            uri = new URI(server.getText().trim());
            String scheme = uri.getScheme();
            if (!("ws".equalsIgnoreCase(scheme) || "wss".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("WebSocket URL must start with ws:// or wss://");
            }
        }
        catch (Exception exception) {
            Utils.sendMessage("&cIRC: invalid WebSocket server address.");
            return;
        }

        WebSocketClient client = new WebSocketClient(uri) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                if (socket != this || !isEnabled()) {
                    close();
                    return;
                }

                String username = currentUsername();
                JsonObject message = new JsonObject();
                message.addProperty("session", "handshake");
                message.addProperty("user_id", username);
                message.addProperty("client_id", CLIENT_ID);
                send(message.toString());
            }

            @Override
            public void onMessage(String message) {
                if (socket == this && isEnabled()) {
                    handleIncomingMessage(message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                if (socket == this) {
                    socket = null;
                    CLIENT_STRINGS.clear();
                }
            }

            @Override
            public void onError(Exception exception) {
                // Java-WebSocket invokes onClose after terminal connection errors.
            }
        };

        socket = client;
        client.connect();
    }

    public void sendChatMessage(String input) {
        String message = input == null ? "" : input.trim();
        if (message.isEmpty()) {
            Utils.sendMessage("&cUsage: .irc <message>");
            return;
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            Utils.sendMessage("&cIRC: message cannot exceed " + MAX_MESSAGE_LENGTH + " characters.");
            return;
        }

        WebSocketClient current = socket;
        if (current == null || !current.isOpen()) {
            Utils.sendMessage("&cIRC: not connected to the server.");
            return;
        }

        JsonObject packet = new JsonObject();
        packet.addProperty("session", "message");
        packet.addProperty("message", message);
        current.send(packet.toString());
    }

    private void disconnect() {
        WebSocketClient current = socket;
        socket = null;
        CLIENT_STRINGS.clear();
        if (current != null) {
            current.close();
        }
    }

    private static void handleIncomingMessage(String message) {
        try {
            JsonElement element = new JsonParser().parse(message);
            if (element.isJsonObject() && "message".equalsIgnoreCase(stringValue(element.getAsJsonObject(), "session"))) {
                displayChatMessage(element.getAsJsonObject());
                return;
            }
            collectUsers(element);
        }
        catch (Exception ignored) {
            // Ignore non-JSON IRC messages instead of interrupting the socket thread.
        }
    }

    private static void displayChatMessage(JsonObject packet) {
        final String userId = sanitizeChatPart(stringValue(packet, "user_id"), 16);
        final String clientString = sanitizeChatPart(stringValue(packet, "client_string"), 64);
        final String message = sanitizeChatPart(stringValue(packet, "message"), MAX_MESSAGE_LENGTH);
        if (userId.isEmpty() || clientString.isEmpty() || message.isEmpty()) {
            return;
        }

        mc.addScheduledTask(new Runnable() {
            @Override
            public void run() {
                if (mc.thePlayer != null) {
                    String formatted = "&7[&bIRC&7] &f" + userId + " &7(&b" + clientString + "&7)&f: " + message;
                    mc.thePlayer.addChatMessage(new ChatComponentText(Utils.formatColor(formatted)));
                }
            }
        });
    }

    private static void collectUsers(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return;
        }
        if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                collectUsers(child);
            }
            return;
        }
        if (!element.isJsonObject()) {
            return;
        }

        JsonObject object = element.getAsJsonObject();
        JsonElement users = object.get("users");
        if (users != null && users.isJsonArray()) {
            JsonArray array = users.getAsJsonArray();
            for (JsonElement user : array) {
                collectUsers(user);
            }
        }

        String userId = stringValue(object, "user_id");
        String clientString = stringValue(object, "client_string");
        String username = firstNonEmpty(
                stringValue(object, "minecraft_username"),
                stringValue(object, "username"),
                stringValue(object, "name"),
                userId
        );
        if (username.isEmpty() || userId.isEmpty()) {
            return;
        }

        String session = stringValue(object, "session");
        if ("disconnect".equalsIgnoreCase(session) || "leave".equalsIgnoreCase(session)) {
            CLIENT_STRINGS.remove(normalize(username));
        }
        else if (!clientString.isEmpty()) {
            CLIENT_STRINGS.put(normalize(username), sanitizeClientString(clientString));
        }
    }

    public static String getTabName(NetworkPlayerInfo playerInfo, String original) {
        if (playerInfo == null || playerInfo.getGameProfile() == null || original == null) {
            return original;
        }

        String clientString = CLIENT_STRINGS.get(normalize(playerInfo.getGameProfile().getName()));
        if (clientString == null || clientString.isEmpty()) {
            return original;
        }

        String suffix = "\u00a77 (\u00a7b" + clientString + "\u00a77)";
        return original.endsWith(suffix) ? original : original + suffix;
    }

    private static String currentUsername() {
        return mc.getSession() == null || mc.getSession().getUsername() == null
                ? ""
                : mc.getSession().getUsername();
    }

    private static String stringValue(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : "";
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return "";
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static String sanitizeClientString(String clientString) {
        String sanitized = sanitizeChatPart(clientString, 64);
        return sanitized.length() > 64 ? sanitized.substring(0, 64) : sanitized;
    }

    private static String sanitizeChatPart(String value, int maxLength) {
        String sanitized = value.replace("\u00a7", "").replace("&", "").replaceAll("[\\r\\n\\t]", "");
        return sanitized.length() > maxLength ? sanitized.substring(0, maxLength) : sanitized;
    }
}
