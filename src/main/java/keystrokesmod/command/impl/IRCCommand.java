package keystrokesmod.command.impl;

import keystrokesmod.command.Command;
import keystrokesmod.command.CommandInput;
import keystrokesmod.module.ModuleManager;

public class IRCCommand extends Command {
    public IRCCommand() {
        super("irc");
    }

    @Override
    public void execute(CommandInput input) {
        if (input.argumentCount() == 0) {
            replyWithHeader("&7Usage: &b" + prefixed("irc") + " <message>");
            return;
        }

        if (ModuleManager.irc == null || !ModuleManager.irc.isEnabled()) {
            replyWithHeader("&cIRC module is disabled.");
            return;
        }

        ModuleManager.irc.sendChatMessage(input.joinArguments(0));
    }
}
