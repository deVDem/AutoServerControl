package ru.devdem.autoServerControl.commands;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import ru.devdem.autoServerControl.AutoServerControl;

/**
 * Команда /ascreload для перечитывания конфигов без перезапуска proxy.
 */
public class ReloadCommand implements SimpleCommand {

    /** Главный класс плагина, через который вызывается reloadConfigs(). */
    private final AutoServerControl plugin;

    /**
     * Создает команду перезагрузки конфигов.
     */
    public ReloadCommand(AutoServerControl plugin) {
        this.plugin = plugin;
    }

    /**
     * Перечитывает config.yml, servers.yml, messages.yml и перерегистрирует команды серверов.
     */
    @Override
    public void execute(final Invocation invocation) {
        CommandSource source = invocation.source();

        source.sendMessage(Component.text("Перезапуск конфигов..", NamedTextColor.YELLOW));
        plugin.reloadConfigs();
        source.sendMessage(Component.text("Конфиги перезапущены!", NamedTextColor.GREEN));

    }

    /**
     * Разрешает reload только источникам с правом devdem.reload.
     */
    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission("devdem.reload");
    }
}
