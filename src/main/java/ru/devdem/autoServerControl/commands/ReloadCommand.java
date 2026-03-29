package ru.devdem.autoServerControl.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import ru.devdem.autoServerControl.AutoServerControl;

public class ReloadCommand implements SimpleCommand {

    private final AutoServerControl plugin;

    public ReloadCommand(AutoServerControl plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(final Invocation invocation) {
        CommandSource source = invocation.source();
        // Get the arguments after the command alias
        String[] args = invocation.arguments();

        source.sendMessage(Component.text("Перезапуск конфигов..", NamedTextColor.YELLOW));
        plugin.reloadConfigs();
        source.sendMessage(Component.text("Конфиги перезапущены!", NamedTextColor.GREEN));

    }

    // This method allows you to control who can execute the command.
    // If the executor does not have the required permission,
    // the execution of the command and the control of its autocompletion
    // will be sent directly to the server on which the sender is located
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission("devdem.reload");
    }
}
