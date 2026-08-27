package keystrokesmod.command;

import keystrokesmod.command.impl.Bind;
import keystrokesmod.command.impl.Binds;
import keystrokesmod.command.impl.Cname;
import keystrokesmod.command.impl.Debug;
import keystrokesmod.command.impl.Enemy;
import keystrokesmod.command.impl.Friend;
import keystrokesmod.command.impl.Help;
import keystrokesmod.command.impl.HideAll;
import keystrokesmod.command.impl.IRCCommand;
import keystrokesmod.command.impl.Name;
import keystrokesmod.command.impl.Ping;
import keystrokesmod.command.impl.Prefix;
import keystrokesmod.command.impl.Profiles;
import keystrokesmod.command.impl.ShowAll;
import keystrokesmod.command.impl.Toggle;
import keystrokesmod.command.impl.Track;
import keystrokesmod.command.impl.Unbind;
import keystrokesmod.utility.Utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Client chat-command registry and dispatcher, based on Suzuran's command flow. */
public class CommandManager {
    public static final String DEFAULT_PREFIX = ".";

    private final List<Command> commands = new ArrayList<>();
    private String[] latestAutoComplete = new String[0];
    private String prefix = DEFAULT_PREFIX;

    public final Track trackCommand;

    public CommandManager() {
        registerCommand(new Ping());
        registerCommand(new Name());
        registerCommand(new Toggle());
        registerCommand(new Bind());
        registerCommand(new Unbind());
        registerCommand(new Binds());
        registerCommand(new Cname());
        registerCommand(new Debug());
        registerCommand(new Friend());
        registerCommand(new Enemy());
        registerCommand(new Prefix());
        registerCommand(trackCommand = new Track());
        registerCommand(new Profiles());
        registerCommand(new ShowAll());
        registerCommand(new HideAll());
        registerCommand(new IRCCommand());
        registerCommand(new Help());
    }

    public boolean handleChatMessage(String message) {
        if (!isCommand(message)) {
            return false;
        }
        executeCommands(message);
        return true;
    }

    public void executeCommands(String input) {
        if (!isCommand(input)) {
            return;
        }

        String rawInput = input.substring(getPrefix().length());
        String[] args = splitArguments(rawInput, false);
        if (args.length == 0 || args[0].isEmpty()) {
            sendUnknownCommand();
            return;
        }

        Command command = getCommand(args[0]);
        if (command == null) {
            sendUnknownCommand();
            return;
        }

        String[] commandArgs = args.length == 1 ? new String[0] : Arrays.copyOfRange(args, 1, args.length);
        command.execute(new CommandInput(input, args[0], commandArgs));
    }

    public boolean autoComplete(String input) {
        latestAutoComplete = getCompletions(input);
        return isCommand(input) && latestAutoComplete.length > 0;
    }

    private String[] getCompletions(String input) {
        if (!isCommand(input)) {
            return new String[0];
        }

        String rawInput = input.substring(getPrefix().length());
        String[] args = splitArguments(rawInput, true);
        if (args.length > 1) {
            Command command = getCommand(args[0]);
            if (command == null) {
                return new String[0];
            }

            String[] commandArgs = Arrays.copyOfRange(args, 1, args.length);
            List<String> completions = command.suggest(new CommandInput(input, args[0], commandArgs));
            return completions == null || completions.isEmpty()
                ? new String[0]
                : completions.toArray(new String[0]);
        }

        String query = args.length == 0 ? "" : args[0];
        List<String> completions = new ArrayList<>();
        for (Command command : commands) {
            String completion = matchingLabel(command, query);
            if (completion != null) {
                completions.add(getPrefix() + completion);
            }
        }
        return completions.toArray(new String[0]);
    }

    private String matchingLabel(Command command, String query) {
        if (startsWithIgnoreCase(command.getName(), query)) {
            return command.getName();
        }
        for (String alias : command.getAliases()) {
            if (startsWithIgnoreCase(alias, query)) {
                return alias;
            }
        }
        return null;
    }

    private static boolean startsWithIgnoreCase(String value, String query) {
        return query.length() <= value.length() && value.regionMatches(true, 0, query, 0, query.length());
    }

    public Command getCommand(String name) {
        for (Command command : commands) {
            if (command.matches(name)) {
                return command;
            }
        }
        return null;
    }

    public void registerCommand(Command command) {
        if (command != null && getCommand(command.getName()) == null) {
            commands.add(command);
        }
    }

    public boolean unregisterCommand(Command command) {
        return commands.remove(command);
    }

    public List<Command> getCommands() {
        return Collections.unmodifiableList(commands);
    }

    public String[] getLatestAutoComplete() {
        return latestAutoComplete.clone();
    }

    public void clearAutoComplete() {
        latestAutoComplete = new String[0];
    }

    public boolean isCommand(String message) {
        return message != null && message.startsWith(prefix);
    }

    public String getPrefix() {
        return prefix;
    }

    public void setPrefix(String prefix) {
        if (isValidPrefix(prefix)) {
            this.prefix = prefix;
            clearAutoComplete();
        }
    }

    public static boolean isValidPrefix(String prefix) {
        return prefix != null && prefix.length() == 1 && !Character.isWhitespace(prefix.charAt(0));
    }

    public String formatOutput(String message) {
        return message;
    }

    private void sendUnknownCommand() {
        Utils.sendMessage(formatOutput("&cCommand not found. Type " + getPrefix() + "help to view all commands."));
    }

    private static String[] splitArguments(String input, boolean preserveTrailingArgument) {
        if (input == null || input.isEmpty()) {
            return new String[] {""};
        }
        String normalized = trimLeadingWhitespace(input);
        if (normalized.isEmpty()) {
            return new String[] {""};
        }
        return normalized.split(" +", preserveTrailingArgument ? -1 : 0);
    }

    private static String trimLeadingWhitespace(String input) {
        int index = 0;
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
            index++;
        }
        return input.substring(index);
    }
}
