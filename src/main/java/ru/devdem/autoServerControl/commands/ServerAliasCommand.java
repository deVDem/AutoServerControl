package ru.devdem.autoServerControl.commands;

import com.velocitypowered.api.command.SimpleCommand;
import ru.devdem.autoServerControl.classes.configuredServer;

public class ServerAliasCommand implements SimpleCommand {

    private configuredServer server;

    public ServerAliasCommand(configuredServer server) {
        this.server = server;
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission("devdem.reload");
    }

    @Override
    public void execute(Invocation invocation) {

    }
}
